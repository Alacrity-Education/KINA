package ro.alacrity.kina.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import ro.alacrity.kina.TestWiring;
import ro.alacrity.kina.TestcontainersConfiguration;
import ro.alacrity.kina.cache.CachedSearch;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.cache.SearchCacheRepository;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PriceBreak;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static ro.alacrity.kina.metrics.Metric.CACHE_SEARCH_LOOKUPS;
import static ro.alacrity.kina.metrics.Metric.DISTRIBUTOR_CALLS;
import static ro.alacrity.kina.metrics.Metric.PARTS_FETCHED;
import static ro.alacrity.kina.metrics.Metric.SEARCH_QUERIES;

/**
 * The metrics backfill (DESIGN.md 3.7 "Backfill") against PostgreSQL. Its own context (the application saves its
 * counters only hourly here) and a store, persistence and backfill built by hand per test, so no other bean writes the
 * counters these tests lower.
 */
@SpringBootTest(properties = "kina.metrics.save-interval=1h")
@Import(TestcontainersConfiguration.class)
class MetricsBackfillTest {

    static final Instant NOW = Instant.now();
    static final List<String> NAMES = List.of(SEARCH_QUERIES.meterName(), DISTRIBUTOR_CALLS.meterName(),
            CACHE_SEARCH_LOOKUPS.meterName(), PARTS_FETCHED.meterName());

    @Autowired JdbcClient jdbc;
    @Autowired PartCacheRepository parts;
    @Autowired SearchCacheRepository searches;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Clock clock;

    MetricsStore store;
    MetricsPersistence persistence;
    MetricsBackfill backfill;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM cached_parts").update();
        jdbc.sql("DELETE FROM cached_searches").update();
        jdbc.sql("DELETE FROM metrics_backfill").update();
        jdbc.sql("DELETE FROM metrics_counters WHERE name IN (?, ?, ?, ?)").params(NAMES.toArray()).update();
        newRun();
    }

    /** A new process: store, persistence (restored from the table) and backfill. */
    void newRun() {
        store = TestWiring.metricsStore(null);
        persistence = TestWiring.wire(new MetricsPersistence(), "jdbc", jdbc, "store", store,
                "transactions", transactions);
        assertThat(persistence.restore()).isTrue();
        KinaProperties properties = TestWiring.properties("kina.metrics.backfill.batch-size", "2");
        backfill = TestWiring.wire(new MetricsBackfill(), "jdbc", jdbc, "properties", properties, "store", store,
                "persistence", persistence, "searches", searches, "parts", parts, "clock", clock);
    }

    /** Two capacitor queries (one key cached by both distributors), one resistor query, one unknown; 3 + 2 parts. */
    void seedCache() {
        searches.upsert(search(Distributor.TME, "10uf x7r 0805"));
        searches.upsert(search(Distributor.MOUSER, "10uf x7r 0805"));
        searches.upsert(search(Distributor.TME, "10k 0603 resistor"));
        searches.upsert(search(Distributor.TME, "qwertyuiop"));
        parts.upsertAll(List.of(capacitor(Distributor.TME, "C1"), capacitor(Distributor.TME, "C2"),
                capacitor(Distributor.TME, "C3"), resistor(Distributor.MOUSER, "R1"),
                resistor(Distributor.MOUSER, "R2")));
        assertThat(jdbc.sql("SELECT type FROM cached_parts ORDER BY part_number").query(String.class).list())
                .containsExactly("capacitor", "capacitor", "capacitor", "resistor", "resistor");
    }

    /** Pre-0.5 history: everything under type=unknown, plus one resistor query already counted with its type. */
    void seedCounters() {
        store.add(SEARCH_QUERIES.key("unknown"), 10);
        store.add(SEARCH_QUERIES.key("resistor"), 1);
        for (String d : List.of("TME", "MOUSER")) {
            store.add(DISTRIBUTOR_CALLS.key(d, "ok", "unknown"), 10);
            store.add(CACHE_SEARCH_LOOKUPS.key(d, "miss", "unknown"), 10);
            store.add(CACHE_SEARCH_LOOKUPS.key(d, "hit", "unknown"), 4);
            store.add(PARTS_FETCHED.key(d, "unknown"), 100);
        }
        persistence.save();
    }

    @Test
    void movesTheLowerBoundsFromUnknownKeepsTotalsAndIsIdempotent() {
        seedCache();
        seedCounters();
        Map<String, Long> totals = totals();

        MetricsBackfill.Report report = backfill.run().orElseThrow();

        // queries: one capacitor key (cached by two distributors); resistor already holds its lower bound
        assertThat(store.get(SEARCH_QUERIES.key("capacitor"))).isEqualTo(1);
        assertThat(store.get(SEARCH_QUERIES.key("resistor"))).isEqualTo(1);
        assertThat(store.get(SEARCH_QUERIES.key("unknown"))).isEqualTo(9);
        assertThat(store.get(DISTRIBUTOR_CALLS.key("TME", "ok", "capacitor"))).isEqualTo(1);
        assertThat(store.get(DISTRIBUTOR_CALLS.key("TME", "ok", "resistor"))).isEqualTo(1);
        assertThat(store.get(DISTRIBUTOR_CALLS.key("TME", "ok", "unknown"))).isEqualTo(8);
        assertThat(store.get(DISTRIBUTOR_CALLS.key("MOUSER", "ok", "capacitor"))).isEqualTo(1);
        assertThat(store.get(DISTRIBUTOR_CALLS.key("MOUSER", "ok", "unknown"))).isEqualTo(9);
        assertThat(store.get(CACHE_SEARCH_LOOKUPS.key("TME", "miss", "unknown"))).isEqualTo(8);
        assertThat(store.get(CACHE_SEARCH_LOOKUPS.key("TME", "hit", "unknown"))).as("hits are not derivable")
                .isEqualTo(4);
        assertThat(store.get(PARTS_FETCHED.key("TME", "capacitor"))).isEqualTo(3);
        assertThat(store.get(PARTS_FETCHED.key("TME", "unknown"))).isEqualTo(97);
        assertThat(store.get(PARTS_FETCHED.key("MOUSER", "resistor"))).isEqualTo(2);
        assertThat(store.get(PARTS_FETCHED.key("MOUSER", "unknown"))).isEqualTo(98);
        assertThat(report.movedByName()).containsExactly(Map.entry("kina.search.queries", 1L),
                Map.entry("kina.distributor.calls", 3L), Map.entry("kina.cache.search.lookups", 3L),
                Map.entry("kina.parts.fetched", 5L));
        assertThat(totals()).isEqualTo(totals);
        assertThat(store.get(Metric.METRICS_BACKFILL_RUNS.key("ok"))).isEqualTo(1);
        assertThat(store.get(Metric.METRICS_BACKFILL_MOVED.key("kina.parts.fetched"))).isEqualTo(5);
        assertThat(backfill.lastRunEpochSeconds()).isPositive();

        // the table holds the lowered unknown rows, and the next scheduled save does not raise them again
        assertThat(stored(SEARCH_QUERIES.key("unknown"))).isEqualTo(9);
        assertThat(stored(PARTS_FETCHED.key("TME", "unknown"))).isEqualTo(97);
        assertThat(stored(PARTS_FETCHED.key("TME", "capacitor"))).isEqualTo(3);
        persistence.save();
        assertThat(stored(SEARCH_QUERIES.key("unknown"))).isEqualTo(9);
        store.increment(SEARCH_QUERIES.key("unknown"));
        persistence.save();
        assertThat(stored(SEARCH_QUERIES.key("unknown"))).isEqualTo(10);
        assertThat(attributed(PARTS_FETCHED.key("TME", "capacitor"))).isEqualTo(3);
        assertThat(attributed(new MetricKey(MetricsBackfill.COMPLETED, ""))).isEqualTo(1);

        // a second run, and a restart restoring the table, move nothing
        assertThat(backfill.run().orElseThrow().totalMoved()).isZero();
        Map<MetricKey, Long> before = stored();
        newRun();
        assertThat(backfill.run().orElseThrow().totalMoved()).isZero();
        assertThat(stored()).isEqualTo(before);
        assertThat(attributed(new MetricKey(MetricsBackfill.COMPLETED, ""))).isEqualTo(3);
    }

    @Test
    void aQueryTheVocabularyNowRecognisesIsRetypedAndMovesOneCount() {
        searches.upsert(search(Distributor.TME, "10uf x7r 0805"));
        // typed by an older vocabulary that did not know it
        jdbc.sql("UPDATE cached_searches SET type = 'unknown'").update();
        store.add(SEARCH_QUERIES.key("unknown"), 5);
        persistence.save();

        MetricsBackfill.Report report = backfill.run().orElseThrow();

        assertThat(report.retypedSearches()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT type FROM cached_searches").query(String.class).single()).isEqualTo("capacitor");
        assertThat(store.get(SEARCH_QUERIES.key("capacitor"))).isEqualTo(1);
        assertThat(store.get(SEARCH_QUERIES.key("unknown"))).isEqualTo(4);
        assertThat(stored(SEARCH_QUERIES.key("unknown"))).isEqualTo(4);
    }

    @Test
    void purgedRowsNeverLowerACounter() {
        seedCache();
        seedCounters();
        backfill.run().orElseThrow();
        Map<MetricKey, Long> before = stored();

        jdbc.sql("DELETE FROM cached_parts").update();
        jdbc.sql("DELETE FROM cached_searches").update();
        assertThat(backfill.run().orElseThrow().totalMoved()).isZero();
        // the targets grew back only partly: still below what was attributed, so nothing moves
        searches.upsert(search(Distributor.TME, "10uf x7r 0805"));
        assertThat(backfill.run().orElseThrow().totalMoved()).isZero();

        assertThat(stored()).isEqualTo(before);
        assertThat(store.get(PARTS_FETCHED.key("TME", "capacitor"))).isEqualTo(3);
    }

    @Test
    void movesNoMoreThanTheUnknownSeriesHolds() {
        seedCache();
        store.add(PARTS_FETCHED.key("TME", "unknown"), 2);
        persistence.save();

        MetricsBackfill.Report report = backfill.run().orElseThrow();

        assertThat(store.get(PARTS_FETCHED.key("TME", "capacitor"))).isEqualTo(2);
        assertThat(store.get(PARTS_FETCHED.key("TME", "unknown"))).isZero();
        assertThat(stored(PARTS_FETCHED.key("TME", "unknown"))).isZero();
        assertThat(report.movedByName()).containsEntry("kina.parts.fetched", 2L)
                .containsEntry("kina.search.queries", 0L);
        // nothing to take from: the Mouser resistor series is not created
        assertThat(store.snapshot()).doesNotContainKey(PARTS_FETCHED.key("MOUSER", "resistor"));
        // the attributed amount is what was moved; a later unknown count is moved by the next run
        assertThat(attributed(PARTS_FETCHED.key("TME", "capacitor"))).isEqualTo(2);
        store.add(PARTS_FETCHED.key("TME", "unknown"), 5);
        backfill.run().orElseThrow();
        assertThat(store.get(PARTS_FETCHED.key("TME", "capacitor"))).isEqualTo(3);
        assertThat(store.get(PARTS_FETCHED.key("TME", "unknown"))).isEqualTo(4);
    }

    @Test
    void theStartupRunNeedsTheMarker() {
        assertThat(backfill.neverCompleted()).isTrue();
        backfill.run().orElseThrow();
        assertThat(backfill.neverCompleted()).isFalse();
    }

    /** Sum over type of every series of the four counters, by name and the other tags. */
    Map<String, Long> totals() {
        Map<String, Long> out = new java.util.TreeMap<>();
        store.snapshot().forEach((key, value) -> {
            if (NAMES.contains(key.name())) {
                Map<String, String> tags = key.tagMap();
                tags.remove("type");
                out.merge(key.name() + tags, value, Long::sum);
            }
        });
        return out;
    }

    Map<MetricKey, Long> stored() {
        Map<MetricKey, Long> out = new java.util.TreeMap<>();
        jdbc.sql("SELECT name, tags, value FROM metrics_counters WHERE name IN (?, ?, ?, ?)").params(NAMES.toArray())
                .query(rs -> {
                    out.put(new MetricKey(rs.getString("name"), rs.getString("tags")), rs.getLong("value"));
                });
        return out;
    }

    long stored(MetricKey key) {
        return jdbc.sql("SELECT value FROM metrics_counters WHERE name = ? AND tags = ?")
                .params(key.name(), key.tags()).query(Long.class).single();
    }

    long attributed(MetricKey key) {
        return jdbc.sql("SELECT attributed FROM metrics_backfill WHERE name = ? AND tags = ?")
                .params(key.name(), key.tags()).query(Long.class).single();
    }

    static CachedSearch search(Distributor distributor, String key) {
        return new CachedSearch(distributor, key, 1, List.of("x"), false, NOW);
    }

    static Part capacitor(Distributor distributor, String partNumber) {
        return new Part(distributor, partNumber, "SAMSUNG", "CL21A106KOQNNNE", "MLCC 10uF", "Capacitors", "0805", 10,
                1, null, List.of(new PriceBreak(1, new BigDecimal("0.10"), "EUR")), null, null, null,
                Map.of("Capacitance", "10uF"), Map.of(), NOW);
    }

    static Part resistor(Distributor distributor, String partNumber) {
        return new Part(distributor, partNumber, "YAGEO", "RC0603FR-0710KL", "Thick Film Resistors 10 kOhms 1% 0603",
                "Chip Resistor - Surface Mount", "0603", 10, 1, null,
                List.of(new PriceBreak(1, new BigDecimal("0.01"), "EUR")), null, null, null,
                Map.of("Resistance", "10 kOhms"), Map.of(), NOW);
    }
}
