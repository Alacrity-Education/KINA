package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartSource;

import java.util.Optional;

/**
 * The word of the attribute's {@link ro.alacrity.kina.domain.PartAttribute#vocabulary() vocabulary} in the package
 * field (JLCPCB {@code SMD-4P,6x6mm}: a switch body of 6x6mm).
 */
public final class PackageFieldWord implements AttributeLogic {

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        return Optional.ofNullable(ctx.word(lookup.attribute().vocabulary(), part.packageName(), lookup.family()));
    }
}
