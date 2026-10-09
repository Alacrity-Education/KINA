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
 * {@link #predicate()}, the relative {@link #slack()} or absolute {@link #margin()} that makes the SQL looser than
 * the Java tolerance, and for {@link Predicate#IN_COMPATIBLE} the {@link #vocabulary()} the kind's comparator
 * ({@link ConstraintKind#refuses}) is run over. The request's value of each column is
 * {@link ConstraintKind#indexWanted}. {@link #javaOnly()} marks a kind whose comparison stays in Java (it never
 * narrows the SQL). A row whose column is NULL (the part does not state the attribute) is always kept, because the
 * judge keeps such a part (unverified). The rule of a rating whose general strategy is {@code BELOW_SPEC} (group
 * {@code R} of the field query) never filters: it only orders the candidates, a part that states the rating and meets
 * it first, so a part below spec reaches the Java check, which excludes and counts it ({@code excluded_below_spec}).
 *
 * <p>On a {@link PartAttribute} constant it declares where the writer stores the attribute's SI value: a typed column
 * ({@code capacitance_f}) or a key of the {@code attrs} JSONB column ({@link #ATTRS} with {@link #keys()}), and the
 * column of the value's condition ({@link #condition()}: the test frequency of an impedance).
 *
 * <p>The typed columns these declarations name, with their {@link #type()}, are the typed columns of
 * {@code part_index} (migration V14) and of the LCSC typed table: the write statement and the SQLite table are derived
 * from them ({@code search.field.PartIndexSql}, {@code SqlitePartIndex}), and {@code PartIndexSchemaTest} checks the
 * migrations against them in both directions.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Indexed {

    /** The JSONB column of the long tail (fan, LED and switch attributes). */
    String ATTRS = "attrs";

    /** The relative slack of a rating's rule: rounding noise only, the Java check compares exactly. */
    double RATING_SLACK = 1e-6;

    /** The significant digits of every {@link ColumnType#FLOAT8} value the writer stores. */
    int SIGNIFICANT_DIGITS = 9;

    /**
     * The relative slack every range adds on both bounds for the writer's rounding to {@link #SIGNIFICANT_DIGITS}
     * digits (a stored value differs from the part's by at most half a unit of the last digit, 5e-9 of the value):
     * relative, so it widens a 1 pF and a 10 MOhm range alike, never by an absolute amount in the column's unit.
     */
    double ROUNDING_SLACK = 1e-8;

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

    /**
     * The vocabulary an {@link Predicate#IN_COMPATIBLE} rule runs the kind's comparator over; {@link Vocabulary#NONE}
     * for every other predicate.
     */
    Vocabulary vocabulary() default Vocabulary.NONE;

    /**
     * On a {@link PartAttribute}: the typed column of the value's condition (the test frequency of an impedance),
     * empty when it has none. A rule on the attribute's column also compares the condition when the request states
     * one.
     */
    String condition() default "";

    /** The SQL type of a column, in PostgreSQL and in SQLite (arrays and JSON as text there, booleans as integers). */
    enum ColumnType {
        /** Not declared (a rule that reads the column of its measure, or a Java-only kind). */
        NONE(null, null),
        TEXT("text", "TEXT"),
        TEXT_ARRAY("text[]", "TEXT"),
        /** {@code double precision}; values are rounded to 9 significant digits by the writer. */
        FLOAT8("float8", "REAL"),
        FLOAT8_ARRAY("float8[]", "TEXT"),
        INT2("smallint", "INTEGER"),
        INT4("int", "INTEGER"),
        BOOL("boolean", "INTEGER"),
        JSONB("jsonb", "TEXT"),
        TIMESTAMPTZ("timestamptz", "TEXT");

        private final String postgres;
        private final String sqlite;

        ColumnType(String postgres, String sqlite) {
            this.postgres = postgres;
            this.sqlite = sqlite;
        }

        /** The PostgreSQL type (the cast of a parameter): {@code float8}, {@code smallint}, {@code text[]}. */
        public String postgres() {
            return postgres;
        }

        /** The SQLite column type: {@code REAL}, {@code INTEGER} or {@code TEXT}. */
        public String sqlite() {
            return sqlite;
        }
    }

    /**
     * A vocabulary of values a part can state for a kind, as the search layer knows it
     * ({@link MatchContext#vocabulary}): an {@link Predicate#IN_COMPATIBLE} rule keeps the values the kind's comparator
     * accepts. A closed vocabulary ({@link #FAMILY}: every value KINA stores) becomes {@code col = ANY(accepted)}, an
     * open one {@code col <> ALL(refused)}, so a value KINA does not know is kept.
     */
    enum Vocabulary {
        NONE(false),
        /** The family labels ({@code ComponentFamily}). */
        FAMILY(true),
        /** The canonical technologies. */
        TECHNOLOGY(false),
        /** The form factor classes. */
        FORM_FACTOR(false),
        /** The connector types the recogniser returns. */
        CONNECTOR_TYPE(false),
        /** The LED types the extractor returns, and the request's plain-emitter classes. */
        LED_TYPE(false),
        /** The LED colours. */
        COLOUR(false),
        /** The switch types (mechanical types and parts that are no switch). */
        SWITCH_TYPE(false),
        /** The switch termination classes. */
        TERMINATION(false);

        private final boolean closed;

        Vocabulary(boolean closed) {
            this.closed = closed;
        }

        /** True for a vocabulary that holds every value a part can state. */
        public boolean closed() {
            return closed;
        }
    }

    /**
     * How a stated value is compared in SQL. Every predicate keeps NULL columns. {@code FieldQueryBuilder} dispatches
     * on it for every kind, with the column values of {@link ConstraintKind#indexWanted}.
     */
    enum Predicate {
        /** Storage only (a {@link PartAttribute} column). */
        NONE,
        /**
         * {@code col BETWEEN x - |x| (slack + ROUNDING_SLACK) - margin AND x + |x| (slack + ROUNDING_SLACK) + margin},
         * per column of the rule (width and length of a body: one value each).
         */
        RANGE,
        /**
         * A minimum: {@code col >= x(1 - slack)}; for a rating a part value of 0 or less is no rating (kept). A
         * {@code BELOW_SPEC} rating renders it in the order of the candidates only, never in the filter.
         */
        GTE,
        /** A maximum: {@code col <= x(1 + slack) + margin}. */
        LTE,
        /** {@code col = x} per column; a case-insensitive kind compares lower case on both sides. */
        EQUAL,
        /**
         * The values the kind's comparator accepts over its {@link #vocabulary()}: {@code col = ANY(set)} for a closed
         * vocabulary (families), else {@code col <> ALL(set)} over the values it refuses (a value KINA does not know is
         * kept).
         */
        IN_COMPATIBLE,
        /** One element of an array column within the range, or an empty array. */
        ARRAY_ANY,
        /** {@code attrs @> {key: x}} per key, or the key absent. */
        JSONB_CONTAINS,
        /** The normalised package key, or a package KINA cannot read, or a can size within the margin. */
        PACKAGE,
        /** The column must be NULL (a single-element request against arrays and networks). */
        ABSENT
    }
}
