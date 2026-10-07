package ro.alacrity.kina.search;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.Deadline;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.distributor.DistributorRegistry;
import ro.alacrity.kina.distributor.PartLookupResult;
import ro.alacrity.kina.distributor.StockUpdate;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PartLookupResponse;
import ro.alacrity.kina.domain.PartResponse;
import ro.alacrity.kina.domain.ResponseDetail;
import ro.alacrity.kina.metrics.KinaMetrics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

/**
 * Looks up one part by distributor part number ({@code get_part}; the clients also accept a manufacturer part number
 * where a retry is cheap, see {@link DistributorClient#lookup}). A part the distributor lists without ships-now stock is
 * reported as {@code out_of_stock} with its identity and, when the distributor gives its data, the part itself with
 * stock 0 (the part number was requested explicitly: the one exception to the stock rule, DESIGN.md 2); its cached
 * row carries {@code in_stock = false} and is never served from the cache. Mouser/TME: the
 * {@code cached_parts} row unless {@code bypassCache} (its stock refreshed when older than {@code kina.cache.stock-ttl};
 * beyond {@code kina.cache.ttl} without a refresh the part is looked up live and served with {@code stale: true} only
 * when that fails or the distributor is not configured), else the distributor, caching the result. LCSC: the JLCPCB
 * database.
 */
@Service
@Slf4j
public class PartLookupService {

    @Autowired private KinaProperties properties;
    @Autowired private DistributorRegistry registry;
    @Autowired private ParametricExtractor extractor;
    @Autowired private PartCacheRepository partCache;
    @Autowired private Clock clock;
    @Autowired private KinaMetrics metrics = KinaMetrics.NOOP;
    private final ExecutorService executor =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("kina-lookup-", 0).factory());

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * The part, or empty when the distributor does not know it or has no ships-now stock for it.
     *
     * @throws DistributorException     when the distributor is not configured or the lookup failed
     * @throws IllegalArgumentException when the part number is blank
     */
    public Optional<PartResponse> getPart(Distributor distributor, String partNumber, boolean bypassCache) {
        return Optional.ofNullable(lookup(distributor, partNumber, bypassCache).part()).filter(p -> p.stock() > 0);
    }

    /**
     * Like {@link #getPart} but also reports the cache status.
     *
     * @throws DistributorException     when the distributor is not configured or the lookup failed
     * @throws IllegalArgumentException when the part number is blank
     */
    public PartLookupResponse lookup(Distributor distributor, String partNumber, boolean bypassCache) {
        return lookup(distributor, partNumber, bypassCache, 1, ResponseDetail.FULL);
    }

    /**
     * Like {@link #lookup(Distributor, String, boolean)} at the given detail level, priced for an order of
     * {@code quantity} pieces (DESIGN.md 4).
     *
     * @throws DistributorException     when the distributor is not configured or the lookup failed
     * @throws IllegalArgumentException when the part number is blank
     */
    public PartLookupResponse lookup(Distributor distributor, String partNumber, boolean bypassCache, int quantity,
                                     ResponseDetail detail) {
        if (distributor == null) {
            throw new IllegalArgumentException("distributor is required");
        }
        if (partNumber == null || partNumber.isBlank()) {
            throw new IllegalArgumentException("part_number must not be blank");
        }
        String number = partNumber.strip();
        Optional<DistributorClient> configured = registry.find(distributor).filter(DistributorClient::isConfigured);
        boolean cached = DistributorRetriever.usesPostgresCache(distributor);
        if (configured.isEmpty() && !cached) {
            throw DistributorException.notConfigured(distributor);
        }
        Part stale = null;
        if (cached && (!bypassCache || configured.isEmpty())) {
            Optional<Part> hit = readCache(distributor, number);
            if (hit.isPresent()) {
                Cached c = refreshed(configured.orElse(null), hit.get());
                if (c.part() != null && !c.stale()) {
                    return PartLookupResponse.found(distributor, number, CacheStatus.HIT,
                            response(extractor.enrich(c.part()), quantity, detail, false));
                }
                stale = c.part() == null ? null : extractor.enrich(c.part());   // null when sold out: looked up live
            }
        }
        if (configured.isEmpty()) {
            // the distributor is not configured: cached metadata is still served, its stock and prices marked stale
            if (stale != null) {
                return PartLookupResponse.found(distributor, number, CacheStatus.HIT,
                        response(stale, quantity, detail, true));
            }
            throw DistributorException.notConfigured(distributor);
        }
        DistributorClient client = configured.get();
        CacheStatus status = !cached ? CacheStatus.NOT_APPLICABLE
                : bypassCache ? CacheStatus.BYPASSED : CacheStatus.MISS;
        PartLookupResult result;
        try {
            result = fetch(client, number);
        } catch (DistributorException e) {
            if (stale != null) {
                log.info("{} lookup of {} failed ({}); serving the cached part with stale stock", distributor, number,
                        e.errorCode());
                return PartLookupResponse.found(distributor, number, CacheStatus.HIT,
                        response(stale, quantity, detail, true));
            }
            throw e;
        }
        if (result.status() == PartLookupResult.Status.OUT_OF_STOCK && result.identity() != null) {
            if (stale != null) {
                markSoldOut(distributor, stale.distributorPartNumber());
            }
            // the part number was requested explicitly: the listed part is returned with stock 0 (DESIGN.md 2)
            Part listed = result.listed().map(this::prepare).orElse(null);
            if (listed != null && cached) {
                try {
                    partCache.upsertListed(List.of(listed));
                } catch (RuntimeException e) {
                    log.warn("Caching listed {} part {} failed: {}", distributor, number, e.toString());
                }
            }
            PartLookupResult.Identity id = result.identity();
            return PartLookupResponse.outOfStock(distributor, number, status, new PartLookupResponse.Identity(
                    id.partNumber(), id.manufacturer(), id.mpn(), id.description()),
                    listed == null ? null : response(listed, quantity, detail, false));
        }
        Optional<Part> part = result.asOptional().filter(p -> p.stock() > 0).map(this::prepare);
        if (part.isEmpty()) {
            return PartLookupResponse.notFound(distributor, number, status, null);
        }
        if (cached) {
            try {
                partCache.upsertAll(List.of(part.get()));
            } catch (RuntimeException e) {
                log.warn("Caching {} part {} failed: {}", distributor, number, e.toString());
            }
        }
        return PartLookupResponse.found(distributor, number, status, response(part.get(), quantity, detail, false));
    }

    private PartResponse response(Part part, int quantity, ResponseDetail detail, boolean stale) {
        ResponseDetail d = detail == null ? ResponseDetail.FULL : detail;
        return PartResponse.of(part, PartResponse.Ranking.NONE, quantity, d,
                d == ResponseDetail.FULL ? null : extractor.extract(part), properties.search().lowStockThreshold(),
                stale, clock.instant());
    }

    /**
     * A cached part after its stock refresh: {@code part} null when the refresh found it sold out; {@code stale} when its
     * stock and prices are older than {@code kina.cache.ttl} and could not be refreshed.
     */
    private record Cached(Part part, boolean stale) {
    }

    /**
     * The cached part, with its stock and prices refreshed when they are older than {@code kina.cache.stock-ttl}
     * (DESIGN.md 3.2 "Stock refresh"; {@code client} null when the distributor is not configured: no refresh). Sold out:
     * the row keeps its metadata, is marked sold out, and the part is looked up live (which reports
     * {@code out_of_stock} with its identity). A failed refresh keeps the cached figures; beyond {@code kina.cache.ttl}
     * they are stale (the part is then looked up live, and served stale if that fails too).
     */
    private Cached refreshed(DistributorClient client, Part part) {
        Instant now = clock.instant();
        if (part.fetchedAt() != null && !part.fetchedAt().isBefore(now.minus(properties.cache().stockTtl()))) {
            return new Cached(part, false);
        }
        if (client != null) {
            try {
                StockUpdate update = client.refreshStock(List.of(part.distributorPartNumber()),
                        Deadline.after(properties.search().distributorTimeout())).get(part.distributorPartNumber());
                if (update != null && update.stock() <= 0) {
                    metrics.stockRefreshed(part.distributor(), "out_of_stock", 1);
                    markSoldOut(part.distributor(), part.distributorPartNumber());
                    return new Cached(null, false);
                }
                if (update != null) {
                    metrics.stockRefreshed(part.distributor(), "ok", 1);
                    Part fresh = part.toBuilder().stock(update.stock())
                            .prices(update.prices().isEmpty() ? part.prices() : update.prices()).fetchedAt(now).build();
                    partCache.updateStock(List.of(fresh));
                    return new Cached(fresh, false);
                }
                metrics.stockRefreshed(part.distributor(), "failed", 1);
            } catch (RuntimeException e) {
                metrics.stockRefreshed(part.distributor(), "failed", 1);
                log.info("{} stock refresh of {} failed, keeping the cached figures: {}", part.distributor(),
                        part.distributorPartNumber(), e.toString());
            }
        }
        boolean stale = part.fetchedAt() != null && part.fetchedAt().isBefore(now.minus(properties.cache().ttl()));
        return new Cached(part, stale);
    }

    private void markSoldOut(Distributor distributor, String partNumber) {
        try {
            partCache.markSoldOut(distributor, partNumber);
        } catch (RuntimeException e) {
            log.warn("Marking {} part {} sold out failed: {}", distributor, partNumber, e.toString());
        }
    }

    private Optional<Part> readCache(Distributor distributor, String partNumber) {
        try {
            return partCache.find(distributor, partNumber);
        } catch (RuntimeException e) {
            log.warn("Reading cached {} part {} failed: {}", distributor, partNumber, e.toString());
            return Optional.empty();
        }
    }

    private Part prepare(Part part) {
        Part withTime = part.fetchedAt() != null ? part : part.toBuilder().fetchedAt(clock.instant()).build();
        return extractor.enrich(withTime);
    }

    /**
     * {@link DistributorClient#lookup} bounded by {@code kina.search.distributor-timeout} of active work, extended by
     * rate-limit waits up to {@code kina.search.max-request-duration} (DESIGN.md 3.6).
     */
    private PartLookupResult fetch(DistributorClient client, String partNumber) {
        Duration timeout = properties.search().distributorTimeout();
        DistributorBudget budget = new DistributorBudget(
                Deadline.after(properties.search().maxRequestDuration()), timeout);
        Future<PartLookupResult> future = executor.submit(() -> client.lookup(partNumber, budget.deadline()));
        try {
            PartLookupResult result = budget.await(future, Duration.ZERO);
            return result == null ? PartLookupResult.notFound() : result;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new DistributorException(client.distributor(), DistributorException.Kind.TIMEOUT,
                    "no answer within " + Durations.format(timeout));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new DistributorException(client.distributor(), DistributorException.Kind.TIMEOUT, "interrupted");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof DistributorException de) {
                throw de;
            }
            log.warn("{} part lookup failed unexpectedly", client.distributor(), e.getCause());
            throw new DistributorException(client.distributor(), DistributorException.Kind.UNAVAILABLE,
                    "lookup failed", e.getCause());
        }
    }
}
