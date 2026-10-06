package ro.alacrity.kina.distributor;

import ro.alacrity.kina.domain.Part;

import java.util.List;

/**
 * One page of distributor results.
 *
 * @param parts        in-stock parts of this page, in distributor relevance order
 * @param totalResults distributor-reported total for the query
 * @param hasMore      whether another page can be requested
 * @param outOfStock   records of this page that matched but were dropped for having no ships-now stock (stock 0, or
 *                     a TME status that does not ship now)
 * @param relaxed      constraints the distributor's own search dropped to find this page (LCSC relaxation:
 *                     "voltage", "dielectric", "package"...); empty when every term matched
 */
public record DistributorSearchPage(List<Part> parts, int totalResults, boolean hasMore, int outOfStock,
                                    List<String> relaxed) {

    public DistributorSearchPage {
        parts = parts == null ? List.of() : List.copyOf(parts);
        outOfStock = Math.max(0, outOfStock);
        relaxed = relaxed == null ? List.of() : List.copyOf(relaxed);
    }

    public DistributorSearchPage(List<Part> parts, int totalResults, boolean hasMore, int outOfStock) {
        this(parts, totalResults, hasMore, outOfStock, List.of());
    }

    public DistributorSearchPage(List<Part> parts, int totalResults, boolean hasMore) {
        this(parts, totalResults, hasMore, 0);
    }

    public static DistributorSearchPage empty() {
        return new DistributorSearchPage(List.of(), 0, false);
    }
}
