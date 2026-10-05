package ro.alacrity.kina.search;

import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Model-based relevance scoring (DESIGN.md section 3.3). The deterministic ranker is always the fallback. */
public interface PartRanker {

    /**
     * Scores candidates for one query. Returns a raw score per candidate key
     * ({@link ro.alacrity.kina.domain.PartKey}: distributor + ":" + distributorPartNumber), higher = more relevant;
     * only the order matters, the caller rank-normalises it. Throws on failure/timeout; the caller falls back to
     * the deterministic ranking.
     */
    Map<String, Double> rank(ParsedQuery query, List<Part> candidates, Duration budget) throws RankingException;

    /** Short name, e.g. "cross-encoder". */
    String name();
}
