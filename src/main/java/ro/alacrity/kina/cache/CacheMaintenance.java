package ro.alacrity.kina.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.config.KinaProperties;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Periodically purges rows older than {@code 2 x kina.cache.ttl} from both cache tables. */
@Component
@Slf4j
public class CacheMaintenance {

    private final PartCacheRepository parts;
    private final SearchCacheRepository searches;
    private final Clock clock;
    private final Duration retention;

    public CacheMaintenance(PartCacheRepository parts, SearchCacheRepository searches, Clock clock,
                            KinaProperties properties) {
        this.parts = parts;
        this.searches = searches;
        this.clock = clock;
        this.retention = properties.cache().ttl().multipliedBy(2);
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

    /** Deletes every row fetched more than {@code 2 x kina.cache.ttl} before the clock's now. */
    public PurgeResult purge() {
        Instant cutoff = clock.instant().minus(retention);
        PurgeResult result = new PurgeResult(parts.deleteOlderThan(cutoff), searches.deleteOlderThan(cutoff));
        log.info("Cache purge (fetched before {}): {} cached_parts and {} cached_searches rows deleted", cutoff,
                result.parts(), result.searches());
        return result;
    }
}
