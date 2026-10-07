package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartFeatures;
import ro.alacrity.kina.domain.PartSource;

import java.util.Map;
import java.util.Optional;

/**
 * A scan of every attribute whose name contains one of the source's {@link ro.alacrity.kina.domain.Source#names()}
 * (as words, not full names) and none of its {@link ro.alacrity.kina.domain.Source#excluding()} words, in the part's
 * order: the first value that parses in the attribute's unit. The fallback for distributor names no list knows
 * ({@code Max. off-state voltage}).
 */
public final class KeyContaining implements AttributeLogic {

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        for (Map.Entry<String, String> e : part.attributes().entrySet()) {
            if (contains(e.getKey(), lookup.source().names()) && !contains(e.getKey(), lookup.source().excluding())) {
                PartFeatures.Measure m = ctx.parse(e.getValue(), lookup.attribute().readKind(), lookup.family());
                if (m != null) {
                    return Optional.of(m);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean contains(String key, String[] words) {
        for (String w : words) {
            if (key.contains(w)) {
                return true;
            }
        }
        return false;
    }
}
