package ro.alacrity.kina.search.field;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.metrics.KinaMetrics;
import ro.alacrity.kina.search.ParametricExtractor;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * The field index re-index job (DESIGN.md 3.8), in the style of the metrics backfill: once in the background after
 * {@link ApplicationReadyEvent} (never blocking startup), it writes an index row for every {@code cached_parts} row
 * whose index row is missing or not current ({@link PartIndexRepository#reindexStale}): in-stock and sold-out rows
 * alike, {@code kina.search.field-index.reindex-batch-size} (500) at a time, on one thread. Until it has covered a
 * distributor, {@link PartIndexRepository#isComplete} is false for it and callers keep the cached-search path. Logs one
 * INFO line with the counts and counts {@code kina_field_index_reindexed_total}. A failure is logged once and never
 * affects a request; the next start, or {@link #run()}, tries again.
 */
@Slf4j
@Component
public class PartIndexReindexer {

    @Autowired private KinaProperties properties;
    @Autowired private PartIndexRepository index;
    @Autowired private KinaMetrics metrics = KinaMetrics.NOOP;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<PartIndexRepository.ReindexReport> last = new AtomicReference<>();

    /** After startup: one background run when enabled. */
    @EventListener(ApplicationReadyEvent.class)
    void onApplicationReady() {
        if (properties.search().fieldIndex().reindexEnabled()) {
            start();
        }
    }

    /** Starts a run on a background thread. */
    public void start() {
        Thread.ofVirtual().name("field-index-reindex").start(this::run);
    }

    /** True while a run is in progress. */
    public boolean running() {
        return running.get();
    }

    /** The last completed run, empty before the first one. */
    public Optional<PartIndexRepository.ReindexReport> lastRun() {
        return Optional.ofNullable(last.get());
    }

    /** The field index state with the progress of the re-index (computed now). */
    public FieldIndexStatus status() {
        Map<Distributor, PartIndexRepository.Coverage> c = index.coverage();
        return new FieldIndexStatus(properties.search().fieldIndex().mode().name().toLowerCase(Locale.ROOT),
                c.values().stream().mapToLong(PartIndexRepository.Coverage::indexed).sum(),
                c.values().stream().mapToLong(PartIndexRepository.Coverage::stale).sum(),
                ParametricExtractor.INDEX_VERSION, running(),
                c.values().stream().filter(x -> !x.complete()).map(x -> x.distributor().name()).toList());
    }

    /** One run; empty when another run is in progress or the run failed. Never throws. */
    public Optional<PartIndexRepository.ReindexReport> run() {
        if (!running.compareAndSet(false, true)) {
            return Optional.empty();
        }
        try {
            PartIndexRepository.ReindexReport report = index.reindexStale(ParametricExtractor.INDEX_VERSION,
                    properties.search().fieldIndex().reindexBatchSize());
            report.written().forEach((d, n) -> metrics.fieldIndexReindexed(d.name(), n));
            last.set(report);
            log.info("Field index re-index (version {}): wrote {} rows ({}), {} unreadable payloads; {} ms",
                    ParametricExtractor.INDEX_VERSION, report.total(), report.written().entrySet().stream()
                            .map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", ")),
                    report.unreadable(), report.millis());
            return Optional.of(report);
        } catch (RuntimeException e) {
            log.warn("Field index re-index failed; the index is incomplete until the next run: {}", e.toString());
            return Optional.empty();
        } finally {
            running.set(false);
        }
    }
}
