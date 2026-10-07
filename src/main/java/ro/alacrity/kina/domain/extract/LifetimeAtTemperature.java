package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.PartSource;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A lifetime in hours with its test temperature when stated ({@code 2000h}, {@code 5000 Hours},
 * {@code 2000 Hrs @ 105°C}); never an inductance.
 */
public final class LifetimeAtTemperature implements AttributeLogic {

    private static final Pattern HOURS = Pattern.compile(
            "(?i)(\\d+(?:[.,]\\d+)?)\\s*(?:h|hrs?|hours?)(?![a-z])(?:\\s*@\\s*\\+?(\\d{2,3})\\s*°?\\s*C)?");

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        for (String v : lookup.values(part)) {
            Matcher m = HOURS.matcher(v);
            if (m.find()) {
                double hours = Double.parseDouble(m.group(1).replace(',', '.'));
                Double temperature = m.group(2) == null ? null : Double.parseDouble(m.group(2));
                return Optional.of(ctx.measure(ParsedQuery.LIFETIME, hours, temperature));
            }
        }
        return Optional.empty();
    }
}
