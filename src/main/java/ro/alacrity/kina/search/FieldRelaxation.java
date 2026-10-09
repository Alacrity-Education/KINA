package ro.alacrity.kina.search;

import lombok.experimental.UtilityClass;
import ro.alacrity.kina.domain.ConstraintKind;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.search.field.FieldPredicate;
import ro.alacrity.kina.search.field.FieldQuery;

import java.util.Collection;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The relaxation of a field query, one definition for Mouser and TME ({@link FieldFirstSearch}) and for LCSC
 * ({@link LcscRetriever}) (DESIGN.md 3.8 "Relaxation", review B4): the steps of {@link FieldQuery#steps()} from the
 * least to the most relaxed, each read in chunks of candidates, until a step has <i>enough</i>; what happens between
 * steps (a distributor call, nothing) is the caller's. Also the one definition of a step the index may answer
 * ({@link #readable}, {@code require-stated-constraint}) and of the candidates loaded at a time ({@link #chunk}).
 */
@UtilityClass
class FieldRelaxation {

    /** The fewest candidates loaded and checked at a time. */
    static final int MIN_CHUNK = 20;

    /** What the loop does after a step that was not enough. */
    enum Next {
        /** Read the next, more relaxed step. */
        RELAX,
        /** Read the same step again (a distributor call added parts). */
        AGAIN,
        /** End the loop (the call cap or the deadline). */
        STOP
    }

    /** What a caller does at one step. */
    interface Steps {

        /** Reads the candidates of {@code step}; true when they are enough. */
        boolean read(FieldQuery.Step step);

        /** After a read of {@code step} that was not enough: {@link Next}. */
        Next notEnough(FieldQuery.Step step);
    }

    /**
     * The end of the loop.
     *
     * @param step   the step that answered or the last step read; null when there was none
     * @param enough the step had enough
     */
    record Result(FieldQuery.Step step, boolean enough) {
    }

    /** Reads {@code steps} in order until one has enough, or the caller stops. */
    static Result relax(List<FieldQuery.Step> steps, Steps reader) {
        FieldQuery.Step last = null;
        for (FieldQuery.Step step : steps) {
            last = step;
            while (true) {
                if (reader.read(step)) {
                    return new Result(step, true);
                }
                Next next = reader.notEnough(step);
                if (next == Next.STOP) {
                    return new Result(step, false);
                }
                if (next == Next.RELAX) {
                    break;
                }
            }
        }
        return new Result(last, false);
    }

    /**
     * Enough candidates for a request of {@code target} results: at least {@code target} of them pass the Java check
     * ({@code passing}) and at least one of them is {@code confirmed} (meets the request with every requested rating
     * stated).
     */
    static boolean enough(Collection<Part> passing, int target, Predicate<Part> confirmed) {
        return passing.size() >= target && passing.stream().anyMatch(confirmed);
    }

    /** The candidates loaded, enriched and checked at a time for {@code target} results: {@code max(2 x target, 20)}. */
    static int chunk(int target) {
        return Math.max(2 * target, MIN_CHUNK);
    }

    /** One chunk of candidates: the parts, and whether the source has no more after them. */
    record Chunk(List<Part> parts, boolean exhausted) {
    }

    /** Where the candidates of a step come from, in order: the rows from {@code offset}, at most {@code size}. */
    @FunctionalInterface
    interface Source {
        Chunk next(int offset, int size);
    }

    /**
     * Reads the candidates of one step in chunks of {@code chunk}, at most {@code limit} rows (the SQL recall limit,
     * {@code kina.search.field-index.max-candidates}), handing each part to {@code offer}, until {@code full} or the
     * source is exhausted; nothing is read when the step is full already.
     */
    static void readChunks(Source source, int chunk, int limit, Consumer<Part> offer, BooleanSupplier full) {
        int offset = 0;
        while (!full.getAsBoolean()) {
            int size = Math.min(chunk, limit - offset);
            if (size <= 0) {
                return;
            }
            Chunk next = source.next(offset, size);
            next.parts().forEach(offer);
            if (next.exhausted()) {
                return;
            }
            offset += size;
        }
    }

    /**
     * True when a step states a constraint of the request beyond its family: a constraint kind the request names
     * (the family and the rules the family implies, such as the LED type of every LED request, do not count) or a
     * requested rating ({@code mosfet 60V}: it never filters, but it orders the candidates, the parts that meet it
     * first). The LCSC typed path runs only for such a request: free text alone is what the FTS5 search and its BM25
     * order are for.
     */
    static boolean statesConstraint(FieldQuery query, FieldQuery.Step step, ParsedQuery parsed) {
        return !query.ratings().isEmpty() || step.predicates().stream()
                .anyMatch(p -> p.kind() != null && p.kind() != ConstraintKind.TYPE && p.kind().wanted(parsed) != null);
    }

    /**
     * True when a step is <i>selective</i>: it states a constraint ({@link #statesConstraint}), free text or a part
     * number. A step of the family alone returns every in-stock part of the family, which is no answer to the request.
     */
    static boolean selective(FieldQuery query, FieldQuery.Step step, ParsedQuery parsed) {
        return statesConstraint(query, step, parsed) || step.predicates().stream()
                .anyMatch(p -> p instanceof FieldPredicate.Word || p instanceof FieldPredicate.Substring
                        || p instanceof FieldPredicate.MpnPrefix);
    }

    /**
     * True when the index may answer {@code step}: always when {@code requireStatedConstraint}
     * ({@code kina.search.field-index.require-stated-constraint}) is off, else only when the step is
     * {@link #selective}.
     */
    static boolean readable(boolean requireStatedConstraint, FieldQuery query, FieldQuery.Step step,
                            ParsedQuery parsed) {
        return !requireStatedConstraint || selective(query, step, parsed);
    }
}
