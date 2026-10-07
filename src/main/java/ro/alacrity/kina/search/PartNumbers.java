package ro.alacrity.kina.search;

import lombok.experimental.UtilityClass;
import ro.alacrity.kina.distributor.PartLookupResult;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Part numbers named in a query (DESIGN.md 3.4 "Requested part numbers"): which tokens are part-number shaped
 * ({@link #in}) and which parts they request ({@link #requests}). Stateless and thread-safe.
 *
 * <p>A part-number token is a free-text keyword (so not a value, unit, package, dielectric or family word: the parser
 * has taken those) with letters and digits mixed: at least {@value #MIN_LENGTH} characters, at least one letter and two
 * digits, no decimal number ({@code 2.54mm}) and none of the standard, interface or quantity words below
 * ({@code RS485}, {@code AEC-Q200}, {@code IP67}, {@code USB3}, {@code 2-channel}, {@code 1000pcs}, {@code 100ppm}).
 * The token is kept as sent ({@code uP1966E}).
 */
@UtilityClass
public class PartNumbers {

    static final int MIN_LENGTH = 5;

    private static final Pattern DECIMAL = Pattern.compile("\\d[.,]\\d");
    /** Standards, interfaces and quantities with digits that are no part number. */
    private static final Pattern NOT_A_PART_NUMBER = Pattern.compile("(?i)^(?:rs-?\\d{3}[a-z]?|aec-?q\\d+.*|ipx?\\d+"
            + "|(?:lp)?ddr\\d+[a-z]?|usb-?\\d.*|gen-?\\d.*|iec-?\\d+.*|en-?\\d{3,}.*|iso-?\\d+.*|ieee-?\\d+.*"
            + "|ul-?\\d+.*|pcie-?\\d.*|sata-?\\d.*|hdmi-?\\d.*|cat-?\\d+[a-z]?|awg-?\\d+|m\\d+x\\d+.*|qi-?\\d.*"
            + "|\\d+(?:[.,]\\d+)?[a-z]+|\\d+-[a-z]+|[a-z]+-\\d+)$");

    /**
     * The part-number tokens of {@code text} in query order, as written (original case), given the free-text
     * {@code keywords} the parser left (lower case).
     */
    static List<String> in(String text, List<String> keywords) {
        if (text == null || text.isBlank() || keywords.isEmpty()) {
            return List.of();
        }
        Set<String> candidates = new LinkedHashSet<>();
        for (String k : keywords) {
            if (shaped(k)) {
                candidates.add(k);
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String token : Recognizers.tokenize(Recognizers.prepare(text))) {
            if (candidates.contains(Recognizers.normalizeKey(token))) {
                out.add(token);
            }
        }
        return List.copyOf(out);
    }

    /** True when a lower-case keyword is part-number shaped (class comment). */
    static boolean shaped(String token) {
        if (token == null || token.length() < MIN_LENGTH || DECIMAL.matcher(token).find()
                || NOT_A_PART_NUMBER.matcher(token).matches() || PassiveDetails.parseDimensions(token) != null) {
            return false;
        }
        long letters = token.chars().filter(Character::isLetter).count();
        long digits = token.chars().filter(Character::isDigit).count();
        if (letters < 1 || digits < 2) {
            return false;
        }
        // "100-240v": every piece a number or a value
        List<String> pieces = new ArrayList<>(List.of(token.split("[-~/]")));
        pieces.removeIf(String::isEmpty);
        return pieces.size() < 2 || !pieces.stream()
                .allMatch(p -> p.chars().allMatch(Character::isDigit) || Recognizers.value(p, null) != null);
    }

    /**
     * True when {@code part} is the part {@code partNumber} requests: its manufacturer or distributor part number equals
     * it or starts with it, letters and digits compared ({@link PartLookupResult#normalize}: hyphens, spaces and case
     * are ignored, {@code EPC2218} requests {@code EPC2218A}).
     */
    public static boolean requests(String partNumber, Part part) {
        String wanted = PartLookupResult.normalize(partNumber);
        if (wanted == null || wanted.isEmpty() || part == null) {
            return false;
        }
        for (String number : new String[] {part.manufacturerPartNumber(), part.distributorPartNumber()}) {
            String n = PartLookupResult.normalize(number);
            if (n != null && n.startsWith(wanted)) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code part} is one of the parts the query names ({@link ParsedQuery#partNumbers()}). */
    public static boolean requested(ParsedQuery query, Part part) {
        return query != null && query.partNumbers().stream().anyMatch(n -> requests(n, part));
    }

    /** The query's part numbers that {@code part} is requested by, in query order. */
    static List<String> requestedBy(ParsedQuery query, Part part) {
        return query.partNumbers().stream().filter(n -> requests(n, part)).toList();
    }
}
