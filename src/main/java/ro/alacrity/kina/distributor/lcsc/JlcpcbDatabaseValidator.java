package ro.alacrity.kina.distributor.lcsc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Checks that a file is a usable JLCPCB parts database and reads its {@code meta} row. */
public final class JlcpcbDatabaseValidator {

    private static final Logger log = LoggerFactory.getLogger(JlcpcbDatabaseValidator.class);

    /**
     * @param sizeBytes  file size on disk
     * @param partCount  {@code SELECT count(*) FROM parts}
     * @param sourceDate {@code meta.date} (e.g. "2026-09-26"), null when the meta table is missing
     * @param lastUpdate {@code meta.last_update}, null when missing
     */
    public record Metadata(long sizeBytes, long partCount, String sourceDate, String lastUpdate) {
    }

    private JlcpcbDatabaseValidator() {
    }

    /** @throws IOException when the file does not open read-only as SQLite or has no {@code parts} rows */
    public static Metadata validate(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("JLCPCB database file missing: " + file);
        }
        long size = Files.size(file);
        try (Connection c = JlcpcbSqliteSearch.openReadOnly(file); Statement st = c.createStatement()) {
            long count;
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM parts")) {
                count = rs.next() ? rs.getLong(1) : 0;
            }
            if (count <= 0) {
                throw new IOException("JLCPCB database " + file + " has no parts");
            }
            String date = null;
            String lastUpdate = null;
            try (ResultSet rs = st.executeQuery("SELECT date, last_update FROM meta LIMIT 1")) {
                if (rs.next()) {
                    date = rs.getString(1);
                    lastUpdate = rs.getString(2);
                }
            } catch (SQLException e) {
                log.warn("JLCPCB database {} has no readable meta table: {}", file, e.getMessage());
            }
            return new Metadata(size, count, date, lastUpdate);
        } catch (SQLException e) {
            throw new IOException("Invalid JLCPCB database " + file + ": " + e.getMessage(), e);
        }
    }
}
