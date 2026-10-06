package ro.alacrity.kina.search;

import lombok.experimental.UtilityClass;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Descriptive details of passive parts that {@link ParametricExtractor} reports as canonical attributes but the
 * ranker does not score (DESIGN.md 3.4): the element count of arrays and networks ({@code Elements}), a capacitor's
 * ripple current ({@code RippleCurrent}) and ESR or impedance, the body dimensions ({@code Dimensions}), the
 * qualification ({@code Qualification}, AEC-Q200...) and free-text features ({@code Features}: low ESR, shielded...).
 * Stateless; every method reads the part's description, package field, category and distributor attributes (names
 * lower-cased) and gives the same answer for a part that was already enriched.
 */
@UtilityClass
class PassiveDetails {

    /** Families whose arrays and networks are told apart from single elements. */
    static final Set<String> ARRAY_FAMILIES = Set.of("resistor", "capacitor", "ferrite");
    /** Families that get dimensions, qualification and features. */
    static final Set<String> PASSIVE_FAMILIES = Set.of("resistor", "capacitor", "inductor", "ferrite");

    // ---------------------------------------------------------------- arrays and networks

    private static final List<String> ELEMENT_NAMES = List.of("elements", "number of elements", "number of resistors",
            "number of capacitors", "number of lines", "number of channels", "number of bits");
    private static final Pattern ARRAY_WORD = Pattern.compile("(?i)(?<![\\p{L}\\d])(?:arrays?|networks?)(?![\\p{L}\\d])");
    private static final Pattern COUNT_WORDS = Pattern.compile(
            "(?i)(?<![\\p{L}\\d.])(\\d{1,2})\\s?-?\\s?(?:lines|elements|resistors|capacitors|channels|bits)(?![\\p{L}])");
    /** JLCPCB networks: package {@code 0603x4}, description {@code 0402x8}. */
    private static final Pattern CHIP_TIMES = Pattern.compile(
            "(?i)(?<![\\d])(?:0201|0402|0603|0805|1206)\\s?[x×*]\\s?(\\d{1,2})(?![\\d])");
    private static final Pattern COMMON_MODE = Pattern.compile("(?i)common[- ]mode");
    private static final Pattern DIGITS = Pattern.compile("\\d{1,2}");

    /**
     * The number of elements of an array or network ({@code 4} for a 4-line bead array, a {@code 0603x4} resistor
     * network), {@link ParsedQuery#ANY_ELEMENTS} for an array whose count is not stated, or null for a single element
     * (or a family where arrays are not told apart). Common-mode chokes and filters are never arrays here.
     */
    static Integer elements(Part part, Map<String, String> attrs, String family) {
        if (family == null || !ARRAY_FAMILIES.contains(family)) {
            return null;
        }
        String text = join(part.description(), part.category(), part.packageName());
        if (COMMON_MODE.matcher(text).find()) {
            return null;
        }
        for (String name : ELEMENT_NAMES) {
            String v = attrs.get(name);
            if (v == null) {
                continue;
            }
            if (v.strip().equalsIgnoreCase("array")) {
                return ParsedQuery.ANY_ELEMENTS;
            }
            Matcher m = DIGITS.matcher(v);
            if (m.find()) {
                int n = Integer.parseInt(m.group());
                return n >= 2 ? n : null;
            }
        }
        boolean array = ARRAY_WORD.matcher(text).find();
        for (Map.Entry<String, String> e : attrs.entrySet()) {
            // TME "Kind of ferrite: array", "Type of resistor: network"
            if ((e.getKey().startsWith("kind of") || e.getKey().startsWith("type of"))
                    && ARRAY_WORD.matcher(e.getValue()).find()) {
                array = true;
            }
        }
        Integer count = null;
        Matcher m = COUNT_WORDS.matcher(text);
        if (m.find()) {
            count = Integer.parseInt(m.group(1));
        }
        Matcher chip = CHIP_TIMES.matcher(text);
        if (count == null && chip.find()) {
            count = Integer.parseInt(chip.group(1));
        }
        if (count != null && count >= 2) {
            return count;
        }
        return array ? ParsedQuery.ANY_ELEMENTS : null;
    }

    /** {@code "4"}, or {@code "array"} when the count is not stated. */
    static String elementsDisplay(Integer elements) {
        return elements == null ? null : elements == ParsedQuery.ANY_ELEMENTS ? "array" : elements.toString();
    }

    // ---------------------------------------------------------------- capacitor ripple current, ESR, impedance

    /** A capacitor's ripple current (TME lists it as "Operating current", Mouser as "Ripple Current"). */
    private static final List<String> RIPPLE_NAMES = List.of("ripplecurrent", "ripple current", "rated ripple current",
            "ripple current (max)", "max ripple current", "current - ripple", "ripple current @ high frequency",
            "ripple current @ low frequency", "operating current", "current rating", "rated current", "current");
    private static final List<String> ESR_NAMES = List.of("esr", "esr (equivalent series resistance)",
            "equivalent series resistance", "esr max", "esr (max)", "max esr", "esr max.");
    private static final List<String> CAP_IMPEDANCE_NAMES = List.of("impedance", "impedance max", "max. impedance",
            "impedance (max)", "max impedance");
    private static final Pattern KEY_FREQUENCY = Pattern.compile("(?i)(\\d+(?:[.,]\\d+)?)\\s*([kmg]?)hz");
    /** LCSC "15mΩ@100kHz" in a polymer capacitor description: the ESR (or impedance) at its test frequency. */
    private static final Pattern OHM_AT_FREQUENCY = Pattern.compile(
            "(?i)(\\d+(?:\\.\\d+)?)\\s*(m|k)?\\s*(?:Ω|ohms?)\\s*@\\s*(\\d+(?:\\.\\d+)?)\\s*(k|m)?hz");

    /** Display form of a capacitor's ripple current ({@code "240mA"}), null when not stated. */
    static String rippleCurrent(Map<String, String> attrs, String family) {
        for (String name : RIPPLE_NAMES) {
            Recognizers.Value v = Recognizers.firstValue(attrs.get(name), ParsedQuery.CURRENT, family);
            if (v != null) {
                return v.display();
            }
        }
        for (Map.Entry<String, String> e : attrs.entrySet()) {
            if (e.getKey().contains("ripple")) {
                Recognizers.Value v = Recognizers.firstValue(e.getValue(), ParsedQuery.CURRENT, family);
                if (v != null) {
                    return v.display();
                }
            }
        }
        return null;
    }

    /**
     * A capacitor's ESR ({@code esr = true}) or impedance in ohm with the test frequency when stated
     * ({@code "15mohm @100kHz"}), null when not stated. The description is read for {@code 15mΩ@100kHz} (LCSC polymer
     * and electrolytic capacitors): ESR when the text says ESR or the capacitor is a polymer one, else impedance.
     */
    static String capacitorResistance(Part part, Map<String, String> attrs, String technology, boolean esr) {
        for (String name : esr ? ESR_NAMES : CAP_IMPEDANCE_NAMES) {
            String v = attrs.get(name);
            String display = ohms(v, frequency(name, v));
            if (display != null) {
                return display;
            }
        }
        for (Map.Entry<String, String> e : attrs.entrySet()) {
            String key = e.getKey();
            boolean match = esr ? key.startsWith("esr ") : key.startsWith("impedance ") && !key.contains("tolerance");
            if (match) {
                String display = ohms(e.getValue(), frequency(key, e.getValue()));
                if (display != null) {
                    return display;
                }
            }
        }
        String description = part.description() == null ? "" : part.description();
        Matcher m = OHM_AT_FREQUENCY.matcher(description);
        if (m.find()) {
            boolean saysEsr = description.toLowerCase(Locale.ROOT).contains("esr")
                    || technology != null && technology.contains("polymer");
            if (saysEsr == esr) {
                double ohm = Double.parseDouble(m.group(1)) * prefix(m.group(2));
                double hz = Double.parseDouble(m.group(3)) * prefix(m.group(4) == null ? null
                        : m.group(4).equalsIgnoreCase("m") ? "M" : m.group(4));
                return Recognizers.of(ParsedQuery.IMPEDANCE, ohm, hz).display();
            }
        }
        return null;
    }

    private static String ohms(String value, Double frequency) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Recognizers.Value v = Recognizers.firstValue(value, ParsedQuery.RESISTANCE, null);
        if (v == null) {
            return null;
        }
        return Recognizers.of(ParsedQuery.IMPEDANCE, v.value(), frequency).display();
    }

    /** The test frequency in Hz from the attribute name ("ESR at 100kHz") or value ("16mohm @100kHz"). */
    private static Double frequency(String name, String value) {
        for (String text : new String[]{name, value}) {
            if (text == null) {
                continue;
            }
            Matcher m = KEY_FREQUENCY.matcher(text);
            if (m.find()) {
                String p = m.group(2).toLowerCase(Locale.ROOT);
                return Double.parseDouble(m.group(1).replace(',', '.')) * switch (p) {
                    case "k" -> 1e3;
                    case "m" -> 1e6;
                    case "g" -> 1e9;
                    default -> 1.0;
                };
            }
        }
        return null;
    }

    private static double prefix(String p) {
        if (p == null) {
            return 1.0;
        }
        return switch (p) {
            case "m" -> 1e-3;
            case "k", "K" -> 1e3;
            case "M" -> 1e6;
            default -> 1.0;
        };
    }

    // ---------------------------------------------------------------- dimensions

    private static final List<String> DIMENSION_NAMES = List.of("dimensions", "body dimensions", "size / dimension",
            "size", "case size", "body size", "dimension");
    /**
     * {@code Ø6.3x5.8mm}, {@code D6.3xL5.8mm}, {@code 8.8x8.4x3.8mm}, {@code D6.3 x 5.8mm}, {@code 3.2 x 1.6mm}: a
     * diameter (Ø/Φ/D) or two or three lengths, ending in mm.
     */
    private static final Pattern DIMENSIONS = Pattern.compile(
            "(?<![\\p{L}\\d.])([ØΦφ]|D)?\\s?(\\d+(?:[.,]\\d+)?)\\s?(?:mm)?\\s?[x×*]\\s?[LH]?\\s?(\\d+(?:[.,]\\d+)?)"
                    + "(?:\\s?(?:mm)?\\s?[x×*]\\s?[LH]?\\s?(\\d+(?:[.,]\\d+)?))?\\s?mm(?![\\p{L}])");
    private static final List<String> DIAMETER_NAMES = List.of("diameter", "body diameter", "case diameter");
    private static final List<String> HEIGHT_NAMES = List.of("height", "body height", "height - seated (max)",
            "case height", "length");
    private static final Pattern MM = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s?mm");

    /**
     * Body dimensions: {@code D6.3 x 5.8mm} for a can (diameter x height), {@code 8.8 x 8.4 x 3.8mm} or
     * {@code 3.2 x 1.6mm} otherwise; from a dimensions attribute, the diameter and height attributes, the package field
     * (LCSC {@code SMD,D6.3xL5.8mm}) or the description (TME {@code Ø6.3x5.8mm}). Null when none states them.
     */
    static String dimensions(Part part, Map<String, String> attrs, String family) {
        if (family == null || !PASSIVE_FAMILIES.contains(family)) {
            return null;
        }
        for (String name : DIMENSION_NAMES) {
            String d = parseDimensions(attrs.get(name));
            if (d != null) {
                return d;
            }
        }
        String diameter = firstMm(attrs, DIAMETER_NAMES);
        String height = firstMm(attrs, HEIGHT_NAMES);
        if (diameter != null && height != null) {
            return "D" + diameter + " x " + height + "mm";
        }
        String fromPackage = parseDimensions(part.packageName());
        return fromPackage != null ? fromPackage : parseDimensions(part.description());
    }

    /** True for the can form {@code D6.3 x 5.8mm}. */
    static boolean isCan(String dimensions) {
        return dimensions != null && dimensions.startsWith("D");
    }

    static String parseDimensions(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m = DIMENSIONS.matcher(text);
        if (!m.find()) {
            return null;
        }
        String a = number(m.group(2));
        String b = number(m.group(3));
        String c = m.group(4) == null ? null : number(m.group(4));
        if (m.group(1) != null && c == null) {
            return "D" + a + " x " + b + "mm";
        }
        return c == null ? a + " x " + b + "mm" : a + " x " + b + " x " + c + "mm";
    }

    private static String firstMm(Map<String, String> attrs, List<String> names) {
        for (String name : names) {
            String v = attrs.get(name);
            if (v != null) {
                Matcher m = MM.matcher(v);
                if (m.find()) {
                    return number(m.group(1));
                }
            }
        }
        return null;
    }

    private static String number(String raw) {
        String n = raw.replace(',', '.');
        return n.contains(".") ? n.replaceAll("0+$", "").replaceAll("\\.$", "") : n;
    }

    // ---------------------------------------------------------------- qualification and features

    private static final Pattern AEC = Pattern.compile("(?i)AEC[- ]?Q\\s?(100|101|102|103|104|200)");

    /** {@code AEC-Q200} (several joined with ", ") from the description or any attribute, else null. */
    static String qualification(Part part, Map<String, String> attrs) {
        Set<String> found = new LinkedHashSet<>();
        List<String> texts = new ArrayList<>();
        texts.add(part.description());
        texts.addAll(attrs.values());
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            Matcher m = AEC.matcher(text);
            while (m.find()) {
                found.add("AEC-Q" + m.group(1));
            }
        }
        return found.isEmpty() ? null : String.join(", ", found);
    }

    private record Feature(String name, Pattern pattern) {
    }

    /** Free-text features of passive parts, in output order. */
    private static final List<Feature> FEATURES = List.of(
            new Feature("low ESR", Pattern.compile("(?i)\\blow[- ]?ESR\\b")),
            new Feature("low impedance", Pattern.compile("(?i)\\blow[- ]impedance\\b")),
            new Feature("high ripple current", Pattern.compile("(?i)\\bhigh[- ]ripple\\b")),
            new Feature("long life", Pattern.compile("(?i)\\blong[- ]life\\b")),
            new Feature("low DCR", Pattern.compile("(?i)\\blow[- ]DCR\\b")),
            new Feature("high current", Pattern.compile("(?i)\\bhigh[- ]current\\b")),
            new Feature("unshielded", Pattern.compile("(?i)\\b(?:un|non)[- ]?shielded\\b")),
            new Feature("semi-shielded", Pattern.compile("(?i)\\bsemi[- ]?shielded\\b")),
            new Feature("shielded", Pattern.compile("(?i)(?<![\\p{L}-])shielded\\b")),
            new Feature("anti-sulfur", Pattern.compile("(?i)\\banti[- ]?sulf")),
            new Feature("soft termination", Pattern.compile("(?i)\\b(?:soft|flexible|flex)[- ]termination\\b")),
            new Feature("low profile", Pattern.compile("(?i)\\blow[- ]profile\\b")));

    /** Comma-separated features ({@code "low ESR"}, {@code "shielded"}...) of a passive part, else null. */
    static String features(Part part, Map<String, String> attrs, String family) {
        if (family == null || !PASSIVE_FAMILIES.contains(family)) {
            return null;
        }
        StringBuilder text = new StringBuilder(part.description() == null ? "" : part.description());
        attrs.values().forEach(v -> text.append(" ; ").append(v));
        Set<String> found = new LinkedHashSet<>();
        for (Feature f : FEATURES) {
            if (f.pattern().matcher(text).find()) {
                found.add(f.name());
            }
        }
        // "unshielded" also contains "shielded" after the prefix; one shielding word only
        if (found.contains("unshielded") || found.contains("semi-shielded")) {
            found.remove("shielded");
        }
        return found.isEmpty() ? null : String.join(", ", found);
    }

    private static String join(String... parts) {
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (p != null) {
                out.append(p).append(" ; ");
            }
        }
        return out.toString();
    }
}
