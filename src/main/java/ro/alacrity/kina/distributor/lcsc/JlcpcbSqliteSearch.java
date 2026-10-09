package ro.alacrity.kina.distributor.lcsc;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.sqlite.Function;
import org.sqlite.SQLiteConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.config.KinaProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.time.Duration;
import java.util.stream.Collectors;

/**
 * Read-only access to the downloaded JLCPCB FTS5 database (DESIGN.md 9.3).
 *
 * <p>A pool of {@code kina.jlcpcb.pool-size} read-only connections, opened {@code mode=ro&immutable=1} (the file is
 * never modified while open: a refresh renames a new file into place) and guarded by a {@link ReentrantReadWriteLock}:
 * a query takes the read lock and borrows a connection (waiting at most {@code kina.jlcpcb.pool-wait}), swapping the
 * file ({@link #replaceDatabase}) or {@link #reopen() reopening} takes the write lock and closes and reopens every
 * connection. When the typed-table sidecar is current ({@link FieldIndexFile}) every connection has it attached as
 * {@code idx}.
 *
 * <p>Query building: terms of 3+ characters go into {@code parts MATCH '"a" AND "b"'}, shorter ones become
 * {@code "Description" LIKE '%tok%' ESCAPE '\'}; value terms ({@code 10k}, {@code 100nF}, {@code 5%}) additionally must
 * appear in the description not directly preceded by a digit or '.', so {@code 10k} does not match {@code 510kΩ}
 * (trigram matching is plain substring matching). Always {@code CAST("Stock" AS INTEGER) > 0}, ordered by
 * {@code rank, stock DESC}.
 *
 * <p>Query relaxation (DESIGN.md 9.3): when the full AND query ({@link MatchMode#ALL}) has no in-stock match,
 * {@link MatchMode#RELAXED} first removes the terms that occur nowhere in the database (one cheap
 * {@code MATCH ... LIMIT 1} probe per term: {@code dupont}, {@code THT} wording...), then drops terms one at a time,
 * least informative first ({@link #DROP_ORDER}: free-text keywords, USB standard/features, mounting, orientation, pitch,
 * dielectric, tolerance, package, rating, value, positions, family, category; later terms of the same kind before
 * earlier ones; the relaxable dielectric and tolerance go before the package, which is hard for most families, and a
 * minimum rating goes after them because the ranker excludes parts below it anyway) and retries while at least
 * {@value #MIN_RELAXED_TERMS} terms remain. A step whose terms are exactly the parametric ones is reported as
 * {@link MatchMode#PARAMETRIC}. Then {@link MatchMode#PARAMETRIC} (only values, packages, dielectrics, family and
 * connector terms, still AND) when not tried yet, then {@link MatchMode#ANY} (every 3+ character term OR-ed, no
 * value-boundary check; BM25 puts rows matching more terms first). The first step with a non-zero count is used for
 * both the count and the page, so pagination is stable.
 */
@Component
@Slf4j
public class JlcpcbSqliteSearch {

    static final String VALUE_FUNCTION = "kina_value";
    /** {@code kina_at_least(text, unit, minimum)}: the text states a value of the unit (V, A, W) of at least minimum. */
    static final String RATING_FUNCTION = "kina_at_least";
    /** A value with an SI prefix and a rating unit at a number boundary: {@code 25V}, {@code 1.5kV}, {@code 125mW}. */
    private static final Pattern RATED_VALUE = Pattern.compile(
            "(?<![\\d.\\p{L}])(\\d+(?:\\.\\d+)?)\\s?([umkM]?)([VAW])(?![a-zA-Z])");

    static final String COLUMNS = """
            "LCSC Part", "First Category", "Second Category", "MFR.Part", "Package", "Solder Joint", \
            "Manufacturer", "Library Type", "Description", "Datasheet", "Price", "Stock\"""";
    private static final String IN_STOCK = "CAST(\"Stock\" AS INTEGER) > 0";

    public enum MatchMode { ALL, RELAXED, PARAMETRIC, ANY }

    /**
     * Relaxation drops terms in this order of kinds (first = least informative); a tolerance (a {@code VALUE} term
     * ending in {@code %}) goes right after the dielectric and before the package ({@link #dropRank}): the package is a
     * hard constraint for most families, so a part found without it is excluded by the ranker.
     */
    static final List<JlcpcbQuery.Kind> DROP_ORDER = List.of(JlcpcbQuery.Kind.KEYWORD, JlcpcbQuery.Kind.FEATURE,
            JlcpcbQuery.Kind.MOUNTING, JlcpcbQuery.Kind.ORIENTATION, JlcpcbQuery.Kind.PITCH,
            JlcpcbQuery.Kind.DIELECTRIC, JlcpcbQuery.Kind.PACKAGE, JlcpcbQuery.Kind.RATING, JlcpcbQuery.Kind.VALUE,
            JlcpcbQuery.Kind.POSITIONS, JlcpcbQuery.Kind.FAMILY, JlcpcbQuery.Kind.CATEGORY);
    /** Relaxation never drops below this many terms (a single term is too vague; PARAMETRIC/ANY follow). */
    static final int MIN_RELAXED_TERMS = 2;

    /**
     * @param rows    the requested page of in-stock rows
     * @param total   number of in-stock rows matching the predicate of {@code mode}
     * @param mode    the relaxation step that produced the rows ({@code null} when the query had no usable terms)
     * @param dropped the terms the relaxation removed (empty for {@link MatchMode#ALL})
     */
    public record Result(List<JlcpcbRow> rows, int total, MatchMode mode, List<String> dropped, int outOfStock) {

        public Result(List<JlcpcbRow> rows, int total, MatchMode mode, List<String> dropped) {
            this(rows, total, mode, dropped, 0);
        }

        /** The same result with the number of rows that match every term but have no stock. */
        Result withOutOfStock(int count) {
            return new Result(rows, total, mode, dropped, count);
        }

        public Result {
            rows = List.copyOf(rows);
            dropped = dropped == null ? List.of() : List.copyOf(dropped);
        }

        public Result(List<JlcpcbRow> rows, int total, MatchMode mode) {
            this(rows, total, mode, List.of());
        }

        static Result empty() {
            return new Result(List.of(), 0, null);
        }
    }

    /** One SQL predicate with its bind parameters. */
    record Predicate(String where, List<Object> params, boolean hasMatch) {
    }

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    @Autowired private KinaProperties properties;
    private Path databaseFile;
    private Path indexFile;
    private int poolSize;
    private Duration poolWait;
    private boolean fieldIndexEnabled;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private BlockingQueue<Connection> idle;                  // null when closed; guarded by lock
    private final List<Connection> pool = new ArrayList<>(); // every open connection; guarded by lock
    private volatile FieldIndexFile.Info fieldIndex;         // the attached sidecar, null when none
    /** How the sidecar is attached to a connection ({@link FieldIndexFile#attach}; tests make it fail). */
    private Attacher attacher = FieldIndexFile::attach;

    /** Attaches the sidecar file to a connection. */
    @FunctionalInterface
    interface Attacher {
        void attach(Connection connection, Path sidecar) throws SQLException;
    }

    @PostConstruct
    void init() {
        databaseFile = properties.jlcpcb().databaseFile().toAbsolutePath().normalize();
        indexFile = FieldIndexFile.sidecar(databaseFile);
        poolSize = properties.jlcpcb().poolSize();
        poolWait = properties.jlcpcb().poolWait();
        fieldIndexEnabled = properties.jlcpcb().fieldIndex().enabled();
    }

    public Path databaseFile() {
        return databaseFile;
    }

    /** The sidecar file with the typed table ({@code <library>.index.db}). */
    public Path indexFile() {
        return indexFile;
    }

    /** True when a database is open; lazily opens an existing file. */
    public boolean isAvailable() {
        lock.readLock().lock();
        try {
            if (idle != null) {
                return true;
            }
        } finally {
            lock.readLock().unlock();
        }
        if (!Files.isRegularFile(databaseFile)) {
            return false;
        }
        lock.writeLock().lock();
        try {
            if (idle == null) {
                openLocked();
            }
            return idle != null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** What the attached typed-table sidecar says; null when none is attached (missing, older or foreign). */
    FieldIndexFile.Info fieldIndex() {
        return isAvailable() ? fieldIndex : null;
    }

    /** True when the typed table is attached on every connection of the pool. */
    public boolean fieldIndexAvailable() {
        return fieldIndex() != null;
    }

    /** Connections idle right now (diagnostics and tests). */
    int idleConnections() {
        lock.readLock().lock();
        try {
            return idle == null ? 0 : idle.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Runs {@code work} on a borrowed connection under the read lock (a swap waits for it), waiting at most
     * {@code maxWait} (the pool wait when null) for a free connection.
     *
     * @throws SQLException          when no connection became free in time or {@code work} fails
     * @throws IllegalStateException when no database is available
     */
    public <T> T withConnection(Duration maxWait, SqlFunction<T> work) throws SQLException {
        isAvailable();   // lazily opens an existing file
        lock.readLock().lock();
        try {
            Connection c = borrow(maxWait == null ? poolWait : maxWait);
            try {
                return work.apply(c);
            } finally {
                idle.offer(c);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    /** A connection of the pool; the read lock is held. */
    private Connection borrow(Duration maxWait) throws SQLException {
        if (idle == null) {
            throw new IllegalStateException("JLCPCB database not available: " + databaseFile);
        }
        try {
            Connection c = idle.poll(Math.max(0, maxWait.toNanos()), TimeUnit.NANOSECONDS);
            if (c == null) {
                throw new SQLException("no free JLCPCB connection within " + maxWait.toMillis() + " ms");
            }
            return c;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("interrupted while waiting for a JLCPCB connection", e);
        }
    }

    /** Closes the current connection (if any) and opens the file again (if it exists). */
    public void reopen() {
        lock.writeLock().lock();
        try {
            closeLocked();
            openLocked();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Runs {@code swap} (e.g. the atomic file move) with the connection closed under the write lock, then reopens. */
    public void replaceDatabase(IoAction swap) throws IOException {
        lock.writeLock().lock();
        try {
            closeLocked();
            try {
                swap.run();
            } finally {
                openLocked();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @FunctionalInterface
    public interface IoAction {
        void run() throws IOException;
    }

    @PreDestroy
    public void close() {
        lock.writeLock().lock();
        try {
            closeLocked();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Searches in-stock parts with relaxation (see class comment).
     *
     * @throws IllegalStateException when no database is available
     */
    public Result search(String query, int offset, int limit) throws SQLException {
        JlcpcbQuery parsed = JlcpcbQuery.parse(query);
        if (parsed.isEmpty()) {
            return Result.empty();
        }
        return withConnection(null, c -> {
            Predicate all = predicate(parsed, MatchMode.ALL);
            int total = all == null ? 0 : count(c, all);
            if (total > 0) {
                return found(c, query, all, total, offset, limit, MatchMode.ALL, List.of());
            }
            // every term matched, but nothing in stock: count those rows (out_of_stock_matches, DESIGN.md 3.2)
            int outOfStock = all == null ? 0 : countIgnoringStock(c, all);
            Result relaxed = relax(c, query, parsed, offset, limit);
            return relaxed.withOutOfStock(outOfStock);
        });
    }

    /** RELAXED, then PARAMETRIC and ANY (class comment), after ALL found nothing in stock. */
    private Result relax(Connection c, String query, JlcpcbQuery parsed, int offset, int limit) throws SQLException {
        // RELAXED: remove dead terms, then drop the least informative term one at a time
        List<JlcpcbQuery.Term> remaining = new ArrayList<>(parsed.terms());
        List<String> dropped = new ArrayList<>();
        List<JlcpcbQuery.Term> dead = remaining.stream().filter(t -> t.matchable() && !occurs(c, t)).toList();
        Set<List<JlcpcbQuery.Term>> tried = new HashSet<>();
        tried.add(List.copyOf(remaining));
        if (!dead.isEmpty() && dead.size() < remaining.size()) {
            remaining.removeAll(dead);
            dead.forEach(t -> dropped.add(t.text()));
            Result r = attempt(c, query, parsed, remaining, dropped, tried, offset, limit);
            if (r != null) {
                return r;
            }
        }
        List<JlcpcbQuery.Term> afterDead = List.copyOf(remaining);
        List<String> droppedDead = List.copyOf(dropped);
        Result r = dropOneAtATime(c, query, parsed, remaining, dropped, tried, offset, limit);
        if (r != null) {
            return r;
        }
        // nothing with the minimum ratings either: the same once more without them (the ranker excludes, or with
        // allow_below_spec flags, the parts below a rating; the closest ones are better than an ANY match)
        List<JlcpcbQuery.Term> withoutRatings = new ArrayList<>(afterDead);
        List<String> droppedRatings = new ArrayList<>(droppedDead);
        afterDead.stream().filter(t -> t.kind() == JlcpcbQuery.Kind.RATING).forEach(t -> {
            withoutRatings.remove(t);
            droppedRatings.add(t.text());
        });
        if (withoutRatings.size() < afterDead.size()) {
            r = attempt(c, query, parsed, withoutRatings, droppedRatings, tried, offset, limit);
            if (r == null) {
                r = dropOneAtATime(c, query, parsed, withoutRatings, droppedRatings, tried, offset, limit);
            }
            if (r != null) {
                return r;
            }
        }
        for (MatchMode mode : List.of(MatchMode.PARAMETRIC, MatchMode.ANY)) {
            if (mode == MatchMode.PARAMETRIC && tried.contains(parsed.parametricTerms())) {
                continue;
            }
            Predicate predicate = predicate(parsed, mode);
            if (predicate == null) {
                continue;
            }
            int total = count(c, predicate);
            if (total > 0) {
                return found(c, query, predicate, total, offset, limit, mode, List.of());
            }
        }
        return Result.empty();
    }

    /**
     * Drops the least informative term one at a time while more than {@value #MIN_RELAXED_TERMS} terms that are not
     * minimum ratings remain (a rating alone is no query); the first step with in-stock rows wins.
     */
    private Result dropOneAtATime(Connection c, String query, JlcpcbQuery parsed, List<JlcpcbQuery.Term> remaining,
                                  List<String> dropped, Set<List<JlcpcbQuery.Term>> tried, int offset, int limit)
            throws SQLException {
        while (remaining.stream().filter(t -> t.kind() != JlcpcbQuery.Kind.RATING).count() > MIN_RELAXED_TERMS) {
            JlcpcbQuery.Term next = leastInformative(remaining);
            remaining.remove(next);
            dropped.add(next.text());
            Result r = attempt(c, query, parsed, remaining, dropped, tried, offset, limit);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /** Rows matching the predicate regardless of stock (0 when the predicate has nothing but the stock filter). */
    private static int countIgnoringStock(Connection c, Predicate p) throws SQLException {
        String suffix = " AND " + IN_STOCK;
        if (!p.where().endsWith(suffix)) {
            return 0;
        }
        return count(c, new Predicate(p.where().substring(0, p.where().length() - suffix.length()), p.params(),
                p.hasMatch()));
    }

    /** One relaxation step: the conjunction of {@code terms}, unless that set was tried before. */
    private Result attempt(Connection c, String query, JlcpcbQuery parsed, List<JlcpcbQuery.Term> terms,
                           List<String> dropped, Set<List<JlcpcbQuery.Term>> tried, int offset, int limit)
            throws SQLException {
        if (terms.isEmpty() || !tried.add(List.copyOf(terms))) {
            return null;
        }
        Predicate predicate = conjunction(terms);
        if (!predicate.hasMatch()) {
            return null;   // LIKE-only predicates scan the whole table
        }
        int total = count(c, predicate);
        if (total == 0) {
            return null;
        }
        MatchMode mode = terms.equals(parsed.parametricTerms()) ? MatchMode.PARAMETRIC : MatchMode.RELAXED;
        return found(c, query, predicate, total, offset, limit, mode, dropped);
    }

    private Result found(Connection c, String query, Predicate predicate, int total, int offset, int limit,
                         MatchMode mode, List<String> dropped) throws SQLException {
        log.debug("JLCPCB query '{}' matched {} rows with mode {}{}", query, total, mode,
                dropped.isEmpty() ? "" : " (dropped " + dropped + ")");
        return new Result(page(c, predicate, offset, limit), total, mode, dropped);
    }

    /** Position of a term in the drop order: its kind's index, a tolerance right after the package. */
    static double dropRank(JlcpcbQuery.Term term) {
        if (term.kind() == JlcpcbQuery.Kind.VALUE && term.text().endsWith("%")) {
            // the tolerance is relaxable, the package is hard for most families (DESIGN.md 3.4): tolerance first
            return DROP_ORDER.indexOf(JlcpcbQuery.Kind.DIELECTRIC) + 0.5;
        }
        return DROP_ORDER.indexOf(term.kind());
    }

    /** The term to drop next: the first kind of {@link #DROP_ORDER}, the last such term of the query. */
    static JlcpcbQuery.Term leastInformative(List<JlcpcbQuery.Term> terms) {
        JlcpcbQuery.Term best = null;
        double bestRank = Double.MAX_VALUE;
        for (JlcpcbQuery.Term term : terms) {
            double rank = dropRank(term);
            if (rank <= bestRank) {
                best = term;
                bestRank = rank;
            }
        }
        return best;
    }

    /** True when the term occurs anywhere in the database (stock ignored, so the probe stops at the first hit). */
    private static boolean occurs(Connection c, JlcpcbQuery.Term term) {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM parts WHERE parts MATCH ? LIMIT 1")) {
            ps.setString(1, matchExpression(term));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return true;   // never drop a term because a probe failed
        }
    }

    /** Looks up one row by LCSC number (regardless of stock). */
    public Optional<JlcpcbRow> findByLcsc(String lcscPart) throws SQLException {
        if (lcscPart == null || lcscPart.isBlank()) {
            return Optional.empty();
        }
        String number = lcscPart.trim().toUpperCase(Locale.ROOT);
        boolean useIndex = number.codePointCount(0, number.length()) >= 3;
        // The column MATCH only uses the trigram index to avoid a full scan; the equality decides.
        String sql = "SELECT " + COLUMNS + " FROM parts WHERE "
                + (useIndex ? "parts MATCH ? AND " : "") + "\"LCSC Part\" = ? LIMIT 1";
        return withConnection(null, c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int i = 1;
                if (useIndex) {
                    ps.setString(i++, "\"LCSC Part\" : " + quote(number));
                }
                ps.setString(i, number);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(row(rs)) : Optional.empty();
                }
            }
        });
    }

    /** Most rows {@link #findByMpn} reads before comparing part numbers. */
    static final int MPN_CANDIDATES = 50;

    /**
     * Rows whose {@code "MFR.Part"} equals {@code mpn} after removing everything but letters and digits (case
     * ignored), regardless of stock, most stock first. The trigram index needs substrings of the stored spelling, which
     * may contain separators the input lacks ({@code ERA6AEB5361V} vs {@code ERA-6AEB5361V}): the normalised input is
     * cut into 3-character chunks at the three possible phases and the phases are OR-ed, so a single separator can
     * never split every chunk set ({@link #mpnMatchExpression}). Empty for inputs shorter than 6 letters and digits.
     */
    public List<JlcpcbRow> findByMpn(String mpn) throws SQLException {
        String expression = mpnMatchExpression(mpn);
        if (expression == null) {
            return List.of();
        }
        String wanted = normalizePartNumber(mpn);
        String sql = "SELECT " + COLUMNS + " FROM parts WHERE parts MATCH ? LIMIT " + MPN_CANDIDATES;
        return withConnection(null, c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, expression);
                List<JlcpcbRow> rows = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JlcpcbRow row = row(rs);
                        if (wanted.equals(normalizePartNumber(row.mfrPart()))) {
                            rows.add(row);
                        }
                    }
                }
                rows.sort(java.util.Comparator.comparingInt(JlcpcbRow::stockQuantity).reversed());
                return rows;
            }
        });
    }

    /**
     * {@code "MFR.Part" : (("ERA" AND "6AE" AND ...) OR ("RA6" AND ...) OR (...))}: 3-character chunks of the
     * normalised part number at phase 0, 1 and 2 (chunks shorter than 3 characters at either end are left out); null
     * when the normalised number has fewer than 6 characters.
     */
    static String mpnMatchExpression(String mpn) {
        String n = normalizePartNumber(mpn);
        if (n == null || n.length() < 6) {
            return null;
        }
        List<String> phases = new ArrayList<>();
        for (int phase = 0; phase < 3; phase++) {
            List<String> chunks = new ArrayList<>();
            for (int i = phase; i + 3 <= n.length(); i += 3) {
                chunks.add(quote(n.substring(i, i + 3)));
            }
            phases.add("(" + String.join(" AND ", chunks) + ")");
        }
        return quote("MFR.Part") + " : (" + String.join(" OR ", phases) + ")";
    }

    /** Upper case, letters and digits only; null for null. */
    static String normalizePartNumber(String partNumber) {
        if (partNumber == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        partNumber.codePoints().filter(Character::isLetterOrDigit)
                .forEach(cp -> out.appendCodePoint(Character.toUpperCase(cp)));
        return out.toString();
    }

    // ---------------------------------------------------------------- query building

    /** Builds the predicate for one relaxation mode; null when the mode does not apply or adds nothing new. */
    static Predicate predicate(JlcpcbQuery query, MatchMode mode) {
        return switch (mode) {
            case ALL -> conjunction(query.terms());
            case RELAXED -> null;   // built step by step in search()
            case PARAMETRIC -> {
                List<JlcpcbQuery.Term> parametric = query.parametricTerms();
                yield parametric.isEmpty() || parametric.size() == query.terms().size() ? null : conjunction(parametric);
            }
            case ANY -> {
                List<JlcpcbQuery.Term> matchable = query.terms().stream().filter(JlcpcbQuery.Term::matchable).toList();
                if (matchable.size() < 2 && matchable.size() == query.terms().size()) {
                    yield null;   // identical to ALL
                }
                if (matchable.isEmpty()) {
                    yield null;
                }
                String match = matchable.stream().map(t -> "(" + matchExpression(t) + ")")
                        .collect(Collectors.joining(" OR "));
                List<Object> params = new ArrayList<>();
                params.add(match);
                yield new Predicate("parts MATCH ? AND " + IN_STOCK, params, true);
            }
        };
    }

    private static Predicate conjunction(List<JlcpcbQuery.Term> terms) {
        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        String match = terms.stream().filter(JlcpcbQuery.Term::matchable).map(t -> "(" + matchExpression(t) + ")")
                .collect(Collectors.joining(" AND "));
        boolean hasMatch = !match.isEmpty();
        if (hasMatch) {
            clauses.add("parts MATCH ?");
            params.add(match);
        }
        for (JlcpcbQuery.Term term : terms) {
            if (term.kind() == JlcpcbQuery.Kind.RATING) {
                clauses.add(RATING_FUNCTION + "(\"Description\", ?, ?)");
                params.add(term.ratingUnit());
                params.add(term.ratingMinimum());
                continue;
            }
            if (!term.matchable()) {
                List<String> likes = new ArrayList<>();
                for (String phrase : term.phrases()) {
                    likes.add("\"Description\" LIKE ? ESCAPE '\\'");
                    params.add("%" + escapeLike(phrase) + "%");
                }
                clauses.add(likes.size() == 1 ? likes.getFirst() : "(" + String.join(" OR ", likes) + ")");
            }
            if (term.kind() == JlcpcbQuery.Kind.VALUE) {
                clauses.add(VALUE_FUNCTION + "(\"Description\", ?)");
                params.add(term.text());
            } else if (term.boundaryChecked()) {
                // positions and pitches also appear in the part number / package ("PM2.54-1x6P", "P=2.54mm");
                // a positions group (16P/17P/18P) accepts any of its alternatives
                String column = term.kind() == JlcpcbQuery.Kind.PITCH ? "\"Package\"" : "\"MFR.Part\"";
                List<String> checks = new ArrayList<>();
                for (String phrase : term.phrases()) {
                    checks.add(VALUE_FUNCTION + "(\"Description\", ?) OR " + VALUE_FUNCTION + "(" + column + ", ?)");
                    params.add(phrase);
                    params.add(phrase);
                }
                clauses.add("(" + String.join(" OR ", checks) + ")");
            }
        }
        clauses.add(IN_STOCK);
        return new Predicate(String.join(" AND ", clauses), params, hasMatch);
    }

    /**
     * MATCH expression of one term: its phrase, or its alternatives OR-ed, behind its column filter, e.g.
     * {@code "Second Category" : ("IC Socket" OR "Transistor Socket")}.
     */
    static String matchExpression(JlcpcbQuery.Term term) {
        List<String> phrases = term.phrases();
        String inner = phrases.size() == 1 ? quote(phrases.getFirst())
                : "(" + phrases.stream().map(JlcpcbSqliteSearch::quote).collect(Collectors.joining(" OR ")) + ")";
        return term.column() == null ? inner : quote(term.column()) + " : " + inner;
    }

    /** FTS5 string literal: wrapped in double quotes, embedded quotes doubled. */
    static String quote(String token) {
        return "\"" + token.replace("\"", "\"\"") + "\"";
    }

    static String escapeLike(String token) {
        return token.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * True when {@code token} occurs in {@code text} (case-insensitive) at a position not directly preceded by a digit
     * or '.', e.g. {@code 10k} is found in {@code "50V 10kΩ"} but not in {@code "510kΩ"} or {@code "1.10k"}.
     */
    static boolean containsValue(String text, String token) {
        if (text == null || token == null || token.isEmpty()) {
            return false;
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        String needle = token.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return false;
            }
            if (at == 0 || !(Character.isDigit(haystack.charAt(at - 1)) || haystack.charAt(at - 1) == '.')) {
                return true;
            }
            from = at + 1;
        }
    }

    // ---------------------------------------------------------------- JDBC

    private static int count(Connection c, Predicate p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM parts WHERE " + p.where())) {
            bind(ps, p.params(), 1);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static List<JlcpcbRow> page(Connection c, Predicate p, int offset, int limit) throws SQLException {
        String order = p.hasMatch() ? "rank, CAST(\"Stock\" AS INTEGER) DESC" : "CAST(\"Stock\" AS INTEGER) DESC";
        String sql = "SELECT " + COLUMNS + " FROM parts WHERE " + p.where() + " ORDER BY " + order + " LIMIT ? OFFSET ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int next = bind(ps, p.params(), 1);
            ps.setInt(next, Math.max(0, limit));
            ps.setInt(next + 1, Math.max(0, offset));
            List<JlcpcbRow> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(row(rs));
                }
            }
            return rows;
        }
    }

    private static int bind(PreparedStatement ps, List<Object> params, int start) throws SQLException {
        int i = start;
        for (Object param : params) {
            ps.setObject(i++, param);
        }
        return i;
    }

    private static JlcpcbRow row(ResultSet rs) throws SQLException {
        return row(rs, 0);
    }

    /** The row of the {@link #COLUMNS} that start after {@code skip} other columns. */
    static JlcpcbRow row(ResultSet rs, int skip) throws SQLException {
        return new JlcpcbRow(rs.getString(skip + 1), rs.getString(skip + 2), rs.getString(skip + 3),
                rs.getString(skip + 4), rs.getString(skip + 5), rs.getString(skip + 6), rs.getString(skip + 7),
                rs.getString(skip + 8), rs.getString(skip + 9), rs.getString(skip + 10), rs.getString(skip + 11),
                rs.getString(skip + 12));
    }

    private void openLocked() {
        if (idle != null || !Files.isRegularFile(databaseFile)) {
            return;
        }
        try {
            Connection first = openPooled(databaseFile);
            pool.add(first);
            try (PreparedStatement probe = first.prepareStatement("SELECT \"LCSC Part\" FROM parts LIMIT 1");
                    ResultSet rs = probe.executeQuery()) {
                rs.next();   // fails fast on a file that is not a JLCPCB database
            }
            registerFunctions(first);
            FieldIndexFile.Info info = attachIndex(first);
            for (int i = 1; i < poolSize; i++) {
                Connection c = openPooled(databaseFile);
                pool.add(c);
                registerFunctions(c);
                if (info != null && !attachTo(c)) {
                    // the typed table is on every connection or on none: the FTS path serves until the next check
                    pool.forEach(FieldIndexFile::detach);
                    info = null;
                }
            }
            BlockingQueue<Connection> queue = new ArrayBlockingQueue<>(poolSize);
            queue.addAll(pool);
            fieldIndex = info;
            idle = queue;
            log.info("Opened JLCPCB database {} with {} connections{}", databaseFile, poolSize,
                    info == null ? "" : ", typed table of " + info.rows() + " rows (version " + info.version() + ")");
        } catch (SQLException e) {
            log.warn("Cannot open JLCPCB database {}: {}", databaseFile, e.getMessage());
            closeLocked();
        }
    }

    /** Attaches the sidecar to {@code c} when the field index is enabled and the sidecar is current; else null. */
    private FieldIndexFile.Info attachIndex(Connection c) {
        if (!fieldIndexEnabled || !Files.isRegularFile(indexFile)) {
            return null;
        }
        try {
            attacher.attach(c, indexFile);
            Optional<FieldIndexFile.Info> info = FieldIndexFile.inspect(c, FieldIndexFile.fingerprint(databaseFile, c));
            if (info.isEmpty()) {
                log.info("Typed table {} does not describe {} (version or source differ): the FTS path serves",
                        indexFile, databaseFile);
                FieldIndexFile.detach(c);
            }
            return info.orElse(null);
        } catch (SQLException e) {
            log.warn("Cannot attach the typed table {}: {}", indexFile, e.getMessage());
            FieldIndexFile.detach(c);
            return null;
        }
    }

    /**
     * Attaches the sidecar to another connection of the pool; false (logged) when it fails, for instance because the
     * sidecar vanished or was replaced after the first connection read it. A failure never closes the pool.
     */
    private boolean attachTo(Connection c) {
        try {
            attacher.attach(c, indexFile);
            return true;
        } catch (SQLException e) {
            log.warn("Cannot attach the typed table {} to every connection, the FTS path serves: {}", indexFile,
                    e.getMessage());
            return false;
        }
    }

    private void closeLocked() {
        idle = null;
        fieldIndex = null;
        for (Connection c : pool) {
            try {
                c.close();
            } catch (SQLException e) {
                log.debug("Closing JLCPCB database failed", e);
            }
        }
        pool.clear();
    }

    /** One read-only, immutable connection of the pool. */
    static Connection openPooled(Path file) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        return config.createConnection("jdbc:sqlite:" + FieldIndexFile.immutableUri(file));
    }

    /** Opens a SQLite file read-only ({@code mode=ro} and {@link SQLiteConfig#setReadOnly}). */
    static Connection openReadOnly(Path file) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        return config.createConnection("jdbc:sqlite:file:" + FieldIndexFile.uriPath(file) + "?mode=ro");
    }

    /**
     * True when {@code text} states a value of {@code unit} ({@code V}, {@code A}, {@code W}) of at least
     * {@code minimum}: {@code 50V} satisfies a 25 V minimum; {@code 125mW}, {@code 1.5kV} and {@code 6A} are read
     * with their prefix ({@code m} milli, {@code M} mega).
     */
    static boolean atLeast(String text, String unit, double minimum) {
        if (text == null || unit == null) {
            return false;
        }
        Matcher m = RATED_VALUE.matcher(text);
        while (m.find()) {
            if (!m.group(3).equals(unit)) {
                continue;
            }
            double multiplier = switch (m.group(2)) {
                case "u" -> 1e-6;
                case "m" -> 1e-3;
                case "k" -> 1e3;
                case "M" -> 1e6;
                default -> 1.0;
            };
            if (Double.parseDouble(m.group(1)) * multiplier >= minimum * (1 - 1e-9)) {
                return true;
            }
        }
        return false;
    }

    private static void registerFunctions(Connection c) throws SQLException {
        Function.create(c, RATING_FUNCTION, new Function() {
            @Override
            protected void xFunc() throws SQLException {
                result(atLeast(value_text(0), value_text(1), value_double(2)) ? 1 : 0);
            }
        }, 3, Function.FLAG_DETERMINISTIC);
        Function.create(c, VALUE_FUNCTION, new Function() {
            @Override
            protected void xFunc() throws SQLException {
                result(containsValue(value_text(0), value_text(1)) ? 1 : 0);
            }
        }, 2, Function.FLAG_DETERMINISTIC);
    }
}
