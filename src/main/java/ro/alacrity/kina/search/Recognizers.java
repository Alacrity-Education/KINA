package ro.alacrity.kina.search;

import lombok.experimental.UtilityClass;
import ro.alacrity.kina.domain.ComponentFamily;
import ro.alacrity.kina.domain.ComponentFamily.Trait;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.PartAttribute;
import ro.alacrity.kina.domain.PartFeatures;

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

import static ro.alacrity.kina.domain.ComponentFamily.CAPACITOR;
import static ro.alacrity.kina.domain.ComponentFamily.COMPARATOR;
import static ro.alacrity.kina.domain.ComponentFamily.CONNECTOR;
import static ro.alacrity.kina.domain.ComponentFamily.CRYSTAL;
import static ro.alacrity.kina.domain.ComponentFamily.DIODE;
import static ro.alacrity.kina.domain.ComponentFamily.FERRITE;
import static ro.alacrity.kina.domain.ComponentFamily.FUSE;
import static ro.alacrity.kina.domain.ComponentFamily.INDUCTOR;
import static ro.alacrity.kina.domain.ComponentFamily.LED;
import static ro.alacrity.kina.domain.ComponentFamily.MCU;
import static ro.alacrity.kina.domain.ComponentFamily.MOSFET;
import static ro.alacrity.kina.domain.ComponentFamily.OPAMP;
import static ro.alacrity.kina.domain.ComponentFamily.OSCILLATOR;
import static ro.alacrity.kina.domain.ComponentFamily.REGULATOR;
import static ro.alacrity.kina.domain.ComponentFamily.RELAY;
import static ro.alacrity.kina.domain.ComponentFamily.RESISTOR;
import static ro.alacrity.kina.domain.ComponentFamily.SCHOTTKY;
import static ro.alacrity.kina.domain.ComponentFamily.SWITCH;
import static ro.alacrity.kina.domain.ComponentFamily.TRANSISTOR;
import static ro.alacrity.kina.domain.ComponentFamily.TVS;
import static ro.alacrity.kina.domain.ComponentFamily.ZENER;

/**
 * Text recognisers shared by {@link QueryParser} and {@link ParametricExtractor} (DESIGN.md section 3.4): component
 * families, SI values (incl. RKM notation), tolerance, dielectric, packages, mounting and the passive technology
 * ({@link TechnologyVocabulary}). Stateless and thread-safe.
 */
@UtilityClass
class Recognizers {

    /**
     * Analysis result of one free text. Values are keyed by the {@link ParsedQuery} kind constants; {@code preferences}
     * holds soft preferences such as {@link ParsedQuery#LOW_DCR}.
     */
    record Analysis(String family, boolean familyExplicit, Map<String, Value> values, String dielectric,
                    String packageName, String mounting, List<String> keywords, String technology,
                    List<String> preferences) {

        Analysis {
            preferences = preferences == null ? List.of() : List.copyOf(preferences);
        }
    }

    /**
     * A numeric value in SI base units (tolerance: percent, temperature: degrees Celsius, lifetime: hours) with its
     * compact display form. {@code condition} is the test frequency (Hz) of an impedance or the temperature (degrees
     * Celsius) of a lifetime when stated, else null.
     */
    record Value(String kind, double value, String display, Double condition) implements PartFeatures.Measure {

        Value(String kind, double value, String display) {
            this(kind, value, display, null);
        }
    }

    // ------------------------------------------------------------------ normalisation

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern PLUS_MINUS_GAP = Pattern.compile("±\\s+");
    private static final Pattern PERCENT_GAP = Pattern.compile("(\\d)\\s+%");
    private static final Pattern DECIMAL_COMMA = Pattern.compile("(?<=\\d),(?=\\d)");
    private static final Pattern NUMBER_UNIT_GAP = Pattern.compile(
            "(?i)(\\d)\\s+(?=(?:pf|nf|uf|mf|f|ohms?|kohms?|mohms?|megohms?|r|k|meg|nh|uh|mh|h|v|kv|mv|vdc|vac|a|ma|ua"
                    + "|w|mw|kw|watts?|kilowatts?|hz|khz|mhz|ghz|hrs?|hours?|volts?|vol|vo)(?![\\p{L}\\d])|°)");
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[\\s,;:()\\[\\]{}|\"<>=~*]+|/(?!\\d)|(?<!\\d)/");

    private static final List<Map.Entry<Pattern, String>> PHRASES = List.of(
            Map.entry(Pattern.compile("(?i)\\bop[- ]?amps?\\b"), "opamp"),
            Map.entry(Pattern.compile("(?i)\\bferrite\\s+beads?\\b"), "ferrite"),
            Map.entry(Pattern.compile("(?i)\\bthr(?:ough|u)[- ]hole\\b"), "through-hole"),
            Map.entry(Pattern.compile("(?i)\\bsurface[- ]mount(?:ed)?\\b"), "SMD"),
            Map.entry(Pattern.compile("(?i)\\b([np])[- ]channel\\b"), "$1-channel"),
            Map.entry(Pattern.compile("(?i)\\bmicro[- ]?controllers?\\b"), "mcu"),
            // gate drivers (Mouser "Gate Drivers", "Half Bridge Gate Dvr", "HALF BRDG DRVR", "Iso 1/2 Bridge Drv",
            // "MOSFET DRIVER"; EPC "ePower Stage", "Half-Bridge Power Stage"): one token each, see FAMILY_WORDS
            Map.entry(Pattern.compile("(?i)(?<![\\p{L}\\d])(?:half|1/2|h)[- ]?(?:bridge|brdg|brg)[- ]+"
                    + "(?:(?:gate|mosfet|fet|gan)[- ]+)?(?:drivers?|drvrs?|drv|dvr)\\b"), "gate-driver"),
            Map.entry(Pattern.compile("(?i)\\b(?:gate|mosfet|igbt|fet)[- ](?:drivers?|drvrs?|dvr)\\b"), "gate-driver"),
            Map.entry(Pattern.compile("(?i)\\b(?:e?power|drgan)[- ]?stages?\\b"), "power-stage"),
            // a GaN half-bridge with an integrated driver (TI LMG, Infineon IGI60 under "GaN FETs") is a gate driver
            Map.entry(Pattern.compile("(?i)\\bhalf[- ]?bridges?\\b(?=.*\\b(?:drivers?|drvrs?|drv)\\b)"),
                    "half-bridge-driver"));

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
        s = imperial(s);
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

    /** The gate driver family (half-bridge, low-side, isolated drivers; GaN power stages). */
    static final String GATE_DRIVER = ComponentFamily.GATE_DRIVER.label();

    private static final Map<String, FamilyWord> FAMILY_WORDS = new LinkedHashMap<>();

    static {
        family(2, false, CAPACITOR, "capacitor", "capacitors", "cap", "caps");
        family(2, true, CAPACITOR, "mlcc");
        family(2, false, RESISTOR, "resistor", "resistors", "res");
        family(2, false, INDUCTOR, "inductor", "inductors", "choke", "chokes");
        family(3, false, FERRITE, "ferrite", "ferrites");
        family(1, false, DIODE, "diode", "diodes", "rectifier", "rectifiers");
        family(3, false, SCHOTTKY, "schottky");
        family(3, false, ZENER, "zener");
        family(3, false, LED, "led", "leds");
        family(3, false, MOSFET, "mosfet", "mosfets", "fet", "fets", "hemt", "hemts");
        // TME writes "Transistor: N-MOSFET" / "P-MOSFET"; the polarity stays a keyword
        family(3, true, MOSFET, "n-mosfet", "p-mosfet");
        family(1, false, TRANSISTOR, "transistor", "transistors");
        family(1, true, TRANSISTOR, "bjt", "npn", "pnp");
        family(3, true, REGULATOR, "ldo");
        family(2, false, REGULATOR, "regulator", "regulators");
        family(3, false, OPAMP, "opamp", "opamps");
        family(3, false, COMPARATOR, "comparator", "comparators");
        family(2, false, MCU, "mcu", "mcus");
        // crystals (passive resonators) and oscillators (active, with a supply) are different families, never mixed
        family(2, false, CRYSTAL, "crystal", "crystals", "xtal", "xtals", "resonator", "resonators");
        family(3, false, OSCILLATOR, "oscillator", "oscillators", "xo", "tcxo", "vcxo", "ocxo", "spxo",
                "vctcxo", "tcxos", "vcxos", "ocxos");
        // TME "Generator: quartz; 16MHz; SMD" (an oscillator), category "Resonators and Generators"
        family(3, false, OSCILLATOR, "generator", "generators");
        family(2, false, CONNECTOR, "connector", "connectors");
        family(2, false, FUSE, "fuse", "fuses", "polyfuse");
        family(3, false, TVS, "tvs");
        family(3, true, TVS, "esd");
        family(2, false, RELAY, "relay", "relays");
        family(2, false, SWITCH, "switch", "switches");
        // gate drivers, GaN power stages and half-bridges with an integrated driver (PHRASES make them one token; the
        // spaced forms serve the lexical family check of part texts); they win over "MOSFET", "FET", "transistor"
        family(4, false, ComponentFamily.GATE_DRIVER, "gate-driver", "power-stage", "half-bridge-driver",
                "gate driver", "gate drivers", "power stage");
    }


    /**
     * Families a part's description may name over the family of its category: a GaN half-bridge with an integrated
     * driver that Mouser lists under {@code GaN FETs} is a gate driver.
     */
    private static final Map<String, Set<String>> OVERRIDES_CATEGORY = Map.of(GATE_DRIVER,
            Set.of(MOSFET.label(), TRANSISTOR.label()));

    /** True when a description naming {@code descriptionFamily} decides over a category naming {@code categoryFamily}. */
    static boolean overridesCategory(String descriptionFamily, String categoryFamily) {
        return descriptionFamily != null && categoryFamily != null
                && OVERRIDES_CATEGORY.getOrDefault(descriptionFamily, Set.of()).contains(categoryFamily);
    }

    /** How a phrase token of {@code PHRASES} is written in a distributor phrase ({@link #familyToken}). */
    private static final Map<String, String> SPELLED = Map.of("gate-driver", "gate driver", "power-stage",
            "power stage", "half-bridge-driver", "half-bridge driver");

    private static void family(int priority, boolean keep, ComponentFamily family, String... words) {
        for (String w : words) {
            FAMILY_WORDS.put(w, new FamilyWord(family.label(), priority, keep));
        }
    }

    /**
     * The first token of {@code text} (original case, e.g. "MOSFET", "MLCC", "LDO") that names {@code family}, or
     * null when the family was inferred rather than written.
     */
    static String familyToken(String text, String family) {
        if (text == null || family == null) {
            return null;
        }
        for (String token : tokenize(prepare(text))) {
            FamilyWord word = FAMILY_WORDS.get(token.toLowerCase(Locale.ROOT));
            if (word != null && word.family().equals(family)) {
                return SPELLED.getOrDefault(token, token);
            }
        }
        return null;
    }

    /** Every family name the parser can yield, sorted (the values of {@code FAMILY_WORDS}). */
    static Set<String> families() {
        Set<String> out = new java.util.TreeSet<>();
        FAMILY_WORDS.values().forEach(w -> out.add(w.family()));
        return out;
    }

    /** Every family a text names by a family word ({@code Resonators and Generators}: crystal and oscillator). */
    static Set<String> familiesIn(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) {
            return out;
        }
        for (String token : tokenize(prepare(text))) {
            FamilyWord word = FAMILY_WORDS.get(token.toLowerCase(Locale.ROOT));
            if (word != null) {
                out.add(word.family());
            }
        }
        return out;
    }

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

    private static boolean frequencyFamily(String family) {
        return ComponentFamily.has(family, Trait.FREQUENCY_VALUED);
    }

    // ------------------------------------------------------------------ dielectric / mounting

    private static final Map<String, String> DIELECTRICS = Map.ofEntries(
            Map.entry("x7r", "X7R"), Map.entry("x5r", "X5R"), Map.entry("c0g", "C0G"), Map.entry("cog", "C0G"),
            Map.entry("np0", "C0G"), Map.entry("npo", "C0G"), Map.entry("y5v", "Y5V"), Map.entry("x7s", "X7S"),
            Map.entry("x6s", "X6S"), Map.entry("x8r", "X8R"), Map.entry("x5s", "X5S"), Map.entry("x7t", "X7T"),
            Map.entry("x8l", "X8L"), Map.entry("z5u", "Z5U"), Map.entry("x6t", "X6T"), Map.entry("x8g", "X8G"));

    /**
     * Mounting words; {@code 插件} (plug-in) and {@code 卧贴} (horizontal SMD) are JLCPCB description wording,
     * {@code radial}, {@code axial} and {@code leaded} name through-hole bodies (Mouser "Radial Leaded", LCSC
     * "Aluminum Electrolytic Capacitors - Leaded").
     */
    private static final Map<String, String> MOUNTINGS = Map.ofEntries(
            Map.entry("smd", "SMD"), Map.entry("smt", "SMD"), Map.entry("tht", "THT"), Map.entry("through-hole", "THT"),
            Map.entry("pth", "THT"), Map.entry("插件", "THT"), Map.entry("卧贴", "SMD"), Map.entry("立贴", "SMD"),
            // Mouser/LCSC capacitor categories and descriptions: "Radial Leaded", "Leaded", "Axial"
            Map.entry("radial", "THT"), Map.entry("axial", "THT"), Map.entry("leaded", "THT"));

    /** "X7R", "C0G" (NP0/NPO/COG are normalised to C0G), or null. */
    static String dielectric(String token) {
        return DIELECTRICS.get(token.toLowerCase(Locale.ROOT));
    }

    /** "SMD" or "THT", or null. */
    static String mounting(String token) {
        return MOUNTINGS.get(token.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ packages

    /**
     * Imperial chip codes (DESIGN.md 3.4 "Packages are imperial"): a bare four-digit code is always one of these, never
     * a metric code ({@code 0603} is imperial 0603, never metric 0603 = imperial 0201).
     */
    private static final Set<String> CHIP_IMPERIAL = Set.of("01005", "0201", "0402", "0603", "0805", "1008", "1206",
            "1210", "1806", "1808", "1812", "1825", "2010", "2220", "2225", "2512", "0806", "0306", "0612", "1218",
            "2920");
    /**
     * Metric chip codes and their imperial code. Used only where the source labels the code as millimetres: TME
     * {@code Case - mm}, Mouser {@code Case Code - mm}, text such as {@code 1608 metric}, {@code (2012 Metric)},
     * {@code 3216M}, {@code 0603mm} ({@link #imperial}).
     */
    private static final Map<String, String> METRIC_TO_IMPERIAL = Map.ofEntries(
            Map.entry("0402", "01005"), Map.entry("0603", "0201"), Map.entry("1005", "0402"), Map.entry("1608", "0603"),
            Map.entry("2012", "0805"), Map.entry("3216", "1206"), Map.entry("3225", "1210"), Map.entry("4532", "1812"),
            Map.entry("4520", "1808"), Map.entry("5025", "2010"), Map.entry("5750", "2220"), Map.entry("5764", "2225"),
            Map.entry("6332", "2512"), Map.entry("6432", "2512"), Map.entry("2016", "0806"), Map.entry("2520", "1008"),
            Map.entry("4516", "1806"));
    /** Crystal and oscillator sizes: the body in tenths of a millimetre ({@code 3225} = 3.2 x 2.5 mm). */
    private static final Set<String> CRYSTAL_SIZES = Set.of("1210", "1612", "2012", "2016", "2520", "3215", "3225",
            "5032", "6035", "7050");
    /** A four-digit chip code labelled as millimetres: {@code 1608 metric}, {@code 3216M}, {@code 0603mm}. */
    private static final Pattern LABELLED_METRIC = Pattern.compile(
            "(?<![\\p{L}\\d.])(\\d{4})(?:\\s?(?i:metric)|\\s?(?i:mm)|M)(?![\\p{L}\\d])");

    /**
     * {@code text} with every chip code it labels as millimetres replaced by the imperial code ({@code 0805 (2012
     * metric)} -&gt; {@code 0805 (0805)}, {@code 3216M} -&gt; {@code 1206}, {@code 0603mm} -&gt; {@code 0201}). Packages
     * are imperial everywhere in KINA; a bare code is never read as metric.
     */
    static String imperial(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher m = LABELLED_METRIC.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String code = METRIC_TO_IMPERIAL.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(code != null ? code : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The imperial code of a metric chip code ({@code 1608} -&gt; {@code 0603}), or null. */
    static String metricToImperial(String code) {
        return code == null ? null : METRIC_TO_IMPERIAL.get(code.strip());
    }

    /** The crystal size code of body dimensions in millimetres ({@code 3.2 x 2.5} -&gt; {@code 3225}), or null. */
    static String crystalSize(double length, double width) {
        long a = Math.round(length * 10);
        long b = Math.round(width * 10);
        if (Math.abs(length * 10 - a) > 0.11 || Math.abs(width * 10 - b) > 0.11 || a < 10 || a > 99 || b < 10
                || b > 99) {
            return null;
        }
        String code = String.valueOf(a) + b;
        return CRYSTAL_SIZES.contains(code) ? code : null;
    }

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

    /** True for a crystal or oscillator size code ({@code 3225}). */
    static boolean isCrystalSize(String packageName) {
        return packageName != null && CRYSTAL_SIZES.contains(packageName.strip());
    }

    /**
     * True when KINA recognises {@code packageName} as a package: an imperial chip code, a crystal size, an IC or
     * discrete package ({@code SOT-23}, {@code SMA}, {@code TO-252}...) or the size of a can capacitor
     * ({@code D6.3 x 5.8mm}). A package string KINA cannot read never contradicts a request.
     */
    static boolean isRecognisedPackage(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return false;
        }
        String t = packageName.strip().toLowerCase(Locale.ROOT);
        return CHIP_IMPERIAL.contains(t) || CRYSTAL_SIZES.contains(t) || icPackage(t, null) != null
                || PassiveDetails.can(packageName) != null;
    }

    /** Largest difference in millimetres between two can capacitor sizes that are the same (diameter, length). */
    static final double CAN_TOLERANCE_MM = 0.2;

    /**
     * Compares a requested package with a part's: true when they are the same ({@link #packageKey}; can capacitors by
     * their diameter and length within {@value #CAN_TOLERANCE_MM} mm, {@code D6.3 x 5.4mm} == {@code D6.3 x 5.5mm}),
     * false when the part states a different recognised package ({@link #isRecognisedPackage}), null when either side
     * is unknown or the part's package cannot be read.
     */
    static Boolean samePackage(String wanted, String actual) {
        if (wanted == null || actual == null || wanted.isBlank() || actual.isBlank()) {
            return null;
        }
        double[] wantedCan = PassiveDetails.can(wanted);
        double[] actualCan = PassiveDetails.can(actual);
        if (wantedCan != null && actualCan != null) {
            return Math.abs(wantedCan[0] - actualCan[0]) <= CAN_TOLERANCE_MM + 1e-9
                    && Math.abs(wantedCan[1] - actualCan[1]) <= CAN_TOLERANCE_MM + 1e-9;
        }
        if (packageKey(wanted).equals(packageKey(actual))) {
            return true;
        }
        return isRecognisedPackage(actual) ? Boolean.FALSE : null;
    }

    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }

    private static final Map<String, String> PACKAGE_ALIASES = Map.ofEntries(
            Map.entry("SOT-23-3", "SOT-23"), Map.entry("SOT-25", "SOT-23-5"), Map.entry("SOT-753", "SOT-23-5"),
            Map.entry("SOT-26", "SOT-23-6"), Map.entry("TO-236", "SOT-23"), Map.entry("TO-236AB", "SOT-23"), Map.entry("SC-70", "SOT-323"), Map.entry("SC-70-3", "SOT-323"),
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
    private static final Pattern LEAD_SUFFIX = Pattern.compile("^((?:SOT|SOD|SC|TO)-\\d{2,3}-\\d{1,2})L$");

    /**
     * Equivalence key for package comparison: {@code SOT-23-3 == SOT-23-3L == SOT-23 == TO-236AB},
     * {@code SO-8 == SOP-8 == SOIC-8}, {@code DPAK == TO-252}, {@code SMA == DO-214AC}. Chip codes are imperial
     * (labelled metric codes were converted at recognition, {@link #imperial}).
     * Returns null for null/blank.
     */
    static String packageKey(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return null;
        }
        String p = packageName.trim().toUpperCase(Locale.ROOT);
        p = LEAD_SUFFIX.matcher(p).replaceFirst("$1");   // JLCPCB "SOT-23-3L", "SOT-89-3L": L marks the lead count
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
     * Recognises the first package in a short text such as a distributor package field ("0805 (2012 Metric)"). A bare
     * four-digit code is imperial; metric chip codes are converted only when {@code metric} is true (the source labels
     * the value as millimetres: TME {@code Case - mm}) or the text says so ({@link #imperial}). Crystal sizes count
     * for crystals and oscillators.
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
            String ic = icPackage(t, family);
            if (ic != null) {
                return ic;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ values

    /**
     * {@code <number><prefix><unit>}. The match ignores case, but the prefix is then read case-sensitively where the
     * case carries meaning: {@code m} is milli and {@code M} is mega for every unit (so {@code 10mA} and {@code 10MA}
     * differ); the single exception is {@code mhz}, read as MHz because millihertz never occurs in component data.
     * Units whose case carries no meaning ({@code v}/{@code V}, {@code a}/{@code A}, {@code w}/{@code W},
     * {@code uf}/{@code uF}, {@code hz}/{@code Hz}) are accepted in either case. Hours ({@code h}) and henry
     * ({@code H}) are told apart by case, see {@link #value}.
     */
    private static final Pattern P_UNIT_VALUE = Pattern.compile(
            "^(\\d+(?:\\.\\d+)?|\\.\\d+)(meg|kilo|[pnumkgPNUMKG]?)(" + unitAlternation() + ")$",
            Pattern.CASE_INSENSITIVE);
    /** Hours: {@code 2000h} (lower-case h only), {@code 2000hrs}, {@code 1000 hours}, {@code 5000Hrs}. */
    private static final Pattern P_HOURS = Pattern.compile("^(\\d+(?:\\.\\d+)?)(h|[hH](?:rs?|RS?|ours?|OURS?))$");
    /** Degrees Celsius: {@code 105°C}, {@code +125°C}, {@code 85°} (NFKC turns {@code ℃} into {@code °C}). */
    private static final Pattern P_DEGREES = Pattern.compile("^\\+?(\\d{1,3}(?:\\.\\d+)?)°[cC]?$");
    /** Mouser writes {@code 105C}: a bare upper-case C after 70..200 is a temperature. */
    private static final Pattern P_BARE_CELSIUS = Pattern.compile("^(\\d{2,3})C$");
    private static final Pattern P_RKM = Pattern.compile("^(\\d{1,3})([pnuPNUkKMRr])(\\d{1,3})(f|h|ohms?)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern P_R_LEADING = Pattern.compile("^[rR](\\d{1,3})$");
    private static final Pattern P_BARE_PREFIX = Pattern.compile("^(\\d+(?:\\.\\d+)?|\\.\\d+)(meg|[kKMnpu])$");
    private static final Pattern P_FRACTION_POWER = Pattern.compile("^(\\d{1,2})/(\\d{1,3})[wW]$");
    /** {@code 5%}, {@code ±0.1%}, and the leading-dot form Mouser writes ({@code .1%}, {@code ±.5%}). */
    private static final Pattern P_TOLERANCE = Pattern.compile("^±?(\\d+(?:\\.\\d+)?|\\.\\d+)%$");
    /** Below this many hours an upper-case {@code H} without prefix stays a henry value even for a capacitor. */
    private static final double MIN_LIFETIME_HOURS = 100;

    /**
     * The unit spellings of a value token: the {@code @Unit} symbols declared on {@link PartAttribute} ({@code f};
     * {@code ohm}, {@code ohms}, {@code r}; {@code h}; {@code v}, {@code vdc}, {@code vac}, {@code volt}, {@code volts},
     * KEMET {@code vol} and {@code vo}; {@code a}; {@code w}, {@code watt}, {@code watts}; {@code hz}), longest first.
     */
    private static String unitAlternation() {
        return String.join("|", PartAttribute.unitSymbols().keySet().stream()
                .sorted(java.util.Comparator.comparingInt(String::length).reversed()
                        .thenComparing(java.util.Comparator.naturalOrder()))
                .toList());
    }

    /** Parses a tolerance token ("±5%", "1%", ".1%") to percent, or null. */
    static Double tolerance(String token) {
        Matcher m = P_TOLERANCE.matcher(token);
        return m.matches() ? Double.parseDouble(m.group(1)) : null;
    }

    /**
     * Parses one value token ("10uF", "4k7", "2R2", "1/8W", "12MHz", "2000h", "105°C"). Ambiguous unit-less forms use
     * the family ({@code 4u7} is an inductance for inductors, a capacitance otherwise). Returns null when not a value.
     *
     * <p>Hours and henry: a lower-case {@code h} (and {@code hrs}, {@code hours}) after a bare number is a lifetime in
     * hours ({@code 2000h}); an upper-case {@code H} is a henry value, except in the text of a part or query whose
     * family is known and not inductive (a capacitor), where {@code 3000H} of at least {@value #MIN_LIFETIME_HOURS}
     * is a lifetime (Mouser "100UF 63V 105C 3000H"). With an SI prefix ({@code uH}, {@code uh}, {@code mH}) it is
     * always henry: hours never carry one.
     */
    static Value value(String token, String family) {
        Matcher m = P_FRACTION_POWER.matcher(token);
        if (m.matches()) {
            double den = Double.parseDouble(m.group(2));
            return den == 0 ? null : of(ParsedQuery.POWER, Double.parseDouble(m.group(1)) / den);
        }
        m = P_HOURS.matcher(token);
        if (m.matches()) {
            return of(ParsedQuery.LIFETIME, Double.parseDouble(m.group(1)));
        }
        m = P_DEGREES.matcher(token);
        if (m.matches()) {
            return of(ParsedQuery.TEMPERATURE, Double.parseDouble(m.group(1)));
        }
        m = P_BARE_CELSIUS.matcher(token);
        if (m.matches()) {
            int degrees = Integer.parseInt(m.group(1));
            return degrees >= 70 && degrees <= 200 ? of(ParsedQuery.TEMPERATURE, degrees) : null;
        }
        m = P_UNIT_VALUE.matcher(token);
        if (m.matches()) {
            double number = Double.parseDouble(m.group(1));
            String prefix = m.group(2);
            String unit = m.group(3).toLowerCase(Locale.ROOT);
            if (unit.equals("r") && !prefix.isEmpty()) {
                return null;
            }
            if (unit.equals("h") && prefix.isEmpty() && family != null && !inductive(family)
                    && number >= MIN_LIFETIME_HOURS) {
                return of(ParsedQuery.LIFETIME, number);   // Mouser "105C 3000H" on a capacitor
            }
            String kind = PartAttribute.unitSymbols().get(unit);   // the declared @Unit symbols
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

    /** Inductors and ferrite beads (an ohm value is impedance or DC resistance there, never a nominal resistance). */
    static boolean inductive(String family) {
        return ComponentFamily.has(family, Trait.INDUCTIVE);
    }

    /**
     * SI prefix multiplier, case-sensitive where the case carries meaning: {@code m} milli, {@code M} mega (also
     * {@code meg}); {@code mhz} is the one tolerated spelling of MHz. Other prefixes are accepted in either case.
     */
    private static Double multiplier(String prefix, String kind) {
        if (prefix.isEmpty()) {
            return 1.0;
        }
        if (prefix.equalsIgnoreCase("meg")) {
            return 1e6;
        }
        if (prefix.equalsIgnoreCase("kilo")) {
            return 1e3;
        }
        return switch (prefix) {
            case "p", "P" -> 1e-12;
            case "n", "N" -> 1e-9;
            case "u", "U" -> 1e-6;
            case "k", "K" -> 1e3;
            case "g", "G" -> 1e9;
            case "m" -> kind.equals(ParsedQuery.FREQUENCY) ? 1e6 : 1e-3;
            case "M" -> 1e6;
            default -> null;
        };
    }

    static Value of(String kind, double value) {
        return new Value(kind, value, display(kind, value));
    }

    /** A value with its test condition (impedance: frequency in Hz; lifetime: temperature in degrees Celsius). */
    static Value of(String kind, double value, Double condition) {
        if (condition == null) {
            return of(kind, value);
        }
        return new Value(kind, value, PartAttribute.display(kind, value, condition), condition);
    }

    /**
     * Compact human form: "10uF", "4.7kohm", "16V", "0.125W", "250W", "1.5kW", "12MHz", "5%", "105°C", "2000h", by the
     * {@code @Unit} declared on {@link PartAttribute} (base unit, prefixes, display rule).
     */
    static String display(String kind, double value) {
        return PartAttribute.display(kind, value);
    }

    // ------------------------------------------------------------------ labelled values (spans)

    private static final String NUMBER = "(\\d+(?:\\.\\d+)?)";
    /**
     * Operating temperature ranges: TME {@code -55...105°C}, {@code -55÷125°C}, LCSC {@code -55℃~+105℃} (NFKC:
     * {@code °C}), {@code -40°C to +85°C}; the upper end is the maximum operating temperature.
     */
    private static final Pattern TEMPERATURE_RANGE = Pattern.compile(
            "(?<![\\d.])[-−]\\s?\\d{1,3}(?:\\.\\d+)?\\s?(?:°C?|℃)?\\s?(?:~|\\.{2,3}|…|÷|to)\\s?\\+?(\\d{1,3}(?:\\.\\d+)?)"
                    + "\\s?(?:°C?|℃|C(?![\\p{L}\\d]))");
    /** Saturation current: {@code Isat 8A}, {@code Isat: 8A}, {@code saturation current 8A}, {@code Isat>=8A}. */
    private static final Pattern ISAT = Pattern.compile(
            "(?<![\\p{L}\\d])(?i:i\\s?sat|saturation(?:\\s+current)?)\\s*(?:[:=]|>=?|≥|min\\.?)?\\s*" + NUMBER
                    + "\\s?(m?)[aA](?![\\p{L}\\d])");
    /** {@code 8A Isat}, {@code 8A saturation current}, {@code 8A (Isat)}. */
    private static final Pattern ISAT_AFTER = Pattern.compile(
            "(?<![\\p{L}\\d.])" + NUMBER + "\\s?(m?)[aA]\\s*\\(?(?i:i\\s?sat|saturation(?:\\s+current)?)\\)?"
                    + "(?![\\p{L}\\d])");
    /** Labelled rated current: {@code Irated 6A}, {@code Ir: 6A}, TME {@code Ioper: 6A}, {@code rated current 6A}. */
    private static final Pattern RATED_CURRENT = Pattern.compile(
            "(?<![\\p{L}\\d])(?i:i\\s?rated|i\\s?rms|i\\s?oper|ir|rated\\s+current|operating\\s+current)\\s*"
                    + "(?:[:=]|>=?|≥)?\\s*" + NUMBER + "\\s?(m?)[aA](?![\\p{L}\\d])");
    /**
     * DC resistance: {@code DCR 20mohm}, {@code DCR=17.2mOhms}, {@code DCR < 20mohm}, {@code DCR<=20mohm},
     * {@code DCR 20mohm max}, {@code max DCR 20mohm}, {@code DC resistance: 28mohm} (Ω is already "ohm").
     */
    private static final Pattern DCR = Pattern.compile(
            "(?<![\\p{L}\\d])(?i:(?:max\\.?\\s+)?(?:dcr|dc\\s+resistance|r\\s?dc))\\s*(?:[:=]|<=?|≤|(?i:max)\\.?)?\\s*"
                    + "(?:(?i:max)\\.?\\s*)?" + NUMBER + "\\s?([mkM]?)(?:(?i:ohms?|r)|[ΩΩ])(?![\\p{L}\\d])(?:\\s*(?i:max)\\b\\.?)?");
    /** {@code 20mohm DCR}, {@code 20mohm max DCR}. */
    private static final Pattern DCR_AFTER = Pattern.compile(
            "(?<![\\p{L}\\d.])" + NUMBER + "\\s?([mkM]?)(?:(?i:ohms?)|[ΩΩ])\\s*(?:(?i:max)\\.?\\s*)?(?i:dcr)(?![\\p{L}\\d])");
    /** "low DCR" is a preference, not a keyword: lower DC resistance ranks higher among otherwise equal parts. */
    private static final Pattern LOW_DCR = Pattern.compile(
            "(?i)(?<![\\p{L}\\d])(?:low(?:est|er)?|ultra[- ]low|very\\s+low)[- ]?dcr(?![\\p{L}\\d])");
    /** Words that only label a lifetime ("2000h lifetime", "endurance 5000h"): not free-text keywords then. */
    private static final Set<String> LIFETIME_WORDS = Set.of("lifetime", "life", "endurance", "service", "load");

    /** Values found by the labelled-span recognisers and the text with those spans blanked out. */
    private record Labelled(Map<String, Value> values, String residual, List<String> preferences) {
    }

    /**
     * Recognises labelled values that a token-by-token reading gets wrong: the maximum of an operating temperature
     * range, saturation and labelled rated currents, DC resistance (a maximum) and the "low DCR" preference. The
     * recognised spans are blanked out of the text.
     */
    private static Labelled labelled(String prepared) {
        Map<String, Value> values = new LinkedHashMap<>();
        List<String> preferences = new ArrayList<>();
        StringBuilder text = new StringBuilder(prepared);
        Matcher m = TEMPERATURE_RANGE.matcher(prepared);
        while (m.find()) {
            values.putIfAbsent(ParsedQuery.TEMPERATURE, of(ParsedQuery.TEMPERATURE, Double.parseDouble(m.group(1))));
            blank(text, m.start(), m.end());
        }
        for (Pattern p : List.of(ISAT, ISAT_AFTER)) {
            m = p.matcher(text);
            while (m.find()) {
                double amps = Double.parseDouble(m.group(1)) * (m.group(2).isEmpty() ? 1 : 1e-3);
                values.putIfAbsent(ParsedQuery.SATURATION_CURRENT, of(ParsedQuery.SATURATION_CURRENT, amps));
                blank(text, m.start(), m.end());
            }
        }
        m = RATED_CURRENT.matcher(text);
        while (m.find()) {
            double amps = Double.parseDouble(m.group(1)) * (m.group(2).isEmpty() ? 1 : 1e-3);
            values.putIfAbsent(ParsedQuery.CURRENT, of(ParsedQuery.CURRENT, amps));
            blank(text, m.start(), m.end());
        }
        for (Pattern p : List.of(DCR, DCR_AFTER)) {
            m = p.matcher(text);
            while (m.find()) {
                double ohms = Double.parseDouble(m.group(1)) * switch (m.group(2)) {
                    case "m" -> 1e-3;
                    case "k" -> 1e3;
                    case "M" -> 1e6;
                    default -> 1.0;
                };
                values.putIfAbsent(ParsedQuery.DCR, of(ParsedQuery.DCR, ohms));
                blank(text, m.start(), m.end());
            }
        }
        m = LOW_DCR.matcher(text);
        while (m.find()) {
            if (!preferences.contains(ParsedQuery.LOW_DCR)) {
                preferences.add(ParsedQuery.LOW_DCR);
            }
            blank(text, m.start(), m.end());
        }
        return new Labelled(values, text.toString(), preferences);
    }

    /**
     * The on-resistance of a MOSFET or transistor in milliohm: {@code 3.2 milliohm}, {@code 270 mohm}, {@code 120mOhm},
     * {@code 70-m?} and {@code 170/248-m?} (Mouser prints {@code ?} where the ohm sign was; of a list the first value).
     * A bare {@code m} ({@code 2.6m}, {@code 185 m}) is too ambiguous and stays unread.
     */
    private static final Pattern RDS_ON = Pattern.compile("(?<![\\p{L}\\d.])" + NUMBER
            + "(?:\\s?/\\s?\\d+(?:\\.\\d+)?)*\\s?-?\\s?(?:(?i:milli-?ohms?)|m\\?|m(?i:ohms?))(?![\\p{L}\\d])");

    /** Families whose ohm value is an on-resistance ({@link #RDS_ON}): MOSFETs and transistors. */
    static boolean rdsOnFamily(String family) {
        return ComponentFamily.has(family, Trait.POLARISED);
    }

    /** Characters stripped around a word before it is read as a value ({@code (16V,} -&gt; {@code 16V}). */
    private static final String WORD_PUNCTUATION = "([{,;:)]}";
    private static final Pattern WORD = Pattern.compile("\\S+");
    private static final Pattern NUMBER_WORD = Pattern.compile("^[+±]?\\d+(?:[.,]\\d+)?$");
    /** A unit on its own ({@code V}, {@code mA}, {@code °C}, {@code hours}): no digits. */
    private static final Pattern UNIT_WORD = Pattern.compile("^[\\p{L}°℃µΩ]+$");
    private static final Map<String, Pattern> LABELLED_KINDS = labelledKinds();

    private static Map<String, Pattern> labelledKinds() {
        Map<String, Pattern> m = new LinkedHashMap<>();
        m.put("temperature-range", TEMPERATURE_RANGE);
        m.put("isat", ISAT);
        m.put("isat-after", ISAT_AFTER);
        m.put("rated-current", RATED_CURRENT);
        m.put("dcr", DCR);
        m.put("dcr-after", DCR_AFTER);
        m.put("low-dcr", LOW_DCR);
        return m;
    }

    private static String labelledKind(String name) {
        return switch (name) {
            case "temperature-range" -> ParsedQuery.TEMPERATURE;
            case "isat", "isat-after" -> ParsedQuery.SATURATION_CURRENT;
            case "rated-current" -> ParsedQuery.CURRENT;
            default -> ParsedQuery.DCR;
        };
    }

    /**
     * Character ranges {@code [start, end)} of the original {@code text} that state a value of one of {@code kinds}:
     * single words ({@code 25V}, {@code 6A}, {@code 105°C}, {@code 2000h}), a number followed by its unit
     * ({@code 25 V}), and the labelled forms ({@code Isat 8A}, {@code DCR < 20mΩ}, {@code low DCR},
     * {@code -40~105°C}). Used to keep minimum ratings out of distributor phrases.
     */
    static List<int[]> valueSpans(String text, String family, Set<String> kinds) {
        List<int[]> spans = new ArrayList<>();
        if (text == null || text.isBlank() || kinds.isEmpty()) {
            return spans;
        }
        LABELLED_KINDS.forEach((name, pattern) -> {
            if (kinds.contains(labelledKind(name))) {
                Matcher m = pattern.matcher(text);
                while (m.find()) {
                    spans.add(new int[] {m.start(), m.end()});
                }
            }
        });
        List<int[]> words = new ArrayList<>();
        Matcher w = WORD.matcher(text);
        while (w.find()) {
            int start = w.start();
            int end = w.end();
            while (start < end && WORD_PUNCTUATION.indexOf(text.charAt(start)) >= 0) {
                start++;
            }
            while (end > start && (WORD_PUNCTUATION + ".").indexOf(text.charAt(end - 1)) >= 0) {
                end--;
            }
            if (start < end) {
                words.add(new int[] {start, end});
            }
        }
        for (int i = 0; i < words.size(); i++) {
            int[] word = words.get(i);
            if (overlaps(spans, word[0], word[1])) {
                continue;
            }
            String core = text.substring(word[0], word[1]);
            Value v = wordValue(core, family);
            if (v != null && kinds.contains(v.kind())) {
                spans.add(word);
                continue;
            }
            if (v == null && i + 1 < words.size() && NUMBER_WORD.matcher(core).matches()
                    && UNIT_WORD.matcher(text.substring(words.get(i + 1)[0], words.get(i + 1)[1])).matches()) {
                int[] next = words.get(i + 1);
                Value pair = wordValue(core + text.substring(next[0], next[1]), family);
                if (pair != null && kinds.contains(pair.kind()) && !overlaps(spans, next[0], next[1])) {
                    spans.add(new int[] {word[0], next[1]});
                    i++;
                }
            }
        }
        return spans;
    }

    /** The value one word states ({@code 2000h@105°C} counts as its lifetime), or null. */
    private static Value wordValue(String word, String family) {
        List<String> tokens = tokenize(prepare(word));
        if (tokens.size() != 1) {
            return null;
        }
        String token = tokens.getFirst();
        int at = token.indexOf('@');
        return value(at > 0 ? token.substring(0, at) : token, family);
    }

    private static boolean overlaps(List<int[]> spans, int start, int end) {
        for (int[] s : spans) {
            if (start < s[1] && s[0] < end) {
                return true;
            }
        }
        return false;
    }

    private static void blank(StringBuilder text, int start, int end) {
        for (int i = start; i < end; i++) {
            text.setCharAt(i, ' ');
        }
    }

    // ------------------------------------------------------------------ analysis

    private static final Set<String> STOPWORDS = Set.of("a", "an", "the", "for", "with", "and", "or", "of", "in",
            "on", "to", "pcs", "pc", "type", "rohs", "new");

    private static final List<String> VALUE_ORDER = List.of(ParsedQuery.CAPACITANCE, ParsedQuery.RESISTANCE,
            ParsedQuery.INDUCTANCE, ParsedQuery.IMPEDANCE, ParsedQuery.FREQUENCY, ParsedQuery.VOLTAGE,
            ParsedQuery.CURRENT, ParsedQuery.SATURATION_CURRENT, ParsedQuery.DCR, ParsedQuery.POWER,
            ParsedQuery.TEMPERATURE, ParsedQuery.LIFETIME, ParsedQuery.TOLERANCE);

    /** A value with the token it was read from. */
    private record Read(Value value, String token) {
    }

    /** Extracts every recognisable feature from a free text (query or part description). */
    static Analysis analyze(String text) {
        return analyze(text, null);
    }

    /**
     * As {@link #analyze(String)}; when the text names no family itself, {@code familyHint} (e.g. the family of the
     * part's category: an LCSC ferrite bead description says only {@code 120Ω@100MHz 30mΩ 3A}) decides how values are
     * read. The hint is not reported as an explicit family.
     */
    static Analysis analyze(String text, String familyHint) {
        String prepared = text == null ? "" : prepare(text);
        Labelled labelled = labelled(prepared);
        String residual = labelled.residual();
        List<String> tokens = text == null ? List.of() : tokenize(residual);

        // pass 1: explicit family (highest priority wins, first on ties)
        String family = null;
        int priority = -1;
        for (String token : tokens) {
            String t = token.toLowerCase(Locale.ROOT);
            FamilyWord fw = FAMILY_WORDS.get(t);
            if (fw == null && DIODE_MPN.matcher(t).matches()) {
                fw = new FamilyWord(DIODE.label(), 0, true);
            }
            if (fw != null && fw.priority() > priority) {
                family = fw.family();
                priority = fw.priority();
            }
        }
        boolean explicit = family != null;
        if (family == null) {
            family = familyHint;
        }

        // pass 2: everything else
        Map<String, Value> values = new LinkedHashMap<>();
        if (text != null && rdsOnFamily(family)) {
            // R_DS(on) spelled "3.2 milliohm", "170/248-m?" (Mouser's lost ohm sign), "120mOhm": the resistance
            Matcher r = RDS_ON.matcher(residual);
            if (r.find()) {
                values.put(ParsedQuery.RESISTANCE, of(ParsedQuery.RESISTANCE, Double.parseDouble(r.group(1)) * 1e-3));
                StringBuilder blanked = new StringBuilder(residual);
                blank(blanked, r.start(), r.end());
                tokens = tokenize(blanked.toString());
            }
        }
        List<Read> ohms = new ArrayList<>();       // resolved after the family is final (impedance / DCR / resistance)
        List<Read> currents = new ArrayList<>();   // inductors: several unlabelled currents (JLCPCB lists two)
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
            if (conditioned(token, family, values, ohms)) {
                continue;
            }
            Value v = value(token, family);
            if (v != null) {
                if (v.kind().equals(ParsedQuery.RESISTANCE)) {
                    ohms.add(new Read(v, t));
                } else if (v.kind().equals(ParsedQuery.CURRENT) && inductive(family)) {
                    currents.add(new Read(v, t));
                } else if (!values.containsKey(v.kind())) {
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
            } else if (!ohms.isEmpty() && ohms.getFirst().value().condition() == null) {
                family = "resistor";
            } else if (values.containsKey(ParsedQuery.INDUCTANCE)) {
                family = "inductor";
            }
        }
        resolveOhms(family, values, ohms, keywords);
        resolveCurrents(family, values, currents, labelled.values());
        labelled.values().forEach(values::putIfAbsent);
        if (values.containsKey(ParsedQuery.LIFETIME)) {
            keywords.removeAll(LIFETIME_WORDS);
        }
        for (String t : deferredSizes) {
            // a crystal size for crystals and oscillators; a bare metric code is never a package (imperial rule)
            String resolved = frequencyFamily(family) && CRYSTAL_SIZES.contains(t) ? t : null;
            if (resolved != null && packageName == null) {
                packageName = resolved;
            } else if (resolved == null || !resolved.equals(packageName)) {
                keywords.add(t);
            }
        }

        String technology = null;
        TechnologyVocabulary.Match tech = text == null ? null : TechnologyVocabulary.find(prepared, family);
        if (tech != null && FAMILY_WORDS.containsKey(prepared.substring(tech.start(), tech.end())
                .toLowerCase(Locale.ROOT))) {
            tech = null;   // "MLCC" alone is the family word (scored lexically), not a technology request
        }
        if (tech != null) {
            technology = tech.technology();
            // the technology words are a typed attribute now, not free text ("mlcc" stays: it is a family word)
            for (String word : tokenize(prepared.substring(tech.start(), tech.end()))) {
                String w = word.toLowerCase(Locale.ROOT);
                if (!FAMILY_WORDS.containsKey(w)) {
                    keywords.remove(w);
                }
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
        return new Analysis(family, explicit, ordered, dielectric, packageName, mounting, normalizedKeywords,
                technology, labelled.preferences());
    }

    /**
     * A token with a test condition, {@code <value>@<condition>}: LCSC {@code 120Ω@100MHz} (impedance of a ferrite
     * bead; for a capacitor the ESR, ignored), {@code 2000hrs@105℃} (lifetime at a temperature), {@code 4.1A@100kHz}
     * (ripple current, ignored: not a current rating). True when the token was consumed.
     */
    private static boolean conditioned(String token, String family, Map<String, Value> values, List<Read> ohms) {
        int at = token.indexOf('@');
        if (at <= 0 || at >= token.length() - 1) {
            return false;
        }
        Value left = value(token.substring(0, at), family);
        if (left == null) {
            return false;
        }
        Value right = value(token.substring(at + 1).replaceAll("^[(]|[)]$", ""), family);
        if (left.kind().equals(ParsedQuery.LIFETIME)) {
            Double temperature = right != null && right.kind().equals(ParsedQuery.TEMPERATURE) ? right.value() : null;
            values.putIfAbsent(ParsedQuery.LIFETIME, of(ParsedQuery.LIFETIME, left.value(), temperature));
        } else if (left.kind().equals(ParsedQuery.RESISTANCE) && right != null
                && right.kind().equals(ParsedQuery.FREQUENCY)) {
            ohms.add(new Read(new Value(ParsedQuery.RESISTANCE, left.value(), left.display(), right.value()),
                    token.toLowerCase(Locale.ROOT)));
        }
        return true;
    }

    /**
     * Ohm values by family: a ferrite bead's first value of at least 1 ohm (or one with a test frequency) is its
     * impedance and a smaller one its DC resistance; an inductor's ohm value is its DC resistance; neither has a
     * nominal resistance. Other families keep the first plain value as the resistance (an ohm value with a test
     * frequency, a capacitor's ESR, is ignored) and further ones are keywords.
     */
    private static void resolveOhms(String family, Map<String, Value> values, List<Read> ohms, List<String> keywords) {
        if (inductive(family)) {
            for (Read r : ohms) {
                Value v = r.value();
                if ("ferrite".equals(family) && !values.containsKey(ParsedQuery.IMPEDANCE)
                        && (v.condition() != null || v.value() >= 1)) {
                    values.put(ParsedQuery.IMPEDANCE, of(ParsedQuery.IMPEDANCE, v.value(), v.condition()));
                } else if (v.condition() == null) {
                    values.putIfAbsent(ParsedQuery.DCR, of(ParsedQuery.DCR, v.value()));
                }
            }
            Value impedance = values.get(ParsedQuery.IMPEDANCE);
            Value frequency = values.get(ParsedQuery.FREQUENCY);
            if (impedance != null && frequency != null) {
                // "120 ohm 100MHz ferrite bead", TME "Imp.@ 100MHz: 120Ω": the frequency is the test frequency
                if (impedance.condition() == null) {
                    values.put(ParsedQuery.IMPEDANCE, of(ParsedQuery.IMPEDANCE, impedance.value(), frequency.value()));
                }
                values.remove(ParsedQuery.FREQUENCY);
            }
            return;
        }
        for (Read r : ohms) {
            if (r.value().condition() != null) {
                continue;
            }
            if (!values.containsKey(ParsedQuery.RESISTANCE)) {
                values.put(ParsedQuery.RESISTANCE, r.value());
            } else {
                keywords.add(r.token());
            }
        }
    }

    /**
     * Currents of an inductor or ferrite bead: a labelled rated current ({@code Ioper: 6A}) wins; otherwise one
     * unlabelled current is the rated current, and of several unlabelled ones (JLCPCB lists rated and saturation
     * current without labels, in no fixed order) the lowest is used as the rated current, which is conservative.
     */
    private static void resolveCurrents(String family, Map<String, Value> values, List<Read> currents,
                                        Map<String, Value> labelled) {
        if (currents.isEmpty() || labelled.containsKey(ParsedQuery.CURRENT)) {
            return;
        }
        Value lowest = currents.stream().map(Read::value).min(java.util.Comparator.comparingDouble(Value::value))
                .orElseThrow();
        Value saturation = labelled.get(ParsedQuery.SATURATION_CURRENT);
        if (saturation != null && currents.size() > 1) {
            // the labelled saturation current is one of them: the rated one is the other
            lowest = currents.stream().map(Read::value)
                    .filter(v -> Math.abs(v.value() - saturation.value()) > 1e-9)
                    .min(java.util.Comparator.comparingDouble(Value::value)).orElse(lowest);
        }
        values.putIfAbsent(ParsedQuery.CURRENT, lowest);
    }

    private static final Pattern RANGE_DASH = Pattern.compile("(?<=\\d[a-zA-Z]{0,3})\\s*[-–]\\s*(?=\\d)");

    /**
     * Every single value of {@code kind} a text states, in text order: words that are a range ({@code 1.2V~37V},
     * {@code 4.8V~5.4V}) or carry a condition ({@code 100nA@0.8V}, {@code 1.1V@(800mA)}) are left out.
     */
    static List<Double> singleValues(String text, String kind, String family) {
        // "1.8V - 3.3V" (Mouser) is a range too
        List<Double> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String word : WHITESPACE.split(RANGE_DASH.matcher(prepare(text)).replaceAll("~"))) {
            if (word.indexOf('~') >= 0 || word.indexOf('@') >= 0 || word.indexOf('÷') >= 0 || word.contains("...")) {
                continue;
            }
            for (String token : tokenize(word)) {
                Value v = value(token, family);
                if (v != null && v.kind().equals(kind)) {
                    out.add(v.value());
                }
            }
        }
        return out;
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
