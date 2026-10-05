package ro.alacrity.kina.cache;

import ro.alacrity.kina.domain.Distributor;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One row of {@code cached_searches} (DESIGN.md sections 3.2 and 8): the ordered distributor part numbers a
 * normalised query returned.
 *
 * @param distributor  the distributor that was searched
 * @param queryKey     the normalised query key (owned by the query parser)
 * @param totalResults distributor-reported total, null when unknown
 * @param partNumbers  distributor part numbers in the distributor's relevance order
 * @param exhausted    true when the distributor reported no further results
 * @param fetchedAt    when the list was (last) fetched
 */
public record CachedSearch(
        Distributor distributor,
        String queryKey,
        Integer totalResults,
        List<String> partNumbers,
        boolean exhausted,
        Instant fetchedAt
) {

    public CachedSearch {
        Objects.requireNonNull(distributor, "distributor");
        Objects.requireNonNull(queryKey, "queryKey");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
        partNumbers = partNumbers == null ? List.of() : List.copyOf(partNumbers);
    }
}
