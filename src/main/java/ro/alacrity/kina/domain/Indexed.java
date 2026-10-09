package ro.alacrity.kina.domain;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The field index rule of a {@link ConstraintKind}, or the field index column of a {@link PartAttribute} (DESIGN.md
 * 3.8 "Field index"). The field index ({@code part_index}) is a recall filter: the SQL a rule renders keeps every row
 * the Java check ({@code PageCollector.Check}) would keep, and may keep more; Java stays the judge.
 *
 * <p>On a {@link ConstraintKind} constant it declares how a stated constraint becomes a predicate: the column (empty:
 * the column the {@link PartAttribute} of the kind's measure declares, {@link ConstraintKind#indexMeasure}), the
 * {@link #predicate()}, and the relative {@link #slack()} or absolute {@link #margin()} that makes the SQL looser than
 * the Java tolerance. {@link #javaOnly()} marks a kind whose comparison stays in Java (it never narrows the SQL). A
 * row whose column is NULL (the part does not state the attribute) is always kept ({@link #nullKept()}), because the
 * judge keeps such a part (unverified).
 *
 * <p>On a {@link PartAttribute} constant it declares where the writer stores the attribute's SI value: a typed column
 * ({@code capacitance_f}) or a key of the {@code attrs} JSONB column ({@link #ATTRS} with {@link #keys()}).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Indexed {

    /** The JSONB column of the long tail (fan, LED and switch attributes). */
    String ATTRS = "attrs";

    /** The column; empty for the column of the kind's measure ({@link PartAttribute} declaration). */
    String column() default "";

    /** The keys of {@link #ATTRS} the rule reads (a JSONB-backed attribute), in rule order. */
    String[] keys() default {};

    /** The type of the column (of the JSON value for an {@link #ATTRS} key). */
    ColumnType type() default ColumnType.NONE;

    /** How a stated value becomes a predicate; {@link Predicate#NONE} on a {@link PartAttribute} (storage only). */
    Predicate predicate() default Predicate.NONE;

    /** Relative slack added to the Java tolerance ({@code 0.015}: a range of +-1.5 %). */
    double slack() default 0;

    /** Absolute slack added to the Java tolerance, in the column's unit ({@code 0.035} mm of pitch). */
    double margin() default 0;

    /** True for a kind whose comparison stays in Java: it never becomes SQL. */
    boolean javaOnly() default false;

    /** Rows whose column is NULL are kept; always true (a part that does not state an attribute is unverified). */
    boolean nullKept() default true;

    /** The SQL type of a column. */
    enum ColumnType {
        /** Not declared (a rule that reads the column of its measure, or a Java-only kind). */
        NONE,
        TEXT,
        TEXT_ARRAY,
        /** {@code double precision}; values are rounded to 9 significant digits by the writer. */
        FLOAT8,
        FLOAT8_ARRAY,
        INT2,
        BOOL,
        JSONB
    }

    /** How a stated value is compared in SQL. Every predicate keeps NULL columns. */
    enum Predicate {
        /** Storage only (a {@link PartAttribute} column). */
        NONE,
        /** {@code col BETWEEN x(1 - slack) - margin AND x(1 + slack) + margin}. */
        RANGE,
        /** A minimum rating: {@code col >= x(1 - slack)}; a part value of 0 or less is no rating (kept). */
        GTE,
        /** A maximum: {@code col <= x(1 + slack) + margin}. */
        LTE,
        /** {@code col = x}; a case-insensitive kind compares lower case on both sides. */
        EQUAL,
        /**
         * The values the kind's comparator accepts: {@code col = ANY(set)} for a closed vocabulary (families), else
         * {@code col <> ALL(set)} over the vocabulary values it refuses (a value KINA does not know is kept).
         */
        IN_COMPATIBLE,
        /** One element of an array column within the range, or an empty array. */
        ARRAY_ANY,
        /** {@code attrs @> {key: x}} per key, or the key absent. */
        JSONB_CONTAINS,
        /** The normalised package key, or a package KINA cannot read, or a can size within the margin. */
        PACKAGE,
        /** The column must be NULL (a single-element request against arrays and networks). */
        ABSENT,
        /** Free text: {@code search_tsv} word prefixes, {@code search_text LIKE} for short tokens. */
        TEXT
    }
}
