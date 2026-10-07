package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.PartAttribute;
import ro.alacrity.kina.domain.PartFeatures;
import ro.alacrity.kina.domain.PartSource;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A ferrite bead's impedance with its test frequency: the first attribute whose name starts with one of the source's
 * names (TME {@code Impedance at 100MHz}, Mouser {@code Impedance}) and whose value is in ohm. The frequency is read
 * from the name ({@code at 100MHz}), else from the value ({@code 120ohm @100MHz}, the canonical form), else from the
 * {@link PartAttribute#TEST_FREQUENCY} attribute (Mouser {@code Test Frequency}).
 */
public final class ImpedanceAtFrequency implements AttributeLogic {

    private static final Pattern KEY_FREQUENCY = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*([kmg]?)hz");

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        for (Map.Entry<String, String> e : part.attributes().entrySet()) {
            if (!startsWithAny(e.getKey(), lookup.source().names())) {
                continue;
            }
            PartFeatures.Measure v = ctx.parse(e.getValue(), ParsedQuery.RESISTANCE, null);
            if (v == null) {
                continue;
            }
            Double frequency = null;
            Matcher m = KEY_FREQUENCY.matcher(e.getKey());
            if (!m.find()) {
                m = KEY_FREQUENCY.matcher(e.getValue());
                m = m.find() ? m : null;
            }
            if (m != null) {
                frequency = Double.parseDouble(m.group(1)) * switch (m.group(2).toLowerCase(Locale.ROOT)) {
                    case "k" -> 1e3;
                    case "m" -> 1e6;   // the key is lower-case: "impedance at 100mhz"
                    case "g" -> 1e9;
                    default -> 1.0;
                };
            }
            if (frequency == null) {
                PartFeatures.Measure f = ctx.read(PartAttribute.TEST_FREQUENCY, PartFeatures.Measure.class);
                frequency = f == null ? null : f.value();
            }
            return Optional.of(ctx.measure(ParsedQuery.IMPEDANCE, v.value(), frequency));
        }
        return Optional.empty();
    }

    private static boolean startsWithAny(String key, String[] prefixes) {
        for (String p : prefixes) {
            if (key.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
