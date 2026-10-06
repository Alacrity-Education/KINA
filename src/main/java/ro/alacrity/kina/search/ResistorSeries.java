package ro.alacrity.kina.search;

import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Power resistor series whose manufacturer part number names the rated wattage (DESIGN.md 3.4 "Power resistors").
 * Read only for resistors whose description and attributes state no power: a stated power always wins. Part numbers
 * only, never datasheets.
 *
 * <table>
 *   <caption>Series</caption>
 *   <tr><td>Arcol {@code HS}, {@code HSA}, {@code HSC} (aluminium housed)</td><td>{@code HS25} 25 W, {@code HSA50}
 *       50 W, {@code HSC100} 100 W; the number must be one of the series wattages (10..300 W), so
 *       {@code HS254R7J} is 25 W</td></tr>
 *   <tr><td>TE {@code THS}</td><td>{@code THS15}, {@code THS25}, {@code THS50}...</td></tr>
 *   <tr><td>Vishay / Dale {@code RH}</td><td>{@code RH-50}, {@code RH-25}, {@code RH050}, {@code RH0254R700}</td></tr>
 *   <tr><td>Ohmite {@code TEH}</td><td>{@code TEH70}, {@code TEH100}</td></tr>
 *   <tr><td>Bourns {@code PWR}</td><td>{@code PWR220T-20} 20 W, {@code PWR263S-35} 35 W</td></tr>
 *   <tr><td>Caddock {@code MP9}</td><td>{@code MP915} 15 W, {@code MP925} 25 W, {@code MP930} 30 W, {@code MP9100}
 *       100 W</td></tr>
 *   <tr><td>{@code LPS}</td><td>{@code LPS0300} 300 W, {@code LPS0800} 800 W</td></tr>
 * </table>
 */
@UtilityClass
class ResistorSeries {

    private record Rule(Pattern manufacturer, Pattern number, Set<Integer> watts) {
    }

    private static final Set<Integer> ARCOL_WATTS = Set.of(10, 15, 25, 50, 75, 100, 150, 200, 250, 300);
    private static final Set<Integer> THS_WATTS = Set.of(10, 15, 25, 50, 75);
    private static final Set<Integer> RH_WATTS = Set.of(5, 10, 25, 50, 100, 250);

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("(?i)arcol"), Pattern.compile("^HS[AC]?(\\d{2,3})"), ARCOL_WATTS),
            new Rule(null, Pattern.compile("^THS(\\d{2})"), THS_WATTS),
            new Rule(Pattern.compile("(?i)vishay|dale"), Pattern.compile("^RH-?(\\d{1,3})"), RH_WATTS),
            new Rule(Pattern.compile("(?i)ohmite"), Pattern.compile("^TEH(70|100)"), null),
            new Rule(Pattern.compile("(?i)bourns"), Pattern.compile("^PWR\\d{3}[A-Z]?-(\\d{1,3})(?!\\d)"), null),
            new Rule(Pattern.compile("(?i)caddock"), Pattern.compile("^MP9(15|25|30|100)(?!\\d)"), null),
            new Rule(null, Pattern.compile("^LPS(\\d{4})"), null));

    /** The wattage the part number names, or null. */
    static Double power(String manufacturer, String mpn) {
        if (mpn == null || mpn.isBlank()) {
            return null;
        }
        String number = mpn.strip().toUpperCase(Locale.ROOT);
        for (Rule rule : RULES) {
            if (rule.manufacturer() != null && (manufacturer == null || !rule.manufacturer().matcher(manufacturer).find())) {
                continue;
            }
            Matcher m = rule.number().matcher(number);
            if (!m.find()) {
                continue;
            }
            Integer watts = rule.watts() == null ? Integer.valueOf(Integer.parseInt(m.group(1)))
                    : longestIn(m.group(1), rule.watts());
            if (watts != null && watts > 0) {
                return watts.doubleValue();
            }
        }
        return null;
    }

    /** The longest leading digits of {@code digits} that are a series wattage ({@code 254} -&gt; 25), or null. */
    private static Integer longestIn(String digits, Set<Integer> allowed) {
        for (int length = digits.length(); length >= 1; length--) {
            int n = Integer.parseInt(digits.substring(0, length));
            if (allowed.contains(n)) {
                return n;
            }
        }
        return null;
    }
}
