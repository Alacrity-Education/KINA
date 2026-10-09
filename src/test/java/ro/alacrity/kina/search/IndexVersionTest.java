package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PartKey;
import ro.alacrity.kina.search.field.PartIndexRow;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The field index is versioned by {@link ParametricExtractor#INDEX_VERSION} (DESIGN.md 3.8): the re-index job rebuilds
 * the rows of an older version. This test hashes what the index is built from (the canonical attributes, the sorted
 * derived attribute names and the index row of every part of the evaluation set and of the recorded LED, switch and fan
 * searches) and fails when the hash is not {@link ParametricExtractor#INDEX_FINGERPRINT}: a change of the extraction
 * or of the index rows needs a version bump and the new fingerprint, set together.
 */
class IndexVersionTest {

    private static final Path DATASET = Path.of("docs/research/data/ranking-eval.jsonl");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final ParametricExtractor extractor = new ParametricExtractor();

    @Test
    void theExtractionMatchesTheFingerprintOfTheIndexVersion() throws Exception {
        String hash = fingerprint();
        assertThat(hash).as("the extraction or the index rows changed: bump ParametricExtractor.INDEX_VERSION (now %d)"
                        + " and set INDEX_FINGERPRINT to %s", ParametricExtractor.INDEX_VERSION, hash)
                .isEqualTo(ParametricExtractor.INDEX_FINGERPRINT);
    }

    private String fingerprint() throws IOException, URISyntaxException, NoSuchAlgorithmException {
        Map<String, Part> pool = new TreeMap<>();
        for (String line : Files.readAllLines(DATASET, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                for (JsonNode c : MAPPER.readTree(line).get("candidates")) {
                    Part part = MAPPER.treeToValue(c.get("part"), Part.class);
                    pool.putIfAbsent(PartKey.of(part), part);
                }
            }
        }
        for (String dir : List.of("leds", "switches", "fans")) {
            URL url = IndexVersionTest.class.getResource("/fixtures/" + dir);
            try (Stream<Path> files = Files.list(Path.of(url.toURI()))) {
                for (Path file : files.sorted().toList()) {
                    JsonNode root;
                    try (InputStream in = Files.newInputStream(file)) {
                        root = MAPPER.readTree(in);
                    }
                    root.get("parts").properties().forEach(e -> e.getValue().forEach(n -> {
                        Part part = MAPPER.treeToValue(n, Part.class);
                        pool.putIfAbsent(PartKey.of(part), part);
                    }));
                }
            }
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Map.Entry<String, Part> e : pool.entrySet()) {
            Part enriched = extractor.enrich(e.getValue());
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("part", e.getKey());
            record.put("extract", new TreeMap<>(extractor.extract(enriched)));
            // Part.derivedAttributes() iterates in a per-JVM order: sorted before hashing
            record.put("derived", new ArrayList<>(new java.util.TreeSet<>(enriched.derivedAttributes())));
            record.put("row", row(PartIndexRows.of(extractor, e.getValue(), true)));
            digest.update(MAPPER.writeValueAsString(record).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** The row with its maps in key order (they are unordered copies). */
    private static Map<String, Object> row(PartIndexRow row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", row.extractorVersion());
        out.put("family", row.family());
        out.put("familyPath", row.familyPath());
        out.put("subtype", row.subtype());
        out.put("polarity", row.polarity());
        out.put("packageKey", row.packageKey());
        out.put("packageReadable", row.packageReadable());
        out.put("packageClass", row.packageClass());
        out.put("can", List.of(String.valueOf(row.canDiameterMm()), String.valueOf(row.canLengthMm())));
        out.put("mounting", row.mounting());
        out.put("technology", row.technology());
        out.put("dielectric", row.dielectric());
        out.put("formFactor", row.formFactor());
        out.put("elements", row.elements());
        out.put("values", new TreeMap<>(row.values()));
        out.put("voltages", row.voltagesV());
        out.put("connector", List.of(String.valueOf(row.connectorType()), String.valueOf(row.gender()),
                String.valueOf(row.positions()), String.valueOf(row.rowsCount()), String.valueOf(row.pitchMm()),
                String.valueOf(row.orientation()), String.valueOf(row.usbType()), String.valueOf(row.usbClass()),
                String.valueOf(row.pinConfiguration())));
        out.put("attrs", new TreeMap<>(row.attrs()));
        out.put("mpn", row.mpn());
        out.put("searchText", row.searchText());
        return out;
    }
}
