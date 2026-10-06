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

    /** Case-insensitive; null or blank is {@link #COMPACT}. */
    @JsonCreator
    public static ResponseDetail parse(String value) {
        if (value == null || value.isBlank()) {
            return COMPACT;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "compact" -> COMPACT;
            case "full" -> FULL;
            default -> throw new IllegalArgumentException("detail must be \"compact\" or \"full\", got: " + value);
        };
    }
}
