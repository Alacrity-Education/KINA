package ro.alacrity.kina.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** {@code {"results": [SearchResponse...]}}, in request order. */
public record BatchSearchResponse(@JsonProperty("results") List<SearchResponse> results) {

    public BatchSearchResponse {
        results = results == null ? List.of() : List.copyOf(results);
    }
}
