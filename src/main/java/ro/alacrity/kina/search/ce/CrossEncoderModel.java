package ro.alacrity.kina.search.ce;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>{@code model-url} may be a local directory (absolute path or {@code file:} URI): it is used in place, nothing is
 * downloaded.
 */
@Component
public class CrossEncoderModel {

    private static final Logger log = LoggerFactory.getLogger(CrossEncoderModel.class);

    /** A loaded, warmed-up model. */
    public record Loaded(BertTokenizer tokenizer, ScoringBackend backend, Variant variant, String onnxFile,
                         Path dir, String revision) {
    }

    /** Loads a backend from an ONNX file (replaced in tests). */
    @FunctionalInterface
    public interface BackendFactory {
        ScoringBackend load(Path onnxFile, int threads) throws Exception;
    }

    private final KinaProperties.CrossEncoder config;
    private final ModelDownloader downloader;
    private final BackendFactory backends;
    private final RankingScoreCache scoreCache;
    private final Set<String> cpuFlags;
    private final String arch;

    private final AtomicBoolean loading = new AtomicBoolean();
    private volatile Loaded loaded;
    private volatile String lastError;
    private volatile String lastLoggedError;

    @Autowired
    public CrossEncoderModel(KinaProperties properties, RankingScoreCache scoreCache) {
        this(properties.ranking().crossEncoder(), new ModelDownloader(properties.ranking().crossEncoder()
                .downloadTimeout()), OnnxScoringBackend::load, scoreCache, ModelLayout.cpuFlags(),
                System.getProperty("os.arch", ""));
    }

    CrossEncoderModel(KinaProperties.CrossEncoder config, ModelDownloader downloader, BackendFactory backends,
                      RankingScoreCache scoreCache, Set<String> cpuFlags, String arch) {
        this.config = config;
        this.downloader = downloader;
        this.backends = backends;
        this.scoreCache = scoreCache;
        this.cpuFlags = cpuFlags;
        this.arch = arch;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!config.enabled()) {
            log.info("Cross-encoder ranking disabled (kina.ranking.cross-encoder.enabled=false)");
            return;
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
            log.warn("Cross-encoder model not available, ranking falls back to the deterministic order "
                    + "(retrying every {}): {}", config.checkInterval(), message);
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
                    throw new IOException("model files not found in " + dir + " (" + file + " missing"
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
            if (local == null) {
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
    static Path localSource(String modelUrl) {
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
