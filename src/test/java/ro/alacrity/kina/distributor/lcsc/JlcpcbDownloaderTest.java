package ro.alacrity.kina.distributor.lcsc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.alacrity.kina.TestWiring;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JlcpcbDownloaderTest {

    private static final String LIBRARY = "basic-parts-fts5.db";

    @TempDir
    Path dir;

    HttpServer server;
    final Map<String, byte[]> files = new ConcurrentHashMap<>();
    final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
    final Map<String, Integer> failuresBeforeSuccess = new ConcurrentHashMap<>();
    String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/tools/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/tools";   // no trailing slash on purpose
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String name = exchange.getRequestURI().getPath().substring("/tools/".length());
        int n = requests.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
        byte[] body = files.get(name);
        int status = body == null ? 404 : n <= failuresBeforeSuccess.getOrDefault(name, 0) ? 500 : 200;
        byte[] payload = status == 200 ? body : "nope".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    @Test
    void sentinelAndChunkNames() {
        assertThat(JlcpcbDownloader.sentinelName("parts-fts5.db")).isEqualTo("chunk_num_fts5.txt");
        assertThat(JlcpcbDownloader.sentinelName("current-parts-fts5.db")).isEqualTo("chunk_num_current_parts_fts5.txt");
        assertThat(JlcpcbDownloader.sentinelName("basic-parts-fts5.db")).isEqualTo("chunk_num_basic_parts_fts5.txt");
        assertThat(JlcpcbDownloader.chunkName("parts-fts5.db", 7)).isEqualTo("parts-fts5.db.zip.007");
        assertThat(JlcpcbDownloader.chunkName("parts-fts5.db", 12)).isEqualTo("parts-fts5.db.zip.012");
    }

    @Test
    void downloadsChunksExtractsValidatesAndInstallsAtomically() throws Exception {
        byte[] zip = zipOf(JlcpcbTestDatabase.create(dir.resolve("source.db")));
        int half = zip.length / 2;
        files.put("chunk_num_basic_parts_fts5.txt", "2\n".getBytes(StandardCharsets.UTF_8));
        files.put(LIBRARY + ".zip.001", Arrays.copyOfRange(zip, 0, half));
        files.put(LIBRARY + ".zip.002", Arrays.copyOfRange(zip, half, zip.length));
        failuresBeforeSuccess.put(LIBRARY + ".zip.002", 1);   // first attempt of chunk 2 fails -> retried

        Path dataDir = dir.resolve("data");
        Path target = dataDir.resolve(LIBRARY);
        Files.createDirectories(dataDir);
        Files.writeString(target, "old database");   // replaced by the install

        JlcpcbDownloader downloader = downloader();
        JlcpcbDownloader.DownloadedDatabase downloaded = downloader.download(baseUrl, LIBRARY, dataDir);

        assertThat(downloaded.file()).isEqualTo(dataDir.resolve("tmp").resolve(LIBRARY));
        assertThat(downloaded.metadata().partCount()).isEqualTo(JlcpcbTestDatabase.SAMPLE.size());
        assertThat(downloaded.metadata().sourceDate()).isEqualTo("2026-09-26");
        assertThat(downloaded.metadata().sizeBytes()).isEqualTo(Files.size(dir.resolve("source.db")));
        assertThat(dataDir.resolve("tmp").resolve(LIBRARY + ".zip")).doesNotExist();
        assertThat(requests.get(LIBRARY + ".zip.002").get()).isEqualTo(2);
        assertThat(requests.get(LIBRARY + ".zip.001").get()).isEqualTo(1);

        downloader.install(downloaded, target);

        assertThat(downloaded.file()).doesNotExist();
        assertThat(target).hasSameBinaryContentAs(dir.resolve("source.db"));
        JlcpcbSqliteSearch search = TestWiring.sqliteSearch(target);
        assertThat(search.findByLcsc("C1525")).isPresent();
        search.close();
    }

    @Test
    void giveUpAfterThreeAttemptsAndCleanUp() throws Exception {
        byte[] zip = zipOf(JlcpcbTestDatabase.create(dir.resolve("source.db")));
        files.put("chunk_num_basic_parts_fts5.txt", "1".getBytes(StandardCharsets.UTF_8));
        files.put(LIBRARY + ".zip.001", zip);
        failuresBeforeSuccess.put(LIBRARY + ".zip.001", 3);

        Path dataDir = dir.resolve("data");
        JlcpcbDownloader downloader = downloader();
        assertThatThrownBy(() -> downloader.download(baseUrl, LIBRARY, dataDir))
                .isInstanceOf(IOException.class).hasMessageContaining("after 3 attempts");
        assertThat(requests.get(LIBRARY + ".zip.001").get()).isEqualTo(3);
        try (var leftovers = Files.list(dataDir.resolve("tmp"))) {
            assertThat(leftovers).isEmpty();
        }
    }

    @Test
    void rejectsInvalidDatabase() throws Exception {
        Path notSqlite = Files.writeString(dir.resolve("garbage.db"), "this is not sqlite".repeat(100));
        files.put("chunk_num_basic_parts_fts5.txt", "1".getBytes(StandardCharsets.UTF_8));
        files.put(LIBRARY + ".zip.001", zipOf(notSqlite));

        Path dataDir = dir.resolve("data");
        JlcpcbDownloader downloader = downloader();
        assertThatThrownBy(() -> downloader.download(baseUrl, LIBRARY, dataDir)).isInstanceOf(IOException.class);
        assertThat(dataDir.resolve("tmp").resolve(LIBRARY)).doesNotExist();
        assertThat(dataDir.resolve("tmp").resolve(LIBRARY + ".zip")).doesNotExist();
    }

    @Test
    void missingSentinelFails() {
        JlcpcbDownloader downloader = downloader();
        assertThatThrownBy(() -> downloader.download(baseUrl, LIBRARY, dir.resolve("data")))
                .isInstanceOf(IOException.class).hasMessageContaining("404");
    }

    private static byte[] zipOf(Path file) throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(LIBRARY));
            Files.copy(file, zip);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    static JlcpcbDownloader downloader() {
        return TestWiring.wire(new JlcpcbDownloader(), "chunkTimeout", Duration.ofSeconds(30),
                "retryDelay", Duration.ZERO);
    }
}
