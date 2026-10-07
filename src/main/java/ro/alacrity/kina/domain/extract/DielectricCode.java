package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartSource;
import ro.alacrity.kina.domain.Vocabulary;

import java.util.Optional;

/** The first dielectric code ({@code X7R}, {@code C0G}) of the named attributes. */
public final class DielectricCode implements AttributeLogic {

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        for (String v : lookup.values(part)) {
            String word = ctx.word(Vocabulary.DIELECTRIC, v, lookup.family());
            if (word != null) {
                return Optional.of(word);
            }
        }
        return Optional.empty();
    }
}
