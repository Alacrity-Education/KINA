package ro.alacrity.kina.search;

import org.springframework.stereotype.Component;
import ro.alacrity.kina.domain.ParsedQuery;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses a free-text component query into a {@link ParsedQuery} (DESIGN.md section 3.4): family keywords, SI values
 * (incl. RKM notation such as {@code 4k7}, {@code 4u7}, {@code 10R}, {@code 2R2}), tolerance, dielectric, package
 * (imperial chip codes; metric chip codes only when the family is a passive; IC/discrete packages by pattern),
 * mounting, connector attributes ({@link ConnectorRecognizer}: type, gender, positions, rows, pitch, orientation) and
 * the remaining free-text keywords. Stateless and thread-safe.
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
        ParsedQuery.Connector connector = null;
        ConnectorRecognizer.Result c = ConnectorRecognizer.analyze(original);
        if (isConnectorQuery(analysis, c)) {
            connector = c.connector();
            // the generic recognisers only see what the connector vocabulary left (no "90 degree", "2.54mm", "6 pos")
            analysis = Recognizers.analyze(c.residual());
        }
        Map<String, ParsedQuery.Constraint> constraints = new LinkedHashMap<>();
        analysis.values().forEach((kind, v) -> constraints.put(kind,
                new ParsedQuery.Constraint(kind, v.value(), v.display())));
        String family = connector != null ? "connector" : analysis.family();
        return new ParsedQuery(original, normalizeKey(original), family, constraints,
                analysis.dielectric(), analysis.packageName(), analysis.mounting(), analysis.keywords(), connector);
    }

    /**
     * A connector request needs connector words ("header", "socket", "connector", "dupont", "JST", "USB-C"...); a pin
     * count alone ("8 pin SOIC op amp") is not enough. An IC/discrete package ({@code SOIC-8}, {@code LQFP-48},
     * {@code SOT-23-6}) or another explicitly named family (op amp, LDO, MCU...) means the pins belong to that part,
     * except for IC sockets.
     */
    static boolean isConnectorQuery(Recognizers.Analysis analysis, ConnectorRecognizer.Result connector) {
        if (!connector.connectorWords()) {
            return false;
        }
        boolean icSocket = ParsedQuery.IC_SOCKET.equals(connector.connector().type());
        if (analysis.packageName() != null && !Recognizers.isChipCode(analysis.packageName()) && !icSocket) {
            return false;
        }
        return !analysis.familyExplicit() || "connector".equals(analysis.family()) || icSocket;
    }

    /** Cache key normalisation: trim, collapse whitespace, lower-case, Unicode NFKC, µ-&gt;u, Ω-&gt;ohm. */
    public static String normalizeKey(String query) {
        return Recognizers.normalizeKey(query);
    }
}
