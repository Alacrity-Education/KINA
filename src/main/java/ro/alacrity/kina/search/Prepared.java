package ro.alacrity.kina.search;

import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.SearchRequest;

import java.util.Set;

/**
 * A validated request: parsed query, clamped max results, resolved distributors, and the checks of its parts
 * ({@link PartChecks}: made once, by the retrieval or the ranking, whichever comes first).
 */
record Prepared(SearchRequest request, ParsedQuery parsed, int maxResults, Set<Distributor> distributors,
                PartChecks checks) {

    Prepared(SearchRequest request, ParsedQuery parsed, int maxResults, Set<Distributor> distributors) {
        this(request, parsed, maxResults, distributors, new PartChecks(parsed));
    }
}
