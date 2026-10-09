package ro.alacrity.kina.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.distributor.lcsc.LcscFieldSearch;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.search.PageCollector.Check;
import ro.alacrity.kina.search.PageCollector.Collected;
import ro.alacrity.kina.search.field.FieldPredicate;
import ro.alacrity.kina.search.field.FieldQuery;
import ro.alacrity.kina.search.field.FieldQueryBuilder;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * LCSC retrieval: the JLCPCB SQLite database is the cache, so a search is live queries on it (DESIGN.md 9.3).
 *
 * <p>With {@code kina.jlcpcb.field-index.enabled} and a current typed table, a request that states a constraint
 * ({@link FieldRelaxation#statesConstraint}) first runs as a field query on it ({@link LcscFieldSearch}) in the one
 * relaxation loop of the field index ({@link FieldRelaxation}): each step's candidates (confirmed first, then the
 * highest stock) are read in chunks and checked until the window is full, and the loop relaxes only while the step
 * has not enough (at least {@code max_results} that pass the Java check, one confirmed). The candidates that pass
 * are the parts the ranker sees; when they are fewer than the window, or none meets the request with its ratings
 * verified, today's FTS5 search ({@code LcscClient.search}) fills the rest of the window and its parts are merged in
 * (deduplicated by LCSC number). Without a current table, with a request that states no constraint (free text alone:
 * the FTS5 order is what it needs) or when the typed query fails, the FTS5 search serves alone, exactly as before the
 * table existed.
 */
@Slf4j
@Component
final class LcscRetriever implements DistributorRetriever {

    @Autowired private KinaProperties properties;
    @Autowired private PageCollector pages;
    @Autowired private RankingService ranking;
    @Autowired private RequestedLookup requested;
    @Autowired private ParametricExtractor extractor;
    @Autowired private LcscFieldSearch fieldSearch;

    /**
     * What the typed query found.
     *
     * @param parts            the candidates of the step used that pass the Java check, enriched, in order
     * @param total            rows that step matches
     * @param step             the relaxation step used (0: none)
     * @param relaxed          the constraints the step left out
     * @param droppedKeywords  the free-text keywords the step left out
     * @param belowSpec        the candidates whose known rating is below the request (and below spec is not
     *                         allowed): they take no place in the window, the ranker excludes and counts them
     *                         ({@code excluded_below_spec}, as on the FTS path)
     */
    record Typed(List<Part> parts, int total, int step, List<String> relaxed, List<String> droppedKeywords,
                 List<Part> belowSpec) {
    }

    @Override
    public Fetched retrieve(DistributorClient client, Prepared prepared, Progress progress,
                            DistributorBudget deadline) {
        Distributor distributor = client.distributor();
        ParsedQuery parsed = prepared.parsed();
        DistributorRetriever.Plan plan = DistributorRetriever.plan(properties, ranking, distributor, prepared);
        String query = plan.query();
        int window = plan.window();
        int maxPages = plan.maxPages();
        Check meets = plan.meets();

        Typed typed = typed(prepared, window, deadline, meets);
        List<Part> existing = List.of();
        if (typed != null) {
            existing = typed.parts();
            progress.parts = existing;
            progress.totalResults = typed.total();
            progress.constraintsRelaxed = typed.relaxed();
        }
        // with typed candidates the FTS search only runs for what is missing from the window (or when none of them is
        // confirmed); its parts are merged in, a part number seen twice counts once
        Collected collected = pages.collect(client, query, 0, window, maxPages, existing, progress, deadline, meets,
                parsed.family());
        Fetched fetched = collected.toFetched(distributor, CacheStatus.NOT_APPLICABLE)
                .withOutOfStockMatches(progress.outOfStock);
        if (typed != null) {
            fetched = withBelowSpec(fetched, typed.belowSpec())
                    .withConstraintsRelaxed(union(typed.relaxed(), fetched.constraintsRelaxed()))
                    .withDroppedKeywords(union(typed.droppedKeywords(), fetched.droppedKeywords()));
        }
        // a part number the query names that the search did not bring is looked up directly
        return requested.complete(client, prepared, fetched, deadline);
    }

    /** One read of the typed table that failed (an SQL error or no free connection). */
    private static final class TypedQueryException extends RuntimeException {
        TypedQueryException(SQLException cause) {
            super(cause);
        }
    }

    /**
     * The field query on the typed table: null when it is not used (disabled, no current table, no stated constraint
     * in the request, no step the index may read) or failed.
     */
    private Typed typed(Prepared prepared, int window, DistributorBudget deadline, Check check) {
        if (!fieldSearch.available()) {
            return null;
        }
        ParsedQuery parsed = prepared.parsed();
        try {
            FieldQuery query = FieldQueryBuilder.build(parsed, ConstraintPolicy.of(ranking), Distributor.LCSC);
            if (!FieldRelaxation.statesConstraint(query, query.step(0), parsed)) {
                return null;   // free text only: BM25 order is what the FTS5 search is for
            }
            Steps steps = new Steps(query, prepared, window, deadline, check);
            FieldRelaxation.Result result = FieldRelaxation.relax(query.steps(), steps);
            if (steps.used == null) {
                return null;
            }
            log.debug("LCSC field query '{}': step {} of {} ({}), {} candidates of {}", parsed.normalizedKey(),
                    steps.used.index(), query.steps().size() - 1, result.enough() ? "enough" : "short",
                    steps.parts.size(), steps.total);
            return new Typed(List.copyOf(steps.parts), steps.total, steps.used.index(), steps.used.relaxed(),
                    keywords(query, steps.used), List.copyOf(steps.belowSpec));
        } catch (RuntimeException e) {
            log.warn("LCSC field query '{}' failed, using the FTS search: {}", parsed.normalizedKey(),
                    e instanceof TypedQueryException ? e.getCause().toString() : e.toString());
            return null;
        }
    }

    /**
     * The steps of the LCSC field query in the relaxation loop: a step's candidates are read in chunks until the window
     * is full ({@link FieldRelaxation#chunk} of the window, at most {@code max-candidates} rows); only the candidates
     * the Java check returns take a place, so the FTS search fills the places of the others instead of the window
     * ending short. Ratings are not filtered in SQL: a candidate below spec is handed to the ranker without a place,
     * which excludes and counts it (DESIGN.md 9.3).
     */
    private final class Steps implements FieldRelaxation.Steps {

        private final FieldQuery query;
        private final ParsedQuery parsed;
        private final int window;
        private final int target;
        private final DistributorBudget deadline;
        private final Check check;
        private final boolean allowBelowSpec;
        private final boolean requireStated;
        private final int recall;
        private List<Part> parts = new ArrayList<>();
        private List<Part> belowSpec = new ArrayList<>();
        private int total;
        private FieldQuery.Step used;

        Steps(FieldQuery query, Prepared prepared, int window, DistributorBudget deadline, Check check) {
            this.query = query;
            this.parsed = prepared.parsed();
            this.window = window;
            this.target = prepared.maxResults();
            this.deadline = deadline;
            this.check = check;
            this.allowBelowSpec = prepared.request().allowBelowSpec();
            KinaProperties.FieldIndex config = properties.search().fieldIndex();
            this.requireStated = config.requireStatedConstraint();
            this.recall = config.maxCandidates();
        }

        @Override
        public boolean read(FieldQuery.Step step) {
            if (!FieldRelaxation.readable(requireStated, query, step, parsed)) {
                return false;
            }
            List<Part> stepParts = new ArrayList<>(window);
            List<Part> stepBelow = new ArrayList<>();
            int[] stepTotal = {0};
            FieldRelaxation.readChunks((offset, size) -> {
                LcscFieldSearch.Candidates candidates;
                try {
                    candidates = fieldSearch.candidates(query, step, offset, size, poolWait());
                } catch (SQLException e) {
                    throw new TypedQueryException(e);
                }
                if (offset == 0) {
                    stepTotal[0] = candidates.total();
                }
                return new FieldRelaxation.Chunk(candidates.parts(), candidates.rows() < size);
            }, FieldRelaxation.chunk(window), recall, part -> {
                if (stepParts.size() >= window) {
                    return;
                }
                Part enriched = extractor.enrich(part);
                if (check.returnable(enriched, allowBelowSpec)) {
                    stepParts.add(enriched);
                } else if (check.returnable(enriched, true)) {
                    stepBelow.add(enriched);
                }
            }, () -> stepParts.size() >= window);
            parts = stepParts;
            belowSpec = stepBelow;
            total = stepTotal[0];
            used = step;
            return FieldRelaxation.enough(parts, target, p -> check.confirmed(p, allowBelowSpec));
        }

        @Override
        public FieldRelaxation.Next notEnough(FieldQuery.Step step) {
            return deadline.remainingNanos() <= 0 ? FieldRelaxation.Next.STOP : FieldRelaxation.Next.RELAX;
        }

        /** The configured {@code kina.jlcpcb.pool-wait}, at most what is left of the deadline (review B12). */
        private Duration poolWait() {
            return Duration.ofNanos(Math.max(1, Math.min(properties.jlcpcb().poolWait().toNanos(),
                    deadline.remainingNanos())));
        }
    }

    /** {@code fetched} with the below-spec candidates of the typed query added after its parts (once per part). */
    private static Fetched withBelowSpec(Fetched fetched, List<Part> belowSpec) {
        if (belowSpec.isEmpty()) {
            return fetched;
        }
        Set<String> seen = new HashSet<>();
        fetched.parts().forEach(p -> seen.add(p.distributorPartNumber()));
        List<Part> merged = new ArrayList<>(fetched.parts());
        belowSpec.stream().filter(p -> seen.add(p.distributorPartNumber())).forEach(merged::add);
        return merged.size() == fetched.parts().size() ? fetched : fetched.withParts(merged);
    }

    /** The free-text words of the group {@code K} when {@code step} left it out. */
    static List<String> keywords(FieldQuery query, FieldQuery.Step step) {
        FieldQuery.Group k = query.group(FieldQuery.Role.K.name());
        if (k == null || !step.dropped().contains(k.name())) {
            return List.of();
        }
        List<String> words = new ArrayList<>();
        for (FieldPredicate p : k.predicates()) {
            if (p instanceof FieldPredicate.Word w) {
                words.add(w.token());
            } else if (p instanceof FieldPredicate.Substring s) {
                words.add(s.token());
            }
        }
        return List.copyOf(words);
    }

    private static List<String> union(List<String> first, List<String> second) {
        Set<String> out = new LinkedHashSet<>(first);
        out.addAll(second);
        return List.copyOf(out);
    }
}
