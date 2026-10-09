package ro.alacrity.kina.search.field;

import ro.alacrity.kina.domain.ConstraintKind;
import ro.alacrity.kina.domain.Indexed;
import ro.alacrity.kina.domain.Indexed.ColumnType;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.PartAttribute;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A column of {@code part_index}, or a key of its {@code attrs} JSONB column (DESIGN.md 3.8).
 *
 * @param name the column name ({@code capacitance_f}, {@code attrs})
 * @param key  the JSON key within {@code attrs}, null for a typed column
 * @param type the SQL type (of the JSON value for a key)
 */
public record IndexColumn(String name, String key, ColumnType type) {

    /** The test frequency of an impedance (the condition of {@link ParsedQuery#IMPEDANCE}). */
    public static final IndexColumn IMPEDANCE_TEST_HZ = new IndexColumn("impedance_test_hz", null, ColumnType.FLOAT8);

    /** A typed column. */
    public static IndexColumn of(String name, ColumnType type) {
        return new IndexColumn(name, null, type);
    }

    /** A key of {@code attrs}. */
    public static IndexColumn attr(String key, ColumnType type) {
        return new IndexColumn(Indexed.ATTRS, key, type);
    }

    /** True for a key of {@code attrs}. */
    public boolean json() {
        return key != null;
    }

    /** {@code capacitance_f}, {@code attrs.colour}. */
    public String display() {
        return key == null ? name : name + "." + key;
    }

    /** The column a {@link PartAttribute} declares, null when it declares none. */
    public static IndexColumn of(PartAttribute attribute) {
        Indexed indexed = attribute == null ? null : attribute.indexed();
        return indexed == null ? null : of(indexed, 0);
    }

    /**
     * The typed value columns the writer fills from the numeric attributes ({@link PartAttribute#indexed()} on a typed
     * column), by the {@link ParsedQuery} kind of the attribute, in declaration order.
     */
    public static Map<String, IndexColumn> valueColumns() {
        Map<String, IndexColumn> out = new LinkedHashMap<>();
        for (PartAttribute a : PartAttribute.VALUES) {
            IndexColumn c = of(a);
            if (c != null && !c.json()) {
                out.put(a.kind(), c);
            }
        }
        return out;
    }

    /** The {@code attrs} keys the writer fills from the numeric attributes, by {@link ParsedQuery} kind. */
    public static Map<String, IndexColumn> valueKeys() {
        Map<String, IndexColumn> out = new LinkedHashMap<>();
        for (PartAttribute a : PartAttribute.VALUES) {
            IndexColumn c = of(a);
            if (c != null && c.json()) {
                out.put(a.kind(), c);
            }
        }
        return out;
    }

    /**
     * The columns a kind's rule reads for a request: its own column (and keys), or the column of the measure the
     * request states for it ({@link ConstraintKind#indexMeasure}); empty when there is none.
     */
    public static List<IndexColumn> of(ConstraintKind kind, ParsedQuery query) {
        Indexed indexed = kind.indexed();
        if (indexed == null || indexed.javaOnly()) {
            return List.of();
        }
        if (!indexed.column().isEmpty()) {
            return own(indexed);
        }
        IndexColumn c = of(PartAttribute.indexedOf(kind.indexMeasure(query)));
        return c == null ? List.of() : List.of(c);
    }

    /** Every column a kind's rule can read (for the documentation table): its own, or those of its measures. */
    public static List<IndexColumn> all(ConstraintKind kind) {
        Indexed indexed = kind.indexed();
        if (indexed == null || indexed.javaOnly()) {
            return List.of();
        }
        if (!indexed.column().isEmpty()) {
            return own(indexed);
        }
        List<IndexColumn> out = new ArrayList<>();
        for (String measure : kind.indexMeasures()) {
            IndexColumn c = of(PartAttribute.indexedOf(measure));
            if (c != null && !out.contains(c)) {
                out.add(c);
            }
        }
        return out;
    }

    private static List<IndexColumn> own(Indexed indexed) {
        if (indexed.keys().length == 0) {
            return List.of(of(indexed, 0));
        }
        List<IndexColumn> out = new ArrayList<>();
        for (int i = 0; i < indexed.keys().length; i++) {
            out.add(of(indexed, i));
        }
        return out;
    }

    private static IndexColumn of(Indexed indexed, int key) {
        return indexed.keys().length == 0 ? new IndexColumn(indexed.column(), null, indexed.type())
                : new IndexColumn(indexed.column(), indexed.keys()[key], indexed.type());
    }
}
