package ro.alacrity.kina.search;

import lombok.AccessLevel;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.Deadline;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.distributor.DistributorRegistry;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;

import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

/**
 * Stage 2 of a search: runs the retriever of every requested distributor in parallel on the service's executor and
 * turns each outcome, a timeout or a failure into a {@link Fetched} (DESIGN.md 3.2, 3.6). The result map iterates
 * in enum order (LCSC, TME, MOUSER).
 */
@Slf4j
@Component
class ParallelRetrieval {

    /** Extra wait after the distributor deadline before a fetch is abandoned (lets a finishing task hand over). */
    static final Duration TIMEOUT_GRACE = Duration.ofMillis(250);

    @Autowired private KinaProperties properties;
    @Autowired private DistributorRegistry registry;
    @Autowired private QueryParser parser;
    @Autowired private LcscRetriever lcscRetriever;
    @Autowired private CachedDistributorRetriever cachedRetriever;
    /** The search executor, owned by {@link PartSearchService}. */
    @Setter(AccessLevel.PACKAGE)
    private ExecutorService executor;

    Map<Distributor, Fetched> retrieveAll(Prepared prepared, Deadline requestDeadline) {
        Duration timeout = properties.search().distributorTimeout();
        Map<Distributor, Fetched> results = new EnumMap<>(Distributor.class);
        Map<Distributor, Future<Fetched>> futures = new EnumMap<>(Distributor.class);
        Map<Distributor, Progress> progress = new EnumMap<>(Distributor.class);
        Map<Distributor, DistributorBudget> budgets = new EnumMap<>(Distributor.class);

        for (Distributor distributor : prepared.distributors()) {
            Optional<DistributorClient> client = registry.find(distributor).filter(DistributorClient::isConfigured);
            if (client.isEmpty()) {
                results.put(distributor, Fetched.failed(distributor, CacheStatus.NOT_APPLICABLE,
                        DistributorException.Kind.NOT_CONFIGURED.code()));
                continue;
            }
            Progress p = new Progress(
                    DistributorRetriever.initialStatus(distributor, prepared.request().bypassCache()));
            progress.put(distributor, p);
            DistributorBudget budget = new DistributorBudget(requestDeadline, timeout);
            budgets.put(distributor, budget);
            DistributorRetriever retriever = retrieverFor(distributor);
            futures.put(distributor, executor.submit(() -> retriever.retrieve(client.get(), prepared, p, budget)));
        }

        futures.forEach((distributor, future) -> {
            Progress p = progress.get(distributor);
            DistributorBudget budget = budgets.get(distributor);
            Fetched fetched;
            try {
                fetched = budget.await(future, TIMEOUT_GRACE);
            } catch (TimeoutException e) {
                future.cancel(true);
                log.info("{} did not answer '{}' within {}{}", distributor, prepared.parsed().normalizedKey(),
                        Durations.format(timeout), budget.rateLimitWaitedNanos() > 0
                                ? " (+ " + budget.rateLimitWaitedMillis() + " ms rate-limit wait)" : "");
                fetched = new Fetched(distributor, p.parts, p.totalResults, p.cache,
                        DistributorException.Kind.TIMEOUT.code(), p.fallbackQuery).withOutOfStockMatches(p.outOfStock)
                        .withConstraintsRelaxed(p.constraintsRelaxed);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                fetched = Fetched.failed(distributor, p.cache, DistributorException.Kind.TIMEOUT.code());
            } catch (ExecutionException | CancellationException e) {
                Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                fetched = new Fetched(distributor, p.parts, p.totalResults, p.cache,
                        errorCode(distributor, cause), p.fallbackQuery).withOutOfStockMatches(p.outOfStock)
                        .withConstraintsRelaxed(p.constraintsRelaxed);
            }
            String phrase = DistributorPhraser.phrase(distributor, prepared.parsed());
            String used = fetched.fallbackQuery() != null ? fetched.fallbackQuery()
                    : phrase != null ? phrase : prepared.parsed().originalText();
            results.put(distributor, fetched.withRateLimitWaitedMs(budget.rateLimitWaitedMillis())
                    .withDistributorQuery(phrase)
                    .withQueryTermsDropped(queryTermsDropped(prepared.parsed(), used, fetched.droppedKeywords())));
        });
        return results;
    }

    private DistributorRetriever retrieverFor(Distributor distributor) {
        return DistributorRetriever.usesPostgresCache(distributor) ? cachedRetriever : lcscRetriever;
    }

    private static String errorCode(Distributor distributor, Throwable error) {
        if (error instanceof DistributorException de) {
            log.info("{} search failed: {}", distributor, de.getMessage());
            return de.errorCode();
        }
        log.warn("{} search failed unexpectedly", distributor, error);
        return DistributorException.Kind.UNAVAILABLE.code();
    }

    /**
     * The stated terms that were not part of the phrase that produced the parts ({@code query_terms_dropped},
     * DESIGN.md 3.2): the constraints the phrase actually sent ({@code used}: the relaxed phrase, the distributor
     * phrase or the user's text) does not state (ratings are never sent to Mouser and TME), plus the free-text keywords
     * LCSC's database search dropped. Informational: the ranker still checks every constraint. Connector queries,
     * whose phrases are rewritten into distributor wording, report only dropped keywords.
     */
    List<String> queryTermsDropped(ParsedQuery parsed, String used, List<String> droppedKeywords) {
        Set<String> out = new LinkedHashSet<>();
        if (!parsed.isConnector() && used != null) {
            ParsedQuery sent = parser.parse(used);
            parsed.constraints().keySet().forEach(kind -> {
                if (sent.constraint(kind) == null) {
                    out.add(kind.replace('_', ' '));
                }
            });
            if (parsed.dielectric() != null && sent.dielectric() == null) {
                out.add("dielectric");
            }
            if (parsed.packageName() != null && sent.packageName() == null) {
                out.add("package");
            }
            if (parsed.technology() != null && sent.technology() == null) {
                out.add("technology");
            }
            if (parsed.mounting() != null && sent.mounting() == null) {
                out.add("mounting");
            }
        }
        if (droppedKeywords != null) {
            out.addAll(droppedKeywords);
        }
        return List.copyOf(out);
    }
}
