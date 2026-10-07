package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartSource;

import java.util.Optional;

/**
 * The words of the attribute's {@link ro.alacrity.kina.domain.PartAttribute#vocabulary() vocabulary} in every named
 * attribute, the description and the category together (a fan's features: TME {@code Additional functions} =
 * {@code autorestart}, {@code Signal output} = {@code F type}, {@code Leads} = {@code leads x3}; Mouser
 * {@code Tach/PWM}): every name counts, none wins.
 */
public final class MergedWords implements AttributeLogic {

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        StringBuilder text = new StringBuilder();
        for (String v : lookup.values(part)) {
            text.append(v).append(" ; ");
        }
        text.append(part.description() == null ? "" : part.description()).append(" ; ")
                .append(part.category() == null ? "" : part.category());
        return Optional.ofNullable(ctx.word(lookup.attribute().vocabulary(), text.toString(), lookup.family()));
    }
}
