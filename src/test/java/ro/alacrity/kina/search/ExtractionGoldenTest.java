package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PartKey;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden master of the parametric extraction: {@link ParametricExtractor#features}, {@link ParametricExtractor#extract}
 * and {@link ParametricExtractor#enrich}, captured on the tree before the declarative extraction refactor (0.6.0,
 * 8dd48f5). It covers every part of the ranking evaluation set (one readable record each), and synthetic probe parts
 * that carry every distributor attribute name the extractor knew then ({@code golden/extraction-names.txt}, a frozen
 * copy): each name alone with typical values in every family context (features, canonical attributes, enriched
 * part), and every ordered pair of names with two different values in five contexts (the features: the precedence
 * between names and lists). The probe records are summarised per attribute name by a SHA-256 digest; the probes run
 * in parallel (the extractor is stateless).
 *
 * <p>On a mismatch the current dump is written to {@code target/golden/extraction-actual.jsonl}. To recapture (only
 * for an intended behaviour change), run with {@code -Dkina.golden.write=true}.
 */
class ExtractionGoldenTest {

    private static final Path DATASET = Path.of("docs/research/data/ranking-eval.jsonl");
    private static final String RESOURCE = "/golden/extraction.jsonl";
    private static final Path SOURCE = Path.of("src/test/resources/golden/extraction.jsonl");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Instant FETCHED = Instant.parse("2026-10-05T00:00:00Z");

    /** Family contexts of the probes: category, description, package field. */
    private static final List<List<String>> CONTEXTS = List.of(
            List.of("", "Part", ""),
            List.of("SMD resistors", "Resistor: thick film; SMD; 0805; 10kΩ", "0805"),
            List.of("Aluminium electrolytic capacitors", "Capacitor: electrolytic; 100uF; 25V", ""),
            List.of("SMD inductors", "Inductor: wire; SMD; 10uH", ""),
            List.of("Ferrite beads", "Ferrite: bead; 120Ω", "0603"),
            List.of("Quartz resonators", "Resonator: quartz; 16MHz", ""),
            List.of("Zener diodes", "Diode: Zener; 5.1V", "SOD-123"),
            List.of("Voltage regulators - LDO", "IC: voltage regulator; LDO; fixed; 3.3V", ""),
            List.of("N channel transistors", "Transistor: N-MOSFET; 60V; 5A", ""),
            List.of("Pin headers", "Connector: pin strips; pin header", ""),
            List.of("USB & IEEE1394 connectors", "Connector: USB C; socket", ""),
            List.of("Resistor networks", "Resistor network; 0603", ""));

    /** Values of the single-name probes. */
    private static final List<String> VALUES = List.of("10uF", "4.7kOhm", "25V", "2A", "250mW", "0.25kW", "10uH",
            "16MHz", "±5%", "X7R", "0805", "2012", "SMD", "THT", "thin film", "2000h @105°C", "5000 Hours",
            "-55...125°C", "+ 105 C", "120ohm @100MHz", "28mohm", "2x5", "1x6", "2.54mm", "0.1 in", "female", "Socket",
            "angled 90°", "vertical", "XH", "6", "24", "USB 3.1 Gen 2", "5Gbps", "IP67", "middle board mount",
            "3.2x2.5x0.8mm", "Ø6.3x5.8mm", "6.3mm", "array", "4", "N-MOSFET", "AEC-Q200", "low ESR", "only for charging");

    /** The two values of the pair probes: every kind once, with different numbers and words. */
    private static final String FIRST = "10uF 25V 2A 100mW 4.7kohm 10uH 16MHz 5% X7R 0805 SMD thin film 2000h @105°C "
            + "-55...125°C 2x5 2.54mm female angled 90° XH 6 pins USB 3.1 Gen 2 IP67 3.2x2.5x0.8mm 120ohm @100MHz";
    private static final String SECOND = "22uF 50V 3A 250mW 10kohm 22uH 25MHz 10% C0G 1206 THT thick film 5000h @85°C "
            + "-40...85°C 1x8 2.0mm male vertical PH 8 pins USB 2.0 IP68 5.0x3.2x1.0mm 600ohm @1GHz";
    /** Contexts of the pair probes (indexes into {@link #CONTEXTS}). */
    private static final List<Integer> PAIR_CONTEXTS = List.of(0, 2, 4, 7, 10);

    private final ParametricExtractor extractor = new ParametricExtractor();

    @Test
    void extractionMatchesTheGoldenMaster() throws Exception {
        List<String> actual = dump();
        if (Boolean.getBoolean("kina.golden.write")) {
            Files.createDirectories(SOURCE.getParent());
            Files.write(SOURCE, actual, StandardCharsets.UTF_8);
            return;
        }
        List<String> expected;
        try (InputStream in = ExtractionGoldenTest.class.getResourceAsStream(RESOURCE)) {
            assertThat(in).as("golden resource %s (capture with -Dkina.golden.write=true)", RESOURCE).isNotNull();
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
        if (!expected.equals(actual)) {
            Path out = Path.of("target/golden/extraction-actual.jsonl");
            Files.createDirectories(out.getParent());
            Files.write(out, actual, StandardCharsets.UTF_8);
        }
        assertThat(actual).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            assertThat(MAPPER.readTree(actual.get(i))).as("line %d", i + 1).isEqualTo(MAPPER.readTree(expected.get(i)));
        }
    }

    private List<String> dump() throws IOException {
        Map<String, Part> pool = new TreeMap<>();
        for (String line : Files.readAllLines(DATASET, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            for (JsonNode c : MAPPER.readTree(line).get("candidates")) {
                Part part = MAPPER.treeToValue(c.get("part"), Part.class);
                pool.putIfAbsent(PartKey.of(part), part);
            }
        }
        List<String> out = new ArrayList<>();
        pool.forEach((key, part) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("part", key);
            r.putAll(record(part));
            out.add(MAPPER.writeValueAsString(r));
        });

        List<String> names;
        try (InputStream in = ExtractionGoldenTest.class.getResourceAsStream("/golden/extraction-names.txt")) {
            assertThat(in).isNotNull();
            names = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(s -> !s.isBlank()).toList();
        }
        // the probes are independent: computed in parallel, kept in name order
        out.addAll(names.parallelStream().map(name -> probeLine(name, names)).toList());
        return out;
    }

    /** The digest line of one attribute name: its single-name probes and its pair probes. */
    private String probeLine(String name, List<String> names) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        int probes = 0;
        for (int c = 0; c < CONTEXTS.size(); c++) {
            for (String value : VALUES) {
                Part part = probe(c, Map.of(name, value));
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("features", features(extractor.features(part)));
                r.put("extract", pairs(extractor.extract(part)));
                Part enriched = extractor.enrich(part);
                r.put("enriched", pairs(enriched.attributes()));
                r.put("derived", enriched.derivedAttributes().stream().sorted().toList());
                probes += digest(digest, r);
            }
        }
        for (int c : PAIR_CONTEXTS) {
            for (String other : names) {
                if (!other.equals(name)) {
                    Map<String, String> attrs = new LinkedHashMap<>();
                    attrs.put(name, FIRST);
                    attrs.put(other, SECOND);
                    probes += digest(digest, features(extractor.features(probe(c, attrs))));
                }
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", name);
        r.put("probes", probes);
        r.put("sha256", HexFormat.of().formatHex(digest.digest()));
        return MAPPER.writeValueAsString(r);
    }

    private static int digest(MessageDigest digest, Object record) {
        digest.update(MAPPER.writeValueAsString(record).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
        return 1;
    }

    private static Part probe(int context, Map<String, String> attributes) {
        List<String> c = CONTEXTS.get(context);
        return new Part(Distributor.TME, "PROBE", "Probe", "PRB-1", c.get(1), c.get(0).isEmpty() ? null : c.get(0),
                c.get(2).isEmpty() ? null : c.get(2), 10, 1, 1, List.of(), null, null, null, attributes, Map.of(),
                FETCHED);
    }

    /** Features, canonical attributes and the enriched part, in a stable form. */
    private Map<String, Object> record(Part part) {
        Map<String, Object> r = new LinkedHashMap<>();
        ParametricExtractor.Features f = extractor.features(part);
        r.put("features", features(f));
        r.put("extract", pairs(extractor.extract(part)));
        Part enriched = extractor.enrich(part);
        r.put("enriched", pairs(enriched.attributes()));
        r.put("derived", enriched.derivedAttributes().stream().sorted().toList());
        Map<String, Object> again = features(extractor.features(enriched));
        r.put("enriched_features", again.equals(features(f)) ? "same" : again);
        return r;
    }

    private static List<List<String>> pairs(Map<String, String> map) {
        List<List<String>> out = new ArrayList<>();
        map.forEach((k, v) -> out.add(java.util.Arrays.asList(k, v)));
        return out;
    }

    private static Map<String, Object> features(ParametricExtractor.Features f) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("family", f.family());
        Map<String, Object> values = new TreeMap<>();
        f.values().forEach((kind, v) -> values.put(kind, List.of(v.kind(), Double.toString(v.value()), v.display(),
                String.valueOf(v.condition()))));
        r.put("values", values);
        r.put("dielectric", f.dielectric());
        r.put("package", f.packageName());
        r.put("mounting", f.mounting());
        r.put("text", f.text());
        r.put("connector", f.connector() == null ? null : f.connector().toString());
        r.put("technology", f.technology());
        r.put("elements", f.elements());
        r.put("details", pairs(f.details()));
        r.put("polarity", f.polarity());
        r.put("subtype", f.subtype());
        r.put("voltages", f.voltages().stream().map(d -> Double.toString(d)).toList());
        r.put("form_factor", f.formFactor());
        return r;
    }
}
