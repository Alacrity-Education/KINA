package ro.alacrity.kina.search;

import jakarta.annotation.PreDestroy;
import lombok.With;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.cache.SearchCacheRepository;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.Deadline;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.distributor.DistributorRegistry;
import ro.alacrity.kina.domain.BatchSearchRequest;
import ro.alacrity.kina.domain.BatchSearchResponse;
import ro.alacrity.kina.domain.ConstraintKind;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.RankingMode;
import ro.alacrity.kina.domain.SearchRequest;
import ro.alacrity.kina.domain.SearchResponse;
import ro.alacrity.kina.metrics.KinaMetrics;
import ro.alacrity.kina.search.RankingService.RankedResults;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
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
    private final StockRefresher stockRefresher;
    private final PageCollector pageCollector;
    private final DistributorRetriever lcscRetriever;
    private final DistributorRetriever cachedRetriever;
    private final ResponseAssembler assembler;
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
        this.stockRefresher = new StockRefresher(properties, registry, partCache, clock);
        this.assembler = new ResponseAssembler(properties, extractor, ranking, stockRefresher, clock);
        this.pageCollector = new PageCollector(extractor, clock);
        this.lcscRetriever = new LcscRetriever(properties, pageCollector, ranking);
        this.cachedRetriever = new CachedDistributorRetriever(properties, pageCollector, ranking, extractor,
                partCache, searchCache, clock);
    }

    @Autowired
    void setMetrics(KinaMetrics metrics) {
        this.metrics = metrics;
        stockRefresher.setMetrics(metrics);
        pageCollector.setMetrics(metrics);
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
        RankedResults ranked = stockRefresher.refresh(prepared, ranking.rank(prepared.parsed(),
                partsByDistributor(fetched), null, rankOptions(prepared)), deadline);
        if (log.isInfoEnabled()) {
            log.info("search '{}': fetch {} ms {}, rank {} ms ({})", prepared.parsed().normalizedKey(),
                    (fetchedAt - started) / 1_000_000, summary(fetched), (System.nanoTime() - fetchedAt) / 1_000_000,
                    ranked.mode().jsonValue());
        }
        SearchResponse response = assembler.assemble(prepared, fetched, ranked, ranked.note());
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
            results.add(assembler.assemble(p, fetched.get(i), stockRefresher.refresh(p, ranked, requestDeadline),
                    note));
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
        return ResponseAssembler.policyOf(ranking);
    }

    /** Whether the part's stock and prices are older than {@code kina.cache.ttl} ({@link StockRefresher#isStale}). */
    boolean isStale(Part part, Instant now) {
        return stockRefresher.isStale(part, now);
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
            Progress p = new Progress(DistributorRetriever.initialStatus(distributor, prepared.request().bypassCache()));
            progress.put(distributor, p);
            DistributorBudget budget = new DistributorBudget(requestDeadline, timeout);
            budgets.put(distributor, budget);
            futures.put(distributor, executor.submit(() -> retrieverFor(distributor).retrieve(client.get(), prepared, p, budget)));
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

    private DistributorRetriever retrieverFor(Distributor distributor) {
        return DistributorRetriever.usesPostgresCache(distributor) ? cachedRetriever : lcscRetriever;
    }

    private static String errorCode(Distributor distributor, Throwable error) {
        if (error instanceof DistributorException de) {
            log.info("{} search failed: {}", distributor, de.getMessage());
            return de.errorCode();
        }
        log.warn("{} search failed unexpectedly", distributor, error);
        return DistributorException.Kind.UNAVAILABLE.code();
    }

    /** Delegate for the tests: {@link PageCollector.Check#meets}. */
    boolean meetsRequest(ParsedQuery parsed, Part part) {
        return PageCollector.Check.meets(ranking, parsed, part);
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
                    && !ConstraintKind.isExactRating(constraint.kind(), parsed.family())) {
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

    static String format(Duration duration) {
        long millis = duration.toMillis();
        return millis % 1000 == 0 ? (millis / 1000) + "s" : millis + "ms";
    }
}
