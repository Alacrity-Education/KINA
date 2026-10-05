package ro.alacrity.kina.search;

import org.springframework.stereotype.Component;
import ro.alacrity.kina.domain.ParsedQuery;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses a free-text component query into a {@link ParsedQuery} (DESIGN.md section 3.4): family keywords, SI values
 * (incl. RKM notation such as {@code 4k7}, {@code 4u7}, {@code 10R}, {@code 2R2}), tolerance, dielectric, package
 * (imperial chip codes; metric chip codes only when the family is a passive; IC/discrete packages by pattern),
 * mounting, and the remaining free-text keywords. Stateless and thread-safe.
 *
 * <p>When no family keyword is present the family is inferred from the value kind (capacitance or dielectric -&gt;
 * capacitor, resistance -&gt; resistor, inductance -&gt; inductor).
 */
@Component
public class QueryParser {

    /** Parses a query; null or blank input yields an empty {@link ParsedQuery}. */
    public ParsedQuery parse(String query) {
        String original = query == null ? "" : query;
        Recognizers.Analysis analysis = Recognizers.analyze(original);
        Map<String, ParsedQuery.Constraint> constraints = new LinkedHashMap<>();
        analysis.values().forEach((kind, v) -> constraints.put(kind,
                new ParsedQuery.Constraint(kind, v.value(), v.display())));
        return new ParsedQuery(original, normalizeKey(original), analysis.family(), constraints,
                analysis.dielectric(), analysis.packageName(), analysis.mounting(), analysis.keywords());
    }

    /** Cache key normalisation: trim, collapse whitespace, lower-case, Unicode NFKC, µ-&gt;u, Ω-&gt;ohm. */
    public static String normalizeKey(String query) {
        return Recognizers.normalizeKey(query);
    }
}
