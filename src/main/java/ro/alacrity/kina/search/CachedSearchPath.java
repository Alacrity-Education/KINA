package ro.alacrity.kina.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.cache.CachedSearch;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.cache.PhraseJournalRepository;
import ro.alacrity.kina.cache.SearchCacheRepository;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.search.PageCollector.Check;
import ro.alacrity.kina.search.PageCollector.Collected;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The cached-search path of Mouser and TME (DESIGN.md 3.2 steps 1 to 4): the Postgres cache of the request's list,
 * the live search, the relaxation ladder and the cache write. Every mode of the field index uses it
 * ({@link FieldIndexStrategy}): {@code off}, {@code shadow} and {@code augment} always, {@code on} when the field-first
 * flow cannot answer. It writes the phrase journal in every mode, so the journal is warm when the mode changes.
 */
@Slf4j
@Component
final class CachedSearchPath {

    @Autowired private KinaProperties properties;
    @Autowired private PageCollector pages;
    @Autowired private RankingService ranking;
    @Autowired private ParametricExtractor extractor;
    @Autowired private PartCacheRepository partCache;
    @Autowired private SearchCacheRepository searchCache;
    @Autowired private PhraseJournalRepository journal;
    @Autowired private Clock clock;

    private record Attempt(Collected collected, String phrase, List<String> relaxed) {
    }

    /**
     * The cached-search path of one distributor (DESIGN.md 3.2 steps 1 to 4): the request's cached list while it is
     * fresh (extended when it is short), else the live search with the relaxation ladder; the cache write; an expired
     * list when the live search fails. Runs on the distributor's fetch thread.
     */
    Fetched search(DistributorClient client, Prepared prepared, Progress progress, DistributorBudget deadline) {
        Distributor distributor = client.distributor();
        ParsedQuery parsed = prepared.parsed();
        DistributorRetriever.Plan plan = DistributorRetriever.plan(properties, ranking, distributor, prepared);
        String query = plan.query();
        int window = plan.window();
        int maxPages = plan.maxPages();
        Check meets = plan.meets();

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
                        : relaxedBy(distributor, parsed, query, fallbackQuery, ConstraintPolicy.of(ranking));
                // metadata is kept and stock ages on its own: a part of any stock age is used (refreshed or marked
                // stale after ranking, DESIGN.md 3.2 "Cache model"); a part missing or sold out is a miss
                Optional<List<Part>> parts = readCachedParts(distributor, search.partNumbers(), false);
                if (parts.isPresent()) {
                    List<Part> cachedParts = parts.get().stream().map(extractor::enrich).toList();
                    boolean nothingReturnable = !cachedParts.isEmpty() && cachedParts.stream()
                            .noneMatch(p -> meets.returnable(p, prepared.request().allowBelowSpec()));
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
        int outOfStockBefore = progress.outOfStock;
        try {
            collected = pages.collect(client, query, 0, window, maxPages, List.of(), progress, deadline, meets,
                    parsed.family());
            recordPhrase(distributor, query, 0, collected, progress.outOfStock - outOfStockBefore, queryKey, now);
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
        int ladderStep = 0;
        for (DistributorPhraser.Relaxation step
                : DistributorPhraser.ladder(distributor, parsed, query, ConstraintPolicy.of(ranking))) {
            ladderStep++;
            if (collected.meeting() > 0 || collected.error() != null || deadline.remainingNanos() <= 0) {
                break;
            }
            log.info("{} found nothing that meets '{}' with '{}', relaxing to '{}'{}", distributor, queryKey,
                    fallbackQuery != null ? fallbackQuery : query, step.phrase(),
                    step.relaxed().isEmpty() ? "" : " (loosens " + step.relaxed() + ")");
            progress.fallbackQuery = step.phrase();
            progress.constraintsRelaxed = step.relaxed();
            Collected previous = collected;
            outOfStockBefore = progress.outOfStock;
            try {
                collected = pages.collect(client, step.phrase(), 0, window, maxPages, List.of(), progress, deadline,
                        meets, parsed.family());
                recordPhrase(distributor, step.phrase(), ladderStep, collected,
                        progress.outOfStock - outOfStockBefore, queryKey, now);
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
        // a live list starts without lookup outcomes: an expired list looks its part numbers up again
        store(distributor, queryKey, collected, now, fallbackQuery, progress.outOfStock, relaxed, null);
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
            collected = pages.collect(client, fallbackQuery != null ? fallbackQuery : query, offset, window, maxPages,
                    cachedParts, progress, deadline, meets, prepared.parsed().family());
            recordPhrase(distributor, fallbackQuery != null ? fallbackQuery : query, fallbackQuery != null ? 1 : 0,
                    collected, progress.outOfStock, search.queryKey(), clock.instant());
        } catch (DistributorException e) {
            // serve what the cache holds, flagged with the error
            log.info("{} could not extend cached search '{}': {}", distributor, search.queryKey(), e.getMessage());
            return new Fetched(distributor, cachedParts, search.totalResults(), CacheStatus.PARTIAL,
                    e.errorCode(), fallbackQuery).withOutOfStockMatches(search.outOfStockMatches())
                    .withConstraintsRelaxed(relaxed);
        }
        store(distributor, search.queryKey(), collected, search.fetchedAt(), fallbackQuery, progress.outOfStock,
                relaxed, search.requestedParts());
        return collected.toFetched(distributor, CacheStatus.PARTIAL, fallbackQuery)
                .withOutOfStockMatches(progress.outOfStock).withConstraintsRelaxed(relaxed);
    }

    /**
     * Records a phrase the distributor answered in the phrase journal (V15), whatever the mode: the journal is warm
     * when {@code kina.search.field-index.mode=on} starts. Never fails the search.
     */
    private void recordPhrase(Distributor distributor, String phrase, int ladderStep, Collected collected,
                              int outOfStock, String queryKey, Instant at) {
        if (collected.error() != null) {
            return;   // a failed later page: the phrase was not answered (DESIGN.md 3.2)
        }
        try {
            journal.record(new PhraseJournalRepository.Entry(distributor, DistributorPhraser.phraseKey(phrase), phrase, at,
                    collected.totalResults(), collected.nextOffset(), collected.exhausted(), outOfStock,
                    collected.all().isEmpty(), ladderStep, queryKey));
        } catch (RuntimeException e) {
            log.warn("Recording {} phrase '{}' in the journal failed: {}", distributor, phrase, e.toString());
        }
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
     * Fresh for {@code kina.cache.ttl}, except a list without any in-stock part, which is fresh only for
     * {@code kina.cache.empty-result-ttl} (whichever is shorter).
     */
    static boolean isFresh(CachedSearch search, Instant freshSince, Instant emptyFreshSince) {
        Instant since = search.partNumbers().isEmpty() && emptyFreshSince.isAfter(freshSince)
                ? emptyFreshSince : freshSince;
        return !search.fetchedAt().isBefore(since);
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
    Optional<Fetched> servedStale(Distributor distributor, ParsedQuery parsed, String query,
                                          CachedSearch search, DistributorException error) {
        List<Part> parts = readCachedParts(distributor, search.partNumbers(), true).orElse(List.of());
        if (parts.isEmpty()) {
            return Optional.empty();
        }
        log.info("{} search '{}' failed ({}); serving the expired cached list of {} parts", distributor,
                search.queryKey(), error.errorCode(), parts.size());
        List<String> relaxed = search.constraintsRelaxed() != null ? search.constraintsRelaxed()
                : relaxedBy(distributor, parsed, query, search.fallbackQuery(), ConstraintPolicy.of(ranking));
        return Optional.of(new Fetched(distributor, parts.stream().map(extractor::enrich).toList(),
                search.totalResults(), CacheStatus.STALE, error.errorCode(), search.fallbackQuery())
                .withOutOfStockMatches(search.outOfStockMatches()).withConstraintsRelaxed(relaxed));
    }

    Optional<CachedSearch> readCachedSearch(Distributor distributor, String queryKey) {
        try {
            return searchCache.find(distributor, queryKey);
        } catch (RuntimeException e) {
            log.warn("Reading the cached {} search failed, fetching from the distributor: {}", distributor,
                    e.toString());
            return Optional.empty();
        }
    }

    /**
     * Writes the new parts and the search list. A list whose parts all fail the request (strict constraints, below
     * spec) is never stored as a reusable search: only its parts are cached. Cache failures are logged, never
     * propagated.
     */
    private void store(Distributor distributor, String queryKey, Collected collected, Instant listFetchedAt,
                       String fallbackQuery, int outOfStockMatches, List<String> constraintsRelaxed,
                       Map<String, CachedSearch.RequestedPart> requestedParts) {
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
                    listFetchedAt, collected.nextOffset(), fallbackQuery, outOfStockMatches, constraintsRelaxed,
                    requestedParts));
        } catch (RuntimeException e) {
            log.warn("Caching {} results for '{}' failed: {}", distributor, queryKey, e.toString());
        }
    }
}
