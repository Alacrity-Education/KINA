package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartSource;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A pitch in millimetres: {@code 2.54mm}, {@code 2.54 mm}, a bare {@code 2.54} (millimetres, up to 20), {@code 0.1 in}
 * or {@code 0.1"} (inches).
 */
public final class PitchValue implements AttributeLogic {

    private static final Pattern MM_VALUE = Pattern.compile("(?i)(\\d+(?:[.,]\\d+)?)\\s*(?:mm)?");

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        for (String v : lookup.values(part)) {
            Double pitch = pitch(v, ctx);
            if (pitch != null) {
                return Optional.of(pitch);
            }
        }
        return Optional.empty();
    }

    private static Double pitch(String value, ExtractionContext ctx) {
        Double recognised = ctx.connector(value).pitchMm();
        if (recognised != null) {
            return recognised;
        }
        Matcher m = MM_VALUE.matcher(value.strip());
        if (m.matches()) {
            double mm = Double.parseDouble(m.group(1).replace(',', '.'));
            return mm > 0 && mm <= 20 ? mm : null;
        }
        return null;
    }
}
