package ro.alacrity.kina.search;

import lombok.With;
import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;

import java.util.List;

/**
 * What one distributor contributed: in-stock parts (enriched, distributor order, deduplicated), the
 * distributor-reported total, the cache status, an error code (null on success), the time spent waiting on
 * rate limits, the constraints the relaxation loosened ({@code constraints_relaxed}), the free-text terms LCSC's
 * database search dropped, the request terms not sent in the phrase ({@code query_terms_dropped}) and the parts the
 * query names by part number that the distributor lists without ships-now stock ({@code listed}, stock 0: ranked last,
 * never counted in {@code fetched}; DESIGN.md 2, stock rule).
 */
record Fetched(Distributor distributor, @With List<Part> parts, Integer totalResults, CacheStatus cache, String error,
               String fallbackQuery, @With long rateLimitWaitedMs, @With String distributorQuery,
               @With Integer outOfStockMatches, @With List<String> constraintsRelaxed,
               @With List<String> droppedKeywords, @With List<String> queryTermsDropped, @With List<Part> listed) {

    Fetched {
        parts = parts == null ? List.of() : List.copyOf(parts);
        listed = listed == null ? List.of() : List.copyOf(listed);
        constraintsRelaxed = constraintsRelaxed == null ? List.of() : List.copyOf(constraintsRelaxed);
        droppedKeywords = droppedKeywords == null ? List.of() : List.copyOf(droppedKeywords);
        queryTermsDropped = queryTermsDropped == null ? List.of() : List.copyOf(queryTermsDropped);
    }

    Fetched(Distributor distributor, List<Part> parts, Integer totalResults, CacheStatus cache, String error,
            String fallbackQuery, long rateLimitWaitedMs) {
        this(distributor, parts, totalResults, cache, error, fallbackQuery, rateLimitWaitedMs, null, null, null,
                null, null, null);
    }

    Fetched(Distributor distributor, List<Part> parts, Integer totalResults, CacheStatus cache, String error,
            String fallbackQuery) {
        this(distributor, parts, totalResults, cache, error, fallbackQuery, 0);
    }

    Fetched(Distributor distributor, List<Part> parts, Integer totalResults, CacheStatus cache, String error) {
        this(distributor, parts, totalResults, cache, error, null);
    }

    static Fetched failed(Distributor distributor, CacheStatus cache, String error) {
        return new Fetched(distributor, List.of(), null, cache, error);
    }
}
