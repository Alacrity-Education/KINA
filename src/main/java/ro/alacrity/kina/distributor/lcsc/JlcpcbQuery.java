package ro.alacrity.kina.distributor.lcsc;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns free query text into search terms for the JLCPCB FTS5 trigram table.
 *
 * <p>Normalisation (kept deliberately simple; the JLCPCB descriptions consistently write {@code 10kΩ}, {@code 4.7uF},
 * {@code ±5%} and never "ohm" or "µ"):
 * <ul>
 *   <li>Unicode NFKC, {@code µ}/{@code μ} -&gt; {@code u}, {@code +/-} and {@code ±} prefixes dropped ({@code 5%}).</li>
 *   <li>Ohm spellings become {@code Ω}: {@code 10kohm}, {@code 10k ohm}, {@code 10kΩ}, {@code 10kR} -&gt; {@code 10kΩ};
 *       RKM {@code 10R} -&gt; {@code 10Ω}, {@code 2R2} -&gt; {@code 2.2Ω}. A bare {@code 10k} stays {@code 10k}
 *       (matches {@code 10kΩ} as a substring) because it may also mean 10 kHz etc.</li>
 *   <li>Other RKM forms: {@code 4k7} -&gt; {@code 4.7k}, {@code 4u7} -&gt; {@code 4.7u}, {@code 3V3} -&gt; {@code 3.3V}.</li>
 *   <li>A number followed by a separate unit token is merged: {@code 10 uF} -&gt; {@code 10uF}.</li>
 *   <li>FTS operators ({@code AND OR NOT NEAR}), quotes and grouping characters and a short list of English stop words
 *       are dropped. Plural family words are singularised ({@code capacitors} -&gt; {@code capacitor}); a few
 *       abbreviations map to the database wording ({@code res} -&gt; {@code resistor}, {@code op amp} -&gt; {@code amplifier}).</li>
 * </ul>
 * Each remaining token is classified ({@link Kind}); everything but {@link Kind#KEYWORD} counts as "parametric" for
 * the query relaxation in {@link JlcpcbSqliteSearch}.
 */
public record JlcpcbQuery(List<Term> terms) {

    public enum Kind { VALUE, PACKAGE, DIELECTRIC, FAMILY, KEYWORD }

    /**
     * @param text the normalised token
     * @param kind its classification
     */
    public record Term(String text, Kind kind) {

        public boolean parametric() {
            return kind != Kind.KEYWORD;
        }

        /** Trigram MATCH needs at least 3 characters. */
        public boolean matchable() {
            return text.codePointCount(0, text.length()) >= 3;
        }
    }

    public JlcpcbQuery {
        terms = List.copyOf(terms);
    }

    public boolean isEmpty() {
        return terms.isEmpty();
    }

    /** Parametric terms only (values, packages, dielectrics, family words). */
    public List<Term> parametricTerms() {
        return terms.stream().filter(Term::parametric).toList();
    }

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "not", "near", "for", "with", "without", "of", "in", "on", "to", "at",
            "by", "from", "as", "is", "are", "be", "i", "me", "my", "we", "our", "need", "needs", "want", "looking",
            "find", "search", "some", "any", "please", "least", "most", "max", "min", "minimum", "maximum", "type",
            "part", "parts", "component", "components", "that", "this", "which", "can", "should", "it", "use", "used");

    private static final Map<String, String> SYNONYMS = Map.ofEntries(
            Map.entry("res", "resistor"), Map.entry("resistance", "resistor"),
            Map.entry("cap", "capacitor"), Map.entry("caps", "capacitor"),
            Map.entry("opamp", "amplifier"), Map.entry("opamps", "amplifier"),
            Map.entry("mosfets", "mosfet"), Map.entry("leds", "led"), Map.entry("fets", "fet"),
            Map.entry("ldos", "ldo"), Map.entry("mcus", "mcu"), Map.entry("bjts", "bjt"));

    private static final Set<String> FAMILY = Set.of(
            "capacitor", "mlcc", "resistor", "inductor", "ferrite", "diode", "schottky", "zener", "led", "mosfet", "fet",
            "transistor", "bjt", "npn", "pnp", "ldo", "regulator", "amplifier", "comparator", "mcu", "microcontroller",
            "crystal", "oscillator", "connector", "fuse", "tvs", "esd", "relay", "switch", "thermistor", "varistor",
            "optocoupler", "header", "eeprom", "flash", "bead", "rectifier", "transceiver", "tantalum", "electrolytic");

    private static final Set<String> DIELECTRICS = Set.of(
            "x7r", "x5r", "c0g", "cog", "np0", "npo", "y5v", "x7s", "x6s", "x8r", "x7t", "x5s", "x6t", "z5u");

    private static final Pattern PACKAGE = Pattern.compile(
            "(?i)(01005|0201|0402|0603|0805|1008|1206|1210|1806|1812|2010|2220|2512"
                    + "|(?:SOT|SOD|TO|SOIC|SOP|SO|SSOP|TSSOP|MSOP|ESOP|QFN|DFN|WQFN|VQFN|UQFN|LQFP|TQFP|QFP|BGA|FBGA|LGA"
                    + "|DO|SC|SMA|SMB|SMC|DPAK|D2PAK|DIP|PDIP|SIP|HC|WLCSP|CASE)(?:-?[A-Z0-9]+)*(?:\\([^)]*\\))?)");

    /** Number + optional SI prefix + unit, or a percentage. Pure numbers are not values. */
    private static final Pattern VALUE = Pattern.compile(
            "(?iu)\\d+(?:\\.\\d+)?(?:[pnumkg](?:f|h|v|a|w|hz|Ω)?|f|h|v|a|w|hz|Ω|%)");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern UNIT_ONLY = Pattern.compile("(?iu)[pnumkg]?(?:f|h|v|a|w|hz|Ω|ohms?)|%");
    private static final Pattern OHM = Pattern.compile("(?iu)(\\d+(?:\\.\\d+)?)([kmg]?)(?:ohms?|Ω|r)");
    private static final Pattern RKM_R = Pattern.compile("(?i)(\\d+)r(\\d+)");
    private static final Pattern RKM_PREFIX = Pattern.compile("(\\d+)([pnuμkKmMG])(\\d+)");
    private static final Pattern RKM_V = Pattern.compile("(?i)(\\d+)v(\\d+)");
    private static final Pattern OHM_WORD = Pattern.compile("(?iu)ohms?|Ω");
    private static final String STRIP = "\"'`()[]{}*^:;,+<>=!?";

    public static JlcpcbQuery parse(String query) {
        if (query == null || query.isBlank()) {
            return new JlcpcbQuery(List.of());
        }
        String text = Normalizer.normalize(query, Normalizer.Form.NFKC)
                .replace('µ', 'u').replace('μ', 'u').replace("+/-", "±");
        List<String> raw = new ArrayList<>();
        for (String piece : text.split("[\\s,;|]+")) {
            String token = strip(piece);
            if (!token.isEmpty()) {
                raw.add(token);
            }
        }
        // merge "10 uF" / "10k ohm" / "op amp"
        List<String> merged = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            String token = raw.get(i);
            String next = i + 1 < raw.size() ? raw.get(i + 1) : null;
            if (next != null && NUMBER.matcher(token).matches() && UNIT_ONLY.matcher(next).matches()) {
                merged.add(token + next);
                i++;
            } else if (next != null && OHM_WORD.matcher(next).matches()
                    && token.matches("(?i)\\d+(?:\\.\\d+)?[kmg]?")) {
                merged.add(token + "Ω");
                i++;
            } else if (next != null && token.equalsIgnoreCase("op") && next.toLowerCase(Locale.ROOT).startsWith("amp")) {
                merged.add("opamp");
                i++;
            } else {
                merged.add(token);
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Term> terms = new ArrayList<>();
        for (String token : merged) {
            Term term = classify(token);
            if (term != null && seen.add(term.text().toLowerCase(Locale.ROOT))) {
                terms.add(term);
            }
        }
        return new JlcpcbQuery(terms);
    }

    private static Term classify(String token) {
        String t = token;
        if (t.startsWith("±")) {
            t = t.substring(1);
        }
        if (t.isEmpty()) {
            return null;
        }
        String lower = t.toLowerCase(Locale.ROOT);
        if (STOP_WORDS.contains(lower) || OHM_WORD.matcher(t).matches() || t.equals("-")) {
            return null;
        }
        t = canonicalValue(t);
        if (VALUE.matcher(t).matches()) {
            return new Term(t, Kind.VALUE);
        }
        if (DIELECTRICS.contains(lower)) {
            return new Term(t, Kind.DIELECTRIC);
        }
        if (PACKAGE.matcher(t).matches()) {
            return new Term(t, Kind.PACKAGE);
        }
        String word = SYNONYMS.getOrDefault(lower, lower);
        String singular = word.endsWith("s") && FAMILY.contains(word.substring(0, word.length() - 1))
                ? word.substring(0, word.length() - 1) : word;
        if (FAMILY.contains(singular)) {
            return new Term(singular, Kind.FAMILY);
        }
        return new Term(word.equals(lower) ? t : word, Kind.KEYWORD);
    }

    /** Applies the ohm and RKM rewrites described in the class comment. */
    static String canonicalValue(String token) {
        Matcher m = RKM_R.matcher(token);
        if (m.matches()) {
            return m.group(1) + decimals(m.group(2)) + "Ω";
        }
        m = OHM.matcher(token);
        if (m.matches()) {
            return m.group(1) + prefix(m.group(2)) + "Ω";
        }
        m = RKM_PREFIX.matcher(token);
        if (m.matches()) {
            String p = m.group(2).equals("μ") ? "u" : m.group(2);
            return m.group(1) + decimals(m.group(3)) + prefix(p);
        }
        m = RKM_V.matcher(token);
        if (m.matches()) {
            return m.group(1) + decimals(m.group(2)) + "V";
        }
        return token;
    }

    /** RKM fraction digits: "7" -> ".7", "0" -> "" (so 5V0 becomes 5V). */
    private static String decimals(String digits) {
        return digits.chars().allMatch(c -> c == '0') ? "" : "." + digits;
    }

    private static String prefix(String p) {
        return switch (p) {
            case "K" -> "k";
            case "g" -> "G";
            default -> p;
        };
    }

    private static String strip(String piece) {
        int start = 0;
        int end = piece.length();
        while (start < end && STRIP.indexOf(piece.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && (STRIP.indexOf(piece.charAt(end - 1)) >= 0 || piece.charAt(end - 1) == '.')) {
            end--;
        }
        String token = piece.substring(start, end);
        // a leading '-' would be the FTS "NOT"-like column exclusion in some syntaxes; keep negative numbers
        if (token.startsWith("-") && (token.length() == 1 || !Character.isDigit(token.charAt(1)))) {
            token = token.substring(1);
        }
        return token;
    }
}
