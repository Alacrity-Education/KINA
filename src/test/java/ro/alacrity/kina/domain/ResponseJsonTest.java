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
        SearchResponse response = new SearchResponse("10uF X7R 0805", ParsedQueryResponse.from(parsed), RankingMode.LAYA,
                null, List.of(new DistributorResult(Distributor.MOUSER, 113, 50, 1, CacheStatus.HIT, null,
                List.of(PartResponse.from(part, 1, 0.93)))));

        String json = mapper.writeValueAsString(response);

        assertThat(json).contains("\"ranking\":\"laya\"", "\"ranking_note\":null", "\"total_results\":113",
                "\"cache\":\"hit\"", "\"part_number\":\"603-CC0805\"", "\"mpn\":\"CC0805MKX7R7BB106\"",
                "\"package\":\"0805\"", "\"min_order_qty\":1", "\"order_multiple\":1", "\"datasheet_url\":",
                "\"photo_url\":", "\"product_url\":", "\"distributor\":\"MOUSER\"",
                "\"parsed\":{\"family\":\"capacitor\"", "\"capacitance\":\"10uF\"", "\"dielectric\":\"X7R\"",
                "\"keywords\":[]", "{\"qty\":1,\"unit_price\":1.40,\"currency\":\"EUR\"}",
                "\"fallback_query\":null", "\"rate_limit_waited_ms\":0");
        assertThat(json).doesNotContain("\"qty\":100", "mounting", "constraints", "packageName");
    }

    @Test
    void distributorResultCarriesTheFallbackQuery() {
        DistributorResult result = new DistributorResult(Distributor.TME, 12, 12, 5, CacheStatus.MISS, null, List.of(),
                "MOSFET 30V SOT-23");
        assertThat(mapper.writeValueAsString(result)).contains("\"fallback_query\":\"MOSFET 30V SOT-23\"");
    }

    @Test
    void distributorResultCarriesTheRateLimitWait() {
        DistributorResult result = new DistributorResult(Distributor.MOUSER, null, 0, 0, CacheStatus.MISS,
                "rate_limited", List.of(), null, 84_000);
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
}
