package ro.alacrity.kina.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.cache.CachedSearch;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.cache.SearchCacheRepository;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.Deadline;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.distributor.DistributorRegistry;
import ro.alacrity.kina.distributor.DistributorSearchPage;
import ro.alacrity.kina.domain.BatchSearchRequest;
import ro.alacrity.kina.domain.BatchSearchResponse;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.DistributorResult;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PartKey;
import ro.alacrity.kina.domain.PriceBreak;
import ro.alacrity.kina.domain.RankingMode;
import ro.alacrity.kina.domain.SearchRequest;
import ro.alacrity.kina.domain.SearchResponse;
import ro.alacrity.kina.search.RankingService.RankedPart;
import ro.alacrity.kina.search.RankingService.RankedResults;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PartSearchServiceTest {

    static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    static final String QUERY = "10uF X7R 0805";

    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final Map<String, Part> cachedParts = new ConcurrentHashMap<>();
    final Map<String, CachedSearch> cachedSearches = new ConcurrentHashMap<>();
    final List<Map<Distributor, List<Part>>> rankedInputs = new CopyOnWriteArrayList<>();
    final List<Duration> rankBudgets = new CopyOnWriteArrayList<>();
    PartSearchService service;

    // ---- fakes ----------------------------------------------------------------------------------------------------

    /** Raw distributor records; null entries are records without ships-now stock (dropped by the client). */
    static class FakeClient implements DistributorClient {
        final Distributor distributor;
        final List<Part> raw = new ArrayList<>();
        final List<int[]> calls = new CopyOnWriteArrayList<>();
        boolean configured = true;
        int pageSize = 50;
        long delayMillis;
        RuntimeException failure;
        Integer failOnCall;
        /** Queries this distributor finds nothing for (every other query returns {@link #raw}). */
        final Set<String> emptyFor = new java.util.HashSet<>();
        final List<String> queries = new CopyOnWriteArrayList<>();

        FakeClient(Distributor distributor) {
            this.distributor = distributor;
        }

        FakeClient records(int count, IntFunction<Part> factory) {
            for (int i = 0; i < count; i++) {
                raw.add(factory.apply(i));
            }
            return this;
        }

        @Override
        public Distributor distributor() {
            return distributor;
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public int maxPageSize() {
            return pageSize;
        }

        @Override
        public DistributorSearchPage search(String query, int offset, int limit) {
            calls.add(new int[] {offset, limit});
            queries.add(query);
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new DistributorException(distributor, DistributorException.Kind.TIMEOUT, "interrupted");
                }
            }
            if (failure != null && (failOnCall == null || failOnCall == calls.size())) {
                throw failure;
            }
            if (emptyFor.contains(query)) {
                return new DistributorSearchPage(List.of(), 0, false);
            }
            int to = Math.min(raw.size(), offset + limit);
            List<Part> parts = offset >= raw.size() ? List.of()
                    : raw.subList(offset, to).stream().filter(p -> p != null).toList();
            return new DistributorSearchPage(parts, raw.size(), to < raw.size());
        }

        @Override
        public Optional<Part> getPart(String distributorPartNumber) {
            return raw.stream().filter(p -> p != null && p.distributorPartNumber().equals(distributorPartNumber))
                    .findFirst();
        }

        List<Integer> offsets() {
            return calls.stream().map(c -> c[0]).toList();
        }
    }

    /**
     * Emulates {@link ro.alacrity.kina.distributor.RateLimitRetry} inside a client: the listed calls (1-based) are
     * rate limited and wait {@link #wait} (recorded on the deadline, then really slept) when it fits the deadline,
     * else fail with {@code RATE_LIMITED}. {@link #ignoreDeadline} makes it wait regardless (a misbehaving client).
     */
    static class RateLimitedClient extends FakeClient {
        final Set<Integer> rateLimitedCalls = new java.util.HashSet<>();
        final List<Deadline> deadlines = new CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();
        Duration wait = Duration.ofMillis(500);
        boolean ignoreDeadline;

        RateLimitedClient(Distributor distributor, Integer... rateLimited) {
            super(distributor);
            rateLimitedCalls.addAll(List.of(rateLimited));
        }

        @Override
        public DistributorSearchPage search(String query, int offset, int limit, Deadline deadline) {
            deadlines.add(deadline);
            if (rateLimitedCalls.contains(callCount.incrementAndGet())) {
                if (!ignoreDeadline && !deadline.fits(wait.toNanos())) {
                    throw new DistributorException(distributor, DistributorException.Kind.RATE_LIMITED,
                            "next retry would exceed the request deadline", null, 0);
                }
                deadline.recordWait(deadline.nanoTime(), wait.toNanos());
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new DistributorException(distributor, DistributorException.Kind.RATE_LIMITED, "interrupted");
                }
            }
            return super.search(query, offset, limit);
        }
    }

    static Part part(Distributor d, String number) {
        return new Part(d, number, "YAGEO", "MPN-" + number, "MLCC 10uF 25V X7R 0805 10%", "Capacitors", "0805",
                1000, 1, 1, List.of(
                new PriceBreak(1000, new BigDecimal("0.02"), "EUR"),
                new PriceBreak(1, new BigDecimal("0.10"), "EUR"),
                new PriceBreak(100, new BigDecimal("0.04"), "EUR"),
                new PriceBreak(10, new BigDecimal("0.07"), "EUR"),
                new PriceBreak(5000, new BigDecimal("0.01"), "EUR")),
                null, null, "https://example.invalid/" + number, Map.of(), Map.of(), NOW);
    }

    PartSearchService service(List<? extends DistributorClient> clients, String... properties) {
        KinaProperties props = RankingFixtures.properties(properties);
        PartCacheRepository partCache = mock(PartCacheRepository.class);
        doAnswer(inv -> {
            Collection<Part> parts = inv.getArgument(0);
            parts.forEach(p -> cachedParts.put(p.key(), p));
            return null;
        }).when(partCache).upsertAll(anyCollection());
        when(partCache.findFresh(any(), anyCollection(), any())).thenAnswer(inv -> {
            Distributor d = inv.getArgument(0);
            Collection<String> numbers = inv.getArgument(1);
            Instant since = inv.getArgument(2);
            Map<String, Part> out = new java.util.HashMap<>();
            for (String n : numbers) {
                Part p = cachedParts.get(PartKey.of(d, n));
                if (p != null && !p.fetchedAt().isBefore(since)) {
                    out.put(n, p);
                }
            }
            return out;
        });
        SearchCacheRepository searchCache = mock(SearchCacheRepository.class);
        when(searchCache.find(any(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(cachedSearches.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        doAnswer(inv -> {
            CachedSearch s = inv.getArgument(0);
            cachedSearches.put(s.distributor() + "|" + s.queryKey(), s);
            return null;
        }).when(searchCache).upsert(any());

        RankingService ranking = mock(RankingService.class);
        when(ranking.rank(any(), anyMap(), any())).thenAnswer(inv -> {
            Map<Distributor, List<Part>> input = inv.getArgument(1);
            Duration budget = inv.getArgument(2);
            rankedInputs.add(input);
            rankBudgets.add(budget == null ? Duration.ofSeconds(-1) : budget);
            Map<Distributor, List<RankedPart>> out = new EnumMap<>(Distributor.class);
            input.forEach((d, parts) -> {
                List<RankedPart> ranked = new ArrayList<>();
                for (int i = 0; i < parts.size(); i++) {
                    ranked.add(new RankedPart(parts.get(i), 0.9 - i * 0.001));
                }
                out.put(d, ranked);
            });
            if (Duration.ZERO.equals(budget)) {
                return new RankedResults(out, RankingMode.FALLBACK, "laya timeout: budget exhausted");
            }
            return new RankedResults(out, RankingMode.LAYA, null);
        });

        service = new PartSearchService(props, new DistributorRegistry(List.copyOf(clients)), new QueryParser(),
                new ParametricExtractor(), ranking, partCache, searchCache, clock);
        return service;
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    static SearchRequest request(int maxResults, Distributor... distributors) {
        return new SearchRequest(QUERY, maxResults, Set.of(distributors), false);
    }

    static DistributorResult result(SearchResponse response, Distributor d) {
        return response.distributors().stream().filter(r -> r.distributor() == d).findFirst().orElseThrow();
    }

    static String key(Distributor d) {
        return d + "|" + QueryParser.normalizeKey(QUERY);
    }

    // ---- cache ----------------------------------------------------------------------------------------------------

    @Test
    void missFetchesAndCachesThenLargerMaxResultsIsServedFromCache() {
        FakeClient mouser = new FakeClient(Distributor.MOUSER).records(113, i -> part(Distributor.MOUSER, "M" + i));
        service(List.of(mouser));

        SearchResponse first = service.search(request(5, Distributor.MOUSER));
        DistributorResult r1 = result(first, Distributor.MOUSER);
        assertThat(r1.cache()).isEqualTo(CacheStatus.MISS);
        assertThat(r1.totalResults()).isEqualTo(113);
        assertThat(r1.fetched()).isEqualTo(50);       // one Mouser page of 50 raw records
        assertThat(r1.returned()).isEqualTo(5);
        assertThat(r1.parts()).extracting(p -> p.rank()).containsExactly(1, 2, 3, 4, 5);
        assertThat(mouser.calls).hasSize(1);
        assertThat(mouser.calls.getFirst()).containsExactly(0, 50);
        CachedSearch stored = cachedSearches.get(key(Distributor.MOUSER));
        assertThat(stored.partNumbers()).hasSize(50);
        assertThat(stored.exhausted()).isFalse();
        assertThat(stored.nextOffset()).isEqualTo(50);
        assertThat(stored.fetchedAt()).isEqualTo(NOW);
        assertThat(cachedParts).hasSize(50);

        SearchResponse second = service.search(request(20, Distributor.MOUSER));
        DistributorResult r2 = result(second, Distributor.MOUSER);
        assertThat(r2.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(r2.returned()).isEqualTo(20);
        assertThat(r2.fetched()).isEqualTo(50);
        assertThat(r2.totalResults()).isEqualTo(113);
        assertThat(mouser.calls).hasSize(1);          // no further distributor call
        assertThat(r2.parts().getFirst().partNumber()).isEqualTo("M0");
    }

    @Test
    void shortCachedListThatCannotServeMaxResultsIsExtendedFromItsNextOffset() {
        // the first raw page (50 records) holds only 10 parts with ships-now stock
        FakeClient mouser = new FakeClient(Distributor.MOUSER)
                .records(150, i -> i < 50 && i % 5 != 0 ? null : part(Distributor.MOUSER, "M" + i));
        service(List.of(mouser));

        DistributorResult first = result(service.search(request(5, Distributor.MOUSER)), Distributor.MOUSER);
        assertThat(first.cache()).isEqualTo(CacheStatus.MISS);
        assertThat(first.fetched()).isEqualTo(10);

        // 10 cached >= 5 requested: still a hit although the cached list is shorter than the window
        DistributorResult again = result(service.search(request(5, Distributor.MOUSER)), Distributor.MOUSER);
        assertThat(again.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(mouser.calls).hasSize(1);

        DistributorResult partial = result(service.search(request(20, Distributor.MOUSER)), Distributor.MOUSER);
        assertThat(partial.cache()).isEqualTo(CacheStatus.PARTIAL);
        assertThat(mouser.offsets()).containsExactly(0, 50);   // continues at the raw offset, not at 10
        assertThat(partial.fetched()).isEqualTo(60);
        assertThat(partial.returned()).isEqualTo(20);
        CachedSearch stored = cachedSearches.get(key(Distributor.MOUSER));
        assertThat(stored.partNumbers()).hasSize(60).startsWith("M0", "M5", "M10");
        assertThat(stored.nextOffset()).isEqualTo(100);
        assertThat(stored.fetchedAt()).isEqualTo(NOW);
    }

    @Test
    void bypassCacheSkipsTheLookupButRefreshesTheCache() {
        FakeClient tme = new FakeClient(Distributor.TME).records(30, i -> part(Distributor.TME, "T" + i));
        service(List.of(tme));
        service.search(request(10, Distributor.TME));
        assertThat(tme.calls).hasSize(1);
        cachedParts.clear();
        cachedSearches.clear();

        SearchResponse response = service.search(new SearchRequest(QUERY, 10, Set.of(Distributor.TME), true));

        DistributorResult r = result(response, Distributor.TME);
        assertThat(r.cache()).isEqualTo(CacheStatus.BYPASSED);
        assertThat(tme.calls).hasSize(2);
        assertThat(cachedSearches.get(key(Distributor.TME)).exhausted()).isTrue();
        assertThat(cachedParts).hasSize(30);
    }

    @Test
    void staleOrIncompleteCacheIsAMiss() {
        FakeClient tme = new FakeClient(Distributor.TME).records(5, i -> part(Distributor.TME, "T" + i));
        service(List.of(tme));
        cachedSearches.put(key(Distributor.TME), new CachedSearch(Distributor.TME, QueryParser.normalizeKey(QUERY),
                5, List.of("T0", "T1"), true, NOW.minus(Duration.ofDays(6))));
        assertThat(result(service.search(request(10, Distributor.TME)), Distributor.TME).cache())
                .isEqualTo(CacheStatus.MISS);

        // fresh list, but one of its parts is no longer in cached_parts
        cachedParts.remove(PartKey.of(Distributor.TME, "T3"));
        assertThat(result(service.search(request(10, Distributor.TME)), Distributor.TME).cache())
                .isEqualTo(CacheStatus.MISS);
        assertThat(tme.calls).hasSize(2);
        assertThat(result(service.search(request(10, Distributor.TME)), Distributor.TME).cache())
                .isEqualTo(CacheStatus.HIT);
    }

    @Test
    void lcscIsNeverCachedInPostgres() {
        FakeClient lcsc = new FakeClient(Distributor.LCSC).records(300, i -> part(Distributor.LCSC, "C" + i));
        lcsc.pageSize = 200;
        service(List.of(lcsc));

        DistributorResult r = result(service.search(request(10, Distributor.LCSC)), Distributor.LCSC);
        DistributorResult again = result(service.search(request(10, Distributor.LCSC)), Distributor.LCSC);

        assertThat(r.cache()).isEqualTo(CacheStatus.NOT_APPLICABLE);
        assertThat(again.cache()).isEqualTo(CacheStatus.NOT_APPLICABLE);
        assertThat(r.fetched()).isEqualTo(40);   // window = candidate-window, one SQLite query
        assertThat(lcsc.calls).hasSize(2);
        assertThat(lcsc.calls.getFirst()).containsExactly(0, 40);
        assertThat(cachedParts).isEmpty();
        assertThat(cachedSearches).isEmpty();
    }

    @Test
    void emptyCachedSearchExpiresAfterTheEmptyResultTtl() {
        FakeClient tme = new FakeClient(Distributor.TME).records(5, i -> part(Distributor.TME, "T" + i));
        service(List.of(tme));
        String key = QueryParser.normalizeKey(QUERY);
        // an empty list fetched 30 minutes ago is still fresh (default empty-result-ttl 1h)
        cachedSearches.put(key(Distributor.TME), new CachedSearch(Distributor.TME, key, 0, List.of(), true,
                NOW.minus(Duration.ofMinutes(30))));
        DistributorResult fresh = result(service.search(request(10, Distributor.TME)), Distributor.TME);
        assertThat(fresh.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(fresh.fetched()).isZero();
        assertThat(tme.calls).isEmpty();

        // two hours old: stale although far younger than the 5 day ttl
        cachedSearches.put(key(Distributor.TME), new CachedSearch(Distributor.TME, key, 0, List.of(), true,
                NOW.minus(Duration.ofHours(2))));
        DistributorResult stale = result(service.search(request(10, Distributor.TME)), Distributor.TME);
        assertThat(stale.cache()).isEqualTo(CacheStatus.MISS);
        assertThat(stale.fetched()).isEqualTo(5);
        assertThat(tme.calls).hasSize(1);
    }

    @Test
    void nonEmptyCachedSearchKeepsTheFullTtlAndEmptyResultTtlIsConfigurable() {
        FakeClient tme = new FakeClient(Distributor.TME).records(5, i -> part(Distributor.TME, "T" + i));
        service(List.of(tme), "kina.cache.empty-result-ttl", "10m");
        String key = QueryParser.normalizeKey(QUERY);
        service.search(request(10, Distributor.TME));
        // a non-empty list written 2 days ago is still a hit
        CachedSearch stored = cachedSearches.get(key(Distributor.TME));
        cachedSearches.put(key(Distributor.TME), new CachedSearch(Distributor.TME, key, stored.totalResults(),
                stored.partNumbers(), true, NOW.minus(Duration.ofDays(2))));
        assertThat(result(service.search(request(10, Distributor.TME)), Distributor.TME).cache())
                .isEqualTo(CacheStatus.HIT);

        cachedSearches.put(key(Distributor.TME), new CachedSearch(Distributor.TME, key, 0, List.of(), true,
                NOW.minus(Duration.ofMinutes(11))));
        assertThat(result(service.search(request(10, Distributor.TME)), Distributor.TME).cache())
                .isEqualTo(CacheStatus.MISS);
        assertThat(tme.calls).hasSize(2);
    }

    // ---- phrase fallback ------------------------------------------------------------------------------------------

    static final String MOSFET_QUERY = "SOT-23 N-channel MOSFET 30V";

    @Test
    void corePhraseKeepsFamilyValuesDielectricAndPackage() {
        QueryParser parser = new QueryParser();
        assertThat(PartSearchService.corePhrase(parser.parse(MOSFET_QUERY))).isEqualTo("MOSFET 30V SOT-23");
        assertThat(PartSearchService.corePhrase(parser.parse("MLCC 10uF 25V X7R 0805 ceramic low ESR")))
                .isEqualTo("MLCC 10uF 25V X7R 0805");
        // nothing to drop, no parametric core, single term
        assertThat(PartSearchService.corePhrase(parser.parse(QUERY))).isNull();
        assertThat(PartSearchService.corePhrase(parser.parse("USB type C receptacle"))).isNull();
        assertThat(PartSearchService.corePhrase(parser.parse("0805 something exotic"))).isNull();
    }

    @Test
    void apiDistributorWithNoResultsIsRetriedOnceWithTheCorePhrase() {
        FakeClient tme = new FakeClient(Distributor.TME).records(8, i -> part(Distributor.TME, "T" + i));
        tme.emptyFor.add(MOSFET_QUERY);
        FakeClient mouser = new FakeClient(Distributor.MOUSER).records(3, i -> part(Distributor.MOUSER, "M" + i));
        service(List.of(tme, mouser));

        SearchResponse response = service.search(new SearchRequest(MOSFET_QUERY, 5, Set.of(), false));

        DistributorResult t = result(response, Distributor.TME);
        assertThat(tme.queries).containsExactly(MOSFET_QUERY, "MOSFET 30V SOT-23");
        assertThat(t.fallbackQuery()).isEqualTo("MOSFET 30V SOT-23");
        assertThat(t.fetched()).isEqualTo(8);
        assertThat(t.returned()).isEqualTo(5);
        assertThat(t.cache()).isEqualTo(CacheStatus.MISS);
        // a distributor that answered the full query is not retried
        DistributorResult m = result(response, Distributor.MOUSER);
        assertThat(mouser.queries).containsExactly(MOSFET_QUERY);
        assertThat(m.fallbackQuery()).isNull();

        // cached under the original query key, with the phrase that was searched
        CachedSearch stored = cachedSearches.get(Distributor.TME + "|" + QueryParser.normalizeKey(MOSFET_QUERY));
        assertThat(stored.fallbackQuery()).isEqualTo("MOSFET 30V SOT-23");
        assertThat(stored.partNumbers()).hasSize(8);

        DistributorResult again = result(service.search(new SearchRequest(MOSFET_QUERY, 5, Set.of(Distributor.TME),
                false)), Distributor.TME);
        assertThat(again.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(again.fallbackQuery()).isEqualTo("MOSFET 30V SOT-23");
        assertThat(tme.queries).hasSize(2);
    }

    @Test
    void partialExtensionOfAFallbackListUsesTheFallbackPhrase() {
        FakeClient mouser = new FakeClient(Distributor.MOUSER)
                .records(150, i -> i < 50 && i % 5 != 0 ? null : part(Distributor.MOUSER, "M" + i));
        mouser.emptyFor.add(MOSFET_QUERY);
        service(List.of(mouser));

        service.search(new SearchRequest(MOSFET_QUERY, 5, Set.of(Distributor.MOUSER), false));
        DistributorResult partial = result(service.search(new SearchRequest(MOSFET_QUERY, 20,
                Set.of(Distributor.MOUSER), false)), Distributor.MOUSER);

        assertThat(partial.cache()).isEqualTo(CacheStatus.PARTIAL);
        assertThat(partial.fallbackQuery()).isEqualTo("MOSFET 30V SOT-23");
        assertThat(mouser.queries).containsExactly(MOSFET_QUERY, "MOSFET 30V SOT-23", "MOSFET 30V SOT-23");
        assertThat(mouser.offsets()).containsExactly(0, 0, 50);
    }

    @Test
    void noFallbackForLcscOrWhenTheFallbackAlsoFindsNothing() {
        FakeClient lcsc = new FakeClient(Distributor.LCSC).records(3, i -> part(Distributor.LCSC, "C" + i));
        lcsc.pageSize = 200;
        lcsc.emptyFor.add(MOSFET_QUERY);
        FakeClient tme = new FakeClient(Distributor.TME);   // finds nothing for anything
        service(List.of(lcsc, tme));

        SearchResponse response = service.search(new SearchRequest(MOSFET_QUERY, 5, Set.of(), false));

        assertThat(lcsc.queries).containsExactly(MOSFET_QUERY);
        assertThat(result(response, Distributor.LCSC).fallbackQuery()).isNull();
        assertThat(result(response, Distributor.LCSC).fetched()).isZero();
        assertThat(tme.queries).containsExactly(MOSFET_QUERY, "MOSFET 30V SOT-23");
        DistributorResult t = result(response, Distributor.TME);
        assertThat(t.fetched()).isZero();
        assertThat(t.fallbackQuery()).isEqualTo("MOSFET 30V SOT-23");
        // the empty result is cached (and expires after kina.cache.empty-result-ttl)
        assertThat(cachedSearches.get(Distributor.TME + "|" + QueryParser.normalizeKey(MOSFET_QUERY)).partNumbers())
                .isEmpty();
    }

    @Test
    void failedFirstPageIsNotRetriedWithTheCorePhrase() {
        FakeClient tme = new FakeClient(Distributor.TME).records(5, i -> part(Distributor.TME, "T" + i));
        tme.failure = new DistributorException(Distributor.TME, DistributorException.Kind.RATE_LIMITED, "429");
        service(List.of(tme));

        DistributorResult t = result(service.search(new SearchRequest(MOSFET_QUERY, 5, Set.of(), false)),
                Distributor.TME);

        assertThat(t.error()).isEqualTo("rate_limited");
        assertThat(t.fallbackQuery()).isNull();
        assertThat(tme.queries).containsExactly(MOSFET_QUERY);
    }

    // ---- paging ---------------------------------------------------------------------------------------------------

    @Test
    void pagesByRawOffsetUntilWindowOrPageLimit() {
        // TME page size 20, every other record without stock: 10 parts per page
        FakeClient tme = new FakeClient(Distributor.TME)
                .records(200, i -> i % 2 == 0 ? part(Distributor.TME, "T" + i) : null);
        tme.pageSize = 20;
        service(List.of(tme));

        DistributorResult r = result(service.search(request(10, Distributor.TME)), Distributor.TME);

        assertThat(tme.calls).extracting(c -> c[0] + "+" + c[1]).containsExactly("0+20", "20+20", "40+20");
        assertThat(r.fetched()).isEqualTo(30);   // max-pages-per-search (3) reached before the window (40)
        assertThat(r.totalResults()).isEqualTo(200);
        CachedSearch stored = cachedSearches.get(key(Distributor.TME));
        assertThat(stored.exhausted()).isFalse();
        assertThat(stored.nextOffset()).isEqualTo(60);
    }

    @Test
    void stopsPagingWhenTheWindowIsFull() {
        FakeClient tme = new FakeClient(Distributor.TME).records(200, i -> part(Distributor.TME, "T" + i));
        tme.pageSize = 25;
        service(List.of(tme));

        DistributorResult r = result(service.search(request(10, Distributor.TME)), Distributor.TME);

        assertThat(tme.offsets()).containsExactly(0, 25);
        assertThat(r.fetched()).isEqualTo(50);
    }

    @Test
    void failureOnALaterPageKeepsCollectedPartsAndReportsTheError() {
        FakeClient tme = new FakeClient(Distributor.TME).records(200, i -> part(Distributor.TME, "T" + i));
        tme.pageSize = 20;
        tme.failure = new DistributorException(Distributor.TME, DistributorException.Kind.RATE_LIMITED, "429");
        tme.failOnCall = 2;
        service(List.of(tme));

        DistributorResult r = result(service.search(request(10, Distributor.TME)), Distributor.TME);

        assertThat(r.error()).isEqualTo("rate_limited");
        assertThat(r.fetched()).isEqualTo(20);
        assertThat(r.returned()).isEqualTo(10);
    }

    // ---- errors and isolation -------------------------------------------------------------------------------------

    @Test
    void slowDistributorTimesOutWithoutDelayingTheOthers() {
        FakeClient mouser = new FakeClient(Distributor.MOUSER).records(10, i -> part(Distributor.MOUSER, "M" + i));
        FakeClient tme = new FakeClient(Distributor.TME).records(10, i -> part(Distributor.TME, "T" + i));
        tme.delayMillis = 5_000;
        service(List.of(mouser, tme), "kina.search.distributor-timeout", "300ms");

        long started = System.nanoTime();
        SearchResponse response = service.search(request(10));
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took).isLessThan(Duration.ofSeconds(3));
        assertThat(result(response, Distributor.TME).error()).isEqualTo("timeout");
        assertThat(result(response, Distributor.TME).parts()).isEmpty();
        assertThat(result(response, Distributor.TME).cache()).isEqualTo(CacheStatus.MISS);
        assertThat(result(response, Distributor.MOUSER).error()).isNull();
        assertThat(result(response, Distributor.MOUSER).returned()).isEqualTo(10);
    }

    @Test
    void rateLimitedDistributorReportsItsErrorCode() {
        FakeClient mouser = new FakeClient(Distributor.MOUSER);
        mouser.failure = new DistributorException(Distributor.MOUSER, DistributorException.Kind.RATE_LIMITED, "429");
        FakeClient lcsc = new FakeClient(Distributor.LCSC).records(3, i -> part(Distributor.LCSC, "C" + i));
        service(List.of(mouser, lcsc));

        SearchResponse response = service.search(request(10));

        DistributorResult m = result(response, Distributor.MOUSER);
        assertThat(m.error()).isEqualTo("rate_limited");
        assertThat(m.parts()).isEmpty();
        assertThat(m.totalResults()).isNull();
        assertThat(result(response, Distributor.LCSC).returned()).isEqualTo(3);
        assertThat(cachedSearches).doesNotContainKey(key(Distributor.MOUSER));
    }

    @Test
    void unexpectedClientExceptionIsReportedAsUnavailable() {
        FakeClient tme = new FakeClient(Distributor.TME);
        tme.failure = new IllegalStateException("boom");
        service(List.of(tme));

        assertThat(result(service.search(request(10, Distributor.TME)), Distributor.TME).error())
                .isEqualTo("unavailable");
    }

    @Test
    void unconfiguredDistributorsAreSkippedByDefaultAndReportedWhenRequested() {
        FakeClient mouser = new FakeClient(Distributor.MOUSER).records(3, i -> part(Distributor.MOUSER, "M" + i));
        mouser.configured = false;
        FakeClient lcsc = new FakeClient(Distributor.LCSC).records(3, i -> part(Distributor.LCSC, "C" + i));
        service(List.of(mouser, lcsc));

        SearchResponse defaults = service.search(request(10));
        assertThat(defaults.distributors()).extracting(DistributorResult::distributor)
                .containsExactly(Distributor.LCSC);

        SearchResponse explicit = service.search(request(10, Distributor.MOUSER, Distributor.TME, Distributor.LCSC));
        assertThat(explicit.distributors()).extracting(DistributorResult::distributor)
                .containsExactly(Distributor.LCSC, Distributor.TME, Distributor.MOUSER);
        for (Distributor d : List.of(Distributor.MOUSER, Distributor.TME)) {   // TME has no client bean at all
            DistributorResult r = result(explicit, d);
            assertThat(r.error()).isEqualTo("not_configured");
            assertThat(r.cache()).isEqualTo(CacheStatus.NOT_APPLICABLE);
            assertThat(r.parts()).isEmpty();
        }
        assertThat(mouser.calls).isEmpty();
    }

    // ---- request handling and response shape ----------------------------------------------------------------------

    @Test
    void maxResultsIsClampedAndDefaulted() {
        FakeClient lcsc = new FakeClient(Distributor.LCSC).records(300, i -> part(Distributor.LCSC, "C" + i));
        lcsc.pageSize = 200;
        service(List.of(lcsc));

        DistributorResult clamped = result(service.search(request(500, Distributor.LCSC)), Distributor.LCSC);
        assertThat(clamped.returned()).isEqualTo(50);
        assertThat(lcsc.calls.getFirst()).containsExactly(0, 50);

        DistributorResult defaulted = result(service.search(request(0, Distributor.LCSC)), Distributor.LCSC);
        assertThat(defaulted.returned()).isEqualTo(10);

        assertThatThrownBy(() -> service.search(SearchRequest.of("   ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pricesAreTrimmedToTheThreeSmallestBracketsAndResponseCarriesParsedQuery() {
        FakeClient mouser = new FakeClient(Distributor.MOUSER).records(2, i -> part(Distributor.MOUSER, "M" + i));
        service(List.of(mouser));

        SearchResponse response = service.search(request(10, Distributor.MOUSER));

        assertThat(response.query()).isEqualTo(QUERY);
        assertThat(response.ranking()).isEqualTo(RankingMode.LAYA);
        assertThat(response.parsed().dielectric()).isEqualTo("X7R");
        assertThat(response.parsed().packageName()).isEqualTo("0805");
        var part = result(response, Distributor.MOUSER).parts().getFirst();
        assertThat(part.prices()).extracting(p -> p.qty()).containsExactly(1, 10, 100);
        assertThat(part.score()).isEqualTo(0.9);
        // the cache keeps the complete list
        assertThat(cachedParts.get(PartKey.of(Distributor.MOUSER, "M0")).prices()).hasSize(5);
    }

    @Test
    void partsAreEnrichedBeforeRankingAndCaching() {
        FakeClient mouser = new FakeClient(Distributor.MOUSER).records(3, i -> part(Distributor.MOUSER, "M" + i));
        service(List.of(mouser));

        SearchResponse response = service.search(request(10, Distributor.MOUSER));

        Part ranked = rankedInputs.getFirst().get(Distributor.MOUSER).getFirst();
        assertThat(ranked.attributes()).containsEntry(ParametricExtractor.CAPACITANCE, "10uF")
                .containsEntry(ParametricExtractor.DIELECTRIC, "X7R")
                .containsEntry(ParametricExtractor.PACKAGE, "0805");
        assertThat(cachedParts.get(PartKey.of(Distributor.MOUSER, "M0")).attributes())
                .containsKey(ParametricExtractor.CAPACITANCE);
        assertThat(result(response, Distributor.MOUSER).parts().getFirst().attributes())
                .containsEntry(ParametricExtractor.VOLTAGE, "25V");
        assertThat(rankBudgets).containsExactly(Duration.ofSeconds(-1));   // null budget = kina.ranking.timeout
    }

    // ---- batch ----------------------------------------------------------------------------------------------------

    @Test
    void batchRanksEachQueryAndFallsBackWhenTheBatchBudgetIsUsedUp() {
        FakeClient lcsc = new FakeClient(Distributor.LCSC).records(5, i -> part(Distributor.LCSC, "C" + i));
        // the first ranking call consumes the whole batch budget
        RankingService slow = rankingThatSleepsOnFirstCall(Duration.ofMillis(700));
        service = new PartSearchService(RankingFixtures.properties("kina.ranking.batch-timeout", "600ms"),
                new DistributorRegistry(List.of(lcsc)), new QueryParser(), new ParametricExtractor(), slow,
                mock(PartCacheRepository.class), mock(SearchCacheRepository.class), clock);

        BatchSearchResponse response = service.searchBatch(BatchSearchRequest.of(List.of(
                SearchRequest.of("10uF X7R 0805"), SearchRequest.of("100nF 0603"), SearchRequest.of("4k7 0603")),
                null, null));

        assertThat(response.results()).hasSize(3);
        assertThat(response.results()).extracting(SearchResponse::query)
                .containsExactly("10uF X7R 0805", "100nF 0603", "4k7 0603");
        assertThat(response.results().get(0).ranking()).isEqualTo(RankingMode.LAYA);
        assertThat(rankBudgets.getFirst()).isLessThanOrEqualTo(Duration.ofMillis(600)).isPositive();
        for (SearchResponse later : response.results().subList(1, 3)) {
            assertThat(later.ranking()).isEqualTo(RankingMode.FALLBACK);
            assertThat(later.rankingNote()).isEqualTo("batch ranking budget of 600ms exhausted");
            assertThat(result(later, Distributor.LCSC).returned()).isEqualTo(5);
        }
        assertThat(rankBudgets.subList(1, 3)).containsOnly(Duration.ZERO);
    }

    @Test
    void batchWithLayaDisabledKeepsTheLayaDisabledNoteWhenTheBudgetIsUsedUp() {
        FakeClient lcsc = new FakeClient(Distributor.LCSC).records(5, i -> part(Distributor.LCSC, "C" + i));
        KinaProperties props = RankingFixtures.properties("kina.ranking.batch-timeout", "0s",
                "kina.ranking.laya.enabled", "false");
        ParametricExtractor extractor = new ParametricExtractor();
        RankingService ranking = new RankingService(props, new DeterministicRanker(extractor),
                mock(PartRanker.class), () -> true, new RankingScoreCache(Duration.ofHours(1)));
        service = new PartSearchService(props, new DistributorRegistry(List.of(lcsc)), new QueryParser(), extractor,
                ranking, mock(PartCacheRepository.class), mock(SearchCacheRepository.class), clock);

        BatchSearchResponse response = service.searchBatch(BatchSearchRequest.of(List.of(
                SearchRequest.of("10uF X7R 0805"), SearchRequest.of("100nF 0603")), null, null));

        assertThat(response.results()).allSatisfy(r -> {
            assertThat(r.ranking()).isEqualTo(RankingMode.FALLBACK);
            assertThat(r.rankingNote()).isEqualTo("laya disabled");
        });
    }

    @Test
    void batchAppliesSharedDistributorsAndRejectsEmptyOrOversizedBatches() {
        FakeClient lcsc = new FakeClient(Distributor.LCSC).records(5, i -> part(Distributor.LCSC, "C" + i));
        FakeClient mouser = new FakeClient(Distributor.MOUSER).records(5, i -> part(Distributor.MOUSER, "M" + i));
        service(List.of(lcsc, mouser));

        BatchSearchResponse response = service.searchBatch(BatchSearchRequest.of(List.of(
                new SearchRequest("10uF", 2, Set.of(), false), new SearchRequest("1k 0603", 3, Set.of(), false)),
                Set.of(Distributor.LCSC), false));

        assertThat(response.results()).allSatisfy(r -> assertThat(r.distributors())
                .extracting(DistributorResult::distributor).containsExactly(Distributor.LCSC));
        assertThat(result(response.results().get(1), Distributor.LCSC).returned()).isEqualTo(3);
        assertThat(mouser.calls).isEmpty();

        assertThatThrownBy(() -> service.searchBatch(BatchSearchRequest.of(List.of(), null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        List<SearchRequest> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tooMany.add(SearchRequest.of("q" + i));
        }
        assertThatThrownBy(() -> service.searchBatch(BatchSearchRequest.of(tooMany, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private RankingService rankingThatSleepsOnFirstCall(Duration sleep) {
        RankingService ranking = mock(RankingService.class);
        when(ranking.rank(any(ParsedQuery.class), anyMap(), any())).thenAnswer(inv -> {
            Map<Distributor, List<Part>> input = inv.getArgument(1);
            Duration budget = inv.getArgument(2);
            rankBudgets.add(budget);
            if (rankBudgets.size() == 1) {
                Thread.sleep(sleep.toMillis());
            }
            Map<Distributor, List<RankedPart>> out = new EnumMap<>(Distributor.class);
            input.forEach((d, parts) -> out.put(d, parts.stream().map(p -> new RankedPart(p, 0.5)).toList()));
            return Duration.ZERO.equals(budget)
                    ? new RankedResults(out, RankingMode.FALLBACK, "laya timeout: budget exhausted")
                    : new RankedResults(out, RankingMode.LAYA, null);
        });
        return ranking;
    }
    // ---- rate limiting (DESIGN.md 3.6) ------------------------------------------------------------------------------

    @Test
    void rateLimitWaitExtendsTheDistributorDeadlineAndIsReported() {
        RateLimitedClient mouser = new RateLimitedClient(Distributor.MOUSER, 1);
        mouser.records(10, i -> part(Distributor.MOUSER, "M" + i));
        mouser.wait = Duration.ofMillis(800);
        FakeClient lcsc = new FakeClient(Distributor.LCSC).records(3, i -> part(Distributor.LCSC, "C" + i));
        service(List.of(mouser, lcsc), "kina.search.distributor-timeout", "300ms",
                "kina.search.max-request-duration", "10s");

        SearchResponse response = service.search(request(10));

        DistributorResult m = result(response, Distributor.MOUSER);
        assertThat(m.error()).as("the 800 ms wait does not count against the 300 ms distributor timeout").isNull();
        assertThat(m.returned()).isEqualTo(10);
        assertThat(m.rateLimitWaitedMs()).isEqualTo(800);
        assertThat(result(response, Distributor.LCSC).rateLimitWaitedMs()).isZero();
    }

    @Test
    void rateLimitWaitNeverExtendsBeyondTheRequestDeadline() {
        RateLimitedClient tme = new RateLimitedClient(Distributor.TME, 1);
        tme.records(10, i -> part(Distributor.TME, "T" + i));
        tme.wait = Duration.ofSeconds(5);
        tme.ignoreDeadline = true; // misbehaves: waits past the request deadline
        service(List.of(tme), "kina.search.distributor-timeout", "300ms", "kina.search.max-request-duration", "600ms");

        long started = System.nanoTime();
        SearchResponse response = service.search(request(10, Distributor.TME));
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took).isLessThan(Duration.ofSeconds(2));
        assertThat(result(response, Distributor.TME).error()).isEqualTo("timeout");
        assertThat(result(response, Distributor.TME).rateLimitWaitedMs()).isEqualTo(5_000);
    }

    @Test
    void rateLimitBeyondTheRequestDeadlineIsReportedAndKeepsEarlierPages() {
        RateLimitedClient tme = new RateLimitedClient(Distributor.TME, 2);
        tme.records(60, i -> part(Distributor.TME, "T" + i));
        tme.pageSize = 20;
        tme.wait = Duration.ofSeconds(30);
        service(List.of(tme), "kina.search.max-request-duration", "2s");

        DistributorResult t = result(service.search(request(40, Distributor.TME)), Distributor.TME);

        assertThat(t.error()).isEqualTo("rate_limited");
        assertThat(t.fetched()).as("the first page is kept").isEqualTo(20);
        assertThat(t.rateLimitWaitedMs()).isZero();
        assertThat(tme.callCount).hasValue(2);
    }

    @Test
    void distributorsGetForksOfOneRequestDeadline() {
        RateLimitedClient mouser = new RateLimitedClient(Distributor.MOUSER);
        mouser.records(3, i -> part(Distributor.MOUSER, "M" + i));
        RateLimitedClient tme = new RateLimitedClient(Distributor.TME);
        tme.records(3, i -> part(Distributor.TME, "T" + i));
        service(List.of(mouser, tme), "kina.search.max-request-duration", "2m");

        long before = System.nanoTime();
        service.search(request(10));

        Deadline m = mouser.deadlines.getFirst();
        Deadline t = tme.deadlines.getFirst();
        assertThat(m).isNotSameAs(t);
        assertThat(m.deadlineNanos()).isEqualTo(t.deadlineNanos());
        assertThat(m.deadlineNanos() - before).isBetween(Duration.ofMinutes(2).toNanos(), Duration.ofSeconds(121).toNanos());
    }

    @Test
    void batchSharesOneRequestDeadline() {
        RateLimitedClient tme = new RateLimitedClient(Distributor.TME, 1);
        tme.records(5, i -> part(Distributor.TME, "T" + i));
        tme.wait = Duration.ofMillis(300);
        service(List.of(tme), "kina.search.max-request-duration", "1m");

        BatchSearchResponse response = service.searchBatch(new BatchSearchRequest(List.of(
                new SearchRequest("10uF X7R 0805", 5, Set.of(), false),
                new SearchRequest("100nF X7R 0603", 5, Set.of(), false),
                new SearchRequest("1k 0603 resistor", 5, Set.of(), false)), Set.of(Distributor.TME), false));

        assertThat(tme.deadlines).hasSize(3);
        assertThat(tme.deadlines.stream().map(Deadline::deadlineNanos).distinct()).hasSize(1);
        assertThat(response.results().stream().mapToLong(r -> r.distributors().getFirst().rateLimitWaitedMs()).sum())
                .as("only the rate-limited query reports a wait").isEqualTo(300);
    }
}
