package ro.alacrity.kina.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import ro.alacrity.kina.TestWiring;
import ro.alacrity.kina.TestcontainersConfiguration;
import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.cache.SearchCacheRepository;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.DistributorResult;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PartResponse;
import ro.alacrity.kina.domain.SearchRequest;
import ro.alacrity.kina.metrics.FieldFallback;
import ro.alacrity.kina.metrics.KinaMetrics;
import ro.alacrity.kina.search.field.FieldSearchShadow;
import ro.alacrity.kina.search.field.PartIndexRepository;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The field index never takes a cached part away (DESIGN.md 3.8 "Re-index and coverage", "Cache preservation"): the
 * cache write writes the index rows in its transaction; with no index rows, a half-built index and after the re-index,
 * a cached search returns the same parts from the cache; until the index covers a distributor it reports itself
 * incomplete (the shadow compares nothing), and afterwards the shadow finds no part dropped.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FieldIndexContinuityTest {

    private static final String QUERY = "10uF X7R 0805";

    @Autowired PartCacheRepository partCache;
    @Autowired SearchCacheRepository searchCache;
    @Autowired PartIndexRepository index;
    @Autowired ro.alacrity.kina.cache.PhraseJournalRepository journal;
    @Autowired JdbcClient jdbc;

    private PartSearchService service;
    private RankingService ranking;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM cached_parts").update();
        jdbc.sql("DELETE FROM cached_searches").update();
        jdbc.sql("DELETE FROM distributor_phrases").update();
        index.forgetCoverage();
    }

    @AfterEach
    void shutdown() {
        if (service != null) {
            service.shutdown();
        }
    }

    private List<String> search(PartSearchServiceTest.FakeClient mouser) {
        if (service == null) {
            ParametricExtractor extractor = new ParametricExtractor();
            KinaProperties props = RankingFixtures.properties("kina.ranking.cross-encoder.enabled", "false");
            ranking = TestWiring.rankingService(props, TestWiring.deterministicRanker(extractor),
                    mock(PartRanker.class), () -> null, TestWiring.scoreCache(Duration.ofHours(1)));
            service = RankingFixtures.searchService(props, TestWiring.registry(List.of(mouser)), new QueryParser(),
                    extractor, ranking, partCache, searchCache, Clock.systemUTC());
        }
        DistributorResult result = PartSearchServiceTest.result(
                service.search(new SearchRequest(QUERY, 10, Set.of(Distributor.MOUSER), false)), Distributor.MOUSER);
        lastCache = result.cache();
        return result.parts().stream().map(PartResponse::partNumber).toList();
    }

    private CacheStatus lastCache;

    @Test
    void cachedPartsStayReachableBeforeDuringAndAfterTheBackfill() {
        PartSearchServiceTest.FakeClient mouser = new PartSearchServiceTest.FakeClient(Distributor.MOUSER)
                .records(12, i -> PartSearchServiceTest.part(Distributor.MOUSER, "M" + i).toBuilder().fetchedAt(null)
                        .build());
        List<String> first = search(mouser);
        assertThat(lastCache).isEqualTo(CacheStatus.MISS);
        assertThat(first).isNotEmpty();
        // the cache write wrote the index rows in its transaction
        assertThat(index.countByDistributor().get(Distributor.MOUSER)).isEqualTo(12);
        assertThat(index.coverage().get(Distributor.MOUSER).complete()).isTrue();

        // an upgrade: cached rows, no index rows
        jdbc.sql("DELETE FROM part_index").update();
        assertThat(index.coverage().get(Distributor.MOUSER).stale()).isEqualTo(12);
        assertThat(index.isComplete(Distributor.MOUSER)).isFalse();
        int calls = mouser.calls.size();
        assertThat(search(mouser)).isEqualTo(first);
        assertThat(lastCache).isEqualTo(CacheStatus.HIT);
        assertThat(shadow().outcome()).isEqualTo("incomplete");

        // a half-built index
        List<Part> half = new ArrayList<>(partCache.findInStock(Distributor.MOUSER,
                IntStream.range(0, 6).mapToObj(i -> "M" + i).toList()).values());
        index.upsertFrom(half);
        assertThat(index.coverage().get(Distributor.MOUSER).stale()).isEqualTo(6);
        assertThat(index.isComplete(Distributor.MOUSER)).isFalse();
        assertThat(search(mouser)).isEqualTo(first);
        assertThat(shadow().outcome()).isEqualTo("incomplete");

        // after the backfill
        PartIndexRepository.ReindexReport report = index.reindexStale(ParametricExtractor.INDEX_VERSION, 4);
        assertThat(report.total()).isEqualTo(6);
        assertThat(index.isComplete(Distributor.MOUSER)).isTrue();
        assertThat(search(mouser)).isEqualTo(first);
        assertThat(mouser.calls).hasSize(calls);   // every repeat was a cache hit
        FieldSearchShadow.Report shadow = shadow();
        assertThat(shadow.outcome()).isEqualTo("ok");
        assertThat(shadow.dropped()).isEmpty();
        assertThat(shadow.returnable()).isEqualTo(12);
        assertThat(shadow.overlap()).isEqualTo(12);
    }

    @Test
    void aStockRefreshKeepsTheIndexInStep() {
        Part part = PartSearchServiceTest.part(Distributor.MOUSER, "S1");
        partCache.upsertAll(List.of(part));
        assertThat(inStock("S1")).isTrue();
        partCache.markSoldOut(Distributor.MOUSER, "S1");
        assertThat(inStock("S1")).isFalse();
        assertThat(index.coverage().get(Distributor.MOUSER).complete()).isTrue();
        partCache.updateStock(List.of(part));
        assertThat(inStock("S1")).isTrue();
        // a listed part (stock 0) is indexed sold out
        partCache.upsertListed(List.of(part.toBuilder().distributorPartNumber("S2").stock(0).build()));
        assertThat(inStock("S2")).isFalse();
    }

    /**
     * The v0.15.0 defect: a search served from the index refreshes the stock of its parts, which rewrites their
     * payloads (stock, prices, fetchedAt). The index rows must stay current, so the next search is served from the
     * index again (no {@code incomplete} fallback), also once the coverage snapshot has expired.
     */
    @Test
    void aStockRefreshAfterAnIndexAnswerKeepsTheNextSearchOnTheIndex() {
        String query = "10uF X7R 0805 25V";
        java.time.Instant twoDaysAgo = java.time.Instant.now().minus(Duration.ofDays(2));
        List<Part> parts = IntStream.rangeClosed(1, 5).mapToObj(i -> RankingFixtures.part(Distributor.MOUSER,
                "R" + i, "YAGEO", "MPN-R" + i, "MLCC 10uF 25V X7R 0805 10%", "Ceramic Capacitors", "0805", 1000,
                "0.10", java.util.Map.of(), java.util.Map.of()).toBuilder().fetchedAt(twoDaysAgo).build()).toList();
        partCache.upsertAll(parts);
        assertThat(index.coverage().get(Distributor.MOUSER).complete()).isTrue();

        FieldFirstSearchTest.PhraseClient mouser = new FieldFirstSearchTest.PhraseClient();
        parts.forEach(p -> mouser.stock.put(p.distributorPartNumber(), new ro.alacrity.kina.distributor.StockUpdate(
                777, List.of(new ro.alacrity.kina.domain.PriceBreak(1, new java.math.BigDecimal("0.08"), "EUR")))));
        KinaMetrics metrics = mock(KinaMetrics.class);
        KinaProperties props = RankingFixtures.properties("kina.ranking.cross-encoder.enabled", "false",
                "kina.search.field-index.mode", "on");
        RankingService rankingOn = TestWiring.rankingService(props, TestWiring.deterministicRanker(
                        new ParametricExtractor()), mock(PartRanker.class), () -> null,
                TestWiring.scoreCache(Duration.ofHours(1)));
        service = RankingFixtures.searchService(props, TestWiring.registry(List.of(mouser)), new QueryParser(),
                new ParametricExtractor(), rankingOn, partCache, searchCache, Clock.systemUTC(), metrics,
                new RankingFixtures.FieldBeans(index, journal));

        DistributorResult first = PartSearchServiceTest.result(service.search(new SearchRequest(query, 3,
                Set.of(Distributor.MOUSER), false)), Distributor.MOUSER);
        assertThat(first.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(mouser.asked).as("answered from the index").isEmpty();
        assertThat(mouser.refreshed).as("the stock of the returned parts was refreshed").hasSize(3);
        // the refresh rewrote the payloads: new stock, prices and fetchedAt
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM cached_parts
                        WHERE distributor = 'MOUSER' AND (payload ->> 'stock')::int = 777""")
                .query(Long.class).single()).isEqualTo(3);

        // the rows stay current: no stale row, also when the coverage is computed again
        index.forgetCoverage();
        assertThat(index.coverage().get(Distributor.MOUSER).stale()).isZero();
        assertThat(index.isComplete(Distributor.MOUSER)).isTrue();
        DistributorResult second = PartSearchServiceTest.result(service.search(new SearchRequest(query, 3,
                Set.of(Distributor.MOUSER), false)), Distributor.MOUSER);
        assertThat(second.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(mouser.asked).isEmpty();
        org.mockito.Mockito.verify(metrics, org.mockito.Mockito.times(2)).fieldServed("MOUSER");
        org.mockito.Mockito.verify(metrics, org.mockito.Mockito.never()).fieldFallback("MOUSER", FieldFallback.INCOMPLETE);
    }

    private boolean inStock(String partNumber) {
        return jdbc.sql("SELECT in_stock FROM part_index WHERE distributor = 'MOUSER' AND part_number = ?")
                .param(partNumber).query(Boolean.class).single();
    }

    /** The shadow comparison of the cached parts (every part the cache returns for the query). */
    private FieldSearchShadow.Report shadow() {
        FieldSearchShadow shadow = TestWiring.wire(new FieldSearchShadow(),
                "properties", TestWiring.properties("kina.search.field-index.mode", "shadow"), "index", index,
                "metrics", KinaMetrics.NOOP);
        ParsedQuery parsed = new QueryParser().parse(QUERY);
        List<Part> cached = new ArrayList<>(partCache.findInStock(Distributor.MOUSER,
                IntStream.range(0, 12).mapToObj(i -> "M" + i).toList()).values());
        ParametricExtractor extractor = new ParametricExtractor();
        List<Part> enriched = cached.stream().map(extractor::enrich).toList();
        return shadow.compare(Distributor.MOUSER, parsed, ranking.policy(), enriched,
                p -> PageCollector.Check.returnable(ranking, parsed, p, false));
    }
}
