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
 * <p>The statement returns {@code distributor, part_number, confirmed} ordered by the requested ratings
 * ({@link FieldQuery#ratings()}, {@link #rated}: per rating 2 when the part states it and meets it, 1 when it does not
 * state it, 0 when it is below spec) descending, then by {@code confirmed} (the part states every column of the step's
 * constraint predicates) descending, then by the soft kinds it matches, then by distributor and part number, so the
 * order is stable: confirmed parts first, a part below spec last (it only reaches the Java check, which excludes and
 * counts it, when the limit leaves room). The ratings and the soft kinds only order: they are never in the
 * {@code WHERE} clause (except in the confirmed-only form of {@link SqliteFieldSql#CONFIRMED}, which selects the
 * first tier of that order).
 */
public abstract class FieldSql {

    /** A statement and its positional parameters. */
    public record Statement(String sql, List<Object> params) {

        public Statement {
            params = List.copyOf(params);
        }
    }

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*");

    /**
     * True for the stated-only form: every value predicate demands a stated, matching value instead of also keeping
     * the rows that do not state it, and the requested ratings are those of the first tier of the dialect's order
     * (SQLite: stated; PostgreSQL: stated and met). These are exactly the rows the superset form orders first, in the
     * same order, in a form an index can seek: when they fill the limit they are the superset's first rows (review
     * A8: the {@code col IS NULL OR} form cannot use the partial value indexes, PostgreSQL reads the family).
     */
    private final boolean statedOnly;

    /** The superset form ({@code statedOnly} false) or the stated-only form of a dialect. */
    protected FieldSql(boolean statedOnly) {
        this.statedOnly = statedOnly;
    }

    /** True for the stated-only form ({@link #statedOnly}). */
    public final boolean statedOnly() {
        return statedOnly;
    }

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

    /** An equality on a column or an {@code attrs} key, NULL kept unless {@link #statedOnly}. */
    protected abstract String equal(IndexColumn column, Object value, List<Object> params);

    /**
     * The array column has an element in {@code [low, high]}, or (unless {@link #statedOnly}) it is empty.
     */
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
        String rated = rated(query.ratings(), params);
        String soft = order(query.soft(), params);
        params.add(limit);
        return new Statement("SELECT distributor, part_number, (" + confirmed(body) + ") AS confirmed FROM "
                + body.from() + " ORDER BY " + (rated == null ? "" : rated + " DESC, ") + "confirmed DESC, " + soft
                + "distributor, part_number LIMIT ?", params);
    }

    /**
     * The order term of the requested ratings, an integer expression, null without ratings: per rating 2 when the part
     * states it and meets it (confirmed), 1 when it does not state it (unverified, returnable), 0 when it states a
     * value below the request (below spec, excluded unless {@code allow_below_spec}). Its parameters are added to
     * {@code params}.
     */
    protected String rated(List<FieldPredicate> ratings, List<Object> params) {
        List<String> terms = new ArrayList<>();
        for (FieldPredicate p : ratings) {
            List<String> stated = statedSql(p);
            if (stated.isEmpty()) {
                continue;
            }
            String isStated = String.join(" AND ", stated);
            String match = render(p, params);
            terms.add("CASE WHEN " + isStated + " AND " + match + " THEN 2 WHEN " + isStated + " THEN 0 ELSE 1 END");
        }
        return terms.isEmpty() ? null : "(" + String.join(" + ", terms) + ")";
    }

    /**
     * The order term of the soft kinds ({@link FieldQuery#soft()}) followed by {@code ", "}, empty without: how many of
     * them the part states with a matching value. Its parameters are added to {@code params}. Each predicate counts as 0 or 1 ({@code CASE}), so
     * the sum is an integer in both dialects.
     */
    protected String order(List<FieldPredicate> predicates, List<Object> params) {
        String count = matched(predicates, params);
        return count == null ? "" : count + " DESC, ";
    }

    /**
     * How many of {@code predicates} the part states with a matching value, as an integer expression (each
     * {@code CASE WHEN <stated> AND <predicate> THEN 1 ELSE 0 END}); null without predicates. Its parameters are added
     * to {@code params}.
     */
    protected String matched(List<FieldPredicate> predicates, List<Object> params) {
        List<String> terms = new ArrayList<>();
        for (FieldPredicate p : predicates) {
            List<String> stated = statedSql(p);
            String match = render(p, params);
            terms.add("CASE WHEN " + (stated.isEmpty() ? "" : String.join(" AND ", stated) + " AND ") + match
                    + " THEN 1 ELSE 0 END");
        }
        return terms.isEmpty() ? null : "(" + String.join(" + ", terms) + ")";
    }


    /**
     * True when the requested ratings count among the stated columns ({@code confirmed}, SQLite's {@code stated}):
     * only the LCSC dialect, which orders by what the part states and then by stock, so a part below spec takes its
     * place among the candidates as in an FTS window; the PostgreSQL dialect orders by {@link #rated} instead.
     */
    protected boolean ratingsStated() {
        return false;
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
        // the ratings only order: a part below spec stays a candidate for the Java check. The stated-only form
        // selects the first tier of the dialect's order: SQLite counts a stated rating (met or below spec) among the
        // stated columns, PostgreSQL orders a rating that is stated and met first ({@link #rated})
        for (FieldPredicate p : query.ratings()) {
            if (ratingsStated()) {
                stated.addAll(statedSql(p));
            }
            if (statedOnly) {
                inner.addAll(statedSql(p));
                if (!ratingsStated()) {
                    inner.add(render(p, innerParams));
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
                yield "(" + orUnstated(isNull(r.column())) + number(r.column()) + " BETWEEN ? AND ?)";
            }
            case AtLeast a -> {
                params.add(a.min());
                String col = number(a.column());
                yield "(" + orUnstated(isNull(a.column())) + col + " >= ?" + (a.rating() ? " OR " + col + " <= 0" : "")
                        + ")";
            }
            case AtMost a -> {
                params.add(a.max());
                yield "(" + orUnstated(isNull(a.column())) + number(a.column()) + " <= ?)";
            }
            case Equal e -> equal(e.column(), e.value(), params);
            case OneOf o -> "(" + orUnstated(isNull(o.column())) + in(text(o.column()), o.values(), params) + ")";
            // NOT IN is never true for NULL: the stated-only form needs no IS NOT NULL
            case NoneOf n -> "(" + orUnstated(isNull(n.column())) + notIn(text(n.column()), n.values(), params) + ")";
            case AnyInRange a -> anyInRange(a.column(), a.low(), a.high(), params);
            case PackageIs pk -> {
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
                String alternatives = String.join(" OR ", any);
                if (statedOnly) {
                    // a readable package that matches (a package KINA cannot read is the unstated branch)
                    yield any.isEmpty() ? "package_readable = " + bool(true)
                            : "(package_readable = " + bool(true) + " AND (" + alternatives + "))";
                }
                yield "(package_readable = " + bool(false) + (any.isEmpty() ? "" : " OR " + alternatives) + ")";
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

    /**
     * The branch of a value predicate that keeps a row which does not state the column: {@code unstated + " OR "} in
     * the superset form, empty in the stated-only form ({@link #statedOnly}). The one place the two forms differ.
     */
    protected final String orUnstated(String unstated) {
        return statedOnly ? "" : unstated + " OR ";
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
