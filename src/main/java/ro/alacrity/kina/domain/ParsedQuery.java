package ro.alacrity.kina.domain;

import java.util.List;
import java.util.Map;

/**
 * A free-text component query after parsing by {@code search.QueryParser} (DESIGN.md section 3.4).
 *
 * <p>Typed constraints are keyed by the constants below. {@link Constraint#value()} is normalised to
 * the SI base unit: farad, ohm, henry, volt, ampere, watt, hertz; tolerance is in percent
 * (e.g. {@code 5.0} for {@code ±5%}). {@link Constraint#display()} is a compact human form
 * ("10uF", "4.7kohm", "16V", "5%") used in responses and Laya prompts.
 *
 * @param originalText the query as received
 * @param normalizedKey normalised cache key (trim, collapse whitespace, lower-case, NFKC, µ-&gt;u, Ω-&gt;ohm)
 * @param family       component family ("capacitor", "resistor", "mosfet"...), null when not recognised
 * @param constraints  typed numeric constraints, insertion-ordered, keyed by the constants in this record
 * @param dielectric   "X7R", "C0G" (NP0 is normalised to C0G), null when absent
 * @param packageName  "0805", "SOT-23"..., imperial code for chip packages, null when absent
 * @param mounting     "SMD" or "THT", null when absent
 * @param keywords     remaining free-text tokens (lower-case)
 */
public record ParsedQuery(
        String originalText,
        String normalizedKey,
        String family,
        Map<String, Constraint> constraints,
        String dielectric,
        String packageName,
        String mounting,
        List<String> keywords
) {

    public static final String CAPACITANCE = "capacitance";
    public static final String RESISTANCE = "resistance";
    public static final String INDUCTANCE = "inductance";
    public static final String VOLTAGE = "voltage";
    public static final String CURRENT = "current";
    public static final String POWER = "power";
    public static final String FREQUENCY = "frequency";
    public static final String TOLERANCE = "tolerance";

    public ParsedQuery {
        constraints = constraints == null ? Map.of() : constraints;
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }

    /**
     * One numeric constraint.
     *
     * @param kind    one of the {@link ParsedQuery} constants (same as the map key)
     * @param value   value in SI base units (tolerance: percent)
     * @param display compact human form, e.g. "10uF"
     */
    public record Constraint(String kind, double value, String display) {
    }

    public Constraint constraint(String kind) {
        return constraints.get(kind);
    }
}
