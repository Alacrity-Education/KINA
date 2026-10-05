package ro.alacrity.kina.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.alacrity.kina.config.KinaProperties;
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
 * cross-encoder scores; both rank-normalised within the candidate set and blended,
 * {@code final = (1 - w) * ranknorm(det) + w * ranknorm(ce)} with {@code w = kina.ranking.cross-encoder.weight}.
 * Never throws; whenever the cross-encoder cannot score (disabled, not loaded, busy, timeout, failure) the
 * deterministic order is returned with {@link RankingMode#FALLBACK} and a note.
 */
@Service
public class RankingService {

    private static final Logger log = LoggerFactory.getLogger(RankingService.class);

    /** Minimum number of candidates per distributor that has results. */
    static final int MIN_CANDIDATES_PER_DISTRIBUTOR = 5;
    /** Below this remaining budget the cross-encoder is not called. */
    static final Duration MIN_CALL_BUDGET = Duration.ofMillis(20);

    /** A part with its final score in [0,1]. */
    public record RankedPart(Part part, double score) {
    }

    /**
     * Ranked parts per distributor (best first, same distributors as the input), the ranking mode and an optional
     * note explaining a fallback (null when the blend succeeded).
     */
    public record RankedResults(Map<Distributor, List<RankedPart>> byDistributor, RankingMode mode, String note) {
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
    private final DeterministicRanker deterministic;
    private final PartRanker ranker;
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
        this.deterministic = deterministic;
        this.ranker = ranker;
        this.modelStatus = modelStatus;
        this.cache = cache;
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
        Duration effective = budget == null ? config.timeout() : budget;
        long deadline = System.nanoTime() + effective.toNanos();
        Map<Distributor, List<Part>> input = fetched == null ? Map.of() : fetched;
        Map<String, Double> det = new HashMap<>();
        Map<Distributor, List<Part>> sorted = new EnumMap<>(Distributor.class);
        try {
            input.forEach((distributor, parts) -> {
                List<Part> unique = dedupe(parts);
                unique.forEach(p -> det.put(PartKey.of(p), safeScore(query, p)));
                List<Part> ordered = new ArrayList<>(unique);
                ordered.sort(byScore(det, det));
                sorted.put(distributor, ordered);
            });
        } catch (RuntimeException e) {
            log.warn("deterministic ranking failed", e);
            sorted.clear();
            input.forEach((distributor, parts) -> sorted.put(distributor, dedupe(parts)));
            return fallback(sorted, det, "ranking failed: " + e.getClass().getSimpleName());
        }

        if (!config.crossEncoder().enabled()) {
            return fallback(sorted, det, "cross-encoder disabled");
        }
        if (sorted.values().stream().allMatch(List::isEmpty)) {
            return new RankedResults(emptyLists(sorted), RankingMode.BLENDED, null);
        }
        try {
            return blendedRanking(query, sorted, det, deadline, effective);
        } catch (RankingException e) {
            log.info("ranking fallback for '{}': {}", query.normalizedKey(), e.getMessage());
            return fallback(sorted, det, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("ranking fallback after unexpected error", e);
            return fallback(sorted, det, "cross-encoder failed: " + e.getClass().getSimpleName());
        }
    }

    private RankedResults blendedRanking(ParsedQuery query, Map<Distributor, List<Part>> sorted,
                                         Map<String, Double> det, long deadline, Duration budget)
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
        return blended(sorted, det, normalise(detCandidates), normalise(raw));
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
     * Candidates (parts with a model score) first, by {@code (1 - w) * detNorm + w * ceNorm}; then the other parts
     * by deterministic score, scored {@code lowest candidate score of the distributor * det} so that the scores stay
     * in [0,1] and descending.
     */
    private RankedResults blended(Map<Distributor, List<Part>> sorted, Map<String, Double> det,
                                  Map<String, Double> detNorm, Map<String, Double> modelNorm) {
        double w = Math.clamp(config.crossEncoder().weight(), 0.0, 1.0);
        Map<String, Double> finalScores = new HashMap<>();
        modelNorm.forEach((key, m) -> finalScores.put(key, (1 - w) * detNorm.getOrDefault(key, 0.0) + w * m));
        Map<Distributor, List<RankedPart>> out = new EnumMap<>(Distributor.class);
        sorted.forEach((distributor, parts) -> {
            List<Part> candidates = new ArrayList<>();
            List<Part> others = new ArrayList<>();
            parts.forEach(p -> (modelNorm.containsKey(PartKey.of(p)) ? candidates : others).add(p));
            candidates.sort(byScore(finalScores, det));
            others.sort(byScore(det, det));
            List<RankedPart> ranked = new ArrayList<>(parts.size());
            candidates.forEach(p -> ranked.add(new RankedPart(p, finalScores.get(PartKey.of(p)))));
            double floor = candidates.isEmpty() ? 1.0 : ranked.getLast().score();
            others.forEach(p -> ranked.add(new RankedPart(p, floor * det.getOrDefault(PartKey.of(p), 0.0))));
            out.put(distributor, List.copyOf(ranked));
        });
        return new RankedResults(out, RankingMode.BLENDED, null);
    }

    private static Map<Distributor, List<RankedPart>> emptyLists(Map<Distributor, List<Part>> sorted) {
        Map<Distributor, List<RankedPart>> out = new EnumMap<>(Distributor.class);
        sorted.keySet().forEach(d -> out.put(d, List.of()));
        return out;
    }

    private static RankedResults fallback(Map<Distributor, List<Part>> sorted, Map<String, Double> det, String note) {
        Map<Distributor, List<RankedPart>> out = new EnumMap<>(Distributor.class);
        sorted.forEach((distributor, parts) -> out.put(distributor,
                parts.stream().map(p -> new RankedPart(p, det.getOrDefault(PartKey.of(p), 0.0))).toList()));
        return new RankedResults(out, RankingMode.FALLBACK, note);
    }

    /** Primary score desc, deterministic score desc, stock desc, unit price (smallest price break) asc. */
    private static Comparator<Part> byScore(Map<String, Double> primary, Map<String, Double> det) {
        Comparator<Part> c = Comparator.comparingDouble(p -> -primary.getOrDefault(PartKey.of(p), 0.0));
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

    private double safeScore(ParsedQuery query, Part part) {
        try {
            return deterministic.score(query, part);
        } catch (RuntimeException e) {
            log.warn("deterministic scoring failed for {}", PartKey.of(part), e);
            return 0.0;
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
