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
import ro.alacrity.kina.domain.Indexed;
import ro.alacrity.kina.domain.ConstraintKind;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PartResponse;
import ro.alacrity.kina.domain.SearchRequest;
import ro.alacrity.kina.metrics.KinaMetrics;
import ro.alacrity.kina.search.field.FieldSearchShadow;
import ro.alacrity.kina.search.field.IndexColumn;
import ro.alacrity.kina.search.field.PartIndexRepository;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
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
    @Autowired JdbcClient jdbc;

    private PartSearchService service;
    private RankingService ranking;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM cached_parts").update();
        jdbc.sql("DELETE FROM cached_searches").update();
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

    @Test
    void everyDeclaredColumnExists() {
        Set<String> columns = new HashSet<>(jdbc.sql("""
                        SELECT column_name FROM information_schema.columns WHERE table_name = 'part_index'""")
                .query(String.class).list());
        Set<String> declared = new HashSet<>();
        IndexColumn.valueColumns().values().forEach(c -> declared.add(c.name()));
        for (ConstraintKind kind : ConstraintKind.values()) {
            IndexColumn.all(kind).forEach(c -> declared.add(c.name()));
        }
        declared.add(IndexColumn.IMPEDANCE_TEST_HZ.name());
        declared.add(Indexed.ATTRS);
        assertThat(columns).containsAll(declared);
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
