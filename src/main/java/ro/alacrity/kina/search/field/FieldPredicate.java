package ro.alacrity.kina.search.field;

import ro.alacrity.kina.domain.ConstraintKind;
import ro.alacrity.kina.domain.Distributor;

import java.util.List;

/**
 * One typed predicate of a {@link FieldQuery}, independent of the SQL dialect ({@link PostgresFieldSql},
 * {@link SqliteFieldSql}). Every value predicate keeps a row whose column is NULL (DESIGN.md 3.8: a part that does not
 * state an attribute is unverified, never excluded) and compares the bare column with constants, never an expression
 * over a value column.
 */
public sealed interface FieldPredicate {

    /** The constraint kind the predicate filters for, null for the always-on rules and free text. */
    ConstraintKind kind();

    /** The columns whose being stated makes a part confirmed for this predicate; empty when none. */
    default List<IndexColumn> stated() {
        return List.of();
    }

    // ---------------------------------------------------------------- always on

    /** {@code distributor = d}. */
    record DistributorIs(Distributor distributor) implements FieldPredicate {
        @Override
        public ConstraintKind kind() {
            return null;
        }
    }

    /** {@code in_stock}: ships-now stock (DESIGN.md 2); the column is a copy and is never indexed. */
    record InStock() implements FieldPredicate {
        @Override
        public ConstraintKind kind() {
            return null;
        }
    }

    // ---------------------------------------------------------------- values

    /** {@code col IS NULL OR col BETWEEN low AND high}. */
    record Range(ConstraintKind kind, IndexColumn column, double low, double high) implements FieldPredicate {
        @Override
        public List<IndexColumn> stated() {
            return List.of(column);
        }
    }

    /**
     * A minimum: {@code col IS NULL OR col >= min}; for a rating also {@code OR col <= 0} (a part value of 0 or less is
     * no rating, the Java check never puts it below spec).
     */
    record AtLeast(ConstraintKind kind, IndexColumn column, double min, boolean rating) implements FieldPredicate {
        @Override
        public List<IndexColumn> stated() {
            return List.of(column);
        }
    }

    /** A maximum: {@code col IS NULL OR col <= max}. */
    record AtMost(ConstraintKind kind, IndexColumn column, double max) implements FieldPredicate {
        @Override
        public List<IndexColumn> stated() {
            return List.of(column);
        }
    }

    /** {@code col IS NULL OR col = value} (a String, Integer or Boolean). */
    record Equal(ConstraintKind kind, IndexColumn column, Object value) implements FieldPredicate {
        @Override
        public List<IndexColumn> stated() {
            return List.of(column);
        }
    }

    /** A closed vocabulary: {@code col IS NULL OR col IN (values)}. */
    record OneOf(ConstraintKind kind, IndexColumn column, List<String> values) implements FieldPredicate {

        public OneOf {
            values = List.copyOf(values);
        }

        @Override
        public List<IndexColumn> stated() {
            return List.of(column);
        }
    }

    /** The values the comparator refuses: {@code col IS NULL OR col NOT IN (values)}; an unknown value is kept. */
    record NoneOf(ConstraintKind kind, IndexColumn column, List<String> values) implements FieldPredicate {

        public NoneOf {
            values = List.copyOf(values);
        }

        @Override
        public List<IndexColumn> stated() {
            return List.of(column);
        }
    }

    /** An array column: empty, or one element {@code BETWEEN low AND high}. */
    record AnyInRange(ConstraintKind kind, IndexColumn column, double low, double high) implements FieldPredicate {
        @Override
        public List<IndexColumn> stated() {
            return List.of(column);
        }
    }

    /**
     * The package: {@code package_key = key}, or a package KINA cannot read ({@code NOT package_readable}), or a can
     * of the size within {@code margin} mm ({@code canDiameterMm} and {@code canLengthMm} null when the request is no
     * can), or a package of the class the request's class is never compared with ({@code neutralClass}: an LED size
     * and a PLCC package; null when none).
     */
    record PackageIs(ConstraintKind kind, String key, Double canDiameterMm, Double canLengthMm, double margin,
                     String neutralClass) implements FieldPredicate {
    }

    /** {@code col IS NULL}: the request refuses every stated value (an array for a single-element request). */
    record Absent(ConstraintKind kind, IndexColumn column) implements FieldPredicate {
    }

    // ---------------------------------------------------------------- free text (the K group)

    /** A keyword of 3 or more letters and digits: a word prefix ({@code search_tsv}, FTS5 MATCH). */
    record Word(String token) implements FieldPredicate {
        @Override
        public ConstraintKind kind() {
            return null;
        }
    }

    /** Any other keyword: {@code search_text LIKE '%token%'}. */
    record Substring(String token) implements FieldPredicate {
        @Override
        public ConstraintKind kind() {
            return null;
        }
    }

    /** One of the part numbers the query names, as an MPN prefix ({@code mpn LIKE 'prefix%'}, trigram index). */
    record MpnPrefix(List<String> prefixes) implements FieldPredicate {

        public MpnPrefix {
            prefixes = List.copyOf(prefixes);
        }

        @Override
        public ConstraintKind kind() {
            return null;
        }
    }
}
