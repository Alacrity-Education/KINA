package ro.alacrity.kina.search;

import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.domain.Part;

import java.util.List;

/** Progress of a running fetch, readable when the fetch times out. */
final class Progress {
    volatile CacheStatus cache;
    volatile List<Part> parts = List.of();
    volatile Integer totalResults;
    volatile String fallbackQuery;
    volatile List<String> constraintsRelaxed = List.of();
    /** Matches without ships-now stock seen on the pages read so far (every phrase of the ladder). */
    volatile int outOfStock;

    Progress(CacheStatus cache) {
        this.cache = cache;
    }
}
