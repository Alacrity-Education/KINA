package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartSource;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SMD or THT from the first word of a JLCPCB package field written with a pin count ({@code SMD-4P,6x6mm},
 * {@code Plugin-6P,7x7mm}, {@code 插件-3P}), which the mounting vocabulary does not read as a word.
 */
public final class PackageFieldPrefix implements AttributeLogic {

    private static final Pattern PREFIX = Pattern.compile("(?i)^\\s*(smd|smt|plugin|插件)-\\d");

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        if (part.packageName() == null) {
            return Optional.empty();
        }
        Matcher m = PREFIX.matcher(part.packageName());
        if (!m.find()) {
            return Optional.empty();
        }
        String word = m.group(1).toLowerCase(java.util.Locale.ROOT);
        return Optional.of(word.startsWith("sm") ? "SMD" : "THT");
    }
}
