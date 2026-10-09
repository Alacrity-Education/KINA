package ro.alacrity.kina.search.field;

import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The SQLite form of {@code part_index} (DESIGN.md 3.8): the same columns as migration V14, arrays and {@code attrs} as
 * JSON text, booleans as 0 and 1, plus {@code fts_rowid}, the rowid of the part in the FTS5 table the free text is
 * matched in. Phase B builds the LCSC typed table with it inside the new JLCPCB file; {@link SqliteFieldSql} reads it.
 */
public final class SqlitePartIndex {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SqlitePartIndex() {
    }

    /** Creates {@code table} (and its indexes) on {@code connection}. */
    public static void create(Connection connection, String table) throws SQLException {
        String t = FieldSql.identifier(table);
        List<String> columns = new ArrayList<>();
        columns.add("fts_rowid INTEGER");
        PartIndexSql.casts().forEach((name, cast) -> columns.add(name + " " + type(cast)));
        columns.add("payload_md5 TEXT");
        try (Statement s = connection.createStatement()) {
            s.execute("CREATE TABLE " + t + " (" + String.join(", ", columns)
                    + ", PRIMARY KEY (distributor, part_number))");
            s.execute("CREATE INDEX " + t + "_cap ON " + t + " (family, capacitance_f) WHERE capacitance_f IS NOT NULL");
            s.execute("CREATE INDEX " + t + "_res ON " + t + " (family, resistance_ohm) WHERE resistance_ohm IS NOT NULL");
            s.execute("CREATE INDEX " + t + "_ind ON " + t + " (family, inductance_h) WHERE inductance_h IS NOT NULL");
            s.execute("CREATE INDEX " + t + "_pkg ON " + t + " (package_key, family)");
            s.execute("CREATE INDEX " + t + "_fam ON " + t + " (family)");
            s.execute("CREATE INDEX " + t + "_fts ON " + t + " (fts_rowid)");
        }
    }

    /** Inserts {@code rows}; {@code ftsRowids} holds the FTS5 rowid of each row (same order). */
    public static void insert(Connection connection, String table, List<PartIndexRow> rows, List<Long> ftsRowids)
            throws SQLException {
        Map<String, String> casts = PartIndexSql.casts();
        List<String> names = new ArrayList<>(casts.keySet());
        String sql = "INSERT INTO " + FieldSql.identifier(table) + " (fts_rowid, " + String.join(", ", names)
                + ", payload_md5) VALUES (?" + ", ?".repeat(names.size()) + ", '')";
        OffsetDateTime now = OffsetDateTime.now();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int r = 0; r < rows.size(); r++) {
                Map<String, Object> values = PartIndexSql.values(rows.get(r), now, JSON);
                ps.setObject(1, ftsRowids.get(r));
                int i = 2;
                for (String name : names) {
                    ps.setObject(i++, sqlite(values.get(name)));
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static Object sqlite(Object value) {
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (value instanceof Object[] array) {
            return JSON.writeValueAsString(Arrays.asList(array));
        }
        if (value instanceof OffsetDateTime t) {
            return t.toString();
        }
        return value;
    }

    private static String type(String cast) {
        return switch (cast) {
            case "float8" -> "REAL";
            case "int", "smallint", "boolean" -> "INTEGER";
            default -> "TEXT";
        };
    }
}
