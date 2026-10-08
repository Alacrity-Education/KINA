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
 * @param partNumbers  the part-number-shaped tokens of the query as sent ({@code uP1966E}, {@code EPC2302}): letters
 *                     and digits mixed, at least 5 characters, no value, unit, package or vocabulary word
 *                     ({@code search.PartNumbers}); a part whose MPN or distributor part number equals one or starts
 *                     with it is the requested part (DESIGN.md 3.4 "Requested part numbers"); empty when none
 * @param fan          the fan attributes of a fan request (family {@code "fan"}: type, supply, frame size, bearing,
 *                     features; DESIGN.md 3.4 "Fans"), else null
 * @param led          the LED attributes of an LED request (family {@code "led"}: colour, lens, LED type, orientation;
 *                     DESIGN.md 3.4 "LEDs"), else null
 * @param sw           the switch attributes of a switch request (family {@code "switch"}: switch type, contacts,
 *                     function, termination class, size, hole diameter, positions, illumination, orientation, the
 *                     AC or DC of the stated voltage; DESIGN.md 3.4 "Switches"), else null
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
        String formFactor,
        List<String> partNumbers,
        Fan fan,
        Led led,
        Switch sw
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
    /** Rotational speed of a fan, in rpm; within 15 % (DESIGN.md 3.4 "Fans"). */
    public static final String SPEED = "speed";
    /** Airflow of a fan, in m³/h; a minimum. */
    public static final String AIRFLOW = "airflow";
    /** Static pressure of a fan, in pascal; a minimum. */
    public static final String STATIC_PRESSURE = "static_pressure";
    /** Acoustic noise of a fan, in dBA; a maximum. */
    public static final String NOISE = "noise";
    /** Dominant (or peak) wavelength of an LED, in nanometres; within 10 nm (DESIGN.md 3.4 "LEDs"). */
    public static final String WAVELENGTH = "wavelength";
    /** Correlated colour temperature of a white LED, in kelvin; within 300 K, relaxable. */
    public static final String COLOUR_TEMPERATURE = "colour_temperature";
    /** Forward voltage of an LED, in volt; a request value is a maximum (the part must not need more). */
    public static final String FORWARD_VOLTAGE = "forward_voltage";
    /** Luminous intensity of an LED, in candela; a minimum. */
    public static final String LUMINOUS_INTENSITY = "luminous_intensity";
    /** Luminous flux of an LED, in lumen; a minimum. */
    public static final String LUMINOUS_FLUX = "luminous_flux";
    /** Viewing angle of an LED, in degrees; within 15 degrees, a preference. */
    public static final String VIEWING_ANGLE = "viewing_angle";
    /** Operating force of a switch, in newton; within 20 %, a preference (DESIGN.md 3.4 "Switches"). */
    public static final String FORCE = "force";
    /** Mechanical life of a switch, in cycles; a minimum. */
    public static final String LIFE = "life";
    /**
     * Ingress protection of a switch: {@code 10 * solids + liquids} ({@code IP67} is 67, {@code IPX7} 7); a minimum
     * in both digits.
     */
    public static final String IP_RATING = "ip_rating";
    /** The AC voltage rating of a switch, in volt (Mouser {@code Voltage Rating AC}, {@code 250VAC}). */
    public static final String VOLTAGE_AC = "voltage_ac";
    /** The DC voltage rating of a switch, in volt (Mouser {@code Voltage Rating DC}, {@code 30VDC}). */
    public static final String VOLTAGE_DC = "voltage_dc";

    /** Fan types ({@link Fan#type()}): an axial fan, a radial (centrifugal) fan or blower. */
    public static final String AXIAL = "axial";
    public static final String RADIAL = "radial";
    /** Fan supplies ({@link Fan#supply()}). */
    public static final String DC = "DC";
    public static final String AC = "AC";

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

    /** {@link #subtype()} of a standard (non-Schottky) rectifier or switching diode. */
    public static final String STANDARD = "standard";

    /** USB mounting style ({@link Connector#mountingStyle()}): SMD signal pins with through-hole shell legs. */
    public static final String HYBRID = "hybrid";
    /** USB features ({@link Connector#features()}). */
    public static final String POWER_ONLY = "power only";
    public static final String WATERPROOF = "waterproof";
    public static final String BOARD_LOCK = "board lock";
    public static final String FULLY_SMD = "fully SMD";
    /** Fan features ({@link Fan#features()}) with a kind of their own: the PWM speed input and the tacho output. */
    public static final String PWM = "PWM";
    public static final String TACHO = "tacho";

    public ParsedQuery {
        constraints = constraints == null ? Map.of() : constraints;
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        preferences = preferences == null ? List.of() : List.copyOf(preferences);
        partNumbers = partNumbers == null ? List.of() : List.copyOf(partNumbers);
    }

    /** A query without connector attributes. */
    public ParsedQuery(String originalText, String normalizedKey, String family, Map<String, Constraint> constraints,
                       String dielectric, String packageName, String mounting, List<String> keywords) {
        this(originalText, normalizedKey, family, constraints, dielectric, packageName, mounting, keywords, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    /**
     * True when the parser recognised something typed: a family, a value or rating, the dielectric, package, mounting,
     * technology, connector attributes or an element count. False for keyword-only text ({@code asdfqwerty zz9}, a bare
     * part number): the search then has no parametric understanding and reports no {@code match}.
     */
    public boolean understood() {
        return family != null || !constraints.isEmpty() || dielectric != null || packageName != null
                || mounting != null || technology != null || connector != null || elements != null
                || formFactor != null || fan != null || led != null || sw != null;
    }

    /** True when the query names a part number ({@link #partNumbers}). */
    public boolean namesPartNumber() {
        return !partNumbers.isEmpty();
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
     * Fan attributes of a query or a part (DESIGN.md 3.4 "Fans"). Every field is null when unknown.
     *
     * @param type     {@link #AXIAL} or {@link #RADIAL} (a blower, a centrifugal fan)
     * @param supply   {@link #DC} or {@link #AC}
     * @param frame    the frame size
     * @param bearing  {@code ball}, {@code sleeve}, {@code fluid dynamic}, {@code rifle} or {@code magnetic}
     * @param features {@code PWM}, {@code tacho}, {@code 3-wire}, {@code 4-wire}, {@code IP55}, {@code auto restart}...,
     *                 never null
     */
    @Builder(toBuilder = true)
    public record Fan(String type, String supply, Frame frame, String bearing, List<String> features) {

        public Fan {
            features = features == null ? List.of() : List.copyOf(features);
        }

        /** True when no attribute is known. */
        public boolean isEmpty() {
            return type == null && supply == null && frame == null && bearing == null && features.isEmpty();
        }
    }

    /**
     * The frame size of a fan in millimetres: width and length (equal for a square frame) and the depth when stated
     * ({@code 40x40x10mm}; a bare {@code 120mm} states width and length only).
     */
    public record Frame(double width, double length, Double depth) {

        /** Largest difference in millimetres between a width or length and the same one. */
        public static final double TOLERANCE_MM = 0.5;
        /**
         * Largest difference in millimetres between two depths that are the same: a nominal 10 mm fan measures 10 to
         * 10.6 mm (Mouser {@code 40x40x10.6mm}), a 15 mm one is another fan.
         */
        public static final double DEPTH_TOLERANCE_MM = 1.0;

        /**
         * True when {@code actual} is this frame: width and length within {@value #TOLERANCE_MM} mm (in either order),
         * and the depth within {@value #DEPTH_TOLERANCE_MM} mm when both state one.
         */
        public boolean matches(Frame actual) {
            boolean straight = same(width, actual.width, TOLERANCE_MM) && same(length, actual.length, TOLERANCE_MM);
            boolean crossed = same(width, actual.length, TOLERANCE_MM) && same(length, actual.width, TOLERANCE_MM);
            return (straight || crossed)
                    && (depth == null || actual.depth == null || same(depth, actual.depth, DEPTH_TOLERANCE_MM));
        }

        private static boolean same(double a, double b, double tolerance) {
            return Math.abs(a - b) <= tolerance + 1e-9;
        }

        /** {@code 40x40x10mm}, {@code 120mm} (width and length only, square), {@code 97x94x33mm}. */
        public String display() {
            String w = number(width);
            if (depth == null && Math.abs(width - length) < 1e-9) {
                return w + "mm";
            }
            return w + "x" + number(length) + (depth == null ? "" : "x" + number(depth)) + "mm";
        }

        private static String number(double v) {
            return java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
        }
    }

    /**
     * LED attributes of a query or a part (DESIGN.md 3.4 "LEDs"). Every field is null when unknown.
     *
     * @param colour      the colour of the light: {@code red}, {@code green}, {@code blue}, {@code yellow},
     *                    {@code amber}, {@code orange}, {@code pink}, {@code purple}, {@code yellow green},
     *                    {@code white}, {@code warm white}, {@code neutral white}, {@code cool white}, {@code UV},
     *                    {@code IR}, {@code RGB}, {@code RGBW}, {@code bi-colour}, {@code tri-colour}
     * @param lens        {@code clear}, {@code diffused} or {@code tinted}
     * @param type        the kind of LED: {@code indicator} and {@code high power} (plain emitters),
     *                    {@code addressable} (with an integrated controller: WS2812, SK6812, APA102), or no discrete
     *                    emitter: {@code strip}, {@code laser}, {@code receiver}, {@code display}, {@code driver};
     *                    a request that names none asks for a plain emitter ({@link #requestedType()})
     * @param orientation {@code right angle} (side view), {@code reverse mount} or {@code vertical} (top view)
     */
    @Builder(toBuilder = true)
    public record Led(String colour, String lens, String type, String orientation) {

        /** The type of a request that names none: a plain emitter (indicator or high power). */
        public static final String PLAIN = "LED";
        public static final String INDICATOR = "indicator";
        public static final String HIGH_POWER = "high power";
        public static final String ADDRESSABLE = "addressable";

        /** True when no attribute is known. */
        public boolean isEmpty() {
            return colour == null && lens == null && type == null && orientation == null;
        }

        /** The type a request asks for: the one it names, else {@link #PLAIN}. */
        public String requestedType() {
            return type == null ? PLAIN : type;
        }

        /**
         * 1 when a part of LED type {@code actual} answers a request for {@code wanted}, -1 when it does not: a plain
         * request takes indicator and high power LEDs, never an addressable LED, a strip, a laser, a receiver, a
         * display or a driver; any other request takes its own type only.
         */
        public static double typeGrade(String wanted, String actual) {
            boolean plainWanted = PLAIN.equals(wanted) || INDICATOR.equals(wanted) || HIGH_POWER.equals(wanted);
            boolean plainActual = INDICATOR.equals(actual) || HIGH_POWER.equals(actual);
            return (plainWanted ? plainActual : wanted.equals(actual)) ? 1 : -1;
        }

        /**
         * 1 when a part of colour {@code actual} answers a request for {@code wanted}, -1 when it does not, null when
         * the part's white is not specific enough for a warm, neutral or cool white request. {@code white} takes every
         * white, {@code green} takes yellow green, {@code yellow} and {@code amber} take each other.
         */
        public static Double colourGrade(String wanted, String actual) {
            if (wanted.equals(actual)) {
                return 1.0;
            }
            if ("white".equals(wanted) && actual.endsWith(" white")) {
                return 1.0;
            }
            if (wanted.endsWith(" white") && "white".equals(actual)) {
                return null;
            }
            if ("green".equals(wanted) && "yellow green".equals(actual)
                    || ("yellow".equals(wanted) || "amber".equals(wanted))
                    && ("yellow".equals(actual) || "amber".equals(actual))) {
                return 1.0;
            }
            return -1.0;
        }
    }

    /**
     * Switch attributes of a query or a part (DESIGN.md 3.4 "Switches"). Every field is null when unknown.
     *
     * @param type               {@code tactile}, {@code pushbutton}, {@code toggle}, {@code slide}, {@code rocker},
     *                           {@code DIP}, {@code rotary}, {@code keylock}, {@code snap action}, {@code reed},
     *                           {@code membrane}, {@code detector}, {@code navigation}; a part may also be no
     *                           mechanical switch at all ({@code IC}: analog, load or Ethernet switches;
     *                           {@code sensor}: Hall, proximity, thermostat; {@code accessory}: caps); a request that
     *                           names none takes every mechanical switch ({@link #requestedType()})
     * @param contacts           the contact configuration ({@code SPDT}, {@code SPST-NO})
     * @param function           {@code momentary}, {@code latching}, or the positions as distributors write them
     *                           ({@code ON-OFF-ON}, {@code (ON)-OFF-(ON)}; brackets mark a momentary position)
     * @param termination        the termination class: {@code PCB} (SMD or THT pins), or a panel-mount class:
     *                           {@code solder lug}, {@code quick connect}, {@code wire leads}, {@code screw}, or
     *                           {@code panel} when only the panel mounting is known
     * @param size               the body size ({@code 6x6mm}, {@code 6x6x4.3mm})
     * @param holeDiameter       the panel cut-out (mounting hole diameter) in millimetres
     * @param positions          the number of switches of a DIP switch, or of positions of a rotary switch
     * @param illuminated        true for an illuminated switch (LED), false for one that says it is not
     * @param illuminationColour the colour of the illumination ({@code red})
     * @param orientation        {@code right angle} (side actuated) or {@code vertical} (top actuated)
     * @param voltageSupply      a request: {@link ParsedQuery#AC} or {@link ParsedQuery#DC} when its voltage says so
     */
    @Builder(toBuilder = true)
    public record Switch(String type, Contacts contacts, String function, String termination, BodySize size,
                         Double holeDiameter, Integer positions, Boolean illuminated, String illuminationColour,
                         String orientation, String voltageSupply) {

        public static final String TACTILE = "tactile";
        public static final String PUSHBUTTON = "pushbutton";
        public static final String MOMENTARY = "momentary";
        public static final String LATCHING = "latching";
        public static final String PCB = "PCB";
        public static final String PANEL = "panel";
        /** Largest difference in millimetres between two hole diameters that are the same. */
        public static final double HOLE_TOLERANCE_MM = 0.1;

        /** True when no attribute is known. */
        public boolean isEmpty() {
            return type == null && contacts == null && function == null && termination == null && size == null
                    && holeDiameter == null && positions == null && illuminated == null && illuminationColour == null
                    && orientation == null && voltageSupply == null;
        }

        /** The type of a request that names none: any mechanical switch. */
        public static final String ANY = "switch";
        /** Part types that are no mechanical switch: switch ICs, switching sensors, accessories (caps). */
        public static final java.util.Set<String> NOT_MECHANICAL = java.util.Set.of("IC", "sensor", "accessory");

        /** The type a request asks for: the one it names, else {@link #ANY}. */
        public String requestedType() {
            return type == null ? ANY : type;
        }

        /**
         * 1 when a part of switch type {@code actual} answers a request for {@code wanted}, else -1: a request that names
         * no type takes every mechanical switch, a pushbutton request takes tactile switches too (a tactile request
         * takes tactile switches only); a switch IC, a Hall or proximity sensor or a cap answers no switch request.
         */
        public static double typeGrade(String wanted, String actual) {
            if (NOT_MECHANICAL.contains(actual)) {
                return wanted.equals(actual) ? 1 : -1;
            }
            return ANY.equals(wanted) || wanted.equals(actual) || PUSHBUTTON.equals(wanted) && TACTILE.equals(actual)
                    ? 1 : -1;
        }

        /**
         * The function of a request against a part's: the positions when both state them ({@code ON-OFF-ON}), else
         * momentary or latching; null when either side does not say.
         */
        public static Double functionGrade(String wanted, String actual) {
            boolean wantedPattern = wanted.contains("ON");
            boolean actualPattern = actual.contains("ON");
            if (wantedPattern && actualPattern) {
                return wanted.equals(actual) ? 1.0 : -1.0;
            }
            String w = action(wanted);
            String a = action(actual);
            return w == null || a == null ? null : w.equals(a) ? 1.0 : -1.0;
        }

        /**
         * {@link #MOMENTARY} or {@link #LATCHING} for a function: a pattern with brackets and one rest position at most
         * is momentary ({@code OFF-(ON)}, {@code ON-(OFF)}, {@code (ON)-OFF-(ON)}), one without brackets latching
         * ({@code ON-OFF}); a mixed one ({@code ON-OFF-(ON)}) neither.
         */
        public static String action(String function) {
            if (!function.contains("ON")) {
                return function;
            }
            String[] positions = function.split("-");
            int bracketed = 0;
            for (String p : positions) {
                if (p.startsWith("(")) {
                    bracketed++;
                }
            }
            if (bracketed == 0) {
                return LATCHING;
            }
            return positions.length - bracketed <= 1 ? MOMENTARY : null;
        }

        /**
         * The termination class of a request against a part's: a PCB request (SMD or THT) never takes a panel-mount
         * part and the reverse; a request for one panel class ({@code solder lug}) takes that class only. Null when
         * the part is panel mount without a known class.
         */
        public static Double terminationGrade(String wanted, String actual) {
            boolean wantedPcb = PCB.equals(wanted);
            boolean actualPcb = PCB.equals(actual);
            if (wantedPcb || actualPcb) {
                return wantedPcb == actualPcb ? 1.0 : -1.0;
            }
            if (PANEL.equals(wanted) || wanted.equals(actual)) {
                return 1.0;
            }
            return PANEL.equals(actual) ? null : -1.0;
        }
    }

    /**
     * A contact configuration: poles and throws ({@code SPDT}: 1 and 2), with the normal state of a single throw when
     * stated ({@code NO}, {@code NC}).
     */
    public record Contacts(int poles, int throwsCount, String form) {

        /** {@code SPST-NO}, {@code SPDT}, {@code DPDT}, {@code 3PDT}, {@code SP3T}. */
        public String display() {
            String p = poles == 1 ? "S" : poles == 2 ? "D" : String.valueOf(poles);
            String t = throwsCount == 1 ? "S" : throwsCount == 2 ? "D" : String.valueOf(throwsCount);
            return p + "P" + t + "T" + (form == null ? "" : "-" + form);
        }

        /**
         * 1 for the same poles and throws (and the same NO or NC when both state one), -1 for different ones; null
         * when the request states NO or NC and the part does not.
         */
        public Double grade(Contacts actual) {
            if (poles != actual.poles || throwsCount != actual.throwsCount) {
                return -1.0;
            }
            if (form == null) {
                return 1.0;
            }
            return actual.form == null ? null : form.equals(actual.form) ? 1.0 : -1.0;
        }
    }

    /**
     * The body size of a switch in millimetres: width and length, and the height when stated ({@code 6x6x4.3mm}).
     */
    public record BodySize(double width, double length, Double height) {

        /** Largest difference in millimetres between two widths, lengths or heights that are the same. */
        public static final double TOLERANCE_MM = 0.5;

        /** True when {@code actual} is this size: width and length within 0.5 mm in either order, the height too. */
        public boolean matches(BodySize actual) {
            boolean straight = same(width, actual.width) && same(length, actual.length);
            boolean crossed = same(width, actual.length) && same(length, actual.width);
            return (straight || crossed) && (height == null || actual.height == null || same(height, actual.height));
        }

        private static boolean same(double a, double b) {
            return Math.abs(a - b) <= TOLERANCE_MM + 1e-9;
        }

        /** {@code 6x6mm}, {@code 6x6x4.3mm}, {@code 12x12x7.3mm}. */
        public String display() {
            return number(width) + "x" + number(length) + (height == null ? "" : "x" + number(height)) + "mm";
        }

        private static String number(double v) {
            return java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
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
