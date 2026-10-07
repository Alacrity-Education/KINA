package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ro.alacrity.kina.TestWiring;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PartKey;
import ro.alacrity.kina.domain.RankingMode;
import ro.alacrity.kina.search.ce.CrossEncoderModel;
import ro.alacrity.kina.search.ce.CrossEncoderPartRanker;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression check of the production blend on the labelled ranking set ({@code docs/research/data/ranking-eval.jsonl},
 * 32 queries, 1259 candidates) with the real model. Runs only when {@code KINA_CROSS_ENCODER_TEST_MODEL_DIR} names a
 * model directory (layout of DESIGN.md 3.5); {@code KINA_CROSS_ENCODER_TEST_VARIANT} picks {@code int8} (default) or
 * {@code fp32}. With {@code KINA_CROSS_ENCODER_TEST_SCORES_DIR} set, the cross-encoder and blended scores are written
 * as score files for {@code scripts/research/evaluate.py}.
 *
 * <p>Metric as in {@code evaluate.py}: NDCG@10 with gain 2^label - 1, per query, averaged; all candidates of a query
 * are ranked together, ties broken by the SHA-1 of the candidate key.
 */
@EnabledIfEnvironmentVariable(named = "KINA_CROSS_ENCODER_TEST_MODEL_DIR", matches = ".+")
class CrossEncoderEvaluationTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Path DATASET = Path.of("docs/research/data/ranking-eval.jsonl");

    @Test
    void blendIsAtLeastAsGoodAsTheDeterministicRanker() throws Exception {
        String dir = System.getenv("KINA_CROSS_ENCODER_TEST_MODEL_DIR");
        String variant = System.getenv().getOrDefault("KINA_CROSS_ENCODER_TEST_VARIANT", "int8");
        KinaProperties props = RankingFixtures.properties(
                "kina.ranking.cross-encoder.model-url", Path.of(dir).toAbsolutePath().toString(),
                "kina.ranking.cross-encoder.variant", variant,
                "kina.ranking.cross-encoder.max-candidates", "1000");
        ParametricExtractor extractor = new ParametricExtractor();
        DeterministicRanker deterministic = TestWiring.deterministicRanker(extractor);
        RankingScoreCache cache = TestWiring.scoreCache(Duration.ofHours(1));
        CrossEncoderModel model = TestWiring.wire(new CrossEncoderModel(), "properties", props, "scoreCache", cache);
        assertThat(model.check()).as("model loads: %s", model.lastError()).isTrue();
        CrossEncoderPartRanker ranker = TestWiring.wire(new CrossEncoderPartRanker(), "properties", props,
                "extractor", extractor, "owner", model);
        RankingService service = TestWiring.rankingService(props, deterministic, ranker, ranker::status, cache);
        QueryParser parser = new QueryParser();

        double detSum = 0;
        double ceSum = 0;
        double blendSum = 0;
        int n = 0;
        List<Long> latencies = new ArrayList<>();
        Map<String, Map<String, Object>> ceOut = new LinkedHashMap<>();
        Map<String, Map<String, Object>> blendOut = new LinkedHashMap<>();
        Map<String, double[]> byCategory = new TreeMap<>();
        for (String line : Files.readAllLines(DATASET, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode rec = MAPPER.readTree(line);
            ParsedQuery query = parser.parse(rec.get("query").asString());
            Map<String, Integer> labels = new HashMap<>();
            Map<Distributor, List<Part>> fetched = new EnumMap<>(Distributor.class);
            List<Part> all = new ArrayList<>();
            for (JsonNode c : rec.get("candidates")) {
                Part part = extractor.enrich(MAPPER.treeToValue(c.get("part"), Part.class));
                labels.put(PartKey.of(part), c.get("label").asInt());
                fetched.computeIfAbsent(part.distributor(), d -> new ArrayList<>()).add(part);
                all.add(part);
            }
            Map<String, Double> det = new HashMap<>();
            all.forEach(p -> det.put(PartKey.of(p), deterministic.score(query, p)));

            long t0 = System.nanoTime();
            Map<String, Double> ce = ranker.rank(query, all, Duration.ofSeconds(20));
            latencies.add((System.nanoTime() - t0) / 1_000_000);

            cache.clear();
            RankingService.RankedResults ranked = service.rank(query, fetched, Duration.ofSeconds(20));
            assertThat(ranked.mode()).as(ranked.note()).isEqualTo(RankingMode.BLENDED);
            Map<String, Double> blend = new HashMap<>();
            ranked.byDistributor().values().forEach(list -> list.forEach(r ->
                    blend.put(PartKey.of(r.part()), r.score())));

            double d = ndcg10(det, labels);
            double c = ndcg10(ce, labels);
            double b = ndcg10(blend, labels);
            detSum += d;
            ceSum += c;
            blendSum += b;
            n++;
            double[] cat = byCategory.computeIfAbsent(rec.get("category").asString(), k -> new double[4]);
            cat[0] += d;
            cat[1] += c;
            cat[2] += b;
            cat[3]++;
            String id = rec.get("id").asString();
            ceOut.put(id, Map.of("scores", ce, "latency_ms", latencies.getLast()));
            blendOut.put(id, Map.of("scores", blend, "latency_ms", latencies.getLast()));
        }
        double det = detSum / n;
        double ce = ceSum / n;
        double blend = blendSum / n;
        latencies.sort(Comparator.naturalOrder());
        CrossEncoderPartRanker.Status status = ranker.status();
        System.out.printf(Locale.ROOT, "cross-encoder evaluation (%s, %s, %d threads, %d queries): NDCG@10 det %.3f, "
                        + "cross-encoder %.3f, blend %.3f; ms/query median %d max %d%n", status.onnxFile(),
                status.variant(), status.threads(), n, det, ce, blend, latencies.get(latencies.size() / 2),
                latencies.getLast());
        byCategory.forEach((k, v) -> System.out.printf(Locale.ROOT, "  %-18s det %.3f  ce %.3f  blend %.3f%n", k,
                v[0] / v[3], v[1] / v[3], v[2] / v[3]));
        String out = System.getenv("KINA_CROSS_ENCODER_TEST_SCORES_DIR");
        if (out != null && !out.isBlank()) {
            String suffix = "java_" + status.variant();
            write(Path.of(out), "ce_" + suffix, ceOut, status);
            write(Path.of(out), "blend_" + suffix, blendOut, status);
        }
        model.close();

        assertThat(blend).isGreaterThanOrEqualTo(det).isGreaterThanOrEqualTo(0.90);
    }

    private static void write(Path dir, String method, Map<String, Map<String, Object>> queries,
                              CrossEncoderPartRanker.Status status) throws IOException {
        Files.createDirectories(dir);
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("method", method);
        file.put("meta", Map.of("onnx", String.valueOf(status.onnxFile()), "threads", status.threads(),
                "source", "CrossEncoderEvaluationTest"));
        file.put("queries", queries);
        Files.writeString(dir.resolve(method + ".json"), MAPPER.writeValueAsString(file));
    }

    /** NDCG@10 as in {@code evaluate.py}; ties broken by SHA-1 of the key. */
    static double ndcg10(Map<String, Double> scores, Map<String, Integer> labels) {
        List<String> keys = new ArrayList<>(labels.keySet());
        keys.sort(Comparator.<String>comparingDouble(k -> -scores.getOrDefault(k, -1e18))
                .thenComparing(CrossEncoderEvaluationTest::sha1));
        List<Integer> ideal = new ArrayList<>(labels.values());
        ideal.sort(Comparator.reverseOrder());
        double dcg = 0;
        double idcg = 0;
        for (int i = 0; i < Math.min(10, keys.size()); i++) {
            dcg += (Math.pow(2, labels.get(keys.get(i))) - 1) / (Math.log(i + 2) / Math.log(2));
            idcg += (Math.pow(2, ideal.get(i)) - 1) / (Math.log(i + 2) / Math.log(2));
        }
        return idcg > 0 ? dcg / idcg : 0.0;
    }

    private static String sha1(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
