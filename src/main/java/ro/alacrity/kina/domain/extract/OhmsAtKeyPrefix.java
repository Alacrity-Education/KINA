package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartSource;

import java.util.Map;
import java.util.Optional;

/**
 * As {@link OhmsAtFrequency}, for the attributes whose name starts with one of the source's names (key prefixes such as
 * {@code "esr "}: {@code ESR 100kHz}) and contains none of its excluding words, in the part's order.
 */
public final class OhmsAtKeyPrefix implements AttributeLogic {

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        for (Map.Entry<String, String> e : part.attributes().entrySet()) {
            String key = e.getKey();
            if (matches(key, lookup.source().names(), lookup.source().excluding())) {
                String display = OhmsAtFrequency.ohms(e.getValue(), OhmsAtFrequency.frequency(key, e.getValue()), ctx);
                if (display != null) {
                    return Optional.of(display);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean matches(String key, String[] prefixes, String[] excluding) {
        for (String x : excluding) {
            if (key.contains(x)) {
                return false;
            }
        }
        for (String p : prefixes) {
            if (key.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
