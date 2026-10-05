package ro.alacrity.kina.distributor.lcsc;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/** {@code jlcpcb_database} (single row, id = 1). */
@Repository
public class JlcpcbDatabaseRepository {

    private final JdbcClient jdbc;

    public JlcpcbDatabaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<JlcpcbDatabaseInfo> find() {
        return jdbc.sql("""
                        SELECT library, file_path, downloaded_at, size_bytes, part_count, source_date
                        FROM jlcpcb_database WHERE id = 1""")
                .query((rs, n) -> new JlcpcbDatabaseInfo(
                        rs.getString("library"),
                        rs.getString("file_path"),
                        rs.getObject("downloaded_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("size_bytes", Long.class),
                        rs.getObject("part_count", Long.class),
                        rs.getString("source_date")))
                .optional();
    }

    /** Inserts or replaces the row. */
    public void save(JlcpcbDatabaseInfo info) {
        jdbc.sql("""
                        INSERT INTO jlcpcb_database (id, library, file_path, downloaded_at, size_bytes, part_count, source_date)
                        VALUES (1, :library, :filePath, :downloadedAt, :sizeBytes, :partCount, :sourceDate)
                        ON CONFLICT (id) DO UPDATE SET
                          library = EXCLUDED.library,
                          file_path = EXCLUDED.file_path,
                          downloaded_at = EXCLUDED.downloaded_at,
                          size_bytes = EXCLUDED.size_bytes,
                          part_count = EXCLUDED.part_count,
                          source_date = EXCLUDED.source_date""")
                .param("library", info.library())
                .param("filePath", info.filePath())
                .param("downloadedAt", OffsetDateTime.ofInstant(info.downloadedAt(), ZoneOffset.UTC))
                .param("sizeBytes", info.sizeBytes())
                .param("partCount", info.partCount())
                .param("sourceDate", info.sourceDate())
                .update();
    }

    public void delete() {
        jdbc.sql("DELETE FROM jlcpcb_database WHERE id = 1").update();
    }
}
