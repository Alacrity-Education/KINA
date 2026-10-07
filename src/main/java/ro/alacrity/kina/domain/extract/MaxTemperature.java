package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.PartSource;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The maximum operating temperature: the largest number of the first operating temperature attribute that has a
 * positive one (TME {@code -55...105°C}, Mouser {@code Maximum Operating Temperature} = {@code + 105 C}).
 */
public final class MaxTemperature implements AttributeLogic {

    private static final Pattern SIGNED_NUMBER = Pattern.compile("[-+−]?\\s?\\d+(?:\\.\\d+)?");

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        for (String v : lookup.values(part)) {
            Matcher m = SIGNED_NUMBER.matcher(v);
            Double max = null;
            while (m.find()) {
                double n = Double.parseDouble(m.group().replace("−", "-").replace(" ", ""));
                max = max == null ? n : Math.max(max, n);
            }
            if (max != null && max > 0) {
                return Optional.of(ctx.measure(ParsedQuery.TEMPERATURE, max, null));
            }
        }
        return Optional.empty();
    }
}
