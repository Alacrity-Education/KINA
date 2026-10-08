package ro.alacrity.kina.domain;

/** How the wanted and the actual value of a {@link ConstraintKind} are compared ({@link Match#mode()}). */
public enum MatchMode {

    /** Equal ({@link Object#equals}). */
    EQUAL,

    /** Equal strings, ignoring case. */
    EQUAL_IGNORE_CASE,

    /** A minimum: actual &gt;= wanted, within the relative {@link Match#tolerance()}. */
    AT_LEAST,

    /** A maximum: actual &lt;= wanted, within the relative {@link Match#tolerance()}. */
    AT_MOST,

    /** The same value within the relative {@link Match#tolerance()}. */
    WITHIN,

    /**
     * A feature the request names: a part that has it earns the weight; one without it earns nothing, or loses the
     * weight and reports {@code feature: <name> missing} when the kind declares {@link Absence#PENALIZE}
     * ({@link Match#absence()}).
     */
    FEATURE,

    /**
     * A graded comparison by the constant's own comparator: 1 same or compatible, -1 a different known value, a value
     * between for a partial match, 0 when not comparable.
     */
    COMPATIBLE,

    /** The constant's own comparator (connector and USB rules, the component type, form factor, elements...). */
    CUSTOM
}
