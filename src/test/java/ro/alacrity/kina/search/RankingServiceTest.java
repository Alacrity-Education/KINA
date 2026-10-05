package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.RankingMode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RankingServiceTest {

    private final QueryParser parser = new QueryParser();
    private final ParametricExtractor extractor = new ParametricExtractor();
    private final DeterministicRanker deterministic = new DeterministicRanker(extractor);

    /** Scripted ranker: returns scores from a map (default 0.5) and records every call. */
    static final class FakeRanker implements PartRanker {
        final Map<String, Double> scores = new LinkedHashMap<>();
        final List<List<Part>> calls = new CopyOnWriteArrayList<>();
        RankingException failure;
        RuntimeException crash;
        CountDownLatch entered;
        CountDownLatch release;

        @Override
        public Map<String, Double> rank(ParsedQuery query, List<Part> candidates, Duration budget)
                throws RankingException {
            calls.add(List.copyOf(candidates));
            if (entered != null) {
                entered.countDown();
            }
            if (release != null) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failure != null) {
                throw failure;
            }
            if (crash != null) {
                throw crash;
            }
            Map<String, Double> out = new LinkedHashMap<>();
            candidates.forEach(p -> out.put(p.key(), scores.getOrDefault(p.key(), 0.5)));
            return out;
        }

        @Override
        public String name() {
            return "fake";
        }
    }

    private RankingService service(FakeRanker ranker, String... properties) {
        KinaProperties props = RankingFixtures.properties(properties);
        return new RankingService(props, deterministic, ranker, () -> true,
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
    void blendsRankNormalisedLayaScoresWithDeterministicScores() {
        ParsedQuery q = parser.parse("10uF X7R 0805");
        Part exact = mlcc(Distributor.MOUSER, "EXACT", "10uF 25V X7R 0805", 1000, "0.10");
        Part x5r = mlcc(Distributor.MOUSER, "X5R", "10uF 25V X5R 0805", 1000, "0.10");
        Part big = mlcc(Distributor.MOUSER, "BIG", "10uF 25V X7R 1206", 1000, "0.10");
        FakeRanker laya = new FakeRanker();
        laya.scores.put(exact.key(), 0.30);   // Laya disagrees: worst for the exact match
        laya.scores.put(x5r.key(), 0.99);
        laya.scores.put(big.key(), 0.99);

        RankingService.RankedResults result = service(laya).rank(q, fetched(Distributor.MOUSER, exact, x5r, big),
                Duration.ofSeconds(5));

        assertThat(result.mode()).isEqualTo(RankingMode.LAYA);
        assertThat(result.note()).isNull();
        List<RankingService.RankedPart> ranked = result.byDistributor().get(Distributor.MOUSER);
        double w = 0.2;
        Map<String, Double> expected = Map.of(
                "EXACT", (1 - w) * deterministic.score(q, exact) + w * 0.0,
                "X5R", (1 - w) * deterministic.score(q, x5r) + w * 1.0,
                "BIG", (1 - w) * deterministic.score(q, big) + w * 1.0);
        ranked.forEach(r -> assertThat(r.score()).isCloseTo(expected.get(r.part().distributorPartNumber()),
                within(1e-9)));
        assertThat(keys(ranked).getFirst()).isEqualTo("EXACT");   // deterministic stays the primary signal
        assertThat(ranked).isSortedAccordingTo((a, b) -> Double.compare(b.score(), a.score()));
    }

    @Test
    void rankNormalisationSharesTiesAndSpansZeroToOne() {
        assertThat(RankingService.normalise(Map.of("a", 0.99, "b", 0.5, "c", 0.1, "d", 0.5)))
                .containsExactlyInAnyOrderEntriesOf(Map.of("a", 1.0, "b", 0.5, "c", 0.0, "d", 0.5));
        assertThat(RankingService.normalise(Map.of("a", 0.7, "b", 0.7))).containsEntry("a", 1.0).containsEntry("b", 1.0);
        assertThat(RankingService.normalise(Map.of("a", 0.99961, "b", 0.99959))).containsEntry("b", 1.0);
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
    void onlyTopCandidatesAreSentAndOthersFollowByDeterministicScore() {
        ParsedQuery q = parser.parse("10uF X7R 0805");
        List<Part> mouser = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            // the first five are good matches, the last three are wrong packages
            mouser.add(mlcc(Distributor.MOUSER, "M" + i, i < 5 ? "10uF 25V X7R 0805" : "10uF 25V X7R 1206",
                    1000 + i, "0.10"));
        }
        FakeRanker laya = new FakeRanker();
        RankingService.RankedResults result = service(laya, "kina.ranking.laya.max-candidates", "5")
                .rank(q, Map.of(Distributor.MOUSER, mouser), Duration.ofSeconds(5));

        assertThat(laya.calls).hasSize(1);
        assertThat(laya.calls.getFirst()).extracting(Part::distributorPartNumber)
                .containsExactlyInAnyOrder("M0", "M1", "M2", "M3", "M4");
        List<RankingService.RankedPart> ranked = result.byDistributor().get(Distributor.MOUSER);
        assertThat(keys(ranked).subList(5, 8)).containsExactly("M7", "M6", "M5");   // stock desc on equal scores
        RankingService.RankedPart nonCandidate = ranked.get(5);
        assertThat(nonCandidate.score()).isCloseTo(0.8 * deterministic.score(q, nonCandidate.part()), within(1e-9));
        assertThat(ranked).isSortedAccordingTo((a, b) -> Double.compare(b.score(), a.score()));
    }

    @Test
    void fallbackWhenDisabled() {
        FakeRanker laya = new FakeRanker();
        ParsedQuery q = parser.parse("10uF X7R 0805");
        Part good = mlcc(Distributor.TME, "GOOD", "10uF 25V X7R 0805", 10, "0.10");
        Part bad = mlcc(Distributor.TME, "BAD", "1uF 25V X5R 1206", 10, "0.10");
        RankingService.RankedResults result = service(laya, "kina.ranking.laya.enabled", "false")
                .rank(q, fetched(Distributor.TME, bad, good), Duration.ofSeconds(5));
        assertThat(result.mode()).isEqualTo(RankingMode.FALLBACK);
        assertThat(result.note()).isEqualTo("laya disabled");
        assertThat(laya.calls).isEmpty();
        assertThat(keys(result.byDistributor().get(Distributor.TME))).containsExactly("GOOD", "BAD");
        assertThat(result.byDistributor().get(Distributor.TME).getFirst().score())
                .isEqualTo(deterministic.score(q, good));
    }

    @Test
    void fallbackOnRankerFailureAndCrash() {
        ParsedQuery q = parser.parse("10uF X7R 0805");
        Part good = mlcc(Distributor.TME, "GOOD", "10uF 25V X7R 0805", 10, "0.10");
        Part bad = mlcc(Distributor.TME, "BAD", "1uF 25V X5R 1206", 10, "0.10");

        FakeRanker timeout = new FakeRanker();
        timeout.failure = new RankingException(RankingException.Reason.TIMEOUT, "laya timeout after 18s");
        RankingService.RankedResults r1 = service(timeout).rank(q, fetched(Distributor.TME, bad, good), null);
        assertThat(r1.mode()).isEqualTo(RankingMode.FALLBACK);
        assertThat(r1.note()).isEqualTo("laya timeout after 18s");
        assertThat(keys(r1.byDistributor().get(Distributor.TME))).containsExactly("GOOD", "BAD");

        FakeRanker crash = new FakeRanker();
        crash.crash = new IllegalStateException("boom");
        RankingService.RankedResults r2 = service(crash).rank(q, fetched(Distributor.TME, bad, good),
                Duration.ofSeconds(5));
        assertThat(r2.mode()).isEqualTo(RankingMode.FALLBACK);
        assertThat(r2.note()).isEqualTo("laya failed: IllegalStateException");

        RankingService.RankedResults r3 = service(new FakeRanker()).rank(q, fetched(Distributor.TME, good),
                Duration.ZERO);
        assertThat(r3.mode()).isEqualTo(RankingMode.FALLBACK);
        assertThat(r3.note()).isEqualTo("laya timeout: budget exhausted");
    }

    @Test
    void semaphoreWaitIsBoundedByTheBudget() throws Exception {
        ParsedQuery q = parser.parse("10uF X7R 0805");
        FakeRanker laya = new FakeRanker();
        laya.entered = new CountDownLatch(1);
        laya.release = new CountDownLatch(1);
        RankingService service = service(laya, "kina.ranking.laya.max-concurrent-requests", "1");
        Part a = mlcc(Distributor.LCSC, "A", "10uF 25V X7R 0805", 10, "0.10");
        Part b = mlcc(Distributor.LCSC, "B", "10uF 25V X7R 0805", 10, "0.10");

        Thread holder = Thread.ofVirtual().start(() ->
                service.rank(q, fetched(Distributor.LCSC, a), Duration.ofSeconds(10)));
        assertThat(laya.entered.await(5, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        RankingService.RankedResults busy = service.rank(parser.parse("10uF X7R 0805 other"),
                fetched(Distributor.LCSC, b), Duration.ofMillis(400));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(busy.mode()).isEqualTo(RankingMode.FALLBACK);
        assertThat(busy.note()).isEqualTo("laya busy: no free slot within 400ms");
        assertThat(elapsed).isBetween(Duration.ofMillis(250), Duration.ofMillis(900));
        laya.release.countDown();
        holder.join(5_000);
        assertThat(laya.calls).hasSize(1);
    }

    @Test
    void cachedScoresAreReusedAndOnlyNewCandidatesAreSent() {
        ParsedQuery q = parser.parse("10uF X7R 0805");
        Part a = mlcc(Distributor.MOUSER, "A", "10uF 25V X7R 0805", 10, "0.10");
        Part b = mlcc(Distributor.MOUSER, "B", "10uF 16V X7R 0805", 10, "0.10");
        Part c = mlcc(Distributor.MOUSER, "C", "10uF 50V X7R 0805", 10, "0.10");
        FakeRanker laya = new FakeRanker();
        laya.scores.put(a.key(), 0.9);
        laya.scores.put(b.key(), 0.2);
        laya.scores.put(c.key(), 0.6);
        RankingService service = service(laya);

        RankingService.RankedResults first = service.rank(q, fetched(Distributor.MOUSER, a, b), Duration.ofSeconds(5));
        RankingService.RankedResults second = service.rank(parser.parse("  10UF  x7r 0805"),
                fetched(Distributor.MOUSER, a, b), Duration.ofSeconds(5));
        assertThat(laya.calls).hasSize(1);
        assertThat(second.mode()).isEqualTo(RankingMode.LAYA);
        assertThat(second.byDistributor()).isEqualTo(first.byDistributor());

        service.rank(q, fetched(Distributor.MOUSER, a, b, c), Duration.ofSeconds(5));
        assertThat(laya.calls).hasSize(2);
        assertThat(laya.calls.get(1)).containsExactly(c);
    }

    @Test
    void tiesAreBrokenByStockThenPrice() {
        ParsedQuery q = parser.parse("widget");
        Part lowStock = RankingFixtures.simple(Distributor.TME, "LOW", "gadget", 100, "0.10");
        Part cheap = RankingFixtures.simple(Distributor.TME, "CHEAP", "gadget", 1000, "0.05");
        Part pricey = RankingFixtures.simple(Distributor.TME, "PRICEY", "gadget", 1000, "0.20");
        RankingService.RankedResults r = service(new FakeRanker(), "kina.ranking.laya.enabled", "false")
                .rank(q, fetched(Distributor.TME, lowStock, pricey, cheap), Duration.ofSeconds(1));
        // LOW has a smaller stock bonus, so it also has a lower deterministic score
        assertThat(keys(r.byDistributor().get(Distributor.TME))).containsExactly("CHEAP", "PRICEY", "LOW");
    }

    @Test
    void emptyInputAndStatus() {
        RankingService service = service(new FakeRanker(), "kina.ranking.laya.url", "http://laya:8000",
                "kina.ranking.laya.weight", "0.3");
        RankingService.RankedResults r = service.rank(parser.parse("x"), Map.of(Distributor.TME, List.of()), null);
        assertThat(r.byDistributor()).containsEntry(Distributor.TME, List.of());
        assertThat(service.rank(parser.parse("x"), null, null).byDistributor()).isEmpty();
        assertThat(service.status()).isEqualTo(
                new RankingService.RankingStatus(true, "http://laya:8000", "multilingual", true, 40, 0.3));
        assertThat(service(new FakeRanker(), "kina.ranking.laya.enabled", "false").status().layaHealthy()).isFalse();
    }
}
