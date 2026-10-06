package ro.alacrity.kina.search;

import org.springframework.stereotype.Component;
import ro.alacrity.kina.domain.ParsedQuery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a free-text component query into a {@link ParsedQuery} (DESIGN.md section 3.4): family keywords, SI values
 * (incl. RKM notation such as {@code 4k7}, {@code 4u7}, {@code 10R}, {@code 2R2}), tolerance, dielectric, package
 * (imperial chip codes always: a bare four-digit code is imperial, a metric code only when labelled, {@code 1608
 * metric}; crystal sizes for crystals and oscillators; can sizes of aluminium capacitors; IC/discrete packages by
 * pattern), transistor polarity and the diode / regulator subtype ({@link ComponentTypes}),
 * mounting, connector attributes ({@link ConnectorRecognizer}: type, gender, positions, rows, pitch, orientation), the
 * technology of a passive ({@link TechnologyVocabulary}: thin film, tantalum, multilayer...), labelled values
 * (saturation current, DC resistance, lifetime, operating temperature, the impedance of a ferrite bead), preferences
 * ("low DCR") and the remaining free-text keywords. Stateless and thread-safe.
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
            connector = withImpliedConfiguration(c.connector());
            // the generic recognisers only see what the connector vocabulary left (no "90 degree", "2.54mm", "6 pos")
            analysis = Recognizers.analyze(c.residual());
        }
        Map<String, ParsedQuery.Constraint> constraints = new LinkedHashMap<>();
        analysis.values().forEach((kind, v) -> constraints.put(kind,
                new ParsedQuery.Constraint(kind, v.value(), v.display(), v.condition())));
        String family = connector != null ? "connector" : analysis.family();
        String polarity = null;
        String subtype = null;
        String packageName = analysis.packageName();
        List<String> keywords = analysis.keywords();
        if (connector == null) {
            polarity = family == null || ComponentTypes.polarised(family) ? ComponentTypes.polarity(original) : null;
            if (family == null && polarity != null) {
                // "SOT-23 N-channel 30V": the polarity names the family
                family = ComponentTypes.NPN.equals(polarity) || ComponentTypes.PNP.equals(polarity) ? "transistor"
                        : "mosfet";
            }
            subtype = ComponentTypes.subtype(family, original);
            if (subtype == null && "regulator".equals(family) && constraints.containsKey(ParsedQuery.VOLTAGE)) {
                subtype = ComponentTypes.FIXED;   // "3.3V LDO": a stated output voltage is a fixed regulator
            }
            if (packageName == null && "capacitor".equals(family)) {
                String can = canSize(original, analysis.technology());
                if (can != null) {
                    packageName = can;
                    keywords = keywords.stream().filter(k -> PassiveDetails.parseDimensions(k) == null).toList();
                }
            }
        }
        return ParsedQuery.builder()
                .originalText(original)
                .normalizedKey(normalizeKey(original))
                .family(family)
                .constraints(constraints)
                .dielectric(analysis.dielectric())
                .packageName(packageName)
                .mounting(analysis.mounting())
                .keywords(keywords)
                .polarity(polarity)
                .subtype(subtype)
                .connector(connector)
                .technology(connector != null ? null : analysis.technology())
                .preferences(analysis.preferences())
                .elements(connector != null ? null : requestedElements(original))
                .build();
    }

    /**
     * The can size of an electrolytic or polymer capacitor request ({@code D6.3 x 5.8mm}) from {@code Ø6.3x5.8mm},
     * {@code D6.3xL5.8mm}, or {@code 6.3x5.8mm} when the technology is an aluminium (electrolytic, polymer, hybrid)
     * one; null otherwise. Can sizes compare within 0.2 mm ({@link Recognizers#samePackage}).
     */
    static String canSize(String text, String technology) {
        String d = PassiveDetails.parseDimensions(text);
        if (d == null || d.chars().filter(c -> c == 'x').count() != 1) {
            return null;
        }
        if (PassiveDetails.isCan(d)) {
            return d;
        }
        boolean aluminium = technology != null && !technology.contains("tantalum")
                && (technology.contains("aluminium") || technology.contains("polymer"));
        return aluminium ? "D" + d : null;
    }

    private static final java.util.regex.Pattern ARRAY_WORDS = java.util.regex.Pattern.compile(
            "(?i)(?<![\\p{L}\\d])(?:arrays?|networks?)(?![\\p{L}\\d])");
    private static final java.util.regex.Pattern ELEMENT_COUNT = java.util.regex.Pattern.compile(
            "(?i)(?<![\\p{L}\\d.])(\\d{1,2})\\s?-?\\s?(?:lines?|elements?)(?![\\p{L}\\d])");

    /**
     * The array or network a request asks for ({@link ParsedQuery#elements()}): {@code 4 lines} / {@code 2 elements}
     * give the count, {@code array} / {@code network} alone {@link ParsedQuery#ANY_ELEMENTS}; null when the request
     * names neither (a single element is wanted).
     */
    static Integer requestedElements(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        java.util.regex.Matcher count = ELEMENT_COUNT.matcher(text);
        if (count.find()) {
            int n = Integer.parseInt(count.group(1));
            if (n >= 2) {
                return n;
            }
        }
        return ARRAY_WORDS.matcher(text).find() ? ParsedQuery.ANY_ELEMENTS : null;
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

    /**
     * A USB request without a pin count gets the configuration its standard implies (DESIGN.md 3.4): "USB 2.0 Type-C"
     * -&gt; 16, "USB 3.1 Type-C" -&gt; 24, a power-only Type-C -&gt; 6 ({@link UsbVocabulary#impliedConfiguration});
     * {@code pinConfigurationImplied} marks it so the ranker gives it half weight. The positions stay unknown.
     */
    static ParsedQuery.Connector withImpliedConfiguration(ParsedQuery.Connector c) {
        if (c == null || !c.isUsb() || c.pinConfiguration() != null || c.positions() != null) {
            return c;
        }
        Integer implied = UsbVocabulary.impliedConfiguration(c.usbType(), UsbVocabulary.standard(c.usbStandard()),
                c.hasFeature(UsbVocabulary.POWER_ONLY));
        if (implied == null) {
            return c;
        }
        return c.toBuilder().pinConfiguration(implied).pinConfigurationImplied(true).build();
    }

    /** Cache key normalisation: trim, collapse whitespace, lower-case, Unicode NFKC, µ-&gt;u, Ω-&gt;ohm. */
    public static String normalizeKey(String query) {
        return Recognizers.normalizeKey(query);
    }
}
