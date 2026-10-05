package ro.alacrity.kina.search;

import org.springframework.stereotype.Component;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;

import java.util.LinkedHashMap;
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
 * <p>Comparable keys: {@value #CAPACITANCE}, {@value #RESISTANCE}, {@value #INDUCTANCE}, {@value #FREQUENCY},
 * {@value #VOLTAGE}, {@value #CURRENT}, {@value #POWER}, {@value #TOLERANCE}, {@value #DIELECTRIC},
 * {@value #PACKAGE}, {@value #MOUNTING}, {@value #FAMILY}; for connectors also {@value #CONNECTOR_TYPE},
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
    public static final String TOLERANCE = "Tolerance";
    public static final String DIELECTRIC = "Dielectric";
    public static final String PACKAGE = "Package";
    public static final String MOUNTING = "Mounting";
    public static final String FAMILY = "Family";
    public static final String CONNECTOR_TYPE = "ConnectorType";
    public static final String GENDER = "Gender";
    public static final String POSITIONS = "Positions";
    public static final String ROWS = "Rows";
    public static final String PITCH = "Pitch";
    public static final String ORIENTATION = "Orientation";
    public static final String SERIES = "Series";

    /** Comparable key per {@link ParsedQuery} value kind, in output order. */
    private static final Map<String, String> KIND_KEYS = orderedKindKeys();

    private static Map<String, String> orderedKindKeys() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(ParsedQuery.CAPACITANCE, CAPACITANCE);
        m.put(ParsedQuery.RESISTANCE, RESISTANCE);
        m.put(ParsedQuery.INDUCTANCE, INDUCTANCE);
        m.put(ParsedQuery.FREQUENCY, FREQUENCY);
        m.put(ParsedQuery.VOLTAGE, VOLTAGE);
        m.put(ParsedQuery.CURRENT, CURRENT);
        m.put(ParsedQuery.POWER, POWER);
        m.put(ParsedQuery.TOLERANCE, TOLERANCE);
        return m;
    }

    // ---------------------------------------------------------------- distributor attribute names (lower-case)

    private static final List<String> CAPACITANCE_NAMES = List.of("capacitance", "capacitance value", "nominal capacitance");
    private static final List<String> RESISTANCE_NAMES = List.of("resistance", "resistance value", "nominal resistance");
    private static final List<String> INDUCTANCE_NAMES = List.of("inductance", "nominal inductance");
    private static final List<String> FREQUENCY_NAMES = List.of("frequency", "nominal frequency", "oscillation frequency");
    /** Preferred voltage ratings (Mouser "Voltage Rating DC", TME "Operating voltage", LCSC "Voltage Rated"). */
    private static final List<String> VOLTAGE_NAMES = List.of("voltage rating dc", "voltage rating - dc", "voltage rating",
            "voltage rated", "rated voltage", "voltage - rated", "operating voltage", "dc voltage rating", "voltage",
            "output voltage", "voltage - output", "voltage - output (min/fixed)", "vr - reverse voltage",
            "reverse voltage (vr)", "vds - drain-source breakdown voltage", "drain source voltage (vdss)",
            "drain to source voltage (vdss)", "vz - zener voltage", "voltage - zener (nom) (vz)",
            "vrwm - reverse standoff voltage", "reverse stand-off voltage (vrwm)", "voltage - reverse standoff (typ)");
    private static final Pattern VOLTAGE_EXCLUDED = Pattern.compile(
            "forward|clamp|breakdown|input|supply|isolation|threshold|gate|ripple|dropout|temperature|coefficient|offset");
    private static final List<String> CURRENT_NAMES = List.of("current rating", "rated current", "current", "current - output",
            "output current", "id - continuous drain current", "continuous drain current (id)", "if - forward current",
            "io - average rectified current", "current - average rectified (io)", "average rectified current (io)",
            "ic - continuous collector current", "collector current (ic)", "current rating (amps)");
    private static final Pattern CURRENT_EXCLUDED = Pattern.compile(
            "leakage|reverse current|surge|quiescent|supply|peak|bias|offset|standby|pulse|trip|saturation");
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

    /** Parsed, comparable features of a part. Values are in SI base units (tolerance: percent). */
    record Features(String family, Map<String, Recognizers.Value> values, String dielectric, String packageName,
                    String mounting, String text, ParsedQuery.Connector connector) {

        Features(String family, Map<String, Recognizers.Value> values, String dielectric, String packageName,
                 String mounting, String text) {
            this(family, values, dielectric, packageName, mounting, text, null);
        }

        Double value(String kind) {
            Recognizers.Value v = values.get(kind);
            return v == null ? null : v.value();
        }
    }

    /**
     * Comparable attributes ({@value #CAPACITANCE}, {@value #VOLTAGE}, {@value #PACKAGE}...) known for the part,
     * insertion-ordered; keys are omitted when unknown.
     */
    public Map<String, String> extract(Part part) {
        Features f = features(part);
        Map<String, String> out = new LinkedHashMap<>();
        KIND_KEYS.forEach((kind, key) -> {
            Recognizers.Value v = f.values().get(kind);
            if (v != null) {
                out.put(key, v.display());
            }
        });
        putIfNotNull(out, DIELECTRIC, f.dielectric());
        putIfNotNull(out, PACKAGE, f.packageName());
        putIfNotNull(out, MOUNTING, f.mounting());
        putIfNotNull(out, FAMILY, f.family());
        ParsedQuery.Connector c = f.connector();
        if (c != null) {
            putIfNotNull(out, CONNECTOR_TYPE, c.type());
            putIfNotNull(out, SERIES, c.series());
            putIfNotNull(out, GENDER, c.gender());
            putIfNotNull(out, POSITIONS, c.positions() == null ? null : c.positions().toString());
            putIfNotNull(out, ROWS, c.rows() == null ? null : c.rows().toString());
            putIfNotNull(out, PITCH, c.pitchDisplay());
            putIfNotNull(out, ORIENTATION, c.orientation());
        }
        return out;
    }

    /**
     * Copy of the part whose {@code attributes} additionally contain the comparable keys of {@link #extract(Part)}.
     * Existing distributor attributes are kept unchanged (also when they use the same key name).
     */
    public Part enrich(Part part) {
        Map<String, String> attributes = new LinkedHashMap<>(part.attributes());
        extract(part).forEach(attributes::putIfAbsent);
        return new Part(part.distributor(), part.distributorPartNumber(), part.manufacturer(),
                part.manufacturerPartNumber(), part.description(), part.category(), part.packageName(), part.stock(),
                part.minimumOrderQuantity(), part.orderMultiple(), part.prices(), part.datasheetUrl(), part.photoUrl(),
                part.productUrl(), attributes, part.extra(), part.fetchedAt());
    }

    /** Typed features used by {@link DeterministicRanker}. */
    Features features(Part part) {
        Recognizers.Analysis description = Recognizers.analyze(part.description());
        Recognizers.Analysis category = categoryAnalysis(part.category());

        Map<String, String> attrs = new LinkedHashMap<>();
        part.attributes().forEach((k, v) -> {
            if (k != null && v != null && !v.isBlank()) {
                attrs.putIfAbsent(k.trim().toLowerCase(Locale.ROOT), v);
            }
        });

        String explicitFamily = category.familyExplicit() ? category.family()
                : description.familyExplicit() ? description.family() : null;
        final String valueFamily = explicitFamily;

        Map<String, Recognizers.Value> values = new LinkedHashMap<>();
        attributeValue(attrs, CAPACITANCE_NAMES, ParsedQuery.CAPACITANCE, valueFamily, values);
        attributeValue(attrs, RESISTANCE_NAMES, ParsedQuery.RESISTANCE, valueFamily, values);
        attributeValue(attrs, INDUCTANCE_NAMES, ParsedQuery.INDUCTANCE, valueFamily, values);
        attributeValue(attrs, FREQUENCY_NAMES, ParsedQuery.FREQUENCY, valueFamily, values);
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
        description.values().forEach(values::putIfAbsent);

        String family = explicitFamily;
        if (family == null) {
            family = values.containsKey(ParsedQuery.CAPACITANCE) ? "capacitor"
                    : values.containsKey(ParsedQuery.RESISTANCE) ? "resistor"
                    : values.containsKey(ParsedQuery.INDUCTANCE) ? "inductor"
                    : description.family();
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
        if (mounting == null) {
            mounting = mountingFromPackage(packageName);
        }

        ParsedQuery.Connector connector = connector(part, attrs, family, packageName);
        if (connector != null) {
            family = "connector";
            if (mounting == null) {
                mounting = connectorMounting(part, attrs);
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
        return new Features(family, values, dielectric, packageName, mounting,
                Recognizers.normalizeKey(text.toString()), connector);
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
        return new ParsedQuery.Connector(type == null ? ParsedQuery.CONNECTOR : type, series, gender, positions, rows,
                pitch, false, orientation);
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
        return part.packageName() == null || part.packageName().isBlank() ? null : part.packageName().trim();
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
