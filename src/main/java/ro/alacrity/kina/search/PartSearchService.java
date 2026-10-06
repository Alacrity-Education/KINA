package ro.alacrity.kina.search;

import jakarta.annotation.PreDestroy;
import lombok.With;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
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
import ro.alacrity.kina.distributor.StockUpdate;
import ro.alacrity.kina.domain.BatchSearchRequest;
import ro.alacrity.kina.domain.BatchSearchResponse;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.DistributorResult;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.ParsedQueryResponse;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PartResponse;
import ro.alacrity.kina.domain.RankingMode;
import ro.alacrity.kina.domain.ResponseDetail;
import ro.alacrity.kina.domain.SearchRequest;
import ro.alacrity.kina.domain.SearchResponse;
import ro.alacrity.kina.metrics.KinaMetrics;
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
import java.util.concurrent.TimeoutException;

/**
 * Search orchestrator (DESIGN.md sections 3.2 and 3.3): parses the query, fetches every requested distributor in
 * parallel on virtual threads (Postgres cache for Mouser/TME, paging through {@link DistributorClient#search}),
 * enriches parts with comparable attributes, ranks them and assembles the {@link SearchResponse}.
 *
 * <p>Distributor failures never fail a search: they become the distributor entry's {@code error}.
 *
 * <p>Every incoming request (one search, one whole batch) has one hard deadline,
 * {@code kina.search.max-request-duration} (DESIGN.md 3.6). Rate-limited distributor calls may wait and retry within
 * it; the time they wait does not count against {@code kina.search.distributor-timeout}.
 */
@Service
@Slf4j
public class PartSearchService {

    /** Pages fetched per search for LCSC (the SQLite query already returns the whole window). */
    static final int LCSC_MAX_PAGES = 1;
    /** Queries of one batch whose distributor fetches run at the same time (protects distributor rate limits). */
    static final int BATCH_FETCH_CONCURRENCY = 4;
    /** Below this remaining batch budget a query is ranked with the fallback ranking. */
    static final Duration MIN_BATCH_RANKING_BUDGET = Duration.ofMillis(250);
    /** Extra wait after the distributor deadline before a fetch is abandoned (lets a finishing task hand over). */
    static final Duration TIMEOUT_GRACE = Duration.ofMillis(250);
    /**
     * Pages read beyond {@code max-pages-per-search} while every match so far was out of stock (DESIGN.md 3.2): the
     * part exists, the next page may hold a shippable one.
     */
    static final int EXTRA_OUT_OF_STOCK_PAGES = 2;

    private final KinaProperties properties;
    private final DistributorRegistry registry;
    private final QueryParser parser;
    private final ParametricExtractor extractor;
    private final RankingService ranking;
    private final PartCacheRepository partCache;
    private final SearchCacheRepository searchCache;
    private final Clock clock;
    private final ExecutorService executor;
    private KinaMetrics metrics = KinaMetrics.NOOP;

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

    @Autowired
    void setMetrics(KinaMetrics metrics) {
        this.metrics = metrics;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    // ---- public API -----------------------------------------------------------------------------------------------

    /** Runs one search. Never fails because of a distributor; rejects a blank query. */
    public SearchResponse search(SearchRequest request) {
        Prepared prepared = prepare(request);
        Deadline deadline = requestDeadline();
        long started = System.nanoTime();
        Map<Distributor, Fetched> fetched = fetchAll(prepared, deadline);
        long fetchedAt = System.nanoTime();
        RankedResults ranked = refreshStock(prepared, ranking.rank(prepared.parsed(), partsByDistributor(fetched),
                null, rankOptions(prepared)), deadline);
        if (log.isInfoEnabled()) {
            log.info("search '{}': fetch {} ms {}, rank {} ms ({})", prepared.parsed().normalizedKey(),
                    (fetchedAt - started) / 1_000_000, summary(fetched), (System.nanoTime() - fetchedAt) / 1_000_000,
                    ranked.mode().jsonValue());
        }
        SearchResponse response = assemble(prepared, fetched, ranked, ranked.note());
        metrics.searchCompleted(response, System.nanoTime() - started);
        return response;
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
        long started = System.nanoTime();
        Deadline requestDeadline = requestDeadline(); // one incoming request: one deadline for every query of the batch

        Semaphore slots = new Semaphore(BATCH_FETCH_CONCURRENCY);
        List<Future<Map<Distributor, Fetched>>> futures = new ArrayList<>();
        for (Prepared p : prepared) {
            futures.add(executor.submit(() -> {
                slots.acquire();
                try {
                    return fetchAll(p, requestDeadline);
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
                // zero budget: the cross-encoder is not called; scores already in the score cache may still be used
                ranked = ranking.rank(p.parsed(), parts, Duration.ZERO, rankOptions(p));
                // with the cross-encoder disabled the fallback has nothing to do with the budget: keep that note
                note = ranked.mode() == RankingMode.FALLBACK && properties.ranking().crossEncoder().enabled()
                        ? "batch ranking budget of " + format(batchBudget) + " exhausted"
                        : ranked.note();
            } else {
                Duration perQuery = properties.ranking().timeout();
                ranked = ranking.rank(p.parsed(), parts, remaining.compareTo(perQuery) < 0 ? remaining : perQuery,
                        rankOptions(p));
                note = ranked.note();
            }
            results.add(assemble(p, fetched.get(i), refreshStock(p, ranked, requestDeadline), note));
        }
        BatchSearchResponse response = new BatchSearchResponse(results);
        metrics.batchCompleted(response, System.nanoTime() - started);
        return response;
    }

    private static RankingService.RankOptions rankOptions(Prepared prepared) {
        return new RankingService.RankOptions(prepared.request().quantity(), prepared.request().allowBelowSpec());
    }

    /** The hard / relaxable constraint table ({@link RankingService#policy()}; the defaults when not available). */
    ConstraintPolicy policy() {
        ConstraintPolicy p = ranking == null ? null : ranking.policy();
        return p == null ? ConstraintPolicy.DEFAULTS : p;
    }

    /** {@code now + kina.search.max-request-duration}. */
    Deadline requestDeadline() {
        return Deadline.after(properties.search().maxRequestDuration());
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
     * distributor-reported total, the cache status, an error code (null on success), the time spent waiting on
     * rate limits, the constraints the relaxation loosened ({@code constraints_relaxed}), the free-text terms LCSC's
     * database search dropped and the request terms not sent in the phrase ({@code query_terms_dropped}).
     */
    record Fetched(Distributor distributor, List<Part> parts, Integer totalResults, CacheStatus cache, String error,
                   String fallbackQuery, @With long rateLimitWaitedMs, @With String distributorQuery,
                   @With Integer outOfStockMatches, @With List<String> constraintsRelaxed,
                   @With List<String> droppedKeywords, @With List<String> queryTermsDropped) {

        Fetched {
            parts = parts == null ? List.of() : List.copyOf(parts);
            constraintsRelaxed = constraintsRelaxed == null ? List.of() : List.copyOf(constraintsRelaxed);
            droppedKeywords = droppedKeywords == null ? List.of() : List.copyOf(droppedKeywords);
            queryTermsDropped = queryTermsDropped == null ? List.of() : List.copyOf(queryTermsDropped);
        }

        Fetched(Distributor distributor, List<Part> parts, Integer totalResults, CacheStatus cache, String error,
                String fallbackQuery, long rateLimitWaitedMs) {
            this(distributor, parts, totalResults, cache, error, fallbackQuery, rateLimitWaitedMs, null, null, null,
                    null, null);
        }

        Fetched(Distributor distributor, List<Part> parts, Integer totalResults, CacheStatus cache, String error,
                String fallbackQuery) {
            this(distributor, parts, totalResults, cache, error, fallbackQuery, 0);
        }

        Fetched(Distributor distributor, List<Part> parts, Integer totalResults, CacheStatus cache, String error) {
            this(distributor, parts, totalResults, cache, error, null);
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
        volatile String fallbackQuery;
        volatile List<String> constraintsRelaxed = List.of();
        /** Matches without ships-now stock seen on the pages read so far (every phrase of the ladder). */
        volatile int outOfStock;

        Progress(CacheStatus cache) {
            this.cache = cache;
        }
    }

    /**
     * Fetches every distributor of the query in parallel. Each fetch has {@code distributor-timeout} of active work,
     * extended by its rate-limit waits, never beyond {@code requestDeadline}.
     */
    private Map<Distributor, Fetched> fetchAll(Prepared prepared, Deadline requestDeadline) {
        Duration timeout = properties.search().distributorTimeout();
        Map<Distributor, Fetched> results = new EnumMap<>(Distributor.class);
        Map<Distributor, Future<Fetched>> futures = new EnumMap<>(Distributor.class);
        Map<Distributor, Progress> progress = new EnumMap<>(Distributor.class);
        Map<Distributor, DistributorBudget> budgets = new EnumMap<>(Distributor.class);

        for (Distributor distributor : prepared.distributors()) {
            Optional<DistributorClient> client = registry.find(distributor).filter(DistributorClient::isConfigured);
            if (client.isEmpty()) {
                results.put(distributor, Fetched.failed(distributor, CacheStatus.NOT_APPLICABLE,
                        DistributorException.Kind.NOT_CONFIGURED.code()));
                continue;
            }
            Progress p = new Progress(initialStatus(distributor, prepared.request().bypassCache()));
            progress.put(distributor, p);
            DistributorBudget budget = new DistributorBudget(requestDeadline, timeout);
            budgets.put(distributor, budget);
            futures.put(distributor, executor.submit(() -> fetchDistributor(client.get(), prepared, p, budget)));
        }

        futures.forEach((distributor, future) -> {
            Progress p = progress.get(distributor);
            DistributorBudget budget = budgets.get(distributor);
            Fetched fetched;
            try {
                fetched = budget.await(future, TIMEOUT_GRACE);
            } catch (TimeoutException e) {
                future.cancel(true);
                log.info("{} did not answer '{}' within {}{}", distributor, prepared.parsed().normalizedKey(),
                        format(timeout), budget.rateLimitWaitedNanos() > 0
                                ? " (+ " + budget.rateLimitWaitedMillis() + " ms rate-limit wait)" : "");
                fetched = new Fetched(distributor, p.parts, p.totalResults, p.cache,
                        DistributorException.Kind.TIMEOUT.code(), p.fallbackQuery).withOutOfStockMatches(p.outOfStock)
                        .withConstraintsRelaxed(p.constraintsRelaxed);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                fetched = Fetched.failed(distributor, p.cache, DistributorException.Kind.TIMEOUT.code());
            } catch (ExecutionException | CancellationException e) {
                Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                fetched = new Fetched(distributor, p.parts, p.totalResults, p.cache,
                        errorCode(distributor, cause), p.fallbackQuery).withOutOfStockMatches(p.outOfStock)
                        .withConstraintsRelaxed(p.constraintsRelaxed);
            }
            String phrase = DistributorPhraser.phrase(distributor, prepared.parsed());
            String used = fetched.fallbackQuery() != null ? fetched.fallbackQuery()
                    : phrase != null ? phrase : prepared.parsed().originalText();
            results.put(distributor, fetched.withRateLimitWaitedMs(budget.rateLimitWaitedMillis())
                    .withDistributorQuery(phrase)
                    .withQueryTermsDropped(queryTermsDropped(prepared.parsed(), used, fetched.droppedKeywords())));
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
     * True when the part meets the request (DESIGN.md 3.2): no known attribute contradicts a strict constraint and no
     * known rating is below the request. Paging and the relaxation ladder go on until a part meets it.
     */
    boolean meetsRequest(ParsedQuery parsed, Part part) {
        RankingService.Verdict v = verdict(parsed, part);
        return v == RankingService.Verdict.MEETS || v == RankingService.Verdict.UNVERIFIED_RATING;
    }

    /** True when the part meets the request with every requested rating stated: paging stops only for such parts. */
    boolean confirmed(ParsedQuery parsed, Part part) {
        return verdict(parsed, part) == RankingService.Verdict.MEETS;
    }

    private RankingService.Verdict verdict(ParsedQuery parsed, Part part) {
        try {
            RankingService.Verdict v = ranking.verdict(parsed, part);
            return v == null ? RankingService.Verdict.MEETS : v;
        } catch (RuntimeException e) {
            log.warn("checking {} against '{}' failed", part.key(), parsed.normalizedKey(), e);
            return RankingService.Verdict.MEETS;
        }
    }

    /** True when the part would be returned: it meets the request, or it is only below spec and that is allowed. */
    private boolean returnable(ParsedQuery parsed, Part part, boolean allowBelowSpec) {
        RankingService.Verdict v = verdict(parsed, part);
        return v != RankingService.Verdict.CONSTRAINT && (allowBelowSpec || v != RankingService.Verdict.BELOW_SPEC);
    }

    /** One rung of the relaxation ladder that produced parts: its result, its phrase and what it loosened. */
    private record Attempt(Collected collected, String phrase, List<String> relaxed) {
    }

    /**
     * DESIGN.md 3.2 for one distributor. Runs on a virtual thread; no further page is requested once {@code budget}
     * has run out.
     */
    Fetched fetchDistributor(DistributorClient client, Prepared prepared, Progress progress, DistributorBudget deadline) {
        Distributor distributor = client.distributor();
        ParsedQuery parsed = prepared.parsed();
        // connector queries are sent in the distributor's own wording; the cache key stays the user's query
        String phrase = DistributorPhraser.phrase(distributor, parsed);
        String query = phrase != null ? phrase : parsed.originalText();
        int window = window(distributor, prepared.maxResults());
        int maxPages = maxPages(distributor);
        Check meets = new Check(p -> meetsRequest(parsed, p), p -> confirmed(parsed, p));

        if (!usesPostgresCache(distributor)) {
            Collected collected = collect(client, query, 0, window, maxPages, List.of(), progress, deadline, meets);
            return collected.toFetched(distributor, CacheStatus.NOT_APPLICABLE)
                    .withOutOfStockMatches(progress.outOfStock);
        }

        String queryKey = parsed.normalizedKey();
        Instant now = clock.instant();
        Instant freshSince = now.minus(properties.cache().ttl());
        Instant emptyFreshSince = now.minus(properties.cache().emptyResultTtl());

        Optional<CachedSearch> expired = Optional.empty();
        if (!prepared.request().bypassCache()) {
            Optional<CachedSearch> stored = readCachedSearch(distributor, queryKey);
            Optional<CachedSearch> cached = stored.filter(c -> isFresh(c, freshSince, emptyFreshSince));
            expired = stored.filter(c -> cached.isEmpty() && !c.partNumbers().isEmpty());
            if (cached.isPresent()) {
                CachedSearch search = cached.get();
                String fallbackQuery = search.fallbackQuery();
                List<String> relaxed = search.constraintsRelaxed() != null ? search.constraintsRelaxed()
                        : relaxedBy(distributor, parsed, query, fallbackQuery, policy());
                // metadata is kept and stock ages on its own: a part of any stock age is used (refreshed or marked
                // stale after ranking, DESIGN.md 3.2 "Cache model"); a part missing or sold out is a miss
                Optional<List<Part>> parts = readCachedParts(distributor, search.partNumbers(), false);
                if (parts.isPresent()) {
                    List<Part> cachedParts = parts.get().stream().map(extractor::enrich).toList();
                    boolean nothingReturnable = !cachedParts.isEmpty() && cachedParts.stream()
                            .noneMatch(p -> returnable(parsed, p, prepared.request().allowBelowSpec()));
                    if (nothingReturnable) {
                        // a hit that would return nothing (every part excluded) never hides a live result
                        log.info("{} cached search '{}' has no part that can be returned; searching live",
                                distributor, queryKey);
                    } else if (isSufficient(search, prepared.maxResults(), window)) {
                        return new Fetched(distributor, cachedParts, search.totalResults(), CacheStatus.HIT, null,
                                fallbackQuery).withOutOfStockMatches(search.outOfStockMatches())
                                .withConstraintsRelaxed(relaxed);
                    } else {
                        return extend(client, prepared, progress, deadline, search, cachedParts, relaxed, query,
                                window, maxPages, meets);
                    }
                }
                // some cached parts are missing or sold out: refetch from the start
            }
        }
        CacheStatus status = prepared.request().bypassCache() ? CacheStatus.BYPASSED : CacheStatus.MISS;
        Collected collected;
        try {
            collected = collect(client, query, 0, window, maxPages, List.of(), progress, deadline, meets);
        } catch (DistributorException e) {
            Optional<Fetched> served = expired.flatMap(search -> servedStale(distributor, parsed, query, search, e));
            if (served.isPresent()) {
                return served.get();
            }
            throw e;
        }
        String fallbackQuery = null;
        List<String> relaxed = List.of();
        Attempt firstWithParts = collected.all().isEmpty() ? null : new Attempt(collected, null, List.of());
        // relaxation ladder (DESIGN.md 3.2): until a phrase finds a part that meets the request
        for (DistributorPhraser.Relaxation step : DistributorPhraser.ladder(distributor, parsed, query, policy())) {
            if (collected.meeting() > 0 || collected.error() != null || deadline.remainingNanos() <= 0) {
                break;
            }
            log.info("{} found nothing that meets '{}' with '{}', relaxing to '{}'{}", distributor, queryKey,
                    fallbackQuery != null ? fallbackQuery : query, step.phrase(),
                    step.relaxed().isEmpty() ? "" : " (loosens " + step.relaxed() + ")");
            progress.fallbackQuery = step.phrase();
            progress.constraintsRelaxed = step.relaxed();
            Collected previous = collected;
            try {
                collected = collect(client, step.phrase(), 0, window, maxPages, List.of(), progress, deadline, meets);
            } catch (DistributorException e) {
                // an earlier phrase did answer: report the failure, cache nothing
                log.info("{} relaxed search '{}' failed: {}", distributor, step.phrase(), e.getMessage());
                Attempt kept = firstWithParts;
                return new Fetched(distributor, kept == null ? List.of() : kept.collected().all(),
                        previous.totalResults(), status, e.errorCode(), kept == null ? step.phrase() : kept.phrase())
                        .withOutOfStockMatches(progress.outOfStock)
                        .withConstraintsRelaxed(kept == null ? step.relaxed() : kept.relaxed());
            }
            fallbackQuery = step.phrase();
            relaxed = step.relaxed();
            if (firstWithParts == null && !collected.all().isEmpty()) {
                firstWithParts = new Attempt(collected, fallbackQuery, relaxed);
            }
        }
        if (collected.meeting() == 0 && firstWithParts != null && firstWithParts.collected() != collected) {
            // no rung found a part that meets the request: keep the least relaxed result that found parts
            collected = firstWithParts.collected();
            fallbackQuery = firstWithParts.phrase();
            relaxed = firstWithParts.relaxed();
        }
        store(distributor, queryKey, collected, now, fallbackQuery, progress.outOfStock, relaxed);
        return collected.toFetched(distributor, status, fallbackQuery).withOutOfStockMatches(progress.outOfStock)
                .withConstraintsRelaxed(relaxed);
    }

    /**
     * A fresh cached list shorter than {@code max_results} and not exhausted: further querying is needed, with the
     * phrase the list was built from so the raw offsets stay valid ({@code PARTIAL}).
     */
    private Fetched extend(DistributorClient client, Prepared prepared, Progress progress, DistributorBudget deadline,
                           CachedSearch search, List<Part> cachedParts, List<String> relaxed, String query, int window,
                           int maxPages, Check meets) {
        Distributor distributor = client.distributor();
        String fallbackQuery = search.fallbackQuery();
        progress.cache = CacheStatus.PARTIAL;
        progress.parts = cachedParts;
        progress.totalResults = search.totalResults();
        progress.fallbackQuery = fallbackQuery;
        progress.constraintsRelaxed = relaxed;
        progress.outOfStock = search.outOfStockMatches() == null ? 0 : search.outOfStockMatches();
        int offset = search.nextOffset() != null ? search.nextOffset() : cachedParts.size();
        Collected collected;
        try {
            collected = collect(client, fallbackQuery != null ? fallbackQuery : query, offset, window, maxPages,
                    cachedParts, progress, deadline, meets);
        } catch (DistributorException e) {
            // serve what the cache holds, flagged with the error
            log.info("{} could not extend cached search '{}': {}", distributor, search.queryKey(), e.getMessage());
            return new Fetched(distributor, cachedParts, search.totalResults(), CacheStatus.PARTIAL,
                    e.errorCode(), fallbackQuery).withOutOfStockMatches(search.outOfStockMatches())
                    .withConstraintsRelaxed(relaxed);
        }
        store(distributor, search.queryKey(), collected, search.fetchedAt(), fallbackQuery, progress.outOfStock,
                relaxed);
        return collected.toFetched(distributor, CacheStatus.PARTIAL, fallbackQuery)
                .withOutOfStockMatches(progress.outOfStock).withConstraintsRelaxed(relaxed);
    }

    /**
     * What the ladder rung {@code fallbackQuery} loosened, for a cached search stored before
     * {@code cached_searches.constraints_relaxed} existed (the ladder is a pure function of the parsed query).
     */
    static List<String> relaxedBy(Distributor distributor, ParsedQuery parsed, String query, String fallbackQuery,
                                  ConstraintPolicy policy) {
        if (fallbackQuery == null) {
            return List.of();
        }
        return DistributorPhraser.ladder(distributor, parsed, query, policy).stream()
                .filter(step -> step.phrase().equals(fallbackQuery))
                .map(DistributorPhraser.Relaxation::relaxed)
                .findFirst().orElse(List.of());
    }

    /**
     * The stated terms that were not part of the phrase that produced the parts ({@code query_terms_dropped},
     * DESIGN.md 3.2): the constraints the phrase actually sent ({@code used}: the relaxed phrase, the distributor
     * phrase or the user's text) does not state (ratings are never sent to Mouser and TME), plus the free-text keywords
     * LCSC's database search dropped. Informational: the ranker still checks every constraint. Connector queries,
     * whose phrases are rewritten into distributor wording, report only dropped keywords.
     */
    List<String> queryTermsDropped(ParsedQuery parsed, String used, List<String> droppedKeywords) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (!parsed.isConnector() && used != null) {
            ParsedQuery sent = parser.parse(used);
            parsed.constraints().keySet().forEach(kind -> {
                if (sent.constraint(kind) == null) {
                    out.add(kind.replace('_', ' '));
                }
            });
            if (parsed.dielectric() != null && sent.dielectric() == null) {
                out.add("dielectric");
            }
            if (parsed.packageName() != null && sent.packageName() == null) {
                out.add("package");
            }
            if (parsed.technology() != null && sent.technology() == null) {
                out.add("technology");
            }
            if (parsed.mounting() != null && sent.mounting() == null) {
                out.add("mounting");
            }
        }
        if (droppedKeywords != null) {
            out.addAll(droppedKeywords);
        }
        return List.copyOf(out);
    }

    /**
     * Fresh for {@code kina.cache.ttl}, except a list without any in-stock part, which is fresh only for
     * {@code kina.cache.empty-result-ttl} (whichever is shorter).
     */
    static boolean isFresh(CachedSearch search, Instant freshSince, Instant emptyFreshSince) {
        Instant since = search.partNumbers().isEmpty() && emptyFreshSince.isAfter(freshSince)
                ? emptyFreshSince : freshSince;
        return !search.fetchedAt().isBefore(since);
    }

    /** {@link #corePhrase(ParsedQuery, Distributor)} with the canonical technology words. */
    static String corePhrase(ParsedQuery parsed) {
        return corePhrase(parsed, null);
    }

    /**
     * The minimal parametric core of a query, the last step of the relaxation ladder ({@link
     * DistributorPhraser#relaxations}, which falls back to the most informative keywords without a core): the family
     * word as written, the values that are not ratings or tolerances (ratings are minimums and never sent; regulator
     * and Zener voltages stay), the technology in the distributor's spelling, the dielectric and the package, e.g.
     * "MOSFET SOT-23" for "SOT-23 N-channel MOSFET 30V" and "MLCC 22uF X7R 1206" for "22uF X7R 1206 25V MLCC". Null when
     * the query has no parametric term or the core would be a single term.
     */
    static String corePhrase(ParsedQuery parsed, Distributor distributor) {
        return corePhrase(parsed, distributor, true);
    }

    /**
     * As {@link #corePhrase(ParsedQuery, Distributor)}; without {@code qualifiers} the dielectric and the technology
     * are left out too; null when that leaves nothing to drop.
     */
    static String corePhrase(ParsedQuery parsed, Distributor distributor, boolean qualifiers) {
        if (!qualifiers && parsed.dielectric() == null && parsed.technology() == null) {
            return null;
        }
        return corePhrase(parsed, distributor, qualifiers ? Set.of() : Set.of("dielectric"));
    }

    /**
     * The parametric core without the constraints in {@code drop} ({@code dielectric}, which also drops the
     * technology, {@code package}, {@code tolerance}; the relaxation ladder, DESIGN.md 3.2). Null when the query has
     * no parametric term left or the core would be a single term.
     */
    static String corePhrase(ParsedQuery parsed, Distributor distributor, Set<String> drop) {
        boolean qualifiers = !drop.contains("dielectric");
        List<String> terms = new ArrayList<>();
        String familyWord = Recognizers.familyToken(parsed.originalText(), parsed.family());
        if (familyWord != null) {
            terms.add(familyWord);
        }
        int parametric = 0;
        ParsedQuery.Constraint tolerance = null;
        for (ParsedQuery.Constraint constraint : parsed.constraints().values()) {
            if (ParsedQuery.TOLERANCE.equals(constraint.kind())) {
                tolerance = constraint;
                continue;
            }
            if (constraint.display() == null
                    || constraint.display().isBlank()
                    || DeterministicRanker.RATING_KINDS.contains(constraint.kind())
                    && !DeterministicRanker.isExactRating(constraint.kind(), parsed.family())) {
                continue;
            }
            // an impedance is sent without its test frequency ("120ohm", not "120ohm @100MHz")
            terms.add(constraint.condition() == null ? constraint.display()
                    : Recognizers.display(constraint.kind(), constraint.value()));
            parametric++;
        }
        if (qualifiers && parsed.technology() != null) {
            String spelling = distributor == null ? null : TechnologyVocabulary.spelling(distributor,
                    parsed.technology());
            terms.add(spelling != null ? spelling : parsed.technology());
            parametric++;
        }
        if (qualifiers && parsed.dielectric() != null) {
            terms.add(parsed.dielectric());
            parametric++;
        }
        // a can size ("D6.3 x 5.8mm") is no search term: the ranker compares it
        if (parsed.packageName() != null && !drop.contains("package") && !PassiveDetails.isCan(parsed.packageName())) {
            terms.add(parsed.packageName());
            parametric++;
        }
        if (tolerance != null && tolerance.display() != null && !drop.contains("tolerance")) {
            terms.add(tolerance.display());
        }
        if (parametric == 0 || terms.size() < 2) {
            return null;
        }
        return String.join(" ", terms);
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
     * The parts in list order whatever the age of their stock, or empty when any of them is missing from
     * {@code cached_parts} or sold out (the whole search is then refetched); with {@code partial} the parts that are
     * there.
     */
    private Optional<List<Part>> readCachedParts(Distributor distributor, List<String> partNumbers, boolean partial) {
        if (partNumbers.isEmpty()) {
            return Optional.of(List.of());
        }
        Map<String, Part> found;
        try {
            found = partCache.findInStock(distributor, partNumbers);
        } catch (RuntimeException e) {
            log.warn("Reading cached {} parts failed, fetching from the distributor: {}", distributor, e.toString());
            return Optional.empty();
        }
        List<Part> ordered = new ArrayList<>(partNumbers.size());
        for (String partNumber : partNumbers) {
            Part part = found.get(partNumber);
            if (part == null) {
                if (partial) {
                    continue;
                }
                return Optional.empty();
            }
            ordered.add(part);
        }
        return Optional.of(ordered);
    }

    /**
     * The live search failed and the query has an expired cached list: its parts are served (cache status
     * {@code stale}, the entry keeps the {@code error}) rather than nothing; their stock and prices are refreshed or
     * marked stale after ranking like any cached part (DESIGN.md 3.2 "Cache model"). Empty when none of the parts is
     * still cached in stock.
     */
    private Optional<Fetched> servedStale(Distributor distributor, ParsedQuery parsed, String query,
                                          CachedSearch search, DistributorException error) {
        List<Part> parts = readCachedParts(distributor, search.partNumbers(), true).orElse(List.of());
        if (parts.isEmpty()) {
            return Optional.empty();
        }
        log.info("{} search '{}' failed ({}); serving the expired cached list of {} parts", distributor,
                search.queryKey(), error.errorCode(), parts.size());
        List<String> relaxed = search.constraintsRelaxed() != null ? search.constraintsRelaxed()
                : relaxedBy(distributor, parsed, query, search.fallbackQuery(), policy());
        return Optional.of(new Fetched(distributor, parts.stream().map(extractor::enrich).toList(),
                search.totalResults(), CacheStatus.STALE, error.errorCode(), search.fallbackQuery())
                .withOutOfStockMatches(search.outOfStockMatches()).withConstraintsRelaxed(relaxed));
    }

    /**
     * True when the part's stock and prices are older than {@code kina.cache.ttl} (Mouser and TME only: LCSC is read
     * from its local database).
     */
    boolean isStale(Part part, Instant now) {
        return usesPostgresCache(part.distributor()) && part.fetchedAt() != null
                && part.fetchedAt().isBefore(now.minus(properties.cache().ttl()));
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

    /**
     * Parts collected for one query plus the paging state to store in {@code cached_searches}, how many of them meet
     * the request ({@link #meetsRequest}), and what LCSC's database search dropped (constraint names, free-text
     * keywords).
     */
    record Collected(List<Part> all, List<Part> fetched, Integer totalResults, boolean exhausted, int nextOffset,
                     String error, List<String> relaxed, List<String> droppedKeywords, int meeting) {

        Collected(List<Part> all, List<Part> fetched, Integer totalResults, boolean exhausted, int nextOffset,
                  String error) {
            this(all, fetched, totalResults, exhausted, nextOffset, error, List.of(), List.of(), all.size());
        }

        Fetched toFetched(Distributor distributor, CacheStatus cache) {
            return toFetched(distributor, cache, null);
        }

        Fetched toFetched(Distributor distributor, CacheStatus cache, String fallbackQuery) {
            return new Fetched(distributor, all, totalResults, cache, error, fallbackQuery)
                    .withConstraintsRelaxed(relaxed).withDroppedKeywords(droppedKeywords);
        }
    }

    /**
     * Which collected parts meet the request ({@link #meetsRequest}) and which of those have every requested rating
     * verified ({@link #confirmed}).
     */
    record Check(java.util.function.Predicate<Part> meets, java.util.function.Predicate<Part> confirmed) {

        static final Check ALL = new Check(p -> true, p -> true);
    }

    /** As {@link #collect(DistributorClient, String, int, int, int, List, Progress, DistributorBudget, Check)}, every part meeting the request. */
    Collected collect(DistributorClient client, String query, int offset, int window, int maxPages,
                      List<Part> existing, Progress progress, DistributorBudget deadline) {
        return collect(client, query, offset, window, maxPages, existing, progress, deadline, Check.ALL);
    }

    /**
     * Pages through {@link DistributorClient#search} from {@code offset} until {@code window} parts are held and at
     * least one of them meets the request with its ratings verified ({@code meets}; DESIGN.md 3.2: a rating is never
     * in the phrase, so the parts that satisfy it may sit on later pages), the distributor reports no more results, {@code maxPages} pages were
     * requested or the next page would not fit within {@code deadline}. Pages have the distributor's
     * {@link DistributorClient#maxPageSize()} (the first one is shortened to end on a page boundary) because
     * distributors drop parts without ships-now stock: paging is driven by raw record offsets, not by the number of
     * parts kept. A failure on the first page propagates; a failure on a later page keeps what was collected and
     * reports the error code.
     */
    Collected collect(DistributorClient client, String query, int offset, int window, int maxPages,
                      List<Part> existing, Progress progress, DistributorBudget deadline, Check meets) {
        Distributor distributor = client.distributor();
        boolean pagedByRecords = usesPostgresCache(distributor);
        int outOfStock = 0;
        List<String> relaxed = List.of();
        List<String> droppedKeywords = List.of();
        int pageSize = Math.max(1, client.maxPageSize());
        List<Part> all = new ArrayList<>(existing);
        List<Part> fetched = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        existing.forEach(p -> seen.add(p.distributorPartNumber()));
        int meeting = (int) existing.stream().filter(meets.meets()).count();
        int confirmed = (int) existing.stream().filter(meets.confirmed()).count();
        Integer total = progress.totalResults;
        boolean hasMore = true;
        int next = Math.max(0, offset);
        int pages = 0;
        long lastPageNanos = 0;
        String error = null;
        // while every match so far is out of stock, up to EXTRA_OUT_OF_STOCK_PAGES more pages (DESIGN.md 3.2)
        while ((pages < maxPages || all.isEmpty() && outOfStock > 0 && pages < maxPages + EXTRA_OUT_OF_STOCK_PAGES)
                && hasMore && (all.size() < window || confirmed == 0)) {
            if (pages > 0 && deadline.remainingNanos() < lastPageNanos) {
                log.debug("{}: no time for another page of '{}'", distributor, query);
                break;
            }
            int limit = pagedByRecords ? pageSize - next % pageSize : Math.min(window, pageSize);
            long started = System.nanoTime();
            long waitedBefore = deadline.rateLimitWaitedNanos();
            DistributorSearchPage page;
            try {
                page = client.search(query, next, limit, deadline.deadline());
            } catch (DistributorException e) {
                if (pages == 0) {
                    throw e;
                }
                log.info("{}: page {} of '{}' failed, keeping {} parts: {}", distributor, pages + 1, query,
                        all.size(), e.getMessage());
                error = e.errorCode();
                break;
            }
            // active time of the page: rate-limit waits do not predict how long the next page takes
            lastPageNanos = Math.max(0, System.nanoTime() - started - (deadline.rateLimitWaitedNanos() - waitedBefore));
            pages++;
            metrics.distributorPage(distributor, System.nanoTime() - started, page.parts().size());
            next += limit;
            total = page.totalResults();
            hasMore = page.hasMore();
            outOfStock += page.outOfStock();
            if (!page.relaxed().isEmpty()) {
                relaxed = page.relaxed();
            }
            if (!page.droppedKeywords().isEmpty()) {
                droppedKeywords = page.droppedKeywords();
            }
            progress.outOfStock += page.outOfStock();
            Instant now = clock.instant();
            for (Part part : page.parts()) {
                if (part == null || part.stock() <= 0 || !seen.add(part.distributorPartNumber())) {
                    continue;
                }
                Part enriched = extractor.enrich(
                        part.fetchedAt() == null ? part.toBuilder().fetchedAt(now).build() : part);
                all.add(enriched);
                fetched.add(enriched);
                if (meets.meets().test(enriched)) {
                    meeting++;
                    if (meets.confirmed().test(enriched)) {
                        confirmed++;
                    }
                }
            }
            progress.parts = List.copyOf(all);
            progress.totalResults = total;
        }
        return new Collected(all, fetched, total, !hasMore, next, error, relaxed, droppedKeywords, meeting);
    }

    /**
     * Writes the new parts and the search list. A list whose parts all fail the request (strict constraints, below
     * spec) is never stored as a reusable search: only its parts are cached. Cache failures are logged, never
     * propagated.
     */
    private void store(Distributor distributor, String queryKey, Collected collected, Instant listFetchedAt,
                       String fallbackQuery, int outOfStockMatches, List<String> constraintsRelaxed) {
        try {
            partCache.upsertAll(collected.fetched());
            if (!collected.all().isEmpty() && collected.meeting() == 0) {
                log.info("{} search '{}': none of its {} parts meets the request; not cached as a search",
                        distributor, queryKey, collected.all().size());
                searchCache.delete(distributor, queryKey);
                return;
            }
            searchCache.upsert(new CachedSearch(distributor, queryKey, collected.totalResults(),
                    collected.all().stream().map(Part::distributorPartNumber).toList(), collected.exhausted(),
                    listFetchedAt, collected.nextOffset(), fallbackQuery, outOfStockMatches, constraintsRelaxed));
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

    // ---- stock refresh --------------------------------------------------------------------------------------------

    /** Rounds of refreshing the parts about to be returned (a sold-out part pulls the next one into the top). */
    static final int STOCK_REFRESH_ROUNDS = 2;

    /**
     * Refreshes the stock and prices of the parts about to be returned whose cached figures are older than
     * {@code kina.cache.stock-ttl} (DESIGN.md 3.2 "Stock refresh"): one cheap distributor call per batch of part numbers
     * (TME {@code /products/data}, Mouser part-number search), only for Mouser and TME and only for the top
     * {@code max_results} parts. Refreshed parts get the new figures and {@code fetchedAt = now} and are written back to
     * the cache; a part that sold out is removed from the list and marked sold out in the cache (its metadata stays). A
     * failed refresh keeps the cached figures. Then parts whose figures are older than {@code kina.cache.ttl} (refresh
     * failed, not attempted, or the distributor is not configured) are stale: they rank below the fresh ones
     * ({@link #demoteStale}) and are returned with {@code stale: true}.
     */
    private RankedResults refreshStock(Prepared prepared, RankedResults ranked, Deadline deadline) {
        Instant now = clock.instant();
        Instant staleBefore = now.minus(properties.cache().stockTtl());
        Map<Distributor, List<RankedPart>> out = new EnumMap<>(Distributor.class);
        boolean changed = false;
        for (Map.Entry<Distributor, List<RankedPart>> entry : ranked.byDistributor().entrySet()) {
            Distributor distributor = entry.getKey();
            List<RankedPart> list = entry.getValue();
            if (!usesPostgresCache(distributor) || list.isEmpty()) {
                out.put(distributor, list);
                continue;
            }
            Optional<DistributorClient> client = registry.find(distributor).filter(DistributorClient::isConfigured);
            List<RankedPart> kept = new ArrayList<>(list);
            Set<String> attempted = new HashSet<>();
            for (int round = 0; client.isPresent() && round < STOCK_REFRESH_ROUNDS && deadline.remainingNanos() > 0;
                 round++) {
                List<String> due = kept.subList(0, Math.min(prepared.maxResults(), kept.size())).stream()
                        .map(RankedPart::part)
                        .filter(p -> p.fetchedAt() == null || p.fetchedAt().isBefore(staleBefore))
                        .map(Part::distributorPartNumber)
                        .filter(attempted::add)
                        .toList();
                if (due.isEmpty()) {
                    break;
                }
                Map<String, StockUpdate> updates;
                try {
                    updates = client.get().refreshStock(due, deadline);
                } catch (DistributorException e) {
                    log.info("{} stock refresh of {} parts failed, keeping the cached figures: {}", distributor,
                            due.size(), e.getMessage());
                    metrics.stockRefreshed(distributor, "failed", due.size());
                    break;
                } catch (RuntimeException e) {
                    log.warn("{} stock refresh failed unexpectedly", distributor, e);
                    metrics.stockRefreshed(distributor, "failed", due.size());
                    break;
                }
                List<Part> refreshed = new ArrayList<>();
                List<String> soldOut = new ArrayList<>();
                List<RankedPart> next = new ArrayList<>(kept.size());
                for (RankedPart r : kept) {
                    StockUpdate update = updates == null ? null : updates.get(r.part().distributorPartNumber());
                    if (update == null) {
                        next.add(r);
                    } else if (update.stock() <= 0) {
                        soldOut.add(r.part().distributorPartNumber());
                    } else {
                        Part p = r.part().toBuilder().stock(update.stock())
                                .prices(update.prices().isEmpty() ? r.part().prices() : update.prices())
                                .fetchedAt(now).build();
                        refreshed.add(p);
                        next.add(new RankedPart(p, r.score(), r.match(), r.mismatches(), r.unverified(),
                                r.belowSpec()));
                    }
                }
                kept = next;
                changed = true;
                log.info("{} stock refresh: {} parts updated, {} sold out", distributor, refreshed.size(),
                        soldOut.size());
                metrics.stockRefreshed(distributor, "ok", refreshed.size());
                metrics.stockRefreshed(distributor, "out_of_stock", soldOut.size());
                metrics.stockRefreshed(distributor, "failed", due.size() - refreshed.size() - soldOut.size());
                writeBack(distributor, refreshed, soldOut);
                if (soldOut.isEmpty()) {
                    break;
                }
            }
            List<RankedPart> ordered = demoteStale(kept, now);
            changed |= ordered != kept;
            out.put(distributor, List.copyOf(ordered));
        }
        return changed ? new RankedResults(out, ranked.mode(), ranked.note(), ranked.excluded(),
                ranked.excludedBelowSpec(), ranked.excludedDetail()) : ranked;
    }

    /**
     * Moves the parts whose stock and prices are stale ({@link #isStale}) below the fresh ones: each stale part's score
     * is lowered by {@code kina.cache.stale-rank-penalty} (reported at least 0) and it is placed before the first fresh
     * part of its group (meeting the request, then below spec) that scores lower; stale parts keep their relative
     * order. With the default penalty of 1.0 every stale part ends up after every fresh part of its group. Returns
     * {@code list} itself when nothing is stale.
     */
    List<RankedPart> demoteStale(List<RankedPart> list, Instant now) {
        if (list.stream().noneMatch(r -> isStale(r.part(), now))) {
            return list;
        }
        double penalty = properties.cache().staleRankPenalty();
        List<RankedPart> out = new ArrayList<>(list.size());
        List<RankedPart> stale = new ArrayList<>();
        for (RankedPart r : list) {
            (isStale(r.part(), now) ? stale : out).add(r);
        }
        for (RankedPart r : stale) {
            double adjusted = r.score() - penalty;
            int at = out.size();
            for (int i = 0; i < out.size(); i++) {
                RankedPart other = out.get(i);
                boolean laterGroup = !r.belowSpec() && other.belowSpec();
                boolean sameGroupLower = r.belowSpec() == other.belowSpec() && !isStale(other.part(), now)
                        && other.score() < adjusted;
                if (laterGroup || sameGroupLower) {
                    at = i;
                    break;
                }
            }
            out.add(at, r.withScore(Math.max(0, adjusted)));
        }
        return out;
    }

    private void writeBack(Distributor distributor, List<Part> refreshed, List<String> soldOut) {
        try {
            partCache.updateStock(refreshed);
            soldOut.forEach(number -> partCache.markSoldOut(distributor, number));
        } catch (RuntimeException e) {
            log.warn("Writing refreshed {} stock to the cache failed: {}", distributor, e.toString());
        }
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

    private SearchResponse assemble(Prepared prepared, Map<Distributor, Fetched> fetched, RankedResults ranked,
                                    String note) {
        SearchRequest request = prepared.request();
        ParsedQuery parsed = prepared.parsed();
        ConstraintPolicy policy = policy();
        boolean understood = parsed.understood();
        int lowStockThreshold = properties.search().lowStockThreshold();
        Instant now = clock.instant();
        List<DistributorResult> results = new ArrayList<>();
        List<String> empty = new ArrayList<>();
        Map<String, Integer> emptyExcluded = new java.util.LinkedHashMap<>();
        int emptyBelowSpec = 0;
        for (Distributor distributor : prepared.distributors()) {
            Fetched f = fetched.get(distributor);
            if (f == null) {
                continue;
            }
            List<RankedPart> rankedParts = ranked.byDistributor().getOrDefault(distributor, List.of());
            int returned = Math.min(prepared.maxResults(), rankedParts.size());
            List<RankedPart> top = rankedParts.subList(0, returned);
            List<PartResponse> parts = new ArrayList<>(returned);
            for (int i = 0; i < returned; i++) {
                RankedPart rp = top.get(i);
                Map<String, String> canonical = request.detail() == ResponseDetail.FULL ? null
                        : extractor.extract(rp.part());
                parts.add(PartResponse.of(rp.part(), new PartResponse.Ranking(i + 1, roundScore(rp.score()),
                                rp.match(), rp.mismatches(), rp.unverified(), rp.belowSpec()),
                        request.quantity(), request.detail(), canonical, lowStockThreshold, isStale(rp.part(), now),
                        now));
            }
            Integer exact = understood ? (int) top.stream().filter(RankedPart::exact).count() : null;
            Map<String, Integer> detail = ranked.excludedDetailBy(distributor);
            String hint = null;
            if (understood && parts.isEmpty() && f.error() == null) {
                // nothing satisfies the hard constraints (DESIGN.md 3.2 "Empty after the hard set"): no substitutes
                hint = policy.hint(parsed, List.of(distributor.name()), detail,
                        ranked.excludedBelowSpecBy(distributor), request.allowBelowSpec());
                empty.add(distributor.name());
                detail.forEach((k, v) -> emptyExcluded.merge(k, v, Integer::sum));
                emptyBelowSpec += ranked.excludedBelowSpecBy(distributor);
            }
            results.add(DistributorResult.builder()
                    .distributor(distributor)
                    .totalResults(f.totalResults())
                    .fetched(f.parts().size())
                    .returned(returned)
                    .cache(f.cache())
                    .error(f.error())
                    .parts(parts)
                    .fallbackQuery(f.fallbackQuery())
                    .rateLimitWaitedMs(f.rateLimitWaitedMs())
                    .distributorQuery(f.distributorQuery())
                    .excludedByConstraints(ranked.excludedBy(distributor))
                    .excludedByConstraintsDetail(detail)
                    .excludedBelowSpec(ranked.excludedBelowSpecBy(distributor))
                    .outOfStockMatches(f.outOfStockMatches())
                    .queryTermsDropped(f.queryTermsDropped())
                    .constraintsRelaxed(actuallyRelaxed(relaxable(parsed, f.constraintsRelaxed(), policy), top))
                    .exactMatches(exact)
                    .hint(hint)
                    .build());
        }
        String hint = !understood ? SearchResponse.NOT_UNDERSTOOD_HINT
                : empty.isEmpty() ? null
                : policy.hint(parsed, empty, emptyExcluded, emptyBelowSpec, request.allowBelowSpec());
        return new SearchResponse(parsed.originalText(), ParsedQueryResponse.from(parsed), ranked.mode(), note,
                results, understood, hint, SearchResponse.currenciesOf(results));
    }

    /**
     * The loosened constraints that may be reported as relaxed: those the policy lets relax for the request's family
     * (a hard constraint or a rating is never relaxed, even when LCSC's database search dropped its term: the ranker
     * excludes the parts that miss it).
     */
    static List<String> relaxable(ParsedQuery parsed, List<String> loosened, ConstraintPolicy policy) {
        if (loosened == null || loosened.isEmpty()) {
            return List.of();
        }
        return loosened.stream().filter(name -> policy.isRelaxable(parsed, name)).toList();
    }

    /**
     * Of the constraints a relaxation loosened (the ladder step of Mouser and TME, the terms LCSC's database search
     * dropped), those the returned parts really miss: a mismatch or an unverified constraint of that name. A step that
     * drops the dielectric and the package may still return parts with the requested dielectric, and LCSC drops terms
     * one at a time, so a loosened term is not necessarily one the results compromise (DESIGN.md 3.2).
     */
    static List<String> actuallyRelaxed(List<String> dropped, List<RankedPart> returned) {
        if (dropped == null || dropped.isEmpty()) {
            return List.of();
        }
        return dropped.stream()
                .filter(name -> returned.stream().anyMatch(r -> r.unverified().contains(name)
                        || r.mismatches().stream().anyMatch(m -> m.startsWith(name + ":"))))
                .toList();
    }

    static double roundScore(double score) {
        return Math.round(score * 1e4) / 1e4;
    }

    static String format(Duration duration) {
        long millis = duration.toMillis();
        return millis % 1000 == 0 ? (millis / 1000) + "s" : millis + "ms";
    }
}
