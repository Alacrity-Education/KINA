package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.PartSource;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The gender of a gender attribute: {@code female}/{@code male}, Mouser {@code Socket}/{@code Receptacle}/
 * {@code Jack} (female) and {@code Pin}/{@code Plug} (male).
 */
public final class GenderWord implements AttributeLogic {

    private static final Pattern FEMALE_WORD = Pattern.compile("(?i)\\bfemale\\b");
    private static final Pattern MALE_WORD = Pattern.compile("(?i)\\bmale\\b");

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        for (String v : lookup.values(part)) {
            String gender = genderWord(v);
            if (gender != null) {
                return Optional.of(gender);
            }
        }
        return Optional.empty();
    }

    /** "female"/"male" when the text says so explicitly (earliest wins), else null. */
    public static String explicitGender(String text) {
        if (text == null) {
            return null;
        }
        Matcher f = FEMALE_WORD.matcher(text);
        Matcher m = MALE_WORD.matcher(text);
        int fi = f.find() ? f.start() : -1;
        int mi = m.find() ? m.start() : -1;
        if (fi >= 0 && (mi < 0 || fi <= mi)) {
            return ParsedQuery.FEMALE;
        }
        return mi >= 0 ? ParsedQuery.MALE : null;
    }

    private static String genderWord(String value) {
        String explicit = explicitGender(value);
        if (explicit != null) {
            return explicit;
        }
        String v = value.toLowerCase(Locale.ROOT);
        if (v.contains("socket") || v.contains("receptacle") || v.contains("jack")) {
            return ParsedQuery.FEMALE;
        }
        if (v.contains("pin") || v.contains("plug")) {
            return ParsedQuery.MALE;
        }
        return null;
    }
}
