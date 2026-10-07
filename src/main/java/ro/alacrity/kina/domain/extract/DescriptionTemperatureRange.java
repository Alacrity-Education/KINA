package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartSource;

import java.util.Optional;

/** The operating temperature range the description prints (LCSC {@code -55℃~+155℃}), as {@link TemperatureRange}. */
public final class DescriptionTemperatureRange implements AttributeLogic {

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        return Optional.ofNullable(TemperatureRange.range(part.description()));
    }

    @Override
    public boolean readsAttributes() {
        return false;
    }
}
