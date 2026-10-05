package ro.alacrity.kina.distributor.mouser;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Parses Mouser's locale formatted prices ({@code "1,40 €"}, {@code "0,853 €"}, {@code "$0.10"}, {@code "1.234,56 €"},
 * {@code "1,234.56"}) into a {@link BigDecimal} (DESIGN.md 9.1):
 * <ul>
 *   <li>everything except digits, {@code ,} and {@code .} is stripped;</li>
 *   <li>when both separators appear the last one is the decimal separator, the other one is grouping;</li>
 *   <li>a lone {@code ,} is the decimal separator when followed by 1-3 digits at the end, otherwise grouping;</li>
 *   <li>a single {@code .} is the decimal separator; repeated {@code .} are grouping.</li>
 * </ul>
 */
public final class MouserPriceParser {

    private static final Pattern NOT_NUMERIC = Pattern.compile("[^0-9.,]");
    private static final Pattern LONE_COMMA_DECIMAL = Pattern.compile("^\\d*,\\d{1,3}$");

    private MouserPriceParser() {
    }

    public static Optional<BigDecimal> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = NOT_NUMERIC.matcher(raw).replaceAll("");
        if (s.chars().noneMatch(Character::isDigit)) {
            return Optional.empty();
        }
        int lastComma = s.lastIndexOf(',');
        int lastDot = s.lastIndexOf('.');
        String normalised;
        if (lastComma >= 0 && lastDot >= 0) {
            char decimal = lastComma > lastDot ? ',' : '.';
            char grouping = decimal == ',' ? '.' : ',';
            int decimalAt = Math.max(lastComma, lastDot);
            String integerPart = s.substring(0, decimalAt);
            String fraction = s.substring(decimalAt + 1);
            if (integerPart.indexOf(decimal) >= 0) {
                return Optional.empty(); // e.g. "1,2.3,4": decimal separator appears twice
            }
            normalised = integerPart.replace(String.valueOf(grouping), "") + "." + fraction;
        } else if (lastComma >= 0) {
            normalised = LONE_COMMA_DECIMAL.matcher(s).matches() ? s.replace(',', '.') : s.replace(",", "");
        } else if (lastDot >= 0 && s.indexOf('.') != lastDot) {
            normalised = s.replace(".", "");
        } else {
            normalised = s;
        }
        if (normalised.startsWith(".")) {
            normalised = "0" + normalised;
        }
        if (normalised.endsWith(".")) {
            normalised = normalised.substring(0, normalised.length() - 1);
        }
        try {
            return Optional.of(new BigDecimal(normalised));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
