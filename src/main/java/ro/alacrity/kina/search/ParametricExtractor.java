package ro.alacrity.kina.search;

import org.springframework.stereotype.Component;
import ro.alacrity.kina.domain.ComponentFamily;
import ro.alacrity.kina.domain.ComponentFamily.Trait;
import ro.alacrity.kina.domain.ConstraintKind;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.PartFeatures;
import ro.alacrity.kina.domain.Part;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Derives comparable parametric attributes from a {@link Part} (DESIGN.md section 3.4) using the same recognisers as
 * {@link QueryParser}. Distributor-provided values (package field, Mouser ProductAttributes, TME parameters, LCSC
 * attributes) take precedence over description parsing. Stateless and thread-safe.
 *
 * <p>Comparable keys: {@value #CAPACITANCE}, {@value #RESISTANCE}, {@value #INDUCTANCE}, {@value #IMPEDANCE},
 * {@value #FREQUENCY}, {@value #VOLTAGE}, {@value #CURRENT} ({@value #RATED_CURRENT} for inductors and ferrite beads),
 * {@value #SATURATION_CURRENT}, {@value #DCR}, {@value #POWER}, {@value #MAX_TEMPERATURE}, {@value #LIFETIME},
 * {@value #TOLERANCE}, {@value #DIELECTRIC},
 * {@value #PACKAGE}, {@value #MOUNTING}, {@value #FAMILY}, {@value #TECHNOLOGY}; for connectors also {@value #CONNECTOR_TYPE},
 * {@value #GENDER}, {@value #POSITIONS}, {@value #ROWS}, {@value #PITCH}, {@value #ORIENTATION} and {@value #SERIES}.
 * Values are compact human-readable strings ("10uF", "25V", "10%", "X7R", "0805", "SMD", "capacitor",
 * "female header", "female", "6", "1", "2.54mm", "right angle").
 */
@Component
public class ParametricExtractor {

    public static final String CAPACITANCE = "Capacitance";
    public static final String RESISTANCE = "Resistance";
    public static final String INDUCTANCE = "Inductance";
    public static final String FREQUENCY = "Frequency";
    public static final String VOLTAGE = "Voltage";
    public static final String CURRENT = "Current";
    public static final String POWER = "Power";
    /** Impedance of a ferrite bead with its test frequency ("120ohm @100MHz"). */
    public static final String IMPEDANCE = "Impedance";
    /** Rated current of an inductor or ferrite bead (I_rated; "Current" for other families). */
    public static final String RATED_CURRENT = "RatedCurrent";
    /** Saturation current of an inductor (I_sat). */
    public static final String SATURATION_CURRENT = "SaturationCurrent";
    /** Maximum DC resistance of an inductor or ferrite bead ("28mohm"). */
    public static final String DCR = "DCR";
    /** Maximum operating temperature ("105°C"). */
    public static final String MAX_TEMPERATURE = "MaxTemperature";
    /** Rated lifetime (endurance) in hours, with the test temperature when stated ("2000h @105°C"). */
    public static final String LIFETIME = "Lifetime";
    public static final String TOLERANCE = "Tolerance";
    public static final String DIELECTRIC = "Dielectric";
    public static final String PACKAGE = "Package";
    public static final String MOUNTING = "Mounting";
    public static final String FAMILY = "Family";
    /** Construction technology of a passive ("thin film", "tantalum", "multilayer"...; {@link TechnologyVocabulary}). */
    public static final String TECHNOLOGY = "Technology";
    public static final String CONNECTOR_TYPE = "ConnectorType";
    public static final String GENDER = "Gender";
    public static final String POSITIONS = "Positions";
    public static final String ROWS = "Rows";
    public static final String PITCH = "Pitch";
    public static final String ORIENTATION = "Orientation";
    public static final String SERIES = "Series";
    // USB connectors (DESIGN.md 3.4)
    public static final String USB_TYPE = "UsbType";
    public static final String USB_STANDARD = "UsbStandard";
    public static final String USB_SPEED = "UsbSpeedGbps";
    public static final String PIN_CONFIGURATION = "PinConfiguration";
    public static final String SHIELD_PINS = "ShieldPinsCounted";
    public static final String MOUNTING_STYLE = "MountingStyle";
    public static final String WATERPROOF = "Waterproof";
    public static final String FEATURES = "Features";
    // descriptive details (PassiveDetails, DESIGN.md 3.4); not scored except Elements (strict constraint "elements")
    /** Number of elements of an array or network ("4", or "array" when not stated). */
    public static final String ELEMENTS = "Elements";
    /** A capacitor's ripple current (never reported as Current). */
    public static final String RIPPLE_CURRENT = "RippleCurrent";
    /** A capacitor's ESR with its test frequency when stated. */
    public static final String ESR = "ESR";
    /** Body dimensions ("D6.3 x 5.8mm" for a can, "8.8 x 8.4 x 3.8mm"). */
    public static final String DIMENSIONS = "Dimensions";
    /** AEC-Q200 and similar. */
    public static final String QUALIFICATION = "Qualification";
    /** The vendor case code of a can capacitor whose Package shows the dimensions (Panasonic "D"). */
    public static final String CASE = "Case";
    /** Transistor polarity ("N-channel", "P-channel", "NPN", "PNP", "complementary"). */
    public static final String POLARITY = "Polarity";
    /** "standard" (rectifier / switching diode of the generic diode family), "fixed" or "adjustable" (regulators). */
    public static final String SUBTYPE = "Subtype";
    /** Form factor class of a passive ({@link FormFactor}: chip, through_hole, chassis, power_package, power_smd). */
    public static final String FORM_FACTOR = "FormFactor";
    /** Operating temperature range as printed by the distributor, normalised ("-55...155°C"). */
    public static final String OPERATING_TEMPERATURE = "OperatingTemperature";

    /** Comparable key per {@link ParsedQuery} value kind, in output order. */
    private static final Map<String, String> KIND_KEYS = orderedKindKeys();

    private static Map<String, String> orderedKindKeys() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(ParsedQuery.CAPACITANCE, CAPACITANCE);
        m.put(ParsedQuery.RESISTANCE, RESISTANCE);
        m.put(ParsedQuery.INDUCTANCE, INDUCTANCE);
        m.put(ParsedQuery.IMPEDANCE, IMPEDANCE);
        m.put(ParsedQuery.FREQUENCY, FREQUENCY);
        m.put(ParsedQuery.VOLTAGE, VOLTAGE);
        m.put(ParsedQuery.CURRENT, CURRENT);
        m.put(ParsedQuery.SATURATION_CURRENT, SATURATION_CURRENT);
        m.put(ParsedQuery.DCR, DCR);
        m.put(ParsedQuery.POWER, POWER);
        m.put(ParsedQuery.TEMPERATURE, MAX_TEMPERATURE);
        m.put(ParsedQuery.LIFETIME, LIFETIME);
        m.put(ParsedQuery.TOLERANCE, TOLERANCE);
        return m;
    }

    /**
     * Every canonical key {@link #extract} can produce: the {@code compact} response detail returns only these and
     * leaves the raw distributor attributes (TME "Operating voltage", "Case - inch"...) to {@code full}.
     */
    public static final Set<String> CANONICAL_KEYS = Set.of(CAPACITANCE, RESISTANCE, INDUCTANCE, IMPEDANCE, FREQUENCY,
            VOLTAGE, CURRENT, RATED_CURRENT, SATURATION_CURRENT, DCR, POWER, MAX_TEMPERATURE, LIFETIME, TOLERANCE,
            "Dielectric", "Package", "Mounting", "Family", "Technology", "ConnectorType", "Gender", "Positions", "Rows",
            "Pitch", "Orientation", "Series", "UsbType", "UsbStandard", "UsbSpeedGbps", "PinConfiguration",
            "ShieldPinsCounted", "MountingStyle", "Waterproof", "Features", ELEMENTS, RIPPLE_CURRENT, ESR, DIMENSIONS,
            QUALIFICATION, CASE, POLARITY, SUBTYPE, FORM_FACTOR, OPERATING_TEMPERATURE);

    /**
     * Canonical keys no distributor sends (verified against the recorded TME and Mouser responses; LCSC parts have no
     * attributes): in a cached payload they can only be derived values. Migration V11 removed them from the rows cached
     * before the payload left the derived attributes out (a {@code Family} of {@code mosfet} cached before the gate
     * driver family existed); keys a distributor may send as well ({@code Capacitance}, {@code Mounting}...) stayed.
     * {@code CachedAttributesTest} checks that the migration lists exactly these.
     */
    static final Set<String> DERIVED_ONLY_KEYS = Set.of(FAMILY, SUBTYPE, FORM_FACTOR, ELEMENTS, RATED_CURRENT,
            SATURATION_CURRENT, MAX_TEMPERATURE, RIPPLE_CURRENT, OPERATING_TEMPERATURE, CONNECTOR_TYPE, USB_TYPE,
            USB_STANDARD, USB_SPEED, PIN_CONFIGURATION, SHIELD_PINS, MOUNTING_STYLE);

    // ---------------------------------------------------------------- distributor attribute names (lower-case)

    private static final List<String> CAPACITANCE_NAMES = List.of("capacitance", "capacitance value", "nominal capacitance",
            "load capacitance", "load capacitance (cl)");
    /** Resistance; for a MOSFET its on-resistance (Mouser {@code Rds On - Drain-Source Resistance}). */
    private static final List<String> RESISTANCE_NAMES = List.of("resistance", "resistance value", "nominal resistance",
            "rds on - drain-source resistance", "drain-source on resistance", "on-state resistance", "rds(on)");
    private static final List<String> INDUCTANCE_NAMES = List.of("inductance", "nominal inductance");
    private static final List<String> FREQUENCY_NAMES = List.of("frequency", "nominal frequency", "oscillation frequency");
    /** Preferred voltage ratings (Mouser "Voltage Rating DC", TME "Operating voltage", LCSC "Voltage Rated"). */
    private static final List<String> VOLTAGE_NAMES = List.of("voltage rating dc", "voltage rating - dc", "voltage rating",
            "voltage rated", "rated voltage", "voltage - rated", "operating voltage", "dc voltage rating", "voltage",
            "output voltage", "voltage - output", "voltage - output (min/fixed)", "vr - reverse voltage",
            "reverse voltage (vr)", "vds - drain-source breakdown voltage", "drain source voltage (vdss)",
            "drain to source voltage (vdss)", "vz - zener voltage", "voltage - zener (nom) (vz)",
            "vrwm - reverse standoff voltage", "reverse stand-off voltage (vrwm)", "voltage - reverse standoff (typ)");
    /** Below this a description voltage is never a transistor's or diode's rating (a threshold or forward voltage). */
    static final double MIN_PLAUSIBLE_RATING_VOLTS = 3.0;

    /** A regulator's output voltage (TME {@code Output voltage}, Mouser {@code Output Voltage}) comes first. */
    private static final List<String> OUTPUT_VOLTAGE_NAMES = List.of("output voltage", "voltage - output",
            "voltage - output (min/fixed)", "output voltage (fixed)", "fixed output voltage");
    /** A Zener diode's Zener voltage (Mouser {@code Vz - Zener Voltage}, TME {@code Zener voltage}) comes first. */
    private static final List<String> ZENER_VOLTAGE_NAMES = List.of("vz - zener voltage", "zener voltage",
            "voltage - zener (nom) (vz)", "zener voltage (vz)", "voltage - zener");
    private static final Pattern VOLTAGE_EXCLUDED = Pattern.compile(
            "forward|clamp|breakdown|input|supply|isolation|threshold|gate|ripple|dropout|temperature|coefficient|offset");
    private static final List<String> CURRENT_NAMES = List.of("current rating", "rated current", "current", "current - output",
            "output current", "id - continuous drain current", "continuous drain current (id)", "if - forward current",
            "io - average rectified current", "current - average rectified (io)", "average rectified current (io)",
            "ic - continuous collector current", "collector current (ic)", "current rating (amps)");
    private static final Pattern CURRENT_EXCLUDED = Pattern.compile(
            "leakage|reverse current|surge|quiescent|supply|peak|bias|offset|standby|pulse|trip|saturation|ripple");
    /** Inductors and ferrite beads: the rated current (TME "Operating current", Mouser "Maximum DC Current"). */
    private static final List<String> RATED_CURRENT_NAMES = List.of("rated current", "current rating",
            "operating current", "maximum dc current", "max. dc current", "dc current", "current - max",
            "current rating (amps)", "irms", "i rms", "rated current (irms)", "current");
    private static final List<String> SATURATION_NAMES = List.of("saturation current", "isat", "current - saturation",
            "current - saturation (isat)", "saturation current (isat)", "isat (max)", "saturation current max.");
    /** Inductors and ferrite beads: DC resistance (TME "Resistance", Mouser "Maximum DC Resistance"). */
    private static final List<String> DCR_NAMES = List.of("dc resistance", "dc resistance (dcr)", "dcr",
            "maximum dc resistance", "max. dc resistance", "dc resistance max", "dc resistance (dcr) (max)",
            "resistance - dc", "resistance", "dc resistance (max)");
    private static final List<String> TEST_FREQUENCY_NAMES = List.of("test frequency", "impedance test frequency",
            "frequency", "measuring frequency");
    /** Lifetime (TME "Service life" = 2000h, Mouser "Lifetime" / "Load Life", DigiKey "Lifetime @ Temp."). */
    private static final List<String> LIFETIME_NAMES = List.of("service life", "lifetime", "life time", "load life",
            "endurance", "useful life", "lifetime @ temp.", "life", "operating life");
    /** Operating temperature (TME "-55...105°C", Mouser "Maximum Operating Temperature" = "+ 105 C"). */
    private static final List<String> TEMPERATURE_NAMES = List.of("maximum operating temperature",
            "max. operating temperature", "operating temperature", "operating temperature range", "temperature range");
    /** Operating temperature ranges (TME {@code Operating temperature} = {@code -55...155°C}). */
    private static final List<String> TEMPERATURE_RANGE_NAMES = List.of("operating temperature",
            "operating temperature range", "temperature range");
    /**
     * A printed temperature range with both ends: TME {@code -55...155°C}, {@code -55÷125°C}, LCSC {@code -55℃~+155℃}
     * (NFKC: {@code °C}), {@code -40°C to +85°C}, {@code -55 ~ +155 C}.
     */
    private static final Pattern TEMPERATURE_RANGE = Pattern.compile(
            "(?<![\\d.])([-−]\\s?\\d{1,3}(?:\\.\\d+)?)\\s?(?:°C?|℃)?\\s?(?:~|\\.{2,3}|…|÷|to)\\s?(\\+?\\d{1,3}(?:\\.\\d+)?)"
                    + "\\s?(?:°C?|℃|C(?![\\p{L}\\d]))");
    private static final Pattern HOURS = Pattern.compile(
            "(?i)(\\d+(?:[.,]\\d+)?)\\s*(?:h|hrs?|hours?)(?![a-z])(?:\\s*@\\s*\\+?(\\d{2,3})\\s*°?\\s*C)?");
    private static final Pattern SIGNED_NUMBER = Pattern.compile("[-+−]?\\s?\\d+(?:\\.\\d+)?");
    private static final Pattern KEY_FREQUENCY = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*([kmg]?)hz");
    private static final List<String> POWER_NAMES = List.of("power rating", "power", "power(watts)", "power (watts)",
            "pd - power dissipation", "power dissipation (pd)", "power dissipation");
    private static final List<String> TOLERANCE_NAMES = List.of("tolerance", "resistance tolerance", "capacitance tolerance",
            "inductance tolerance");
    private static final List<String> DIELECTRIC_NAMES = List.of("dielectric", "temperature coefficient",
            "temperature characteristic", "temperature characteristics", "dielectric material", "tempco");
    private static final List<String> PACKAGE_INCH_NAMES = List.of("case code - in", "case - inch", "case code (inch)",
            "package (inch)", "imperial size", "case code - inch");
    private static final List<String> PACKAGE_MM_NAMES = List.of("case code - mm", "case - mm", "case code (mm)",
            "metric size", "package (mm)");
    private static final List<String> PACKAGE_NAMES = List.of("package / case", "package/case", "package", "case",
            "supplier device package", "package type", "case / package", "case/package", "housing");
    private static final List<String> MOUNTING_NAMES = List.of("mounting", "mounting style", "mounting type",
            "mounting method", "termination style", "montage", "electrical mounting");
    /**
     * Technology parameters: TME {@code Type of resistor} / {@code Type of capacitor} / {@code Type of inductor}
     * (verified live 2026-10-05: thin film, thick film, metal film, carbon film, metal oxide, wire-wound, metal strip;
     * ceramic, tantalum, tantalum-polymer, polymer, electrolytic, polypropylene, polyester, supercapacitor; wire,
     * multilayer, thin film), {@code Kind of capacitor} (MLCC), {@code Kind of resistor} (current shunt, sensing);
     * generic names other sources use.
     */
    private static final List<String> TECHNOLOGY_NAMES = List.of("type of resistor", "type of capacitor",
            "type of inductor", "kind of capacitor", "kind of resistor", "technology", "composition", "construction",
            "resistor type", "capacitor type", "inductor type");

    // connector attributes (TME parameters verified live 2026-10-05: "Type of connector" = pin strips, "Connector" =
    // socket, "Kind of connector" = female, "Number of pins" = 6, "Spatial orientation" = angled 90°,
    // "Contacts pitch" = 2.54mm, "Connector pinout layout" = 1x6, "Electrical mounting" = THT, "Manufacturer series" = XH;
    // Mouser ProductAttributes names as documented by Mouser)
    private static final List<String> CONNECTOR_TYPE_NAMES = List.of("type of connector", "connector type", "connector",
            "product", "product type", "type");
    private static final List<String> GENDER_NAMES = List.of("kind of connector", "gender", "contact gender",
            "connector gender");
    private static final List<String> POSITIONS_NAMES = List.of("number of pins", "number of positions", "positions",
            "no. of positions", "number of contacts", "number of ways", "number of circuits", "pins");
    private static final List<String> ROWS_NAMES = List.of("number of rows", "rows", "no. of rows");
    private static final List<String> LAYOUT_NAMES = List.of("connector pinout layout", "pinout layout", "layout");
    private static final List<String> PITCH_NAMES = List.of("contacts pitch", "pitch", "contact pitch", "pitch - mating",
            "raster");
    private static final List<String> ORIENTATION_NAMES = List.of("spatial orientation", "mounting angle", "orientation",
            "angle", "termination orientation");
    private static final List<String> SERIES_NAMES = List.of("manufacturer series", "series");
    /**
     * TME parameters that carry USB details (verified live 2026-10-05: {@code Version} = USB 2.0 / USB 3.1 Gen 2 /
     * USB 4.0, {@code Data transfer rate} = 5Gbps, {@code Connector variant} = middle board mount / Gen.2x2 / sealed,
     * {@code Connectors application} = only for charging (6p), {@code IP rating} = IP67, {@code Electrical mounting}
     * = hybrid SMT/THT). Mouser's keyword search returns no such ProductAttributes for USB connectors (only
     * Packaging and Standard Pack Qty) and LCSC has no parameter columns, so both rely on the description.
     */
    private static final List<String> USB_ATTRIBUTE_NAMES = List.of("type of connector", "version", "usb version",
            "usb standard", "data transfer rate", "data rate", "connector variant", "connectors application",
            "ip rating", "ingress protection", "electrical mounting", "mounting style");
    private static final Pattern DIGITS = Pattern.compile("\\d{1,3}");
    private static final Pattern CONNECTOR_ATTRIBUTE = Pattern.compile(
            "type of connector|connector type|number of positions|contact gender|kind of connector");

    private static final Set<String> SMD_PACKAGE_PREFIXES = Set.of("SOT", "SOD", "SC-", "SOIC", "SOP", "SSOP",
            "TSSOP", "MSOP", "QFN", "DFN", "LQFP", "TQFP", "QFP", "BGA", "LGA", "WLCSP", "SMA", "SMB", "SMC", "DO-214", "TO-252",
            "TO-263", "DPAK", "D2PAK", "SON", "WSON", "VSON", "TDFN", "UDFN", "XDFN", "USON", "LFCSP", "VQFN", "WQFN",
            "UQFN", "TQFN", "MELF", "MINIMELF", "PLCC");
    private static final Set<String> THT_PACKAGE_PREFIXES = Set.of("DIP", "PDIP", "TO-220", "TO-92", "TO-247", "HC-49",
            "DO-41", "DO-35", "DO-15", "DO-201", "SIP", "ZIP");

    private static final Pattern CATEGORY_SEPARATOR = Pattern.compile("\\s*[/>\\\\]\\s*");

    /**
     * Parsed, comparable features of a part. Values are in SI base units (tolerance: percent).
     *
     * @param elements number of elements of an array or network ({@link ParsedQuery#ANY_ELEMENTS} when not stated),
     *                 null for a single element
     * @param details  descriptive canonical attributes in output order ({@value #RIPPLE_CURRENT}, {@value #ESR},
     *                 {@value #IMPEDANCE} of a capacitor, {@value #DIMENSIONS}, {@value #CASE},
     *                 {@value #QUALIFICATION}, {@value #FEATURES}); not scored
     * @param polarity transistor polarity ({@link ComponentTypes#polarity}), null when not stated
     * @param subtype  "standard" for a rectifier or switching diode of the generic diode family, "fixed" or
     *                 "adjustable" for a regulator ({@link ComponentTypes#subtype}), else null
     * @param voltages the voltages a regulator or Zener part states as its specification: the output or Zener voltage
     *                 attribute when there is one, else every single voltage of the description (JLCPCB lists the
     *                 values of a part without labels, sorted as text: {@code 1.1V@(800mA) 15V 1A 3.3V}); ranges
     *                 ({@code 1.2V~37V}) and conditioned values ({@code 100nA@0.8V}) are left out. Empty for other
     *                 families
     * @param formFactor the form factor class of a passive ({@link FormFactor#ofPart}), null when nothing says
     */
    record Features(String family, Map<String, Recognizers.Value> values, String dielectric, String packageName,
                    String mounting, String text, ParsedQuery.Connector connector, String technology,
                    Integer elements, Map<String, String> details, String polarity, String subtype,
                    List<Double> voltages, String formFactor) implements PartFeatures {

        Features {
            details = details == null ? Map.of() : details;
            voltages = voltages == null ? List.of() : List.copyOf(voltages);
        }

        @Override
        public Recognizers.Value measure(String kind) {
            return values.get(kind);
        }
    }

    /**
     * Comparable attributes ({@value #CAPACITANCE}, {@value #VOLTAGE}, {@value #PACKAGE}...) known for the part,
     * insertion-ordered; keys are omitted when unknown.
     */
    public Map<String, String> extract(Part part) {
        Features f = features(part);
        Map<String, String> out = new LinkedHashMap<>();
        boolean inductive = Recognizers.inductive(f.family());
        KIND_KEYS.forEach((kind, key) -> {
            Recognizers.Value v = f.values().get(kind);
            if (v != null) {
                out.put(inductive && ParsedQuery.CURRENT.equals(kind) ? RATED_CURRENT : key, v.display());
            }
        });
        putIfNotNull(out, DIELECTRIC, f.dielectric());
        putIfNotNull(out, PACKAGE, f.packageName());
        putIfNotNull(out, MOUNTING, f.mounting());
        putIfNotNull(out, FAMILY, f.family());
        putIfNotNull(out, TECHNOLOGY, f.technology());
        putIfNotNull(out, POLARITY, f.polarity());
        putIfNotNull(out, SUBTYPE, f.subtype());
        putIfNotNull(out, ELEMENTS, PassiveDetails.elementsDisplay(f.elements()));
        if (FormFactor.ofPackage(f.packageName()) == null) {
            putIfNotNull(out, FORM_FACTOR, f.formFactor());   // a package (0805, SOT-227) already says it
        }
        f.details().forEach(out::putIfAbsent);
        ParsedQuery.Connector c = f.connector();
        if (c != null) {
            putIfNotNull(out, CONNECTOR_TYPE, c.type());
            putIfNotNull(out, SERIES, c.series());
            putIfNotNull(out, GENDER, c.gender());
            putIfNotNull(out, POSITIONS, c.positions() == null ? null : c.positions().toString());
            putIfNotNull(out, ROWS, c.rows() == null ? null : c.rows().toString());
            putIfNotNull(out, PITCH, c.pitchDisplay());
            putIfNotNull(out, ORIENTATION, c.orientation());
            if (c.isUsb()) {
                putIfNotNull(out, USB_TYPE, c.usbType());
                putIfNotNull(out, USB_STANDARD, c.usbStandard());
                putIfNotNull(out, USB_SPEED, c.usbSpeedGbps() == null ? null
                        : UsbVocabulary.speedDisplay(c.usbSpeedGbps()));
                putIfNotNull(out, PIN_CONFIGURATION, c.pinConfiguration() == null ? null
                        : c.pinConfiguration().toString());
                putIfNotNull(out, SHIELD_PINS, c.shieldPinsCounted() == null ? null : c.shieldPinsCounted().toString());
                putIfNotNull(out, MOUNTING_STYLE, c.mountingStyle() != null ? c.mountingStyle() : f.mounting());
                if (c.hasFeature(UsbVocabulary.WATERPROOF)) {
                    out.put(WATERPROOF, c.features().stream().filter(x -> x.startsWith("IP")).findFirst().orElse("yes"));
                }
                if (!c.features().isEmpty()) {
                    out.put(FEATURES, String.join(", ", c.features()));
                }
            }
        }
        return out;
    }

    /**
     * Copy of the part whose {@code attributes} additionally contain the comparable keys of {@link #extract(Part)},
     * derived by this extractor (DESIGN.md 3.2 "Cache model"). Distributor attributes are kept unchanged (also when
     * they use the same key name); the keys added are listed in {@link Part#derivedAttributes()}, so the cache stores
     * the part without them ({@link Part#asStored()}) and every read derives them again. The attributes an earlier
     * {@code enrich} listed are derived again, never kept, so enriching an enriched part gives the same part.
     */
    public Part enrich(Part part) {
        Map<String, String> own = new LinkedHashMap<>(part.attributes());
        own.keySet().removeAll(part.derivedAttributes());
        Part base = part.toBuilder().attributes(own).derivedAttributes(Set.of()).build();
        Map<String, String> attributes = new LinkedHashMap<>(own);
        Set<String> derived = new LinkedHashSet<>();
        extract(base).forEach((key, value) -> {
            String raw = attributes.get(key);
            if (raw == null) {
                attributes.put(key, value);
                derived.add(key);
            } else if (!raw.equals(value) && sameQuantity(key, raw, value)) {
                attributes.put(key, value);   // TME "Power: 0.25kW" is shown in the canonical form "250W"
            }
        });
        return base.toBuilder().attributes(attributes).derivedAttributes(derived).build();
    }

    /**
     * True when a distributor's {@code Power} attribute states the canonical power in another form (TME
     * {@code 0.25kW}): it is then shown as the canonical {@code 250W}. Every other distributor attribute is kept as
     * given.
     */
    private static boolean sameQuantity(String key, String raw, String canonical) {
        String kind = KIND_KEYS.entrySet().stream().filter(e -> e.getValue().equals(key)).map(Map.Entry::getKey)
                .findFirst().orElse(null);
        if (!ParsedQuery.POWER.equals(kind)) {
            return false;
        }
        Recognizers.Value a = Recognizers.firstValue(raw, kind, null);
        Recognizers.Value b = Recognizers.firstValue(canonical, kind, null);
        return a != null && b != null && a.condition() == null
                && ConstraintKind.sameValue(b.value(), a.value(), 1e-9);
    }

    /** Typed features used by {@link DeterministicRanker}. */
    Features features(Part part) {
        Recognizers.Analysis category = categoryAnalysis(part.category());
        // the category's family decides how a description without a family word is read (LCSC ferrite beads)
        Recognizers.Analysis description = Recognizers.analyze(part.description(),
                category.familyExplicit() ? category.family() : null);

        Map<String, String> attrs = new LinkedHashMap<>();
        part.attributes().forEach((k, v) -> {
            if (k != null && v != null && !v.isBlank()) {
                attrs.putIfAbsent(k.trim().toLowerCase(Locale.ROOT), v);
            }
        });

        String explicitFamily = category.familyExplicit() ? category.family()
                : description.familyExplicit() ? description.family() : null;
        if (category.familyExplicit() && description.familyExplicit() && category.family() != null
                && category.family().equals(ComponentFamily.parentOf(description.family()))) {
            // the description names a specialisation of the category's family (TME "SMD N channel transistors" with
            // "Transistor: N-MOSFET"): the more specific family wins
            explicitFamily = description.family();
        }
        if (category.familyExplicit() && description.familyExplicit()
                && Recognizers.overridesCategory(description.family(), category.family())) {
            // a half-bridge with an integrated driver under "GaN FETs" is a gate driver
            explicitFamily = description.family();
        }
        if (category.familyExplicit() && description.familyExplicit()
                && ComponentFamily.has(category.family(), Trait.FREQUENCY_VALUED)
                && ComponentFamily.has(description.family(), Trait.FREQUENCY_VALUED)
                && Recognizers.familiesIn(lastCategorySegment(part.category())).containsAll(FREQUENCY_FAMILIES)) {
            // a category naming both kinds (TME "Resonators and Generators"): the description decides
            // ("Crystal; 16MHz" is a crystal, "Generator: quartz; 16MHz" an oscillator)
            explicitFamily = description.family();
        }
        final String valueFamily = explicitFamily;

        Map<String, Recognizers.Value> values = new LinkedHashMap<>();
        boolean inductive = Recognizers.inductive(valueFamily);
        attributeValue(attrs, CAPACITANCE_NAMES, ParsedQuery.CAPACITANCE, valueFamily, values);
        if (inductive) {
            inductiveAttributes(attrs, valueFamily, values);
        } else {
            attributeValue(attrs, RESISTANCE_NAMES, ParsedQuery.RESISTANCE, valueFamily, values);
        }
        attributeValue(attrs, INDUCTANCE_NAMES, ParsedQuery.INDUCTANCE, valueFamily, values);
        attributeValue(attrs, FREQUENCY_NAMES, ParsedQuery.FREQUENCY, valueFamily, values);
        if ("regulator".equals(valueFamily)) {
            attributeValue(attrs, OUTPUT_VOLTAGE_NAMES, ParsedQuery.VOLTAGE, valueFamily, values);
        } else if ("zener".equals(valueFamily)) {
            attributeValue(attrs, ZENER_VOLTAGE_NAMES, ParsedQuery.VOLTAGE, valueFamily, values);
        }
        attributeValue(attrs, VOLTAGE_NAMES, ParsedQuery.VOLTAGE, valueFamily, values);
        if (!values.containsKey(ParsedQuery.VOLTAGE)) {
            attrs.forEach((k, v) -> {
                if (k.contains("voltage") && !VOLTAGE_EXCLUDED.matcher(k).find()) {
                    putFirst(values, ParsedQuery.VOLTAGE, v, valueFamily);
                }
            });
        }
        attributeValue(attrs, CURRENT_NAMES, ParsedQuery.CURRENT, valueFamily, values);
        if (!values.containsKey(ParsedQuery.CURRENT)) {
            attrs.forEach((k, v) -> {
                if (k.contains("current") && !CURRENT_EXCLUDED.matcher(k).find()) {
                    putFirst(values, ParsedQuery.CURRENT, v, valueFamily);
                }
            });
        }
        attributeValue(attrs, POWER_NAMES, ParsedQuery.POWER, valueFamily, values);
        attributeValue(attrs, TOLERANCE_NAMES, ParsedQuery.TOLERANCE, valueFamily, values);
        lifetimeAttribute(attrs, values);
        temperatureAttribute(attrs, values);
        Set<String> fromAttributes = Set.copyOf(values.keySet());
        description.values().forEach(values::putIfAbsent);
        if (!values.containsKey(ParsedQuery.POWER) && "resistor".equals(explicitFamily != null ? explicitFamily
                : values.containsKey(ParsedQuery.RESISTANCE) ? "resistor" : null)) {
            // Arcol HS50, TE THS25, Vishay RH-50...: the series names the wattage the text does not state
            Double watts = ResistorSeries.power(part.manufacturer(), part.manufacturerPartNumber());
            if (watts != null) {
                values.put(ParsedQuery.POWER, Recognizers.of(ParsedQuery.POWER, watts));
            }
        }

        String family = explicitFamily;
        if (family == null) {
            family = values.containsKey(ParsedQuery.CAPACITANCE) ? "capacitor"
                    : values.containsKey(ParsedQuery.RESISTANCE) ? "resistor"
                    : values.containsKey(ParsedQuery.INDUCTANCE) ? "inductor"
                    : description.family();
        }
        if (Recognizers.inductive(family)) {
            values.remove(ParsedQuery.RESISTANCE);   // an inductor's or ferrite bead's ohm value is DCR or impedance
        }
        if (!fromAttributes.contains(ParsedQuery.VOLTAGE) && ComponentFamily.has(family, Trait.LARGEST_VOLTAGE)) {
            ratingFromLargestVoltage(part.description(), family, values);
        }
        Map<String, String> details = new LinkedHashMap<>();
        putIfNotNull(details, OPERATING_TEMPERATURE, operatingTemperature(attrs, part.description()));
        if ("capacitor".equals(family)) {
            // a capacitor's current is its ripple current (TME "Operating current" 0.24A on EEEFK1C101P), never Current
            values.remove(ParsedQuery.CURRENT);
            putIfNotNull(details, RIPPLE_CURRENT, PassiveDetails.rippleCurrent(attrs, family));
        }

        String dielectric = null;
        for (String name : DIELECTRIC_NAMES) {
            String v = attrs.get(name);
            if (v != null && dielectric == null) {
                dielectric = Recognizers.tokenize(Recognizers.prepare(v)).stream()
                        .map(Recognizers::dielectric).filter(d -> d != null).findFirst().orElse(null);
            }
        }
        if (dielectric == null) {
            dielectric = description.dielectric();
        }

        String packageName = packageOf(part, attrs, family, description);
        if (ComponentFamily.has(family, Trait.FREQUENCY_VALUED) && !Recognizers.isCrystalSize(packageName)
                && (packageName == null || !Recognizers.isRecognisedPackage(packageName))) {
            // TME "Body dimensions: 3.2x2.5x0.8mm" (no case code): the size code of a crystal is its body in mm
            String size = crystalSize(part, attrs);
            if (size != null) {
                packageName = size;
            }
        }
        String mounting = null;
        for (String name : MOUNTING_NAMES) {
            String v = attrs.get(name);
            if (v != null && mounting == null) {
                mounting = Recognizers.tokenize(Recognizers.prepare(v)).stream()
                        .map(Recognizers::mounting).filter(m -> m != null).findFirst().orElse(null);
            }
        }
        if (mounting == null) {
            mounting = description.mounting();
        }
        if (mounting == null && part.packageName() != null) {
            // LCSC package fields "SMD,D8xL10mm", "插件,D6.3xL8mm"
            mounting = Recognizers.tokenize(Recognizers.prepare(part.packageName())).stream()
                    .map(Recognizers::mounting).filter(m -> m != null).findFirst().orElse(null);
        }
        if (mounting == null && part.category() != null) {
            // Mouser "Aluminium Electrolytic Capacitors - Radial Leaded", "... - SMD"; LCSC "... - Leaded"
            mounting = Recognizers.analyze(part.category()).mounting();
        }
        if (mounting == null) {
            mounting = mountingFromPackage(packageName);
        }

        String technology = technology(part, attrs, family);
        if ("capacitor".equals(family)) {
            putIfNotNull(details, ESR, PassiveDetails.capacitorResistance(part, attrs, technology, true));
            putIfNotNull(details, IMPEDANCE, PassiveDetails.capacitorResistance(part, attrs, technology, false));
        }
        String dimensions = PassiveDetails.dimensions(part, attrs, family);
        if (dimensions != null) {
            details.put(DIMENSIONS, dimensions);
            if ("capacitor".equals(family) && PassiveDetails.isCan(dimensions)
                    && (packageName == null || !Recognizers.isChipCode(packageName)) && !dimensions.equals(packageName)) {
                // a can capacitor's package is its size; the vendor case code (Panasonic "D") is kept as Case
                String code = attrs.containsKey("case") ? attrs.get("case").strip() : packageName;
                if (code != null && !code.isBlank() && !code.equals(dimensions)
                        && PassiveDetails.parseDimensions(code) == null) {
                    details.put(CASE, code);
                }
                packageName = dimensions;
            }
        }
        putIfNotNull(details, QUALIFICATION, PassiveDetails.qualification(part, attrs));

        ParsedQuery.Connector connector = connector(part, attrs, family, packageName);
        if (connector != null) {
            family = "connector";
            if (mounting == null) {
                mounting = connectorMounting(part, attrs);
            }
            if (mounting == null && (UsbVocabulary.MID_MOUNT.equals(connector.mountingStyle())
                    || connector.hasFeature(UsbVocabulary.FULLY_SMD))) {
                mounting = "SMD";   // Mouser "MSMT", TME "Fully SMT"
            }
            // Mouser "Gold plated 3u", "6.5H" (height) are no capacitance/inductance of a connector
            for (String kind : List.of(ParsedQuery.CAPACITANCE, ParsedQuery.INDUCTANCE, ParsedQuery.RESISTANCE)) {
                if (!fromAttributes.contains(kind)) {
                    values.remove(kind);
                }
            }
        }

        StringBuilder text = new StringBuilder();
        for (String s : new String[]{part.manufacturer(), part.manufacturerPartNumber(), part.distributorPartNumber(),
                part.description(), part.category(), part.packageName()}) {
            if (s != null) {
                text.append(s).append(' ');
            }
        }
        part.attributes().values().forEach(v -> text.append(v).append(' '));
        if (connector == null) {
            putIfNotNull(details, FEATURES, PassiveDetails.features(part, attrs, family));
        }
        String typeText = typeText(part, attrs);
        String polarity = ComponentFamily.has(family, Trait.POLARISED) ? ComponentTypes.polarity(typeText) : null;
        String subtype = connector == null ? ComponentTypes.subtype(family, typeText) : null;
        List<Double> voltages = List.of();
        if (ConstraintKind.isExactRating(ParsedQuery.VOLTAGE, family)) {
            voltages = fromAttributes.contains(ParsedQuery.VOLTAGE) ? List.of(values.get(ParsedQuery.VOLTAGE).value())
                    : Recognizers.singleValues(part.description(), ParsedQuery.VOLTAGE, family);
        }
        String formFactor = connector != null ? null
                : FormFactor.ofPart(family, packageName, part.description(), part.category(), part.packageName());
        return new Features(family, values, dielectric, packageName, mounting,
                Recognizers.normalizeKey(text.toString()), connector, connector != null ? null : technology,
                connector == null ? PassiveDetails.elements(part, attrs, family) : null, details, polarity, subtype,
                voltages, formFactor);
    }

    /**
     * The voltage rating of a transistor or diode whose description lists voltages without labels: the largest single
     * voltage (Vds, Vrrm; words with a condition such as {@code 1.25V@150mA} or {@code 500uA@40V} and ranges are left
     * out). A largest value below {@value #MIN_PLAUSIBLE_RATING_VOLTS} V is a threshold or forward voltage, not a
     * rating: the voltage is then unknown (unverified, never a wrong exclusion).
     */
    private static void ratingFromLargestVoltage(String description, String family,
                                                 Map<String, Recognizers.Value> values) {
        List<Double> voltages = Recognizers.singleValues(description, ParsedQuery.VOLTAGE, family);
        if (voltages.isEmpty()) {
            return;
        }
        double largest = voltages.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        if (largest < MIN_PLAUSIBLE_RATING_VOLTS) {
            values.remove(ParsedQuery.VOLTAGE);
        } else {
            values.put(ParsedQuery.VOLTAGE, Recognizers.of(ParsedQuery.VOLTAGE, largest));
        }
    }

    /**
     * The part's technology (resistors, capacitors, inductors only): distributor parameters ({@link #TECHNOLOGY_NAMES}),
     * then the description, then the category. A construction ({@code metal strip}, {@code thick film}) from any source
     * wins over the application word {@code current sense} (Mouser "Current Sense Resistors - SMD", TME "Kind of
     * resistor: current shunt, sensing").
     */
    static String technology(Part part, Map<String, String> attrs, String family) {
        if (!TechnologyVocabulary.applies(family)) {
            return null;
        }
        List<String> found = new java.util.ArrayList<>();
        for (String name : TECHNOLOGY_NAMES) {
            String t = TechnologyVocabulary.ofAttribute(attrs.get(name), family);
            if (t != null) {
                found.add(t);
            }
        }
        String fromDescription = TechnologyVocabulary.of(part.description(), family);
        if (fromDescription != null) {
            found.add(fromDescription);
        }
        String fromCategory = TechnologyVocabulary.of(part.category(), family);
        if (fromCategory != null) {
            found.add(fromCategory);
        }
        String fromSeries = TechnologyVocabulary.ofPart(part.manufacturer(), part.manufacturerPartNumber(),
                part.category(), family);
        if (fromSeries != null) {
            found.add(fromSeries);
        }
        // "polymer" alone is refined by another source: JLCPCB "Polarized Polymer" in the category "Polymer Aluminum
        // Capacitors" is aluminium polymer, "polymer" next to "tantalum" is tantalum polymer
        // (TME "Capacitor: polymer; ...; OS-CON SVF" is aluminium polymer too)
        if (found.contains(TechnologyVocabulary.POLYMER)) {
            Set<String> mentioned = new java.util.HashSet<>(found);
            mentioned.addAll(TechnologyVocabulary.mentions(part.description(), family));
            mentioned.addAll(TechnologyVocabulary.mentions(part.category(), family));
            if (mentioned.contains(TechnologyVocabulary.TANTALUM)
                    || mentioned.contains(TechnologyVocabulary.TANTALUM_POLYMER)) {
                return TechnologyVocabulary.TANTALUM_POLYMER;
            }
            if (mentioned.contains(TechnologyVocabulary.ALUMINIUM_POLYMER)
                    || mentioned.contains(TechnologyVocabulary.ALUMINIUM_ELECTROLYTIC)) {
                return TechnologyVocabulary.ALUMINIUM_POLYMER;
            }
        }
        return found.stream().filter(t -> !TechnologyVocabulary.CURRENT_SENSE.equals(t)).findFirst()
                .orElse(found.isEmpty() ? null : found.getFirst());
    }

    /** The frequency-valued families, both named by a category such as TME "Resonators and Generators". */
    private static final Set<String> FREQUENCY_FAMILIES = Set.of(ComponentFamily.CRYSTAL.label(),
            ComponentFamily.OSCILLATOR.label());
    /** Attributes that state the kind of a semiconductor (TME {@code Type of transistor}, {@code Type of diode}...). */
    private static final List<String> TYPE_NAMES = List.of("type of transistor", "type of diode",
            "kind of voltage regulator", "type of voltage regulator", "transistor polarity", "polarity",
            "channel type", "output type", "regulator type", "transistor type", "diode type", "configuration",
            "number of channels", "technology");
    private static final Pattern BODY = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s?(?:mm)?\\s?[x×*]\\s?(\\d+(?:[.,]\\d+)?)");
    private static final List<String> BODY_NAMES = List.of("body dimensions", "dimensions", "size / dimension",
            "size", "case size", "body size");

    /** The text that names a semiconductor's type: its type attributes, the category and the description. */
    private static String typeText(Part part, Map<String, String> attrs) {
        StringBuilder out = new StringBuilder();
        for (String name : TYPE_NAMES) {
            String v = attrs.get(name);
            if (v != null) {
                out.append(v).append(" ; ");
            }
        }
        if (part.category() != null) {
            out.append(part.category()).append(" ; ");
        }
        if (part.description() != null) {
            out.append(part.description());
        }
        return out.toString();
    }

    /**
     * The size code of a crystal or oscillator from its body dimensions ({@code 3.2x2.5x0.8mm} -&gt; {@code 3225}):
     * a dimensions attribute, else the description; null when none gives a known size.
     */
    private static String crystalSize(Part part, Map<String, String> attrs) {
        for (String name : BODY_NAMES) {
            String size = crystalSize(attrs.get(name));
            if (size != null) {
                return size;
            }
        }
        return crystalSize(part.description());
    }

    private static String crystalSize(String text) {
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher m = BODY.matcher(text);
        while (m.find()) {
            String size = Recognizers.crystalSize(Double.parseDouble(m.group(1).replace(',', '.')),
                    Double.parseDouble(m.group(2).replace(',', '.')));
            if (size != null) {
                return size;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- connectors

    /**
     * Connector attributes of the part, or null when it is not a connector. A part is a connector when its category
     * names one ("Connectors / Female Headers", "Pin headers", "Headers &amp; Wire Housings", "IC / Transistor Socket"),
     * when it has connector parameters (TME "Type of connector", Mouser "Number of Positions"), or when its
     * description names a specific connector type; never when it has an IC/discrete package (unless an IC socket).
     *
     * <p>Precedence: distributor attributes, then the description (with the package field, e.g. LCSC
     * {@code Plugin,P=2.54mm}), then the category. The description's explicit gender beats the category (TME files
     * female sockets under "Pin headers"); the type is refined by the gender ("pin strips" + female = female header).
     */
    private static ParsedQuery.Connector connector(Part part, Map<String, String> attrs, String family,
                                                   String packageName) {
        String categoryText = lastCategorySegment(part.category());
        ConnectorRecognizer.Result category = ConnectorRecognizer.analyze(categoryText);
        StringBuilder descriptionText = new StringBuilder();
        if (part.description() != null) {
            descriptionText.append(part.description()).append(" ; ");
        }
        if (part.packageName() != null) {
            descriptionText.append(part.packageName());
        }
        ConnectorRecognizer.Result description = ConnectorRecognizer.analyze(descriptionText.toString());
        boolean hasAttributes = attrs.keySet().stream().anyMatch(k -> CONNECTOR_ATTRIBUTE.matcher(k).find());
        String descriptionType = description.connector().type();
        boolean specificDescription = descriptionType != null && !ParsedQuery.CONNECTOR.equals(descriptionType)
                && !ParsedQuery.USB.equals(descriptionType);
        boolean connectorFamily = "connector".equals(family);
        if (!(category.connectorWords() || hasAttributes || specificDescription || connectorFamily)) {
            return null;
        }
        // TME "USB cables and adapters" ("Cable; USB C plug,USB C plug"), "Plug-in Power Supplies", Mouser "Sensor
        // Cables / Actuator Cables": no board connector
        if (categoryText != null && !categoryText.toLowerCase(Locale.ROOT).contains("connector")
                && ConnectorRecognizer.NOT_A_CONNECTOR.matcher(categoryText).find()) {
            return null;
        }
        if (part.description() != null && PRODUCT_NOT_CONNECTOR.matcher(part.description()).find()) {
            return null;   // TME "Adapter; USB A socket,USB C plug" filed under "USB & IEEE1394 connectors"
        }
        if (!category.connectorWords() && !hasAttributes && part.description() != null
                && ConnectorRecognizer.NOT_A_CONNECTOR.matcher(part.description()).find()
                && UsbVocabulary.isUsbType(descriptionType)) {
            return null;
        }
        String categoryType = category.connector().type();
        boolean icSocket = ParsedQuery.IC_SOCKET.equals(categoryType) || ParsedQuery.IC_SOCKET.equals(descriptionType);
        if (packageName != null && !icSocket && Recognizers.icPackage(packageName, null) != null
                && !category.connectorWords()) {
            return null;
        }

        // attributes
        String attrType = null;
        String attrGender = null;
        for (String name : CONNECTOR_TYPE_NAMES) {
            String v = attrs.get(name);
            if (v != null) {
                ConnectorRecognizer.Result r = ConnectorRecognizer.analyze(v);
                if (attrType == null && r.connector().type() != null
                        && !ParsedQuery.CONNECTOR.equals(r.connector().type())) {
                    attrType = r.connector().type();
                }
                if (attrGender == null && r.connector().gender() != null && !ParsedQuery.CONNECTOR.equals(attrType)) {
                    attrGender = explicitGender(v);
                }
            }
        }
        for (String name : GENDER_NAMES) {
            String v = attrs.get(name);
            if (v != null && attrGender == null) {
                attrGender = genderWord(v);
            }
        }
        Integer positions = firstInt(attrs, POSITIONS_NAMES);
        Integer rows = firstInt(attrs, ROWS_NAMES);
        for (String name : LAYOUT_NAMES) {
            String v = attrs.get(name);
            if (v != null) {
                ParsedQuery.Connector layout = ConnectorRecognizer.analyze(v).connector();
                if (rows == null) {
                    rows = layout.rows();
                }
                if (positions == null) {
                    positions = layout.positions();
                }
            }
        }
        Double pitch = null;
        for (String name : PITCH_NAMES) {
            String v = attrs.get(name);
            if (v != null && pitch == null) {
                pitch = pitchAttribute(v);
            }
        }
        String orientation = null;
        for (String name : ORIENTATION_NAMES) {
            String v = attrs.get(name);
            if (v != null && orientation == null) {
                orientation = ConnectorRecognizer.analyze(v).connector().orientation();
            }
        }
        String series = null;
        for (String name : SERIES_NAMES) {
            String v = attrs.get(name);
            if (v != null && series == null && !v.isBlank() && v.strip().length() <= 4) {
                series = v.strip().toUpperCase(Locale.ROOT);
            }
        }

        ParsedQuery.Connector d = description.connector();
        ParsedQuery.Connector c = category.connector();
        String type = firstSpecific(attrType, descriptionType, categoryType);
        String gender = attrGender != null ? attrGender
                : explicitGender(descriptionText.toString()) != null ? explicitGender(descriptionText.toString())
                : explicitGender(categoryText) != null ? explicitGender(categoryText)
                : d.gender() != null ? d.gender() : c.gender();
        type = refine(type, gender);
        if (gender == null) {
            gender = ParsedQuery.PIN_HEADER.equals(type) || ParsedQuery.BOX_HEADER.equals(type) ? ParsedQuery.MALE
                    : ParsedQuery.FEMALE_HEADER.equals(type) || ParsedQuery.IC_SOCKET.equals(type) ? ParsedQuery.FEMALE
                    : null;
        }
        if (positions == null) {
            positions = d.positions() != null ? d.positions() : c.positions();
        }
        if (rows == null) {
            rows = d.rows() != null ? d.rows() : c.rows();
        }
        if (pitch == null) {
            pitch = d.pitchMm() != null && !d.pitchImplied() ? d.pitchMm() : c.pitchMm();
        }
        if (orientation == null) {
            orientation = d.orientation() != null ? d.orientation() : c.orientation();
        }
        if (series == null) {
            series = d.series() != null ? d.series() : c.series();
        }
        if (series == null && ParsedQuery.WIRE_TO_BOARD.equals(type)) {
            series = ConnectorRecognizer.series(descriptionText.toString());
        }
        if (!ParsedQuery.WIRE_TO_BOARD.equals(type)) {
            series = null;
        }
        if (rows == null && positions != null && positions == 1) {
            rows = 1;
        }
        String finalType = type == null ? ParsedQuery.CONNECTOR : type;
        if (UsbVocabulary.isUsbType(finalType) || d.usbType() != null || c.usbType() != null) {
            boolean positionsFromAttributes = firstInt(attrs, POSITIONS_NAMES) != null;
            return usbConnector(part, attrs, finalType, gender, positions, positionsFromAttributes, orientation, d, c,
                    description.usb());
        }
        return new ParsedQuery.Connector(finalType, series, gender, positions, rows, pitch, false, orientation);
    }

    /**
     * USB details of a USB connector part (DESIGN.md 3.4). Distributor parameters (TME {@code Type of connector},
     * {@code Version}, {@code Data transfer rate}, {@code Connector variant}, {@code Connectors application},
     * {@code IP rating}, {@code Electrical mounting}, {@code Number of pins}) win over the description, the description
     * over the category. {@code Positions} stays the reported count; the pin configuration maps shell-counted counts
     * to the canonical one (17P/18P -&gt; 16, {@link UsbVocabulary#configuration}). Physical consistency: a Type-C part
     * with 12/14/16 contacts is USB 2.0 whatever the label says (LCSC writes "USB 3.1" on many 16P parts), one with
     * 2/4/6 contacts is power only (no data standard); an unlabelled Micro-B 5P / Type-A 4P is USB 2.0, Micro-B 10P /
     * Type-A 9P USB 3.x Gen 1. A Type-C 24P part without a stated standard keeps none (no pin-based guess).
     */
    private static ParsedQuery.Connector usbConnector(Part part, Map<String, String> attrs, String type, String gender,
                                                      Integer positions, boolean positionsFromAttributes,
                                                      String orientation, ParsedQuery.Connector description,
                                                      ParsedQuery.Connector category,
                                                      UsbVocabulary.Analysis descriptionUsb) {
        StringBuilder attrText = new StringBuilder();
        for (String name : USB_ATTRIBUTE_NAMES) {
            String v = attrs.get(name);
            if (v != null) {
                attrText.append(v).append(" ; ");
            }
        }
        UsbVocabulary.Analysis attr = UsbVocabulary.analyze(attrText, true);
        String usbType = attr.usbType() != null ? attr.usbType()
                : description.usbType() != null ? description.usbType()
                : category.usbType() != null ? category.usbType() : UsbVocabulary.usbTypeOf(type);
        String connectorType = UsbVocabulary.isUsbType(type) || ParsedQuery.CONNECTOR.equals(type)
                ? (usbType != null ? UsbVocabulary.connectorType(usbType) : type) : type;

        Integer configuration;
        Integer shield = null;
        if (!positionsFromAttributes && descriptionUsb != null && descriptionUsb.plusPositions() != null) {
            positions = descriptionUsb.plusPositions();
            configuration = descriptionUsb.plusConfiguration();
            shield = descriptionUsb.plusShield();
        } else {
            configuration = UsbVocabulary.configuration(usbType, positions);
            if (configuration != null && positions != null && positions > configuration) {
                shield = positions - configuration;
            }
        }

        Set<String> features = new java.util.LinkedHashSet<>(attr.features());
        if (attr.ipRating() != null) {
            features.add(attr.ipRating());
        }
        features.addAll(description.features());
        String mpnIp = part.manufacturerPartNumber() == null ? null : ipInPartNumber(part.manufacturerPartNumber());
        if (mpnIp != null && features.stream().noneMatch(x -> x.startsWith("IP"))) {
            // LCSC/JLCPCB write the sealing only in the part number ("USBC-0032IPX8-00", "TYPE-C 6PFS ... IPX7")
            features.add(UsbVocabulary.WATERPROOF);
            features.add(mpnIp);
        }
        // TME "Data transfer rate" (5Gbps) beats "Version" when both are given (CX90B1-24P: USB 4.0 + 20Gbps + Gen.2x2)
        UsbVocabulary.Standard rate = null;
        for (String name : List.of("data transfer rate", "data rate")) {
            String v = attrs.get(name);
            if (v != null && rate == null) {
                rate = UsbVocabulary.analyze(new StringBuilder(v), true).standard();
            }
        }
        UsbVocabulary.Standard standard = rate != null ? rate : attr.standard() != null ? attr.standard()
                : UsbVocabulary.standard(description.usbStandard());
        if (usbType == null && standard != null && standard.rank() >= 5) {
            usbType = ParsedQuery.USB_TYPE_C;   // USB4 and Thunderbolt 3/4 exist only on Type-C (Mouser "Receptacle, USB4")
            connectorType = UsbVocabulary.isUsbType(connectorType) || ParsedQuery.CONNECTOR.equals(connectorType)
                    ? ParsedQuery.USB_C : connectorType;
            if (configuration == null) {
                configuration = UsbVocabulary.configuration(usbType, positions);
                if (configuration != null && positions != null && positions > configuration) {
                    shield = positions - configuration;
                }
            }
        }
        if (ParsedQuery.USB_TYPE_C.equals(usbType) && configuration != null
                && UsbVocabulary.TYPE_C_POWER_ONLY.contains(configuration)) {
            features.add(UsbVocabulary.POWER_ONLY);
        }
        if (features.contains(UsbVocabulary.POWER_ONLY) && ParsedQuery.USB_TYPE_C.equals(usbType)) {
            standard = null;   // no D+/D- or SuperSpeed contacts, whatever the label says
        } else {
            standard = UsbVocabulary.physicalStandard(usbType, configuration, standard);
        }
        List<String> featureList = List.copyOf(features);
        return ParsedQuery.Connector.builder()
                .type(connectorType)
                .gender(gender)
                .positions(positions)
                .orientation(orientation)
                .usbType(usbType)
                .usbStandard(standard == null ? null : standard.name())
                .usbSpeedGbps(standard == null ? null : standard.gbps())
                .pinConfiguration(configuration)
                .shieldPinsCounted(shield)
                .mountingStyle(ConnectorRecognizer.mountingStyle(featureList))
                .features(featureList)
                .build();
    }

    /** TME descriptions that start with the product kind: "Adapter; ...", "Cable; ...", "Hub USB; ...". */
    private static final Pattern PRODUCT_NOT_CONNECTOR = Pattern.compile(
            "(?i)^\\s*(?:adapter|cable|hub|power supply|usb power supply|charger|card reader|docking station)\\b");

    private static final Pattern IP_IN_MPN = Pattern.compile("(?i)IP(X[4-8]|6[5-8])(?![0-9])");

    /** {@code IPX8}, {@code IP67} inside a part number, else null. */
    private static String ipInPartNumber(String mpn) {
        java.util.regex.Matcher m = IP_IN_MPN.matcher(mpn);
        return m.find() ? "IP" + m.group(1).toUpperCase(Locale.ROOT) : null;
    }

    /** The first type that is more specific than "connector"/"header"/"usb"; else the first non-null one. */
    private static String firstSpecific(String... types) {
        for (String t : types) {
            if (t != null && !ParsedQuery.CONNECTOR.equals(t) && !ParsedQuery.HEADER.equals(t)
                    && !ParsedQuery.USB.equals(t)) {
                return t;
            }
        }
        for (String t : types) {
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    private static String refine(String type, String gender) {
        if (type == null || gender == null) {
            return type;
        }
        if (ParsedQuery.FEMALE.equals(gender)
                && (ParsedQuery.PIN_HEADER.equals(type) || ParsedQuery.HEADER.equals(type))) {
            return ParsedQuery.FEMALE_HEADER;
        }
        if (ParsedQuery.MALE.equals(gender)
                && (ParsedQuery.FEMALE_HEADER.equals(type) || ParsedQuery.HEADER.equals(type))) {
            return ParsedQuery.PIN_HEADER;
        }
        if (ParsedQuery.MALE.equals(gender) && ParsedQuery.IDC_SOCKET.equals(type)) {
            return ParsedQuery.BOX_HEADER;
        }
        return type;
    }

    private static final Pattern FEMALE_WORD = Pattern.compile("(?i)\\bfemale\\b");
    private static final Pattern MALE_WORD = Pattern.compile("(?i)\\bmale\\b");

    /** "female"/"male" when the text says so explicitly (earliest wins), else null. */
    private static String explicitGender(String text) {
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher f = FEMALE_WORD.matcher(text);
        java.util.regex.Matcher m = MALE_WORD.matcher(text);
        int fi = f.find() ? f.start() : -1;
        int mi = m.find() ? m.start() : -1;
        if (fi >= 0 && (mi < 0 || fi <= mi)) {
            return ParsedQuery.FEMALE;
        }
        return mi >= 0 ? ParsedQuery.MALE : null;
    }

    /** Gender attribute values: "female"/"male", Mouser "Socket"/"Receptacle"/"Pin"/"Plug". */
    private static String genderWord(String value) {
        String explicit = explicitGender(value);
        if (explicit != null) {
            return explicit;
        }
        String v = value.toLowerCase(Locale.ROOT);
        if (v.contains("socket") || v.contains("receptacle") || v.contains("jack")) {
            return ParsedQuery.FEMALE;
        }
        if (v.contains("pin") || v.contains("plug")) {
            return ParsedQuery.MALE;
        }
        return null;
    }

    private static final Pattern MM_VALUE = Pattern.compile("(?i)(\\d+(?:[.,]\\d+)?)\\s*(?:mm)?");

    /** A pitch attribute value: "2.54mm", "2.54 mm", "2.54" (millimetres), "0.1 in" / {@code 0.1"} (inches). */
    static Double pitchAttribute(String value) {
        Double recognised = ConnectorRecognizer.analyze(value).connector().pitchMm();
        if (recognised != null) {
            return recognised;
        }
        java.util.regex.Matcher m = MM_VALUE.matcher(value.strip());
        if (m.matches()) {
            double mm = Double.parseDouble(m.group(1).replace(',', '.'));
            return mm > 0 && mm <= 20 ? mm : null;
        }
        return null;
    }

    private static Integer firstInt(Map<String, String> attrs, List<String> names) {
        for (String name : names) {
            String v = attrs.get(name);
            if (v != null) {
                java.util.regex.Matcher m = DIGITS.matcher(v);
                if (m.find()) {
                    int n = Integer.parseInt(m.group());
                    if (n > 0) {
                        return n;
                    }
                }
            }
        }
        return null;
    }

    private static String lastCategorySegment(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        String[] segments = CATEGORY_SEPARATOR.split(category.strip());
        String last = segments[segments.length - 1];
        // "Connectors / Pin Header & Female Header" mixes both genders: leave the decision to the description
        return last.contains("&") && last.toLowerCase(Locale.ROOT).contains("female") ? "header" : last;
    }

    /** THT/SMD from connector wording (LCSC {@code 插件}, {@code Plugin}, {@code 卧贴}). */
    private static String connectorMounting(Part part, Map<String, String> attrs) {
        String text = (part.description() == null ? "" : part.description()) + " "
                + (part.packageName() == null ? "" : part.packageName());
        return ConnectorRecognizer.analyze(text).mounting();
    }

    /** Category paths ("Crystals, Oscillators/Crystals"): the most specific (last) segment decides the family. */
    private static Recognizers.Analysis categoryAnalysis(String category) {
        if (category == null || category.isBlank()) {
            return Recognizers.analyze(null);
        }
        String[] segments = CATEGORY_SEPARATOR.split(category);
        Recognizers.Analysis last = Recognizers.analyze(segments[segments.length - 1]);
        return last.familyExplicit() ? last : Recognizers.analyze(category);
    }

    private static String packageOf(Part part, Map<String, String> attrs, String family,
                                    Recognizers.Analysis description) {
        String fromField = Recognizers.findPackage(part.packageName(), family, false);
        if (fromField != null) {
            return fromField;
        }
        for (String name : PACKAGE_INCH_NAMES) {
            String p = Recognizers.findPackage(attrs.get(name), family, false);
            if (p != null) {
                return p;
            }
        }
        for (String name : PACKAGE_MM_NAMES) {
            String p = Recognizers.findPackage(attrs.get(name), family, true);
            if (p != null) {
                return p;
            }
        }
        for (String name : PACKAGE_NAMES) {
            String p = Recognizers.findPackage(attrs.get(name), family, false);
            if (p != null) {
                return p;
            }
        }
        if (description.packageName() != null) {
            return description.packageName();
        }
        if (part.packageName() != null && !part.packageName().isBlank() && !part.packageName().strip().equals("-")) {
            return part.packageName().trim();   // stated by the distributor, even if not recognised: never overridden
        }
        return ComponentFamily.has(family, Trait.PASSIVE)
                ? packageFromPartNumber(part.manufacturerPartNumber(), part.manufacturer()) : null;
    }

    // ---------------------------------------------------------------- package from the part number

    /**
     * Series whose part number is {@code <series><imperial chip code>...} ({@code TNPW0805...}, {@code RC0805FR-07...},
     * {@code CRGCQ0805...}). Mined from the JLCPCB database (2026-10-05): every row of these prefixes with a chip
     * package (over 450 000 rows) states the same code as its {@code Package} column. Vishay {@code CRCW}, {@code TNPW},
     * {@code TNPU}, {@code RCP}, {@code RCS}, {@code RCG}, {@code RCWE}, {@code MCT}, {@code MCS}, {@code MCU},
     * {@code MCA}, {@code PAT}, {@code PLT}, {@code PLTT}, {@code PTN}, {@code WSL}, {@code VJ}; Yageo {@code RC},
     * {@code RT}, {@code AC}, {@code AT}, {@code AA}, {@code AF}, {@code AR}, {@code PE}, {@code PT}, {@code SR},
     * {@code RE}, {@code RL}, {@code RV}, {@code CC}, {@code CQ}; Stackpole {@code RNCF}, {@code RMCF}, {@code RMCS},
     * {@code RMCP}, {@code RMEF}, {@code RGC}, {@code RNCS}, {@code CSR}; TE {@code CPF}, {@code CRG}, {@code CRGH},
     * {@code CRGV}, {@code CRGCQ}. KEMET's {@code C0805C106K...} only for KEMET: TDK, iCM and Darfon write metric
     * codes after the same {@code C} ({@code C0603...} is a 0201 part).
     */
    private static final Set<String> CHIP_CODE_SERIES = Set.of("CRCW", "TNPW", "TNPU", "RCP", "RCS", "RCG", "RCWE",
            "MCT", "MCS", "MCU", "MCA", "PAT", "PLT", "PLTT", "PTN", "WSL", "VJ", "RC", "RT", "AC", "AT", "AA", "AF",
            "AR", "PE", "PT", "SR", "RE", "RL", "RV", "CC", "CQ", "RNCF", "RMCF", "RMCS", "RMCP", "RMEF", "RGC", "RNCS",
            "CSR", "CPF", "CRG", "CRGH", "CRGV", "CRGCQ");
    private static final Pattern MPN_CHIP_CODE = Pattern.compile(
            "^([A-Z]{1,5})(0201|0402|0603|0805|1206|1210|1812|2010|2512)");
    /**
     * Manufacturers that put metric size codes after a letter prefix (Samsung {@code RC0402...} = 01005, Susumu
     * {@code RT0603...} = 0201, TDK {@code C0603...}/{@code MLG0603...}, Taiyo Yuden {@code HK0603...}, Sunlord
     * {@code SDCL0603...}, Murata): never read a chip code from their part numbers.
     */
    private static final Pattern METRIC_CODE_MAKERS = Pattern.compile("(?i)samsung|tdk|susumu|taiyo|sunlord|murata");
    /**
     * TE RN73 thin film resistors: size letters after {@code RN73} and the TCR letter, e.g. {@code RN73C2A5K36BTDF}
     * (TE datasheet 1773270 "How To Order": 1E 0402, 1J 0603, 2A 0805, 2B 1206, 2E 1210, 2H 2010, 3A 2512; the JLCPCB
     * database agrees for all 172 000 rows of 1E/1J/2A/2B/2E).
     */
    private static final Pattern RN73 = Pattern.compile("^RN73[A-Z]?(1E|1J|2A|2B|2E|2H|3A)");
    private static final Map<String, String> RN73_SIZES = Map.of("1E", "0402", "1J", "0603", "2A", "0805",
            "2B", "1206", "2E", "1210", "2H", "2010", "3A", "2512");

    /**
     * The imperial chip code a chip resistor/capacitor part number states (conservative, see {@link #CHIP_CODE_SERIES}),
     * or null. Used only when neither the package field, the attributes nor the description name a package.
     */
    static String packageFromPartNumber(String mpn, String manufacturer) {
        if (mpn == null || mpn.isBlank() || manufacturer != null && METRIC_CODE_MAKERS.matcher(manufacturer).find()) {
            return null;
        }
        String number = mpn.strip().toUpperCase(Locale.ROOT);
        java.util.regex.Matcher rn73 = RN73.matcher(number);
        if (rn73.find()) {
            return RN73_SIZES.get(rn73.group(1));
        }
        java.util.regex.Matcher m = MPN_CHIP_CODE.matcher(number);
        if (!m.find()) {
            return null;
        }
        String series = m.group(1);
        boolean kemet = "C".equals(series) && manufacturer != null
                && manufacturer.toLowerCase(Locale.ROOT).contains("kemet");
        return CHIP_CODE_SERIES.contains(series) || kemet ? m.group(2) : null;
    }

    private static String mountingFromPackage(String packageName) {
        if (packageName == null) {
            return null;
        }
        if (Recognizers.isChipCode(packageName)) {
            return "SMD";
        }
        String p = packageName.toUpperCase(Locale.ROOT);
        for (String prefix : THT_PACKAGE_PREFIXES) {
            if (p.startsWith(prefix)) {
                return "THT";
            }
        }
        for (String prefix : SMD_PACKAGE_PREFIXES) {
            if (p.startsWith(prefix)) {
                return "SMD";
            }
        }
        return null;
    }

    /**
     * Inductor and ferrite bead parameters: rated current, saturation current, DC resistance and (ferrite beads) the
     * impedance with its test frequency, from TME ({@code Operating current}, {@code Saturation current},
     * {@code Resistance}, {@code Impedance at 100MHz}) and Mouser ({@code Maximum DC Current}, {@code Saturation
     * Current}, {@code Maximum DC Resistance}, {@code Impedance} + {@code Test Frequency}) attributes. A plain
     * {@code Resistance} attribute of such a part is its DC resistance, never a nominal resistance.
     */
    private static void inductiveAttributes(Map<String, String> attrs, String family,
                                            Map<String, Recognizers.Value> values) {
        attributeValue(attrs, RATED_CURRENT_NAMES, ParsedQuery.CURRENT, family, values);
        for (String name : SATURATION_NAMES) {
            Recognizers.Value v = Recognizers.firstValue(attrs.get(name), ParsedQuery.CURRENT, family);
            if (v != null) {
                values.putIfAbsent(ParsedQuery.SATURATION_CURRENT, Recognizers.of(ParsedQuery.SATURATION_CURRENT,
                        v.value()));
                break;
            }
        }
        for (String name : DCR_NAMES) {
            Recognizers.Value v = Recognizers.firstValue(attrs.get(name), ParsedQuery.RESISTANCE, null);
            if (v != null) {
                values.putIfAbsent(ParsedQuery.DCR, Recognizers.of(ParsedQuery.DCR, v.value()));
                break;
            }
        }
        if (!"ferrite".equals(family)) {
            return;
        }
        for (Map.Entry<String, String> e : attrs.entrySet()) {
            if (!e.getKey().startsWith("impedance")) {
                continue;
            }
            Recognizers.Value v = Recognizers.firstValue(e.getValue(), ParsedQuery.RESISTANCE, null);
            if (v == null) {
                continue;
            }
            Double frequency = null;
            // "Impedance at 100MHz" (TME), or the value itself ("120ohm @100MHz", the canonical form)
            java.util.regex.Matcher m = KEY_FREQUENCY.matcher(e.getKey());
            if (!m.find()) {
                m = KEY_FREQUENCY.matcher(e.getValue());
                m = m.find() ? m : null;
            }
            if (m != null) {
                frequency = Double.parseDouble(m.group(1)) * switch (m.group(2).toLowerCase(Locale.ROOT)) {
                    case "k" -> 1e3;
                    case "m" -> 1e6;   // the key is lower-case: "impedance at 100mhz"
                    case "g" -> 1e9;
                    default -> 1.0;
                };
            }
            for (String name : TEST_FREQUENCY_NAMES) {
                Recognizers.Value f = frequency != null ? null
                        : Recognizers.firstValue(attrs.get(name), ParsedQuery.FREQUENCY, family);
                if (f != null) {
                    frequency = f.value();
                }
            }
            values.put(ParsedQuery.IMPEDANCE, Recognizers.of(ParsedQuery.IMPEDANCE, v.value(), frequency));
            return;
        }
    }

    /** Lifetime in hours from a lifetime attribute ("2000h", "5000 Hours", "2000 Hrs @ 105°C"): never inductance. */
    private static void lifetimeAttribute(Map<String, String> attrs, Map<String, Recognizers.Value> values) {
        for (String name : LIFETIME_NAMES) {
            String v = attrs.get(name);
            if (v == null) {
                continue;
            }
            java.util.regex.Matcher m = HOURS.matcher(v);
            if (m.find()) {
                double hours = Double.parseDouble(m.group(1).replace(',', '.'));
                Double temperature = m.group(2) == null ? null : Double.parseDouble(m.group(2));
                values.putIfAbsent(ParsedQuery.LIFETIME, Recognizers.of(ParsedQuery.LIFETIME, hours, temperature));
                return;
            }
        }
    }

    /**
     * The operating temperature range, normalised to {@code <min>...<max>°C}: a range attribute (TME
     * {@code Operating temperature}), else a range the description prints (LCSC {@code -55℃~+155℃}); null when neither
     * states both ends.
     */
    static String operatingTemperature(Map<String, String> attrs, String description) {
        for (String name : TEMPERATURE_RANGE_NAMES) {
            String range = temperatureRange(attrs.get(name));
            if (range != null) {
                return range;
            }
        }
        return temperatureRange(description);
    }

    private static String temperatureRange(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        java.util.regex.Matcher m = TEMPERATURE_RANGE.matcher(
                java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC));
        if (!m.find()) {
            return null;
        }
        double min = Double.parseDouble(m.group(1).replace("−", "-").replace(" ", ""));
        double max = Double.parseDouble(m.group(2).replace("+", ""));
        if (min >= max) {
            return null;
        }
        return plain(min) + "..." + plain(max) + "°C";
    }

    private static String plain(double v) {
        return java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    /** The maximum operating temperature: the largest number of an operating temperature attribute. */
    private static void temperatureAttribute(Map<String, String> attrs, Map<String, Recognizers.Value> values) {
        for (String name : TEMPERATURE_NAMES) {
            String v = attrs.get(name);
            if (v == null) {
                continue;
            }
            java.util.regex.Matcher m = SIGNED_NUMBER.matcher(v);
            Double max = null;
            while (m.find()) {
                double n = Double.parseDouble(m.group().replace("−", "-").replace(" ", ""));
                max = max == null ? n : Math.max(max, n);
            }
            if (max != null && max > 0) {
                values.putIfAbsent(ParsedQuery.TEMPERATURE, Recognizers.of(ParsedQuery.TEMPERATURE, max));
                return;
            }
        }
    }

    private static void attributeValue(Map<String, String> attrs, Iterable<String> names, String kind, String family,
                                       Map<String, Recognizers.Value> values) {
        for (String name : names) {
            String v = attrs.get(name);
            if (v != null) {
                putFirst(values, kind, v, family);
                if (values.containsKey(kind)) {
                    return;
                }
            }
        }
    }

    private static void putFirst(Map<String, Recognizers.Value> values, String kind, String text, String family) {
        if (!values.containsKey(kind)) {
            Recognizers.Value v = Recognizers.firstValue(text, kind, family);
            if (v != null) {
                values.put(kind, v);
            }
        }
    }

    private static void putIfNotNull(Map<String, String> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
