package ro.alacrity.kina.search;

import lombok.extern.slf4j.Slf4j;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.distributor.PartLookupResult;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The last step of a retrieval when the query names part numbers (DESIGN.md 3.2 "Requested part numbers"): a part
 * number that none of the fetched parts carries is looked up directly ({@link DistributorClient#lookup}, one call per
 * missing part number, within the distributor's budget). An in-stock part joins the fetched parts (and the part
 * cache); a part the distributor lists without ships-now stock joins {@link Fetched#listed()} with stock 0 and is
 * cached with {@code in_stock = false}: the one exception to the stock rule (DESIGN.md 2), so it is never served to a
 * search that does not name it. A failed lookup is logged and leaves the retrieval as it was.
 */
@Slf4j
final class RequestedLookup {

    private final ParametricExtractor extractor;
    /** Null for LCSC (its database is the cache). */
    private final PartCacheRepository partCache;
    private final Clock clock;

    RequestedLookup(ParametricExtractor extractor, PartCacheRepository partCache, Clock clock) {
        this.extractor = extractor;
        this.partCache = partCache;
        this.clock = clock;
    }

    /** {@code fetched} with the parts the query names that it lacks, when the distributor has them. */
    Fetched complete(DistributorClient client, Prepared prepared, Fetched fetched, DistributorBudget budget) {
        ParsedQuery query = prepared.parsed();
        if (!query.namesPartNumber() || fetched.error() != null) {
            return fetched;
        }
        List<Part> inStock = new ArrayList<>();
        List<Part> listed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        fetched.parts().forEach(p -> seen.add(p.distributorPartNumber()));
        for (String number : query.partNumbers()) {
            if (fetched.parts().stream().anyMatch(p -> PartNumbers.requests(number, p))
                    || inStock.stream().anyMatch(p -> PartNumbers.requests(number, p))) {
                continue;
            }
            if (budget.remainingNanos() <= 0) {
                break;
            }
            PartLookupResult result;
            try {
                result = client.lookup(number, budget.deadline());
            } catch (DistributorException e) {
                log.info("{} lookup of requested part number {} failed: {}", client.distributor(), number,
                        e.getMessage());
                continue;
            } catch (RuntimeException e) {
                log.warn("{} lookup of requested part number {} failed unexpectedly", client.distributor(), number, e);
                continue;
            }
            if (result == null) {
                continue;
            }
            Part part = result.asOptional().or(result::listed).orElse(null);
            if (part == null || !PartNumbers.requests(number, part) || !seen.add(part.distributorPartNumber())) {
                continue;
            }
            Part enriched = extractor.enrich(part.fetchedAt() != null ? part
                    : part.toBuilder().fetchedAt(clock.instant()).build());
            (enriched.stock() > 0 ? inStock : listed).add(enriched);
        }
        if (inStock.isEmpty() && listed.isEmpty()) {
            return fetched;
        }
        cache(inStock, listed);
        List<Part> parts = new ArrayList<>(fetched.parts());
        parts.addAll(inStock);
        return fetched.withParts(parts).withListed(listed);
    }

    private void cache(List<Part> inStock, List<Part> listed) {
        if (partCache == null) {
            return;
        }
        try {
            partCache.upsertAll(inStock);
            partCache.upsertListed(listed);
        } catch (RuntimeException e) {
            log.warn("Caching requested parts failed: {}", e.toString());
        }
    }
}
