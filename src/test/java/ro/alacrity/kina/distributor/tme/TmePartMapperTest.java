package ro.alacrity.kina.distributor.tme;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PriceBreak;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static ro.alacrity.kina.distributor.tme.TmeTestSupport.fixture;

/** Mapping against responses recorded from the live TME API v2 (2026-10-05, query "10uF 0805 X7R", country RO). */
class TmePartMapperTest {

    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    private final Map<String, TmeResponses.Product> products = bySymbol(
            fixture("search.json", TmeResponses.SearchResponse.class).data().products().elements(),
            TmeResponses.Product::symbol);
    private final Map<String, TmeResponses.ProductData> data = bySymbol(
            fixture("data.json", TmeResponses.DataResponse.class).data().elements(), TmeResponses.ProductData::symbol);
    private final Map<String, TmeResponses.ProductParameters> parameters = bySymbol(
            fixture("parameters.json", TmeResponses.ParametersResponse.class).data().elements(),
            TmeResponses.ProductParameters::symbol);
    private final Map<String, TmeResponses.ProductFiles> files = bySymbol(
            fixture("files.json", TmeResponses.FilesResponse.class).data().elements(), TmeResponses.ProductFiles::symbol);

    private static <T> Map<String, T> bySymbol(List<T> elements, Function<T, String> symbol) {
        return elements.stream().collect(Collectors.toMap(symbol, Function.identity(), (a, b) -> a));
    }

    private Optional<Part> map(String symbol) {
        return TmePartMapper.toPart(products.get(symbol), data.get(symbol), parameters.get(symbol),
                TmePartMapper.datasheetUrl(files.get(symbol)), NOW);
    }

    @Test
    void mapsLiveCapacitor() {
        Part part = map("CL21B106KPQNNNE").orElseThrow();

        assertThat(part.distributor()).isEqualTo(Distributor.TME);
        assertThat(part.distributorPartNumber()).isEqualTo("CL21B106KPQNNNE");
        assertThat(part.manufacturer()).isEqualTo("SAMSUNG");
        assertThat(part.manufacturerPartNumber()).isEqualTo("CL21B106KPQNNNE");
        assertThat(part.description()).isEqualTo("Capacitor: ceramic; MLCC; 10uF; 10V; X7R; ±10%; SMD; 0805");
        assertThat(part.category()).isEqualTo("MLCC SMD capacitors");
        assertThat(part.packageName()).isEqualTo("0805");
        assertThat(part.stock()).isEqualTo(14);
        assertThat(part.minimumOrderQuantity()).isEqualTo(1);
        assertThat(part.orderMultiple()).isEqualTo(1);
        assertThat(part.prices()).hasSize(7);
        assertThat(part.prices().getFirst()).isEqualTo(new PriceBreak(1, new BigDecimal("0.2184"), "EUR"));
        assertThat(part.prices()).extracting(PriceBreak::quantity).isSorted();
        assertThat(part.datasheetUrl()).isEqualTo("https://www.tme.eu/Document/7da762c1dbaf553c64ad9c40d3603826/mlcc_samsung.pdf");
        assertThat(part.photoUrl()).startsWith("https://ce8dc832c.cloudimg.io/v7/_cdn_/DA/F2/B0/00/1/733101_1.jpg?width=640");
        assertThat(part.productUrl()).isEqualTo("https://www.tme.eu/en/details/CL21B106KPQNNNE/");
        assertThat(part.attributes()).contains(
                entry("Manufacturer", "SAMSUNG"),
                entry("Capacitance", "10µF"),
                entry("Operating voltage", "10V"),
                entry("Dielectric", "X7R"),
                entry("Tolerance", "±10%"),
                entry("Case - inch", "0805"),
                entry("Case - mm", "2012"));
        assertThat(part.attributes()).doesNotContainKey("Manufacturer series"); // empty values are skipped
        assertThat(part.attributes().keySet()).startsWith("Manufacturer", "Type of capacitor", "Kind of capacitor");
        assertThat(part.extra()).containsEntry("product_status", List.of("HARDLY_AVAILABLE"))
                .containsEntry("category_id", 113537L)
                .containsEntry("manufacturer_id", 78L)
                .containsEntry("unit", "pcs")
                .containsEntry("packing", List.of(Map.of("id", "RL1", "amount", 2000)))
                .containsEntry("price_type", "NET")
                .containsEntry("special_prices", false);
        assertThat((BigDecimal) part.extra().get("tax_rate")).isEqualByComparingTo("21");
        assertThat(part.fetchedAt()).isEqualTo(NOW);
    }

    @Test
    void mapsEveryRecordedProduct() {
        assertThat(products).hasSize(20);
        for (String symbol : products.keySet()) {
            Part part = map(symbol).orElseThrow();
            assertThat(part.stock()).isPositive();
            assertThat(part.packageName()).isEqualTo("0805");
            assertThat(part.prices()).isNotEmpty();
            assertThat(part.prices()).allSatisfy(p -> assertThat(p.currency()).isEqualTo("EUR"));
            assertThat(part.manufacturerPartNumber()).isNotBlank();
            assertThat(part.photoUrl()).startsWith("https://");
        }
    }

    @Test
    void datasheetIgnoresLinkAndVideoDocuments() {
        // GRM21BR71A106KE51L only has LNK (link .txt) and YTB documents: the TME product page stands in
        assertThat(TmePartMapper.datasheetUrl(files.get("GRM21BR71A106KE51L"))).isNull();
        Part noSheet = map("GRM21BR71A106KE51L").orElseThrow();
        assertThat(noSheet.datasheetUrl()).isEqualTo("https://www.tme.eu/en/details/GRM21BR71A106KE51L/");
        assertThat(noSheet.extra()).containsEntry("datasheet_source", "product_page");
        assertThat(TmePartMapper.datasheetUrl(files.get("CS2012X7R106K160NR")))
                .isEqualTo("https://www.tme.eu/Document/15a3409220a9cd1969199d6f4e29e942/CS.pdf");
        assertThat(map("CS2012X7R106K160NR").orElseThrow().extra()).containsEntry("datasheet_source", "dte");
        assertThat(TmePartMapper.datasheetUrl(null)).isNull();
    }

    @Test
    void datasheetFallsBackToADocumentNamedDatasheet() {
        TmeResponses.ProductFiles productFiles = new TmeResponses.ProductFiles("X", new TmeResponses.Documents(List.of(
                new TmeResponses.Document("//www.tme.eu/Document/a/Eaton_22-LNK.txt", "LNK", 163L,
                        "Eaton_22-LNK.txt", "EN"),
                new TmeResponses.Document("//www.tme.eu/Document/b/hcma-data-sheet.pdf", "INS", 10L,
                        "hcma-data-sheet.pdf", "EN"))));

        assertThat(TmePartMapper.datasheet(productFiles)).isEqualTo(new TmePartMapper.Datasheet(
                "https://www.tme.eu/Document/b/hcma-data-sheet.pdf", TmePartMapper.SOURCE_DOCUMENT));
        // only the DTE document counts as "the" datasheet when one exists
        assertThat(TmePartMapper.datasheetUrl(productFiles)).isNull();
    }

    @Test
    void datasheetPrefersPdfDocumentation() {
        TmeResponses.ProductFiles productFiles = new TmeResponses.ProductFiles("X", new TmeResponses.Documents(List.of(
                new TmeResponses.Document("//www.tme.eu/Document/a/link.txt", "LNK", 10L, "link.txt", "EN"),
                new TmeResponses.Document("//www.tme.eu/Document/b/notes.txt", "DTE", 10L, "notes.txt", "EN"),
                new TmeResponses.Document("https://example.com/ds.pdf", "DTE", 10L, "ds.pdf", "EN"))));

        assertThat(TmePartMapper.datasheetUrl(productFiles)).isEqualTo("https://example.com/ds.pdf");
    }

    @Test
    void packageFallsBackToCaseParameter() {
        TmeResponses.Product product = fixture("search-bc847.json", TmeResponses.SearchResponse.class)
                .data().products().elements().getFirst();
        TmeResponses.ProductParameters params = fixture("parameters-bc847.json", TmeResponses.ParametersResponse.class)
                .data().elements().stream().filter(p -> p.symbol().equals(product.symbol())).findFirst().orElseThrow();
        TmeResponses.ProductData stock = new TmeResponses.ProductData(BigDecimal.TEN, product.symbol(), null, null);

        Part part = TmePartMapper.toPart(product, stock, params, null, NOW).orElseThrow();

        assertThat(part.packageName()).isEqualTo("SOT23");
        assertThat(part.manufacturerPartNumber()).isEqualTo("BC847B");
        assertThat(part.extra()).containsEntry("product_status", List.of());
        assertThat(part.prices()).isEmpty();
        assertThat(part.datasheetUrl()).isEqualTo("https://www.tme.eu/en/details/BC847B-DIO/");
    }

    @Test
    void packagePreferenceOrder() {
        TmeResponses.Parameter caseMm = param("Case - mm", "2012");
        TmeResponses.Parameter caseParam = param("Case", "SOT23", "TO236AB");
        TmeResponses.Parameter caseInch = param("Case - inch", "0805");
        TmeResponses.Parameter emptyInch = new TmeResponses.Parameter(1L, "Case - inch", List.of());

        assertThat(TmePartMapper.packageName(List.of(caseMm, caseParam, caseInch))).isEqualTo("0805");
        assertThat(TmePartMapper.packageName(List.of(caseMm, emptyInch, caseParam))).isEqualTo("SOT23");
        assertThat(TmePartMapper.packageName(List.of(param("Package", "DIP8"), caseMm))).isEqualTo("DIP8");
        // a bare Case - mm code is labelled: the search layer reads it as metric (2012 mm = imperial 0805)
        assertThat(TmePartMapper.packageName(List.of(caseMm))).isEqualTo("2012 mm");
        assertThat(TmePartMapper.packageName(List.of(param("Mounting", "SMD")))).isNull();
        assertThat(TmePartMapper.attributes(List.of(caseParam))).containsExactly(entry("Case", "SOT23, TO236AB"));
    }

    @Test
    void outOfStockOrMissingDataIsDropped() {
        TmeResponses.ProductData outOfStock = fixture("data-out-of-stock.json", TmeResponses.DataResponse.class)
                .data().elements().getFirst();
        assertThat(outOfStock.stockQuantity()).isZero();
        TmeResponses.Product product = products.get("CL21B106KPQNNNE");

        assertThat(TmePartMapper.toPart(product, outOfStock, null, null, NOW)).isEmpty();
        assertThat(TmePartMapper.toPart(product, null, null, null, NOW)).isEmpty();
        assertThat(TmePartMapper.toPart(product,
                new TmeResponses.ProductData(null, product.symbol(), null, null), null, null, NOW)).isEmpty();
    }

    @Test
    void excludedProductStatusesAreDroppedButOtherStatusesKept() {
        TmeResponses.ProductData stock = new TmeResponses.ProductData(new BigDecimal("500"), "EXT-0", null, null);
        List<String> excluded = ro.alacrity.kina.config.KinaProperties.Tme.DEFAULT_EXCLUDED_STATUSES;

        for (String status : excluded) {
            TmeResponses.Product product = new TmeResponses.Product(List.of("NEW", status.toLowerCase()), "EXT-0",
                    null, List.of(), null, "desc", null, null, null, null, null);
            assertThat(TmePartMapper.toPart(product, stock, null, (String) null, NOW, excluded)).as(status).isEmpty();
            // without an exclusion list the same product maps (legacy overload)
            assertThat(TmePartMapper.toPart(product, stock, null, null, NOW)).isPresent();
        }

        TmeResponses.Product hardly = new TmeResponses.Product(List.of("HARDLY_AVAILABLE"), "EXT-0", null,
                List.of(), null, "desc", null, null, null, null, null);
        Part part = TmePartMapper.toPart(hardly, stock, null, (String) null, NOW, excluded).orElseThrow();
        assertThat(part.extra()).containsEntry("product_status", List.of("HARDLY_AVAILABLE"));
        assertThat(TmePartMapper.toPart(new TmeResponses.Product(null, "EXT-0", null, List.of(), null, "desc",
                null, null, null, null, null), stock, null, (String) null, NOW, excluded)).isPresent();
    }

    @Test
    void specialPricesAndMissingOptionalFields() {
        TmeResponses.Product bare = new TmeResponses.Product(null, "ABC/1", null, List.of(), null, "desc",
                null, null, null, null, null);
        TmeResponses.Prices prices = new TmeResponses.Prices(List.of(
                new TmeResponses.Price(new BigDecimal("100"), new BigDecimal("0.5"), true),
                new TmeResponses.Price(new BigDecimal("1"), new BigDecimal("0.9"), false)), "EUR", "NET", null);
        TmeResponses.ProductData stock = new TmeResponses.ProductData(new BigDecimal("5"), "ABC/1", null, prices);

        Part part = TmePartMapper.toPart(bare, stock, null, null, NOW).orElseThrow();

        assertThat(part.manufacturer()).isNull();
        assertThat(part.manufacturerPartNumber()).isNull();
        assertThat(part.category()).isNull();
        assertThat(part.packageName()).isNull();
        assertThat(part.photoUrl()).isNull();
        assertThat(part.minimumOrderQuantity()).isNull();
        assertThat(part.attributes()).isEmpty();
        assertThat(part.productUrl()).isEqualTo("https://www.tme.eu/en/details/ABC%2F1/");
        assertThat(part.prices()).extracting(PriceBreak::quantity).containsExactly(1, 100);
        assertThat(part.extra()).containsEntry("special_prices", true).doesNotContainKey("tax_rate");
    }

    private static TmeResponses.Parameter param(String name, String... values) {
        return new TmeResponses.Parameter(1L, name,
                java.util.Arrays.stream(values).map(v -> new TmeResponses.ParameterValue(2L, v)).toList());
    }
}
