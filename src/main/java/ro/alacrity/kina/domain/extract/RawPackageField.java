package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartSource;

import java.util.Optional;

/** The package field as the distributor states it, even when KINA does not recognise it: never overridden by a guess. */
public final class RawPackageField implements AttributeLogic {

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        String p = part.packageName();
        return p != null && !p.isBlank() && !p.strip().equals("-") ? Optional.of(p.trim()) : Optional.empty();
    }
}
