package ro.alacrity.kina.cache;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Periodic purge of the Postgres cache (DESIGN.md 3.2 "Cache model"): search lists and journal phrases older than
 * {@code 2 x kina.cache.ttl} and the parts of a distributor whose {@code kina.cache.metadata-retention} is finite and expired (by
 * {@code metadata_fetched_at}). With the default retention {@code forever} parts are never purged.
 */
@Component
@Slf4j
public class CacheMaintenance {

    /** The distributors whose parts are cached in PostgreSQL (LCSC's SQLite database is its own cache). */
    static final Distributor[] CACHED = {Distributor.TME, Distributor.MOUSER};

    @Autowired private PartCacheRepository parts;
    @Autowired private SearchCacheRepository searches;
    /** The phrase journal (V15); null in tests that build the job by hand. */
    @Autowired(required = false) private PhraseJournalRepository phrases;
    @Autowired private Clock clock;
    @Autowired private KinaProperties properties;
    private final Map<Distributor, Duration> metadataRetention = new EnumMap<>(Distributor.class);

    @PostConstruct
    void readRetention() {
        for (Distributor d : CACHED) {
            Optional<Duration> retention = properties.cache().metadataRetention(d.name());
            retention.ifPresent(r -> metadataRetention.put(d, r));
            log.info("Cached {} metadata is kept {}", d, retention.map(r -> "for " + r).orElse("forever"));
        }
    }

    /** Rows deleted by one {@link #purge()} run. */
    public record PurgeResult(int parts, int searches, int phrases) {

        public PurgeResult(int parts, int searches) {
            this(parts, searches, 0);
        }
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
        Instant searchCutoff = now.minus(properties.cache().ttl().multipliedBy(2));
        int deletedParts = 0;
        for (Map.Entry<Distributor, Duration> e : metadataRetention.entrySet()) {
            deletedParts += parts.deleteMetadataOlderThan(e.getKey(), now.minus(e.getValue()));
        }
        int deletedSearches = searches.deleteOlderThan(searchCutoff);
        int deletedPhrases = phrases == null ? 0 : phrases.deleteOlderThan(searchCutoff);
        PurgeResult result = new PurgeResult(deletedParts, deletedSearches, deletedPhrases);
        log.info("Cache purge: {} cached_parts rows (metadata retention {}), {} cached_searches rows and {} "
                + "distributor_phrases rows (fetched or asked before {}) deleted", result.parts(),
                metadataRetention.isEmpty() ? "forever" : metadataRetention, result.searches(), result.phrases(),
                searchCutoff);
        return result;
    }
}
