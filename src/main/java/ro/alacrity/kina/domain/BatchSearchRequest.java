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
 * Several searches sharing distributor selection and cache behaviour. Only {@code query} and
 * {@code max_results} of each entry are meaningful; use {@link #expanded()} to get the effective requests.
 */
public record BatchSearchRequest(
        @JsonProperty("queries") @NotEmpty @Size(max = MAX_QUERIES) List<@Valid SearchRequest> queries,
        @JsonProperty("distributors") Set<Distributor> distributors,
        @JsonProperty("bypass_cache") boolean bypassCache
) {

    public static final int MAX_QUERIES = 20;

    public BatchSearchRequest {
        queries = queries == null ? List.of() : List.copyOf(queries);
        distributors = distributors == null || distributors.isEmpty()
                ? Set.of()
                : Set.copyOf(EnumSet.copyOf(distributors));
    }

    @JsonCreator
    public static BatchSearchRequest of(
            @JsonProperty("queries") List<SearchRequest> queries,
            @JsonProperty("distributors") Set<Distributor> distributors,
            @JsonProperty("bypass_cache") Boolean bypassCache) {
        return new BatchSearchRequest(queries, distributors, bypassCache != null && bypassCache);
    }

    /** Each query with the batch-level distributors and bypass flag applied. */
    public List<SearchRequest> expanded() {
        return queries.stream().map(q -> q.with(distributors, bypassCache)).toList();
    }
}
