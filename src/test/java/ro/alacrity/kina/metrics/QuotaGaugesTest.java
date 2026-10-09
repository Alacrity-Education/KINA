package ro.alacrity.kina.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import ro.alacrity.kina.TestWiring;
import ro.alacrity.kina.distributor.ApiQuotaTracker;
import ro.alacrity.kina.domain.Distributor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** The quota gauges (DESIGN.md 3.7): computed on read, the limit a series of its own, nothing in the store. */
class QuotaGaugesTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final MetricsStore store = TestWiring.metricsStore(registry);
    private final ApiQuotaTracker tracker = TestWiring.wire(new ApiQuotaTracker(), "properties",
            TestWiring.properties(), "clock", Clock.fixed(NOW, ZoneOffset.UTC));

    private double gauge(Metric metric, String... values) {
        return registry.get(metric.meterName()).tags(metric.key(values).micrometerTags()).gauge().value();
    }

    @Test
    void usedLimitAndThrottleAreGaugesPerDistributorAndWindow() {
        TestWiring.wire(new QuotaGauges(), "tracker", tracker, "registry", registry);
        for (int i = 0; i < 15; i++) {
            tracker.record(Distributor.MOUSER);
        }
        tracker.record(Distributor.TME);

        assertThat(gauge(Metric.DISTRIBUTOR_QUOTA_USED, "MOUSER", "minute")).isEqualTo(15);
        assertThat(gauge(Metric.DISTRIBUTOR_QUOTA_USED, "MOUSER", "day")).isEqualTo(15);
        assertThat(gauge(Metric.DISTRIBUTOR_QUOTA_USED, "TME", "day")).isEqualTo(1);
        // the maxima are separate series
        assertThat(gauge(Metric.DISTRIBUTOR_QUOTA_LIMIT, "MOUSER", "minute")).isEqualTo(30);
        assertThat(gauge(Metric.DISTRIBUTOR_QUOTA_LIMIT, "MOUSER", "day")).isEqualTo(1000);
        assertThat(gauge(Metric.DISTRIBUTOR_QUOTA_LIMIT, "TME", "day")).isEqualTo(2000);
        assertThat(gauge(Metric.DISTRIBUTOR_QUOTA_THROTTLED_UNTIL, "MOUSER")).isZero();

        tracker.throttle(Distributor.MOUSER, Duration.ofSeconds(30));
        assertThat(gauge(Metric.DISTRIBUTOR_QUOTA_THROTTLED_UNTIL, "MOUSER")).isEqualTo(NOW.getEpochSecond() + 30);
        assertThat(registry.find("kina.distributor.quota.used").tag("distributor", "LCSC").gauge()).isNull();
        assertThat(Metric.DISTRIBUTOR_QUOTA_THROTTLED_UNTIL.prometheusName())
                .isEqualTo("kina_distributor_quota_throttled_until_seconds");
        assertThat(Metric.DISTRIBUTOR_QUOTA_USED.prometheusName()).isEqualTo("kina_distributor_quota_used");
        assertThat(Metric.DISTRIBUTOR_QUOTA_LIMIT.prometheusName()).isEqualTo("kina_distributor_quota_limit");
    }

    @Test
    void nothingOfItIsInTheStoreSoNothingIsPersisted() {
        TestWiring.wire(new QuotaGauges(), "tracker", tracker, "registry", registry);
        tracker.record(Distributor.MOUSER);
        tracker.throttle(Distributor.MOUSER, Duration.ofSeconds(30));

        assertThat(store.snapshot().keySet()).noneMatch(key -> key.name().startsWith("kina.distributor.quota"));
        assertThat(Metric.DISTRIBUTOR_QUOTA_USED.type()).isEqualTo(Metric.Type.GAUGE);
        assertThat(Metric.DISTRIBUTOR_QUOTA_LIMIT.type()).isEqualTo(Metric.Type.GAUGE);
        assertThat(Metric.DISTRIBUTOR_QUOTA_THROTTLED_UNTIL.type()).isEqualTo(Metric.Type.GAUGE);
    }
}
