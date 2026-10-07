package ro.alacrity.kina.search;

import lombok.experimental.UtilityClass;
import ro.alacrity.kina.domain.ParsedQuery;

import java.util.regex.Pattern;

/**
 * The component type distinctions that are hard constraints (DESIGN.md 3.4 "Hard constraints"), read the same way from
 * queries and from distributor text: the polarity of a transistor ({@value #N_CHANNEL}, {@value #P_CHANNEL},
 * {@value #NPN}, {@value #PNP}, or {@value #COMPLEMENTARY} for an N+P pair), whether a diode is a standard rectifier /
 * switching diode ({@value #STANDARD}, as opposed to the Schottky, Zener, TVS and LED families) and whether a
 * regulator is {@value #FIXED} or {@value #ADJUSTABLE}. Crystals and oscillators are separate families
 * ({@link Recognizers}). Wording mined from the JLCPCB database and live TME and Mouser responses (2026-10-07):
 * JLCPCB {@code 1 N-channel}, {@code 2 P-Channel}, {@code NPN}, categories {@code Switching Diodes},
 * {@code Diodes - General Purpose}, {@code Diodes - Fast Recovery Rectifiers}, descriptions {@code Fixed} /
 * {@code Adjustable}; TME {@code Transistor: N-MOSFET}, {@code SMD N channel transistors}, {@code Diode: rectifying},
 * {@code Kind of voltage regulator: fixed, LDO, linear}, {@code LDO adjustable voltage regulators}; Mouser
 * {@code MOSFETs N-Channel}, {@code N-CH}, {@code Rectifiers}, {@code ADJ}. Stateless and thread-safe.
 */
@UtilityClass
class ComponentTypes {

    static final String N_CHANNEL = "N-channel";
    static final String P_CHANNEL = "P-channel";
    static final String NPN = "NPN";
    static final String PNP = "PNP";
    /** An N- and P-channel pair (or NPN + PNP) in one package: a type of its own. */
    static final String COMPLEMENTARY = "complementary";

    /** A standard (non-Schottky) rectifier or switching diode. */
    static final String STANDARD = ParsedQuery.STANDARD;
    static final String FIXED = "fixed";
    static final String ADJUSTABLE = "adjustable";

    private static final String BEFORE = "(?<![\\p{L}\\d])";
    private static final String AFTER = "(?![\\p{L}])";
    private static final Pattern N = Pattern.compile("(?i)" + BEFORE
            + "(?:n[- ]?(?:channel|ch|mosfets?|mos|fets?)|nmos(?:fet)?|nfet|n沟道)" + AFTER);
    private static final Pattern P = Pattern.compile("(?i)" + BEFORE
            + "(?:p[- ]?(?:channel|ch|mosfets?|mos|fets?)|pmos(?:fet)?|pfet|p沟道)" + AFTER);
    private static final Pattern NPN_WORD = Pattern.compile("(?i)" + BEFORE + "npn" + AFTER);
    private static final Pattern PNP_WORD = Pattern.compile("(?i)" + BEFORE + "pnp" + AFTER);
    private static final Pattern COMPLEMENTARY_WORDS = Pattern.compile("(?i)" + BEFORE
            + "(?:complementary|n\\s?\\+\\s?p|p\\s?\\+\\s?n|n\\s?/\\s?p(?:[- ]?(?:channel|ch))?|n\\s?&\\s?p"
            + "|n\\s+and\\s+p)" + AFTER);

    /**
     * The polarity a text names: {@value #N_CHANNEL} ({@code N-channel}, {@code N-CH}, {@code N-MOSFET},
     * {@code NMOS}), {@value #P_CHANNEL}, {@value #NPN}, {@value #PNP}, {@value #COMPLEMENTARY} when it names a
     * pair ({@code N+P}, {@code complementary}, or both polarities); null when it names none.
     */
    static String polarity(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (COMPLEMENTARY_WORDS.matcher(text).find()) {
            return COMPLEMENTARY;
        }
        boolean n = N.matcher(text).find();
        boolean p = P.matcher(text).find();
        boolean npn = NPN_WORD.matcher(text).find();
        boolean pnp = PNP_WORD.matcher(text).find();
        int kinds = (n ? 1 : 0) + (p ? 1 : 0) + (npn ? 1 : 0) + (pnp ? 1 : 0);
        if (kinds > 1) {
            return COMPLEMENTARY;
        }
        return n ? N_CHANNEL : p ? P_CHANNEL : npn ? NPN : pnp ? PNP : null;
    }

    /** Words of a standard (non-Schottky) rectifier or switching diode. */
    private static final Pattern STANDARD_DIODE = Pattern.compile("(?i)" + BEFORE
            + "(?:rectifiers?|rectifying|switching|general[- ]purpose|fast[- ]recovery|ultra[- ]?fast|hyper[- ]?fast"
            + "|high[- ]efficiency|standard[- ]recovery|universal|small[- ]signal|super[- ]barrier|sbr)" + AFTER);

    /**
     * {@value #STANDARD} when the text of a part or request of the generic diode family names a standard rectifier or
     * switching diode ({@code rectifier}, {@code rectifying}, {@code switching}, {@code general purpose}, {@code fast
     * recovery}...); null otherwise. Only meaningful when the family is {@code diode}: a Schottky part says
     * {@code Schottky rectifying} and is of the Schottky family.
     */
    static String diodeSubtype(String text) {
        return text != null && STANDARD_DIODE.matcher(text).find() ? STANDARD : null;
    }

    private static final Pattern ADJUSTABLE_WORDS = Pattern.compile("(?i)" + BEFORE
            + "(?:adjustable|adj|variable|programmable output)" + AFTER);
    private static final Pattern FIXED_WORDS = Pattern.compile("(?i)" + BEFORE + "fixed" + AFTER);

    /**
     * {@value #ADJUSTABLE} ({@code adjustable}, {@code ADJ}, {@code variable}) or {@value #FIXED} ({@code fixed}) for
     * a regulator text, null when it says neither (or both).
     */
    static String regulatorSubtype(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        boolean adjustable = ADJUSTABLE_WORDS.matcher(text).find();
        boolean fixed = FIXED_WORDS.matcher(text).find();
        if (adjustable == fixed) {
            return null;
        }
        return adjustable ? ADJUSTABLE : FIXED;
    }

    /**
     * The subtype of a request or part of {@code family}: {@link #diodeSubtype} for the generic diode family,
     * {@link #regulatorSubtype} for regulators, else null.
     */
    static String subtype(String family, String text) {
        if ("diode".equals(family)) {
            return diodeSubtype(text);
        }
        if ("regulator".equals(family)) {
            return regulatorSubtype(text);
        }
        return null;
    }

    /** True for families whose parts have a polarity (transistors and MOSFETs). */
    static boolean polarised(String family) {
        return "mosfet".equals(family) || "transistor".equals(family);
    }
}
