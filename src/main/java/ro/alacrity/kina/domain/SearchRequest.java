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
 * @param quantity     pieces to order (default 1): ranks parts with less stock or a larger minimum order quantity
 *                     lower and prices the order ({@code total_price}, DESIGN.md 3.4 "Quantity")
 * @param detail       {@code compact} (default) or {@code full} part details (DESIGN.md 4)
 * @param allowBelowSpec false (default): a part whose known rating is below the request is left out
 *                     ({@code excluded_below_spec}); true: such parts are returned after every part that meets the
 *                     request, flagged {@code below_spec} and ordered by how close they are (DESIGN.md 3.4)
 */
public record SearchRequest(
        @JsonProperty("query") @NotBlank String query,
        @JsonProperty("max_results") @Min(1) @Max(MAX_MAX_RESULTS) int maxResults,
        @JsonProperty("distributors") Set<Distributor> distributors,
        @JsonProperty("bypass_cache") boolean bypassCache,
        @JsonProperty("quantity") @Min(1) @Max(MAX_QUANTITY) int quantity,
        @JsonProperty("detail") ResponseDetail detail,
        @JsonProperty("allow_below_spec") boolean allowBelowSpec
) {

    public static final int DEFAULT_MAX_RESULTS = 10;
    public static final int MAX_MAX_RESULTS = 50;
    public static final int MAX_QUANTITY = 10_000_000;

    public SearchRequest {
        distributors = distributors == null || distributors.isEmpty()
                ? Set.of()
                : Set.copyOf(EnumSet.copyOf(distributors));
        detail = detail == null ? ResponseDetail.COMPACT : detail;
    }

    /** A request for one piece with compact details. */
    public SearchRequest(String query, int maxResults, Set<Distributor> distributors, boolean bypassCache) {
        this(query, maxResults, distributors, bypassCache, 1, ResponseDetail.COMPACT);
    }

    /** Below-spec parts excluded (the default). */
    public SearchRequest(String query, int maxResults, Set<Distributor> distributors, boolean bypassCache,
                         int quantity, ResponseDetail detail) {
        this(query, maxResults, distributors, bypassCache, quantity, detail, false);
    }

    /** JSON/tool entry point: absent or null values take their defaults. */
    @JsonCreator
    public static SearchRequest of(
            @JsonProperty("query") String query,
            @JsonProperty("max_results") Integer maxResults,
            @JsonProperty("distributors") Set<Distributor> distributors,
            @JsonProperty("bypass_cache") Boolean bypassCache,
            @JsonProperty("quantity") Integer quantity,
            @JsonProperty("detail") ResponseDetail detail,
            @JsonProperty("allow_below_spec") Boolean allowBelowSpec) {
        return new SearchRequest(
                query,
                maxResults == null ? DEFAULT_MAX_RESULTS : maxResults,
                distributors,
                bypassCache != null && bypassCache,
                quantity == null ? 1 : quantity,
                detail,
                allowBelowSpec != null && allowBelowSpec);
    }

    /** Without {@code allow_below_spec} (false). */
    public static SearchRequest of(String query, Integer maxResults, Set<Distributor> distributors,
                                   Boolean bypassCache, Integer quantity, ResponseDetail detail) {
        return of(query, maxResults, distributors, bypassCache, quantity, detail, null);
    }

    /** Without quantity and detail (one piece, compact). */
    public static SearchRequest of(String query, Integer maxResults, Set<Distributor> distributors,
                                   Boolean bypassCache) {
        return of(query, maxResults, distributors, bypassCache, null, null, null);
    }

    public static SearchRequest of(String query) {
        return new SearchRequest(query, DEFAULT_MAX_RESULTS, Set.of(), false);
    }

    /** Copy with the given distributors, cache flag and detail (used to expand batch requests). */
    public SearchRequest with(Set<Distributor> newDistributors, boolean newBypassCache, ResponseDetail newDetail) {
        return with(newDistributors, newBypassCache, newDetail, allowBelowSpec);
    }

    /** Copy with the given distributors, cache flag, detail and below-spec choice. */
    public SearchRequest with(Set<Distributor> newDistributors, boolean newBypassCache, ResponseDetail newDetail,
                              boolean newAllowBelowSpec) {
        return new SearchRequest(query, maxResults, newDistributors, newBypassCache, quantity, newDetail,
                newAllowBelowSpec);
    }

    /** Copy with the given distributors and cache flag. */
    public SearchRequest with(Set<Distributor> newDistributors, boolean newBypassCache) {
        return with(newDistributors, newBypassCache, detail);
    }
}
