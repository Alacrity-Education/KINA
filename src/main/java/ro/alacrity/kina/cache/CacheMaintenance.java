package ro.alacrity.kina.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.Distributor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Periodic purge of the Postgres cache (DESIGN.md 3.2 "Cache model"): search lists older than {@code 2 x kina.cache.ttl}
 * and the parts of a distributor whose {@code kina.cache.metadata-retention} is finite and expired (by
 * {@code metadata_fetched_at}). With the default retention {@code forever} parts are never purged.
 */
@Component
@Slf4j
public class CacheMaintenance {

    /** The distributors whose parts are cached in PostgreSQL (LCSC's SQLite database is its own cache). */
    static final Distributor[] CACHED = {Distributor.TME, Distributor.MOUSER};

    private final PartCacheRepository parts;
    private final SearchCacheRepository searches;
    private final Clock clock;
    private final Duration searchRetention;
    private final Map<Distributor, Duration> metadataRetention = new EnumMap<>(Distributor.class);

    public CacheMaintenance(PartCacheRepository parts, SearchCacheRepository searches, Clock clock,
                            KinaProperties properties) {
        this.parts = parts;
        this.searches = searches;
        this.clock = clock;
        this.searchRetention = properties.cache().ttl().multipliedBy(2);
        for (Distributor d : CACHED) {
            Optional<Duration> retention = properties.cache().metadataRetention(d.name());
            retention.ifPresent(r -> metadataRetention.put(d, r));
            log.info("Cached {} metadata is kept {}", d, retention.map(r -> "for " + r).orElse("forever"));
        }
    }

    /** Rows deleted by one {@link #purge()} run. */
    public record PurgeResult(int parts, int searches) {
    }

    /** Every 6 hours; the first run is delayed so startup (and tests) are not raced. */
    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT6H")
    public void scheduledPurge() {
        try {
            purge();
        } catch (RuntimeException e) {
            log.warn("Cache purge failed: {}", e.toString());
        }
    }

    /**
     * Deletes the search lists fetched more than {@code 2 x kina.cache.ttl} before the clock's now and the parts whose
     * metadata retention expired.
     */
    public PurgeResult purge() {
        Instant now = clock.instant();
        Instant searchCutoff = now.minus(searchRetention);
        int deletedParts = 0;
        for (Map.Entry<Distributor, Duration> e : metadataRetention.entrySet()) {
            deletedParts += parts.deleteMetadataOlderThan(e.getKey(), now.minus(e.getValue()));
        }
        PurgeResult result = new PurgeResult(deletedParts, searches.deleteOlderThan(searchCutoff));
        log.info("Cache purge: {} cached_parts rows (metadata retention {}) and {} cached_searches rows (fetched "
                + "before {}) deleted", result.parts(), metadataRetention.isEmpty() ? "forever" : metadataRetention,
                result.searches(), searchCutoff);
        return result;
    }
}
