package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.PartAttribute;
import ro.alacrity.kina.domain.PartFeatures;
import ro.alacrity.kina.domain.PartSource;

import java.util.Optional;

/**
 * The power rating a resistor series implies (Arcol HS50, TE THS25, Vishay RH-50: the series names the wattage the
 * text does not state). Only for a resistor: the family the category or description names, or, when neither names
 * one, a part with a resistance.
 */
public final class SeriesPower implements AttributeLogic {

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        String family = lookup.family() != null ? lookup.family()
                : ctx.read(PartAttribute.RESISTANCE, PartFeatures.Measure.class) != null ? "resistor" : null;
        if (!"resistor".equals(family)) {
            return Optional.empty();
        }
        Double watts = ctx.seriesPower(part.manufacturer(), part.manufacturerPartNumber());
        return watts == null ? Optional.empty() : Optional.of(ctx.measure(ParsedQuery.POWER, watts, null));
    }

    @Override
    public boolean readsAttributes() {
        return false;
    }
}
