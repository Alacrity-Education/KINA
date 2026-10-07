package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartSource;
import ro.alacrity.kina.domain.Vocabulary;

import java.util.Optional;

/** The first package of the named attributes, which state millimetres (TME {@code Case - mm}): a metric chip code is converted to the imperial one. */
public final class MetricPackageCode implements AttributeLogic {

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        for (String v : lookup.values(part)) {
            String word = ctx.word(Vocabulary.METRIC_PACKAGE, v, lookup.family());
            if (word != null) {
                return Optional.of(word);
            }
        }
        return Optional.empty();
    }
}
