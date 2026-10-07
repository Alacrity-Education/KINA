package ro.alacrity.kina.domain;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The relaxation policy of a {@link ConstraintKind} constant (DESIGN.md 3.2 and 3.4). A constant carries one general
 * declaration (no {@link #families()}): the strategy when a family does not make the kind hard. Family-specific
 * declarations override it for the families they list ({@link #ALL} for every family). Resolution: the family's own
 * declaration, then {@link #ALL}, then the general one; {@code kina.search.hard-constraints.<family>} replaces the
 * declared table of a family (listed kinds {@link RelaxStrategy#NEVER}, the others their general strategy).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@Repeatable(Relax.List.class)
public @interface Relax {

    /** {@link #families()} entry for every policy family. */
    String ALL = "*";

    RelaxStrategy strategy();

    /** Position in the relaxation ladder, lower first; only for {@link RelaxStrategy#LADDER}. */
    int order() default 0;

    /** Score cost of a relaxed mismatch; -1 uses the {@link Match#weight()}. */
    double cost() default -1;

    /** The policy families ({@link PolicyFamily}) this declaration applies to; empty for the general declaration. */
    String[] families() default {};

    /** Container of repeated {@link Relax} declarations. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface List {
        Relax[] value();
    }
}
