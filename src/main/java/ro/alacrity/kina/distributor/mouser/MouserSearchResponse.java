package ro.alacrity.kina.distributor.mouser;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Body of {@code /search/keyword} and {@code /search/partnumber}. {@code SearchResults} is null on errors. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MouserSearchResponse(
        @JsonProperty("Errors") List<MouserError> errors,
        @JsonProperty("SearchResults") SearchResults searchResults) {

    public MouserSearchResponse {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchResults(
            @JsonProperty("NumberOfResult") Integer numberOfResult,
            @JsonProperty("Parts") List<MouserPart> parts) {

        public SearchResults {
            parts = parts == null ? List.of() : List.copyOf(parts);
        }
    }
}
