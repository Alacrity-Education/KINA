package ro.alacrity.kina.distributor.mouser;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** One {@code ProductAttributes} entry; names may repeat (e.g. several "Packaging" values). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MouserAttribute(
        @JsonProperty("AttributeName") String attributeName,
        @JsonProperty("AttributeValue") String attributeValue,
        @JsonProperty("AttributeCost") String attributeCost) {
}
