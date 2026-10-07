package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.PartSource;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The number of elements of an array or network ({@code 4} for a 4-line bead array, a {@code 0603x4} resistor
 * network), {@link ParsedQuery#ANY_ELEMENTS} for an array whose count is not stated, empty for a single element. The
 * named count attributes come first (a count below 2 is a single element), then the array words of a {@code kind of}
 * or {@code type of} attribute, the description, category and package field ({@code 4 lines}, {@code 0603x4}).
 * Common-mode chokes and filters are never arrays here.
 */
public final class ElementsCount implements AttributeLogic {

    private static final Pattern ARRAY_WORD = Pattern.compile("(?i)(?<![\\p{L}\\d])(?:arrays?|networks?)(?![\\p{L}\\d])");
    private static final Pattern COUNT_WORDS = Pattern.compile(
            "(?i)(?<![\\p{L}\\d.])(\\d{1,2})\\s?-?\\s?(?:lines|elements|resistors|capacitors|channels|bits)(?![\\p{L}])");
    /** JLCPCB networks: package {@code 0603x4}, description {@code 0402x8}. */
    private static final Pattern CHIP_TIMES = Pattern.compile(
            "(?i)(?<![\\p{L}\\d])(?:0201|0402|0603|0805|1206)[x×*](\\d{1,2})(?![\\p{L}\\d])");
    private static final Pattern COMMON_MODE = Pattern.compile("(?i)common[- ]mode");
    private static final Pattern DIGITS = Pattern.compile("\\d{1,2}");

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        String text = join(part.description(), part.category(), part.packageName());
        if (COMMON_MODE.matcher(text).find()) {
            return Optional.empty();
        }
        for (String v : lookup.values(part)) {
            if (v.strip().equalsIgnoreCase("array")) {
                return Optional.of(ParsedQuery.ANY_ELEMENTS);
            }
            Matcher m = DIGITS.matcher(v);
            if (m.find()) {
                int n = Integer.parseInt(m.group());
                return n >= 2 ? Optional.of(n) : Optional.empty();
            }
        }
        boolean array = ARRAY_WORD.matcher(text).find();
        for (Map.Entry<String, String> e : part.attributes().entrySet()) {
            // TME "Kind of ferrite: array", "Type of resistor: network"
            if ((e.getKey().startsWith("kind of") || e.getKey().startsWith("type of"))
                    && ARRAY_WORD.matcher(e.getValue()).find()) {
                array = true;
            }
        }
        Integer count = null;
        Matcher m = COUNT_WORDS.matcher(text);
        if (m.find()) {
            count = Integer.parseInt(m.group(1));
        }
        Matcher chip = CHIP_TIMES.matcher(text);
        if (count == null && chip.find()) {
            count = Integer.parseInt(chip.group(1));
        }
        if (count != null && count >= 2) {
            return Optional.of(count);
        }
        return array ? Optional.of(ParsedQuery.ANY_ELEMENTS) : Optional.empty();
    }

    private static String join(String... parts) {
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (p != null) {
                out.append(p).append(" ; ");
            }
        }
        return out.toString();
    }
}
