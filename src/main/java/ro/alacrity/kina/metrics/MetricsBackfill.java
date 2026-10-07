package ro.alacrity.kina.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.cache.SearchCacheRepository;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.Distributor;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static ro.alacrity.kina.metrics.Metric.CACHE_SEARCH_LOOKUPS;
import static ro.alacrity.kina.metrics.Metric.DISTRIBUTOR_CALLS;
import static ro.alacrity.kina.metrics.Metric.METRICS_BACKFILL_LAST_RUN;
import static ro.alacrity.kina.metrics.Metric.METRICS_BACKFILL_MOVED;
import static ro.alacrity.kina.metrics.Metric.METRICS_BACKFILL_RUNS;
import static ro.alacrity.kina.metrics.Metric.PARTS_FETCHED;
import static ro.alacrity.kina.metrics.Metric.SEARCH_QUERIES;

/**
 * The metrics backfill (DESIGN.md 3.7 "Backfill"): daily at {@code kina.metrics.backfill.cron} (06:00), and once at
 * startup while it never completed against this database. A run re-types every {@code cached_searches} and
 * {@code cached_parts} row, derives from them a lower bound of what each typed counter must hold, and moves the missing
 * amount from the {@code type=unknown} series of the same counter (same other tags) to the typed one, never more than
 * the unknown series holds. Totals over {@code type} never change, a typed counter is never lowered, and a second run
 * moves nothing. Best effort: a failure is logged once per run and never affects a request.
 */
@Slf4j
@Component
public class MetricsBackfill {

    /** The {@code metrics_backfill} row that marks a completed run ({@code attributed} = completed runs). */
    static final String COMPLETED = "backfill.completed";

    /** The counters the backfill attributes, in the order of the log line. */
    static final List<Metric> SERIES = List.of(SEARCH_QUERIES, DISTRIBUTOR_CALLS, CACHE_SEARCH_LOOKUPS, PARTS_FETCHED);

    @Autowired private JdbcClient jdbc;
    @Autowired private KinaProperties properties;
    @Autowired private MetricsStore store;
    @Autowired private MetricsPersistence persistence;
    @Autowired private SearchCacheRepository searches;
    @Autowired private PartCacheRepository parts;
    @Autowired private Clock clock;
    @Autowired private ObjectProvider<MeterRegistry> registry;
    @Autowired private ObjectProvider<TaskScheduler> scheduler;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong lastRunEpochSeconds = new AtomicLong();

    /**
     * What one run did.
     *
     * @param retypedSearches {@code cached_searches} rows whose type changed
     * @param retypedParts    {@code cached_parts} rows whose type changed
     * @param moved           amount moved per typed counter key (only keys that moved something)
     * @param millis          duration of the run
     */
    public record Report(int retypedSearches, int retypedParts, Map<MetricKey, Long> moved, long millis) {

        /** Amount moved per counter name, every name of {@link #SERIES} present. */
        public Map<String, Long> movedByName() {
            Map<String, Long> out = new LinkedHashMap<>();
            SERIES.forEach(m -> out.put(m.meterName(), 0L));
            moved.forEach((key, n) -> out.merge(key.name(), n, Long::sum));
            return out;
        }

        public long totalMoved() {
            return moved.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    /**
     * The backfill for the Status tab.
     *
     * @param lastRun when a run last completed; null when none did
     * @param runs    runs by outcome ({@code ok}, {@code failed})
     * @param moved   amount moved from {@code type=unknown} per counter name, over all runs
     */
    public record Status(Instant lastRun, Map<String, Long> runs, Map<String, Long> moved) {
    }

    /** The last completed run, the run counters and what the runs moved. */
    public Status status() {
        long last = lastRunEpochSeconds.get();
        return new Status(last == 0 ? null : Instant.ofEpochSecond(last),
                new TreeMap<>(store.sumBy(METRICS_BACKFILL_RUNS, "outcome")),
                new TreeMap<>(store.sumBy(METRICS_BACKFILL_MOVED, "name")));
    }

    @PostConstruct
    void registerGauge() {
        try {
            jdbc.sql("SELECT updated_at FROM metrics_backfill WHERE name = ? AND tags = ''")
                    .param(COMPLETED)
                    .query((rs, n) -> rs.getObject(1, OffsetDateTime.class))
                    .optional()
                    .ifPresent(at -> lastRunEpochSeconds.set(at.toEpochSecond()));
        } catch (RuntimeException e) {
            log.debug("Reading the last metrics backfill run failed: {}", e.toString());
        }
        MeterRegistry meters = registry == null ? null : registry.getIfAvailable();
        if (meters != null) {
            Gauge.builder(METRICS_BACKFILL_LAST_RUN.meterName(), lastRunEpochSeconds, AtomicLong::doubleValue)
                    .description(METRICS_BACKFILL_LAST_RUN.help())
                    .baseUnit(METRICS_BACKFILL_LAST_RUN.baseUnit())
                    .register(meters);
        }
    }

    /** After startup (the counters are restored by then): {@link #startWhenNeverRun()}. */
    @EventListener(ApplicationReadyEvent.class)
    void onApplicationReady() {
        startWhenNeverRun();
    }

    /** Starts a run in the background when enabled and no run ever completed; true when one was started. */
    boolean startWhenNeverRun() {
        if (!properties.metrics().backfill().enabled() || !neverCompleted()) {
            return false;
        }
        log.info("The metrics backfill never ran against this database; running it now");
        TaskScheduler tasks = scheduler == null ? null : scheduler.getIfAvailable();
        if (tasks != null) {
            tasks.schedule(this::run, clock.instant());
        } else {
            Thread.ofVirtual().name("metrics-backfill").start(this::run);
        }
        return true;
    }

    /** The daily run. */
    @Scheduled(cron = "${kina.metrics.backfill.cron:0 0 6 * * *}")
    public void scheduled() {
        if (properties.metrics().backfill().enabled()) {
            run();
        }
    }

    /** True when {@code metrics_backfill} has no completion marker (false when it cannot be read). */
    boolean neverCompleted() {
        try {
            return jdbc.sql("SELECT count(*) FROM metrics_backfill WHERE name = ? AND tags = ''")
                    .param(COMPLETED).query(Long.class).single() == 0;
        } catch (RuntimeException e) {
            log.debug("Reading the metrics backfill marker failed: {}", e.toString());
            return false;
        }
    }

    /**
     * One run. Empty when another run is in progress or the run failed (logged once at WARN, counted as
     * {@code failed}). Never throws.
     */
    public Optional<Report> run() {
        if (!running.compareAndSet(false, true)) {
            log.info("A metrics backfill run is in progress; not starting another");
            return Optional.empty();
        }
        long start = System.nanoTime();
        try {
            Report report = backfill(start);
            store.increment(METRICS_BACKFILL_RUNS.key("ok"));
            report.movedByName().forEach((name, n) -> store.add(METRICS_BACKFILL_MOVED.key(name), n));
            lastRunEpochSeconds.set(clock.instant().getEpochSecond());
            log.info("Metrics backfill: retyped {} cached_searches and {} cached_parts rows; moved from type=unknown: "
                            + "{}; {} ms", report.retypedSearches(), report.retypedParts(),
                    report.movedByName().entrySet().stream().map(e -> e.getKey() + " " + e.getValue())
                            .collect(Collectors.joining(", ")), report.millis());
            return Optional.of(report);
        } catch (RuntimeException e) {
            store.increment(METRICS_BACKFILL_RUNS.key("failed"));
            log.warn("Metrics backfill failed after {} ms, retrying at the next run: {}",
                    (System.nanoTime() - start) / 1_000_000, e.toString());
            return Optional.empty();
        } finally {
            running.set(false);
        }
    }

    private Report backfill(long start) {
        int batchSize = properties.metrics().backfill().batchSize();
        int retypedSearches = searches.retype(batchSize);
        int retypedParts = parts.retype(batchSize);

        Map<MetricKey, Long> targets = targets();
        Map<MetricKey, Long> attributed = attributed();
        Map<MetricKey, Map<MetricKey, Long>> wanted = new TreeMap<>();
        targets.forEach((key, target) -> {
            // what the counter holds already (counted live, or moved by an earlier run) counts against the target
            long held = Math.max(store.get(key), attributed.getOrDefault(key, 0L));
            if (target > held) {
                wanted.computeIfAbsent(unknownOf(key), k -> new TreeMap<>()).put(key, target - held);
            }
        });
        List<MetricsPersistence.Transfer> transfers = new ArrayList<>();
        wanted.forEach((unknown, deltas) -> share(store.get(unknown), deltas).forEach((key, amount) -> {
            if (amount > 0) {
                transfers.add(new MetricsPersistence.Transfer(unknown, key, amount));
            }
        }));
        Map<MetricKey, Long> moved = new TreeMap<>();
        persistence.transfer(transfers, amounts -> {
            for (int i = 0; i < transfers.size(); i++) {
                long amount = amounts.get(i);
                if (amount > 0) {
                    MetricKey key = transfers.get(i).to();
                    moved.put(key, amount);
                    jdbc.sql("""
                                    INSERT INTO metrics_backfill (name, tags, attributed, updated_at)
                                    VALUES (?, ?, ?, now())
                                    ON CONFLICT (name, tags) DO UPDATE
                                      SET attributed = metrics_backfill.attributed + EXCLUDED.attributed,
                                          updated_at = EXCLUDED.updated_at""")
                            .params(key.name(), key.tags(), amount).update();
                }
            }
            jdbc.sql("""
                            INSERT INTO metrics_backfill (name, tags, attributed, updated_at) VALUES (?, '', 1, now())
                            ON CONFLICT (name, tags) DO UPDATE
                              SET attributed = metrics_backfill.attributed + 1, updated_at = EXCLUDED.updated_at""")
                    .param(COMPLETED).update();
        });
        return new Report(retypedSearches, retypedParts, moved, (System.nanoTime() - start) / 1_000_000);
    }

    /**
     * Splits {@code pool} (what one unknown series holds) over the typed series that want {@code deltas}: each gets
     * its delta when the pool covers them all, else a share proportional to its delta (largest remainders first, ties
     * in key order), so no type is favoured by its name.
     */
    static Map<MetricKey, Long> share(long pool, Map<MetricKey, Long> deltas) {
        long sum = deltas.values().stream().mapToLong(Long::longValue).sum();
        if (sum <= pool) {
            return deltas;
        }
        Map<MetricKey, Long> out = new TreeMap<>();
        Map<MetricKey, Long> remainders = new TreeMap<>();
        long given = 0;
        for (Map.Entry<MetricKey, Long> e : deltas.entrySet()) {
            BigInteger scaled = BigInteger.valueOf(e.getValue())
                    .multiply(BigInteger.valueOf(Math.max(0, pool)));
            BigInteger[] qr = scaled.divideAndRemainder(BigInteger.valueOf(sum));
            out.put(e.getKey(), qr[0].longValue());
            remainders.put(e.getKey(), qr[1].longValue());
            given += qr[0].longValue();
        }
        long left = Math.max(0, pool) - given;
        List<MetricKey> order = new ArrayList<>(remainders.keySet());
        order.sort((a, b) -> Long.compare(remainders.get(b), remainders.get(a)));
        for (int i = 0; i < left && i < order.size(); i++) {
            out.merge(order.get(i), 1L, Long::sum);
        }
        return out;
    }

    /**
     * The lower bound of each typed counter the database shows (only types other than unknown): queries per type
     * (distinct cached query keys), successful distributor calls and cache misses per distributor and type (cached
     * searches; each was fetched once), parts fetched per distributor and type (cached parts).
     */
    Map<MetricKey, Long> targets() {
        Map<MetricKey, Long> out = new TreeMap<>();
        jdbc.sql("SELECT type, count(DISTINCT query_key) AS n FROM cached_searches GROUP BY type")
                .query(rs -> {
                    String type = typed(rs.getString("type"));
                    if (type != null) {
                        out.merge(SEARCH_QUERIES.key(type), rs.getLong("n"), Long::sum);
                    }
                });
        jdbc.sql("SELECT distributor, type, count(*) AS n FROM cached_searches GROUP BY distributor, type")
                .query(rs -> {
                    String type = typed(rs.getString("type"));
                    String distributor = distributor(rs.getString("distributor"));
                    if (type != null && distributor != null) {
                        out.merge(DISTRIBUTOR_CALLS.key(distributor, "ok", type), rs.getLong("n"), Long::sum);
                        out.merge(CACHE_SEARCH_LOOKUPS.key(distributor, "miss", type), rs.getLong("n"), Long::sum);
                    }
                });
        jdbc.sql("SELECT distributor, type, count(*) AS n FROM cached_parts GROUP BY distributor, type")
                .query(rs -> {
                    String type = typed(rs.getString("type"));
                    String distributor = distributor(rs.getString("distributor"));
                    if (type != null && distributor != null) {
                        out.merge(PARTS_FETCHED.key(distributor, type), rs.getLong("n"), Long::sum);
                    }
                });
        return out;
    }

    private Map<MetricKey, Long> attributed() {
        Map<MetricKey, Long> out = new HashMap<>();
        jdbc.sql("SELECT name, tags, attributed FROM metrics_backfill WHERE name <> ?")
                .param(COMPLETED)
                .query(rs -> {
                    out.put(new MetricKey(rs.getString("name"), rs.getString("tags")), rs.getLong("attributed"));
                });
        return out;
    }

    /** The {@code type=unknown} series of the counter of {@code key} (same name and other tags). */
    static MetricKey unknownOf(MetricKey key) {
        Map<String, String> tags = key.tagMap();
        tags.put("type", KinaMetrics.UNKNOWN_TYPE);
        return new MetricKey(key.name(), MetricKey.canonical(tags));
    }

    /** The type tag of a column value, null for unknown (nothing to attribute). */
    private static String typed(String column) {
        String type = KinaMetrics.typeOf(column);
        return KinaMetrics.UNKNOWN_TYPE.equals(type) ? null : type;
    }

    private static String distributor(String name) {
        try {
            return Distributor.valueOf(name).name();
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    /** The end of the last successful run (Unix epoch seconds), 0 when none since startup and no marker. */
    long lastRunEpochSeconds() {
        return lastRunEpochSeconds.get();
    }
}
