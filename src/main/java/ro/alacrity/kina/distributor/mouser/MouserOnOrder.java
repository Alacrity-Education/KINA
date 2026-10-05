package ro.alacrity.kina.distributor.mouser;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** One {@code AvailabilityOnOrder} entry (expected delivery); never counted as stock. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MouserOnOrder(
        @JsonProperty("Quantity") Long quantity,
        @JsonProperty("Date") String date) {
}
