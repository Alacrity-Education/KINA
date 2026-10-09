package ro.alacrity.kina.search.field;

import java.util.ArrayList;
import java.util.List;

/**
 * The SQLite dialect of {@link FieldSql} (the LCSC typed table of phase B, DESIGN.md 3.8): the same columns, with
 * {@code attrs} and the array columns as JSON text ({@code json_extract}, {@code json_each}), booleans as 0 and 1, and
 * free-text words as an FTS5 {@code MATCH} on the trigram table of the JLCPCB file, joined by its rowid
 * ({@code fts_rowid}), as {@code JlcpcbQuery} does today; shorter tokens are {@code LIKE}.
 */
public class SqliteFieldSql extends FieldSql {

    /** The renderer for {@code part_index} joined to the FTS5 table {@code parts}. */
    public static final SqliteFieldSql INSTANCE = new SqliteFieldSql("part_index", "parts", false);

    /**
     * The confirmed subset of {@link #INSTANCE}: every value predicate demands a stated, matching value instead of
     * also keeping NULL ({@code col BETWEEN ? AND ?} instead of {@code col IS NULL OR col BETWEEN ? AND ?}). These are
     * exactly the rows {@code INSTANCE} flags {@code confirmed}, in a form the indexes can seek (SQLite cannot seek an
     * {@code IS NULL OR} predicate, it reads every row of the family: study 12.2, the planner lesson).
     */
    public static final SqliteFieldSql CONFIRMED = new SqliteFieldSql("part_index", "parts", true);

    private final String table;
    private final String ftsTable;
    private final boolean confirmedOnly;

    /** A renderer for the typed table {@code table} whose {@code fts_rowid} is the rowid of {@code ftsTable}. */
    public SqliteFieldSql(String table, String ftsTable) {
        this(table, ftsTable, false);
    }

    private SqliteFieldSql(String table, String ftsTable, boolean confirmedOnly) {
        this.table = identifier(table);
        this.ftsTable = identifier(ftsTable);
        this.confirmedOnly = confirmedOnly;
    }

    @Override
    protected String render(FieldPredicate p, List<Object> params) {
        if (!confirmedOnly) {
            return super.render(p, params);
        }
        return switch (p) {
            case FieldPredicate.Range r -> {
                params.add(r.low());
                params.add(r.high());
                yield number(r.column()) + " BETWEEN ? AND ?";
            }
            case FieldPredicate.AtLeast a -> {
                params.add(a.min());
                String col = number(a.column());
                yield a.rating() ? "(" + col + " >= ? OR " + col + " <= 0)" : col + " >= ?";
            }
            case FieldPredicate.AtMost a -> {
                params.add(a.max());
                yield number(a.column()) + " <= ?";
            }
            case FieldPredicate.Equal e -> {
                params.add(e.value() instanceof Boolean b ? bool(b) : e.value());
                yield text(e.column()) + " = ?";
            }
            case FieldPredicate.OneOf o -> in(text(o.column()), o.values(), params);
            case FieldPredicate.NoneOf n -> "(" + text(n.column()) + " IS NOT NULL AND "
                    + notIn(text(n.column()), n.values(), params) + ")";
            case FieldPredicate.AnyInRange a -> {
                params.add(a.low());
                params.add(a.high());
                String col = identifier(a.column().name());
                yield "EXISTS (SELECT 1 FROM json_each(" + col + ") WHERE value BETWEEN ? AND ?)";
            }
            case FieldPredicate.PackageIs pk -> {
                List<String> any = new ArrayList<>();
                if (pk.key() != null) {
                    any.add("package_key = ?");
                    params.add(pk.key());
                }
                if (pk.canDiameterMm() != null && pk.canLengthMm() != null) {
                    any.add("(can_d_mm BETWEEN ? AND ? AND can_l_mm BETWEEN ? AND ?)");
                    params.add(pk.canDiameterMm() - pk.margin());
                    params.add(pk.canDiameterMm() + pk.margin());
                    params.add(pk.canLengthMm() - pk.margin());
                    params.add(pk.canLengthMm() + pk.margin());
                }
                if (pk.neutralClass() != null) {
                    any.add("package_class = ?");
                    params.add(pk.neutralClass());
                }
                yield any.isEmpty() ? "package_readable = 1"
                        : "(package_readable = 1 AND (" + String.join(" OR ", any) + "))";
            }
            default -> super.render(p, params);
        };
    }

    private static final String SELECT_PREFIX = "SELECT distributor, part_number, (";
    private static final String FROM = " AS confirmed FROM ";
    private static final String ORDER = " ORDER BY confirmed DESC, distributor, part_number LIMIT ?";

    /**
     * The candidates of one step for the LCSC retriever: {@code fts_rowid, part_number, stated, total}, the parts
     * that state most of the requested attributes first, then the highest stock, then the part number; {@code total} is the number of rows the step matches (a
     * window function, one pass). The rows of the JLCPCB table are read by the FTS rowid.
     */
    public Statement candidates(FieldQuery query, FieldQuery.Step step, int limit) {
        Statement base = select(query, step, limit);
        String sql = base.sql();
        if (!sql.startsWith(SELECT_PREFIX) || !sql.endsWith(ORDER)) {
            throw new IllegalStateException("unexpected field statement: " + sql);
        }
        String body = sql.substring(SELECT_PREFIX.length(), sql.length() - ORDER.length());
        int from = body.indexOf(FROM);
        // "(a AND b AND c)" becomes "(a) + (b) + (c)": how many of the requested attributes the part states, so a
        // part that states all but one ranks before one that states none (the base orders by all-or-nothing)
        String confirmed = body.substring(0, from);
        confirmed = confirmed.substring(0, confirmed.length() - 1);   // the closing parenthesis of "(... )"
        String stated = "(" + String.join(") + (", confirmed.split(" AND ")) + ")";
        // the window function counts every matching row in the same pass (before ORDER BY and LIMIT)
        sql = "SELECT fts_rowid, part_number, " + stated + " AS stated, count(*) OVER () AS total FROM "
                + body.substring(from + FROM.length())
                + " ORDER BY stated DESC, stock DESC, part_number LIMIT ?";
        return new Statement(sql, base.params());
    }

    /** {@code SELECT count(*)} of the rows one step matches. */
    public Statement count(FieldQuery query, FieldQuery.Step step) {
        Statement base = select(query, step, 1);
        String sql = base.sql();
        int from = sql.indexOf(FROM);
        if (from < 0 || !sql.endsWith(ORDER)) {
            throw new IllegalStateException("unexpected field statement: " + sql);
        }
        String rest = sql.substring(from + FROM.length(), sql.length() - ORDER.length());
        return new Statement("SELECT count(*) FROM " + rest, base.params().subList(0, base.params().size() - 1));
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
