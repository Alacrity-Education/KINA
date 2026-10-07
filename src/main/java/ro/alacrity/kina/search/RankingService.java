package ro.alacrity.kina.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.Availability;
import ro.alacrity.kina.domain.BelowSpecPart;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PartKey;
import ro.alacrity.kina.domain.PriceBreak;
import ro.alacrity.kina.domain.RankingMode;
import ro.alacrity.kina.search.ce.CrossEncoderPartRanker;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Ranks fetched parts for one query (DESIGN.md section 3.3): deterministic scores for every part; for a bounded
 * candidate set (the deterministic top {@code max-candidates}, shared proportionally between distributors) raw
 * cross-encoder scores; both rank-normalised within the candidate set and blended (so {@code score} orders the list but
 * is relative: the last of several exact matches can be 0; {@link RankedPart#match()} is the absolute grade),
 * {@code final = (1 - w) * ranknorm(det) + w * ranknorm(ce)} with {@code w = kina.ranking.cross-encoder.weight}.
 * Never throws; whenever the cross-encoder cannot score (disabled, not loaded, busy, timeout, failure) the
 * deterministic order is returned with {@link RankingMode#FALLBACK} and a note.
 */
@Service
@Slf4j
public class RankingService {

    /** Minimum number of candidates per distributor that has results. */
    static final int MIN_CANDIDATES_PER_DISTRIBUTOR = 5;
    /** Below this remaining budget the cross-encoder is not called. */
    static final Duration MIN_CALL_BUDGET = Duration.ofMillis(20);

    /**
     * A part with its final score in [0,1], its deterministic match grade ({@link DeterministicRanker.Assessment}, null
     * when unknown or when the query was not understood), its mismatches and unverified constraints, and whether a
     * known rating is below the request (only returned with {@link RankOptions#allowBelowSpec()}).
     */
    public record RankedPart(Part part, double score, Double match, List<String> mismatches, List<String> unverified,
                             boolean belowSpec) {

        public RankedPart {
            mismatches = mismatches == null ? List.of() : List.copyOf(mismatches);
            unverified = unverified == null ? List.of() : List.copyOf(unverified);
        }

        public RankedPart(Part part, double score, Double match, List<String> mismatches) {
            this(part, score, match, mismatches, List.of(), false);
        }

        public RankedPart(Part part, double score, Double match) {
            this(part, score, match, List.of());
        }

        public RankedPart(Part part, double score) {
            this(part, score, null);
        }

        /** Every stated constraint verified and met ({@code match} 1.0, nothing unverified, not below spec). */
        public boolean exact() {
            return match != null && match >= 0.995 && unverified.isEmpty() && !belowSpec;
        }

        RankedPart withScore(double newScore) {
            return new RankedPart(part, newScore, match, mismatches, unverified, belowSpec);
        }
    }

    /**
     * How one query is ranked: the order quantity (stock shortfall, MOQ and low-stock penalties) and whether parts whose
     * known rating is below the request are returned (flagged, after every part that meets the request, closest first)
     * instead of excluded.
     */
    public record RankOptions(int quantity, boolean allowBelowSpec) {

        public static final RankOptions DEFAULT = new RankOptions(1, false);

        public RankOptions {
            quantity = Math.max(1, quantity);
        }
    }

    /** How a part relates to the request before ranking ({@link #verdict}). */
    public enum Verdict {
        /** Meets the request; every requested rating is stated and met. */
        MEETS,
        /** Meets the request as far as it is known, but a requested rating is not stated (unverified). */
        UNVERIFIED_RATING,
        /**
         * Left out: a known attribute contradicts a hard constraint ({@link ConstraintPolicy}: the primary value, the
         * package, the type...).
         */
        CONSTRAINT,
        /** Left out (unless {@code allow_below_spec}): a known rating is below the request. */
        BELOW_SPEC
    }

    /** Why a part is left out before ranking ({@link #exclusion}). */
    public enum Exclusion {
        /** A known attribute contradicts a hard constraint ({@link ConstraintPolicy}). */
        CONSTRAINT,
        /** A known rating is below the request (or a DCR above its maximum). */
        BELOW_SPEC
    }

    /**
     * Ranked parts per distributor (best first, same distributors as the input), the ranking mode, an optional note
     * explaining a fallback (null when the blend succeeded), the parts left out per distributor and, per distributor,
     * the hard constraint each excluded part was counted under (its first conflict, {@link ConstraintPolicy.Result}).
     */
    public record RankedResults(Map<Distributor, List<RankedPart>> byDistributor, RankingMode mode, String note,
                                Map<Distributor, Integer> excluded, Map<Distributor, Integer> excludedBelowSpec,
                                Map<Distributor, Map<String, Integer>> excludedDetail,
                                Map<Distributor, List<BelowSpecPart>> belowSpecDetail,
                                Map<Distributor, List<ExcludedRequest>> excludedRequested) {

        public RankedResults {
            excluded = excluded == null ? Map.of() : Map.copyOf(excluded);
            excludedBelowSpec = excludedBelowSpec == null ? Map.of() : Map.copyOf(excludedBelowSpec);
            excludedDetail = excludedDetail == null ? Map.of() : Map.copyOf(excludedDetail);
            belowSpecDetail = belowSpecDetail == null ? Map.of() : Map.copyOf(belowSpecDetail);
            excludedRequested = excludedRequested == null ? Map.of() : Map.copyOf(excludedRequested);
        }

        public RankedResults(Map<Distributor, List<RankedPart>> byDistributor, RankingMode mode, String note,
                             Map<Distributor, Integer> excluded, Map<Distributor, Integer> excludedBelowSpec,
                             Map<Distributor, Map<String, Integer>> excludedDetail) {
            this(byDistributor, mode, note, excluded, excludedBelowSpec, excludedDetail, null, null);
        }

        public RankedResults(Map<Distributor, List<RankedPart>> byDistributor, RankingMode mode, String note,
                             Map<Distributor, Integer> excluded, Map<Distributor, Integer> excludedBelowSpec) {
            this(byDistributor, mode, note, excluded, excludedBelowSpec, null);
        }

        public RankedResults(Map<Distributor, List<RankedPart>> byDistributor, RankingMode mode, String note,
                             Map<Distributor, Integer> excluded) {
            this(byDistributor, mode, note, excluded, null, null);
        }

        public RankedResults(Map<Distributor, List<RankedPart>> byDistributor, RankingMode mode, String note) {
            this(byDistributor, mode, note, null, null, null);
        }

        /** Parts of {@code distributor} excluded per hard constraint ({@code excluded_by_constraints_detail}). */
        public Map<String, Integer> excludedDetailBy(Distributor distributor) {
            return excludedDetail.getOrDefault(distributor, Map.of());
        }

        /** Parts of {@code distributor} removed because a known attribute contradicts a strict constraint. */
        public int excludedBy(Distributor distributor) {
            return excluded.getOrDefault(distributor, 0);
        }

        /** Parts of {@code distributor} removed because a known rating is below the request. */
        public int excludedBelowSpecBy(Distributor distributor) {
            return excludedBelowSpec.getOrDefault(distributor, 0);
        }

        /**
         * Up to {@value #MAX_BELOW_SPEC_DETAIL} parts of {@code distributor} removed for a rating below the request,
         * closest to the request first ({@code excluded_below_spec_detail}).
         */
        public List<BelowSpecPart> belowSpecDetailBy(Distributor distributor) {
            return belowSpecDetail.getOrDefault(distributor, List.of());
        }

        /** Parts of {@code distributor} that the query names by part number but that were left out, with why. */
        public List<ExcludedRequest> excludedRequestedBy(Distributor distributor) {
            return excludedRequested.getOrDefault(distributor, List.of());
        }

        RankedResults withExcluded(Map<Distributor, Integer> counts, Map<Distributor, Integer> belowSpec,
                                   Map<Distributor, Map<String, Integer>> detail,
                                   Map<Distributor, List<BelowSpecPart>> belowSpecParts,
                                   Map<Distributor, List<ExcludedRequest>> requested) {
            return new RankedResults(byDistributor, mode, note, counts, belowSpec, detail, belowSpecParts, requested);
        }

        /** The same results with other ranked lists (stock refresh, annotation); every count is kept. */
        RankedResults withParts(Map<Distributor, List<RankedPart>> parts) {
            return new RankedResults(parts, mode, note, excluded, excludedBelowSpec, excludedDetail, belowSpecDetail,
                    excludedRequested);
        }
    }

    /** Entries of {@code excluded_below_spec_detail} per distributor. */
    static final int MAX_BELOW_SPEC_DETAIL = 5;

    /**
     * A part the query names by part number ({@link PartNumbers}) that was left out before ranking.
     *
     * @param partNumber the query's part number that names it, as sent
     * @param part       the part
     * @param reason     why, in plain words: the hard constraint it contradicts ({@code type}) or the failed rating
     *                   ({@code voltage 80V below 100V})
     */
    public record ExcludedRequest(String partNumber, Part part, String reason) {
    }

    /**
     * Ranking configuration and cross-encoder health, for {@code list_distributors}.
     *
     * @param crossEncoderEnabled {@code kina.ranking.cross-encoder.enabled}
     * @param modelVariant        variant in use ({@code int8}/{@code fp32}), the configured one until loaded
     * @param modelDir            model directory
     * @param modelRevision       source revision from {@code model.json} (Hugging Face commit), null when unknown
     * @param ready               the model is loaded and warmed up
     * @param maxCandidates       candidates scored per query
     * @param weight              cross-encoder weight in the rank blend
     * @param lastError           why the model is not loaded (null when fine)
     * @param threads             ONNX Runtime intra-op threads
     * @param avgLatencyMs        mean cross-encoder time per scored query since start, null before the first one
     */
    public record RankingStatus(boolean crossEncoderEnabled, String modelVariant, String modelDir,
                                String modelRevision, boolean ready, int maxCandidates, double weight,
                                String lastError, int threads, Double avgLatencyMs) {
    }

    private final KinaProperties.Ranking config;
    private final KinaProperties.Search search;
    private final DeterministicRanker deterministic;
    private final PartRanker ranker;
    private final ConstraintPolicy policy;
    private final Supplier<CrossEncoderPartRanker.Status> modelStatus;
    private final RankingScoreCache cache;

    @Autowired
    public RankingService(KinaProperties properties, DeterministicRanker deterministic,
                          CrossEncoderPartRanker crossEncoder, RankingScoreCache cache) {
        this(properties, deterministic, crossEncoder, crossEncoder::status, cache);
    }

    RankingService(KinaProperties properties, DeterministicRanker deterministic, PartRanker ranker,
                   Supplier<CrossEncoderPartRanker.Status> modelStatus, RankingScoreCache cache) {
        this.config = properties.ranking();
        this.search = properties.search();
        this.deterministic = deterministic;
        this.ranker = ranker;
        this.modelStatus = modelStatus;
        this.cache = cache;
        this.policy = ConstraintPolicy.from(properties.search());
    }

    /** The hard / relaxable constraint table in use ({@code kina.search.hard-constraints}). */
    public ConstraintPolicy policy() {
        return policy;
    }

    public RankingStatus status() {
        KinaProperties.CrossEncoder ce = config.crossEncoder();
        CrossEncoderPartRanker.Status s;
        try {
            s = modelStatus.get();
        } catch (RuntimeException e) {
            s = null;
        }
        if (s == null) {
            return new RankingStatus(ce.enabled(), ce.variant().name().toLowerCase(Locale.ROOT),
                    ce.resolvedModelDir().toString(), null, false, ce.maxCandidates(), ce.weight(),
                    "status unavailable", ce.effectiveThreads(), null);
        }
        return new RankingStatus(ce.enabled(), s.variant(), s.modelDir(), s.revision(), ce.enabled() && s.loaded(),
                ce.maxCandidates(), ce.weight(), s.lastError(), s.threads(), s.avgLatencyMs());
    }

    /**
     * Ranks every distributor's parts for {@code query} within {@code budget} (null: {@code kina.ranking.timeout}).
     * The returned parts are the input parts (not enriched).
     */
    public RankedResults rank(ParsedQuery query, Map<Distributor, List<Part>> fetched, Duration budget) {
        return rank(query, fetched, budget, RankOptions.DEFAULT);
    }

    /** As {@link #rank(ParsedQuery, Map, Duration, RankOptions)} for {@code quantity} pieces, below-spec excluded. */
    public RankedResults rank(ParsedQuery query, Map<Distributor, List<Part>> fetched, Duration budget,
                              int quantity) {
        return rank(query, fetched, budget, new RankOptions(quantity, false));
    }

    /**
     * Why {@code part} would be left out for {@code query} before ranking (DESIGN.md 3.4): a known attribute
     * contradicts a strict constraint ({@link Exclusion#CONSTRAINT}), or a known rating is below the request
     * ({@link Exclusion#BELOW_SPEC}); null when the part meets the request. Used by the search to decide whether a
     * distributor's result holds anything that meets the request (paging and relaxation, DESIGN.md 3.2).
     */
    public Exclusion exclusion(ParsedQuery query, Part part) {
        return switch (verdict(query, part)) {
            case CONSTRAINT -> Exclusion.CONSTRAINT;
            case BELOW_SPEC -> Exclusion.BELOW_SPEC;
            default -> null;
        };
    }

    /**
     * {@link #exclusion} with one more distinction: a part that meets the request but does not state a requested rating
     * ({@link Verdict#UNVERIFIED_RATING}). The search pages on (within {@code max-pages-per-search}) until a part
     * meets the request with every rating verified, and relaxes only when nothing meets it at all (DESIGN.md 3.2).
     */
    public Verdict verdict(ParsedQuery query, Part part) {
        if (safeCheck(query, part).conflict()) {
            return Verdict.CONSTRAINT;
        }
        DeterministicRanker.Assessment a = safeAssess(query, part);
        if (a.isBelowSpec()) {
            return Verdict.BELOW_SPEC;
        }
        boolean ratingUnverified = a.unverified().stream()
                .anyMatch(u -> DeterministicRanker.RATING_KINDS.contains(u.replace(' ', '_')));
        return ratingUnverified ? Verdict.UNVERIFIED_RATING : Verdict.MEETS;
    }

    /**
     * Ranks for an order of {@code options.quantity()} pieces (DESIGN.md 3.3 and 3.4). Parts whose known attribute
     * contradicts a hard constraint ({@link ConstraintPolicy}, {@code kina.search.hard-constraints}) are removed and
     * counted ({@link RankedResults#excludedBy}, per constraint {@link RankedResults#excludedDetailBy}); parts with a known rating below the request are removed and counted
     * ({@link RankedResults#excludedBelowSpecBy}) unless {@code options.allowBelowSpec()}, which keeps them flagged in
     * the last tier, ordered by their distance from the target. Every other part gets a tier: parts with a stock
     * shortfall after those without, parts with a mismatch or an unverified constraint after complete matches. The
     * quantity, MOQ, low-stock and lifecycle penalties are subtracted from the deterministic score and again from the
     * final score. When the query was not understood ({@link ParsedQuery#understood()}) every match grade is null.
     */
    public RankedResults rank(ParsedQuery query, Map<Distributor, List<Part>> fetched, Duration budget,
                              RankOptions options) {
        RankOptions opts = options == null ? RankOptions.DEFAULT : options;
        Duration effective = budget == null ? config.timeout() : budget;
        long deadline = System.nanoTime() + effective.toNanos();
        Map<Distributor, List<Part>> input = fetched == null ? Map.of() : fetched;
        Map<String, Double> det = new HashMap<>();
        Map<String, Integer> tiers = new HashMap<>();
        Map<String, Double> penalties = new HashMap<>();
        Map<String, Double> distances = new HashMap<>();
        Map<String, DeterministicRanker.Assessment> assessments = new HashMap<>();
        Map<Distributor, Integer> excluded = new EnumMap<>(Distributor.class);
        Map<Distributor, Integer> excludedBelowSpec = new EnumMap<>(Distributor.class);
        Map<Distributor, Map<String, Integer>> detail = new EnumMap<>(Distributor.class);
        Map<Distributor, List<BelowSpecCandidate>> belowSpecLeftOut = new EnumMap<>(Distributor.class);
        Map<Distributor, List<ExcludedRequest>> requestedLeftOut = new EnumMap<>(Distributor.class);
        Map<Distributor, List<Part>> sorted = new EnumMap<>(Distributor.class);
        int qty = opts.quantity();
        boolean understood = query.understood();
        try {
            input.forEach((distributor, parts) -> {
                List<Part> kept = new ArrayList<>();
                for (Part p : dedupe(parts)) {
                    ConstraintPolicy.Result check = safeCheck(query, p);
                    List<String> naming = PartNumbers.requestedBy(query, p);
                    // a requested part listed without stock (stock 0) is not part of fetched: never counted
                    boolean listed = p.stock() <= 0;
                    if (check.conflict()) {
                        if (!listed) {
                            excluded.merge(distributor, 1, Integer::sum);
                            detail.computeIfAbsent(distributor, d -> new LinkedHashMap<>())
                                    .merge(check.reason(), 1, Integer::sum);
                        }
                        naming.forEach(n -> requestedLeftOut.computeIfAbsent(distributor, d -> new ArrayList<>())
                                .add(new ExcludedRequest(n, p, check.reason())));
                        continue;
                    }
                    DeterministicRanker.Assessment a = safeAssess(query, p);
                    if (a.isBelowSpec() && !opts.allowBelowSpec()) {
                        if (!listed) {
                            excludedBelowSpec.merge(distributor, 1, Integer::sum);
                            belowSpecLeftOut.computeIfAbsent(distributor, d -> new ArrayList<>())
                                    .add(new BelowSpecCandidate(p, a));
                        }
                        naming.forEach(n -> requestedLeftOut.computeIfAbsent(distributor, d -> new ArrayList<>())
                                .add(new ExcludedRequest(n, p, shortfallText(a))));
                        continue;
                    }
                    kept.add(p);
                    String key = PartKey.of(p);
                    double penalty = penalty(p, qty);
                    assessments.put(key, a);
                    // the overshoot is in the deterministic score already; the final score loses it again
                    penalties.put(key, penalty + a.overshoot());
                    det.put(key, Math.clamp(a.score() - penalty, 0.0, 1.0));
                    distances.put(key, a.isBelowSpec() ? a.belowSpecDistance() : 0.0);
                    // the part the query names by part number comes first (DESIGN.md 3.3, "requested part first"); one
                    // listed without stock comes after every part in stock
                    tiers.put(key, listed ? LISTED_TIER : (naming.isEmpty() ? 0 : REQUESTED_TIER)
                            + (a.isBelowSpec() ? 8 : 0) + (p.stock() < qty ? 4 : 0) + (a.complete() ? 0 : 1));
                }
                kept.sort(byScore(tiers, distances, det, det));
                sorted.put(distributor, kept);
            });
        } catch (RuntimeException e) {
            log.warn("deterministic ranking failed", e);
            sorted.clear();
            tiers.clear();
            distances.clear();
            input.forEach((distributor, parts) -> sorted.put(distributor, dedupe(parts)));
            return annotate(fallback(sorted, det, tiers, "ranking failed: " + e.getClass().getSimpleName()),
                    assessments, understood).withExcluded(excluded, excludedBelowSpec, detail,
                    belowSpecDetail(belowSpecLeftOut), requestedLeftOut);
        }

        if (!config.crossEncoder().enabled()) {
            return annotate(fallback(sorted, det, tiers, "cross-encoder disabled"), assessments, understood)
                    .withExcluded(excluded, excludedBelowSpec, detail,
                    belowSpecDetail(belowSpecLeftOut), requestedLeftOut);
        }
        if (sorted.values().stream().allMatch(List::isEmpty)) {
            return new RankedResults(emptyLists(sorted), RankingMode.BLENDED, null, excluded, excludedBelowSpec,
                    detail, belowSpecDetail(belowSpecLeftOut), requestedLeftOut);
        }
        try {
            return annotate(blendedRanking(query, sorted, det, tiers, distances, penalties, deadline, effective),
                    assessments, understood).withExcluded(excluded, excludedBelowSpec, detail,
                    belowSpecDetail(belowSpecLeftOut), requestedLeftOut);
        } catch (RankingException e) {
            log.info("ranking fallback for '{}': {}", query.normalizedKey(), e.getMessage());
            return annotate(fallback(sorted, det, tiers, e.getMessage()), assessments, understood)
                    .withExcluded(excluded, excludedBelowSpec, detail,
                    belowSpecDetail(belowSpecLeftOut), requestedLeftOut);
        } catch (RuntimeException e) {
            log.warn("ranking fallback after unexpected error", e);
            return annotate(fallback(sorted, det, tiers, "cross-encoder failed: " + e.getClass().getSimpleName()),
                    assessments, understood).withExcluded(excluded, excludedBelowSpec, detail,
                    belowSpecDetail(belowSpecLeftOut), requestedLeftOut);
        }
    }

    /**
     * Score deduction of a part for an order of {@code quantity} pieces (DESIGN.md 3.4): stock shortfall and minimum
     * order quantity ({@code kina.search.quantity.*}), low stock ({@code kina.search.low-stock-threshold}) and the
     * lifecycle ({@code kina.search.lifecycle.*}).
     */
    double penalty(Part part, int quantity) {
        KinaProperties.Quantity q = search.quantity();
        double penalty = DeterministicRanker.quantityPenalty(part, quantity, q.stockShortfallPenalty(), q.moqPenalty())
                + lifecyclePenalty(part);
        if (Availability.isLowStock(part, quantity, search.lowStockThreshold())) {
            penalty += q.lowStockPenalty();
        }
        return penalty;
    }

    private RankedResults blendedRanking(ParsedQuery query, Map<Distributor, List<Part>> sorted,
                                         Map<String, Double> det, Map<String, Integer> tiers,
                                         Map<String, Double> distances, Map<String, Double> penalties,
                                         long deadline, Duration budget)
            throws RankingException {
        Map<Distributor, Integer> quotas = quotas(sorted, config.crossEncoder().maxCandidates());
        List<Part> candidates = new ArrayList<>();
        sorted.forEach((d, parts) -> candidates.addAll(parts.subList(0, quotas.getOrDefault(d, 0))));

        String queryKey = query.normalizedKey();
        Map<String, Double> raw = new HashMap<>();
        List<Part> toScore = new ArrayList<>();
        for (Part p : candidates) {
            Double cached = cache.get(queryKey, PartKey.of(p));
            if (cached != null) {
                raw.put(PartKey.of(p), cached);
            } else {
                toScore.add(p);
            }
        }

        if (!toScore.isEmpty()) {
            Duration remaining = Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
            if (remaining.compareTo(MIN_CALL_BUDGET) < 0) {
                throw new RankingException(RankingException.Reason.TIMEOUT, "cross-encoder timeout: budget exhausted");
            }
            Map<String, Double> scores;
            try {
                scores = ranker.rank(query, toScore, remaining);
            } catch (RankingException e) {
                if (e.reason() == RankingException.Reason.TIMEOUT) {
                    // name the whole ranking budget, not what was left of it for the model
                    throw new RankingException(RankingException.Reason.TIMEOUT,
                            "cross-encoder timeout after " + CrossEncoderPartRanker.format(budget), e);
                }
                throw e;
            }
            for (Part p : toScore) {
                Double s = scores.get(PartKey.of(p));
                if (s != null && !s.isNaN() && !s.isInfinite()) {
                    raw.put(PartKey.of(p), s);
                    cache.put(queryKey, PartKey.of(p), s);
                }
            }
        }
        if (raw.isEmpty()) {
            throw new RankingException(RankingException.Reason.FAILED, "cross-encoder failed: no scores");
        }
        Map<String, Double> detCandidates = new HashMap<>();
        raw.keySet().forEach(k -> detCandidates.put(k, det.getOrDefault(k, 0.0)));
        return blended(sorted, det, tiers, distances, penalties, normalise(detCandidates), normalise(raw));
    }

    /**
     * Candidate count per distributor: {@code maxCandidates} shared proportionally to the number of parts, at least
     * {@value #MIN_CANDIDATES_PER_DISTRIBUTOR} per distributor with results (bounded by its part count).
     */
    static Map<Distributor, Integer> quotas(Map<Distributor, List<Part>> sorted, int maxCandidates) {
        Map<Distributor, Integer> quotas = new EnumMap<>(Distributor.class);
        int total = sorted.values().stream().mapToInt(List::size).sum();
        if (total == 0) {
            return quotas;
        }
        int max = Math.max(0, maxCandidates);
        if (total <= max) {
            sorted.forEach((d, parts) -> quotas.put(d, parts.size()));
            return quotas;
        }
        int assigned = 0;
        for (Map.Entry<Distributor, List<Part>> e : sorted.entrySet()) {
            int n = e.getValue().size();
            if (n == 0) {
                continue;
            }
            int q = (int) Math.floor((double) max * n / total);
            q = Math.min(n, Math.max(Math.min(MIN_CANDIDATES_PER_DISTRIBUTOR, n), q));
            quotas.put(e.getKey(), q);
            assigned += q;
        }
        // hand out the rounding remainder to the distributors with the most parts left
        while (assigned < max) {
            Distributor best = null;
            int bestLeft = 0;
            for (Map.Entry<Distributor, Integer> e : quotas.entrySet()) {
                int left = sorted.get(e.getKey()).size() - e.getValue();
                if (left > bestLeft) {
                    best = e.getKey();
                    bestLeft = left;
                }
            }
            if (best == null) {
                break;
            }
            quotas.merge(best, 1, Integer::sum);
            assigned++;
        }
        // the per-distributor minimum may overshoot: trim the largest quotas above the minimum
        while (assigned > max) {
            Distributor largest = null;
            int largestQuota = MIN_CANDIDATES_PER_DISTRIBUTOR;
            for (Map.Entry<Distributor, Integer> e : quotas.entrySet()) {
                if (e.getValue() > largestQuota) {
                    largest = e.getKey();
                    largestQuota = e.getValue();
                }
            }
            if (largest == null) {
                break;
            }
            quotas.merge(largest, -1, Integer::sum);
            assigned--;
        }
        return quotas;
    }

    /**
     * Rank normalisation within the candidate set: best distinct score 1.0, worst 0.0, linear in the dense rank;
     * equal scores (to 4 decimals) share a value; a single distinct score maps to 1.0.
     */
    static Map<String, Double> normalise(Map<String, Double> raw) {
        TreeSet<Double> distinct = new TreeSet<>(Comparator.reverseOrder());
        raw.values().forEach(v -> distinct.add(round(v)));
        List<Double> ranks = new ArrayList<>(distinct);
        Map<String, Double> out = new HashMap<>();
        raw.forEach((key, value) -> {
            int rank = ranks.indexOf(round(value));
            out.put(key, ranks.size() == 1 ? 1.0 : 1.0 - (double) rank / (ranks.size() - 1));
        });
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 1e4) / 1e4;
    }

    /**
     * Candidates (parts with a model score) first, by {@code (1 - w) * detNorm + w * ceNorm} minus the part's penalty
     * (so a low-stock, large-MOQ or last-time-buy part visibly loses score); then the other parts by deterministic
     * score, scored {@code lowest candidate score of the distributor * det} so that the scores stay in [0,1] and
     * descending. Below-spec parts (last tier) are ordered by their distance from the target, never by the blend.
     */
    private RankedResults blended(Map<Distributor, List<Part>> sorted, Map<String, Double> det,
                                  Map<String, Integer> tiers, Map<String, Double> distances,
                                  Map<String, Double> penalties, Map<String, Double> detNorm,
                                  Map<String, Double> modelNorm) {
        double w = Math.clamp(config.crossEncoder().weight(), 0.0, 1.0);
        Map<String, Double> finalScores = new HashMap<>();
        modelNorm.forEach((key, m) -> finalScores.put(key, Math.max(0.0,
                (1 - w) * detNorm.getOrDefault(key, 0.0) + w * m - penalties.getOrDefault(key, 0.0))));
        Map<Distributor, List<RankedPart>> out = new EnumMap<>(Distributor.class);
        sorted.forEach((distributor, parts) -> {
            List<Part> candidates = new ArrayList<>();
            List<Part> others = new ArrayList<>();
            parts.forEach(p -> (modelNorm.containsKey(PartKey.of(p)) ? candidates : others).add(p));
            candidates.sort(byScore(tiers, distances, finalScores, det));
            others.sort(byScore(tiers, distances, det, det));
            List<RankedPart> ranked = new ArrayList<>(parts.size());
            candidates.forEach(p -> ranked.add(new RankedPart(p, finalScores.get(PartKey.of(p)))));
            double floor = candidates.isEmpty() ? 1.0 : ranked.stream().mapToDouble(RankedPart::score).min()
                    .orElse(1.0);
            others.forEach(p -> ranked.add(new RankedPart(p, floor * det.getOrDefault(PartKey.of(p), 0.0))));
            out.put(distributor, descending(ranked, tiers, distances));
        });
        return new RankedResults(out, RankingMode.BLENDED, null);
    }

    private static Map<Distributor, List<RankedPart>> emptyLists(Map<Distributor, List<Part>> sorted) {
        Map<Distributor, List<RankedPart>> out = new EnumMap<>(Distributor.class);
        sorted.keySet().forEach(d -> out.put(d, List.of()));
        return out;
    }

    /**
     * Adds every part's match grade (null when the query was not understood), mismatches, unverified constraints and
     * below-spec flag from its deterministic assessment.
     */
    private static RankedResults annotate(RankedResults results, Map<String, DeterministicRanker.Assessment> assessed,
                                          boolean understood) {
        Map<Distributor, List<RankedPart>> out = new EnumMap<>(Distributor.class);
        results.byDistributor().forEach((d, parts) -> out.put(d, parts.stream()
                .map(r -> {
                    DeterministicRanker.Assessment a = assessed.get(PartKey.of(r.part()));
                    if (a == null) {
                        return r;
                    }
                    return new RankedPart(r.part(), r.score(), understood ? a.match() : null, a.mismatches(),
                            a.unverified(), a.isBelowSpec());
                })
                .toList()));
        return results.withParts(out);
    }

    /**
     * Deterministic-score deduction for the part's lifecycle ({@code kina.search.lifecycle.*}): a last-time-buy part
     * (TME {@code AVAILABLE_WHILE_STOCKS_LAST}, Mouser end of life / obsolete / NRND) and, smaller, a supply-constrained
     * one (TME {@code HARDLY_AVAILABLE}).
     */
    private double lifecyclePenalty(Part part) {
        return switch (Availability.lifecycleOf(part)) {
            case Availability.LAST_TIME_BUY -> search.lifecycle().lastTimeBuyPenalty();
            case Availability.SUPPLY_CONSTRAINED -> search.lifecycle().supplyConstrainedPenalty();
            default -> 0.0;
        };
    }

    /**
     * Orders by tier (then, in the below-spec tier, by distance from the target) and keeps {@code score}
     * non-increasing down the list: a part ranked after better-tier parts (incomplete match, stock shortfall, below
     * spec) never shows a higher score than the parts above it.
     */
    private static List<RankedPart> descending(List<RankedPart> ranked, Map<String, Integer> tiers,
                                               Map<String, Double> distances) {
        List<RankedPart> ordered = new ArrayList<>(ranked);
        ordered.sort(Comparator.<RankedPart>comparingInt(r -> tiers.getOrDefault(PartKey.of(r.part()), 0))
                .thenComparingDouble(r -> distances.getOrDefault(PartKey.of(r.part()), 0.0)));
        List<RankedPart> out = new ArrayList<>(ordered.size());
        double previous = Double.MAX_VALUE;
        for (RankedPart r : ordered) {
            // a part named by part number leads with the full score; the others keep theirs below it
            double score = tiers.getOrDefault(PartKey.of(r.part()), 0) < 0 ? 1.0
                    : Math.min(r.score(), previous);
            out.add(score == r.score() ? r : r.withScore(score));
            previous = score;
        }
        return List.copyOf(out);
    }

    private static RankedResults fallback(Map<Distributor, List<Part>> sorted, Map<String, Double> det,
                                          Map<String, Integer> tiers, String note) {
        Map<Distributor, List<RankedPart>> out = new EnumMap<>(Distributor.class);
        // the parts are already in tier order: only keep the scores non-increasing
        sorted.forEach((distributor, parts) -> out.put(distributor, descending(parts.stream()
                .map(p -> new RankedPart(p, det.getOrDefault(PartKey.of(p), 0.0)))
                .toList(), tiers, Map.of())));
        return new RankedResults(out, RankingMode.FALLBACK, note);
    }

    /** Tier offset of a part the query names by part number: before every other part of its distributor. */
    static final int REQUESTED_TIER = -16;
    /**
     * Tier of a requested part listed without stock (stock 0, DESIGN.md 2): after every part in stock, below spec
     * included.
     */
    static final int LISTED_TIER = 32;

    /** A part left out below spec, with its assessment (for {@code excluded_below_spec_detail}). */
    private record BelowSpecCandidate(Part part, DeterministicRanker.Assessment assessment) {
    }

    /** The {@value #MAX_BELOW_SPEC_DETAIL} parts per distributor closest to the request, each with its first failure. */
    private static Map<Distributor, List<BelowSpecPart>> belowSpecDetail(
            Map<Distributor, List<BelowSpecCandidate>> leftOut) {
        Map<Distributor, List<BelowSpecPart>> out = new EnumMap<>(Distributor.class);
        leftOut.forEach((distributor, candidates) -> out.put(distributor, candidates.stream()
                .filter(c -> !c.assessment().shortfalls().isEmpty())
                .sorted(Comparator.comparingDouble(c -> c.assessment().belowSpecDistance()))
                .limit(MAX_BELOW_SPEC_DETAIL)
                .map(c -> {
                    DeterministicRanker.Shortfall first = c.assessment().shortfalls().getFirst();
                    return new BelowSpecPart(c.part().distributorPartNumber(), c.part().manufacturerPartNumber(),
                            first.rating(), first.partValue(), first.requested());
                })
                .toList()));
        return out;
    }

    /** {@code voltage 80V below 100V} (a DCR: {@code dcr 40mohm above 20mohm}). */
    private static String shortfallText(DeterministicRanker.Assessment a) {
        if (a.shortfalls().isEmpty()) {
            return "a rating below the request";
        }
        DeterministicRanker.Shortfall s = a.shortfalls().getFirst();
        return s.rating() + " " + s.partValue() + (ParsedQuery.DCR.equals(s.rating()) ? " above " : " below ")
                + s.requested();
    }

    /**
     * Tier asc (0 = complete match; +1 a mismatch or an unverified constraint; +4 stock below the quantity; +8 below
     * spec), distance from the target asc (below-spec parts only), primary score desc, deterministic score desc, stock
     * desc, unit price (smallest price break) asc.
     */
    private static Comparator<Part> byScore(Map<String, Integer> tiers, Map<String, Double> distances,
                                            Map<String, Double> primary, Map<String, Double> det) {
        Comparator<Part> c = Comparator.<Part>comparingInt(p -> tiers.getOrDefault(PartKey.of(p), 0))
                .thenComparingDouble(p -> distances.getOrDefault(PartKey.of(p), 0.0))
                .thenComparingDouble(p -> -primary.getOrDefault(PartKey.of(p), 0.0));
        return c.thenComparingDouble(p -> -det.getOrDefault(PartKey.of(p), 0.0))
                .thenComparingInt(p -> -p.stock())
                .thenComparing(RankingService::unitPrice, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static BigDecimal unitPrice(Part part) {
        return part.prices().stream()
                .min(Comparator.comparingInt(PriceBreak::quantity))
                .map(PriceBreak::unitPrice)
                .orElse(null);
    }

    private ConstraintPolicy.Result safeCheck(ParsedQuery query, Part part) {
        try {
            return deterministic.check(query, part, policy);
        } catch (RuntimeException e) {
            log.warn("constraint check failed for {}", PartKey.of(part), e);
            return new ConstraintPolicy.Result(List.of(), false);
        }
    }

    private DeterministicRanker.Assessment safeAssess(ParsedQuery query, Part part) {
        try {
            return deterministic.assess(query, part);
        } catch (RuntimeException e) {
            log.warn("deterministic scoring failed for {}", PartKey.of(part), e);
            return new DeterministicRanker.Assessment(0.0, 0.0);
        }
    }

    private static List<Part> dedupe(List<Part> parts) {
        if (parts == null) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        Map<String, Part> unique = new LinkedHashMap<>();
        for (Part p : parts) {
            if (p != null && seen.add(PartKey.of(p))) {
                unique.put(PartKey.of(p), p);
            }
        }
        return List.copyOf(unique.values());
    }
}
