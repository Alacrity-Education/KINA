package ro.alacrity.kina.cache;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * How a distributor's results were obtained (DESIGN.md section 3.2). Serialised lower-case.
 * {@code NOT_APPLICABLE} is used for LCSC (the SQLite database is the cache) and for failed distributors
 * that were never looked up. {@code STALE}: the live search failed (the entry carries the {@code error}) and the parts
 * come from the expired cached list of the query, their stock and prices marked as they are (DESIGN.md 3.2 "Cache
 * model").
 */
public enum CacheStatus {
    HIT, MISS, PARTIAL, BYPASSED, NOT_APPLICABLE, STALE;

    @JsonValue
    public String jsonValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
