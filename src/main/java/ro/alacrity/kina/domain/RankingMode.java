package ro.alacrity.kina.domain;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * How a search response was ranked, serialised lower-case: {@code "blended"} (deterministic score blended with the
 * cross-encoder, DESIGN.md 3.3) or {@code "fallback"} (deterministic only; {@code ranking_note} says why).
 */
public enum RankingMode {
    BLENDED, FALLBACK;

    @JsonValue
    public String jsonValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
