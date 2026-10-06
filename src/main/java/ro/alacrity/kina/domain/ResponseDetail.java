package ro.alacrity.kina.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * How much of each part a response carries (DESIGN.md 4), serialised lower-case. {@link #COMPACT} (the default):
 * identity, stock, order rules, prices, availability, links, scores and the canonical attributes only. {@link #FULL}:
 * additionally the category, the photo URL, the raw distributor attributes and the distributor-specific {@code extra}
 * fields.
 */
public enum ResponseDetail {
    COMPACT, FULL;

    @JsonValue
    public String jsonValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Case-insensitive; null or blank is {@link #COMPACT} (the default of the searches). */
    @JsonCreator
    public static ResponseDetail parse(String value) {
        return parse(value, COMPACT);
    }

    /** Case-insensitive; null or blank is {@code fallback} ({@code get_part} defaults to {@link #FULL}). */
    public static ResponseDetail parse(String value, ResponseDetail fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "compact" -> COMPACT;
            case "full" -> FULL;
            default -> throw new IllegalArgumentException("detail must be \"compact\" or \"full\", got: " + value);
        };
    }
}
