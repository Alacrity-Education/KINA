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
 * Connector wording (DESIGN.md 9.3): a double-quoted phrase is one term ({@code "Female Header"}); quoted connector
 * category phrases ({@code "Female Header"}, {@code "Pin Header"}, {@code "IC Socket"}, {@code "Terminal Block"}...)
 * and the unquoted words {@code female header(s)} / {@code pin header(s)} become {@link Kind#CATEGORY} terms matched
 * as a {@code "Second Category"} column filter; mounting synonyms map to the database vocabulary ({@code THT},
 * {@code through hole}, {@code PTH} -&gt; {@code ("Through Hole" OR "Plugin" OR "THT")}; {@code SMD}, {@code SMT},
 * {@code surface mount} -&gt; {@code ("SMD" OR "SMT" OR "Surface Mount")}), orientation words ({@code right angle},
 * {@code 90°}, {@code 90 degree}, {@code angled} -&gt; {@code "Right Angle"}; {@code vertical}, {@code straight}),
 * positions ({@code 1x6}, {@code 2*3P} -&gt; {@code 1x6P}/{@code 2x3P}; {@code 6P}, {@code 6 pin}, {@code 6-position}
 * -&gt; {@code 6P}) and pitches ({@code 2.54mm}).
 *
 * <p>Each remaining token is classified ({@link Kind}); everything but {@link Kind#KEYWORD} counts as "parametric" for
 * the query relaxation in {@link JlcpcbSqliteSearch}.
 */
public record JlcpcbQuery(List<Term> terms) {

    public enum Kind {
        VALUE, PACKAGE, DIELECTRIC, FAMILY, KEYWORD,
        /** A JLCPCB "Second Category" phrase, matched as a column filter. */
        CATEGORY,
        /** Connector positions in JLCPCB wording ({@code 1x6P}, {@code 6P}), checked like a value. */
        POSITIONS,
        /** A pitch / length in millimetres ({@code 2.54mm}), checked like a value. */
        PITCH,
        /** Connector orientation ({@code "Right Angle"}). */
        ORIENTATION,
        /** Mounting ({@code Through Hole} / {@code Surface Mount}). */
        MOUNTING
    }

    /**
     * @param text         the normalised token (for display, logging and LIKE matching)
     * @param kind         its classification
     * @param alternatives MATCH alternatives OR-ed together (empty: {@code text} itself)
     * @param column       column filter for the MATCH ({@code "Second Category"}), or null for all indexed columns
     */
    public record Term(String text, Kind kind, List<String> alternatives, String column) {

        public Term {
            alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        }

        public Term(String text, Kind kind) {
            this(text, kind, List.of(), null);
        }

        public boolean parametric() {
            return kind != Kind.KEYWORD;
        }

        /** The phrases to MATCH: the alternatives, or the text itself. */
        public List<String> phrases() {
            return alternatives.isEmpty() ? List.of(text) : alternatives;
        }

        /** Trigram MATCH needs at least 3 characters (in every alternative). */
        public boolean matchable() {
            return phrases().stream().allMatch(p -> p.codePointCount(0, p.length()) >= 3);
        }

        /** Checked with the value-boundary function ({@code 6P} must not match {@code 16P}). */
        public boolean boundaryChecked() {
            return kind == Kind.VALUE || kind == Kind.POSITIONS || kind == Kind.PITCH;
        }
    }

    /** Known JLCPCB connector category phrases (lower-case key) and the "Second Category" alternatives they match. */
    static final Map<String, List<String>> CATEGORIES = Map.ofEntries(
            Map.entry("female header", List.of("Female Header")),
            Map.entry("female headers", List.of("Female Header")),
            Map.entry("pin header", List.of("Pin Header")),
            Map.entry("pin headers", List.of("Pin Header")),
            Map.entry("header", List.of("Header")),
            Map.entry("ic socket", List.of("IC Socket", "Transistor Socket")),
            Map.entry("ic sockets", List.of("IC Socket", "Transistor Socket")),
            Map.entry("terminal block", List.of("Terminal Block", "Screw Terminal")),
            Map.entry("screw terminal", List.of("Terminal Block", "Screw Terminal")),
            Map.entry("wire to board", List.of("Wire To Board")),
            Map.entry("usb connectors", List.of("USB Connector")),
            Map.entry("idc connectors", List.of("IDC Connector")),
            Map.entry("fpc", List.of("FPC")),
            Map.entry("d-sub", List.of("D-Sub")),
            Map.entry("dc power", List.of("DC Power")));
    static final String CATEGORY_COLUMN = "Second Category";

    private static final List<String> THT = List.of("Through Hole", "Plugin", "THT");
    private static final List<String> SMD = List.of("SMD", "SMT", "Surface Mount");
    private static final List<String> RIGHT_ANGLE = List.of("Right Angle");
    private static final List<String> VERTICAL = List.of("Vertical", "Straight");
    private static final Set<String> THT_WORDS = Set.of("tht", "pth", "through-hole", "through hole", "thru-hole",
            "thru hole", "plugin");
    private static final Set<String> SMD_WORDS = Set.of("smd", "smt", "surface mount", "surface-mount");
    private static final Set<String> RIGHT_ANGLE_WORDS = Set.of("right angle", "right-angle", "right angled",
            "rightangle", "90°", "90deg", "90 degree", "90 degrees", "90 deg", "angled", "horizontal", "90*");
    private static final Set<String> VERTICAL_WORDS = Set.of("vertical", "straight", "180°", "180 degree");
    private static final Pattern GRID = Pattern.compile("(?i)(\\d{1,2})[x×*](\\d{1,3})p?");
    /** {@code 6P} (upper-case: a lower-case {@code 22p} stays a capacitance), {@code 6pin}, {@code 6-pos}, {@code 6way}. */
    private static final Pattern POSITIONS = Pattern.compile("(\\d{1,3})-?(?:P|(?i:pins?|pos|positions?|ways?))");
    private static final Pattern PITCH = Pattern.compile("(?i)\\d{1,2}(?:\\.\\d{1,2})?mm");
    private static final Pattern POSITION_WORD = Pattern.compile("(?i)pins?|pos|positions?|ways?|circuits?|contacts?");
    private static final Pattern QUOTED = Pattern.compile("(?<=^|\\s)\"([^\"]+)\"(?=\\s|$)");

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
            "part", "parts", "component", "components", "that", "this", "which", "can", "should", "it", "use", "used",
            "style", "dupont", "degree", "degrees", "deg", "pins", "position", "positions");

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
        // double-quoted phrases at token boundaries are single terms ("Female Header", "Right Angle")
        List<String> raw = new ArrayList<>();
        Matcher quoted = QUOTED.matcher(text);
        int last = 0;
        while (quoted.find()) {
            split(text.substring(last, quoted.start()), raw);
            String phrase = quoted.group(1).strip().replaceAll("\\s+", " ");
            if (!phrase.isEmpty()) {
                raw.add(phrase.contains(" ") ? "\u0000" + phrase : strip(phrase));
            }
            last = quoted.end();
        }
        split(text.substring(last), raw);
        // merge "10 uF" / "10k ohm" / "op amp" / "right angle" / "90 degree" / "6 pin" / "female header"
        List<String> merged = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            String token = raw.get(i);
            String next = i + 1 < raw.size() ? raw.get(i + 1) : null;
            String lower = token.toLowerCase(Locale.ROOT);
            String nextLower = next == null ? "" : next.toLowerCase(Locale.ROOT);
            if (next != null && NUMBER.matcher(token).matches() && UNIT_ONLY.matcher(next).matches()) {
                merged.add(token + next);
                i++;
            } else if (next != null && OHM_WORD.matcher(next).matches()
                    && token.matches("(?i)\\d+(?:\\.\\d+)?[kmg]?")) {
                merged.add(token + "Ω");
                i++;
            } else if (next != null && lower.equals("op") && nextLower.startsWith("amp")) {
                merged.add("opamp");
                i++;
            } else if (lower.equals("female") && nextLower.equals("pin") && i + 2 < raw.size()
                    && raw.get(i + 2).toLowerCase(Locale.ROOT).matches("headers?")) {
                merged.add("\u0000female header");   // "female pin header" is a female header
                i += 2;
            } else if (next != null && NUMBER.matcher(token).matches() && nextLower.equals("mm")) {
                merged.add(token + "mm");
                i++;
            } else if (next != null && token.matches("\\d{1,3}") && POSITION_WORD.matcher(next).matches()) {
                merged.add(token + "P");
                i++;
            } else if (next != null && (lower.equals("90") || lower.equals("180"))
                    && nextLower.matches("°|deg|degrees?")) {
                merged.add("\u0000" + lower + " degree");
                i++;
            } else if (next != null && (lower.equals("right") && nextLower.startsWith("angle")
                    || lower.equals("through") && nextLower.equals("hole")
                    || lower.equals("surface") && nextLower.startsWith("mount")
                    || (lower.equals("female") || lower.equals("pin")) && nextLower.matches("headers?")
                    || lower.equals("terminal") && nextLower.matches("blocks?")
                    || lower.equals("screw") && nextLower.matches("terminals?")
                    || lower.equals("ic") && nextLower.matches("sockets?"))) {
                merged.add("\u0000" + token + " " + next);
                i++;
            } else {
                merged.add(token);
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Term> terms = new ArrayList<>();
        for (String token : merged) {
            Term term = token.startsWith("\u0000") ? classifyPhrase(token.substring(1)) : classify(token);
            if (term != null && seen.add(term.kind() + ":" + term.text().toLowerCase(Locale.ROOT))) {
                terms.add(term);
            }
        }
        // JLCPCB never writes "Through Hole" next to "Right Angle" (right-angle THT headers only say "Right Angle")
        boolean rightAngle = terms.stream().anyMatch(t -> t.kind() == Kind.ORIENTATION && t.text().equals("Right Angle"));
        if (rightAngle) {
            terms.removeIf(t -> t.kind() == Kind.MOUNTING && t.alternatives().equals(THT));
        }
        return new JlcpcbQuery(terms);
    }

    private static void split(String text, List<String> out) {
        for (String piece : text.split("[\\s,;|]+")) {
            String token = strip(piece);
            if (!token.isEmpty()) {
                out.add(token);
            }
        }
    }

    /** A multi-word phrase: a category, mounting or orientation phrase, else a keyword phrase. */
    private static Term classifyPhrase(String phrase) {
        String lower = phrase.toLowerCase(Locale.ROOT);
        List<String> category = CATEGORIES.get(lower);
        if (category != null) {
            return new Term(category.getFirst(), Kind.CATEGORY, category, CATEGORY_COLUMN);
        }
        Term connector = connectorTerm(lower);
        if (connector != null) {
            return connector;
        }
        String cleaned = phrase.replace("\"", "");
        return cleaned.isBlank() ? null : new Term(cleaned, Kind.KEYWORD);
    }

    /** Mounting / orientation synonyms in the database vocabulary, or null. */
    private static Term connectorTerm(String lower) {
        if (THT_WORDS.contains(lower)) {
            return new Term("Through Hole", Kind.MOUNTING, THT, null);
        }
        if (SMD_WORDS.contains(lower)) {
            return new Term("SMD", Kind.MOUNTING, SMD, null);
        }
        if (RIGHT_ANGLE_WORDS.contains(lower)) {
            return new Term("Right Angle", Kind.ORIENTATION, RIGHT_ANGLE, null);
        }
        if (VERTICAL_WORDS.contains(lower)) {
            return new Term("Vertical", Kind.ORIENTATION, VERTICAL, null);
        }
        return null;
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
        Term connector = connectorTerm(lower);
        if (connector != null) {
            return connector;
        }
        Matcher grid = GRID.matcher(t);
        if (grid.matches()) {
            return new Term(grid.group(1) + "x" + grid.group(2) + "P", Kind.POSITIONS);
        }
        Matcher positions = POSITIONS.matcher(t);
        if (positions.matches()) {
            return new Term(positions.group(1) + "P", Kind.POSITIONS);
        }
        if (PITCH.matcher(t).matches()) {
            return new Term(t.substring(0, t.length() - 2) + "mm", Kind.PITCH);
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
