package ro.alacrity.kina.search;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.domain.ConstraintKind;
import ro.alacrity.kina.domain.Match;
import ro.alacrity.kina.domain.MatchMode;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PartFeatures;
import ro.alacrity.kina.domain.RelaxStrategy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rule-based relevance score in [0,1] (DESIGN.md section 3.4); the primary ranking signal. Pure function of the
 * query and the part, so the ordering is stable.
 *
 * <p>The signals are declared on {@link ConstraintKind} ({@link Match}: weight, comparison, scope, order); this class
 * sums them. A part that matches a stated attribute earns its weight, one that misses it loses it, one that does not
 * state it is <b>unverified</b> (listed in {@link Assessment#unverified()} and left out of both sides of the grade).
 * Connector requests use the connector signals (positions, rows, gender, orientation, pitch, connector type,
 * mounting), USB requests the USB signals (USB type, pin configuration, standard, gender, mounting style,
 * orientation, features) instead of the primary value. The ratings ({@link Match#RATING}) share their weight equally
 * between the stated ones; a minimum rating above the request earns the same, minus a small preference for the
 * closest one in the score only (up to {@value #W_RATING_EXCESS}, so 25 V &gt; 35 V &gt; 50 V for a 25 V request).
 * Not declared on a kind: the free-text keywords (lexical, {@value #W_LEXICAL}, score only) and the tie-break (up to
 * {@value #W_TIE_BREAK}: log10(stock), has a price, JLCPCB Basic/Preferred).
 *
 * <p>{@link #assess} also reports the <b>match grade</b>: the typed signals the part earned (tie-break, preferences and
 * free-text keywords excluded) divided by what a part matching every stated and <i>verified</i> parameter would earn,
 * clamped to [0,1]. A word such as {@code heatsink} or {@code housed} missing from the part text lowers the score,
 * never the grade. 1.0 means every verified parameter matches; with a non-empty unverified list it is not a confirmed
 * fit. It is absolute (not rank-normalised) and does not influence the order. A known rating below the request (or a
 * DCR above its maximum) is <b>below spec</b> ({@link Assessment#belowSpec()}, with its distance from the target).
 */
@Component
@RequiredArgsConstructor
public class DeterministicRanker {

    static final double W_LEXICAL = 0.10;
    static final double W_TIE_BREAK = 0.05;
    /** Largest score deduction for a rating above the requested one (closest rating preferred; score only). */
    static final double W_RATING_EXCESS = 0.05;
    /** Rating ratio (in octaves) at which the excess deduction is complete: 4x the requested rating. */
    static final double RATING_EXCESS_OCTAVES = 2.0;
    static final double W_TIE_STOCK = 0.03;
    static final double W_TIE_PRICE = 0.01;
    static final double W_TIE_LIBRARY = 0.01;
    /** Stock at which the stock bonus saturates (log10 scale). */
    static final double STOCK_SATURATION_LOG10 = 6.0;
    /** Ratings: minimums, except {@link ParsedQuery#DCR} (a maximum); in score order. */
    static final List<String> RATING_KINDS = ConstraintKind.ratingMeasures();
    /** A minimum order quantity this many decades above the quantity loses the whole MOQ penalty. */
    static final double MOQ_PENALTY_DECADES = 3.0;

    private final ParametricExtractor extractor;

    /**
     * Deterministic score and match grade of one part.
     *
     * @param score             relevance in [0,1] (the ranking signal)
     * @param match             share of the stated and verified parameters the part satisfies, in [0,1] (class
     *                          comment); null when the part states none of the stated constraints
     * @param mismatches        stated parameters the part is known not to satisfy
     * @param unverified        stated constraints the part does not state ({@code "current"}, {@code "package"}...)
     * @param belowSpec         rating kinds whose known value is below the request (a DCR above its maximum)
     * @param belowSpecDistance how far below: the sum of {@code |ln(part / requested)|} over {@code belowSpec}
     * @param shortfalls        the failed ratings of {@code belowSpec} with the part's and the requested value
     * @param overshoot         the rating overshoot penalty already taken from {@code score}
     *                          ({@link ro.alacrity.kina.domain.Overshoot}); the ranking takes it from the final score
     *                          again
     */
    public record Assessment(double score, Double match, List<String> mismatches, List<String> unverified,
                             List<String> belowSpec, double belowSpecDistance, List<Shortfall> shortfalls,
                             double overshoot) {

        public Assessment {
            mismatches = mismatches == null ? List.of() : List.copyOf(mismatches);
            unverified = unverified == null ? List.of() : List.copyOf(unverified);
            belowSpec = belowSpec == null ? List.of() : List.copyOf(belowSpec);
            shortfalls = shortfalls == null ? List.of() : List.copyOf(shortfalls);
        }

        public Assessment(double score, Double match, List<String> mismatches, List<String> unverified,
                          List<String> belowSpec, double belowSpecDistance) {
            this(score, match, mismatches, unverified, belowSpec, belowSpecDistance, List.of(), 0);
        }

        public Assessment(double score, double match, List<String> mismatches) {
            this(score, match, mismatches, List.of(), List.of(), 0);
        }

        public Assessment(double score, double match) {
            this(score, match, List.of());
        }

        /** A known rating is below the request. */
        public boolean isBelowSpec() {
            return !belowSpec.isEmpty();
        }

        /** Every stated constraint is verified and met: no mismatch and nothing unverified. */
        public boolean complete() {
            return mismatches.isEmpty() && unverified.isEmpty();
        }
    }

    /**
     * A rating the part is known to fail: the rating ({@code voltage}), the part's value ({@code 80V}) and the
     * requested one ({@code 100V}); a DCR above its maximum likewise.
     */
    public record Shortfall(String rating, String partValue, String requested) {
    }

    /**
     * The stated parameters the part is known not to satisfy, in plain words ({@code "dielectric: X5R instead of
     * X7R"}, {@code "package: 1210 instead of 1206"}, {@code "voltage: 16V below 25V"}); an attribute the part does not
     * state is not a mismatch. Reported per part as {@code mismatches} (DESIGN.md 4), in {@link Match#report()} order.
     */
    static List<String> mismatches(ParsedQuery query, ParametricExtractor.Features f) {
        SearchMatchContext context = new SearchMatchContext(query, f);
        List<String> out = new ArrayList<>();
        for (ConstraintKind kind : ConstraintKind.reported()) {
            String mismatch = kind.mismatch(context);
            if (mismatch != null) {
                out.add(mismatch);
            }
        }
        return out;
    }

    /**
     * How a part relates to the hard constraints given ({@link ConstraintPolicy}): a known contradiction
     * ({@link #CONFLICT}, the part is excluded), a stated mounting or technology the part does not state or that is not
     * comparable ({@link #UNKNOWN}, the part stays but ranks below known matches), or nothing against it
     * ({@link #MATCH}).
     */
    public enum ConstraintCheck { MATCH, UNKNOWN, CONFLICT }

    /** Checks the constraints in {@code hard} ({@link ConstraintPolicy} names) that the request states against the part. */
    public ConstraintCheck check(ParsedQuery query, Part part, java.util.Collection<String> hard) {
        return check(query, extractor.features(part), hard);
    }

    static ConstraintCheck check(ParsedQuery query, ParametricExtractor.Features f, java.util.Collection<String> hard) {
        ConstraintPolicy.Result r = ConstraintPolicy.check(query, f, hard);
        return r.conflict() ? ConstraintCheck.CONFLICT : r.unknown() ? ConstraintCheck.UNKNOWN : ConstraintCheck.MATCH;
    }

    /** The hard-constraint check of {@code part} under {@code policy}, with the constraints it contradicts. */
    public ConstraintPolicy.Result check(ParsedQuery query, Part part, ConstraintPolicy policy) {
        return policy.check(query, extractor.features(part));
    }

    /**
     * Score deduction for an order of {@code quantity} pieces (DESIGN.md 3.4 "Quantity"): {@code stockWeight} when the
     * part has fewer pieces in stock than requested, and up to {@code moqWeight} when its minimum order quantity
     * exceeds the quantity, also for a quantity of 1 ({@code moqWeight * min(1, log10(moq / quantity) / 3)}, complete
     * at 1000x: a 2000-piece MOQ for one piece loses all of it, an MOQ of 10 a third).
     */
    public static double quantityPenalty(Part part, int quantity, double stockWeight, double moqWeight) {
        int qty = Math.max(1, quantity);
        double penalty = part.stock() < qty ? stockWeight : 0;
        Integer moq = part.minimumOrderQuantity();
        if (moq != null && moq > qty) {
            penalty += moqWeight * Math.min(1.0, Math.log10((double) moq / qty) / MOQ_PENALTY_DECADES);
        }
        return penalty;
    }

    /** Relevance of {@code part} for {@code query}, clamped to [0,1]. */
    public double score(ParsedQuery query, Part part) {
        return score(query, part, extractor.features(part));
    }

    /** Score and match grade of {@code part} for {@code query}. */
    public Assessment assess(ParsedQuery query, Part part) {
        return assess(query, part, extractor.features(part));
    }

    double score(ParsedQuery query, Part part, ParametricExtractor.Features f) {
        return assess(query, part, f).score();
    }

    Assessment assess(ParsedQuery query, Part part, ParametricExtractor.Features f) {
        SearchMatchContext context = new SearchMatchContext(query, f);
        Match.Scope scope = Match.Scope.of(query);
        String family = ConstraintPolicy.policyFamily(query);
        double score = 0;
        double possible = 0;
        double preference = 0;   // score-only adjustments, not part of the match grade
        List<String> unverified = new ArrayList<>();
        List<String> belowSpec = new ArrayList<>();
        List<Shortfall> shortfalls = new ArrayList<>();
        double belowSpecDistance = 0;
        double overshoot = 0;   // a rating far above the request (score only, also taken from the final score)
        Map<String, Integer> groupSizes = groupSizes(query, scope);

        for (ConstraintKind kind : ConstraintKind.scored()) {
            Match match = kind.match();
            if (!match.scope().covers(scope)) {
                continue;
            }
            if (!match.group().isEmpty()) {
                // a rating: an equal share of the group weight; minimums below the request and maximums above it are
                // below spec, a minimum above it is preferred less the further it is (score only)
                if (kind.wanted(query) == null) {
                    continue;
                }
                PartFeatures.Measure actual = (PartFeatures.Measure) kind.actual(query, f);
                if (actual == null) {
                    unverified.add(kind.reported(query));
                    continue;
                }
                int stated = groupSizes.get(match.group());
                double share = match.weight() / stated;
                possible += share;
                double wanted = ((ParsedQuery.Constraint) kind.wanted(query)).value();
                double partValue = actual.value();
                Double grade = kind.compare(context);
                boolean ok = grade == null || grade > 0;
                score += ok ? share : cost(kind, family, -share);
                boolean bound = kind.generalStrategy() == RelaxStrategy.BELOW_SPEC;
                if (!ok && bound && wanted > 0 && partValue > 0) {
                    belowSpec.add(kind.reported(query));
                    shortfalls.add(new Shortfall(kind.reported(query), actual.display(),
                            ((ParsedQuery.Constraint) kind.wanted(query)).display()));
                    belowSpecDistance += Math.abs(Math.log(partValue / wanted));
                }
                if (ok && bound && match.mode() == MatchMode.AT_LEAST && wanted > 0
                        && partValue > wanted * (1 + match.tolerance())) {
                    double octaves = Math.log(partValue / wanted) / Math.log(2);
                    preference -= W_RATING_EXCESS * Math.min(1.0, octaves / RATING_EXCESS_OCTAVES) / stated;
                    overshoot += kind.overshootPenalty(family, wanted, partValue);
                }
                continue;
            }
            ConstraintKind.Outcome o = kind.score(context, match.weight());
            switch (o.state()) {
                case NOT_STATED -> { }
                case UNKNOWN -> unverified.add(kind.reported(query));
                case COUNTED, UNCOUNTED -> {
                    double points = cost(kind, family, o.points());
                    if (!match.inGrade()) {
                        preference += points;
                    } else {
                        if (o.state() == ConstraintKind.Outcome.State.COUNTED) {
                            possible += o.weight();
                        }
                        score += points;
                    }
                }
            }
        }

        // the match grade counts typed constraints only: free-text keywords rank, they never grade
        Double match = possible <= 0 ? (unverified.isEmpty() ? Double.valueOf(1.0) : null)
                : Double.valueOf(Math.clamp(score / possible, 0.0, 1.0));

        // lexical overlap (score only)
        if (!query.keywords().isEmpty()) {
            long found = query.keywords().stream().filter(k -> f.text().contains(k)).count();
            score += W_LEXICAL * found / query.keywords().size();
        }
        double base = Math.clamp(score + preference + tieBreak(part), 0.0, 1.0);
        return new Assessment(Math.max(0.0, base - overshoot), match, mismatches(query, f), unverified, belowSpec,
                belowSpecDistance, shortfalls, overshoot);
    }

    /** How many members of each {@link Match#group()} the request states. */
    private static Map<String, Integer> groupSizes(ParsedQuery query, Match.Scope scope) {
        Map<String, Integer> sizes = new HashMap<>();
        for (ConstraintKind kind : ConstraintKind.scored()) {
            Match match = kind.match();
            if (!match.group().isEmpty() && match.scope().covers(scope) && kind.wanted(query) != null) {
                sizes.merge(match.group(), 1, Integer::sum);
            }
        }
        return sizes;
    }

    /** A miss costs the declared {@code cost} of the family's {@link ro.alacrity.kina.domain.Relax} when it has one. */
    private static double cost(ConstraintKind kind, String family, double points) {
        double cost = kind.cost(family);
        return points < 0 && cost >= 0 ? -cost : points;
    }

    /**
     * The connector (or USB) signals of a connector request ({@link Match.Scope#CONNECTOR}, {@link Match.Scope#USB}),
     * summed in score order; 0 when the request or the part has no connector attributes.
     */
    static double connectorScore(ParsedQuery query, ParametricExtractor.Features f) {
        if (query.connector() == null || f.connector() == null) {
            return 0;
        }
        SearchMatchContext context = new SearchMatchContext(query, f);
        Match.Scope scope = Match.Scope.of(query);
        double score = 0;
        for (ConstraintKind kind : ConstraintKind.scored()) {
            Match match = kind.match();
            if (match.scope() != scope) {
                continue;
            }
            ConstraintKind.Outcome o = kind.score(context, match.weight());
            if (o.state() == ConstraintKind.Outcome.State.COUNTED
                    || o.state() == ConstraintKind.Outcome.State.UNCOUNTED) {
                score += o.points();
            }
        }
        return score;
    }

    static double tieBreak(Part part) {
        double bonus = 0;
        if (part.stock() > 0) {
            bonus += W_TIE_STOCK * Math.min(1.0, Math.log10(part.stock()) / STOCK_SATURATION_LOG10);
        }
        if (!part.prices().isEmpty()) {
            bonus += W_TIE_PRICE;
        }
        for (Map.Entry<String, Object> e : part.extra().entrySet()) {
            if (e.getKey() != null && e.getKey().toLowerCase(Locale.ROOT).contains("library")
                    && e.getValue() != null) {
                String v = e.getValue().toString().toLowerCase(Locale.ROOT);
                if (v.contains("basic") || v.contains("preferred")) {
                    bonus += W_TIE_LIBRARY;
                    break;
                }
            }
        }
        return Math.min(bonus, W_TIE_BREAK);
    }
}
