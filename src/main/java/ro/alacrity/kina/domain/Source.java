package ro.alacrity.kina.domain;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One way to read a {@link PartAttribute} from a part (DESIGN.md 3.4 "Attribute sources"). An attribute declares its
 * sources in precedence order ({@link #precedence()}, lower first); the first source whose {@link #logic()} yields a
 * value wins. A source applies to a part only when its {@link #distributors()}, {@link #families()},
 * {@link #traits()} and {@link #exceptTraits()} allow it; the family is the one the attribute is read with
 * ({@link PartAttribute#readsWithValueFamily()}).
 *
 * <p>New distributor spellings of an attribute are added to the {@link #names()} of its source, never in the
 * extractor.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@Repeatable(Source.List.class)
public @interface Source {

    /**
     * Lower-case distributor attribute names, tried in this order (Mouser {@code ProductAttributes}, TME parameters,
     * LCSC attributes). Some logic classes read them differently (as key prefixes or key words); their Javadoc says.
     */
    String[] names() default {};

    /** The distributors this source applies to; empty for every distributor. */
    Distributor[] distributors() default {};

    /** The families this source applies to (exact family, not its specialisations); empty for every family. */
    ComponentFamily[] families() default {};

    /** Traits the family must have (all of them). */
    ComponentFamily.Trait[] traits() default {};

    /** Traits the family must not have. */
    ComponentFamily.Trait[] exceptTraits() default {};

    /** Words that disqualify an attribute name for the key scans ({@code KeyContaining}). */
    String[] excluding() default {};

    /** Position among the sources of the attribute, lower first; ties keep the declaration order. */
    int precedence() default 0;

    /** How the value is read: {@link AttributeLogic.Simple} looks the names up and parses the value in its unit. */
    Class<? extends AttributeLogic> logic() default AttributeLogic.Simple.class;

    /** Container of repeated {@link Source} declarations. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface List {
        Source[] value();
    }
}
