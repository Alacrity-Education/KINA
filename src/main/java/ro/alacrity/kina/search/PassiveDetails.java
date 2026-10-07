package ro.alacrity.kina.search;

import lombok.experimental.UtilityClass;
import ro.alacrity.kina.domain.ComponentFamily;
import ro.alacrity.kina.domain.ComponentFamily.Trait;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PartAttribute;
import ro.alacrity.kina.domain.extract.Dimensions;

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

    // ---------------------------------------------------------------- arrays and networks (ElementsCount)

    /** {@code "4"}, or {@code "array"} when the count is not stated. */
    static String elementsDisplay(Integer elements) {
        return elements == null ? null : elements == ParsedQuery.ANY_ELEMENTS ? "array" : elements.toString();
    }

    // ---------------------------------------------------------------- capacitor ripple current, ESR, impedance

    /** LCSC "15mΩ@100kHz" in a polymer capacitor description: the ESR (or impedance) at its test frequency. */
    private static final Pattern OHM_AT_FREQUENCY = Pattern.compile(
            "(?i)(\\d+(?:\\.\\d+)?)\\s*(m|k)?\\s*(?:Ω|ohms?)\\s*@\\s*(\\d+(?:\\.\\d+)?)\\s*(k|m)?hz");

    /**
     * A capacitor's ESR ({@code esr = true}) or impedance in ohm with the test frequency when stated
     * ({@code "15mohm @100kHz"}), null when not stated: the declared attributes ({@link PartAttribute#ESR},
     * {@link PartAttribute#CAPACITOR_IMPEDANCE}), else the description's {@code 15mΩ@100kHz} (LCSC polymer and
     * electrolytic capacitors): ESR when the text says ESR or the capacitor is a polymer one, else impedance. The
     * description is read here, not as a declared source, because it needs the technology.
     */
    static String capacitorResistance(Part part, SearchExtractionContext ctx, String technology, boolean esr) {
        String stated = ctx.read(esr ? PartAttribute.ESR : PartAttribute.CAPACITOR_IMPEDANCE, String.class);
        if (stated != null) {
            return stated;
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

    /** True for the can form {@code D6.3 x 5.8mm}. */
    static boolean isCan(String dimensions) {
        return dimensions != null && dimensions.startsWith("D");
    }

    private static final Pattern CAN = Pattern.compile("^D(\\d+(?:\\.\\d+)?) x (\\d+(?:\\.\\d+)?)mm$");

    /**
     * The diameter and length in millimetres of a can size ({@code D6.3 x 5.8mm}, also as written: {@code Ø6.3x5.8mm},
     * {@code D6.3xL5.8mm}), or null when the text is no can size.
     */
    static double[] can(String text) {
        String d = parseDimensions(text);
        if (d == null || !isCan(d)) {
            return null;
        }
        Matcher m = CAN.matcher(d);
        return m.matches() ? new double[] {Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))} : null;
    }

    /** The normalised dimensions a text states ({@link Dimensions#parse}), null when it states none. */
    static String parseDimensions(String text) {
        return Dimensions.parse(text);
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
        if (!ComponentFamily.has(family, Trait.PASSIVE)) {
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
}
