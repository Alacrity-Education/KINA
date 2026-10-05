package ro.alacrity.kina.search.ce;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Downloads cross-encoder model files from an HTTP(S) directory (by default Hugging Face {@code resolve/main/}) into
 * the model directory (DESIGN.md 3.5).
 *
 * <p>Each file is streamed to {@code <dir>/tmp/<name>.part}, its size checked against {@code X-Linked-Size} (Hugging
 * Face LFS) or {@code Content-Length}, its SHA-256 checked when the server names one ({@code X-Linked-Etag} of an LFS
 * file), then atomically moved into place. {@code model.json} records the source, the repository revision
 * ({@code X-Repo-Commit}), the variant, the files and the download time.
 *
 * <p>Timeouts: connect {@value #CONNECT_TIMEOUT_SECONDS}s, whole file {@code download-timeout}. Redirects (Hugging
 * Face to its CDN) are followed; the headers of the redirect responses are inspected too.
 */
public class ModelDownloader {

    private static final Logger log = LoggerFactory.getLogger(ModelDownloader.class);

    static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final JsonMapper MAPPER = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    /** A requested file does not exist at the source (HTTP 404), e.g. a fine-tuned model without that variant. */
    public static class NotFoundException extends IOException {

        private static final long serialVersionUID = 1L;

        public NotFoundException(String message) {
            super(message);
        }
    }

    /** {@code model.json}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Manifest(
            @JsonProperty("source") String source,
            @JsonProperty("repo") String repo,
            @JsonProperty("revision") String revision,
            @JsonProperty("variant") String variant,
            @JsonProperty("onnx_file") String onnxFile,
            @JsonProperty("downloaded_at") String downloadedAt,
            @JsonProperty("files") Map<String, FileInfo> files) {

        public Manifest {
            files = files == null ? Map.of() : Map.copyOf(files);
        }
    }

    /** One downloaded file. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FileInfo(@JsonProperty("size") long size, @JsonProperty("sha256") String sha256) {
    }

    private record Downloaded(long size, String sha256, String revision) {
    }

    private final HttpClient http;
    private final Duration fileTimeout;
    private final Clock clock;

    public ModelDownloader(Duration fileTimeout) {
        this(fileTimeout, Clock.systemUTC());
    }

    ModelDownloader(Duration fileTimeout, Clock clock) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.fileTimeout = fileTimeout;
        this.clock = clock;
    }

    /**
     * Downloads {@code file} (relative path such as {@code onnx/model.onnx}) from {@code baseUrl} into {@code dir}
     * unless it is already there, and records it in the manifest.
     */
    public void ensure(String baseUrl, Path dir, String file, String variant, String onnxFile)
            throws IOException, InterruptedException {
        Path target = dir.resolve(file);
        if (Files.isRegularFile(target)) {
            return;
        }
        Downloaded d = download(baseUrl, file, target, dir.resolve("tmp"));
        Manifest old = readManifest(dir).orElse(null);
        Map<String, FileInfo> files = new LinkedHashMap<>(old == null ? Map.of() : old.files());
        files.put(file, new FileInfo(d.size(), d.sha256()));
        String revision = d.revision() != null ? d.revision() : old == null ? null : old.revision();
        writeManifest(dir, new Manifest(baseUrl, repository(baseUrl), revision, variant, onnxFile,
                clock.instant().toString(), files));
    }

    /** Records the variant / ONNX file in use without downloading anything (e.g. after a fallback). */
    public void updateVariant(Path dir, String variant, String onnxFile) throws IOException {
        Manifest old = readManifest(dir).orElse(null);
        if (old == null || (variant.equals(old.variant()) && onnxFile.equals(old.onnxFile()))) {
            return;
        }
        writeManifest(dir, new Manifest(old.source(), old.repo(), old.revision(), variant, onnxFile,
                old.downloadedAt(), old.files()));
    }

    private Downloaded download(String baseUrl, String file, Path target, Path tmpDir)
            throws IOException, InterruptedException {
        URI uri = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/").resolve(file);
        Files.createDirectories(tmpDir);
        Path part = tmpDir.resolve(file.replace('/', '_') + ".part");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(fileTimeout).GET().build();
        log.info("Downloading cross-encoder file {} from {}", file, safe(uri));
        long started = System.nanoTime();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        boolean ok = false;
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status == 404) {
                throw new NotFoundException(file + " not found at " + safe(uri));
            }
            if (status / 100 != 2) {
                throw new IOException("HTTP " + status + " for " + safe(uri));
            }
            Long expectedSize = expectedSize(response);
            String expectedSha = expectedSha256(response);
            MessageDigest digest = sha256();
            long size = 0;
            try (OutputStream out = Files.newOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = body.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("download of " + file + " interrupted");
                    }
                    out.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                    size += n;
                }
            }
            if (expectedSize != null && size != expectedSize) {
                throw new IOException(file + ": size " + size + " bytes, expected " + expectedSize);
            }
            if (size == 0) {
                throw new IOException(file + ": empty response");
            }
            String sha = HexFormat.of().formatHex(digest.digest());
            if (expectedSha != null && !expectedSha.equals(sha)) {
                throw new IOException(file + ": SHA-256 " + sha + ", expected " + expectedSha);
            }
            Files.createDirectories(target.toAbsolutePath().getParent());
            try {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            }
            ok = true;
            log.info("Downloaded cross-encoder file {} ({} bytes) in {} ms", file, size,
                    (System.nanoTime() - started) / 1_000_000);
            return new Downloaded(size, sha, header(response, "x-repo-commit"));
        } finally {
            if (!ok) {
                Files.deleteIfExists(part);
            }
        }
    }

    /** {@code X-Linked-Size} of any response in the redirect chain (LFS), else the final {@code Content-Length}. */
    private static Long expectedSize(HttpResponse<?> response) {
        String linked = header(response, "x-linked-size");
        String value = linked != null ? linked : response.headers().firstValue("content-length").orElse(null);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The SHA-256 named by {@code X-Linked-Etag} (Hugging Face LFS files), or null. */
    private static String expectedSha256(HttpResponse<?> response) {
        String etag = header(response, "x-linked-etag");
        if (etag == null) {
            return null;
        }
        String v = etag.replace("W/", "").replace("\"", "").trim().toLowerCase(Locale.ROOT);
        return SHA256.matcher(v).matches() ? v : null;
    }

    /** First value of {@code name} in the final response or any redirect response before it. */
    static String header(HttpResponse<?> response, String name) {
        for (HttpResponse<?> r = response; r != null; r = r.previousResponse().orElse(null)) {
            HttpHeaders h = r.headers();
            Optional<String> v = h.firstValue(name);
            if (v.isPresent() && !v.get().isBlank()) {
                return v.get().trim();
            }
        }
        return null;
    }

    public static Optional<Manifest> readManifest(Path dir) {
        Path file = dir.resolve(ModelLayout.MANIFEST);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(file.toFile(), Manifest.class));
        } catch (JacksonException e) {
            log.warn("Ignoring unreadable cross-encoder manifest {}: {}", file, e.getOriginalMessage());
            return Optional.empty();
        }
    }

    static void writeManifest(Path dir, Manifest manifest) throws IOException {
        Path tmp = dir.resolve("tmp").resolve(ModelLayout.MANIFEST + ".part");
        Files.createDirectories(tmp.getParent());
        Files.writeString(tmp, MAPPER.writeValueAsString(manifest));
        try {
            Files.move(tmp, dir.resolve(ModelLayout.MANIFEST), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, dir.resolve(ModelLayout.MANIFEST), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** {@code cross-encoder/ms-marco-MiniLM-L6-v2} for a Hugging Face {@code resolve} URL, else null. */
    static String repository(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            if (uri.getHost() == null || !uri.getHost().endsWith("huggingface.co")) {
                return null;
            }
            String path = uri.getPath();
            int resolve = path.indexOf("/resolve/");
            return resolve > 1 ? path.substring(1, resolve) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** URI without user info or query (never log credentials embedded in a URL). */
    static String safe(URI uri) {
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + uri.getPath();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
