package ro.alacrity.kina.metrics;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory values of KINA's counters and timers (DESIGN.md 3.7): one {@link AtomicLong} per {@link MetricKey}, exported
 * to Micrometer as a {@link FunctionCounter} (or, for the {@code :count}/{@code :nanos} pair of a timer, a
 * {@link FunctionTimer}) the first time the key appears. {@link MetricsPersistence} saves {@link #snapshot()} to
 * PostgreSQL and adds the stored values back with {@link #restore(Map)} on startup, so the exported series only grow
 * across restarts. Thread-safe and lock-free on the increment path.
 */
@Slf4j
public class MetricsStore {

    /** Suffix of a timer's event count. */
    public static final String COUNT = ":count";
    /** Suffix of a timer's total time in nanoseconds. */
    public static final String NANOS = ":nanos";

    private final MeterRegistry registry;
    private final ConcurrentHashMap<MetricKey, AtomicLong> values = new ConcurrentHashMap<>();
    /** Strong references to the timer cells (Micrometer holds function meter state weakly). */
    private final ConcurrentHashMap<MetricKey, TimerCells> timers = new ConcurrentHashMap<>();
    private final Set<MetricKey> registered = ConcurrentHashMap.newKeySet();

    /** Count and total nanoseconds of one timer. */
    record TimerCells(AtomicLong count, AtomicLong nanos) {
    }

    /**
     * @param registry where new meters are registered; null keeps the values in memory only (tests, no-op metrics)
     */
    public MetricsStore(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Adds {@code amount} (ignored when not positive) to a counter. */
    public void add(MetricKey key, long amount) {
        if (amount > 0) {
            cell(key).addAndGet(amount);
        }
    }

    /** Adds one to a counter. */
    public void increment(MetricKey key) {
        cell(key).incrementAndGet();
    }

    /** Records one timed event of {@code nanos} on the timer {@code key} (a key without the suffixes). */
    public void record(MetricKey key, long nanos) {
        TimerCells cells = timer(key);
        cells.count().incrementAndGet();
        cells.nanos().addAndGet(Math.max(0, nanos));
    }

    /** The current value of a counter (or of a timer half), 0 when it never moved. */
    public long get(MetricKey key) {
        AtomicLong cell = values.get(key);
        return cell == null ? 0 : cell.get();
    }

    /** Sum over every tag combination of {@code name}. */
    public long sum(String name) {
        long total = 0;
        for (Map.Entry<MetricKey, AtomicLong> e : values.entrySet()) {
            if (e.getKey().name().equals(name)) {
                total += e.getValue().get();
            }
        }
        return total;
    }

    /** Sums of {@code name} grouped by the value of the tag {@code tagKey}, sorted by that value. */
    public Map<String, Long> sumBy(String name, String tagKey) {
        Map<String, Long> out = new TreeMap<>();
        values.forEach((key, cell) -> {
            if (key.name().equals(name)) {
                String tag = key.tag(tagKey);
                out.merge(tag == null ? "none" : tag, cell.get(), Long::sum);
            }
        });
        return out;
    }

    /** A consistent-enough copy of every value, sorted by key (each value is read atomically). */
    public Map<MetricKey, Long> snapshot() {
        Map<MetricKey, Long> out = new TreeMap<>();
        values.forEach((key, cell) -> out.put(key, cell.get()));
        return Collections.unmodifiableMap(out);
    }

    /**
     * Adds stored values (from the previous run) to the in-memory ones, registering their meters. Values counted in
     * this run before the restore are kept: in-memory = stored + counted since start.
     */
    public void restore(Map<MetricKey, Long> stored) {
        stored.forEach((key, value) -> {
            if (value != null && value > 0) {
                cell(key).addAndGet(value);
            } else {
                cell(key);
            }
        });
    }

    private AtomicLong cell(MetricKey key) {
        AtomicLong cell = values.get(key);
        if (cell != null) {
            return cell;
        }
        String name = key.name();
        if (name.endsWith(COUNT) || name.endsWith(NANOS)) {
            TimerCells cells = timer(key.withName(name.substring(0, name.lastIndexOf(':'))));
            return name.endsWith(COUNT) ? cells.count() : cells.nanos();
        }
        AtomicLong created = new AtomicLong();
        AtomicLong existing = values.putIfAbsent(key, created);
        if (existing != null) {
            return existing;
        }
        if (registry != null && registered.add(key)) {
            try {
                FunctionCounter.builder(name, created, AtomicLong::doubleValue)
                        .description(MetricNames.description(name))
                        .tags(key.micrometerTags())
                        .register(registry);
            } catch (RuntimeException e) {
                log.warn("Cannot register counter {}: {}", key, e.toString());
            }
        }
        return created;
    }

    private TimerCells timer(MetricKey key) {
        TimerCells cells = timers.get(key);
        if (cells != null) {
            return cells;
        }
        AtomicLong count = values.computeIfAbsent(key.withName(key.name() + COUNT), k -> new AtomicLong());
        AtomicLong nanos = values.computeIfAbsent(key.withName(key.name() + NANOS), k -> new AtomicLong());
        TimerCells created = new TimerCells(count, nanos);
        TimerCells existing = timers.putIfAbsent(key, created);
        if (existing != null) {
            return existing;
        }
        if (registry != null && registered.add(key)) {
            try {
                FunctionTimer.builder(key.name(), created, c -> c.count().get(), c -> c.nanos().get(),
                                TimeUnit.NANOSECONDS)
                        .description(MetricNames.description(key.name()))
                        .tags(key.micrometerTags())
                        .register(registry);
            } catch (RuntimeException e) {
                log.warn("Cannot register timer {}: {}", key, e.toString());
            }
        }
        return created;
    }
}
