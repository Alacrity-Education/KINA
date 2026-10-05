package ro.alacrity.kina.distributor.lcsc;

import java.time.Instant;

/**
 * The single {@code jlcpcb_database} row (id = 1): which database file is installed and when it was downloaded.
 *
 * @param library      library file name, e.g. {@code parts-fts5.db}
 * @param filePath     absolute path of the installed file
 * @param downloadedAt when the file was installed (for adopted files: the file's last-modified time)
 * @param sizeBytes    file size, may be null
 * @param partCount    {@code count(*)} of the parts table, may be null
 * @param sourceDate   {@code meta.date} of the database, may be null
 */
public record JlcpcbDatabaseInfo(
        String library,
        String filePath,
        Instant downloadedAt,
        Long sizeBytes,
        Long partCount,
        String sourceDate) {
}
