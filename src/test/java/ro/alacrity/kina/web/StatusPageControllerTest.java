package ro.alacrity.kina.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import ro.alacrity.kina.distributor.ApiQuotaTracker;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.metrics.MetricsGauges;
import ro.alacrity.kina.security.DevModeIntegrationTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** The Status tab (DESIGN.md 6, "Web UI"). */
@DevModeIntegrationTest
class StatusPageControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MetricsGauges gauges;

    @Autowired
    ApiQuotaTracker quota;

    @Value("${spring.ai.mcp.server.version:dev}")
    String version;

    @Test
    void rendersEveryCardWithTheCacheByType() throws Exception {
        String key = "status-page-" + System.nanoTime();
        jdbc.sql("""
                        INSERT INTO cached_searches (distributor, query_key, part_numbers, fetched_at, type)
                        VALUES ('TME', ?, '[]'::jsonb, now(), 'resistor')""").param(key).update();
        try {
            gauges.refresh();
            MockHttpServletResponse response = mvc.perform(get("/status")).andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
            String page = response.getContentAsString();
            assertThat(page)
                    .contains("aria-current=\"page\" class=\"active\">Status</a>")
                    .contains("id=\"version\">" + version + "<")
                    .contains("<dd>dev</dd>")
                    .contains("http://localhost")
                    .contains("id=\"distributors\"", "id=\"ranking\"", "id=\"cache\"", "id=\"counters\"",
                            "id=\"users\"", "id=\"cache-by-distributor\"", "id=\"cache-by-type\"")
                    .contains("TME parts", "TME searches", "MOUSER parts")
                    // columns: TME parts, TME searches, MOUSER parts, MOUSER searches
                    .containsPattern("<td>resistor</td>\\s*<td class=\"num\">\\d[\\d,]*</td>\\s*<td class=\"num\">"
                            + "[1-9][\\d,]*</td>")
                    .contains("Tool calls", "Search queries by type", "Backfill runs", "Moved by the backfill")
                    .contains("id=\"backfill-last-run\"")
                    .contains("JLCPCB");
        } finally {
            jdbc.sql("DELETE FROM cached_searches WHERE query_key = ?").param(key).update();
        }
    }

    @Test
    void showsTheDistributorApiQuotaAsUsedOverLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            quota.record(Distributor.MOUSER);
        }
        quota.throttle(Distributor.TME, Duration.ofSeconds(90));

        String page = mvc.perform(get("/status")).andReturn().getResponse().getContentAsString();

        assertThat(page).contains("id=\"quota\"", "Distributor API quota", "sliding");
        assertThat(page).containsPattern("<td>MOUSER</td>\\s*<td class=\"num\">\\d+/30</td>\\s*"
                + "<td class=\"num\">\\d+/1000</td>");
        assertThat(page).containsPattern("<td>TME</td>\\s*<td class=\"num\">\\d+/30</td>\\s*"
                + "<td class=\"num\">\\d+/2000</td>\\s*<td>throttled until ");
    }

    @Test
    void durationsAreShort() {
        assertThat(StatusPageController.duration(Duration.ofSeconds(65))).isEqualTo("1m 5s");
        assertThat(StatusPageController.duration(Duration.ofMinutes(125))).isEqualTo("2h 5m");
        assertThat(StatusPageController.duration(Duration.ofHours(120))).isEqualTo("5d 0h 0m");
    }
}
