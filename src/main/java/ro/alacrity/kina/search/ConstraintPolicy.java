package ro.alacrity.kina.search;

import lombok.extern.slf4j.Slf4j;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.ConstraintKind;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.PolicyFamily;
import ro.alacrity.kina.domain.Relax;
import ro.alacrity.kina.domain.RelaxStrategy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which stated constraints are hard (never relaxed: a part whose known value contradicts one is excluded and counted in
 * {@code excluded_by_constraints}) and which are relaxable (the relaxation ladder may loosen them, a part that misses
 * one is returned with the miss in {@code mismatches} and the constraint in {@code constraints_relaxed}), per policy
 * family (DESIGN.md 3.4 "Hard constraints", user decision 2026-10-07).
 *
 * <p>The policy is declared on {@link ConstraintKind} with {@link Relax}: the code defaults ({@link #DEFAULT_HARD})
 * are the kinds whose declared strategy for the family is {@link RelaxStrategy#NEVER}, the ladder
 * ({@link #RELAXABLE}) the {@link RelaxStrategy#LADDER} kinds in their declared order. This class reads those
 * declarations, applies the configuration and runs the exclusion check ({@link #check}) and the reporting; the ladder
 * ({@link DistributorPhraser#ladder}) and the response use it.
 *
 * <p>{@code kina.search.hard-constraints} overrides the list of a family ({@code capacitor: [value, package]}): the
 * listed kinds are hard, every other kind takes its general strategy; the deprecated
 * {@code kina.search.strict-constraints} is still read: {@code mounting}, {@code technology} or {@code elements}
 * missing from it are removed from every family (with a warning).
 */
@Slf4j
public final class ConstraintPolicy {

    /** The hard / relaxable constraint table ({@link RankingService#policy()}; the defaults when not available). */
    static ConstraintPolicy of(RankingService ranking) {
        ConstraintPolicy p = ranking == null ? null : ranking.policy();
        return p == null ? DEFAULTS : p;
    }

    // ---- constraint names (the labels of ConstraintKind; also the keys of excluded_by_constraints_detail, except
    // the primary value: its kind is reported)
    public static final String PACKAGE = ConstraintKind.PACKAGE.label();
    /** The exact voltage of a Zener diode or a fixed regulator (within 2 %). */
    public static final String VOLTAGE = ConstraintKind.EXACT_VOLTAGE.label();
    public static final String FORM_FACTOR = ConstraintKind.FORM_FACTOR.label();

    /** Every name a hard-constraint list may contain: the labels of the policy kinds. */
    public static final Set<String> NAMES = ConstraintKind.policyKinds().stream().map(ConstraintKind::label)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** Constraints the relaxation may loosen when they are not hard, in ladder order (DESIGN.md 3.2). */
    public static final List<String> RELAXABLE = ConstraintKind.ladder().stream().map(ConstraintKind::label).toList();

    // ---- policy families, by their configuration key
    public static final String RESISTOR = PolicyFamily.RESISTOR.key();
    public static final String CAPACITOR = PolicyFamily.CAPACITOR.key();
    public static final String INDUCTOR = PolicyFamily.INDUCTOR.key();
    /** Every other family, and requests whose family is not known. */
    public static final String DEFAULT = PolicyFamily.DEFAULT.key();

    /** The decided table (user decision 2026-10-07): the hard constraints per family, read from the declarations. */
    public static final Map<String, List<String>> DEFAULT_HARD = defaults();

    private static Map<String, List<String>> defaults() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (PolicyFamily family : PolicyFamily.values()) {
            m.put(family.key(), declaredHard(family).stream().map(ConstraintKind::label).toList());
        }
        return Collections.unmodifiableMap(m);
    }

    /** The policy with the code defaults. */
    public static final ConstraintPolicy DEFAULTS = new ConstraintPolicy(Map.of());

    /** The kinds {@code kina.search.strict-constraints} could hold. */
    private static final List<ConstraintKind> LEGACY_STRICT = List.of(ConstraintKind.MOUNTING,
            ConstraintKind.TECHNOLOGY, ConstraintKind.ELEMENTS);

    /** The hard kinds per policy family. */
    private final Map<String, Set<ConstraintKind>> hard;

    /** The declared defaults with the families in {@code overrides} replaced. */
    private ConstraintPolicy(Map<String, Set<ConstraintKind>> overrides) {
        Map<String, Set<ConstraintKind>> m = new LinkedHashMap<>();
        for (PolicyFamily family : PolicyFamily.values()) {
            Set<ConstraintKind> kinds = overrides.containsKey(family.key()) ? overrides.get(family.key())
                    : declaredHard(family);
            m.put(family.key(), kinds.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(kinds)));
        }
        this.hard = Collections.unmodifiableMap(m);
    }

    /** The kinds a family's declarations make hard, in check order. */
    private static Set<ConstraintKind> declaredHard(PolicyFamily family) {
        Set<ConstraintKind> kinds = EnumSet.noneOf(ConstraintKind.class);
        ConstraintKind.policyKinds().stream().filter(k -> k.strategy(family) == RelaxStrategy.NEVER)
                .forEach(kinds::add);
        return kinds;
    }

    /**
     * The policy of {@code kina.search}: the declared defaults, the deprecated {@code strict-constraints} folded in (a
     * warning), then the {@code hard-constraints} overrides. Unknown family keys and names are ignored with a warning.
     */
    public static ConstraintPolicy from(KinaProperties.Search search) {
        if (search == null) {
            return DEFAULTS;
        }
        Map<String, Set<ConstraintKind>> table = new LinkedHashMap<>();
        for (PolicyFamily family : PolicyFamily.values()) {
            table.put(family.key(), declaredHard(family));
        }
        List<String> strict = search.strictConstraints();
        if (strict != null) {
            log.warn("kina.search.strict-constraints (KINA_STRICT_CONSTRAINTS) is deprecated, use "
                    + "kina.search.hard-constraints (DESIGN.md 3.4); read as: {} hard", strict);
            for (ConstraintKind kind : LEGACY_STRICT) {
                if (!strict.contains(kind.label())) {
                    table.values().forEach(kinds -> kinds.remove(kind));
                }
            }
        }
        search.hardConstraints().forEach((rawFamily, rawNames) -> {
            PolicyFamily policyFamily = PolicyFamily.byKey(rawFamily.strip());
            if (policyFamily == null) {
                log.warn("kina.search.hard-constraints: unknown family '{}' ignored (known: {})", rawFamily,
                        DEFAULT_HARD.keySet());
                return;
            }
            String family = policyFamily.key();
            Set<ConstraintKind> kinds = EnumSet.noneOf(ConstraintKind.class);
            for (String raw : rawNames == null ? List.<String>of() : rawNames) {
                String name = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT).replace('-', ' ')
                        .replace('_', ' ');
                ConstraintKind kind = ConstraintKind.byPolicyName(name);
                if (kind != null) {
                    kinds.add(kind);
                } else if (!name.isEmpty()) {
                    log.warn("kina.search.hard-constraints.{}: unknown constraint '{}' ignored (known: {})", family,
                            raw, NAMES);
                }
            }
            table.put(family, kinds);
        });
        return new ConstraintPolicy(table);
    }

    /** The policy family of a request: its component family, {@code usb} for USB connectors, else {@code default}. */
    public static String policyFamily(ParsedQuery query) {
        return PolicyFamily.of(query).key();
    }

    /** The hard kinds of the request's family. */
    Set<ConstraintKind> hardKinds(ParsedQuery query) {
        return hard.getOrDefault(policyFamily(query), hard.get(DEFAULT));
    }

    /** The hard constraints of the request's family, by name. */
    public Set<String> hardFor(ParsedQuery query) {
        return names(hardKinds(query));
    }

    public boolean isHard(ParsedQuery query, String name) {
        ConstraintKind kind = ConstraintKind.byPolicyName(name);
        return kind != null && hardKinds(query).contains(kind);
    }

    /**
     * The strategy of a kind for the request under this policy: {@link RelaxStrategy#NEVER} when the family makes it
     * hard, else the family's own declaration when that is not NEVER (the speed of a fan is relaxable), else its general
     * strategy ({@link ConstraintKind#relaxedStrategy}).
     */
    public RelaxStrategy strategy(ParsedQuery query, ConstraintKind kind) {
        return hardKinds(query).contains(kind) ? RelaxStrategy.NEVER : kind.relaxedStrategy(PolicyFamily.of(query));
    }

    /** True when {@code name} may be loosened for the request: a ladder kind its family does not make hard. */
    public boolean isRelaxable(ParsedQuery query, String name) {
        ConstraintKind kind = ConstraintKind.byPolicyName(name);
        return kind != null && strategy(query, kind) == RelaxStrategy.LADDER;
    }

    /** The table as configured, family by family (for documentation and {@code list_distributors}). */
    public Map<String, Set<String>> table() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        hard.forEach((family, kinds) -> out.put(family, names(kinds)));
        return out;
    }

    private static Set<String> names(Set<ConstraintKind> kinds) {
        return kinds.stream().map(ConstraintKind::label).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    // ---------------------------------------------------------------- the check

    /**
     * How a part relates to the hard constraints of a request.
     *
     * @param conflicts the hard constraints whose known value the part contradicts, in check order, by the name
     *                  reported in {@code excluded_by_constraints_detail} (the primary value by its kind:
     *                  {@code capacitance}, {@code frequency}...); empty when nothing contradicts
     * @param unknown   true when a stated hard constraint (mounting, technology) could not be compared
     */
    public record Result(List<String> conflicts, boolean unknown) {

        public Result {
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        }

        public boolean conflict() {
            return !conflicts.isEmpty();
        }

        /** The constraint a part is counted under: its first conflict. */
        public String reason() {
            return conflicts.isEmpty() ? null : conflicts.getFirst();
        }
    }

    /** {@link #check(ParsedQuery, ParametricExtractor.Features, Collection)} with the request family's hard set. */
    Result check(ParsedQuery query, ParametricExtractor.Features f) {
        return check(query, f, hardKinds(query));
    }

    /** {@link #check(ParsedQuery, ParametricExtractor.Features, Set)} for constraint names. */
    static Result check(ParsedQuery query, ParametricExtractor.Features f, Collection<String> hard) {
        Set<ConstraintKind> kinds = EnumSet.noneOf(ConstraintKind.class);
        if (hard != null) {
            hard.stream().map(ConstraintKind::byPolicyName).filter(java.util.Objects::nonNull).forEach(kinds::add);
        }
        return check(query, f, kinds);
    }

    /**
     * Checks the stated constraints in {@code hard} against the part's known features, kind by kind in declaration
     * order ({@link ConstraintKind#conflict}). An attribute the part does not state never conflicts (it is unverified,
     * the part ranks below verified matches); a package string KINA cannot read never conflicts either.
     */
    static Result check(ParsedQuery query, ParametricExtractor.Features f, Set<ConstraintKind> hard) {
        List<String> conflicts = new ArrayList<>();
        boolean unknown = false;
        if (hard.isEmpty()) {
            return new Result(conflicts, false);
        }
        SearchMatchContext context = new SearchMatchContext(query, f, hard);
        for (ConstraintKind kind : ConstraintKind.policyKinds()) {
            if (!hard.contains(kind)) {
                continue;
            }
            switch (kind.conflict(context)) {
                case CONFLICT -> conflicts.add(kind.reported(query));
                case UNKNOWN -> unknown = true;
                case MATCH -> { }
            }
        }
        return new Result(conflicts, unknown);
    }

    // ---------------------------------------------------------------- reporting

    /**
     * The stated hard constraints of a request by their reported name (the primary value by its kind), in the order
     * of {@link #check}; the component type is left out (every request has one). A hint names them next to the
     * constraints that excluded parts.
     */
    public List<String> statedHard(ParsedQuery query) {
        Set<ConstraintKind> hardSet = hardKinds(query);
        return ConstraintKind.policyKinds().stream().filter(k -> hardSet.contains(k) && k.namedInHint(query))
                .map(k -> k.reported(query)).toList();
    }

    /**
     * A short description of the request for a hint: the primary value, the polarity or subtype, the words of the
     * declared kinds ({@link ConstraintKind#describes}), the family and the package ({@code 22uF capacitor in package
     * 1206}, {@code N-channel mosfet in package SOT-23}, {@code 12V DC axial 40x40x10mm fan}).
     */
    static String describe(ParsedQuery query) {
        List<String> words = new ArrayList<>();
        String primary = query.isConnector() ? null : ConstraintKind.primaryKind(query);
        if (primary != null) {
            words.add(query.constraint(primary).display());
        }
        if (query.constraint(ParsedQuery.VOLTAGE) != null
                && ConstraintKind.isExactRating(ParsedQuery.VOLTAGE, query.family())) {
            words.add(query.constraint(ParsedQuery.VOLTAGE).display());
        }
        if (query.polarity() != null) {
            words.add(query.polarity());
        }
        if (query.subtype() != null) {
            words.add(query.subtype());
        }
        // the words the declared kinds describe the request with (a fan's type and frame size)
        for (ConstraintKind kind : ConstraintKind.policyKinds()) {
            String described = kind.describes(query);
            if (described != null) {
                words.add(described);
            }
        }
        ParsedQuery.Connector c = query.connector();
        if (c != null) {
            if (c.positions() != null) {
                words.add(c.positions() + "-position");
            }
            words.add(c.usbType() != null ? "USB " + c.usbType() : c.type() != null ? c.type() : "connector");
        } else {
            words.add(query.family() != null ? query.family() : "part");
        }
        String text = String.join(" ", words);
        return query.packageName() != null ? text + " in package " + query.packageName() : text;
    }

    /**
     * The hint of distributors that returned nothing (DESIGN.md 3.2 "Empty after the hard set"): what was asked, at
     * which distributors, which hard constraints could not be met (those that excluded parts, else the stated ones),
     * how many parts were below a stated rating, and that no substitutes are returned.
     *
     * @param distributors   the distributors that returned nothing (display names)
     * @param excludedBy     parts excluded per constraint, summed over those distributors
     * @param belowSpec      parts below a stated rating, summed over those distributors
     * @param allowBelowSpec whether the request already returns parts below spec
     */
    String hint(ParsedQuery query, List<String> distributors, Map<String, Integer> excludedBy, int belowSpec,
                boolean allowBelowSpec) {
        List<String> names = new ArrayList<>();
        excludedBy.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> names.add(e.getKey()));
        for (String stated : statedHard(query)) {
            if (!names.contains(stated)) {
                names.add(stated);
            }
        }
        StringBuilder out = new StringBuilder("No in-stock ").append(describe(query)).append(" at ")
                .append(joinAnd(distributors));
        if (!names.isEmpty()) {
            out.append("; ").append(joinAnd(names)).append(names.size() == 1 ? " is" : " are")
                    .append(" never relaxed");
        }
        out.append('.');
        if (belowSpec > 0 && !allowBelowSpec) {
            out.append(' ').append(belowSpec).append(belowSpec == 1 ? " part was" : " parts were")
                    .append(" left out for a rating below the request; pass \"allow_below_spec\": true to see them.");
        }
        List<String> alternatives = new ArrayList<>();
        if (names.contains(PACKAGE)) {
            alternatives.add("package");
        }
        String primary = query.isConnector() ? null : ConstraintKind.primaryKind(query);
        if (primary != null && names.contains(primary.replace('_', ' ')) || names.contains(VOLTAGE)) {
            alternatives.add("value");
        }
        out.append(" No substitutes are returned; try another ")
                .append(alternatives.isEmpty() ? "wording" : String.join(" or ", alternatives)).append('.');
        return out.toString();
    }

    private static String joinAnd(List<String> items) {
        if (items.size() <= 1) {
            return items.isEmpty() ? "" : items.getFirst();
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.getLast();
    }
}
