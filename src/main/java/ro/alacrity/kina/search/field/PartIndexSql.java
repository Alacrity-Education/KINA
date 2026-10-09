package ro.alacrity.kina.search.field;

import ro.alacrity.kina.domain.Indexed.ColumnType;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The columns of {@code part_index}, its write statement and its parameters, declared once (DESIGN.md 3.8, 8): the
 * structural columns are listed here ({@link #structural()}: identity, version, stock, the family path, the package's
 * readable flag, class and can size, {@code attrs}, the MPN and the text), every typed column the model declares comes
 * from the {@code @Indexed} declarations ({@link IndexColumn#declared()}), with its type. A declared column is read
 * from the {@link PartIndexRow} component of its name in camel case ({@code package_key}: {@code packageKey()}), or
 * from {@link PartIndexRow#values()} for a value column. The SQLite table is built from the same list
 * ({@link SqlitePartIndex}); {@code PartIndexSchemaTest} checks both against the migrations.
 *
 * <p>The row is inserted from the {@code cached_parts} row of the part, so the index never holds a part the cache does
 * not. Its {@code metadata_md5} is a parameter: the hash of the part the row was built from (never computed from the
 * cache row at insert time, so a payload written meanwhile leaves the row visibly stale). Every parameter carries its
 * SQL type (the statement is an {@code INSERT ... SELECT}).
 */
final class PartIndexSql {

    private PartIndexSql() {
    }

    /** A column, its type and how its parameter is read from a row. */
    private record Col(String name, ColumnType type, Function<Ctx, Object> value) {
    }

    private record Ctx(PartIndexRow row, OffsetDateTime now, JsonMapper json) {
    }

    /**
     * The columns of the PostgreSQL table that are not written from a row: the payload hash (computed by the insert),
     * the metadata hash (a parameter of its own) and the generated text vector.
     */
    static final Map<String, String> POSTGRES_ONLY = Map.of("payload_md5", "text", "metadata_md5", "text",
            "search_tsv", "tsvector");

    /** Columns compared to skip an unchanged row (everything but the time of indexing). */
    static final List<String> COMPARED;
    /** The columns written, in parameter order (payload_md5 is computed). */
    static final List<String> COLUMNS;
    static final String UPSERT;
    private static final List<Col> COLS = columns();

    static {
        COLUMNS = COLS.stream().map(Col::name).toList();
        // the metadata hash makes a row current (DESIGN.md 3.8); payload_md5 is informational since V16: a row whose
        // only difference is the payload's stock, prices or timestamps is not rewritten
        List<String> compared = new ArrayList<>(COLUMNS);
        compared.remove("indexed_at");
        compared.add("metadata_md5");
        COMPARED = List.copyOf(compared);
        List<String> insert = new ArrayList<>(COLUMNS);
        insert.add("metadata_md5");
        insert.add("payload_md5");
        String select = COLS.stream().map(c -> "?::" + c.type().postgres()).collect(Collectors.joining(", "))
                + ", ?::text, md5(c.payload::text)";
        String update = insert.stream().filter(c -> !c.equals("distributor") && !c.equals("part_number"))
                .map(c -> c + " = EXCLUDED." + c).collect(Collectors.joining(", "));
        String distinct = "(" + COMPARED.stream().map(c -> "part_index." + c).collect(Collectors.joining(", "))
                + ") IS DISTINCT FROM (" + COMPARED.stream().map(c -> "EXCLUDED." + c)
                .collect(Collectors.joining(", ")) + ")";
        UPSERT = "INSERT INTO part_index (" + String.join(", ", insert) + ") SELECT " + select
                + " FROM cached_parts c WHERE c.distributor = ? AND c.part_number = ?"
                + " ON CONFLICT (distributor, part_number) DO UPDATE SET " + update + " WHERE " + distinct;
    }

    /** The parameters of {@link #UPSERT} for {@code row}. */
    static Object[] params(PartIndexRow row, OffsetDateTime now, JsonMapper json) {
        Ctx ctx = new Ctx(row, now, json);
        List<Object> out = new ArrayList<>(COLS.size() + 2);
        for (Col c : COLS) {
            out.add(c.value().apply(ctx));
        }
        out.add(row.metadataMd5());
        out.add(row.distributor().name());
        out.add(row.partNumber());
        return out.toArray();
    }

    /** The column names and types of {@link #COLUMNS}, in order. */
    static Map<String, ColumnType> types() {
        Map<String, ColumnType> out = new LinkedHashMap<>();
        COLS.forEach(c -> out.put(c.name(), c.type()));
        return out;
    }

    /** The values of {@code row} by column name, in {@link #COLUMNS} order (arrays as Java arrays, attrs as JSON). */
    static Map<String, Object> values(PartIndexRow row, OffsetDateTime now, JsonMapper json) {
        Ctx ctx = new Ctx(row, now, json);
        Map<String, Object> out = new LinkedHashMap<>();
        COLS.forEach(c -> out.put(c.name(), c.value().apply(ctx)));
        return out;
    }

    /** The structural columns: written by every row, never compared by a rule of their own. */
    private static List<Col> structural() {
        return List.of(
                new Col("distributor", ColumnType.TEXT, c -> c.row().distributor().name()),
                new Col("part_number", ColumnType.TEXT, c -> c.row().partNumber()),
                new Col("extractor_version", ColumnType.INT4, c -> c.row().extractorVersion()),
                new Col("indexed_at", ColumnType.TIMESTAMPTZ, Ctx::now),
                new Col("in_stock", ColumnType.BOOL, c -> c.row().inStock()),
                new Col("family_path", ColumnType.TEXT_ARRAY, c -> c.row().familyPath().toArray(String[]::new)),
                new Col("policy_family", ColumnType.TEXT, c -> c.row().policyFamily()),
                new Col("subtype", ColumnType.TEXT, c -> c.row().subtype()),
                new Col("package_readable", ColumnType.BOOL, c -> c.row().packageReadable()),
                new Col("package_class", ColumnType.TEXT, c -> c.row().packageClass()),
                new Col("can_d_mm", ColumnType.FLOAT8, c -> c.row().canDiameterMm()),
                new Col("can_l_mm", ColumnType.FLOAT8, c -> c.row().canLengthMm()),
                new Col("attrs", ColumnType.JSONB, c -> c.json().writeValueAsString(c.row().attrs())),
                new Col("mpn", ColumnType.TEXT, c -> c.row().mpn()),
                new Col("search_text", ColumnType.TEXT, c -> c.row().searchText()));
    }

    private static List<Col> columns() {
        List<Col> cols = new ArrayList<>(structural());
        Map<String, Method> components = new LinkedHashMap<>();
        for (RecordComponent rc : PartIndexRow.class.getRecordComponents()) {
            components.put(rc.getName(), rc.getAccessor());
        }
        List<String> valueColumns = new ArrayList<>();
        IndexColumn.valueColumns().values().forEach(c -> valueColumns.add(c.name()));
        IndexColumn.valueColumns().keySet().forEach(kind -> {
            IndexColumn condition = IndexColumn.condition(ro.alacrity.kina.domain.PartAttribute.indexedOf(kind));
            if (condition != null) {
                valueColumns.add(condition.name());
            }
        });
        IndexColumn.declared().forEach((name, type) -> {
            if (valueColumns.contains(name)) {
                cols.add(new Col(name, type, c -> c.row().values().get(name)));
                return;
            }
            Method accessor = components.get(camelCase(name));
            if (accessor == null) {
                throw new IllegalStateException("part_index." + name + " is declared but PartIndexRow has no "
                        + camelCase(name) + "()");
            }
            cols.add(new Col(name, type, c -> sql(read(accessor, c.row()), type)));
        });
        return List.copyOf(cols);
    }

    /** {@code package_key} -&gt; {@code packageKey}. */
    static String camelCase(String column) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char ch : column.toCharArray()) {
            if (ch == '_') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(ch) : ch);
                upper = false;
            }
        }
        return out.toString();
    }

    private static Object read(Method accessor, PartIndexRow row) {
        try {
            return accessor.invoke(row);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("cannot read " + accessor.getName() + " of a part_index row", e);
        }
    }

    /** A component's value as the parameter of its column type (a list as an array). */
    private static Object sql(Object value, ColumnType type) {
        if (value instanceof List<?> list) {
            return type == ColumnType.FLOAT8_ARRAY ? list.toArray(Double[]::new) : list.toArray(String[]::new);
        }
        return value;
    }
}
