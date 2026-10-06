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
        Deadline deadline = requestDeadline();
        long started = System.nanoTime();
        Map<Distributor, Fetched> fetched = fetchAll(prepared, deadline);
        long fetchedAt = System.nanoTime();
        RankedResults ranked = ranking.rank(prepared.parsed(), partsByDistributor(fetched), null,
                prepared.request().quantity());
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
                ranked = ranking.rank(p.parsed(), parts, Duration.ZERO, p.request().quantity());
                // with the cross-encoder disabled the fallback has nothing to do with the budget: keep that note
                note = ranked.mode() == RankingMode.FALLBACK && properties.ranking().crossEncoder().enabled()
                        ? "batch ranking budget of " + format(batchBudget) + " exhausted"
                        : ranked.note();
            } else {
                Duration perQuery = properties.ranking().timeout();
                ranked = ranking.rank(p.parsed(), parts, remaining.compareTo(perQuery) < 0 ? remaining : perQuery,
                        p.request().quantity());
                note = ranked.note();
            }
            results.add(assemble(p, fetched.get(i), ranked, note));
        }
        return new BatchSearchResponse(results);
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
     * distributor-reported total, the cache status, an error code (null on success) and the time spent waiting on
     * rate limits.
     */
    record Fetched(Distributor distributor, List<Part> parts, Integer totalResults, CacheStatus cache, String error,
                   String fallbackQuery, @With long rateLimitWaitedMs, @With String distributorQuery,
                   @With Integer outOfStockMatches, @With List<String> relaxed) {

        Fetched {
            parts = parts == null ? List.of() : List.copyOf(parts);
            relaxed = relaxed == null ? List.of() : List.copyOf(relaxed);
        }

        Fetched(Distributor distributor, List<Part> parts, Integer totalResults, CacheStatus cache, String error,
                String fallbackQuery, long rateLimitWaitedMs) {
            this(distributor, parts, totalResults, cache, error, fallbackQuery, rateLimitWaitedMs, null, null, null);
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
                        DistributorException.Kind.TIMEOUT.code(), p.fallbackQuery).withOutOfStockMatches(p.outOfStock);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                fetched = Fetched.failed(distributor, p.cache, DistributorException.Kind.TIMEOUT.code());
            } catch (ExecutionException | CancellationException e) {
                Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                fetched = new Fetched(distributor, p.parts, p.totalResults, p.cache,
                        errorCode(distributor, cause), p.fallbackQuery).withOutOfStockMatches(p.outOfStock);
            }
            String phrase = DistributorPhraser.phrase(distributor, prepared.parsed());
            String used = fetched.fallbackQuery() != null ? fetched.fallbackQuery()
                    : phrase != null ? phrase : prepared.parsed().originalText();
            results.put(distributor, fetched.withRateLimitWaitedMs(budget.rateLimitWaitedMillis())
                    .withDistributorQuery(phrase)
                    .withRelaxed(relaxed(prepared.parsed(), used, fetched.relaxed())));
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
     * DESIGN.md 3.2 for one distributor. Runs on a virtual thread; no further page is requested once {@code budget}
     * has run out.
     */
    Fetched fetchDistributor(DistributorClient client, Prepared prepared, Progress progress, DistributorBudget deadline) {
        Distributor distributor = client.distributor();
        // connector queries are sent in the distributor's own wording; the cache key stays the user's query
        String phrase = DistributorPhraser.phrase(distributor, prepared.parsed());
        String query = phrase != null ? phrase : prepared.parsed().originalText();
        int window = window(distributor, prepared.maxResults());
        int maxPages = maxPages(distributor);

        if (!usesPostgresCache(distributor)) {
            Collected collected = collect(client, query, 0, window, maxPages, List.of(), progress, deadline);
            return collected.toFetched(distributor, CacheStatus.NOT_APPLICABLE)
                    .withOutOfStockMatches(progress.outOfStock);
        }

        String queryKey = prepared.parsed().normalizedKey();
        Instant now = clock.instant();
        Instant freshSince = now.minus(properties.cache().ttl());
        Instant emptyFreshSince = now.minus(properties.cache().emptyResultTtl());

        if (!prepared.request().bypassCache()) {
            Optional<CachedSearch> cached = readCachedSearch(distributor, queryKey)
                    .filter(c -> isFresh(c, freshSince, emptyFreshSince));
            if (cached.isPresent()) {
                CachedSearch search = cached.get();
                String fallbackQuery = search.fallbackQuery();
                Optional<List<Part>> parts = readCachedParts(distributor, search.partNumbers(), freshSince);
                if (parts.isPresent()) {
                    List<Part> cachedParts = parts.get().stream().map(extractor::enrich).toList();
                    if (isSufficient(search, prepared.maxResults(), window)) {
                        return new Fetched(distributor, cachedParts, search.totalResults(), CacheStatus.HIT, null,
                                fallbackQuery).withOutOfStockMatches(search.outOfStockMatches());
                    }
                    // fresh but short and not exhausted: further querying is needed (with the phrase the list
                    // was built from, so the raw offsets stay valid)
                    progress.cache = CacheStatus.PARTIAL;
                    progress.parts = cachedParts;
                    progress.totalResults = search.totalResults();
                    progress.fallbackQuery = fallbackQuery;
                    progress.outOfStock = search.outOfStockMatches() == null ? 0 : search.outOfStockMatches();
                    int offset = search.nextOffset() != null ? search.nextOffset() : cachedParts.size();
                    Collected collected;
                    try {
                        collected = collect(client, fallbackQuery != null ? fallbackQuery : query, offset, window,
                                maxPages, cachedParts, progress, deadline);
                    } catch (DistributorException e) {
                        // serve what the cache holds, flagged with the error
                        log.info("{} could not extend cached search '{}': {}", distributor, queryKey, e.getMessage());
                        return new Fetched(distributor, cachedParts, search.totalResults(), CacheStatus.PARTIAL,
                                e.errorCode(), fallbackQuery).withOutOfStockMatches(search.outOfStockMatches());
                    }
                    store(distributor, queryKey, collected, search.fetchedAt(), fallbackQuery, progress.outOfStock);
                    return collected.toFetched(distributor, CacheStatus.PARTIAL, fallbackQuery)
                            .withOutOfStockMatches(progress.outOfStock);
                }
                // some cached parts are missing or stale: refetch from the start
            }
        }
        CacheStatus status = prepared.request().bypassCache() ? CacheStatus.BYPASSED : CacheStatus.MISS;
        Collected collected = collect(client, query, 0, window, maxPages, List.of(), progress, deadline);
        String fallbackQuery = null;
        // relaxation ladder (DESIGN.md 3.2): until a phrase finds an in-stock part
        for (String step : DistributorPhraser.relaxations(distributor, prepared.parsed(), query)) {
            if (!collected.all().isEmpty() || collected.error() != null || deadline.remainingNanos() <= 0) {
                break;
            }
            log.info("{} found no in-stock part for '{}', relaxing to '{}'", distributor,
                    fallbackQuery != null ? fallbackQuery : query, step);
            progress.fallbackQuery = step;
            Collected previous = collected;
            try {
                collected = collect(client, step, 0, window, maxPages, List.of(), progress, deadline);
            } catch (DistributorException e) {
                // an earlier phrase did answer (with nothing): report the failure, cache nothing
                log.info("{} relaxed search '{}' failed: {}", distributor, step, e.getMessage());
                return new Fetched(distributor, List.of(), previous.totalResults(), status, e.errorCode(), step)
                        .withOutOfStockMatches(progress.outOfStock);
            }
            fallbackQuery = step;
        }
        store(distributor, queryKey, collected, now, fallbackQuery, progress.outOfStock);
        return collected.toFetched(distributor, status, fallbackQuery).withOutOfStockMatches(progress.outOfStock);
    }

    /**
     * The stated constraints that were not part of the search that produced the parts ({@code relaxed}, DESIGN.md
     * 3.2): those the phrase actually sent ({@code used}: the relaxed phrase, the distributor phrase or the user's
     * text) does not state, plus what the distributor's own relaxation dropped ({@code dropped}, LCSC). Empty for
     * connector queries, whose phrases are rewritten into distributor wording.
     */
    List<String> relaxed(ParsedQuery parsed, String used, List<String> dropped) {
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
        if (dropped != null) {
            out.addAll(dropped);
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
     * are left out too (the last relaxation step); null when that leaves nothing to drop.
     */
    static String corePhrase(ParsedQuery parsed, Distributor distributor, boolean qualifiers) {
        if (!qualifiers && parsed.dielectric() == null && parsed.technology() == null) {
            return null;
        }
        List<String> terms = new ArrayList<>();
        String familyWord = Recognizers.familyToken(parsed.originalText(), parsed.family());
        if (familyWord != null) {
            terms.add(familyWord);
        }
        int parametric = 0;
        for (ParsedQuery.Constraint constraint : parsed.constraints().values()) {
            if (ParsedQuery.TOLERANCE.equals(constraint.kind()) || constraint.display() == null
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
        if (parsed.packageName() != null) {
            terms.add(parsed.packageName());
            parametric++;
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
                     String error, List<String> relaxed) {

        Collected(List<Part> all, List<Part> fetched, Integer totalResults, boolean exhausted, int nextOffset,
                  String error) {
            this(all, fetched, totalResults, exhausted, nextOffset, error, List.of());
        }

        Fetched toFetched(Distributor distributor, CacheStatus cache) {
            return toFetched(distributor, cache, null);
        }

        Fetched toFetched(Distributor distributor, CacheStatus cache, String fallbackQuery) {
            return new Fetched(distributor, all, totalResults, cache, error, fallbackQuery).withRelaxed(relaxed);
        }
    }

    /**
     * Pages through {@link DistributorClient#search} from {@code offset} until {@code window} parts are held, the
     * distributor reports no more results, {@code maxPages} pages were requested or the next page would not fit
     * within {@code deadline}. Pages have the distributor's {@link DistributorClient#maxPageSize()} (the first one is
     * shortened to end on a page boundary) because distributors drop parts without ships-now stock: paging is driven
     * by raw record offsets, not by the number of parts kept. A failure on the first page propagates; a failure on a
     * later page keeps what was collected and reports the error code.
     */
    Collected collect(DistributorClient client, String query, int offset, int window, int maxPages,
                      List<Part> existing, Progress progress, DistributorBudget deadline) {
        Distributor distributor = client.distributor();
        boolean pagedByRecords = usesPostgresCache(distributor);
        int outOfStock = 0;
        List<String> relaxed = List.of();
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
        // while every match so far is out of stock, up to EXTRA_OUT_OF_STOCK_PAGES more pages (DESIGN.md 3.2)
        while ((pages < maxPages || all.isEmpty() && outOfStock > 0 && pages < maxPages + EXTRA_OUT_OF_STOCK_PAGES)
                && hasMore && all.size() < window) {
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
            next += limit;
            total = page.totalResults();
            hasMore = page.hasMore();
            outOfStock += page.outOfStock();
            if (!page.relaxed().isEmpty()) {
                relaxed = page.relaxed();
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
            }
            progress.parts = List.copyOf(all);
            progress.totalResults = total;
        }
        return new Collected(all, fetched, total, !hasMore, next, error, relaxed);
    }

    /** Writes the new parts and the search list. Cache failures are logged, never propagated. */
    private void store(Distributor distributor, String queryKey, Collected collected, Instant listFetchedAt,
                       String fallbackQuery, int outOfStockMatches) {
        try {
            partCache.upsertAll(collected.fetched());
            searchCache.upsert(new CachedSearch(distributor, queryKey, collected.totalResults(),
                    collected.all().stream().map(Part::distributorPartNumber).toList(), collected.exhausted(),
                    listFetchedAt, collected.nextOffset(), fallbackQuery, outOfStockMatches));
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
                Map<String, String> canonical = request.detail() == ResponseDetail.FULL ? null
                        : extractor.extract(rp.part());
                parts.add(PartResponse.of(rp.part(), i + 1, roundScore(rp.score()), rp.match(), rp.mismatches(),
                        request.quantity(), request.detail(), canonical));
            }
            int exact = (int) parts.stream().filter(p -> p.match() != null && p.match() >= 0.995).count();
            results.add(DistributorResult.builder()
                    .distributor(distributor)
                    .totalResults(f.totalResults())
                    .fetched(rankedParts.size())
                    .returned(returned)
                    .cache(f.cache())
                    .error(f.error())
                    .parts(parts)
                    .fallbackQuery(f.fallbackQuery())
                    .rateLimitWaitedMs(f.rateLimitWaitedMs())
                    .distributorQuery(f.distributorQuery())
                    .excludedByConstraints(ranked.excludedBy(distributor))
                    .outOfStockMatches(f.outOfStockMatches())
                    .relaxed(f.relaxed())
                    .exactMatches(exact)
                    .build());
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
