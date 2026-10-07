package ro.alacrity.kina.domain;

/**
 * How a stated constraint may be loosened ({@link Relax}, DESIGN.md 3.2 and 3.4). The policy table of a family lists
 * the kinds that are {@link #NEVER}; every other kind falls back to the strategy of its general {@link Relax}.
 */
public enum RelaxStrategy {

    /**
     * Hard: never relaxed. A part whose known value contradicts it is excluded and counted in
     * {@code excluded_by_constraints}; a part that does not state it stays, unverified.
     */
    NEVER,

    /**
     * Relaxable: the relaxation ladder may loosen it, in {@link Relax#order()}; a part that misses it is returned
     * with the miss in {@code mismatches} and the constraint in {@code constraints_relaxed}.
     */
    LADDER,

    /**
     * Soft: ranked and graded, a miss is a mismatch, never excludes a part, and the ladder has no step for it (the
     * state of a constraint a family does not make hard and the ladder does not loosen).
     */
    SOFT,

    /**
     * A rating: never relaxed downward. A known value below the request (above it for a maximum) is below spec: the
     * part is excluded unless {@code allow_below_spec}; a higher minimum rating satisfies it, with a small score
     * preference for the closest one. Not in the policy table.
     */
    BELOW_SPEC,

    /** A preference: score only, never excludes, never part of the match grade, never reported. */
    PREFERENCE
}
