package ro.alacrity.kina.distributor.lcsc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.lcsc.JlcpcbDatabaseManager.Decision;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JlcpcbDatabaseManagerTest {

    private static final String LIBRARY = "parts-fts5.db";
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z").truncatedTo(ChronoUnit.SECONDS);

    @TempDir
    Path dir;

    JlcpcbDownloader downloader = mock(JlcpcbDownloader.class);
    JlcpcbDatabaseRepository repository = mock(JlcpcbDatabaseRepository.class);
    JlcpcbSqliteSearch search;
    JlcpcbDatabaseManager manager;
    Path file;

    @BeforeEach
    void setUp() {
        KinaProperties.Jlcpcb config = new KinaProperties.Jlcpcb(dir, LIBRARY, "http://example.invalid/",
                Duration.ofDays(5), Duration.ofHours(1), 200);
        file = config.databaseFile().toAbsolutePath().normalize();
        search = new JlcpcbSqliteSearch(file);
        manager = new JlcpcbDatabaseManager(config, downloader, repository, search, true,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        search.close();
    }

    @Test
    void missingFileTriggersDownload() {
        when(repository.find()).thenReturn(Optional.empty());
        assertThat(manager.evaluate()).isEqualTo(Decision.DOWNLOAD);
        verify(repository, never()).save(any());
    }

    @Test
    void adoptsFreshUnregisteredFileWithoutDownloading() throws Exception {
        JlcpcbTestDatabase.create(file);
        Instant modified = NOW.minus(Duration.ofDays(1));
        Files.setLastModifiedTime(file, FileTime.from(modified));
        when(repository.find()).thenReturn(Optional.empty());

        assertThat(manager.evaluate()).isEqualTo(Decision.UP_TO_DATE);

        ArgumentCaptor<JlcpcbDatabaseInfo> saved = ArgumentCaptor.forClass(JlcpcbDatabaseInfo.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().library()).isEqualTo(LIBRARY);
        assertThat(saved.getValue().filePath()).isEqualTo(file.toString());
        assertThat(saved.getValue().downloadedAt()).isEqualTo(modified);
        assertThat(saved.getValue().partCount()).isEqualTo(JlcpcbTestDatabase.SAMPLE.size());
        assertThat(saved.getValue().sourceDate()).isEqualTo("2026-09-26");
        assertThat(saved.getValue().sizeBytes()).isEqualTo(Files.size(file));

        JlcpcbStatus status = manager.status().orElseThrow();
        assertThat(status.available()).isTrue();
        assertThat(status.downloadedAt()).isEqualTo(modified);
        assertThat(status.downloading()).isFalse();

        manager.check();
        verify(downloader, never()).download(anyString(), anyString(), any());
    }

    @Test
    void adoptsStaleFileAndSchedulesRefresh() throws Exception {
        JlcpcbTestDatabase.create(file);
        Files.setLastModifiedTime(file, FileTime.from(NOW.minus(Duration.ofDays(6))));
        when(repository.find()).thenReturn(Optional.empty());

        assertThat(manager.evaluate()).isEqualTo(Decision.DOWNLOAD);
        verify(repository).save(any());
        assertThat(search.isAvailable()).isTrue();   // the old file keeps serving meanwhile
    }

    @Test
    void invalidUnregisteredFileIsReplaced() throws Exception {
        Files.writeString(file, "not a database");
        when(repository.find()).thenReturn(Optional.empty());
        assertThat(manager.evaluate()).isEqualTo(Decision.DOWNLOAD);
        verify(repository, never()).save(any());
    }

    @Test
    void freshRowMeansUpToDateAndStaleRowMeansDownload() throws Exception {
        JlcpcbTestDatabase.create(file);
        when(repository.find()).thenReturn(Optional.of(
                new JlcpcbDatabaseInfo(LIBRARY, file.toString(), NOW.minus(Duration.ofDays(4)), 1L, 9L, "x")));
        assertThat(manager.evaluate()).isEqualTo(Decision.UP_TO_DATE);

        when(repository.find()).thenReturn(Optional.of(
                new JlcpcbDatabaseInfo(LIBRARY, file.toString(), NOW.minus(Duration.ofDays(5)), 1L, 9L, "x")));
        assertThat(manager.evaluate()).isEqualTo(Decision.DOWNLOAD);
        verify(repository, never()).save(any());
    }

    @Test
    void rowForAnotherLibraryLeadsToAdoption() throws Exception {
        JlcpcbTestDatabase.create(file);
        when(repository.find()).thenReturn(Optional.of(new JlcpcbDatabaseInfo("basic-parts-fts5.db",
                dir.resolve("basic-parts-fts5.db").toString(), NOW, 1L, 1L, "x")));
        Files.setLastModifiedTime(file, FileTime.from(NOW));
        assertThat(manager.evaluate()).isEqualTo(Decision.UP_TO_DATE);
        verify(repository).save(any());
    }

    @Test
    void postgresFailureServesExistingFile() throws Exception {
        JlcpcbTestDatabase.create(file);
        when(repository.find()).thenThrow(new DataAccessResourceFailureException("down"));
        assertThat(manager.evaluate()).isEqualTo(Decision.UP_TO_DATE);
        assertThat(manager.status().orElseThrow().lastError()).contains("down");
    }

    @Test
    void downloadSwapsFileRecordsRowAndNeverRunsTwice() throws Exception {
        Path staged = JlcpcbTestDatabase.create(Files.createDirectories(dir.resolve("tmp")).resolve(LIBRARY));
        JlcpcbDownloader.DownloadedDatabase downloaded = new JlcpcbDownloader.DownloadedDatabase(staged,
                JlcpcbDatabaseValidator.validate(staged));
        CountDownLatch release = new CountDownLatch(1);
        when(downloader.download(anyString(), anyString(), any())).thenAnswer(inv -> {
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return downloaded;
        });
        doAnswer(inv -> {
            Files.move(staged, file);
            return null;
        }).when(downloader).install(downloaded, file);
        when(repository.find()).thenReturn(Optional.empty());
        assertThat(search.isAvailable()).isFalse();

        manager.check();                               // file missing -> starts the download
        assertThat(manager.isDownloading()).isTrue();
        assertThat(manager.startDownload()).isFalse();  // already running
        manager.check();                               // still only one
        release.countDown();

        await().atMost(Duration.ofSeconds(10)).until(() -> !manager.isDownloading());
        verify(downloader, times(1)).download(anyString(), anyString(), any());
        ArgumentCaptor<JlcpcbDatabaseInfo> saved = ArgumentCaptor.forClass(JlcpcbDatabaseInfo.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().downloadedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().partCount()).isEqualTo(JlcpcbTestDatabase.SAMPLE.size());
        assertThat(search.isAvailable()).isTrue();
        assertThat(search.findByLcsc("C1525")).isPresent();
        assertThat(manager.status().orElseThrow().lastError()).isNull();
    }

    @Test
    void failedDownloadIsReported() throws Exception {
        when(repository.find()).thenReturn(Optional.empty());
        when(downloader.download(anyString(), anyString(), any())).thenThrow(new IOException("HTTP 503"));
        manager.check();
        await().atMost(Duration.ofSeconds(10)).until(() -> !manager.isDownloading());
        JlcpcbStatus status = manager.status().orElseThrow();
        assertThat(status.available()).isFalse();
        assertThat(status.lastError()).contains("HTTP 503");
        verify(repository, never()).save(any());
    }
}
