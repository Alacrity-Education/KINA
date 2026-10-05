package ro.alacrity.kina.search;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.Deadline;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.distributor.DistributorRegistry;
import ro.alacrity.kina.distributor.PartLookupResult;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PartLookupResponse;
import ro.alacrity.kina.domain.PartResponse;

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
 * reported as {@code out_of_stock} with its identity, never returned as a part and never cached. Mouser/TME: a fresh
 * {@code cached_parts} row unless {@code bypassCache}, else the distributor, caching the result. LCSC: the JLCPCB
 * database.
 */
@Service
@Slf4j
public class PartLookupService {

    private final KinaProperties properties;
    private final DistributorRegistry registry;
    private final ParametricExtractor extractor;
    private final PartCacheRepository partCache;
    private final Clock clock;
    private final ExecutorService executor;

    public PartLookupService(KinaProperties properties, DistributorRegistry registry, ParametricExtractor extractor,
                             PartCacheRepository partCache, Clock clock) {
        this.properties = properties;
        this.registry = registry;
        this.extractor = extractor;
        this.partCache = partCache;
        this.clock = clock;
        this.executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("kina-lookup-", 0).factory());
    }

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
        return Optional.ofNullable(lookup(distributor, partNumber, bypassCache).part());
    }

    /**
     * Like {@link #getPart} but also reports the cache status.
     *
     * @throws DistributorException     when the distributor is not configured or the lookup failed
     * @throws IllegalArgumentException when the part number is blank
     */
    public PartLookupResponse lookup(Distributor distributor, String partNumber, boolean bypassCache) {
        if (distributor == null) {
            throw new IllegalArgumentException("distributor is required");
        }
        if (partNumber == null || partNumber.isBlank()) {
            throw new IllegalArgumentException("part_number must not be blank");
        }
        String number = partNumber.strip();
        DistributorClient client = registry.find(distributor)
                .filter(DistributorClient::isConfigured)
                .orElseThrow(() -> DistributorException.notConfigured(distributor));
        boolean cached = PartSearchService.usesPostgresCache(distributor);

        if (cached && !bypassCache) {
            Optional<Part> hit = readCache(distributor, number);
            if (hit.isPresent()) {
                return PartLookupResponse.found(distributor, number, CacheStatus.HIT,
                        PartResponse.from(extractor.enrich(hit.get())));
            }
        }
        CacheStatus status = !cached ? CacheStatus.NOT_APPLICABLE
                : bypassCache ? CacheStatus.BYPASSED : CacheStatus.MISS;
        PartLookupResult result = fetch(client, number);
        if (result.status() == PartLookupResult.Status.OUT_OF_STOCK && result.identity() != null) {
            PartLookupResult.Identity id = result.identity();
            return PartLookupResponse.outOfStock(distributor, number, status, new PartLookupResponse.Identity(
                    id.partNumber(), id.manufacturer(), id.mpn(), id.description()));
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
        return PartLookupResponse.found(distributor, number, status, PartResponse.from(part.get()));
    }

    private Optional<Part> readCache(Distributor distributor, String partNumber) {
        try {
            Instant freshSince = clock.instant().minus(properties.cache().ttl());
            return partCache.find(distributor, partNumber, freshSince);
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
                    "no answer within " + PartSearchService.format(timeout));
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
