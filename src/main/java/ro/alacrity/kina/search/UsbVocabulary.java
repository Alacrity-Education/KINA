package ro.alacrity.kina.search;

import lombok.experimental.UtilityClass;
import ro.alacrity.kina.domain.ParsedQuery;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * USB connector vocabulary shared by {@link ConnectorRecognizer} (queries and part texts), {@link ParametricExtractor},
 * {@link DeterministicRanker} and {@link DistributorPhraser} (DESIGN.md 3.4, 9.1-9.3). Stateless and thread-safe.
 *
 * <p>Vocabulary mined on 2026-10-05 (counts are JLCPCB database rows in {@code Connectors / USB Connectors}, 8 606 rows,
 * 6 503 in stock; TME and Mouser from live searches through KINA):
 * <ul>
 *   <li><b>LCSC/JLCPCB</b> (description only, no parametric columns): connector type {@code Type-C} (2 739),
 *       {@code TypeC} (131), {@code Type-A} (1 663), {@code TypeA} (64), {@code Type-B} (119), {@code Micro-B} (668),
 *       {@code MicroB} (15), {@code Micro-AB} (16), {@code Mini-B} (115), {@code MiniB}, {@code Mini-AB} (19); positions
 *       {@code 16P} (Type-C: 1 119), {@code 24P} (804), {@code 6P} (480), {@code 14P} (107), {@code 12P} (48),
 *       {@code 2P}/{@code 8P}, {@code 5P} (Micro-B 623), {@code 4P}/{@code 9P} (Type-A 1 194/199); standard
 *       {@code USB 2.0}, {@code USB 3.0}, {@code USB 3.1} (841 Type-C rows, also on 16P and 6P parts that physically
 *       cannot carry SuperSpeed), {@code USB 3.2}, {@code USB4}/{@code USB 4}; gender {@code Female}/{@code Male};
 *       mounting {@code Surface Mount}, {@code Through Hole}, package {@code SMD}/{@code Plugin}/{@code 插件};
 *       orientation {@code Right Angle}, {@code Vertical}, {@code Vertical, Flag}, {@code Side insertion};
 *       mid-mount {@code Recessed}, {@code Sink board}, {@code Sinking}, {@code Laminated board} (沉板);
 *       straddle {@code Clamping plate} (plugs); board lock {@code With Locating Pins}, {@code with Post};
 *       waterproof only in the part number ({@code IPX7}, {@code IPX8}) or {@code with O-ring}. No 17P/18P Type-C row:
 *       JLCPCB lists the signal contacts; the only shell-counted Type-C found is {@code TYPE-C-24} with package
 *       {@code SMD-26P}. {@code aP+bP} means several ports ({@code 4P+4P} stacked Type-A, {@code 9P+9P},
 *       {@code 4P+14P} Type-A + Type-C combo); {@code 2P+4J} is 2 pins + 4 legs (脚); {@code 16P+1.0} in a part number
 *       is a sink depth, not a pin count. No power-only or PD wording.</li>
 *   <li><b>TME</b> (parameters, the richest source): {@code Type of connector} = {@code USB C}, {@code USB B micro},
 *       {@code USB A}, {@code USB B}; {@code Connector} = socket/plug; {@code Number of pins}; {@code Version} =
 *       {@code USB 2.0}, {@code USB 3.0}, {@code USB 3.1}, {@code USB 3.1 Gen 1/2}, {@code USB 3.2}, {@code USB 4.0};
 *       {@code Data transfer rate} = {@code 0.48Gbps}, {@code 5Gbps}, {@code 10Gbps}, {@code 20Gbps};
 *       {@code Connector variant} = {@code top board mount}, {@code middle board mount}, {@code Gen.2x2},
 *       {@code sealed}; {@code Electrical mounting} = {@code SMT}, {@code THT}, {@code SMT, THT},
 *       {@code hybrid SMT/THT}, {@code Fully SMT}; {@code Spatial orientation} = horizontal/vertical/angled 90°;
 *       {@code Connectors application} = {@code only for charging (6p)}; {@code IP rating} = IP67/IP68/IPX7.</li>
 *   <li><b>Mouser</b> (keyword search returns only {@code Packaging}/{@code Standard Pack Qty} as ProductAttributes for
 *       USB connectors, so everything comes from the description): {@code Type C, 2.0}, {@code USB2.0 Type C Rcpt},
 *       {@code USB Jack 2.0, Type-C}, {@code USB jack 3.1 C type 24pin}, {@code USB 3.2 Type C Gen 1},
 *       {@code USB3.2 Gen 2}, {@code USB 3.2 Gen 2x1, 10 Gbps}, {@code USB4, 40 Gbps}, {@code 480 Mbps},
 *       {@code 16 Pin}/{@code 16P}/{@code 24pos.}/{@code 24Ckt}/{@code 8 Positions}, {@code Rec}/{@code Recpt}/
 *       {@code Rcpt}/{@code Skt}/{@code Receptacle}/{@code Plug}, {@code Horz}/{@code HZ}/{@code R/A}/{@code Vert},
 *       {@code Mid Mount SMT}/{@code Mid Mnt}/{@code MidMt}/{@code MSMT}/{@code Mid-Mnt}, {@code Top Mount}/{@code
 *       TOPMNT}/{@code T.Mt.}, {@code Hybrid}, {@code SMT & TH stakes}, {@code Power Only}, {@code Charge-Only},
 *       {@code IP67}/{@code IPX7}/{@code Waterproof}, {@code W/ PEGS}/{@code peg}/{@code with post}.</li>
 * </ul>
 */
@UtilityClass
class UsbVocabulary {

    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    // ------------------------------------------------------------------ connector types

    private record TypeRule(Pattern pattern, String usbType) {
    }

    /** USB connector types in priority order; a rule's spans are not reconsidered by later rules. */
    static final List<TypeRule> TYPE_RULES = List.of(
            new TypeRule(Pattern.compile("\\busb[\\s-]*(?:type[\\s-]*)?c\\b|\\btype[\\s-]*c\\b|(?<![°º℃\\d])\\bc[\\s-]type\\b", F),
                    ParsedQuery.USB_TYPE_C),
            new TypeRule(Pattern.compile("\\bmicro[\\s-]*(?:usb[\\s-]*)?(?:type[\\s-]*)?ab\\b|\\busb[\\s-]+ab[\\s-]+micro\\b",
                    F), ParsedQuery.USB_MICRO_AB),
            new TypeRule(Pattern.compile("\\bmicro[\\s-]*(?:usb[\\s-]*)?(?:type[\\s-]*)?b\\b|\\bmicro[\\s-]*usb\\b"
                    + "|\\busb[\\s-]+b[\\s-]+micro\\b|\\busb[\\s-]+micro(?:[\\s-]+b)?\\b", F), ParsedQuery.USB_MICRO_B),
            new TypeRule(Pattern.compile("\\bmini[\\s-]*(?:usb[\\s-]*)?(?:type[\\s-]*)?ab\\b", F), ParsedQuery.USB_MINI_AB),
            new TypeRule(Pattern.compile("\\bmini[\\s-]*(?:usb[\\s-]*)?(?:type[\\s-]*)?b\\b|\\bmini[\\s-]*usb\\b"
                    + "|\\busb[\\s-]+b[\\s-]+mini\\b", F), ParsedQuery.USB_MINI_B),
            new TypeRule(Pattern.compile("\\busb[\\s-]*(?:type[\\s-]*)?a\\b|\\btype[\\s-]*a\\b", F), ParsedQuery.USB_TYPE_A),
            new TypeRule(Pattern.compile("\\busb[\\s-]*(?:type[\\s-]*)?b\\b|\\btype[\\s-]*b\\b", F), ParsedQuery.USB_TYPE_B));

    /** Every USB type pattern OR-ed (for blanking). */
    static final Pattern ANY_TYPE = Pattern.compile(String.join("|",
            TYPE_RULES.stream().map(r -> r.pattern().pattern()).toList()), F);

    /** The first USB connector type named in {@code text} (by rule priority, earliest position within a rule). */
    static String usbType(CharSequence text) {
        if (text == null) {
            return null;
        }
        for (TypeRule rule : TYPE_RULES) {
            if (rule.pattern().matcher(text).find()) {
                return rule.usbType();
            }
        }
        return null;
    }

    /** The {@link ParsedQuery} connector type of a USB type ({@code Type-C} -&gt; {@code usb-c}...). */
    static String connectorType(String usbType) {
        if (usbType == null) {
            return null;
        }
        return switch (usbType) {
            case ParsedQuery.USB_TYPE_C -> ParsedQuery.USB_C;
            case ParsedQuery.USB_MICRO_B, ParsedQuery.USB_MICRO_AB -> ParsedQuery.MICRO_USB;
            default -> ParsedQuery.USB;
        };
    }

    /** The USB type implied by a connector type ({@code usb-c} -&gt; Type-C, {@code micro usb} -&gt; Micro-B). */
    static String usbTypeOf(String connectorType) {
        if (ParsedQuery.USB_C.equals(connectorType)) {
            return ParsedQuery.USB_TYPE_C;
        }
        return ParsedQuery.MICRO_USB.equals(connectorType) ? ParsedQuery.USB_MICRO_B : null;
    }

    /** {@code usb-c}, {@code micro usb} or {@code usb}. */
    static boolean isUsbType(String connectorType) {
        return ParsedQuery.USB_C.equals(connectorType) || ParsedQuery.MICRO_USB.equals(connectorType)
                || ParsedQuery.USB.equals(connectorType);
    }

    // ------------------------------------------------------------------ standards

    /**
     * A USB standard with its canonical speed class.
     *
     * @param name canonical name ({@code USB 2.0}, {@code USB 3.2 Gen 1}, {@code USB 3.x} (generation not stated),
     *             {@code USB 3.2 Gen 2}, {@code USB 3.2 Gen 2x2}, {@code USB4}, {@code Thunderbolt 3/4}, {@code USB 1.1})
     * @param gbps signalling rate in Gbit/s (USB 3.x without generation: 5, the minimum)
     * @param rank speed class: 0 = 1.1, 1 = 2.0, 2 = 3.x Gen 1 (and 3.x unknown), 3 = Gen 2, 4 = Gen 2x2, 5 = 40 Gbps
     */
    record Standard(String name, double gbps, int rank) {

        boolean generationUnknown() {
            return USB_3X.equals(name);
        }

        boolean superSpeed() {
            return rank >= 2;
        }
    }

    static final String USB_3X = "USB 3.x";
    static final Standard USB_1_1 = new Standard("USB 1.1", 0.012, 0);
    static final Standard USB_2_0 = new Standard("USB 2.0", 0.48, 1);
    static final Standard GEN_1 = new Standard("USB 3.2 Gen 1", 5, 2);
    static final Standard GEN_UNKNOWN = new Standard(USB_3X, 5, 2);
    static final Standard GEN_2 = new Standard("USB 3.2 Gen 2", 10, 3);
    static final Standard GEN_2X2 = new Standard("USB 3.2 Gen 2x2", 20, 4);
    static final Standard USB4 = new Standard("USB4", 40, 5);
    static final Standard TB3 = new Standard("Thunderbolt 3", 40, 5);
    static final Standard TB4 = new Standard("Thunderbolt 4", 40, 5);

    private static final Map<String, Standard> BY_NAME = Map.of(USB_1_1.name(), USB_1_1, USB_2_0.name(), USB_2_0,
            GEN_1.name(), GEN_1, GEN_UNKNOWN.name(), GEN_UNKNOWN, GEN_2.name(), GEN_2, GEN_2X2.name(), GEN_2X2,
            USB4.name(), USB4, TB3.name(), TB3, TB4.name(), TB4);

    static Standard standard(String name) {
        return name == null ? null : BY_NAME.get(name);
    }

    private record StandardRule(Pattern pattern, Standard standard) {
    }

    private static final String V3 = "(?:usb[\\s-]*)?3\\.[12][\\s,-]*";

    /** Standard wording in priority order (the first rule that matches decides; all are blanked). */
    private static final List<StandardRule> STANDARD_RULES = List.of(
            new StandardRule(Pattern.compile("\\bthunderbolt[\\s-]*3\\b|\\btb3\\b", F), TB3),
            new StandardRule(Pattern.compile("\\bthunderbolt[\\s-]*4\\b|\\btb4\\b|\\bthunderbolt\\b", F), TB4),
            new StandardRule(Pattern.compile("\\busb[\\s-]*4(?:\\.0)?(?:[\\s-]*v?[12](?:\\.0)?)?(?![\\w.])"
                    + "(?!\\s*(?:p|pins?|pos|positions?|ports?)\\b)|(?<![\\w.])40\\s*gbps\\b", F), USB4),
            new StandardRule(Pattern.compile("\\b(?:" + V3 + ")?gen\\.?[\\s-]*2\\s*x\\s*2\\b|(?<![\\w.])20\\s*gbps\\b", F),
                    GEN_2X2),
            new StandardRule(Pattern.compile("\\b(?:" + V3 + ")?gen\\.?[\\s-]*2(?:\\s*x\\s*1)?\\b|(?<![\\w.])10\\s*gbps\\b"
                    + "|\\bsuperspeed\\s*(?:\\+|plus)", F), GEN_2),
            new StandardRule(Pattern.compile("\\b(?:" + V3 + ")?gen\\.?[\\s-]*1\\b|\\busb[\\s-]*3\\.0\\b|\\busb3\\.0\\b"
                    + "|(?<![\\w.])5\\s*gbps\\b|(?<![\\w.])5g\\b|\\bsuperspeed\\b", F), GEN_1),
            new StandardRule(Pattern.compile("\\busb[\\s-]*3\\.[12]\\b|\\busb[\\s-]*3(?:\\.x)?(?![\\w.])", F), GEN_UNKNOWN),
            new StandardRule(Pattern.compile("\\busb[\\s-]*2\\.[01]\\b|(?<![\\w.])480\\s*mbps\\b|(?<![\\w.])0\\.48\\s*gbps\\b"
                    + "|\\bhi(?:gh)?[\\s-]*speed\\s+usb\\b", F), USB_2_0),
            new StandardRule(Pattern.compile("\\busb[\\s-]*1\\.[01]\\b|(?<![\\w.])12\\s*mbps\\b", F), USB_1_1));

    /** A bare version next to USB wording ({@code Type C, 2.0}, {@code USB Jack 2.0}, {@code USB A 3.0 Skt}). */
    private static final Pattern BARE_VERSION = Pattern.compile("(?<![\\w.])(1\\.1|2\\.0|3\\.0|3\\.1|3\\.2)(?![\\w.%])");

    static Standard bareVersion(String version) {
        return switch (version) {
            case "1.1" -> USB_1_1;
            case "2.0" -> USB_2_0;
            case "3.0" -> GEN_1;
            default -> GEN_UNKNOWN;
        };
    }

    // ------------------------------------------------------------------ features

    static final String POWER_ONLY = "power only";
    static final String PD = "PD";
    static final String MID_MOUNT = "mid-mount";
    static final String TOP_MOUNT = "top-mount";
    static final String HYBRID = "hybrid";
    static final String THROUGH_HOLE_SHELL = "through-hole shell";
    static final String WATERPROOF = "waterproof";
    static final String BOARD_LOCK = "board lock";
    static final String STRADDLE = "straddle-mount";
    static final String MULTI_PORT = "multi-port";
    static final String FULLY_SMD = "fully SMD";

    private record FeatureRule(Pattern pattern, String feature) {
    }

    private static final List<FeatureRule> FEATURE_RULES = List.of(
            new FeatureRule(Pattern.compile("\\bpower[\\s-]*only\\b|\\bcharg(?:e|ing)[\\s-]*only\\b"
                    + "|\\bonly\\s+for\\s+charging\\b|\\bfor\\s+charging\\s+only\\b", F), POWER_ONLY),
            new FeatureRule(Pattern.compile("\\bpower[\\s-]*delivery\\b|\\busb[\\s-]*pd\\b|\\bpd\\b", F), PD),
            new FeatureRule(Pattern.compile("\\bmid(?:dle)?(?:[\\s-]*board)?[\\s-]*(?:mount(?:ing|ed)?|mnt|mt)\\b\\.?"
                    + "|\\bmidmt\\b|\\bmsmt\\b|\\bmid[\\s-]*surface\\b|\\bmid\\b|\\bsunken\\b|\\bsink(?:ing)?(?:[\\s-]*board)?\\b"
                    + "|\\brecessed\\b|\\blaminated\\s+board\\b|\\bdrop[\\s-]*in\\b|\\bmiddle\\b", F), MID_MOUNT),
            new FeatureRule(Pattern.compile("\\btop[\\s-]*(?:board[\\s-]*)?(?:mount(?:ed)?|mnt|mt)\\b\\.?|\\btopmnt\\b"
                    + "|\\bt\\.mt\\b\\.?|\\btop[\\s-]*mount\\b", F), TOP_MOUNT),
            new FeatureRule(Pattern.compile("\\bhybrid(?:[\\s-]*(?:smt|smd)\\s*/\\s*tht)?(?:[\\s-]*mount)?\\b"
                    + "|\\bsm[td]\\s*[,/&+]\\s*(?:tht|dip)\\b|\\b(?:dip|tht)\\s*[+&]\\s*sm[td]\\b", F), HYBRID),
            new FeatureRule(Pattern.compile("\\b(?:sm[td]\\s*&\\s*)?(?:th|tht|through[\\s-]*hole|dip)\\s+"
                    + "(?:shell|stakes?|legs?|tabs?|posts?)\\b|\\bw/\\s*shell\\s+stakes?\\b|\\bshell\\s+stakes?\\b", F),
                    THROUGH_HOLE_SHELL),
            new FeatureRule(Pattern.compile("\\bfully\\s+sm[td]\\b|\\ball[\\s-]*sm[td]\\b", F), FULLY_SMD),
            new FeatureRule(Pattern.compile("\\bwater[\\s-]*proof\\b|\\bsealed\\b|\\bo-?ring\\b|\\bgasket\\b", F),
                    WATERPROOF),
            new FeatureRule(Pattern.compile("\\bboard[\\s-]*locks?\\b|\\blocating\\s+(?:pins?|pegs?|posts?)\\b"
                    + "|\\b(?:w/|with)\\s*(?:pegs?|posts?)\\b|\\bpegs?\\b|\\blocating\\s+pegs?\\b", F), BOARD_LOCK),
            new FeatureRule(Pattern.compile("\\bclamping\\s+plate\\b|\\bstraddle(?:[\\s-]*mount)?\\b", F), STRADDLE));

    /** USB context words. */
    static final Pattern USB_WORD = Pattern.compile("usb|thunderbolt", F);

    /** IP ratings: {@code IP67}, {@code IP68}, {@code IPX7}, {@code IP65}. */
    private static final Pattern IP_RATING = Pattern.compile("\\bip([x0-9])([0-9])k?\\b", F);
    /** {@code 4 legs}, {@code 2 legs}, LCSC {@code 4J} (脚). */
    private static final Pattern LEGS = Pattern.compile("(?<![\\w.])([2-8])\\s*(?:legs?|j)\\b", F);
    /** Explicit sums: {@code 16+2P}, {@code 16P+2}, {@code 4P+4P}, {@code 4P+14P}, {@code 2P+4J}, {@code 9Px2+12P}. */
    private static final Pattern PLUS = Pattern.compile(
            "(?<![\\w.])\\d{1,2}\\s*[pj]?(?:\\s*x\\s*\\d)?(?:\\s*\\+\\s*\\d{1,2}\\s*[pj]?(?:\\s*x\\s*\\d)?)+(?![\\w.])", F);
    private static final Pattern PLUS_TERM = Pattern.compile("(\\d{1,2})\\s*([pj]?)(?:\\s*x\\s*(\\d))?", F);

    // ------------------------------------------------------------------ pin configurations

    /** Canonical signal-pin configurations per USB type (the counts a request or a datasheet means). */
    static final Map<String, List<Integer>> CANONICAL = Map.of(
            ParsedQuery.USB_TYPE_C, List.of(6, 12, 14, 16, 24),
            ParsedQuery.USB_MICRO_B, List.of(5, 10),
            ParsedQuery.USB_MICRO_AB, List.of(5, 10),
            ParsedQuery.USB_MINI_B, List.of(5),
            ParsedQuery.USB_MINI_AB, List.of(5),
            ParsedQuery.USB_TYPE_A, List.of(4, 9),
            ParsedQuery.USB_TYPE_B, List.of(4, 9));
    /** Type-C configurations without data lines (VBUS/GND/CC only, or VBUS/GND only). */
    static final Set<Integer> TYPE_C_POWER_ONLY = Set.of(2, 4, 6);

    /**
     * A reported pin count as a canonical configuration: itself when canonical for the type, else the canonical
     * configuration C with {@code N - C} in {1, 2} (shell, shield or mounting pins counted by the distributor:
     * Type-C 17/18 -&gt; 16, 25/26 -&gt; 24, 7/8 -&gt; 6, 13 -&gt; 12; Micro-B 6/7 -&gt; 5; Type-A 5/6 -&gt; 4, 10/11 -&gt; 9);
     * 14 stays 14 (a real USB 2.0 Type-C configuration). Null for an unknown type or a count with no such C.
     */
    static Integer configuration(String usbType, Integer reported) {
        if (reported == null || usbType == null) {
            return null;
        }
        List<Integer> canonical = CANONICAL.get(usbType);
        if (canonical == null) {
            return null;
        }
        if (canonical.contains(reported)) {
            return reported;
        }
        for (int extra = 1; extra <= 2; extra++) {
            if (canonical.contains(reported - extra)) {
                return reported - extra;
            }
        }
        if (ParsedQuery.USB_TYPE_C.equals(usbType) && TYPE_C_POWER_ONLY.contains(reported)) {
            return reported;
        }
        return null;
    }

    /**
     * The configuration a request implies when it names the standard but no pin count (DESIGN.md 3.4): Type-C power
     * only -&gt; 6, Type-C USB 2.0 -&gt; 16, Type-C USB 3.x/USB4/Thunderbolt -&gt; 24; Micro-B/Mini-B USB 2.0 -&gt; 5,
     * Micro-B USB 3.x -&gt; 10; Type-A/Type-B USB 2.0 -&gt; 4, USB 3.x -&gt; 9; else null.
     */
    static Integer impliedConfiguration(String usbType, Standard standard, boolean powerOnly) {
        if (usbType == null) {
            return null;
        }
        if (ParsedQuery.USB_TYPE_C.equals(usbType)) {
            if (powerOnly) {
                return 6;
            }
            if (standard == null) {
                return null;
            }
            return standard.superSpeed() ? 24 : 16;
        }
        if (standard == null) {
            return null;
        }
        boolean three = standard.superSpeed();
        return switch (usbType) {
            case ParsedQuery.USB_MICRO_B, ParsedQuery.USB_MICRO_AB -> three ? 10 : 5;
            case ParsedQuery.USB_MINI_B, ParsedQuery.USB_MINI_AB -> three ? null : 5;
            case ParsedQuery.USB_TYPE_A, ParsedQuery.USB_TYPE_B -> three ? 9 : 4;
            default -> null;
        };
    }

    /**
     * The standard a part's configuration implies when the distributor states none, or caps a stated one: a Type-C
     * part with 12, 14 or 16 contacts has no SuperSpeed pairs (USB 2.0 at most: LCSC labels many 16P parts "USB 3.1"
     * after the Type-C specification generation); Micro-B/Mini-B 5 and Type-A/B 4 are USB 2.0; Micro-B 10 and
     * Type-A/B 9 are USB 3.x Gen 1. Type-C 24 implies nothing (USB 3.x, USB4 and USB 2.0-only 24P parts exist).
     */
    static Standard physicalStandard(String usbType, Integer configuration, Standard stated) {
        if (usbType == null || configuration == null) {
            return stated;
        }
        if (ParsedQuery.USB_TYPE_C.equals(usbType)) {
            if (configuration == 12 || configuration == 14 || configuration == 16) {
                return stated == null || stated.superSpeed() ? USB_2_0 : stated;
            }
            return stated;
        }
        if (stated != null) {
            return stated;
        }
        return switch (usbType) {
            case ParsedQuery.USB_MICRO_B, ParsedQuery.USB_MICRO_AB -> configuration == 5 ? USB_2_0
                    : configuration == 10 ? GEN_1 : null;
            case ParsedQuery.USB_MINI_B, ParsedQuery.USB_MINI_AB -> configuration == 5 ? USB_2_0 : null;
            case ParsedQuery.USB_TYPE_A, ParsedQuery.USB_TYPE_B -> configuration == 4 ? USB_2_0
                    : configuration == 9 ? GEN_1 : null;
            default -> null;
        };
    }

    /**
     * Standard comparison for the ranker: 1 same speed class, 0.5 a higher class than requested, -1 a lower class,
     * null when unknown. A USB 3.x part or request without a generation matches any USB 3.x class it could be; a
     * request for Gen 2 (or faster) against a USB 3.x part of unknown generation is unknown.
     */
    static Double compare(Standard wanted, Standard actual) {
        if (wanted == null || actual == null) {
            return null;
        }
        if (wanted.generationUnknown() && actual.rank() >= 2 && actual.rank() <= 4) {
            return 1.0;
        }
        if (actual.generationUnknown() && wanted.rank() >= 2 && wanted.rank() <= 4) {
            return wanted.rank() == 2 ? 1.0 : null;
        }
        if (actual.rank() == wanted.rank()) {
            return 1.0;
        }
        return actual.rank() > wanted.rank() ? 0.5 : -1.0;
    }

    // ------------------------------------------------------------------ analysis

    /**
     * What one text says about a USB connector, beyond the generic connector attributes.
     *
     * @param usbType           {@link ParsedQuery#USB_TYPE_C}, {@link ParsedQuery#USB_MICRO_B}... or null
     * @param standard          the stated standard, or null
     * @param versionToken      the version as written ({@code 2.0}, {@code 3.1}, {@code USB4}, {@code Gen 2}), or null
     * @param features          recognised features in a stable order ({@link #POWER_ONLY}, {@link #MID_MOUNT}...)
     * @param ipRating          {@code IP67}, {@code IPX8}..., or null
     * @param plusPositions     the reported total of an explicit sum ({@code 16+2P} -&gt; 18), or null
     * @param plusConfiguration the signal configuration of an explicit sum ({@code 16+2P} -&gt; 16), or null
     * @param plusShield        the shell/shield pins of an explicit sum ({@code 16+2P} -&gt; 2), or null
     */
    record Analysis(String usbType, Standard standard, String versionToken, List<String> features, String ipRating,
                    Integer plusPositions, Integer plusConfiguration, Integer plusShield) {

        boolean has(String feature) {
            return features.contains(feature);
        }
    }

    /**
     * Recognises USB wording in {@code s} (NFKC text, case kept) and blanks the standard, feature, IP rating and
     * explicit-sum spans (not the connector type words: {@link ConnectorRecognizer}'s type rules blank those).
     *
     * @param usbContext true when the text is about USB (a USB type or the word "USB" occurs); bare versions
     *                   ({@code Type C, 2.0}) and the {@code PD}/{@code mid}/{@code sealed}... words count only then
     */
    static Analysis analyze(StringBuilder s, boolean usbContext) {
        String usbType = usbType(s);
        boolean usb = usbContext || usbType != null || USB_WORD.matcher(s).find();
        if (!usb) {
            return new Analysis(null, null, null, List.of(), null, null, null, null);
        }
        Standard standard = null;
        String version = null;
        for (StandardRule rule : STANDARD_RULES) {
            Matcher m = rule.pattern().matcher(s);
            if (m.find() && standard == null) {
                standard = rule.standard();
                version = versionToken(m.group(), standard);
            }
            blank(rule.pattern(), s);
        }
        if (standard == null && usb) {
            Matcher m = BARE_VERSION.matcher(s);
            if (m.find()) {
                standard = bareVersion(m.group(1));
                version = m.group(1);
                s.replace(m.start(), m.end(), " ".repeat(m.end() - m.start()));
            }
        }
        Set<String> features = new LinkedHashSet<>();
        String ip = null;
        if (usb) {
            Matcher m = IP_RATING.matcher(s);
            if (m.find()) {
                ip = ("IP" + m.group(1) + m.group(2)).toUpperCase(Locale.ROOT);
                features.add(WATERPROOF);
            }
            blank(IP_RATING, s);
            for (FeatureRule rule : FEATURE_RULES) {
                if (blank(rule.pattern(), s)) {
                    features.add(rule.feature());
                }
            }
        }
        Integer plusPositions = null;
        Integer plusConfiguration = null;
        Integer plusShield = null;
        if (usb) {
            Matcher m = PLUS.matcher(s);
            while (m.find()) {
                List<int[]> pins = new ArrayList<>();
                Matcher t = PLUS_TERM.matcher(m.group());
                boolean anyP = false;
                while (t.find()) {
                    int n = Integer.parseInt(t.group(1));
                    int times = t.group(3) == null ? 1 : Integer.parseInt(t.group(3));
                    if ("j".equalsIgnoreCase(t.group(2))) {
                        features.add(n + " legs");
                    } else {
                        anyP |= "p".equalsIgnoreCase(t.group(2));
                        pins.add(new int[] {n, times});
                    }
                }
                if (!anyP && !m.group().toLowerCase(Locale.ROOT).contains("j")) {
                    continue;   // "3+2" without P is not a pin count
                }
                s.replace(m.start(), m.end(), " ".repeat(m.end() - m.start()));
                if (pins.isEmpty()) {
                    continue;
                }
                List<Integer> canonical = usbType != null && CANONICAL.containsKey(usbType) ? CANONICAL.get(usbType)
                        : List.of(4, 5, 6, 9, 10, 12, 14, 16, 24);
                int total = pins.stream().mapToInt(p -> p[0] * p[1]).sum();
                if (pins.size() == 1) {
                    plusPositions = total;
                    plusConfiguration = usbType != null ? configuration(usbType, pins.getFirst()[0])
                            : canonical.contains(pins.getFirst()[0]) ? pins.getFirst()[0] : null;
                    if (pins.getFirst()[1] > 1) {
                        features.add(MULTI_PORT);
                    }
                } else if (pins.size() == 2 && pins.get(1)[0] <= 4 && pins.get(1)[1] == 1 && pins.get(0)[1] == 1
                        && canonical.contains(pins.get(0)[0]) && pins.get(1)[0] != pins.get(0)[0]) {
                    plusPositions = total;                       // 16+2P: signal contacts plus shell pins
                    plusConfiguration = pins.get(0)[0];
                    plusShield = pins.get(1)[0];
                } else {
                    // 4P+4P, 9P+9P: stacked ports; 4P+14P next to "Type-A、Type-C": a combo; 8P+16P (Molex
                    // 2171800001, one Type-C port): the canonical term is the configuration
                    plusPositions = total;
                    plusConfiguration = pins.stream().map(p -> p[0]).filter(canonical::contains)
                            .max(Integer::compare).orElse(null);
                    boolean stacked = pins.stream().map(p -> p[0]).distinct().count() == 1;
                    if (stacked || TYPE_RULES.stream().filter(r -> r.pattern().matcher(s).find()).count() > 1) {
                        features.add(MULTI_PORT);
                    }
                }
            }
            m = LEGS.matcher(s);
            while (m.find()) {
                features.add(m.group(1) + " legs");
            }
            blank(LEGS, s);
        }
        return new Analysis(usbType, standard, version, List.copyOf(features), ip, plusPositions, plusConfiguration,
                plusShield);
    }

    private static String versionToken(String matched, Standard standard) {
        Matcher v = Pattern.compile("\\d\\.\\d").matcher(matched);
        if (standard == USB4) {
            return "USB4";
        }
        if (standard == TB3 || standard == TB4) {
            return standard.name();
        }
        if (v.find()) {
            String token = v.group();
            Matcher gen = Pattern.compile("(?i)gen\\.?\\s*(\\dx?\\d?)").matcher(matched);
            return gen.find() ? token + " Gen " + gen.group(1).toLowerCase(Locale.ROOT) : token;
        }
        return switch (standard.rank()) {
            case 1 -> "2.0";
            case 2 -> "3.0";
            case 3 -> "Gen 2";
            case 4 -> "Gen 2x2";
            default -> null;
        };
    }

    /** Replaces every match by blanks (keeps offsets); true when something matched. */
    static boolean blank(Pattern p, StringBuilder s) {
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

    /** "16" etc. for a speed in Gbit/s ("0.48" for USB 2.0). */
    static String speedDisplay(double gbps) {
        return java.math.BigDecimal.valueOf(gbps).stripTrailingZeros().toPlainString();
    }
}
