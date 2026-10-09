package ro.alacrity.kina.search.field;

import lombok.experimental.UtilityClass;
import ro.alacrity.kina.domain.ComponentFamily;
import ro.alacrity.kina.domain.ConstraintKind;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Indexed;
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
 * <p>The kinds with a comparator of their own (the family, technology, form factor, connector type, LED and switch
 * words) are turned into the values the comparator accepts or refuses, computed with the same comparator over the
 * vocabulary ({@link FieldVocabulary}); a value outside the vocabulary is always kept.
 */
@UtilityClass
public class FieldQueryBuilder {

    /** Free-text tokens of at least this many letters and digits are word prefixes; shorter ones are substrings. */
    public static final int WORD_MIN_LENGTH = 3;

    /** Absolute widening of every range, against values that are exactly zero. */
    private static final double EPSILON = 1e-12;

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    /**
     * The field query of {@code query} at {@code distributor} (null: every distributor) under {@code policy}; the
     * ratings are left out when {@code allowBelowSpec} (they only rank then).
     */
    public FieldQuery build(ParsedQuery query, ConstraintPolicy policy, Distributor distributor,
                            boolean allowBelowSpec) {
        ConstraintPolicy p = policy == null ? ConstraintPolicy.DEFAULTS : policy;
        boolean packageHard = p.strategy(query, ConstraintKind.PACKAGE) == RelaxStrategy.NEVER;
        List<Group> groups = new ArrayList<>();

        List<ConstraintKind> hardKinds = new ArrayList<>();
        List<FieldPredicate> hard = new ArrayList<>();
        if (distributor != null) {
            hard.add(new FieldPredicate.DistributorIs(distributor));
        }
        hard.add(new FieldPredicate.InStock());
        for (ConstraintKind kind : ConstraintKind.policyKinds()) {
            if (p.strategy(query, kind) == RelaxStrategy.NEVER) {
                List<FieldPredicate> predicates = predicates(kind, query, packageHard);
                if (!predicates.isEmpty()) {
                    hardKinds.add(kind);
                    hard.addAll(predicates);
                }
            }
        }
        groups.add(new Group(Role.H, Role.H.name(), hardKinds, hard));

        if (!allowBelowSpec) {
            List<ConstraintKind> ratingKinds = new ArrayList<>();
            List<FieldPredicate> ratings = new ArrayList<>();
            for (ConstraintKind kind : ConstraintKind.scored()) {
                // the ratings the ranker checks against the request: below spec excludes unless allowed
                if (kind.isRating() && kind.generalStrategy() == RelaxStrategy.BELOW_SPEC) {
                    List<FieldPredicate> predicates = predicates(kind, query, packageHard);
                    if (!predicates.isEmpty()) {
                        ratingKinds.add(kind);
                        ratings.addAll(predicates);
                    }
                }
            }
            if (!ratings.isEmpty()) {
                groups.add(new Group(Role.R, Role.R.name(), ratingKinds, ratings));
            }
        }

        int rung = 0;
        for (ConstraintKind kind : ConstraintKind.ladder()) {
            if (p.strategy(query, kind) == RelaxStrategy.LADDER) {
                List<FieldPredicate> predicates = predicates(kind, query, packageHard);
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
                List<FieldPredicate> predicates = predicates(kind, query, packageHard);
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
     * The predicates of one kind for a request: empty when the request does not state it, the kind is Java only, or
     * its comparator refuses nothing KINA can name.
     */
    public List<FieldPredicate> predicates(ConstraintKind kind, ParsedQuery q, boolean packageHard) {
        Indexed rule = kind.indexed();
        if (rule == null || rule.javaOnly()) {
            return List.of();
        }
        List<IndexColumn> columns = IndexColumn.of(kind, q);
        if (columns.isEmpty()) {
            return List.of();
        }
        IndexColumn column = columns.getFirst();
        ParsedQuery.Connector connector = q.connector();
        return switch (kind) {
            case TYPE -> family(kind, column, q.family());
            case EXACT_VOLTAGE -> {
                Double v = number(kind.wanted(q));
                yield v == null ? List.of() : List.of(new AnyInRange(kind, column, low(v, rule), high(v, rule)));
            }
            case PACKAGE -> packageIs(kind, rule, q.packageName());
            case ELEMENTS -> q.elements() == null && ComponentFamily.has(q.family(), ComponentFamily.Trait.ARRAYS)
                    ? List.of(new Absent(kind, column)) : List.of();
            case FORM_FACTOR -> refused(kind, column, FieldVocabulary.requestedFormFactor(q, packageHard),
                    FieldVocabulary.formFactors(),
                    (w, v) -> FieldVocabulary.compatibleFormFactor(w, v) == Boolean.FALSE);
            case TECHNOLOGY -> refused(kind, column, q.technology(), FieldVocabulary.technologies(),
                    (w, v) -> FieldVocabulary.compareTechnology(w, v) < 0);
            case CONNECTOR_TYPE -> refused(kind, column, (String) kind.wanted(q), FieldVocabulary.CONNECTOR_TYPES,
                    (w, v) -> FieldVocabulary.connectorTypesMatch(w, v) == Boolean.FALSE);
            case USB_TYPE -> {
                if (connector == null || !connector.isUsb()) {
                    yield List.of();
                }
                String type = connector.usbType() != null ? connector.usbType()
                        : FieldVocabulary.usbTypeOf(connector.type());
                yield type == null ? List.of() : List.of(new Equal(kind, column, type));
            }
            case PIN_CONFIGURATION -> {
                if (connector == null || !connector.isUsb() || connector.pinConfigurationImplied()) {
                    yield List.of();
                }
                String type = connector.usbType() != null ? connector.usbType()
                        : FieldVocabulary.usbTypeOf(connector.type());
                Integer pins = connector.pinConfiguration() != null ? connector.pinConfiguration()
                        : FieldVocabulary.usbConfiguration(type, connector.positions());
                pins = pins != null ? pins : connector.positions();
                yield pins == null ? List.of() : List.of(new Equal(kind, column, pins));
            }
            case USB_STANDARD -> {
                Integer wanted = FieldVocabulary.requestedUsbClass((String) kind.wanted(q));
                yield wanted == null ? List.of() : List.of(new AtLeast(kind, column, wanted, false));
            }
            case FAN_TYPE -> {
                ParsedQuery.Fan fan = (ParsedQuery.Fan) kind.wanted(q);
                List<FieldPredicate> out = new ArrayList<>();
                if (fan != null && fan.type() != null) {
                    out.add(new Equal(kind, columns.get(0), fan.type()));
                }
                if (fan != null && fan.supply() != null) {
                    out.add(new Equal(kind, columns.get(1), fan.supply()));
                }
                yield out;
            }
            case FRAME_SIZE -> {
                ParsedQuery.Frame frame = (ParsedQuery.Frame) kind.wanted(q);
                yield frame == null ? List.of() : sortedSize(kind, rule, columns, frame.width(), frame.length());
            }
            case SWITCH_SIZE -> {
                ParsedQuery.BodySize size = (ParsedQuery.BodySize) kind.wanted(q);
                yield size == null ? List.of() : sortedSize(kind, rule, columns, size.width(), size.length());
            }
            case LED_TYPE -> {
                List<String> vocabulary = new ArrayList<>(FieldVocabulary.ledTypes());
                vocabulary.add(ParsedQuery.Led.INDICATOR);
                vocabulary.add(ParsedQuery.Led.HIGH_POWER);
                yield refused(kind, column, (String) kind.wanted(q), vocabulary,
                        (w, v) -> ParsedQuery.Led.typeGrade(w, v) < 0);
            }
            case COLOUR -> refused(kind, column, (String) kind.wanted(q), FieldVocabulary.ledColours(), (w, v) -> {
                Double g = ParsedQuery.Led.colourGrade(w, v);
                return g != null && g < 0;
            });
            case SWITCH_TYPE -> refused(kind, column, (String) kind.wanted(q), FieldVocabulary.switchTypes(),
                    (w, v) -> ParsedQuery.Switch.typeGrade(w, v) < 0);
            case TERMINATION -> refused(kind, column, (String) kind.wanted(q), FieldVocabulary.terminations(),
                    (w, v) -> {
                        Double g = ParsedQuery.Switch.terminationGrade(w, v);
                        return g != null && g < 0;
                    });
            case CONTACTS -> {
                ParsedQuery.Contacts contacts = (ParsedQuery.Contacts) kind.wanted(q);
                if (contacts == null) {
                    yield List.of();
                }
                List<FieldPredicate> out = new ArrayList<>();
                out.add(new Equal(kind, columns.get(0),
                        new ParsedQuery.Contacts(contacts.poles(), contacts.throwsCount(), null).display()));
                if (contacts.form() != null) {
                    out.add(new Equal(kind, columns.get(1), contacts.form()));
                }
                yield out;
            }
            case DIELECTRIC -> {
                Object wanted = kind.wanted(q);
                yield wanted == null ? List.of()
                        : List.of(new Equal(kind, column, ((String) wanted).toLowerCase(Locale.ROOT)));
            }
            case VALUE -> {
                ParsedQuery.Constraint wanted = (ParsedQuery.Constraint) kind.wanted(q);
                if (wanted == null) {
                    yield List.of();
                }
                List<FieldPredicate> out = new ArrayList<>();
                out.add(new Range(kind, column, low(wanted.value(), rule), high(wanted.value(), rule)));
                if (ParsedQuery.IMPEDANCE.equals(kind.indexMeasure(q)) && wanted.condition() != null) {
                    out.add(new Range(kind, IndexColumn.IMPEDANCE_TEST_HZ, low(wanted.condition(), rule),
                            high(wanted.condition(), rule)));
                }
                yield out;
            }
            default -> generic(kind, rule, column, kind.wanted(q));
        };
    }

    /** The rule of a kind whose comparison is its {@link ro.alacrity.kina.domain.Match} mode on one value. */
    private static List<FieldPredicate> generic(ConstraintKind kind, Indexed rule, IndexColumn column,
                                                Object wanted) {
        if (wanted == null) {
            return List.of();
        }
        return switch (rule.predicate()) {
            case RANGE -> {
                Double v = number(wanted);
                yield v == null ? List.of() : List.of(new Range(kind, column, low(v, rule), high(v, rule)));
            }
            case GTE -> {
                Double v = number(wanted);
                // a request of 0 or less is no rating: the Java check never puts a part below it
                yield v == null || v <= 0 ? List.of()
                        : List.of(new AtLeast(kind, column, v * (1 - rule.slack()) - rule.margin(), true));
            }
            case LTE -> {
                Double v = number(wanted);
                yield v == null || v <= 0 ? List.of()
                        : List.of(new AtMost(kind, column, v * (1 + rule.slack()) + rule.margin()));
            }
            case EQUAL, JSONB_CONTAINS -> wanted instanceof String || wanted instanceof Integer
                    || wanted instanceof Boolean ? List.of(new Equal(kind, column, wanted)) : List.of();
            default -> throw new IllegalStateException(kind + ": " + rule.predicate() + " needs a rule of its own");
        };
    }

    /** The families a request of {@code wanted} accepts ({@link ComponentFamily#compatible}), as a closed set. */
    private static List<FieldPredicate> family(ConstraintKind kind, IndexColumn column, String wanted) {
        if (wanted == null) {
            return List.of();
        }
        Set<String> accepted = new LinkedHashSet<>();
        accepted.add(wanted);
        FieldVocabulary.families().stream().filter(f -> ComponentFamily.compatible(wanted, f)).forEach(accepted::add);
        return List.of(new OneOf(kind, column, List.copyOf(accepted)));
    }

    private static List<FieldPredicate> packageIs(ConstraintKind kind, Indexed rule, String wanted) {
        if (wanted == null || wanted.isBlank()) {
            return List.of();
        }
        double[] can = FieldVocabulary.can(wanted);
        String wantedClass = FieldVocabulary.packageClass(wanted);
        String neutral = FieldVocabulary.LED_PACKAGE.equals(wantedClass) ? FieldVocabulary.PLCC_PACKAGE
                : FieldVocabulary.PLCC_PACKAGE.equals(wantedClass) ? FieldVocabulary.LED_PACKAGE : null;
        return List.of(new PackageIs(kind, FieldVocabulary.packageKey(wanted), can == null ? null : can[0],
                can == null ? null : can[1], Math.max(rule.margin(), FieldVocabulary.canTolerance()), neutral));
    }

    /** Width and length in either order: the sorted pair, each within the margin. */
    private static List<FieldPredicate> sortedSize(ConstraintKind kind, Indexed rule, List<IndexColumn> columns,
                                                   double width, double length) {
        double min = Math.min(width, length);
        double max = Math.max(width, length);
        return List.of(new Range(kind, columns.get(0), min - rule.margin(), min + rule.margin()),
                new Range(kind, columns.get(1), max - rule.margin(), max + rule.margin()));
    }

    /** The vocabulary values the comparator refuses for {@code wanted} ({@code refuses}); none when nothing. */
    private static List<FieldPredicate> refused(ConstraintKind kind, IndexColumn column, String wanted,
                                                List<String> vocabulary, Refuses refuses) {
        if (wanted == null) {
            return List.of();
        }
        List<String> out = vocabulary.stream().distinct().filter(v -> refuses.test(wanted, v)).toList();
        return out.isEmpty() ? List.of() : List.of(new NoneOf(kind, column, out));
    }

    /** True when a part of value {@code actual} contradicts a request for {@code wanted}. */
    @FunctionalInterface
    private interface Refuses {
        boolean test(String wanted, String actual);
    }

    private static double low(double value, Indexed rule) {
        return value - Math.abs(value) * rule.slack() - rule.margin() - EPSILON;
    }

    private static double high(double value, Indexed rule) {
        return value + Math.abs(value) * rule.slack() + rule.margin() + EPSILON;
    }

    private static Double number(Object value) {
        if (value instanceof ParsedQuery.Constraint c) {
            return c.value();
        }
        return value instanceof Number n ? n.doubleValue() : null;
    }
}
