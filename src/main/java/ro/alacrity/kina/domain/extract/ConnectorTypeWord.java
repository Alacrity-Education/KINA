package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.PartSource;

import java.util.Optional;

/**
 * The connector type the named attributes state (TME {@code Type of connector} = {@code pin strips}, Mouser
 * {@code Product} = {@code Headers & Wire Housings}): the first more specific than a generic connector.
 */
public final class ConnectorTypeWord implements AttributeLogic {

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        for (String v : lookup.values(part)) {
            String type = ctx.connector(v).type();
            if (type != null && !ParsedQuery.CONNECTOR.equals(type)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
