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
 * @param fallbackQuery null, or the shorter parametric core phrase that was sent to the distributor because the full
 *                      query found nothing (e.g. "MOSFET 30V SOT-23" for "SOT-23 N-channel MOSFET 30V")
 */
public record DistributorResult(
        @JsonProperty("distributor") Distributor distributor,
        @JsonProperty("total_results") Integer totalResults,
        @JsonProperty("fetched") int fetched,
        @JsonProperty("returned") int returned,
        @JsonProperty("cache") CacheStatus cache,
        @JsonProperty("error") String error,
        @JsonProperty("parts") List<PartResponse> parts,
        @JsonProperty("fallback_query") String fallbackQuery
) {

    public DistributorResult {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }

    /** An entry for a search of the query itself (no phrase fallback). */
    public DistributorResult(Distributor distributor, Integer totalResults, int fetched, int returned,
                             CacheStatus cache, String error, List<PartResponse> parts) {
        this(distributor, totalResults, fetched, returned, cache, error, parts, null);
    }

    /** An entry for a distributor that failed; carries an empty part list. */
    public static DistributorResult failed(Distributor distributor, String error, CacheStatus cache) {
        return new DistributorResult(distributor, null, 0, 0, cache, error, List.of());
    }
}
