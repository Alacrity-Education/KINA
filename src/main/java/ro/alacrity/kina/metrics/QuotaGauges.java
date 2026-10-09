package ro.alacrity.kina.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.distributor.ApiQuotaTracker;
import ro.alacrity.kina.distributor.ApiQuotaTracker.Window;
import ro.alacrity.kina.domain.Distributor;

import java.time.Instant;

import static ro.alacrity.kina.metrics.Metric.DISTRIBUTOR_QUOTA_LIMIT;
import static ro.alacrity.kina.metrics.Metric.DISTRIBUTOR_QUOTA_THROTTLED_UNTIL;
import static ro.alacrity.kina.metrics.Metric.DISTRIBUTOR_QUOTA_USED;

/**
 * The API quota gauges (DESIGN.md 3.7): per tracked distributor the requests used in each window, the window's limit
 * (its own series, so a dashboard can divide or alert) and the end of the latest observed rate limit. Computed from
 * the in-memory {@link ApiQuotaTracker} on every scrape; never stored in the {@link MetricsStore}, so nothing is
 * persisted or restored.
 */
@Component
public class QuotaGauges {

    @Autowired private ApiQuotaTracker tracker;
    @Autowired private MeterRegistry registry;

    @PostConstruct
    void registerGauges() {
        for (Distributor d : ApiQuotaTracker.TRACKED) {
            for (Window w : Window.values()) {
                Gauge.builder(DISTRIBUTOR_QUOTA_USED.meterName(), tracker, t -> t.used(d, w))
                        .description(DISTRIBUTOR_QUOTA_USED.help())
                        .tags(DISTRIBUTOR_QUOTA_USED.key(d.name(), w.label()).micrometerTags())
                        .register(registry);
                Gauge.builder(DISTRIBUTOR_QUOTA_LIMIT.meterName(), tracker, t -> t.limit(d, w))
                        .description(DISTRIBUTOR_QUOTA_LIMIT.help())
                        .tags(DISTRIBUTOR_QUOTA_LIMIT.key(d.name(), w.label()).micrometerTags())
                        .register(registry);
            }
            Gauge.builder(DISTRIBUTOR_QUOTA_THROTTLED_UNTIL.meterName(), tracker, t -> epochSeconds(t.throttledUntil(d)))
                    .description(DISTRIBUTOR_QUOTA_THROTTLED_UNTIL.help())
                    .baseUnit(DISTRIBUTOR_QUOTA_THROTTLED_UNTIL.baseUnit())
                    .tags(DISTRIBUTOR_QUOTA_THROTTLED_UNTIL.key(d.name()).micrometerTags())
                    .register(registry);
        }
    }

    private static double epochSeconds(Instant until) {
        return until == null ? 0 : until.getEpochSecond();
    }
}
