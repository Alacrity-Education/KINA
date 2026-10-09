package ro.alacrity.kina.distributor.lcsc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import ro.alacrity.kina.TestWiring;
import ro.alacrity.kina.config.KinaProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The typed table in the refresh and adoption flow of {@link JlcpcbDatabaseManager} (DESIGN.md 9.3). */
class JlcpcbDatabaseManagerFieldIndexTest {

    private static final String LIBRARY = "parts-fts5.db";
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z").truncatedTo(ChronoUnit.SECONDS);

    @TempDir
    Path dir;

    JlcpcbDownloader downloader = mock(JlcpcbDownloader.class);
    JlcpcbDatabaseRepository repository = mock(JlcpcbDatabaseRepository.class);
    JlcpcbFieldIndexBuilder builder;
    JlcpcbSqliteSearch search;
    JlcpcbDatabaseManager manager;
    Path file;

    @BeforeEach
    void setUp() {
        file = dir.resolve(LIBRARY).toAbsolutePath().normalize();
        search = LcscTestSupport.search(file, true);
        builder = spy(LcscTestSupport.builder(file));
        KinaProperties properties = LcscTestSupport.properties(file, 4, true, Duration.ofSeconds(10));
        manager = TestWiring.wire(new JlcpcbDatabaseManager(), "properties", properties, "downloader", downloader,
                "repository", repository, "search", search, "indexBuilder", builder,
                "clock", Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.find()).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        search.close();
    }

    private Path stage() throws Exception {
        return JlcpcbTestDatabase.create(Files.createDirectories(dir.resolve("tmp")).resolve(LIBRARY),
                JlcpcbTestDatabase.typed());
    }

    @Test
    void downloadBuildsTheTableBeforeTheRenameAndInstallsTheMainFileFirst() throws Exception {
        Path staged = stage();
        JlcpcbDownloader.DownloadedDatabase downloaded = new JlcpcbDownloader.DownloadedDatabase(staged,
                JlcpcbDatabaseValidator.validate(staged));
        when(downloader.download(anyString(), anyString(), any())).thenReturn(downloaded);
        doAnswer(inv -> {
            // the sidecar of the new file already exists (built and warmed in tmp) when the rename happens
            assertThat(FieldIndexFile.sidecar(staged)).exists();
            Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return null;
        }).when(downloader).install(downloaded, file);
        doAnswer(inv -> {
            Files.move(inv.getArgument(0), inv.getArgument(1), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return null;
        }).when(downloader).installIndex(any(), any());

        manager.runDownload();

        InOrder order = inOrder(builder, downloader);
        order.verify(builder).buildAndWarm(staged, FieldIndexFile.sidecar(staged));
        order.verify(downloader).install(downloaded, file);
        order.verify(downloader).installIndex(FieldIndexFile.sidecar(staged), search.indexFile());
        assertThat(search.indexFile()).exists();
        assertThat(FieldIndexFile.sidecar(staged)).doesNotExist();
        assertThat(search.fieldIndexAvailable()).isTrue();
        JlcpcbStatus.FieldIndex status = manager.status().orElseThrow().fieldIndex();
        assertThat(status.enabled()).isTrue();
        assertThat(status.available()).isTrue();
        assertThat(status.rows()).isEqualTo(
                JlcpcbTestDatabase.typed().stream().filter(r -> r.stockQuantity() > 0).count());
        assertThat(status.version()).isPositive();
        assertThat(status.builtAt()).isNotNull();
        assertThat(manager.status().orElseThrow().lastError()).isNull();
    }

    @Test
    void aFailedBuildStillInstallsTheNewFileAndTheFtsPathServes() throws Exception {
        Path staged = stage();
        JlcpcbDownloader.DownloadedDatabase downloaded = new JlcpcbDownloader.DownloadedDatabase(staged,
                JlcpcbDatabaseValidator.validate(staged));
        when(downloader.download(anyString(), anyString(), any())).thenReturn(downloaded);
        doAnswer(inv -> {
            Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return null;
        }).when(downloader).install(downloaded, file);
        doAnswer(inv -> {
            throw new IOException("disk full");
        }).when(builder).buildAndWarm(any(), any());

        manager.runDownload();

        verify(downloader, never()).installIndex(any(), any());
        assertThat(search.isAvailable()).isTrue();
        assertThat(search.fieldIndexAvailable()).isFalse();
        assertThat(search.search("10uF X7R 0805", 0, 10).total()).isPositive();
        assertThat(manager.status().orElseThrow().lastError()).contains("typed table").contains("disk full");
    }

    @Test
    void adoptingAFileWithoutTheTableBuildsItInTheBackgroundWhileTheFtsPathServes() throws Exception {
        JlcpcbTestDatabase.create(file, JlcpcbTestDatabase.typed());
        CountDownLatch inBuild = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            inBuild.countDown();
            assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
            return inv.callRealMethod();
        }).when(builder).buildAndWarm(any(), any());
        doAnswer(inv -> {
            Files.move(inv.getArgument(0), inv.getArgument(1), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return null;
        }).when(downloader).installIndex(any(), any());
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(NOW.minus(Duration.ofDays(1))));

        manager.check();   // adopts the file (fresh), then starts the build

        assertThat(inBuild.await(10, TimeUnit.SECONDS)).isTrue();
        JlcpcbStatus.FieldIndex building = manager.status().orElseThrow().fieldIndex();
        assertThat(building.enabled()).isTrue();
        assertThat(building.available()).isFalse();
        assertThat(building.building()).isTrue();
        // meanwhile LCSC searches run on the FTS path
        assertThat(search.fieldIndexAvailable()).isFalse();
        assertThat(search.search("10uF X7R 0805", 0, 10).total()).isPositive();
        assertThat(manager.startIndexBuild()).isFalse();   // one build at a time

        release.countDown();
        await().atMost(Duration.ofSeconds(30)).until(search::fieldIndexAvailable);
        JlcpcbStatus.FieldIndex ready = manager.status().orElseThrow().fieldIndex();
        assertThat(ready.available()).isTrue();
        assertThat(ready.rows()).isPositive();
        await().atMost(Duration.ofSeconds(5)).until(() -> !manager.status().orElseThrow().fieldIndex().building());
        assertThat(search.search("10uF X7R 0805", 0, 10).total()).isPositive();
        assertThat(manager.startIndexBuild()).isFalse();   // nothing left to build
    }

    @Test
    void aTableOfAnOlderExtractorVersionIsRebuilt() throws Exception {
        JlcpcbTestDatabase.create(file, JlcpcbTestDatabase.typed());
        LcscTestSupport.buildIndex(file);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + search.indexFile());
                Statement st = c.createStatement()) {
            st.execute("UPDATE kina_meta SET value = '0' WHERE key = 'index_version'");
        }
        doAnswer(inv -> {
            Files.move(inv.getArgument(0), inv.getArgument(1), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return null;
        }).when(downloader).installIndex(any(), any());
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(NOW.minus(Duration.ofDays(1))));

        assertThat(search.isAvailable()).isTrue();
        assertThat(search.fieldIndexAvailable()).isFalse();   // version mismatch: the FTS path serves

        manager.check();
        await().atMost(Duration.ofSeconds(30)).until(search::fieldIndexAvailable);
        verify(builder).buildAndWarm(file, FieldIndexFile.sidecar(dir.resolve("tmp").resolve(LIBRARY)));
    }

    @Test
    void disabledFieldIndexNeverBuilds() throws Exception {
        JlcpcbTestDatabase.create(file, JlcpcbTestDatabase.typed());
        KinaProperties off = LcscTestSupport.properties(file, 4, false, Duration.ofSeconds(10));
        JlcpcbSqliteSearch plain = TestWiring.wire(new JlcpcbSqliteSearch(), "properties", off);
        JlcpcbDatabaseManager disabled = TestWiring.wire(new JlcpcbDatabaseManager(), "properties", off,
                "downloader", downloader, "repository", repository, "search", plain, "indexBuilder", builder,
                "clock", Clock.fixed(NOW, ZoneOffset.UTC));
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(NOW.minus(Duration.ofDays(1))));
        try {
            disabled.check();
            assertThat(disabled.startIndexBuild()).isFalse();
            verify(builder, never()).buildAndWarm(any(), any());
            JlcpcbStatus.FieldIndex status = disabled.status().orElseThrow().fieldIndex();
            assertThat(status.enabled()).isFalse();
            assertThat(status.available()).isFalse();
            assertThat(status.building()).isFalse();
        } finally {
            plain.close();
        }
    }
}
