package ro.alacrity.kina.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.cache.CachedSearch;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.cache.PhraseJournalRepository;
import ro.alacrity.kina.cache.SearchCacheRepository;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.domain.ConstraintKind;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.metrics.KinaMetrics;
import ro.alacrity.kina.search.PageCollector.Check;
import ro.alacrity.kina.search.PageCollector.Collected;
import ro.alacrity.kina.search.field.FieldPredicate;
import ro.alacrity.kina.search.field.FieldQuery;
import ro.alacrity.kina.search.field.FieldQueryBuilder;
import ro.alacrity.kina.search.field.PartIndexRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The field-first search of Mouser and TME ({@code kina.search.field-index.mode=on}, DESIGN.md 3.2 "Field-first flow",
 * study 5.2). Per distributor, from the least to the most relaxed step of the {@link FieldQuery}:
 *
 * <ol>
 *   <li>run the field query of the step on {@code part_index} (a recall filter: the Java check decides) and add the
 *       parts this request already received live; when at least {@code max_results} of them pass the Java check and
 *       one is confirmed, answer;</li>
 *   <li>else look up the phrase of the step in the journal ({@link PhraseJournalRepository}); a fresh row means the
 *       distributor was already asked: no call;</li>
 *   <li>else call the distributor with the phrase (the paging rules, deadline and rate-limit retry of
 *       {@link PageCollector}), write the parts to the cache (which writes the index), record the phrase in the journal
 *       and query again;</li>
 *   <li>else relax one step and repeat. Stops at the first step with enough, at the end of the steps (the result is
 *       what there is) and when a call would exceed {@code max-live-calls-per-distributor}.</li>
 * </ol>
 *
 * <p>A request the flow cannot answer from the index returns an outcome that sends the caller to the cached-search path
 * ({@link Outcome#legacy}): {@code bypass_cache}, an index that is not complete for the distributor, a request whose
 * first step is only the family (too generic to answer from fields), an SQL error before any call. A distributor
 * failure without any candidate is returned as {@link Outcome#failure} (the caller serves an expired list or fails).
 */
@Slf4j
@Component
final class FieldFirstSearch {

    /** Parts of a part-number search stored in its cached search (the requested-part outcomes live on that row). */
    private static final int ROW_PARTS = 50;

    @Autowired private KinaProperties properties;
    @Autowired private PageCollector pages;
    @Autowired private RankingService ranking;
    @Autowired private ParametricExtractor extractor;
    @Autowired private PartCacheRepository partCache;
    @Autowired private SearchCacheRepository searchCache;
    @Autowired private PhraseJournalRepository journal;
    @Autowired private PartIndexRepository index;
    @Autowired private Clock clock;
    @Autowired private KinaMetrics metrics = KinaMetrics.NOOP;

    /**
     * What the flow made of a request: an answer, the reason to take the cached-search path, or a distributor failure
     * with nothing to serve.
     */
    record Outcome(Fetched fetched, String legacyReason, DistributorException failure) {

        static Outcome answered(Fetched fetched) {
            return new Outcome(fetched, null, null);
        }

        static Outcome legacy(String reason) {
            return new Outcome(null, reason, null);
        }

        static Outcome failure(DistributorException failure) {
            return new Outcome(null, null, failure);
        }
    }

    /** One relaxation step with the phrase that goes with it (null: no distributor phrase for the step). */
    private record Rung(FieldQuery.Step step, String phrase, int ladderStep) {

        List<String> relaxed() {
            return step.relaxed();
        }
    }

    /** The parts of a step: the index hits (most relevant first) and the ones received live. */
    private record Candidates(List<Part> indexed, List<Part> passing) {
    }

    /** An SQL failure of the field query. */
    private static final class FieldSqlException extends RuntimeException {
        FieldSqlException(Throwable cause) {
            super(cause);
        }
    }

    Outcome search(DistributorClient client, Prepared prepared, Progress progress, DistributorBudget deadline) {
        Distributor distributor = client.distributor();
        ParsedQuery parsed = prepared.parsed();
        if (prepared.request().bypassCache()) {
            return Outcome.legacy("bypass");
        }
        KinaProperties.FieldIndex config = properties.search().fieldIndex();
        FieldQuery query;
        try {
            if (!index.isComplete(distributor)) {
                return Outcome.legacy("incomplete");
            }
            query = FieldQueryBuilder.build(parsed, ConstraintPolicy.of(ranking), distributor,
                    prepared.request().allowBelowSpec()).withStaleBelow(config.minVersion());
        } catch (RuntimeException e) {
            log.warn("The field query of {} '{}' could not be built: {}", distributor, parsed.normalizedKey(),
                    e.toString());
            return Outcome.legacy("sql_error");
        }
        if (!selective(query.step(0), parsed)) {
            return Outcome.legacy("generic");
        }
        Run run = new Run(client, prepared, progress, deadline, query);
        try {
            return run.execute();
        } catch (FieldSqlException e) {
            log.warn("The field query of {} '{}' failed: {}", distributor, parsed.normalizedKey(),
                    e.getCause().toString());
            return run.calls == 0 ? Outcome.legacy("sql_error") : Outcome.answered(run.assemble(null));
        }
    }

    /**
     * True when a step holds a predicate the request states: free text, a part number or a constraint kind it names
     * (the family and the rules the family implies, such as the LED type of every LED request, do not count). A step
     * of only those returns every in-stock part of the family, which is no answer to the request.
     */
    static boolean selective(FieldQuery.Step step, ParsedQuery parsed) {
        return step.predicates().stream().anyMatch(p -> p instanceof FieldPredicate.Word
                || p instanceof FieldPredicate.Substring || p instanceof FieldPredicate.MpnPrefix
                || p.kind() != null && p.kind() != ConstraintKind.TYPE && p.kind().wanted(parsed) != null);
    }

    /** One search of one distributor: the state of the loop. */
    private final class Run {

        private final DistributorClient client;
        private final Distributor distributor;
        private final Prepared prepared;
        private final ParsedQuery parsed;
        private final Progress progress;
        private final DistributorBudget deadline;
        private final FieldQuery query;
        private final boolean allowBelowSpec;
        private final DistributorRetriever.Plan plan;
        private final List<Rung> rungs;
        private final String firstKey;
        private final int cap;

        /** Parts loaded from the cache and enriched, by distributor part number. */
        private final Map<String, Part> loaded = new HashMap<>();
        private final Map<String, RankingService.Verdict> verdicts = new HashMap<>();
        /** Parts received live in this request, by distributor part number. */
        private final Map<String, Part> live = new LinkedHashMap<>();
        private final Set<String> asked = new HashSet<>();
        private final Map<String, PhraseJournalRepository.Entry> consulted = new HashMap<>();
        private final Instant now;

        int calls;
        private boolean calledFirst;
        private boolean calledRelaxed;
        private int stepsTried;
        private Integer liveTotal;
        private int liveOutOfStock;
        private Rung served;
        private List<Part> indexed = List.of();
        private DistributorException failure;
        private Collected lastCollected;
        private Collected firstWithParts;
        private Rung lastCalled;
        private Rung firstWithPartsRung;

        Run(DistributorClient client, Prepared prepared, Progress progress, DistributorBudget deadline,
            FieldQuery query) {
            this.client = client;
            this.distributor = client.distributor();
            this.prepared = prepared;
            this.parsed = prepared.parsed();
            this.progress = progress;
            this.deadline = deadline;
            this.query = query;
            this.allowBelowSpec = prepared.request().allowBelowSpec();
            this.plan = DistributorRetriever.plan(properties, ranking, distributor, prepared);
            this.firstKey = DistributorPhraser.phraseKey(plan.query());
            this.rungs = rungs();
            this.cap = Math.max(1, properties.search().fieldIndex().maxLiveCallsPerDistributor());
            this.now = clock.instant();
        }

        /** The steps of the field query with the ladder phrase of each (study 5.2: the phrase of the step). */
        private List<Rung> rungs() {
            List<DistributorPhraser.Relaxation> ladder = DistributorPhraser.ladder(distributor, parsed, plan.query(),
                    ConstraintPolicy.of(ranking));
            List<Rung> out = new ArrayList<>();
            for (FieldQuery.Step step : query.steps()) {
                int at = -1;
                String phrase = null;
                if (step.index() == 0) {
                    phrase = plan.query();
                } else if (step.relaxed().isEmpty()) {
                    // the keywords are dropped: the minimal core when it words the request differently
                    for (int i = 0; i < ladder.size(); i++) {
                        DistributorPhraser.Relaxation r = ladder.get(i);
                        if (r.relaxed().isEmpty() && !DistributorPhraser.phraseKey(r.phrase()).equals(firstKey)) {
                            at = i;
                            phrase = r.phrase();
                            break;
                        }
                    }
                    phrase = phrase != null ? phrase : plan.query();
                } else {
                    Set<String> wanted = new java.util.HashSet<>(step.relaxed());
                    for (int i = 0; i < ladder.size() && phrase == null; i++) {
                        if (new java.util.HashSet<>(ladder.get(i).relaxed()).equals(wanted)) {
                            at = i;
                            phrase = ladder.get(i).phrase();
                        }
                    }
                    for (int i = 0; i < ladder.size() && phrase == null; i++) {
                        if (ladder.get(i).relaxed().containsAll(wanted)) {
                            at = i;
                            phrase = ladder.get(i).phrase();
                        }
                    }
                }
                out.add(new Rung(step, phrase, phrase == null || at < 0 ? 0 : at + 1));
            }
            return out;
        }

        Outcome execute() {
            for (Rung rung : rungs) {
                stepsTried++;
                progress.fieldSteps = stepsTried;
                served = rung;
                Candidates c = candidates(rung);
                if (enough(c)) {
                    break;
                }
                if (rung.phrase() == null || !asked.add(DistributorPhraser.phraseKey(rung.phrase()))) {
                    continue;
                }
                String key = DistributorPhraser.phraseKey(rung.phrase());
                Optional<PhraseJournalRepository.Entry> entry = lookup(key);
                if (entry.isPresent() && entry.get().isFresh(now, properties.cache().ttl(),
                        properties.cache().emptyResultTtl())) {
                    consulted.put(key, entry.get());
                    metrics.fieldJournalHit(distributor.name());
                    continue;
                }
                if (calls >= cap || deadline.remainingNanos() <= 0) {
                    log.info("{} '{}': no further call at step {} ({})", distributor, parsed.normalizedKey(),
                            rung.step().index(), calls >= cap ? "call cap " + cap : "deadline");
                    break;
                }
                if (!call(rung, key)) {
                    break;
                }
                if (enough(candidates(rung))) {
                    break;
                }
            }
            if (failure != null && indexed.isEmpty() && live.isEmpty()) {
                return Outcome.failure(failure);
            }
            Fetched fetched = assemble(failure);
            if (failure == null) {
                if (calls == 0) {
                    metrics.fieldServed(distributor.name());
                }
                writeSearchRow();
            }
            return Outcome.answered(fetched);
        }

        private Optional<PhraseJournalRepository.Entry> lookup(String key) {
            try {
                return journal.find(distributor, key);
            } catch (RuntimeException e) {
                log.warn("Reading the {} phrase journal failed: {}", distributor, e.toString());
                return Optional.empty();
            }
        }

        /** Asks the distributor with the phrase of {@code rung}; false when the call failed. */
        private boolean call(Rung rung, String key) {
            boolean first = rung.step().index() == 0;
            progress.fallbackQuery = fallbackOf(rung);
            progress.constraintsRelaxed = rung.relaxed();
            int outOfStockBefore = progress.outOfStock;
            Collected collected;
            try {
                collected = pages.collect(client, rung.phrase(), 0, plan.window(), plan.maxPages(), List.of(),
                        progress, deadline, plan.meets(), parsed.family());
            } catch (DistributorException e) {
                log.info("{} search '{}' at step {} failed: {}", distributor, rung.phrase(), rung.step().index(),
                        e.getMessage());
                failure = e;
                return false;
            }
            calls++;
            progress.fetchedLive = true;
            metrics.fieldLiveCall(distributor.name(), rung.step().index());
            if (first) {
                calledFirst = true;
            } else {
                calledRelaxed = true;
            }
            liveTotal = collected.totalResults();
            int outOfStock = progress.outOfStock - outOfStockBefore;
            liveOutOfStock += outOfStock;
            collected.all().forEach(p -> live.put(p.distributorPartNumber(), p));
            progress.parts = List.copyOf(merged());
            lastCollected = collected;
            lastCalled = rung;
            if (firstWithParts == null && !collected.all().isEmpty()) {
                firstWithParts = collected;
                firstWithPartsRung = rung;
            }
            boolean stored = storeParts(collected);
            if (stored) {
                try {
                    journal.record(new PhraseJournalRepository.Entry(distributor, key, rung.phrase(), now,
                            collected.totalResults(), collected.nextOffset(), collected.exhausted(), outOfStock,
                            collected.all().isEmpty(), rung.ladderStep(), parsed.normalizedKey()));
                } catch (RuntimeException e) {
                    log.warn("Recording {} phrase '{}' in the journal failed: {}", distributor, rung.phrase(),
                            e.toString());
                }
            }
            if (collected.error() != null) {
                // a later page failed: the parts of the earlier pages are kept, the error is reported
                failure = new DistributorException(distributor, errorKind(collected.error()), collected.error());
            }
            return collected.error() == null;
        }

        private boolean storeParts(Collected collected) {
            try {
                partCache.upsertAll(collected.fetched());
                return true;
            } catch (RuntimeException e) {
                log.warn("Caching {} results for '{}' failed: {}", distributor, parsed.normalizedKey(), e.toString());
                return false;
            }
        }

        /** The index hits of a step plus the live parts that pass the Java check and the step's ladder kinds. */
        private Candidates candidates(Rung rung) {
            if (selective(rung.step(), parsed)) {
                List<PartIndexRepository.Hit> hits;
                try {
                    hits = index.query(query, rung.step(), properties.search().fieldIndex().maxCandidates());
                } catch (RuntimeException e) {
                    throw new FieldSqlException(e);
                }
                load(hits);
                List<Part> list = new ArrayList<>(hits.size());
                for (PartIndexRepository.Hit h : hits) {
                    Part part = loaded.get(h.partNumber());
                    if (part != null) {
                        list.add(part);
                    }
                }
                indexed = list;
            }
            List<ConstraintKind> ladder = query.groups(FieldQuery.Role.L).stream()
                    .filter(g -> !rung.step().dropped().contains(g.name())).flatMap(g -> g.kinds().stream()).toList();
            Set<String> seen = new HashSet<>();
            List<Part> passing = new ArrayList<>();
            for (Part part : indexed) {
                if (seen.add(part.distributorPartNumber()) && returnable(part)) {
                    passing.add(part);
                }
            }
            for (Part part : live.values()) {
                if (seen.add(part.distributorPartNumber()) && returnable(part) && meetsLadder(part, ladder)) {
                    passing.add(part);
                }
            }
            return new Candidates(indexed, passing);
        }

        private void load(List<PartIndexRepository.Hit> hits) {
            List<String> missing = hits.stream().map(PartIndexRepository.Hit::partNumber)
                    .filter(n -> !loaded.containsKey(n)).toList();
            if (missing.isEmpty()) {
                return;
            }
            Map<String, Part> found;
            try {
                found = partCache.findInStock(distributor, missing);
            } catch (RuntimeException e) {
                throw new FieldSqlException(e);
            }
            found.forEach((n, p) -> loaded.put(n, extractor.enrich(p)));
        }

        /** At least {@code max_results} parts pass the Java check and one of them is confirmed. */
        private boolean enough(Candidates c) {
            return c.passing().size() >= prepared.maxResults() && c.passing().stream().anyMatch(this::confirmed);
        }

        private RankingService.Verdict verdict(Part part) {
            return verdicts.computeIfAbsent(part.distributorPartNumber(),
                    n -> Check.verdict(ranking, parsed, part));
        }

        private boolean returnable(Part part) {
            RankingService.Verdict v = verdict(part);
            return v != RankingService.Verdict.CONSTRAINT
                    && (allowBelowSpec || v != RankingService.Verdict.BELOW_SPEC);
        }

        /** Meets the request with every requested rating stated (a below-spec part counts when it is allowed). */
        private boolean confirmed(Part part) {
            RankingService.Verdict v = verdict(part);
            return v == RankingService.Verdict.MEETS || allowBelowSpec && v == RankingService.Verdict.BELOW_SPEC;
        }

        /** True when no ladder kind of {@code ladder} is a known mismatch of the part. */
        private boolean meetsLadder(Part part, List<ConstraintKind> ladder) {
            if (ladder.isEmpty()) {
                return true;
            }
            try {
                SearchMatchContext context = new SearchMatchContext(parsed, extractor.features(part));
                for (ConstraintKind kind : ladder) {
                    Double grade = kind.compare(context);
                    if (grade != null && grade < 0) {
                        return false;
                    }
                }
            } catch (RuntimeException e) {
                log.debug("Checking the ladder kinds of {} failed: {}", part.key(), e.toString());
            }
            return true;
        }

        /** The index hits of the last step and every part received live, without duplicates. */
        private List<Part> merged() {
            Map<String, Part> out = new LinkedHashMap<>();
            indexed.forEach(p -> out.putIfAbsent(p.distributorPartNumber(), p));
            live.forEach(out::putIfAbsent);
            return new ArrayList<>(out.values());
        }

        /** The phrase to report as {@code fallback_query}: that of a relaxed step when it differs from the first. */
        private String fallbackOf(Rung rung) {
            if (rung == null || rung.step().index() == 0 || rung.phrase() == null
                    || DistributorPhraser.phraseKey(rung.phrase()).equals(firstKey)) {
                return null;
            }
            return rung.phrase();
        }

        /** The result of the search so far; {@code error} is the failure to report (null: none). */
        Fetched assemble(DistributorException error) {
            List<Part> parts = merged();
            CacheStatus status = calls == 0 ? (error != null ? CacheStatus.STALE : CacheStatus.HIT)
                    : calledRelaxed ? CacheStatus.PARTIAL : CacheStatus.MISS;
            String key = served == null || served.phrase() == null ? null : DistributorPhraser.phraseKey(served.phrase());
            Integer total = liveTotal;
            Integer outOfStock = calls > 0 ? Integer.valueOf(liveOutOfStock) : null;
            if (calls == 0 && key != null) {
                PhraseJournalRepository.Entry entry = consulted.get(key);
                if (entry == null) {
                    entry = lookup(key).orElse(null);
                }
                if (entry != null) {
                    total = entry.rawTotal();
                    outOfStock = entry.outOfStock();
                }
            }
            if (total == null) {
                total = indexed.size();
            }
            Fetched fetched = new Fetched(distributor, parts, total, status,
                    error != null ? error.errorCode() : null, fallbackOf(served));
            return fetched.withOutOfStockMatches(outOfStock)
                    .withConstraintsRelaxed(served == null ? List.of() : served.relaxed())
                    .withFetchedLive(calls > 0).withFieldSteps(stepsTried);
        }

        /**
         * Keeps {@code cached_searches} as the cached-search path writes it (DESIGN.md 3.2: a rollback to the other
         * modes finds the list, an expired list can still be served when a distributor fails): the list of the last
         * phrase asked, as the cached-search path would have stored it. A search naming part numbers also gets a row
         * when it made no call, so the outcomes of their direct lookups have a row to live on.
         */
        private void writeSearchRow() {
            try {
                String queryKey = parsed.normalizedKey();
                if (calls > 0) {
                    Collected collected = lastCollected.meeting() > 0 || lastCollected.all().isEmpty() ? lastCollected
                            : firstWithParts != null ? firstWithParts : lastCollected;
                    Rung rung = collected == lastCollected ? lastCalled : firstWithPartsRung;
                    if (!collected.all().isEmpty() && collected.meeting() == 0) {
                        searchCache.delete(distributor, queryKey);
                        return;
                    }
                    searchCache.upsert(new CachedSearch(distributor, queryKey, collected.totalResults(),
                            collected.all().stream().map(Part::distributorPartNumber).toList(), collected.exhausted(),
                            now, collected.nextOffset(), fallbackOf(rung), liveOutOfStock, rung.relaxed(), null));
                } else if (parsed.namesPartNumber() && searchCache.find(distributor, queryKey).isEmpty()) {
                    List<String> numbers = merged().stream().limit(ROW_PARTS).map(Part::distributorPartNumber)
                            .toList();
                    searchCache.upsert(new CachedSearch(distributor, queryKey, null, numbers, false, now, null,
                            fallbackOf(served), null, served == null ? List.of() : served.relaxed(), null));
                }
            } catch (RuntimeException e) {
                log.warn("Caching the {} search '{}' failed: {}", distributor, parsed.normalizedKey(), e.toString());
            }
        }
    }

    private static DistributorException.Kind errorKind(String code) {
        for (DistributorException.Kind kind : DistributorException.Kind.values()) {
            if (kind.code().equals(code)) {
                return kind;
            }
        }
        return DistributorException.Kind.UNAVAILABLE;
    }
}
