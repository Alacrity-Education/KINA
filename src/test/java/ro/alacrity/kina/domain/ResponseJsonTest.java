package ro.alacrity.kina.domain;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.cache.CacheStatus;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the snake_case wire contract of DESIGN.md section 4. */
class ResponseJsonTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void searchResponseIsSnakeCaseAndTrimsPrices() {
        Part part = new Part(Distributor.MOUSER, "603-CC0805", "YAGEO", "CC0805MKX7R7BB106", "MLCC", "Capacitors",
                "0805", 76689, 1, 1,
                List.of(new PriceBreak(100, new BigDecimal("0.5"), "EUR"), new PriceBreak(1, new BigDecimal("1.40"), "EUR"),
                        new PriceBreak(10, new BigDecimal("0.853"), "EUR"), new PriceBreak(50, new BigDecimal("0.631"), "EUR")),
                "https://ds", "https://img", "https://prod", Map.of("Capacitance", "10uF"), Map.of("rohs", "RoHS Compliant"),
                Instant.parse("2026-10-05T00:00:00Z"));
        Map<String, ParsedQuery.Constraint> constraints = new LinkedHashMap<>();
        constraints.put(ParsedQuery.CAPACITANCE, new ParsedQuery.Constraint(ParsedQuery.CAPACITANCE, 10e-6, "10uF"));
        ParsedQuery parsed = new ParsedQuery("10uF X7R 0805", "10uf x7r 0805", "capacitor", constraints, "X7R", "0805",
                null, List.of());
        SearchResponse response = new SearchResponse("10uF X7R 0805", ParsedQueryResponse.from(parsed), RankingMode.BLENDED,
                null, List.of(DistributorResult.builder().distributor(Distributor.MOUSER).totalResults(113).fetched(50)
                        .returned(1).cache(CacheStatus.HIT).parts(List.of(PartResponse.from(part, 1, 0.93)))
                        .exactMatches(0).build()));

        String json = mapper.writeValueAsString(response);

        assertThat(json).contains("\"query_understood\":true", "\"currencies\":[\"EUR\"]", "\"excluded_below_spec\":0",
                "\"query_terms_dropped\":[]", "\"constraints_relaxed\":[]", "\"stock_as_of\":\"2026-10-05T00:00:00Z\"")
                .doesNotContain("\"relaxed\"", "\"hint\"", "\"below_spec\"", "\"unverified\"", "\"stale\"");
        assertThat(json).contains("\"attributions\":[\"Product data provided by Mouser Electronics\"]");
        assertThat(json).contains("\"ranking\":\"blended\"", "\"ranking_note\":null", "\"total_results\":113",
                "\"cache\":\"hit\"", "\"part_number\":\"603-CC0805\"", "\"mpn\":\"CC0805MKX7R7BB106\"",
                "\"package\":\"0805\"", "\"min_order_qty\":1", "\"order_multiple\":1", "\"datasheet_url\":",
                "\"photo_url\":", "\"product_url\":", "\"distributor\":\"MOUSER\"",
                "\"parsed\":{\"family\":\"capacitor\"", "\"capacitance\":\"10uF\"", "\"dielectric\":\"X7R\"",
                "\"keywords\":[]", "{\"qty\":1,\"unit_price\":1.40,\"currency\":\"EUR\"}",
                "\"fallback_query\":null", "\"rate_limit_waited_ms\":0", "\"distributor_query\":null",
                "\"excluded_by_constraints\":0", "\"out_of_stock_matches\":null",
                "\"exact_matches\":0", "\"availability\":{\"status\":\"in_stock\"", "\"lifecycle\":\"active\"");
        assertThat(json).doesNotContain("\"qty\":100", "mismatches", "mounting", "\"constraints\"", "packageName", "\"connector\"");
    }

    @Test
    void stalePartIsFlaggedNextToItsStockTimestamp() {
        Part part = new Part(Distributor.TME, "CL21B106KAYQNNE", "SAMSUNG", "CL21B106KAYQNNE", "MLCC 10uF", null, null,
                500, 1, 1, List.of(), null, null, null, Map.of(), Map.of(), Instant.parse("2026-10-01T08:00:00Z"));

        String json = mapper.writeValueAsString(PartResponse.of(part, PartResponse.Ranking.NONE, 1,
                ResponseDetail.COMPACT, Map.of(), 10, true, Instant.parse("2026-10-05T09:00:00Z")));

        assertThat(json).contains("\"stock_as_of\":\"2026-10-01T08:00:00Z\",\"stale\":true",
                "\"availability\":{\"status\":\"stale\",\"note\":\"Stock and price were last confirmed on 2026-10-01 "
                        + "(4 days ago) and could not be refreshed; check them at the distributor before ordering. "
                        + "Last known stock: 500.\"}");
        PartLookupResponse lookup = PartLookupResponse.found(Distributor.TME, "CL21B106KAYQNNE", CacheStatus.HIT,
                PartResponse.from(part));
        assertThat(mapper.writeValueAsString(lookup))
                .contains("\"attributions\":[\"Data powered by TME.eu Data – no guarantee of data accuracy\"]");
        assertThat(PartLookupResponse.notFound(Distributor.TME, "X", CacheStatus.MISS, null).attributions()).isEmpty();
    }

    @Test
    void partCarriesTheMatchGradeNextToTheScore() {
        Part part = new Part(Distributor.MOUSER, "71-TNPW08055K36BEEA", "Vishay", "TNPW08055K36BEEA",
                "Thin Film Resistors - SMD 5.36Kohms .1% 25ppm", "Thin Film Resistors - SMD", null, 500, 1, 1,
                List.of(), null, null, null, Map.of("Technology", "thin film"), Map.of(),
                Instant.parse("2026-10-05T00:00:00Z"));

        String json = mapper.writeValueAsString(PartResponse.from(part, 4, 0.0, 0.987));

        assertThat(json).startsWith("{\"rank\":4,\"score\":0.0,\"match\":0.99,\"distributor\":\"MOUSER\"")
                .contains("\"Technology\":\"thin film\"");
        assertThat(mapper.writeValueAsString(PartResponse.from(part))).contains("\"match\":null");
    }

    @Test
    void parsedTechnologyIsSnakeCaseAndOmittedWhenAbsent() {
        ParsedQuery thin = ParsedQuery.builder().originalText("thin film resistor").normalizedKey("thin film resistor")
                .family("resistor").technology("thin film").build();
        assertThat(mapper.writeValueAsString(ParsedQueryResponse.from(thin)))
                .contains("\"family\":\"resistor\"", "\"technology\":\"thin film\"");
        ParsedQuery plain = ParsedQuery.builder().originalText("10k").normalizedKey("10k").family("resistor").build();
        assertThat(mapper.writeValueAsString(ParsedQueryResponse.from(plain))).doesNotContain("technology");
    }

    @Test
    void lookupResponseCarriesTheReasonAndTheIdentityOfAnOutOfStockPart() {
        String outOfStock = mapper.writeValueAsString(PartLookupResponse.outOfStock(Distributor.MOUSER,
                "ERA6AEB5361V", CacheStatus.MISS, new PartLookupResponse.Identity("667-ERA-6AEB5361V", "Panasonic",
                        "ERA-6AEB5361V", "Thin Film Resistors - SMD 0805 5.36Kohm 0.1% 25ppm")));
        assertThat(outOfStock).contains("\"found\":false", "\"reason\":\"out_of_stock\"", "\"error\":null",
                "\"identity\":{\"part_number\":\"667-ERA-6AEB5361V\",\"manufacturer\":\"Panasonic\","
                        + "\"mpn\":\"ERA-6AEB5361V\",\"description\":", "\"part\":null");
        assertThat(outOfStock).doesNotContain("stock\":", "prices");

        String unknown = mapper.writeValueAsString(PartLookupResponse.notFound(Distributor.TME, "X", CacheStatus.MISS,
                null));
        assertThat(unknown).contains("\"reason\":\"not_found\"").doesNotContain("identity");
        String failed = mapper.writeValueAsString(PartLookupResponse.notFound(Distributor.TME, "X", null,
                "rate_limited"));
        assertThat(failed).contains("\"error\":\"rate_limited\"", "\"reason\":null");
    }

    @Test
    void distributorResultCarriesTheFallbackQuery() {
        DistributorResult result = DistributorResult.builder().distributor(Distributor.TME).totalResults(12).fetched(12)
                .returned(5).cache(CacheStatus.MISS).fallbackQuery("MOSFET 30V SOT-23").build();
        assertThat(mapper.writeValueAsString(result)).contains("\"fallback_query\":\"MOSFET 30V SOT-23\"");
    }

    @Test
    void distributorResultCarriesTheDistributorQuery() {
        DistributorResult result = DistributorResult.builder().distributor(Distributor.TME).totalResults(166).fetched(40)
                .returned(5).cache(CacheStatus.MISS).distributorQuery("pin strips female 6 angled").build();
        assertThat(mapper.writeValueAsString(result)).contains("\"distributor_query\":\"pin strips female 6 angled\"",
                "\"fallback_query\":null");
    }

    @Test
    void parsedConnectorOmitsUnknownAttributes() {
        ParsedQuery parsed = ParsedQuery.builder().originalText("USB-C receptacle").normalizedKey("usb-c receptacle")
                .family("connector").connector(ParsedQuery.Connector.builder().type("usb-c").gender("female").build())
                .build();
        assertThat(mapper.writeValueAsString(ParsedQueryResponse.from(parsed)))
                .isEqualTo("{\"family\":\"connector\",\"keywords\":[],\"connector\":{\"type\":\"usb-c\","
                        + "\"gender\":\"female\"}}");
    }

    @Test
    void distributorResultCarriesTheRateLimitWait() {
        DistributorResult result = DistributorResult.builder().distributor(Distributor.MOUSER).cache(CacheStatus.MISS)
                .error("rate_limited").rateLimitWaitedMs(84_000).build();
        assertThat(mapper.writeValueAsString(result)).contains("\"error\":\"rate_limited\"",
                "\"rate_limit_waited_ms\":84000");
    }

    @Test
    void searchRequestDefaults() {
        SearchRequest request = mapper.readValue("{\"query\":\"10k 0603\"}", SearchRequest.class);
        assertThat(request.maxResults()).isEqualTo(SearchRequest.DEFAULT_MAX_RESULTS);
        assertThat(request.distributors()).isEmpty();
        assertThat(request.bypassCache()).isFalse();

        BatchSearchRequest batch = mapper.readValue(
                "{\"queries\":[{\"query\":\"a\",\"max_results\":5}],\"distributors\":[\"TME\"],\"bypass_cache\":true}",
                BatchSearchRequest.class);
        assertThat(batch.expanded()).containsExactly(new SearchRequest("a", 5, Set.of(Distributor.TME), true));
    }

    @Test
    void partRoundTripsForCachePayload() {
        Part part = new Part(Distributor.TME, "CL21A106KOQNNNE", "SAMSUNG", "CL21A106KOQNNNE", "d", null, "0805", 10,
                null, null, List.of(new PriceBreak(1, new BigDecimal("0.1"), "EUR")), null, null, "https://p",
                Map.of("a", "b"), Map.of("unit", "pcs"), Instant.parse("2026-10-05T00:00:00Z"));
        assertThat(mapper.readValue(mapper.writeValueAsString(part), Part.class)).isEqualTo(part);
        assertThat(part.key()).isEqualTo("TME:CL21A106KOQNNNE");
    }

    @Test
    void rankingSummaryIsSnakeCaseAndKeepsNullFields() {
        DistributorStatusResponse.RankingSummary summary = new DistributorStatusResponse.RankingSummary("blended", true,
                true, "cross-encoder/ms-marco-MiniLM-L6-v2", "int8", "c5ee24cb16019beea0893ab7796b1df96625c6b8",
                "/data/cross-encoder", 4, 87.5, null, 40, 0.5, "PT5S");
        String json = mapper.writeValueAsString(summary);
        assertThat(json).contains("\"mode\":\"blended\"", "\"cross_encoder_enabled\":true", "\"ready\":true",
                "\"model_variant\":\"int8\"", "\"model_revision\":\"c5ee24cb16019beea0893ab7796b1df96625c6b8\"",
                "\"model_dir\":\"/data/cross-encoder\"", "\"threads\":4", "\"avg_latency_ms\":87.5",
                "\"last_error\":null", "\"max_candidates\":40", "\"weight\":0.5", "\"timeout\":\"PT5S\"");
    }
}
