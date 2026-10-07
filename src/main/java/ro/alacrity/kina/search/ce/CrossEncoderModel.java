package ro.alacrity.kina.search.ce;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.config.KinaProperties.CrossEncoder.Variant;
import ro.alacrity.kina.search.RankingScoreCache;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the cross-encoder model (DESIGN.md 3.5): makes sure the files are in {@code kina.ranking.cross-encoder.model-dir}
 * (downloading missing ones from {@code model-url}), loads the tokenizer and the ONNX session, warms it up and
 * publishes it. Never blocks startup: the first attempt runs on a virtual thread after {@link ApplicationReadyEvent};
 * a failed or incomplete attempt is logged once at WARN and retried every {@code check-interval}. Until a model is
 * loaded, ranking falls back to the deterministic order.
 *
 * <p>Variant: {@code int8} tries the int8 file matching the CPU, then the other int8 file, then fp32; a file that is
 * missing at the source (404) or fails to create a session or to run the warm-up is skipped.
 *
 * <p>{@code model-url} may be a local directory (absolute path or {@code file:} URI): it is used in place and read
 * only, nothing is downloaded or written (the Docker image bundles the model this way at {@code /opt/kina/cross-encoder}).
 * When no network source is involved (local directory or {@code auto-download=false}), a failure means the files are
 * missing or unusable and is logged at ERROR; download failures are logged at WARN.
 */
@Component
@Slf4j
public class CrossEncoderModel {

    /** A loaded, warmed-up model. */
    public record Loaded(BertTokenizer tokenizer, ScoringBackend backend, Variant variant, String onnxFile,
                         Path dir, String revision) {
    }

    /** Loads a backend from an ONNX file (replaced in tests). */
    @FunctionalInterface
    public interface BackendFactory {
        ScoringBackend load(Path onnxFile, int threads) throws Exception;
    }

    @Autowired private KinaProperties properties;
    @Autowired private RankingScoreCache scoreCache;
    private BackendFactory backends = OnnxScoringBackend::load;
    private Set<String> cpuFlags = ModelLayout.cpuFlags();
    private String arch = System.getProperty("os.arch", "");
    private KinaProperties.CrossEncoder config;
    private ModelDownloader downloader;

    private final AtomicBoolean loading = new AtomicBoolean();
    private volatile Loaded loaded;
    private volatile String lastError;
    private volatile String lastLoggedError;

    @PostConstruct
    void init() {
        config = properties.ranking().crossEncoder();
        downloader = new ModelDownloader(config.downloadTimeout());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!config.enabled()) {
            log.info("Cross-encoder ranking disabled (kina.ranking.cross-encoder.enabled=false)");
            return;
        }
        Path local = localSource(config.modelUrl());
        if (local != null) {
            log.info("Cross-encoder model: local directory {} used in place (read only, no download)",
                    local.toAbsolutePath().normalize());
        } else if (config.autoDownload()) {
            log.info("Cross-encoder model: {}, downloaded on demand from {}", config.resolvedModelDir(),
                    safeUrl(config.modelUrl()));
        } else {
            log.info("Cross-encoder model: {} (auto-download disabled)", config.resolvedModelDir());
        }
        Thread.ofVirtual().name("cross-encoder-load").start(this::check);
    }

    @Scheduled(initialDelayString = "${kina.ranking.cross-encoder.check-interval:1h}",
            fixedDelayString = "${kina.ranking.cross-encoder.check-interval:1h}")
    public void scheduledCheck() {
        if (config.enabled() && loaded == null) {
            check();
        }
    }

    /** Loads the model unless it is loaded or loading. Never throws. Returns true when a model is loaded. */
    public boolean check() {
        if (loaded != null) {
            return true;
        }
        if (!loading.compareAndSet(false, true)) {
            return false;
        }
        try {
            Loaded l = load();
            loaded = l;
            lastError = null;
            lastLoggedError = null;
            if (scoreCache != null) {
                scoreCache.clear();
            }
            log.info("Cross-encoder ready: {} ({}), revision {}, {} threads, from {}", l.onnxFile(),
                    l.variant().name().toLowerCase(Locale.ROOT), l.revision() == null ? "unknown" : l.revision(),
                    config.effectiveThreads(), l.dir());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted");
            return false;
        } catch (Exception e) {
            fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return false;
        } finally {
            loading.set(false);
        }
    }

    private void fail(String message) {
        lastError = message;
        if (!Objects.equals(message, lastLoggedError)) {
            lastLoggedError = message;
            if (localSource(config.modelUrl()) != null || !config.autoDownload()) {
                log.error("Cross-encoder model files in {} are missing or unusable, ranking falls back to the "
                        + "deterministic order (checking again every {}): {}", modelDir(), config.checkInterval(),
                        message);
            } else {
                log.warn("Cross-encoder model not available, ranking falls back to the deterministic order "
                        + "(retrying every {}): {}", config.checkInterval(), message);
            }
        } else {
            log.debug("Cross-encoder model still not available: {}", message);
        }
    }

    private Loaded load() throws Exception {
        Path local = localSource(config.modelUrl());
        Path dir = local != null ? local.toAbsolutePath().normalize() : config.resolvedModelDir();
        boolean download = local == null && config.autoDownload();
        List<String> failures = new ArrayList<>();
        if (download) {
            replaceFilesOfAnotherSource(dir);
        }
        for (String file : ModelLayout.COMMON_FILES) {
            if (!Files.isRegularFile(dir.resolve(file))) {
                if (!download) {
                    throw new IOException((Files.isDirectory(dir) ? "model files not found in " + dir + " ("
                            : "model directory " + dir + " does not exist (") + file + " missing"
                            + (local == null ? ", auto-download disabled)" : ")"));
                }
                downloader.ensure(config.modelUrl(), dir, file, variantName(config.variant()), null);
            }
        }
        BertTokenizer tokenizer = BertTokenizer.load(dir.resolve(ModelLayout.VOCAB));
        for (String onnx : ModelLayout.onnxCandidates(config.variant(), cpuFlags, arch)) {
            Path path = dir.resolve(onnx);
            if (!Files.isRegularFile(path)) {
                if (!download) {
                    failures.add(onnx + " missing");
                    continue;
                }
                try {
                    downloader.ensure(config.modelUrl(), dir, onnx, variantName(ModelLayout.variantOf(onnx)), onnx);
                } catch (ModelDownloader.NotFoundException e) {
                    failures.add(onnx + " not at source");
                    continue;
                }
            }
            ScoringBackend backend = null;
            try {
                backend = backends.load(path, config.effectiveThreads());
                long t0 = System.nanoTime();
                backend.score(List.of(tokenizer.encodePair("warm up", "10uF 25V X7R 0805 MLCC",
                        config.maxSequenceLength())), Long.MAX_VALUE);
                log.debug("Cross-encoder warm-up took {} ms", (System.nanoTime() - t0) / 1_000_000);
            } catch (Exception | UnsatisfiedLinkError e) {
                if (backend != null) {
                    backend.close();
                }
                log.warn("Cannot use cross-encoder file {}: {}", onnx, e.getMessage());
                failures.add(onnx + ": " + e.getMessage());
                continue;
            }
            Variant variant = ModelLayout.variantOf(onnx);
            if (download) {   // a local or not-managed directory is never written (it may be read only)
                try {
                    downloader.updateVariant(dir, variantName(variant), onnx);
                } catch (IOException e) {
                    log.debug("Cannot update the cross-encoder manifest: {}", e.getMessage());
                }
            }
            String revision = ModelDownloader.readManifest(dir).map(ModelDownloader.Manifest::revision).orElse(null);
            return new Loaded(tokenizer, backend, variant, onnx, dir, revision);
        }
        throw new IOException("no usable ONNX file in " + dir + ": " + String.join("; ", failures));
    }

    /**
     * When {@code model.json} says the files came from another {@code model-url}, deletes the model files so the
     * configured source is downloaded instead of mixing two models. Directories without a manifest
     * (pre-provisioned by hand) are left alone.
     */
    private void replaceFilesOfAnotherSource(Path dir) throws IOException {
        ModelDownloader.Manifest manifest = ModelDownloader.readManifest(dir).orElse(null);
        if (manifest == null || manifest.source() == null
                || stripSlash(manifest.source()).equals(stripSlash(config.modelUrl()))) {
            return;
        }
        log.info("Cross-encoder source changed from {} to {}, replacing the model files in {}", manifest.source(),
                config.modelUrl(), dir);
        List<String> files = new ArrayList<>(ModelLayout.COMMON_FILES);
        files.addAll(List.of(ModelLayout.FP32, ModelLayout.QINT8_AVX512_VNNI, ModelLayout.QUINT8_AVX2,
                ModelLayout.MANIFEST));
        for (String f : files) {
            Files.deleteIfExists(dir.resolve(f));
        }
    }

    private static String stripSlash(String url) {
        String u = url.trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    /** A local directory named by {@code model-url} (absolute path or {@code file:} URI), else null. */
    public static Path localSource(String modelUrl) {
        if (modelUrl == null || modelUrl.isBlank()) {
            return null;
        }
        String url = modelUrl.trim();
        if (url.startsWith("file:")) {
            return Path.of(URI.create(url));
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return null;
        }
        return Path.of(url);
    }

    private static String safeUrl(String url) {
        try {
            return ModelDownloader.safe(URI.create(url.trim()));
        } catch (IllegalArgumentException e) {
            return "(invalid URL)";
        }
    }

    private static String variantName(Variant v) {
        return v.name().toLowerCase(Locale.ROOT);
    }

    /** The loaded model, or null while not loaded. */
    public Loaded loaded() {
        return loaded;
    }

    public boolean isReady() {
        return loaded != null;
    }

    public String lastError() {
        return lastError;
    }

    public boolean isEnabled() {
        return config.enabled();
    }

    /** Directory the model is (to be) loaded from. */
    public Path modelDir() {
        Loaded l = loaded;
        if (l != null) {
            return l.dir();
        }
        Path local = localSource(config.modelUrl());
        return local != null ? local.toAbsolutePath().normalize() : config.resolvedModelDir();
    }

    @PreDestroy
    public void close() {
        Loaded l = loaded;
        loaded = null;
        if (l != null) {
            l.backend().close();
        }
    }
}
