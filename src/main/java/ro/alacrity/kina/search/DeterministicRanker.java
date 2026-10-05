package ro.alacrity.kina.search;

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
 */
@Component
public class DeterministicRanker {

    static final double W_PRIMARY_VALUE = 0.30;
    static final double W_PACKAGE = 0.20;
    static final double W_DIELECTRIC = 0.15;
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

    public DeterministicRanker(ParametricExtractor extractor) {
        this.extractor = extractor;
    }

    /** Relevance of {@code part} for {@code query}, clamped to [0,1]. */
    public double score(ParsedQuery query, Part part) {
        return score(query, part, extractor.features(part));
    }

    double score(ParsedQuery query, Part part, ParametricExtractor.Features f) {
        double score = 0;

        // primary value; connector queries use the connector signals instead
        String primary = query.isConnector() ? null : primaryKind(query);
        if (query.isConnector()) {
            score += connectorScore(query, f);
        }
        if (primary != null) {
            Double partValue = f.value(primary);
            if (partValue != null) {
                score += sameValue(query.constraint(primary).value(), partValue, VALUE_MATCH_TOLERANCE)
                        ? W_PRIMARY_VALUE : -W_PRIMARY_VALUE;
            }
        }

        // package
        String wantedPackage = Recognizers.packageKey(query.packageName());
        String partPackage = Recognizers.packageKey(f.packageName());
        if (wantedPackage != null && partPackage != null) {
            score += wantedPackage.equals(partPackage) ? W_PACKAGE : -W_PACKAGE;
        }

        // dielectric
        if (query.dielectric() != null && f.dielectric() != null) {
            score += query.dielectric().equalsIgnoreCase(f.dielectric()) ? W_DIELECTRIC : -W_DIELECTRIC;
        }

        // ratings
        List<String> requested = RATING_KINDS.stream().filter(k -> query.constraint(k) != null).toList();
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
        if (tolerance != null && partTolerance != null) {
            score += partTolerance <= tolerance.value() + 1e-9 ? W_TOLERANCE : -W_TOLERANCE;
        }

        // family
        score += familyScore(query.family(), f);

        // lexical overlap
        if (!query.keywords().isEmpty()) {
            long found = query.keywords().stream().filter(k -> f.text().contains(k)).count();
            score += W_LEXICAL * found / query.keywords().size();
        }

        score += tieBreak(part);
        return Math.clamp(score, 0.0, 1.0);
    }

    /** Connector signals (class comment); 0 for every attribute unknown on either side. */
    static double connectorScore(ParsedQuery query, ParametricExtractor.Features f) {
        ParsedQuery.Connector wanted = query.connector();
        ParsedQuery.Connector actual = f.connector();
        if (wanted == null || actual == null) {
            return 0;
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
