package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartSource;

import java.util.Locale;
import java.util.Optional;

/** A connector series code (TME {@code Manufacturer series} = {@code XH}): a value of up to four characters, upper-case. */
public final class ShortSeries implements AttributeLogic {

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        for (String v : lookup.values(part)) {
            if (!v.isBlank() && v.strip().length() <= 4) {
                return Optional.of(v.strip().toUpperCase(Locale.ROOT));
            }
        }
        return Optional.empty();
    }
}
