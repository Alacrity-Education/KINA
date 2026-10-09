package ro.alacrity.kina.search;

import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.domain.Part;

import java.util.List;

/**
 * Progress of one running fetch: shared mutable state, readable when the fetch times out or fails. One instance per
 * distributor and query, created by {@link ParallelRetrieval}. Only the fetch thread writes it (the retriever and
 * {@link PageCollector#collect}); the awaiting thread reads it, hence the {@code volatile} fields. {@code outOfStock}
 * is cumulative over every page and ladder rung, and {@code collect} starts from {@code totalResults}: pass the same
 * instance through every rung. Never copy it or turn it into a record.
 */
final class Progress {
    volatile CacheStatus cache;
    volatile List<Part> parts = List.of();
    volatile Integer totalResults;
    volatile String fallbackQuery;
    volatile List<String> constraintsRelaxed = List.of();
    /** Matches without ships-now stock seen on the pages read so far (every phrase of the ladder). */
    volatile int outOfStock;
    /** True once a distributor call of this retrieval has answered. */
    volatile boolean fetchedLive;
    /**
     * Distributor search calls this retrieval made (one per page, every phrase, failed calls included; never LCSC,
     * whose database is local): the {@code live_calls} of the result.
     */
    final java.util.concurrent.atomic.AtomicInteger liveCalls = new java.util.concurrent.atomic.AtomicInteger();
    /** Steps of the field query evaluated so far (the field-first flow; 0 otherwise). */
    volatile int fieldSteps;

    Progress(CacheStatus cache) {
        this.cache = cache;
    }
}
