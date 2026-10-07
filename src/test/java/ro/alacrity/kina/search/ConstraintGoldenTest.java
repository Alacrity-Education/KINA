package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.TestWiring;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden master of the constraint model: scores, match grades, mismatches, unverified and below-spec lists, the
 * hard-constraint check under several policies, the stated hard constraints, hints, relaxation ladders and the policy
 * tables, captured on the tree before the declarative refactor (0.5.0, f3c5d81). Every query (the ranking evaluation
 * set and the queries of the search tests) is scored against every part of the evaluation set; the full records are
 * summarised per query by a SHA-256 digest, and a sample is kept readable.
 *
 * <p>The master covers the vocabulary it was captured with: the policy families and constraint names of
 * {@link #CAPTURED_FAMILIES} and {@link #CAPTURED_NAMES}. Families and names added later (fans, 0.13) are left out of
 * the tables and name lists here and covered by their own tests ({@code FanParserTest}, {@code FanExtractionTest},
 * {@code FanRankingTest}); every query and part of the master must still give the same results. The one exception
 * is {@link #REMODELLED_QUERIES}: the queries of a family whose request vocabulary was modelled after the capture (an
 * LED request has had a colour, an LED type and a policy row of its own since 0.13). Their records (the query line and
 * its sampled pairs) are left out of the comparison and covered by the family's own tests ({@code LedParserTest},
 * {@code LedRankingTest}); a query that changes because of a new family word still fails.
 *
 * <p>Scores are compared as {@link Double#toString} strings, so they must stay bit-identical. On a mismatch the
 * current dump is written to {@code target/golden/constraints-actual.jsonl}. To recapture (only for an intended
 * behaviour change), run with {@code -Dkina.golden.write=true}.
 */
class ConstraintGoldenTest {

    private static final Path DATASET = Path.of("docs/research/data/ranking-eval.jsonl");
    private static final String RESOURCE = "/golden/constraints.jsonl";
    private static final Path SOURCE = Path.of("src/test/resources/golden/constraints.jsonl");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Queries beyond the evaluation set and the search tests: every family and constraint kind at least once. */
    private static final List<String> EXTRA = List.of(
            "16MHz crystal 18pF 3225 SMD", "16MHz crystal 12pF 2520", "LDO 5V SOT-23-5", "adjustable regulator SOT-223",
            "P-channel MOSFET SOT-23", "TVS diode 5V SOD-323", "LED 0603 red", "1x6 male pin header vertical 2.54mm THT",
            "2x3 pin header 2.54mm SMD", "terminal block 2 position 5.08mm", "USB-C receptacle 16 pin hybrid",
            "USB-C receptacle mid-mount waterproof", "micro USB B receptacle 5 pin SMD", "USB Type-A receptacle THT",
            "USB-C power only 6 pin", "USB-C receptacle 16 pin right angle", "USB-C receptacle vertical",
            "resistor 10k 0603 TCR 25ppm", "100uF 25V low ESR", "10k 0603 1% 100ppm thin film", "fuse 500mA 0603",
            "ferrite bead 600 ohm 100MHz 0603 2A DCR < 0.1 ohm", "10uF 25V X5R 0805 ±20%", "1uF 50V 0603 C0G",
            "RJ45 jack", "IDC socket 2x5", "FPC 0.5mm 24 pin", "D-SUB 9 female", "barrel jack 2.1mm",
            "100 ohm 1W 2512 resistor", "16MHz oscillator 3.3V", "1N5819", "BC547 NPN TO-92", "Zener 3.3V 500mW SOD-123",
            "electrolytic capacitor 470uF 35V 105°C 5000h THT", "inductor 10uH 1A shielded THT",
            "10uH inductor 1210 Isat 2A DCR < 100mOhm low DCR", "female header 2x10 2.54mm vertical SMD",
            "Schottky 40V 1A SOD-123", "zener 12V", "regulator 3.3V 1A", "crystal 32.768kHz 12.5pF",
            "1k resistor array 0603 4 elements", "10nF capacitor array 0603", "100nF 16V X7R 0402 5%",
            "MOSFET N-channel 60V 5A SOT-23 THT", "0805", "SMD resistor", "chassis mount resistor 50W",
            "1206 1% thick film 10k 0.25W", "tantalum 47uF 10V 1206", "4.7uF 0603", "JST XH 4 pin male",
            "pin header 1x8 2.54mm right angle male", "female header 1x4 2.0mm");

    /** The policy families of the captured master, in table order. */
    static final List<String> CAPTURED_FAMILIES = List.of("resistor", "capacitor", "inductor", "ferrite", "crystal",
            "oscillator", "diode", "transistor", "regulator", "connector", "usb", "default");
    /** The constraint names (policy kinds) of the captured master, sorted. */
    static final List<String> CAPTURED_NAMES = List.of("connector type", "dcr", "dielectric", "elements", "esr",
            "form factor", "gender", "load capacitance", "mounting", "orientation", "package", "pin configuration",
            "pitch", "polarity", "positions", "tcr", "technology", "tolerance", "type", "usb standard", "usb type",
            "value", "voltage");

    /** The queries of re-modelled families, left out of the comparison (LEDs, 0.13). */
    static final List<String> REMODELLED_QUERIES = List.of("LED 0603 red");

    private final QueryParser parser = new QueryParser();
    private final ParametricExtractor extractor = new ParametricExtractor();
    private final DeterministicRanker ranker = TestWiring.deterministicRanker(extractor);

    @Test
    void constraintModelMatchesTheGoldenMaster() throws Exception {
        List<String> actual = dump();
        if (Boolean.getBoolean("kina.golden.write")) {
            Files.createDirectories(SOURCE.getParent());
            Files.write(SOURCE, actual, StandardCharsets.UTF_8);
            return;
        }
        List<String> expected;
        try (InputStream in = ConstraintGoldenTest.class.getResourceAsStream(RESOURCE)) {
            assertThat(in).as("golden resource %s (capture with -Dkina.golden.write=true)", RESOURCE).isNotNull();
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
        expected = compared(expected);
        actual = compared(actual);
        if (!expected.equals(actual)) {
            Path out = Path.of("target/golden/constraints-actual.jsonl");
            Files.createDirectories(out.getParent());
            Files.write(out, actual, StandardCharsets.UTF_8);
        }
        assertThat(actual).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            assertThat(MAPPER.readTree(actual.get(i))).as("line %d", i + 1).isEqualTo(MAPPER.readTree(expected.get(i)));
        }
    }

    /** The lines compared: every line but the records of {@link #REMODELLED_QUERIES}. */
    private static List<String> compared(List<String> lines) {
        return lines.stream().filter(line -> {
            JsonNode node = MAPPER.readTree(line);
            JsonNode query = node.has("text") ? node.get("text") : node.get("query");
            return query == null || !REMODELLED_QUERIES.contains(query.asString());
        }).toList();
    }

    // ---------------------------------------------------------------- the dump

    /**
     * The golden lines: the policy tables, one record per query (its per-policy results and a digest of its pairs),
     * then the sampled pair records. With {@code -Dkina.golden.pairs=<file>} every pair record is also written there
     * (for diffing two trees).
     */
    private List<String> dump() throws IOException, NoSuchAlgorithmException {
        List<String> queries = new ArrayList<>();
        Map<String, Part> pool = new TreeMap<>();
        for (String line : Files.readAllLines(DATASET, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode rec = MAPPER.readTree(line);
            queries.add(rec.get("query").asString());
            for (JsonNode c : rec.get("candidates")) {
                Part part = extractor.enrich(MAPPER.treeToValue(c.get("part"), Part.class));
                pool.putIfAbsent(PartKey.of(part), part);
            }
        }
        queries.addAll(testQueries());
        queries.addAll(EXTRA);
        List<String> distinct = List.copyOf(new LinkedHashSet<>(queries));

        Map<String, ConstraintPolicy> policies = policies();
        List<String> out = new ArrayList<>();
        String pairsFile = System.getProperty("kina.golden.pairs");
        List<String> allPairs = new ArrayList<>();
        Map<String, Object> tables = new LinkedHashMap<>();
        policies.forEach((name, p) -> tables.put(name, p.table().entrySet().stream()
                .filter(e -> CAPTURED_FAMILIES.contains(e.getKey())).collect(
                LinkedHashMap::new, (m, e) -> m.put(e.getKey(), captured(e.getValue())), Map::putAll)));
        out.add(line(Map.of("policies", tables)));

        List<String> samples = new ArrayList<>();
        Map<String, ParametricExtractor.Features> features = new LinkedHashMap<>();
        pool.forEach((key, part) -> features.put(key, extractor.features(part)));
        int index = 0;
        for (String text : distinct) {
            ParsedQuery q = parser.parse(text);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("text", text);
            r.put("policy_family", ConstraintPolicy.policyFamily(q));
            r.put("describe", ConstraintPolicy.describe(q));
            Map<String, Object> perPolicy = new LinkedHashMap<>();
            policies.forEach((name, p) -> perPolicy.put(name, perPolicy(q, p)));
            r.put("by_policy", perPolicy);

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int pairs = 0;
            for (Map.Entry<String, Part> e : pool.entrySet()) {
                Map<String, Object> pair = pair(q, e.getValue(), features.get(e.getKey()), policies);
                String line = line(pair);
                if (pairsFile != null) {
                    allPairs.add(text + "\t" + e.getKey() + "\t" + line);
                }
                digest.update(line.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
                if ((index + pairs) % 997 == 0) {
                    Map<String, Object> sample = new LinkedHashMap<>();
                    sample.put("query", text);
                    sample.put("part", e.getKey());
                    sample.putAll(pair);
                    samples.add(line(sample));
                }
                pairs++;
            }
            r.put("pairs", pairs);
            r.put("pairs_sha256", HexFormat.of().formatHex(digest.digest()));
            out.add(line(r));
            index++;
        }
        out.addAll(samples);
        if (pairsFile != null) {
            Files.write(Path.of(pairsFile), allPairs, StandardCharsets.UTF_8);
        }
        return out;
    }

    private static String line(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    private Map<String, Object> perPolicy(ParsedQuery q, ConstraintPolicy p) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("hard", captured(p.hardFor(q)));
        r.put("stated_hard", p.statedHard(q));
        r.put("relaxable", CAPTURED_NAMES.stream().filter(n -> p.isRelaxable(q, n)).toList());
        r.put("reportable", ResponseAssembler.relaxable(q, CAPTURED_NAMES, p));
        Map<String, Integer> excluded = new LinkedHashMap<>();
        excluded.put("package", 3);
        excluded.put("capacitance", 1);
        excluded.put("gender", 3);
        r.put("hint_plain", p.hint(q, List.of("TME"), Map.of(), 0, false));
        r.put("hint_excluded", p.hint(q, List.of("LCSC", "TME", "MOUSER"), excluded, 2, false));
        r.put("hint_below_allowed", p.hint(q, List.of("MOUSER"), Map.of("voltage", 1), 1, true));
        Map<String, Object> ladders = new LinkedHashMap<>();
        for (Distributor d : Distributor.values()) {
            String phrase = DistributorPhraser.phrase(d, q);
            ladders.put(d.name() + ":null", steps(DistributorPhraser.ladder(d, q, null, p)));
            ladders.put(d.name() + ":phrase", steps(DistributorPhraser.ladder(d, q, phrase, p)));
        }
        r.put("ladders", ladders);
        return r;
    }

    /** The captured names of a set of constraint names, sorted. */
    private static List<String> captured(java.util.Collection<String> names) {
        return names.stream().filter(CAPTURED_NAMES::contains).sorted().toList();
    }

    private static List<String> steps(List<DistributorPhraser.Relaxation> steps) {
        return steps.stream().map(s -> s.phrase() + " " + s.relaxed()).toList();
    }

    private Map<String, Object> pair(ParsedQuery q, Part part, ParametricExtractor.Features f,
                                     Map<String, ConstraintPolicy> policies) {
        Map<String, Object> r = new LinkedHashMap<>();
        DeterministicRanker.Assessment a = ranker.assess(q, part, f);
        r.put("score", Double.toString(a.score()));
        r.put("match", a.match() == null ? null : Double.toString(a.match()));
        r.put("mismatches", a.mismatches());
        r.put("unverified", a.unverified());
        r.put("below_spec", a.belowSpec());
        r.put("below_spec_distance", Double.toString(a.belowSpecDistance()));
        Map<String, Object> checks = new LinkedHashMap<>();
        policies.forEach((name, p) -> {
            ConstraintPolicy.Result c = p.check(q, f);
            checks.put(name, c.conflicts() + (c.unknown() ? " unknown" : ""));
        });
        r.put("checks", checks);
        return r;
    }

    // ---------------------------------------------------------------- inputs

    /** Policies: the defaults, a configured override, the legacy strict list, everything hard, nothing hard. */
    static Map<String, ConstraintPolicy> policies() {
        Map<String, ConstraintPolicy> m = new LinkedHashMap<>();
        m.put("defaults", ConstraintPolicy.from(search(null, Map.of())));
        Map<String, List<String>> configured = new LinkedHashMap<>();
        configured.put("capacitor", List.of("value", "package", "dielectric", "tolerance"));
        configured.put("inductor", List.of("value", "package", "DCR"));
        configured.put("connector", List.of("orientation", "positions", "Connector_Type"));
        configured.put("usb", List.of());
        configured.put("widget", List.of("value"));
        configured.put("resistor", List.of("VALUE", "Form-Factor", "bogus", "tcr"));
        m.put("configured", ConstraintPolicy.from(search(null, configured)));
        m.put("legacy_mounting", ConstraintPolicy.from(search(List.of("mounting"), Map.of())));
        m.put("legacy_mixed", ConstraintPolicy.from(search(List.of("technology", "elements"),
                Map.of("diode", List.of("package")))));
        Map<String, List<String>> all = new LinkedHashMap<>();
        Map<String, List<String>> none = new LinkedHashMap<>();
        for (String family : ConstraintPolicy.DEFAULT_HARD.keySet()) {
            all.put(family, ConstraintPolicy.NAMES.stream().sorted().toList());
            none.put(family, List.of());
        }
        // the families added after the capture keep their defaults in the policies the master was captured with
        all.keySet().retainAll(CAPTURED_FAMILIES);
        none.keySet().retainAll(CAPTURED_FAMILIES);
        m.put("all_hard", ConstraintPolicy.from(search(null, all)));
        m.put("none_hard", ConstraintPolicy.from(search(null, none)));
        return m;
    }

    private static KinaProperties.Search search(List<String> strict, Map<String, List<String>> hard) {
        return new KinaProperties.Search(40, 10, 50, Duration.ofSeconds(12), Duration.ofMinutes(2), strict, hard,
                null, null, 10);
    }

    /** The query strings of the search tests (a frozen copy, so the golden master does not move with the tests). */
    private static List<String> testQueries() throws IOException {
        try (InputStream in = ConstraintGoldenTest.class.getResourceAsStream("/golden/test-queries.txt")) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(s -> !s.isBlank())
                    .sorted(Comparator.naturalOrder()).toList();
        }
    }
}
