package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartSource;

import java.util.Optional;

/** The dimensions the description states (TME {@code Ø6.3x5.8mm}). */
public final class DescriptionDimensions implements AttributeLogic {

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        return Optional.ofNullable(Dimensions.parse(part.description()));
    }

    @Override
    public boolean readsAttributes() {
        return false;
    }
}
