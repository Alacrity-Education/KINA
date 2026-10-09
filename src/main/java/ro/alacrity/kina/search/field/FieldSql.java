package ro.alacrity.kina.search.field;

import ro.alacrity.kina.domain.ConstraintKind;
import ro.alacrity.kina.search.field.FieldPredicate.Absent;
import ro.alacrity.kina.search.field.FieldPredicate.AnyInRange;
import ro.alacrity.kina.search.field.FieldPredicate.AtLeast;
import ro.alacrity.kina.search.field.FieldPredicate.AtMost;
import ro.alacrity.kina.search.field.FieldPredicate.DistributorIs;
import ro.alacrity.kina.search.field.FieldPredicate.Equal;
import ro.alacrity.kina.search.field.FieldPredicate.InStock;
import ro.alacrity.kina.search.field.FieldPredicate.MpnPrefix;
import ro.alacrity.kina.search.field.FieldPredicate.NoneOf;
import ro.alacrity.kina.search.field.FieldPredicate.OneOf;
import ro.alacrity.kina.search.field.FieldPredicate.PackageIs;
import ro.alacrity.kina.search.field.FieldPredicate.Range;
import ro.alacrity.kina.search.field.FieldPredicate.Substring;
import ro.alacrity.kina.search.field.FieldPredicate.Word;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Renders a {@link FieldQuery} step as SQL (DESIGN.md 3.8). The two dialects ({@link PostgresFieldSql},
 * {@link SqliteFieldSql}) render the same predicates; only the column access (JSON keys, arrays, lists) and the free
 * text differ. Every value predicate keeps NULL columns and compares the bare column with constants.
 *
 * <p>The statement returns {@code distributor, part_number, confirmed} ordered by {@code confirmed} (every stated
 * column of the step's constraint predicates is stated by the part) descending, then by distributor and part number,
 * so the order is stable.
 */
public abstract class FieldSql {

    /** A statement and its positional parameters. */
    public record Statement(String sql, List<Object> params) {

        public Statement {
            params = List.copyOf(params);
        }
    }

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*");

    /** The table the statement reads. */
    protected abstract String table();

    /** A typed value or the value of an {@code attrs} key, as text. */
    protected abstract String text(IndexColumn column);

    /** A typed value or the value of an {@code attrs} key, as a number. */
    protected abstract String number(IndexColumn column);

    /** The SQL of {@code value IN (values)} with its parameters added to {@code params}. */
    protected abstract String in(String value, List<String> values, List<Object> params);

    /** The SQL of {@code value NOT IN (values)} with its parameters added to {@code params}. */
    protected abstract String notIn(String value, List<String> values, List<Object> params);

    /** An equality on a column or an {@code attrs} key, NULL kept. */
    protected abstract String equal(IndexColumn column, Object value, List<Object> params);

    /** {@code array column is empty or has an element in [low, high]}. */
    protected abstract String anyInRange(IndexColumn column, double low, double high, List<Object> params);

    /** True when the array column holds an element. */
    protected abstract String nonEmpty(IndexColumn column);

    /** A free-text word (3 or more letters and digits). */
    protected abstract String word(String token, List<Object> params);

    /** A parameter value for a boolean. */
    protected abstract Object bool(boolean value);

    /** The statement of {@code query}'s step {@code step}, at most {@code limit} rows. */
    public Statement select(FieldQuery query, int step, int limit) {
        return select(query, query.step(step), limit);
    }

    /** The statement of one step of {@code query}, at most {@code limit} rows. */
    public Statement select(FieldQuery query, FieldQuery.Step step, int limit) {
        return select(query, step, limit, null);
    }

    /**
     * The statement of one step of {@code query} restricted to the part numbers {@code among} (null: every part), at
     * most {@code limit} rows.
     */
    public Statement select(FieldQuery query, FieldQuery.Step step, int limit, List<String> among) {
        Body body = body(query, step, among);
        List<Object> params = new ArrayList<>(body.params());
        String soft = soft(query, params);
        params.add(limit);
        return new Statement("SELECT distributor, part_number, (" + confirmed(body) + ") AS confirmed FROM "
                + body.from() + " ORDER BY confirmed DESC, " + soft + "distributor, part_number LIMIT ?", params);
    }

    /**
     * The order term of the soft kinds ({@link FieldQuery#soft()}) followed by {@code ", "}, empty without: how many
     * of them the part states with a matching value. Its parameters are added to {@code params}.
     */
    protected String soft(FieldQuery query, List<Object> params) {
        List<String> terms = new ArrayList<>();
        for (FieldPredicate p : query.soft()) {
            List<String> stated = statedSql(p);
            String match = render(p, params);
            terms.add("(" + (stated.isEmpty() ? "" : String.join(" AND ", stated) + " AND ") + match + ")");
        }
        return terms.isEmpty() ? "" : "(" + String.join(") + (", terms) + ") DESC, ";
    }

    /**
     * The parts of a step's statement, for a dialect that selects other columns or orders differently
     * ({@link SqliteFieldSql#candidates}): the stated conditions (one per requested column, without parameters), those
     * of the family ({@code familyStated}, a subset), the {@code table [WHERE ...]} clause and its parameters.
     */
    protected record Body(List<String> stated, List<String> familyStated, String from, List<Object> params) {

        protected Body {
            stated = List.copyOf(stated);
            familyStated = List.copyOf(familyStated);
            params = List.copyOf(params);
        }
    }

    /** True when the part states every requested column: the conjunction of the body's stated conditions. */
    protected String confirmed(Body body) {
        return body.stated().isEmpty() ? bool(true).toString() : String.join(" AND ", body.stated());
    }

    /** The parts of one step's statement restricted to {@code among} (null: every part). */
    protected Body body(FieldQuery query, FieldQuery.Step step, List<String> among) {
        List<Object> params = new ArrayList<>();
        List<Object> innerParams = new ArrayList<>();
        List<String> where = new ArrayList<>();
        List<String> inner = new ArrayList<>();
        Set<String> stated = new LinkedHashSet<>();
        Set<String> familyStated = new LinkedHashSet<>();
        for (FieldPredicate p : step.predicates()) {
            // the family and the always-on rules hold for every row; the others keep rows of an older extractor
            if (p.kind() != null && p.kind() != ConstraintKind.TYPE) {
                inner.add(render(p, innerParams));
            } else {
                where.add(render(p, params));
            }
            if (p.kind() != null) {
                stated.addAll(statedSql(p));
                if (p.kind() == ConstraintKind.TYPE) {
                    familyStated.addAll(statedSql(p));
                }
            }
        }
        if (!inner.isEmpty()) {
            String all = String.join(" AND ", inner);
            if (query.staleBelow() > 0) {
                where.add("(extractor_version < ? OR (" + all + "))");
                params.add(query.staleBelow());
            } else {
                where.add(all);
            }
            params.addAll(innerParams);
        }
        if (among != null) {
            where.add(among.isEmpty() ? bool(false).toString() : in("part_number", among, params));
        }
        return new Body(List.copyOf(stated), List.copyOf(familyStated),
                table() + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where)), params);
    }

    /** One predicate. */
    protected String render(FieldPredicate p, List<Object> params) {
        return switch (p) {
            case DistributorIs d -> {
                params.add(d.distributor().name());
                yield "distributor = ?";
            }
            case InStock s -> "in_stock = " + bool(true);
            case Range r -> {
                params.add(r.low());
                params.add(r.high());
                yield "(" + isNull(r.column()) + " OR " + number(r.column()) + " BETWEEN ? AND ?)";
            }
            case AtLeast a -> {
                params.add(a.min());
                String col = number(a.column());
                yield "(" + isNull(a.column()) + " OR " + col + " >= ?" + (a.rating() ? " OR " + col + " <= 0" : "")
                        + ")";
            }
            case AtMost a -> {
                params.add(a.max());
                yield "(" + isNull(a.column()) + " OR " + number(a.column()) + " <= ?)";
            }
            case Equal e -> equal(e.column(), e.value(), params);
            case OneOf o -> "(" + isNull(o.column()) + " OR " + in(text(o.column()), o.values(), params) + ")";
            case NoneOf n -> "(" + isNull(n.column()) + " OR " + notIn(text(n.column()), n.values(), params) + ")";
            case AnyInRange a -> anyInRange(a.column(), a.low(), a.high(), params);
            case PackageIs pk -> {
                StringBuilder sql = new StringBuilder("(package_readable = ").append(bool(false));
                if (pk.key() != null) {
                    sql.append(" OR package_key = ?");
                    params.add(pk.key());
                }
                if (pk.canDiameterMm() != null && pk.canLengthMm() != null) {
                    sql.append(" OR (can_d_mm BETWEEN ? AND ? AND can_l_mm BETWEEN ? AND ?)");
                    params.add(pk.canDiameterMm() - pk.margin());
                    params.add(pk.canDiameterMm() + pk.margin());
                    params.add(pk.canLengthMm() - pk.margin());
                    params.add(pk.canLengthMm() + pk.margin());
                }
                if (pk.neutralClass() != null) {
                    sql.append(" OR package_class = ?");
                    params.add(pk.neutralClass());
                }
                yield sql.append(')').toString();
            }
            case Absent a -> isNull(a.column());
            case Word w -> word(w.token(), params);
            case Substring s -> {
                params.add("%" + escapeLike(s.token()) + "%");
                yield "search_text LIKE ? ESCAPE '\\'";
            }
            case MpnPrefix m -> {
                List<String> any = new ArrayList<>();
                for (String prefix : m.prefixes()) {
                    params.add(escapeLike(prefix) + "%");
                    any.add("mpn LIKE ? ESCAPE '\\'");
                }
                yield "(" + String.join(" OR ", any) + ")";
            }
        };
    }

    /** The SQL that is true when the part states what {@code p} compares (for the confirmed flag). */
    private List<String> statedSql(FieldPredicate p) {
        if (p instanceof PackageIs) {
            return List.of("package_readable = " + bool(true));
        }
        if (p instanceof AnyInRange a) {
            return List.of(nonEmpty(a.column()));
        }
        return p.stated().stream().map(c -> "NOT " + isNull(c)).toList();
    }

    /** {@code col IS NULL}, or the key absent from {@code attrs}. */
    protected String isNull(IndexColumn column) {
        return (column.json() ? text(column) : identifier(column.name())) + " IS NULL";
    }

    /** A checked column name or JSON key (they come from the declarations, never from a request). */
    protected static String identifier(String name) {
        if (!IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("not a column or key name: " + name);
        }
        return name;
    }

    /** {@code token} with the LIKE wildcards and the escape character escaped. */
    protected static String escapeLike(String token) {
        return token.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
