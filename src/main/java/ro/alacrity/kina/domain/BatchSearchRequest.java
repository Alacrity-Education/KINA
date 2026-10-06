package ro.alacrity.kina.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Several searches sharing distributor selection, cache behaviour, response detail and the below-spec choice. Only
 * {@code query}, {@code max_results}, {@code quantity} and {@code allow_below_spec} of each entry are meaningful (a
 * query allows below-spec parts when it or the batch says so); use {@link #expanded()} to get the effective requests.
 */
public record BatchSearchRequest(
        @JsonProperty("queries") @NotEmpty @Size(max = MAX_QUERIES) List<@Valid SearchRequest> queries,
        @JsonProperty("distributors") Set<Distributor> distributors,
        @JsonProperty("bypass_cache") boolean bypassCache,
        @JsonProperty("detail") ResponseDetail detail,
        @JsonProperty("allow_below_spec") boolean allowBelowSpec
) {

    public static final int MAX_QUERIES = 20;

    public BatchSearchRequest {
        queries = queries == null ? List.of() : List.copyOf(queries);
        distributors = distributors == null || distributors.isEmpty()
                ? Set.of()
                : Set.copyOf(EnumSet.copyOf(distributors));
        detail = detail == null ? ResponseDetail.COMPACT : detail;
    }

    public BatchSearchRequest(List<SearchRequest> queries, Set<Distributor> distributors, boolean bypassCache) {
        this(queries, distributors, bypassCache, ResponseDetail.COMPACT);
    }

    public BatchSearchRequest(List<SearchRequest> queries, Set<Distributor> distributors, boolean bypassCache,
                              ResponseDetail detail) {
        this(queries, distributors, bypassCache, detail, false);
    }

    @JsonCreator
    public static BatchSearchRequest of(
            @JsonProperty("queries") List<SearchRequest> queries,
            @JsonProperty("distributors") Set<Distributor> distributors,
            @JsonProperty("bypass_cache") Boolean bypassCache,
            @JsonProperty("detail") ResponseDetail detail,
            @JsonProperty("allow_below_spec") Boolean allowBelowSpec) {
        return new BatchSearchRequest(queries, distributors, bypassCache != null && bypassCache, detail,
                allowBelowSpec != null && allowBelowSpec);
    }

    public static BatchSearchRequest of(List<SearchRequest> queries, Set<Distributor> distributors,
                                        Boolean bypassCache, ResponseDetail detail) {
        return of(queries, distributors, bypassCache, detail, null);
    }

    public static BatchSearchRequest of(List<SearchRequest> queries, Set<Distributor> distributors,
                                        Boolean bypassCache) {
        return of(queries, distributors, bypassCache, null, null);
    }

    /**
     * Each query with the batch-level distributors, bypass flag and detail applied (each keeps its quantity; below-spec
     * parts are allowed when the query or the batch allows them).
     */
    public List<SearchRequest> expanded() {
        return queries.stream()
                .map(q -> q.with(distributors, bypassCache, detail, allowBelowSpec || q.allowBelowSpec()))
                .toList();
    }
}
