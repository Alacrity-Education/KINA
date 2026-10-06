package ro.alacrity.kina.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import ro.alacrity.kina.TestcontainersConfiguration;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.distributor.DistributorRegistry;
import ro.alacrity.kina.distributor.DistributorSearchPage;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PriceBreak;
import ro.alacrity.kina.metrics.KinaMetrics;
import ro.alacrity.kina.metrics.MetricKey;
import ro.alacrity.kina.metrics.MetricNames;
import ro.alacrity.kina.metrics.MetricsStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MCP tools end to end through {@code POST /mcp} (stateless Streamable HTTP) with Testcontainers Postgres and a
 * fake Mouser client registered in place of the real distributor registry.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class McpToolsIntegrationTest {

    static final FakeMouser MOUSER = new FakeMouser();

    @TestBean
    DistributorRegistry distributorRegistry;

    static DistributorRegistry distributorRegistry() {
        return new DistributorRegistry(List.of(MOUSER));
    }

    @Autowired
    RestTestClient client;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    KinaMetrics metrics;

    final JsonMapper json = JsonMapper.builder().build();

    /** 60 MLCCs, every third one without ships-now stock (dropped like the real client does). */
    static final class FakeMouser implements DistributorClient {
        final AtomicInteger searches = new AtomicInteger();
        final List<Part> raw = new ArrayList<>();

        FakeMouser() {
            for (int i = 0; i < 60; i++) {
                raw.add(i % 3 == 2 ? null : new Part(Distributor.MOUSER, "603-CC0805KRX7R" + i, "YAGEO",
                        "CC0805KRX7R" + i, (i == 4 ? "MLCC 10uF 25V X7R 0805 10%" : "MLCC 100nF 50V X7R 0805 10%"),
                        "Ceramic Capacitors", null, 1000 + i, 1, 1,
                        List.of(new PriceBreak(1, new BigDecimal("0.10"), "EUR"),
                                new PriceBreak(10, new BigDecimal("0.05"), "EUR"),
                                new PriceBreak(100, new BigDecimal("0.03"), "EUR"),
                                new PriceBreak(1000, new BigDecimal("0.01"), "EUR")),
                        "https://example.invalid/ds.pdf", "https://example.invalid/p.jpg",
                        "https://example.invalid/p", Map.of(), Map.of("rohs", "RoHS Compliant"), Instant.now()));
            }
        }

        @Override
        public Distributor distributor() {
            return Distributor.MOUSER;
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public int maxPageSize() {
            return 50;
        }

        @Override
        public DistributorSearchPage search(String query, int offset, int limit) {
            searches.incrementAndGet();
            if (query.contains("ratelimit")) {
                throw new DistributorException(Distributor.MOUSER, DistributorException.Kind.RATE_LIMITED, "429");
            }
            int to = Math.min(raw.size(), offset + limit);
            List<Part> parts = offset >= raw.size() ? List.of()
                    : raw.subList(offset, to).stream().filter(p -> p != null).toList();
            return new DistributorSearchPage(parts, raw.size(), to < raw.size());
        }

        @Override
        public Optional<Part> getPart(String partNumber) {
            return raw.stream().filter(p -> p != null && p.distributorPartNumber().equals(partNumber)).findFirst();
        }
    }

    JsonNode call(String tool, String arguments) {
        String body = client.post().uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .body("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                        + "\",\"arguments\":" + arguments + "}}")
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody();
        JsonNode rpc = json.readTree(body);
        assertThat(rpc.path("error").isMissingNode()).as(body).isTrue();
        JsonNode result = rpc.path("result");
        assertThat(result.path("isError").asBoolean(false)).as(body).isFalse();
        return json.readTree(result.path("content").get(0).path("text").asString());
    }

    @Test
    void searchPartsThroughMcpCachesInPostgresAndServesTheRepeatFromCache() {
        int before = MOUSER.searches.get();
        JsonNode first = call("search_parts",
                "{\"query\":\"10uF X7R 0805\",\"max_results\":5,\"distributors\":[\"mouser\"]}");

        assertThat(first.path("query").asString()).isEqualTo("10uF X7R 0805");
        assertThat(first.path("parsed").path("dielectric").asString()).isEqualTo("X7R");
        assertThat(first.path("ranking").asString()).isIn("blended", "fallback");
        JsonNode mouser = first.path("distributors").get(0);
        assertThat(mouser.path("distributor").asString()).isEqualTo("MOUSER");
        assertThat(mouser.path("cache").asString()).isEqualTo("miss");
        assertThat(mouser.path("total_results").asInt()).isEqualTo(60);
        assertThat(mouser.path("fetched").asInt()).isEqualTo(34);   // 50 raw records, every third without stock
        assertThat(mouser.path("returned").asInt()).isEqualTo(5);
        assertThat(mouser.path("error").isNull()).isTrue();
        assertThat(mouser.path("distributor_query").isNull()).isTrue();   // sent verbatim
        assertThat(first.path("parsed").path("connector").isMissingNode()).isTrue();
        JsonNode top = mouser.path("parts").get(0);
        assertThat(top.path("rank").asInt()).isEqualTo(1);
        assertThat(top.path("part_number").asString()).isEqualTo("603-CC0805KRX7R4");   // the only 10uF part
        assertThat(top.path("prices")).hasSize(3);
        assertThat(top.path("attributes").path("Capacitance").asString()).isEqualTo("10uF");
        // detail "compact" (default): no photo, no raw extra; availability is always there
        assertThat(top.path("photo_url").isMissingNode()).isTrue();
        assertThat(top.path("extra").isMissingNode()).isTrue();
        assertThat(top.path("availability").path("status").asString()).isEqualTo("in_stock");
        assertThat(MOUSER.searches.get() - before).isEqualTo(1);

        Integer cachedRows = jdbc.sql("SELECT count(*) FROM cached_parts WHERE distributor = 'MOUSER'")
                .query(Integer.class).single();
        assertThat(cachedRows).isGreaterThanOrEqualTo(34);
        Integer nextOffset = jdbc.sql("SELECT next_offset FROM cached_searches WHERE query_key = '10uf x7r 0805'")
                .query(Integer.class).single();
        assertThat(nextOffset).isEqualTo(50);

        JsonNode again = call("search_parts", "{\"query\":\"10uF  x7r 0805\",\"max_results\":20}");
        JsonNode cached = again.path("distributors").get(0);
        assertThat(cached.path("cache").asString()).isEqualTo("hit");
        assertThat(cached.path("returned").asInt()).isEqualTo(20);
        assertThat(MOUSER.searches.get() - before).isEqualTo(1);
    }

    @Test
    void connectorQueriesReportTheDistributorPhraseAndTheParsedConnector() {
        JsonNode response = call("search_parts", "{\"query\":\"female header 1x6 right angle 2.54mm\","
                + "\"distributors\":[\"mouser\"],\"bypass_cache\":true}");

        JsonNode connector = response.path("parsed").path("connector");
        assertThat(response.path("parsed").path("family").asString()).isEqualTo("connector");
        assertThat(connector.path("type").asString()).isEqualTo("female header");
        assertThat(connector.path("gender").asString()).isEqualTo("female");
        assertThat(connector.path("positions").asInt()).isEqualTo(6);
        assertThat(connector.path("rows").asInt()).isEqualTo(1);
        assertThat(connector.path("pitch").asString()).isEqualTo("2.54mm");
        assertThat(connector.path("orientation").asString()).isEqualTo("right angle");
        JsonNode mouser = response.path("distributors").get(0);
        assertThat(mouser.path("distributor_query").asString())
                .isEqualTo("female header 6 pos 2.54mm right angle");
        assertThat(mouser.path("fallback_query").isNull()).isTrue();
    }

    @Test
    void distributorErrorsAndUnconfiguredDistributorsAreReportedNotThrown() {
        JsonNode response = call("search_parts",
                "{\"query\":\"ratelimit 1k\",\"distributors\":[\"MOUSER\",\"TME\"],\"bypass_cache\":true}");

        JsonNode distributors = response.path("distributors");
        assertThat(distributors).hasSize(2);
        assertThat(distributors.get(0).path("distributor").asString()).isEqualTo("TME");
        assertThat(distributors.get(0).path("error").asString()).isEqualTo("not_configured");
        assertThat(distributors.get(1).path("error").asString()).isEqualTo("rate_limited");
        assertThat(distributors.get(1).path("cache").asString()).isEqualTo("bypassed");
        assertThat(distributors.get(1).path("parts")).isEmpty();
    }

    @Test
    void batchGetPartAndListDistributors() {
        JsonNode batch = call("search_parts_batch",
                "{\"queries\":[{\"query\":\"100nF X7R 0805\",\"max_results\":2},{\"query\":\"10uF 0805\"}]}");
        assertThat(batch.path("results")).hasSize(2);
        assertThat(batch.path("results").get(0).path("distributors").get(0).path("returned").asInt()).isEqualTo(2);
        assertThat(batch.path("results").get(1).path("distributors").get(0).path("returned").asInt()).isEqualTo(10);

        JsonNode found = call("get_part", "{\"distributor\":\"mouser\",\"part_number\":\"603-CC0805KRX7R1\"}");
        assertThat(found.path("found").asBoolean()).isTrue();
        assertThat(found.path("part").path("mpn").asString()).isEqualTo("CC0805KRX7R1");
        assertThat(found.path("part").path("prices")).hasSize(3);

        JsonNode missing = call("get_part", "{\"distributor\":\"MOUSER\",\"part_number\":\"nope\",\"bypass_cache\":true}");
        assertThat(missing.path("found").asBoolean()).isFalse();
        assertThat(missing.path("part").isNull()).isTrue();

        JsonNode unconfigured = call("get_part", "{\"distributor\":\"tme\",\"part_number\":\"X\"}");
        assertThat(unconfigured.path("found").asBoolean()).isFalse();
        assertThat(unconfigured.path("error").asString()).isEqualTo("not_configured");

        JsonNode list = call("list_distributors", "{}");
        assertThat(list.path("distributors")).hasSize(3);
        assertThat(list.path("distributors").get(0).path("distributor").asString()).isEqualTo("LCSC");
        assertThat(list.path("distributors").get(2).path("configured").asBoolean()).isTrue();
        assertThat(list.path("distributors").get(1).path("configured").asBoolean()).isFalse();
        assertThat(list.path("cache").path("parts").asLong()).isPositive();
        assertThat(list.path("ranking").path("model").asString()).isEqualTo("cross-encoder/ms-marco-MiniLM-L6-v2");
        // Spring tests never download the model (src/test/resources/config/application.yml)
        assertThat(list.path("ranking").path("ready").asBoolean()).isFalse();
        assertThat(list.path("ranking").path("mode").asString()).isEqualTo("fallback");
        assertThat(list.path("ranking").path("cross_encoder_enabled").asBoolean()).isTrue();
    }

    @Test
    void toolCallsAndSearchesAreCounted() {
        MetricsStore store = metrics.store();
        long searches = store.sum(MetricNames.SEARCHES);
        long queries = store.sum(MetricNames.SEARCH_QUERIES);
        long toolCalls = store.get(MetricKey.of(MetricNames.TOOL_CALLS, "tool", "search_parts"));
        long batchCalls = store.get(MetricKey.of(MetricNames.TOOL_CALLS, "tool", "search_parts_batch"));
        long mouserOk = store.get(
                MetricKey.of(MetricNames.DISTRIBUTOR_CALLS, "distributor", "MOUSER", "outcome", "ok"));
        long rateLimited = store.get(
                MetricKey.of(MetricNames.DISTRIBUTOR_CALLS, "distributor", "MOUSER", "outcome", "rate_limited"));
        long returned = store.get(MetricKey.of(MetricNames.PARTS_RETURNED, "distributor", "MOUSER"));

        call("search_parts", "{\"query\":\"100nF X7R 0805 metrics\",\"max_results\":3,\"distributors\":[\"mouser\"]}");
        call("search_parts", "{\"query\":\"ratelimit metrics\",\"distributors\":[\"mouser\"]}");
        call("search_parts_batch", "{\"queries\":[{\"query\":\"100nF 0805 a\"},{\"query\":\"100nF 0805 b\"}],"
                + "\"distributors\":[\"mouser\"]}");

        assertThat(store.sum(MetricNames.SEARCHES)).isEqualTo(searches + 3);
        assertThat(store.sum(MetricNames.SEARCH_QUERIES)).isEqualTo(queries + 4);
        assertThat(store.get(MetricKey.of(MetricNames.TOOL_CALLS, "tool", "search_parts"))).isEqualTo(toolCalls + 2);
        assertThat(store.get(MetricKey.of(MetricNames.TOOL_CALLS, "tool", "search_parts_batch")))
                .isEqualTo(batchCalls + 1);
        assertThat(store.get(MetricKey.of(MetricNames.DISTRIBUTOR_CALLS, "distributor", "MOUSER", "outcome", "ok")))
                .isEqualTo(mouserOk + 3);
        assertThat(store.get(MetricKey.of(MetricNames.DISTRIBUTOR_CALLS, "distributor", "MOUSER",
                "outcome", "rate_limited"))).isEqualTo(rateLimited + 1);
        assertThat(store.get(MetricKey.of(MetricNames.PARTS_RETURNED, "distributor", "MOUSER")))
                .isGreaterThanOrEqualTo(returned + 3);

        JsonNode list = call("list_distributors", "{}");
        assertThat(list.path("metrics").path("searches").asLong()).isGreaterThanOrEqualTo(searches + 3);
        assertThat(list.path("metrics").path("tool_calls").path("search_parts").asLong())
                .isGreaterThanOrEqualTo(toolCalls + 2);
        assertThat(list.path("metrics").has("cache_added")).isTrue();
        assertThat(list.path("metrics").has("rate_limited_calls")).isTrue();
        assertThat(list.path("metrics").path("cross_encoder_executions").asLong()).isZero();

        client.get().uri("/api/v1/distributors").exchange().expectStatus().isOk();
        String summary = client.get().uri("/api/v1/metrics/summary").exchange()
                .expectStatus().isOk().returnResult(String.class).getResponseBody();
        JsonNode summaryJson = json.readTree(summary);
        assertThat(summaryJson.path("summary").path("searches").asLong()).isGreaterThanOrEqualTo(searches + 3);
        List<String> names = new ArrayList<>();
        summaryJson.path("counters").forEach(c -> names.add(c.path("name").asString()));
        assertThat(names).contains("kina_searches_total", "kina_tool_calls_total", "kina_search_duration_seconds_count",
                "kina_search_duration_seconds_sum", "kina_api_requests_total");
    }

    @Test
    void toolListDescribesTheSearchParameters() {
        String body = client.post().uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody();
        JsonNode tools = json.readTree(body).path("result").path("tools");
        List<String> names = new ArrayList<>();
        tools.forEach(t -> names.add(t.path("name").asString()));
        assertThat(names).contains("ping", "search_parts", "search_parts_batch", "get_part", "list_distributors");
        JsonNode search = null;
        for (JsonNode t : tools) {
            if (t.path("name").asString().equals("search_parts")) {
                search = t;
            }
        }
        assertThat(search).isNotNull();
        JsonNode properties = search.path("inputSchema").path("properties");
        assertThat(properties.has("query")).isTrue();
        assertThat(properties.has("max_results")).isTrue();
        assertThat(properties.has("bypass_cache")).isTrue();
        assertThat(search.path("inputSchema").path("required").toString()).contains("query")
                .doesNotContain("max_results");
        assertThat(properties.path("max_results").path("description").asString()).contains("PER DISTRIBUTOR");
        assertThat(search.path("description").asString()).contains("up to 2 minutes", "rate_limit_waited_ms");
    }
}
