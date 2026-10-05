package ro.alacrity.kina.distributor.mouser;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One entry of the Mouser {@code Errors} list, e.g.
 * {@code {"Id":0,"Code":"Invalid","Message":"Invalid unique identifier.","PropertyName":"API Key"}}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MouserError(
        @JsonProperty("Id") Integer id,
        @JsonProperty("Code") String code,
        @JsonProperty("Message") String message,
        @JsonProperty("ResourceKey") String resourceKey,
        @JsonProperty("PropertyName") String propertyName) {
}
