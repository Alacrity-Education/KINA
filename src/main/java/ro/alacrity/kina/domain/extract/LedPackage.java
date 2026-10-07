package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartSource;
import ro.alacrity.kina.domain.Vocabulary;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The LED package name of the package field and the named attributes (JLCPCB {@code SMD5050-6P}: {@code 5050},
 * {@code Plugin,D=5mm}: {@code 5mm}; TME {@code Case: 5050,PLCC6}: {@code 5050}; Mouser {@code Package / Case: T-1 3/4}:
 * {@code 5mm}): LED size codes are package names, never metric chip codes. A size code or lamp in any of them comes
 * before a PLCC package (TME {@code Case - mm: 5050} and {@code Case: PLCC6}).
 */
public final class LedPackage implements AttributeLogic {

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        List<String> texts = new ArrayList<>();
        if (part.packageName() != null) {
            texts.add(part.packageName());
        }
        texts.addAll(lookup.values(part));
        // an LED size code or lamp first, wherever it is (TME "Case - mm: 5050" before "Case: PLCC6"), then PLCC
        String plcc = null;
        for (String v : texts) {
            String word = ctx.word(Vocabulary.LED_PACKAGE, v, lookup.family());
            if (word != null && !word.startsWith("PLCC")) {
                return Optional.of(word);
            }
            if (plcc == null) {
                plcc = word;
            }
        }
        return Optional.ofNullable(plcc);
    }
}
