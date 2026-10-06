package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.RankingMode;
import ro.alacrity.kina.search.ce.CrossEncoderPartRanker;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RankingServiceTest {

    private final QueryParser parser = new QueryParser();
    private final ParametricExtractor extractor = new ParametricExtractor();
    private final DeterministicRanker deterministic = new DeterministicRanker(extractor);

    static final CrossEncoderPartRanker.Status READY = new CrossEncoderPartRanker.Status(true, true, "int8",
            "/data/cross-encoder", "c5ee24cb", "onnx/model_quint8_avx2.onnx", 4, null, 85.0, 3);

    /** Scripted ranker: returns raw scores from a map (default 0.0) and records every call. */
    static final class FakeRanker implements PartRanker {
        final Map<String, Double> scores = new LinkedHashMap<>();
        final List<List<Part>> calls = new CopyOnWriteArrayList<>();
        final List<Duration> budgets = new CopyOnWriteArrayList<>();
        RankingException failure;
        RuntimeException crash;

        @Override
        public Map<String, Double> rank(ParsedQuery query, List<Part> candidates, Duration budget)
                throws RankingException {
            calls.add(List.copyOf(candidates));
            budgets.add(budget);
            if (failure != null) {
                throw failure;
            }
            if (crash != null) {
                throw crash;
            }
            Map<String, Double> out = new LinkedHashMap<>();
            candidates.forEach(p -> out.put(p.key(), scores.getOrDefault(p.key(), 0.0)));
            return out;
        }

        @Override
        public String name() {
            return "fake";
        }
    }

    private RankingService service(FakeRanker ranker, String... properties) {
        KinaProperties props = RankingFixtures.properties(properties);
        return new RankingService(props, deterministic, ranker, () -> READY,
                new RankingScoreCache(props.ranking().scoreCacheTtl()));
    }

    private static Part mlcc(Distributor d, String number, String description, int stock, String price) {
        return RankingFixtures.part(d, number, "ACME", number, description, "Capacitors", null, stock, price,
                Map.of(), Map.of());
    }

    private static Map<Distributor, List<Part>> fetched(Distributor d, Part... parts) {
        Map<Distributor, List<Part>> m = new EnumMap<>(Distributor.class);
        m.put(d, List.of(parts));
        return m;
    }

    private static List<String> keys(List<RankingService.RankedPart> ranked) {
        return ranked.stream().map(r -> r.part().distributorPartNumber()).toList();
    }

    @Test
    void everyRankedPartCarriesItsAbsoluteMatchGrade() {
        Part exact1 = mlcc(Distributor.MOUSER, "A", "Capacitor: ceramic; 10uF; 25V; X7R; SMD; 0805", 5000, "0.10");
        Part exact2 = mlcc(Distributor.MOUSER, "B", "Capacitor: ceramic; 10uF; 25V; X7R; SMD; 0805", 10, "0.20");
        Part wrong = mlcc(Distributor.MOUSER, "C", "Capacitor: ceramic; 1uF; 25V; X7R; SMD; 0805", 100, "0.10");
        ParsedQuery q = parser.parse("10uF 25V X7R 0805");
        FakeRanker ranker = new FakeRanker();
        ranker.scores.put(exact1.key(), 3.0);
        ranker.scores.put(exact2.key(), 1.0);
        ranker.scores.put(wrong.key(), 2.0);

        for (RankingService.RankedResults results : List.of(
                service(ranker).rank(q, fetched(Distributor.MOUSER, exact1, exact2, wrong), null),
                service(ranker, "kina.ranking.cross-encoder.enabled", "false")
                        .rank(q, fetched(Distributor.MOUSER, exact1, exact2, wrong), null))) {
            Map<String, RankingService.RankedPart> byNumber = new LinkedHashMap<>();
            results.byDistributor().get(Distributor.MOUSER)
                    .forEach(r -> byNumber.put(r.part().distributorPartNumber(), r));
            // the scores are relative (the worse exact match may be far below 1); the match grade is not
            assertThat(byNumber.get("A").match()).isEqualTo(1.0);
            assertThat(byNumber.get("B").match()).isEqualTo(1.0);
            assertThat(byNumber.get("C").match()).isLessThan(0.5);
        }
    }

    @Test
    void blendsRankNormalisedDeterministicAndCrossEncoderScoresFiftyFifty() {
        ParsedQuery q = parser.parse("10uF X7R 0805");
        Part exact = mlcc(Distributor.MOUSER, "EXACT", "10uF 25V X7R 0805", 1000, "0.10");
        Part x5r = mlcc(Distributor.MOUSER, "X5R", "10uF 25V X5R 0805", 1000, "0.10");
        Part big = mlcc(Distributor.MOUSER, "BIG", "10uF 25V X7R 1206", 1000, "0.10");
        Part wrong = mlcc(Distributor.MOUSER, "WRONG", "1uF 25V X5R 1206", 1000, "0.10");
        FakeRanker ce = new FakeRanker();
        ce.scores.put(exact.key(), 7.5);
        ce.scores.put(x5r.key(), -2.0);
        ce.scores.put(big.key(), 8.1);      // the model prefers the wrong package
        ce.scores.put(wrong.key(), -9.0);

        RankingService.RankedResults result = service(ce).rank(q,
                fetched(Distributor.MOUSER, wrong, big, x5r, exact), Duration.ofSeconds(5));

        assertThat(result.mode()).isEqualTo(RankingMode.BLENDED);
        assertThat(result.note()).isNull();
        Map<String, Double> det = new HashMap<>();
        for (Part p : List.of(exact, x5r, big, wrong)) {
            det.put(p.key(), deterministic.score(q, p));
        }
        Map<String, Double> detNorm = RankingService.normalise(det);
        Map<String, Double> ceNorm = RankingService.normalise(ce.scores);
        List<RankingService.RankedPart> ranked = result.byDistributor().get(Distributor.MOUSER);
        ranked.forEach(r -> assertThat(r.score()).isCloseTo(
                0.5 * detNorm.get(r.part().key()) + 0.5 * ceNorm.get(r.part().key()), within(1e-9)));
        assertThat(ranked).isSortedAccordingTo((a, b) -> Double.compare(b.score(), a.score()));
        assertThat(keys(ranked).getFirst()).isEqualTo("EXACT");
        assertThat(keys(ranked).getLast()).isEqualTo("WRONG");
        assertThat(ranked.getFirst().score()).isLessThanOrEqualTo(1.0);
        assertThat(ranked.getLast().score()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void weightIsConfigurable() {
        ParsedQuery q = parser.parse("10uF X7R 0805");
        Part exact = mlcc(Distributor.TME, "EXACT", "10uF 25V X7R 0805", 1000, "0.10");
        // both complete matches (a mismatch would put BIG in a lower tier whatever the weight)
        Part big = mlcc(Distributor.TME, "BIG", "10uF 50V X7R 0805", 100, "0.10");
        FakeRanker ce = new FakeRanker();
        ce.scores.put(exact.key(), -1.0);
        ce.scores.put(big.key(), 5.0);
        // weight 1: model order only; weight 0: deterministic order only
        assertThat(keys(service(ce, "kina.ranking.cross-encoder.weight", "1.0")
                .rank(q, fetched(Distributor.TME, exact, big), null).byDistributor().get(Distributor.TME)))
                .containsExactly("BIG", "EXACT");
        assertThat(keys(service(ce, "kina.ranking.cross-encoder.weight", "0")
                .rank(q, fetched(Distributor.TME, big, exact), null).byDistributor().get(Distributor.TME)))
                .containsExactly("EXACT", "BIG");
    }

    @Test
    void rankNormalisationSharesTiesAndSpansZeroToOne() {
        assertThat(RankingService.normalise(Map.of("a", 0.99, "b", 0.5, "c", 0.1, "d", 0.5)))
                .containsExactlyInAnyOrderEntriesOf(Map.of("a", 1.0, "b", 0.5, "c", 0.0, "d", 0.5));
        assertThat(RankingService.normalise(Map.of("a", 0.7, "b", 0.7))).containsEntry("a", 1.0).containsEntry("b", 1.0);
        assertThat(RankingService.normalise(Map.of("a", 0.99961, "b", 0.99959))).containsEntry("b", 1.0);
        assertThat(RankingService.normalise(Map.of("a", -7.25, "b", 3.0, "c", -11.0)))
                .containsExactlyInAnyOrderEntriesOf(Map.of("a", 0.5, "b", 1.0, "c", 0.0));
    }

    @Test
    void candidatesAreSharedProportionallyWithAMinimumPerDistributor() {
        Map<Distributor, List<Part>> sorted = new EnumMap<>(Distributor.class);
        sorted.put(Distributor.LCSC, parts(Distributor.LCSC, 100));
        sorted.put(Distributor.MOUSER, parts(Distributor.MOUSER, 50));
        sorted.put(Distributor.TME, parts(Distributor.TME, 10));
        assertThat(RankingService.quotas(sorted, 40))
                .containsExactlyInAnyOrderEntriesOf(Map.of(Distributor.LCSC, 23, Distributor.MOUSER, 12, Distributor.TME, 5));

        sorted.put(Distributor.TME, parts(Distributor.TME, 3));
        sorted.put(Distributor.MOUSER, List.of());
        assertThat(RankingService.quotas(sorted, 40))
                .containsExactlyInAnyOrderEntriesOf(Map.of(Distributor.LCSC, 37, Distributor.TME, 3));
        assertThat(RankingService.quotas(sorted, 200))
                .containsExactlyInAnyOrderEntriesOf(Map.of(Distributor.LCSC, 100, Distributor.TME, 3, Distributor.MOUSER, 0));
    }

    private static List<Part> parts(Distributor d, int n) {
        List<Part> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(RankingFixtures.simple(d, d.name() + i, "part " + i, 100, "1.00"));
        }
        return list;
    }

    @Test
    void onlyTopCandidatesAreScoredAndOthersFollowByDeterministicScore() {
        ParsedQuery q = parser.parse("10uF X7R 0805");
        List<Part> mouser = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            // the first five are good matches, the last three are wrong packages
            mouser.add(mlcc(Distributor.MOUSER, "M" + i, i < 5 ? "10uF 25V X7R 0805" : "10uF 25V X7R 1206",
                    1000 + i, "0.10"));
        }
        FakeRanker ce = new FakeRanker();
        ce.scores.put(mouser.get(0).key(), 1.0);
        RankingService.RankedResults result = service(ce, "kina.ranking.cross-encoder.max-candidates", "5")
                .rank(q, Map.of(Distributor.MOUSER, mouser), Duration.ofSeconds(5));

        assertThat(ce.calls).hasSize(1);
        assertThat(ce.calls.getFirst()).extracting(Part::distributorPartNumber)
                .containsExactlyInAnyOrder("M0", "M1", "M2", "M3", "M4");
        List<RankingService.RankedPart> ranked = result.byDistributor().get(Distributor.MOUSER);
        assertThat(keys(ranked).subList(5, 8)).containsExactly("M7", "M6", "M5");   // stock desc on equal scores
        double lowestCandidate = ranked.get(4).score();
        RankingService.RankedPart nonCandidate = ranked.get(5);
        assertThat(nonCandidate.score())
                .isCloseTo(lowestCandidate * deterministic.score(q, nonCandidate.part()), within(1e-9));
        assertThat(ranked).isSortedAccordingTo((a, b) -> Double.compare(b.score(), a.score()));
    }

    @Test
    void tiesInTheBlendAreBrokenByDeterministicScoreThenStockThenPrice() {
        ParsedQuery q = parser.parse("10uF X7R 0805");
        Part lowStock = mlcc(Distributor.LCSC, "LOW", "10uF 25V X7R 0805", 10, "0.10");
        Part cheap = mlcc(Distributor.LCSC, "CHEAP", "10uF 25V X7R 0805", 1000, "0.05");
        Part pricey = mlcc(Distributor.LCSC, "PRICEY", "10uF 25V X7R 0805", 1000, "0.20");
        FakeRanker ce = new FakeRanker();   // every model score equal
        RankingService.RankedResults r = service(ce).rank(q, fetched(Distributor.LCSC, lowStock, pricey, cheap),
                Duration.ofSeconds(5));
        assertThat(r.mode()).isEqualTo(RankingMode.BLENDED);
        assertThat(keys(r.byDistributor().get(Distributor.LCSC))).containsExactly("CHEAP", "PRICEY", "LOW");
    }

    @Test
    void fallbackWhenDisabled() {
        FakeRanker ce = new FakeRanker();
        ParsedQuery q = parser.parse("10uF X7R 0805");
        Part good = mlcc(Distributor.TME, "GOOD", "10uF 25V X7R 0805", 10, "0.10");
        Part bad = mlcc(Distributor.TME, "BAD", "1uF 25V X5R 1206", 10, "0.10");
        RankingService.RankedResults result = service(ce, "kina.ranking.cross-encoder.enabled", "false")
                .rank(q, fetched(Distributor.TME, bad, good), Duration.ofSeconds(5));
        assertThat(result.mode()).isEqualTo(RankingMode.FALLBACK);
        assertThat(result.note()).isEqualTo("cross-encoder disabled");
        assertThat(ce.calls).isEmpty();
        assertThat(keys(result.byDistributor().get(Distributor.TME))).containsExactly("GOOD", "BAD");
        assertThat(result.byDistributor().get(Distributor.TME).getFirst().score())
                .isEqualTo(deterministic.score(q, good));
    }

    @Test
    void fallbackWhenTheModelIsNotLoadedTimesOutFailsOrCrashes() {
        ParsedQuery q = parser.parse("10uF X7R 0805");
        Part good = mlcc(Distributor.TME, "GOOD", "10uF 25V X7R 0805", 10, "0.10");
        Part bad = mlcc(Distributor.TME, "BAD", "1uF 25V X5R 1206", 10, "0.10");

        FakeRanker notLoaded = new FakeRanker();
        notLoaded.failure = new RankingException(RankingException.Reason.UNAVAILABLE,
                "cross-encoder model not loaded yet");
        RankingService.RankedResults r0 = service(notLoaded).rank(q, fetched(Distributor.TME, bad, good), null);
        assertThat(r0.mode()).isEqualTo(RankingMode.FALLBACK);
        assertThat(r0.note()).isEqualTo("cross-encoder model not loaded yet");
        assertThat(keys(r0.byDistributor().get(Distributor.TME))).containsExactly("GOOD", "BAD");

        FakeRanker timeout = new FakeRanker();
        timeout.failure = new RankingException(RankingException.Reason.TIMEOUT, "cross-encoder timeout after 4.9s");
        RankingService.RankedResults r1 = service(timeout).rank(q, fetched(Distributor.TME, bad, good), null);
        assertThat(r1.mode()).isEqualTo(RankingMode.FALLBACK);
        assertThat(r1.note()).isEqualTo("cross-encoder timeout after 5s");   // the configured kina.ranking.timeout
        assertThat(timeout.budgets.getFirst()).isLessThanOrEqualTo(Duration.ofSeconds(5)).isPositive();

        FakeRanker failed = new FakeRanker();
        failed.failure = new RankingException(RankingException.Reason.FAILED, "cross-encoder failed: bad shape");
        assertThat(service(failed).rank(q, fetched(Distributor.TME, good), null).note())
                .isEqualTo("cross-encoder failed: bad shape");

        FakeRanker crash = new FakeRanker();
        crash.crash = new IllegalStateException("boom");
        RankingService.RankedResults r2 = service(crash).rank(q, fetched(Distributor.TME, bad, good),
                Duration.ofSeconds(5));
        assertThat(r2.mode()).isEqualTo(RankingMode.FALLBACK);
        assertThat(r2.note()).isEqualTo("cross-encoder failed: IllegalStateException");

        RankingService.RankedResults r3 = service(new FakeRanker()).rank(q, fetched(Distributor.TME, good),
                Duration.ZERO);
        assertThat(r3.mode()).isEqualTo(RankingMode.FALLBACK);
        assertThat(r3.note()).isEqualTo("cross-encoder timeout: budget exhausted");
    }

    @Test
    void cachedScoresAreReusedAndOnlyNewCandidatesAreScored() {
        ParsedQuery q = parser.parse("10uF X7R 0805");
        Part a = mlcc(Distributor.MOUSER, "A", "10uF 25V X7R 0805", 10, "0.10");
        Part b = mlcc(Distributor.MOUSER, "B", "10uF 16V X7R 0805", 10, "0.10");
        Part c = mlcc(Distributor.MOUSER, "C", "10uF 50V X7R 0805", 10, "0.10");
        FakeRanker ce = new FakeRanker();
        ce.scores.put(a.key(), 9.0);
        ce.scores.put(b.key(), -2.0);
        ce.scores.put(c.key(), 6.0);
        RankingService service = service(ce);

        RankingService.RankedResults first = service.rank(q, fetched(Distributor.MOUSER, a, b), Duration.ofSeconds(5));
        RankingService.RankedResults second = service.rank(parser.parse("  10UF  x7r 0805"),
                fetched(Distributor.MOUSER, a, b), Duration.ofSeconds(5));
        assertThat(ce.calls).hasSize(1);
        assertThat(second.mode()).isEqualTo(RankingMode.BLENDED);
        assertThat(second.byDistributor()).isEqualTo(first.byDistributor());

        service.rank(q, fetched(Distributor.MOUSER, a, b, c), Duration.ofSeconds(5));
        assertThat(ce.calls).hasSize(2);
        assertThat(ce.calls.get(1)).containsExactly(c);

        // fully cached: the model is not needed at all, even when it would fail now
        ce.failure = new RankingException(RankingException.Reason.UNAVAILABLE, "cross-encoder model not loaded yet");
        assertThat(service.rank(q, fetched(Distributor.MOUSER, a, b, c), Duration.ofSeconds(5)).mode())
                .isEqualTo(RankingMode.BLENDED);
    }

    @Test
    void candidatesAcrossDistributorsShareOneNormalisation() {
        ParsedQuery q = parser.parse("10uF X7R 0805");
        Part lcsc = mlcc(Distributor.LCSC, "L", "10uF 25V X7R 0805", 10, "0.10");
        Part tme = mlcc(Distributor.TME, "T", "10uF 25V X7R 1206", 10, "0.10");
        FakeRanker ce = new FakeRanker();
        ce.scores.put(lcsc.key(), 1.0);
        ce.scores.put(tme.key(), 0.0);
        Map<Distributor, List<Part>> in = new EnumMap<>(Distributor.class);
        in.put(Distributor.LCSC, List.of(lcsc));
        in.put(Distributor.TME, List.of(tme));
        RankingService.RankedResults r = service(ce).rank(q, in, Duration.ofSeconds(5));
        assertThat(ce.calls).hasSize(1);
        assertThat(r.byDistributor().get(Distributor.LCSC).getFirst().score()).isEqualTo(1.0);
        assertThat(r.byDistributor().get(Distributor.TME).getFirst().score()).isEqualTo(0.0);
    }

    @Test
    void emptyInputAndStatus() {
        RankingService service = service(new FakeRanker(), "kina.ranking.cross-encoder.weight", "0.3",
                "kina.ranking.cross-encoder.model-dir", "/data/cross-encoder");
        RankingService.RankedResults r = service.rank(parser.parse("x"), Map.of(Distributor.TME, List.of()), null);
        assertThat(r.byDistributor()).containsEntry(Distributor.TME, List.of());
        assertThat(service.rank(parser.parse("x"), null, null).byDistributor()).isEmpty();
        assertThat(service.status()).isEqualTo(new RankingService.RankingStatus(true, "int8", "/data/cross-encoder",
                "c5ee24cb", true, 40, 0.3, null, 4, 85.0));

        KinaProperties disabled = RankingFixtures.properties("kina.ranking.cross-encoder.enabled", "false");
        RankingService off = new RankingService(disabled, deterministic, new FakeRanker(), () -> READY,
                new RankingScoreCache(Duration.ofHours(1)));
        assertThat(off.status().ready()).isFalse();
        assertThat(off.status().crossEncoderEnabled()).isFalse();
        RankingService broken = new RankingService(disabled, deterministic, new FakeRanker(), () -> {
            throw new IllegalStateException("x");
        }, new RankingScoreCache(Duration.ofHours(1)));
        assertThat(broken.status().ready()).isFalse();
        assertThat(broken.status().lastError()).isEqualTo("status unavailable");
    }
}
