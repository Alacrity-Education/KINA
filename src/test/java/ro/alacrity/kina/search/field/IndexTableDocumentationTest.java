package ro.alacrity.kina.search.field;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.domain.ConstraintKind;
import ro.alacrity.kina.domain.Indexed;
import ro.alacrity.kina.domain.RelaxStrategy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The field index rules of DESIGN.md 3.8 ("Field index rules") are rendered from the {@link Indexed} declarations of
 * {@link ConstraintKind}, so the document and the code cannot drift; and every kind that can exclude a part (hard for
 * some family, a rating, or on the ladder) declares a rule or is explicitly Java only. On a mismatch the message holds
 * the table to paste.
 */
class IndexTableDocumentationTest {

    private static final Path DESIGN = Path.of("docs/DESIGN.md");
    private static final String HEADER = "| Kind | Column | Type | Rule (NULL always kept) |";

    static List<String> rendered() {
        List<String> rows = new ArrayList<>();
        for (ConstraintKind kind : ConstraintKind.values()) {
            Indexed indexed = kind.indexed();
            if (indexed == null) {
                continue;
            }
            if (indexed.javaOnly()) {
                rows.add("| `" + kind.name() + "` | | | Java only |");
                continue;
            }
            List<IndexColumn> columns = IndexColumn.all(kind);
            String names = columns.stream().map(c -> "`" + c.display() + "`").collect(Collectors.joining(", "));
            String type = columns.stream().map(c -> c.type().name().toLowerCase(Locale.ROOT)).distinct()
                    .collect(Collectors.joining(", "));
            rows.add("| `" + kind.name() + "` | " + names + " | " + type + " | " + rule(indexed) + " |");
        }
        return rows;
    }

    private static String rule(Indexed indexed) {
        StringBuilder out = new StringBuilder(indexed.predicate().name());
        if (indexed.slack() != 0) {
            out.append(", slack ").append(number(indexed.slack()));
        }
        if (indexed.margin() != 0) {
            out.append(", margin ").append(number(indexed.margin()));
        }
        return out.toString();
    }

    private static String number(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    @Test
    void theDocumentedTableIsTheDeclaredOne() throws IOException {
        List<String> rendered = rendered();
        assertThat(documented()).as("DESIGN.md 3.8 table; expected:%n%s%n|---|---|---|---|%n%s", HEADER,
                String.join("\n", rendered)).isEqualTo(rendered);
    }

    @Test
    void everyKindThatCanExcludeAPartHasARuleOrIsJavaOnly() {
        for (ConstraintKind kind : ConstraintKind.values()) {
            boolean excludes = kind.relaxDeclarations().stream().anyMatch(r -> r.strategy() == RelaxStrategy.NEVER
                    || r.strategy() == RelaxStrategy.LADDER || r.strategy() == RelaxStrategy.BELOW_SPEC);
            if (excludes) {
                assertThat(kind.indexed()).as("%s needs @Indexed (a rule or javaOnly)", kind).isNotNull();
            }
            Indexed indexed = kind.indexed();
            if (indexed != null) {
                assertThat(indexed.vocabulary() != Indexed.Vocabulary.NONE).as("%s: vocabulary", kind)
                        .isEqualTo(indexed.predicate() == Indexed.Predicate.IN_COMPATIBLE);
                assertThat(ro.alacrity.kina.search.FieldVocabulary.vocabulary(indexed.vocabulary()).isEmpty())
                        .as("%s: a declared vocabulary has values", kind)
                        .isEqualTo(indexed.vocabulary() == Indexed.Vocabulary.NONE);
                if (!indexed.javaOnly()) {
                    assertThat(IndexColumn.all(kind)).as("%s resolves to a column", kind).isNotEmpty();
                }
            }
        }
    }

    @Test
    void preferencesNeverReachSqlAndSoftKindsNeverFilter() {
        for (ConstraintKind kind : ConstraintKind.values()) {
            boolean preference = kind.relaxDeclarations().stream()
                    .allMatch(r -> r.strategy() == RelaxStrategy.PREFERENCE);
            if (preference) {
                assertThat(kind.indexed()).as(kind.name()).isNull();
            }
        }
        // a soft kind may declare a rule (ROWS): it only orders the candidates (FieldQuery.soft), never in a step
        FieldQuery query = FieldQueryBuilder.build(new ro.alacrity.kina.search.QueryParser().parse(
                "female header 1x6 right angle"), ro.alacrity.kina.search.ConstraintPolicy.DEFAULTS, null);
        assertThat(query.groups(FieldQuery.Role.S)).flatExtracting(FieldQuery.Group::kinds)
                .contains(ConstraintKind.ROWS);
        for (FieldQuery.Step step : query.steps()) {
            assertThat(step.predicates()).noneMatch(p -> p.kind() == ConstraintKind.ROWS);
        }
    }

    private static List<String> documented() throws IOException {
        List<String> lines = Files.readAllLines(DESIGN, StandardCharsets.UTF_8);
        int start = lines.indexOf(HEADER);
        assertThat(start).as("table header in DESIGN.md: %s", HEADER).isNotNegative();
        List<String> rows = new ArrayList<>();
        for (int i = start + 2; i < lines.size() && lines.get(i).startsWith("|"); i++) {
            rows.add(lines.get(i));
        }
        return rows;
    }
}
