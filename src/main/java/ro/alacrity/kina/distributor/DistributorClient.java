package ro.alacrity.kina.distributor;

import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;

import java.util.Optional;

/**
 * One distributor backend. Implementations are Spring beans and are collected by {@link DistributorRegistry}.
 * Stock rule: only quantity that ships now counts; parts without it are never returned.
 */
public interface DistributorClient {

    Distributor distributor();

    /** False when credentials/database are missing; the distributor is then reported as unavailable. */
    boolean isConfigured();

    /** Largest page the distributor accepts in one call (Mouser 50, TME 50 (verify; fall back to 20), LCSC 200). */
    int maxPageSize();

    /**
     * Returns in-stock parts only (stock &gt; 0), in the distributor's own relevance order.
     * {@code offset} is 0-based. {@code totalResults} is the distributor-reported total for the query.
     */
    DistributorSearchPage search(String query, int offset, int limit) throws DistributorException;

    /** Looks up one part by distributor part number; empty when unknown or not in stock. */
    Optional<Part> getPart(String distributorPartNumber) throws DistributorException;
}
