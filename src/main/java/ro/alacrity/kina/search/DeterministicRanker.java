package ro.alacrity.kina.search;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rule-based relevance score in [0,1] (DESIGN.md section 3.4); the primary ranking signal. Pure function of the
 * query and the part, so the ordering is stable.
 *
 * <table>
 *   <caption>Signals</caption>
 *   <tr><td>primary value (C/R/L; frequency for crystals/oscillators)</td><td>{@value #W_PRIMARY_VALUE}</td>
 *       <td>within 1 % -&gt; +, different -&gt; -, unknown -&gt; 0</td></tr>
 *   <tr><td>package</td><td>{@value #W_PACKAGE}</td><td>equivalent (0805 == 2012 metric, SOT-23-3 == SOT-23...)</td></tr>
 *   <tr><td>dielectric</td><td>{@value #W_DIELECTRIC}</td><td>exact, C0G == NP0</td></tr>
 *   <tr><td>technology</td><td>{@value #W_TECHNOLOGY}</td><td>same (or compatible) technology +, a different known
 *       one -, unknown 0 ({@link TechnologyVocabulary#compare})</td></tr>
 *   <tr><td>voltage / current / power rating</td><td>{@value #W_RATING}</td><td>part &gt;= requested (shared
 *       between the requested ratings); regulators and Zeners: equal within 2 %</td></tr>
 *   <tr><td>tolerance</td><td>{@value #W_TOLERANCE}</td><td>part &lt;= requested</td></tr>
 *   <tr><td>family</td><td>{@value #W_FAMILY}</td><td>same (or more specific) family; a different known family is
 *       penalised by the same amount</td></tr>
 *   <tr><td>lexical</td><td>{@value #W_LEXICAL}</td><td>share of free-text keywords found in the part text</td></tr>
 *   <tr><td>tie-break</td><td>up to {@value #W_TIE_BREAK}</td><td>log10(stock), has a price, JLCPCB Basic/Preferred</td></tr>
 * </table>
 *
 * <p>Connector queries ({@link ParsedQuery#isConnector()}) replace the primary value signal by connector signals:
 * positions {@value #W_POSITIONS}, rows mismatch -{@value #W_ROWS}, gender {@value #W_GENDER}, orientation
 * {@value #W_ORIENTATION}, pitch {@value #W_PITCH} (2.54 mm == 0.1"), connector type {@value #W_CONNECTOR_TYPE},
 * mounting {@value #W_CONNECTOR_MOUNTING}; each is +weight on a match and -weight on a mismatch, 0 when either side
 * is unknown. A header query with positions but no rows mildly prefers single-row parts
 * (-{@value #W_ROWS_UNSPECIFIED} for multi-row ones).
 *
 * <p>USB connector requests ({@link ParsedQuery.Connector#isUsb()}) use USB signals instead
 * ({@link #usbScore}): USB type {@value #W_USB_TYPE} (Type-C vs Micro-B vs Type-A...), pin configuration
 * {@value #W_USB_PINS} (canonical configuration, so a 17P/18P part is a 16-pin Type-C; half weight when the request only
 * implies the count through its standard), USB standard {@value #W_USB_STANDARD} (same speed class; a higher class
 * earns half, a lower one or a power-only part is a mismatch), gender {@value #W_USB_GENDER}, mounting style
 * {@value #W_USB_MOUNTING} (mid-mount / hybrid / SMD / THT), orientation {@value #W_USB_ORIENTATION} and
 * +{@value #W_USB_FEATURE} per requested feature present (waterproof, board lock, power only).
 *
 * <p>{@link #assess} also reports the <b>match grade</b>: the signals the part earned (tie-break excluded) divided by
 * what a part matching every stated parameter would earn, clamped to [0,1]. An attribute the part does not state
 * earns nothing, so 1.0 means every stated parameter is known and matches. It is absolute (not rank-normalised) and
 * does not influence the order.
 */
@Component
@RequiredArgsConstructor
public class DeterministicRanker {

    static final double W_PRIMARY_VALUE = 0.30;
    static final double W_PACKAGE = 0.20;
    static final double W_DIELECTRIC = 0.15;
    static final double W_TECHNOLOGY = 0.15;
    static final double W_RATING = 0.10;
    static final double W_TOLERANCE = 0.10;
    static final double W_FAMILY = 0.05;
    static final double W_LEXICAL = 0.10;
    static final double W_TIE_BREAK = 0.05;
    // connector signals (replace W_PRIMARY_VALUE for connector queries)
    static final double W_POSITIONS = 0.30;
    static final double W_ROWS = 0.10;
    static final double W_ROWS_UNSPECIFIED = 0.08;
    static final double W_GENDER = 0.20;
    static final double W_ORIENTATION = 0.15;
    static final double W_PITCH = 0.15;
    static final double W_CONNECTOR_TYPE = 0.10;
    static final double W_CONNECTOR_MOUNTING = 0.05;
    // USB connector signals (replace the connector signals for USB requests, DESIGN.md 3.4)
    static final double W_USB_TYPE = 0.30;
    static final double W_USB_PINS = 0.20;
    static final double W_USB_STANDARD = 0.20;
    static final double W_USB_GENDER = 0.15;
    static final double W_USB_MOUNTING = 0.10;
    static final double W_USB_ORIENTATION = 0.05;
    static final double W_USB_FEATURE = 0.03;
    /** Features that earn {@link #W_USB_FEATURE} when requested and present. */
    static final List<String> USB_BONUS_FEATURES = List.of(UsbVocabulary.WATERPROOF, UsbVocabulary.BOARD_LOCK,
            UsbVocabulary.POWER_ONLY);
    /** Absolute tolerance for "same pitch" in millimetres (2.54 == 0.1" == 2.540). */
    static final double PITCH_TOLERANCE_MM = 0.03;
    static final double W_TIE_STOCK = 0.03;
    static final double W_TIE_PRICE = 0.01;
    static final double W_TIE_LIBRARY = 0.01;
    /** Stock at which the stock bonus saturates (log10 scale). */
    static final double STOCK_SATURATION_LOG10 = 6.0;
    /** Relative tolerance for "same value". */
    static final double VALUE_MATCH_TOLERANCE = 0.01;
    /** Relative tolerance for regulator output / Zener voltages, which must match rather than exceed. */
    static final double EXACT_VOLTAGE_TOLERANCE = 0.02;

    private static final List<String> PRIMARY_KINDS = List.of(ParsedQuery.CAPACITANCE, ParsedQuery.RESISTANCE,
            ParsedQuery.INDUCTANCE);
    private static final List<String> RATING_KINDS = List.of(ParsedQuery.VOLTAGE, ParsedQuery.CURRENT,
            ParsedQuery.POWER);

    private final ParametricExtractor extractor;

    /**
     * Deterministic score and match grade of one part.
     *
     * @param score relevance in [0,1] (the ranking signal)
     * @param match share of the stated parameters the part satisfies, in [0,1] (class comment)
     */
    public record Assessment(double score, double match) {
    }

    /** Relevance of {@code part} for {@code query}, clamped to [0,1]. */
    public double score(ParsedQuery query, Part part) {
        return score(query, part, extractor.features(part));
    }

    /** Score and match grade of {@code part} for {@code query}. */
    public Assessment assess(ParsedQuery query, Part part) {
        return assess(query, part, extractor.features(part));
    }

    double score(ParsedQuery query, Part part, ParametricExtractor.Features f) {
        return assess(query, part, f).score();
    }

    Assessment assess(ParsedQuery query, Part part, ParametricExtractor.Features f) {
        double score = 0;
        double possible = 0;

        // primary value; connector queries use the connector signals instead
        String primary = query.isConnector() ? null : primaryKind(query);
        if (query.isConnector()) {
            score += connectorScore(query, f);
            possible += connectorPossible(query);
        }
        if (primary != null) {
            possible += W_PRIMARY_VALUE;
            Double partValue = f.value(primary);
            if (partValue != null) {
                score += sameValue(query.constraint(primary).value(), partValue, VALUE_MATCH_TOLERANCE)
                        ? W_PRIMARY_VALUE : -W_PRIMARY_VALUE;
            }
        }

        // package
        String wantedPackage = Recognizers.packageKey(query.packageName());
        String partPackage = Recognizers.packageKey(f.packageName());
        if (wantedPackage != null) {
            possible += W_PACKAGE;
        }
        if (wantedPackage != null && partPackage != null) {
            score += wantedPackage.equals(partPackage) ? W_PACKAGE : -W_PACKAGE;
        }

        // dielectric
        if (query.dielectric() != null) {
            possible += W_DIELECTRIC;
        }
        if (query.dielectric() != null && f.dielectric() != null) {
            score += query.dielectric().equalsIgnoreCase(f.dielectric()) ? W_DIELECTRIC : -W_DIELECTRIC;
        }

        // technology (thin film vs thick film, tantalum vs ceramic...)
        if (query.technology() != null) {
            possible += W_TECHNOLOGY;
            score += W_TECHNOLOGY * TechnologyVocabulary.compare(query.technology(), f.technology());
        }

        // ratings
        List<String> requested = RATING_KINDS.stream().filter(k -> query.constraint(k) != null).toList();
        if (!requested.isEmpty()) {
            possible += W_RATING;
        }
        for (String kind : requested) {
            Double partValue = f.value(kind);
            if (partValue == null) {
                continue;
            }
            double wanted = query.constraint(kind).value();
            boolean ok = kind.equals(ParsedQuery.VOLTAGE) && exactVoltageFamily(query.family())
                    ? sameValue(wanted, partValue, EXACT_VOLTAGE_TOLERANCE)
                    : partValue >= wanted * (1 - 1e-9);
            score += (ok ? W_RATING : -W_RATING) / requested.size();
        }

        // tolerance
        ParsedQuery.Constraint tolerance = query.constraint(ParsedQuery.TOLERANCE);
        Double partTolerance = f.value(ParsedQuery.TOLERANCE);
        if (tolerance != null) {
            possible += W_TOLERANCE;
        }
        if (tolerance != null && partTolerance != null) {
            score += partTolerance <= tolerance.value() + 1e-9 ? W_TOLERANCE : -W_TOLERANCE;
        }

        // family
        if (query.family() != null) {
            possible += W_FAMILY;
        }
        score += familyScore(query.family(), f);

        // lexical overlap
        if (!query.keywords().isEmpty()) {
            possible += W_LEXICAL;
            long found = query.keywords().stream().filter(k -> f.text().contains(k)).count();
            score += W_LEXICAL * found / query.keywords().size();
        }

        double match = possible <= 0 ? 1.0 : Math.clamp(score / possible, 0.0, 1.0);
        return new Assessment(Math.clamp(score + tieBreak(part), 0.0, 1.0), match);
    }

    /** What a connector part matching every stated connector attribute earns ({@link #connectorScore}). */
    static double connectorPossible(ParsedQuery query) {
        ParsedQuery.Connector wanted = query.connector();
        if (wanted == null) {
            return 0;
        }
        double possible = 0;
        if (wanted.isUsb()) {
            String wantedType = wanted.usbType() != null ? wanted.usbType() : UsbVocabulary.usbTypeOf(wanted.type());
            if (wantedType != null) {
                possible += W_USB_TYPE;
            }
            if (wanted.pinConfiguration() != null || wanted.positions() != null) {
                possible += wanted.pinConfigurationImplied() ? W_USB_PINS / 2 : W_USB_PINS;
            }
            if (UsbVocabulary.standard(wanted.usbStandard()) != null) {
                possible += W_USB_STANDARD;
            }
            if (wanted.gender() != null) {
                possible += W_USB_GENDER;
            }
            if (wanted.mountingStyle() != null || query.mounting() != null) {
                possible += W_USB_MOUNTING;
            }
            if (wanted.orientation() != null) {
                possible += W_USB_ORIENTATION;
            }
            for (String feature : USB_BONUS_FEATURES) {
                if (wanted.hasFeature(feature)) {
                    possible += W_USB_FEATURE;
                }
            }
            return possible;
        }
        if (wanted.positions() != null) {
            possible += W_POSITIONS;
        }
        if (wanted.gender() != null) {
            possible += W_GENDER;
        }
        if (wanted.orientation() != null) {
            possible += W_ORIENTATION;
        }
        if (wanted.pitchMm() != null) {
            possible += W_PITCH;
        }
        if (wanted.type() != null && !ParsedQuery.CONNECTOR.equals(wanted.type())
                && !ParsedQuery.HEADER.equals(wanted.type()) && !ParsedQuery.USB.equals(wanted.type())) {
            possible += W_CONNECTOR_TYPE;
        }
        if (query.mounting() != null) {
            possible += W_CONNECTOR_MOUNTING;
        }
        return possible;
    }

    /** Connector signals (class comment); 0 for every attribute unknown on either side. */
    static double connectorScore(ParsedQuery query, ParametricExtractor.Features f) {
        ParsedQuery.Connector wanted = query.connector();
        ParsedQuery.Connector actual = f.connector();
        if (wanted == null || actual == null) {
            return 0;
        }
        if (wanted.isUsb()) {
            return usbScore(query, wanted, actual, f.mounting());
        }
        double score = 0;
        if (wanted.positions() != null && actual.positions() != null) {
            score += wanted.positions().equals(actual.positions()) ? W_POSITIONS : -W_POSITIONS;
        }
        if (wanted.rows() != null && actual.rows() != null) {
            score += wanted.rows().equals(actual.rows()) ? 0 : -W_ROWS;
        } else if (wanted.rows() == null && wanted.positions() != null && actual.rows() != null && actual.rows() > 1
                && ConnectorRecognizer.isHeader(wanted.type())) {
            score -= W_ROWS_UNSPECIFIED;
        }
        if (wanted.gender() != null && actual.gender() != null) {
            score += wanted.gender().equals(actual.gender()) ? W_GENDER : -W_GENDER;
        }
        if (wanted.orientation() != null && actual.orientation() != null) {
            score += wanted.orientation().equals(actual.orientation()) ? W_ORIENTATION : -W_ORIENTATION;
        }
        if (wanted.pitchMm() != null && actual.pitchMm() != null) {
            score += Math.abs(wanted.pitchMm() - actual.pitchMm()) <= PITCH_TOLERANCE_MM ? W_PITCH : -W_PITCH;
        }
        Boolean type = ConnectorRecognizer.typesMatch(wanted.type(), actual.type());
        if (type != null) {
            score += type ? W_CONNECTOR_TYPE : -W_CONNECTOR_TYPE;
        }
        if (query.mounting() != null && f.mounting() != null) {
            score += query.mounting().equals(f.mounting()) ? W_CONNECTOR_MOUNTING : -W_CONNECTOR_MOUNTING;
        }
        return score;
    }

    /**
     * USB signals (class comment); every attribute unknown on either side scores 0. A part that is not a USB connector
     * (a pin header, an RJ45 jack) is a type mismatch for a USB request.
     */
    static double usbScore(ParsedQuery query, ParsedQuery.Connector wanted, ParsedQuery.Connector actual,
                           String partMounting) {
        double score = 0;
        // USB type: Type-C vs Micro-B vs Type-A...; a generic "USB" on either side is unknown
        String wantedType = wanted.usbType() != null ? wanted.usbType() : UsbVocabulary.usbTypeOf(wanted.type());
        String actualType = actual.usbType() != null ? actual.usbType() : UsbVocabulary.usbTypeOf(actual.type());
        if (wantedType != null) {
            if (actualType != null) {
                score += wantedType.equals(actualType) ? W_USB_TYPE : -W_USB_TYPE;
            } else if (!actual.isUsb() && actual.type() != null && !ParsedQuery.CONNECTOR.equals(actual.type())) {
                score -= W_USB_TYPE;
            }
        }
        // pin configuration: canonical on both sides (17P/18P == 16); implied by the standard -> half weight
        Integer wantedPins = wanted.pinConfiguration() != null ? wanted.pinConfiguration()
                : UsbVocabulary.configuration(wantedType, wanted.positions());
        if (wantedPins == null) {
            wantedPins = wanted.positions();
        }
        Integer actualPins = actual.pinConfiguration() != null ? actual.pinConfiguration()
                : UsbVocabulary.configuration(wantedType, actual.positions());
        if (wantedPins != null && actualPins != null) {
            double w = wanted.pinConfigurationImplied() ? W_USB_PINS / 2 : W_USB_PINS;
            score += wantedPins.equals(actualPins) ? w : -w;
        }
        // standard: same speed class +, higher half, lower or power-only -
        UsbVocabulary.Standard wantedStandard = UsbVocabulary.standard(wanted.usbStandard());
        if (wantedStandard != null) {
            if (actual.hasFeature(UsbVocabulary.POWER_ONLY) && actual.usbStandard() == null) {
                score -= W_USB_STANDARD;
            } else {
                Double cmp = UsbVocabulary.compare(wantedStandard, UsbVocabulary.standard(actual.usbStandard()));
                if (cmp != null) {
                    score += cmp > 0 ? W_USB_STANDARD * cmp : -W_USB_STANDARD;
                }
            }
        }
        if (wanted.gender() != null && actual.gender() != null) {
            score += wanted.gender().equals(actual.gender()) ? W_USB_GENDER : -W_USB_GENDER;
        }
        Double mounting = usbMounting(wanted.mountingStyle(), query.mounting(), actual, partMounting);
        if (mounting != null) {
            score += W_USB_MOUNTING * mounting;
        }
        if (wanted.orientation() != null && actual.orientation() != null) {
            score += wanted.orientation().equals(actual.orientation()) ? W_USB_ORIENTATION : -W_USB_ORIENTATION;
        }
        for (String feature : USB_BONUS_FEATURES) {
            if (wanted.hasFeature(feature) && actual.hasFeature(feature)) {
                score += W_USB_FEATURE;
            }
        }
        return score;
    }

    /**
     * Mounting comparison in [-1, 1], null when unknown: a requested mid-mount / hybrid / top-mount style against the
     * part's style (a part that does not say is unknown); else SMD/THT against the part's mounting, where a hybrid part
     * (SMD signal pins, through-hole shell legs) counts half for either.
     */
    static Double usbMounting(String wantedStyle, String wantedMounting, ParsedQuery.Connector actual,
                              String partMounting) {
        String actualStyle = actual.mountingStyle();
        if (wantedStyle != null && actualStyle != null) {
            return wantedStyle.equals(actualStyle) ? 1.0 : -1.0;
        }
        if (wantedStyle != null && UsbVocabulary.HYBRID.equals(wantedStyle)
                && actual.hasFeature(UsbVocabulary.FULLY_SMD)) {
            return -1.0;
        }
        if (wantedMounting == null) {
            return null;
        }
        if (UsbVocabulary.HYBRID.equals(actualStyle)) {
            return 0.5;
        }
        if (partMounting == null) {
            return null;
        }
        return wantedMounting.equals(partMounting) ? 1.0 : -1.0;
    }

    /** First of capacitance/resistance/inductance in the query; frequency for crystals and oscillators. */
    static String primaryKind(ParsedQuery query) {
        for (String kind : PRIMARY_KINDS) {
            if (query.constraint(kind) != null) {
                return kind;
            }
        }
        return query.constraint(ParsedQuery.FREQUENCY) != null ? ParsedQuery.FREQUENCY : null;
    }

    static boolean sameValue(double wanted, double actual, double relativeTolerance) {
        if (wanted == 0) {
            return Math.abs(actual) < 1e-12;
        }
        return Math.abs(actual - wanted) <= relativeTolerance * Math.abs(wanted) + 1e-15;
    }

    private static boolean exactVoltageFamily(String family) {
        return "regulator".equals(family) || "zener".equals(family);
    }

    private static double familyScore(String wanted, ParametricExtractor.Features f) {
        if (wanted == null) {
            return 0;
        }
        String actual = f.family();
        if (actual != null) {
            if (actual.equals(wanted) || wanted.equals(Recognizers.parentFamily(actual))) {
                return W_FAMILY;
            }
            if (actual.equals(Recognizers.parentFamily(wanted))) {
                return 0;   // generic part family, e.g. "diode" for a "schottky" request: neutral
            }
            return -W_FAMILY;
        }
        for (String word : Recognizers.familyWords(wanted)) {
            if (f.text().contains(word)) {
                return W_FAMILY;
            }
        }
        return 0;
    }

    static double tieBreak(Part part) {
        double bonus = 0;
        if (part.stock() > 0) {
            bonus += W_TIE_STOCK * Math.min(1.0, Math.log10(part.stock()) / STOCK_SATURATION_LOG10);
        }
        if (!part.prices().isEmpty()) {
            bonus += W_TIE_PRICE;
        }
        for (Map.Entry<String, Object> e : part.extra().entrySet()) {
            if (e.getKey() != null && e.getKey().toLowerCase(Locale.ROOT).contains("library")
                    && e.getValue() != null) {
                String v = e.getValue().toString().toLowerCase(Locale.ROOT);
                if (v.contains("basic") || v.contains("preferred")) {
                    bonus += W_TIE_LIBRARY;
                    break;
                }
            }
        }
        return Math.min(bonus, W_TIE_BREAK);
    }
}
