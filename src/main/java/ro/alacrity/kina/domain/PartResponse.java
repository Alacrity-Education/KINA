package ro.alacrity.kina.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Builder;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * A part as returned to clients (DESIGN.md section 4). {@code rank}, {@code score} and {@code match} are null for
 * {@code get_part}. {@code score} orders the list (rank-normalised, relative to the other candidates); {@code match}
 * is the absolute deterministic match grade in [0,1] (1.0 = every stated parameter is known and matches), rounded to
 * 2 decimals. Prices are trimmed to the {@value #MAX_PRICE_BREAKS} smallest quantity brackets.
 */
@JsonPropertyOrder({"rank", "score", "match", "distributor", "part_number", "manufacturer", "mpn", "description",
        "category", "package", "stock", "min_order_qty", "order_multiple", "prices", "datasheet_url",
        "photo_url", "product_url", "attributes", "extra"})
@Builder
public record PartResponse(
        @JsonProperty("rank") Integer rank,
        @JsonProperty("score") Double score,
        @JsonProperty("match") Double match,
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

    /** Ranked search result entry without a match grade. */
    public static PartResponse from(Part part, Integer rank, Double score) {
        return from(part, rank, score, null);
    }

    /** Ranked search result entry; {@code match} is rounded to 2 decimals. */
    public static PartResponse from(Part part, Integer rank, Double score, Double match) {
        return builder()
                .rank(rank)
                .score(score)
                .match(match == null ? null : Math.round(match * 100) / 100.0)
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
