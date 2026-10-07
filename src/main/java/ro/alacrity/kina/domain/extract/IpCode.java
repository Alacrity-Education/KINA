package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartSource;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An ingress protection code ({@code IP67}, {@code IPX7}) as its two digits ({@code 67}, {@code 7}): the named
 * attributes (TME {@code IP rating}), or the description and category when the source names none. An {@code X} is 0.
 */
public final class IpCode implements AttributeLogic {

    private static final Pattern IP = Pattern.compile("(?i)(?<![\\p{L}\\d])IP\\s?([0-6X])([0-9X])(?![\\p{L}\\d])");

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        Iterable<String> texts = lookup.source().names().length > 0 ? lookup.values(part)
                : java.util.List.of(part.description() == null ? "" : part.description(),
                part.category() == null ? "" : part.category());
        for (String v : texts) {
            Integer code = code(v);
            if (code != null) {
                return Optional.of(ctx.measure(lookup.attribute().kind(), code, null));
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean readsAttributes() {
        return true;
    }

    /** The two digits of the first IP code of a text ({@code IP67}: 67, {@code IPX7}: 7), null when none. */
    public static Integer code(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = IP.matcher(text);
        if (!m.find()) {
            return null;
        }
        int solids = Character.isDigit(m.group(1).charAt(0)) ? m.group(1).charAt(0) - '0' : 0;
        int liquids = Character.isDigit(m.group(2).charAt(0)) ? m.group(2).charAt(0) - '0' : 0;
        return 10 * solids + liquids;
    }
}
