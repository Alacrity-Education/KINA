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
 *   <tr><td>ratings: voltage, (rated) current, saturation current, power, temperature, lifetime; DCR</td>
 *       <td>{@value #W_RATING}</td><td>minimum ratings: part &gt;= requested (shared between the requested ratings);
 *       a higher rating earns the same, minus a small preference for the closest one in the score only (up to
 *       {@value #W_RATING_EXCESS}, so 25 V &gt; 35 V &gt; 50 V for a 25 V request); DCR is a maximum (part &lt;=
 *       requested); regulator and Zener voltages and fuse currents: equal within 2 %</td></tr>
 *   <tr><td>mounting (non-connector requests)</td><td>{@value #W_MOUNTING}</td><td>SMD/THT same +, different -</td></tr>
 *   <tr><td>low DCR preference</td><td>up to {@value #W_LOW_DCR}</td><td>lower DCR scores higher (score only)</td></tr>
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
 * what a part matching every stated and <i>verified</i> parameter would earn, clamped to [0,1]. A stated constraint the
 * part does not state at all is <b>unverified</b>: it is listed ({@link Assessment#unverified()}) and left out of both
 * sides of the grade, so 1.0 means every verified parameter matches; with a non-empty unverified list it is not a
 * confirmed fit. It is absolute (not rank-normalised) and does not influence the order. A known rating below the
 * request (or a DCR above its maximum) is <b>below spec</b> ({@link Assessment#belowSpec()}, with its distance from
 * the target).
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
    /** Largest score deduction for a rating above the requested one (closest rating preferred; score only). */
    static final double W_RATING_EXCESS = 0.05;
    /** Rating ratio (in octaves) at which the excess deduction is complete: 4x the requested rating. */
    static final double RATING_EXCESS_OCTAVES = 2.0;
    /** SMD/THT of a non-connector request (connector and USB requests have their own mounting signals). */
    static final double W_MOUNTING = 0.05;
    /** "low DCR" preference: {@code W_LOW_DCR / (1 + DCR / LOW_DCR_REFERENCE_OHM)} (score only). */
    static final double W_LOW_DCR = 0.04;
    static final double LOW_DCR_REFERENCE_OHM = 0.01;
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
            ParsedQuery.INDUCTANCE, ParsedQuery.IMPEDANCE);
    /** Ratings: minimums, except {@link ParsedQuery#DCR} (a maximum). */
    static final List<String> RATING_KINDS = List.of(ParsedQuery.VOLTAGE, ParsedQuery.CURRENT,
            ParsedQuery.SATURATION_CURRENT, ParsedQuery.POWER, ParsedQuery.TEMPERATURE, ParsedQuery.LIFETIME,
            ParsedQuery.DCR);
    /** Strict constraint names ({@code kina.search.strict-constraints}). */
    public static final String STRICT_MOUNTING = "mounting";
    public static final String STRICT_TECHNOLOGY = "technology";
    /** An array or network for a request that does not ask for one (resistors, capacitors, ferrite beads). */
    public static final String STRICT_ELEMENTS = "elements";
    /** A minimum order quantity this many decades above the quantity loses the whole MOQ penalty. */
    static final double MOQ_PENALTY_DECADES = 3.0;

    private final ParametricExtractor extractor;

    /**
     * Deterministic score and match grade of one part.
     *
     * @param score             relevance in [0,1] (the ranking signal)
     * @param match             share of the stated and verified parameters the part satisfies, in [0,1] (class
     *                          comment); null when the part states none of the stated constraints
     * @param mismatches        stated parameters the part is known not to satisfy
     * @param unverified        stated constraints the part does not state ({@code "current"}, {@code "package"}...)
     * @param belowSpec         rating kinds whose known value is below the request (a DCR above its maximum)
     * @param belowSpecDistance how far below: the sum of {@code |ln(part / requested)|} over {@code belowSpec}
     */
    public record Assessment(double score, Double match, List<String> mismatches, List<String> unverified,
                             List<String> belowSpec, double belowSpecDistance) {

        public Assessment {
            mismatches = mismatches == null ? List.of() : List.copyOf(mismatches);
            unverified = unverified == null ? List.of() : List.copyOf(unverified);
            belowSpec = belowSpec == null ? List.of() : List.copyOf(belowSpec);
        }

        public Assessment(double score, double match, List<String> mismatches) {
            this(score, match, mismatches, List.of(), List.of(), 0);
        }

        public Assessment(double score, double match) {
            this(score, match, List.of());
        }

        /** A known rating is below the request. */
        public boolean isBelowSpec() {
            return !belowSpec.isEmpty();
        }

        /** Every stated constraint is verified and met: no mismatch and nothing unverified. */
        public boolean complete() {
            return mismatches.isEmpty() && unverified.isEmpty();
        }
    }

    /**
     * The stated parameters the part is known not to satisfy, in plain words ({@code "dielectric: X5R instead of
     * X7R"}, {@code "package: 1210 instead of 1206"}, {@code "voltage: 16V below 25V"}); an attribute the part does not
     * state is not a mismatch. Reported per part as {@code mismatches} (DESIGN.md 4).
     */
    static List<String> mismatches(ParsedQuery query, ParametricExtractor.Features f) {
        List<String> out = new java.util.ArrayList<>();
        String primary = query.isConnector() ? null : primaryKind(query);
        if (primary != null) {
            ParsedQuery.Constraint wanted = query.constraint(primary);
            Recognizers.Value actual = f.values().get(primary);
            if (actual != null && !(sameValue(wanted.value(), actual.value(), VALUE_MATCH_TOLERANCE)
                    && (wanted.condition() == null || actual.condition() == null
                    || sameValue(wanted.condition(), actual.condition(), VALUE_MATCH_TOLERANCE)))) {
                out.add(label(primary) + ": " + actual.display() + " instead of " + wanted.display());
            }
        }
        String wantedPackage = Recognizers.packageKey(query.packageName());
        String partPackage = Recognizers.packageKey(f.packageName());
        if (wantedPackage != null && partPackage != null && !wantedPackage.equals(partPackage)) {
            out.add("package: " + f.packageName() + " instead of " + query.packageName());
        }
        if (query.dielectric() != null && f.dielectric() != null
                && !query.dielectric().equalsIgnoreCase(f.dielectric())) {
            out.add("dielectric: " + f.dielectric() + " instead of " + query.dielectric());
        }
        if (query.technology() != null && TechnologyVocabulary.compare(query.technology(), f.technology()) < 0) {
            out.add("technology: " + f.technology() + " instead of " + query.technology());
        }
        for (String kind : RATING_KINDS) {
            ParsedQuery.Constraint wanted = query.constraint(kind);
            Recognizers.Value actual = f.values().get(kind);
            if (wanted == null || actual == null) {
                continue;
            }
            if (ParsedQuery.DCR.equals(kind)) {
                if (actual.value() > wanted.value() * (1 + 1e-9)) {
                    out.add("dcr: " + actual.display() + " above " + wanted.display());
                }
            } else if (isExactRating(kind, query.family())) {
                if (!sameValue(wanted.value(), actual.value(), EXACT_VOLTAGE_TOLERANCE)) {
                    out.add(label(kind) + ": " + actual.display() + " instead of " + wanted.display());
                }
            } else if (actual.value() < wanted.value() * (1 - 1e-9)) {
                out.add(label(kind) + ": " + actual.display() + " below " + wanted.display());
            }
        }
        ParsedQuery.Constraint tolerance = query.constraint(ParsedQuery.TOLERANCE);
        Recognizers.Value partTolerance = f.values().get(ParsedQuery.TOLERANCE);
        if (tolerance != null && partTolerance != null && partTolerance.value() > tolerance.value() + 1e-9) {
            out.add("tolerance: " + partTolerance.display() + " instead of " + tolerance.display());
        }
        if (query.mounting() != null && f.mounting() != null && !query.mounting().equals(f.mounting())
                && !(f.connector() != null && UsbVocabulary.HYBRID.equals(f.connector().mountingStyle()))) {
            out.add("mounting: " + f.mounting() + " instead of " + query.mounting());
        }
        if (query.family() != null && familyScore(query.family(), f) < 0) {
            out.add("family: " + f.family() + " instead of " + query.family());
        }
        if (query.elements() == null && f.elements() != null && query.family() != null
                && PassiveDetails.ARRAY_FAMILIES.contains(query.family())) {
            out.add("elements: " + PassiveDetails.elementsDisplay(f.elements()) + " instead of single");
        } else if (query.elements() != null) {
            String wantedElements = PassiveDetails.elementsDisplay(query.elements());
            if (f.elements() == null) {
                out.add("elements: single instead of " + wantedElements);
            } else if (query.elements() != ParsedQuery.ANY_ELEMENTS && f.elements() != ParsedQuery.ANY_ELEMENTS
                    && !query.elements().equals(f.elements())) {
                out.add("elements: " + f.elements() + " instead of " + wantedElements);
            }
        }
        ParsedQuery.Connector wanted = query.connector();
        ParsedQuery.Connector actual = f.connector();
        if (wanted != null && actual != null && !wanted.isUsb()) {
            if (wanted.positions() != null && actual.positions() != null && !wanted.positions().equals(actual.positions())) {
                out.add("positions: " + actual.positions() + " instead of " + wanted.positions());
            }
            if (wanted.gender() != null && actual.gender() != null && !wanted.gender().equals(actual.gender())) {
                out.add("gender: " + actual.gender() + " instead of " + wanted.gender());
            }
            if (wanted.pitchMm() != null && actual.pitchMm() != null
                    && Math.abs(wanted.pitchMm() - actual.pitchMm()) > PITCH_TOLERANCE_MM) {
                out.add("pitch: " + actual.pitchDisplay() + " instead of " + wanted.pitchDisplay());
            }
            if (wanted.orientation() != null && actual.orientation() != null
                    && !wanted.orientation().equals(actual.orientation())) {
                out.add("orientation: " + actual.orientation() + " instead of " + wanted.orientation());
            }
        }
        return out;
    }

    private static String label(String kind) {
        return kind.replace('_', ' ');
    }

    /**
     * How a part relates to the request's strict constraints ({@code kina.search.strict-constraints}): a known
     * contradiction ({@link #CONFLICT}, the part is excluded), an attribute the part does not state
     * ({@link #UNKNOWN}, the part stays but ranks below known matches), or nothing against it ({@link #MATCH}).
     */
    public enum ConstraintCheck { MATCH, UNKNOWN, CONFLICT }

    /**
     * Checks the strict constraints the request states (mounting SMD/THT, the technology of a passive) against the
     * part. Mounting: a known different mounting conflicts (a hybrid USB part never does). Technology: a different
     * known technology conflicts ({@link TechnologyVocabulary#compare} = -1); an unknown or not comparable one is
     * {@link ConstraintCheck#UNKNOWN}.
     */
    public ConstraintCheck check(ParsedQuery query, Part part, java.util.Collection<String> strict) {
        return check(query, extractor.features(part), strict);
    }

    static ConstraintCheck check(ParsedQuery query, ParametricExtractor.Features f, java.util.Collection<String> strict) {
        if (strict == null || strict.isEmpty()) {
            return ConstraintCheck.MATCH;
        }
        boolean unknown = false;
        if (strict.contains(STRICT_ELEMENTS) && query.elements() == null && f.elements() != null
                && query.family() != null && PassiveDetails.ARRAY_FAMILIES.contains(query.family())) {
            return ConstraintCheck.CONFLICT;   // a bead array or resistor network for a single-element request
        }
        if (strict.contains(STRICT_MOUNTING) && query.mounting() != null) {
            boolean hybrid = f.connector() != null && UsbVocabulary.HYBRID.equals(f.connector().mountingStyle());
            if (f.mounting() == null || hybrid) {
                unknown = true;
            } else if (!query.mounting().equals(f.mounting())) {
                return ConstraintCheck.CONFLICT;
            }
        }
        if (strict.contains(STRICT_TECHNOLOGY) && query.technology() != null) {
            int cmp = TechnologyVocabulary.compare(query.technology(), f.technology());
            if (cmp < 0) {
                return ConstraintCheck.CONFLICT;
            }
            unknown |= cmp == 0;
        }
        return unknown ? ConstraintCheck.UNKNOWN : ConstraintCheck.MATCH;
    }

    /**
     * Score deduction for an order of {@code quantity} pieces (DESIGN.md 3.4 "Quantity"): {@code stockWeight} when the
     * part has fewer pieces in stock than requested, and up to {@code moqWeight} when its minimum order quantity
     * exceeds the quantity, also for a quantity of 1 ({@code moqWeight * min(1, log10(moq / quantity) / 3)}, complete
     * at 1000x: a 2000-piece MOQ for one piece loses all of it, an MOQ of 10 a third).
     */
    public static double quantityPenalty(Part part, int quantity, double stockWeight, double moqWeight) {
        int qty = Math.max(1, quantity);
        double penalty = part.stock() < qty ? stockWeight : 0;
        Integer moq = part.minimumOrderQuantity();
        if (moq != null && moq > qty) {
            penalty += moqWeight * Math.min(1.0, Math.log10((double) moq / qty) / MOQ_PENALTY_DECADES);
        }
        return penalty;
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
        List<String> unverified = new java.util.ArrayList<>();

        // primary value; connector queries use the connector signals instead
        String primary = query.isConnector() ? null : primaryKind(query);
        if (query.isConnector()) {
            score += connectorScore(query, f);
            possible += connectorPossible(query, f, unverified);
        }
        if (primary != null) {
            Recognizers.Value partValue = f.values().get(primary);
            if (partValue == null) {
                unverified.add(label(primary));
            } else {
                possible += W_PRIMARY_VALUE;
                ParsedQuery.Constraint wanted = query.constraint(primary);
                boolean same = sameValue(wanted.value(), partValue.value(), VALUE_MATCH_TOLERANCE)
                        // an impedance is compared at its test frequency when both sides state one
                        && (wanted.condition() == null || partValue.condition() == null
                        || sameValue(wanted.condition(), partValue.condition(), VALUE_MATCH_TOLERANCE));
                score += same ? W_PRIMARY_VALUE : -W_PRIMARY_VALUE;
            }
        }

        // package
        String wantedPackage = Recognizers.packageKey(query.packageName());
        String partPackage = Recognizers.packageKey(f.packageName());
        if (wantedPackage != null) {
            if (partPackage == null) {
                unverified.add("package");
            } else {
                possible += W_PACKAGE;
                score += wantedPackage.equals(partPackage) ? W_PACKAGE : -W_PACKAGE;
            }
        }

        // dielectric
        if (query.dielectric() != null) {
            if (f.dielectric() == null) {
                unverified.add("dielectric");
            } else {
                possible += W_DIELECTRIC;
                score += query.dielectric().equalsIgnoreCase(f.dielectric()) ? W_DIELECTRIC : -W_DIELECTRIC;
            }
        }

        // technology (thin film vs thick film, tantalum vs ceramic...); a known but not comparable one earns nothing
        if (query.technology() != null) {
            if (f.technology() == null) {
                unverified.add("technology");
            } else {
                possible += W_TECHNOLOGY;
                score += W_TECHNOLOGY * TechnologyVocabulary.compare(query.technology(), f.technology());
            }
        }

        // ratings: minimums (a higher rating satisfies them), DCR a maximum; each stated rating has an equal share
        List<String> requested = RATING_KINDS.stream().filter(k -> query.constraint(k) != null).toList();
        double preference = 0;   // score-only adjustments, not part of the match grade
        List<String> belowSpec = new java.util.ArrayList<>();
        double belowSpecDistance = 0;
        for (String kind : requested) {
            Double partValue = f.value(kind);
            if (partValue == null) {
                unverified.add(label(kind));
                continue;
            }
            double share = W_RATING / requested.size();
            possible += share;
            double wanted = query.constraint(kind).value();
            boolean exact = isExactRating(kind, query.family());
            boolean ok;
            if (ParsedQuery.DCR.equals(kind)) {
                ok = partValue <= wanted * (1 + 1e-9);
            } else if (exact) {
                ok = sameValue(wanted, partValue, EXACT_VOLTAGE_TOLERANCE);
            } else {
                ok = partValue >= wanted * (1 - 1e-9);
            }
            score += ok ? share : -share;
            if (!ok && !exact && wanted > 0 && partValue > 0) {
                belowSpec.add(label(kind));
                belowSpecDistance += Math.abs(Math.log(partValue / wanted));
            }
            if (ok && !exact && !ParsedQuery.DCR.equals(kind) && wanted > 0 && partValue > wanted * (1 + 1e-9)) {
                double octaves = Math.log(partValue / wanted) / Math.log(2);
                preference -= W_RATING_EXCESS * Math.min(1.0, octaves / RATING_EXCESS_OCTAVES) / requested.size();
            }
        }

        // mounting of a non-connector request (connector requests score it among the connector signals)
        if (!query.isConnector() && query.mounting() != null) {
            if (f.mounting() == null) {
                unverified.add("mounting");
            } else {
                possible += W_MOUNTING;
                score += query.mounting().equals(f.mounting()) ? W_MOUNTING : -W_MOUNTING;
            }
        }

        // "low DCR": lower DC resistance ranks higher among otherwise equal parts
        Double dcr = f.value(ParsedQuery.DCR);
        if (query.prefers(ParsedQuery.LOW_DCR) && dcr != null && dcr >= 0) {
            preference += W_LOW_DCR / (1 + dcr / LOW_DCR_REFERENCE_OHM);
        }

        // tolerance
        ParsedQuery.Constraint tolerance = query.constraint(ParsedQuery.TOLERANCE);
        Double partTolerance = f.value(ParsedQuery.TOLERANCE);
        if (tolerance != null) {
            if (partTolerance == null) {
                unverified.add("tolerance");
            } else {
                possible += W_TOLERANCE;
                score += partTolerance <= tolerance.value() + 1e-9 ? W_TOLERANCE : -W_TOLERANCE;
            }
        }

        // an element count of an array the part does not state
        if (query.elements() != null && query.elements() != ParsedQuery.ANY_ELEMENTS
                && f.elements() != null && f.elements() == ParsedQuery.ANY_ELEMENTS) {
            unverified.add("elements");
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

        Double match = possible <= 0 ? (unverified.isEmpty() ? Double.valueOf(1.0) : null)
                : Double.valueOf(Math.clamp(score / possible, 0.0, 1.0));
        return new Assessment(Math.clamp(score + preference + tieBreak(part), 0.0, 1.0), match, mismatches(query, f),
                unverified, belowSpec, belowSpecDistance);
    }

    /**
     * What a connector part matching every stated and verified connector attribute earns ({@link #connectorScore});
     * attributes the request states but the part does not are added to {@code unverified} instead.
     */
    static double connectorPossible(ParsedQuery query, ParametricExtractor.Features f, List<String> unverified) {
        ParsedQuery.Connector wanted = query.connector();
        if (wanted == null) {
            return 0;
        }
        ParsedQuery.Connector actual = f.connector();
        double possible = 0;
        if (wanted.isUsb()) {
            String wantedType = wanted.usbType() != null ? wanted.usbType() : UsbVocabulary.usbTypeOf(wanted.type());
            String actualType = actual == null ? null
                    : actual.usbType() != null ? actual.usbType() : UsbVocabulary.usbTypeOf(actual.type());
            boolean otherConnector = actual != null && !actual.isUsb() && actual.type() != null
                    && !ParsedQuery.CONNECTOR.equals(actual.type());
            if (wantedType != null) {
                possible += verified(actualType != null || otherConnector, W_USB_TYPE, "usb type", unverified);
            }
            if (wanted.pinConfiguration() != null || wanted.positions() != null) {
                Integer actualPins = actual == null ? null : actual.pinConfiguration() != null
                        ? actual.pinConfiguration() : UsbVocabulary.configuration(wantedType, actual.positions());
                possible += verified(actualPins != null,
                        wanted.pinConfigurationImplied() ? W_USB_PINS / 2 : W_USB_PINS, "pin configuration",
                        unverified);
            }
            UsbVocabulary.Standard wantedStandard = UsbVocabulary.standard(wanted.usbStandard());
            if (wantedStandard != null) {
                boolean known = actual != null && (actual.hasFeature(UsbVocabulary.POWER_ONLY)
                        && actual.usbStandard() == null
                        || UsbVocabulary.compare(wantedStandard, UsbVocabulary.standard(actual.usbStandard())) != null);
                possible += verified(known, W_USB_STANDARD, "usb standard", unverified);
            }
            if (wanted.gender() != null) {
                possible += verified(actual != null && actual.gender() != null, W_USB_GENDER, "gender", unverified);
            }
            if (wanted.mountingStyle() != null || query.mounting() != null) {
                boolean known = actual != null
                        && usbMounting(wanted.mountingStyle(), query.mounting(), actual, f.mounting()) != null;
                possible += verified(known, W_USB_MOUNTING, "mounting", unverified);
            }
            if (wanted.orientation() != null) {
                possible += verified(actual != null && actual.orientation() != null, W_USB_ORIENTATION,
                        "orientation", unverified);
            }
            for (String feature : USB_BONUS_FEATURES) {
                if (wanted.hasFeature(feature)) {
                    possible += W_USB_FEATURE;
                }
            }
            return possible;
        }
        if (wanted.positions() != null) {
            possible += verified(actual != null && actual.positions() != null, W_POSITIONS, "positions", unverified);
        }
        if (wanted.gender() != null) {
            possible += verified(actual != null && actual.gender() != null, W_GENDER, "gender", unverified);
        }
        if (wanted.orientation() != null) {
            possible += verified(actual != null && actual.orientation() != null, W_ORIENTATION, "orientation",
                    unverified);
        }
        if (wanted.pitchMm() != null) {
            possible += verified(actual != null && actual.pitchMm() != null, W_PITCH, "pitch", unverified);
        }
        if (wanted.type() != null && !ParsedQuery.CONNECTOR.equals(wanted.type())
                && !ParsedQuery.HEADER.equals(wanted.type()) && !ParsedQuery.USB.equals(wanted.type())) {
            possible += verified(actual != null && ConnectorRecognizer.typesMatch(wanted.type(), actual.type()) != null,
                    W_CONNECTOR_TYPE, "connector type", unverified);
        }
        if (query.mounting() != null) {
            possible += verified(f.mounting() != null, W_CONNECTOR_MOUNTING, "mounting", unverified);
        }
        return possible;
    }

    /** {@code weight} when the part states the attribute; else 0 and the attribute is unverified. */
    private static double verified(boolean known, double weight, String name, List<String> unverified) {
        if (known) {
            return weight;
        }
        unverified.add(name);
        return 0;
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

    /**
     * True when a rating of {@code kind} must match rather than be exceeded: the output voltage of a regulator, the
     * Zener voltage, the current of a fuse. Every other voltage, current, power, temperature and lifetime in a request
     * is a minimum rating, which {@link DistributorPhraser} leaves out of distributor phrases.
     */
    static boolean isExactRating(String kind, String family) {
        return ParsedQuery.VOLTAGE.equals(kind) && exactVoltageFamily(family)
                || ParsedQuery.CURRENT.equals(kind) && "fuse".equals(family);
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
