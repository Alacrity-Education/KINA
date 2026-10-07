package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartSource;
import ro.alacrity.kina.domain.Vocabulary;

import java.util.Optional;

/** The first package of the named attributes; a four-digit chip code is imperial. */
public final class PackageCode implements AttributeLogic {

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        for (String v : lookup.values(part)) {
            String word = ctx.word(Vocabulary.PACKAGE, v, lookup.family());
            if (word != null) {
                return Optional.of(word);
            }
        }
        return Optional.empty();
    }
}
