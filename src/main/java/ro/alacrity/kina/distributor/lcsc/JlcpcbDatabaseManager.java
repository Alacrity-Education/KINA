package ro.alacrity.kina.distributor.lcsc;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.metrics.KinaMetrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the JLCPCB database present and fresh (DESIGN.md 9.3). Never blocks startup: the first check runs on a
 * virtual thread after {@link ApplicationReadyEvent}, downloads run on their own virtual thread, and at most one
 * download runs at a time. A {@link Scheduled} check repeats the decision every {@code kina.jlcpcb.check-interval}.
 *
 * <p>Decision ({@link #evaluate()}):
 * <ol>
 *   <li>file missing -&gt; download;</li>
 *   <li>file present but no {@code jlcpcb_database} row describing it (pre-seeded or restored volume) -&gt; adopt it:
 *       validate, insert the row with {@code downloaded_at} = the file's last-modified time; an invalid file is
 *       re-downloaded;</li>
 *   <li>row older than {@code kina.jlcpcb.refresh-after} -&gt; download (the old file keeps serving meanwhile);</li>
 *   <li>otherwise nothing.</li>
 * </ol>
 * When Postgres cannot be read, an existing file is served and no download is started.
 *
 * <p>{@code kina.jlcpcb.auto-download} (default true) disables starting downloads, e.g. in tests.
 */
@Component
@Slf4j
public class JlcpcbDatabaseManager {

    /** What {@link #evaluate()} concluded. */
    enum Decision { UP_TO_DATE, DOWNLOAD }

    @Autowired private KinaProperties properties;
    @Autowired private JlcpcbDownloader downloader;
    @Autowired private JlcpcbDatabaseRepository repository;
    @Autowired private JlcpcbSqliteSearch search;
    @Autowired private KinaMetrics metrics = KinaMetrics.NOOP;
    private Clock clock = Clock.systemUTC();
    private KinaProperties.Jlcpcb config;

    private final AtomicBoolean downloading = new AtomicBoolean();
    private final Object checkLock = new Object();
    private volatile Thread downloadThread;
    private volatile JlcpcbDatabaseInfo current;
    private volatile String lastError;

    @PostConstruct
    void init() {
        config = properties.jlcpcb();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        Thread.ofVirtual().name("jlcpcb-startup-check").start(() -> {
            downloader.cleanTemp(config.dataDir(), config.library());
            check();
        });
    }

    @Scheduled(initialDelayString = "${kina.jlcpcb.check-interval:1h}",
            fixedDelayString = "${kina.jlcpcb.check-interval:1h}")
    public void scheduledCheck() {
        check();
    }

    /** Evaluates the refresh policy and starts a background download when needed. Never throws. */
    public void check() {
        try {
            if (evaluate() == Decision.DOWNLOAD) {
                if (config.autoDownload()) {
                    startDownload();
                } else {
                    log.info("JLCPCB database needs a download but kina.jlcpcb.auto-download is false");
                }
            }
        } catch (RuntimeException e) {
            lastError = e.getMessage();
            log.warn("JLCPCB database check failed", e);
        }
    }

    /** Applies the decision rules (see class comment), adopting an unregistered file on the way. */
    Decision evaluate() {
        synchronized (checkLock) {
            Path file = search.databaseFile();
            boolean fileExists = Files.isRegularFile(file);
            if (fileExists) {
                search.isAvailable();   // opens the file if not open yet
            }
            Optional<JlcpcbDatabaseInfo> row;
            try {
                row = repository.find();
            } catch (DataAccessException e) {
                lastError = "cannot read jlcpcb_database: " + e.getMessage();
                log.warn("Cannot read jlcpcb_database, serving the existing file if any: {}", e.getMessage());
                return fileExists ? Decision.UP_TO_DATE : Decision.DOWNLOAD;
            }
            if (!fileExists) {
                log.info("JLCPCB database {} is missing", file);
                return Decision.DOWNLOAD;
            }
            JlcpcbDatabaseInfo info = row.filter(r -> describes(r, file)).orElse(null);
            if (info == null) {
                info = adopt(file);
                if (info == null) {
                    return Decision.DOWNLOAD;
                }
            }
            current = info;
            Instant refreshAt = info.downloadedAt().plus(config.refreshAfter());
            if (!refreshAt.isAfter(clock.instant())) {
                log.info("JLCPCB database downloaded at {} is older than {}", info.downloadedAt(), config.refreshAfter());
                return Decision.DOWNLOAD;
            }
            return Decision.UP_TO_DATE;
        }
    }

    private boolean describes(JlcpcbDatabaseInfo row, Path file) {
        return config.library().equals(row.library()) && file.toString().equals(normalise(row.filePath()));
    }

    /** Registers an existing, unregistered file; null when it is not a valid database. */
    private JlcpcbDatabaseInfo adopt(Path file) {
        try {
            JlcpcbDatabaseValidator.Metadata meta = JlcpcbDatabaseValidator.validate(file);
            Instant modified = Files.getLastModifiedTime(file).toInstant();
            JlcpcbDatabaseInfo info = new JlcpcbDatabaseInfo(config.library(), file.toString(), modified,
                    meta.sizeBytes(), meta.partCount(), meta.sourceDate());
            repository.save(info);
            log.info("Adopted existing JLCPCB database {} ({} parts, modified {})", file, meta.partCount(), modified);
            return info;
        } catch (IOException e) {
            lastError = e.getMessage();
            log.warn("Existing JLCPCB database {} is not usable, downloading a new one: {}", file, e.getMessage());
            return null;
        }
    }

    /** Starts a background download unless one is already running. */
    boolean startDownload() {
        if (!downloading.compareAndSet(false, true)) {
            log.debug("JLCPCB download already running");
            return false;
        }
        try {
            downloadThread = Thread.ofVirtual().name("jlcpcb-download").start(() -> {
                try {
                    runDownload();
                } finally {
                    downloading.set(false);
                    downloadThread = null;
                }
            });
            return true;
        } catch (RuntimeException e) {
            downloading.set(false);
            throw e;
        }
    }

    /** Downloads, swaps the file under the search write lock and records the row. Runs on the download thread. */
    void runDownload() {
        Path target = search.databaseFile();
        try {
            JlcpcbDownloader.DownloadedDatabase downloaded =
                    downloader.download(config.baseUrl(), config.library(), config.dataDir());
            search.replaceDatabase(() -> downloader.install(downloaded, target));
            JlcpcbDatabaseInfo info = new JlcpcbDatabaseInfo(config.library(), target.toString(), clock.instant(),
                    downloaded.metadata().sizeBytes(), downloaded.metadata().partCount(), downloaded.metadata().sourceDate());
            current = info;
            lastError = null;
            repository.save(info);
            metrics.jlcpcbDownload("ok");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            metrics.jlcpcbDownload("interrupted");
            lastError = "download interrupted";
            log.info("JLCPCB download interrupted");
            downloader.cleanTemp(config.dataDir(), config.library());
        } catch (IOException | RuntimeException e) {
            lastError = e.getMessage();
            metrics.jlcpcbDownload("failed");
            log.warn("JLCPCB database download failed: {}", e.toString());
            downloader.cleanTemp(config.dataDir(), config.library());
        }
    }

    public boolean isDownloading() {
        return downloading.get();
    }

    /** Current state for diagnostics; always present. */
    public Optional<JlcpcbStatus> status() {
        JlcpcbDatabaseInfo info = current;
        return Optional.of(new JlcpcbStatus(
                search.isAvailable(),
                search.databaseFile(),
                config.library(),
                info == null ? null : info.downloadedAt(),
                info == null ? null : info.partCount(),
                info == null ? null : info.sourceDate(),
                downloading.get(),
                lastError));
    }

    @PreDestroy
    public void shutdown() {
        Thread t = downloadThread;
        if (t != null) {
            t.interrupt();
        }
    }

    private static String normalise(String path) {
        return path == null ? "" : Path.of(path).toAbsolutePath().normalize().toString();
    }
}
