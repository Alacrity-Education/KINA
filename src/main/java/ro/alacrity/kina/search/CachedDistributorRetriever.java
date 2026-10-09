package ro.alacrity.kina.search;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.cache.CachedSearch;
import ro.alacrity.kina.cache.SearchCacheRepository;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.config.KinaProperties.FieldIndexMode;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.domain.Distributor;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Mouser and TME retrieval (DESIGN.md 3.2): the strategy of {@code kina.search.field-index.mode}
 * ({@link FieldIndexStrategy}: one bean per mode) searches, then a part number the query names that the search did
 * not bring is looked up directly. Built by {@link PartSearchService}.
 */
@Slf4j
@Component
final class CachedDistributorRetriever implements DistributorRetriever {

    @Autowired private KinaProperties properties;
    @Autowired private SearchCacheRepository searchCache;
    @Autowired private RequestedLookup requested;
    @Autowired private CachedSearchPath cached;
    @Autowired private List<FieldIndexStrategy> strategies;
    private final Map<FieldIndexMode, FieldIndexStrategy> byMode = new EnumMap<>(FieldIndexMode.class);

    @PostConstruct
    void init() {
        strategies.forEach(s -> byMode.put(s.mode(), s));
    }

    /**
     * DESIGN.md 3.2 for one distributor. Runs on a virtual thread; no further page is requested once {@code budget}
     * has run out.
     */
    @Override
    public Fetched retrieve(DistributorClient client, Prepared prepared, Progress progress,
                            DistributorBudget deadline) {
        FieldIndexMode mode = properties.search().fieldIndex().mode();
        FieldIndexStrategy strategy = byMode.get(mode);
        if (strategy == null) {
            throw new IllegalStateException("no strategy for kina.search.field-index.mode=" + mode);
        }
        Fetched searched = strategy.search(client, prepared, progress, deadline);
        // a part number the query names that the search did not bring is looked up directly, once per cached search
        if (!prepared.parsed().namesPartNumber() || searched.error() != null) {
            return searched;
        }
        Distributor distributor = client.distributor();
        String queryKey = prepared.parsed().normalizedKey();
        Map<String, CachedSearch.RequestedPart> known = Map.of();
        if (searched.cache() == CacheStatus.HIT || searched.cache() == CacheStatus.PARTIAL) {
            // the key contains the part numbers, so this row belongs to an explicit part-number search
            known = cached.readCachedSearch(distributor, queryKey).map(CachedSearch::requestedParts)
                    .orElse(Map.of());
        }
        RequestedLookup.Result result = requested.complete(client, prepared, searched, deadline, known);
        if (!result.learned().isEmpty()) {
            try {
                searchCache.recordRequested(distributor, queryKey, result.inStock(), result.learned());
            } catch (RuntimeException e) {
                log.warn("Caching the requested part numbers of {} '{}' failed: {}", distributor, queryKey,
                        e.toString());
            }
        }
        return result.fetched();
    }
}
