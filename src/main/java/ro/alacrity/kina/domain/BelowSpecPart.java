package ro.alacrity.kina.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * One part left out for a rating below the request ({@code excluded_below_spec_detail}, DESIGN.md 4), so a count such
 * as "1 part was left out" names the part and the rating it fails, e.g.
 * {@code {"part_number":"65-EPC2218A","mpn":"EPC2218A","rating":"voltage","part_value":"80V","requested":"100V"}}.
 *
 * @param partNumber the distributor part number
 * @param mpn        the manufacturer part number, may be null
 * @param rating     the failed rating ({@code voltage}, {@code current}, {@code power}, {@code dcr}...); of several the
 *                   first in score order
 * @param partValue  the part's value of that rating as KINA read it from the distributor's data
 * @param requested  the requested minimum (a maximum for {@code dcr})
 */
@JsonPropertyOrder({"part_number", "mpn", "rating", "part_value", "requested"})
public record BelowSpecPart(
        @JsonProperty("part_number") String partNumber,
        @JsonProperty("mpn") String mpn,
        @JsonProperty("rating") String rating,
        @JsonProperty("part_value") String partValue,
        @JsonProperty("requested") String requested) {
}
