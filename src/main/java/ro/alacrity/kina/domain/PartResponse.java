package ro.alacrity.kina.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Builder;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * A part as returned to clients (DESIGN.md section 4). {@code rank} and {@code score} are null for
 * {@code get_part}. Prices are trimmed to the {@value #MAX_PRICE_BREAKS} smallest quantity brackets.
 */
@JsonPropertyOrder({"rank", "score", "distributor", "part_number", "manufacturer", "mpn", "description",
        "category", "package", "stock", "min_order_qty", "order_multiple", "prices", "datasheet_url",
        "photo_url", "product_url", "attributes", "extra"})
@Builder
public record PartResponse(
        @JsonProperty("rank") Integer rank,
        @JsonProperty("score") Double score,
        @JsonProperty("distributor") Distributor distributor,
        @JsonProperty("part_number") String partNumber,
        @JsonProperty("manufacturer") String manufacturer,
        @JsonProperty("mpn") String mpn,
        @JsonProperty("description") String description,
        @JsonProperty("category") String category,
        @JsonProperty("package") String packageName,
        @JsonProperty("stock") int stock,
        @JsonProperty("min_order_qty") Integer minOrderQty,
        @JsonProperty("order_multiple") Integer orderMultiple,
        @JsonProperty("prices") List<PriceResponse> prices,
        @JsonProperty("datasheet_url") String datasheetUrl,
        @JsonProperty("photo_url") String photoUrl,
        @JsonProperty("product_url") String productUrl,
        @JsonProperty("attributes") Map<String, String> attributes,
        @JsonProperty("extra") Map<String, Object> extra
) {

    public static final int MAX_PRICE_BREAKS = 3;

    /** Ranked search result entry. */
    public static PartResponse from(Part part, Integer rank, Double score) {
        return builder()
                .rank(rank)
                .score(score)
                .distributor(part.distributor())
                .partNumber(part.distributorPartNumber())
                .manufacturer(part.manufacturer())
                .mpn(part.manufacturerPartNumber())
                .description(part.description())
                .category(part.category())
                .packageName(part.packageName())
                .stock(part.stock())
                .minOrderQty(part.minimumOrderQuantity())
                .orderMultiple(part.orderMultiple())
                .prices(trimPrices(part.prices()))
                .datasheetUrl(part.datasheetUrl())
                .photoUrl(part.photoUrl())
                .productUrl(part.productUrl())
                .attributes(part.attributes())
                .extra(part.extra())
                .build();
    }

    /** Unranked entry (get_part). */
    public static PartResponse from(Part part) {
        return from(part, null, null);
    }

    /** The {@value #MAX_PRICE_BREAKS} smallest quantity brackets, ascending by quantity. */
    public static List<PriceResponse> trimPrices(List<PriceBreak> prices) {
        return prices.stream()
                .sorted(Comparator.comparingInt(PriceBreak::quantity))
                .limit(MAX_PRICE_BREAKS)
                .map(PriceResponse::from)
                .toList();
    }
}
