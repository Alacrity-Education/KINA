package ro.alacrity.kina.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import ro.alacrity.kina.cache.CacheStatus;

import java.util.List;

/**
 * Per-distributor section of a {@link SearchResponse}.
 *
 * @param totalResults what the distributor reported for the query (null when unknown / on error)
 * @param fetched      how many in-stock parts KINA holds for the query
 * @param returned     {@code min(max_results, fetched)}
 * @param error        null, or "rate_limited", "unavailable", "not_configured", "timeout", "bad_response"
 */
public record DistributorResult(
        @JsonProperty("distributor") Distributor distributor,
        @JsonProperty("total_results") Integer totalResults,
        @JsonProperty("fetched") int fetched,
        @JsonProperty("returned") int returned,
        @JsonProperty("cache") CacheStatus cache,
        @JsonProperty("error") String error,
        @JsonProperty("parts") List<PartResponse> parts
) {

    public DistributorResult {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }

    /** An entry for a distributor that failed; carries an empty part list. */
    public static DistributorResult failed(Distributor distributor, String error, CacheStatus cache) {
        return new DistributorResult(distributor, null, 0, 0, cache, error, List.of());
    }
}
