package ro.alacrity.kina.search;

import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PartKey;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The hard-constraint check and the deterministic assessment of the parts of one request, computed once and shared by
 * the retrieval (paging, relaxation, the field index's candidate window) and the ranking stage (review B2: a part is
 * checked once per request, not again by the ranker). One instance per request ({@link Prepared#checks()}), bound to
 * its parsed query (another query is never served from it), written by the distributors' fetch threads, hence
 * concurrent. An entry belongs to one part instance: a part received again (fresh stock from a live call, a stock
 * refresh) is another instance and is checked again, since the assessment's tie-break reads the stock.
 */
public final class PartChecks {

    private final ParsedQuery query;

    /** The checks of the parts of a request for {@code query}. */
    public PartChecks(ParsedQuery query) {
        this.query = query;
    }

    private record Entry<T>(Part part, T value) {
    }

    private final Map<String, Entry<ConstraintPolicy.Result>> checks = new ConcurrentHashMap<>();
    private final Map<String, Entry<DeterministicRanker.Assessment>> assessments = new ConcurrentHashMap<>();

    /**
     * The hard-constraint check of {@code part} for {@code query}, computed by {@code compute} unless this part was
     * checked for this request's query.
     */
    ConstraintPolicy.Result check(ParsedQuery query, Part part, Supplier<ConstraintPolicy.Result> compute) {
        return get(checks, query, part, compute);
    }

    /** The assessment of {@code part} for {@code query}, computed once per part instance of this request. */
    DeterministicRanker.Assessment assessment(ParsedQuery query, Part part,
                                              Supplier<DeterministicRanker.Assessment> compute) {
        return get(assessments, query, part, compute);
    }

    private <T> T get(Map<String, Entry<T>> memo, ParsedQuery asked, Part part, Supplier<T> compute) {
        if (asked != query) {
            return compute.get();
        }
        String key = PartKey.of(part);
        Entry<T> entry = memo.get(key);
        if (entry != null && entry.part() == part) {
            return entry.value();
        }
        T value = compute.get();
        memo.put(key, new Entry<>(part, value));
        return value;
    }
}
