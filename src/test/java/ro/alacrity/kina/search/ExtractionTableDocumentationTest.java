package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ComponentFamily;
import ro.alacrity.kina.domain.PartAttribute;
import ro.alacrity.kina.domain.Source;
import ro.alacrity.kina.domain.Unit;
import ro.alacrity.kina.domain.ValueDisplay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The attribute source table of DESIGN.md 3.4 ("Attribute sources") is rendered from the {@code @Source} and
 * {@code @Unit} declarations of {@link PartAttribute}, so the document and the code cannot drift. On a mismatch the
 * table to paste is written to {@code target/extraction-table.md}.
 */
class ExtractionTableDocumentationTest {

    private static final Path DESIGN = Path.of("docs/DESIGN.md");
    private static final String HEADER = "| Attribute | Unit | Source (precedence order) | Applies to | Names |";

    /** One row per source; the attribute and its unit on its first row. */
    static List<String> rendered() {
        List<String> rows = new ArrayList<>();
        rows.add(HEADER);
        rows.add("|---|---|---|---|---|");
        for (PartAttribute a : PartAttribute.values()) {
            boolean first = true;
            for (Source s : a.sources()) {
                rows.add("| " + (first ? "`" + a.name() + "`" : "") + " | " + (first ? unit(a.unit()) : "") + " | "
                        + logic(s.logic()) + " | " + appliesTo(s) + " | " + names(s) + " |");
                first = false;
            }
        }
        return rows;
    }

    private static String unit(Unit unit) {
        if (unit == null) {
            return "";
        }
        StringBuilder out = new StringBuilder("`" + unit.base() + "`");
        if (unit.symbols().length > 0) {
            List<String> symbols = new ArrayList<>();
            for (int i = 0; i < unit.symbols().length; i++) {
                double factor = unit.factors().length > 0 ? unit.factors()[i] : 1;
                symbols.add("`" + unit.symbols()[i] + "`" + (factor == 1 ? "" : " ×" + ValueDisplay.format(factor)));
            }
            out.append(" from ").append(String.join(", ", symbols));
        }
        if (unit.families().length > 0) {
            out.append(" in ").append(code(Arrays.stream(unit.families()).map(ComponentFamily::label)
                    .toArray(String[]::new)));
        }
        if (unit.display() != ValueDisplay.Prefixed.class) {
            out.append(" (").append(unit.display().getSimpleName())
                    .append(unit.alternative().isEmpty() ? "" : ", `" + unit.alternative() + "`").append(")");
        }
        return out.toString();
    }

    private static String logic(Class<? extends AttributeLogic> logic) {
        return "`" + logic.getSimpleName() + "`";
    }

    private static String appliesTo(Source s) {
        List<String> parts = new ArrayList<>();
        if (s.distributors().length > 0) {
            parts.add(code(Arrays.stream(s.distributors()).map(Enum::name).toArray(String[]::new)));
        }
        if (s.families().length > 0) {
            parts.add(code(Arrays.stream(s.families()).map(ComponentFamily::label).toArray(String[]::new)));
        }
        for (ComponentFamily.Trait t : s.traits()) {
            parts.add("`" + t.name() + "`");
        }
        for (ComponentFamily.Trait t : s.exceptTraits()) {
            parts.add("not `" + t.name() + "`");
        }
        return String.join(", ", parts);
    }

    private static String names(Source s) {
        String names = code(s.names());
        return s.excluding().length == 0 ? names : names + "; not " + code(s.excluding());
    }

    private static String code(String[] values) {
        return Arrays.stream(values).map(v -> "`" + v + "`").collect(Collectors.joining(", "));
    }

    @Test
    void theDocumentedTableIsTheDeclaredOne() throws IOException {
        List<String> lines = Files.readAllLines(DESIGN, StandardCharsets.UTF_8);
        int start = lines.indexOf(HEADER);
        List<String> documented = new ArrayList<>();
        for (int i = Math.max(start, 0); start >= 0 && i < lines.size() && lines.get(i).startsWith("|"); i++) {
            documented.add(lines.get(i));
        }
        List<String> rendered = rendered();
        if (!documented.equals(rendered)) {
            Path out = Path.of("target/extraction-table.md");
            Files.createDirectories(out.getParent());
            Files.write(out, rendered, StandardCharsets.UTF_8);
        }
        assertThat(start).as("table header in DESIGN.md: %s", HEADER).isNotNegative();
        assertThat(documented).as("DESIGN.md 3.4 attribute source table (expected in target/extraction-table.md)")
                .isEqualTo(rendered);
    }
}
