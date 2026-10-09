package ro.alacrity.kina.search.field;

import lombok.experimental.UtilityClass;
import ro.alacrity.kina.domain.ConstraintKind;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Indexed;
import ro.alacrity.kina.domain.MatchContext;
import ro.alacrity.kina.domain.MatchMode;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.RelaxStrategy;
import ro.alacrity.kina.search.ConstraintPolicy;
import ro.alacrity.kina.search.FieldVocabulary;
import ro.alacrity.kina.search.field.FieldPredicate.Absent;
import ro.alacrity.kina.search.field.FieldPredicate.AnyInRange;
import ro.alacrity.kina.search.field.FieldPredicate.AtLeast;
import ro.alacrity.kina.search.field.FieldPredicate.AtMost;
import ro.alacrity.kina.search.field.FieldPredicate.Equal;
import ro.alacrity.kina.search.field.FieldPredicate.NoneOf;
import ro.alacrity.kina.search.field.FieldPredicate.OneOf;
import ro.alacrity.kina.search.field.FieldPredicate.PackageIs;
import ro.alacrity.kina.search.field.FieldPredicate.Range;
import ro.alacrity.kina.search.field.FieldQuery.Group;
import ro.alacrity.kina.search.field.FieldQuery.Role;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a parsed request into a {@link FieldQuery} (DESIGN.md 3.8): the rules come from the {@link Indexed}
 * declarations of {@link ConstraintKind}, the strategy of each kind for the request's family from
 * {@link ConstraintPolicy#strategy}. Every predicate is at most as strict as the Java check of its kind
 * ({@code PageCollector.Check}): a looser SQL filter costs a wasted candidate, a stricter one loses a part.
 *
 * <p>Every kind is turned into predicates by its declared {@link Indexed#predicate()} alone, with the request's value
 * of each column from {@link ConstraintKind#indexWanted}: no kind has a rule of its own here. An
 * {@link Indexed.Predicate#IN_COMPATIBLE} rule (the family, technology, form factor, connector type, LED and switch
 * words) runs the kind's own comparator ({@link ConstraintKind#refuses}) over its declared
 * {@link Indexed#vocabulary()}: a closed vocabulary becomes the values it accepts, an open one the values it refuses,
 * so a value outside the vocabulary is always kept.
 */
@UtilityClass
public class FieldQueryBuilder {

    /** Free-text tokens of at least this many letters and digits are word prefixes; shorter ones are substrings. */
    public static final int WORD_MIN_LENGTH = 3;

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    /**
     * The field query of {@code query} at {@code distributor} (null: every distributor) under {@code policy}. The
     * stated ratings form the order-only group {@code R}: ratings are never filtered in SQL (a part below spec is
     * excluded and counted by the Java check, or kept flagged with {@code allow_below_spec}), so the query is the same
     * with and without {@code allow_below_spec}.
     */
    public FieldQuery build(ParsedQuery query, ConstraintPolicy policy, Distributor distributor) {
        ConstraintPolicy p = policy == null ? ConstraintPolicy.DEFAULTS : policy;
        Set<ConstraintKind> hardSet = EnumSet.noneOf(ConstraintKind.class);
        ConstraintKind.policyKinds().stream().filter(k -> p.strategy(query, k) == RelaxStrategy.NEVER)
                .forEach(hardSet::add);
        MatchContext context = FieldVocabulary.requestContext(query, hardSet);
        List<Group> groups = new ArrayList<>();

        List<ConstraintKind> hardKinds = new ArrayList<>();
        List<FieldPredicate> hard = new ArrayList<>();
        if (distributor != null) {
            hard.add(new FieldPredicate.DistributorIs(distributor));
        }
        hard.add(new FieldPredicate.InStock());
        for (ConstraintKind kind : ConstraintKind.policyKinds()) {
            if (p.strategy(query, kind) == RelaxStrategy.NEVER) {
                List<FieldPredicate> predicates = predicates(kind, context);
                if (!predicates.isEmpty()) {
                    hardKinds.add(kind);
                    hard.addAll(predicates);
                }
            }
        }
        groups.add(new Group(Role.H, Role.H.name(), hardKinds, hard));

        // the ratings the ranker checks against the request: they order the candidates (orderOnly), the Java check
        // excludes and counts a part below spec, so the response reports it as the cached-search path does
        List<ConstraintKind> ratingKinds = new ArrayList<>();
        List<FieldPredicate> ratings = new ArrayList<>();
        for (ConstraintKind kind : ConstraintKind.scored()) {
            if (kind.isRating() && kind.generalStrategy() == RelaxStrategy.BELOW_SPEC) {
                List<FieldPredicate> predicates = predicates(kind, context);
                if (!predicates.isEmpty()) {
                    ratingKinds.add(kind);
                    ratings.addAll(predicates);
                }
            }
        }
        if (!ratings.isEmpty()) {
            groups.add(new Group(Role.R, Role.R.name(), ratingKinds, ratings));
        }

        int rung = 0;
        for (ConstraintKind kind : ConstraintKind.ladder()) {
            if (p.strategy(query, kind) == RelaxStrategy.LADDER) {
                List<FieldPredicate> predicates = predicates(kind, context);
                if (!predicates.isEmpty()) {
                    rung++;
                    groups.add(new Group(Role.L, Role.L.name() + rung, List.of(kind), predicates));
                }
            }
        }

        List<FieldPredicate> text = keywords(query);
        if (!text.isEmpty()) {
            groups.add(new Group(Role.K, Role.K.name(), List.of(), text));
        }

        // the soft kinds the request states (a connector's rows): only the order of the candidates
        List<ConstraintKind> softKinds = new ArrayList<>();
        List<FieldPredicate> soft = new ArrayList<>();
        for (ConstraintKind kind : ConstraintKind.scored()) {
            if (!kind.isRating() && p.strategy(query, kind) == RelaxStrategy.SOFT) {
                List<FieldPredicate> predicates = predicates(kind, context);
                if (!predicates.isEmpty()) {
                    softKinds.add(kind);
                    soft.addAll(predicates);
                }
            }
        }
        if (!soft.isEmpty()) {
            groups.add(new Group(Role.S, Role.S.name(), softKinds, soft));
        }
        return new FieldQuery(distributor, groups, 0);
    }

    /** The free-text predicates: every keyword, and the requested part numbers as MPN prefixes. */
    static List<FieldPredicate> keywords(ParsedQuery query) {
        List<FieldPredicate> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String keyword : query.keywords()) {
            String token = FieldVocabulary.normalize(keyword).strip();
            if (token.isEmpty() || !seen.add(token)) {
                continue;
            }
            out.add(token.length() >= WORD_MIN_LENGTH && WORD.matcher(token).matches()
                    ? new FieldPredicate.Word(token) : new FieldPredicate.Substring(token));
        }
        List<String> prefixes = query.partNumbers().stream().map(FieldVocabulary::normalize).map(String::strip)
                .filter(s -> !s.isEmpty()).distinct().toList();
        if (!prefixes.isEmpty()) {
            out.add(new FieldPredicate.MpnPrefix(prefixes));
        }
        return out;
    }

    /**
     * The predicates of one kind for the request of {@code context}: empty when the request does not state it, the kind
     * is Java only, or its comparator refuses nothing KINA can name. Dispatches on the declared predicate only.
     */
    public List<FieldPredicate> predicates(ConstraintKind kind, MatchContext context) {
        Indexed rule = kind.indexed();
        if (rule == null || rule.javaOnly()) {
            return List.of();
        }
        List<IndexColumn> columns = IndexColumn.of(kind, context.query());
        List<Object> wanted = columns.isEmpty() ? List.of() : kind.indexWanted(context);
        if (wanted.isEmpty()) {
            return List.of();
        }
        List<FieldPredicate> out = new ArrayList<>();
        IndexColumn column = columns.getFirst();
        Object first = wanted.getFirst();
        switch (rule.predicate()) {
            case RANGE -> {
                for (int i = 0; i < Math.min(columns.size(), wanted.size()); i++) {
                    Double v = number(wanted.get(i));
                    if (v != null) {
                        out.add(new Range(kind, columns.get(i), low(v, rule), high(v, rule)));
                    }
                }
            }
            case ARRAY_ANY -> {
                Double v = number(first);
                if (v != null) {
                    out.add(new AnyInRange(kind, column, low(v, rule), high(v, rule)));
                }
            }
            case GTE -> {
                // a request of 0 or less states no minimum: the Java check never puts a part below it
                Double v = number(first);
                if (v != null && v > 0) {
                    out.add(new AtLeast(kind, column, v * (1 - rule.slack()) - rule.margin(), kind.isRating()));
                }
            }
            case LTE -> {
                Double v = number(first);
                if (v != null && v > 0) {
                    out.add(new AtMost(kind, column, v * (1 + rule.slack()) + rule.margin()));
                }
            }
            case EQUAL, JSONB_CONTAINS -> {
                boolean ignoreCase = kind.match() != null && kind.match().mode() == MatchMode.EQUAL_IGNORE_CASE;
                for (int i = 0; i < Math.min(columns.size(), wanted.size()); i++) {
                    Object v = wanted.get(i);
                    if (v instanceof String text) {
                        out.add(new Equal(kind, columns.get(i), ignoreCase ? text.toLowerCase(Locale.ROOT) : text));
                    } else if (v instanceof Integer || v instanceof Boolean) {
                        out.add(new Equal(kind, columns.get(i), v));
                    }
                }
            }
            case IN_COMPATIBLE -> {
                if (first instanceof String w) {
                    out.addAll(compatible(kind, rule.vocabulary(), column, w, context));
                }
            }
            case PACKAGE -> {
                if (first instanceof String w && !w.isBlank()) {
                    out.add(packageIs(kind, rule, w));
                }
            }
            case ABSENT -> out.add(new Absent(kind, column));
            case NONE -> throw new IllegalStateException(kind + ": @Indexed without a predicate");
        }
        return out;
    }

    /**
     * The values of {@code vocabulary} the kind's comparator ({@link ConstraintKind#refuses}) accepts for
     * {@code wanted} (a closed vocabulary: {@code col = ANY(wanted and accepted)}), or refuses (an open one:
     * {@code col <> ALL(refused)}; none when it refuses nothing).
     */
    private static List<FieldPredicate> compatible(ConstraintKind kind, Indexed.Vocabulary vocabulary,
                                                   IndexColumn column, String wanted, MatchContext context) {
        List<String> values = context.vocabulary(vocabulary).stream().distinct().toList();
        if (vocabulary.closed()) {
            Set<String> accepted = new LinkedHashSet<>();
            accepted.add(wanted);
            values.stream().filter(v -> !kind.refuses(context, wanted, v)).forEach(accepted::add);
            return List.of(new OneOf(kind, column, List.copyOf(accepted)));
        }
        List<String> refused = values.stream().filter(v -> kind.refuses(context, wanted, v)).toList();
        return refused.isEmpty() ? List.of() : List.of(new NoneOf(kind, column, refused));
    }

    /**
     * The package rule: the package key, or a can size within the declared margin (at least the can tolerance of
     * the Java check, {@code PackageIs}), or the class an LED size is never compared with.
     */
    private static FieldPredicate packageIs(ConstraintKind kind, Indexed rule, String wanted) {
        double[] can = FieldVocabulary.can(wanted);
        String wantedClass = FieldVocabulary.packageClass(wanted);
        String neutral = FieldVocabulary.LED_PACKAGE.equals(wantedClass) ? FieldVocabulary.PLCC_PACKAGE
                : FieldVocabulary.PLCC_PACKAGE.equals(wantedClass) ? FieldVocabulary.LED_PACKAGE : null;
        return new PackageIs(kind, FieldVocabulary.packageKey(wanted), can == null ? null : can[0],
                can == null ? null : can[1], rule.margin(), neutral);
    }

    /** The lower bound of a range: the rule's slack and margin, plus the writer's rounding (relative). */
    static double low(double value, Indexed rule) {
        return value - Math.abs(value) * (rule.slack() + Indexed.ROUNDING_SLACK) - rule.margin();
    }

    /** The upper bound of a range: the rule's slack and margin, plus the writer's rounding (relative). */
    static double high(double value, Indexed rule) {
        return value + Math.abs(value) * (rule.slack() + Indexed.ROUNDING_SLACK) + rule.margin();
    }

    private static Double number(Object value) {
        if (value instanceof ParsedQuery.Constraint c) {
            return c.value();
        }
        return value instanceof Number n ? n.doubleValue() : null;
    }
}
