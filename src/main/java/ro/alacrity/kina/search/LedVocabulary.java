package ro.alacrity.kina.search;

import lombok.experimental.UtilityClass;
import ro.alacrity.kina.domain.ComponentFamily;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Vocabulary;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LED vocabulary shared by {@link QueryParser}, {@link Recognizers} and {@link ParametricExtractor} (DESIGN.md 3.4
 * "LEDs"): the colour of the light, the lens, the LED type, the orientation, the LED package names and the spellings of
 * the LED units the value tokens need ({@code 120°}, {@code 470 nm}, ranges such as {@code 1.8÷2.6V} and
 * {@code 620nm~630nm}). Read only in texts of the LED family. Stateless and thread-safe.
 *
 * <p>Wording seen in the JLCPCB database (2026-10-07): categories {@code LED Indication - Discrete},
 * {@code Infrared LED Emitters}, {@code RGB LEDs}, {@code RGB LEDs(Built-in IC)}, {@code Ultraviolet LEDs (UVLED)};
 * descriptions {@code -40℃~+85℃ 120° 2.3V 20mA 225mcd 50mW 620nm~630nm 625nm Discrete Diode Red Top-mount Water Clear}
 * and {@code 3.4V~4V 30° 3mm Round Lens 468nm 470nm Blue Frosted White Lens}; packages {@code 0603},
 * {@code Plugin,D=5mm}, {@code SMD5050-6P}, {@code SMD2835-2P}, {@code PLCC-4(2.6x3)}.
 */
@UtilityClass
class LedVocabulary {

    static final String LED = ComponentFamily.LED.label();

    /** An LED's attributes in a text, the package it names and the lower-case tokens that stated them. */
    record Analysis(ParsedQuery.Led led, String packageName, Set<String> consumed) {
    }

    private static final String BEFORE = "(?<![\\p{L}\\d])";
    private static final String AFTER = "(?![\\p{L}\\d])";

    // ---------------------------------------------------------------- units

    /** The LED units of a range (a value on each side; the right one carries the unit). */
    private static final String UNIT = "(?:nm|mcd|cd|lm|v|k(?![\\p{L}])|°(?![cCfF])|deg)";
    /**
     * A range of LED values: TME {@code 1.8÷2.6V}, {@code 30÷60mcd}, JLCPCB {@code 620nm~630nm}, {@code 630mcd~1.25cd}.
     */
    private static final Pattern RANGE = Pattern.compile("(?i)(?<![\\d.\\-−])(\\d+(?:\\.\\d+)?)\\s?(" + UNIT + ")?\\s?"
            + "(?:~|÷|…|\\.{2,3}|-|to)\\s?\\+?(\\d+(?:\\.\\d+)?)\\s?(" + UNIT + ")" + AFTER);
    /** LED unit spellings rewritten to one token each. */
    private static final List<Map.Entry<Pattern, String>> UNIT_SPELLINGS = List.of(
            // JLCPCB lists values with the ideographic comma: "R:20mA、G:20mA"
            Map.entry(Pattern.compile("、"), " "),
            // the radiant intensity of an IR emitter ("3mW/sr@IF=20mA") is no power and its condition no current
            Map.entry(Pattern.compile("(?i)\\d+(?:\\.\\d+)?\\s?mw\\s?/\\s?sr(?:@\\S*)?"), " "),
            // a viewing angle: "120°", "120 deg", "120 degrees", "2θ1/2=120°" (never "85°C")
            Map.entry(Pattern.compile("(?i)(\\d)\\s?(?:°(?![cCfF])|deg(?:rees?)?" + AFTER + ")"), "$1deg"),
            Map.entry(Pattern.compile("(?i)(\\d)\\s+(?=(?:nm|mcd|cd|lm|k)" + AFTER + ")"), "$1"),
            // "T-1 3/4" is the 5 mm lamp, "T-1" the 3 mm one; "Ø5mm", "D=5mm", "φ3mm" a round lamp
            Map.entry(Pattern.compile("(?i)" + BEFORE + "T-?1\\s?3/4" + AFTER), "5mm"),
            Map.entry(Pattern.compile("(?i)" + BEFORE + "T-?1" + AFTER + "(?!\\s?3/4)"), "3mm"),
            Map.entry(Pattern.compile("(?i)(?:[Øøφ]|D=)\\s?(\\d+(?:\\.\\d+)?)\\s?mm"), " $1mm"),
            Map.entry(Pattern.compile("(?i)(\\d)\\s+mm" + AFTER), "$1mm"));

    /**
     * {@code prepared} with the LED units in their token spelling: a viewing angle {@code 120°} as {@code 120deg} (so it
     * is no temperature), {@code 470 nm} as {@code 470nm}, the radiant intensity of an IR emitter dropped, and a range
     * as its upper end ({@code 1.8÷2.6V} -&gt; {@code 2.6V}: the most an LED needs or the brightest it is binned at),
     * a wavelength range as its centre ({@code 620nm~630nm} -&gt; {@code 625nm}). For texts of the LED family only.
     */
    static String normaliseUnits(String prepared) {
        if (prepared == null || prepared.isEmpty()) {
            return prepared;
        }
        String s = prepared;
        for (Map.Entry<Pattern, String> spelling : UNIT_SPELLINGS) {
            s = spelling.getKey().matcher(s).replaceAll(spelling.getValue());
        }
        Matcher m = RANGE.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String unit = m.group(4).toLowerCase(Locale.ROOT);
            String left = m.group(2) == null ? unit : m.group(2).toLowerCase(Locale.ROOT);
            String replacement;
            if (unit.equals("nm") && left.equals("nm")) {
                double centre = (Double.parseDouble(m.group(1)) + Double.parseDouble(m.group(3))) / 2;
                replacement = java.math.BigDecimal.valueOf(centre).stripTrailingZeros().toPlainString() + "nm";
            } else if (unit.equals("°") || unit.equals("deg")) {
                replacement = m.group(3) + "deg";
            } else {
                replacement = m.group(3) + m.group(4);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ---------------------------------------------------------------- colour

    /** Colour words, most specific first, and their canonical colour. */
    private static final List<Map.Entry<Pattern, String>> COLOURS = List.of(
            Map.entry(word("rgbw|rgb\\s?\\+\\s?w|rgb-w"), "RGBW"),
            Map.entry(word("rgb|full[- ]colou?r|multi[- ]?colou?r"), "RGB"),
            Map.entry(word("bi[- ]?colou?r|two[- ]colou?r|dual[- ]colou?r"), "bi-colour"),
            Map.entry(word("tri[- ]?colou?r|three[- ]colou?r"), "tri-colour"),
            Map.entry(word("warm[- ]?white"), "warm white"),
            Map.entry(word("neutral[- ]?white|natural[- ]?white|nature[- ]?white"), "neutral white"),
            Map.entry(word("cool[- ]?white|cold[- ]?white|pure[- ]?white|daylight(?:[- ]?white)?"), "cool white"),
            Map.entry(word("yellow[- ]?green|yellowish[- ]green"), "yellow green"),
            Map.entry(word("white"), "white"),
            Map.entry(word("(?:emerald|pure|true|super)?[- ]?green"), "green"),
            Map.entry(word("(?:super|hyper|deep|bright)?[- ]?red"), "red"),
            Map.entry(word("(?:royal|deep|sky)?[- ]?blue"), "blue"),
            Map.entry(word("yellow"), "yellow"),
            Map.entry(word("amber"), "amber"),
            Map.entry(word("orange"), "orange"),
            Map.entry(word("pink|magenta"), "pink"),
            Map.entry(word("purple|violet"), "purple"),
            Map.entry(word("ultra[- ]?violet|uv[- ]?[abc]?|uvled"), "UV"),
            Map.entry(word("infra[- ]?red|ir(?!\\s?[=:@])"), "IR"));
    /** Colours that are a single colour (two of them make a bi-colour LED). */
    private static final Set<String> SINGLE = Set.of("red", "green", "blue", "yellow", "amber", "orange", "pink",
            "purple", "yellow green", "white", "warm white", "neutral white", "cool white");
    /** A lens colour ({@code Red Lens}, {@code Frosted White Lens}, {@code Lens Color: Red}): no colour of the light. */
    private static final Pattern LENS_COLOUR = Pattern.compile("(?i)" + BEFORE
            + "(?:(?:water\\s)?clear|frosted|diffused|milky|tinted)?\\s?[\\p{L}]+\\s(?:diffused\\s)?lens"
            + AFTER + "|" + BEFORE + "lens(?:\\scolou?r)?\\s?[:=]?\\s?[\\p{L}]+(?:\\s[\\p{L}]+)?");

    private static Pattern word(String alternatives) {
        return Pattern.compile("(?i)" + BEFORE + "(?:" + alternatives + ")" + AFTER);
    }

    /**
     * The colour of the light a text names: RGBW, RGB, bi-colour or tri-colour when it says so, else its one colour
     * word (two different ones make a bi-colour LED, more a tri-colour one), a lens colour left out ({@code Blue Frosted
     * White Lens} is a blue LED); null when it names none.
     */
    static String colour(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = LENS_COLOUR.matcher(text).replaceAll(" ");
        Set<String> found = new LinkedHashSet<>();
        for (Map.Entry<Pattern, String> c : COLOURS) {
            Matcher m = c.getKey().matcher(s);
            if (m.find()) {
                if (!SINGLE.contains(c.getValue())) {
                    return c.getValue();
                }
                found.add(c.getValue());
                s = c.getKey().matcher(s).replaceAll(" ");   // "warm white" is no "white" too
            }
        }
        if (found.containsAll(Set.of("red", "green", "blue"))) {
            return found.stream().anyMatch(f -> f.endsWith("white")) ? "RGBW" : "RGB";   // Mouser "Red, Green, Blue"
        }
        if (found.size() > 1 && found.stream().allMatch(f -> f.endsWith("white"))) {
            return found.iterator().next();
        }
        return found.isEmpty() ? null : found.size() == 1 ? found.iterator().next()
                : found.size() == 2 ? "bi-colour" : "tri-colour";
    }

    /**
     * The colour band of a wavelength in nanometres (DESIGN.md 3.4 "LEDs"): 620 to 645 red, 585 to 600 yellow
     * (amber), 515 to 540 green, 460 to 480 blue, 395 to 410 UV, 840 to 950 IR; null between the bands.
     */
    static String band(double nm) {
        if (nm >= 620 && nm <= 645) {
            return "red";
        }
        if (nm >= 585 && nm <= 600) {
            return "yellow";
        }
        if (nm >= 515 && nm <= 540) {
            return "green";
        }
        if (nm >= 460 && nm <= 480) {
            return "blue";
        }
        if (nm >= 395 && nm <= 410) {
            return "UV";
        }
        return nm >= 840 && nm <= 950 ? "IR" : null;
    }

    // ---------------------------------------------------------------- lens, type, orientation

    private static final Pattern DIFFUSED = word("diffused|diffuse|milky|frosted|white[- ]diffused");
    private static final Pattern TINTED = word("tinted|colou?red[- ]lens|[\\p{L}]+[- ]lens");
    private static final Pattern CLEAR = word("water[- ]?clear|clear|transparent");

    /** {@code diffused}, {@code tinted} or {@code clear}: the lens a text names (diffused before tinted), else null. */
    static String lens(String text) {
        if (text == null) {
            return null;
        }
        if (DIFFUSED.matcher(text).find()) {
            return "diffused";
        }
        Matcher tinted = TINTED.matcher(text);
        while (tinted.find()) {
            String w = tinted.group().toLowerCase(Locale.ROOT);
            if (!w.startsWith("clear") && !w.startsWith("round") && !w.startsWith("flat") && !w.startsWith("dome")
                    && !w.startsWith("water") && !w.startsWith("transparent") && !w.startsWith("cylindrical")
                    && !w.startsWith("square") && !w.startsWith("no")) {
                return "tinted";
            }
        }
        return CLEAR.matcher(text).find() ? "clear" : null;
    }

    /** LED type words, most specific first, and their type. */
    private static final List<Map.Entry<Pattern, String>> TYPES = List.of(
            Map.entry(word("ws28\\d\\d[a-z]{0,2}|sk68\\d\\d[a-z\\-0-9]{0,6}|sk9822|apa10[2-9][a-z\\-0-9]{0,4}"
                    + "|neopixels?|addressable|built-in ic|built-in driver|integrated ic|intelligent control"
                    + "|smart (?:rgb )?led|digital rgb"), ParsedQuery.Led.ADDRESSABLE),
            Map.entry(word("led[- ]?strips?|strip[- ]lights?|light[- ]strips?|led[- ]tapes?"), "strip"),
            Map.entry(word("laser(?:[- ]diodes?)?"), "laser"),
            Map.entry(word("receivers?|photo[- ]?diodes?|photo[- ]?transistors?|photo[- ]?interrupters?"
                    + "|opto[- ]?couplers?|photo[- ]?couplers?|light sensors?|ambient light"), "receiver"),
            Map.entry(word("segment displays?|\\d-segment|seven[- ]segment|dot[- ]matrix|bar[- ]?graph|digital tubes?"
                    + "|led displays?|display modules?"),
                    "display"),
            Map.entry(word("drivers?|led drivers?|constant current"), "driver"),
            Map.entry(word("high[- ]power|power leds?|\\d+(?:\\.\\d+)?\\s?w star|star pcb|star board"),
                    ParsedQuery.Led.HIGH_POWER),
            Map.entry(word("indicators?|indication|status|pcb leds?"), ParsedQuery.Led.INDICATOR));

    /**
     * The LED type a text names. With {@code partDefaults} (a part's description or category) a text that names none
     * is an indicator LED (a plain emitter); a request's type is only what it says, and the plain words
     * ({@code indicator}, {@code status}) name no type of a request.
     */
    static String type(String text, boolean partDefaults) {
        if (text != null) {
            for (Map.Entry<Pattern, String> t : TYPES) {
                if (t.getKey().matcher(text).find()) {
                    if (!partDefaults && ParsedQuery.Led.INDICATOR.equals(t.getValue())) {
                        return null;
                    }
                    return t.getValue();
                }
            }
        }
        return partDefaults ? ParsedQuery.Led.INDICATOR : null;
    }

    private static final Pattern RIGHT_ANGLE = word("right[- ]?angle(?:d)?|side[- ]?view|side[- ]?emitting"
            + "|side[- ]?looking|side[- ]?mount");
    private static final Pattern REVERSE = word("reverse[- ]?mount(?:ed|ing)?");
    private static final Pattern TOP = word("top[- ]?view|top[- ]?mount|top[- ]?emitting|top[- ]?looking|vertical");

    /** {@code right angle}, {@code reverse mount} or {@code vertical} (top view), else null. */
    static String orientation(String text) {
        if (text == null) {
            return null;
        }
        if (RIGHT_ANGLE.matcher(text).find()) {
            return ParsedQuery.RIGHT_ANGLE;
        }
        if (REVERSE.matcher(text).find()) {
            return "reverse mount";
        }
        return TOP.matcher(text).find() ? ParsedQuery.VERTICAL : null;
    }

    // ---------------------------------------------------------------- packages

    /**
     * LED package names (the body in tenths of a millimetre): never a metric chip code to convert, and never read as
     * one ({@code 3528} is no {@code 1411}).
     */
    static final Set<String> SIZE_CODES = Set.of("3528", "5050", "2835", "3014", "5730", "5630", "3030", "3535",
            "2020", "4014", "7030", "1515");
    /** Through-hole lamps by diameter (and the rectangular 2x5x7 mm lamp). */
    static final Set<String> LAMPS = Set.of("1.8mm", "3mm", "4mm", "5mm", "8mm", "10mm", "2x5x7mm");
    private static final Pattern SIZE_CODE = Pattern.compile("(?i)(?<![\\d.])(?:smd|led)?-?(" + String.join("|",
            SIZE_CODES) + ")(?:-\\d{1,2}p|p\\d|-[a-z]+)?(?![\\d.])");
    private static final Pattern LAMP = Pattern.compile("(?i)(?<![\\d.x×*])(1\\.8|3|4|5|8|10)\\s?mm(?![\\d.x×*])");
    private static final Pattern RECTANGULAR = Pattern.compile("(?i)(?<![\\d.])2\\s?[x×*]\\s?5\\s?[x×*]\\s?7\\s?(?:mm)?");
    private static final Pattern PLCC = Pattern.compile("(?i)" + BEFORE + "plcc-?([246])" + AFTER);

    /** Words that make a millimetre size in a part's description a through-hole lamp ({@code 5mm round lamp head}). */
    private static final Pattern LAMP_CUE = word("round|lamp|through[- ]?hole|tht|plugin|插件|dip|radial");
    /** Longest attribute value whose bare millimetre size is a lamp ({@code 5 mm (T-1 3/4)}). */
    private static final int SHORT_VALUE = 24;

    /**
     * The LED package a text names: an LED size code ({@code 5050}, {@code SMD2835-2P} -&gt; {@code 2835}; before
     * {@code PLCC6} in TME's {@code 5050,PLCC6}), a PLCC package ({@code PLCC-4}), a through-hole lamp ({@code 5mm},
     * {@code T-1 3/4}, {@code Plugin,D=5mm}, {@code 2x5x7mm}); null when none. Chip codes ({@code 0603}) are read by
     * the general package recognition. A part's text names a lamp only in a short attribute value or next to a
     * through-hole word ({@code 5mm round lamp head}): JLCPCB lists the body dimensions of SMD LEDs as bare
     * {@code 5mm 5mm}.
     */
    static String packageName(String text) {
        return packageName(text, false);
    }

    private static String packageName(String text, boolean part) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = normaliseUnits(text);
        Matcher m = SIZE_CODE.matcher(s);
        if (m.find()) {
            return m.group(1);
        }
        m = PLCC.matcher(s);
        if (m.find()) {
            return "PLCC-" + m.group(1);
        }
        if (RECTANGULAR.matcher(s).find()) {
            return "2x5x7mm";
        }
        if (part && s.length() > SHORT_VALUE && !LAMP_CUE.matcher(s).find()) {
            return null;
        }
        m = LAMP.matcher(s);
        return m.find() ? m.group(1) + "mm" : null;
    }

    /** True for an LED package name ({@code 5050}, {@code 5mm}): a package KINA recognises. */
    static boolean isPackage(String name) {
        if (name == null) {
            return false;
        }
        String n = name.strip().toLowerCase(Locale.ROOT);
        return SIZE_CODES.contains(n) || LAMPS.contains(n);
    }

    /** THT for a through-hole lamp, SMD for an LED size code; null for anything else. */
    static String mounting(String packageName) {
        if (packageName == null) {
            return null;
        }
        String n = packageName.strip().toLowerCase(Locale.ROOT);
        return LAMPS.contains(n) ? "THT" : SIZE_CODES.contains(n) ? "SMD" : null;
    }

    // ---------------------------------------------------------------- analysis

    /** Words that only label an LED value ({@code Vf 3.2V}, {@code If 20mA}, {@code 2θ1/2}). */
    private static final Pattern LABELS = word("vf|if|iv|2θ1/2|θ|lumens?|cct|wavelength|viewing angle|angle"
            + "|forward voltage|forward current|luminous intensity|colou?r temperature");
    /** The spans whose tokens the vocabulary reads (never free-text keywords of an LED request). */
    private static final List<Pattern> CONSUMED = List.of(DIFFUSED, TINTED, CLEAR, RIGHT_ANGLE, REVERSE, TOP,
            LENS_COLOUR, SIZE_CODE, PLCC, RECTANGULAR, LAMP, LABELS, word("lens|lamp|smd|tht|leds?"),
            union(COLOURS), union(TYPES));

    private static Pattern union(List<Map.Entry<Pattern, String>> words) {
        return Pattern.compile("(?i)" + String.join("|", words.stream()
                .map(e -> "(?:" + e.getKey().pattern().replaceFirst("^\\(\\?i\\)", "") + ")").toList()));
    }

    /**
     * The LED attributes of a text of the LED family. With {@code partDefaults} (a part's description or category) a
     * text that names no type is an indicator LED; a request's type is only what it says.
     */
    static Analysis analyze(String text, boolean partDefaults) {
        if (text == null || text.isBlank()) {
            return new Analysis(new ParsedQuery.Led(null, null, type(null, partDefaults), null), null, Set.of());
        }
        String prepared = normaliseUnits(Recognizers.prepare(text));
        ParsedQuery.Led led = new ParsedQuery.Led(colour(prepared), lens(prepared), type(prepared, partDefaults),
                orientation(prepared));
        Set<String> consumed = new LinkedHashSet<>();
        for (Pattern p : CONSUMED) {
            Matcher m = p.matcher(prepared);
            while (m.find()) {
                Recognizers.tokenize(m.group()).forEach(t -> consumed.add(Recognizers.normalizeKey(t)));
            }
        }
        return new Analysis(led, packageName(prepared), Set.copyOf(consumed));
    }

    /**
     * The word of an LED vocabulary in a part's attribute value, description or category ({@link ParametricExtractor}
     * sources): the colour, the lens, the type with the part default (an indicator LED), the orientation, the
     * package; null when the text names none.
     */
    static String word(Vocabulary vocabulary, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String prepared = normaliseUnits(Recognizers.prepare(text));
        return switch (vocabulary) {
            case LED_COLOUR -> colour(prepared);
            case LENS -> lens(prepared);
            case LED_TYPE -> type(prepared, true);
            case LED_ORIENTATION -> orientation(prepared);
            case LED_PACKAGE -> packageName(prepared, true);
            default -> null;
        };
    }

    /** Every token of {@code text} an LED request reads as a value word: the LED words of {@link #analyze}. */
    static List<String> consumed(String text) {
        return new ArrayList<>(analyze(text, false).consumed());
    }
}
