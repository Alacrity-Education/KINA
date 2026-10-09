package ro.alacrity.kina.search.field;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.cache.PartCacheRepository;
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
 * {@link ApplicationReadyEvent} (never blocking startup) and then every {@code kina.search.field-index.reindex-interval}
 * (1 hour) as a safety net, on one thread. A run first fills the metadata hashes of rows written before V16
 * ({@link PartCacheRepository#backfillMetadataHashes}, then {@link PartIndexRepository#backfillMetadataHashes}: no
 * extraction), then writes an index row for every {@code cached_parts} row whose index row is missing or not current
 * ({@link PartIndexRepository#reindexStale}): in-stock and sold-out rows alike,
 * {@code kina.search.field-index.reindex-batch-size} (500) at a time. Until it has covered a distributor,
 * {@link PartIndexRepository#isComplete} is false for it and callers keep the cached-search path. Logs one INFO line
 * with the counts and counts {@code kina_field_index_reindexed_total}; the rows a periodic sweep had to rewrite also
 * count {@code kina_field_index_sweep_repaired_total} (every cache write keeps its rows current, so it should stay 0).
 * A failure is logged once and never affects a request; the next sweep tries again.
 */
@Slf4j
@Component
public class PartIndexReindexer {

    @Autowired private KinaProperties properties;
    @Autowired private PartIndexRepository index;
    @Autowired(required = false) private PartCacheRepository cache;
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

    /**
     * The periodic sweep: re-indexes the rows that are not current, on the scheduler thread (one run at a time; a
     * sweep that finds the startup run still going does nothing).
     */
    @Scheduled(initialDelayString = "${kina.search.field-index.reindex-interval:1h}",
            fixedDelayString = "${kina.search.field-index.reindex-interval:1h}")
    void sweep() {
        if (properties.search().fieldIndex().reindexEnabled()) {
            run(true);
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

    /** One run (the startup kind); empty when another run is in progress or the run failed. Never throws. */
    public Optional<PartIndexRepository.ReindexReport> run() {
        return run(false);
    }

    /**
     * One run; {@code sweep}: a periodic one, whose rewritten rows count as repaired. Empty when another run is in
     * progress or the run failed. Never throws.
     */
    public Optional<PartIndexRepository.ReindexReport> run(boolean sweep) {
        if (!running.compareAndSet(false, true)) {
            return Optional.empty();
        }
        try {
            int batch = properties.search().fieldIndex().reindexBatchSize();
            long cacheHashes = cache == null ? 0 : cache.backfillMetadataHashes(batch);
            long indexHashes = index.backfillMetadataHashes();
            PartIndexRepository.ReindexReport report = index.reindexStale(ParametricExtractor.INDEX_VERSION, batch);
            report.written().forEach((d, n) -> {
                metrics.fieldIndexReindexed(d.name(), n);
                if (sweep) {
                    metrics.fieldIndexSweepRepaired(d.name(), n);
                }
            });
            last.set(report);
            log.info("Field index {} (version {}): wrote {} rows ({}), {} unreadable payloads, {} cache and {} index "
                            + "metadata hashes filled; {} ms", sweep ? "sweep" : "re-index",
                    ParametricExtractor.INDEX_VERSION, report.total(), report.written().entrySet().stream()
                            .map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", ")),
                    report.unreadable(), cacheHashes, indexHashes, report.millis());
            return Optional.of(report);
        } catch (RuntimeException e) {
            log.warn("Field index {} failed; stale rows stay stale until the next sweep: {}",
                    sweep ? "sweep" : "re-index", e.toString());
            return Optional.empty();
        } finally {
            running.set(false);
        }
    }
}
