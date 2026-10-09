package ro.alacrity.kina.metrics;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The metrics table of DESIGN.md 3.7 is the {@link Metric} declarations: every meter once, with its Prometheus name,
 * its type and its tag keys, and nothing else.
 */
class MetricDocumentationTest {

    private static final Path DESIGN = Path.of("docs/DESIGN.md");
    private static final String HEADER = "| Metric | Type | Tags | Meaning |";
    private static final Pattern CODE = Pattern.compile("`([^`]+)`");

    @Test
    void theDocumentedTableIsTheDeclaredOne() throws IOException {
        Map<String, String> declared = new LinkedHashMap<>();
        Arrays.stream(Metric.values()).forEach(m -> declared.put(m.prometheusName(),
                m.type().name().toLowerCase(Locale.ROOT) + " " + m.tags()));
        assertThat(documented()).containsExactlyInAnyOrderEntriesOf(declared);
    }

    /**
     * A tag with a closed set of values ({@link Metric#tagValues()}, such as the {@code reason} of
     * {@code kina_field_fallbacks_total}): the help text lists every value and the DESIGN.md row names each one.
     */
    @Test
    void theDeclaredTagValuesAreInTheHelpTextAndTheDocumentedRow() throws IOException {
        List<String> lines = Files.readAllLines(DESIGN, StandardCharsets.UTF_8);
        for (Metric metric : Metric.values()) {
            Metric.TagValues values = metric.tagValues();
            if (values == null) {
                continue;
            }
            String row = lines.stream().filter(l -> l.startsWith("| `" + metric.prometheusName() + "`")).findFirst()
                    .orElseThrow();
            String meaning = row.split("\\|", -1)[4];
            List<String> documented = CODE.matcher(meaning).results().map(r -> r.group(1)).toList();
            assertThat(documented).as("values of %s in DESIGN.md", metric.prometheusName())
                    .containsAll(values.values());
            values.values().forEach(v -> assertThat(metric.help()).as("help of %s", metric).contains(v));
        }
        assertThat(Metric.FIELD_FALLBACKS.tagValues().values())
                .containsExactly("mode", "bypass", "incomplete", "generic", "sql_error");
    }

    @Test
    void aKeyTakesOneValuePerDeclaredTag() {
        assertThat(Metric.DISTRIBUTOR_CALLS.key("MOUSER", "ok", "capacitor"))
                .isEqualTo(MetricKey.of("kina.distributor.calls", "distributor", "MOUSER", "outcome", "ok", "type",
                        "capacitor"));
        assertThat(Metric.SEARCHES.key()).isEqualTo(MetricKey.of("kina.searches"));
        assertThatThrownBy(() -> Metric.TOOL_CALLS.key()).isInstanceOf(IllegalArgumentException.class);
        assertThat(Metric.help("kina.searches")).isEqualTo(Metric.SEARCHES.help());
        assertThat(Metric.help("kina.unknown")).isNull();
    }

    /** Prometheus name -&gt; "type [tag, ...]" from the DESIGN.md table. */
    private static Map<String, String> documented() throws IOException {
        List<String> lines = Files.readAllLines(DESIGN, StandardCharsets.UTF_8);
        int start = lines.indexOf(HEADER);
        assertThat(start).as("metrics table header in DESIGN.md: %s", HEADER).isNotNegative();
        Map<String, String> table = new LinkedHashMap<>();
        for (int i = start + 2; i < lines.size() && lines.get(i).startsWith("|"); i++) {
            String[] cells = lines.get(i).split("\\|", -1);
            Matcher name = CODE.matcher(cells[1]);
            assertThat(name.find()).as(lines.get(i)).isTrue();
            String type = cells[2].strip().split("\\s+")[0];
            List<String> tags = CODE.matcher(cells[3]).results().map(r -> r.group(1)).toList();
            table.put(name.group(1), type + " " + tags);
        }
        return table;
    }
}
