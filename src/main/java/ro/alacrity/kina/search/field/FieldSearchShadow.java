package ro.alacrity.kina.search.field;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.config.KinaProperties.FieldIndexMode;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.metrics.KinaMetrics;
import ro.alacrity.kina.search.ConstraintPolicy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The shadow mode of the field index ({@code kina.search.field-index.mode=shadow}, DESIGN.md 3.8, study phase 0): next
 * to the cached-search path of one distributor, the field query runs on {@code part_index} and its answer is compared
 * with what the Java path returned. Only logs (DEBUG, WARN for a dropped part) and counters; the search result never
 * changes. Measured: the candidates of the unrelaxed step, their overlap with the returned parts, and the returned
 * parts the Java check keeps that the most relaxed step ({@code H + R}) would drop (must be 0, else the SQL filter is
 * stricter than the judge, risk R1). A distributor whose index is incomplete is counted {@code incomplete} and not
 * compared.
 */
@Slf4j
@Component
public class FieldSearchShadow {

    @Autowired private KinaProperties properties;
    @Autowired private PartIndexRepository index;
    @Autowired private KinaMetrics metrics = KinaMetrics.NOOP;

    /**
     * What one shadow comparison found.
     *
     * @param outcome    {@code ok}, {@code dropped}, {@code incomplete} or {@code failed}
     * @param candidates rows of the unrelaxed step
     * @param overlap    returned parts among them
     * @param returnable returned parts the Java check keeps
     * @param dropped    returnable parts the most relaxed step does not return (part numbers)
     * @param unindexed  returned parts without an index row (not compared)
     */
    public record Report(String outcome, int candidates, int overlap, int returnable, List<String> dropped,
                         int unindexed) {
    }

    /** True when the shadow comparison runs ({@code mode=shadow}). */
    public boolean enabled() {
        return properties.search().fieldIndex().mode() == FieldIndexMode.SHADOW;
    }

    /**
     * Compares in the background when {@link #enabled()}; never throws, never delays the search.
     *
     * @param returned   the parts the Java path holds for the distributor
     * @param returnable the Java check ({@code PageCollector.Check.returnable} for the request)
     */
    public void observe(Distributor distributor, ParsedQuery parsed, ConstraintPolicy policy, boolean allowBelowSpec,
                        List<Part> returned, Predicate<Part> returnable) {
        if (!enabled()) {
            return;
        }
        List<Part> parts = List.copyOf(returned);
        Thread.ofVirtual().name("field-shadow").start(() -> compare(distributor, parsed, policy, allowBelowSpec, parts,
                returnable));
    }

    /** One comparison, now (tests and {@link #observe}). Never throws. */
    public Report compare(Distributor distributor, ParsedQuery parsed, ConstraintPolicy policy, boolean allowBelowSpec,
                          List<Part> returned, Predicate<Part> returnable) {
        Report report;
        try {
            report = run(distributor, parsed, policy, allowBelowSpec, returned, returnable);
        } catch (RuntimeException e) {
            log.debug("Shadow field query of {} '{}' failed: {}", distributor, parsed.normalizedKey(), e.toString());
            report = new Report("failed", 0, 0, 0, List.of(), 0);
        }
        metrics.fieldShadow(distributor.name(), report.outcome(), report.candidates(), report.dropped().size());
        if (!report.dropped().isEmpty()) {
            log.warn("Shadow field query of {} '{}' would drop {} returnable parts: {}", distributor,
                    parsed.normalizedKey(), report.dropped().size(), report.dropped());
        } else {
            log.debug("Shadow field query of {} '{}': {}", distributor, parsed.normalizedKey(), report);
        }
        return report;
    }

    private Report run(Distributor distributor, ParsedQuery parsed, ConstraintPolicy policy, boolean allowBelowSpec,
                       List<Part> returned, Predicate<Part> returnable) {
        if (!index.isComplete(distributor)) {
            return new Report("incomplete", 0, 0, 0, List.of(), 0);
        }
        KinaProperties.FieldIndex config = properties.search().fieldIndex();
        FieldQuery query = FieldQueryBuilder.build(parsed, policy, distributor, allowBelowSpec)
                .withStaleBelow(config.minVersion());
        List<PartIndexRepository.Hit> hits = index.query(query, config.maxCandidates());
        Set<String> candidates = new HashSet<>();
        hits.forEach(h -> candidates.add(h.partNumber()));
        List<String> numbers = returned.stream().map(Part::distributorPartNumber).distinct().toList();
        int overlap = (int) numbers.stream().filter(candidates::contains).count();

        Set<String> indexed = index.indexed(distributor, numbers);
        List<String> kept = new ArrayList<>();
        int unindexed = 0;
        for (Part part : returned) {
            if (!indexed.contains(part.distributorPartNumber())) {
                unindexed++;
            } else if (returnable.test(part)) {
                kept.add(part.distributorPartNumber());
            }
        }
        Set<String> relaxed = new HashSet<>();
        if (!kept.isEmpty()) {
            index.query(query, query.relaxed(), kept.size(), kept).forEach(h -> relaxed.add(h.partNumber()));
        }
        List<String> dropped = kept.stream().filter(n -> !relaxed.contains(n)).toList();
        return new Report(dropped.isEmpty() ? "ok" : "dropped", hits.size(), overlap, kept.size(), dropped, unindexed);
    }
}
