package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartSource;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Body dimensions ({@code D6.3 x 5.8mm} for a can, {@code 8.8 x 8.4 x 3.8mm}, {@code 3.2 x 1.6mm}): the first of the
 * named attributes that states them. {@link #parse} reads the written forms.
 */
public final class Dimensions implements AttributeLogic {

    /**
     * {@code Ø6.3x5.8mm}, {@code D6.3xL5.8mm}, {@code 8.8x8.4x3.8mm}, {@code D6.3 x 5.8mm}, {@code 3.2 x 1.6mm}: a
     * diameter (Ø/Φ/D) or two or three lengths, ending in mm.
     */
    private static final Pattern DIMENSIONS = Pattern.compile(
            "(?<![\\p{L}\\d.])([ØΦφ]|D)?\\s?(\\d+(?:[.,]\\d+)?)\\s?(?:mm)?\\s?[x×*]\\s?[LH]?\\s?(\\d+(?:[.,]\\d+)?)"
                    + "(?:\\s?(?:mm)?\\s?[x×*]\\s?[LH]?\\s?(\\d+(?:[.,]\\d+)?))?\\s?mm(?![\\p{L}])");

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        for (String v : lookup.values(part)) {
            String d = parse(v);
            if (d != null) {
                return Optional.of(d);
            }
        }
        return Optional.empty();
    }

    /** The normalised dimensions a text states, null when it states none. */
    public static String parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m = DIMENSIONS.matcher(text);
        if (!m.find()) {
            return null;
        }
        String a = number(m.group(2));
        String b = number(m.group(3));
        String c = m.group(4) == null ? null : number(m.group(4));
        if (m.group(1) != null && c == null) {
            return "D" + a + " x " + b + "mm";
        }
        return c == null ? a + " x " + b + "mm" : a + " x " + b + " x " + c + "mm";
    }

    /** A millimetre number without trailing zeros ({@code 6,30} -&gt; {@code 6.3}). */
    static String number(String raw) {
        String n = raw.replace(',', '.');
        return n.contains(".") ? n.replaceAll("0+$", "").replaceAll("\\.$", "") : n;
    }
}
