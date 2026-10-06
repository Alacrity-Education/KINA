package ro.alacrity.kina.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import ro.alacrity.kina.search.ce.CrossEncoderModel;
import ro.alacrity.kina.search.ce.ModelDownloader;

import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Builds the {@code list_distributors} payload. Never calls the Mouser/TME APIs (quota). */
@Service
@Slf4j
@RequiredArgsConstructor
public class DistributorStatusService {

    private final KinaProperties properties;
    private final DistributorRegistry registry;
    private final PartCacheRepository partCache;
    private final RankingService ranking;
    private final ObjectProvider<JlcpcbDatabaseManager> jlcpcb;

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
        RankingSummary rankingSummary = RankingSummary.builder()
                .mode(r.ready() ? "blended" : "fallback")
                .crossEncoderEnabled(r.crossEncoderEnabled())
                .ready(r.ready())
                .model(modelName(properties.ranking().crossEncoder().modelUrl()))
                .modelVariant(r.modelVariant())
                .modelRevision(r.modelRevision())
                .modelDir(r.modelDir())
                .threads(r.threads())
                .avgLatencyMs(r.avgLatencyMs())
                .lastError(r.lastError())
                .maxCandidates(r.maxCandidates())
                .weight(r.weight())
                .timeout(properties.ranking().timeout().toString())
                .build();
        return new DistributorStatusResponse(distributors, cache, rankingSummary);
    }

    /**
     * {@code cross-encoder/ms-marco-MiniLM-L6-v2} for a Hugging Face URL or for a local directory whose
     * {@code model.json} names that repository (the model bundled in the image), else the URL or path itself.
     */
    static String modelName(String modelUrl) {
        if (modelUrl == null) {
            return null;
        }
        int host = modelUrl.indexOf("huggingface.co/");
        int resolve = modelUrl.indexOf("/resolve/");
        if (host >= 0 && resolve > host) {
            return modelUrl.substring(host + "huggingface.co/".length(), resolve);
        }
        try {
            Path local = CrossEncoderModel.localSource(modelUrl);
            if (local != null) {
                String repo = ModelDownloader.readManifest(local).map(ModelDownloader.Manifest::repo).orElse(null);
                if (repo != null && !repo.isBlank()) {
                    return repo;
                }
            }
        } catch (RuntimeException e) {
            // not a usable path: report the configured value
        }
        return modelUrl;
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
