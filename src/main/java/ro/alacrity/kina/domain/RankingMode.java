package ro.alacrity.kina.domain;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/** How a search response was ranked. Serialised lower-case ({@code "laya"}, {@code "fallback"}). */
public enum RankingMode {
    LAYA, FALLBACK;

    @JsonValue
    public String jsonValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
