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
 * {@value #PACKAGE}, {@value #MOUNTING}, {@value #FAMILY}. Values are compact human-readable strings
 * ("10uF", "25V", "10%", "X7R", "0805", "SMD", "capacitor").
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
            "mounting method", "termination style", "montage");

    private static final Set<String> SMD_PACKAGE_PREFIXES = Set.of("SOT", "SOD", "SC-", "SOIC", "SOP", "SSOP",
            "TSSOP", "MSOP", "QFN", "DFN", "LQFP", "TQFP", "QFP", "BGA", "LGA", "WLCSP", "SMA", "SMB", "SMC", "DO-214", "TO-252",
            "TO-263", "DPAK", "D2PAK", "SON", "WSON", "VSON", "TDFN", "UDFN", "XDFN", "USON", "LFCSP", "VQFN", "WQFN",
            "UQFN", "TQFN", "MELF", "MINIMELF", "PLCC");
    private static final Set<String> THT_PACKAGE_PREFIXES = Set.of("DIP", "PDIP", "TO-220", "TO-92", "TO-247", "HC-49",
            "DO-41", "DO-35", "DO-15", "DO-201", "SIP", "ZIP");

    private static final Pattern CATEGORY_SEPARATOR = Pattern.compile("\\s*[/>\\\\]\\s*");

    /** Parsed, comparable features of a part. Values are in SI base units (tolerance: percent). */
    record Features(String family, Map<String, Recognizers.Value> values, String dielectric, String packageName,
                    String mounting, String text) {

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

        StringBuilder text = new StringBuilder();
        for (String s : new String[]{part.manufacturer(), part.manufacturerPartNumber(), part.distributorPartNumber(),
                part.description(), part.category(), part.packageName()}) {
            if (s != null) {
                text.append(s).append(' ');
            }
        }
        part.attributes().values().forEach(v -> text.append(v).append(' '));
        return new Features(family, values, dielectric, packageName, mounting,
                Recognizers.normalizeKey(text.toString()));
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
