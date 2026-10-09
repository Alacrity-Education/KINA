package ro.alacrity.kina.search;

import lombok.experimental.UtilityClass;
import ro.alacrity.kina.domain.ComponentFamily;
import ro.alacrity.kina.domain.ComponentFamily.Trait;
import ro.alacrity.kina.domain.ParsedQuery;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The form factor of a passive (DESIGN.md 3.4 "Form factor"): a bounded class of body and mounting that a power
 * resistor request cares about more than the exact package. Read from the request's words and package and from the
 * part's package, description and category (distributor text only). Applies to resistors, capacitors, inductors,
 * ferrite beads and requests without a family. Stateless and thread-safe.
 *
 * <table>
 *   <caption>Classes</caption>
 *   <tr><td>{@value #CHIP}</td><td>imperial chip codes ({@code 0201}..{@code 2512}, also {@code 1225}, {@code 2728},
 *       {@code 4527}); for resistors without a body package also the words {@code SMD} / {@code SMT} / surface
 *       mount (Mouser "Thick Film Resistors - SMD")</td></tr>
 *   <tr><td>{@value #THROUGH_HOLE}</td><td>leaded bodies: {@code axial}, {@code radial}, {@code leaded}. A bare
 *       "Through Hole" or "THT" is not enough: Mouser files TO-220 and TO-247 power resistors under "Through
 *       Hole"</td></tr>
 *   <tr><td>{@value #CHASSIS}</td><td>chassis, heatsink, bolt or screw mount; aluminium housed ({@code Alum Housed},
 *       {@code aluminum housed}); {@code with heatsink}</td></tr>
 *   <tr><td>{@value #POWER_PACKAGE}</td><td>packages screwed to a heatsink: SOT-227, TO-220, TO-247, TO-218,
 *       TO-126, TO-264, TO-3P</td></tr>
 *   <tr><td>{@value #POWER_SMD}</td><td>surface-mount power packages: TO-263 / D2PAK, TO-252 / DPAK, D3PAK,
 *       TO-268</td></tr>
 * </table>
 *
 * <p>Compatibility ({@link #compatible}): a {@value #CHASSIS} request accepts {@value #CHASSIS} and
 * {@value #POWER_PACKAGE} parts (both mount on a heatsink) and so does a {@value #POWER_PACKAGE} request; every
 * other class accepts only itself. A part whose class cannot be read is unknown.
 */
@UtilityClass
public class FormFactor {

    public static final String CHIP = "chip";
    public static final String THROUGH_HOLE = "through_hole";
    public static final String CHASSIS = "chassis";
    public static final String POWER_PACKAGE = "power_package";
    public static final String POWER_SMD = "power_smd";

    /** Every class, in documentation order. */
    public static final List<String> CLASSES = List.of(CHIP, THROUGH_HOLE, CHASSIS, POWER_PACKAGE, POWER_SMD);

    /**
     * The mounting a class implies ({@code Mounting} of the part: {@code SMD} or {@code THT}): a chip or power SMD
     * package is surface mount, a leaded body through hole; a package screwed to a heatsink (SOT-227, TO-220: through
     * hole or chassis mount) and a chassis part imply none. A request that names a package of such a class (where the package is hard) refuses a part of the other
     * mounting ({@code ConstraintKind.MOUNTING}).
     */
    public static final Map<String, String> MOUNTING = Map.of(CHIP, "SMD", POWER_SMD, "SMD", THROUGH_HOLE, "THT");

    /** Chip codes beyond {@link Recognizers#isChipCode} that name chip resistors (wide-terminal and power chips). */
    private static final Set<String> EXTRA_CHIP_CODES = Set.of("1225", "2728", "4527", "0612", "1218");

    private static final List<String> POWER_PACKAGE_KEYS = List.of("SOT227", "ISOTOP", "TO220", "TO247", "TO218",
            "TO126", "TO264", "TO3P");
    private static final List<String> POWER_SMD_KEYS = List.of("TO263", "TO252", "TO268", "D2PAK", "D3PAK", "DPAK");

    private static final Pattern CHASSIS_WORDS = Pattern.compile("(?iu)(?<![\\p{L}\\d])(?:"
            + "chassis(?:[- ]mount(?:ed|ing)?)?"
            + "|heat[- ]?sinks?(?:[- ]mount(?:ed|ing)?)?"
            + "|bolt(?:[- ]?(?:on|down|mount(?:ed|ing)?))?"
            + "|screw(?:[- ]?(?:on|mount(?:ed|ing)?))?(?![- ]?terminals?)"
            + "|alum(?:inium|inum)?\\.?[- ]?(?:housed|housing|clad|cased?)"
            + ")(?![\\p{L}\\d])");
    private static final Pattern THROUGH_HOLE_WORDS = Pattern.compile(
            "(?iu)(?<![\\p{L}\\d])(?:axial|radial|leaded)(?![\\p{L}\\d])");
    private static final Pattern SMD_WORDS = Pattern.compile(
            "(?iu)(?<![\\p{L}\\d])(?:smd|smt|surface[- ]mount(?:ed)?)(?![\\p{L}\\d])");
    private static final Pattern CHIP_CODE_WORD = Pattern.compile("(?<![\\p{L}\\d.])(\\d{4,5})(?![\\p{L}\\d])");

    /** True when KINA reads a form factor for {@code family} (passives, and an unknown family). */
    public static boolean applies(String family) {
        return family == null || ComponentFamily.has(family, Trait.PASSIVE);
    }

    /** The class a package names ({@code SOT-227} -&gt; power_package, {@code 0805} -&gt; chip), or null. */
    public static String ofPackage(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return null;
        }
        if (Recognizers.isChipCode(packageName) || EXTRA_CHIP_CODES.contains(packageName.strip())) {
            return CHIP;
        }
        String key = Recognizers.packageKey(packageName);
        if (key == null) {
            return null;
        }
        for (String prefix : POWER_SMD_KEYS) {
            if (key.startsWith(prefix)) {
                return POWER_SMD;
            }
        }
        for (String prefix : POWER_PACKAGE_KEYS) {
            if (key.startsWith(prefix)) {
                return POWER_PACKAGE;
            }
        }
        if (key.startsWith("AXIAL") || key.startsWith("RADIAL")) {
            return THROUGH_HOLE;   // LCSC "AXIAL-0.6", "Radial"
        }
        return null;
    }

    /** The mounting a package implies through its class ({@link #MOUNTING}), null when it implies none. */
    public static String mountingOf(String packageName) {
        String formFactor = ofPackage(packageName);
        return formFactor == null ? null : MOUNTING.get(formFactor);
    }

    /** The class the words of a text name ({@code heatsink mount} -&gt; chassis, {@code axial} -&gt; through_hole). */
    static String ofWords(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (CHASSIS_WORDS.matcher(text).find()) {
            return CHASSIS;
        }
        return THROUGH_HOLE_WORDS.matcher(text).find() ? THROUGH_HOLE : null;
    }

    /** The form factor the request's own words name (chassis words), or null; the package is read separately. */
    public static String ofRequestWords(String text, String family) {
        if (!applies(family)) {
            return null;
        }
        return text != null && CHASSIS_WORDS.matcher(text).find() ? CHASSIS : null;
    }

    /**
     * The form factor a request asks for: the class of its package when it names one with a class (the more specific
     * statement: {@code SOT-227 heatsink} is a power package), else the class its words name; null when neither.
     * The package counts only when it is hard for the family ({@code packageHard}): an inductor's relaxable package
     * implies nothing.
     */
    public static String ofRequest(ParsedQuery query, boolean packageHard) {
        if (query == null || !applies(query.family())) {
            return null;
        }
        String fromPackage = packageHard ? ofPackage(query.packageName()) : null;
        return fromPackage != null ? fromPackage : query.formFactor();
    }

    /**
     * The part's class: its package first, then chassis or leaded-body words of the description, category and package
     * field, then (resistors only) chip codes and the words SMD / SMT; null when nothing says.
     */
    static String ofPart(String family, String packageName, String description, String category, String packageField) {
        if (!applies(family)) {
            return null;
        }
        String fromPackage = ofPackage(packageName);
        if (fromPackage != null) {
            return fromPackage;
        }
        String text = String.join(" ; ", nonNull(description), nonNull(category), nonNull(packageField));
        String fromWords = ofWords(text);
        if (fromWords != null) {
            return fromWords;
        }
        if (!"resistor".equals(family)) {
            return null;
        }
        java.util.regex.Matcher m = CHIP_CODE_WORD.matcher(nonNull(description));
        while (m.find()) {
            if (Recognizers.isChipCode(m.group(1)) || EXTRA_CHIP_CODES.contains(m.group(1))) {
                return CHIP;
            }
        }
        return SMD_WORDS.matcher(text).find() ? CHIP : null;
    }

    private static final Map<String, Set<String>> ACCEPTS = Map.of(
            CHASSIS, Set.of(CHASSIS, POWER_PACKAGE),
            POWER_PACKAGE, Set.of(POWER_PACKAGE, CHASSIS),
            CHIP, Set.of(CHIP),
            THROUGH_HOLE, Set.of(THROUGH_HOLE),
            POWER_SMD, Set.of(POWER_SMD));

    /** True when a part of class {@code actual} satisfies a request for {@code wanted}; null when either is unknown. */
    public static Boolean compatible(String wanted, String actual) {
        if (wanted == null || actual == null) {
            return null;
        }
        return ACCEPTS.getOrDefault(wanted, Set.of(wanted)).contains(actual);
    }

    /** The display form of a class ({@code power_package} -&gt; {@code power package}). */
    static String label(String formFactor) {
        return formFactor == null ? null : formFactor.replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }
}
