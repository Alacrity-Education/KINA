package ro.alacrity.kina.search;

import lombok.experimental.UtilityClass;
import ro.alacrity.kina.domain.ComponentFamily;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Vocabulary;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Switch vocabulary shared by {@link QueryParser}, {@link Recognizers} and {@link ParametricExtractor} (DESIGN.md 3.4
 * "Switches"): the switch type, the contact configuration, the function, the termination class, the body size, the
 * panel cut-out, the positions of a DIP or rotary switch, the illumination, the orientation, the AC or DC of a voltage
 * and the IP code, and the spellings of the switch units the value tokens need ({@code 100,000 cycles},
 * {@code 20 thousand cycles}, {@code 160 gf}). Read only in texts of the switch family, except the words of switch ICs
 * and switching sensors, which every text may name ({@link #word}). Stateless and thread-safe.
 *
 * <p>Wording seen in the JLCPCB database (2026-10-07): categories {@code Tactile Switches}, {@code Slide Switches},
 * {@code DIP Switches}, {@code Pushbutton Switches}, {@code Toggle Switches}, {@code Rocker Switches},
 * {@code Limit Switches}, {@code Keylock Switches}; descriptions {@code -30℃~+80℃ 100,000 cycles 12V 2.6N 50mA 6mm 6mm
 * 8.5mm Black Gull Wing Round Button SPST Surface Mount,Vertical Without Bracket}, {@code 125V 3A Cylinder Lever PC Pin
 * SPDT Through Hole}, {@code 1.8N 100mA 30V 5,000 cycles DPDT Latching PC Pin}; packages {@code SMD-4P,6x6mm},
 * {@code 插件,8.7x4.4mm}.
 */
@UtilityClass
class SwitchVocabulary {

    static final String SWITCH = ComponentFamily.SWITCH.label();

    /** A switch's attributes in a text, the IP code it names and the lower-case tokens that stated them. */
    record Analysis(ParsedQuery.Switch sw, Integer ipCode, Set<String> consumed) {
    }

    private static final String BEFORE = "(?<![\\p{L}\\d])";
    private static final String AFTER = "(?![\\p{L}\\d])";

    private static Pattern word(String alternatives) {
        return Pattern.compile("(?i)" + BEFORE + "(?:" + alternatives + ")" + AFTER);
    }

    // ---------------------------------------------------------------- units

    /** Switch unit spellings rewritten to one token each. */
    private static final List<Map.Entry<Pattern, String>> UNIT_SPELLINGS = List.of(
            // "100,000 cycles" (prepare made it "100.000"), "1.000.000 cycles": thousands separators
            Map.entry(Pattern.compile("(?i)(?<![\\d.])(\\d{1,3})[.,](\\d{3})[.,](\\d{3})\\s?(?=(?:cycles?|times|operations)"
                    + AFTER + ")"), "$1$2$3"),
            Map.entry(Pattern.compile("(?i)(?<![\\d.])(\\d{1,3})[.,](\\d{3})\\s?(?=(?:cycles?|times|operations)" + AFTER
                    + ")"), "$1$2"),
            // "20 thousand cycles", "10K cycles", "1M cycles"
            Map.entry(Pattern.compile("(?i)(\\d)\\s?thousand\\s?(?=(?:cycles?|times|operations)" + AFTER + ")"), "$1k"),
            Map.entry(Pattern.compile("(?i)(\\d)\\s?million\\s?(?=(?:cycles?|times|operations)" + AFTER + ")"), "$1M"),
            Map.entry(Pattern.compile("(?i)(\\d[kKM]?)\\s+(?=(?:cycles?|times|operations|gf|n)" + AFTER + ")"), "$1"));

    /**
     * {@code prepared} with the switch units in their token spelling ({@code 100.000 cycles} -&gt;
     * {@code 100000cycles}, {@code 20 thousand cycles} -&gt; {@code 20kcycles}, {@code 160 gf} -&gt; {@code 160gf}).
     * For texts of the switch family only.
     */
    static String normaliseUnits(String prepared) {
        if (prepared == null || prepared.isEmpty()) {
            return prepared;
        }
        String s = prepared;
        for (Map.Entry<Pattern, String> spelling : UNIT_SPELLINGS) {
            s = spelling.getKey().matcher(s).replaceAll(spelling.getValue());
        }
        return s;
    }

    // ---------------------------------------------------------------- type

    /** Switch ICs and switching sensors (also in texts of other families): never a mechanical switch. */
    private static final List<Map.Entry<Pattern, String>> NOT_MECHANICAL = List.of(
            Map.entry(word("ic-switch(?:es)?|switch ics?|multiplexers?|analog(?:ue)? switch(?:es)?"), "IC"),
            Map.entry(word("sensor-switch(?:es)?|hall(?:[- ]effect)? sensors?"), "sensor"),
            Map.entry(word("switch accessories|switch caps?|keycaps?|key caps?|button caps?|actuator caps?"),
                    "accessory"));
    /** Mechanical switch types, most specific first, and their canonical type. */
    private static final List<Map.Entry<Pattern, String>> TYPES = List.of(
            Map.entry(word("dip-?switch(?:es)?|dip|dil|piano"), "DIP"),
            Map.entry(word("tactile|tact|tact switch(?:es)?|touch switch(?:es)?"), ParsedQuery.Switch.TACTILE),
            Map.entry(word("micro-?switch(?:es)?|snap[- ]action|limit|basic switch(?:es)?|travel switch(?:es)?"),
                    "snap action"),
            Map.entry(word("keylock|key[- ]lock|key[- ]operated|key switch(?:es)?|keyswitch(?:es)?"), "keylock"),
            Map.entry(word("toggle|lever"), "toggle"),
            Map.entry(word("rocker"), "rocker"),
            Map.entry(word("slide|slider"), "slide"),
            Map.entry(word("rotary|thumbwheel|coded rotary|bcd"), "rotary"),
            Map.entry(word("reed"), "reed"),
            Map.entry(word("membrane"), "membrane"),
            Map.entry(word("detector|detect switch(?:es)?|detection switch(?:es)?"), "detector"),
            Map.entry(word("navigation|joystick|multi-?directional|5-way|five-way"), "navigation"),
            Map.entry(word("pushbuttons?|push-buttons?|push switch(?:es)?|momentary buttons?|push buttons?"),
                    ParsedQuery.Switch.PUSHBUTTON));

    /**
     * The switch type a text names: a switch IC, a switching sensor or a cap first, else the first mechanical type;
     * with {@code anyFamily} (a text of another family) only the first three.
     */
    static String type(String text, boolean anyFamily) {
        if (text == null) {
            return null;
        }
        for (Map.Entry<Pattern, String> t : NOT_MECHANICAL) {
            if (t.getKey().matcher(text).find()) {
                return t.getValue();
            }
        }
        if (anyFamily) {
            return null;
        }
        for (Map.Entry<Pattern, String> t : TYPES) {
            if (t.getKey().matcher(text).find()) {
                return t.getValue();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- contacts, function

    /** {@code SPDT}, {@code DPDT}, {@code 3PDT}, {@code SP3T}, {@code 1P2T}, {@code 2P2T}, {@code SPST-NO}. */
    private static final Pattern CONTACTS = Pattern.compile("(?i)" + BEFORE + "([SD1-4]|\\d)P([SD1-9]|\\d{1,2})T"
            + "(?:[- ]?(NO|NC|N\\.O\\.|N\\.C\\.))?" + AFTER);
    /** {@code 1 Form A} (SPST-NO), {@code 1 Form B} (SPST-NC), {@code 2 Form C} (DPDT). */
    private static final Pattern FORM = Pattern.compile("(?i)" + BEFORE + "([1-4])\\s?form\\s?([abc])" + AFTER);
    /** {@code 1xNO}, {@code 1NO}, {@code 2xNO}. */
    private static final Pattern TIMES_NO = Pattern.compile(BEFORE + "([1-4])\\s?[xX]?\\s?(NO|NC)" + AFTER);
    private static final Pattern NORMALLY = Pattern.compile("(?i)" + BEFORE + "normally[- ](open|closed)" + AFTER);

    /** The contact configuration a text names, else null. */
    static ParsedQuery.Contacts contacts(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = CONTACTS.matcher(text);
        if (m.find()) {
            int poles = count(m.group(1));
            int throwsCount = count(m.group(2));
            String form = m.group(3) == null ? null : m.group(3).replace(".", "").toUpperCase(Locale.ROOT);
            Matcher normally = NORMALLY.matcher(text);
            if (form == null && throwsCount == 1 && normally.find()) {
                form = normally.group(1).equalsIgnoreCase("open") ? "NO" : "NC";
            }
            return new ParsedQuery.Contacts(poles, throwsCount, throwsCount == 1 ? form : null);
        }
        m = FORM.matcher(text);
        if (m.find()) {
            int poles = Integer.parseInt(m.group(1));
            String letter = m.group(2).toUpperCase(Locale.ROOT);
            return letter.equals("C") ? new ParsedQuery.Contacts(poles, 2, null)
                    : new ParsedQuery.Contacts(poles, 1, letter.equals("A") ? "NO" : "NC");
        }
        m = TIMES_NO.matcher(text);
        if (m.find()) {
            return new ParsedQuery.Contacts(Integer.parseInt(m.group(1)), 1, m.group(2).toUpperCase(Locale.ROOT));
        }
        return null;
    }

    /** A contact configuration displayed ({@code SPDT}) read back, null when it is none. */
    static ParsedQuery.Contacts parseContacts(String display) {
        return contacts(display);
    }

    private static int count(String s) {
        return switch (s.toUpperCase(Locale.ROOT)) {
            case "S" -> 1;
            case "D" -> 2;
            default -> Integer.parseInt(s);
        };
    }

    /** Distributor positions ({@code ON-OFF-ON}, {@code (ON)-OFF-(ON)}, Mouser {@code ON-NONE-ON}). */
    private static final Pattern POSITIONS = Pattern.compile("(?i)" + BEFORE
            + "(\\(?(?:ON|OFF)\\)?(?:\\s?-\\s?(?:\\(?(?:ON|OFF)\\)?|NONE)){1,3})" + AFTER);
    private static final Pattern MOMENTARY = word("momentary|non[- ]?latching|non[- ]?locking|push[- ]to[- ]make"
            + "|push[- ]to[- ]break|monostable|non[- ]?maintained|spring[- ]return");
    private static final Pattern LATCHING = word("latching|self[- ]?locking|locking|maintained|push[- ]push"
            + "|bistable|alternate(?: action)?|toggle action");

    /**
     * The function a text names: the positions as distributors write them ({@code ON-OFF-ON}; {@code NONE} dropped:
     * Mouser {@code ON-NONE-ON} is {@code ON-ON}), else {@code momentary} or {@code latching}; null when neither.
     */
    static String function(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = POSITIONS.matcher(text);
        while (m.find()) {
            String p = m.group(1).toUpperCase(Locale.ROOT).replaceAll("\\s", "").replace("-NONE", "");
            if (p.contains("-")) {
                return p;
            }
        }
        if (MOMENTARY.matcher(text).find()) {
            return ParsedQuery.Switch.MOMENTARY;
        }
        return LATCHING.matcher(text).find() ? ParsedQuery.Switch.LATCHING : null;
    }

    // ---------------------------------------------------------------- termination

    private static final Pattern SOLDER_LUG = word("solder[- ]?lugs?|lugs?|solder[- ]?tags?|solder[- ]?terminals?"
            + "|solder[- ]?eyelets?");
    private static final Pattern QUICK_CONNECT = word("quick[- ]?connect(?:s|ors?)?|faston|spade|blade terminals?"
            + "|\\d\\.\\d\\s?mm (?:tabs?|terminals?|connectors?)|connectors? \\d[.,]\\dmm");
    private static final Pattern WIRE_LEADS = word("wire[- ]leads?|flying leads?|with wires?|pre-?wired|lead wires?");
    private static final Pattern SCREW = word("screw[- ]?terminals?|screw");
    private static final Pattern GENERIC_SOLDER = word("for (?:wire )?soldering|wire soldering|solder|soldering");
    private static final Pattern PCB = word("pc[- ]?pins?|pcb|pcb[- ]mount|board[- ]mount|smd|smt|surface[- ]mount"
            + "|tht|through[- ]hole|gull[- ]wing|j[- ]lead|插件");
    private static final Pattern PANEL = word("panel(?:[- ]mount(?:ed|ing)?)?|front[- ]panel|snap[- ]in|bushing");

    /**
     * The termination class a text names: an explicit panel termination (solder lug, quick connect, wire leads,
     * screw), else PCB (SMD, THT, PC pins), else a generic solder word (the user's {@code for wire soldering}) as solder
     * lugs, else {@code panel} for a panel-mount word; null when none.
     */
    static String termination(String text) {
        if (text == null) {
            return null;
        }
        if (SOLDER_LUG.matcher(text).find()) {
            return "solder lug";
        }
        if (QUICK_CONNECT.matcher(text).find()) {
            return "quick connect";
        }
        if (WIRE_LEADS.matcher(text).find()) {
            return "wire leads";
        }
        if (SCREW.matcher(text).find()) {
            return "screw";
        }
        if (PCB.matcher(text).find()) {
            return ParsedQuery.Switch.PCB;
        }
        if (GENERIC_SOLDER.matcher(text).find()) {
            return "solder lug";
        }
        return PANEL.matcher(text).find() ? ParsedQuery.Switch.PANEL : null;
    }

    // ---------------------------------------------------------------- size, hole, positions

    private static final String MM = "(\\d{1,2}(?:\\.\\d+)?)\\s?(?:mm)?";
    /** {@code 6x6}, {@code 6x6x4.3}, {@code 6 x 6 x 5 mm}, {@code 12x12mm}, {@code 6*6}. */
    private static final Pattern SIZE = Pattern.compile("(?i)(?<![\\p{L}\\d.])" + MM + "\\s?[x×*]\\s?" + MM
            + "(?:\\s?[x×*]\\s?" + MM + ")?(?![\\d.x×*])");
    /** {@code 4.3mm height}, {@code height 4.3mm}, {@code h=5mm}, {@code H5mm}. */
    private static final Pattern HEIGHT = Pattern.compile("(?i)(?<![\\p{L}\\d.])(?:(\\d{1,2}(?:\\.\\d+)?)\\s?mm\\s"
            + "(?:height|high|tall)|(?:height|h)\\s?[:=]?\\s?(\\d{1,2}(?:\\.\\d+)?)\\s?mm)(?![\\p{L}\\d])");
    /** A bare size {@code 12mm}, {@code Ø16mm}, {@code M12}. */
    private static final Pattern BARE = Pattern.compile("(?i)(?<![\\p{L}\\d.x×*])(?:[Øø]\\s?|M)?(\\d{1,2}(?:\\.\\d+)?)"
            + "\\s?mm(?![\\p{L}\\d.x×*])|(?<![\\p{L}\\d])M(\\d{1,2})(?![\\p{L}\\d.])");
    /** A labelled cut-out: {@code 12mm hole}, {@code hole 16mm}, {@code Ø16mm}, {@code mounting hole 19 mm}. */
    private static final Pattern HOLE = Pattern.compile("(?i)(?:(?<![\\p{L}\\d.])(\\d{1,2}(?:\\.\\d+)?)\\s?mm\\s"
            + "(?:mounting\\s)?(?:hole|cut-?out|panel cut-?out)|(?:hole|cut-?out)(?:\\sdiam(?:eter|\\.)?)?\\s?[:=]?\\s?"
            + "(\\d{1,2}(?:\\.\\d+)?)\\s?mm|[Øø]\\s?(\\d{1,2}(?:\\.\\d+)?)\\s?mm)");
    /** The smallest and largest body side and panel cut-out, in millimetres. */
    private static final double MIN_MM = 1.5;
    /** The smallest bare size read as a body or cut-out ({@code 2.54mm} next to a DIP switch is its pitch). */
    private static final double MIN_BARE_MM = 3;
    private static final double MAX_MM = 40;

    /** The body size a text states ({@code 6x6x4.3mm}), with a labelled height; null when none. */
    static ParsedQuery.BodySize size(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = SIZE.matcher(text);
        while (m.find()) {
            double a = Double.parseDouble(m.group(1));
            double b = Double.parseDouble(m.group(2));
            if (a < MIN_MM || b < MIN_MM || a > MAX_MM || b > MAX_MM) {
                continue;
            }
            Double h = m.group(3) == null ? height(text) : Double.valueOf(m.group(3));
            return new ParsedQuery.BodySize(a, b, h);
        }
        return null;
    }

    private static Double height(String text) {
        Matcher h = HEIGHT.matcher(text);
        if (!h.find()) {
            return null;
        }
        return Double.parseDouble(h.group(1) != null ? h.group(1) : h.group(2));
    }

    /** A body size displayed ({@code 6x6x4.3mm}) read back, null when it is none. */
    static ParsedQuery.BodySize parseSize(String display) {
        return size(display);
    }

    /** The labelled panel cut-out of a text ({@code 12mm hole}, {@code Ø16mm}) in millimetres, else null. */
    static Double hole(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = HOLE.matcher(text);
        while (m.find()) {
            String g = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            double mm = Double.parseDouble(g);
            if (mm >= MIN_MM && mm <= MAX_MM) {
                return mm;
            }
        }
        return null;
    }

    /** The first bare millimetre size of a text ({@code 12mm}, {@code M12}), else null. */
    static Double bare(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = BARE.matcher(text);
        while (m.find()) {
            double mm = Double.parseDouble(m.group(1) != null ? m.group(1) : m.group(2));
            if (mm >= MIN_BARE_MM && mm <= MAX_MM) {
                return mm;
            }
        }
        return null;
    }

    /** {@code 8 position(s)}, {@code 8 pos}, {@code 8-way}, {@code 8 bit}, {@code 8 channels} of a DIP switch. */
    private static final Pattern POSITION_COUNT = Pattern.compile("(?i)(?<![\\p{L}\\d.])(\\d{1,2})\\s?-?\\s?"
            + "(?:positions?|pos\\.?|ways?|bits?|channels?|switches|sections?)(?![\\p{L}\\d])");

    /** The positions a text states ({@code 8 position}), else null. */
    static Integer positions(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = POSITION_COUNT.matcher(text);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    // ---------------------------------------------------------------- illumination, orientation, supply, IP

    private static final Pattern NOT_ILLUMINATED = word("non[- ]?illuminated|not illuminated|without (?:led|light|lamp)"
            + "|no (?:led|light|lamp)|unlit");
    private static final Pattern ILLUMINATED = word("illuminated|lighted|lit|backlit|backlight|with led|led ring"
            + "|ring led|led|lamp");
    private static final Pattern ILLUMINATION_COLOUR = Pattern.compile("(?i)" + BEFORE
            + "(red|green|blue|yellow|amber|orange|white|rgb|bi-?colou?r)\\s(?:led|ring|illumination|illuminated|light)"
            + AFTER + "|" + BEFORE + "(?:illumination|led)\\s?(?:colou?r)?\\s?[:=]?\\s?"
            + "(red|green|blue|yellow|amber|orange|white|rgb)" + AFTER);

    /** True for an illuminated switch, false for one that says it is not, null when the text does not say. */
    static Boolean illuminated(String text) {
        if (text == null) {
            return null;
        }
        if (NOT_ILLUMINATED.matcher(text).find()) {
            return false;
        }
        return ILLUMINATED.matcher(text).find() ? Boolean.TRUE : null;
    }

    /** The colour of the illumination a text names ({@code red LED}, {@code LED colour: red}), else null. */
    static String illuminationColour(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = ILLUMINATION_COLOUR.matcher(text);
        if (!m.find()) {
            return null;
        }
        String colour = (m.group(1) != null ? m.group(1) : m.group(2)).toLowerCase(Locale.ROOT);
        return colour.equals("rgb") ? "RGB" : colour.startsWith("bi") ? "bi-colour" : colour;
    }

    private static final Pattern RIGHT_ANGLE = word("right[- ]?angle(?:d)?|side[- ]?actuated|side[- ]?operated"
            + "|horizontal(?: type)?|side[- ]?push");
    private static final Pattern VERTICAL = word("vertical|top[- ]?actuated|top[- ]?operated|straight");

    /** {@code right angle} (side actuated) or {@code vertical} (top actuated), else null. */
    static String orientation(String text) {
        if (text == null) {
            return null;
        }
        if (RIGHT_ANGLE.matcher(text).find()) {
            return ParsedQuery.RIGHT_ANGLE;
        }
        return VERTICAL.matcher(text).find() ? ParsedQuery.VERTICAL : null;
    }

    private static final Pattern AC_VOLTAGE = Pattern.compile("(?i)(?<![\\p{L}\\d.])\\d+(?:\\.\\d+)?\\s?k?\\s?v\\s?ac"
            + AFTER);
    private static final Pattern DC_VOLTAGE = Pattern.compile("(?i)(?<![\\p{L}\\d.])\\d+(?:\\.\\d+)?\\s?k?\\s?v\\s?dc"
            + AFTER);

    /** {@link ParsedQuery#AC} or {@link ParsedQuery#DC} when a request's voltage says so (and not both), else null. */
    static String supply(String text) {
        if (text == null) {
            return null;
        }
        boolean ac = AC_VOLTAGE.matcher(text).find();
        boolean dc = DC_VOLTAGE.matcher(text).find();
        return ac == dc ? null : ac ? ParsedQuery.AC : ParsedQuery.DC;
    }

    private static final Pattern SEALED = word("sealed|waterproof|water[- ]?proof|washable");

    /**
     * The IP code a request states as its two digits ({@code IP67}: 67): an IP code, or IP67 for {@code sealed} and
     * {@code waterproof}; null when none.
     */
    static Integer ipCode(String text) {
        Integer code = ro.alacrity.kina.domain.extract.IpCode.code(text);
        if (code != null) {
            return code;
        }
        return text != null && SEALED.matcher(text).find() ? Integer.valueOf(67) : null;
    }

    // ---------------------------------------------------------------- analysis

    /** Tokens a switch text claims from the package vocabulary ({@code DIP} is a switch type there, no package). */
    private static final Set<String> CLAIMED = Set.of("dip", "dil", "dip-switch", "dipswitch");

    /** True when {@code token} is a switch word a switch text claims ({@code DIP}): no package. */
    static boolean claims(String family, String token) {
        return SWITCH.equals(family) && CLAIMED.contains(token.toLowerCase(Locale.ROOT));
    }

    /** Words that only label a switch value or describe the actuation ({@code operating force}, {@code cycles}). */
    private static final Pattern LABELS = word("button|buttons|actuator|operating force|force|life|cycles|height"
            + "|mount|mounting|mounted|type|ip\\d\\d|ipx\\d|hole|cut-?out|contacts?|function");

    /** The spans whose tokens the vocabulary reads (never free-text keywords of a switch request). */
    private static final List<Pattern> CONSUMED = List.of(CONTACTS, FORM, TIMES_NO, NORMALLY, POSITIONS, MOMENTARY,
            LATCHING, SOLDER_LUG, QUICK_CONNECT, WIRE_LEADS, SCREW, GENERIC_SOLDER, PCB, PANEL, SIZE, HEIGHT, HOLE, BARE,
            POSITION_COUNT, NOT_ILLUMINATED, ILLUMINATED, ILLUMINATION_COLOUR, RIGHT_ANGLE, VERTICAL, SEALED, LABELS,
            union(TYPES), union(NOT_MECHANICAL), word("switch|switches"));

    private static Pattern union(List<Map.Entry<Pattern, String>> words) {
        return Pattern.compile("(?i)" + String.join("|", words.stream()
                .map(e -> "(?:" + e.getKey().pattern().replaceFirst("^\\(\\?i\\)", "") + ")").toList()));
    }

    /**
     * The switch attributes of a request of the switch family. A bare millimetre size is the panel cut-out of a panel
     * switch (pushbutton, toggle, rocker, keylock, a panel or solder lug termination) and the body of a tactile switch
     * ({@code tactile 12mm}: 12x12mm); an SMD or THT mounting makes the termination PCB.
     */
    static Analysis analyze(String text, String mounting) {
        if (text == null || text.isBlank()) {
            return new Analysis(ParsedQuery.Switch.builder().build(), null, Set.of());
        }
        String prepared = normaliseUnits(Recognizers.prepare(text));
        String type = type(prepared, false);
        String termination = termination(prepared);
        if (termination == null && mounting != null) {
            termination = ParsedQuery.Switch.PCB;
        }
        ParsedQuery.BodySize size = size(prepared);
        Double hole = hole(prepared);
        Double bare = size == null && hole == null ? bare(prepared) : null;
        if (bare != null) {
            boolean body = ParsedQuery.Switch.TACTILE.equals(type) || ParsedQuery.Switch.PCB.equals(termination)
                    || "DIP".equals(type) || "slide".equals(type);
            if (body) {
                size = new ParsedQuery.BodySize(bare, bare, null);
            } else {
                hole = bare;
            }
        }
        Boolean illuminated = illuminated(prepared);
        ParsedQuery.Switch sw = ParsedQuery.Switch.builder()
                .type(type)
                .contacts(contacts(prepared))
                .function(function(prepared))
                .termination(termination)
                .size(size)
                .holeDiameter(hole)
                .positions(positions(prepared))
                .illuminated(Boolean.TRUE.equals(illuminated) ? Boolean.TRUE : null)
                .illuminationColour(illuminationColour(prepared))
                .orientation(orientation(prepared))
                .voltageSupply(supply(prepared))
                .build();
        Set<String> consumed = new LinkedHashSet<>();
        for (Pattern p : CONSUMED) {
            Matcher m = p.matcher(prepared);
            while (m.find()) {
                Recognizers.tokenize(m.group()).forEach(t -> consumed.add(Recognizers.normalizeKey(t)));
            }
        }
        return new Analysis(sw, ipCode(prepared), Set.copyOf(consumed));
    }

    /**
     * The word of a switch vocabulary in a part's attribute value, description or category ({@link ParametricExtractor}
     * sources); for a text of another family only the switch type of a switch IC or switching sensor. Null when the
     * text names none.
     */
    static String word(Vocabulary vocabulary, String text, String family) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String prepared = normaliseUnits(Recognizers.prepare(text));
        if (!SWITCH.equals(family)) {
            return vocabulary == Vocabulary.SWITCH_TYPE ? type(prepared, true) : null;
        }
        return switch (vocabulary) {
            case SWITCH_TYPE -> type(prepared, false);
            case CONTACTS -> {
                ParsedQuery.Contacts c = contacts(prepared);
                yield c == null ? null : c.display();
            }
            case SWITCH_FUNCTION -> function(prepared);
            case TERMINATION -> termination(prepared);
            case SWITCH_SIZE -> {
                ParsedQuery.BodySize s = size(prepared);
                yield s == null ? null : s.display();
            }
            case HOLE_DIAMETER -> {
                Double mm = hole(prepared);
                if (mm == null && prepared.length() <= SHORT_VALUE) {
                    mm = bare(prepared);   // an attribute value "12mm"
                }
                yield mm == null ? null : java.math.BigDecimal.valueOf(mm).stripTrailingZeros().toPlainString() + "mm";
            }
            case SWITCH_POSITIONS -> {
                Integer n = positions(prepared);
                yield n == null ? null : n.toString();
            }
            case ILLUMINATION -> {
                Boolean lit = illuminated(prepared);
                yield lit == null ? null : lit ? "yes" : "no";
            }
            case ILLUMINATION_COLOUR -> illuminationColour(prepared);
            case SWITCH_ORIENTATION -> orientation(prepared);
            default -> null;
        };
    }

    /** Longest attribute value whose bare millimetre size is a cut-out ({@code 12 mm}). */
    private static final int SHORT_VALUE = 12;
}
