package ro.alacrity.kina.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ro.alacrity.kina.TestWiring;
import ro.alacrity.kina.TestcontainersConfiguration;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PriceBreak;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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

    @Autowired
    MetricsGauges gauges;

    @Autowired
    MeterRegistry registry;

    @Autowired
    PlatformTransactionManager transactions;

    /** Unique names so the application's own persistence bean never collides with this test. */
    final String prefix = "test." + UUID.randomUUID().toString().replace("-", "") + ".";

    @Test
    void saveRestoreAndKeepGrowingAcrossRestarts() {
        MetricKey calls = MetricKey.of(prefix + "calls", "tool", "search_parts");
        MetricKey duration = MetricKey.of(prefix + "duration");

        MetricsStore firstRun = TestWiring.metricsStore(null);
        MetricsPersistence first = TestWiring.wire(new MetricsPersistence(), "jdbc", jdbc, "store", firstRun);
        assertThat(first.restore()).isTrue();
        firstRun.add(calls, 5);
        firstRun.record(duration, 3_000_000_000L);
        assertThat(first.save()).isEqualTo(3);
        assertThat(first.save()).as("nothing changed").isZero();
        assertThat(stored(calls)).isEqualTo(5);
        assertThat(stored(new MetricKey(prefix + "duration:nanos", ""))).isEqualTo(3_000_000_000L);

        // the next run: counts before its restore are kept on top of the stored values
        MetricsStore secondRun = TestWiring.metricsStore(null);
        secondRun.increment(calls);
        MetricsPersistence second = TestWiring.wire(new MetricsPersistence(), "jdbc", jdbc, "store", secondRun);
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
        long before = metrics.store().sum(Metric.TOOL_CALLS);
        metrics.toolCall("ping", () -> "ok");

        assertThat(persistence.save()).isPositive();
        Long saved = jdbc.sql("SELECT sum(value) FROM metrics_counters WHERE name = ?")
                .param(Metric.TOOL_CALLS.meterName()).query(Long.class).single();
        assertThat(saved).isEqualTo(before + 1);
    }

    @Test
    void cacheWritesCountNewAndRefreshedRows() {
        String a = prefix + "A";
        String b = prefix + "B";
        long added = metrics.store().get(Metric.CACHE_PARTS_ADDED.key("TME"));
        long refreshed = metrics.store().get(Metric.CACHE_PARTS_REFRESHED.key("TME"));

        partCache.upsertAll(List.of(part(a)));
        partCache.upsertAll(List.of(part(a), part(b)));

        assertThat(metrics.store().get(Metric.CACHE_PARTS_ADDED.key("TME")))
                .isEqualTo(added + 2);
        assertThat(metrics.store().get(Metric.CACHE_PARTS_REFRESHED.key("TME")))
                .isEqualTo(refreshed + 1);

        gauges.refresh();
        long rows = jdbc.sql("SELECT count(*) FROM cached_parts WHERE distributor = 'TME'").query(Long.class).single();
        // kina_cache_parts and kina_cache_searches carry the type column of the rows and sum to the row counts
        assertThat(typedGauge(Metric.CACHE_PARTS, null)).isEqualTo(rows);
        assertThat(typedGauge(Metric.CACHE_PARTS, "capacitor")).isGreaterThanOrEqualTo(2);
        long searchRows = jdbc.sql("SELECT count(*) FROM cached_searches WHERE distributor = 'TME'").query(Long.class)
                .single();
        assertThat(typedGauge(Metric.CACHE_SEARCHES, null)).isEqualTo(searchRows);
        assertThat(registry.get(Metric.CACHE_PARTS_FRESH.meterName()).tag("distributor", "TME").gauge().value())
                .isEqualTo(rows);
        assertThat(registry.get(Metric.CACHE_PARTS_STALE.meterName()).tag("distributor", "TME").gauge().value())
                .isZero();
        assertThat(registry.get(Metric.CACHE_PARTS_STALE_STOCK.meterName()).tag("distributor", "TME").gauge().value())
                .isZero();

        // stock older than the ttl: still in stock, stale stock; a sold-out row is kept for its metadata only
        jdbc.sql("UPDATE cached_parts SET stock_fetched_at = now() - interval '4 days' WHERE part_number = ?")
                .param(a).update();
        partCache.markSoldOut(Distributor.TME, b);
        gauges.refresh();
        assertThat(registry.get(Metric.CACHE_PARTS_FRESH.meterName()).tag("distributor", "TME").gauge().value())
                .isEqualTo(rows - 2);
        assertThat(registry.get(Metric.CACHE_PARTS_STALE.meterName()).tag("distributor", "TME").gauge().value())
                .isEqualTo(2);
        assertThat(registry.get(Metric.CACHE_PARTS_STALE_STOCK.meterName()).tag("distributor", "TME").gauge().value())
                .isEqualTo(1);
        assertThat(registry.get(Metric.USERS_KNOWN.meterName()).gauge().value()).isPositive(); // the development admin
    }

    /**
     * V9 appends {@code type=unknown} to the stored rows of the five search counters, once. Flyway already ran it on
     * this database, so the statements run again on a temporary copy of {@code metrics_counters} (which shadows the
     * real table in this transaction) holding legacy rows, and the transaction is rolled back.
     */
    @Test
    void v9TagsTheLegacySearchCountersAsUnknownOnce() throws Exception {
        String migration = new ClassPathResource("db/migration/V9__metrics_counters_type_tag.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            jdbc.sql("CREATE TEMP TABLE metrics_counters (LIKE public.metrics_counters INCLUDING ALL) ON COMMIT DROP")
                    .update();
            Map<String, String> legacy = new LinkedHashMap<>();
            legacy.put("kina.search.queries", "");
            legacy.put("kina.distributor.calls", "distributor=MOUSER,outcome=ok");
            legacy.put("kina.parts.returned", "distributor=TME");
            legacy.put("kina.parts.fetched", "distributor=LCSC");
            legacy.put("kina.cache.search.lookups", "distributor=MOUSER,status=hit");
            legacy.put("kina.searches", "");
            legacy.put("kina.distributor.duration:count", "distributor=TME");
            legacy.forEach((name, tags) -> jdbc.sql("INSERT INTO metrics_counters (name, tags, value) VALUES (?, ?, 7)")
                    .params(name, tags).update());
            jdbc.sql("INSERT INTO metrics_counters (name, tags, value) VALUES ('kina.parts.fetched', "
                    + "'distributor=TME,type=resistor', 3)").update();

            jdbc.sql(migration).update();
            Map<String, String> once = rows();
            jdbc.sql(migration).update();

            assertThat(rows()).as("idempotent").isEqualTo(once);
            assertThat(once).containsEntry("kina.search.queries|type=unknown", "7")
                    .containsEntry("kina.distributor.calls|distributor=MOUSER,outcome=ok,type=unknown", "7")
                    .containsEntry("kina.parts.returned|distributor=TME,type=unknown", "7")
                    .containsEntry("kina.parts.fetched|distributor=LCSC,type=unknown", "7")
                    .containsEntry("kina.cache.search.lookups|distributor=MOUSER,status=hit,type=unknown", "7")
                    .containsEntry("kina.parts.fetched|distributor=TME,type=resistor", "3")
                    .containsEntry("kina.searches|", "7")
                    .containsEntry("kina.distributor.duration:count|distributor=TME", "7")
                    .hasSize(8);
            // the rewritten tags are the canonical form the application uses for the same series
            assertThat(once).containsKey("kina.distributor.calls|" + Metric.DISTRIBUTOR_CALLS.key("MOUSER", "ok", "unknown").tags());
        });
    }

    /** Sum of the TME series of a typed cache gauge, of one type or (null) of all. */
    double typedGauge(Metric metric, String type) {
        return registry.get(metric.meterName()).tag("distributor", "TME").gauges().stream()
                .filter(g -> type == null || type.equals(g.getId().getTag("type")))
                .mapToDouble(g -> g.value()).sum();
    }

    Map<String, String> rows() {
        Map<String, String> out = new TreeMap<>();
        jdbc.sql("SELECT name, tags, value FROM metrics_counters").query((RowCallbackHandler) rs -> out.put(
                rs.getString("name") + "|" + rs.getString("tags"), Long.toString(rs.getLong("value"))));
        return out;
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
