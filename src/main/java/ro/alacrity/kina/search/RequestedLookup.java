package ro.alacrity.kina.search;

import lombok.extern.slf4j.Slf4j;
import ro.alacrity.kina.cache.CachedSearch.RequestedPart;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.distributor.PartLookupResult;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The last step of a retrieval when the query names part numbers (DESIGN.md 3.2 "Requested part numbers"): a part
 * number that none of the fetched parts carries is looked up directly ({@link DistributorClient#lookup}, one call per
 * missing part number, within the distributor's budget). An in-stock part joins the fetched parts (and the part
 * cache); a part the distributor lists without ships-now stock joins {@link Fetched#listed()} with stock 0 and is
 * cached with {@code in_stock = false}: the one exception to the stock rule (DESIGN.md 2), so it is never served to a
 * search that does not name it. A failed lookup is logged and leaves the retrieval as it was.
 *
 * <p>The outcome of a cached search's earlier lookups ({@code cached_searches.requested_parts}) is honoured instead of
 * calling the distributor again: {@code not_found} is not retried, a {@code listed} part is read from its
 * {@code in_stock = false} row ({@link PartCacheRepository#findListed}), and an in-stock part is already in the cached
 * list. The new outcomes are returned for the caller to store.
 */
@Slf4j
final class RequestedLookup {

    /**
     * The completed retrieval and what the live lookups learned.
     *
     * @param fetched     the retrieval with the requested parts added
     * @param learned     the outcome of every live lookup, by the part number as sent (empty when none ran)
     * @param inStock     the distributor part numbers of the in-stock parts the live lookups found
     */
    record Result(Fetched fetched, Map<String, RequestedPart> learned, List<String> inStock) {
    }

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
        return complete(client, prepared, fetched, budget, Map.of()).fetched();
    }

    /**
     * As {@link #complete(DistributorClient, Prepared, Fetched, DistributorBudget)}, honouring the {@code known}
     * outcomes of a cached search for the same query.
     */
    Result complete(DistributorClient client, Prepared prepared, Fetched fetched, DistributorBudget budget,
                    Map<String, RequestedPart> known) {
        ParsedQuery query = prepared.parsed();
        if (!query.namesPartNumber() || fetched.error() != null) {
            return new Result(fetched, Map.of(), List.of());
        }
        Map<String, RequestedPart> outcomes = known == null ? Map.of() : known;
        List<Part> inStock = new ArrayList<>();
        List<Part> listed = new ArrayList<>();
        Map<String, RequestedPart> learned = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        fetched.parts().forEach(p -> seen.add(p.distributorPartNumber()));
        for (String number : query.partNumbers()) {
            if (fetched.parts().stream().anyMatch(p -> PartNumbers.requests(number, p))
                    || inStock.stream().anyMatch(p -> PartNumbers.requests(number, p))) {
                continue;
            }
            RequestedPart before = outcomes.get(number);
            if (before != null && RequestedPart.NOT_FOUND.equals(before.status())) {
                continue;   // looked up for this cached search: the distributor does not have it
            }
            if (before != null && RequestedPart.LISTED.equals(before.status())) {
                Optional<Part> cached = cachedListed(client, query, number, before.partNumber());
                if (cached.isPresent() && seen.add(cached.get().distributorPartNumber())) {
                    listed.add(extractor.enrich(cached.get()));
                    continue;
                }
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
            if (part == null || !PartNumbers.requests(number, part)) {
                learned.put(number, new RequestedPart(RequestedPart.NOT_FOUND, null));
                continue;
            }
            Part enriched = extractor.enrich(part.fetchedAt() != null ? part
                    : part.toBuilder().fetchedAt(clock.instant()).build());
            boolean stocked = enriched.stock() > 0;
            learned.put(number, new RequestedPart(stocked ? RequestedPart.FOUND : RequestedPart.LISTED,
                    enriched.distributorPartNumber()));
            if (seen.add(enriched.distributorPartNumber())) {
                (stocked ? inStock : listed).add(enriched);
            }
        }
        cache(inStock, listed.stream().filter(p -> learned.values().stream()
                .anyMatch(r -> p.distributorPartNumber().equals(r.partNumber()))).toList());
        if (inStock.isEmpty() && listed.isEmpty()) {
            return new Result(fetched, learned, List.of());
        }
        List<Part> parts = new ArrayList<>(fetched.parts());
        parts.addAll(inStock);
        return new Result(fetched.withParts(parts).withListed(listed), learned,
                inStock.stream().map(Part::distributorPartNumber).toList());
    }

    /**
     * The cached {@code in_stock = false} row of a part listed without stock, read only for a part number the query
     * names explicitly (DESIGN.md 2, stock rule) and only when the row is the part that number requests.
     */
    private Optional<Part> cachedListed(DistributorClient client, ParsedQuery query, String number,
                                        String partNumber) {
        if (partCache == null || partNumber == null || !query.partNumbers().contains(number)) {
            return Optional.empty();
        }
        try {
            return partCache.findListed(client.distributor(), partNumber)
                    .filter(p -> p.stock() <= 0 && PartNumbers.requests(number, p));
        } catch (RuntimeException e) {
            log.warn("Reading the listed {} part {} failed: {}", client.distributor(), partNumber, e.toString());
            return Optional.empty();
        }
    }

    private void cache(List<Part> inStock, List<Part> listed) {
        if (partCache == null || inStock.isEmpty() && listed.isEmpty()) {
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
