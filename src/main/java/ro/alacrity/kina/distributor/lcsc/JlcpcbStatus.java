package ro.alacrity.kina.distributor.lcsc;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Diagnostics for {@code list_distributors}.
 *
 * @param available    a database file is open and searchable
 * @param file         configured database file
 * @param library      configured library name
 * @param downloadedAt when the installed file was downloaded (null when unknown)
 * @param partCount    parts in the installed database (null when unknown)
 * @param sourceDate   {@code meta.date} of the installed database (null when unknown)
 * @param downloading  a download is running right now
 * @param lastError    message of the last failed check/download, null after a success
 */
public record JlcpcbStatus(
        boolean available,
        Path file,
        String library,
        Instant downloadedAt,
        Long partCount,
        String sourceDate,
        boolean downloading,
        String lastError) {
}
