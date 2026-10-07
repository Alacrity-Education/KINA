package ro.alacrity.kina.metrics;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

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

    /** Writes the given values as they are, also lower ones (only {@link #transfer}). */
    static final String EXACT = """
            INSERT INTO metrics_counters (name, tags, value, updated_at)
            SELECT n, t, v, now() FROM unnest(?::text[], ?::text[], ?::bigint[]) AS u(n, t, v)
            ON CONFLICT (name, tags) DO UPDATE SET value = EXCLUDED.value, updated_at = EXCLUDED.updated_at""";

    @Autowired private JdbcClient jdbc;
    @Autowired private MetricsStore store;
    @Autowired private PlatformTransactionManager transactions;
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

    /** One move of the metrics backfill: up to {@code amount} from the counter {@code from} to {@code to}. */
    public record Transfer(MetricKey from, MetricKey to, long amount) {
    }

    /**
     * Applies {@code transfers} to the store ({@link MetricsStore#move}) and writes the changed counters to
     * {@code metrics_counters} with their exact values, in one transaction with {@code alsoInTransaction} (which
     * receives the amounts actually moved, in the order of {@code transfers}). This is the only write that lowers a
     * stored value: the regular {@link #save} keeps the larger one. The written values are remembered as saved, so the
     * next save does not write the source counters again. Holds the lock of {@link #save} throughout; on failure the
     * moves are undone in memory and the exception is thrown.
     *
     * @throws IllegalStateException when the stored counters have not been restored
     */
    public synchronized List<Long> transfer(List<Transfer> transfers, Consumer<List<Long>> alsoInTransaction) {
        if (!restored && !restore()) {
            throw new IllegalStateException("the metric counters are not restored from the database");
        }
        List<Long> moved = new ArrayList<>(transfers.size());
        Set<MetricKey> changed = new TreeSet<>();
        for (Transfer t : transfers) {
            long amount = store.move(t.from(), t.to(), t.amount());
            moved.add(amount);
            if (amount > 0) {
                changed.add(t.from());
                changed.add(t.to());
            }
        }
        Map<MetricKey, Long> written = new LinkedHashMap<>();
        changed.forEach(key -> written.put(key, store.get(key)));
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                if (!written.isEmpty()) {
                    jdbc.sql(EXACT)
                            .params(written.keySet().stream().map(MetricKey::name).toArray(String[]::new),
                                    written.keySet().stream().map(MetricKey::tags).toArray(String[]::new),
                                    written.values().toArray(Long[]::new))
                            .update();
                }
                alsoInTransaction.accept(moved);
            });
        } catch (RuntimeException e) {
            for (int i = transfers.size() - 1; i >= 0; i--) {
                Transfer t = transfers.get(i);
                store.move(t.to(), t.from(), moved.get(i));
            }
            throw e;
        }
        saved.putAll(written);
        return moved;
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
