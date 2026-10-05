package ro.alacrity.kina.distributor;

import ro.alacrity.kina.domain.Part;

import java.util.List;

/**
 * One page of distributor results.
 *
 * @param parts        in-stock parts of this page, in distributor relevance order
 * @param totalResults distributor-reported total for the query
 * @param hasMore      whether another page can be requested
 */
public record DistributorSearchPage(List<Part> parts, int totalResults, boolean hasMore) {

    public DistributorSearchPage {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }

    public static DistributorSearchPage empty() {
        return new DistributorSearchPage(List.of(), 0, false);
    }
}
