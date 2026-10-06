package ro.alacrity.kina.metrics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import ro.alacrity.kina.TestcontainersConfiguration;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PriceBreak;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Counters survive a restart: save, restore into a new store (the next run), keep growing (DESIGN.md 3.7). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MetricsPersistenceTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    KinaMetrics metrics;

    @Autowired
    MetricsPersistence persistence;

    @Autowired
    PartCacheRepository partCache;

    /** Unique names so the application's own persistence bean never collides with this test. */
    final String prefix = "test." + UUID.randomUUID().toString().replace("-", "") + ".";

    @Test
    void saveRestoreAndKeepGrowingAcrossRestarts() {
        MetricKey calls = MetricKey.of(prefix + "calls", "tool", "search_parts");
        MetricKey duration = MetricKey.of(prefix + "duration");

        MetricsStore firstRun = new MetricsStore(null);
        MetricsPersistence first = new MetricsPersistence(jdbc, firstRun);
        assertThat(first.restore()).isTrue();
        firstRun.add(calls, 5);
        firstRun.record(duration, 3_000_000_000L);
        assertThat(first.save()).isEqualTo(3);
        assertThat(first.save()).as("nothing changed").isZero();
        assertThat(stored(calls)).isEqualTo(5);
        assertThat(stored(new MetricKey(prefix + "duration:nanos", ""))).isEqualTo(3_000_000_000L);

        // the next run: counts before its restore are kept on top of the stored values
        MetricsStore secondRun = new MetricsStore(null);
        secondRun.increment(calls);
        MetricsPersistence second = new MetricsPersistence(jdbc, secondRun);
        assertThat(second.restore()).isTrue();
        assertThat(secondRun.get(calls)).isEqualTo(6);
        assertThat(secondRun.get(new MetricKey(prefix + "duration:count", ""))).isEqualTo(1);

        secondRun.increment(calls);
        second.save();
        assertThat(stored(calls)).isEqualTo(7);

        // a stale writer (an old run still saving) never makes the stored value go down
        firstRun.increment(calls);
        first.save();
        assertThat(stored(calls)).isEqualTo(7);
    }

    @Test
    void theApplicationRestoredItsCountersAtStartup() {
        assertThat(persistence.isRestored()).isTrue();
        long before = metrics.store().sum(MetricNames.TOOL_CALLS);
        metrics.toolCall("ping", () -> "ok");

        assertThat(persistence.save()).isPositive();
        Long saved = jdbc.sql("SELECT sum(value) FROM metrics_counters WHERE name = ?")
                .param(MetricNames.TOOL_CALLS).query(Long.class).single();
        assertThat(saved).isEqualTo(before + 1);
    }

    @Test
    void cacheWritesCountNewAndRefreshedRows() {
        String a = prefix + "A";
        String b = prefix + "B";
        long added = metrics.store().get(MetricKey.of(MetricNames.CACHE_PARTS_ADDED, "distributor", "TME"));
        long refreshed = metrics.store().get(MetricKey.of(MetricNames.CACHE_PARTS_REFRESHED, "distributor", "TME"));

        partCache.upsertAll(List.of(part(a)));
        partCache.upsertAll(List.of(part(a), part(b)));

        assertThat(metrics.store().get(MetricKey.of(MetricNames.CACHE_PARTS_ADDED, "distributor", "TME")))
                .isEqualTo(added + 2);
        assertThat(metrics.store().get(MetricKey.of(MetricNames.CACHE_PARTS_REFRESHED, "distributor", "TME")))
                .isEqualTo(refreshed + 1);
    }

    long stored(MetricKey key) {
        return jdbc.sql("SELECT value FROM metrics_counters WHERE name = ? AND tags = ?")
                .params(key.name(), key.tags()).query(Long.class).single();
    }

    static Part part(String partNumber) {
        return new Part(Distributor.TME, partNumber, "YAGEO", partNumber, "MLCC 100nF", "Capacitors", null, 100, 1,
                1, List.of(new PriceBreak(1, new BigDecimal("0.10"), "EUR")), null, null, null, Map.of(), Map.of(),
                Instant.now());
    }
}
