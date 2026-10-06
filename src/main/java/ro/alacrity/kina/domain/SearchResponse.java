package ro.alacrity.kina.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * Result of one search (DESIGN.md section 4). Serialised snake_case; {@code ranking_note} is emitted even when null.
 *
 * @param queryUnderstood false when the parser recognised no family and no typed constraint (keyword-only text such as
 *                        {@code asdfqwerty zz9}): every part then has {@code match} null and every distributor
 *                        {@code exact_matches} null, so "no parametric understanding" is distinguishable from "no
 *                        matches"
 * @param hint            what to change when {@code queryUnderstood} is false; for an understood query, when a
 *                        distributor returned nothing, which hard constraints could not be met there (they are never
 *                        relaxed and no substitutes are returned, DESIGN.md 3.2); else null (omitted)
 * @param currencies      the currencies of the prices in this response, sorted (LCSC USD, TME and Mouser EUR by
 *                        default); prices are never converted
 * @param attributions    the notice of every distributor whose parts this response returns ({@link
 *                        Distributor#attribution()}, enum order); computed from {@code distributors} when null
 */
@JsonPropertyOrder({"query", "parsed", "query_understood", "hint", "ranking", "ranking_note", "currencies",
        "attributions", "distributors"})
public record SearchResponse(
        @JsonProperty("query") String query,
        @JsonProperty("parsed") ParsedQueryResponse parsed,
        @JsonProperty("ranking") RankingMode ranking,
        @JsonProperty("ranking_note") String rankingNote,
        @JsonProperty("distributors") List<DistributorResult> distributors,
        @JsonProperty("query_understood") boolean queryUnderstood,
        @JsonProperty("hint") @JsonInclude(JsonInclude.Include.NON_NULL) String hint,
        @JsonProperty("currencies") List<String> currencies,
        @JsonProperty("attributions") List<String> attributions
) {

    /** {@link #hint} of a query without parametric understanding. */
    public static final String NOT_UNDERSTOOD_HINT = "No component type or parameter was recognised, so the parts were "
            + "found by keywords only and match is not computed. Name the component type and its key parameters "
            + "(e.g. \"10uF 25V X7R 0805 capacitor\"), or look a manufacturer part number up with get_part.";

    public SearchResponse {
        distributors = distributors == null ? List.of() : List.copyOf(distributors);
        currencies = currencies == null ? List.of() : List.copyOf(currencies);
        attributions = attributions == null ? attributionsOf(distributors) : List.copyOf(attributions);
    }

    /** A response whose attributions are computed from {@code distributors}. */
    public SearchResponse(String query, ParsedQueryResponse parsed, RankingMode ranking, String rankingNote,
                          List<DistributorResult> distributors, boolean queryUnderstood, String hint,
                          List<String> currencies) {
        this(query, parsed, ranking, rankingNote, distributors, queryUnderstood, hint, currencies, null);
    }

    /** The attributions of the distributors that returned at least one part. */
    public static List<String> attributionsOf(List<DistributorResult> distributors) {
        if (distributors == null) {
            return List.of();
        }
        return Distributor.attributions(distributors.stream()
                .filter(d -> d.distributor() != null && !d.parts().isEmpty())
                .map(DistributorResult::distributor)
                .toList());
    }

    /** An understood query; currencies taken from the returned parts. */
    public SearchResponse(String query, ParsedQueryResponse parsed, RankingMode ranking, String rankingNote,
                          List<DistributorResult> distributors) {
        this(query, parsed, ranking, rankingNote, distributors, true, null, currenciesOf(distributors), null);
    }

    /** The distinct currencies of every returned part's prices, sorted. */
    public static List<String> currenciesOf(List<DistributorResult> distributors) {
        if (distributors == null) {
            return List.of();
        }
        return distributors.stream()
                .flatMap(d -> d.parts().stream())
                .flatMap(p -> p.prices() == null ? java.util.stream.Stream.<PriceResponse>empty() : p.prices().stream())
                .map(PriceResponse::currency)
                .filter(c -> c != null && !c.isBlank())
                .distinct()
                .sorted()
                .toList();
    }
}
