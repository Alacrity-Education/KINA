package ro.alacrity.kina.distributor.mouser;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** One Mouser part as returned by the search endpoints (fields observed on the live API, 2026-10-05). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MouserPart(
        @JsonProperty("Availability") String availability,
        @JsonProperty("AvailabilityInStock") String availabilityInStock,
        @JsonProperty("AvailabilityOnOrder") List<MouserOnOrder> availabilityOnOrder,
        @JsonProperty("FactoryStock") String factoryStock,
        @JsonProperty("DataSheetUrl") String dataSheetUrl,
        @JsonProperty("Description") String description,
        @JsonProperty("ImagePath") String imagePath,
        @JsonProperty("Category") String category,
        @JsonProperty("LeadTime") String leadTime,
        @JsonProperty("LifecycleStatus") String lifecycleStatus,
        @JsonProperty("Manufacturer") String manufacturer,
        @JsonProperty("ManufacturerPartNumber") String manufacturerPartNumber,
        @JsonProperty("Min") String min,
        @JsonProperty("Mult") String mult,
        @JsonProperty("MouserPartNumber") String mouserPartNumber,
        @JsonProperty("ProductAttributes") List<MouserAttribute> productAttributes,
        @JsonProperty("PriceBreaks") List<MouserPriceBreak> priceBreaks,
        @JsonProperty("ProductDetailUrl") String productDetailUrl,
        @JsonProperty("Reeling") Boolean reeling,
        @JsonProperty("ROHSStatus") String rohsStatus,
        @JsonProperty("SuggestedReplacement") String suggestedReplacement,
        @JsonProperty("SalesMaximumOrderQty") String salesMaximumOrderQty) {

    public MouserPart {
        availabilityOnOrder = availabilityOnOrder == null ? List.of() : List.copyOf(availabilityOnOrder);
        productAttributes = productAttributes == null ? List.of() : List.copyOf(productAttributes);
        priceBreaks = priceBreaks == null ? List.of() : List.copyOf(priceBreaks);
    }
}
