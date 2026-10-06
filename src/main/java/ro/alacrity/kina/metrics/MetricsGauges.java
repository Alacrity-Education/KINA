package ro.alacrity.kina.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static ro.alacrity.kina.metrics.MetricNames.CACHE_PARTS;
import static ro.alacrity.kina.metrics.MetricNames.CACHE_PARTS_FRESH;
import static ro.alacrity.kina.metrics.MetricNames.CACHE_PARTS_STALE;
import static ro.alacrity.kina.metrics.MetricNames.CACHE_SEARCHES;
import static ro.alacrity.kina.metrics.MetricNames.JLCPCB_DATABASE_AGE;
import static ro.alacrity.kina.metrics.MetricNames.JLCPCB_DATABASE_PARTS;
import static ro.alacrity.kina.metrics.MetricNames.TOKENS_ACTIVE;
import static ro.alacrity.kina.metrics.MetricNames.USERS_KNOWN;
import static ro.alacrity.kina.metrics.MetricNames.USERS_REVOKED;

/**
 * Gauges of what the database holds (DESIGN.md 3.7), not persisted: cache rows per distributor (all, fresh, stale;
 * searches), users and active tokens, recomputed every {@code kina.metrics.save-interval} with a few {@code count(*)}
 * queries (a failed refresh keeps the previous values and is logged once at WARN); the JLCPCB database part count and
 * age, read from memory on every scrape.
 */
@Slf4j
public class MetricsGauges {

    /** The distributors whose results are cached in PostgreSQL (LCSC's SQLite database is its own cache). */
    static final Distributor[] CACHED = {Distributor.MOUSER, Distributor.TME};

    private final JdbcClient jdbc;
    private final Duration ttl;
    private final Clock clock;
    private final ObjectProvider<JlcpcbDatabaseManager> jlcpcb;

    private final Map<Distributor, AtomicLong> parts = new EnumMap<>(Distributor.class);
    private final Map<Distributor, AtomicLong> fresh = new EnumMap<>(Distributor.class);
    private final Map<Distributor, AtomicLong> stale = new EnumMap<>(Distributor.class);
    private final Map<Distributor, AtomicLong> searches = new EnumMap<>(Distributor.class);
    private final AtomicLong usersKnown = new AtomicLong();
    private final AtomicLong usersRevoked = new AtomicLong();
    private final AtomicLong tokensActive = new AtomicLong();
    private boolean failing;

    public MetricsGauges(JdbcClient jdbc, KinaProperties properties, Clock clock,
                         ObjectProvider<JlcpcbDatabaseManager> jlcpcb, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.ttl = properties.cache().ttl();
        this.clock = clock;
        this.jlcpcb = jlcpcb;
        for (Distributor d : CACHED) {
            register(registry, CACHE_PARTS, parts, d);
            register(registry, CACHE_PARTS_FRESH, fresh, d);
            register(registry, CACHE_PARTS_STALE, stale, d);
            register(registry, CACHE_SEARCHES, searches, d);
        }
        gauge(registry, USERS_KNOWN, usersKnown, null);
        gauge(registry, USERS_REVOKED, usersRevoked, null);
        gauge(registry, TOKENS_ACTIVE, tokensActive, null);
        // read on every scrape: the JLCPCB status is in memory
        Gauge.builder(JLCPCB_DATABASE_PARTS, this, MetricsGauges::jlcpcbParts)
                .description(MetricNames.description(JLCPCB_DATABASE_PARTS))
                .register(registry);
        Gauge.builder(JLCPCB_DATABASE_AGE, this, MetricsGauges::jlcpcbAgeSeconds)
                .description(MetricNames.description(JLCPCB_DATABASE_AGE))
                .baseUnit("seconds")
                .register(registry);
    }

    private static void register(MeterRegistry registry, String name, Map<Distributor, AtomicLong> holders,
                                 Distributor distributor) {
        AtomicLong holder = new AtomicLong();
        holders.put(distributor, holder);
        Gauge.builder(name, holder, AtomicLong::doubleValue)
                .description(MetricNames.description(name))
                .tag("distributor", distributor.name())
                .register(registry);
    }

    private static void gauge(MeterRegistry registry, String name, AtomicLong holder, String unit) {
        Gauge.builder(name, holder, AtomicLong::doubleValue)
                .description(MetricNames.description(name))
                .baseUnit(unit)
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
        OffsetDateTime freshSince = clock.instant().minus(ttl).atOffset(ZoneOffset.UTC);
        Map<Distributor, long[]> counts = new EnumMap<>(Distributor.class);
        jdbc.sql("""
                        SELECT distributor, count(*) AS total, count(*) FILTER (WHERE fetched_at >= ?) AS fresh
                        FROM cached_parts GROUP BY distributor""")
                .param(freshSince)
                .query(rs -> {
                    Distributor d = distributor(rs.getString("distributor"));
                    if (d != null) {
                        counts.put(d, new long[] {rs.getLong("total"), rs.getLong("fresh")});
                    }
                });
        Map<Distributor, Long> searchCounts = new EnumMap<>(Distributor.class);
        jdbc.sql("SELECT distributor, count(*) AS n FROM cached_searches GROUP BY distributor")
                .query(rs -> {
                    Distributor d = distributor(rs.getString("distributor"));
                    if (d != null) {
                        searchCounts.put(d, rs.getLong("n"));
                    }
                });
        for (Distributor d : CACHED) {
            long[] c = counts.getOrDefault(d, new long[2]);
            parts.get(d).set(c[0]);
            fresh.get(d).set(c[1]);
            stale.get(d).set(c[0] - c[1]);
            searches.get(d).set(searchCounts.getOrDefault(d, 0L));
        }
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
