package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartSource;

import java.util.Optional;

/**
 * The first word of the attribute's {@link ro.alacrity.kina.domain.PartAttribute#vocabulary() vocabulary} in the named
 * attributes (TME {@code Kind of fan} = {@code blower}: radial; {@code Kind of Bearing} = {@code slide}: sleeve).
 */
public final class VocabularyWord implements AttributeLogic {

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        for (String v : lookup.values(part)) {
            String word = ctx.word(lookup.attribute().vocabulary(), v, lookup.family());
            if (word != null) {
                return Optional.of(word);
            }
        }
        return Optional.empty();
    }
}
