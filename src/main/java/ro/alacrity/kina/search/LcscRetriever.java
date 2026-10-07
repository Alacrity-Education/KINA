package ro.alacrity.kina.search;

import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.search.PageCollector.Check;
import ro.alacrity.kina.search.PageCollector.Collected;

import java.time.Clock;
import java.util.List;

/** LCSC retrieval: the JLCPCB SQLite database is the cache, so a search is one live query. */
final class LcscRetriever implements DistributorRetriever {

    private final KinaProperties properties;
    private final PageCollector pages;
    private final RankingService ranking;
    private final RequestedLookup requested;

    LcscRetriever(KinaProperties properties, PageCollector pages, RankingService ranking,
                  ParametricExtractor extractor, Clock clock) {
        this.properties = properties;
        this.pages = pages;
        this.ranking = ranking;
        this.requested = new RequestedLookup(extractor, null, clock);
    }

    @Override
    public Fetched retrieve(DistributorClient client, Prepared prepared, Progress progress,
                            DistributorBudget deadline) {
        Distributor distributor = client.distributor();
        ParsedQuery parsed = prepared.parsed();
        DistributorRetriever.Plan plan = DistributorRetriever.plan(properties, ranking, distributor, prepared);
        String query = plan.query();
        int window = plan.window();
        int maxPages = plan.maxPages();
        Check meets = plan.meets();

        Collected collected = pages.collect(client, query, 0, window, maxPages, List.of(), progress, deadline, meets,
                parsed.family());
        // a part number the query names that the search did not bring is looked up directly
        return requested.complete(client, prepared, collected.toFetched(distributor, CacheStatus.NOT_APPLICABLE)
                .withOutOfStockMatches(progress.outOfStock), deadline);
    }
}
