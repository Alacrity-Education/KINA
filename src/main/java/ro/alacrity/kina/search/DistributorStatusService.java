package ro.alacrity.kina.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import ro.alacrity.kina.cache.CacheStatistics;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.distributor.DistributorRegistry;
import ro.alacrity.kina.distributor.lcsc.JlcpcbDatabaseManager;
import ro.alacrity.kina.distributor.lcsc.JlcpcbStatus;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.DistributorStatusResponse;
import ro.alacrity.kina.domain.DistributorStatusResponse.CacheSummary;
import ro.alacrity.kina.domain.DistributorStatusResponse.DistributorStatus;
import ro.alacrity.kina.domain.DistributorStatusResponse.JlcpcbSummary;
import ro.alacrity.kina.domain.DistributorStatusResponse.RankingSummary;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Builds the {@code list_distributors} payload. Never calls the Mouser/TME APIs (quota). */
@Service
public class DistributorStatusService {

    private static final Logger log = LoggerFactory.getLogger(DistributorStatusService.class);

    private final KinaProperties properties;
    private final DistributorRegistry registry;
    private final PartCacheRepository partCache;
    private final RankingService ranking;
    private final ObjectProvider<JlcpcbDatabaseManager> jlcpcb;

    public DistributorStatusService(KinaProperties properties, DistributorRegistry registry,
                                    PartCacheRepository partCache, RankingService ranking,
                                    ObjectProvider<JlcpcbDatabaseManager> jlcpcb) {
        this.properties = properties;
        this.registry = registry;
        this.partCache = partCache;
        this.ranking = ranking;
        this.jlcpcb = jlcpcb;
    }

    public DistributorStatusResponse status() {
        CacheStatistics stats = null;
        try {
            stats = partCache.stats();
        } catch (RuntimeException e) {
            log.warn("Reading cache statistics failed: {}", e.toString());
        }
        List<DistributorStatus> distributors = new ArrayList<>();
        for (Distributor distributor : Distributor.values()) {
            distributors.add(status(distributor, stats));
        }
        CacheSummary cache = stats == null ? null : new CacheSummary(properties.cache().ttl().toString(),
                stats.parts(), stats.freshParts(), stats.searches(), stats.oldestFetch());
        RankingService.RankingStatus r = ranking.status();
        RankingSummary rankingSummary = new RankingSummary(r.layaEnabled(), r.layaHealthy(), r.model(),
                r.maxCandidates(), r.weight(), properties.ranking().timeout().toString());
        return new DistributorStatusResponse(distributors, cache, rankingSummary);
    }

    private DistributorStatus status(Distributor distributor, CacheStatistics stats) {
        Optional<DistributorClient> client = registry.find(distributor);
        boolean configured = client.map(DistributorClient::isConfigured).orElse(false);
        boolean usesCache = PartSearchService.usesPostgresCache(distributor);
        Long cachedParts = usesCache && stats != null ? stats.partsByDistributor().getOrDefault(distributor, 0L) : null;
        return switch (distributor) {
            case MOUSER -> new DistributorStatus(distributor, configured, configured,
                    configured ? "Mouser Search API (keyword search, in-stock only); daily quota about 1000 calls"
                            : "not configured: MOUSER_API_KEY is not set",
                    true, cachedParts, properties.distributors().mouser().maxResultsPerSearch(), null);
            case TME -> {
                KinaProperties.Tme tme = properties.distributors().tme();
                yield new DistributorStatus(distributor, configured, configured,
                        configured ? "TME API v2 (country " + tme.country() + ", currency " + tme.currency()
                                + "; in-stock only, excluding product statuses " + tme.excludedStatuses() + ")"
                                : "not configured: TME_TOKEN / TME_APPLICATION_SECRET are not set",
                        true, cachedParts, tme.maxResultsPerSearch(), null);
            }
            case LCSC -> lcsc(configured);
        };
    }

    private DistributorStatus lcsc(boolean configured) {
        JlcpcbDatabaseManager manager = jlcpcb.getIfAvailable();
        JlcpcbStatus s = null;
        try {
            s = manager == null ? null : manager.status().orElse(null);
        } catch (RuntimeException e) {
            log.warn("Reading the JLCPCB database status failed: {}", e.toString());
        }
        int max = properties.jlcpcb().maxResultsPerSearch();
        if (s == null) {
            return new DistributorStatus(Distributor.LCSC, configured, false, "JLCPCB database status unknown",
                    false, null, max, null);
        }
        String detail;
        if (s.available()) {
            detail = "JLCPCB parts database " + s.library()
                    + (s.partCount() == null ? "" : ", " + NumberFormat.getIntegerInstance(Locale.ROOT)
                    .format(s.partCount()) + " parts")
                    + (s.sourceDate() == null ? "" : ", source date " + s.sourceDate())
                    + (s.downloadedAt() == null ? "" : ", downloaded " + s.downloadedAt())
                    + (s.downloading() ? "; a refresh download is running" : "");
        } else if (s.downloading()) {
            detail = "JLCPCB parts database is being downloaded; LCSC searches are unavailable until it finishes";
        } else {
            detail = "JLCPCB parts database not downloaded yet"
                    + (s.lastError() == null ? "" : " (last error: " + s.lastError() + ")");
        }
        JlcpcbSummary summary = new JlcpcbSummary(s.available(), s.library(), s.downloadedAt(), s.sourceDate(),
                s.partCount(), s.downloading(), s.lastError());
        return new DistributorStatus(Distributor.LCSC, configured, s.available(), detail, false, null, max, summary);
    }
}
