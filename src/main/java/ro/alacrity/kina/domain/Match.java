package ro.alacrity.kina.domain;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * How a {@link ConstraintKind} is matched and scored by the deterministic ranker (DESIGN.md 3.4). A part that matches
 * earns {@link #weight()}, one that misses loses it, and a part that does not state the attribute is unverified (it
 * earns nothing and the attribute is left out of its match grade).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Match {

    /** The {@link #group()} of the ratings (voltage, current, power...: minimums; DCR: a maximum; exact ratings). */
    String RATING = "rating";

    MatchMode mode();

    /** Tolerance of {@link MatchMode#WITHIN}, {@link MatchMode#AT_LEAST} and {@link MatchMode#AT_MOST}, relative. */
    double tolerance() default 0;

    /** Score weight; for a {@link #group()} the weight of the whole group. */
    double weight();

    /**
     * A group whose {@link #weight()} is shared equally, at run time, between the members the request states (the
     * ratings).
     */
    String group() default "";

    /** False for score-only signals (preferences): never part of the match grade. */
    boolean inGrade() default true;

    /** The requests the signal applies to. */
    Scope scope() default Scope.ANY;

    /** Position among the signals of the score (and of the unverified list), lower first. */
    int order();

    /** Position in the {@code mismatches} of a part, lower first; 0 when the kind reports no mismatch. */
    int report() default 0;

    /** The requests a signal applies to. */
    enum Scope {
        /** Every request. */
        ANY,
        /** Requests that are not for a connector. */
        PART,
        /** Connector requests other than USB. */
        CONNECTOR,
        /** USB connector requests. */
        USB;

        /** The scope of a request. */
        public static Scope of(ParsedQuery query) {
            if (query.connector() == null) {
                return PART;
            }
            return query.connector().isUsb() ? USB : CONNECTOR;
        }

        /** True when a signal of this scope applies to a request of {@code request} scope. */
        public boolean covers(Scope request) {
            return this == ANY || this == request;
        }
    }
}
