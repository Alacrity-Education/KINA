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
     * Rate limits are not waited for ({@code RATE_LIMITED} at once).
     */
    DistributorSearchPage search(String query, int offset, int limit) throws DistributorException;

    /**
     * Like {@link #search(String, int, int)}, but a rate-limited distributor call may wait and retry while the wait
     * fits {@code deadline} (the request deadline, DESIGN.md section 3.6); waits are recorded on {@code deadline}.
     * The default ignores the deadline (no rate limiting to handle, e.g. LCSC's local database).
     */
    default DistributorSearchPage search(String query, int offset, int limit, Deadline deadline)
            throws DistributorException {
        return search(query, offset, limit);
    }

    /** Looks up one part by distributor part number; empty when unknown or not in stock. */
    Optional<Part> getPart(String distributorPartNumber) throws DistributorException;

    /** Like {@link #getPart(String)}, waiting for rate limits within {@code deadline} (see {@link #search(String, int, int, Deadline)}). */
    default Optional<Part> getPart(String distributorPartNumber, Deadline deadline) throws DistributorException {
        return getPart(distributorPartNumber);
    }

    /**
     * Looks up one part by distributor part number (or, where the distributor allows a cheap retry, by manufacturer part
     * number compared with {@link PartLookupResult#normalize}), telling "listed but no ships-now stock"
     * ({@link PartLookupResult.Status#OUT_OF_STOCK}) apart from "unknown" ({@link PartLookupResult.Status#NOT_FOUND}).
     * The default only knows {@link #getPart(String, Deadline)} and reports every miss as not found.
     */
    default PartLookupResult lookup(String partNumber, Deadline deadline) throws DistributorException {
        return PartLookupResult.of(getPart(partNumber, deadline));
    }
}
