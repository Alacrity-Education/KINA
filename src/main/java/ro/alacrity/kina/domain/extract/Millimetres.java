package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartSource;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A length in millimetres ({@code 6.3mm} -&gt; {@code 6.3}): the first of the named attributes that states one. */
public final class Millimetres implements AttributeLogic {

    private static final Pattern MM = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s?mm");

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        for (String v : lookup.values(part)) {
            Matcher m = MM.matcher(v);
            if (m.find()) {
                return Optional.of(Dimensions.number(m.group(1)));
            }
        }
        return Optional.empty();
    }
}
