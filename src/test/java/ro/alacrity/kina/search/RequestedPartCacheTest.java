package ro.alacrity.kina.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import ro.alacrity.kina.TestcontainersConfiguration;
import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.cache.CachedSearch;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.cache.SearchCacheRepository;
import ro.alacrity.kina.distributor.Deadline;
import ro.alacrity.kina.distributor.DistributorRegistry;
import ro.alacrity.kina.distributor.PartLookupResult;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.DistributorResult;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PartResponse;
import ro.alacrity.kina.domain.SearchRequest;
import ro.alacrity.kina.domain.SearchResponse;
import ro.alacrity.kina.search.PartSearchServiceTest.FakeClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The outcome of a requested part-number lookup is part of the cached search (DESIGN.md 3.2 "Requested part numbers",
 * V10 {@code cached_searches.requested_parts}): a repeated explicit part-number search is served from the cache with
 * no distributor call, whether the lookup found the part in stock, listed without stock, or not at all; an expired list
 * looks it up again. Real PostgreSQL repositories, a fake Mouser.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RequestedPartCacheTest {

    static final String QUERY = "QQX12345 10uF X7R 0805";
    static final String TOKEN = "QQX12345";

    @Autowired
    PartCacheRepository partCache;

    @Autowired
    SearchCacheRepository searchCache;

    @Autowired
    JdbcClient jdbc;

    final MutableClock clock = new MutableClock(Instant.now().truncatedTo(ChronoUnit.MICROS));
    PartSearchService service;

    /** A clock the test moves forward. */
    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Mouser with keyword results that never carry the requested part, and a counted part-number lookup. */
    static class Mouser extends FakeClient {
        final AtomicInteger lookups = new AtomicInteger();
        final PartLookupResult answer;

        Mouser(List<Part> keywordResults, PartLookupResult answer) {
            super(Distributor.MOUSER);
            raw.addAll(keywordResults);
            this.answer = answer;
        }

        @Override
        public PartLookupResult lookup(String partNumber, Deadline deadline) {
            lookups.incrementAndGet();
            return answer;
        }
    }

    static Part keywordPart(String number) {
        return PartSearchServiceTest.part(Distributor.MOUSER, number).toBuilder().fetchedAt(null).build();
    }

    static Part requested(int stock) {
        return PartSearchServiceTest.part(Distributor.MOUSER, "65-" + TOKEN).toBuilder().manufacturerPartNumber(TOKEN)
                .stock(stock).fetchedAt(null).build();
    }

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

    SearchResponse search(Mouser mouser, String query) {
        if (service == null) {
            ParametricExtractor extractor = new ParametricExtractor();
            var props = RankingFixtures.properties("kina.ranking.cross-encoder.enabled", "false");
            RankingService ranking = new RankingService(props, new DeterministicRanker(extractor),
                    mock(PartRanker.class), () -> null, new RankingScoreCache(Duration.ofHours(1)));
            service = new PartSearchService(props, new DistributorRegistry(List.of(mouser)), new QueryParser(),
                    extractor, ranking, partCache, searchCache, clock);
        }
        return service.search(new SearchRequest(query, 5, Set.of(Distributor.MOUSER), false));
    }

    static DistributorResult mouser(SearchResponse response) {
        return PartSearchServiceTest.result(response, Distributor.MOUSER);
    }

    @Test
    void aRepeatedSearchForAPartFoundInStockMakesNoDistributorCall() {
        Mouser mouser = new Mouser(List.of(keywordPart("M1"), keywordPart("M2")),
                PartLookupResult.found(requested(500).toBuilder().fetchedAt(clock.instant()).build()));

        DistributorResult first = mouser(search(mouser, QUERY));
        assertThat(first.cache()).isEqualTo(CacheStatus.MISS);
        assertThat(first.parts().getFirst().partNumber()).isEqualTo("65-" + TOKEN);
        assertThat(mouser.calls).hasSize(1);
        assertThat(mouser.lookups).hasValue(1);
        CachedSearch row = searchCache.find(Distributor.MOUSER, QueryParser.normalizeKey(QUERY)).orElseThrow();
        assertThat(row.partNumbers()).containsExactly("M1", "M2", "65-" + TOKEN);   // appended to the list
        assertThat(row.requestedParts()).containsEntry(TOKEN,
                new CachedSearch.RequestedPart(CachedSearch.RequestedPart.FOUND, "65-" + TOKEN));

        DistributorResult again = mouser(search(mouser, QUERY));
        assertThat(again.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(mouser.calls).hasSize(1);          // no search call
        assertThat(mouser.lookups).hasValue(1);       // and no lookup
        assertThat(again.parts()).extracting(PartResponse::partNumber).isEqualTo(
                first.parts().stream().map(PartResponse::partNumber).toList());
        assertThat(again.requestedPartFound()).isTrue();
    }

    @Test
    void aRepeatedSearchForAPartNumberNotFoundMakesNoDistributorCall() {
        Mouser mouser = new Mouser(List.of(keywordPart("M1")), PartLookupResult.notFound());

        DistributorResult first = mouser(search(mouser, QUERY));
        assertThat(first.requestedPartFound()).isFalse();
        assertThat(mouser.lookups).hasValue(1);
        assertThat(searchCache.find(Distributor.MOUSER, QueryParser.normalizeKey(QUERY)).orElseThrow()
                .requestedParts()).containsEntry(TOKEN,
                new CachedSearch.RequestedPart(CachedSearch.RequestedPart.NOT_FOUND, null));

        DistributorResult again = mouser(search(mouser, QUERY));
        assertThat(again.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(mouser.calls).hasSize(1);
        assertThat(mouser.lookups).hasValue(1);
        assertThat(again.requestedPartFound()).isFalse();
        assertThat(again.hint()).isEqualTo(first.hint())
                .startsWith(TOKEN + " is not listed in stock at MOUSER");
    }

    @Test
    void aRepeatedSearchForAPartListedWithoutStockIsServedFromItsNotInStockRow() {
        Mouser mouser = new Mouser(List.of(keywordPart("M1")),
                PartLookupResult.outOfStock(new PartLookupResult.Identity("65-" + TOKEN, "YAGEO", TOKEN, null),
                        requested(0).toBuilder().fetchedAt(clock.instant()).build()));

        DistributorResult first = mouser(search(mouser, QUERY));
        assertThat(first.parts().getLast().partNumber()).isEqualTo("65-" + TOKEN);
        assertThat(first.parts().getLast().stock()).isZero();
        CachedSearch row = searchCache.find(Distributor.MOUSER, QueryParser.normalizeKey(QUERY)).orElseThrow();
        assertThat(row.partNumbers()).containsExactly("M1");   // the in-stock list never holds a stock-0 part
        assertThat(row.requestedParts()).containsEntry(TOKEN,
                new CachedSearch.RequestedPart(CachedSearch.RequestedPart.LISTED, "65-" + TOKEN));
        assertThat(jdbc.sql("SELECT in_stock FROM cached_parts WHERE part_number = ?").param("65-" + TOKEN)
                .query(Boolean.class).single()).isFalse();

        DistributorResult again = mouser(search(mouser, QUERY));
        assertThat(again.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(mouser.calls).hasSize(1);
        assertThat(mouser.lookups).hasValue(1);
        assertThat(again.parts()).extracting(PartResponse::partNumber).containsExactly("M1", "65-" + TOKEN);
        assertThat(again.parts().getLast().stock()).isZero();
        assertThat(again.parts().getLast().availability().status()).isEqualTo("out_of_stock");

        // a search that does not name the part number never sees the not-in-stock row
        DistributorResult keyword = mouser(search(mouser, "10uF X7R 0805"));
        assertThat(keyword.parts()).allSatisfy(p -> assertThat(p.stock()).isPositive());
        assertThat(keyword.parts()).extracting(PartResponse::partNumber).doesNotContain("65-" + TOKEN);
    }

    @Test
    void aListedPartFollowsThe24HourStockRefreshAndMayComeBackInStock() {
        List<List<String>> refreshed = new java.util.concurrent.CopyOnWriteArrayList<>();
        Mouser mouser = new Mouser(List.of(keywordPart("M1")),
                PartLookupResult.outOfStock(new PartLookupResult.Identity("65-" + TOKEN, "YAGEO", TOKEN, null),
                        requested(0).toBuilder().fetchedAt(clock.instant()).build())) {
            @Override
            public java.util.Map<String, ro.alacrity.kina.distributor.StockUpdate> refreshStock(
                    List<String> partNumbers, Deadline deadline) {
                refreshed.add(List.copyOf(partNumbers));
                return partNumbers.contains("65-" + TOKEN)
                        ? java.util.Map.of("65-" + TOKEN, new ro.alacrity.kina.distributor.StockUpdate(25, List.of()))
                        : java.util.Map.of();
            }
        };
        search(mouser, QUERY);
        assertThat(refreshed).isEmpty();   // fresh figures: nothing to refresh

        clock.now = clock.now.plus(Duration.ofHours(25));   // older than kina.cache.stock-ttl, list still fresh
        DistributorResult later = mouser(search(mouser, QUERY));
        assertThat(later.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(mouser.lookups).hasValue(1);
        assertThat(refreshed).anySatisfy(batch -> assertThat(batch).contains("65-" + TOKEN));
        PartResponse back = later.parts().stream().filter(p -> p.partNumber().equals("65-" + TOKEN)).findFirst()
                .orElseThrow();
        assertThat(back.stock()).isEqualTo(25);
        assertThat(later.requestedPartFound()).isTrue();
        assertThat(partCache.find(Distributor.MOUSER, "65-" + TOKEN)).hasValueSatisfying(p ->
                assertThat(p.stock()).isEqualTo(25));   // in stock again: served like any cached part
    }

    @Test
    void theListedRowIsReadOnlyForTheSearchThatNamesItsPartNumber() {
        partCache.upsertListed(List.of(requested(0).toBuilder().fetchedAt(clock.instant()).build()));
        Mouser mouser = new Mouser(List.of(keywordPart("M1")), PartLookupResult.notFound());
        RequestedLookup lookup = new RequestedLookup(new ParametricExtractor(), partCache, clock);
        var known = java.util.Map.of(TOKEN,
                new CachedSearch.RequestedPart(CachedSearch.RequestedPart.LISTED, "65-" + TOKEN));
        DistributorBudget budget = new DistributorBudget(Deadline.after(Duration.ofSeconds(5)), Duration.ofSeconds(5));
        Fetched fetched = new Fetched(Distributor.MOUSER, List.of(keywordPart("M1")), 1, CacheStatus.HIT, null);

        // the query names the part number: the row is read, no lookup
        Prepared named = service(mouser).prepare(new SearchRequest(QUERY, 5, Set.of(Distributor.MOUSER), false));
        assertThat(lookup.complete(mouser, named, fetched, budget, known).fetched().listed())
                .extracting(Part::distributorPartNumber).containsExactly("65-" + TOKEN);
        assertThat(mouser.lookups).hasValue(0);
        // a query that does not name it never gets the row, whatever outcomes it is handed
        Prepared other = service.prepare(new SearchRequest("10uF X7R 0805", 5, Set.of(Distributor.MOUSER), false));
        assertThat(lookup.complete(mouser, other, fetched, budget, known).fetched().listed()).isEmpty();
    }

    @Test
    void anExpiredListLooksThePartNumberUpAgain() {
        Mouser mouser = new Mouser(List.of(keywordPart("M1")), PartLookupResult.notFound());
        search(mouser, QUERY);
        assertThat(mouser.lookups).hasValue(1);

        clock.now = clock.now.plus(Duration.ofDays(4));   // beyond kina.cache.ttl (3 days)
        DistributorResult later = mouser(search(mouser, QUERY));
        assertThat(later.cache()).isEqualTo(CacheStatus.MISS);
        assertThat(mouser.calls).hasSize(2);
        assertThat(mouser.lookups).hasValue(2);
    }

    @Test
    void anEmptyListKeepsItsLookupOutcomeForTheEmptyResultTtlOnly() {
        Mouser mouser = new Mouser(List.of(), PartLookupResult.notFound());
        search(mouser, QUERY);   // the phrase and the relaxation ladder find nothing: an empty list is cached
        int calls = mouser.calls.size();
        assertThat(mouser.lookups).hasValue(1);
        DistributorResult again = mouser(search(mouser, QUERY));
        assertThat(again.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(mouser.calls).hasSize(calls);
        assertThat(mouser.lookups).hasValue(1);

        clock.now = clock.now.plus(Duration.ofHours(2));   // beyond kina.cache.empty-result-ttl (1 hour)
        search(mouser, QUERY);
        assertThat(mouser.calls).hasSizeGreaterThan(calls);
        assertThat(mouser.lookups).hasValue(2);
    }

    private PartSearchService service(Mouser mouser) {
        search(mouser, "warm up");
        return service;
    }
}
