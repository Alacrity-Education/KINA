package ro.alacrity.kina.search;

import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.SearchRequest;

import java.util.Set;

/** A validated request: parsed query, clamped max results, resolved distributors. */
record Prepared(SearchRequest request, ParsedQuery parsed, int maxResults, Set<Distributor> distributors) {
}
