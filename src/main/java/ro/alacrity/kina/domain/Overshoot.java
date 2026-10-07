package ro.alacrity.kina.domain;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A score penalty for a minimum rating far above the request (DESIGN.md 3.4 "Rating overshoot"), declared on a
 * {@link ConstraintKind} rating: a part rated above {@link #ratio()} times the requested value loses
 * {@link #perOctave()} of score per octave beyond that ratio, up to {@link #maxOctaves()} octaves. The part still meets
 * the request (ratings are minimums), keeps its match grade and is never excluded; the penalty is taken from the
 * deterministic score and again from the final score, so a 600 V part ranks below the 100 V to 200 V parts of a 100 V
 * request. One general declaration (no {@link #families()}) and family-specific ones; resolution as for {@link Relax}:
 * the family's own declaration, then the general one.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@Repeatable(Overshoot.List.class)
public @interface Overshoot {

    /** The rating ratio (part / requested) above which the penalty starts. */
    double ratio();

    /** Score lost per octave (doubling) above {@link #ratio()}. */
    double perOctave() default 0.25;

    /** Octaves above {@link #ratio()} at which the penalty is complete. */
    double maxOctaves() default 2.0;

    /** The policy families ({@link PolicyFamily}) of this declaration; empty for the general one. */
    String[] families() default {};

    /** Container of repeated {@link Overshoot} declarations. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface List {
        Overshoot[] value();
    }
}
