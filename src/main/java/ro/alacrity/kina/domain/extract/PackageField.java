package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartSource;
import ro.alacrity.kina.domain.Vocabulary;

import java.util.Optional;

/** The package the distributor's package field names (LCSC {@code 0805}, TME {@code SOT23}), when KINA recognises it. */
public final class PackageField implements AttributeLogic {

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        return Optional.ofNullable(ctx.word(Vocabulary.PACKAGE, part.packageName(), lookup.family()));
    }
}
