package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartSource;

import java.util.Optional;

/**
 * What the part's description says about the attribute (a value, the dielectric, package or mounting word): the
 * source after the distributor attributes.
 */
public final class Described implements AttributeLogic {

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        return Optional.ofNullable(ctx.described(lookup.attribute(), Object.class));
    }

    @Override
    public boolean readsAttributes() {
        return false;
    }
}
