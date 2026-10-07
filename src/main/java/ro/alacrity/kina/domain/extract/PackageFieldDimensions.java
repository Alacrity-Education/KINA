package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartSource;

import java.util.Optional;

/** The dimensions the package field states (LCSC {@code SMD,D6.3xL5.8mm}). */
public final class PackageFieldDimensions implements AttributeLogic {

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        return Optional.ofNullable(Dimensions.parse(part.packageName()));
    }
}
