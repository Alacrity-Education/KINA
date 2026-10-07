package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartSource;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A count (positions, rows): the first positive number of up to three digits in the named attributes. */
public final class FirstInteger implements AttributeLogic {

    private static final Pattern DIGITS = Pattern.compile("\\d{1,3}");

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        for (String v : lookup.values(part)) {
            Matcher m = DIGITS.matcher(v);
            if (m.find()) {
                int n = Integer.parseInt(m.group());
                if (n > 0) {
                    return Optional.of(n);
                }
            }
        }
        return Optional.empty();
    }
}
