package ro.alacrity.kina.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.config.KinaProperties.FieldIndexMode;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.metrics.FieldFallback;
import ro.alacrity.kina.metrics.KinaMetrics;
import ro.alacrity.kina.search.PageCollector.Check;
import ro.alacrity.kina.search.field.FieldQuery;
import ro.alacrity.kina.search.field.FieldQueryBuilder;
import ro.alacrity.kina.search.field.FieldSearchShadow;
import ro.alacrity.kina.search.field.PartIndexRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** The four {@link FieldIndexStrategy} beans, one per {@code kina.search.field-index.mode} (DESIGN.md 3.8 "Modes"). */
final class FieldIndexStrategies {

    private FieldIndexStrategies() {
    }

    /** {@code off}: the cached-search path; the index is written, never read. */
    @Component
    static final class Off implements FieldIndexStrategy {

        @Autowired private CachedSearchPath cached;
        @Autowired private KinaMetrics metrics = KinaMetrics.NOOP;

        @Override
        public FieldIndexMode mode() {
            return FieldIndexMode.OFF;
        }

        @Override
        public Fetched search(DistributorClient client, Prepared prepared, Progress progress,
                              DistributorBudget deadline) {
            metrics.fieldFallback(client.distributor().name(), FieldFallback.MODE);
            return cached.search(client, prepared, progress, deadline);
        }
    }

    /**
     * {@code shadow}: the cached-search path, then the field query compared with its result in the background (logs and
     * counters only); the result never changes.
     */
    @Component
    static final class Shadow implements FieldIndexStrategy {

        @Autowired private CachedSearchPath cached;
        @Autowired private FieldSearchShadow shadow;
        @Autowired private RankingService ranking;
        @Autowired private KinaMetrics metrics = KinaMetrics.NOOP;

        @Override
        public FieldIndexMode mode() {
            return FieldIndexMode.SHADOW;
        }

        @Override
        public Fetched search(DistributorClient client, Prepared prepared, Progress progress,
                              DistributorBudget deadline) {
            Distributor distributor = client.distributor();
            metrics.fieldFallback(distributor.name(), FieldFallback.MODE);
            Fetched searched = cached.search(client, prepared, progress, deadline);
            if (searched.error() == null) {
                Check check = Check.of(ranking, prepared);
                boolean allowBelowSpec = prepared.request().allowBelowSpec();
                shadow.observe(distributor, prepared.parsed(), ConstraintPolicy.of(ranking), searched.parts(),
                        part -> check.returnable(part, allowBelowSpec));
            }
            return searched;
        }
    }

    /**
     * {@code augment} (DESIGN.md 3.2 "Augment"): the cached-search path; on a cached list ({@code HIT} or
     * {@code PARTIAL}) the in-stock parts of the field query's first step that the list does not hold are added to it,
     * before the check and the ranking, so the cache is searched by field and not only by the lists of earlier
     * queries. Only parts the Java check would return are added; no distributor call is made; {@code total_results}
     * stays the distributor's figure and {@code fetched} reports the merged set. An incomplete index, a request the
     * index may not answer ({@link FieldRelaxation#readable}) or an SQL error adds nothing.
     */
    @Slf4j
    @Component
    static final class Augment implements FieldIndexStrategy {

        @Autowired private KinaProperties properties;
        @Autowired private CachedSearchPath cached;
        @Autowired private RankingService ranking;
        @Autowired private ParametricExtractor extractor;
        @Autowired private PartCacheRepository partCache;
        @Autowired private PartIndexRepository index;
        @Autowired private KinaMetrics metrics = KinaMetrics.NOOP;

        @Override
        public FieldIndexMode mode() {
            return FieldIndexMode.AUGMENT;
        }

        @Override
        public Fetched search(DistributorClient client, Prepared prepared, Progress progress,
                              DistributorBudget deadline) {
            Distributor distributor = client.distributor();
            metrics.fieldFallback(distributor.name(), FieldFallback.MODE);
            Fetched searched = cached.search(client, prepared, progress, deadline);
            if (searched.error() != null
                    || searched.cache() != CacheStatus.HIT && searched.cache() != CacheStatus.PARTIAL) {
                return searched;
            }
            ParsedQuery parsed = prepared.parsed();
            try {
                return augment(distributor, prepared, searched);
            } catch (RuntimeException e) {
                log.warn("Adding field candidates to the {} search '{}' failed: {}", distributor,
                        parsed.normalizedKey(), e.toString());
                metrics.fieldFallback(distributor.name(), FieldFallback.SQL_ERROR);
                return searched;
            }
        }

        private Fetched augment(Distributor distributor, Prepared prepared, Fetched searched) {
            if (!index.isComplete(distributor)) {
                return searched;
            }
            ParsedQuery parsed = prepared.parsed();
            KinaProperties.FieldIndex config = properties.search().fieldIndex();
            FieldQuery query = FieldQueryBuilder.build(parsed, ConstraintPolicy.of(ranking), distributor)
                    .withStaleBelow(config.minVersion());
            if (!FieldRelaxation.readable(config.requireStatedConstraint(), query, query.step(0), parsed)) {
                return searched;
            }
            Set<String> held = searched.parts().stream().map(Part::distributorPartNumber)
                    .collect(Collectors.toSet());
            List<String> missing = index.query(query, config.maxCandidates()).stream()
                    .map(PartIndexRepository.Hit::partNumber).filter(n -> !held.contains(n)).toList();
            if (missing.isEmpty()) {
                return searched;
            }
            Check check = Check.of(ranking, prepared);
            boolean allowBelowSpec = prepared.request().allowBelowSpec();
            Map<String, Part> found = partCache.findInStock(distributor, missing);
            List<Part> merged = new ArrayList<>(searched.parts());
            for (String number : missing) {
                Part part = found.get(number);
                if (part != null) {
                    Part enriched = extractor.enrich(part);
                    if (check.returnable(enriched, allowBelowSpec)) {
                        merged.add(enriched);
                    }
                }
            }
            return merged.size() == searched.parts().size() ? searched : searched.withParts(merged);
        }
    }

    /**
     * {@code on} (DESIGN.md 3.2 "Field-first flow"): the field-first flow; a request it cannot answer takes the
     * cached-search path (counted by reason). When the distributor failed and no step of the index found a part to
     * return: the expired cached list as the cached-search path serves it, else what the index holds (flagged with the
     * error), else the failure.
     */
    @Component
    static final class On implements FieldIndexStrategy {

        @Autowired private KinaProperties properties;
        @Autowired private FieldFirstSearch fieldFirst;
        @Autowired private CachedSearchPath cached;
        @Autowired private RankingService ranking;
        @Autowired private KinaMetrics metrics = KinaMetrics.NOOP;

        @Override
        public FieldIndexMode mode() {
            return FieldIndexMode.ON;
        }

        @Override
        public Fetched search(DistributorClient client, Prepared prepared, Progress progress,
                              DistributorBudget deadline) {
            Distributor distributor = client.distributor();
            FieldFirstSearch.Outcome outcome = fieldFirst.search(client, prepared, progress, deadline);
            if (outcome.failure() != null) {
                ParsedQuery parsed = prepared.parsed();
                String query = DistributorRetriever.plan(properties, ranking, distributor, prepared).query();
                Optional<Fetched> served = cached.readCachedSearch(distributor, parsed.normalizedKey())
                        .filter(c -> !c.partNumbers().isEmpty())
                        .flatMap(c -> cached.servedStale(distributor, parsed, query, c, outcome.failure()));
                if (served.isPresent()) {
                    Fetched stale = served.get();
                    return outcome.fetched() == null ? stale : stale.withFieldSteps(outcome.fetched().fieldSteps());
                }
                if (outcome.fetched() != null) {
                    return outcome.fetched();
                }
                throw outcome.failure();
            }
            if (outcome.fetched() != null) {
                return outcome.fetched();
            }
            metrics.fieldFallback(distributor.name(), outcome.legacyReason());
            return cached.search(client, prepared, progress, deadline);
        }
    }
}
