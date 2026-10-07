package ro.alacrity.kina.search;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.cache.SearchCacheRepository;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.Deadline;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.distributor.DistributorRegistry;
import ro.alacrity.kina.domain.BatchSearchRequest;
import ro.alacrity.kina.domain.BatchSearchResponse;
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
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Search orchestrator (DESIGN.md sections 3.2 and 3.3). It runs the five stages of a search in sequence and holds
 * nothing else:
 * <ol>
 *   <li>{@link #prepare}: validate and parse the request into a {@link Prepared};</li>
 *   <li>{@link ParallelRetrieval}: fetch every requested distributor in parallel on virtual threads, one
 *       {@link DistributorRetriever} each ({@link LcscRetriever}, {@link CachedDistributorRetriever}, which page
 *       through {@link PageCollector}), giving a {@link Fetched} per distributor;</li>
 *   <li>{@link RankingService}: rank the parts;</li>
 *   <li>{@link StockRefresher}: refresh the stock of the ranked parts;</li>
 *   <li>{@link ResponseAssembler}: assemble the {@link SearchResponse}.</li>
 * </ol>
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
    private final KinaProperties properties;
    private final DistributorRegistry registry;
    private final QueryParser parser;
    private final RankingService ranking;
    private final ExecutorService executor;
    private final StockRefresher stockRefresher;
    private final PageCollector pageCollector;
    private final ParallelRetrieval retrieval;
    private final ResponseAssembler assembler;
    private KinaMetrics metrics = KinaMetrics.NOOP;

    @Autowired
    public PartSearchService(KinaProperties properties, DistributorRegistry registry, QueryParser parser,
                             ParametricExtractor extractor, RankingService ranking, PartCacheRepository partCache,
                             SearchCacheRepository searchCache, Clock clock) {
        this.properties = properties;
        this.registry = registry;
        this.parser = parser;
        this.ranking = ranking;
        this.executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("kina-search-", 0).factory());
        this.stockRefresher = new StockRefresher(properties, registry, partCache, clock);
        this.assembler = new ResponseAssembler(properties, extractor, ranking, stockRefresher, clock);
        this.pageCollector = new PageCollector(extractor, clock);
        this.retrieval = new ParallelRetrieval(properties, registry, parser, executor,
                new LcscRetriever(properties, pageCollector, ranking),
                new CachedDistributorRetriever(properties, pageCollector, ranking, extractor, partCache, searchCache,
                        clock));
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
        Map<Distributor, Fetched> fetched = retrieval.retrieveAll(prepared, deadline);
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
                    return retrieval.retrieveAll(p, requestDeadline);
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

    /** Delegate for the tests: {@link PageCollector.Check#meets}. */
    boolean meetsRequest(ParsedQuery parsed, Part part) {
        return PageCollector.Check.meets(ranking, parsed, part);
    }

    /** {@code now + kina.search.max-request-duration}. */
    Deadline requestDeadline() {
        return Deadline.after(properties.search().maxRequestDuration());
    }

    // ---- preparation ----------------------------------------------------------------------------------------------

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
