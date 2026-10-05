package ro.alacrity.kina.search;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.cache.CachedSearch;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.cache.SearchCacheRepository;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.distributor.DistributorRegistry;
import ro.alacrity.kina.distributor.DistributorSearchPage;
import ro.alacrity.kina.domain.BatchSearchRequest;
import ro.alacrity.kina.domain.BatchSearchResponse;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.DistributorResult;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.ParsedQueryResponse;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PartResponse;
import ro.alacrity.kina.domain.RankingMode;
import ro.alacrity.kina.domain.SearchRequest;
import ro.alacrity.kina.domain.SearchResponse;
import ro.alacrity.kina.search.RankingService.RankedPart;
import ro.alacrity.kina.search.RankingService.RankedResults;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Search orchestrator (DESIGN.md sections 3.2 and 3.3): parses the query, fetches every requested distributor in
 * parallel on virtual threads (Postgres cache for Mouser/TME, paging through {@link DistributorClient#search}),
 * enriches parts with comparable attributes, ranks them and assembles the {@link SearchResponse}.
 *
 * <p>Distributor failures never fail a search: they become the distributor entry's {@code error}.
 */
@Service
public class PartSearchService {

    private static final Logger log = LoggerFactory.getLogger(PartSearchService.class);

    /** Pages fetched per search for LCSC (the SQLite query already returns the whole window). */
    static final int LCSC_MAX_PAGES = 1;
    /** Queries of one batch whose distributor fetches run at the same time (protects distributor rate limits). */
    static final int BATCH_FETCH_CONCURRENCY = 4;
    /** Below this remaining batch budget a query is ranked with the fallback ranking. */
    static final Duration MIN_BATCH_RANKING_BUDGET = Duration.ofMillis(250);
    /** Extra wait after the distributor deadline before a fetch is abandoned (lets a finishing task hand over). */
    static final Duration TIMEOUT_GRACE = Duration.ofMillis(250);

    private final KinaProperties properties;
    private final DistributorRegistry registry;
    private final QueryParser parser;
    private final ParametricExtractor extractor;
    private final RankingService ranking;
    private final PartCacheRepository partCache;
    private final SearchCacheRepository searchCache;
    private final Clock clock;
    private final ExecutorService executor;

    @Autowired
    public PartSearchService(KinaProperties properties, DistributorRegistry registry, QueryParser parser,
                             ParametricExtractor extractor, RankingService ranking, PartCacheRepository partCache,
                             SearchCacheRepository searchCache, Clock clock) {
        this.properties = properties;
        this.registry = registry;
        this.parser = parser;
        this.extractor = extractor;
        this.ranking = ranking;
        this.partCache = partCache;
        this.searchCache = searchCache;
        this.clock = clock;
        this.executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("kina-search-", 0).factory());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    // ---- public API -----------------------------------------------------------------------------------------------

    /** Runs one search. Never fails because of a distributor; rejects a blank query. */
    public SearchResponse search(SearchRequest request) {
        Prepared prepared = prepare(request);
        long started = System.nanoTime();
        Map<Distributor, Fetched> fetched = fetchAll(prepared);
        long fetchedAt = System.nanoTime();
        RankedResults ranked = ranking.rank(prepared.parsed(), partsByDistributor(fetched), null);
        if (log.isInfoEnabled()) {
            log.info("search '{}': fetch {} ms {}, rank {} ms ({})", prepared.parsed().normalizedKey(),
                    (fetchedAt - started) / 1_000_000, summary(fetched), (System.nanoTime() - fetchedAt) / 1_000_000,
                    ranked.mode().jsonValue());
        }
        return assemble(prepared, fetched, ranked, ranked.note());
    }

    /**
     * Runs every query of the batch. Fetches run in parallel (at most {@value #BATCH_FETCH_CONCURRENCY} queries at a
     * time); ranking is sequential within {@code kina.ranking.batch-timeout}; queries left when it expires use the
     * fallback ranking.
     */
    public BatchSearchResponse searchBatch(BatchSearchRequest batch) {
        List<SearchRequest> requests = batch == null ? List.of() : batch.expanded();
        if (requests.isEmpty()) {
            throw new IllegalArgumentException("queries must contain at least one query");
        }
        if (requests.size() > BatchSearchRequest.MAX_QUERIES) {
            throw new IllegalArgumentException("at most " + BatchSearchRequest.MAX_QUERIES + " queries per batch");
        }
        List<Prepared> prepared = requests.stream().map(this::prepare).toList();

        Semaphore slots = new Semaphore(BATCH_FETCH_CONCURRENCY);
        List<Future<Map<Distributor, Fetched>>> futures = new ArrayList<>();
        for (Prepared p : prepared) {
            futures.add(executor.submit(() -> {
                slots.acquire();
                try {
                    return fetchAll(p);
                } finally {
                    slots.release();
                }
            }));
        }
        List<Map<Distributor, Fetched>> fetched = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            fetched.add(awaitBatchFetch(futures.get(i), prepared.get(i)));
        }

        Duration batchBudget = properties.ranking().batchTimeout();
        long deadline = System.nanoTime() + batchBudget.toNanos();
        List<SearchResponse> results = new ArrayList<>(prepared.size());
        for (int i = 0; i < prepared.size(); i++) {
            Prepared p = prepared.get(i);
            Map<Distributor, List<Part>> parts = partsByDistributor(fetched.get(i));
            Duration remaining = Duration.ofNanos(deadline - System.nanoTime());
            RankedResults ranked;
            String note;
            if (remaining.compareTo(MIN_BATCH_RANKING_BUDGET) < 0) {
                // zero budget: Laya is not called; scores already in the score cache may still be used
                ranked = ranking.rank(p.parsed(), parts, Duration.ZERO);
                note = ranked.mode() == RankingMode.FALLBACK
                        ? "batch ranking budget of " + format(batchBudget) + " exhausted"
                        : ranked.note();
            } else {
                Duration perQuery = properties.ranking().timeout();
                ranked = ranking.rank(p.parsed(), parts, remaining.compareTo(perQuery) < 0 ? remaining : perQuery);
                note = ranked.note();
            }
            results.add(assemble(p, fetched.get(i), ranked, note));
        }
        return new BatchSearchResponse(results);
    }

    // ---- preparation ----------------------------------------------------------------------------------------------

    /** A validated request: parsed query, clamped max results, resolved distributors. */
    record Prepared(SearchRequest request, ParsedQuery parsed, int maxResults, Set<Distributor> distributors) {
    }

    Prepared prepare(SearchRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        int requested = request.maxResults() <= 0 ? properties.search().defaultMaxResults() : request.maxResults();
        int maxResults = Math.clamp(requested, 1, Math.max(1, properties.search().maxMaxResults()));
        Set<Distributor> distributors = request.distributors().isEmpty()
                ? registry.configured()
                : EnumSet.copyOf(request.distributors());
        ParsedQuery parsed = parser.parse(request.query().strip());
        return new Prepared(request, parsed, maxResults, distributors);
    }

    /** {@code max(maxResults, candidate-window)} capped by the distributor's {@code max-results-per-search}. */
    int window(Distributor distributor, int maxResults) {
        int cap = switch (distributor) {
            case MOUSER -> properties.distributors().mouser().maxResultsPerSearch();
            case TME -> properties.distributors().tme().maxResultsPerSearch();
            case LCSC -> properties.jlcpcb().maxResultsPerSearch();
        };
        return Math.max(1, Math.min(Math.max(maxResults, properties.search().candidateWindow()), cap));
    }

    int maxPages(Distributor distributor) {
        return Math.max(1, switch (distributor) {
            case MOUSER -> properties.distributors().mouser().maxPagesPerSearch();
            case TME -> properties.distributors().tme().maxPagesPerSearch();
            case LCSC -> LCSC_MAX_PAGES;
        });
    }

    /** Only Mouser and TME use the Postgres cache; the JLCPCB SQLite database is LCSC's cache. */
    static boolean usesPostgresCache(Distributor distributor) {
        return distributor != Distributor.LCSC;
    }

    // ---- fetching -------------------------------------------------------------------------------------------------

    /**
     * What one distributor contributed: in-stock parts (enriched, distributor order, deduplicated), the
     * distributor-reported total, the cache status and an error code (null on success).
     */
    record Fetched(Distributor distributor, List<Part> parts, Integer totalResults, CacheStatus cache, String error) {

        Fetched {
            parts = parts == null ? List.of() : List.copyOf(parts);
        }

        static Fetched failed(Distributor distributor, CacheStatus cache, String error) {
            return new Fetched(distributor, List.of(), null, cache, error);
        }
    }

    /** Progress of a running fetch, readable when the fetch times out. */
    static final class Progress {
        volatile CacheStatus cache;
        volatile List<Part> parts = List.of();
        volatile Integer totalResults;

        Progress(CacheStatus cache) {
            this.cache = cache;
        }
    }

    private Map<Distributor, Fetched> fetchAll(Prepared prepared) {
        Duration timeout = properties.search().distributorTimeout();
        long deadline = System.nanoTime() + timeout.toNanos();
        Map<Distributor, Fetched> results = new EnumMap<>(Distributor.class);
        Map<Distributor, Future<Fetched>> futures = new EnumMap<>(Distributor.class);
        Map<Distributor, Progress> progress = new EnumMap<>(Distributor.class);

        for (Distributor distributor : prepared.distributors()) {
            Optional<DistributorClient> client = registry.find(distributor).filter(DistributorClient::isConfigured);
            if (client.isEmpty()) {
                results.put(distributor, Fetched.failed(distributor, CacheStatus.NOT_APPLICABLE,
                        DistributorException.Kind.NOT_CONFIGURED.code()));
                continue;
            }
            Progress p = new Progress(initialStatus(distributor, prepared.request().bypassCache()));
            progress.put(distributor, p);
            futures.put(distributor, executor.submit(() -> fetchDistributor(client.get(), prepared, p, deadline)));
        }

        futures.forEach((distributor, future) -> {
            Progress p = progress.get(distributor);
            try {
                long waitNanos = Math.max(0, deadline - System.nanoTime()) + TIMEOUT_GRACE.toNanos();
                results.put(distributor, future.get(waitNanos, TimeUnit.NANOSECONDS));
            } catch (TimeoutException e) {
                future.cancel(true);
                log.info("{} did not answer '{}' within {}", distributor, prepared.parsed().normalizedKey(),
                        format(timeout));
                results.put(distributor, new Fetched(distributor, p.parts, p.totalResults, p.cache,
                        DistributorException.Kind.TIMEOUT.code()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                results.put(distributor, Fetched.failed(distributor, p.cache,
                        DistributorException.Kind.TIMEOUT.code()));
            } catch (ExecutionException | CancellationException e) {
                Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                results.put(distributor, new Fetched(distributor, p.parts, p.totalResults, p.cache,
                        errorCode(distributor, cause)));
            }
        });
        return results;
    }

    private static CacheStatus initialStatus(Distributor distributor, boolean bypassCache) {
        if (!usesPostgresCache(distributor)) {
            return CacheStatus.NOT_APPLICABLE;
        }
        return bypassCache ? CacheStatus.BYPASSED : CacheStatus.MISS;
    }

    private static String errorCode(Distributor distributor, Throwable error) {
        if (error instanceof DistributorException de) {
            log.info("{} search failed: {}", distributor, de.getMessage());
            return de.errorCode();
        }
        log.warn("{} search failed unexpectedly", distributor, error);
        return DistributorException.Kind.UNAVAILABLE.code();
    }

    /**
     * DESIGN.md 3.2 for one distributor. Runs on a virtual thread; {@code deadline} is a {@link System#nanoTime()}
     * value after which no further page is requested.
     */
    Fetched fetchDistributor(DistributorClient client, Prepared prepared, Progress progress, long deadline) {
        Distributor distributor = client.distributor();
        String query = prepared.parsed().originalText();
        int window = window(distributor, prepared.maxResults());
        int maxPages = maxPages(distributor);

        if (!usesPostgresCache(distributor)) {
            Collected collected = collect(client, query, 0, window, maxPages, List.of(), progress, deadline);
            return collected.toFetched(distributor, CacheStatus.NOT_APPLICABLE);
        }

        String queryKey = prepared.parsed().normalizedKey();
        Instant now = clock.instant();
        Instant freshSince = now.minus(properties.cache().ttl());

        if (!prepared.request().bypassCache()) {
            Optional<CachedSearch> cached = readCachedSearch(distributor, queryKey)
                    .filter(c -> !c.fetchedAt().isBefore(freshSince));
            if (cached.isPresent()) {
                CachedSearch search = cached.get();
                Optional<List<Part>> parts = readCachedParts(distributor, search.partNumbers(), freshSince);
                if (parts.isPresent()) {
                    List<Part> cachedParts = parts.get().stream().map(extractor::enrich).toList();
                    if (isSufficient(search, prepared.maxResults(), window)) {
                        return new Fetched(distributor, cachedParts, search.totalResults(), CacheStatus.HIT, null);
                    }
                    // fresh but short and not exhausted: further querying is needed
                    progress.cache = CacheStatus.PARTIAL;
                    progress.parts = cachedParts;
                    progress.totalResults = search.totalResults();
                    int offset = search.nextOffset() != null ? search.nextOffset() : cachedParts.size();
                    Collected collected;
                    try {
                        collected = collect(client, query, offset, window, maxPages, cachedParts, progress, deadline);
                    } catch (DistributorException e) {
                        // serve what the cache holds, flagged with the error
                        log.info("{} could not extend cached search '{}': {}", distributor, queryKey, e.getMessage());
                        return new Fetched(distributor, cachedParts, search.totalResults(), CacheStatus.PARTIAL,
                                e.errorCode());
                    }
                    store(distributor, queryKey, collected, search.fetchedAt());
                    return collected.toFetched(distributor, CacheStatus.PARTIAL);
                }
                // some cached parts are missing or stale: refetch from the start
            }
        }
        CacheStatus status = prepared.request().bypassCache() ? CacheStatus.BYPASSED : CacheStatus.MISS;
        Collected collected = collect(client, query, 0, window, maxPages, List.of(), progress, deadline);
        store(distributor, queryKey, collected, now);
        return collected.toFetched(distributor, status);
    }

    /**
     * A cached list can be served as-is when the distributor had nothing more, when it already holds the fetch
     * window, or when it holds at least {@code maxResults} parts (querying more would not change what is returned
     * beyond ranking depth, and would spend distributor quota).
     */
    static boolean isSufficient(CachedSearch search, int maxResults, int window) {
        int size = search.partNumbers().size();
        return search.exhausted() || size >= window || size >= maxResults;
    }

    /**
     * The parts in list order, or empty when any of them is missing from {@code cached_parts} or stale (the whole
     * search is then refetched).
     */
    private Optional<List<Part>> readCachedParts(Distributor distributor, List<String> partNumbers,
                                                 Instant freshSince) {
        if (partNumbers.isEmpty()) {
            return Optional.of(List.of());
        }
        Map<String, Part> found;
        try {
            found = partCache.findFresh(distributor, partNumbers, freshSince);
        } catch (RuntimeException e) {
            log.warn("Reading cached {} parts failed, fetching from the distributor: {}", distributor, e.toString());
            return Optional.empty();
        }
        List<Part> ordered = new ArrayList<>(partNumbers.size());
        for (String partNumber : partNumbers) {
            Part part = found.get(partNumber);
            if (part == null) {
                return Optional.empty();
            }
            ordered.add(part);
        }
        return Optional.of(ordered);
    }

    private Optional<CachedSearch> readCachedSearch(Distributor distributor, String queryKey) {
        try {
            return searchCache.find(distributor, queryKey);
        } catch (RuntimeException e) {
            log.warn("Reading the cached {} search failed, fetching from the distributor: {}", distributor,
                    e.toString());
            return Optional.empty();
        }
    }

    /** Parts collected for one query plus the paging state to store in {@code cached_searches}. */
    record Collected(List<Part> all, List<Part> fetched, Integer totalResults, boolean exhausted, int nextOffset,
                     String error) {

        Fetched toFetched(Distributor distributor, CacheStatus cache) {
            return new Fetched(distributor, all, totalResults, cache, error);
        }
    }

    /**
     * Pages through {@link DistributorClient#search} from {@code offset} until {@code window} parts are held, the
     * distributor reports no more results, {@code maxPages} pages were requested or the next page would not fit
     * before {@code deadline}. Pages have the distributor's {@link DistributorClient#maxPageSize()} (the first one is
     * shortened to end on a page boundary) because distributors drop parts without ships-now stock: paging is driven
     * by raw record offsets, not by the number of parts kept. A failure on the first page propagates; a failure on a
     * later page keeps what was collected and reports the error code.
     */
    Collected collect(DistributorClient client, String query, int offset, int window, int maxPages,
                      List<Part> existing, Progress progress, long deadline) {
        Distributor distributor = client.distributor();
        boolean pagedByRecords = usesPostgresCache(distributor);
        int pageSize = Math.max(1, client.maxPageSize());
        List<Part> all = new ArrayList<>(existing);
        List<Part> fetched = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        existing.forEach(p -> seen.add(p.distributorPartNumber()));
        Integer total = progress.totalResults;
        boolean hasMore = true;
        int next = Math.max(0, offset);
        int pages = 0;
        long lastPageNanos = 0;
        String error = null;
        while (pages < maxPages && hasMore && all.size() < window) {
            if (pages > 0 && System.nanoTime() + lastPageNanos > deadline) {
                log.debug("{}: no time for another page of '{}'", distributor, query);
                break;
            }
            int limit = pagedByRecords ? pageSize - next % pageSize : Math.min(window, pageSize);
            long started = System.nanoTime();
            DistributorSearchPage page;
            try {
                page = client.search(query, next, limit);
            } catch (DistributorException e) {
                if (pages == 0) {
                    throw e;
                }
                log.info("{}: page {} of '{}' failed, keeping {} parts: {}", distributor, pages + 1, query,
                        all.size(), e.getMessage());
                error = e.errorCode();
                break;
            }
            lastPageNanos = System.nanoTime() - started;
            pages++;
            next += limit;
            total = page.totalResults();
            hasMore = page.hasMore();
            Instant now = clock.instant();
            for (Part part : page.parts()) {
                if (part == null || part.stock() <= 0 || !seen.add(part.distributorPartNumber())) {
                    continue;
                }
                Part enriched = extractor.enrich(part.fetchedAt() == null ? withFetchedAt(part, now) : part);
                all.add(enriched);
                fetched.add(enriched);
            }
            progress.parts = List.copyOf(all);
            progress.totalResults = total;
        }
        return new Collected(all, fetched, total, !hasMore, next, error);
    }

    /** Writes the new parts and the search list. Cache failures are logged, never propagated. */
    private void store(Distributor distributor, String queryKey, Collected collected, Instant listFetchedAt) {
        try {
            partCache.upsertAll(collected.fetched());
            searchCache.upsert(new CachedSearch(distributor, queryKey, collected.totalResults(),
                    collected.all().stream().map(Part::distributorPartNumber).toList(), collected.exhausted(),
                    listFetchedAt, collected.nextOffset()));
        } catch (RuntimeException e) {
            log.warn("Caching {} results for '{}' failed: {}", distributor, queryKey, e.toString());
        }
    }

    private Map<Distributor, Fetched> awaitBatchFetch(Future<Map<Distributor, Fetched>> future, Prepared prepared) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return failedAll(prepared, DistributorException.Kind.TIMEOUT.code());
        } catch (ExecutionException e) {
            log.warn("batch query '{}' failed", prepared.parsed().normalizedKey(), e.getCause());
            return failedAll(prepared, DistributorException.Kind.UNAVAILABLE.code());
        }
    }

    private static Map<Distributor, Fetched> failedAll(Prepared prepared, String error) {
        Map<Distributor, Fetched> out = new EnumMap<>(Distributor.class);
        prepared.distributors().forEach(d -> out.put(d, Fetched.failed(d, CacheStatus.NOT_APPLICABLE, error)));
        return out;
    }

    private static Part withFetchedAt(Part p, Instant fetchedAt) {
        return new Part(p.distributor(), p.distributorPartNumber(), p.manufacturer(), p.manufacturerPartNumber(),
                p.description(), p.category(), p.packageName(), p.stock(), p.minimumOrderQuantity(),
                p.orderMultiple(), p.prices(), p.datasheetUrl(), p.photoUrl(), p.productUrl(), p.attributes(),
                p.extra(), fetchedAt);
    }

    // ---- assembly -------------------------------------------------------------------------------------------------

    /** {@code [MOUSER=hit/50, TME=timeout/0]} for the timing log. */
    private static String summary(Map<Distributor, Fetched> fetched) {
        StringBuilder out = new StringBuilder("[");
        fetched.forEach((d, f) -> out.append(out.length() > 1 ? ", " : "").append(d).append('=')
                .append(f.error() != null ? f.error() : f.cache().jsonValue()).append('/').append(f.parts().size()));
        return out.append(']').toString();
    }

    private static Map<Distributor, List<Part>> partsByDistributor(Map<Distributor, Fetched> fetched) {
        Map<Distributor, List<Part>> parts = new EnumMap<>(Distributor.class);
        fetched.forEach((d, f) -> parts.put(d, f.parts()));
        return parts;
    }

    private static SearchResponse assemble(Prepared prepared, Map<Distributor, Fetched> fetched, RankedResults ranked,
                                           String note) {
        List<DistributorResult> results = new ArrayList<>();
        for (Distributor distributor : prepared.distributors()) {
            Fetched f = fetched.get(distributor);
            if (f == null) {
                continue;
            }
            List<RankedPart> rankedParts = ranked.byDistributor().getOrDefault(distributor, List.of());
            int returned = Math.min(prepared.maxResults(), rankedParts.size());
            List<PartResponse> parts = new ArrayList<>(returned);
            for (int i = 0; i < returned; i++) {
                RankedPart rp = rankedParts.get(i);
                parts.add(PartResponse.from(rp.part(), i + 1, roundScore(rp.score())));
            }
            results.add(new DistributorResult(distributor, f.totalResults(), rankedParts.size(), returned, f.cache(),
                    f.error(), parts));
        }
        return new SearchResponse(prepared.parsed().originalText(), ParsedQueryResponse.from(prepared.parsed()),
                ranked.mode(), note, results);
    }

    static double roundScore(double score) {
        return Math.round(score * 1e4) / 1e4;
    }

    static String format(Duration duration) {
        long millis = duration.toMillis();
        return millis % 1000 == 0 ? (millis / 1000) + "s" : millis + "ms";
    }
}
