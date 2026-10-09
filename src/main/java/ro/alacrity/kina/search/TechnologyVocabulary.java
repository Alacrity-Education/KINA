package ro.alacrity.kina.search;

import lombok.experimental.UtilityClass;
import ro.alacrity.kina.domain.Distributor;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Construction technology of passives and the semiconductor of transistors (DESIGN.md 3.4 "Technology"), shared by
 * {@link QueryParser}, {@link ParametricExtractor}, {@link DeterministicRanker} and {@link DistributorPhraser}. Only
 * applies to the families resistor, capacitor and inductor (the construction) and MOSFET, transistor and gate driver
 * (GaN, SiC, silicon), where the words are unambiguous. Stateless and thread-safe.
 *
 * <p>Wording mined on 2026-10-05: JLCPCB descriptions ({@code Thin Film Resistor}, {@code Thick Film Resistor},
 * {@code Metal Film Resistor}, {@code Carbon Film Resistor}, {@code Metal foil resistor}, {@code Current Sense Resistor},
 * {@code Metallized Polyester}, {@code Polyethylene Terephthalate (PET)}, {@code Metallized PPS},
 * {@code Polarized Polymer}, {@code Multilayer inductor}, {@code Wire-wound inductor}) and second categories
 * ({@code Tantalum Capacitors}, {@code Film Capacitors}, {@code Polypropylene Film Capacitors (CBB)},
 * {@code Multilayer Ceramic Capacitors MLCC - SMD/SMT}, {@code Aluminum Electrolytic Capacitors - SMD},
 * {@code Polymer Aluminum Capacitors}, {@code Supercapacitors}); TME parameters {@code Type of resistor} ({@code thin
 * film}, {@code thick film}, {@code metal film}, {@code carbon film}, {@code metal oxide}, {@code wire-wound},
 * {@code metal strip}), {@code Kind of resistor} ({@code current shunt, sensing}), {@code Type of capacitor}
 * ({@code ceramic}, {@code tantalum}, {@code tantalum-polymer}, {@code polymer}, {@code electrolytic},
 * {@code polypropylene}, {@code polyester}, {@code supercapacitor}), {@code Kind of capacitor} ({@code MLCC}),
 * {@code Type of inductor} ({@code wire}, {@code multilayer}, {@code thin film}); Mouser categories
 * ({@code Thin Film Resistors - SMD}, {@code Wirewound Resistors - Through Hole}, {@code Tantalum Capacitors - Solid SMD},
 * {@code Tantalum Capacitors - Polymer}, {@code Aluminium Organic Polymer Capacitors}, {@code Film Capacitors}).
 *
 * <p>"Ferrite" is not a technology here: the word names the ferrite-bead family in KINA.
 */
@UtilityClass
public class TechnologyVocabulary {

    // resistors
    public static final String THIN_FILM = "thin film";
    public static final String THICK_FILM = "thick film";
    public static final String METAL_FILM = "metal film";
    public static final String CARBON_FILM = "carbon film";
    public static final String CARBON_COMPOSITION = "carbon composition";
    public static final String METAL_OXIDE = "metal oxide";
    public static final String WIREWOUND = "wirewound";
    public static final String METAL_FOIL = "metal foil";
    public static final String METAL_STRIP = "metal strip";
    public static final String CURRENT_SENSE = "current sense";
    // capacitors
    public static final String CERAMIC = "ceramic";
    public static final String TANTALUM = "tantalum";
    public static final String TANTALUM_POLYMER = "tantalum polymer";
    public static final String POLYMER = "polymer";
    /** Aluminium (organic) polymer capacitors, OS-CON style included; a different technology from tantalum polymer. */
    public static final String ALUMINIUM_POLYMER = "aluminium polymer";
    /** Hybrid polymer aluminium electrolytic capacitors (polymer plus liquid electrolyte). */
    public static final String HYBRID_POLYMER = "hybrid polymer";
    public static final String ALUMINIUM_ELECTROLYTIC = "aluminium electrolytic";
    public static final String FILM = "film";
    public static final String POLYPROPYLENE = "polypropylene";
    public static final String POLYESTER = "polyester";
    public static final String PPS = "PPS";
    public static final String SUPERCAPACITOR = "supercapacitor";
    // inductors
    public static final String MULTILAYER = "multilayer";
    // transistors, MOSFETs and gate drivers: the semiconductor
    /** Gallium nitride (GaN FETs and HEMTs, eGaN, CoolGaN, GaNFast; a GaN gate driver drives GaN switches). */
    public static final String GAN = "GaN";
    /** Silicon carbide (SiC MOSFETs). */
    public static final String SIC = "SiC";
    public static final String SILICON = "silicon";

    /** Every canonical technology, in declaration order (the field index refuses by this list). */
    public static final List<String> VALUES = List.of(THIN_FILM, THICK_FILM, METAL_FILM, CARBON_FILM,
            CARBON_COMPOSITION, METAL_OXIDE, WIREWOUND, METAL_FOIL, METAL_STRIP, CURRENT_SENSE, CERAMIC, TANTALUM,
            TANTALUM_POLYMER, POLYMER, ALUMINIUM_POLYMER, HYBRID_POLYMER, ALUMINIUM_ELECTROLYTIC, FILM, POLYPROPYLENE,
            POLYESTER, PPS, SUPERCAPACITOR, MULTILAYER, GAN, SIC, SILICON);

    /** A recognised technology mention: canonical value and the span in the searched text. */
    public record Match(String technology, int start, int end) {
    }

    private record Rule(Pattern pattern, String technology) {
    }

    private static Rule rule(String regex, String technology) {
        return new Rule(Pattern.compile("(?iu)(?<![\\p{L}\\d])(?:" + regex + ")(?![\\p{L}\\d])"), technology);
    }

    /** Resistor rules, most specific first. */
    private static final List<Rule> RESISTOR = List.of(
            rule("thin[- ]?films?", THIN_FILM),
            rule("thick[- ]?films?", THICK_FILM),
            rule("metal[- ]?films?", METAL_FILM),
            rule("carbon[- ]?films?", CARBON_FILM),
            rule("carbon[- ]composition", CARBON_COMPOSITION),
            rule("metal[- ]?oxide", METAL_OXIDE),
            rule("metal[- ]?foil|bulk metal foil", METAL_FOIL),
            rule("metal[- ]?strip", METAL_STRIP),
            rule("wire[- ]?wound", WIREWOUND),
            rule("current[- ]?sens(?:e|ing)|current shunt|shunt", CURRENT_SENSE));

    /**
     * Capacitor rules, most specific first ("tantalum polymer" before "polymer" and "tantalum", "aluminium polymer"
     * before "polymer" and "aluminium electrolytic").
     */
    private static final List<Rule> CAPACITOR = List.of(
            rule("tantalum[- ]polymer|polymer[- ]tantalum|tantalum capacitors?\\s*-\\s*polymer", TANTALUM_POLYMER),
            rule("hybrid(?:[- ]polymer)?(?:[- ]alumin(?:i)?um)?(?:[- ]electrolytic)?|polymer[- ]hybrid",
                    HYBRID_POLYMER),
            rule("alumin(?:i)?um[- ](?:organic[- ])?(?:conductive[- ])?polymer|polymer[- ]alumin(?:i)?um"
                    + "|(?:conductive|organic)[- ]polymer[- ]alumin(?:i)?um|os-?con|solid[- ]polymer(?: electrolytic)?"
                    + "|solid capacitors?|alumin(?:i)?um solid", ALUMINIUM_POLYMER),
            rule("polymer", POLYMER),
            rule("tantalum", TANTALUM),
            rule("super[- ]?capacitors?|ultra[- ]?capacitors?|supercaps?|edlc", SUPERCAPACITOR),
            rule("alumin(?:i)?um electrolytic|electrolytic", ALUMINIUM_ELECTROLYTIC),
            rule("polypropylene|mkp|cbb", POLYPROPYLENE),
            rule("polyester|polyethylene terephthalate|pet|mylar|mkt", POLYESTER),
            rule("pps|polyphenylene sulfide", PPS),
            rule("film", FILM),
            rule("multi[- ]?layer ceramic|ceramic|mlcc", CERAMIC));

    /** Inductor rules. */
    private static final List<Rule> INDUCTOR = List.of(
            rule("thin[- ]?film", THIN_FILM),
            rule("wire[- ]?wound", WIREWOUND),
            rule("multi[- ]?layer", MULTILAYER));

    /**
     * Transistor, MOSFET and gate driver rules (wording seen live at Mouser on 2026-10-07: categories {@code GaN FETs},
     * {@code SiC MOSFETs}; descriptions {@code EPC eGaN FET}, {@code CoolGaN Transistor}, {@code GaNFast},
     * {@code Integrated DrGaN}, {@code GaN-on-SiC HEMT}, {@code LEGACY GAN SYSTEMS}). GaN first, so {@code GaN-on-SiC}
     * is GaN. Silicon only when the words say so ({@code silicon}, {@code Si MOSFET}); a plain {@code MOSFETs}
     * category names no semiconductor.
     */
    private static final List<Rule> TRANSISTOR = List.of(
            rule("(?:e|cool|dr)?gan(?:fast)?(?:[- ]on[- ](?:si|sic|silicon))?|gallium[- ]nitride", GAN),
            rule("silicon[- ]carbide|sic", SIC),
            rule("silicon|si[- ](?:mosfets?|fets?|transistors?)", SILICON));

    private static final Set<String> FILM_KINDS = Set.of(POLYPROPYLENE, POLYESTER, PPS);
    /** Polymer kinds a bare "polymer" request accepts. */
    private static final Set<String> POLYMER_KINDS = Set.of(ALUMINIUM_POLYMER, TANTALUM_POLYMER);
    /** Kinds a hybrid polymer part neither matches nor contradicts. */
    private static final Set<String> HYBRID_NEUTRAL = Set.of(ALUMINIUM_POLYMER, POLYMER, ALUMINIUM_ELECTROLYTIC);
    private static final Set<String> SENSE_CONSTRUCTIONS = Set.of(METAL_STRIP, METAL_FOIL);

    /** The rules of a family, empty for families without technologies. */
    private static List<Rule> rules(String family) {
        if (family == null) {
            return List.of();
        }
        return switch (family) {
            case "resistor" -> RESISTOR;
            case "capacitor" -> CAPACITOR;
            case "inductor" -> INDUCTOR;
            case "mosfet", "transistor", "gate driver" -> TRANSISTOR;
            default -> List.of();
        };
    }

    /**
     * True when {@code family} has a technology vocabulary (resistor, capacitor, inductor; MOSFET, transistor and gate
     * driver: GaN, SiC, silicon).
     */
    public static boolean applies(String family) {
        return !rules(family).isEmpty();
    }

    /**
     * The technology a text names for {@code family}, or null. When the text names several, a construction
     * ({@code metal strip}, {@code thick film}...) wins over the application word {@code current sense}; otherwise the
     * earliest mention wins.
     */
    public static Match find(String text, String family) {
        List<Rule> rules = rules(family);
        if (text == null || text.isBlank() || rules.isEmpty()) {
            return null;
        }
        Match best = null;
        Match sense = null;
        int[] taken = new int[text.length() + 1];
        for (Rule r : rules) {
            Matcher m = r.pattern().matcher(text);
            while (m.find()) {
                if (overlaps(taken, m.start(), m.end())) {
                    continue;   // "thin film" already consumed "film"
                }
                mark(taken, m.start(), m.end());
                Match match = new Match(r.technology(), m.start(), m.end());
                if (CURRENT_SENSE.equals(r.technology())) {
                    if (sense == null || match.start() < sense.start()) {
                        sense = match;
                    }
                } else if (best == null || match.start() < best.start()) {
                    best = match;
                }
            }
        }
        return best != null ? best : sense;
    }

    /** Every technology a text mentions for {@code family} (non-overlapping, most specific rule first). */
    public static Set<String> mentions(String text, String family) {
        List<Rule> rules = rules(family);
        if (text == null || text.isBlank() || rules.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new java.util.LinkedHashSet<>();
        int[] taken = new int[text.length() + 1];
        for (Rule r : rules) {
            Matcher m = r.pattern().matcher(text);
            while (m.find()) {
                if (!overlaps(taken, m.start(), m.end())) {
                    mark(taken, m.start(), m.end());
                    out.add(r.technology());
                }
            }
        }
        return out;
    }

    /** The technology of a text, or null (see {@link #find}). */
    public static String of(String text, String family) {
        Match m = find(text, family);
        return m == null ? null : m.technology();
    }

    /**
     * The technology of a distributor parameter value (TME {@code Type of resistor}, {@code Type of inductor},
     * {@code Kind of capacitor}...). Also accepts TME's bare {@code wire} ({@code Type of inductor: wire}).
     */
    public static String ofAttribute(String value, String family) {
        if (value == null) {
            return null;
        }
        if (("inductor".equals(family) || "resistor".equals(family))
                && value.strip().toLowerCase(Locale.ROOT).equals("wire")) {
            return WIREWOUND;
        }
        if (rules(family) == TRANSISTOR && value.strip().equalsIgnoreCase("si")) {
            return SILICON;   // Mouser "Technology: Si"
        }
        return of(value, family);
    }

    /** Mouser files planar power resistors (LPS...) under "Planar Resistors": a thick film on a ceramic substrate. */
    private static final Pattern PLANAR_CATEGORY = Pattern.compile("(?i)(?<![\\p{L}])planar resistors?(?![\\p{L}])");
    private static final Pattern OHMITE = Pattern.compile("(?i)ohmite");

    /**
     * The technology a part's series or category implies when the words do not name it (resistors only): the Mouser
     * category {@code Planar Resistors} and Ohmite's {@code TGH} series ({@code TGHG}, {@code TGHPV}...) are thick
     * film. Only for parts: a request saying "planar" keeps its words. TME's {@code Type of resistor: power} and the
     * TME series {@code LPR} / {@code AHP} name no technology and get none.
     */
    public static String ofPart(String manufacturer, String mpn, String category, String family) {
        if (!"resistor".equals(family)) {
            return null;
        }
        if (category != null && PLANAR_CATEGORY.matcher(category).find()) {
            return THICK_FILM;
        }
        if (mpn != null && manufacturer != null && OHMITE.matcher(manufacturer).find()
                && mpn.strip().toUpperCase(Locale.ROOT).startsWith("TGH")) {
            return THICK_FILM;
        }
        return null;
    }

    private static boolean overlaps(int[] taken, int start, int end) {
        for (int i = start; i < end; i++) {
            if (taken[i] != 0) {
                return true;
            }
        }
        return false;
    }

    private static void mark(int[] taken, int start, int end) {
        for (int i = start; i < end; i++) {
            taken[i] = 1;
        }
    }

    /**
     * Compares a requested technology with a part's: 1 = same (or compatible: a {@code film} request accepts
     * polypropylene/polyester/PPS, a {@code tantalum} request accepts tantalum polymer, a bare {@code polymer} request
     * accepts aluminium polymer and tantalum polymer, a {@code current sense} request accepts metal strip and metal
     * foil), -1 = a different known technology (an aluminium polymer request against a tantalum polymer or a plain
     * aluminium electrolytic part), 0 = unknown on either side or not comparable (a polypropylene request against a
     * part that only says "film"; an aluminium polymer request against a part that only says "polymer"; a hybrid
     * polymer part against any aluminium request; a current-sense request against a thick-film part, which may well be
     * a sense resistor).
     */
    public static int compare(String wanted, String actual) {
        if (wanted == null || actual == null) {
            return 0;
        }
        if (wanted.equals(actual)) {
            return 1;
        }
        if (FILM.equals(wanted) && FILM_KINDS.contains(actual)) {
            return 1;
        }
        if (FILM.equals(actual) && FILM_KINDS.contains(wanted)) {
            return 0;
        }
        if (POLYMER.equals(wanted) && POLYMER_KINDS.contains(actual)) {
            return 1;   // a bare "polymer" request accepts aluminium and tantalum polymer
        }
        if (TANTALUM_POLYMER.equals(actual) && TANTALUM.equals(wanted)) {
            return 1;
        }
        if (TANTALUM_POLYMER.equals(wanted) && (TANTALUM.equals(actual) || POLYMER.equals(actual))) {
            return 0;
        }
        if (ALUMINIUM_POLYMER.equals(wanted) && POLYMER.equals(actual)) {
            return 0;   // "polymer" alone does not say aluminium or tantalum
        }
        if (HYBRID_POLYMER.equals(actual) && HYBRID_NEUTRAL.contains(wanted)
                || HYBRID_POLYMER.equals(wanted) && HYBRID_NEUTRAL.contains(actual)) {
            return 0;
        }
        if (CURRENT_SENSE.equals(wanted)) {
            return SENSE_CONSTRUCTIONS.contains(actual) ? 1 : 0;
        }
        if (CURRENT_SENSE.equals(actual)) {
            return 0;
        }
        return -1;
    }

    /** Distributor spellings that differ from the canonical name and help that distributor's search. */
    private static final Map<Distributor, Map<String, String>> SPELLINGS = Map.of(
            // JLCPCB: quoted phrases keep the words adjacent (FTS5 phrase); categories carry the capacitor kinds
            Distributor.LCSC, Map.ofEntries(
                    Map.entry(THIN_FILM, "\"Thin Film\""), Map.entry(THICK_FILM, "\"Thick Film\""),
                    Map.entry(METAL_FILM, "\"Metal Film\""), Map.entry(CARBON_FILM, "\"Carbon Film\""),
                    Map.entry(METAL_OXIDE, "\"Metal Oxide\""), Map.entry(METAL_FOIL, "\"Metal Foil\""),
                    Map.entry(CURRENT_SENSE, "\"Current Sense\""), Map.entry(ALUMINIUM_ELECTROLYTIC,
                            "\"Aluminum Electrolytic\""), Map.entry(ALUMINIUM_POLYMER, "\"Polymer Aluminum\"")),
            // TME (verified live 2026-10-05): "wirewound resistor 5W" finds the "wire-wound" resistors, "wire-wound
            // resistor 5W" needs the fallback; "aluminium electrolytic 100uF" finds nothing, TME says "electrolytic"
            Distributor.TME, Map.of(WIREWOUND, "wirewound", ALUMINIUM_ELECTROLYTIC, "electrolytic",
                    ALUMINIUM_POLYMER, "polymer"),
            // Mouser categories: "Wirewound Resistors", "Thin Film Resistors", "Thick Film Resistors"
            Distributor.MOUSER, Map.of(WIREWOUND, "wirewound", THIN_FILM, "thin film", THICK_FILM, "thick film",
                    ALUMINIUM_POLYMER, "aluminum organic polymer"));

    /** The distributor's spelling of a technology, or null when it has none of its own. */
    public static String spelling(Distributor distributor, String technology) {
        Map<String, String> spellings = SPELLINGS.get(distributor);
        return spellings == null || technology == null ? null : spellings.get(technology);
    }
}
