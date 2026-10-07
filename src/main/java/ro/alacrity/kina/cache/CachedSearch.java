package ro.alacrity.kina.cache;

import ro.alacrity.kina.domain.Distributor;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One row of {@code cached_searches} (DESIGN.md sections 3.2 and 8): the ordered distributor part numbers a
 * normalised query returned.
 *
 * @param distributor  the distributor that was searched
 * @param queryKey     the normalised query key (owned by the query parser)
 * @param totalResults distributor-reported total, null when unknown
 * @param partNumbers  distributor part numbers in the distributor's relevance order
 * @param exhausted    true when the distributor reported no further results
 * @param fetchedAt    when the list was (last) fetched
 * @param nextOffset   0-based distributor record offset where the next page starts (raw records, including parts
 *                     dropped for having no ships-now stock); null when unknown (rows written before V2)
 * @param fallbackQuery the shorter core phrase that was sent to the distributor instead of the query (phrase
 *                      fallback), null when the query itself was searched
 * @param outOfStockMatches records matched without ships-now stock while building the list; null when unknown
 *                      (rows written before V5)
 * @param constraintsRelaxed the constraints the relaxation ladder loosened to build the list ({@code dielectric},
 *                      {@code package}, {@code tolerance}); null when unknown (rows written before V7)
 * @param requestedParts the outcome of the direct lookup of each part number the query names, by the part number as
 *                      sent (V10); null when nothing was looked up (or the row predates V10)
 */
public record CachedSearch(
        Distributor distributor,
        String queryKey,
        Integer totalResults,
        List<String> partNumbers,
        boolean exhausted,
        Instant fetchedAt,
        Integer nextOffset,
        String fallbackQuery,
        Integer outOfStockMatches,
        List<String> constraintsRelaxed,
        Map<String, RequestedPart> requestedParts
) {

    /**
     * The outcome of one requested part-number lookup (DESIGN.md 3.2 "Requested part numbers").
     *
     * @param status     {@value #FOUND} (in stock; its number is in {@code partNumbers}), {@value #LISTED} (listed
     *                   without ships-now stock; the {@code cached_parts} row has {@code in_stock = false}) or
     *                   {@value #NOT_FOUND}
     * @param partNumber the distributor part number, null for {@value #NOT_FOUND}
     */
    public record RequestedPart(@JsonProperty("status") String status,
                                @JsonProperty("part_number") String partNumber) {

        public static final String FOUND = "found";
        public static final String LISTED = "listed";
        public static final String NOT_FOUND = "not_found";
    }

    /** A row without requested parts. */
    public CachedSearch(Distributor distributor, String queryKey, Integer totalResults, List<String> partNumbers,
                        boolean exhausted, Instant fetchedAt, Integer nextOffset, String fallbackQuery,
                        Integer outOfStockMatches, List<String> constraintsRelaxed) {
        this(distributor, queryKey, totalResults, partNumbers, exhausted, fetchedAt, nextOffset, fallbackQuery,
                outOfStockMatches, constraintsRelaxed, null);
    }

    /** A row without the loosened constraints (unknown). */
    public CachedSearch(Distributor distributor, String queryKey, Integer totalResults, List<String> partNumbers,
                        boolean exhausted, Instant fetchedAt, Integer nextOffset, String fallbackQuery,
                        Integer outOfStockMatches) {
        this(distributor, queryKey, totalResults, partNumbers, exhausted, fetchedAt, nextOffset, fallbackQuery,
                outOfStockMatches, null);
    }

    /** A row without an out-of-stock count. */
    public CachedSearch(Distributor distributor, String queryKey, Integer totalResults, List<String> partNumbers,
                        boolean exhausted, Instant fetchedAt, Integer nextOffset, String fallbackQuery) {
        this(distributor, queryKey, totalResults, partNumbers, exhausted, fetchedAt, nextOffset, fallbackQuery, null);
    }

    /** A row without a known next offset. */
    public CachedSearch(Distributor distributor, String queryKey, Integer totalResults, List<String> partNumbers,
                        boolean exhausted, Instant fetchedAt) {
        this(distributor, queryKey, totalResults, partNumbers, exhausted, fetchedAt, null, null, null);
    }

    /** A row for a search of the query itself (no phrase fallback). */
    public CachedSearch(Distributor distributor, String queryKey, Integer totalResults, List<String> partNumbers,
                        boolean exhausted, Instant fetchedAt, Integer nextOffset) {
        this(distributor, queryKey, totalResults, partNumbers, exhausted, fetchedAt, nextOffset, null, null);
    }

    public CachedSearch {
        Objects.requireNonNull(distributor, "distributor");
        Objects.requireNonNull(queryKey, "queryKey");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
        partNumbers = partNumbers == null ? List.of() : List.copyOf(partNumbers);
        constraintsRelaxed = constraintsRelaxed == null ? null : List.copyOf(constraintsRelaxed);
        requestedParts = requestedParts == null ? null
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(requestedParts));
    }
}
