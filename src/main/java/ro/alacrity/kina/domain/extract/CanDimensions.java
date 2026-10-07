package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartAttribute;
import ro.alacrity.kina.domain.PartSource;

import java.util.Optional;

/**
 * Can dimensions from a diameter and a height attribute ({@link PartAttribute#DIAMETER} and
 * {@link PartAttribute#HEIGHT}): {@code D6.3 x 5.8mm} when both are stated.
 */
public final class CanDimensions implements AttributeLogic {

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        String diameter = ctx.read(PartAttribute.DIAMETER, String.class);
        String height = ctx.read(PartAttribute.HEIGHT, String.class);
        return diameter != null && height != null ? Optional.of("D" + diameter + " x " + height + "mm")
                : Optional.empty();
    }
}
