package ro.alacrity.kina.search.ce;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.config.KinaProperties.CrossEncoder.Variant;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PriceBreak;
import ro.alacrity.kina.search.ParametricExtractor;
import ro.alacrity.kina.search.QueryParser;
import ro.alacrity.kina.search.RankingException;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrossEncoderPartRankerTest {

    static BertTokenizer tokenizer;
    private final ParametricExtractor extractor = new ParametricExtractor();
    private final QueryParser parser = new QueryParser();

    @BeforeAll
    static void loadVocabulary() throws Exception {
        tokenizer = new BertTokenizer(BertTokenizerTest.readLines("/ce/vocab.txt"));
    }

    /** Backend that returns {@code 100 - index} per pair and records the batches. */
    static final class FakeBackend implements ScoringBackend {
        final List<List<BertTokenizer.Encoding>> batches = new CopyOnWriteArrayList<>();
        Duration delay = Duration.ZERO;
        CountDownLatch entered;
        CountDownLatch release;
        Exception failure;
        int counter;

        @Override
        public synchronized float[] score(List<BertTokenizer.Encoding> batch, long deadlineNanos) throws Exception {
            batches.add(List.copyOf(batch));
            if (entered != null) {
                entered.countDown();
            }
            if (release != null) {
                release.await(10, TimeUnit.SECONDS);
            }
            if (!delay.isZero()) {
                Thread.sleep(delay.toMillis());
            }
            if (failure != null) {
                throw failure;
            }
            float[] out = new float[batch.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = 100 - counter++;
            }
            return out;
        }

        @Override
        public void close() {
        }
    }

    static KinaProperties.CrossEncoder config(String... kv) {
        Map<String, String> source = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            source.put("kina.ranking.cross-encoder." + kv[i], kv[i + 1]);
        }
        source.put("kina.public-base-url", "");
        return new Binder(new MapConfigurationPropertySource(source))
                .bindOrCreate("kina", Bindable.of(KinaProperties.class)).ranking().crossEncoder();
    }

    private CrossEncoderPartRanker ranker(FakeBackend backend, String... kv) {
        CrossEncoderModel.Loaded loaded = backend == null ? null : new CrossEncoderModel.Loaded(tokenizer, backend,
                Variant.INT8, ModelLayout.QINT8_AVX512_VNNI, Path.of("/models/ce"), "abc123");
        return new CrossEncoderPartRanker(config(kv), extractor, () -> loaded, null);
    }

    private static Part part(String number, String manufacturer, String mpn, String description, String category,
                             String packageName, Map<String, String> attributes) {
        return new Part(Distributor.LCSC, number, manufacturer, mpn, description, category, packageName, 100, 1, 1,
                List.of(new PriceBreak(1, new BigDecimal("0.01"), "USD")), null, null, null, attributes, Map.of(),
                Instant.parse("2026-10-05T00:00:00Z"));
    }

    private static List<Part> parts(int n) {
        List<Part> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(part("C" + i, "ACME", "MPN" + i, "10uF 25V X7R 0805 capacitor number " + i, null, null, Map.of()));
        }
        return out;
    }

    @Test
    void documentTextIsTheStudysCandidateText() {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("Capacitance", "10uF");
        attrs.put("Voltage", "16V");
        Part p = part("C326595", "YAGEO", "CC0805KKX7R7BB106", "10uF 16V X7R ±10%",
                "Capacitors / Multilayer Ceramic Capacitors MLCC - SMD/SMT", "0805", attrs);
        // distributor attributes first, then the comparable attributes the extractor adds (ParametricExtractor.enrich)
        String enriched = String.join("; ", extractor.enrich(p).attributes().entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue()).toList());
        assertThat(enriched).startsWith("Capacitance: 10uF; Voltage: 16V; ").contains("Dielectric: X7R");
        assertThat(ranker(new FakeBackend()).documentText(p)).isEqualTo("YAGEO | CC0805KKX7R7BB106 | 10uF 16V X7R ±10% | "
                + "Capacitors / Multilayer Ceramic Capacitors MLCC - SMD/SMT | package 0805 | " + enriched);

        Part bare = part("C1", null, "", "widget", null, null, Map.of());
        assertThat(ranker(new FakeBackend()).documentText(bare)).isEqualTo("widget");
    }

    @Test
    void scoresInBatchesPaddedPerBatchAndReturnsRawLogitsPerKey() throws Exception {
        FakeBackend backend = new FakeBackend();
        List<Part> candidates = parts(37);
        Map<String, Double> scores = ranker(backend).rank(parser.parse("10uF X7R 0805"), candidates,
                Duration.ofSeconds(5));

        assertThat(backend.batches).extracting(List::size).containsExactly(16, 16, 5);
        assertThat(scores).hasSize(37);
        assertThat(scores.get(candidates.get(0).key())).isEqualTo(100.0);
        assertThat(scores.get(candidates.get(36).key())).isEqualTo(64.0);
        BertTokenizer.Encoding first = backend.batches.getFirst().getFirst();
        assertThat(first.inputIds()[0]).isEqualTo(101L);
        // the query is the user's text as typed
        assertThat(first.inputIds()).startsWith(101L, (long) tokenizer.tokenIds("10uF").getFirst());

        FakeBackend small = new FakeBackend();
        ranker(small, "batch-size", "5").rank(parser.parse("x"), parts(12), Duration.ofSeconds(5));
        assertThat(small.batches).extracting(List::size).containsExactly(5, 5, 2);
    }

    @Test
    void duplicatesAreScoredOnce() throws Exception {
        FakeBackend backend = new FakeBackend();
        Part p = parts(1).getFirst();
        assertThat(ranker(backend).rank(parser.parse("x"), List.of(p, p), Duration.ofSeconds(5))).hasSize(1);
        assertThat(backend.batches.getFirst()).hasSize(1);
    }

    @Test
    void longDocumentsAreTruncatedToTheMaximumSequenceLength() throws Exception {
        FakeBackend backend = new FakeBackend();
        Part longPart = part("C9", "ACME", "MPN", "capacitor ".repeat(500), null, null, Map.of());
        ranker(backend, "max-sequence-length", "64").rank(parser.parse("10uF X7R 0805"), List.of(longPart),
                Duration.ofSeconds(5));
        assertThat(backend.batches.getFirst().getFirst().length()).isEqualTo(64);
    }

    @Test
    void notLoadedAndDisabled() {
        assertThatThrownBy(() -> ranker(null).rank(parser.parse("x"), parts(1), Duration.ofSeconds(5)))
                .isInstanceOf(RankingException.class).hasMessage("cross-encoder model not loaded yet")
                .extracting(e -> ((RankingException) e).reason()).isEqualTo(RankingException.Reason.UNAVAILABLE);
        assertThat(ranker(null).isReady()).isFalse();
        CrossEncoderPartRanker disabled = ranker(new FakeBackend(), "enabled", "false");
        assertThat(disabled.isReady()).isFalse();
        assertThatThrownBy(() -> disabled.rank(parser.parse("x"), parts(1), Duration.ofSeconds(5)))
                .isInstanceOf(RankingException.class).hasMessage("cross-encoder disabled");
    }

    @Test
    void timeoutIsCheckedBetweenBatches() {
        FakeBackend backend = new FakeBackend();
        backend.delay = Duration.ofMillis(250);
        assertThatThrownBy(() -> ranker(backend).rank(parser.parse("x"), parts(40), Duration.ofMillis(400)))
                .isInstanceOf(RankingException.class).hasMessage("cross-encoder timeout after 400ms")
                .extracting(e -> ((RankingException) e).reason()).isEqualTo(RankingException.Reason.TIMEOUT);
        assertThat(backend.batches).hasSizeLessThan(3);
        assertThatThrownBy(() -> ranker(backend).rank(parser.parse("x"), parts(1), Duration.ZERO))
                .isInstanceOf(RankingException.class)
                .extracting(e -> ((RankingException) e).reason()).isEqualTo(RankingException.Reason.TIMEOUT);
    }

    @Test
    void backendFailureBecomesAFailedRankingException() {
        FakeBackend backend = new FakeBackend();
        backend.failure = new IllegalStateException("bad input shape");
        assertThatThrownBy(() -> ranker(backend).rank(parser.parse("x"), parts(3), Duration.ofSeconds(5)))
                .isInstanceOf(RankingException.class).hasMessage("cross-encoder failed: bad input shape")
                .extracting(e -> ((RankingException) e).reason()).isEqualTo(RankingException.Reason.FAILED);
    }

    @Test
    void concurrencyIsBoundedAndTheWaitFitsTheBudget() throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.entered = new CountDownLatch(1);
        backend.release = new CountDownLatch(1);
        CrossEncoderPartRanker ranker = ranker(backend, "max-concurrent", "1");
        Thread holder = Thread.ofVirtual().start(() -> {
            try {
                ranker.rank(parser.parse("a"), parts(1), Duration.ofSeconds(10));
            } catch (RankingException e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(backend.entered.await(5, TimeUnit.SECONDS)).isTrue();
        long start = System.nanoTime();
        assertThatThrownBy(() -> ranker.rank(parser.parse("b"), parts(1), Duration.ofMillis(300)))
                .isInstanceOf(RankingException.class).hasMessage("cross-encoder busy: no free slot within 300ms");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofMillis(200),
                Duration.ofMillis(900));
        backend.release.countDown();
        holder.join(5_000);
        assertThat(backend.batches).hasSize(1);
    }

    @Test
    void statusReportsTheLoadedModelAndLatency() throws Exception {
        CrossEncoderPartRanker ranker = ranker(new FakeBackend(), "threads", "3");
        assertThat(ranker.status().avgLatencyMs()).isNull();
        ranker.rank(parser.parse("x"), parts(2), Duration.ofSeconds(5));
        CrossEncoderPartRanker.Status s = ranker.status();
        assertThat(s.loaded()).isTrue();
        assertThat(s.variant()).isEqualTo("int8");
        assertThat(s.onnxFile()).isEqualTo(ModelLayout.QINT8_AVX512_VNNI);
        assertThat(s.revision()).isEqualTo("abc123");
        assertThat(s.modelDir()).isEqualTo(Path.of("/models/ce").toString());
        assertThat(s.threads()).isEqualTo(3);
        assertThat(s.calls()).isEqualTo(1);
        assertThat(s.avgLatencyMs()).isNotNull().isGreaterThanOrEqualTo(0.0);
        assertThat(ranker(null).status().loaded()).isFalse();
        assertThat(ranker.name()).isEqualTo("cross-encoder");
    }

    @Test
    void durationsAreFormattedForNotes() {
        assertThat(CrossEncoderPartRanker.format(Duration.ofSeconds(5))).isEqualTo("5s");
        assertThat(CrossEncoderPartRanker.format(Duration.ofMillis(1500))).isEqualTo("1.5s");
        assertThat(CrossEncoderPartRanker.format(Duration.ofMillis(250))).isEqualTo("250ms");
    }
}
