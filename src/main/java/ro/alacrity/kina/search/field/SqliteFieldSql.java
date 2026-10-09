package ro.alacrity.kina.search.field;

import java.util.List;

/**
 * The SQLite dialect of {@link FieldSql} (the LCSC typed table of phase B, DESIGN.md 3.8): the same columns, with
 * {@code attrs} and the array columns as JSON text ({@code json_extract}, {@code json_each}), booleans as 0 and 1, and
 * free-text words as an FTS5 {@code MATCH} on the trigram table of the JLCPCB file, joined by its rowid
 * ({@code fts_rowid}), as {@code JlcpcbQuery} does today; shorter tokens are {@code LIKE}.
 */
public class SqliteFieldSql extends FieldSql {

    /** The renderer for {@code part_index} joined to the FTS5 table {@code parts}. */
    public static final SqliteFieldSql INSTANCE = new SqliteFieldSql("part_index", "parts");

    private final String table;
    private final String ftsTable;

    /** A renderer for the typed table {@code table} whose {@code fts_rowid} is the rowid of {@code ftsTable}. */
    public SqliteFieldSql(String table, String ftsTable) {
        this.table = identifier(table);
        this.ftsTable = identifier(ftsTable);
    }

    @Override
    protected String table() {
        return table;
    }

    @Override
    protected String text(IndexColumn column) {
        return column.json() ? "json_extract(attrs, '$." + identifier(column.key()) + "')"
                : identifier(column.name());
    }

    @Override
    protected String number(IndexColumn column) {
        return text(column);
    }

    @Override
    protected String in(String value, List<String> values, List<Object> params) {
        params.addAll(values);
        return value + " IN (" + placeholders(values.size()) + ")";
    }

    @Override
    protected String notIn(String value, List<String> values, List<Object> params) {
        params.addAll(values);
        return value + " NOT IN (" + placeholders(values.size()) + ")";
    }

    @Override
    protected String equal(IndexColumn column, Object value, List<Object> params) {
        params.add(value instanceof Boolean b ? bool(b) : value);
        return "(" + isNull(column) + " OR " + text(column) + " = ?)";
    }

    @Override
    protected String anyInRange(IndexColumn column, double low, double high, List<Object> params) {
        params.add(low);
        params.add(high);
        String col = identifier(column.name());
        return "(json_array_length(" + col + ") = 0 OR EXISTS (SELECT 1 FROM json_each(" + col
                + ") WHERE value BETWEEN ? AND ?))";
    }

    @Override
    protected String nonEmpty(IndexColumn column) {
        return "json_array_length(" + identifier(column.name()) + ") > 0";
    }

    @Override
    protected String word(String token, List<Object> params) {
        params.add("\"" + token.replace("\"", "\"\"") + "\"");
        return "fts_rowid IN (SELECT rowid FROM " + ftsTable + " WHERE " + ftsTable + " MATCH ?)";
    }

    @Override
    protected Object bool(boolean value) {
        return value ? 1 : 0;
    }

    private static String placeholders(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }
}
