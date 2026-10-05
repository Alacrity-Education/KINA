package ro.alacrity.kina.cache;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * How a distributor's results were obtained (DESIGN.md section 3.2). Serialised lower-case.
 * {@code NOT_APPLICABLE} is used for LCSC (the SQLite database is the cache) and for failed distributors
 * that were never looked up.
 */
public enum CacheStatus {
    HIT, MISS, PARTIAL, BYPASSED, NOT_APPLICABLE;

    @JsonValue
    public String jsonValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
