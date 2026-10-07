package ro.alacrity.kina.search;

import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.search.PageCollector.Check;

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

    /** What a retriever sends to the distributor: the phrase, the fetch window, the page cap and the request check. */
    record Plan(String query, int window, int maxPages, Check meets) {
    }

    /**
     * The opening of every retrieval. Connector queries are sent in the distributor's own wording; the cache key
     * stays the user's query.
     */
    static Plan plan(KinaProperties properties, RankingService ranking, Distributor distributor, Prepared prepared) {
        ParsedQuery parsed = prepared.parsed();
        String phrase = DistributorPhraser.phrase(distributor, parsed);
        String query = phrase != null ? phrase : parsed.originalText();
        int window = window(properties, distributor, prepared.maxResults());
        int maxPages = maxPages(properties, distributor);
        return new Plan(query, window, maxPages, Check.of(ranking, parsed));
    }

    static CacheStatus initialStatus(Distributor distributor, boolean bypassCache) {
        if (!usesPostgresCache(distributor)) {
            return CacheStatus.NOT_APPLICABLE;
        }
        return bypassCache ? CacheStatus.BYPASSED : CacheStatus.MISS;
    }
}
