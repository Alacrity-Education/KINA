package ro.alacrity.kina.search.field;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.domain.ConstraintKind;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Indexed;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.search.ConstraintPolicy;
import ro.alacrity.kina.search.QueryParser;
import ro.alacrity.kina.search.field.FieldPredicate.AtLeast;
import ro.alacrity.kina.search.field.FieldPredicate.AtMost;
import ro.alacrity.kina.search.field.FieldPredicate.Equal;
import ro.alacrity.kina.search.field.FieldPredicate.NoneOf;
import ro.alacrity.kina.search.field.FieldPredicate.OneOf;
import ro.alacrity.kina.search.field.FieldPredicate.PackageIs;
import ro.alacrity.kina.search.field.FieldPredicate.Range;
import ro.alacrity.kina.search.field.FieldQuery.Role;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The field query of a request (DESIGN.md 3.8; the worked examples of the study, section 4.5). */
class FieldQueryBuilderTest {

    private final QueryParser parser = new QueryParser();

    private FieldQuery build(String text) {
        return FieldQueryBuilder.build(parser.parse(text), ConstraintPolicy.DEFAULTS, Distributor.MOUSER);
    }

    private static <T extends FieldPredicate> T predicate(FieldQuery q, String group, ConstraintKind kind,
                                                          Class<T> type) {
        return q.group(group).predicates().stream().filter(p -> p.kind() == kind).filter(type::isInstance)
                .map(type::cast).findFirst().orElseThrow(() -> new AssertionError(kind + " in " + group));
    }

    @Test
    void anMlccRequest() {
        FieldQuery q = build("10uF X7R 0805 25V");
        assertThat(q.groups()).extracting(FieldQuery.Group::name).containsExactly("H", "R", "L1");
        assertThat(predicate(q, "H", ConstraintKind.TYPE, OneOf.class).values()).contains("capacitor");
        Range value = predicate(q, "H", ConstraintKind.VALUE, Range.class);
        assertThat(value.column().name()).isEqualTo("capacitance_f");
        assertThat(value.low()).isCloseTo(10e-6 * 0.985, within(1e-12));
        assertThat(value.high()).isCloseTo(10e-6 * 1.015, within(1e-12));
        assertThat(predicate(q, "H", ConstraintKind.PACKAGE, PackageIs.class).key()).isEqualTo("0805");
        assertThat(q.group("H").kinds()).contains(ConstraintKind.ELEMENTS);
        AtLeast voltage = predicate(q, "R", ConstraintKind.VOLTAGE_RATING, AtLeast.class);
        assertThat(voltage.column().name()).isEqualTo("voltage_v");
        assertThat(voltage.min()).isCloseTo(25, within(1e-4)).isLessThan(25);
        assertThat(predicate(q, "L1", ConstraintKind.DIELECTRIC, Equal.class).value()).isEqualTo("x7r");
        assertThat(q.steps()).hasSize(2);
        assertThat(q.relaxed().relaxed()).containsExactly("dielectric");

        // ratings never filter (the Java check excludes and counts a part below spec): they only order
        assertThat(q.ratings()).containsExactly(voltage);
        assertThat(q.steps()).allSatisfy(step -> assertThat(step.predicates())
                .noneMatch(p -> p.kind() == ConstraintKind.VOLTAGE_RATING));
        assertThat(q.relaxed().predicates()).containsExactlyElementsOf(q.group("H").predicates());
    }

    @Test
    void ratingsOrderTheCandidatesInBothDialects() {
        FieldQuery q = build("electrolytic capacitor 470uF 35V 105°C 5000h THT");
        assertThat(q.group("R").kinds()).contains(ConstraintKind.VOLTAGE_RATING, ConstraintKind.TEMPERATURE,
                ConstraintKind.LIFETIME);
        for (FieldSql dialect : List.of(PostgresFieldSql.INSTANCE, SqliteFieldSql.INSTANCE)) {
            String sql = dialect.select(q, q.step(0), 40).sql();
            String where = sql.substring(sql.indexOf(" WHERE "), sql.indexOf(" ORDER BY "));
            assertThat(where).doesNotContain("voltage_v").doesNotContain("max_temp_c").doesNotContain("lifetime_h");
            String order = sql.substring(sql.indexOf(" ORDER BY "));
            if (dialect == PostgresFieldSql.INSTANCE) {
                // met (2) before not stated (1) before below spec (0), ahead of the confirmed flag
                assertThat(order).contains("THEN 2 WHEN").contains("voltage_v").contains("lifetime_h");
                assertThat(order.indexOf("voltage_v")).isLessThan(order.indexOf("confirmed DESC"));
            } else {
                // LCSC: a part that states the ratings counts as confirmed, below spec or not (FTS-like window)
                assertThat(sql.substring(0, sql.indexOf(" FROM "))).contains("voltage_v");
            }
        }
        // the confirmed-only SQLite form selects the first tier of its order: ratings stated, never compared
        FieldSql.Statement confirmed = SqliteFieldSql.CONFIRMED.count(q, q.step(0));
        assertThat(confirmed.sql()).contains("NOT voltage_v IS NULL").contains("NOT lifetime_h IS NULL")
                .doesNotContain("voltage_v >=");
        assertThat(SqliteFieldSql.INSTANCE.count(q, q.step(0)).sql()).doesNotContain("voltage_v");
    }

    /**
     * The ranges are relative: the writer's rounding to 9 significant digits widens a bound by a share of the value
     * ({@code Indexed.ROUNDING_SLACK}), never by an absolute amount that is the whole of a 1 pF value (review A7).
     */
    @Test
    void rangesAreRelativeForPicofaradsAndMegohms() {
        Range pf = predicate(build("1pF C0G 0402 capacitor"), "H", ConstraintKind.VALUE, Range.class);
        assertThat(pf.column().name()).isEqualTo("capacitance_f");
        assertThat(pf.low()).isCloseTo(1e-12 * (1 - 0.015 - 1e-8), within(1e-24)).isGreaterThan(0.98e-12);
        assertThat(pf.high()).isCloseTo(1e-12 * (1 + 0.015 + 1e-8), within(1e-24)).isLessThan(1.02e-12);
        Range mohm = predicate(build("10Mohm 1% 0603 resistor"), "H", ConstraintKind.VALUE, Range.class);
        assertThat(mohm.column().name()).isEqualTo("resistance_ohm");
        assertThat(mohm.low()).isCloseTo(10e6 * (1 - 0.015 - 1e-8), within(1e-6));
        assertThat(mohm.high()).isCloseTo(10e6 * (1 + 0.015 + 1e-8), within(1e-6));
        // a part at the edge of the Java tolerance (1 %) stays inside after the writer's rounding
        for (Range r : List.of(pf, mohm)) {
            double center = (r.low() + r.high()) / 2;
            for (double edge : List.of(center * 0.99, center * 1.01)) {
                double stored = ro.alacrity.kina.search.FieldVocabulary.round(edge);
                assertThat(stored).isBetween(r.low(), r.high());
            }
        }
    }

    /**
     * The builder dispatches on the declared predicate of every kind (review A4): each predicate it emits is the form of
     * its kind's {@code @Indexed} predicate, and every predicate form is reached by some request.
     */
    @Test
    void everyPredicateIsTheFormOfItsDeclaration() {
        List<String> queries = List.of("10uF X7R 0805 25V", "4.7k 1% 0603 resistor thin film", "Zener 3.3V SOD-123",
                "ferrite bead 600 ohm 100MHz 0603", "USB-C receptacle 16 pin SMD USB 2.0",
                "1x6 female header 2.54mm right angle THT", "40x40x10 fan 12V 3000rpm ball bearing",
                "red LED 0603 20mA", "SPDT toggle switch solder lug illuminated", "6x6mm tactile switch SMD",
                "chassis mount resistor 50W", "rotary switch 4 position", "LED 5mm warm white 3000K 120 degree",
                "100uF 25V electrolytic D6.3x7.7mm");
        java.util.Set<Indexed.Predicate> reached = java.util.EnumSet.noneOf(Indexed.Predicate.class);
        for (String text : queries) {
            for (FieldQuery.Group group : build(text).groups()) {
                for (FieldPredicate p : group.predicates()) {
                    if (p.kind() == null) {
                        continue;
                    }
                    Indexed.Predicate declared = p.kind().indexed().predicate();
                    reached.add(declared);
                    assertThat(form(declared)).as("%s: %s", text, p).contains(p.getClass());
                }
            }
        }
        assertThat(reached).containsAll(java.util.EnumSet.complementOf(java.util.EnumSet.of(Indexed.Predicate.NONE)));
    }

    private static List<Class<? extends FieldPredicate>> form(Indexed.Predicate predicate) {
        return switch (predicate) {
            case RANGE -> List.of(Range.class);
            case GTE -> List.of(AtLeast.class);
            case LTE -> List.of(AtMost.class);
            case EQUAL, JSONB_CONTAINS -> List.of(Equal.class);
            case IN_COMPATIBLE -> List.of(OneOf.class, NoneOf.class);
            case ARRAY_ANY -> List.of(FieldPredicate.AnyInRange.class);
            case PACKAGE -> List.of(PackageIs.class);
            case ABSENT -> List.of(FieldPredicate.Absent.class);
            case NONE -> List.of();
        };
    }

    /** Review A11: the declared package margin is the one applied, and it covers the can tolerance of the check. */
    @Test
    void theDeclaredPackageMarginCoversTheCanTolerance() {
        double margin = ConstraintKind.PACKAGE.indexed().margin();
        assertThat(margin).isGreaterThanOrEqualTo(ro.alacrity.kina.search.FieldVocabulary.canTolerance());
        assertThat(predicate(build("100uF 25V electrolytic D6.3x7.7mm"), "H", ConstraintKind.PACKAGE, PackageIs.class)
                .margin()).isEqualTo(margin);
    }

    @Test
    void anImpedanceComparesItsTestFrequencyThroughTheDeclaredConditionColumn() {
        FieldQuery q = build("ferrite bead 600 ohm 100MHz 0603");
        List<Range> ranges = q.group("H").predicates().stream().filter(p -> p.kind() == ConstraintKind.VALUE)
                .map(Range.class::cast).toList();
        assertThat(ranges).extracting(r -> r.column().name()).containsExactly("impedance_ohm", "impedance_test_hz");
        assertThat(ranges.get(1).low()).isCloseTo(100e6 * 0.985, within(1.0));
    }

    @Test
    void aThinFilmResistor() {
        FieldQuery q = build("4.7k 1% 0603 resistor thin film");
        assertThat(predicate(q, "H", ConstraintKind.VALUE, Range.class).column().name()).isEqualTo("resistance_ohm");
        NoneOf technology = predicate(q, "H", ConstraintKind.TECHNOLOGY, NoneOf.class);
        assertThat(technology.values()).contains("thick film").doesNotContain("thin film");
        AtMost tolerance = predicate(q, q.groups(Role.L).stream()
                .filter(g -> g.kinds().contains(ConstraintKind.TOLERANCE)).findFirst().orElseThrow().name(),
                ConstraintKind.TOLERANCE, AtMost.class);
        assertThat(tolerance.max()).isCloseTo(1, within(1e-5)).isGreaterThan(1);
    }

    @Test
    void aUsbReceptacle() {
        FieldQuery q = build("USB-C receptacle 16 pin SMD USB 2.0");
        assertThat(predicate(q, "H", ConstraintKind.USB_TYPE, Equal.class).value()).isEqualTo("Type-C");
        assertThat(predicate(q, "H", ConstraintKind.PIN_CONFIGURATION, Equal.class).value()).isEqualTo(16);
        assertThat(predicate(q, "H", ConstraintKind.USB_STANDARD, AtLeast.class).min()).isEqualTo(1);
        assertThat(predicate(q, "H", ConstraintKind.MOUNTING, Equal.class).value()).isEqualTo("SMD");
        assertThat(predicate(q, "H", ConstraintKind.GENDER, Equal.class).value()).isEqualTo("female");
    }

    @Test
    void freeTextIsDroppedFirst() {
        FieldQuery q = build("low-noise op amp for audio, SOIC-8");
        assertThat(q.group("K")).isNotNull();
        assertThat(q.steps().get(1).dropped()).containsExactly("K");
        assertThat(q.group("K").predicates()).allMatch(p -> p.kind() == null);
    }

    @Test
    void everyStatementComparesBareColumnsAndBindsEveryParameter() throws IOException {
        JsonMapper mapper = JsonMapper.builder().build();
        for (String line : Files.readAllLines(Path.of("docs/research/data/ranking-eval.jsonl"),
                StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            ParsedQuery parsed = parser.parse(mapper.readTree(line).get("query").asString());
            FieldQuery q = FieldQueryBuilder.build(parsed, ConstraintPolicy.DEFAULTS, null).withStaleBelow(1);
            for (FieldQuery.Step step : q.steps()) {
                List<FieldSql.Statement> statements = List.of(PostgresFieldSql.INSTANCE.select(q, step, 40),
                        SqliteFieldSql.INSTANCE.select(q, step, 40), SqliteFieldSql.INSTANCE.candidates(q, step, 40),
                        SqliteFieldSql.CONFIRMED.candidates(q, step, 40), SqliteFieldSql.CONFIRMED.count(q, step));
                for (FieldSql.Statement s : statements) {
                    assertThat(s.sql()).doesNotContainIgnoringCase("abs(");
                    long placeholders = s.sql().chars().filter(c -> c == '?').count();
                    assertThat(s.params()).as(s.sql()).hasSize((int) placeholders);
                    assertThat(s.sql()).doesNotContain("stock_fetched_at");
                }
            }
        }
    }
}
