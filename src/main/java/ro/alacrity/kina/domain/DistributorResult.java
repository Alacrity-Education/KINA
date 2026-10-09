package ro.alacrity.kina.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import ro.alacrity.kina.cache.CacheStatus;

import java.util.List;
import java.util.Map;

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
 * @param excludedByConstraints parts left out because a known attribute contradicts a hard constraint of the request
 *                      (never relaxed: the primary value, the package except for inductors, crystals and oscillators,
 *                      mounting, technology, the component type...; {@code kina.search.hard-constraints})
 * @param excludedByConstraintsDetail {@code excludedByConstraints} per constraint, each part counted under its first
 *                      conflict ({@code {"capacitance": 12, "package": 3}}); empty when nothing was excluded
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
 * @param hint          when the distributor returned nothing for an understood query (no error): which hard
 *                      constraints could not be met and that no substitutes are returned; when a part number the
 *                      query names is not among the parts ({@code requestedPartFound} false): that it is not listed in
 *                      stock there, or why it was left out; else null (omitted)
 * @param requestedPartFound null when the query names no part number ({@code parsed.part_numbers}) or the distributor
 *                      failed; true when a returned part is the requested one for every part number (its MPN or
 *                      distributor part number equals it or starts with it); false otherwise, with a {@code hint}
 * @param fetchedLive  true when this search called the distributor for the query, also when the call failed
 *                      ({@code error} says why); always {@code liveCalls > 0}; false when the parts came from the
 *                      cache or the distributor is LCSC (its local database is read, nothing is called)
 * @param liveCalls    the distributor search calls this request made for this distributor: one per page, every
 *                      phrase (relaxed steps and fallback phrases included), failed calls included; 0 when none
 *                      (LCSC always 0). Stock refreshes and the direct part-number lookups are not search calls and
 *                      are not counted
 * @param fieldSteps   how many steps of the field query (DESIGN.md 3.8) the field-first search evaluated before it
 *                      answered; 0 when the search took the cached-search path
 * @param excludedBelowSpecDetail up to 5 of the {@code excludedBelowSpec} parts, closest to the request first: part
 *                      number, MPN and the failed rating with the part's and the requested value; empty when none
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
        @JsonProperty("excluded_by_constraints_detail") Map<String, Integer> excludedByConstraintsDetail,
        @JsonProperty("excluded_below_spec") int excludedBelowSpec,
        @JsonProperty("out_of_stock_matches") Integer outOfStockMatches,
        @JsonProperty("query_terms_dropped") List<String> queryTermsDropped,
        @JsonProperty("constraints_relaxed") List<String> constraintsRelaxed,
        @JsonProperty("exact_matches") Integer exactMatches,
        @JsonProperty("hint") @JsonInclude(JsonInclude.Include.NON_NULL) String hint,
        @JsonProperty("requested_part_found") Boolean requestedPartFound,
        @JsonProperty("excluded_below_spec_detail") List<BelowSpecPart> excludedBelowSpecDetail,
        @JsonProperty("fetched_live") boolean fetchedLive,
        @JsonProperty("live_calls") int liveCalls,
        @JsonProperty("field_steps_tried") int fieldSteps
) {

    public DistributorResult {
        parts = parts == null ? List.of() : List.copyOf(parts);
        queryTermsDropped = queryTermsDropped == null ? List.of() : List.copyOf(queryTermsDropped);
        constraintsRelaxed = constraintsRelaxed == null ? List.of() : List.copyOf(constraintsRelaxed);
        excludedBelowSpecDetail = excludedBelowSpecDetail == null ? List.of() : List.copyOf(excludedBelowSpecDetail);
        excludedByConstraintsDetail = excludedByConstraintsDetail == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(excludedByConstraintsDetail));
    }

    /** An entry for a distributor that failed; carries an empty part list. */
    public static DistributorResult failed(Distributor distributor, String error, CacheStatus cache) {
        return builder().distributor(distributor).cache(cache).error(error).build();
    }
}
