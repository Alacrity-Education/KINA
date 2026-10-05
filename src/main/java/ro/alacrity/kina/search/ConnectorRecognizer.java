package ro.alacrity.kina.search;

import ro.alacrity.kina.domain.ParsedQuery;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Connector vocabulary shared by {@link QueryParser} and {@link ParametricExtractor} (DESIGN.md 3.4): connector type
 * (pin header, female header, box header, terminal block, JST wire-to-board, USB-C, FPC, RJ45, D-sub...), gender,
 * number of positions, rows, pitch and orientation, in the wording of users and of the three distributors
 * (LCSC {@code 1x6P 2.54mm Right Angle 弯插}, TME {@code pin strips; socket; female; PIN: 6; angled 90°},
 * Mouser {@code 6 POS 2.54MM RA Female}). Stateless and thread-safe.
 *
 * <p>{@link #analyze} returns what it recognised plus the text with the recognised spans blanked out, so the generic
 * {@link Recognizers} only see what is left (no {@code 90 degree}, {@code style}, {@code pins} noise keywords).
 */
final class ConnectorRecognizer {

    private ConnectorRecognizer() {
    }

    /**
     * What one text says about a connector.
     *
     * @param connector      the recognised attributes (never null; {@link ParsedQuery.Connector#isEmpty()} when none)
     * @param connectorWords true when the text contains connector words (a type, "connector", "header", "socket",
     *                       "plug", "jack", "receptacle", "dupont", "jst"...); positions alone ("8 pin") are not enough
     * @param mounting       "THT" / "SMD" from connector-specific wording (LCSC {@code 插件}/{@code Plugin}/{@code 卧贴}),
     *                       else null
     * @param residual       the text (NFKC) with every recognised span replaced by a blank
     */
    record Result(ParsedQuery.Connector connector, boolean connectorWords, String mounting, String residual,
                  UsbVocabulary.Analysis usb) {

        Result(ParsedQuery.Connector connector, boolean connectorWords, String mounting, String residual) {
            this(connector, connectorWords, mounting, residual, null);
        }
    }

    // ------------------------------------------------------------------ patterns (applied to lower-case text)

    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    /** {@code 1x6}, {@code 2x3P}, {@code 1*6}, {@code 2×20 pins}; not {@code 4x4mm} (dimensions). */
    private static final Pattern GRID = Pattern.compile(
            "(?<![\\w.])(\\d{1,2})\\s*[x×*]\\s*(\\d{1,3})(?:\\s*(?:p\\b|pins?\\b|pos(?:itions?)?\\b|ways?\\b|contacts?\\b))?"
                    + "(?![\\w.])(?!\\s*mm)", F);
    private static final Pattern ROWS_WORD = Pattern.compile(
            "\\b(single|one|dual|double|two|triple|three)[- ]rows?\\b|\\b(\\d)[- ]rows?\\b|\\b(sil|dil)\\b", F);
    private static final Pattern POSITIONS_LABEL = Pattern.compile(
            "\\b(?:number\\s+of\\s+|no\\.?\\s*of\\s+)?(?:pins?|positions?|contacts?|ways?|circuits?)\\s*:\\s*(\\d{1,3})\\b",
            F);
    private static final Pattern POSITIONS = Pattern.compile(
            "(?<![\\w.])(\\d{1,3})\\s*-?\\s*(?:positions?|pos|pins?|ways?|circuits?|ckts?|contacts?|poles?|pole|cores?)\\b"
                    + "|(?<![\\w.])(\\d{1,3})(?:p|pin|pos|ckt)\\b", F);
    private static final Pattern PITCH_LCSC = Pattern.compile("\\bp\\s*=\\s*(\\d+(?:\\.\\d+)?)\\s*mm\\b", F);
    private static final Pattern PITCH_LABEL = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*mm\\s*pitch\\b|\\bpitch\\s*(?:of\\s*)?[:=]?\\s*(\\d+(?:\\.\\d+)?)\\s*mm\\b", F);
    private static final Pattern PITCH_INCH = Pattern.compile(
            "(?<![\\w.])(0?\\.\\d{1,3})\\s*(?:\"|''|″|in\\b|inch(?:es)?\\b)(?:\\s*pitch\\b)?"
                    + "|(?<![\\w.])(100|50|79)\\s*mil\\b|(?<![\\w.])\\.(100|050)(?![\\w.])", F);
    private static final Pattern PITCH_MM = Pattern.compile("(?<![\\w.])(\\d{1,2}(?:\\.\\d{1,2})?)\\s*mm\\b", F);
    /** A bare number that can only be a connector pitch ({@code header 1x6 2.54}). */
    private static final Pattern PITCH_BARE = Pattern.compile("(?<![\\w.])(2\\.54|1\\.27|5\\.08|3\\.81|3\\.96|7\\.62)(?![\\w.])");
    /** Pitches that occur on connectors; a bare "Nmm" in a description is only a pitch when it is one of these. */
    private static final Set<Double> STANDARD_PITCHES = Set.of(0.3, 0.4, 0.5, 0.635, 0.8, 1.0, 1.25, 1.27, 1.5, 2.0,
            2.2, 2.5, 2.54, 3.0, 3.5, 3.81, 3.96, 4.2, 5.0, 5.08, 7.5, 7.62, 10.0, 10.16);

    private static final Pattern RIGHT_ANGLE = Pattern.compile(
            "\\bright[- ]?angled?\\b|\\br/?a\\b|\\brt\\.?[- ]?angl(?:e|ed)?\\b|\\brtan\\b|\\bhorz\\b|\\bhoriz\\b|\\bhorztl\\b|(?<![\\w.])90\\s*(?:°|º|˚|\\*|-?\\s*deg(?:ree)?s?\\b)|\\bangled\\b"
                    + "|\\bhorizontal\\b|\\bside[- ]entry\\b|\\bside[- ]insertion\\b|弯插|\\bbent\\b|\\bhrz\\b|\\bhz\\b", F);
    private static final Pattern VERTICAL = Pattern.compile(
            "\\bvertical\\b|\\bvert\\b\\.?|\\bupright\\b|\\bstraight\\b|(?<![\\w.])180\\s*(?:°|º|˚|-?\\s*deg(?:ree)?s?\\b)|\\btop[- ]entry\\b|直插|立贴", F);

    private static final Pattern FEMALE = Pattern.compile("\\bfemale\\b|\\bfem\\b|\\bfml\\b", F);
    private static final Pattern MALE = Pattern.compile("\\bmale\\b", F);
    /** Mouser abbreviates receptacles as RECEP / RECPT / RCPT. */
    private static final Pattern WEAK_FEMALE = Pattern.compile(
            "\\breceptacles?\\b|\\brecep(?:t)?s?\\b|\\brecpt\\b|\\brcpt\\b|\\brec\\b|\\bsockets?\\b|\\bskt\\b|\\bjacks?\\b", F);
    private static final Pattern WEAK_MALE = Pattern.compile("\\bplugs?\\b", F);

    private record TypeRule(Pattern pattern, String type) {
    }

    /** Type rules in priority order (the first matching rule decides; every match is blanked out). */
    private static final List<TypeRule> TYPES = List.of(
            new TypeRule(Pattern.compile("\\b(?:box(?:ed)?|shrouded)[- ]?(?:pin[- ])?headers?\\b|\\bidc[- ](?:box[- ])?headers?\\b",
                    F), ParsedQuery.BOX_HEADER),
            new TypeRule(Pattern.compile("\\b(?:ic|dip|chip)\\s*(?:/\\s*transistor\\s*)?sockets?\\b|\\btransistor\\s+sockets?\\b"
                    + "|socket:\\s*integrated circuits", F), ParsedQuery.IC_SOCKET),
            new TypeRule(Pattern.compile("\\b(?:(?:pcb|screw|barrier|pluggable|spring[- ]clamp)\\s+)?terminal[- ]blocks?\\b"
                    + "|\\bscrew[- ]terminals?\\b|\\bbarrier[- ]terminals?\\b", F), ParsedQuery.TERMINAL_BLOCK),
            new TypeRule(Pattern.compile("\\bfemale[- ](?:pin[- ])?headers?\\b|\\b(?:pin[- ])?socket[- ]strips?\\b"
                    + "|\\bpin[- ]sockets?\\b|\\bheader[- ](?:sockets?|receptacles?)\\b|\\bsocket[- ]headers?\\b", F),
                    ParsedQuery.FEMALE_HEADER),
            new TypeRule(Pattern.compile("\\b(?:male[- ])?pin[- ]?headers?\\b|\\bmale[- ]headers?\\b|\\bbreak[- ]?away[- ]headers?\\b"
                    + "|\\bpin[- ]strips?\\b|\\bterminal[- ]strips?\\b", F), ParsedQuery.PIN_HEADER),
            new TypeRule(Pattern.compile("\\bjst\\b|\\bwire[- ]to[- ]board\\b|\\bwire-board\\b", F), ParsedQuery.WIRE_TO_BOARD),
            new TypeRule(Pattern.compile("\\busb[\\s-]*(?:type[\\s-]*)?c\\b|\\btype[\\s-]*c\\b|(?<![°º℃\\d])\\bc[\\s-]type\\b", F),
                    ParsedQuery.USB_C),
            new TypeRule(Pattern.compile("\\bmicro[\\s-]*(?:usb[\\s-]*)?(?:type[\\s-]*)?a?b\\b|\\bmicro[\\s-]*usb\\b"
                    + "|\\busb[\\s-]+a?b[\\s-]+micro\\b|\\busb[\\s-]+micro(?:[\\s-]+a?b)?\\b", F), ParsedQuery.MICRO_USB),
            new TypeRule(UsbVocabulary.ANY_TYPE, ParsedQuery.USB),
            new TypeRule(Pattern.compile("\\busb\\b", F), ParsedQuery.USB),
            new TypeRule(Pattern.compile("\\bffc\\b|\\bfpc\\b|\\bffc/fpc\\b|\\bflat flexible\\b", F), ParsedQuery.FPC),
            new TypeRule(Pattern.compile("\\brj-?45\\b|\\b8p8c\\b|\\bmodular jacks?\\b|\\bethernet (?:jacks?|connectors?)\\b",
                    F), ParsedQuery.RJ45),
            new TypeRule(Pattern.compile("\\bd-?sub(?:miniature)?\\b|\\bd[be]-?(?:9|15|25|37)\\b", F), ParsedQuery.D_SUB),
            new TypeRule(Pattern.compile("\\bbarrel[- ]jacks?\\b|\\bdc[- ](?:power[- ])?jacks?\\b|\\bdc power connectors?\\b"
                    + "|\\bdc supply\\b|\\bpower jacks?\\b", F), ParsedQuery.BARREL_JACK),
            new TypeRule(Pattern.compile("\\bidc\\b", F), ParsedQuery.IDC_SOCKET),
            new TypeRule(Pattern.compile("\\bheaders?\\b|\\bheaders? & wire housings\\b", F), ParsedQuery.HEADER));

    private static final Pattern DUPONT = Pattern.compile("\\bdu[- ]?pont\\b", F);
    private static final Pattern SERIES = Pattern.compile("\\b(xh|ph|gh|sh|zh|eh|vh)\\b", F);
    private static final Map<String, Double> SERIES_PITCH = Map.of("XH", 2.5, "PH", 2.0, "GH", 1.25, "SH", 1.0,
            "ZH", 1.5, "EH", 2.5, "VH", 3.96);
    private static final Pattern GENERIC = Pattern.compile(
            "\\bconnectors?\\b|\\bconn\\b|\\breceptacles?\\b|\\brecep(?:t)?s?\\b|\\bsockets?\\b|\\bplugs?\\b|\\bjacks?\\b", F);
    /** Words that carry no information once the connector attributes are recognised. */
    private static final Pattern NOISE = Pattern.compile(
            "\\b(?:style|degrees?|deg|pins?|positions?|pos|ways?|rows?|pitch|angle|mount(?:ing)?|type|kind|"
                    + "contacts?|circuits?|with|for)\\b", F);
    /** A bare 1-2 digit number not followed by a unit ("USB C socket 16", not Mouser "5 Vdc", "20 V", "5 A"). */
    private static final Pattern BARE_NUMBER = Pattern.compile("(?<![\\w.,+-])(\\d{1,2})(?![\\w.,%+-])"
            + "(?!\\s*(?:v|vdc|vac|a|ma|w|mm|ohm|gbps|mbps|hz|khz|mhz|k|°|℃|cycles|times|ports?|pcs)\\b)", F);
    /** "USB port", "charging port": a port is a connector only in USB wording. */
    private static final Pattern PORT = Pattern.compile("\\bports?\\b", F);
    /**
     * Products that name a USB type without being a board connector (DESIGN.md 3.4): "USB-C PD controller",
     * "USB to UART bridge IC", "USB ESD protection diode", "USB-C cable", "USB 5V 2A power adapter".
     */
    static final Pattern NOT_A_CONNECTOR = Pattern.compile("\\b(?:ic|ics|chip|controllers?|bridge|uart|serial|hubs?|phy"
            + "|transceivers?|esd|tvs|protection|diodes?|cables?|cords?|adapters?|adaptors?|chargers?|power\\s+suppl(?:y|ies)"
            + "|modules?|switch(?:es)?|mux|converters?|flash\\s+drives?|sticks?|card\\s+readers?|isolators?|drivers?"
            + "|docking|testers?|breakout)\\b", F);
    private static final Pattern THT_WORDS = Pattern.compile("插件|\\bplugin\\b", F);
    private static final Pattern SMD_WORDS = Pattern.compile("卧贴|立贴", F);

    private static final Pattern DECIMAL_COMMA = Pattern.compile("(?<=\\d),(?=\\d)");

    /** NFKC text with decimal commas as points (case is kept: every pattern is case-insensitive). */
    private static String normalise(String text) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC);
        return DECIMAL_COMMA.matcher(s).replaceAll(".");
    }

    /** Recognises connector attributes in a query or a part text (see {@link Result}). */
    static Result analyze(String text) {
        if (text == null || text.isBlank()) {
            return new Result(new ParsedQuery.Connector(null, null, null, null, null, null, false, null), false, null,
                    text == null ? "" : text);
        }
        StringBuilder s = new StringBuilder(normalise(text));

        // USB standard, features and explicit sums first ("Gen 2x2" is no 2x2 grid, "IP67" no value)
        UsbVocabulary.Analysis usb = UsbVocabulary.analyze(s, false);

        // type: the first rule that matches (priority order); every rule's matches are blanked
        String type = null;
        for (TypeRule rule : TYPES) {
            if (find(rule.pattern(), s) != null && type == null) {
                type = rule.type();
            }
            blank(rule.pattern(), s);
        }
        boolean dupont = blank(DUPONT, s);
        String series = null;
        Matcher sm = SERIES.matcher(s);
        if ((type == null || ParsedQuery.WIRE_TO_BOARD.equals(type) || ParsedQuery.CONNECTOR.equals(type))
                && sm.find() && (ParsedQuery.WIRE_TO_BOARD.equals(type) || find(GENERIC, s) != null)) {
            series = sm.group(1).toUpperCase(Locale.ROOT);
            type = ParsedQuery.WIRE_TO_BOARD;
            blank(SERIES, s);
        }

        // rows x positions before positions and pitch
        Integer rows = null;
        Integer positions = null;
        Matcher m = GRID.matcher(s);
        if (m.find()) {
            int a = Integer.parseInt(m.group(1));
            int b = Integer.parseInt(m.group(2));
            if (a > 4 && b <= 4) {
                int t = a;
                a = b;
                b = t;
            }
            if (a >= 1 && b >= 1) {
                rows = a;
                positions = a * b;
            }
        }
        blank(GRID, s);
        m = ROWS_WORD.matcher(s);
        if (m.find() && rows == null) {
            String word = m.group(1) != null ? m.group(1) : m.group(3);   // SIL / DIL: single / dual in line
            rows = m.group(2) != null ? Integer.parseInt(m.group(2)) : switch (word.toLowerCase(Locale.ROOT)) {
                case "single", "one", "sil" -> 1;
                case "dual", "double", "two", "dil" -> 2;
                default -> 3;
            };
        }
        blank(ROWS_WORD, s);

        // pitch: LCSC "P=2.54mm", "2.54mm pitch", inches, then the first standard "Nmm"
        Double pitch = null;
        boolean pitchImplied = false;
        m = PITCH_LCSC.matcher(s);
        if (m.find()) {
            pitch = Double.parseDouble(m.group(1));
        }
        blank(PITCH_LCSC, s);
        m = PITCH_LABEL.matcher(s);
        if (m.find() && pitch == null) {
            pitch = Double.parseDouble(m.group(1) != null ? m.group(1) : m.group(2));
        }
        blank(PITCH_LABEL, s);
        m = PITCH_INCH.matcher(s);
        if (m.find() && pitch == null) {
            double inch = m.group(1) != null ? Double.parseDouble(m.group(1))
                    : m.group(2) != null ? Integer.parseInt(m.group(2)) / 1000.0 : Integer.parseInt(m.group(3)) / 1000.0;
            if (inch > 0 && inch <= 0.4) {
                pitch = Math.round(inch * 25.4 * 100) / 100.0;
            }
        }
        blank(PITCH_INCH, s);
        m = PITCH_MM.matcher(s);
        while (pitch == null && m.find()) {
            double mm = Double.parseDouble(m.group(1));
            if (STANDARD_PITCHES.contains(mm)) {
                pitch = mm;
                s.replace(m.start(), m.end(), " ".repeat(m.end() - m.start()));
            }
        }
        m = PITCH_BARE.matcher(s);
        if (pitch == null && m.find()) {
            pitch = Double.parseDouble(m.group(1));
            s.replace(m.start(), m.end(), " ".repeat(m.end() - m.start()));
        }
        if (pitch == null && series != null) {
            pitch = SERIES_PITCH.get(series);
            pitchImplied = pitch != null;
        }
        if (pitch == null && dupont) {
            pitch = 2.54;
            pitchImplied = true;
        }

        // orientation: the earliest marker wins
        String orientation = null;
        int ra = firstIndex(RIGHT_ANGLE, s);
        int vertical = firstIndex(VERTICAL, s);
        if (ra >= 0 && (vertical < 0 || ra <= vertical)) {
            orientation = ParsedQuery.RIGHT_ANGLE;
        } else if (vertical >= 0) {
            orientation = ParsedQuery.VERTICAL;
        }
        blank(RIGHT_ANGLE, s);
        blank(VERTICAL, s);

        // positions (after the grid, pitch and orientation spans are gone)
        m = POSITIONS_LABEL.matcher(s);
        if (m.find() && positions == null) {
            positions = Integer.parseInt(m.group(1));
        }
        blank(POSITIONS_LABEL, s);
        m = POSITIONS.matcher(s);
        while (m.find()) {
            String digits = m.group(1) != null ? m.group(1) : m.group(2);
            int n = Integer.parseInt(digits);
            if (positions == null && n > 0) {
                positions = n;
            }
        }
        blank(POSITIONS, s);

        // gender: explicit words beat socket/receptacle/jack/plug; the earliest explicit word wins
        String original = normalise(text);
        String gender = gender(original);

        boolean generic = GENERIC.matcher(original).find();
        boolean connectorWords = type != null || dupont || series != null || generic;
        if (usb.usbType() != null && (type == null || UsbVocabulary.isUsbType(type))) {
            type = UsbVocabulary.connectorType(usb.usbType());
        }
        if (UsbVocabulary.isUsbType(type) && !generic && !dupont && series == null) {
            // a bare "USB" or a USB type next to IC/cable/adapter words is not a connector request
            boolean explicit = gender != null || PORT.matcher(original).find();
            connectorWords = explicit || usb.usbType() != null && !NOT_A_CONNECTOR.matcher(original).find();
        }
        if (type == null && dupont) {
            type = ParsedQuery.HEADER;
        }
        if (type == null && connectorWords) {
            type = ParsedQuery.CONNECTOR;
        }
        type = refineType(type, gender);
        if (gender == null) {
            gender = impliedGender(type);
        }

        String mounting = null;
        if (THT_WORDS.matcher(original).find()) {
            mounting = "THT";
        } else if (SMD_WORDS.matcher(original).find()) {
            mounting = "SMD";
        }
        if (orientation == null && "THT".equals(mounting) && original.contains("插件") && isHeader(type)) {
            orientation = ParsedQuery.VERTICAL;   // JLCPCB: 插件 straight through-hole vs 弯插 right angle
        }

        if (connectorWords) {
            blank(FEMALE, s);
            blank(MALE, s);
            blank(GENERIC, s);
            blank(NOISE, s);
        }
        ParsedQuery.Connector connector;
        if (UsbVocabulary.isUsbType(type) || usb.usbType() != null) {
            if (connectorWords) {
                blank(PORT, s);
            }
            // TME wording "USB C socket 16": a bare number that is a pin configuration of the USB type
            Matcher bare = BARE_NUMBER.matcher(s);
            String usbType = usb.usbType() != null ? usb.usbType() : UsbVocabulary.usbTypeOf(type);
            if (positions == null && usb.plusPositions() == null && bare.find()
                    && UsbVocabulary.configuration(usbType, Integer.parseInt(bare.group(1))) != null) {
                positions = Integer.parseInt(bare.group(1));
                s.replace(bare.start(), bare.end(), " ".repeat(bare.end() - bare.start()));
            }
            connector = usbConnector(type, gender, positions, orientation, usb);
        } else {
            connector = new ParsedQuery.Connector(type, series, gender, positions, rows, pitch, pitchImplied,
                    orientation);
        }
        return new Result(connector, connectorWords, mounting, s.toString(), usb);
    }

    /**
     * A USB connector: the reported positions are kept, the canonical pin configuration is derived from them
     * ({@link UsbVocabulary#configuration}) or from an explicit sum ({@code 16+2P}); standard and features as written.
     * No inference here: a query's implied configuration ({@link QueryParser}) and a part's physical standard
     * ({@link ParametricExtractor}) are applied by the callers.
     */
    static ParsedQuery.Connector usbConnector(String type, String gender, Integer positions, String orientation,
                                              UsbVocabulary.Analysis usb) {
        String usbType = usb.usbType() != null ? usb.usbType() : UsbVocabulary.usbTypeOf(type);
        Integer configuration;
        Integer shield = null;
        if (usb.plusPositions() != null) {
            positions = usb.plusPositions();
            configuration = usb.plusConfiguration();
            shield = usb.plusShield();
        } else {
            configuration = UsbVocabulary.configuration(usbType, positions);
            if (configuration != null && positions != null && positions > configuration) {
                shield = positions - configuration;
            }
        }
        List<String> features = new ArrayList<>(usb.features());
        if (usb.ipRating() != null) {
            features.add(usb.ipRating());
        }
        UsbVocabulary.Standard standard = usb.standard();
        return new ParsedQuery.Connector(type == null ? UsbVocabulary.connectorType(usbType) : type, null, gender,
                positions, null, null, false, orientation, usbType, standard == null ? null : standard.name(),
                standard == null ? null : standard.gbps(), configuration, false, shield,
                mountingStyle(usb.features()), features);
    }

    /** mid-mount, else hybrid (also SMD with through-hole shell legs), else top-mount; null when none is said. */
    static String mountingStyle(List<String> features) {
        if (features.contains(UsbVocabulary.MID_MOUNT)) {
            return UsbVocabulary.MID_MOUNT;
        }
        if (features.contains(UsbVocabulary.HYBRID) || features.contains(UsbVocabulary.THROUGH_HOLE_SHELL)) {
            return UsbVocabulary.HYBRID;
        }
        return features.contains(UsbVocabulary.TOP_MOUNT) ? UsbVocabulary.TOP_MOUNT : null;
    }

    private static String gender(String text) {
        int female = firstIndex(FEMALE, text);
        int male = firstIndex(MALE, text);
        if (female >= 0 && (male < 0 || female <= male)) {
            return ParsedQuery.FEMALE;
        }
        if (male >= 0) {
            return ParsedQuery.MALE;
        }
        int weakFemale = firstIndex(WEAK_FEMALE, text);
        int weakMale = firstIndex(WEAK_MALE, text);
        if (weakFemale >= 0 && (weakMale < 0 || weakFemale <= weakMale)) {
            return ParsedQuery.FEMALE;
        }
        return weakMale >= 0 ? ParsedQuery.MALE : null;
    }

    /** "female pin header" is a female header; TME "pin strips; socket; male" is a pin header. */
    private static String refineType(String type, String gender) {
        if (type == null) {
            return null;
        }
        if (ParsedQuery.FEMALE.equals(gender)
                && (ParsedQuery.PIN_HEADER.equals(type) || ParsedQuery.HEADER.equals(type))) {
            return ParsedQuery.FEMALE_HEADER;
        }
        if (ParsedQuery.MALE.equals(gender)
                && (ParsedQuery.FEMALE_HEADER.equals(type) || ParsedQuery.HEADER.equals(type))) {
            return ParsedQuery.PIN_HEADER;
        }
        if (ParsedQuery.IDC_SOCKET.equals(type) && ParsedQuery.MALE.equals(gender)) {
            return ParsedQuery.BOX_HEADER;
        }
        return type;
    }

    private static String impliedGender(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case ParsedQuery.PIN_HEADER, ParsedQuery.BOX_HEADER -> ParsedQuery.MALE;
            case ParsedQuery.FEMALE_HEADER, ParsedQuery.IC_SOCKET, ParsedQuery.IDC_SOCKET -> ParsedQuery.FEMALE;
            default -> null;
        };
    }

    /** The wire-to-board series named in a text ("XH", "PH"...), or null. */
    static String series(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = SERIES.matcher(text);
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : null;
    }

    /** Pin headers, female headers, box headers and gender-less headers. */
    static boolean isHeader(String type) {
        return ParsedQuery.PIN_HEADER.equals(type) || ParsedQuery.FEMALE_HEADER.equals(type)
                || ParsedQuery.HEADER.equals(type) || ParsedQuery.BOX_HEADER.equals(type);
    }

    /**
     * Whether two connector types describe compatible parts: equal, or one is a gender-less {@code header} and the
     * other a pin/female/box header. Null or the generic {@code connector} is unknown (also "compatible").
     */
    static Boolean typesMatch(String wanted, String actual) {
        if (wanted == null || actual == null || ParsedQuery.CONNECTOR.equals(wanted)
                || ParsedQuery.CONNECTOR.equals(actual)) {
            return null;
        }
        if (wanted.equals(actual)) {
            return true;
        }
        if ((ParsedQuery.HEADER.equals(wanted) && isHeader(actual))
                || (ParsedQuery.HEADER.equals(actual) && isHeader(wanted))) {
            return null;
        }
        if (ParsedQuery.USB.equals(wanted) && actual.contains("usb") || ParsedQuery.USB.equals(actual) && wanted.contains("usb")) {
            return null;
        }
        return false;
    }

    // ------------------------------------------------------------------ helpers

    private static String find(Pattern p, CharSequence s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group() : null;
    }

    private static int firstIndex(Pattern p, CharSequence s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.start() : -1;
    }

    /** Replaces every match by blanks (keeps offsets); true when something matched. */
    private static boolean blank(Pattern p, StringBuilder s) {
        Matcher m = p.matcher(s);
        List<int[]> spans = new ArrayList<>();
        while (m.find()) {
            spans.add(new int[] {m.start(), m.end()});
        }
        for (int[] span : spans) {
            s.replace(span[0], span[1], " ".repeat(span[1] - span[0]));
        }
        return !spans.isEmpty();
    }
}
