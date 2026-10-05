package ro.alacrity.kina.search;

import lombok.experimental.UtilityClass;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rewrites a parsed query into the wording each distributor's search understands (DESIGN.md 3.2 "Distributor
 * phrasing"). Connector queries are rewritten; a passive request naming a technology gets the technology words in the
 * distributor's spelling ({@link #technologyPhrase}); for every other query {@link #phrase} returns null and the
 * user's text is sent verbatim. Pure functions of the parsed query, so a cached search can report what was sent.
 *
 * <ul>
 *   <li>LCSC (JLCPCB FTS5 database): category phrase, {@code RxNP}/{@code NP}, {@code "Right Angle"}, pitch, mounting,
 *       e.g. {@code "Female Header" 6P "Right Angle" 2.54mm}; {@code JlcpcbQuery} turns the category phrase into a
 *       {@code "Second Category"} column filter and maps the mounting/orientation words to the database vocabulary.</li>
 *   <li>TME (phrase of at most {@value #TME_MAX_LENGTH} characters): TME description wording, most informative tokens
 *       first, e.g. {@code pin strips female 6 angled}.</li>
 *   <li>Mouser (keyword): {@code female header 6 pos right angle}, {@code male header 40 pos 2.54mm vertical}.</li>
 * </ul>
 *
 * <p>USB connector requests ({@link #usbPhrase}) never carry the pin count: distributors list some Type-C receptacles
 * with their shell pins (17P/18P for a 16-pin part, {@code PIN: 17}, {@code 17 Positions}), so a count in the phrase
 * would exclude them; the ranker sorts by the canonical configuration instead. Type, gender, mounting, standard and
 * features stay, e.g. for {@code USB-C receptacle 16 pin SMD USB 2.0}: LCSC
 * {@code "USB Connectors" Type-C 16P/17P/18P "USB 2.0" "Surface Mount"} (an OR group of the configuration and its
 * shell-counted variants), TME {@code USB C socket SMT 2.0}, Mouser {@code USB type C receptacle SMD 2.0}.
 */
@UtilityClass
public class DistributorPhraser {

    /** TME's {@code phrase} limit (DESIGN.md 9.2). */
    public static final int TME_MAX_LENGTH = 40;
    /** Fewest and most tokens of a keyword fallback phrase (DESIGN.md 3.2). */
    static final int MIN_FALLBACK_TOKENS = 3;
    static final int MAX_FALLBACK_TOKENS = 5;

    /**
     * The phrase to send to {@code distributor} instead of the user's text, or null to send the text verbatim
     * (every non-connector query, and connector queries without any recognised attribute).
     */
    public static String phrase(Distributor distributor, ParsedQuery query) {
        if (query != null && !query.isConnector() && query.technology() != null) {
            return technologyPhrase(distributor, query);
        }
        if (query == null || !query.isConnector() || query.connector().isEmpty()) {
            return null;
        }
        String phrase = query.connector().isUsb() ? usbPhrase(distributor, query) : switch (distributor) {
            case LCSC -> lcsc(query);
            case TME -> tme(query);
            case MOUSER -> mouser(query);
        };
        if (phrase == null || phrase.isBlank()
                || QueryParser.normalizeKey(phrase).equals(query.normalizedKey())) {
            return null;
        }
        return phrase;
    }

    /**
     * The shorter phrase tried once when {@code sent} (the phrase or the user's text) found nothing at Mouser or TME
     * (DESIGN.md 3.2 "Phrase fallback"): for connector queries the type words with the positions (TME) or with the
     * pitch and orientation (Mouser); otherwise the parametric core ({@link PartSearchService#corePhrase}) or, for keyword-only
     * queries, the {@value #MIN_FALLBACK_TOKENS} to {@value #MAX_FALLBACK_TOKENS} most informative tokens. Null when
     * there is nothing shorter to try or it equals {@code sent}.
     */
    public static String fallback(Distributor distributor, ParsedQuery query, String sent) {
        if (query == null || distributor == Distributor.LCSC) {
            return null;
        }
        String candidate = null;
        if (query.isConnector() && query.connector().isUsb()) {
            candidate = usbFallback(distributor, query.connector());
        } else if (query.isConnector() && !query.connector().isEmpty()) {
            candidate = distributor == Distributor.TME ? tmeFallback(query) : mouserFallback(query);
        }
        if (candidate == null) {
            candidate = PartSearchService.corePhrase(query);
        }
        if (candidate == null) {
            candidate = keywordCore(query);
        }
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        if (distributor == Distributor.TME) {
            candidate = limit(candidate, TME_MAX_LENGTH);
        }
        String sentKey = QueryParser.normalizeKey(sent == null ? query.originalText() : sent);
        return QueryParser.normalizeKey(candidate).equals(sentKey) ? null : candidate;
    }

    // ---------------------------------------------------------------- technology (passives)

    /**
     * The user's text with the technology words in the distributor's spelling ({@link TechnologyVocabulary#spelling}):
     * LCSC a quoted JLCPCB phrase ({@code "Thin Film"}, {@code "Aluminum Electrolytic"}), TME {@code wirewound} and
     * {@code electrolytic}, Mouser {@code wirewound}, {@code thin film}, {@code thick film}. Null (text sent verbatim)
     * when the distributor has no spelling of its own or the text already uses it.
     */
    static String technologyPhrase(Distributor distributor, ParsedQuery q) {
        String spelling = TechnologyVocabulary.spelling(distributor, q.technology());
        String text = q.originalText();
        TechnologyVocabulary.Match m = spelling == null ? null : TechnologyVocabulary.find(text, q.family());
        if (m == null || !q.technology().equals(m.technology())) {
            return null;
        }
        String phrase = (text.substring(0, m.start()).stripTrailing() + " " + spelling + " "
                + text.substring(m.end()).stripLeading()).strip().replaceAll("\\s+", " ");
        return QueryParser.normalizeKey(phrase).equals(q.normalizedKey()) ? null : phrase;
    }

    // ---------------------------------------------------------------- LCSC

    private static String lcsc(ParsedQuery q) {
        ParsedQuery.Connector c = q.connector();
        List<String> tokens = new ArrayList<>();
        String type = c.type();
        String category = switch (type == null ? "" : type) {
            case ParsedQuery.FEMALE_HEADER -> "\"Female Header\"";
            case ParsedQuery.PIN_HEADER -> "\"Pin Header\"";
            case ParsedQuery.HEADER -> "\"Header\"";
            case ParsedQuery.BOX_HEADER -> "\"IDC Header\"";
            case ParsedQuery.IDC_SOCKET -> "\"IDC Connectors\"";
            case ParsedQuery.IC_SOCKET -> "\"IC Socket\"";
            case ParsedQuery.TERMINAL_BLOCK -> "\"Terminal Block\"";
            case ParsedQuery.WIRE_TO_BOARD -> "\"Wire To Board\"";
            case ParsedQuery.USB_C, ParsedQuery.MICRO_USB, ParsedQuery.USB -> "\"USB Connectors\"";
            case ParsedQuery.FPC -> "\"FPC\"";
            case ParsedQuery.RJ45 -> "RJ45";
            case ParsedQuery.D_SUB -> "\"D-Sub\"";
            case ParsedQuery.BARREL_JACK -> "\"DC Power\"";
            default -> null;
        };
        if (category != null) {
            tokens.add(category);
        }
        if (ParsedQuery.USB_C.equals(type)) {
            tokens.add("Type-C");
        } else if (ParsedQuery.MICRO_USB.equals(type)) {
            tokens.add("Micro-B");
        } else if (ParsedQuery.WIRE_TO_BOARD.equals(type) && c.series() != null) {
            tokens.add(c.series());
        } else if (ParsedQuery.D_SUB.equals(type) && c.gender() != null) {
            tokens.add(capitalise(c.gender()));
        }
        String positions = lcscPositions(c);
        if (positions != null) {
            tokens.add(positions);
        }
        boolean rightAngle = ParsedQuery.RIGHT_ANGLE.equals(c.orientation());
        if (rightAngle) {
            tokens.add("\"Right Angle\"");
        }
        if (c.pitchMm() != null) {
            tokens.add(c.pitchDisplay());
        }
        // JLCPCB writes "Through Hole" only for straight THT headers (right-angle ones say "Right Angle" instead)
        if ("THT".equals(q.mounting()) && !rightAngle) {
            tokens.add("\"Through Hole\"");
        } else if ("SMD".equals(q.mounting())) {
            tokens.add("\"Surface Mount\"");
            if (ParsedQuery.VERTICAL.equals(c.orientation())) {
                tokens.add("Vertical");
            }
        }
        // free-text words last: the relaxation drops them first when they do not occur together
        tokens.addAll(informativeKeywords(q, 2));
        return tokens.isEmpty() ? null : String.join(" ", tokens);
    }

    /** {@code 1x6P} when the rows are known (and divide the positions), else {@code 6P}. */
    private static String lcscPositions(ParsedQuery.Connector c) {
        if (c.positions() == null) {
            return null;
        }
        if (c.rows() != null && c.rows() > 0 && c.positions() % c.rows() == 0) {
            return c.rows() + "x" + (c.positions() / c.rows()) + "P";
        }
        return c.positions() + "P";
    }

    // ---------------------------------------------------------------- TME

    private static String tmeTypeWords(ParsedQuery.Connector c) {
        String type = c.type() == null ? "" : c.type();
        String socketOrPlug = ParsedQuery.MALE.equals(c.gender()) ? "plug" : "socket";
        return switch (type) {
            case ParsedQuery.FEMALE_HEADER -> "pin strips female";
            case ParsedQuery.PIN_HEADER -> "pin header male";
            case ParsedQuery.HEADER -> "pin strips";
            case ParsedQuery.BOX_HEADER -> "IDC male";
            case ParsedQuery.IDC_SOCKET -> "IDC female";
            case ParsedQuery.IC_SOCKET -> "IC socket";
            case ParsedQuery.TERMINAL_BLOCK -> "terminal block";
            case ParsedQuery.WIRE_TO_BOARD -> c.series() != null ? "wire-board " + c.series() : "wire-board";
            case ParsedQuery.USB_C -> "USB C " + socketOrPlug;
            case ParsedQuery.MICRO_USB -> "USB B micro " + socketOrPlug;
            case ParsedQuery.USB -> "USB " + socketOrPlug;
            case ParsedQuery.FPC -> "FFC/FPC";
            case ParsedQuery.RJ45 -> "RJ45 " + socketOrPlug;
            case ParsedQuery.D_SUB -> c.gender() != null ? "D-Sub " + c.gender() : "D-Sub";
            case ParsedQuery.BARREL_JACK -> "DC supply " + socketOrPlug;
            default -> null;
        };
    }

    /** TME words for the orientation: "angled"/"straight", "horizontal"/"vertical" for USB and FFC/FPC. */
    private static String tmeOrientation(ParsedQuery.Connector c) {
        if (c.orientation() == null) {
            return null;
        }
        boolean flat = c.type() != null && (c.type().contains("usb") || ParsedQuery.FPC.equals(c.type()));
        if (ParsedQuery.RIGHT_ANGLE.equals(c.orientation())) {
            return flat ? "horizontal" : "angled";
        }
        return flat ? "vertical" : "straight";
    }

    private static String tme(ParsedQuery q) {
        ParsedQuery.Connector c = q.connector();
        List<String> tokens = new ArrayList<>();
        String type = tmeTypeWords(c);
        if (type != null) {
            tokens.add(type);
        } else {
            tokens.addAll(informativeKeywords(q, 2));
            tokens.add("connector");
        }
        if (c.positions() != null) {
            tokens.add(c.rows() != null && c.rows() > 1 && c.positions() % c.rows() == 0
                    ? c.rows() + "x" + (c.positions() / c.rows()) : c.positions().toString());
        }
        String orientation = tmeOrientation(c);
        if (orientation != null) {
            tokens.add(orientation);
        }
        if (c.pitchMm() != null && !c.pitchImplied()) {
            tokens.add(c.pitchDisplay());
        }
        if (type != null) {
            tokens.addAll(informativeKeywords(q, 2));   // kept only while the phrase stays within 40 characters
        }
        return join(tokens, TME_MAX_LENGTH);
    }

    private static String tmeFallback(ParsedQuery q) {
        ParsedQuery.Connector c = q.connector();
        String type = tmeTypeWords(c);
        if (type == null) {
            return null;
        }
        List<String> tokens = new ArrayList<>(List.of(type));
        if (c.positions() != null) {
            tokens.add(c.positions().toString());
        }
        return join(tokens, TME_MAX_LENGTH);
    }

    // ---------------------------------------------------------------- Mouser

    private static String mouserTypeWords(ParsedQuery.Connector c) {
        String type = c.type() == null ? "" : c.type();
        String receptacleOrPlug = ParsedQuery.MALE.equals(c.gender()) ? "plug" : "receptacle";
        return switch (type) {
            case ParsedQuery.FEMALE_HEADER -> "female header";
            case ParsedQuery.PIN_HEADER -> "male header";
            case ParsedQuery.HEADER -> "header";
            case ParsedQuery.BOX_HEADER -> "shrouded header";
            case ParsedQuery.IDC_SOCKET -> "IDC socket";
            case ParsedQuery.IC_SOCKET -> "IC socket";
            case ParsedQuery.TERMINAL_BLOCK -> "terminal block";
            case ParsedQuery.WIRE_TO_BOARD -> c.series() != null ? "JST " + c.series() : "wire to board";
            case ParsedQuery.USB_C -> "USB type C " + receptacleOrPlug;
            case ParsedQuery.MICRO_USB -> "micro USB " + receptacleOrPlug;
            case ParsedQuery.USB -> "USB " + receptacleOrPlug;
            case ParsedQuery.FPC -> "FPC connector";
            case ParsedQuery.RJ45 -> "RJ45 " + (ParsedQuery.MALE.equals(c.gender()) ? "plug" : "jack");
            case ParsedQuery.D_SUB -> c.gender() != null ? "D-Sub " + c.gender() : "D-Sub";
            case ParsedQuery.BARREL_JACK -> "DC power jack";
            default -> null;
        };
    }

    private static String mouser(ParsedQuery q) {
        ParsedQuery.Connector c = q.connector();
        List<String> tokens = new ArrayList<>();
        String type = mouserTypeWords(c);
        if (type != null) {
            tokens.add(type);
        } else {
            tokens.addAll(informativeKeywords(q, 2));
            tokens.add("connector");
        }
        // positions as "N pos": verified live 2026-10-05, "female header 6 pos right angle" finds 6-pin right-angle
        // female headers while "... 6 position ..." matched 9 modular jacks; an implied pitch (Dupont) is left out
        if (c.positions() != null) {
            tokens.add(c.positions() + " pos");
        }
        if (c.pitchMm() != null && !c.pitchImplied()) {
            tokens.add(c.pitchDisplay());
        }
        if (c.orientation() != null) {
            tokens.add(c.orientation());
        }
        if (q.mounting() != null && !ConnectorRecognizer.isHeader(c.type())) {
            tokens.add(q.mounting());
        }
        if (type != null) {
            tokens.addAll(informativeKeywords(q, 2));
        }
        return String.join(" ", tokens);
    }

    /** The type words with the pitch and orientation (positions dropped). */
    private static String mouserFallback(ParsedQuery q) {
        ParsedQuery.Connector c = q.connector();
        String type = mouserTypeWords(c);
        if (type == null) {
            return null;
        }
        List<String> tokens = new ArrayList<>(List.of(type));
        if (c.pitchMm() != null && !c.pitchImplied()) {
            tokens.add(c.pitchDisplay());
        }
        if (c.orientation() != null) {
            tokens.add(c.orientation());
        }
        return String.join(" ", tokens);
    }

    // ---------------------------------------------------------------- USB connectors

    /**
     * USB connector phrase (DESIGN.md 3.2): type, gender, mounting, standard and features in each distributor's
     * wording; never the pin count (see the class comment). LCSC: {@code "USB Connectors"} + the dominant JLCPCB
     * spelling of the type ({@code Type-C}, {@code Micro-B}, {@code Type-A}...; {@code JlcpcbQuery} adds
     * {@code TypeC}/{@code MicroB}/... as alternatives), {@code Male} for plugs, the stated pin count as an OR group
     * ({@link #lcscUsbPositions}), the standard ({@code "USB 2.0"},
     * {@code "USB 3"} for any 3.x, {@code USB4}), mounting, orientation and {@code mid-mount}/{@code waterproof}/
     * {@code "board lock"} (mapped to the database words by {@code JlcpcbQuery}). TME (40 characters, TME ANDs the
     * words): {@code USB C socket}, {@code SMT}/{@code THT}, {@code horizontal}/{@code vertical}, {@code 2.0} (3.x is
     * left out: TME's Version values vary between 3.0, 3.1, 3.2 and Gen spellings, and "USB C socket 24 horizontal 3.1"
     * found nothing live), {@code charging} (TME "only for charging (6p)"), {@code middle} (TME "middle board mount"),
     * the IP rating or {@code waterproof}. Mouser: {@code USB type C receptacle}, orientation, mounting, the version
     * as written ({@code 2.0}, {@code 3.1}, {@code USB4}), {@code mid}, {@code hybrid}, the IP rating or
     * {@code waterproof}, and for power-only requests {@code 6 pos power only} (the one exception to "no pin count":
     * live, "USB type C receptacle 6 pos power only" returned 11 power-only parts, "... power only" without it 1).
     */
    static String usbPhrase(Distributor distributor, ParsedQuery q) {
        ParsedQuery.Connector c = q.connector();
        String usbType = c.usbType() != null ? c.usbType() : UsbVocabulary.usbTypeOf(c.type());
        boolean plug = ParsedQuery.MALE.equals(c.gender());
        String version = UsbVocabulary.analyze(new StringBuilder(q.originalText()), true).versionToken();
        UsbVocabulary.Standard standard = UsbVocabulary.standard(c.usbStandard());
        List<String> tokens = new ArrayList<>();
        switch (distributor) {
            case LCSC -> {
                tokens.add("\"USB Connectors\"");
                if (usbType != null) {
                    tokens.add(usbType);
                }
                if (plug) {
                    tokens.add("Male");
                }
                String positions = lcscUsbPositions(usbType, c);
                if (positions != null) {
                    tokens.add(positions);
                }
                if (standard != null) {
                    tokens.add(standard.rank() <= 1 ? "\"" + standard.name() + "\"" : standard.rank() >= 5 ? "USB4"
                            : "\"USB 3\"");
                }
                if ("THT".equals(q.mounting())) {
                    tokens.add("\"Through Hole\"");
                } else if ("SMD".equals(q.mounting())) {
                    tokens.add("\"Surface Mount\"");
                }
                if (ParsedQuery.RIGHT_ANGLE.equals(c.orientation())) {
                    tokens.add("\"Right Angle\"");
                } else if (ParsedQuery.VERTICAL.equals(c.orientation())) {
                    tokens.add("Vertical");
                }
                if (c.hasFeature(UsbVocabulary.MID_MOUNT)) {
                    tokens.add("mid-mount");
                }
                if (c.hasFeature(UsbVocabulary.WATERPROOF)) {
                    tokens.add("waterproof");
                }
                if (c.hasFeature(UsbVocabulary.BOARD_LOCK)) {
                    tokens.add("\"board lock\"");
                }
                tokens.addAll(informativeKeywords(q, 2));
                return String.join(" ", tokens);
            }
            case TME -> {
                tokens.add(tmeUsbType(usbType, c.type()) + " " + (plug ? "plug" : "socket"));
                if ("THT".equals(q.mounting())) {
                    tokens.add("THT");
                } else if ("SMD".equals(q.mounting())) {
                    tokens.add("SMT");
                }
                String orientation = tmeOrientation(c);
                if (orientation != null) {
                    tokens.add(orientation);
                }
                if (standard != null && standard.rank() == 1) {
                    tokens.add("2.0");
                } else if (standard != null && standard.rank() == 2 && !ParsedQuery.USB_TYPE_C.equals(usbType)) {
                    tokens.add("3.0");
                }
                if (c.hasFeature(UsbVocabulary.POWER_ONLY)) {
                    tokens.add("charging");
                }
                if (c.hasFeature(UsbVocabulary.MID_MOUNT)) {
                    tokens.add("middle");
                }
                if (c.hasFeature(UsbVocabulary.WATERPROOF)) {
                    tokens.add(ipRating(c) != null ? ipRating(c) : "waterproof");
                }
                tokens.addAll(informativeKeywords(q, 2));
                return join(tokens, TME_MAX_LENGTH);
            }
            default -> {
                tokens.add(mouserUsbType(usbType, c.type()) + " " + (plug ? "plug" : "receptacle"));
                if (c.orientation() != null) {
                    tokens.add(c.orientation());
                }
                if (q.mounting() != null) {
                    tokens.add(q.mounting());
                }
                if (version != null) {
                    tokens.add(version);
                }
                if (c.hasFeature(UsbVocabulary.POWER_ONLY)) {
                    tokens.add("6 pos power only");
                }
                if (c.hasFeature(UsbVocabulary.MID_MOUNT)) {
                    tokens.add("mid");
                }
                if (UsbVocabulary.HYBRID.equals(c.mountingStyle())) {
                    tokens.add("hybrid");
                }
                if (c.hasFeature(UsbVocabulary.WATERPROOF)) {
                    tokens.add(ipRating(c) != null ? ipRating(c) : "waterproof");
                }
                tokens.addAll(informativeKeywords(q, 2));
                return String.join(" ", tokens);
            }
        }
    }

    /**
     * LCSC positions of a USB request whose pin count the user gave, as an OR group of the configuration and its
     * shell-counted variants ({@code 16P/17P/18P}, {@code 24P/25P/26P}, {@code 6P/7P/8P}; JlcpcbQuery matches any of
     * them at a number boundary). JLCPCB descriptions list the signal contacts (no 17P/18P Type-C row exists), so the
     * group costs nothing and keeps the fixed LCSC window on the requested configuration: without it a 50-part window
     * of the 2 991 in-stock Type-C rows held only two 6-pin parts for "USB-C 6 pin power only". An implied
     * configuration (from the standard) is not used.
     */
    private static String lcscUsbPositions(String usbType, ParsedQuery.Connector c) {
        if (c.pinConfigurationImplied() || c.pinConfiguration() == null && c.positions() == null) {
            return null;
        }
        int configuration = c.pinConfiguration() != null ? c.pinConfiguration() : c.positions();
        List<String> group = new ArrayList<>(List.of(configuration + "P"));
        for (int extra = 1; extra <= 2 && usbType != null; extra++) {
            Integer mapped = UsbVocabulary.configuration(usbType, configuration + extra);
            if (mapped != null && mapped == configuration) {
                group.add((configuration + extra) + "P");
            }
        }
        return String.join("/", group);
    }

    /** The shorter USB phrase tried when the first one found nothing: the type and gender words only. */
    private static String usbFallback(Distributor distributor, ParsedQuery.Connector c) {
        String usbType = c.usbType() != null ? c.usbType() : UsbVocabulary.usbTypeOf(c.type());
        boolean plug = ParsedQuery.MALE.equals(c.gender());
        return distributor == Distributor.TME ? tmeUsbType(usbType, c.type()) + " " + (plug ? "plug" : "socket")
                : mouserUsbType(usbType, c.type()) + " " + (plug ? "plug" : "receptacle");
    }

    /** TME "Type of connector" wording: {@code USB C}, {@code USB B micro}, {@code USB A}... */
    private static String tmeUsbType(String usbType, String type) {
        if (usbType == null) {
            return ParsedQuery.MICRO_USB.equals(type) ? "USB B micro" : "USB";
        }
        return switch (usbType) {
            case ParsedQuery.USB_TYPE_C -> "USB C";
            case ParsedQuery.USB_MICRO_B -> "USB B micro";
            case ParsedQuery.USB_MICRO_AB -> "USB AB micro";
            case ParsedQuery.USB_MINI_B, ParsedQuery.USB_MINI_AB -> "USB B mini";
            case ParsedQuery.USB_TYPE_A -> "USB A";
            case ParsedQuery.USB_TYPE_B -> "USB B";
            default -> "USB";
        };
    }

    /** Mouser keyword wording: {@code USB type C}, {@code micro USB}, {@code USB type A}... */
    private static String mouserUsbType(String usbType, String type) {
        if (usbType == null) {
            return ParsedQuery.MICRO_USB.equals(type) ? "micro USB" : "USB";
        }
        return switch (usbType) {
            case ParsedQuery.USB_TYPE_C -> "USB type C";
            case ParsedQuery.USB_MICRO_B -> "micro USB";
            case ParsedQuery.USB_MICRO_AB -> "micro USB AB";
            case ParsedQuery.USB_MINI_B, ParsedQuery.USB_MINI_AB -> "mini USB";
            case ParsedQuery.USB_TYPE_A -> "USB type A";
            case ParsedQuery.USB_TYPE_B -> "USB type B";
            default -> "USB";
        };
    }

    private static String ipRating(ParsedQuery.Connector c) {
        return c.features().stream().filter(f -> f.startsWith("IP")).findFirst().orElse(null);
    }

    // ---------------------------------------------------------------- keyword core

    /** Words that rarely identify a part. */
    private static final Set<String> FILLER = Set.of("nice", "good", "cheap", "small", "tiny", "big", "large", "best",
            "quality", "standard", "generic", "common", "part", "parts", "component", "components", "module", "board",
            "please", "need", "want", "looking", "some", "any", "new", "original", "genuine", "high", "low", "like");
    private static final Pattern HAS_DIGIT = Pattern.compile(".*\\d.*");
    private static final Pattern HAS_LETTER = Pattern.compile(".*\\p{L}.*");

    /**
     * The {@value #MIN_FALLBACK_TOKENS} to {@value #MAX_FALLBACK_TOKENS} most informative tokens of a query without a
     * parametric core, in query order: the family word, value/package terms, then keywords ranked by informativeness
     * (part-number-like tokens with letters and digits first, then longer words; filler words never). Null when the
     * query has fewer than {@value #MIN_FALLBACK_TOKENS} usable tokens or nothing would be dropped.
     */
    static String keywordCore(ParsedQuery q) {
        List<String> words = Recognizers.tokenize(Recognizers.prepare(q.originalText() == null ? "" : q.originalText()));
        if (words.size() <= MIN_FALLBACK_TOKENS) {
            return null;
        }
        Set<String> keywords = new LinkedHashSet<>(q.keywords());
        String familyWord = Recognizers.familyToken(q.originalText(), q.family());
        record Scored(int index, String word, int score) {
        }
        List<Scored> scored = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            String key = QueryParser.normalizeKey(w);
            if (!seen.add(key) || FILLER.contains(key) || STOP.contains(key)) {
                continue;
            }
            int score;
            if (w.equals(familyWord)) {
                score = 5;
            } else if (!keywords.contains(key)) {
                score = 4;                                    // recognised value, package, dielectric, mounting
            } else if (HAS_DIGIT.matcher(w).matches() && HAS_LETTER.matcher(w).matches()) {
                score = 3;                                    // part-number-like
            } else if (key.length() >= 5) {
                score = 2;
            } else if (key.length() >= 3) {
                score = 1;
            } else {
                continue;
            }
            scored.add(new Scored(i, w, score));
        }
        if (scored.size() < MIN_FALLBACK_TOKENS) {
            return null;
        }
        List<Scored> best = scored.stream()
                .sorted(Comparator.comparingInt(Scored::score).reversed().thenComparingInt(Scored::index))
                .limit(MAX_FALLBACK_TOKENS)
                .sorted(Comparator.comparingInt(Scored::index))
                .toList();
        if (best.size() >= words.size()) {
            return null;
        }
        return String.join(" ", best.stream().map(Scored::word).toList());
    }

    private static final Set<String> STOP = Set.of("a", "an", "the", "for", "with", "and", "or", "of", "in", "on",
            "to", "pcs", "pc", "type", "rohs", "new", "style");

    /** Up to {@code max} keywords of the query, most informative first (see {@link #keywordCore}). */
    private static List<String> informativeKeywords(ParsedQuery q, int max) {
        return q.keywords().stream()
                .filter(k -> !FILLER.contains(k) && !STOP.contains(k) && k.length() >= 2)
                .sorted(Comparator.comparingInt((String k) -> HAS_DIGIT.matcher(k).matches() ? 0 : 1)
                        .thenComparing(Comparator.comparingInt(String::length).reversed()))
                .limit(max)
                .toList();
    }

    // ---------------------------------------------------------------- helpers

    /** Joins tokens in order, skipping any token that would push the phrase beyond {@code max} characters. */
    private static String join(List<String> tokens, int max) {
        StringBuilder out = new StringBuilder();
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            int length = out.length() + (out.isEmpty() ? 0 : 1) + token.length();
            if (length > max) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(token);
        }
        return out.isEmpty() ? null : out.toString();
    }

    /** Shortens a phrase at a word boundary to at most {@code max} characters. */
    private static String limit(String phrase, int max) {
        return phrase.length() <= max ? phrase : join(List.of(phrase.split(" ")), max);
    }

    private static String capitalise(String word) {
        return word.isEmpty() ? word : word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1);
    }
}
