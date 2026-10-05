package ro.alacrity.kina.distributor.mouser;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** One {@code PriceBreaks} entry; {@code Price} is locale formatted, e.g. {@code "1,40 €"} or {@code "$0.10"}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MouserPriceBreak(
        @JsonProperty("Quantity") Integer quantity,
        @JsonProperty("Price") String price,
        @JsonProperty("Currency") String currency) {
}
