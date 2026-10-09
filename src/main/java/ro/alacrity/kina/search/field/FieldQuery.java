package ro.alacrity.kina.search.field;

import ro.alacrity.kina.domain.ConstraintKind;
import ro.alacrity.kina.domain.Distributor;

import java.util.ArrayList;
import java.util.List;

/**
 * A request as ordered predicate groups over {@code part_index} (DESIGN.md 3.8), built by {@link FieldQueryBuilder}
 * and rendered by {@link PostgresFieldSql} or {@link SqliteFieldSql}. Dialect independent.
 *
 * <p>Groups: {@link Role#H} (never relaxed: the always-on rules, the kinds the request's family makes hard),
 * {@link Role#R} (the stated ratings: never a filter, they only order the candidates, confirmed first; the Java check
 * excludes and counts the parts below spec), {@link Role#L} (one group per stated ladder kind, in {@code @Relax} order),
 * {@link Role#K} (the free-text keywords and requested part numbers, all must match) and {@link Role#S} (the stated soft
 * kinds, such as a connector's rows: never a filter, they only order the candidates). The relaxation drops whole groups
 * ({@link #steps()}): first {@code K}, then {@code L1}, {@code L1, L2}...; {@code H} is never dropped.
 *
 * @param distributor the distributor, null for every distributor
 * @param groups      the groups in order {@code H, R, L1..Ln, K, S}; empty groups are left out (except {@code H})
 * @param staleBelow  rows indexed by an extractor older than this ({@code extractor_version < staleBelow}) are kept by
 *                    every predicate but the family and the always-on rules; 0 for none
 */
public record FieldQuery(Distributor distributor, List<Group> groups, int staleBelow) {

    public FieldQuery {
        groups = List.copyOf(groups);
    }

    /** The role of a group. */
    public enum Role {
        /** Hard: never relaxed. */
        H,
        /**
         * Ratings ({@code BELOW_SPEC} kinds): never in a step's filter, so a part below spec reaches the Java check,
         * which excludes and counts it ({@code excluded_below_spec}) or, with {@code allow_below_spec}, keeps it
         * flagged. They order the candidates: a part that states every requested rating and meets it first.
         */
        R,
        /** One ladder kind: dropped in ladder order. */
        L,
        /** Free text: dropped first. */
        K,
        /**
         * Soft kinds: never in a step's filter. A part that states a matching value ranks before one that does not
         * (the order of the candidates, so a {@code max-candidates} cut keeps the better ones).
         */
        S;

        /** True for the groups that only order the candidates and are never in a step's filter. */
        public boolean orderOnly() {
            return this == R || this == S;
        }
    }

    /**
     * One group of predicates.
     *
     * @param name {@code H}, {@code R}, {@code L1}... or {@code K}
     * @param kinds the constraint kinds of the group, in check order
     */
    public record Group(Role role, String name, List<ConstraintKind> kinds, List<FieldPredicate> predicates) {

        public Group {
            kinds = List.copyOf(kinds);
            predicates = List.copyOf(predicates);
        }
    }

    /**
     * One relaxation step: every group but {@code dropped}.
     *
     * @param index   0 for the unrelaxed query
     * @param dropped the names of the groups left out ({@code K}, {@code L1}...)
     * @param relaxed the ladder kinds left out (their labels)
     */
    public record Step(int index, List<String> dropped, List<String> relaxed, List<FieldPredicate> predicates) {

        public Step {
            dropped = List.copyOf(dropped);
            relaxed = List.copyOf(relaxed);
            predicates = List.copyOf(predicates);
        }
    }

    /** This query treating rows older than {@code version} as unknown (0: none). */
    public FieldQuery withStaleBelow(int version) {
        return new FieldQuery(distributor, groups, Math.max(0, version));
    }

    /** The group of a name, null when the query has none. */
    public Group group(String name) {
        return groups.stream().filter(g -> g.name().equals(name)).findFirst().orElse(null);
    }

    /** The groups of a role, in order. */
    public List<Group> groups(Role role) {
        return groups.stream().filter(g -> g.role() == role).toList();
    }

    /**
     * The relaxation steps (DESIGN.md 3.8, study 4.2): every group; without {@code K} (when there is one); then
     * without {@code L1}, {@code L1, L2}... The last step holds {@code H} only.
     */
    public List<Step> steps() {
        List<Step> steps = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        List<String> relaxed = new ArrayList<>();
        steps.add(step(0, dropped, relaxed));
        if (group(Role.K.name()) != null) {
            dropped.add(Role.K.name());
            steps.add(step(steps.size(), dropped, relaxed));
        }
        for (Group l : groups(Role.L)) {
            dropped.add(l.name());
            l.kinds().forEach(k -> relaxed.add(k.label()));
            steps.add(step(steps.size(), dropped, relaxed));
        }
        return List.copyOf(steps);
    }

    /** Step {@code index} of {@link #steps()} (the last one for an index past the end). */
    public Step step(int index) {
        List<Step> steps = steps();
        return steps.get(Math.clamp(index, 0, steps.size() - 1));
    }

    /** The predicates of the soft kinds ({@link Role#S}): they order the candidates of every step, never filter. */
    public List<FieldPredicate> soft() {
        return groups(Role.S).stream().flatMap(g -> g.predicates().stream()).toList();
    }

    /**
     * The predicates of the stated ratings ({@link Role#R}): they order the candidates of every step (a part that
     * states and meets them first), never filter.
     */
    public List<FieldPredicate> ratings() {
        return groups(Role.R).stream().flatMap(g -> g.predicates().stream()).toList();
    }

    /** The most relaxed step: {@code H} only (the superset of every part the Java check returns). */
    public Step relaxed() {
        return steps().getLast();
    }

    private Step step(int index, List<String> dropped, List<String> relaxed) {
        List<FieldPredicate> predicates = new ArrayList<>();
        groups.stream().filter(g -> !g.role().orderOnly() && !dropped.contains(g.name()))
                .forEach(g -> predicates.addAll(g.predicates()));
        return new Step(index, dropped, relaxed, predicates);
    }
}
