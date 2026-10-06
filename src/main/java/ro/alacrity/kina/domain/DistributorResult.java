package ro.alacrity.kina.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import ro.alacrity.kina.cache.CacheStatus;

import java.util.List;

/**
 * Per-distributor section of a {@link SearchResponse} (DESIGN.md 3.2 "Counts" and 4).
 *
 * <p>Counts: {@code fetched} is every in-stock part received from the distributor for the query before any exclusion;
 * {@code excludedByConstraints} and {@code excludedBelowSpec} are subsets of it, and
 * {@code returned <= fetched - excludedByConstraints - excludedBelowSpec}. {@code outOfStockMatches} are records the
 * distributor matched without ships-now stock; they are not part of {@code fetched}.
 *
 * @param totalResults what the distributor reported for the query (null when unknown / on error)
 * @param fetched      in-stock parts received from the distributor for the query, before any exclusion
 * @param returned     parts in this response: {@code min(max_results, fetched - exclusions)}
 * @param error        null, or "rate_limited", "unavailable", "not_configured", "timeout", "bad_response"
 * @param fallbackQuery null, or the relaxed phrase that produced the parts after the first phrase found nothing that
 *                      meets the request (e.g. "MLCC 22uF 1206" for "22uF X7R 1206 25V MLCC")
 * @param rateLimitWaitedMs milliseconds this distributor's fetch spent waiting on rate limits (0 when none)
 * @param distributorQuery null when the user's text was sent verbatim, else the distributor-specific phrase KINA sent
 *                      instead (connector queries, e.g. {@code pin strips female 6 angled} for TME; queries with
 *                      ratings, which are left out); a {@code fallbackQuery} is what was sent after this phrase found
 *                      nothing
 * @param excludedByConstraints parts left out because a known attribute contradicts a strict constraint of the request
 *                      (mounting, technology, elements; {@code kina.search.strict-constraints})
 * @param excludedBelowSpec parts left out because a known rating is below the request (voltage, current, saturation
 *                      current, power, temperature, lifetime; DCR above a stated maximum); 0 when
 *                      {@code allow_below_spec} is true (they are returned with {@code below_spec: true})
 * @param outOfStockMatches records the distributor matched without ships-now stock (dropped by the stock rule) on the
 *                      pages KINA read; null when unknown (a cached search stored before this was counted)
 * @param queryTermsDropped stated terms that were not sent in the phrase that produced the parts (informational:
 *                      ratings are never sent to Mouser and TME, LCSC free-text keywords its database search dropped);
 *                      the ranker still checks every constraint
 * @param constraintsRelaxed constraints actually loosened to obtain the parts ({@code dielectric}, {@code package},
 *                      {@code tolerance} by the relaxation ladder; for LCSC the dropped terms the returned parts
 *                      really miss); empty when nothing was relaxed
 * @param exactMatches  returned parts whose constraints are all verified and met ({@code match} 1.0, no
 *                      {@code unverified}); null when the query was not understood
 */
@Builder
public record DistributorResult(
        @JsonProperty("distributor") Distributor distributor,
        @JsonProperty("total_results") Integer totalResults,
        @JsonProperty("fetched") int fetched,
        @JsonProperty("returned") int returned,
        @JsonProperty("cache") CacheStatus cache,
        @JsonProperty("error") String error,
        @JsonProperty("parts") List<PartResponse> parts,
        @JsonProperty("fallback_query") String fallbackQuery,
        @JsonProperty("rate_limit_waited_ms") long rateLimitWaitedMs,
        @JsonProperty("distributor_query") String distributorQuery,
        @JsonProperty("excluded_by_constraints") int excludedByConstraints,
        @JsonProperty("excluded_below_spec") int excludedBelowSpec,
        @JsonProperty("out_of_stock_matches") Integer outOfStockMatches,
        @JsonProperty("query_terms_dropped") List<String> queryTermsDropped,
        @JsonProperty("constraints_relaxed") List<String> constraintsRelaxed,
        @JsonProperty("exact_matches") Integer exactMatches
) {

    public DistributorResult {
        parts = parts == null ? List.of() : List.copyOf(parts);
        queryTermsDropped = queryTermsDropped == null ? List.of() : List.copyOf(queryTermsDropped);
        constraintsRelaxed = constraintsRelaxed == null ? List.of() : List.copyOf(constraintsRelaxed);
    }

    /** An entry for a distributor that failed; carries an empty part list. */
    public static DistributorResult failed(Distributor distributor, String error, CacheStatus cache) {
        return builder().distributor(distributor).cache(cache).error(error).build();
    }
}
