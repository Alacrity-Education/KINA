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

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Ranks fetched parts for one query (DESIGN.md section 3.3): deterministic scores for every part, Laya scores for a
 * bounded candidate set, rank-normalised and blended with weight {@code kina.ranking.laya.weight}. Never throws; on
 * any Laya problem the deterministic order is returned with {@link RankingMode#FALLBACK} and a note.
 */
@Service
public class RankingService {

    private static final Logger log = LoggerFactory.getLogger(RankingService.class);

    /** Minimum number of Laya candidates per distributor that has results. */
    static final int MIN_CANDIDATES_PER_DISTRIBUTOR = 5;
    /** Below this remaining budget a Laya call is not attempted. */
    static final Duration MIN_CALL_BUDGET = Duration.ofMillis(100);

    /** A part with its final score in [0,1]. */
    public record RankedPart(Part part, double score) {
    }

    /**
     * Ranked parts per distributor (best first, same distributors as the input), the ranking mode and an optional
     * note explaining a fallback (null when Laya ranking succeeded).
     */
    public record RankedResults(Map<Distributor, List<RankedPart>> byDistributor, RankingMode mode, String note) {
    }

    /** Ranking configuration and Laya health, for {@code list_distributors}/diagnostics. */
    public record RankingStatus(boolean layaEnabled, String layaUrl, String model, boolean layaHealthy,
                                int maxCandidates, double weight) {
    }

    private final KinaProperties.Ranking config;
    private final DeterministicRanker deterministic;
    private final PartRanker ranker;
    private final BooleanSupplier healthCheck;
    private final RankingScoreCache cache;
    private final Semaphore slots;

    @Autowired
    public RankingService(KinaProperties properties, DeterministicRanker deterministic, LayaPartRanker laya,
                          RankingScoreCache cache) {
        this(properties, deterministic, laya, laya::isHealthy, cache);
    }

    RankingService(KinaProperties properties, DeterministicRanker deterministic, PartRanker ranker,
                   BooleanSupplier healthCheck, RankingScoreCache cache) {
        this.config = properties.ranking();
        this.deterministic = deterministic;
        this.ranker = ranker;
        this.healthCheck = healthCheck;
        this.cache = cache;
        this.slots = new Semaphore(Math.max(1, config.laya().maxConcurrentRequests()), true);
    }

    public RankingStatus status() {
        KinaProperties.Laya laya = config.laya();
        boolean healthy;
        try {
            healthy = laya.enabled() && healthCheck.getAsBoolean();
        } catch (RuntimeException e) {
            healthy = false;
        }
        return new RankingStatus(laya.enabled(), laya.url(), laya.model(), healthy, laya.maxCandidates(),
                laya.weight());
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

        if (!config.laya().enabled()) {
            return fallback(sorted, det, "laya disabled");
        }
        if (sorted.values().stream().allMatch(List::isEmpty)) {
            return blended(sorted, det, Map.of(), null);
        }
        try {
            return layaRanking(query, sorted, det, deadline, effective);
        } catch (RankingException e) {
            log.info("ranking fallback for '{}': {}", query.normalizedKey(), e.getMessage());
            return fallback(sorted, det, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("ranking fallback after unexpected error", e);
            return fallback(sorted, det, "laya failed: " + e.getClass().getSimpleName());
        }
    }

    private RankedResults layaRanking(ParsedQuery query, Map<Distributor, List<Part>> sorted, Map<String, Double> det,
                                      long deadline, Duration budget) throws RankingException {
        Map<Distributor, Integer> quotas = quotas(sorted, config.laya().maxCandidates());
        List<Part> candidates = new ArrayList<>();
        sorted.forEach((d, parts) -> candidates.addAll(parts.subList(0, quotas.getOrDefault(d, 0))));

        String queryKey = query.normalizedKey();
        Map<String, Double> raw = new HashMap<>();
        List<Part> toSend = new ArrayList<>();
        for (Part p : candidates) {
            Double cached = cache.get(queryKey, PartKey.of(p));
            if (cached != null) {
                raw.put(PartKey.of(p), cached);
            } else {
                toSend.add(p);
            }
        }

        if (!toSend.isEmpty()) {
            long waitNanos = deadline - System.nanoTime() - MIN_CALL_BUDGET.toNanos();
            if (waitNanos < 0) {
                throw new RankingException(RankingException.Reason.TIMEOUT, "laya timeout: budget exhausted");
            }
            boolean acquired;
            try {
                acquired = slots.tryAcquire(waitNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RankingException(RankingException.Reason.BUSY, "laya busy: interrupted while waiting", e);
            }
            if (!acquired) {
                throw new RankingException(RankingException.Reason.BUSY,
                        "laya busy: no free slot within " + LayaPartRanker.format(budget));
            }
            try {
                Duration remaining = Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
                if (remaining.compareTo(MIN_CALL_BUDGET) < 0) {
                    throw new RankingException(RankingException.Reason.TIMEOUT, "laya timeout: budget exhausted");
                }
                Map<String, Double> scores = ranker.rank(query, toSend, remaining);
                for (Part p : toSend) {
                    Double s = scores.get(PartKey.of(p));
                    if (s != null && !s.isNaN()) {
                        raw.put(PartKey.of(p), s);
                        cache.put(queryKey, PartKey.of(p), s);
                    }
                }
            } finally {
                slots.release();
            }
        }
        if (raw.isEmpty()) {
            throw new RankingException(RankingException.Reason.BAD_RESPONSE, "laya bad response: no scores");
        }
        return blended(sorted, det, normalise(raw), null);
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
     * Rank normalisation within the candidate set: best distinct raw score 1.0, worst 0.0, linear in the dense rank;
     * equal scores share a value; a single distinct score maps to 1.0.
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

    private RankedResults blended(Map<Distributor, List<Part>> sorted, Map<String, Double> det,
                                  Map<String, Double> layaNorm, String note) {
        double w = Math.clamp(config.laya().weight(), 0.0, 1.0);
        Map<String, Double> finalScores = new HashMap<>();
        det.forEach((key, d) -> {
            Double l = layaNorm.get(key);
            // non-candidates get layaNorm = 0, which keeps them below every candidate of the same distributor
            finalScores.put(key, (1 - w) * d + w * (l == null ? 0.0 : l));
        });
        Map<Distributor, List<RankedPart>> out = new EnumMap<>(Distributor.class);
        sorted.forEach((distributor, parts) -> {
            List<Part> candidates = new ArrayList<>();
            List<Part> others = new ArrayList<>();
            parts.forEach(p -> (layaNorm.containsKey(PartKey.of(p)) ? candidates : others).add(p));
            candidates.sort(byScore(finalScores, det));
            others.sort(byScore(det, det));
            List<RankedPart> ranked = new ArrayList<>(parts.size());
            candidates.forEach(p -> ranked.add(new RankedPart(p, finalScores.get(PartKey.of(p)))));
            others.forEach(p -> ranked.add(new RankedPart(p, finalScores.get(PartKey.of(p)))));
            out.put(distributor, List.copyOf(ranked));
        });
        return new RankedResults(out, RankingMode.LAYA, note);
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
