package ro.alacrity.kina.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Builder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * A part as returned to clients (DESIGN.md section 4). {@code rank}, {@code score} and {@code match} are null for
 * {@code get_part}. {@code score} orders the list (rank-normalised, relative to the other candidates); {@code match}
 * is the absolute deterministic match grade in [0,1] (1.0 = every stated parameter is known and matches), rounded to
 * 2 decimals. Prices are trimmed to the {@value #MAX_PRICE_BREAKS} smallest quantity brackets.
 *
 * <p>Detail ({@link ResponseDetail}): {@code compact} carries the identity ({@code distributor}, {@code part_number},
 * {@code manufacturer}, {@code manufacturer_id} when the distributor has one, {@code mpn}), {@code description},
 * {@code stock}, the order rules, {@code prices}, the order pricing when the quantity is above 1,
 * {@code availability}, the links and only the canonical attributes; {@code full} adds {@code category},
 * {@code package}, {@code photo_url}, every distributor attribute and {@code extra}, and always the order pricing.
 * Fields a detail level leaves out are omitted from the JSON.
 *
 * @param orderedQuantity     pieces actually ordered for the requested quantity: raised to the minimum order quantity
 *                            (or the smallest price bracket) and rounded up to the order multiple
 * @param unitPriceAtQuantity unit price of the price bracket that applies to {@code orderedQuantity}
 * @param totalPrice          {@code unitPriceAtQuantity * orderedQuantity}, in the price currency
 * @param lifecycle           {@code active}, {@code last_time_buy}, {@code supply_constrained} or {@code new}
 *                            ({@link Availability#lifecycleOf})
 * @param mismatches          stated parameters the part is known not to satisfy, e.g. {@code "dielectric: X5R instead
 *                            of X7R"}; omitted when empty (search results only)
 */
@JsonPropertyOrder({"rank", "score", "match", "distributor", "part_number", "manufacturer", "manufacturer_id", "mpn",
        "description", "category", "package", "stock", "min_order_qty", "order_multiple", "prices",
        "ordered_quantity", "unit_price_at_quantity", "total_price", "availability", "lifecycle", "mismatches",
        "datasheet_url", "photo_url",
        "product_url", "attributes", "extra"})
@Builder
public record PartResponse(
        @JsonProperty("rank") Integer rank,
        @JsonProperty("score") Double score,
        @JsonProperty("match") Double match,
        @JsonProperty("distributor") Distributor distributor,
        @JsonProperty("part_number") String partNumber,
        @JsonProperty("manufacturer") String manufacturer,
        @JsonProperty("manufacturer_id") @JsonInclude(JsonInclude.Include.NON_NULL) String manufacturerId,
        @JsonProperty("mpn") String mpn,
        @JsonProperty("description") String description,
        @JsonProperty("category") @JsonInclude(JsonInclude.Include.NON_NULL) String category,
        @JsonProperty("package") @JsonInclude(JsonInclude.Include.NON_NULL) String packageName,
        @JsonProperty("stock") int stock,
        @JsonProperty("min_order_qty") Integer minOrderQty,
        @JsonProperty("order_multiple") Integer orderMultiple,
        @JsonProperty("prices") List<PriceResponse> prices,
        @JsonProperty("ordered_quantity") @JsonInclude(JsonInclude.Include.NON_NULL) Integer orderedQuantity,
        @JsonProperty("unit_price_at_quantity") @JsonInclude(JsonInclude.Include.NON_NULL)
        BigDecimal unitPriceAtQuantity,
        @JsonProperty("total_price") @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal totalPrice,
        @JsonProperty("availability") Availability availability,
        @JsonProperty("lifecycle") String lifecycle,
        @JsonProperty("mismatches") @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> mismatches,
        @JsonProperty("datasheet_url") String datasheetUrl,
        @JsonProperty("photo_url") @JsonInclude(JsonInclude.Include.NON_NULL) String photoUrl,
        @JsonProperty("product_url") String productUrl,
        @JsonProperty("attributes") Map<String, String> attributes,
        @JsonProperty("extra") @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> extra
) {

    public static final int MAX_PRICE_BREAKS = 3;

    /** Ranked search result entry without a match grade ({@code full} detail, one piece). */
    public static PartResponse from(Part part, Integer rank, Double score) {
        return from(part, rank, score, null);
    }

    /** Ranked search result entry; {@code match} is rounded to 2 decimals ({@code full} detail, one piece). */
    public static PartResponse from(Part part, Integer rank, Double score, Double match) {
        return of(part, rank, score, match, 1, ResponseDetail.FULL, null);
    }

    /** Unranked entry (get_part), {@code full} detail. */
    public static PartResponse from(Part part) {
        return from(part, null, null);
    }

    /**
     * A part at the given detail level for an order of {@code quantity} pieces.
     *
     * @param canonical the canonical attributes ({@code ParametricExtractor.extract}) for {@code compact}; null uses
     *                  the part's attributes
     */
    public static PartResponse of(Part part, Integer rank, Double score, Double match, int quantity,
                                  ResponseDetail detail, Map<String, String> canonical) {
        return of(part, rank, score, match, List.of(), quantity, detail, canonical);
    }

    /** As {@link #of(Part, Integer, Double, Double, int, ResponseDetail, Map)} with the part's mismatches. */
    public static PartResponse of(Part part, Integer rank, Double score, Double match, List<String> mismatches,
                                  int quantity, ResponseDetail detail, Map<String, String> canonical) {
        boolean full = detail == ResponseDetail.FULL;
        int qty = Math.max(1, quantity);
        Order order = full || qty > 1 ? order(part, qty) : null;
        Object manufacturerId = part.extra().get("manufacturer_id");
        return builder()
                .rank(rank)
                .score(score)
                .match(match == null ? null : Math.round(match * 100) / 100.0)
                .distributor(part.distributor())
                .partNumber(part.distributorPartNumber())
                .manufacturer(part.manufacturer())
                .manufacturerId(manufacturerId == null ? null : manufacturerId.toString())
                .mpn(part.manufacturerPartNumber())
                .description(part.description())
                .category(full ? part.category() : null)
                .packageName(full ? part.packageName() : null)
                .stock(part.stock())
                .minOrderQty(part.minimumOrderQuantity())
                .orderMultiple(part.orderMultiple())
                .prices(trimPrices(part.prices()))
                .orderedQuantity(order == null ? null : order.quantity())
                .unitPriceAtQuantity(order == null ? null : order.unitPrice())
                .totalPrice(order == null ? null : order.total())
                .availability(Availability.of(part, qty, full))
                .lifecycle(Availability.lifecycleOf(part))
                .mismatches(mismatches == null ? List.of() : List.copyOf(mismatches))
                .datasheetUrl(part.datasheetUrl())
                .photoUrl(full ? part.photoUrl() : null)
                .productUrl(part.productUrl())
                .attributes(full || canonical == null ? part.attributes() : canonical)
                .extra(full ? part.extra() : null)
                .build();
    }

    /** The {@value #MAX_PRICE_BREAKS} smallest quantity brackets, ascending by quantity. */
    public static List<PriceResponse> trimPrices(List<PriceBreak> prices) {
        return prices.stream()
                .sorted(Comparator.comparingInt(PriceBreak::quantity))
                .limit(MAX_PRICE_BREAKS)
                .map(PriceResponse::from)
                .toList();
    }

    /** What an order of a quantity costs: pieces ordered, unit price of the applicable bracket, total. */
    public record Order(int quantity, BigDecimal unitPrice, BigDecimal total) {
    }

    /**
     * Prices an order of {@code quantity} pieces: the quantity is raised to the minimum order quantity (and to the
     * smallest price bracket, which is where the distributor's price list starts), rounded up to the order multiple,
     * and priced at the largest bracket not above it (all brackets, not only the three returned). The unit price and
     * the total are null when the part has no prices.
     */
    public static Order order(Part part, int quantity) {
        long ordered = Math.max(1, quantity);
        if (part.minimumOrderQuantity() != null) {
            ordered = Math.max(ordered, part.minimumOrderQuantity());
        }
        List<PriceBreak> breaks = part.prices().stream().sorted(Comparator.comparingInt(PriceBreak::quantity))
                .toList();
        if (!breaks.isEmpty()) {
            ordered = Math.max(ordered, breaks.getFirst().quantity());
        }
        Integer multiple = part.orderMultiple();
        if (multiple != null && multiple > 1 && ordered % multiple != 0) {
            ordered = (ordered / multiple + 1) * multiple;
        }
        int pieces = (int) Math.min(ordered, Integer.MAX_VALUE);
        PriceBreak applicable = null;
        for (PriceBreak b : breaks) {
            if (b.quantity() <= pieces) {
                applicable = b;
            }
        }
        if (applicable == null || applicable.unitPrice() == null) {
            return new Order(pieces, null, null);
        }
        BigDecimal total = applicable.unitPrice().multiply(BigDecimal.valueOf(pieces));
        int scale = Math.clamp(total.stripTrailingZeros().scale(), 2, 4);
        return new Order(pieces, applicable.unitPrice(), total.setScale(scale, RoundingMode.HALF_UP));
    }
}
