package ro.alacrity.kina.metrics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import ro.alacrity.kina.TestcontainersConfiguration;
import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.DistributorResult;
import ro.alacrity.kina.domain.ParsedQueryResponse;
import ro.alacrity.kina.domain.RankingMode;
import ro.alacrity.kina.domain.SearchResponse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production mode: the Prometheus endpoint answers on the management port without any credentials, and the main port
 * does not serve it (DESIGN.md 3.7).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "management.server.port=0",
        "kina.security.mode=prod",
        "kina.security.oidc.issuer-uri=http://127.0.0.1:9/fake-issuer",
        "kina.security.oidc.client-id=kina-test",
        "kina.security.oidc.client-secret=not-a-real-secret"})
class PrometheusEndpointTest {

    @LocalServerPort
    int serverPort;

    @LocalManagementPort
    int managementPort;

    @Autowired
    KinaMetrics metrics;

    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void scrapeWorksOnTheManagementPortWithoutAuthentication() throws Exception {
        metrics.toolCall("ping", () -> "ok");
        metrics.distributorPage(Distributor.LCSC, "resistor", 1_000_000, 3);
        metrics.searchCompleted(new SearchResponse("10k 0603 resistor", new ParsedQueryResponse("resistor", Map.of(),
                null, "0603", null, List.of()), RankingMode.BLENDED, null, List.of(DistributorResult.builder()
                .distributor(Distributor.MOUSER).cache(CacheStatus.MISS).returned(2).fetched(2).build())), 1_000_000);
        metrics.searchCompleted(new SearchResponse("asdf", null, RankingMode.BLENDED, null, List.of()), 1_000_000);
        metrics.stockRefreshed(Distributor.MOUSER, "out_of_stock", 2);
        assertThat(managementPort).isPositive().isNotEqualTo(serverPort);

        HttpResponse<String> scrape = get(managementPort, "/actuator/prometheus");

        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/plain"));
        assertThat(scrape.body())
                .contains("kina_tool_calls_total{tool=\"ping\"}")
                .contains("kina_parts_fetched_total{distributor=\"LCSC\",type=\"resistor\"}")
                .contains("kina_search_queries_total{type=\"resistor\"}")
                .contains("kina_search_queries_total{type=\"unknown\"}")
                .contains("kina_distributor_calls_total{distributor=\"MOUSER\",outcome=\"ok\",type=\"resistor\"}")
                .contains("kina_parts_returned_total{distributor=\"MOUSER\",type=\"resistor\"}")
                .contains("kina_cache_search_lookups_total{distributor=\"MOUSER\",status=\"miss\",type=\"resistor\"}")
                .contains("kina_distributor_duration_seconds_count{distributor=\"LCSC\"}")
                .contains("kina_cache_parts{distributor=\"MOUSER\"}")
                .contains("kina_cache_parts_stale{distributor=\"TME\"}")
                .contains("kina_cache_parts_stale_stock{distributor=\"MOUSER\"}")
                .contains("kina_cache_stock_refreshes_total{distributor=\"MOUSER\",outcome=\"out_of_stock\"}")
                .contains("kina_users_known ")
                .contains("kina_tokens_active ")
                .contains("kina_jlcpcb_database_age_seconds ")
                .contains("# HELP kina_tool_calls_total MCP tool calls");
        // every sample of the five search counters carries the component type (DESIGN.md 3.7)
        assertThat(scrape.body().lines().filter(line -> line.startsWith("kina_search_queries_total")
                || line.startsWith("kina_distributor_calls_total") || line.startsWith("kina_parts_returned_total")
                || line.startsWith("kina_parts_fetched_total") || line.startsWith("kina_cache_search_lookups_total")))
                .hasSizeGreaterThanOrEqualTo(6)
                .allSatisfy(line -> assertThat(line).contains("type=\""));
        assertThat(get(managementPort, "/actuator/health").statusCode()).isEqualTo(200);
    }

    @Test
    void theMainPortDoesNotServeActuator() throws Exception {
        HttpResponse<String> scrape = get(serverPort, "/actuator/prometheus");

        assertThat(scrape.statusCode()).isNotEqualTo(200);
        assertThat(scrape.body()).doesNotContain("kina_");
        // health moved to the management port as well
        assertThat(get(serverPort, "/actuator/health").statusCode()).isNotEqualTo(200);
    }
}
