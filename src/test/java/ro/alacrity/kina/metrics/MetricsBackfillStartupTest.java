package ro.alacrity.kina.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import ro.alacrity.kina.TestcontainersConfiguration;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With the backfill enabled, a database it never ran against gets one run right after startup (DESIGN.md 3.7
 * "Backfill"); once the completion marker exists, startup does not run it again.
 */
@SpringBootTest(properties = "kina.metrics.backfill.enabled=true")
@Import(TestcontainersConfiguration.class)
class MetricsBackfillStartupTest {

    @Autowired MetricsBackfill backfill;
    @Autowired KinaMetrics metrics;
    @Autowired JdbcClient jdbc;
    @Autowired MeterRegistry registry;

    @Test
    void runsOnceAtTheFirstStartOnly() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (backfill.neverCompleted() && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
        }
        assertThat(backfill.neverCompleted()).as("the startup run completed").isFalse();
        assertThat(metrics.store().get(Metric.METRICS_BACKFILL_RUNS.key("ok"))).isEqualTo(1);
        assertThat(registry.get(Metric.METRICS_BACKFILL_LAST_RUN.meterName()).gauge().value()).isPositive();

        assertThat(backfill.startWhenNeverRun()).as("the marker exists").isFalse();
        assertThat(jdbc.sql("SELECT attributed FROM metrics_backfill WHERE name = ?").param(MetricsBackfill.COMPLETED)
                .query(Long.class).single()).isEqualTo(1);
    }
}
