package ro.alacrity.kina.domain;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The unit of a numeric {@link PartAttribute} (DESIGN.md 3.4 "Units"). The {@link #symbols()} are the spellings a
 * value token may end in ({@code 10uF}, {@code 25Vdc}); each symbol belongs to one attribute, whose kind a token in
 * that unit is read as. Attributes that share a unit without owning its symbols ({@code DCR} in ohm, the saturation
 * current in ampere) are read in the unit of the attribute that owns them and keep their own kind. Values are
 * displayed by {@link #display()} with the {@link #base()} and {@link #prefixes()}. A symbol of a non-SI unit carries its
 * conversion {@link #factors() factor} to the base unit ({@code cfm} is 1.699 m³/h); a unit that only one family's
 * texts use ({@code rpm}, {@code Pa}, {@code dBA}: fans) names that family ({@link #families()}), so the same letters
 * stay what they were elsewhere ({@code 10pA} is a current, {@code 80dB} of an op amp no noise rating).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Unit {

    /** Lower-case unit spellings of a value token, matched case-insensitively; empty when another attribute owns them. */
    String[] symbols() default {};

    /** The base unit of the display form ({@code F}, {@code ohm}, {@code V}...). */
    String base();

    /** SI prefixes of the display form, smallest first ({@code ""} for none); the largest that fits is used. */
    String[] prefixes() default {""};

    /** The display rule ({@link ValueDisplay.Prefixed} unless the unit needs another). */
    Class<? extends ValueDisplay> display() default ValueDisplay.Prefixed.class;

    /**
     * The base units of one of each {@link #symbols()} symbol, by position ({@code 1.699} for {@code cfm} in m³/h);
     * empty when every symbol is the base unit. An SI prefix still applies ({@code kPa}).
     */
    double[] factors() default {};

    /** The families whose texts read the {@link #symbols()}; empty for every family (and a text of no known family). */
    ComponentFamily[] families() default {};

    /**
     * The unit {@link ValueDisplay.WithAlternative} shows in brackets, as written ({@code CFM}); one of the
     * {@link #symbols()} ignoring case, whose {@link #factors() factor} converts it.
     */
    String alternative() default "";
}
