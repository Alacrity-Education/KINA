package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartAttribute;
import ro.alacrity.kina.domain.PartSource;

import java.util.Optional;

/**
 * The positions a pinout layout states (TME {@code Connector pinout layout} = {@code 2x5}): reads the names of
 * {@link PartAttribute#PINOUT_LAYOUT}, the first value that states them.
 */
public final class LayoutPositions implements AttributeLogic {

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        for (String v : part.values(PartAttribute.PINOUT_LAYOUT.names())) {
            Integer n = ctx.connector(v).positions();
            if (n != null) {
                return Optional.of(n);
            }
        }
        return Optional.empty();
    }
}
