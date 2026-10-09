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
import ro.alacrity.kina.metrics.FieldFallback;
import ro.alacrity.kina.metrics.KinaMetrics;
import ro.alacrity.kina.search.PageCollector.Check;
import ro.alacrity.kina.search.PageCollector.Collected;
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
import java.util.function.Predicate;

/**
 * The field-first search of Mouser and TME ({@code kina.search.field-index.mode=on}, DESIGN.md 3.2 "Field-first flow",
 * study 5.2). Per distributor, the steps of the {@link FieldQuery} from the least to the most relaxed, in the one
 * relaxation loop of the field index ({@link FieldRelaxation}):
 *
 * <ol>
 *   <li>read the step: its field query on {@code part_index} (a recall filter: the Java check decides) gives up to
 *       {@code max-candidates} keys, loaded, enriched and checked in chunks ({@link FieldRelaxation#chunk}) until
 *       enough; the parts of the request's fresh cached list (what the cached-search path would serve) and the parts
 *       this request already received live join them; when at least {@code max_results} pass the Java check and one is
 *       confirmed, answer;</li>
 *   <li>else look up the phrase of the step ({@link DistributorPhraser#phraseFor}) in the journal
 *       ({@link PhraseJournalRepository}); a fresh row means the distributor was already asked: relax;</li>
 *   <li>else call the distributor with the phrase (the paging rules, deadline and rate-limit retry of
 *       {@link PageCollector}), write the parts to the cache (which writes the index), record the phrase in the journal
 *       and read the step again;</li>
 *   <li>stop at the first step with enough, at the end of the steps (the result is what there is) and when a call would
 *       exceed {@code max-live-calls-per-distributor} or the deadline.</li>
 * </ol>
 *
 * <p>A request the flow cannot answer from the index returns an outcome that sends the caller to the cached-search path
 * ({@link Outcome#legacy}): {@code bypass_cache}, an index that is not complete for the distributor, a request whose
 * first step is not selective (too generic to answer from fields; {@link FieldRelaxation#readable}), an SQL error or a
 * journal read failure before any call.
 *
 * <p>After a failed call no further call is made: the request's cached list (expired or not) joins the candidates and
 * the remaining steps are read from the index. When no step found a part the Java check returns, the outcome is a
 * {@link Outcome#failure} (the caller serves the expired list as the cached-search path does, else what the index
 * holds with the error, else fails).
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
    record Outcome(Fetched fetched, FieldFallback legacyReason, DistributorException failure) {

        static Outcome answered(Fetched fetched) {
            return new Outcome(fetched, null, null);
        }

        static Outcome legacy(FieldFallback reason) {
            return new Outcome(null, reason, null);
        }

        static Outcome failure(DistributorException failure) {
            return new Outcome(null, null, failure);
        }

        /**
         * The distributor failed and no step found a part the Java check returns: the caller serves the request's
         * expired cached list when there is one, else {@code fetched} (what the index holds, flagged with the error).
         */
        static Outcome failure(DistributorException failure, Fetched fetched) {
            return new Outcome(fetched, null, failure);
        }
    }

    /** One relaxation step with the phrase the ladder declares for it (null: no distributor phrase for the step). */
    private record Rung(FieldQuery.Step step, String phrase, int ladderStep) {

        List<String> relaxed() {
            return step.relaxed();
        }
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
            return Outcome.legacy(FieldFallback.BYPASS);
        }
        KinaProperties.FieldIndex config = properties.search().fieldIndex();
        FieldQuery query;
        try {
            if (!index.isComplete(distributor)) {
                return Outcome.legacy(FieldFallback.INCOMPLETE);
            }
            query = FieldQueryBuilder.build(parsed, ConstraintPolicy.of(ranking), distributor)
                    .withStaleBelow(config.minVersion());
        } catch (RuntimeException e) {
            log.warn("The field query of {} '{}' could not be built: {}", distributor, parsed.normalizedKey(),
                    e.toString());
            return Outcome.legacy(FieldFallback.SQL_ERROR);
        }
        if (!FieldRelaxation.readable(config.requireStatedConstraint(), query, query.step(0), parsed)) {
            return Outcome.legacy(FieldFallback.GENERIC);
        }
        Run run = new Run(client, prepared, progress, deadline, query);
        try {
            return run.execute();
        } catch (FieldSqlException e) {
            log.warn("The field query of {} '{}' failed: {}", distributor, parsed.normalizedKey(),
                    e.getCause().toString());
            return run.calls == 0 ? Outcome.legacy(FieldFallback.SQL_ERROR) : Outcome.answered(run.assemble(null));
        }
    }

    /** One search of one distributor: the state of the loop and its steps ({@link FieldRelaxation.Steps}). */
    private final class Run implements FieldRelaxation.Steps {

        private final DistributorClient client;
        private final Distributor distributor;
        private final ParsedQuery parsed;
        private final Progress progress;
        private final DistributorBudget deadline;
        private final FieldQuery query;
        private final boolean allowBelowSpec;
        private final DistributorRetriever.Plan plan;
        private final Check check;
        private final Map<Integer, Rung> rungs = new HashMap<>();
        private final String firstKey;
        private final int cap;
        private final int target;
        private final int chunk;
        private final int recall;
        private final boolean requireStated;

        /** Parts loaded from the cache and enriched (or received live, the newer copy), by distributor part number. */
        private final Map<String, Part> loaded = new HashMap<>();
        /** Parts received live in this request, by distributor part number. */
        private final Map<String, Part> live = new LinkedHashMap<>();
        /** Parts of the request's fresh cached list ({@code cached_searches}), by distributor part number. */
        private final Map<String, Part> listed = new LinkedHashMap<>();
        private final Set<String> asked = new HashSet<>();
        private final Map<String, PhraseJournalRepository.Entry> consulted = new HashMap<>();
        private final Instant now;

        int calls;
        private boolean calledRelaxed;
        /** A step had a part the Java check returns. */
        private boolean found;
        private boolean expiredLoaded;
        private int stepsTried;
        private Integer liveTotal;
        private int liveOutOfStock;
        private Rung served;
        /** The index candidates of the last step read, loaded and in index order. */
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
            this.parsed = prepared.parsed();
            this.progress = progress;
            this.deadline = deadline;
            this.query = query;
            this.allowBelowSpec = prepared.request().allowBelowSpec();
            this.plan = DistributorRetriever.plan(properties, ranking, distributor, prepared);
            this.check = plan.meets();
            this.firstKey = DistributorPhraser.phraseKey(plan.query());
            KinaProperties.FieldIndex config = properties.search().fieldIndex();
            this.cap = Math.max(1, config.maxLiveCallsPerDistributor());
            this.target = prepared.maxResults();
            this.chunk = FieldRelaxation.chunk(target);
            this.recall = config.maxCandidates();
            this.requireStated = config.requireStatedConstraint();
            this.now = clock.instant();
        }

        /** The step with the phrase the ladder declares for it (study 5.2: the phrase of the step). */
        private Rung rung(FieldQuery.Step step) {
            return rungs.computeIfAbsent(step.index(), i -> {
                DistributorPhraser.StepPhrase phrase = DistributorPhraser.phraseFor(distributor, parsed, plan.query(),
                        ConstraintPolicy.of(ranking), step.dropped().contains(FieldQuery.Role.K.name()),
                        step.relaxed());
                return new Rung(step, phrase.phrase(), phrase.ladderStep());
            });
        }

        Outcome execute() {
            loadCachedList();
            FieldRelaxation.relax(query.steps(), this);
            if (failure != null && indexed.isEmpty() && listed.isEmpty() && live.isEmpty()) {
                return Outcome.failure(failure);
            }
            if (failure != null && !found) {
                // no step found a part to return: the expired cached list as the cached-search path serves it
                return Outcome.failure(failure, assemble(failure));
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

        /**
         * Reads a step: the index hits (keys only, at most {@code max-candidates}), loaded, enriched and checked in
         * chunks until enough (review B2: the first chunk always, the next ones only while short), then the cached
         * list and the parts received live.
         */
        @Override
        public boolean read(FieldQuery.Step step) {
            Rung rung = rung(step);
            served = rung;
            stepsTried = Math.max(stepsTried, step.index() + 1);
            progress.fieldSteps = stepsTried;
            List<ConstraintKind> ladder = query.groups(FieldQuery.Role.L).stream()
                    .filter(g -> !step.dropped().contains(g.name())).flatMap(g -> g.kinds().stream()).toList();
            if (FieldRelaxation.readable(requireStated, query, step, parsed)) {
                List<PartIndexRepository.Hit> hits;
                try {
                    hits = index.query(query, step, recall);
                } catch (RuntimeException e) {
                    throw new FieldSqlException(e);
                }
                List<Part> read = new ArrayList<>(Math.min(hits.size(), chunk));
                FieldRelaxation.readChunks((offset, size) -> {
                    List<PartIndexRepository.Hit> slice = hits.subList(Math.min(offset, hits.size()),
                            Math.min(offset + size, hits.size()));
                    return new FieldRelaxation.Chunk(load(slice), offset + size >= hits.size());
                }, chunk, recall, read::add, () -> enough(passing(read, ladder)));
                indexed = read;
            }
            List<Part> passing = passing(indexed, ladder);
            found |= !passing.isEmpty();
            return enough(passing);
        }

        /**
         * The step was short: ask the distributor with its phrase unless the journal knows it, it was asked in this
         * request, a call failed, the call cap or the deadline is reached.
         */
        @Override
        public FieldRelaxation.Next notEnough(FieldQuery.Step step) {
            Rung rung = rung(step);
            if (failure != null) {
                // the distributor is failing: the relaxed steps are read from the index, never asked
                return FieldRelaxation.Next.RELAX;
            }
            if (rung.phrase() == null || !asked.add(DistributorPhraser.phraseKey(rung.phrase()))) {
                return FieldRelaxation.Next.RELAX;
            }
            String key = DistributorPhraser.phraseKey(rung.phrase());
            Optional<PhraseJournalRepository.Entry> entry = decide(key);
            if (entry.isPresent() && entry.get().isFresh(now, properties.cache().ttl(),
                    properties.cache().emptyResultTtl())) {
                consulted.put(key, entry.get());
                metrics.fieldJournalHit(distributor.name());
                return FieldRelaxation.Next.RELAX;
            }
            if (calls >= cap || deadline.remainingNanos() <= 0) {
                log.info("{} '{}': no further call at step {} ({})", distributor, parsed.normalizedKey(),
                        step.index(), calls >= cap ? "call cap " + cap : "deadline");
                return FieldRelaxation.Next.STOP;
            }
            if (!call(rung, key)) {
                // no further call; the parts of the expired cached list join the candidates, as the
                // cached-search path serves that list when its call fails
                loadExpiredList();
            }
            return FieldRelaxation.Next.AGAIN;
        }

        /**
         * The candidates that pass the Java check: the index candidates read so far, then the cached list and the parts
         * received live (these also must not miss a ladder kind the step keeps), each part once.
         */
        private List<Part> passing(List<Part> candidates, List<ConstraintKind> ladder) {
            Set<String> seen = new HashSet<>();
            List<Part> out = new ArrayList<>();
            for (Part part : candidates) {
                if (seen.add(part.distributorPartNumber()) && check.returnable(part, allowBelowSpec)) {
                    out.add(part);
                }
            }
            for (Map<String, Part> received : List.of(listed, live)) {
                for (Part part : received.values()) {
                    if (seen.add(part.distributorPartNumber()) && check.returnable(part, allowBelowSpec)
                            && meetsLadder(part, ladder)) {
                        out.add(part);
                    }
                }
            }
            return out;
        }

        private boolean enough(List<Part> passing) {
            Predicate<Part> confirmed = p -> check.confirmed(p, allowBelowSpec);
            return FieldRelaxation.enough(passing, target, confirmed);
        }

        /** The parts of {@code hits}, loaded once per request and enriched, in hit order. */
        private List<Part> load(List<PartIndexRepository.Hit> hits) {
            List<String> missing = hits.stream().map(PartIndexRepository.Hit::partNumber)
                    .filter(n -> !loaded.containsKey(n)).toList();
            if (!missing.isEmpty()) {
                Map<String, Part> found;
                try {
                    found = partCache.findInStock(distributor, missing);
                } catch (RuntimeException e) {
                    throw new FieldSqlException(e);
                }
                found.forEach((n, p) -> loaded.put(n, extractor.enrich(p)));
            }
            List<Part> out = new ArrayList<>(hits.size());
            for (PartIndexRepository.Hit h : hits) {
                Part part = loaded.get(h.partNumber());
                if (part != null) {
                    out.add(part);
                }
            }
            return out;
        }

        /**
         * The journal row that decides whether the phrase is asked. A read failure is an SQL failure of the flow
         * (before any call: the cached-search path, reason {@code sql_error}), never a reason to spend a call.
         */
        private Optional<PhraseJournalRepository.Entry> decide(String key) {
            try {
                return journal.find(distributor, key);
            } catch (RuntimeException e) {
                throw new FieldSqlException(e);
            }
        }

        /** The journal row for reporting the distributor's figures; a read failure only loses them. */
        private Optional<PhraseJournalRepository.Entry> lookup(String key) {
            try {
                return journal.find(distributor, key);
            } catch (RuntimeException e) {
                log.warn("Reading the {} phrase journal failed: {}", distributor, e.toString());
                return Optional.empty();
            }
        }

        /**
         * The parts of the request's cached list while it is fresh: what the cached-search path would serve for the
         * request (DESIGN.md 3.2 "Field-first flow"). The distributor's answer to the request's phrase can hold parts
         * the field query does not select (a keyword match for a part number that is not in the cache, an
         * accessory), so the field path serves at least what the cached-search path serves. A read failure is
         * logged and only loses them.
         */
        private void loadCachedList() {
            Instant freshSince = now.minus(properties.cache().ttl());
            Instant emptyFreshSince = now.minus(properties.cache().emptyResultTtl());
            loadList("cached", c -> CachedSearchPath.isFresh(c, freshSince, emptyFreshSince));
        }

        /**
         * After a failed call: the parts of the request's cached list even when it has expired (the list the
         * cached-search path serves when its call fails), once. A read failure only loses them.
         */
        private void loadExpiredList() {
            if (expiredLoaded) {
                return;
            }
            expiredLoaded = true;
            loadList("expired", c -> true);
        }

        private void loadList(String what, Predicate<CachedSearch> accept) {
            try {
                Optional<CachedSearch> cached = searchCache.find(distributor, parsed.normalizedKey()).filter(accept);
                if (cached.isEmpty() || cached.get().partNumbers().isEmpty()) {
                    return;
                }
                List<String> numbers = cached.get().partNumbers();
                Map<String, Part> found = partCache.findInStock(distributor, numbers);
                for (String number : numbers) {
                    Part part = found.get(number);
                    if (part != null) {
                        listed.putIfAbsent(number, loaded.computeIfAbsent(number, n -> extractor.enrich(part)));
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Reading the {} {} list of '{}' failed: {}", what, distributor, parsed.normalizedKey(),
                        e.toString());
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
                        progress, deadline, check, parsed.family());
            } catch (DistributorException e) {
                log.info("{} search '{}' at step {} failed: {}", distributor, rung.phrase(), rung.step().index(),
                        e.getMessage());
                failure = e;
                return false;
            }
            calls++;
            progress.fetchedLive = true;
            metrics.fieldLiveCall(distributor.name(), rung.step().index());
            if (!first) {
                calledRelaxed = true;
            }
            liveTotal = collected.totalResults();
            int outOfStock = progress.outOfStock - outOfStockBefore;
            liveOutOfStock += outOfStock;
            collected.all().forEach(p -> live.put(p.distributorPartNumber(), p));
            // the live copy (fresh stock) replaces the cached one for the index hits too (review B17)
            loaded.putAll(live);
            progress.parts = List.copyOf(merged());
            lastCollected = collected;
            lastCalled = rung;
            if (firstWithParts == null && !collected.all().isEmpty()) {
                firstWithParts = collected;
                firstWithPartsRung = rung;
            }
            boolean stored = storeParts(collected);
            // a failed later page keeps the parts of the earlier pages, but the phrase was not answered: it is not
            // journaled, so the next request asks it again (DESIGN.md 3.2: a failed call writes nothing)
            if (stored && collected.error() == null) {
                try {
                    // asked when the call answered, not when the request started (a rate-limit wait may lie between)
                    journal.record(new PhraseJournalRepository.Entry(distributor, key, rung.phrase(), clock.instant(),
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

        /**
         * Every part received live, the cached list and the index candidates read at the last step, without
         * duplicates, in that order: the ranking stage checks at most {@code max-candidates} parts per distributor
         * ({@code RankingService.capped}), so a cut only ever drops index candidates.
         */
        private List<Part> merged() {
            Map<String, Part> out = new LinkedHashMap<>();
            live.forEach(out::putIfAbsent);
            listed.forEach(out::putIfAbsent);
            indexed.forEach(p -> out.putIfAbsent(p.distributorPartNumber(), p));
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
            // a failure with no call answered: stale; a failure after a call answered (a later page, a relaxed
            // phrase): partial, with the error (DESIGN.md 3.2)
            CacheStatus status = calls == 0 ? (error != null ? CacheStatus.STALE : CacheStatus.HIT)
                    : error != null || calledRelaxed ? CacheStatus.PARTIAL : CacheStatus.MISS;
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
