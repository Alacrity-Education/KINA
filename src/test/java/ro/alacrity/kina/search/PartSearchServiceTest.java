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
import ro.alacrity.kina.domain.PartResponse;
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
    final Set<String> soldOut = ConcurrentHashMap.newKeySet();
    final Map<String, CachedSearch> cachedSearches = new ConcurrentHashMap<>();
    final List<Map<Distributor, List<Part>>> rankedInputs = new CopyOnWriteArrayList<>();
    final List<Duration> rankBudgets = new CopyOnWriteArrayList<>();
    final List<Integer> rankQuantities = new CopyOnWriteArrayList<>();
    final List<RankingService.RankOptions> rankOptions = new CopyOnWriteArrayList<>();
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
            int outOfStock = offset >= raw.size() ? 0 : (to - offset) - parts.size();
            return new DistributorSearchPage(parts, raw.size(), to < raw.size(), outOfStock);
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
        when(partCache.findInStock(any(), anyCollection())).thenAnswer(inv -> {
            Distributor d = inv.getArgument(0);
            Collection<String> numbers = inv.getArgument(1);
            Map<String, Part> out = new java.util.HashMap<>();
            for (String n : numbers) {
                Part p = cachedParts.get(PartKey.of(d, n));
                if (p != null && !soldOut.contains(p.key())) {
                    out.put(n, p);
                }
            }
            return out;
        });
        doAnswer(inv -> {
            Collection<Part> parts = inv.getArgument(0);
            parts.forEach(p -> cachedParts.put(p.key(), p));
            return null;
        }).when(partCache).updateStock(anyCollection());
        doAnswer(inv -> {
            soldOut.add(PartKey.of(inv.getArgument(0), inv.getArgument(1)));
            return null;
        }).when(partCache).markSoldOut(any(), anyString());
        SearchCacheRepository searchCache = mock(SearchCacheRepository.class);
        when(searchCache.find(any(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(cachedSearches.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        doAnswer(inv -> {
            CachedSearch s = inv.getArgument(0);
            cachedSearches.put(s.distributor() + "|" + s.queryKey(), s);
            return null;
        }).when(searchCache).upsert(any());

        RankingService ranking = mock(RankingService.class);
        when(ranking.rank(any(), anyMap(), any(), any(RankingService.RankOptions.class))).thenAnswer(inv -> {
            Map<Distributor, List<Part>> input = inv.getArgument(1);
            Duration budget = inv.getArgument(2);
            RankingService.RankOptions options = inv.getArgument(3);
            rankQuantities.add(options.quantity());
            rankOptions.add(options);
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
                return new RankedResults(out, RankingMode.FALLBACK, "cross-encoder timeout: budget exhausted");
            }
            return new RankedResults(out, RankingMode.BLENDED, null);
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
    /** The phrase every distributor gets: the 30V minimum rating is never sent (DESIGN.md 3.2). */
    static final String MOSFET_PHRASE = "SOT-23 N-channel MOSFET";
    /** The minimal core, the last step of the relaxation ladder. */
    static final String MOSFET_CORE = "MOSFET SOT-23";

    @Test
    void corePhraseKeepsFamilyValuesDielectricAndPackage() {
        QueryParser parser = new QueryParser();
        assertThat(PartSearchService.corePhrase(parser.parse(MOSFET_QUERY))).isEqualTo(MOSFET_CORE);
        // ratings are never part of the core
        assertThat(PartSearchService.corePhrase(parser.parse("MLCC 10uF 25V X7R 0805 ceramic low ESR")))
                .isEqualTo("MLCC 10uF X7R 0805");
        // the technology is, in the distributor's spelling
        assertThat(PartSearchService.corePhrase(parser.parse("100uF 16V polymer aluminium capacitor SMD"),
                Distributor.TME)).isEqualTo("capacitor 100uF polymer");
        // the tolerance is, last: it is the last constraint the ladder loosens
        assertThat(PartSearchService.corePhrase(parser.parse("22uF X7R 1206 25V MLCC 10%")))
                .isEqualTo("MLCC 22uF X7R 1206 10%");
        assertThat(PartSearchService.corePhrase(parser.parse("22uF X7R 1206 25V MLCC 10%"), null,
                Set.of("dielectric", "package"))).isEqualTo("MLCC 22uF 10%");
        // a regulator's voltage is a specification, not a rating
        assertThat(PartSearchService.corePhrase(parser.parse("LDO 3.3V SOT-23-5"))).isEqualTo("LDO 3.3V SOT-23-5");
        // the core may equal the query: the ladder skips a step equal to what was sent
        assertThat(PartSearchService.corePhrase(parser.parse(QUERY))).isEqualTo(QUERY);
        // no parametric core, single term
        assertThat(PartSearchService.corePhrase(parser.parse("USB type C receptacle"))).isNull();
        assertThat(PartSearchService.corePhrase(parser.parse("0805 something exotic"))).isNull();
    }

    @Test
    void apiDistributorWithNoResultsIsRetriedOnceWithTheCorePhrase() {
        FakeClient tme = new FakeClient(Distributor.TME).records(8, i -> part(Distributor.TME, "T" + i));
        tme.emptyFor.add(MOSFET_PHRASE);
        FakeClient mouser = new FakeClient(Distributor.MOUSER).records(3, i -> part(Distributor.MOUSER, "M" + i));
        service(List.of(tme, mouser));

        SearchResponse response = service.search(new SearchRequest(MOSFET_QUERY, 5, Set.of(), false));

        DistributorResult t = result(response, Distributor.TME);
        assertThat(tme.queries).containsExactly(MOSFET_PHRASE, MOSFET_CORE);
        assertThat(t.fallbackQuery()).isEqualTo(MOSFET_CORE);
        assertThat(t.fetched()).isEqualTo(8);
        assertThat(t.returned()).isEqualTo(5);
        assertThat(t.cache()).isEqualTo(CacheStatus.MISS);
        // a distributor that answered the full query is not retried
        DistributorResult m = result(response, Distributor.MOUSER);
        assertThat(mouser.queries).containsExactly(MOSFET_PHRASE);
        assertThat(m.fallbackQuery()).isNull();

        // cached under the original query key, with the phrase that was searched
        CachedSearch stored = cachedSearches.get(Distributor.TME + "|" + QueryParser.normalizeKey(MOSFET_QUERY));
        assertThat(stored.fallbackQuery()).isEqualTo(MOSFET_CORE);
        assertThat(stored.partNumbers()).hasSize(8);

        DistributorResult again = result(service.search(new SearchRequest(MOSFET_QUERY, 5, Set.of(Distributor.TME),
                false)), Distributor.TME);
        assertThat(again.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(again.fallbackQuery()).isEqualTo(MOSFET_CORE);
        assertThat(tme.queries).hasSize(2);
    }

    @Test
    void partialExtensionOfAFallbackListUsesTheFallbackPhrase() {
        FakeClient mouser = new FakeClient(Distributor.MOUSER)
                .records(150, i -> i < 50 && i % 5 != 0 ? null : part(Distributor.MOUSER, "M" + i));
        mouser.emptyFor.add(MOSFET_PHRASE);
        service(List.of(mouser));

        service.search(new SearchRequest(MOSFET_QUERY, 5, Set.of(Distributor.MOUSER), false));
        DistributorResult partial = result(service.search(new SearchRequest(MOSFET_QUERY, 20,
                Set.of(Distributor.MOUSER), false)), Distributor.MOUSER);

        assertThat(partial.cache()).isEqualTo(CacheStatus.PARTIAL);
        assertThat(partial.fallbackQuery()).isEqualTo(MOSFET_CORE);
        assertThat(mouser.queries).containsExactly(MOSFET_PHRASE, MOSFET_CORE, MOSFET_CORE);
        assertThat(mouser.offsets()).containsExactly(0, 0, 50);
    }

    @Test
    void noFallbackForLcscOrWhenTheFallbackAlsoFindsNothing() {
        FakeClient lcsc = new FakeClient(Distributor.LCSC).records(3, i -> part(Distributor.LCSC, "C" + i));
        lcsc.pageSize = 200;
        lcsc.emptyFor.add(MOSFET_PHRASE + " >=30V");
        FakeClient tme = new FakeClient(Distributor.TME);   // finds nothing for anything
        service(List.of(lcsc, tme));

        SearchResponse response = service.search(new SearchRequest(MOSFET_QUERY, 5, Set.of(), false));

        // LCSC checks the minimum rating itself (a local database)
        assertThat(lcsc.queries).containsExactly(MOSFET_PHRASE + " >=30V");
        assertThat(result(response, Distributor.LCSC).fallbackQuery()).isNull();
        assertThat(result(response, Distributor.LCSC).fetched()).isZero();
        assertThat(tme.queries).containsExactly(MOSFET_PHRASE, MOSFET_CORE);
        DistributorResult t = result(response, Distributor.TME);
        assertThat(t.fetched()).isZero();
        assertThat(t.fallbackQuery()).isEqualTo(MOSFET_CORE);
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
        assertThat(tme.queries).containsExactly(MOSFET_PHRASE);
    }

    // ---- relaxation ladder, out-of-stock matches, quantity and detail --------------------------------------------

    @Test
    void relaxationLadderStopsAtTheFirstPhraseWithInStockParts() {
        String query = "22uF X7R 1206 25V 10% MLCC";
        FakeClient tme = new FakeClient(Distributor.TME).records(6, i -> part(Distributor.TME, "T" + i));
        tme.emptyFor.add("22uF X7R 1206 10% MLCC");
        tme.emptyFor.add("MLCC 22uF 1206 10%");
        service(List.of(tme));

        DistributorResult t = result(service.search(new SearchRequest(query, 5, Set.of(), false)), Distributor.TME);

        // the rating never reaches TME; the core has the same words as the phrase (skipped); then the dielectric
        // goes, then the tolerance; the package of a capacitor is never relaxed (DESIGN.md 3.4)
        assertThat(tme.queries).containsExactly("22uF X7R 1206 10% MLCC", "MLCC 22uF 1206 10%", "MLCC 22uF 1206");
        assertThat(t.distributorQuery()).isEqualTo("22uF X7R 1206 10% MLCC");
        assertThat(t.fallbackQuery()).isEqualTo("MLCC 22uF 1206");
        // the response lists only what the returned parts miss; this ranking stub reports no mismatches
        assertThat(t.constraintsRelaxed()).isEmpty();
        assertThat(t.queryTermsDropped()).contains("voltage", "dielectric", "tolerance").doesNotContain("package");
        assertThat(t.fetched()).isEqualTo(6);
        CachedSearch stored = cachedSearches.get(Distributor.TME + "|" + QueryParser.normalizeKey(query));
        assertThat(stored.fallbackQuery()).isEqualTo("MLCC 22uF 1206");
        assertThat(stored.constraintsRelaxed()).containsExactly("dielectric", "tolerance");
        // a cache hit reports the same relaxation
        DistributorResult hit = result(service.search(new SearchRequest(query, 5, Set.of(), false)),
                Distributor.TME);
        assertThat(hit.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(hit.fallbackQuery()).isEqualTo("MLCC 22uF 1206");
    }

    @Test
    void pagesOnWhileEveryMatchIsOutOfStockAndReportsTheCount() {
        // Mouser reads one page per search; the first 50 records have no ships-now stock
        FakeClient mouser = new FakeClient(Distributor.MOUSER)
                .records(100, i -> i < 50 ? null : part(Distributor.MOUSER, "M" + i));
        service(List.of(mouser));

        DistributorResult m = result(service.search(request(5, Distributor.MOUSER)), Distributor.MOUSER);

        assertThat(mouser.offsets()).containsExactly(0, 50);
        assertThat(m.fetched()).isEqualTo(50);
        assertThat(m.outOfStockMatches()).isEqualTo(50);
        assertThat(cachedSearches.get(Distributor.MOUSER + "|" + QueryParser.normalizeKey(QUERY)).outOfStockMatches())
                .isEqualTo(50);
        // a cache hit reports the stored count
        assertThat(result(service.search(request(5, Distributor.MOUSER)), Distributor.MOUSER).outOfStockMatches())
                .isEqualTo(50);
    }

    @Test
    void outOfStockOnlyResultsStopAfterTheExtraPages() {
        FakeClient mouser = new FakeClient(Distributor.MOUSER).records(400, i -> null);
        service(List.of(mouser));

        DistributorResult m = result(service.search(request(5, Distributor.MOUSER)), Distributor.MOUSER);

        // 1 page + EXTRA_OUT_OF_STOCK_PAGES, then the same for the last relaxation step (dielectric dropped)
        assertThat(mouser.offsets()).containsExactly(0, 50, 100, 0, 50, 100);
        assertThat(mouser.queries).containsExactly(QUERY, QUERY, QUERY, "10uF 0805", "10uF 0805", "10uF 0805");
        assertThat(m.fetched()).isZero();
        assertThat(m.outOfStockMatches()).isEqualTo(300);
        assertThat(m.constraintsRelaxed()).isEmpty();   // no part was returned, so none misses the dielectric
    }

    @Test
    void quantityReachesTheRankingAndDetailShapesTheParts() {
        FakeClient tme = new FakeClient(Distributor.TME).records(2, i -> part(Distributor.TME, "T" + i)
                .toBuilder().attributes(Map.of("Operating voltage", "25V DC")).extra(Map.of("manufacturer_id", 7))
                .build());
        service(List.of(tme));

        SearchResponse compact = service.search(new SearchRequest(QUERY, 5, Set.of(), false, 25,
                ro.alacrity.kina.domain.ResponseDetail.COMPACT));
        SearchResponse full = service.search(new SearchRequest(QUERY, 5, Set.of(), false, 1,
                ro.alacrity.kina.domain.ResponseDetail.FULL));

        assertThat(rankQuantities).containsExactly(25, 1);
        var c = result(compact, Distributor.TME).parts().getFirst();
        assertThat(c.attributes()).containsEntry("Voltage", "25V").doesNotContainKey("Operating voltage");
        assertThat(c.extra()).isNull();
        assertThat(c.manufacturerId()).isEqualTo("7");
        assertThat(c.orderedQuantity()).isEqualTo(25);
        assertThat(c.totalPrice()).isEqualByComparingTo("1.75");   // 25 x 0.07 (the 10-piece bracket)
        assertThat(c.availability().status()).isEqualTo("in_stock");
        var f = result(full, Distributor.TME).parts().getFirst();
        assertThat(f.attributes()).containsEntry("Operating voltage", "25V DC").containsEntry("Voltage", "25V");
        assertThat(f.extra()).containsEntry("manufacturer_id", 7);
    }

    // ---- distributor phrasing (connector queries) -----------------------------------------------------------------

    static final String CONNECTOR_QUERY = "90 degree dupont style female pin header 90 degree THT pins 6 position";

    @Test
    void connectorQueriesAreSentInEachDistributorsWording() {
        FakeClient lcsc = new FakeClient(Distributor.LCSC).records(3, i -> part(Distributor.LCSC, "C" + i));
        lcsc.pageSize = 200;
        FakeClient tme = new FakeClient(Distributor.TME).records(4, i -> part(Distributor.TME, "T" + i));
        FakeClient mouser = new FakeClient(Distributor.MOUSER).records(2, i -> part(Distributor.MOUSER, "M" + i));
        service(List.of(lcsc, tme, mouser));

        SearchResponse response = service.search(new SearchRequest(CONNECTOR_QUERY, 5, Set.of(), false));

        assertThat(lcsc.queries).containsExactly("\"Female Header\" 6P \"Right Angle\" 2.54mm");
        assertThat(tme.queries).containsExactly("pin strips female 6 angled");
        assertThat(mouser.queries).containsExactly("female header 6 pos right angle");
        assertThat(result(response, Distributor.LCSC).distributorQuery())
                .isEqualTo("\"Female Header\" 6P \"Right Angle\" 2.54mm");
        assertThat(result(response, Distributor.TME).distributorQuery()).isEqualTo("pin strips female 6 angled");
        assertThat(result(response, Distributor.TME).fallbackQuery()).isNull();
        assertThat(result(response, Distributor.MOUSER).distributorQuery())
                .isEqualTo("female header 6 pos right angle");
        assertThat(response.parsed().connector().type()).isEqualTo("female header");

        // cached under the user's normalised query; a hit still reports the phrase
        assertThat(cachedSearches).containsKey(Distributor.TME + "|" + QueryParser.normalizeKey(CONNECTOR_QUERY));
        DistributorResult hit = result(service.search(new SearchRequest(CONNECTOR_QUERY, 5, Set.of(Distributor.TME),
                false)), Distributor.TME);
        assertThat(hit.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(hit.distributorQuery()).isEqualTo("pin strips female 6 angled");
        assertThat(tme.queries).hasSize(1);
    }

    @Test
    void connectorPhraseWithoutResultsFallsBackToTheShorterPhrase() {
        FakeClient tme = new FakeClient(Distributor.TME).records(4, i -> part(Distributor.TME, "T" + i));
        tme.emptyFor.add("pin strips female 6 angled");
        FakeClient mouser = new FakeClient(Distributor.MOUSER).records(2, i -> part(Distributor.MOUSER, "M" + i));
        mouser.emptyFor.add("female header 6 pos right angle");
        service(List.of(tme, mouser));

        SearchResponse response = service.search(new SearchRequest(CONNECTOR_QUERY, 5, Set.of(), false));

        assertThat(tme.queries).containsExactly("pin strips female 6 angled", "pin strips female 6");
        DistributorResult t = result(response, Distributor.TME);
        assertThat(t.distributorQuery()).isEqualTo("pin strips female 6 angled");
        assertThat(t.fallbackQuery()).isEqualTo("pin strips female 6");
        assertThat(t.fetched()).isEqualTo(4);
        assertThat(mouser.queries).containsExactly("female header 6 pos right angle", "female header right angle");
        assertThat(result(response, Distributor.MOUSER).fallbackQuery()).isEqualTo("female header right angle");
        assertThat(cachedSearches.get(Distributor.TME + "|" + QueryParser.normalizeKey(CONNECTOR_QUERY))
                .fallbackQuery()).isEqualTo("pin strips female 6");
    }

    @Test
    void keywordOnlyQueriesFallBackToTheirMostInformativeTokens() {
        String query = "ESP32-WROOM-32 wifi bluetooth module with antenna";
        FakeClient tme = new FakeClient(Distributor.TME).records(3, i -> part(Distributor.TME, "T" + i));
        tme.emptyFor.add(query);
        service(List.of(tme));

        DistributorResult t = result(service.search(new SearchRequest(query, 5, Set.of(), false)), Distributor.TME);

        assertThat(tme.queries).containsExactly(query, "ESP32-WROOM-32 wifi bluetooth antenna");
        assertThat(t.fallbackQuery()).isEqualTo("ESP32-WROOM-32 wifi bluetooth antenna");
        assertThat(t.distributorQuery()).isNull();   // the user's text was sent verbatim
        assertThat(t.fetched()).isEqualTo(3);
    }

    @Test
    void nonConnectorQueriesReportNoDistributorQuery() {
        FakeClient tme = new FakeClient(Distributor.TME).records(3, i -> part(Distributor.TME, "T" + i));
        service(List.of(tme));
        DistributorResult t = result(service.search(request(5, Distributor.TME)), Distributor.TME);
        assertThat(tme.queries).containsExactly(QUERY);
        assertThat(t.distributorQuery()).isNull();
        assertThat(t.fallbackQuery()).isNull();
    }

    @Test
    void partialExtensionOfAConnectorSearchPagesOnWithThePhrase() {
        FakeClient mouser = new FakeClient(Distributor.MOUSER)
                .records(150, i -> i < 50 && i % 5 != 0 ? null : part(Distributor.MOUSER, "M" + i));
        service(List.of(mouser));

        service.search(new SearchRequest(CONNECTOR_QUERY, 5, Set.of(Distributor.MOUSER), false));
        DistributorResult partial = result(service.search(new SearchRequest(CONNECTOR_QUERY, 20,
                Set.of(Distributor.MOUSER), false)), Distributor.MOUSER);

        assertThat(partial.cache()).isEqualTo(CacheStatus.PARTIAL);
        assertThat(mouser.queries).containsExactly("female header 6 pos right angle",
                "female header 6 pos right angle");
        assertThat(mouser.offsets()).containsExactly(0, 50);
        assertThat(partial.distributorQuery()).isEqualTo("female header 6 pos right angle");
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
        assertThat(response.ranking()).isEqualTo(RankingMode.BLENDED);
        assertThat(response.parsed().dielectric()).isEqualTo("X7R");
        assertThat(response.parsed().packageName()).isEqualTo("0805");
        var part = result(response, Distributor.MOUSER).parts().getFirst();
        assertThat(part.prices()).extracting(p -> p.qty()).containsExactly(1, 10, 100);
        assertThat(part.score()).isNull();   // compact detail leaves score out; rank orders the list
        assertThat(part.rank()).isEqualTo(1);
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
        assertThat(response.results().get(0).ranking()).isEqualTo(RankingMode.BLENDED);
        assertThat(rankBudgets.getFirst()).isLessThanOrEqualTo(Duration.ofMillis(600)).isPositive();
        for (SearchResponse later : response.results().subList(1, 3)) {
            assertThat(later.ranking()).isEqualTo(RankingMode.FALLBACK);
            assertThat(later.rankingNote()).isEqualTo("batch ranking budget of 600ms exhausted");
            assertThat(result(later, Distributor.LCSC).returned()).isEqualTo(5);
        }
        assertThat(rankBudgets.subList(1, 3)).containsOnly(Duration.ZERO);
    }

    @Test
    void batchWithTheCrossEncoderDisabledKeepsTheDisabledNoteWhenTheBudgetIsUsedUp() {
        FakeClient lcsc = new FakeClient(Distributor.LCSC).records(5, i -> part(Distributor.LCSC, "C" + i));
        KinaProperties props = RankingFixtures.properties("kina.ranking.batch-timeout", "0s",
                "kina.ranking.cross-encoder.enabled", "false");
        ParametricExtractor extractor = new ParametricExtractor();
        RankingService ranking = new RankingService(props, new DeterministicRanker(extractor),
                mock(PartRanker.class), () -> null, new RankingScoreCache(Duration.ofHours(1)));
        service = new PartSearchService(props, new DistributorRegistry(List.of(lcsc)), new QueryParser(), extractor,
                ranking, mock(PartCacheRepository.class), mock(SearchCacheRepository.class), clock);

        BatchSearchResponse response = service.searchBatch(BatchSearchRequest.of(List.of(
                SearchRequest.of("10uF X7R 0805"), SearchRequest.of("100nF 0603")), null, null));

        assertThat(response.results()).allSatisfy(r -> {
            assertThat(r.ranking()).isEqualTo(RankingMode.FALLBACK);
            assertThat(r.rankingNote()).isEqualTo("cross-encoder disabled");
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
        when(ranking.rank(any(ParsedQuery.class), anyMap(), any(), any(RankingService.RankOptions.class))).thenAnswer(inv -> {
            Map<Distributor, List<Part>> input = inv.getArgument(1);
            Duration budget = inv.getArgument(2);
            rankBudgets.add(budget);
            if (rankBudgets.size() == 1) {
                Thread.sleep(sleep.toMillis());
            }
            Map<Distributor, List<RankedPart>> out = new EnumMap<>(Distributor.class);
            input.forEach((d, parts) -> out.put(d, parts.stream().map(p -> new RankedPart(p, 0.5)).toList()));
            return Duration.ZERO.equals(budget)
                    ? new RankedResults(out, RankingMode.FALLBACK, "cross-encoder timeout: budget exhausted")
                    : new RankedResults(out, RankingMode.BLENDED, null);
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

    // ---- power resistors (recorded fixtures, DESIGN.md 3.4 "Power resistors") -------------------------------------

    /** Recorded parts of {@code fixtures/power-resistors/query-<name>.json} (live probe of 2026-10-07) per distributor. */
    static Map<Distributor, List<Part>> powerFixture(String name) {
        tools.jackson.databind.json.JsonMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        try (var in = PartSearchServiceTest.class.getResourceAsStream("/fixtures/power-resistors/query-" + name
                + ".json")) {
            tools.jackson.databind.JsonNode root = mapper.readTree(in);
            Map<Distributor, List<Part>> out = new EnumMap<>(Distributor.class);
            root.get("parts").properties().forEach(e -> {
                List<Part> parts = new ArrayList<>();
                e.getValue().forEach(node -> parts.add(mapper.treeToValue(node, Part.class)));
                out.put(Distributor.valueOf(e.getKey()), parts);
            });
            return out;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The search over the recorded parts with the real deterministic ranking (cross-encoder off). */
    SearchResponse powerSearch(String name, String query, ro.alacrity.kina.domain.ResponseDetail detail) {
        List<FakeClient> clients = new ArrayList<>();
        powerFixture(name).forEach((d, parts) -> {
            FakeClient client = new FakeClient(d);
            client.raw.addAll(parts);
            clients.add(client);
        });
        KinaProperties props = RankingFixtures.properties("kina.ranking.cross-encoder.enabled", "false");
        ParametricExtractor extractor = new ParametricExtractor();
        RankingService ranking = new RankingService(props, new DeterministicRanker(extractor),
                mock(PartRanker.class), () -> null, new RankingScoreCache(Duration.ofHours(1)));
        service = new PartSearchService(props, new DistributorRegistry(List.copyOf(clients)), new QueryParser(),
                extractor, ranking, mock(PartCacheRepository.class), mock(SearchCacheRepository.class), clock);
        return service.search(new SearchRequest(query, 10, Set.of(), false, 1, detail));
    }

    @Test
    void queryA_heatsinkMountPowerResistorsAreExactAndShowWatts() {
        SearchResponse response = powerSearch("a", "150W power resistor 4.7 ohm heatsink mount",
                ro.alacrity.kina.domain.ResponseDetail.COMPACT);

        assertThat(response.parsed().formFactor()).isEqualTo(FormFactor.CHASSIS);
        DistributorResult mouser = result(response, Distributor.MOUSER);
        // "300watts" / "800watts" are read: nothing is unverified, every planar part is an exact match
        assertThat(mouser.parts()).allSatisfy(p -> {
            assertThat(p.unverified()).isEmpty();
            assertThat(p.match()).isEqualTo(1.0);
            assertThat(p.score()).isNull();   // compact: rank carries the order
            assertThat(p.attributes()).containsEntry("Technology", "thick film").containsKey("Power");
        });
        assertThat(mouser.parts()).filteredOn(p -> p.mpn().equals("LPS0300H4R70JB")).singleElement()
                .satisfies(p -> assertThat(p.attributes()).containsEntry("Power", "300W"));
        assertThat(mouser.exactMatches()).isEqualTo(mouser.returned()).isEqualTo(4);
        DistributorResult tme = result(response, Distributor.TME);
        assertThat(tme.parts()).filteredOn(p -> p.mpn().equals("AHP250W-4R7F")).singleElement()
                .satisfies(p -> assertThat(p.attributes()).containsEntry("Power", "250W"));
        assertThat(tme.exactMatches()).isEqualTo(2);
        assertThat(result(response, Distributor.LCSC).exactMatches()).isEqualTo(1);   // LTO150, TO-247
    }

    @Test
    void queryB_sot227RequestExcludesChipAndLeadedResistors() {
        SearchResponse response = powerSearch("b", "300W 10 ohm power resistor SOT-227 heatsink",
                ro.alacrity.kina.domain.ResponseDetail.FULL);

        DistributorResult mouser = result(response, Distributor.MOUSER);
        assertThat(mouser.parts()).extracting(PartResponse::mpn).containsExactly("TGHPV10R0KE");
        assertThat(mouser.parts().getFirst().score()).isNotNull();   // full detail keeps score
        // chip resistors (category "- SMD", chip size 1225) and the axial ones are form-factor exclusions
        // (PWR3014W, PWR2010W, AC03AT "- SMD"; RCL1225; two G003 "Axial")
        assertThat(mouser.excludedByConstraintsDetail()).containsEntry(ConstraintPolicy.FORM_FACTOR, 6);
        // the through-hole and chassis parts without a class read now their power: 1 W, 5 W, 12.5 W are below 300 W
        assertThat(mouser.excludedBelowSpec()).isEqualTo(3);
        assertThat(mouser.exactMatches()).isEqualTo(1);
        assertThat(result(response, Distributor.LCSC).exactMatches()).isEqualTo(1);
    }

    @Test
    void queryC_aluminiumHousedChassisResistorsAreExactMatches() {
        SearchResponse response = powerSearch("c", "25W 100 ohm aluminium housed chassis mount resistor",
                ro.alacrity.kina.domain.ResponseDetail.COMPACT);

        DistributorResult mouser = result(response, Distributor.MOUSER);
        assertThat(mouser.exactMatches()).isPositive();
        assertThat(mouser.parts().getFirst().mpn()).isEqualTo("HS25E3 100R F M145");
        assertThat(mouser.parts().getFirst().match()).isEqualTo(1.0);
        assertThat(result(response, Distributor.TME).exactMatches()).isPositive();
        DistributorResult lcsc = result(response, Distributor.LCSC);
        assertThat(lcsc.exactMatches()).isPositive();
        // the surface-mount power packages (TO-263, D2PAK) are no chassis part
        assertThat(lcsc.excludedByConstraintsDetail()).containsEntry(ConstraintPolicy.FORM_FACTOR, 2);
    }
}
