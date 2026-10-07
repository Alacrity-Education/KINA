package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ComponentFamily;
import ro.alacrity.kina.domain.ComponentFamily.Trait;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.PartSource;

import java.util.List;
import java.util.Optional;

/**
 * The voltage the description states, when no attribute does. For a transistor or diode
 * ({@link Trait#LARGEST_VOLTAGE}, decided by the part's family) whose description lists voltages without labels it is
 * the largest single voltage (Vds, Vrrm; words with a condition such as {@code 1.25V@150mA} and ranges are left out);
 * a largest value below {@value #MIN_PLAUSIBLE_RATING_VOLTS} V is a threshold or forward voltage, not a rating, and
 * the voltage is then unknown (unverified, never a wrong exclusion). Otherwise the description's voltage.
 */
public final class LargestVoltage implements AttributeLogic {

    /** Below this a description voltage is never a transistor's or diode's rating. */
    public static final double MIN_PLAUSIBLE_RATING_VOLTS = 3.0;

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        String family = ctx.partFamily();
        if (ComponentFamily.has(family, Trait.LARGEST_VOLTAGE)) {
            List<Double> voltages = ctx.singleValues(part.description(), ParsedQuery.VOLTAGE, family);
            if (!voltages.isEmpty()) {
                double largest = voltages.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
                return largest < MIN_PLAUSIBLE_RATING_VOLTS ? Optional.empty()
                        : Optional.of(ctx.measure(ParsedQuery.VOLTAGE, largest, null));
            }
        }
        return Optional.ofNullable(ctx.described(lookup.attribute(), Object.class));
    }

    @Override
    public boolean readsAttributes() {
        return false;
    }
}
