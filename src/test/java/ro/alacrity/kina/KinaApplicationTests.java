package ro.alacrity.kina;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.DistributorRegistry;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class KinaApplicationTests {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    KinaProperties properties;

    @Autowired
    DistributorRegistry registry;

    @Autowired
    RestTestClient client;

    @Test
    void contextLoadsAndFlywayMigrated() {
        Integer tables = jdbc.sql("""
                        SELECT count(*) FROM information_schema.tables
                        WHERE table_schema = 'public' AND table_name IN
                          ('users','access_tokens','oauth_clients','oauth_authorization_codes',
                           'oauth_refresh_tokens','cached_parts','cached_searches','jlcpcb_database')""")
                .query(Integer.class).single();
        assertThat(tables).isEqualTo(8);
        assertThat(properties.cache().ttl()).isEqualTo(Duration.ofDays(5));
        assertThat(properties.ranking().laya().maxConcurrentRequests()).isEqualTo(1);
        assertThat(properties.distributors().tme().maxResultsPerSearch()).isEqualTo(60);
        assertThat(registry).isNotNull();
    }

    @Test
    void mcpToolsListContainsPing() {
        String body = client.post().uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody();
        assertThat(body).contains("\"name\":\"ping\"");
    }

    @Test
    void mcpPingToolCall() {
        String body = client.post().uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .body("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"ping\",\"arguments\":{}}}")
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody();
        assertThat(body).contains("\\\"status\\\":\\\"ok\\\"");
    }
}
