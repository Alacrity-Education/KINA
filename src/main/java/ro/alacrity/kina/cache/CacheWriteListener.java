package ro.alacrity.kina.cache;

import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;

import java.util.Collection;

/**
 * Follows the writes of {@link PartCacheRepository} (the field index, DESIGN.md 3.8). The repository asks for the work
 * of every payload write (an upsert, a listed part, a stock refresh) before its transaction ({@link #upserting}: the expensive part, extraction, runs outside it) and runs
 * the returned work inside the same transaction as the payload write. A listener isolates its own failures: the cache
 * write never fails because of it.
 */
public interface CacheWriteListener {

    /** Nothing to do. */
    Runnable NOTHING = () -> { };

    /**
     * The work for {@code parts} about to be written to {@code cached_parts} with {@code in_stock = inStock}; run in
     * the write's transaction after the payloads are written.
     */
    Runnable upserting(Collection<Part> parts, boolean inStock);

    /**
     * {@code cached_parts.in_stock} of {@code partNumbers} became {@code inStock} without a payload write (a part a
     * stock refresh found sold out); run in the write's transaction.
     */
    void stockChanged(Distributor distributor, Collection<String> partNumbers, boolean inStock);
}
