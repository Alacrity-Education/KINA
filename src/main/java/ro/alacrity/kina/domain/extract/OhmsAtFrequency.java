package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.PartFeatures;
import ro.alacrity.kina.domain.PartSource;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A capacitor's ESR or impedance in ohm with its test frequency, as displayed ({@code "15mohm @100kHz"}): the first
 * named attribute in ohm. The frequency is read from the attribute name ({@code ESR at 100kHz}) or its value
 * ({@code 16mohm @100kHz}).
 */
public final class OhmsAtFrequency implements AttributeLogic {

    private static final Pattern KEY_FREQUENCY = Pattern.compile("(?i)(\\d+(?:[.,]\\d+)?)\\s*([kmg]?)hz");

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        for (String name : lookup.source().names()) {
            String v = part.attribute(name);
            String display = ohms(v, frequency(name, v), ctx);
            if (display != null) {
                return Optional.of(display);
            }
        }
        return Optional.empty();
    }

    /** The display form of an ohm value at a frequency, null when the value is no ohm value. */
    static String ohms(String value, Double frequency, ExtractionContext ctx) {
        if (value == null || value.isBlank()) {
            return null;
        }
        PartFeatures.Measure v = ctx.parse(value, ParsedQuery.RESISTANCE, null);
        if (v == null) {
            return null;
        }
        return ctx.measure(ParsedQuery.IMPEDANCE, v.value(), frequency).display();
    }

    /** The test frequency in Hz from the attribute name ("ESR at 100kHz") or value ("16mohm @100kHz"). */
    static Double frequency(String name, String value) {
        for (String text : new String[]{name, value}) {
            if (text == null) {
                continue;
            }
            Matcher m = KEY_FREQUENCY.matcher(text);
            if (m.find()) {
                String p = m.group(2).toLowerCase(Locale.ROOT);
                return Double.parseDouble(m.group(1).replace(',', '.')) * switch (p) {
                    case "k" -> 1e3;
                    case "m" -> 1e6;
                    case "g" -> 1e9;
                    default -> 1.0;
                };
            }
        }
        return null;
    }
}
