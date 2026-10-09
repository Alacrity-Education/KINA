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

    /** Creates {@code table} and its indexes on {@code connection}. */
    public static void create(Connection connection, String table) throws SQLException {
        createTable(connection, table);
        createIndexes(connection, table);
    }

    /**
     * Creates {@code table} without its indexes (a bulk load is faster that way). Besides the columns of
     * {@code part_index} it holds {@code fts_rowid} and {@code stock} (the ships-now stock, to order candidates).
     */
    public static void createTable(Connection connection, String table) throws SQLException {
        String t = FieldSql.identifier(table);
        List<String> columns = new ArrayList<>();
        columns.add("fts_rowid INTEGER");
        PartIndexSql.types().forEach((name, type) -> columns.add(name + " " + type.sqlite()));
        columns.add("payload_md5 TEXT");
        columns.add("stock INTEGER");
        try (Statement s = connection.createStatement()) {
            s.execute("CREATE TABLE " + t + " (" + String.join(", ", columns)
                    + ", PRIMARY KEY (distributor, part_number))");
        }
    }

    /** Creates the indexes of {@code table}. */
    public static void createIndexes(Connection connection, String table) throws SQLException {
        String t = FieldSql.identifier(table);
        try (Statement s = connection.createStatement()) {
            s.execute("CREATE INDEX " + t + "_cap ON " + t + " (family, capacitance_f) WHERE capacitance_f IS NOT NULL");
            s.execute("CREATE INDEX " + t + "_res ON " + t + " (family, resistance_ohm) WHERE resistance_ohm IS NOT NULL");
            s.execute("CREATE INDEX " + t + "_ind ON " + t + " (family, inductance_h) WHERE inductance_h IS NOT NULL");
            s.execute("CREATE INDEX " + t + "_pos ON " + t + " (family, positions) WHERE positions IS NOT NULL");
            s.execute("CREATE INDEX " + t + "_usb ON " + t + " (family, usb_type) WHERE usb_type IS NOT NULL");
            s.execute("CREATE INDEX " + t + "_pkg ON " + t + " (package_key, family)");
            s.execute("CREATE INDEX " + t + "_fam ON " + t + " (family)");
            s.execute("CREATE INDEX " + t + "_fts ON " + t + " (fts_rowid)");
        }
    }

    /** Inserts {@code rows}; {@code ftsRowids} holds the FTS5 rowid of each row (same order); stock 0. */
    public static void insert(Connection connection, String table, List<PartIndexRow> rows, List<Long> ftsRowids)
            throws SQLException {
        insert(connection, table, rows, ftsRowids, null);
    }

    /** Inserts {@code rows} with their FTS5 rowids and ships-now {@code stocks} (same order; null: 0). */
    public static void insert(Connection connection, String table, List<PartIndexRow> rows, List<Long> ftsRowids,
                              List<Integer> stocks) throws SQLException {
        List<String> names = new ArrayList<>(PartIndexSql.types().keySet());
        String sql = "INSERT INTO " + FieldSql.identifier(table) + " (fts_rowid, " + String.join(", ", names)
                + ", payload_md5, stock) VALUES (?" + ", ?".repeat(names.size()) + ", '', ?)";
        OffsetDateTime now = OffsetDateTime.now();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int r = 0; r < rows.size(); r++) {
                Map<String, Object> values = PartIndexSql.values(rows.get(r), now, JSON);
                ps.setObject(1, ftsRowids.get(r));
                int i = 2;
                for (String name : names) {
                    ps.setObject(i++, sqlite(values.get(name)));
                }
                ps.setObject(i, stocks == null ? 0 : stocks.get(r));
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
}
