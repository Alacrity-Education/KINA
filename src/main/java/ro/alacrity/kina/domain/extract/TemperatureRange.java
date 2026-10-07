package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartSource;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An operating temperature range with both ends, normalised to {@code <min>...<max>°C}: the first of the named
 * attributes that prints one (TME {@code -55...155°C}, {@code -55÷125°C}, LCSC {@code -55℃~+155℃},
 * {@code -40°C to +85°C}, {@code -55 ~ +155 C}).
 */
public final class TemperatureRange implements AttributeLogic {

    private static final Pattern RANGE = Pattern.compile(
            "(?<![\\d.])([-−]\\s?\\d{1,3}(?:\\.\\d+)?)\\s?(?:°C?|℃)?\\s?(?:~|\\.{2,3}|…|÷|to)\\s?(\\+?\\d{1,3}(?:\\.\\d+)?)"
                    + "\\s?(?:°C?|℃|C(?![\\p{L}\\d]))");

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        for (String v : lookup.values(part)) {
            String range = range(v);
            if (range != null) {
                return Optional.of(range);
            }
        }
        return Optional.empty();
    }

    /** The normalised range a text prints, null when it prints none with both ends. */
    static String range(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m = RANGE.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC));
        if (!m.find()) {
            return null;
        }
        double min = Double.parseDouble(m.group(1).replace("−", "-").replace(" ", ""));
        double max = Double.parseDouble(m.group(2).replace("+", ""));
        if (min >= max) {
            return null;
        }
        return plain(min) + "..." + plain(max) + "°C";
    }

    private static String plain(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }
}
