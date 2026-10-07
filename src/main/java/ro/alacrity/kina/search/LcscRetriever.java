package ro.alacrity.kina.search;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.search.PageCollector.Check;
import ro.alacrity.kina.search.PageCollector.Collected;

import java.util.List;

/** LCSC retrieval: the JLCPCB SQLite database is the cache, so a search is one live query. */
@Component
final class LcscRetriever implements DistributorRetriever {

    @Autowired private KinaProperties properties;
    @Autowired private PageCollector pages;
    @Autowired private RankingService ranking;
    @Autowired private RequestedLookup requested;

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
