package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.domain.ConstraintKind;
import ro.alacrity.kina.domain.PolicyFamily;
import ro.alacrity.kina.domain.RelaxStrategy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-family table of DESIGN.md 3.4 ("Hard constraints") is rendered from the {@code @Relax} declarations of
 * {@link ConstraintKind}, so the document and the code cannot drift. On a mismatch the message holds the table to
 * paste.
 */
class ConstraintTableDocumentationTest {

    private static final Path DESIGN = Path.of("docs/DESIGN.md");
    private static final String HEADER = "| Family | Hard (never relaxed) | Relaxable (ladder order) |";

    /** The effective default table: per family the hard kinds (check order) and the ladder kinds (ladder order). */
    static Map<String, List<List<String>>> rendered() {
        Map<String, List<List<String>>> table = new LinkedHashMap<>();
        for (PolicyFamily family : PolicyFamily.values()) {
            List<String> hard = ConstraintKind.policyKinds().stream()
                    .filter(k -> k.strategy(family) == RelaxStrategy.NEVER).map(ConstraintKind::label).toList();
            List<String> relaxable = ConstraintKind.ladder().stream()
                    .filter(k -> k.strategy(family) == RelaxStrategy.LADDER).map(ConstraintKind::label).toList();
            table.put(family.key(), List.of(hard, relaxable));
        }
        return table;
    }

    static String markdown(Map<String, List<List<String>>> table) {
        StringBuilder out = new StringBuilder(HEADER).append("\n|---|---|---|\n");
        table.forEach((family, columns) -> out.append("| `").append(family).append("` | ")
                .append(cell(columns.get(0))).append(" | ").append(cell(columns.get(1))).append(" |\n"));
        return out.toString();
    }

    private static String cell(List<String> names) {
        return names.isEmpty() ? "" : String.join(", ", names.stream().map(n -> "`" + n + "`").toList());
    }

    @Test
    void theDocumentedTableIsTheDeclaredOne() throws IOException {
        Map<String, List<List<String>>> rendered = rendered();
        assertThat(documented()).as("DESIGN.md 3.4 table; expected:%n%s", markdown(rendered)).isEqualTo(rendered);
    }

    @Test
    void theDeclaredTableIsTheEffectiveDefault() {
        rendered().forEach((family, columns) -> {
            assertThat(ConstraintPolicy.DEFAULTS.table().get(family)).as(family).isEqualTo(Set.copyOf(columns.get(0)));
            assertThat(ConstraintPolicy.DEFAULT_HARD.get(family)).as(family).isEqualTo(columns.get(0));
        });
    }

    private static Map<String, List<List<String>>> documented() throws IOException {
        List<String> lines = Files.readAllLines(DESIGN, StandardCharsets.UTF_8);
        int start = lines.indexOf(HEADER);
        assertThat(start).as("table header in DESIGN.md: %s", HEADER).isNotNegative();
        Map<String, List<List<String>>> table = new LinkedHashMap<>();
        for (int i = start + 2; i < lines.size() && lines.get(i).startsWith("|"); i++) {
            String[] cells = lines.get(i).split("\\|", -1);
            table.put(strip(cells[1]), List.of(names(cells[2]), names(cells[3])));
        }
        return table;
    }

    private static List<String> names(String cell) {
        String s = cell.strip();
        return s.isEmpty() ? List.of() : Arrays.stream(s.split(",")).map(ConstraintTableDocumentationTest::strip)
                .toList();
    }

    private static String strip(String s) {
        return s.strip().replace("`", "");
    }
}
