package ro.alacrity.kina.metrics;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.alacrity.kina.domain.MetricsSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/v1/metrics/summary} (DESIGN.md 3.7): the key counters plus every persisted counter and timer in
 * Prometheus naming, as JSON. Authenticated like the rest of {@code /api}; the Prometheus scrape endpoint is on the
 * management port.
 */
@RestController
@RequestMapping("/api/v1/metrics")
public class MetricsController {

    @Autowired private KinaMetrics metrics;

    /**
     * @param summary  the key counters (also in {@code list_distributors})
     * @param counters every counter and timer value in Prometheus naming, sorted by name and tags
     */
    public record SummaryResponse(@JsonProperty("summary") MetricsSummary summary,
                                  @JsonProperty("counters") List<CounterValue> counters) {
    }

    /**
     * One series: {@code kina_searches_total}, or {@code kina_search_duration_seconds_count} and {@code _sum} for a
     * timer (the sum in seconds).
     */
    public record CounterValue(@JsonProperty("name") String name, @JsonProperty("tags") Map<String, String> tags,
                               @JsonProperty("value") double value) {
    }

    @GetMapping("/summary")
    public SummaryResponse summary() {
        List<CounterValue> counters = new ArrayList<>();
        metrics.store().snapshot().forEach((key, value) -> {
            String name = key.name();
            if (name.endsWith(MetricsStore.COUNT)) {
                counters.add(new CounterValue(Metric.prometheusName(base(name), true) + "_count", key.tagMap(),
                        value));
            } else if (name.endsWith(MetricsStore.NANOS)) {
                counters.add(new CounterValue(Metric.prometheusName(base(name), true) + "_sum", key.tagMap(),
                        value / 1e9));
            } else {
                counters.add(new CounterValue(Metric.prometheusName(name, false), key.tagMap(), value));
            }
        });
        return new SummaryResponse(metrics.summary(), counters);
    }

    private static String base(String name) {
        return name.substring(0, name.lastIndexOf(':'));
    }
}
