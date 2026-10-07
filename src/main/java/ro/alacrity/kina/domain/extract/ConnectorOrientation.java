package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartSource;

import java.util.Optional;

/** The orientation (right angle, vertical) the first of the named attributes that states one gives. */
public final class ConnectorOrientation implements AttributeLogic {

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        for (String v : lookup.values(part)) {
            String orientation = ctx.connector(v).orientation();
            if (orientation != null) {
                return Optional.of(orientation);
            }
        }
        return Optional.empty();
    }
}
