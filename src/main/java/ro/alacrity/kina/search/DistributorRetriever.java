package ro.alacrity.kina.search;

import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.domain.Distributor;

/**
 * Retrieval of one query from one distributor (DESIGN.md 3.2). Runs on a virtual thread; exceptions escape to the
 * caller's {@code Future}.
 */
interface DistributorRetriever {

    /** Pages fetched per search for LCSC (the SQLite query already returns the whole window). */
    int LCSC_MAX_PAGES = 1;

    /**
     * Fetches the query. No further page is requested once {@code budget} has run out.
     */
    Fetched retrieve(DistributorClient client, Prepared prepared, Progress progress, DistributorBudget budget);

    /** Only Mouser and TME use the Postgres cache; the JLCPCB SQLite database is LCSC's cache. */
    static boolean usesPostgresCache(Distributor distributor) {
        return distributor != Distributor.LCSC;
    }

    /** {@code max(maxResults, candidate-window)} capped by the distributor's {@code max-results-per-search}. */
    static int window(KinaProperties properties, Distributor distributor, int maxResults) {
        int cap = switch (distributor) {
            case MOUSER -> properties.distributors().mouser().maxResultsPerSearch();
            case TME -> properties.distributors().tme().maxResultsPerSearch();
            case LCSC -> properties.jlcpcb().maxResultsPerSearch();
        };
        return Math.max(1, Math.min(Math.max(maxResults, properties.search().candidateWindow()), cap));
    }

    static int maxPages(KinaProperties properties, Distributor distributor) {
        return Math.max(1, switch (distributor) {
            case MOUSER -> properties.distributors().mouser().maxPagesPerSearch();
            case TME -> properties.distributors().tme().maxPagesPerSearch();
            case LCSC -> LCSC_MAX_PAGES;
        });
    }

    static CacheStatus initialStatus(Distributor distributor, boolean bypassCache) {
        if (!usesPostgresCache(distributor)) {
            return CacheStatus.NOT_APPLICABLE;
        }
        return bypassCache ? CacheStatus.BYPASSED : CacheStatus.MISS;
    }
}
