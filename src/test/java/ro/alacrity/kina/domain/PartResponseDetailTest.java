package ro.alacrity.kina.domain;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Response detail levels, order pricing and availability (DESIGN.md 4). */
class PartResponseDetailTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private static Part part(Distributor d, int stock, Integer moq, Integer multiple, Map<String, Object> extra,
                             PriceBreak... prices) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("Operating voltage", "50V DC");
        attributes.put("Case - inch", "0603");
        attributes.put("Voltage", "50V");
        return new Part(d, "PN-1", "SAMSUNG", "CL10B104KB8NNNC", "MLCC 100nF 50V X7R 0603", "MLCC", "0603", stock,
                moq, multiple, List.of(prices), "https://ds", "https://img", "https://prod", attributes, extra,
                Instant.parse("2026-10-05T00:00:00Z"));
    }

    @Test
    void orderIsRaisedToTheMinimumAndTheMultipleAndPricedAtItsBracket() {
        Part p = part(Distributor.TME, 100_000, 100, 50, Map.of(),
                new PriceBreak(100, new BigDecimal("0.0300"), "EUR"), new PriceBreak(1000, new BigDecimal("0.0100"), "EUR"));
        PartResponse.Order order = PartResponse.order(p, 25);
        assertThat(order.quantity()).isEqualTo(100);
        assertThat(order.unitPrice()).isEqualByComparingTo("0.03");
        assertThat(order.total()).isEqualByComparingTo("3.00");
        PartResponse.Order rounded = PartResponse.order(p, 1020);
        assertThat(rounded.quantity()).isEqualTo(1050);
        assertThat(rounded.total()).isEqualByComparingTo("10.50");
        // LCSC: no minimum known, brackets start at 1
        Part lcsc = part(Distributor.LCSC, 5000, null, null, Map.of(), new PriceBreak(1, new BigDecimal("0.0068"), "USD"),
                new PriceBreak(200, new BigDecimal("0.0056"), "USD"));
        assertThat(PartResponse.order(lcsc, 25)).isEqualTo(new PartResponse.Order(25, new BigDecimal("0.0068"),
                new BigDecimal("0.17")));
        assertThat(PartResponse.order(part(Distributor.MOUSER, 10, 1, 1, Map.of()), 5).total()).isNull();
    }

    @Test
    void compactKeepsIdentityAndCanonicalAttributesFullKeepsEverything() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("product_status", List.of("HARDLY_AVAILABLE"));
        extra.put("manufacturer_id", 451);
        extra.put("packing", List.of(Map.of("id", "RL1")));
        Part p = part(Distributor.TME, 4000, 10, 10, extra, new PriceBreak(10, new BigDecimal("0.02"), "EUR"));
        Map<String, String> canonical = Map.of("Voltage", "50V", "Package", "0603");

        String compact = mapper.writeValueAsString(PartResponse.of(p, 1, 0.9, 1.0, 1, ResponseDetail.COMPACT,
                canonical));
        assertThat(compact).contains("\"part_number\":\"PN-1\"", "\"manufacturer\":\"SAMSUNG\"",
                "\"manufacturer_id\":\"451\"", "\"mpn\":\"CL10B104KB8NNNC\"", "\"description\":", "\"stock\":4000",
                "\"min_order_qty\":10", "\"order_multiple\":10", "\"prices\":", "\"datasheet_url\":\"https://ds\"",
                "\"product_url\":\"https://prod\"", "\"availability\":{\"status\":\"in_stock\"",
                "\"lifecycle\":\"supply_constrained\"", "\"stock_as_of\":\"2026-10-05T00:00:00Z\"",
                "\"Voltage\":\"50V\"", "\"match\":1.0", "\"score\":0.9");
        assertThat(compact).doesNotContain("photo_url", "\"extra\"", "Operating voltage", "Case - inch", "packing",
                "product_status", "\"category\"", "\"package\"", "total_price", "ordered_quantity");

        String compact25 = mapper.writeValueAsString(PartResponse.of(p, 1, 0.9, 1.0, 25, ResponseDetail.COMPACT,
                canonical));
        assertThat(compact25).contains("\"ordered_quantity\":30", "\"unit_price_at_quantity\":0.02",
                "\"total_price\":0.60");

        String full = mapper.writeValueAsString(PartResponse.of(p, 1, 0.9, 1.0, 1, ResponseDetail.FULL, canonical));
        assertThat(full).contains("\"photo_url\":\"https://img\"", "\"extra\":", "\"product_status\"",
                "\"Operating voltage\"", "\"category\":\"MLCC\"", "\"package\":\"0603\"", "\"total_price\":0.20",
                "\"ordered_quantity\":10");
    }

    @Test
    void availabilityFromStockAndDistributorFlags() {
        assertThat(Availability.of(part(Distributor.MOUSER, 2, 1, 1, Map.of()), 10, false))
                .isEqualTo(new Availability(Availability.LIMITED, "Only 2 ship now, fewer than the 10 requested."));
        assertThat(Availability.of(part(Distributor.MOUSER, 20, 1, 1, Map.of()), 10, false).status())
                .isEqualTo(Availability.IN_STOCK);
        // low stock: below the threshold (default 10), or below twice the quantity
        assertThat(Availability.of(part(Distributor.TME, 2, 1, 1, Map.of()), 1, false))
                .isEqualTo(new Availability(Availability.LOW_STOCK, "Only 2 in stock."));
        assertThat(Availability.of(part(Distributor.TME, 15, 1, 1, Map.of()), 10, false))
                .isEqualTo(new Availability(Availability.LOW_STOCK, "Only 15 in stock, less than twice the 10 requested."));
        assertThat(Availability.of(part(Distributor.TME, 15, 1, 1, Map.of()), 1, false, 20).status())
                .isEqualTo(Availability.LOW_STOCK);
        assertThat(Availability.of(part(Distributor.TME, 10, 1, 1, Map.of()), 1, false).status())
                .isEqualTo(Availability.IN_STOCK);
        Part lastBuy = part(Distributor.TME, 20, 1, 1,
                Map.of("product_status", List.of("AVAILABLE_WHILE_STOCKS_LAST", "MOQ_VALID_WHILE_STOCKS_LAST", "NEW")));
        assertThat(Availability.of(lastBuy, 1, false)).satisfies(a -> {
            assertThat(a.status()).isEqualTo(Availability.LAST_UNITS);
            assertThat(a.note()).contains("while stocks last", "minimum order quantity may change")
                    .doesNotContain("recently");
        });
        assertThat(Availability.lifecycleOf(lastBuy)).isEqualTo(Availability.LAST_TIME_BUY);
        // HARDLY_AVAILABLE is a supply flag, not low stock: lifecycle only, the availability stays the stock
        Part hardly = part(Distributor.TME, 150_000, 1, 1, Map.of("product_status", List.of("HARDLY_AVAILABLE")));
        assertThat(Availability.of(hardly, 1, false).status()).isEqualTo(Availability.IN_STOCK);
        assertThat(Availability.of(hardly, 1, false).note()).contains("Ships now from stock.", "limited market");
        assertThat(Availability.lifecycleOf(hardly)).isEqualTo(Availability.SUPPLY_CONSTRAINED);
        assertThat(Availability.lifecycleOf(part(Distributor.TME, 20, 1, 1, Map.of("product_status", List.of("NEW")))))
                .isEqualTo(Availability.NEW);
        assertThat(Availability.lifecycleOf(part(Distributor.LCSC, 20, 1, 1, Map.of()))).isEqualTo(Availability.ACTIVE);
        assertThat(Availability.of(part(Distributor.TME, 20, 1, 1, Map.of("product_status", List.of("DANGEROUS"))),
                1, false).note()).contains("shipping restrictions");
        assertThat(Availability.of(part(Distributor.TME, 20, 1, 1, Map.of("product_status", List.of("NEW"))), 1, true)
                .note()).contains("Ships now from stock.", "recently");
        assertThat(Availability.of(part(Distributor.TME, 20, 1, 1,
                Map.of("product_status", List.of("ONLY_FOR_SPECIAL_ORDER"))), 1, false).status())
                .isEqualTo(Availability.SPECIAL_ORDER);
        assertThat(Availability.of(part(Distributor.TME, 20, 1, 1,
                Map.of("product_status", List.of("EXTERNAL_WAREHOUSE"))), 1, false).status())
                .isEqualTo(Availability.EXTERNAL_WAREHOUSE);
        Map<String, Object> mouser = new LinkedHashMap<>();
        mouser.put("lifecycle_status", "Not Recommended for New Designs");
        mouser.put("sales_maximum_order_qty", 500);
        mouser.put("reeling", true);
        Availability eol = Availability.of(part(Distributor.MOUSER, 9000, 1, 1, mouser), 1000, false);
        assertThat(eol.status()).isEqualTo(Availability.LAST_UNITS);
        assertThat(eol.note()).contains("Not Recommended for New Designs", "at most 500 per order")
                .doesNotContain("MouseReel");
        assertThat(Availability.lifecycleOf(part(Distributor.MOUSER, 9000, 1, 1, mouser)))
                .isEqualTo(Availability.LAST_TIME_BUY);
        assertThat(Availability.of(part(Distributor.LCSC, 9000, null, null, Map.of("library_type", "Basic")), 1, true)
                .note()).contains("JLCPCB assembly library: Basic.");
        assertThat(Availability.of(part(Distributor.LCSC, 9000, null, null, Map.of("library_type", "Basic")), 1, false)
                .note()).isEqualTo("Ships now from stock.");
    }

    @Test
    void detailParsesCaseInsensitivelyAndDefaultsToCompact() {
        assertThat(ResponseDetail.parse(null)).isEqualTo(ResponseDetail.COMPACT);
        assertThat(ResponseDetail.parse(" FULL ")).isEqualTo(ResponseDetail.FULL);
        assertThatThrownBy(() -> ResponseDetail.parse("verbose")).isInstanceOf(IllegalArgumentException.class);
        assertThat(SearchRequest.of("x").detail()).isEqualTo(ResponseDetail.COMPACT);
        assertThat(SearchRequest.of("x").quantity()).isEqualTo(1);
        assertThat(BatchSearchRequest.of(List.of(SearchRequest.of("x", 5, null, null, 25, null)), null, null,
                ResponseDetail.FULL).expanded().getFirst()).satisfies(r -> {
                    assertThat(r.quantity()).isEqualTo(25);
                    assertThat(r.detail()).isEqualTo(ResponseDetail.FULL);
                });
    }
}
