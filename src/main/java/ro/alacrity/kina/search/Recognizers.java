package ro.alacrity.kina.search;

import ro.alacrity.kina.domain.ParsedQuery;

import java.math.BigDecimal;
import java.math.MathContext;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text recognisers shared by {@link QueryParser} and {@link ParametricExtractor} (DESIGN.md section 3.4): component
 * families, SI values (incl. RKM notation), tolerance, dielectric, packages and mounting. Stateless and thread-safe.
 */
final class Recognizers {

    private Recognizers() {
    }

    /** Analysis result of one free text. Values are keyed by the {@link ParsedQuery} kind constants. */
    record Analysis(String family, boolean familyExplicit, Map<String, Value> values, String dielectric,
                    String packageName, String mounting, List<String> keywords) {
    }

    /** A numeric value in SI base units (tolerance: percent) with its compact display form. */
    record Value(String kind, double value, String display) {
    }

    // ------------------------------------------------------------------ normalisation

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern PLUS_MINUS_GAP = Pattern.compile("±\\s+");
    private static final Pattern PERCENT_GAP = Pattern.compile("(\\d)\\s+%");
    private static final Pattern DECIMAL_COMMA = Pattern.compile("(?<=\\d),(?=\\d)");
    private static final Pattern NUMBER_UNIT_GAP = Pattern.compile(
            "(?i)(\\d)\\s+(?=(?:pf|nf|uf|mf|f|ohms?|kohms?|mohms?|megohms?|r|k|meg|nh|uh|mh|h|v|kv|mv|vdc|vac|a|ma|ua"
                    + "|w|mw|kw|hz|khz|mhz|ghz)(?![\\p{L}\\d]))");
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[\\s,;:()\\[\\]{}|\"<>=~*]+|/(?!\\d)|(?<!\\d)/");

    private static final List<Map.Entry<Pattern, String>> PHRASES = List.of(
            Map.entry(Pattern.compile("(?i)\\bop[- ]?amps?\\b"), "opamp"),
            Map.entry(Pattern.compile("(?i)\\bferrite\\s+beads?\\b"), "ferrite"),
            Map.entry(Pattern.compile("(?i)\\bthr(?:ough|u)[- ]hole\\b"), "through-hole"),
            Map.entry(Pattern.compile("(?i)\\bsurface[- ]mount(?:ed)?\\b"), "SMD"),
            Map.entry(Pattern.compile("(?i)\\b([np])[- ]channel\\b"), "$1-channel"),
            Map.entry(Pattern.compile("(?i)\\bmicro[- ]?controllers?\\b"), "mcu"));

    /** Cache key normalisation: trim, collapse whitespace, lower-case, NFKC, µ-&gt;u, Ω-&gt;ohm. */
    static String normalizeKey(String text) {
        if (text == null) {
            return "";
        }
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        s = replaceSymbols(s);
        return WHITESPACE.matcher(s).replaceAll(" ").trim();
    }

    private static String replaceSymbols(String s) {
        return s.replace('µ', 'u').replace('μ', 'u')
                .replace("Ω", "ohm").replace("Ω", "ohm").replace("ω", "ohm");
    }

    /** Case-preserving preparation for token analysis. */
    static String prepare(String text) {
        String s = replaceSymbols(Normalizer.normalize(text, Normalizer.Form.NFKC));
        s = s.replace("+/-", "±").replace("+-", "±");
        s = PLUS_MINUS_GAP.matcher(s).replaceAll("±");
        s = PERCENT_GAP.matcher(s).replaceAll("$1%");
        s = DECIMAL_COMMA.matcher(s).replaceAll(".");
        for (Map.Entry<Pattern, String> phrase : PHRASES) {
            s = phrase.getKey().matcher(s).replaceAll(phrase.getValue());
        }
        s = NUMBER_UNIT_GAP.matcher(s).replaceAll("$1");
        return WHITESPACE.matcher(s).replaceAll(" ").trim();
    }

    static List<String> tokenize(String prepared) {
        List<String> tokens = new ArrayList<>();
        for (String raw : TOKEN_SPLIT.split(prepared)) {
            String t = stripPunctuation(raw);
            if (!t.isEmpty() && t.codePoints().anyMatch(Character::isLetterOrDigit)) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    private static String stripPunctuation(String token) {
        int start = 0;
        int end = token.length();
        while (start < end && "-+'`!?&#".indexOf(token.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && "-+.'`!?&#".indexOf(token.charAt(end - 1)) >= 0) {
            end--;
        }
        return token.substring(start, end);
    }

    // ------------------------------------------------------------------ families

    private record FamilyWord(String family, int priority, boolean keepAsKeyword) {
    }

    private static final Map<String, FamilyWord> FAMILY_WORDS = new LinkedHashMap<>();

    static {
        family(2, false, "capacitor", "capacitor", "capacitors", "cap", "caps");
        family(2, true, "capacitor", "mlcc");
        family(2, false, "resistor", "resistor", "resistors", "res");
        family(2, false, "inductor", "inductor", "inductors", "choke", "chokes");
        family(3, false, "ferrite", "ferrite", "ferrites");
        family(1, false, "diode", "diode", "diodes", "rectifier", "rectifiers");
        family(3, false, "schottky", "schottky");
        family(3, false, "zener", "zener");
        family(3, false, "led", "led", "leds");
        family(3, false, "mosfet", "mosfet", "mosfets", "fet", "fets");
        family(1, false, "transistor", "transistor", "transistors");
        family(1, true, "transistor", "bjt", "npn", "pnp");
        family(3, true, "regulator", "ldo");
        family(2, false, "regulator", "regulator", "regulators");
        family(3, false, "opamp", "opamp", "opamps");
        family(3, false, "comparator", "comparator", "comparators");
        family(2, false, "mcu", "mcu", "mcus");
        family(2, false, "crystal", "crystal", "crystals", "xtal");
        family(3, false, "oscillator", "oscillator", "oscillators");
        family(2, false, "connector", "connector", "connectors");
        family(2, false, "fuse", "fuse", "fuses", "polyfuse");
        family(3, false, "tvs", "tvs");
        family(3, true, "tvs", "esd");
        family(2, false, "relay", "relay", "relays");
        family(2, false, "switch", "switch", "switches");
    }

    private static void family(int priority, boolean keep, String family, String... words) {
        for (String w : words) {
            FAMILY_WORDS.put(w, new FamilyWord(family, priority, keep));
        }
    }

    /** Families that are a specialisation of a generic family ({@code schottky} is a {@code diode}). */
    private static final Map<String, String> FAMILY_PARENT = Map.of(
            "schottky", "diode", "zener", "diode", "tvs", "diode", "led", "diode", "mosfet", "transistor",
            "oscillator", "crystal");

    private static final Pattern DIODE_MPN = Pattern.compile("(?i)^1n\\d{3,4}[a-z]{0,2}$");

    /** Lower-case words that name a family, used for lexical family detection. */
    static Set<String> familyWords(String family) {
        Set<String> words = new HashSet<>();
        FAMILY_WORDS.forEach((w, f) -> {
            if (f.family().equals(family)) {
                words.add(w);
            }
        });
        return words;
    }

    static String parentFamily(String family) {
        return family == null ? null : FAMILY_PARENT.get(family);
    }

    private static boolean passiveFamily(String family) {
        return "capacitor".equals(family) || "resistor".equals(family) || "inductor".equals(family)
                || "ferrite".equals(family);
    }

    private static boolean frequencyFamily(String family) {
        return "crystal".equals(family) || "oscillator".equals(family);
    }

    // ------------------------------------------------------------------ dielectric / mounting

    private static final Map<String, String> DIELECTRICS = Map.ofEntries(
            Map.entry("x7r", "X7R"), Map.entry("x5r", "X5R"), Map.entry("c0g", "C0G"), Map.entry("cog", "C0G"),
            Map.entry("np0", "C0G"), Map.entry("npo", "C0G"), Map.entry("y5v", "Y5V"), Map.entry("x7s", "X7S"),
            Map.entry("x6s", "X6S"), Map.entry("x8r", "X8R"), Map.entry("x5s", "X5S"), Map.entry("x7t", "X7T"),
            Map.entry("x8l", "X8L"), Map.entry("z5u", "Z5U"), Map.entry("x6t", "X6T"), Map.entry("x8g", "X8G"));

    private static final Map<String, String> MOUNTINGS = Map.of(
            "smd", "SMD", "smt", "SMD", "tht", "THT", "through-hole", "THT", "pth", "THT");

    /** "X7R", "C0G" (NP0/NPO/COG are normalised to C0G), or null. */
    static String dielectric(String token) {
        return DIELECTRICS.get(token.toLowerCase(Locale.ROOT));
    }

    /** "SMD" or "THT", or null. */
    static String mounting(String token) {
        return MOUNTINGS.get(token.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ packages

    private static final Set<String> CHIP_IMPERIAL = Set.of("01005", "0201", "0402", "0603", "0805", "1008", "1206",
            "1210", "1806", "1812", "2010", "2220", "2512", "0806", "0306", "0612", "1218", "2920");
    private static final Map<String, String> METRIC_TO_IMPERIAL = Map.ofEntries(
            Map.entry("0603", "0201"), Map.entry("1005", "0402"), Map.entry("1608", "0603"), Map.entry("2012", "0805"),
            Map.entry("3216", "1206"), Map.entry("3225", "1210"), Map.entry("4532", "1812"), Map.entry("5025", "2010"),
            Map.entry("5750", "2220"), Map.entry("6432", "2512"), Map.entry("2016", "0806"), Map.entry("2520", "1008"),
            Map.entry("4516", "1806"));
    private static final Set<String> CRYSTAL_SIZES = Set.of("1210", "1612", "2012", "2016", "2520", "3215", "3225",
            "5032", "6035", "7050");

    private static final Pattern P_SOT = Pattern.compile("^sot-?(\\d{2,3})(?:-(\\d{1,2}))?([a-z]{0,2})$");
    private static final Pattern P_SOD = Pattern.compile("^sod-?(\\d{2,3})([a-z]{0,2})$");
    private static final Pattern P_SC = Pattern.compile("^sc-?(\\d{2,3})(?:-(\\d{1,2}))?([a-z]{0,2})$");
    private static final Pattern P_TO = Pattern.compile("^to-?(\\d{1,3})([a-z]{0,2})(?:-(\\d{1,2})([a-z]{0,2}))?$");
    private static final Pattern P_DO = Pattern.compile("^do-?(\\d{2,3})([a-z]{0,2})$");
    private static final Pattern P_PAK = Pattern.compile("^(d2-?pak|d3-?pak|d-?pak|i2-?pak|i-?pak)$");
    private static final Pattern P_IC = Pattern.compile("^(soic|sop|so|ssop|tssop|msop|qsop|vssop|htssop|hsop|esop"
            + "|qfn|dfn|vqfn|wqfn|uqfn|tqfn|lqfp|tqfp|qfp|bga|lga|wlcsp|dip|pdip|sip|zip|plcc|son|wson|vson|tdfn|udfn"
            + "|xdfn|uson|lfcsp|csp)-?(\\d{1,4})?((?:-[a-z0-9]+)*)$");
    private static final Set<String> IC_NEEDS_PINS = Set.of("so", "sip", "zip", "csp", "son", "sop");
    private static final Pattern P_SMX = Pattern.compile("^(sma|smb|smc|smaf|smbf)$");
    private static final Pattern P_MELF = Pattern.compile("^(melf|minimelf|mini-melf|micromelf|ll-?34|ll-?41)$");
    private static final Pattern P_HC49 = Pattern.compile("^hc-?49(?:u|s|us)?(?:-smd)?$");
    private static final Pattern P_SMD_SIZE = Pattern.compile("^smd-?(\\d{4})(?:-\\d{1,2}p)?$");

    /**
     * Recognises a non-chip (IC/discrete) package token, returning its display form ("SOT-23", "SOIC-8"), or null.
     * Chip size codes are handled by {@link #analyze} because they depend on the family.
     */
    static String icPackage(String token, String family) {
        String t = token.toLowerCase(Locale.ROOT);
        Matcher m;
        if ((m = P_SOT.matcher(t)).matches()) {
            return "SOT-" + m.group(1) + (m.group(2) != null ? "-" + m.group(2) : "") + upper(m.group(3));
        }
        if ((m = P_SOD.matcher(t)).matches()) {
            return "SOD-" + m.group(1) + upper(m.group(2));
        }
        if ((m = P_SC.matcher(t)).matches()) {
            return "SC-" + m.group(1) + (m.group(2) != null ? "-" + m.group(2) : "") + upper(m.group(3));
        }
        if ((m = P_TO.matcher(t)).matches()) {
            return "TO-" + m.group(1) + upper(m.group(2))
                    + (m.group(3) != null ? "-" + m.group(3) + upper(m.group(4)) : "");
        }
        if ((m = P_DO.matcher(t)).matches()) {
            return "DO-" + m.group(1) + upper(m.group(2));
        }
        if (P_PAK.matcher(t).matches()) {
            return t.replace("-", "").toUpperCase(Locale.ROOT);
        }
        if ((m = P_IC.matcher(t)).matches()) {
            String kind = m.group(1);
            if (m.group(2) == null && IC_NEEDS_PINS.contains(kind)) {
                return null;
            }
            return kind.toUpperCase(Locale.ROOT) + (m.group(2) != null ? "-" + m.group(2) : "") + upper(m.group(3));
        }
        if (P_SMX.matcher(t).matches() && !"connector".equals(family)) {
            return t.toUpperCase(Locale.ROOT);
        }
        if (P_MELF.matcher(t).matches()) {
            return t.toUpperCase(Locale.ROOT);
        }
        if (P_HC49.matcher(t).matches()) {
            return "HC-49" + t.replaceFirst("^hc-?49", "").toUpperCase(Locale.ROOT);
        }
        if ((m = P_SMD_SIZE.matcher(t)).matches() && CRYSTAL_SIZES.contains(m.group(1))) {
            return m.group(1);
        }
        return null;
    }

    /** True for imperial chip codes ("0805") and crystal sizes ("3225"). */
    static boolean isChipCode(String packageName) {
        return CHIP_IMPERIAL.contains(packageName) || CRYSTAL_SIZES.contains(packageName);
    }

    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }

    private static final Map<String, String> PACKAGE_ALIASES = Map.ofEntries(
            Map.entry("SOT-23-3", "SOT-23"), Map.entry("SOT-25", "SOT-23-5"), Map.entry("SOT-753", "SOT-23-5"),
            Map.entry("SOT-26", "SOT-23-6"), Map.entry("SC-70", "SOT-323"), Map.entry("SC-70-3", "SOT-323"),
            Map.entry("SOT-323-3", "SOT-323"), Map.entry("SC-70-5", "SOT-353"), Map.entry("SC-70-6", "SOT-363"),
            Map.entry("SC-88", "SOT-363"), Map.entry("SC-88A", "SOT-353"), Map.entry("SC-59", "SOT-23"),
            Map.entry("SOT-223-3", "SOT-223"), Map.entry("SOT-223-4", "SOT-223"), Map.entry("SOT-89-3", "SOT-89"),
            Map.entry("DPAK", "TO-252"), Map.entry("TO-252AA", "TO-252"), Map.entry("TO-252-3", "TO-252"),
            Map.entry("D2PAK", "TO-263"), Map.entry("TO-263AB", "TO-263"), Map.entry("TO-263-3", "TO-263"),
            Map.entry("TO-220AB", "TO-220"), Map.entry("TO-220-3", "TO-220"), Map.entry("TO-92-3", "TO-92"),
            Map.entry("TO-247-3", "TO-247"), Map.entry("DO-214AC", "SMA"), Map.entry("DO-214AA", "SMB"),
            Map.entry("DO-214AB", "SMC"), Map.entry("SOD-123FL", "SOD-123F"), Map.entry("MINI-MELF", "MINIMELF"),
            Map.entry("LL-34", "MINIMELF"), Map.entry("LL34", "MINIMELF"));
    private static final Pattern IC_CANON = Pattern.compile("^(SOIC|SOP|SO|SSOP|TSSOP|MSOP|QSOP|VSSOP|HTSSOP|HSOP|ESOP"
            + "|QFN|DFN|VQFN|WQFN|UQFN|TQFN|LQFP|TQFP|QFP|BGA|LGA|WLCSP|DIP|PDIP|SIP|ZIP|PLCC|SON|WSON|VSON|TDFN|UDFN"
            + "|XDFN|USON|LFCSP|CSP)-(\\d{1,4}).*$");
    private static final Pattern NON_ALNUM = Pattern.compile("[^A-Z0-9]");

    /**
     * Equivalence key for package comparison: {@code 0805 == 2012 (metric, converted at recognition)},
     * {@code SOT-23-3 == SOT-23}, {@code SO-8 == SOP-8 == SOIC-8}, {@code DPAK == TO-252}, {@code SMA == DO-214AC}.
     * Returns null for null/blank.
     */
    static String packageKey(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return null;
        }
        String p = packageName.trim().toUpperCase(Locale.ROOT);
        p = PACKAGE_ALIASES.getOrDefault(p, p);
        Matcher m = IC_CANON.matcher(p);
        if (m.matches()) {
            String kind = m.group(1);
            if (kind.equals("SO") || kind.equals("SOP")) {
                kind = "SOIC";
            } else if (kind.equals("PDIP")) {
                kind = "DIP";
            }
            p = kind + "-" + m.group(2);
        }
        return NON_ALNUM.matcher(p).replaceAll("");
    }

    /**
     * Recognises the first package in a short text such as a distributor package field ("0805 (2012 Metric)").
     * Metric chip codes are converted when {@code metric} is true or the family is a passive.
     */
    static String findPackage(String text, String family, boolean metric) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (String token : tokenize(prepare(text))) {
            String t = token.toLowerCase(Locale.ROOT);
            if (frequencyFamily(family) && CRYSTAL_SIZES.contains(t)) {
                return t;
            }
            if (metric && METRIC_TO_IMPERIAL.containsKey(t)) {
                return METRIC_TO_IMPERIAL.get(t);
            }
            if (CHIP_IMPERIAL.contains(t)) {
                return t;
            }
            if (passiveFamily(family) && METRIC_TO_IMPERIAL.containsKey(t)) {
                return METRIC_TO_IMPERIAL.get(t);
            }
            String ic = icPackage(t, family);
            if (ic != null) {
                return ic;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ values

    private static final Pattern P_UNIT_VALUE = Pattern.compile(
            "^(\\d+(?:\\.\\d+)?|\\.\\d+)(meg|[pnumkgPNUMKG]?)(f|ohms?|r|h|v|vdc|vac|a|w|hz)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_RKM = Pattern.compile("^(\\d{1,3})([pnuPNUkKMRr])(\\d{1,3})(f|h|ohms?)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern P_R_LEADING = Pattern.compile("^[rR](\\d{1,3})$");
    private static final Pattern P_BARE_PREFIX = Pattern.compile("^(\\d+(?:\\.\\d+)?)(meg|[kKMnpu])$");
    private static final Pattern P_FRACTION_POWER = Pattern.compile("^(\\d{1,2})/(\\d{1,3})[wW]$");
    private static final Pattern P_TOLERANCE = Pattern.compile("^±?(\\d+(?:\\.\\d+)?)%$");

    /** Parses a tolerance token ("±5%", "1%") to percent, or null. */
    static Double tolerance(String token) {
        Matcher m = P_TOLERANCE.matcher(token);
        return m.matches() ? Double.parseDouble(m.group(1)) : null;
    }

    /**
     * Parses one value token ("10uF", "4k7", "2R2", "1/8W", "12MHz"). Ambiguous unit-less forms use the family
     * ({@code 4u7} is an inductance for inductors, a capacitance otherwise). Returns null when not a value.
     */
    static Value value(String token, String family) {
        Matcher m = P_FRACTION_POWER.matcher(token);
        if (m.matches()) {
            double den = Double.parseDouble(m.group(2));
            return den == 0 ? null : of(ParsedQuery.POWER, Double.parseDouble(m.group(1)) / den);
        }
        m = P_UNIT_VALUE.matcher(token);
        if (m.matches()) {
            double number = Double.parseDouble(m.group(1));
            String prefix = m.group(2);
            String unit = m.group(3).toLowerCase(Locale.ROOT);
            if (unit.equals("r") && !prefix.isEmpty()) {
                return null;
            }
            String kind = switch (unit) {
                case "f" -> ParsedQuery.CAPACITANCE;
                case "ohm", "ohms", "r" -> ParsedQuery.RESISTANCE;
                case "h" -> ParsedQuery.INDUCTANCE;
                case "v", "vdc", "vac" -> ParsedQuery.VOLTAGE;
                case "a" -> ParsedQuery.CURRENT;
                case "w" -> ParsedQuery.POWER;
                default -> ParsedQuery.FREQUENCY;
            };
            Double multiplier = multiplier(prefix, kind);
            return multiplier == null ? null : of(kind, number * multiplier);
        }
        m = P_RKM.matcher(token);
        if (m.matches()) {
            return rkm(Double.parseDouble(m.group(1) + "." + m.group(3)), m.group(2).charAt(0),
                    m.group(4) == null ? null : m.group(4).toLowerCase(Locale.ROOT), family);
        }
        m = P_R_LEADING.matcher(token);
        if (m.matches()) {
            return of(ParsedQuery.RESISTANCE, Double.parseDouble("0." + m.group(1)));
        }
        m = P_BARE_PREFIX.matcher(token);
        if (m.matches()) {
            double number = Double.parseDouble(m.group(1));
            return switch (m.group(2)) {
                case "k", "K" -> of(ParsedQuery.RESISTANCE, number * 1e3);
                case "M", "meg" -> of(ParsedQuery.RESISTANCE, number * 1e6);
                case "n" -> of(inductive(family) ? ParsedQuery.INDUCTANCE : ParsedQuery.CAPACITANCE, number * 1e-9);
                case "p" -> of(ParsedQuery.CAPACITANCE, number * 1e-12);
                default -> of(inductive(family) ? ParsedQuery.INDUCTANCE : ParsedQuery.CAPACITANCE, number * 1e-6);
            };
        }
        return null;
    }

    /** RKM notation: {@code 4k7}, {@code 2R2}, {@code 1M5}, {@code 4u7}, {@code 4n7}, {@code 2p2}. */
    private static Value rkm(double number, char letter, String unit, String family) {
        boolean ohmUnit = unit == null || unit.startsWith("ohm");
        switch (letter) {
            case 'r', 'R' -> {
                return ohmUnit ? of(ParsedQuery.RESISTANCE, number) : null;
            }
            case 'k', 'K' -> {
                return ohmUnit ? of(ParsedQuery.RESISTANCE, number * 1e3) : null;
            }
            case 'M' -> {
                return ohmUnit ? of(ParsedQuery.RESISTANCE, number * 1e6) : null;
            }
            default -> {
                if (unit != null && unit.startsWith("ohm")) {
                    return null;
                }
                double mult = switch (Character.toLowerCase(letter)) {
                    case 'p' -> 1e-12;
                    case 'n' -> 1e-9;
                    default -> 1e-6;
                };
                boolean inductance = "h".equals(unit) || (unit == null && inductive(family));
                return of(inductance ? ParsedQuery.INDUCTANCE : ParsedQuery.CAPACITANCE, number * mult);
            }
        }
    }

    private static boolean inductive(String family) {
        return "inductor".equals(family) || "ferrite".equals(family);
    }

    private static Double multiplier(String prefix, String kind) {
        if (prefix.isEmpty()) {
            return 1.0;
        }
        if (prefix.equalsIgnoreCase("meg")) {
            return 1e6;
        }
        return switch (prefix) {
            case "p", "P" -> 1e-12;
            case "n", "N" -> 1e-9;
            case "u", "U" -> 1e-6;
            case "k", "K" -> 1e3;
            case "g", "G" -> 1e9;
            case "m" -> kind.equals(ParsedQuery.FREQUENCY) ? 1e6 : 1e-3;
            case "M" -> kind.equals(ParsedQuery.RESISTANCE) || kind.equals(ParsedQuery.FREQUENCY) ? 1e6 : 1e-3;
            default -> null;
        };
    }

    static Value of(String kind, double value) {
        return new Value(kind, value, display(kind, value));
    }

    private record Prefix(String symbol, double multiplier) {
    }

    private static final List<Prefix> CAP_PREFIXES = List.of(new Prefix("p", 1e-12), new Prefix("n", 1e-9),
            new Prefix("u", 1e-6), new Prefix("m", 1e-3), new Prefix("", 1));
    private static final List<Prefix> RES_PREFIXES = List.of(new Prefix("m", 1e-3), new Prefix("", 1),
            new Prefix("k", 1e3), new Prefix("M", 1e6), new Prefix("G", 1e9));
    private static final List<Prefix> IND_PREFIXES = List.of(new Prefix("n", 1e-9), new Prefix("u", 1e-6),
            new Prefix("m", 1e-3), new Prefix("", 1));
    private static final List<Prefix> SMALL_PREFIXES = List.of(new Prefix("u", 1e-6), new Prefix("m", 1e-3),
            new Prefix("", 1), new Prefix("k", 1e3));
    private static final List<Prefix> FREQ_PREFIXES = List.of(new Prefix("", 1), new Prefix("k", 1e3),
            new Prefix("M", 1e6), new Prefix("G", 1e9));

    /** Compact human form: "10uF", "4.7kohm", "16V", "125mW", "12MHz", "5%". */
    static String display(String kind, double value) {
        if (kind.equals(ParsedQuery.TOLERANCE)) {
            return format(value) + "%";
        }
        List<Prefix> prefixes;
        String unit;
        switch (kind) {
            case ParsedQuery.CAPACITANCE -> {
                prefixes = CAP_PREFIXES;
                unit = "F";
            }
            case ParsedQuery.RESISTANCE -> {
                prefixes = RES_PREFIXES;
                unit = "ohm";
            }
            case ParsedQuery.INDUCTANCE -> {
                prefixes = IND_PREFIXES;
                unit = "H";
            }
            case ParsedQuery.VOLTAGE -> {
                prefixes = SMALL_PREFIXES;
                unit = "V";
            }
            case ParsedQuery.CURRENT -> {
                prefixes = SMALL_PREFIXES;
                unit = "A";
            }
            case ParsedQuery.POWER -> {
                prefixes = SMALL_PREFIXES;
                unit = "W";
            }
            default -> {
                prefixes = FREQ_PREFIXES;
                unit = "Hz";
            }
        }
        if (value == 0) {
            return "0" + unit;
        }
        Prefix chosen = prefixes.getFirst();
        for (Prefix p : prefixes) {
            if (Math.abs(value) >= p.multiplier() * (1 - 1e-9)) {
                chosen = p;
            }
        }
        return format(value / chosen.multiplier()) + chosen.symbol() + unit;
    }

    private static String format(double v) {
        return new BigDecimal(v).round(new MathContext(6)).stripTrailingZeros().toPlainString();
    }

    // ------------------------------------------------------------------ analysis

    private static final Set<String> STOPWORDS = Set.of("a", "an", "the", "for", "with", "and", "or", "of", "in",
            "on", "to", "pcs", "pc", "type", "rohs", "new");

    private static final List<String> VALUE_ORDER = List.of(ParsedQuery.CAPACITANCE, ParsedQuery.RESISTANCE,
            ParsedQuery.INDUCTANCE, ParsedQuery.FREQUENCY, ParsedQuery.VOLTAGE, ParsedQuery.CURRENT, ParsedQuery.POWER,
            ParsedQuery.TOLERANCE);

    /** Extracts every recognisable feature from a free text (query or part description). */
    static Analysis analyze(String text) {
        List<String> tokens = text == null ? List.of() : tokenize(prepare(text));

        // pass 1: explicit family (highest priority wins, first on ties)
        String family = null;
        int priority = -1;
        for (String token : tokens) {
            String t = token.toLowerCase(Locale.ROOT);
            FamilyWord fw = FAMILY_WORDS.get(t);
            if (fw == null && DIODE_MPN.matcher(t).matches()) {
                fw = new FamilyWord("diode", 0, true);
            }
            if (fw != null && fw.priority() > priority) {
                family = fw.family();
                priority = fw.priority();
            }
        }
        boolean explicit = family != null;

        // pass 2: everything else
        Map<String, Value> values = new LinkedHashMap<>();
        String dielectric = null;
        String packageName = null;
        String mounting = null;
        List<String> keywords = new ArrayList<>();
        List<String> deferredSizes = new ArrayList<>();   // 4-digit size codes whose meaning depends on the family
        for (String token : tokens) {
            String t = token.toLowerCase(Locale.ROOT);
            FamilyWord fw = FAMILY_WORDS.get(t);
            if (fw != null) {
                if (fw.keepAsKeyword()) {
                    keywords.add(t);
                }
                continue;
            }
            String d = dielectric(t);
            if (d != null) {
                if (dielectric == null) {
                    dielectric = d;
                } else if (!dielectric.equals(d)) {
                    keywords.add(t);
                }
                continue;
            }
            Double tol = tolerance(t);
            if (tol != null) {
                values.putIfAbsent(ParsedQuery.TOLERANCE, of(ParsedQuery.TOLERANCE, tol));
                continue;
            }
            String mnt = mounting(t);
            if (mnt != null) {
                if (mounting == null) {
                    mounting = mnt;
                }
                continue;
            }
            if (CHIP_IMPERIAL.contains(t) && !(frequencyFamily(family) && CRYSTAL_SIZES.contains(t))) {
                if (packageName == null) {
                    packageName = t;
                } else if (!packageName.equals(t)) {
                    keywords.add(t);
                }
                continue;
            }
            if (METRIC_TO_IMPERIAL.containsKey(t) || CRYSTAL_SIZES.contains(t)) {
                deferredSizes.add(t);
                continue;
            }
            String ic = icPackage(t, family);
            if (ic != null) {
                if (packageName == null) {
                    packageName = ic;
                } else if (!packageName.equals(ic)) {
                    keywords.add(t);
                }
                continue;
            }
            Value v = value(token, family);
            if (v != null) {
                if (!values.containsKey(v.kind())) {
                    values.put(v.kind(), v);
                } else {
                    keywords.add(t);
                }
                continue;
            }
            if (!STOPWORDS.contains(t)) {
                keywords.add(t);
            }
        }

        if (family == null) {
            if (values.containsKey(ParsedQuery.CAPACITANCE) || dielectric != null) {
                family = "capacitor";
            } else if (values.containsKey(ParsedQuery.RESISTANCE)) {
                family = "resistor";
            } else if (values.containsKey(ParsedQuery.INDUCTANCE)) {
                family = "inductor";
            }
        }
        for (String t : deferredSizes) {
            String resolved = null;
            if (frequencyFamily(family) && CRYSTAL_SIZES.contains(t)) {
                resolved = t;
            } else if (passiveFamily(family) && METRIC_TO_IMPERIAL.containsKey(t)) {
                resolved = METRIC_TO_IMPERIAL.get(t);
            }
            if (resolved != null && packageName == null) {
                packageName = resolved;
            } else if (resolved == null || !resolved.equals(packageName)) {
                keywords.add(t);
            }
        }

        Map<String, Value> ordered = new LinkedHashMap<>();
        for (String kind : VALUE_ORDER) {
            Value v = values.get(kind);
            if (v != null) {
                ordered.put(kind, v);
            }
        }
        List<String> normalizedKeywords = keywords.stream().map(Recognizers::normalizeKey).distinct().toList();
        return new Analysis(family, explicit, ordered, dielectric, packageName, mounting, normalizedKeywords);
    }

    /** First value of the given kind found in a short text (e.g. an attribute value), or null. */
    static Value firstValue(String text, String kind, String family) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (String token : tokenize(prepare(text))) {
            if (kind.equals(ParsedQuery.TOLERANCE)) {
                Double tol = tolerance(token);
                if (tol != null) {
                    return of(ParsedQuery.TOLERANCE, tol);
                }
                continue;
            }
            Value v = value(token, family);
            if (v != null && v.kind().equals(kind)) {
                return v;
            }
        }
        return null;
    }
}
