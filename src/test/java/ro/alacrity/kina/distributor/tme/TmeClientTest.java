package ro.alacrity.kina.distributor.tme;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.Deadline;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.distributor.DistributorException.Kind;
import ro.alacrity.kina.distributor.DistributorSearchPage;
import ro.alacrity.kina.distributor.FakeTime;
import ro.alacrity.kina.distributor.PartLookupResult;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static ro.alacrity.kina.distributor.tme.TmeTestSupport.JSON;
import static ro.alacrity.kina.distributor.tme.TmeTestSupport.expectToken;
import static ro.alacrity.kina.distributor.tme.TmeTestSupport.fixture;
import static ro.alacrity.kina.distributor.tme.TmeTestSupport.get;
import static ro.alacrity.kina.distributor.tme.TmeTestSupport.query;
import static ro.alacrity.kina.distributor.tme.TmeTestSupport.queryCount;

class TmeClientTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T10:00:00Z"), ZoneOffset.UTC);

    private MockRestServiceServer server;
    private final FakeTime time = new FakeTime(CLOCK.instant());

    /**
     * Client with a direct executor so the data/parameters/files calls happen in a deterministic order, and a
     * rate-limit policy on fake time (no real sleeping, no jitter).
     */
    private TmeClient client(KinaProperties.Tme properties) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new TmeClient(properties, builder.build(), CLOCK, Runnable::run, time.retry(Distributor.TME, 0.5));
    }

    private static String json(Object value) {
        return JSON.writeValueAsString(value);
    }

    // ---- synthetic responses --------------------------------------------------------------------------------

    private static List<String> symbols(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> "SYM-" + i).toList();
    }

    private static String searchJson(List<String> symbols, int count, int pages, int page) {
        List<Map<String, Object>> elements = new ArrayList<>();
        for (String symbol : symbols) {
            Map<String, Object> product = new LinkedHashMap<>();
            product.put("product_status", List.of());
            product.put("symbol", symbol);
            product.put("category", Map.of("id", 1, "name", "Cat"));
            product.put("manufacturer_symbols", List.of("MPN-" + symbol));
            product.put("manufacturer", Map.of("id", 2, "name", "ACME"));
            product.put("description", "Part " + symbol);
            product.put("multiples", 1);
            product.put("minimal_amount", 1);
            elements.add(product);
        }
        return json(Map.of("status", "OK", "data", Map.of(
                "products", Map.of("elements", elements),
                "counters", Map.of("pages", pages, "count", count, "page", page))));
    }

    private static String dataJson(Map<String, Integer> stock) {
        List<Map<String, Object>> elements = new ArrayList<>();
        stock.forEach((symbol, quantity) -> elements.add(Map.of("stock_quantity", quantity, "symbol", symbol,
                "prices", Map.of("elements", List.of(Map.of("amount", 1, "price", 0.5, "special", false)),
                        "currency", "EUR", "type", "NET", "tax", Map.of("type", "VAT", "rate", 21.0)))));
        return json(Map.of("status", "OK", "data", Map.of("elements", elements)));
    }

    private static String dataJson(List<String> symbols, int stock) {
        Map<String, Integer> map = new LinkedHashMap<>();
        symbols.forEach(s -> map.put(s, stock));
        return dataJson(map);
    }

    private static String parametersJson(List<String> symbols) {
        List<Map<String, Object>> elements = symbols.stream().map(s -> Map.<String, Object>of("symbol", s,
                "parameters", Map.of("elements", List.of(Map.of("id", 1, "name", "Case - inch",
                        "values", List.of(Map.of("id", 3, "value", "0603"))))))).toList();
        return json(Map.of("status", "OK", "data", Map.of("elements", elements)));
    }

    private static String filesJson(List<String> symbols) {
        List<Map<String, Object>> elements = symbols.stream().map(s -> Map.<String, Object>of("symbol", s,
                "documents", Map.of("elements", List.of(Map.of("url", "//www.tme.eu/Document/x/" + s + ".pdf",
                        "type", "DTE", "file_name", s + ".pdf"))))).toList();
        return json(Map.of("status", "OK", "data", Map.of("elements", elements)));
    }

    private static MediaType jsonType() {
        return MediaType.APPLICATION_JSON;
    }

    // ---- tests ------------------------------------------------------------------------------------------------

    @Test
    void searchMergesDataParametersAndDatasheetsAndDropsOutOfStock() {
        TmeClient client = client(TmeTestSupport.properties(20));
        // recorded data with two symbols forced out of stock
        ObjectNode data = (ObjectNode) JSON.readTree(fixture("data.json"));
        for (JsonNode element : data.path("data").path("elements")) {
            String symbol = element.path("symbol").asString();
            if (symbol.equals("GRM21BR71A106KE51L") || symbol.equals("C0805C106K8RAC")) {
                ((ObjectNode) element).put("stock_quantity", 0);
            }
        }

        expectToken(server, "t1");
        server.expect(get("/products/search"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer t1"))
                .andExpect(header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(query("phrase", "10uF 0805 X7R"))
                .andExpect(query("scope[]", "products", "counters"))
                .andExpect(query("filter[in_stock]", "true"))
                .andExpect(query("country", "RO"))
                .andExpect(query("limit", "20"))
                .andExpect(query("page", "1"))
                .andRespond(withSuccess(fixture("search.json"), jsonType()));
        server.expect(get("/products/data"))
                .andExpect(queryCount("symbols[]", 20))
                .andExpect(query("scope[]", "prices", "stock"))
                .andExpect(query("currency", "EUR"))
                .andExpect(query("country", "RO"))
                .andRespond(withSuccess(json(data), jsonType()));
        server.expect(get("/products/parameters"))
                .andExpect(queryCount("symbols[]", 20))
                .andExpect(query("country", "RO"))
                .andRespond(withSuccess(fixture("parameters.json"), jsonType()));
        server.expect(get("/products/files"))
                .andExpect(queryCount("symbols[]", 20))
                .andRespond(withSuccess(fixture("files.json"), jsonType()));

        DistributorSearchPage page = client.search("  10uF   0805 X7R ", 0, 20);

        server.verify();
        assertThat(page.totalResults()).isEqualTo(111);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.parts()).hasSize(18);
        assertThat(page.outOfStock()).isEqualTo(2);   // reported as out_of_stock_matches
        assertThat(page.parts()).extracting(Part::distributorPartNumber)
                .doesNotContain("GRM21BR71A106KE51L", "C0805C106K8RAC")
                .startsWith("CL21B106KPQNNNE", "CS2012X7R106K160NR", "GCM21BR71A106KE22L");
        Part first = page.parts().getFirst();
        assertThat(first.stock()).isEqualTo(14);
        assertThat(first.packageName()).isEqualTo("0805");
        assertThat(first.attributes()).containsEntry("Dielectric", "X7R");
        assertThat(first.datasheetUrl()).endsWith("/mlcc_samsung.pdf");
        assertThat(first.fetchedAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void offsetMapsToPageAndInPageSkip() {
        TmeClient client = client(TmeTestSupport.properties(20));
        List<String> page2 = symbols(20);

        expectToken(server, "t");
        server.expect(get("/products/search"))
                .andExpect(query("limit", "20"))
                .andExpect(query("page", "2"))
                .andRespond(withSuccess(searchJson(page2, 40, 2, 2), jsonType()));
        List<String> requested = page2.subList(5, 10);
        server.expect(get("/products/data")).andExpect(query("symbols[]", requested.toArray(String[]::new)))
                .andRespond(withSuccess(dataJson(requested, 7), jsonType()));
        server.expect(get("/products/parameters")).andRespond(withSuccess(parametersJson(requested), jsonType()));
        server.expect(get("/products/files")).andRespond(withSuccess(filesJson(requested), jsonType()));

        DistributorSearchPage page = client.search("resistor", 25, 5);

        server.verify();
        assertThat(page.parts()).extracting(Part::distributorPartNumber).containsExactlyElementsOf(requested);
        assertThat(page.totalResults()).isEqualTo(40);
        assertThat(page.hasMore()).as("15 more elements on this page").isTrue();
        assertThat(page.parts().getFirst().packageName()).isEqualTo("0603");
        assertThat(page.parts().getFirst().datasheetUrl()).isEqualTo("https://www.tme.eu/Document/x/SYM-6.pdf");
    }

    @Test
    void lastPageHasNoMore() {
        TmeClient client = client(TmeTestSupport.properties(20));
        List<String> symbols = symbols(3);

        expectToken(server, "t");
        server.expect(get("/products/search")).andExpect(query("page", "1"))
                .andRespond(withSuccess(searchJson(symbols, 3, 1, 1), jsonType()));
        server.expect(get("/products/data")).andRespond(withSuccess(dataJson(symbols, 0), jsonType()));
        server.expect(get("/products/parameters")).andRespond(withSuccess(parametersJson(symbols), jsonType()));
        server.expect(get("/products/files")).andRespond(withSuccess(filesJson(symbols), jsonType()));

        DistributorSearchPage page = client.search("resistor", 0, 20);

        assertThat(page.parts()).isEmpty();
        assertThat(page.totalResults()).isEqualTo(3);
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void emptySearchResultMakesNoFurtherCalls() {
        TmeClient client = client(TmeTestSupport.properties(60));
        expectToken(server, "t");
        server.expect(get("/products/search")).andExpect(query("limit", "60"))
                .andRespond(withSuccess("{\"status\":\"OK\",\"data\":{\"products\":{\"elements\":[]},"
                        + "\"counters\":{\"pages\":0,\"count\":0,\"page\":1}}}", jsonType()));

        DistributorSearchPage page = client.search("nothing matches this", 0, 40);

        server.verify();
        assertThat(page.parts()).isEmpty();
        assertThat(page.totalResults()).isZero();
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void batchesMoreThanFiftySymbols() {
        TmeClient client = client(TmeTestSupport.properties(60));
        assertThat(client.maxPageSize()).isEqualTo(60);
        List<String> symbols = symbols(60);
        List<String> first = symbols.subList(0, 50);
        List<String> rest = symbols.subList(50, 60);

        expectToken(server, "t");
        server.expect(get("/products/search")).andExpect(query("limit", "60"))
                .andRespond(withSuccess(searchJson(symbols, 200, 4, 1), jsonType()));
        server.expect(get("/products/data")).andExpect(query("symbols[]", first.toArray(String[]::new)))
                .andRespond(withSuccess(dataJson(first, 10), jsonType()));
        server.expect(get("/products/data")).andExpect(query("symbols[]", rest.toArray(String[]::new)))
                .andRespond(withSuccess(dataJson(rest, 10), jsonType()));
        server.expect(get("/products/parameters")).andExpect(queryCount("symbols[]", 50))
                .andRespond(withSuccess(parametersJson(first), jsonType()));
        server.expect(get("/products/parameters")).andExpect(queryCount("symbols[]", 10))
                .andRespond(withSuccess(parametersJson(rest), jsonType()));
        server.expect(get("/products/files")).andExpect(queryCount("symbols[]", 50))
                .andRespond(withSuccess(filesJson(first), jsonType()));
        server.expect(get("/products/files")).andExpect(queryCount("symbols[]", 10))
                .andRespond(withSuccess(filesJson(rest), jsonType()));

        DistributorSearchPage page = client.search("capacitor", 0, 100);

        server.verify();
        assertThat(page.parts()).hasSize(60);
        assertThat(page.parts()).extracting(Part::distributorPartNumber).containsExactlyElementsOf(symbols);
        assertThat(page.parts()).allSatisfy(p -> assertThat(p.packageName()).isEqualTo("0603"));
        assertThat(page.hasMore()).isTrue();
    }

    @Test
    void pageSizeIsClampedToTheApiMaximum() {
        assertThat(client(TmeTestSupport.properties(500)).maxPageSize()).isEqualTo(100);
        assertThat(client(TmeTestSupport.properties(0)).maxPageSize()).isEqualTo(1);
    }

    @Test
    void longQueriesAreShortenedToFortyCharacters() {
        assertThat(TmeClient.phrase("ceramic capacitor 10uF 16V X7R 0805 SMD MLCC"))
                .isEqualTo("ceramic capacitor 10uF 16V X7R 0805 SMD");
        assertThat(TmeClient.phrase("x".repeat(50))).hasSize(40);
        assertThatThrownBy(() -> TmeClient.phrase(" a "))
                .isInstanceOfSatisfying(DistributorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.BAD_RESPONSE));
    }

    @Test
    void filesEndpointRefusalIsLoggedOnceAndSkipped() {
        TmeClient client = client(TmeTestSupport.properties(20));
        List<String> symbols = symbols(2);
        String forbidden = "{\"code\":\"E_ACTION_NOT_ALLOWED\",\"error_code\":99,\"message\":\"Action not allowed.\",\"error_data\":[]}";

        expectToken(server, "t");
        server.expect(get("/products/search")).andRespond(withSuccess(searchJson(symbols, 2, 1, 1), jsonType()));
        server.expect(get("/products/data")).andRespond(withSuccess(dataJson(symbols, 5), jsonType()));
        server.expect(get("/products/parameters")).andRespond(withSuccess(parametersJson(symbols), jsonType()));
        server.expect(get("/products/files"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body(forbidden));
        // second search: /products/files is not called again
        server.expect(get("/products/search")).andRespond(withSuccess(searchJson(symbols, 2, 1, 1), jsonType()));
        server.expect(get("/products/data")).andRespond(withSuccess(dataJson(symbols, 5), jsonType()));
        server.expect(get("/products/parameters")).andRespond(withSuccess(parametersJson(symbols), jsonType()));

        DistributorSearchPage first = client.search("diode", 0, 20);
        DistributorSearchPage second = client.search("diode", 0, 20);

        server.verify();
        // without /products/files the product page stands in for the datasheet
        assertThat(first.parts()).hasSize(2).allSatisfy(p -> {
            assertThat(p.datasheetUrl()).isEqualTo(p.productUrl());
            assertThat(p.extra()).containsEntry("datasheet_source", "product_page");
        });
        assertThat(second.parts()).hasSize(2);
    }

    @Test
    void getPartLooksUpOneSymbol() {
        TmeClient client = client(TmeTestSupport.properties(60));
        String symbol = "CL21B106KPQNNNE";
        TmeResponses.DataResponse recorded = fixture("data.json", TmeResponses.DataResponse.class);
        String data = json(Map.of("status", "OK", "data", Map.of("elements",
                List.of(JSON.readTree(fixture("data.json")).path("data").path("elements").get(0)))));
        assertThat(recorded.data().elements().getFirst().symbol()).isEqualTo(symbol);

        expectToken(server, "t");
        server.expect(get("/products")).andExpect(query("symbols[]", symbol)).andExpect(query("country", "RO"))
                .andRespond(withSuccess(fixture("products.json"), jsonType()));
        server.expect(get("/products/data")).andExpect(query("symbols[]", symbol))
                .andRespond(withSuccess(data, jsonType()));
        server.expect(get("/products/parameters")).andExpect(query("symbols[]", symbol))
                .andRespond(withSuccess(fixture("parameters.json"), jsonType()));
        server.expect(get("/products/files")).andExpect(query("symbols[]", symbol))
                .andRespond(withSuccess(fixture("files.json"), jsonType()));

        Optional<Part> part = client.getPart(" " + symbol + " ");

        server.verify();
        assertThat(part).hasValueSatisfying(p -> {
            assertThat(p.distributorPartNumber()).isEqualTo(symbol);
            assertThat(p.manufacturer()).isEqualTo("SAMSUNG");
            assertThat(p.stock()).isEqualTo(14);
            assertThat(p.packageName()).isEqualTo("0805");
            assertThat(p.datasheetUrl()).endsWith("mlcc_samsung.pdf");
        });
    }

    @Test
    void getPartUnknownOrOutOfStockIsEmpty() {
        TmeClient client = client(TmeTestSupport.properties(60));
        expectToken(server, "t");
        server.expect(get("/products")).andRespond(withSuccess(fixture("products-unknown.json"), jsonType()));
        // the miss is retried once by manufacturer part number, as written and with letters and digits only
        server.expect(get("/products")).andExpect(query("mpns[]", "NO-SUCH-SYMBOL", "NOSUCHSYMBOL"))
                .andRespond(withSuccess(fixture("products-unknown.json"), jsonType()));

        assertThat(client.getPart("NO-SUCH-SYMBOL")).isEmpty();
        assertThat(client.getPart("  ")).isEmpty();
        server.verify();

        server.reset();
        server.expect(get("/products")).andRespond(withSuccess(fixture("products.json"), jsonType()));
        server.expect(get("/products/data")).andRespond(withSuccess(dataJson(List.of("CL21B106KPQNNNE"), 0), jsonType()));
        server.expect(get("/products/parameters")).andRespond(withSuccess(fixture("parameters.json"), jsonType()));
        server.expect(get("/products/files")).andRespond(withSuccess(fixture("files.json"), jsonType()));

        assertThat(client.getPart("CL21B106KPQNNNE")).isEmpty();
        server.verify();
    }

    @Test
    void lookupTellsOutOfStockFromNotFound() {
        TmeClient client = client(TmeTestSupport.properties(60));
        expectToken(server, "t");
        server.expect(get("/products")).andRespond(withSuccess(fixture("products-unknown.json"), jsonType()));
        server.expect(get("/products")).andRespond(withSuccess(fixture("products-unknown.json"), jsonType()));
        assertThat(client.lookup("NO-SUCH-SYMBOL", Deadline.immediate()).status())
                .isEqualTo(PartLookupResult.Status.NOT_FOUND);
        server.verify();

        server.reset();
        server.expect(get("/products")).andRespond(withSuccess(fixture("products.json"), jsonType()));
        server.expect(get("/products/data"))
                .andRespond(withSuccess(dataJson(List.of("CL21B106KPQNNNE"), 0), jsonType()));
        server.expect(get("/products/parameters")).andRespond(withSuccess(fixture("parameters.json"), jsonType()));
        server.expect(get("/products/files")).andRespond(withSuccess(fixture("files.json"), jsonType()));

        PartLookupResult result = client.lookup("CL21B106KPQNNNE", Deadline.immediate());

        server.verify();
        assertThat(result.status()).isEqualTo(PartLookupResult.Status.OUT_OF_STOCK);
        assertThat(result.asOptional()).isEmpty();
        assertThat(result.listed()).hasValueSatisfying(p -> {
            assertThat(p.distributorPartNumber()).isEqualTo("CL21B106KPQNNNE");
            assertThat(p.stock()).isZero();
        });
        assertThat(result.identity()).isEqualTo(new PartLookupResult.Identity("CL21B106KPQNNNE", "SAMSUNG",
                "CL21B106KPQNNNE", "Capacitor: ceramic; MLCC; 10uF; 10V; X7R; \u00b110%; SMD; 0805"));
    }

    @Test
    void lookupByHyphenatedMpnFindsTheUnhyphenatedSymbol() {
        // live 2026-10-05: TME lists Panasonic ERA-6AEB5361V as symbol ERA6AEB5361V with both manufacturer symbols
        String product = fixture("products.json").replace("\"symbol\":\"CL21B106KPQNNNE\"", "\"symbol\":\"ERA6AEB5361V\"")
                .replace("\"manufacturer_symbols\":[\"CL21B106KPQNNNE\"]",
                        "\"manufacturer_symbols\":[\"ERA6AEB5361V\",\"ERA-6AEB5361V\"]");
        TmeClient client = client(TmeTestSupport.properties(60));
        expectToken(server, "t");
        server.expect(get("/products")).andRespond(withSuccess(fixture("products-unknown.json"), jsonType()));
        server.expect(get("/products")).andExpect(query("mpns[]", "ERA-6AEB5361V", "ERA6AEB5361V"))
                .andRespond(withSuccess(product, jsonType()));
        server.expect(get("/products/data"))
                .andRespond(withSuccess(dataJson(List.of("ERA6AEB5361V"), 120), jsonType()));
        server.expect(get("/products/parameters")).andRespond(withSuccess(fixture("parameters.json"), jsonType()));
        server.expect(get("/products/files")).andRespond(withSuccess(fixture("files.json"), jsonType()));

        PartLookupResult result = client.lookup("ERA-6AEB5361V", Deadline.immediate());

        server.verify();
        assertThat(result.status()).isEqualTo(PartLookupResult.Status.FOUND);
        assertThat(result.part().distributorPartNumber()).isEqualTo("ERA6AEB5361V");
        assertThat(result.part().stock()).isEqualTo(120);
    }

    @Test
    void lookupWithSpacesTriesTheHyphenatedAndTheJoinedSpelling() {
        // live 2026-10-06: TME refuses "HCMA0703 2R2 R" (HTTP 400, characters not permitted in symbols[0]);
        // HCMA0703-2R2-R is the symbol
        String product = fixture("products.json").replace("\"symbol\":\"CL21B106KPQNNNE\"", "\"symbol\":\"HCMA0703-2R2-R\"")
                .replace("\"manufacturer_symbols\":[\"CL21B106KPQNNNE\"]", "\"manufacturer_symbols\":[\"HCMA0703-2R2-R\"]");
        TmeClient client = client(TmeTestSupport.properties(60));
        expectToken(server, "t");
        server.expect(get("/products")).andExpect(query("symbols[]", "HCMA0703-2R2-R"))
                .andRespond(withSuccess(product, jsonType()));
        server.expect(get("/products/data"))
                .andRespond(withSuccess(dataJson(List.of("HCMA0703-2R2-R"), 300), jsonType()));
        server.expect(get("/products/parameters")).andRespond(withSuccess(fixture("parameters.json"), jsonType()));
        server.expect(get("/products/files")).andRespond(withSuccess(fixture("files.json"), jsonType()));

        PartLookupResult result = client.lookup("  HCMA0703 2R2 R ", Deadline.immediate());

        server.verify();
        assertThat(result.status()).isEqualTo(PartLookupResult.Status.FOUND);
        assertThat(result.part().distributorPartNumber()).isEqualTo("HCMA0703-2R2-R");
    }

    @Test
    void refusedPartNumberIsNotFoundNotBadResponse() {
        TmeClient client = client(TmeTestSupport.properties(60));
        expectToken(server, "t");
        for (int i = 0; i < 3; i++) {   // both symbol spellings, then the manufacturer part numbers
            server.expect(get("/products")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON).body(fixture("error-validation.json")));
        }

        assertThat(client.lookup("ABC 123", Deadline.immediate()).status())
                .isEqualTo(PartLookupResult.Status.NOT_FOUND);
        server.verify();
        assertThat(PartLookupResult.variants("HCMA0703 2R2 R")).containsExactly("HCMA0703-2R2-R", "HCMA07032R2R");
        assertThat(PartLookupResult.variants("ERA-6AEB5361V")).containsExactly("ERA-6AEB5361V");
        assertThat(PartLookupResult.variants("A\tB<C>")).containsExactly("A-BC", "ABC");
    }

    @Test
    void excludedStatusesAcceptAPrefix() {
        TmeResponses.Product blocked = JSON.readValue(fixture("products.json"), TmeResponses.ProductsResponse.class)
                .data().elements().getFirst();
        TmeResponses.Product zbl = new TmeResponses.Product(List.of("BLOCKED_FOR_ZBL_PL"), blocked.symbol(),
                blocked.category(), blocked.manufacturerSymbols(), blocked.manufacturer(), blocked.description(),
                blocked.multiples(), blocked.minimalAmount(), blocked.unit(), blocked.packing(), blocked.assets());
        assertThat(TmePartMapper.hasExcludedStatus(zbl, KinaProperties.Tme.DEFAULT_EXCLUDED_STATUSES)).isTrue();
        assertThat(TmePartMapper.hasExcludedStatus(zbl, List.of("BLOCKED_FOR_ZBL"))).isFalse();
    }

    @Test
    void notConfigured() {
        KinaProperties.Tme blankSecret = new KinaProperties.Tme("token", " ", "RO", "EUR", "en",
                TmeTestSupport.BASE, 60, 3, KinaProperties.Tme.DEFAULT_EXCLUDED_STATUSES);
        TmeClient client = client(blankSecret);

        assertThat(client.isConfigured()).isFalse();
        assertThatThrownBy(() -> client.search("10uF", 0, 10))
                .isInstanceOfSatisfying(DistributorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.NOT_CONFIGURED));
        assertThatThrownBy(() -> client.getPart("X"))
                .isInstanceOfSatisfying(DistributorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.NOT_CONFIGURED));
        server.verify(); // no HTTP calls
        assertThat(client(TmeTestSupport.properties(60)).isConfigured()).isTrue();
    }

    @Test
    void validationErrorIsBadResponseWithTmeMessage() {
        TmeClient client = client(TmeTestSupport.properties(60));
        expectToken(server, "t");
        server.expect(get("/products/search"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body(fixture("error-validation.json")));

        assertThatThrownBy(() -> client.search("10uF", 0, 10))
                .isInstanceOfSatisfying(DistributorException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.BAD_RESPONSE);
                    assertThat(e.getMessage()).contains("E_INPUT_PARAMS_VALIDATION_ERROR", "Input data is not valid.",
                            "limit: This value should be between 1 and 100.");
                });
    }

    @Test
    void rateLimitedIsRateLimited() {
        TmeClient client = client(TmeTestSupport.properties(60));
        expectToken(server, "t");
        server.expect(get("/products/search")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.search("10uF", 0, 10))
                .isInstanceOfSatisfying(DistributorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED));
    }

    @Test
    void serverErrorIsUnavailable() {
        TmeClient client = client(TmeTestSupport.properties(60));
        expectToken(server, "t");
        server.expect(get("/products/search")).andRespond(withServerError());

        assertThatThrownBy(() -> client.search("10uF", 0, 10))
                .isInstanceOfSatisfying(DistributorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE));
    }

    @Test
    void timeoutIsTimeout() {
        TmeClient client = client(TmeTestSupport.properties(60));
        expectToken(server, "t");
        server.expect(get("/products/search")).andRespond(withException(new HttpTimeoutException("request timed out")));

        assertThatThrownBy(() -> client.search("10uF", 0, 10))
                .isInstanceOfSatisfying(DistributorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.TIMEOUT));
    }

    @Test
    void ioErrorIsUnavailable() {
        TmeClient client = client(TmeTestSupport.properties(60));
        expectToken(server, "t");
        server.expect(get("/products/search")).andRespond(withException(new java.net.ConnectException("refused")));

        assertThatThrownBy(() -> client.search("10uF", 0, 10))
                .isInstanceOfSatisfying(DistributorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE));
    }

    @Test
    void unauthorizedTwiceIsUnavailable() {
        TmeClient client = client(TmeTestSupport.properties(60));
        expectToken(server, "a");
        server.expect(get("/products/search")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        expectToken(server, "b");
        server.expect(get("/products/search")).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer b"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.search("10uF", 0, 10))
                .isInstanceOfSatisfying(DistributorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE));
        server.verify();
    }

    @Test
    void failingDataCallFailsTheSearch() {
        TmeClient client = client(TmeTestSupport.properties(60));
        List<String> symbols = symbols(2);
        expectToken(server, "t");
        server.expect(get("/products/search")).andRespond(withSuccess(searchJson(symbols, 2, 1, 1), jsonType()));
        server.expect(ExpectedCount.once(), get("/products/data")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(ExpectedCount.manyTimes(), get("/products/parameters"))
                .andRespond(withSuccess(parametersJson(symbols), jsonType()));
        server.expect(ExpectedCount.manyTimes(), get("/products/files")).andRespond(withSuccess(filesJson(symbols), jsonType()));

        assertThatThrownBy(() -> client.search("10uF", 0, 10))
                .isInstanceOfSatisfying(DistributorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED));
    }
    // ---- rate limiting (DESIGN.md 3.6) ------------------------------------------------------------------------------

    private void expectEnrichment(List<String> symbols) {
        server.expect(get("/products/data")).andRespond(withSuccess(dataJson(symbols, 5), jsonType()));
        server.expect(get("/products/parameters")).andRespond(withSuccess(parametersJson(symbols), jsonType()));
        server.expect(get("/products/files")).andRespond(withSuccess(filesJson(symbols), jsonType()));
    }

    @Test
    void searchRateLimitedThenSuccessWaitsAndReportsTheWait() {
        TmeClient client = client(TmeTestSupport.properties(20));
        List<String> symbols = symbols(2);
        expectToken(server, "t");
        server.expect(get("/products/search"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "4"));
        server.expect(get("/products/search")).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer t"))
                .andRespond(withSuccess(searchJson(symbols, 2, 1, 1), jsonType()));
        expectEnrichment(symbols);
        Deadline deadline = time.deadline(Duration.ofMinutes(2));

        DistributorSearchPage page = client.search("10uF", 0, 20, deadline);

        server.verify();
        assertThat(page.parts()).hasSize(2);
        assertThat(time.sleeps()).containsExactly(Duration.ofSeconds(4));
        assertThat(deadline.rateLimitWaitedMillis()).isEqualTo(4_000);
    }

    @Test
    void retryAfterBeyondTheDeadlineIsRateLimitedAtOnce() {
        TmeClient client = client(TmeTestSupport.properties(20));
        expectToken(server, "t");
        server.expect(get("/products/search"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "600"));

        assertThatThrownBy(() -> client.search("10uF", 0, 20, time.deadline(Duration.ofMinutes(2))))
                .isInstanceOfSatisfying(DistributorException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED);
                    assertThat(e.distributor()).isEqualTo(Distributor.TME);
                    assertThat(e.getMessage()).contains("rate limited by TME", "would exceed the request deadline");
                });
        server.verify();
        assertThat(time.sleeps()).isEmpty();
    }

    @Test
    void rateLimitedTokenRequestIsRetried() {
        TmeClient client = client(TmeTestSupport.properties(60));
        server.expect(requestTo(TmeTestSupport.BASE + "/auth/token"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        expectToken(server, "t");
        server.expect(get("/products")).andRespond(withSuccess(fixture("products.json"), jsonType()));
        server.expect(get("/products/data")).andRespond(withSuccess(fixture("data.json"), jsonType()));
        server.expect(get("/products/parameters")).andRespond(withSuccess(fixture("parameters.json"), jsonType()));
        server.expect(get("/products/files")).andRespond(withSuccess(fixture("files.json"), jsonType()));

        assertThat(client.getPart("CL21B106KPQNNNE", time.deadline(Duration.ofMinutes(2)))).isPresent();

        server.verify();
        assertThat(time.sleeps()).containsExactly(Duration.ofSeconds(2));
    }

    @Test
    void rateLimitedEnrichmentCallIsRetried() {
        TmeClient client = client(TmeTestSupport.properties(20));
        List<String> symbols = symbols(2);
        expectToken(server, "t");
        server.expect(get("/products/search")).andRespond(withSuccess(searchJson(symbols, 2, 1, 1), jsonType()));
        server.expect(get("/products/data"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "1"));
        expectEnrichment(symbols);
        Deadline deadline = time.deadline(Duration.ofMinutes(2));

        assertThat(client.search("10uF", 0, 20, deadline).parts()).hasSize(2);

        server.verify();
        assertThat(deadline.rateLimitWaitedMillis()).isEqualTo(1_000);
    }

    @Test
    void refreshStockReadsProductsDataInBatchesOfFifty() {
        TmeClient client = client(TmeTestSupport.properties(20));
        List<String> symbols = symbols(51);
        Map<String, Integer> first = new LinkedHashMap<>();
        symbols.subList(0, 50).forEach(s -> first.put(s, 7));
        first.put(symbols.get(1), 0);   // sold out since it was cached
        expectToken(server, "t");
        server.expect(get("/products/data"))
                .andExpect(request -> assertThat(request.getURI().getRawQuery()).contains("scope%5B%5D=stock")
                        .contains("symbols%5B%5D=" + symbols.get(49)).doesNotContain(symbols.get(50)))
                .andRespond(withSuccess(dataJson(first), jsonType()));
        server.expect(get("/products/data")).andRespond(withSuccess(dataJson(Map.of(symbols.get(50), 3)), jsonType()));

        Map<String, ro.alacrity.kina.distributor.StockUpdate> updates = client.refreshStock(symbols,
                time.deadline(Duration.ofMinutes(2)));

        server.verify();
        assertThat(updates).hasSize(51);
        assertThat(updates.get(symbols.getFirst()).stock()).isEqualTo(7);
        assertThat(updates.get(symbols.getFirst()).prices()).hasSize(1);
        assertThat(updates.get(symbols.get(1)).stock()).isZero();
        assertThat(updates.get(symbols.get(50)).stock()).isEqualTo(3);
        assertThat(client.refreshStock(List.of(), time.deadline(Duration.ofMinutes(2)))).isEmpty();
    }
}
