package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartAttribute;
import ro.alacrity.kina.domain.PartSource;

import java.util.Optional;

/**
 * The gender a connector type attribute states explicitly (TME {@code Type of connector} = {@code female header}):
 * reads the names of {@link PartAttribute#CONNECTOR_TYPE}, the first value that names a gender with the word
 * {@code female} or {@code male}.
 */
public final class StatedGender implements AttributeLogic {

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        for (String v : part.values(PartAttribute.CONNECTOR_TYPE.names())) {
            if (ctx.connector(v).gender() != null) {
                String gender = GenderWord.explicitGender(v);
                if (gender != null) {
                    return Optional.of(gender);
                }
            }
        }
        return Optional.empty();
    }
}
