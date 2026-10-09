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
 * @param fieldIndex   the typed in-stock table (DESIGN.md 9.3)
 */
public record JlcpcbStatus(
        boolean available,
        Path file,
        String library,
        Instant downloadedAt,
        Long partCount,
        String sourceDate,
        boolean downloading,
        String lastError,
        FieldIndex fieldIndex) {

    /**
     * The typed in-stock table of the sidecar file.
     *
     * @param enabled   {@code kina.jlcpcb.field-index.enabled}
     * @param available the table is attached and current: LCSC searches use the field query
     * @param version   the extractor version it was built with (null when not available)
     * @param rows      rows in the table (null when not available)
     * @param builtAt   when it was built (null when not available)
     * @param building  a build is running (adoption of a file without a current table)
     */
    public record FieldIndex(boolean enabled, boolean available, Integer version, Long rows, Instant builtAt,
                             boolean building) {
    }
}
