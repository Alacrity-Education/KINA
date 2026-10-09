package ro.alacrity.kina.search.field;

import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The write statement of {@code part_index} and its parameters. The columns are listed once ({@link #COLUMNS}); the
 * value columns come from the {@code PartAttribute} declarations ({@link IndexColumn#valueColumns()}). The row is
 * inserted from the {@code cached_parts} row of the part, so the index never holds a part the cache does not. Its
 * {@code metadata_md5} is a parameter: the hash of the part the row was built from (never computed from the cache row
 * at insert time, so a payload written meanwhile leaves the row visibly stale). Every parameter carries its SQL type
 * (the statement is an {@code INSERT ... SELECT}).
 */
final class PartIndexSql {

    private PartIndexSql() {
    }

    /** A column, its SQL cast and how its parameter is read from a row. */
    private record Col(String name, String cast, Function<Ctx, Object> value) {
    }

    private record Ctx(PartIndexRow row, OffsetDateTime now, JsonMapper json) {
    }

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
        String select = COLS.stream().map(c -> "?::" + c.cast()).collect(Collectors.joining(", "))
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

    /** The column names and SQL casts of {@link #COLUMNS}, in order. */
    static Map<String, String> casts() {
        Map<String, String> out = new LinkedHashMap<>();
        COLS.forEach(c -> out.put(c.name(), c.cast()));
        return out;
    }

    /** The values of {@code row} by column name, in {@link #COLUMNS} order (arrays as Java arrays, attrs as JSON). */
    static Map<String, Object> values(PartIndexRow row, OffsetDateTime now, JsonMapper json) {
        Ctx ctx = new Ctx(row, now, json);
        Map<String, Object> out = new LinkedHashMap<>();
        COLS.forEach(c -> out.put(c.name(), c.value().apply(ctx)));
        return out;
    }

    private static List<Col> columns() {
        List<Col> cols = new ArrayList<>();
        cols.add(new Col("distributor", "text", c -> c.row().distributor().name()));
        cols.add(new Col("part_number", "text", c -> c.row().partNumber()));
        cols.add(new Col("extractor_version", "int", c -> c.row().extractorVersion()));
        cols.add(new Col("indexed_at", "timestamptz", Ctx::now));
        cols.add(new Col("in_stock", "boolean", c -> c.row().inStock()));
        cols.add(new Col("family", "text", c -> c.row().family()));
        cols.add(new Col("family_path", "text[]", c -> c.row().familyPath().toArray(String[]::new)));
        cols.add(new Col("policy_family", "text", c -> c.row().policyFamily()));
        cols.add(new Col("subtype", "text", c -> c.row().subtype()));
        cols.add(new Col("polarity", "text", c -> c.row().polarity()));
        cols.add(new Col("package_key", "text", c -> c.row().packageKey()));
        cols.add(new Col("package_readable", "boolean", c -> c.row().packageReadable()));
        cols.add(new Col("package_class", "text", c -> c.row().packageClass()));
        cols.add(new Col("can_d_mm", "float8", c -> c.row().canDiameterMm()));
        cols.add(new Col("can_l_mm", "float8", c -> c.row().canLengthMm()));
        cols.add(new Col("mounting", "text", c -> c.row().mounting()));
        cols.add(new Col("technology", "text", c -> c.row().technology()));
        cols.add(new Col("dielectric", "text", c -> c.row().dielectric()));
        cols.add(new Col("form_factor", "text", c -> c.row().formFactor()));
        cols.add(new Col("elements", "smallint", c -> c.row().elements()));
        Map<String, IndexColumn> values = new LinkedHashMap<>();
        IndexColumn.valueColumns().values().forEach(col -> values.put(col.name(), col));
        values.put(IndexColumn.IMPEDANCE_TEST_HZ.name(), IndexColumn.IMPEDANCE_TEST_HZ);
        values.keySet().forEach(name -> cols.add(new Col(name, "float8", c -> c.row().values().get(name))));
        cols.add(new Col("voltages_v", "float8[]", c -> c.row().voltages().toArray(Double[]::new)));
        cols.add(new Col("connector_type", "text", c -> c.row().connectorType()));
        cols.add(new Col("gender", "text", c -> c.row().gender()));
        cols.add(new Col("positions", "smallint", c -> c.row().positions()));
        cols.add(new Col("rows_count", "smallint", c -> c.row().rowsCount()));
        cols.add(new Col("pitch_mm", "float8", c -> c.row().pitchMm()));
        cols.add(new Col("orientation", "text", c -> c.row().orientation()));
        cols.add(new Col("usb_type", "text", c -> c.row().usbType()));
        cols.add(new Col("usb_class", "smallint", c -> c.row().usbClass()));
        cols.add(new Col("pin_configuration", "smallint", c -> c.row().pinConfiguration()));
        cols.add(new Col("attrs", "jsonb", c -> c.json().writeValueAsString(c.row().attrs())));
        cols.add(new Col("mpn", "text", c -> c.row().mpn() == null ? "" : c.row().mpn()));
        cols.add(new Col("search_text", "text", c -> c.row().searchText()));
        return List.copyOf(cols);
    }
}
