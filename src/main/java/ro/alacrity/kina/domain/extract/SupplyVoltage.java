package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.PartSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The largest AC (for {@link ParsedQuery#VOLTAGE_AC}) or DC (for {@link ParsedQuery#VOLTAGE_DC}) voltage a switch's
 * attributes and description state: TME {@code 3A/125VAC}, {@code 0.05A/12VDC}, Mouser {@code 250 VAC}, {@code 24V DC}.
 * A voltage without AC or DC says nothing here.
 */
public final class SupplyVoltage implements AttributeLogic {

    private static final Pattern SUPPLY = Pattern.compile(
            "(?i)(?<![\\p{L}\\d.])(\\d+(?:\\.\\d+)?)\\s?(k?)\\s?v\\s?(ac|dc)(?![\\p{L}\\d])");

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        String wanted = ParsedQuery.VOLTAGE_AC.equals(lookup.attribute().kind()) ? "ac" : "dc";
        List<String> texts = new ArrayList<>(part.attributes().values());
        texts.add(part.description() == null ? "" : part.description());
        Double largest = null;
        for (String text : texts) {
            Matcher m = SUPPLY.matcher(text);
            while (m.find()) {
                if (m.group(3).equalsIgnoreCase(wanted)) {
                    double volts = Double.parseDouble(m.group(1)) * (m.group(2).isEmpty() ? 1 : 1e3);
                    largest = largest == null ? volts : Math.max(largest, volts);
                }
            }
        }
        return largest == null ? Optional.empty() : Optional.of(ctx.measure(lookup.attribute().kind(), largest, null));
    }

    @Override
    public boolean readsAttributes() {
        return false;
    }
}
