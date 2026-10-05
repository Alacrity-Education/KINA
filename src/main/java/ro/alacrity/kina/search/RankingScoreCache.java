package ro.alacrity.kina.search;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.config.KinaProperties;

import java.time.Duration;

/**
 * In-memory cache of raw Laya scores (DESIGN.md section 3.3), keyed by {@code normalizedKey + "|" + partKey},
 * expiring {@code kina.ranking.score-cache-ttl} after write, at most {@value #MAX_ENTRIES} entries.
 */
@Component
public class RankingScoreCache {

    static final long MAX_ENTRIES = 50_000;

    private final Cache<String, Double> cache;

    @Autowired
    public RankingScoreCache(KinaProperties properties) {
        this(properties.ranking().scoreCacheTtl());
    }

    RankingScoreCache(Duration ttl) {
        this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(MAX_ENTRIES).build();
    }

    static String key(String normalizedKey, String partKey) {
        return normalizedKey + "|" + partKey;
    }

    /** Raw score, or null when absent or expired. */
    public Double get(String normalizedKey, String partKey) {
        return cache.getIfPresent(key(normalizedKey, partKey));
    }

    public void put(String normalizedKey, String partKey, double rawScore) {
        cache.put(key(normalizedKey, partKey), rawScore);
    }

    public long size() {
        return cache.estimatedSize();
    }

    public void clear() {
        cache.invalidateAll();
    }
}
