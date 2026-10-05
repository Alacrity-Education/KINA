package ro.alacrity.kina.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/** A price bracket as returned to clients: {@code {"qty":1,"unit_price":1.40,"currency":"EUR"}}. */
public record PriceResponse(
        @JsonProperty("qty") int qty,
        @JsonProperty("unit_price") BigDecimal unitPrice,
        @JsonProperty("currency") String currency
) {

    public static PriceResponse from(PriceBreak priceBreak) {
        return new PriceResponse(priceBreak.quantity(), priceBreak.unitPrice(), priceBreak.currency());
    }
}
