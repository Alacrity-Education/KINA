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
 * displayed by {@link #display()} with the {@link #base()} and {@link #prefixes()}.
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
}
