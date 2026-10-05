package ro.alacrity.kina.cache;

import ro.alacrity.kina.domain.Distributor;

import java.time.Instant;
import java.util.Map;

/**
 * Snapshot of the Postgres component cache, e.g. for {@code list_distributors}.
 *
 * @param parts              rows in {@code cached_parts}
 * @param freshParts         rows in {@code cached_parts} fetched within {@code kina.cache.ttl}
 * @param searches           rows in {@code cached_searches}
 * @param partsByDistributor {@code cached_parts} rows per distributor; every distributor is present (0 when none)
 * @param oldestFetch        oldest {@code cached_parts.fetched_at}, null when the table is empty
 */
public record CacheStatistics(
        long parts,
        long freshParts,
        long searches,
        Map<Distributor, Long> partsByDistributor,
        Instant oldestFetch
) {

    public CacheStatistics {
        partsByDistributor = partsByDistributor == null ? Map.of() : Map.copyOf(partsByDistributor);
    }
}
