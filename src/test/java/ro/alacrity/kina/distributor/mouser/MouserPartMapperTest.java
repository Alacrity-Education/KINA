package ro.alacrity.kina.distributor.mouser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PriceBreak;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class MouserPartMapperTest {

    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    private final MouserPartMapper mapper = new MouserPartMapper();

    /** The first part of the recorded keyword response, modified by {@code change}. */
    private static MouserPart fixturePart(int index, Consumer<ObjectNode> change) {
        JsonNode root = MouserFixtures.JSON.readTree(MouserFixtures.text(MouserFixtures.KEYWORD));
        ObjectNode part = (ObjectNode) root.get("SearchResults").get("Parts").get(index);
        change.accept(part);
        return MouserFixtures.JSON.treeToValue(part, MouserPart.class);
    }

    @Test
    void mapsEveryFieldOfTheRecordedPart() {
        Part part = mapper.map(fixturePart(0, p -> { }), NOW).orElseThrow();

        assertThat(part.distributor()).isEqualTo(Distributor.MOUSER);
        assertThat(part.distributorPartNumber()).isEqualTo("603-CC0805MKX77BB106");
        assertThat(part.manufacturer()).isEqualTo("YAGEO");
        assertThat(part.manufacturerPartNumber()).isEqualTo("CC0805MKX7R7BB106");
        assertThat(part.description()).isEqualTo("Multilayer Ceramic Capacitors MLCC - SMD/SMT 16V 10uF X7R 0805 20% HI CV");
        assertThat(part.category()).isEqualTo("Multilayer Ceramic Capacitors MLCC - SMD/SMT");
        assertThat(part.packageName()).isNull(); // the keyword endpoint returns no "Package / Case" attribute
        assertThat(part.stock()).isEqualTo(76689);
        assertThat(part.minimumOrderQuantity()).isEqualTo(1);
        assertThat(part.orderMultiple()).isEqualTo(1);
        assertThat(part.datasheetUrl()).isEqualTo("https://www.mouser.com/datasheet/3/508/1/UPY_GPHC_X7R_6_3V_TO_250V.pdf");
        assertThat(part.photoUrl()).isEqualTo("https://www.mouser.com/images/yageo/images/AC_capacitor_SPL.jpg");
        assertThat(part.productUrl()).startsWith("https://ro.mouser.com/en/ProductDetail/YAGEO/CC0805MKX7R7BB106");
        assertThat(part.fetchedAt()).isEqualTo(NOW);
    }

    @Test
    void keepsTheCompletePriceListAscending() {
        Part part = mapper.map(fixturePart(0, p -> { }), NOW).orElseThrow();

        assertThat(part.prices()).extracting(PriceBreak::quantity)
                .containsExactly(1, 10, 50, 100, 500, 1500, 3000, 6000);
        assertThat(part.prices()).extracting(PriceBreak::currency).containsOnly("EUR");
        assertThat(part.prices().get(0).unitPrice()).isEqualByComparingTo("1.40");
        assertThat(part.prices().get(1).unitPrice()).isEqualByComparingTo("0.853");
        assertThat(part.prices().get(7).unitPrice()).isEqualByComparingTo("0.245");
    }

    @Test
    void sortsUnorderedPriceBreaksAndSkipsUnparsableOnes() {
        MouserPart source = fixturePart(0, p -> {
            ArrayNode breaks = (ArrayNode) p.get("PriceBreaks");
            List<JsonNode> reversed = new ArrayList<>();
            breaks.forEach(reversed::addFirst);
            breaks.removeAll();
            reversed.forEach(breaks::add);
            breaks.addObject().put("Quantity", 20).put("Price", "Quote").put("Currency", "EUR");
        });

        Part part = mapper.map(source, NOW).orElseThrow();

        assertThat(part.prices()).extracting(PriceBreak::quantity)
                .containsExactly(1, 10, 50, 100, 500, 1500, 3000, 6000);
    }

    @Test
    void joinsRepeatedAttributeNames() {
        Part part = mapper.map(fixturePart(0, p -> { }), NOW).orElseThrow();

        assertThat(part.attributes()).containsExactly(
                entry("Packaging", "Reel, Cut Tape, MouseReel"),
                entry("Standard Pack Qty", "3000"));
    }

    @Test
    void takesThePackageFromThePackageAttribute() {
        MouserPart source = fixturePart(0, p -> {
            ArrayNode attributes = (ArrayNode) p.get("ProductAttributes");
            attributes.addObject().put("AttributeName", "Case Code - in").put("AttributeValue", "0805");
            attributes.addObject().put("AttributeName", "Package / Case").put("AttributeValue", "0805 (2012 metric)");
        });

        assertThat(mapper.map(source, NOW).orElseThrow().packageName()).isEqualTo("0805 (2012 metric)");
    }

    @Test
    void fillsTheExtras() {
        Part first = mapper.map(fixturePart(0, p -> { }), NOW).orElseThrow();
        Part second = mapper.map(fixturePart(1, p -> { }), NOW).orElseThrow();

        Map<String, Object> extra = first.extra();
        assertThat(extra).containsOnlyKeys("lifecycle_status", "rohs", "lead_time", "factory_stock", "category",
                "suggested_replacement", "reeling", "sales_maximum_order_qty", "availability_on_order");
        assertThat(extra.get("lifecycle_status")).isNull();
        assertThat(extra.get("rohs")).isEqualTo("RoHS Compliant");
        assertThat(extra.get("lead_time")).isEqualTo("140 Days");
        assertThat(extra.get("factory_stock")).isEqualTo(0);
        assertThat(extra.get("category")).isEqualTo("Multilayer Ceramic Capacitors MLCC - SMD/SMT");
        assertThat(extra.get("suggested_replacement")).isNull();
        assertThat(extra.get("reeling")).isEqualTo(true);
        assertThat(extra.get("sales_maximum_order_qty")).isEqualTo(12000);
        assertThat(extra.get("availability_on_order")).isEqualTo(List.of());

        assertThat(second.extra().get("availability_on_order"))
                .isEqualTo(List.of(Map.of("quantity", 98000L, "date", "2026-10-19T00:00:00")));
        assertThat(second.stock()).as("on-order quantity never counts as stock").isEqualTo(92212);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "", "n/a", "-5"})
    void dropsPartsWithoutShipsNowStock(String availabilityInStock) {
        MouserPart source = fixturePart(1, p -> p.put("AvailabilityInStock", availabilityInStock));

        assertThat(mapper.map(source, NOW)).isEmpty();
    }

    @Test
    void dropsPartsWithMissingStock() {
        MouserPart source = fixturePart(1, p -> p.remove("AvailabilityInStock"));

        assertThat(mapper.map(source, NOW)).isEmpty();
    }

    @Test
    void ignoresAvailabilityTextAndFactoryStock() {
        MouserPart source = fixturePart(0, p -> p.put("AvailabilityInStock", "12").put("Availability", "99999 In Stock")
                .put("FactoryStock", "50000"));

        assertThat(mapper.map(source, NOW)).map(Part::stock).contains(12);
    }

    @Test
    void mapsTheWholeRecordedResponse() {
        List<Part> parts = MouserFixtures.response(MouserFixtures.KEYWORD).searchResults().parts().stream()
                .map(p -> mapper.map(p, NOW))
                .flatMap(Optional::stream)
                .toList();

        assertThat(parts).hasSize(5).allSatisfy(p -> {
            assertThat(p.stock()).isPositive();
            assertThat(p.prices()).isNotEmpty().isSortedAccordingTo(
                    (a, b) -> Integer.compare(a.quantity(), b.quantity()));
            assertThat(p.prices()).allSatisfy(pb -> assertThat(pb.unitPrice()).isGreaterThan(BigDecimal.ZERO));
        });
    }
}
