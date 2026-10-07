package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartSource;

import java.util.Optional;

/**
 * The word of the attribute's {@link ro.alacrity.kina.domain.PartAttribute#vocabulary() vocabulary} that the part's
 * description and category state (Mouser {@code Blowers & Centrifugal Fans}: radial; {@code DC Fans 40x40x10mm 12VDC}:
 * axial, DC, 40x40x10mm): the source after the distributor attributes.
 */
public final class DescribedWord implements AttributeLogic {

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        String text = (part.description() == null ? "" : part.description()) + " ; "
                + (part.category() == null ? "" : part.category());
        return Optional.ofNullable(ctx.word(lookup.attribute().vocabulary(), text, lookup.family()));
    }

    @Override
    public boolean readsAttributes() {
        return false;
    }
}
