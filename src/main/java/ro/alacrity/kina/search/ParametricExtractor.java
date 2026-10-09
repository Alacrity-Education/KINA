package ro.alacrity.kina.search;

import org.springframework.stereotype.Component;
import ro.alacrity.kina.domain.ComponentFamily;
import ro.alacrity.kina.domain.ComponentFamily.Trait;
import ro.alacrity.kina.domain.ConstraintKind;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.PartAttribute;
import ro.alacrity.kina.domain.PartFeatures;
import ro.alacrity.kina.domain.PartSource;
import ro.alacrity.kina.domain.extract.FirstInteger;
import ro.alacrity.kina.domain.extract.GenderWord;
import ro.alacrity.kina.domain.extract.PartNumberPackage;
import ro.alacrity.kina.domain.Part;

import java.util.HashSet;
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
 * attributes) take precedence over description parsing. The attribute names, units, logic and precedence are declared
 * on {@link PartAttribute} ({@code @Source}, {@code @Unit}); this class runs them and keeps the rules that combine
 * several sources. Stateless and thread-safe.
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

    /**
     * The version of the extraction the field index ({@code part_index.extractor_version}, DESIGN.md 3.8) was written
     * with. Bump it with every change of what the extractor or {@link PartIndexRows} derive: rows of an older version
     * are re-indexed in the background ({@code PartIndexReindexer}) and, until then, are kept by every rule but the
     * family. {@code IndexVersionTest} fails when the extraction changes without a bump.
     */
    public static final int INDEX_VERSION = 1;

    /**
     * The SHA-256 of the extraction and the index rows of the evaluation set at {@link #INDEX_VERSION}
     * ({@code IndexVersionTest}); change it together with the version.
     */
    public static final String INDEX_FINGERPRINT =
            "2030428e0ed10389aac5b605ca2b5da61df03c1a9e6f823b451771a8ff2af6cd";

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
    // fans (DESIGN.md 3.4 "Fans")
    public static final String SPEED = "Speed";
    public static final String AIRFLOW = "Airflow";
    public static final String STATIC_PRESSURE = "StaticPressure";
    public static final String NOISE = "Noise";
    /** "axial" or "radial". */
    public static final String FAN_TYPE = "FanType";
    /** "DC" or "AC". */
    public static final String FAN_SUPPLY = "FanSupply";
    /** "40x40x10mm". */
    public static final String FRAME_SIZE = "FrameSize";
    /** "ball", "sleeve", "fluid dynamic", "vapo"... */
    public static final String BEARING = "Bearing";
    // LEDs (DESIGN.md 3.4 "LEDs")
    public static final String WAVELENGTH = "Wavelength";
    public static final String COLOUR_TEMPERATURE = "ColourTemperature";
    public static final String FORWARD_VOLTAGE = "ForwardVoltage";
    public static final String LUMINOUS_INTENSITY = "LuminousIntensity";
    public static final String LUMINOUS_FLUX = "LuminousFlux";
    public static final String VIEWING_ANGLE = "ViewingAngle";
    /** "red", "warm white", "RGB"... (not "Colour": TME sends that for the colour of a housing). */
    public static final String COLOUR = "LedColour";
    /** "clear", "diffused", "tinted" (not "Lens": TME sends that with its own wording). */
    public static final String LENS = "LensType";
    /** "indicator", "high power", "addressable", "strip", "receiver"... */
    public static final String LED_TYPE = "LedType";
    // switches (DESIGN.md 3.4 "Switches")
    public static final String FORCE = "Force";
    public static final String LIFE = "Life";
    public static final String IP_RATING = "IpRating";
    public static final String VOLTAGE_AC = "VoltageAC";
    public static final String VOLTAGE_DC = "VoltageDC";
    /** "tactile", "toggle", "DIP"...; "IC" or "sensor" for a part that is no mechanical switch. */
    public static final String SWITCH_TYPE = "SwitchType";
    /** "SPDT", "SPST-NO". */
    public static final String CONTACTS = "Contacts";
    /** "momentary", "latching", "ON-OFF-ON". */
    public static final String SWITCH_FUNCTION = "SwitchFunction";
    /** "PCB", "solder lug", "quick connect", "wire leads", "screw", "panel". */
    public static final String TERMINATION = "Termination";
    /** "6x6x4.3mm". */
    public static final String SWITCH_SIZE = "SwitchSize";
    /** "12mm". */
    public static final String HOLE_DIAMETER = "HoleDiameter";
    public static final String SWITCH_POSITIONS = "SwitchPositions";
    /** "yes" or "no". */
    public static final String ILLUMINATED = "Illuminated";
    public static final String ILLUMINATION_COLOUR = "IlluminationColour";

    /**
     * Every canonical key {@link #extract} can produce: the {@code compact} response detail returns only these and
     * leaves the raw distributor attributes (TME "Operating voltage", "Case - inch"...) to {@code full}.
     */
    public static final Set<String> CANONICAL_KEYS = Set.of(CAPACITANCE, RESISTANCE, INDUCTANCE, IMPEDANCE, FREQUENCY,
            VOLTAGE, CURRENT, RATED_CURRENT, SATURATION_CURRENT, DCR, POWER, MAX_TEMPERATURE, LIFETIME, TOLERANCE,
            "Dielectric", "Package", "Mounting", "Family", "Technology", "ConnectorType", "Gender", "Positions", "Rows",
            "Pitch", "Orientation", "Series", "UsbType", "UsbStandard", "UsbSpeedGbps", "PinConfiguration",
            "ShieldPinsCounted", "MountingStyle", "Waterproof", "Features", ELEMENTS, RIPPLE_CURRENT, ESR, DIMENSIONS,
            QUALIFICATION, CASE, POLARITY, SUBTYPE, FORM_FACTOR, OPERATING_TEMPERATURE, SPEED, AIRFLOW, STATIC_PRESSURE,
            NOISE, FAN_TYPE, FAN_SUPPLY, FRAME_SIZE, BEARING, WAVELENGTH, COLOUR_TEMPERATURE, FORWARD_VOLTAGE,
            LUMINOUS_INTENSITY, LUMINOUS_FLUX, VIEWING_ANGLE, COLOUR, LENS, LED_TYPE, FORCE, LIFE, IP_RATING, VOLTAGE_AC,
            VOLTAGE_DC, SWITCH_TYPE, CONTACTS, SWITCH_FUNCTION, TERMINATION, SWITCH_SIZE, HOLE_DIAMETER,
            SWITCH_POSITIONS, ILLUMINATED, ILLUMINATION_COLOUR);

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



    // connector attributes (TME parameters verified live 2026-10-05: "Type of connector" = pin strips, "Connector" =
    // socket, "Kind of connector" = female, "Number of pins" = 6, "Spatial orientation" = angled 90°,
    // "Contacts pitch" = 2.54mm, "Connector pinout layout" = 1x6, "Electrical mounting" = THT, "Manufacturer series" = XH;
    // Mouser ProductAttributes names as documented by Mouser)
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
                    List<Double> voltages, String formFactor, ParsedQuery.Fan fan,
                    ParsedQuery.Led led, ParsedQuery.Switch sw) implements PartFeatures {

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
        for (PartAttribute attribute : PartAttribute.VALUES) {
            Recognizers.Value v = f.values().get(attribute.kind());
            if (v != null) {
                // an inductor's or ferrite bead's current is its rated current
                out.put(inductive && attribute == PartAttribute.CURRENT ? RATED_CURRENT : attribute.key(), v.display());
            }
        }
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
        ParsedQuery.Fan fan = f.fan();
        if (fan != null) {
            putIfNotNull(out, FAN_TYPE, fan.type());
            putIfNotNull(out, FAN_SUPPLY, fan.supply());
            putIfNotNull(out, FRAME_SIZE, fan.frame() == null ? null : fan.frame().display());
            putIfNotNull(out, BEARING, fan.bearing());
            if (!fan.features().isEmpty()) {
                out.put(FEATURES, String.join(", ", fan.features()));
            }
        }
        ParsedQuery.Led led = f.led();
        if (led != null) {
            putIfNotNull(out, COLOUR, led.colour());
            putIfNotNull(out, LENS, led.lens());
            putIfNotNull(out, LED_TYPE, led.type());
            putIfNotNull(out, ORIENTATION, led.orientation());
        }
        ParsedQuery.Switch sw = f.sw();
        if (sw != null) {
            putIfNotNull(out, SWITCH_TYPE, sw.type());
            putIfNotNull(out, CONTACTS, sw.contacts() == null ? null : sw.contacts().display());
            putIfNotNull(out, SWITCH_FUNCTION, sw.function());
            putIfNotNull(out, TERMINATION, sw.termination());
            putIfNotNull(out, SWITCH_SIZE, sw.size() == null ? null : sw.size().display());
            putIfNotNull(out, HOLE_DIAMETER, sw.holeDiameter() == null ? null
                    : java.math.BigDecimal.valueOf(sw.holeDiameter()).stripTrailingZeros().toPlainString() + "mm");
            putIfNotNull(out, SWITCH_POSITIONS, sw.positions() == null ? null : sw.positions().toString());
            putIfNotNull(out, ILLUMINATED, sw.illuminated() == null ? null : sw.illuminated() ? "yes" : "no");
            putIfNotNull(out, ILLUMINATION_COLOUR, sw.illuminationColour());
            putIfNotNull(out, ORIENTATION, sw.orientation());
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
        if (PartAttribute.ofKey(key) != PartAttribute.POWER) {
            return false;
        }
        Recognizers.Value a = Recognizers.firstValue(raw, ParsedQuery.POWER, null);
        Recognizers.Value b = Recognizers.firstValue(canonical, ParsedQuery.POWER, null);
        return a != null && b != null && a.condition() == null
                && ConstraintKind.sameValue(b.value(), a.value(), 1e-9);
    }

    /** Typed features used by {@link DeterministicRanker}. */
    Features features(Part part) {
        Recognizers.Analysis category = categoryAnalysis(part.category());
        // the category's family decides how a description without a family word is read (LCSC ferrite beads)
        Recognizers.Analysis description = Recognizers.analyze(part.description(),
                category.familyExplicit() ? category.family() : null);

        PartSource source = PartSource.of(part);
        Map<String, String> attrs = source.attributes();

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
        SearchExtractionContext ctx = new SearchExtractionContext(source, description, valueFamily,
                c -> partFamily(valueFamily, c, description));

        // every numeric attribute through its declared sources (PartAttribute, DESIGN.md 3.4 "Attribute sources"):
        // the first source in precedence order that reads a value wins
        Map<String, Recognizers.Value> values = new LinkedHashMap<>();
        Set<String> fromAttributes = new HashSet<>();
        for (PartAttribute attribute : PartAttribute.VALUES) {
            PartAttribute.Reading r = ctx.reading(attribute);
            if (r != null) {
                values.put(attribute.kind(), (Recognizers.Value) r.value());
                if (r.statedByAttributes()) {
                    fromAttributes.add(attribute.kind());
                }
            }
        }

        String family = ctx.partFamily();
        Map<String, String> details = new LinkedHashMap<>();
        putIfNotNull(details, OPERATING_TEMPERATURE, ctx.read(PartAttribute.OPERATING_TEMPERATURE, String.class));
        if ("capacitor".equals(family)) {
            // a capacitor's current is its ripple current (TME "Operating current" 0.24A on EEEFK1C101P), never Current;
            // the part's family decides, which is known only once the values are read (so no source filter)
            values.remove(ParsedQuery.CURRENT);
            Recognizers.Value ripple = ctx.read(PartAttribute.RIPPLE_CURRENT, Recognizers.Value.class);
            putIfNotNull(details, RIPPLE_CURRENT, ripple == null ? null : ripple.display());
        }

        String dielectric = ctx.read(PartAttribute.DIELECTRIC, String.class);
        String packageName = ctx.read(PartAttribute.PACKAGE, String.class);
        if (ComponentFamily.has(family, Trait.FREQUENCY_VALUED) && !Recognizers.isCrystalSize(packageName)
                && (packageName == null || !Recognizers.isRecognisedPackage(packageName))) {
            // TME "Body dimensions: 3.2x2.5x0.8mm" (no case code): the size code of a crystal is its body in mm
            String size = crystalSize(part, ctx.texts(PartAttribute.CRYSTAL_BODY));
            if (size != null) {
                packageName = size;
            }
        }
        // the declared mounting sources, then two fallbacks that are no attribute: the category's wording and the
        // package the part ends up with (after the crystal and can rules)
        String mounting = ctx.read(PartAttribute.MOUNTING, String.class);
        if (mounting == null && part.category() != null) {
            // Mouser "Aluminium Electrolytic Capacitors - Radial Leaded", "... - SMD"; LCSC "... - Leaded"
            mounting = Recognizers.analyze(part.category()).mounting();
        }
        if (mounting == null) {
            mounting = mountingFromPackage(packageName);
        }

        String technology = technology(part, ctx.texts(PartAttribute.TECHNOLOGY), family);
        if ("capacitor".equals(family)) {
            putIfNotNull(details, ESR, PassiveDetails.capacitorResistance(part, ctx, technology, true));
            putIfNotNull(details, IMPEDANCE, PassiveDetails.capacitorResistance(part, ctx, technology, false));
        }
        String dimensions = ctx.read(PartAttribute.DIMENSIONS, String.class);
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

        ParsedQuery.Connector connector = connector(part, ctx, family, packageName);
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
        String typeText = typeText(part, ctx.texts(PartAttribute.SEMICONDUCTOR_TYPE));
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
                connector == null ? ctx.read(PartAttribute.ELEMENTS, Integer.class) : null, details, polarity, subtype,
                voltages, formFactor, connector == null ? fan(ctx) : null,
                connector == null ? led(ctx, values.get(ParsedQuery.WAVELENGTH)) : null,
                connector == null ? sw(ctx, mounting) : null);
    }

    /**
     * The fan attributes of a part as their declared sources read them ({@link PartAttribute#FAN_TYPE},
     * {@link PartAttribute#FAN_SUPPLY}, {@link PartAttribute#FRAME_SIZE}, {@link PartAttribute#BEARING},
     * {@link PartAttribute#FAN_FEATURES}: fan family only), null when none says anything.
     */
    private static ParsedQuery.Fan fan(SearchExtractionContext ctx) {
        String frame = ctx.read(PartAttribute.FRAME_SIZE, String.class);
        String features = ctx.read(PartAttribute.FAN_FEATURES, String.class);
        ParsedQuery.Fan fan = new ParsedQuery.Fan(ctx.read(PartAttribute.FAN_TYPE, String.class),
                ctx.read(PartAttribute.FAN_SUPPLY, String.class), frame == null ? null : FanVocabulary.frame(frame),
                ctx.read(PartAttribute.BEARING, String.class),
                features == null ? List.of() : List.of(features.split(", ")));
        return fan.isEmpty() ? null : fan;
    }

    /**
     * The LED attributes of a part as their declared sources read them ({@link PartAttribute#COLOUR},
     * {@link PartAttribute#LENS}, {@link PartAttribute#LED_TYPE}, {@link PartAttribute#LED_ORIENTATION}: LED family
     * only), null for a part of another family. A part without a colour word gets the band of its wavelength.
     */
    private static ParsedQuery.Led led(SearchExtractionContext ctx, Recognizers.Value wavelength) {
        String colour = ctx.read(PartAttribute.COLOUR, String.class);
        if (colour == null && wavelength != null) {
            colour = LedVocabulary.band(wavelength.value());
        }
        ParsedQuery.Led led = new ParsedQuery.Led(colour, ctx.read(PartAttribute.LENS, String.class),
                ctx.read(PartAttribute.LED_TYPE, String.class), ctx.read(PartAttribute.LED_ORIENTATION, String.class));
        return led.isEmpty() ? null : led;
    }

    /**
     * The switch attributes of a part as their declared sources read them ({@link PartAttribute#SWITCH_TYPE} for every
     * family, the others for switches only), null when none says anything. A tactile switch is momentary when nothing
     * says otherwise, an SMD or THT switch has a PCB termination, and the positions of a DIP switch are its number of
     * switches ({@link PartAttribute#DIP_SWITCHES}).
     */
    private static ParsedQuery.Switch sw(SearchExtractionContext ctx, String mounting) {
        String type = ctx.read(PartAttribute.SWITCH_TYPE, String.class);
        String contacts = ctx.read(PartAttribute.CONTACTS, String.class);
        String size = ctx.read(PartAttribute.SWITCH_SIZE, String.class);
        String hole = ctx.read(PartAttribute.HOLE_DIAMETER, String.class);
        Object positions = "DIP".equals(type) && ctx.read(PartAttribute.DIP_SWITCHES, Object.class) != null
                ? ctx.read(PartAttribute.DIP_SWITCHES, Object.class) : ctx.read(PartAttribute.SWITCH_POSITIONS, Object.class);
        String termination = ctx.read(PartAttribute.TERMINATION, String.class);
        if (termination == null && type != null && mounting != null && !SwitchVocabulary.isNotMechanical(type)) {
            termination = ParsedQuery.Switch.PCB;   // JLCPCB "SMD-4P,6x6mm": soldered to the board
        }
        String illuminated = ctx.read(PartAttribute.ILLUMINATED, String.class);
        String function = ctx.read(PartAttribute.SWITCH_FUNCTION, String.class);
        if (function == null && ParsedQuery.Switch.TACTILE.equals(type)) {
            function = ParsedQuery.Switch.MOMENTARY;   // a tactile switch springs back
        }
        ParsedQuery.Switch sw = ParsedQuery.Switch.builder()
                .type(type)
                .contacts(contacts == null ? null : SwitchVocabulary.parseContacts(contacts))
                .function(function)
                .termination(termination)
                .size(size == null ? null : SwitchVocabulary.parseSize(size))
                .holeDiameter(hole == null ? null : SwitchVocabulary.bare(hole))
                .positions(positions == null ? null : Integer.valueOf(positions.toString()))
                .illuminated(illuminated == null ? null : illuminated.equals("yes"))
                .illuminationColour(ctx.read(PartAttribute.ILLUMINATION_COLOUR, String.class))
                .orientation(ctx.read(PartAttribute.SWITCH_ORIENTATION, String.class))
                .build();
        return sw.isEmpty() ? null : sw;
    }

    /**
     * The part's family: the one the category or description names, else the family its values imply (a capacitance
     * makes a capacitor, a resistance a resistor, an inductance an inductor), else the description's guess.
     */
    private static String partFamily(String explicitFamily, SearchExtractionContext ctx,
                                     Recognizers.Analysis description) {
        if (explicitFamily != null) {
            return explicitFamily;
        }
        return ctx.read(PartAttribute.CAPACITANCE, PartFeatures.Measure.class) != null ? "capacitor"
                : ctx.read(PartAttribute.RESISTANCE, PartFeatures.Measure.class) != null ? "resistor"
                : ctx.read(PartAttribute.INDUCTANCE, PartFeatures.Measure.class) != null ? "inductor"
                : description.family();
    }

    /**
     * The part's technology (resistors, capacitors, inductors only): distributor parameters (every name of
     * {@link PartAttribute#TECHNOLOGY} counts, so they are merged here rather than read first-wins),
     * then the description, then the category. A construction ({@code metal strip}, {@code thick film}) from any source
     * wins over the application word {@code current sense} (Mouser "Current Sense Resistors - SMD", TME "Kind of
     * resistor: current shunt, sensing").
     */
    static String technology(Part part, List<String> attributes, String family) {
        if (!TechnologyVocabulary.applies(family)) {
            return null;
        }
        List<String> found = new java.util.ArrayList<>();
        for (String value : attributes) {
            String t = TechnologyVocabulary.ofAttribute(value, family);
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
    private static final Pattern BODY = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s?(?:mm)?\\s?[x×*]\\s?(\\d+(?:[.,]\\d+)?)");

    /**
     * The text that names a semiconductor's type: its type attributes ({@link PartAttribute#SEMICONDUCTOR_TYPE}), the
     * category and the description.
     */
    private static String typeText(Part part, List<String> typeAttributes) {
        StringBuilder out = new StringBuilder();
        for (String v : typeAttributes) {
            out.append(v).append(" ; ");
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
     * a body attribute ({@link PartAttribute#CRYSTAL_BODY}), else the description; null when none gives a known size.
     */
    private static String crystalSize(Part part, List<String> bodyAttributes) {
        for (String body : bodyAttributes) {
            String size = crystalSize(body);
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
    private static ParsedQuery.Connector connector(Part part, SearchExtractionContext ctx, String family,
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
        boolean hasAttributes = ctx.part().attributes().keySet().stream()
                .anyMatch(k -> CONNECTOR_ATTRIBUTE.matcher(k).find());
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

        // attributes (the declared sources of PartAttribute)
        String attrType = ctx.read(PartAttribute.CONNECTOR_TYPE, String.class);
        String attrGender = ctx.read(PartAttribute.GENDER, String.class);
        PartAttribute.Reading positionsRead = ctx.reading(PartAttribute.POSITIONS);
        Integer positions = positionsRead == null ? null : (Integer) positionsRead.value();
        // a USB part's count stated by a positions attribute (not by the pinout layout) is the count as reported
        boolean positionsFromAttributes = positionsRead != null && positionsRead.logic() instanceof FirstInteger;
        Integer rows = ctx.read(PartAttribute.ROWS, Integer.class);
        Double pitch = ctx.read(PartAttribute.PITCH, Double.class);
        String orientation = ctx.read(PartAttribute.ORIENTATION, String.class);
        String series = ctx.read(PartAttribute.SERIES, String.class);

        ParsedQuery.Connector d = description.connector();
        ParsedQuery.Connector c = category.connector();
        String type = firstSpecific(attrType, descriptionType, categoryType);
        String gender = attrGender != null ? attrGender
                : GenderWord.explicitGender(descriptionText.toString()) != null
                ? GenderWord.explicitGender(descriptionText.toString())
                : GenderWord.explicitGender(categoryText) != null ? GenderWord.explicitGender(categoryText)
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
            return usbConnector(part, ctx, finalType, gender, positions, positionsFromAttributes, orientation, d, c,
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
    private static ParsedQuery.Connector usbConnector(Part part, SearchExtractionContext ctx, String type, String gender,
                                                      Integer positions, boolean positionsFromAttributes,
                                                      String orientation, ParsedQuery.Connector description,
                                                      ParsedQuery.Connector category,
                                                      UsbVocabulary.Analysis descriptionUsb) {
        StringBuilder attrText = new StringBuilder();
        for (String v : ctx.texts(PartAttribute.USB_DETAILS)) {
            attrText.append(v).append(" ; ");
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
        for (String v : ctx.texts(PartAttribute.USB_RATE)) {
            if (rate == null) {
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

    /**
     * The imperial chip code a chip resistor/capacitor part number states, or null ({@link PartNumberPackage}, the last
     * source of {@link PartAttribute#PACKAGE}).
     */
    static String packageFromPartNumber(String mpn, String manufacturer) {
        return PartNumberPackage.packageFromPartNumber(mpn, manufacturer);
    }

    private static String mountingFromPackage(String packageName) {
        if (packageName == null) {
            return null;
        }
        String led = LedVocabulary.mounting(packageName);
        if (led != null) {
            return led;   // a 5mm lamp is THT, a 5050 LED SMD
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

    private static void putIfNotNull(Map<String, String> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
