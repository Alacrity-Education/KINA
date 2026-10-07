package ro.alacrity.kina.metrics;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import ro.alacrity.kina.TestWiring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetricsStoreTest {

    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final MetricsStore store = TestWiring.metricsStore(registry);

    @Test
    void canonicalTagsDoNotDependOnOrder() {
        MetricKey a = MetricKey.of("kina.distributor.calls", "outcome", "ok", "distributor", "MOUSER");
        MetricKey b = MetricKey.of("kina.distributor.calls", "distributor", "MOUSER", "outcome", "ok");

        assertThat(a).isEqualTo(b);
        assertThat(a.tags()).isEqualTo("distributor=MOUSER,outcome=ok");
        assertThat(a.tagMap()).containsExactly(Map.entry("distributor", "MOUSER"), Map.entry("outcome", "ok"));
        assertThat(MetricKey.of("kina.searches").tags()).isEmpty();
    }

    @Test
    void tagValuesAreSanitised() {
        MetricKey key = MetricKey.of("x", "endpoint", "/a,b=c", "reason", null);

        assertThat(key.tags()).isEqualTo("endpoint=/a_b_c,reason=none");
        assertThatThrownBy(() -> MetricKey.of("x", "odd")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void incrementAndAddAreExportedAsFunctionCounters() {
        MetricKey key = MetricKey.of("kina.tool.calls", "tool", "ping");
        store.increment(key);
        store.increment(key);
        store.add(key, 3);
        store.add(key, -5); // ignored
        store.add(key, 0);

        assertThat(store.get(key)).isEqualTo(5);
        FunctionCounter counter = registry.get("kina.tool.calls").tag("tool", "ping").functionCounter();
        assertThat(counter.count()).isEqualTo(5.0);
        assertThat(counter.getId().getDescription()).isEqualTo("MCP tool calls");
    }

    @Test
    void timersKeepCountAndNanosAsTwoKeys() {
        MetricKey key = MetricKey.of("kina.search.duration");
        store.record(key, 1_500_000_000L);
        store.record(key, 500_000_000L);

        assertThat(store.get(key.withName("kina.search.duration:count"))).isEqualTo(2);
        assertThat(store.get(key.withName("kina.search.duration:nanos"))).isEqualTo(2_000_000_000L);
        FunctionTimer timer = registry.get("kina.search.duration").functionTimer();
        assertThat(timer.count()).isEqualTo(2.0);
        assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(2.0);
    }

    @Test
    void snapshotIsSortedAndSumsGroupByTag() {
        store.increment(MetricKey.of("kina.tool.calls", "tool", "search_parts"));
        store.add(MetricKey.of("kina.tool.calls", "tool", "get_part"), 2);
        store.increment(MetricKey.of("kina.searches"));

        assertThat(store.snapshot().keySet()).extracting(MetricKey::toString).containsExactly(
                "kina.searches", "kina.tool.calls{tool=get_part}", "kina.tool.calls{tool=search_parts}");
        assertThat(store.sum("kina.tool.calls")).isEqualTo(3);
        assertThat(store.sumBy("kina.tool.calls", "tool")).containsExactly(Map.entry("get_part", 2L),
                Map.entry("search_parts", 1L));
        assertThat(store.sum("kina.unknown")).isZero();
    }

    @Test
    void restoreAddsStoredValuesAndRegistersTheirMeters() {
        MetricKey calls = MetricKey.of("kina.tool.calls", "tool", "ping");
        store.increment(calls); // counted in this run before the restore

        store.restore(Map.of(calls, 10L,
                new MetricKey("kina.search.duration:count", ""), 4L,
                new MetricKey("kina.search.duration:nanos", ""), 8_000_000_000L,
                MetricKey.of("kina.logins", "outcome", "ok"), 0L));

        assertThat(store.get(calls)).isEqualTo(11);
        assertThat(registry.get("kina.search.duration").functionTimer().count()).isEqualTo(4.0);
        assertThat(registry.get("kina.search.duration").functionTimer().totalTime(TimeUnit.SECONDS)).isEqualTo(8.0);
        assertThat(registry.get("kina.logins").tag("outcome", "ok").functionCounter().count()).isZero();
    }

    @Test
    void withoutRegistryValuesStayInMemory() {
        MetricsStore memoryOnly = TestWiring.metricsStore(null);
        memoryOnly.increment(MetricKey.of("kina.searches"));
        memoryOnly.record(MetricKey.of("kina.search.duration"), 10);

        assertThat(memoryOnly.sum("kina.searches")).isEqualTo(1);
        assertThat(memoryOnly.snapshot()).hasSize(3);
    }
}
