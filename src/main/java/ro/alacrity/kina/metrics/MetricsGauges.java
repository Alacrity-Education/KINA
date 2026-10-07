package ro.alacrity.kina.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.lcsc.JlcpcbDatabaseManager;
import ro.alacrity.kina.distributor.lcsc.JlcpcbStatus;
import ro.alacrity.kina.domain.Distributor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static ro.alacrity.kina.metrics.Metric.CACHE_PARTS;
import static ro.alacrity.kina.metrics.Metric.CACHE_PARTS_FRESH;
import static ro.alacrity.kina.metrics.Metric.CACHE_PARTS_STALE;
import static ro.alacrity.kina.metrics.Metric.CACHE_PARTS_STALE_STOCK;
import static ro.alacrity.kina.metrics.Metric.CACHE_SEARCHES;
import static ro.alacrity.kina.metrics.Metric.JLCPCB_DATABASE_AGE;
import static ro.alacrity.kina.metrics.Metric.JLCPCB_DATABASE_PARTS;
import static ro.alacrity.kina.metrics.Metric.TOKENS_ACTIVE;
import static ro.alacrity.kina.metrics.Metric.USERS_KNOWN;
import static ro.alacrity.kina.metrics.Metric.USERS_REVOKED;

/**
 * Gauges of what the database holds (DESIGN.md 3.7), not persisted: cache rows per distributor (all and searches also
 * per {@code type}, the column written with each row; fresh: in stock
 * with stock and prices younger than {@code kina.cache.ttl}; stale: the rest, kept for their metadata; stale stock: in
 * stock with stock and prices older than the TTL; searches), users and active tokens, recomputed every {@code kina.metrics.save-interval} with a few {@code count(*)}
 * queries (a failed refresh keeps the previous values and is logged once at WARN); the JLCPCB database part count and
 * age, read from memory on every scrape.
 */
@Slf4j
@Component
public class MetricsGauges {

    /** The distributors whose results are cached in PostgreSQL (LCSC's SQLite database is its own cache). */
    static final Distributor[] CACHED = {Distributor.MOUSER, Distributor.TME};

    @Autowired private JdbcClient jdbc;
    @Autowired private KinaProperties properties;
    @Autowired private Clock clock;
    @Autowired private ObjectProvider<JlcpcbDatabaseManager> jlcpcb;
    @Autowired private MeterRegistry registry;

    /** {@code kina_cache_parts} and {@code kina_cache_searches} by distributor and type, registered as they appear. */
    private final Map<MetricKey, AtomicLong> typed = new ConcurrentHashMap<>();
    private final Map<Distributor, AtomicLong> fresh = new EnumMap<>(Distributor.class);
    private final Map<Distributor, AtomicLong> stale = new EnumMap<>(Distributor.class);
    private final Map<Distributor, AtomicLong> staleStock = new EnumMap<>(Distributor.class);
    private final AtomicLong usersKnown = new AtomicLong();
    private final AtomicLong usersRevoked = new AtomicLong();
    private final AtomicLong tokensActive = new AtomicLong();
    private boolean failing;

    @PostConstruct
    void registerGauges() {
        for (Distributor d : CACHED) {
            // the unknown series always exists, so both names are exported before the first cached row
            typed(CACHE_PARTS, d.name(), KinaMetrics.UNKNOWN_TYPE);
            register(registry, CACHE_PARTS_FRESH, fresh, d);
            register(registry, CACHE_PARTS_STALE, stale, d);
            register(registry, CACHE_PARTS_STALE_STOCK, staleStock, d);
            typed(CACHE_SEARCHES, d.name(), KinaMetrics.UNKNOWN_TYPE);
        }
        gauge(registry, USERS_KNOWN, usersKnown);
        gauge(registry, USERS_REVOKED, usersRevoked);
        gauge(registry, TOKENS_ACTIVE, tokensActive);
        // read on every scrape: the JLCPCB status is in memory
        Gauge.builder(JLCPCB_DATABASE_PARTS.meterName(), this, MetricsGauges::jlcpcbParts)
                .description(JLCPCB_DATABASE_PARTS.help())
                .register(registry);
        Gauge.builder(JLCPCB_DATABASE_AGE.meterName(), this, MetricsGauges::jlcpcbAgeSeconds)
                .description(JLCPCB_DATABASE_AGE.help())
                .baseUnit(JLCPCB_DATABASE_AGE.baseUnit())
                .register(registry);
    }

    private static void register(MeterRegistry registry, Metric metric, Map<Distributor, AtomicLong> holders,
                                 Distributor distributor) {
        AtomicLong holder = new AtomicLong();
        holders.put(distributor, holder);
        Gauge.builder(metric.meterName(), holder, AtomicLong::doubleValue)
                .description(metric.help())
                .tag("distributor", distributor.name())
                .register(registry);
    }

    /** The holder of one typed gauge series, registered the first time it is asked for. */
    private AtomicLong typed(Metric metric, String distributor, String type) {
        return typed.computeIfAbsent(metric.key(distributor, type), key -> {
            AtomicLong holder = new AtomicLong();
            Gauge.builder(metric.meterName(), holder, AtomicLong::doubleValue)
                    .description(metric.help())
                    .tags(key.micrometerTags())
                    .register(registry);
            return holder;
        });
    }

    private static void gauge(MeterRegistry registry, Metric metric, AtomicLong holder) {
        Gauge.builder(metric.meterName(), holder, AtomicLong::doubleValue)
                .description(metric.help())
                .register(registry);
    }

    /** Recomputes every gauge. Never throws. */
    @Scheduled(fixedDelayString = "${kina.metrics.save-interval:30s}")
    public void refresh() {
        try {
            refreshDatabase();
            if (failing) {
                failing = false;
                log.info("Metric gauges are read again");
            }
        } catch (RuntimeException e) {
            if (!failing) {
                failing = true;
                log.warn("Reading the metric gauges failed, keeping the previous values: {}", e.toString());
            }
        }
    }

    private void refreshDatabase() {
        OffsetDateTime freshSince = clock.instant().minus(properties.cache().ttl()).atOffset(ZoneOffset.UTC);
        Map<Distributor, long[]> counts = new EnumMap<>(Distributor.class);
        jdbc.sql("""
                        SELECT distributor, count(*) AS total,
                               count(*) FILTER (WHERE in_stock AND stock_fetched_at >= ?) AS fresh,
                               count(*) FILTER (WHERE in_stock AND stock_fetched_at < ?) AS stale_stock
                        FROM cached_parts GROUP BY distributor""")
                .params(freshSince, freshSince)
                .query(rs -> {
                    Distributor d = distributor(rs.getString("distributor"));
                    if (d != null) {
                        counts.put(d, new long[] {rs.getLong("total"), rs.getLong("fresh"), rs.getLong("stale_stock")});
                    }
                });
        Map<MetricKey, Long> typedCounts = new HashMap<>();
        typedCounts(typedCounts, CACHE_PARTS, "cached_parts");
        typedCounts(typedCounts, CACHE_SEARCHES, "cached_searches");
        for (Distributor d : CACHED) {
            long[] c = counts.getOrDefault(d, new long[3]);
            fresh.get(d).set(c[1]);
            stale.get(d).set(c[0] - c[1]);
            staleStock.get(d).set(c[2]);
        }
        // a type no longer present keeps its series at 0
        typed.forEach((key, holder) -> holder.set(typedCounts.getOrDefault(key, 0L)));
        typedCounts.forEach((key, n) -> typed(metricOf(key), key.tag("distributor"), key.tag("type")).set(n));
        jdbc.sql("""
                        SELECT count(*) FILTER (WHERE access_revoked_at IS NULL) AS known,
                               count(*) FILTER (WHERE access_revoked_at IS NOT NULL) AS revoked
                        FROM users""")
                .query(rs -> {
                    usersKnown.set(rs.getLong("known"));
                    usersRevoked.set(rs.getLong("revoked"));
                });
        Long active = jdbc.sql("SELECT count(*) FROM access_tokens WHERE revoked_at IS NULL AND expires_at > ?")
                .param(clock.instant().atOffset(ZoneOffset.UTC))
                .query(Long.class)
                .single();
        tokensActive.set(active == null ? 0 : active);
    }

    /** Rows of {@code table} per cached distributor and type (NULL and unexpected values count as unknown). */
    private void typedCounts(Map<MetricKey, Long> out, Metric metric, String table) {
        jdbc.sql("SELECT distributor, type, count(*) AS n FROM " + table + " GROUP BY distributor, type")
                .query(rs -> {
                    Distributor d = distributor(rs.getString("distributor"));
                    if (d != null && d != Distributor.LCSC) {
                        out.merge(metric.key(d.name(), KinaMetrics.typeOf(rs.getString("type"))), rs.getLong("n"),
                                Long::sum);
                    }
                });
    }

    // ---- reading (the Status tab) ---------------------------------------------------------------------------------

    /**
     * The cache gauges of one distributor, as of the last refresh.
     *
     * @param partsByType    {@code kina_cache_parts} per type, types with no rows left out
     * @param searchesByType {@code kina_cache_searches} per type, types with no rows left out
     */
    public record CacheCounts(long parts, long fresh, long stale, long staleStock, long searches,
                              Map<String, Long> partsByType, Map<String, Long> searchesByType) {
    }

    /** The cache gauges per cached distributor (Mouser, TME), as of the last refresh. */
    public Map<Distributor, CacheCounts> cacheCounts() {
        Map<Distributor, CacheCounts> out = new EnumMap<>(Distributor.class);
        for (Distributor d : CACHED) {
            Map<String, Long> parts = byType(CACHE_PARTS, d);
            Map<String, Long> searches = byType(CACHE_SEARCHES, d);
            out.put(d, new CacheCounts(sum(parts), fresh.get(d).get(), stale.get(d).get(), staleStock.get(d).get(),
                    sum(searches), parts, searches));
        }
        return out;
    }

    /** {@code kina_users_known}, as of the last refresh. */
    public long usersKnown() {
        return usersKnown.get();
    }

    /** {@code kina_users_revoked}, as of the last refresh. */
    public long usersRevoked() {
        return usersRevoked.get();
    }

    /** {@code kina_tokens_active}, as of the last refresh. */
    public long tokensActive() {
        return tokensActive.get();
    }

    private Map<String, Long> byType(Metric metric, Distributor distributor) {
        Map<String, Long> out = new TreeMap<>();
        typed.forEach((key, holder) -> {
            if (key.name().equals(metric.meterName()) && distributor.name().equals(key.tag("distributor"))
                    && holder.get() > 0) {
                out.put(key.tag("type"), holder.get());
            }
        });
        return out;
    }

    private static long sum(Map<String, Long> values) {
        return values.values().stream().mapToLong(Long::longValue).sum();
    }

    private static Metric metricOf(MetricKey key) {
        return key.name().equals(CACHE_PARTS.meterName()) ? CACHE_PARTS : CACHE_SEARCHES;
    }

    double jlcpcbParts() {
        JlcpcbStatus status = jlcpcbStatus();
        return status == null || status.partCount() == null ? 0 : status.partCount();
    }

    double jlcpcbAgeSeconds() {
        JlcpcbStatus status = jlcpcbStatus();
        Instant downloaded = status == null ? null : status.downloadedAt();
        return downloaded == null ? 0 : Math.max(0, Duration.between(downloaded, clock.instant()).toSeconds());
    }

    private JlcpcbStatus jlcpcbStatus() {
        try {
            JlcpcbDatabaseManager manager = jlcpcb.getIfAvailable();
            return manager == null ? null : manager.status().orElse(null);
        } catch (RuntimeException e) {
            log.debug("Reading the JLCPCB status for the metrics failed: {}", e.toString());
            return null;
        }
    }

    private static Distributor distributor(String name) {
        try {
            return Distributor.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
