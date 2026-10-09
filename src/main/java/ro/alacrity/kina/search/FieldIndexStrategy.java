package ro.alacrity.kina.search;

import ro.alacrity.kina.config.KinaProperties.FieldIndexMode;
import ro.alacrity.kina.distributor.DistributorClient;

/**
 * How one {@code kina.search.field-index.mode} searches a Mouser or TME distributor (DESIGN.md 3.8 "Modes"): one bean
 * per mode ({@link FieldIndexStrategies}), picked by {@link CachedDistributorRetriever}. {@code off}, {@code shadow}
 * and {@code augment} take the cached-search path ({@link CachedSearchPath}); {@code shadow} then compares the field
 * query in the background, {@code augment} adds the field candidates to a cached list; {@code on} is the field-first
 * flow ({@link FieldFirstSearch}) with the cached-search path as its fallback.
 */
interface FieldIndexStrategy {

    /** The mode this strategy implements. */
    FieldIndexMode mode();

    /** The search of one distributor under this mode (the requested-part lookup follows in the retriever). */
    Fetched search(DistributorClient client, Prepared prepared, Progress progress, DistributorBudget deadline);
}
