package ro.alacrity.kina.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.EnumSet;
import java.util.Set;

/**
 * One part search.
 *
 * @param query        free-text component description, e.g. "10uF X7R 0805"
 * @param maxResults   1..50 parts returned per distributor (default 10)
 * @param distributors distributors to query; empty means all configured
 * @param bypassCache  skip the cache lookup (the cache is still refreshed)
 */
public record SearchRequest(
        @JsonProperty("query") @NotBlank String query,
        @JsonProperty("max_results") @Min(1) @Max(MAX_MAX_RESULTS) int maxResults,
        @JsonProperty("distributors") Set<Distributor> distributors,
        @JsonProperty("bypass_cache") boolean bypassCache
) {

    public static final int DEFAULT_MAX_RESULTS = 10;
    public static final int MAX_MAX_RESULTS = 50;

    public SearchRequest {
        distributors = distributors == null || distributors.isEmpty()
                ? Set.of()
                : Set.copyOf(EnumSet.copyOf(distributors));
    }

    /** JSON/tool entry point: absent or null values take their defaults. */
    @JsonCreator
    public static SearchRequest of(
            @JsonProperty("query") String query,
            @JsonProperty("max_results") Integer maxResults,
            @JsonProperty("distributors") Set<Distributor> distributors,
            @JsonProperty("bypass_cache") Boolean bypassCache) {
        return new SearchRequest(
                query,
                maxResults == null ? DEFAULT_MAX_RESULTS : maxResults,
                distributors,
                bypassCache != null && bypassCache);
    }

    public static SearchRequest of(String query) {
        return new SearchRequest(query, DEFAULT_MAX_RESULTS, Set.of(), false);
    }

    /** Copy with the given distributors and cache flag (used to expand batch requests). */
    public SearchRequest with(Set<Distributor> newDistributors, boolean newBypassCache) {
        return new SearchRequest(query, maxResults, newDistributors, newBypassCache);
    }
}
