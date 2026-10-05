package ro.alacrity.kina.distributor.lcsc;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Read-only access to the downloaded JLCPCB FTS5 database (DESIGN.md 9.3).
 *
 * <p>One SQLite connection guarded by a {@link ReentrantReadWriteLock}: queries take the read lock, swapping the file
 * ({@link #replaceDatabase}) or {@link #reopen() reopening} takes the write lock.
 *
 * <p>Query building: terms of 3+ characters go into {@code parts MATCH '"a" AND "b"'}, shorter ones become
 * {@code "Description" LIKE '%tok%' ESCAPE '\'}; value terms ({@code 10k}, {@code 100nF}, {@code 5%}) additionally must
 * appear in the description not directly preceded by a digit or '.', so {@code 10k} does not match {@code 510kΩ}
 * (trigram matching is plain substring matching). Always {@code CAST("Stock" AS INTEGER) > 0}, ordered by
 * {@code rank, stock DESC}.
 *
 * <p>Query relaxation: when the full AND query has no in-stock match the search retries with
 * {@link MatchMode#PARAMETRIC} (only values, packages, dielectrics and family words, still AND), then
 * {@link MatchMode#ANY} (every 3+ character term OR-ed, no value-boundary check; BM25 puts rows matching more terms
 * first). The first mode with a non-zero count is used for both the count and the page, so pagination is stable.
 */
@Component
public class JlcpcbSqliteSearch {

    private static final Logger log = LoggerFactory.getLogger(JlcpcbSqliteSearch.class);

    static final String VALUE_FUNCTION = "kina_value";

    private static final String COLUMNS = """
            "LCSC Part", "First Category", "Second Category", "MFR.Part", "Package", "Solder Joint", \
            "Manufacturer", "Library Type", "Description", "Datasheet", "Price", "Stock\"""";
    private static final String IN_STOCK = "CAST(\"Stock\" AS INTEGER) > 0";

    public enum MatchMode { ALL, PARAMETRIC, ANY }

    /**
     * @param rows  the requested page of in-stock rows
     * @param total number of in-stock rows matching the predicate of {@code mode}
     * @param mode  the relaxation step that produced the rows ({@code null} when the query had no usable terms)
     */
    public record Result(List<JlcpcbRow> rows, int total, MatchMode mode) {

        public Result {
            rows = List.copyOf(rows);
        }

        static Result empty() {
            return new Result(List.of(), 0, null);
        }
    }

    /** One SQL predicate with its bind parameters. */
    record Predicate(String where, List<Object> params, boolean hasMatch) {
    }

    private final Path databaseFile;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private Connection connection;   // guarded by lock

    @Autowired
    public JlcpcbSqliteSearch(KinaProperties properties) {
        this(properties.jlcpcb().databaseFile());
    }

    public JlcpcbSqliteSearch(Path databaseFile) {
        this.databaseFile = databaseFile.toAbsolutePath().normalize();
    }

    public Path databaseFile() {
        return databaseFile;
    }

    /** True when a database is open; lazily opens an existing file. */
    public boolean isAvailable() {
        lock.readLock().lock();
        try {
            if (connection != null) {
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
            if (connection == null) {
                openLocked();
            }
            return connection != null;
        } finally {
            lock.writeLock().unlock();
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
        isAvailable();   // lazily opens an existing file
        lock.readLock().lock();
        try {
            Connection c = requireConnection();
            for (MatchMode mode : MatchMode.values()) {
                Predicate predicate = predicate(parsed, mode);
                if (predicate == null) {
                    continue;
                }
                int total = count(c, predicate);
                if (total > 0) {
                    log.debug("JLCPCB query '{}' matched {} rows with mode {}", query, total, mode);
                    return new Result(page(c, predicate, offset, limit), total, mode);
                }
            }
            return Result.empty();
        } finally {
            lock.readLock().unlock();
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
        isAvailable();   // lazily opens an existing file
        lock.readLock().lock();
        try (PreparedStatement ps = requireConnection().prepareStatement(sql)) {
            int i = 1;
            if (useIndex) {
                ps.setString(i++, "\"LCSC Part\" : " + quote(number));
            }
            ps.setString(i, number);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(row(rs)) : Optional.empty();
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    // ---------------------------------------------------------------- query building

    /** Builds the predicate for one relaxation mode; null when the mode does not apply or adds nothing new. */
    static Predicate predicate(JlcpcbQuery query, MatchMode mode) {
        return switch (mode) {
            case ALL -> conjunction(query.terms());
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
                String match = matchable.stream().map(t -> quote(t.text())).collect(Collectors.joining(" OR "));
                List<Object> params = new ArrayList<>();
                params.add(match);
                yield new Predicate("parts MATCH ? AND " + IN_STOCK, params, true);
            }
        };
    }

    private static Predicate conjunction(List<JlcpcbQuery.Term> terms) {
        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        String match = terms.stream().filter(JlcpcbQuery.Term::matchable).map(t -> quote(t.text()))
                .collect(Collectors.joining(" AND "));
        boolean hasMatch = !match.isEmpty();
        if (hasMatch) {
            clauses.add("parts MATCH ?");
            params.add(match);
        }
        for (JlcpcbQuery.Term term : terms) {
            if (!term.matchable()) {
                clauses.add("\"Description\" LIKE ? ESCAPE '\\'");
                params.add("%" + escapeLike(term.text()) + "%");
            }
            if (term.kind() == JlcpcbQuery.Kind.VALUE) {
                clauses.add(VALUE_FUNCTION + "(\"Description\", ?)");
                params.add(term.text());
            }
        }
        clauses.add(IN_STOCK);
        return new Predicate(String.join(" AND ", clauses), params, hasMatch);
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
        return new JlcpcbRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getString(11), rs.getString(12));
    }

    private Connection requireConnection() {
        if (connection == null) {
            throw new IllegalStateException("JLCPCB database not available: " + databaseFile);
        }
        return connection;
    }

    private void openLocked() {
        if (connection != null || !Files.isRegularFile(databaseFile)) {
            return;
        }
        try {
            connection = openReadOnly(databaseFile);
            try (PreparedStatement probe = connection.prepareStatement("SELECT \"LCSC Part\" FROM parts LIMIT 1");
                    ResultSet rs = probe.executeQuery()) {
                rs.next();   // fails fast on a file that is not a JLCPCB database
            }
            registerFunctions(connection);
            log.info("Opened JLCPCB database {}", databaseFile);
        } catch (SQLException e) {
            log.warn("Cannot open JLCPCB database {}: {}", databaseFile, e.getMessage());
            closeLocked();
        }
    }

    private void closeLocked() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                log.debug("Closing JLCPCB database failed", e);
            }
            connection = null;
        }
    }

    /** Opens a SQLite file read-only ({@code mode=ro} and {@link SQLiteConfig#setReadOnly}). */
    static Connection openReadOnly(Path file) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        return config.createConnection("jdbc:sqlite:file:" + uriPath(file) + "?mode=ro");
    }

    /** Escapes the characters that are special in SQLite URI filenames. */
    private static String uriPath(Path file) {
        return file.toAbsolutePath().toString()
                .replace("%", "%25").replace(" ", "%20").replace("?", "%3f").replace("#", "%23");
    }

    private static void registerFunctions(Connection c) throws SQLException {
        Function.create(c, VALUE_FUNCTION, new Function() {
            @Override
            protected void xFunc() throws SQLException {
                result(containsValue(value_text(0), value_text(1)) ? 1 : 0);
            }
        }, 2, Function.FLAG_DETERMINISTIC);
    }
}
