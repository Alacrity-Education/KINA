package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartSource;
import ro.alacrity.kina.domain.Vocabulary;

import java.util.Optional;

/** The mounting word of the package field (LCSC {@code SMD,D8xL10mm}, {@code 插件,D6.3xL8mm}). */
public final class PackageFieldMounting implements AttributeLogic {

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        return Optional.ofNullable(part.packageName() == null ? null
                : ctx.word(Vocabulary.MOUNTING, part.packageName(), lookup.family()));
    }
}
