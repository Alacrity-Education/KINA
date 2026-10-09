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

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * LCSC retrieval: the JLCPCB SQLite database is the cache, so a search is live queries on it (DESIGN.md 9.3).
 *
 * <p>With {@code kina.jlcpcb.field-index.enabled} and a current typed table the request first runs as a field query on
 * it ({@link LcscFieldSearch}): the unrelaxed step, then one relaxation step at a time while fewer candidates than
 * the window come back. The candidates (confirmed first, then the highest stock) are the parts the Java check and the
 * ranker then see. When the ladder is exhausted with fewer candidates than the window, or none of them meets the
 * request with its ratings verified, today's FTS5 search ({@code LcscClient.search}) fills the rest of the window and
 * its parts are merged in (deduplicated by LCSC number). Without a current table, with a free-text-only request or
 * when the typed query fails, the FTS5 search serves alone, exactly as before the table existed.
 */
@Slf4j
@Component
final class LcscRetriever implements DistributorRetriever {

    /** The longest the field query waits for a free connection of the pool. */
    private static final Duration MAX_POOL_WAIT = Duration.ofSeconds(10);

    @Autowired private KinaProperties properties;
    @Autowired private PageCollector pages;
    @Autowired private RankingService ranking;
    @Autowired private RequestedLookup requested;
    @Autowired private ParametricExtractor extractor;
    /** Null in tests that build the retriever without the typed table. */
    @Autowired(required = false) private LcscFieldSearch fieldSearch;

    /**
     * What the typed query found.
     *
     * @param parts            the candidates of the step used, enriched, in order
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

        Typed typed = typed(prepared, window, deadline);
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

    /**
     * The field query on the typed table: null when it is not used (disabled, no current table, no typed constraint in
     * the request) or failed. Relaxes one step at a time while the candidates are fewer than {@code window}.
     */
    private Typed typed(Prepared prepared, int window, DistributorBudget deadline) {
        if (fieldSearch == null || !fieldSearch.available()) {
            return null;
        }
        try {
            FieldQuery query = FieldQueryBuilder.build(prepared.parsed(), ConstraintPolicy.of(ranking),
                    Distributor.LCSC);
            if (!constrains(query)) {
                return null;   // free text only: BM25 order is what the FTS5 search is for
            }
            List<FieldQuery.Step> steps = query.steps();
            LcscFieldSearch.Candidates best = null;
            FieldQuery.Step used = null;
            for (FieldQuery.Step step : steps) {
                if (best != null && deadline.remainingNanos() <= 0) {
                    break;
                }
                Duration wait = Duration.ofNanos(Math.max(1, Math.min(MAX_POOL_WAIT.toNanos(),
                        deadline.remainingNanos())));
                // twice the window: the SQL ranges are wider than the Java check (a 4.75k part for 4.7k), so some
                // candidates are left out below; the window is filled from the rest (validation 2026-10-09)
                best = fieldSearch.candidates(query, step, window * 2, wait);
                used = step;
                if (best.total() >= window) {
                    break;
                }
            }
            if (best == null) {
                return null;
            }
            // only the candidates the Java check returns take a place in the window, so the FTS search fills the
            // places of the others instead of the window ending short; ratings are not filtered in SQL, so a candidate
            // below spec is handed to the ranker without a place, which excludes and counts it (DESIGN.md 9.3)
            boolean allowBelowSpec = prepared.request().allowBelowSpec();
            List<Part> parts = new ArrayList<>(window);
            List<Part> belowSpec = new ArrayList<>();
            for (Part part : best.parts()) {
                Part enriched = extractor.enrich(part);
                RankingService.Verdict verdict = PageCollector.Check.verdict(ranking, prepared.parsed(), enriched);
                if (verdict == RankingService.Verdict.CONSTRAINT) {
                    continue;
                }
                if (verdict == RankingService.Verdict.BELOW_SPEC && !allowBelowSpec) {
                    belowSpec.add(enriched);
                    continue;
                }
                parts.add(enriched);
                if (parts.size() >= window) {
                    break;
                }
            }
            log.debug("LCSC field query '{}': step {} of {}, {} candidates of {}", prepared.parsed().normalizedKey(),
                    used.index(), steps.size() - 1, parts.size(), best.total());
            return new Typed(parts, best.total(), used.index(), used.relaxed(), keywords(query, used),
                    List.copyOf(belowSpec));
        } catch (java.sql.SQLException | RuntimeException e) {
            log.warn("LCSC field query '{}' failed, using the FTS search: {}", prepared.parsed().normalizedKey(),
                    e.toString());
            return null;
        }
    }

    /** {@code fetched} with the below-spec candidates of the typed query added after its parts (once per part). */
    private static Fetched withBelowSpec(Fetched fetched, List<Part> belowSpec) {
        if (belowSpec.isEmpty()) {
            return fetched;
        }
        Set<String> seen = new java.util.HashSet<>();
        fetched.parts().forEach(p -> seen.add(p.distributorPartNumber()));
        List<Part> merged = new ArrayList<>(fetched.parts());
        belowSpec.stream().filter(p -> seen.add(p.distributorPartNumber())).forEach(merged::add);
        return merged.size() == fetched.parts().size() ? fetched : fetched.withParts(merged);
    }

    /** True when the unrelaxed step states a constraint of the request (not only stock and distributor). */
    static boolean constrains(FieldQuery query) {
        return query.step(0).predicates().stream().anyMatch(p -> p.kind() != null);
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
