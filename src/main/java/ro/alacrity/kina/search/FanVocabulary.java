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
 * Fan vocabulary shared by {@link QueryParser}, {@link Recognizers} and {@link ParametricExtractor} (DESIGN.md 3.4
 * "Fans"): the fan type ({@value ParsedQuery#AXIAL} or {@value ParsedQuery#RADIAL}), the supply ({@value ParsedQuery#DC}
 * or {@value ParsedQuery#AC}), the frame size, the bearing and the features, and the spellings of the fan units that
 * the value tokens need ({@code m³/h}, {@code r/min}, {@code dB(A)}, {@code mmH₂O}). Read only in texts of the fan
 * family: {@code axial} and {@code radial} name a capacitor's leads elsewhere ({@link Recognizers} mounting words).
 * Stateless and thread-safe.
 *
 * <p>Wording seen live and in the JLCPCB database (2026-10-07): Mouser categories {@code DC Fans}, {@code AC Fans},
 * {@code Blowers}, descriptions {@code DC Fans 40x40x10mm 12VDC 0.06A 8.1CFM 5000RPM}; TME {@code Fan: DC; axial;
 * 12VDC; 40x40x10mm; 8.5m3/h; 23dBA; ball bearing; 4500rpm}; JLCPCB category {@code Cooling fan}, descriptions
 * {@code 0.14A 0.7W 1.01CFM 17.8dB(A) 25mm×25mm×10mm 5V 8500RPM DC cooling fan} and {@code 50*50*20mm}.
 */
@UtilityClass
class FanVocabulary {

    static final String FAN = ComponentFamily.FAN.label();

    /** A fan's attributes in a text, and the lower-case tokens that stated them (not free-text keywords). */
    record Analysis(ParsedQuery.Fan fan, Set<String> consumed) {
    }

    // ---------------------------------------------------------------- units

    /** Fan unit spellings rewritten to one token each, so {@code /} and brackets do not split them. */
    private static final List<Map.Entry<Pattern, String>> UNIT_SPELLINGS = List.of(
            // JLCPCB lists values with the ideographic comma: "、37.7dB(A) 0.14A"
            Map.entry(Pattern.compile("、"), " "),
            // TME writes "13.52m<sup>3</sup>/h" and "4.83mm H<sub>2</sub>O"
            Map.entry(Pattern.compile("(?i)<su[bp]>\\s?(\\w+)\\s?</su[bp]>"), "$1"),
            // TME "4200 (±10%)rpm": the tolerance of a rated value is no value of its own
            Map.entry(Pattern.compile("\\s?\\(\\s?±\\s?\\d+(?:\\.\\d+)?\\s?%\\s?\\)\\s?(?=\\p{L})"), ""),
            // a speed range is its upper end, the rated speed (TME "Rotational rate/speed: 0...2000rpm", a PWM fan)
            Map.entry(Pattern.compile("(?i)(?<![\\d.])\\d+(?:\\.\\d+)?\\s?(?:\\.{2,3}|…|÷|~|-|to)\\s?(\\d+(?:\\.\\d+)?)\\s?"
                    + "(?=rpm(?![\\p{L}\\d]))"), "$1"),
            // Mouser 0.25"H2O: inches of water
            Map.entry(Pattern.compile("(?i)(\\d)\\s?(?:\"|''|”|″)\\s?h2o(?![\\p{L}\\d])"), "$1inH2O"),
            Map.entry(Pattern.compile("(?i)m\\^?3\\s?/\\s?h(?:r|our)?(?![\\p{L}\\d])"), "m3h"),
            Map.entry(Pattern.compile("(?i)m\\^?3\\s?/\\s?min(?![\\p{L}\\d])"), "m3min"),
            Map.entry(Pattern.compile("(?i)(?<![\\p{L}])l\\s?/\\s?min(?![\\p{L}\\d])|(?<![\\p{L}])lpm(?![\\p{L}\\d])"),
                    "lmin"),
            Map.entry(Pattern.compile("(?i)ft\\^?3\\s?/\\s?min(?![\\p{L}\\d])"), "cfm"),
            Map.entry(Pattern.compile("(?i)(?<![\\p{L}])r\\s?/\\s?min(?![\\p{L}\\d])"), "rpm"),
            Map.entry(Pattern.compile("(?i)db\\s?\\(\\s?a\\s?\\)"), "dBA"),
            Map.entry(Pattern.compile("(?i)mm\\s?(?:h2o|aq|w\\.?c\\.?)(?![\\p{L}\\d])"), "mmH2O"),
            Map.entry(Pattern.compile("(?i)(?<![\\p{L}])in(?:ch(?:es)?)?\\.?\\s?(?:h2o|w\\.?c\\.?)(?![\\p{L}\\d])"),
                    "inH2O"),
            Map.entry(Pattern.compile("(?i)(\\d)\\s?k\\s+rpm(?![\\p{L}\\d])"), "$1krpm"));
    /** A number and a fan unit written apart ({@code 40 CFM}, {@code 2.5 mmH2O}, {@code 3000 rpm}). */
    private static final Pattern NUMBER_UNIT_GAP = Pattern.compile(
            "(?i)(\\d)\\s+(?=(?:rpm|cfm|m3h|m3min|lmin|k?pa|mmh2o|inh2o|dba|db)(?![\\p{L}\\d]))");

    /**
     * {@code prepared} with the fan units in their token spelling ({@code 70 m³/h} -&gt; {@code 70m3h}, {@code r/min}
     * -&gt; {@code rpm}, {@code 25dB(A)} -&gt; {@code 25dBA}, {@code 3k rpm} -&gt; {@code 3krpm}); NFKC has turned
     * {@code ³} and {@code ₂} into digits. For texts of the fan family only.
     */
    static String normaliseUnits(String prepared) {
        if (prepared == null || prepared.isEmpty()) {
            return prepared;
        }
        String s = prepared;
        for (Map.Entry<Pattern, String> spelling : UNIT_SPELLINGS) {
            s = spelling.getKey().matcher(s).replaceAll(spelling.getValue());
        }
        return NUMBER_UNIT_GAP.matcher(s).replaceAll("$1");
    }

    // ---------------------------------------------------------------- words

    private static final String BEFORE = "(?<![\\p{L}\\d])";
    private static final String AFTER = "(?![\\p{L}\\d])";

    /** Radial fans: radial, centrifugal, blower, squirrel cage, turbo blower. */
    private static final Pattern RADIAL_WORDS = Pattern.compile("(?i)" + BEFORE
            + "(?:radial|centrifugal|blowers?|squirrel[- ]cage)" + AFTER);
    /** Axial fans: axial, tube-axial. */
    private static final Pattern AXIAL_WORDS = Pattern.compile("(?i)" + BEFORE + "(?:tube-?axial|axial)" + AFTER);
    /** A text that says fan (a part's default type is axial). */
    private static final Pattern FAN_WORD = Pattern.compile("(?i)" + BEFORE + "fans?" + AFTER);
    /** The type words a fan text claims from the mounting vocabulary ({@code axial}, {@code radial}). */
    private static final Set<String> TYPE_TOKENS = Set.of("axial", "radial", "tubeaxial", "tube-axial");

    private static final Pattern DC_WORDS = Pattern.compile("(?i)" + BEFORE + "(?:dc|\\d+(?:\\.\\d+)?\\s?vdc|brushless)"
            + AFTER);
    private static final Pattern AC_WORDS = Pattern.compile("(?i)" + BEFORE + "(?:ac|\\d+(?:\\.\\d+)?\\s?vac)" + AFTER);

    /** Bearing words, most specific first, and their canonical name. */
    private static final List<Map.Entry<Pattern, String>> BEARINGS = List.of(
            Map.entry(Pattern.compile("(?i)" + BEFORE + "(?:(?:dual|double|two|twin|2)[- ]?balls?|twin[- ]bearings?)" + AFTER), "ball"),
            // TME "Kind of Bearing: HDB" (Akasa's hydro dynamic bearing) and "FD" (fluid dynamic)
            Map.entry(Pattern.compile("(?i)" + BEFORE + "(?:fluid[- ]dynamic|fluid|fdb|fd|hdb|hydro(?:dynamic|lic)?"
                    + "|hypro|hydraulic)" + AFTER), "fluid dynamic"),
            // TME "Kind of Bearing: rolling": a rolling-element bearing, in a fan a ball bearing
            Map.entry(Pattern.compile("(?i)" + BEFORE + "(?:ball|bb|rolling)" + AFTER), "ball"),
            Map.entry(Pattern.compile("(?i)" + BEFORE + "(?:sleeve|slide)" + AFTER), "sleeve"),
            Map.entry(Pattern.compile("(?i)" + BEFORE + "rifle" + AFTER), "rifle"),
            Map.entry(Pattern.compile("(?i)" + BEFORE + "(?:magnetic|maglev|mag-lev)" + AFTER), "magnetic"),
            // Sunon's Vapo bearing (TME "Kind of Bearing: Vapo", Mouser "Vapo")
            Map.entry(Pattern.compile("(?i)" + BEFORE + "vapo" + AFTER), "vapo"));
    private static final Pattern BEARING_WORD = Pattern.compile("(?i)" + BEFORE + "bearings?" + AFTER);

    /**
     * Feature words and their canonical name. {@value ParsedQuery#PWM} and {@value ParsedQuery#TACHO} have kinds of
     * their own ({@code ConstraintKind.FAN_PWM}, {@code FAN_TACHO}: a part that does not state a requested one is a
     * mismatch); a {@code sensor} is a tacho unless it is a lock or temperature sensor.
     */
    private static final List<Map.Entry<Pattern, String>> FEATURES = List.of(
            Map.entry(Pattern.compile("(?i)" + BEFORE + "pwm" + AFTER), ParsedQuery.PWM),
            // TME "Signal output: F type" (FG, a tacho signal) and "R type" (rotation detection, a locked-rotor alarm)
            Map.entry(Pattern.compile("(?i)" + BEFORE + "(?:tacho(?:meter)?|tach|fg|f[- ]type|speed[- ]sensor"
                    + "|speed[- ]signal|rpm[- ]signal|frequency[- ]generator"
                    + "|(?<!lock[- ])(?<!rotor[- ])(?<!temperature[- ])(?<!thermal[- ])(?<!temp[- ])sensor)" + AFTER),
                    ParsedQuery.TACHO),
            Map.entry(Pattern.compile("(?i)" + BEFORE + "(?:locked[- ]rotor|lock[- ]rotor|rotor[- ]lock|lock[- ]sensor|alarm|r[- ]type)"
                    + AFTER), "locked rotor"),
            Map.entry(Pattern.compile("(?i)" + BEFORE + "auto(?:matic)?[- ]?restart" + AFTER), "auto restart"),
            // "2 wire", "3-wire", "4 pin"; TME "leads x3", Mouser "4x Lead Wires", "2xWire"
            Map.entry(Pattern.compile("(?i)" + BEFORE + "(?:2[- ]?(?:wires?|leads?)|leads?\\s?x\\s?2"
                    + "|2\\s?x\\s?(?:lead\\s)?(?:wires?|leads?))" + AFTER), "2-wire"),
            Map.entry(Pattern.compile("(?i)" + BEFORE + "(?:3[- ]?(?:wires?|leads?|pins?)|leads?\\s?x\\s?3"
                    + "|3\\s?x\\s?(?:lead\\s)?(?:wires?|leads?))" + AFTER), "3-wire"),
            Map.entry(Pattern.compile("(?i)" + BEFORE + "(?:4[- ]?(?:wires?|leads?|pins?)|leads?\\s?x\\s?4"
                    + "|4\\s?x\\s?(?:lead\\s)?(?:wires?|leads?))" + AFTER), "4-wire"));
    /**
     * What a wire count says about a fan's signals, on a request and on a part: two wires carry none, three a tacho,
     * four PWM and a tacho. A signal a row leaves out is absent (a 3-wire fan has no PWM input); a feature the text
     * states stays whatever its wire count (distributor data wins).
     */
    private static final Map<String, List<String>> WIRES = Map.of(
            "2-wire", List.of(),
            "3-wire", List.of(ParsedQuery.TACHO),
            "4-wire", List.of(ParsedQuery.PWM, ParsedQuery.TACHO));
    private static final Pattern IP_RATING = Pattern.compile("(?i)" + BEFORE + "ip\\s?([0-6x][0-9])" + AFTER);

    // ---------------------------------------------------------------- frame size

    private static final String MM = "(\\d{1,3}(?:\\.\\d+)?)\\s?(?:mm)?";
    /**
     * {@code 40x40x10mm}, {@code 40 x 40 x 10 mm}, {@code 25mm×25mm×10mm}, {@code 50*50*20mm}, {@code 50x15 mm}; a round
     * blower {@code Ø97x33mm} (TME) as its diameter and depth.
     */
    private static final Pattern FRAME = Pattern.compile("(?i)(?:(?<=[Øø])|" + BEFORE + ")" + MM + "\\s?[x×*]\\s?" + MM
            + "(?:\\s?[x×*]\\s?" + MM + ")?(?![\\p{L}\\d.])");
    /** A bare size: {@code 120mm}, {@code 40 mm}, {@code Ø50mm}. */
    private static final Pattern BARE_FRAME = Pattern.compile("(?i)(?<![\\p{L}\\d.x×*])[Øø]?\\s?(\\d{2,3})\\s?mm"
            + "(?![\\p{L}\\d.x×*])");
    /**
     * The shorthand frame code of the trade: four digits as a two-digit width and a two-digit depth ({@code 2510} is
     * 25x25x10, {@code 9225} 92x92x25), five digits as a three-digit width and a two-digit depth ({@code 12025} is
     * 120x120x25); {@code 4010mm} too. Not inside a part number ({@code SF4020SH24}, {@code AK-4010MS}), a decimal or a
     * rated value ({@code 4020rpm}, {@code 2510 h}).
     */
    private static final Pattern FRAME_CODE = Pattern.compile("(?i)(?<![\\p{L}\\d/_-])(?<!\\d[.,])"
            + "(\\d{2,3})(\\d{2})(?:\\s?mm)?(?![\\p{L}\\d/_-])(?![.,]\\d)"
            + "(?!\\s?(?:rpm|cfm|m3h|pa|kpa|dba?|v|vdc|vac|w|a|ma|h|hrs?|hours?|pcs)(?![\\p{L}\\d]))");
    /** The shallowest and deepest frame a shorthand code states, in millimetres. */
    private static final double MIN_CODE_DEPTH_MM = 4;
    private static final double MAX_CODE_DEPTH_MM = 60;
    /** The smallest and largest frame of a fan, in millimetres. */
    private static final double MIN_FRAME_MM = 15;
    private static final double MAX_FRAME_MM = 300;
    /** The largest bare size read as a frame ({@code 300mm} next to a fan is more often its lead length). */
    private static final double MAX_BARE_FRAME_MM = 250;

    /**
     * The frame size a fan text states: three dimensions as width, length and depth; two equal ones as width and
     * length; two different ones as a square frame and its depth ({@code 50x15 mm}: 50x50x15); a bare {@code 120mm} as
     * width and length; a shorthand code ({@code 2510}, {@code 12025}) as width, length and depth (width 15 to 300 mm,
     * depth 4 to 60 mm). Null when none, or when the size is no fan's (below 15 mm or above 300 mm). Callers read only
     * texts of the fan family: {@code 2512} is a resistor's package elsewhere.
     */
    static ParsedQuery.Frame frame(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m = FRAME.matcher(text);
        while (m.find()) {
            double a = Double.parseDouble(m.group(1));
            double b = Double.parseDouble(m.group(2));
            ParsedQuery.Frame frame;
            if (m.group(3) != null) {
                frame = new ParsedQuery.Frame(a, b, Double.parseDouble(m.group(3)));
            } else if (Math.abs(a - b) <= ParsedQuery.Frame.TOLERANCE_MM) {
                frame = new ParsedQuery.Frame(a, b, null);
            } else {
                double side = Math.max(a, b);
                frame = new ParsedQuery.Frame(side, side, Math.min(a, b));
            }
            if (fanSized(frame.width()) && fanSized(frame.length())) {
                return frame;
            }
        }
        m = FRAME_CODE.matcher(text);
        while (m.find()) {
            double side = Double.parseDouble(m.group(1));
            double depth = Double.parseDouble(m.group(2));
            if (fanSized(side) && depth >= MIN_CODE_DEPTH_MM && depth <= MAX_CODE_DEPTH_MM) {
                return new ParsedQuery.Frame(side, side, depth);
            }
        }
        m = BARE_FRAME.matcher(text);
        while (m.find()) {
            double side = Double.parseDouble(m.group(1));
            if (fanSized(side) && side <= MAX_BARE_FRAME_MM) {
                return new ParsedQuery.Frame(side, side, null);
            }
        }
        return null;
    }

    private static boolean fanSized(double mm) {
        return mm >= MIN_FRAME_MM && mm <= MAX_FRAME_MM;
    }

    /** The spans whose tokens the vocabulary reads (never free-text keywords of a fan request). */
    private static final List<Pattern> CONSUMED = List.of(RADIAL_WORDS, AXIAL_WORDS, DC_WORDS, AC_WORDS,
            Pattern.compile("(?i)" + BEFORE + "(?:cooling|brushless)" + AFTER), BEARING_WORD, FRAME, FRAME_CODE,
            BARE_FRAME, IP_RATING, union(BEARINGS), union(FEATURES));

    /** One pattern matching any of {@code words} (each a {@code (?i)} pattern). */
    private static Pattern union(List<Map.Entry<Pattern, String>> words) {
        return Pattern.compile("(?i)" + String.join("|", words.stream()
                .map(e -> "(?:" + e.getKey().pattern().replaceFirst("^\\(\\?i\\)", "") + ")").toList()));
    }

    /** A frame size display read back ({@code 40x40x10mm}, {@code 120mm}), null when it is none. */
    static ParsedQuery.Frame parseFrame(String display) {
        return frame(display);
    }

    // ---------------------------------------------------------------- analysis

    /**
     * The fan attributes of a text of the fan family. With {@code partDefaults} (a part's description or category) a
     * text that says fan and names no radial word is an axial fan, and a {@code VDC}/{@code VAC} voltage states the
     * supply; a request's type is only what it says.
     */
    static Analysis analyze(String text, boolean partDefaults) {
        if (text == null || text.isBlank()) {
            return new Analysis(null, Set.of());
        }
        String prepared = normaliseUnits(Recognizers.prepare(text));
        String type = type(prepared, partDefaults);
        String supply = supply(prepared);
        ParsedQuery.Frame frame = frame(prepared);
        String bearing = bearing(prepared);
        List<String> features = features(prepared);
        // the tokens of every span the vocabulary read are no free-text keywords
        Set<String> consumed = new LinkedHashSet<>();
        for (Pattern p : CONSUMED) {
            Matcher m = p.matcher(prepared);
            while (m.find()) {
                if (p != FRAME && p != FRAME_CODE && p != BARE_FRAME || frame(m.group()) != null) {
                    Recognizers.tokenize(m.group()).forEach(t -> consumed.add(Recognizers.normalizeKey(t)));
                }
            }
        }
        ParsedQuery.Fan fan = new ParsedQuery.Fan(type, supply, frame, bearing, features);
        return new Analysis(fan.isEmpty() ? null : fan, Set.copyOf(consumed));
    }

    /** {@value ParsedQuery#RADIAL}, {@value ParsedQuery#AXIAL}, or with {@code partDefaults} axial for a fan text. */
    static String type(String text, boolean partDefaults) {
        if (text == null) {
            return null;
        }
        if (RADIAL_WORDS.matcher(text).find()) {
            return ParsedQuery.RADIAL;
        }
        if (AXIAL_WORDS.matcher(text).find() || partDefaults && FAN_WORD.matcher(text).find()) {
            return ParsedQuery.AXIAL;
        }
        return null;
    }

    /** {@value ParsedQuery#DC} or {@value ParsedQuery#AC} when the text says one (and not both), else null. */
    static String supply(String text) {
        if (text == null) {
            return null;
        }
        boolean dc = DC_WORDS.matcher(text).find();
        boolean ac = AC_WORDS.matcher(text).find();
        if (dc == ac) {
            return null;
        }
        return dc ? ParsedQuery.DC : ParsedQuery.AC;
    }

    /** The canonical bearing a text names ({@code ball}, {@code sleeve}, {@code fluid dynamic}...), else null. */
    static String bearing(String text) {
        if (text == null) {
            return null;
        }
        for (Map.Entry<Pattern, String> b : BEARINGS) {
            if (b.getKey().matcher(text).find()) {
                return b.getValue();
            }
        }
        return null;
    }

    /**
     * The features a text names and those its wire count implies ({@link #WIRES}), in vocabulary order ({@code PWM},
     * {@code tacho}, ..., the IP rating last).
     */
    static List<String> features(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Set<String> named = new LinkedHashSet<>();
        for (Map.Entry<Pattern, String> f : FEATURES) {
            if (f.getKey().matcher(text).find()) {
                named.add(f.getValue());
            }
        }
        for (String wires : List.copyOf(named)) {
            named.addAll(WIRES.getOrDefault(wires, List.of()));
        }
        for (Map.Entry<Pattern, String> f : FEATURES) {
            if (named.contains(f.getValue()) && !out.contains(f.getValue())) {
                out.add(f.getValue());
            }
        }
        Matcher ip = IP_RATING.matcher(text);
        if (ip.find()) {
            out.add("IP" + ip.group(1).toUpperCase(Locale.ROOT));
        }
        return out;
    }

    /**
     * The word of a fan vocabulary in a part's attribute value, description or category ({@link ParametricExtractor}
     * sources): the type with the part default (a text that says fan is axial), the supply, the frame size displayed,
     * the bearing, the features comma separated; null when the text names none.
     */
    static String word(Vocabulary vocabulary, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String prepared = normaliseUnits(Recognizers.prepare(text));
        return switch (vocabulary) {
            case FAN_TYPE -> type(prepared, true);
            case FAN_SUPPLY -> supply(prepared);
            case FRAME_SIZE -> {
                ParsedQuery.Frame frame = frame(prepared);
                yield frame == null ? null : frame.display();
            }
            case BEARING -> bearing(prepared);
            case FAN_FEATURES -> {
                List<String> features = features(prepared);
                yield features.isEmpty() ? null : String.join(", ", features);
            }
            default -> null;
        };
    }

    /** True when {@code token} is a fan type word a fan text claims ({@code axial}, {@code radial}): no mounting. */
    static boolean claims(String family, String token) {
        return FAN.equals(family) && TYPE_TOKENS.contains(token.toLowerCase(Locale.ROOT));
    }
}
