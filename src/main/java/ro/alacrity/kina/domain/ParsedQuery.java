package ro.alacrity.kina.domain;

import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * A free-text component query after parsing by {@code search.QueryParser} (DESIGN.md section 3.4).
 *
 * <p>Typed constraints are keyed by the constants below. {@link Constraint#value()} is normalised to
 * the SI base unit: farad, ohm, henry, volt, ampere, watt, hertz; tolerance is in percent
 * (e.g. {@code 5.0} for {@code ±5%}), {@link #TEMPERATURE} in degrees Celsius, {@link #LIFETIME} in hours.
 * {@link Constraint#display()} is a compact human form ("10uF", "4.7kohm", "16V", "5%", "120ohm @100MHz") used in
 * responses. Voltage, current, saturation current, power, temperature and lifetime are minimum ratings (a part with a
 * higher rating satisfies them); {@link #DCR} is a maximum.
 *
 * @param originalText the query as received
 * @param normalizedKey normalised cache key (trim, collapse whitespace, lower-case, NFKC, µ-&gt;u, Ω-&gt;ohm)
 * @param family       component family ("capacitor", "resistor", "mosfet"...), null when not recognised
 * @param constraints  typed numeric constraints, insertion-ordered, keyed by the constants in this record
 * @param dielectric   "X7R", "C0G" (NP0 is normalised to C0G), null when absent
 * @param packageName  "0805", "SOT-23"..., imperial code for chip packages, null when absent
 * @param mounting     "SMD" or "THT", null when absent
 * @param keywords     remaining free-text tokens (lower-case)
 * @param connector    connector attributes when the query asks for a connector (family {@code "connector"}), else null
 * @param technology   construction technology of a passive ("thin film", "tantalum", "multilayer"...; see
 *                     {@code search.TechnologyVocabulary}), null when the query names none
 * @param preferences  soft preferences that are not constraints, e.g. {@link #LOW_DCR} ("low DCR": lower DC
 *                     resistance ranks higher among otherwise equal parts)
 * @param elements     null when the request does not ask for an array or network (a single resistor, capacitor or
 *                     ferrite bead is wanted); {@link #ANY_ELEMENTS} for "array"/"network" without a count; else the
 *                     requested number of elements ("4 lines", "4 elements", "x4")
 * @param polarity     transistor polarity ("N-channel", "P-channel", "NPN", "PNP", "complementary"), null when not
 *                     stated; a hard constraint (DESIGN.md 3.4)
 * @param subtype      "standard" for a rectifier or switching diode of the generic diode family, "fixed" or
 *                     "adjustable" for a regulator (a stated output voltage implies "fixed"), else null; a hard
 *                     constraint
 * @param formFactor   the form factor the request's words name ({@code "chassis"} for heatsink, chassis, bolt or screw
 *                     mount and aluminium housed; {@code search.FormFactor}), null when none; a package with a form
 *                     factor (SOT-227, 0805) is read from {@link #packageName} instead. A hard constraint for
 *                     resistors, capacitors, inductors and the default family (DESIGN.md 3.4)
 */
@Builder(toBuilder = true)
public record ParsedQuery(
        String originalText,
        String normalizedKey,
        String family,
        Map<String, Constraint> constraints,
        String dielectric,
        String packageName,
        String mounting,
        List<String> keywords,
        Connector connector,
        String technology,
        List<String> preferences,
        Integer elements,
        String polarity,
        String subtype,
        String formFactor
) {

    /** {@link #elements()} of a request for an array or network whose element count is not stated. */
    public static final int ANY_ELEMENTS = 0;

    public static final String CAPACITANCE = "capacitance";
    public static final String RESISTANCE = "resistance";
    public static final String INDUCTANCE = "inductance";
    public static final String VOLTAGE = "voltage";
    public static final String CURRENT = "current";
    public static final String POWER = "power";
    public static final String FREQUENCY = "frequency";
    public static final String TOLERANCE = "tolerance";
    /** Impedance of a ferrite bead at its test frequency ({@link Constraint#condition()}, Hz), in ohm. */
    public static final String IMPEDANCE = "impedance";
    /** Saturation current of an inductor (I_sat), in ampere; a minimum. */
    public static final String SATURATION_CURRENT = "saturation_current";
    /** Maximum DC resistance of an inductor or ferrite bead, in ohm; a maximum. */
    public static final String DCR = "dcr";
    /** Maximum operating temperature, in degrees Celsius; a minimum. */
    public static final String TEMPERATURE = "temperature";
    /** Rated lifetime (endurance) in hours, at the temperature in {@link Constraint#condition()} when stated. */
    public static final String LIFETIME = "lifetime";

    /** Preference: lower DC resistance ranks higher ("low DCR"). */
    public static final String LOW_DCR = "low dcr";

    /** Connector types ({@link Connector#type()}). */
    public static final String PIN_HEADER = "pin header";
    public static final String FEMALE_HEADER = "female header";
    /** A header whose gender is not known (Dupont style, Mouser "Headers &amp; Wire Housings"). */
    public static final String HEADER = "header";
    public static final String BOX_HEADER = "box header";
    public static final String IDC_SOCKET = "idc socket";
    public static final String IC_SOCKET = "ic socket";
    public static final String TERMINAL_BLOCK = "terminal block";
    public static final String WIRE_TO_BOARD = "wire-to-board";
    public static final String USB_C = "usb-c";
    public static final String MICRO_USB = "micro usb";
    public static final String USB = "usb";
    public static final String FPC = "fpc";
    public static final String RJ45 = "rj45";
    public static final String D_SUB = "d-sub";
    public static final String BARREL_JACK = "barrel jack";
    /** Generic connector without a recognised type. */
    public static final String CONNECTOR = "connector";

    /** USB connector types ({@link Connector#usbType()}). */
    public static final String USB_TYPE_C = "Type-C";
    public static final String USB_MICRO_B = "Micro-B";
    public static final String USB_MICRO_AB = "Micro-AB";
    public static final String USB_MINI_B = "Mini-B";
    public static final String USB_MINI_AB = "Mini-AB";
    public static final String USB_TYPE_A = "Type-A";
    public static final String USB_TYPE_B = "Type-B";

    public static final String MALE = "male";
    public static final String FEMALE = "female";

    public static final String RIGHT_ANGLE = "right angle";
    public static final String VERTICAL = "vertical";

    public ParsedQuery {
        constraints = constraints == null ? Map.of() : constraints;
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        preferences = preferences == null ? List.of() : List.copyOf(preferences);
    }

    /** A query without connector attributes. */
    public ParsedQuery(String originalText, String normalizedKey, String family, Map<String, Constraint> constraints,
                       String dielectric, String packageName, String mounting, List<String> keywords) {
        this(originalText, normalizedKey, family, constraints, dielectric, packageName, mounting, keywords, null, null,
                null, null, null, null, null);
    }

    /**
     * True when the parser recognised something typed: a family, a value or rating, the dielectric, package, mounting,
     * technology, connector attributes or an element count. False for keyword-only text ({@code asdfqwerty zz9}, a bare
     * part number): the search then has no parametric understanding and reports no {@code match}.
     */
    public boolean understood() {
        return family != null || !constraints.isEmpty() || dielectric != null || packageName != null
                || mounting != null || technology != null || connector != null || elements != null
                || formFactor != null;
    }

    /** True when the query states the preference ({@link #LOW_DCR}). */
    public boolean prefers(String preference) {
        return preferences.contains(preference);
    }

    /** True when the query asks for a connector (connector words were recognised). */
    public boolean isConnector() {
        return connector != null;
    }

    /**
     * Connector attributes of a query or a part (DESIGN.md 3.4). Every field is null when unknown.
     *
     * @param type          one of the connector type constants of {@link ParsedQuery} ("female header", "usb-c"...)
     * @param series        wire-to-board series ("XH", "PH", "GH", "SH", "ZH"), else null
     * @param gender        {@link #MALE} or {@link #FEMALE}
     * @param positions     total number of positions (pins / contacts / ways) as written or reported, e.g. 6 for
     *                      {@code 2x3}; 17 for a Type-C part listed as {@code 17P}
     * @param rows          number of rows ({@code 1x6} -&gt; 1, {@code 2x3} -&gt; 2, "dual row" -&gt; 2)
     * @param pitchMm       contact pitch in millimetres (0.1" -&gt; 2.54)
     * @param pitchImplied  true when the pitch was not written but implied (Dupont -&gt; 2.54 mm, JST XH -&gt; 2.5 mm)
     * @param orientation   {@link #RIGHT_ANGLE} or {@link #VERTICAL}
     * @param usbType       USB connector type ({@link #USB_TYPE_C}, {@link #USB_MICRO_B}, {@link #USB_TYPE_A}...)
     * @param usbStandard   canonical USB standard ("USB 2.0", "USB 3.2 Gen 1", "USB 3.x" (generation not stated),
     *                      "USB 3.2 Gen 2", "USB 3.2 Gen 2x2", "USB4", "Thunderbolt 3", "Thunderbolt 4")
     * @param usbSpeedGbps  speed class of the standard in Gbit/s (0.48, 5, 10, 20, 40)
     * @param pinConfiguration canonical signal-pin configuration (Type-C 6/12/14/16/24, Micro-B 5/10, Type-A 4/9);
     *                      {@code 17P}/{@code 18P} Type-C -&gt; 16 (shell pins counted), see {@link #shieldPinsCounted}
     * @param pinConfigurationImplied true when a query did not state the pin count and the configuration is inferred
     *                      from the standard ("USB 2.0 Type-C" -&gt; 16, "USB 3.1 Type-C" -&gt; 24, power only -&gt; 6)
     * @param shieldPinsCounted shell/shield/mounting pins included in {@link #positions} (17P -&gt; 1), else null
     * @param mountingStyle "mid-mount", "hybrid" (SMD signal pins with through-hole shell legs) or "top-mount"
     * @param features      USB features ("power only", "PD", "mid-mount", "hybrid", "waterproof", "IP67",
     *                      "board lock", "through-hole shell", "4 legs", "multi-port"...), never null
     */
    @Builder(toBuilder = true)
    public record Connector(String type, String series, String gender, Integer positions, Integer rows, Double pitchMm,
                            boolean pitchImplied, String orientation, String usbType, String usbStandard,
                            Double usbSpeedGbps, Integer pinConfiguration, boolean pinConfigurationImplied,
                            Integer shieldPinsCounted, String mountingStyle, List<String> features) {

        public Connector {
            features = features == null ? List.of() : List.copyOf(features);
        }

        /** Connector attributes without USB details. */
        public Connector(String type, String series, String gender, Integer positions, Integer rows, Double pitchMm,
                         boolean pitchImplied, String orientation) {
            this(type, series, gender, positions, rows, pitchMm, pitchImplied, orientation, null, null, null, null,
                    false, null, null, List.of());
        }

        /** True when no attribute is known. */
        public boolean isEmpty() {
            return type == null && series == null && gender == null && positions == null && rows == null
                    && pitchMm == null && orientation == null && usbType == null && usbStandard == null
                    && pinConfiguration == null && mountingStyle == null && features.isEmpty();
        }

        /** True for USB connectors (a USB type, or the connector type usb-c / micro usb / usb). */
        public boolean isUsb() {
            return usbType != null || USB_C.equals(type) || MICRO_USB.equals(type) || USB.equals(type);
        }

        public boolean hasFeature(String feature) {
            return features.contains(feature);
        }

        /** Pitch as display text ("2.54mm", "2mm"), or null. */
        public String pitchDisplay() {
            if (pitchMm == null) {
                return null;
            }
            String s = java.math.BigDecimal.valueOf(pitchMm).stripTrailingZeros().toPlainString();
            return s + "mm";
        }
    }

    /**
     * One numeric constraint.
     *
     * @param kind      one of the {@link ParsedQuery} constants (same as the map key)
     * @param value     value in SI base units (tolerance: percent, temperature: degrees Celsius, lifetime: hours)
     * @param display   compact human form, e.g. "10uF", "120ohm @100MHz", "2000h @105°C"
     * @param condition the test condition when stated: the test frequency in Hz of an {@link #IMPEDANCE}, the
     *                  temperature in degrees Celsius of a {@link #LIFETIME}; else null
     */
    public record Constraint(String kind, double value, String display, Double condition) {

        public Constraint(String kind, double value, String display) {
            this(kind, value, display, null);
        }
    }

    public Constraint constraint(String kind) {
        return constraints.get(kind);
    }
}
