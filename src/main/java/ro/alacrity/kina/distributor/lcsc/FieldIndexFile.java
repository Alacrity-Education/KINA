package ro.alacrity.kina.distributor.lcsc;

import lombok.experimental.UtilityClass;
import ro.alacrity.kina.search.ParametricExtractor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The sidecar file of the typed in-stock table (DESIGN.md 9.3): {@code <library without .db>.index.db} next to the
 * JLCPCB file, holding {@code part_index} and {@code kina_meta}. Readers attach it read-only as {@code idx}; it is only
 * ever replaced by a rename, never modified in place. {@link #inspect} decides whether it describes the main file
 * that is open now.
 */
@UtilityClass
class FieldIndexFile {

    /** The schema name the sidecar is attached under. */
    static final String SCHEMA = "idx";
    /** The typed table. */
    static final String TABLE = "part_index";
    static final String META = "kina_meta";

    /** What a valid sidecar says about itself. */
    record Info(int version, long rows, Instant builtAt) {
    }

    /** {@code parts-fts5.db} gives {@code parts-fts5.index.db} in the same directory. */
    static Path sidecar(Path main) {
        String name = main.getFileName().toString();
        String base = name.endsWith(".db") ? name.substring(0, name.length() - 3) : name;
        return main.resolveSibling(base + ".index.db");
    }

    /**
     * What identifies the main file: its size and the {@code meta} row (part count, date, last update). Cheap (no
     * scan); a new download changes it.
     */
    static String fingerprint(Path main, Connection mainConnection) throws SQLException {
        String meta = "";
        try (Statement st = mainConnection.createStatement();
                ResultSet rs = st.executeQuery("SELECT partcount, date, last_update FROM meta LIMIT 1")) {
            if (rs.next()) {
                meta = rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3);
            }
        } catch (SQLException e) {
            // a file without a meta row is identified by its size alone
        }
        try {
            return Files.size(main) + "|" + meta;
        } catch (IOException e) {
            throw new SQLException("cannot stat " + main, e);
        }
    }

    /** The SQLite URI of a file opened read-only and immutable (it is never modified while open). */
    static String immutableUri(Path file) {
        return "file:" + uriPath(file) + "?mode=ro&immutable=1";
    }

    /** Escapes the characters that are special in SQLite URI filenames. */
    static String uriPath(Path file) {
        return file.toAbsolutePath().toString()
                .replace("%", "%25").replace(" ", "%20").replace("?", "%3f").replace("#", "%23");
    }

    /** {@code ATTACH} the sidecar read-only on {@code connection}. */
    static void attach(Connection connection, Path sidecar) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("ATTACH DATABASE '" + immutableUri(sidecar).replace("'", "''") + "' AS " + SCHEMA);
        }
    }

    static void detach(Connection connection) {
        try (Statement st = connection.createStatement()) {
            st.execute("DETACH DATABASE " + SCHEMA);
        } catch (SQLException e) {
            // already detached
        }
    }

    /**
     * What the sidecar attached as {@code idx} on {@code connection} says when it is current: the table and
     * {@code kina_meta} exist, the version is the running extractor's and it was built from the main file open on
     * this connection ({@code fingerprint}). Empty otherwise (a missing, older or foreign sidecar means "no typed
     * table").
     */
    static Optional<Info> inspect(Connection connection, String fingerprint) {
        try {
            Map<String, String> meta = new HashMap<>();
            try (PreparedStatement ps = connection.prepareStatement("SELECT key, value FROM " + SCHEMA + "." + META);
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    meta.put(rs.getString(1), rs.getString(2));
                }
            }
            int version = Integer.parseInt(meta.getOrDefault("index_version", "-1"));
            if (version != ParametricExtractor.INDEX_VERSION || !fingerprint.equals(meta.get("source"))) {
                return Optional.empty();
            }
            return Optional.of(new Info(version, Long.parseLong(meta.getOrDefault("rows", "0")),
                    Instant.parse(meta.getOrDefault("built_at", Instant.EPOCH.toString()))));
        } catch (SQLException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
