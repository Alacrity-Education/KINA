package ro.alacrity.kina.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Result of one search (DESIGN.md section 4). Serialised snake_case; {@code ranking_note} is emitted even when null. */
public record SearchResponse(
        @JsonProperty("query") String query,
        @JsonProperty("parsed") ParsedQueryResponse parsed,
        @JsonProperty("ranking") RankingMode ranking,
        @JsonProperty("ranking_note") String rankingNote,
        @JsonProperty("distributors") List<DistributorResult> distributors
) {

    public SearchResponse {
        distributors = distributors == null ? List.of() : List.copyOf(distributors);
    }
}
