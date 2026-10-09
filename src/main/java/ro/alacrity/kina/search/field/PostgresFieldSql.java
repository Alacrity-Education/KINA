package ro.alacrity.kina.search.field;

import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * The PostgreSQL dialect of {@link FieldSql} over {@code part_index} (migration V14, DESIGN.md 3.8 and 8):
 * {@code attrs} keys through {@code ->>} (equality through {@code @>}, served by the {@code jsonb_path_ops} GIN
 * index), value sets as array parameters ({@code = ANY(?)}, {@code <> ALL(?)}), free-text words as
 * {@code search_tsv @@ to_tsquery('simple', 'word:*')}, substrings and MPN prefixes as {@code LIKE} (trigram GIN).
 */
public class PostgresFieldSql extends FieldSql {

    /** The renderer for {@code part_index}. */
    public static final PostgresFieldSql INSTANCE = new PostgresFieldSql();

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    protected String table() {
        return "part_index";
    }

    @Override
    protected String text(IndexColumn column) {
        return column.json() ? "(attrs ->> '" + identifier(column.key()) + "')" : identifier(column.name());
    }

    @Override
    protected String number(IndexColumn column) {
        return column.json() ? "(attrs ->> '" + identifier(column.key()) + "')::float8" : identifier(column.name());
    }

    @Override
    protected String in(String value, List<String> values, List<Object> params) {
        params.add(values.toArray(String[]::new));
        return value + " = ANY(?)";
    }

    @Override
    protected String notIn(String value, List<String> values, List<Object> params) {
        params.add(values.toArray(String[]::new));
        return value + " <> ALL(?)";
    }

    @Override
    protected String equal(IndexColumn column, Object value, List<Object> params) {
        if (column.json()) {
            params.add(JSON.writeValueAsString(Map.of(column.key(), value)));
            return "(" + isNull(column) + " OR attrs @> ?::jsonb)";
        }
        params.add(value);
        return "(" + isNull(column) + " OR " + identifier(column.name()) + " = ?)";
    }

    @Override
    protected String anyInRange(IndexColumn column, double low, double high, List<Object> params) {
        params.add(low);
        params.add(high);
        String col = identifier(column.name());
        return "(cardinality(" + col + ") = 0 OR EXISTS (SELECT 1 FROM unnest(" + col
                + ") AS e(v) WHERE e.v BETWEEN ? AND ?))";
    }

    @Override
    protected String nonEmpty(IndexColumn column) {
        return "cardinality(" + identifier(column.name()) + ") > 0";
    }

    @Override
    protected String word(String token, List<Object> params) {
        params.add(token + ":*");
        return "search_tsv @@ to_tsquery('simple', ?)";
    }

    @Override
    protected Object bool(boolean value) {
        return value;
    }
}
