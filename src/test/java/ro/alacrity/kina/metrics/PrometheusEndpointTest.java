package ro.alacrity.kina.metrics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import ro.alacrity.kina.TestcontainersConfiguration;
import ro.alacrity.kina.domain.Distributor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

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
        metrics.distributorPage(Distributor.LCSC, 1_000_000, 3);
        metrics.stockRefreshed(Distributor.MOUSER, "out_of_stock", 2);
        assertThat(managementPort).isPositive().isNotEqualTo(serverPort);

        HttpResponse<String> scrape = get(managementPort, "/actuator/prometheus");

        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/plain"));
        assertThat(scrape.body())
                .contains("kina_tool_calls_total{tool=\"ping\"}")
                .contains("kina_parts_fetched_total{distributor=\"LCSC\"}")
                .contains("kina_distributor_duration_seconds_count{distributor=\"LCSC\"}")
                .contains("kina_cache_parts{distributor=\"MOUSER\"}")
                .contains("kina_cache_parts_stale{distributor=\"TME\"}")
                .contains("kina_cache_parts_stale_stock{distributor=\"MOUSER\"}")
                .contains("kina_cache_stock_refreshes_total{distributor=\"MOUSER\",outcome=\"out_of_stock\"}")
                .contains("kina_users_known ")
                .contains("kina_tokens_active ")
                .contains("kina_jlcpcb_database_age_seconds ")
                .contains("# HELP kina_tool_calls_total MCP tool calls");
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
