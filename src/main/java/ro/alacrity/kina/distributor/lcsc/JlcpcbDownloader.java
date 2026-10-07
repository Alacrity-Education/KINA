package ro.alacrity.kina.distributor.lcsc;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Downloads the chunked JLCPCB parts database published by kicad-jlcpcb-tools (DESIGN.md 9.3):
 * sentinel {@code chunk_num_*.txt} -&gt; N, chunks {@code <library>.zip.001..NNN} streamed into
 * {@code <data-dir>/tmp/<library>.zip}, the single zip entry extracted to {@code <data-dir>/tmp/<library>} and
 * validated. {@link #install} then atomically renames it to {@code <data-dir>/<library>}.
 */
@Component
@Slf4j
public class JlcpcbDownloader {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration SENTINEL_TIMEOUT = Duration.ofSeconds(30);
    static final Duration CHUNK_TIMEOUT = Duration.ofMinutes(10);
    static final int MAX_ATTEMPTS = 3;
    private static final int MAX_CHUNKS = 999;
    private static final long MB = 1024L * 1024L;

    /**
     * A validated database waiting in the temp directory.
     *
     * @param file     {@code <data-dir>/tmp/<library>}
     * @param metadata validation result
     */
    public record DownloadedDatabase(Path file, JlcpcbDatabaseValidator.Metadata metadata) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private Duration chunkTimeout = CHUNK_TIMEOUT;
    private Duration retryDelay = Duration.ofSeconds(5);

    /** {@code parts-fts5.db} -&gt; {@code chunk_num_fts5.txt}; {@code current-parts-fts5.db} -&gt; {@code chunk_num_current_parts_fts5.txt}. */
    static String sentinelName(String library) {
        String base = library.endsWith(".db") ? library.substring(0, library.length() - 3) : library;
        if (base.equals("parts-fts5")) {
            return "chunk_num_fts5.txt";
        }
        return "chunk_num_" + base.replace('-', '_') + ".txt";
    }

    static String chunkName(String library, int index) {
        return library + ".zip." + String.format(Locale.ROOT, "%03d", index);
    }

    /**
     * Downloads, extracts and validates the database into {@code <dataDir>/tmp/<library>}. Temp files are removed on
     * failure; the zip is always removed.
     */
    public DownloadedDatabase download(String baseUrl, String library, Path dataDir)
            throws IOException, InterruptedException {
        URI base = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        Path tmpDir = dataDir.resolve("tmp");
        Files.createDirectories(tmpDir);
        Path zip = tmpDir.resolve(library + ".zip");
        Path extracted = tmpDir.resolve(library);
        boolean success = false;
        try {
            int chunks = readChunkCount(base.resolve(sentinelName(library)));
            log.info("Downloading JLCPCB database {} ({} chunks) from {}", library, chunks, base);
            downloadChunks(base, library, chunks, zip);
            extract(zip, extracted);
            Files.deleteIfExists(zip);
            JlcpcbDatabaseValidator.Metadata metadata = JlcpcbDatabaseValidator.validate(extracted);
            log.info("JLCPCB database {} downloaded: {} parts, {} MB, source date {}", library,
                    metadata.partCount(), metadata.sizeBytes() / MB, metadata.sourceDate());
            success = true;
            return new DownloadedDatabase(extracted, metadata);
        } finally {
            Files.deleteIfExists(zip);
            if (!success) {
                Files.deleteIfExists(extracted);
            }
        }
    }

    /** Atomically renames the downloaded file over {@code target} (same filesystem: both live under the data dir). */
    public void install(DownloadedDatabase downloaded, Path target) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        try {
            Files.move(downloaded.file(), target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            log.warn("Atomic move not supported for {}, falling back to a plain replace", target);
            Files.move(downloaded.file(), target, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("Installed JLCPCB database at {}", target);
    }

    /** Deletes any leftovers in the temp directory (e.g. after a crash). */
    public void cleanTemp(Path dataDir, String library) {
        Path tmpDir = dataDir.resolve("tmp");
        for (Path p : new Path[] {tmpDir.resolve(library + ".zip"), tmpDir.resolve(library)}) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                log.debug("Cannot delete {}", p, e);
            }
        }
    }

    private int readChunkCount(URI sentinel) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(sentinel).timeout(SENTINEL_TIMEOUT).GET().build();
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode() + " for " + sentinel);
                }
                int n = Integer.parseInt(response.body().trim());
                if (n < 1 || n > MAX_CHUNKS) {
                    throw new IOException("Implausible chunk count " + n + " in " + sentinel);
                }
                return n;
            } catch (NumberFormatException e) {
                throw new IOException("Sentinel " + sentinel + " is not a number", e);
            } catch (IOException e) {
                last = e;
                log.warn("Reading {} failed (attempt {}/{}): {}", sentinel, attempt, MAX_ATTEMPTS, e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    pause(attempt);
                }
            }
        }
        throw last;
    }

    private void downloadChunks(URI base, String library, int chunks, Path zip) throws IOException, InterruptedException {
        try (FileChannel out = FileChannel.open(zip, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            for (int i = 1; i <= chunks; i++) {
                URI uri = base.resolve(chunkName(library, i));
                long start = out.size();
                IOException last = null;
                boolean done = false;
                for (int attempt = 1; attempt <= MAX_ATTEMPTS && !done; attempt++) {
                    out.truncate(start);
                    out.position(start);
                    try {
                        long bytes = downloadChunk(uri, out);
                        done = true;
                        log.info("JLCPCB download chunk {}/{} done ({} MB, {} MB total)", i, chunks, bytes / MB,
                                out.size() / MB);
                    } catch (IOException e) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new InterruptedException("JLCPCB download interrupted");
                        }
                        last = e;
                        log.warn("JLCPCB chunk {}/{} failed (attempt {}/{}): {}", i, chunks, attempt, MAX_ATTEMPTS,
                                e.getMessage());
                        if (attempt < MAX_ATTEMPTS) {
                            pause(attempt);
                        }
                    }
                }
                if (!done) {
                    throw new IOException("Giving up on " + uri + " after " + MAX_ATTEMPTS + " attempts", last);
                }
            }
        }
    }

    /** Streams one chunk to the channel; the whole body must arrive within {@link #chunkTimeout}. */
    private long downloadChunk(URI uri, FileChannel out) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(chunkTimeout).GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " for " + uri);
            }
            // Watchdog: HttpRequest.timeout only covers the response headers, so close the stream on overrun.
            CompletableFuture<Void> watchdog = CompletableFuture.runAsync(() -> closeQuietly(in),
                    CompletableFuture.delayedExecutor(chunkTimeout.toMillis(), TimeUnit.MILLISECONDS));
            try {
                byte[] buffer = new byte[256 * 1024];
                long total = 0;
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException("interrupted");
                    }
                    ByteBuffer bb = ByteBuffer.wrap(buffer, 0, read);
                    while (bb.hasRemaining()) {
                        out.write(bb);
                    }
                    total += read;
                }
                if (watchdog.isDone()) {
                    throw new IOException("Chunk " + uri + " exceeded " + chunkTimeout);
                }
                return total;
            } catch (IOException e) {
                if (watchdog.isDone()) {
                    throw new IOException("Chunk " + uri + " exceeded " + chunkTimeout, e);
                }
                throw e;
            } finally {
                watchdog.cancel(false);
            }
        }
    }

    private static void extract(Path zip, Path target) throws IOException {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            ZipEntry entry = null;
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                if (entry != null) {
                    throw new IOException("Expected exactly one entry in " + zip + ", found " + entry.getName()
                            + " and " + e.getName());
                }
                entry = e;
            }
            if (entry == null) {
                throw new IOException("Empty zip " + zip);
            }
            log.info("Extracting {} ({} MB) from {}", entry.getName(), Math.max(0, entry.getSize()) / MB, zip);
            try (InputStream in = zf.getInputStream(entry)) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void pause(int attempt) throws InterruptedException {
        long millis = retryDelay.toMillis() * attempt;
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // best effort
        }
    }
}
