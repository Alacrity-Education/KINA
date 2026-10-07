package ro.alacrity.kina.metrics;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the counters of the {@link MetricsStore} in {@code metrics_counters} (DESIGN.md 3.7): restores them on startup
 * (stored + counted since start) and saves the changed ones every {@code kina.metrics.save-interval} (30 s) and on
 * shutdown, with one upsert statement. A database failure never affects requests: it is logged once at WARN and the
 * next tick tries again. Nothing is saved before the restore succeeded, so a run that could not read the stored values
 * never overwrites them with smaller ones (the upsert also keeps the larger value).
 */
@Slf4j
@Component
public class MetricsPersistence {

    static final String UPSERT = """
            INSERT INTO metrics_counters (name, tags, value, updated_at)
            SELECT n, t, v, now() FROM unnest(?::text[], ?::text[], ?::bigint[]) AS u(n, t, v)
            ON CONFLICT (name, tags) DO UPDATE
              SET value = GREATEST(metrics_counters.value, EXCLUDED.value), updated_at = EXCLUDED.updated_at""";

    @Autowired private JdbcClient jdbc;
    @Autowired private MetricsStore store;
    private final Map<MetricKey, Long> saved = new HashMap<>();
    private volatile boolean restored;
    private boolean failing;

    public boolean isRestored() {
        return restored;
    }

    @PostConstruct
    void restoreOnStartup() {
        restore();
    }

    /** Adds the stored values to the store; true when done (now or earlier). Never throws. */
    public synchronized boolean restore() {
        if (restored) {
            return true;
        }
        try {
            Map<MetricKey, Long> stored = new LinkedHashMap<>();
            jdbc.sql("SELECT name, tags, value FROM metrics_counters ORDER BY name, tags")
                    .query(rs -> {
                        stored.put(new MetricKey(rs.getString("name"), rs.getString("tags")), rs.getLong("value"));
                    });
            store.restore(stored);
            saved.putAll(stored);
            restored = true;
            log.info("Restored {} metric counters from the database", stored.size());
            recovered();
            return true;
        } catch (RuntimeException e) {
            failed("reading", e);
            return false;
        }
    }

    /** Saves every counter that changed since the last save; returns the number of rows written. Never throws. */
    @Scheduled(fixedDelayString = "${kina.metrics.save-interval:30s}",
            initialDelayString = "${kina.metrics.save-interval:30s}")
    public synchronized int save() {
        if (!restored && !restore()) {
            return 0;
        }
        List<String> names = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        List<Long> values = new ArrayList<>();
        Map<MetricKey, Long> snapshot = store.snapshot();
        snapshot.forEach((key, value) -> {
            if (!value.equals(saved.get(key))) {
                names.add(key.name());
                tags.add(key.tags());
                values.add(value);
            }
        });
        if (names.isEmpty()) {
            return 0;
        }
        try {
            jdbc.sql(UPSERT)
                    .params(names.toArray(String[]::new), tags.toArray(String[]::new), values.toArray(Long[]::new))
                    .update();
            for (int i = 0; i < names.size(); i++) {
                saved.put(new MetricKey(names.get(i), tags.get(i)), values.get(i));
            }
            recovered();
            return names.size();
        } catch (RuntimeException e) {
            failed("saving", e);
            return 0;
        }
    }

    @PreDestroy
    void saveOnShutdown() {
        save();
    }

    private void failed(String action, RuntimeException e) {
        if (!failing) {
            failing = true;
            log.warn("{} metric counters failed, retrying every save interval: {}",
                    Character.toUpperCase(action.charAt(0)) + action.substring(1), e.toString());
        } else {
            log.debug("{} metric counters failed again: {}", action, e.toString());
        }
    }

    private void recovered() {
        if (failing) {
            failing = false;
            log.info("Metric counters are saved again");
        }
    }
}
