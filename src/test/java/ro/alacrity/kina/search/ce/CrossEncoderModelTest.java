package ro.alacrity.kina.search.ce;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.alacrity.kina.TestWiring;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.config.KinaProperties.CrossEncoder.Variant;
import ro.alacrity.kina.search.RankingScoreCache;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Model download (in-process HTTP server), verification, manifest, variant choice and fallbacks. */
class CrossEncoderModelTest {

    static final String REVISION = "c5ee24cb16019beea0893ab7796b1df96625c6b8";

    @TempDir
    Path tmp;

    HttpServer server;
    String baseUrl;
    final Map<String, byte[]> files = new ConcurrentHashMap<>();
    final Map<String, String> wrongSize = new ConcurrentHashMap<>();
    final AtomicInteger requests = new AtomicInteger();
    final List<Path> loadedFiles = new CopyOnWriteArrayList<>();
    final Set<String> brokenModels = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void startServer() throws IOException {
        files.put(ModelLayout.VOCAB, String.join("\n", BertTokenizerTestVocab.TOKENS).getBytes(StandardCharsets.UTF_8));
        files.put(ModelLayout.CONFIG, "{\"model_type\":\"bert\"}".getBytes(StandardCharsets.UTF_8));
        files.put(ModelLayout.TOKENIZER_CONFIG, "{\"do_lower_case\":true}".getBytes(StandardCharsets.UTF_8));
        files.put(ModelLayout.FP32, "fp32-graph".repeat(100).getBytes(StandardCharsets.UTF_8));
        files.put(ModelLayout.QINT8_AVX512_VNNI, "qint8-graph".repeat(50).getBytes(StandardCharsets.UTF_8));
        files.put(ModelLayout.QUINT8_AVX2, "quint8-graph".repeat(50).getBytes(StandardCharsets.UTF_8));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Hugging Face style: /repo/resolve/main/<file> answers 302 with X-Repo-Commit and the LFS headers,
        // the CDN path serves the bytes
        server.createContext("/repo/resolve/main/", exchange -> {
            requests.incrementAndGet();
            String file = exchange.getRequestURI().getPath().substring("/repo/resolve/main/".length());
            byte[] body = files.get(file);
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("X-Repo-Commit", REVISION);
            if (file.endsWith(".onnx")) {
                exchange.getResponseHeaders().add("X-Linked-Size", wrongSize.getOrDefault(file,
                        String.valueOf(body.length)));
                exchange.getResponseHeaders().add("X-Linked-Etag", "\"" + sha256(body) + "\"");
            }
            exchange.getResponseHeaders().add("Location", "/cdn/" + file);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/cdn/", exchange -> {
            byte[] body = files.get(exchange.getRequestURI().getPath().substring("/cdn/".length()));
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/repo/resolve/main/";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private KinaProperties config(String... kv) {
        String[] all = new String[kv.length + 6];
        all[0] = "model-dir";
        all[1] = tmp.resolve("data/jlcpcb/../cross-encoder").toString();
        all[2] = "model-url";
        all[3] = baseUrl;
        all[4] = "download-timeout";
        all[5] = "10s";
        System.arraycopy(kv, 0, all, 6, kv.length);
        return CrossEncoderPartRankerTest.properties(all);
    }

    private CrossEncoderModel model(KinaProperties properties, Set<String> cpuFlags) {
        CrossEncoderModel.BackendFactory backends = (file, threads) -> {
            if (brokenModels.stream().anyMatch(file::endsWith)) {
                throw new IllegalStateException("cannot create session for " + file.getFileName());
            }
            loadedFiles.add(file);
            return new CrossEncoderPartRankerTest.FakeBackend();
        };
        return TestWiring.wire(new CrossEncoderModel(), "properties", properties,
                "scoreCache", new RankingScoreCache(Duration.ofHours(1)), "backends", backends,
                "cpuFlags", cpuFlags, "arch", "amd64");
    }

    @Test
    void downloadsVerifiesAndRecordsTheManifestThenLoads() throws Exception {
        CrossEncoderModel model = model(config(), Set.of("avx2", "avx_vnni"));
        assertThat(model.check()).as(model.lastError()).isTrue();

        Path dir = tmp.resolve("data/cross-encoder");
        assertThat(model.modelDir()).isEqualTo(dir);   // ../ normalised
        assertThat(dir.resolve(ModelLayout.VOCAB)).exists();
        assertThat(dir.resolve(ModelLayout.QINT8_AVX512_VNNI)).hasBinaryContent(files.get(ModelLayout.QINT8_AVX512_VNNI));
        assertThat(dir.resolve(ModelLayout.FP32)).doesNotExist();
        assertThat(dir.resolve("tmp")).isEmptyDirectory();
        assertThat(loadedFiles).containsExactly(dir.resolve(ModelLayout.QINT8_AVX512_VNNI));

        ModelDownloader.Manifest manifest = ModelDownloader.readManifest(dir).orElseThrow();
        assertThat(manifest.revision()).isEqualTo(REVISION);
        assertThat(manifest.variant()).isEqualTo("int8");
        assertThat(manifest.onnxFile()).isEqualTo(ModelLayout.QINT8_AVX512_VNNI);
        assertThat(manifest.source()).isEqualTo(baseUrl);
        assertThat(manifest.downloadedAt()).isNotBlank();
        assertThat(manifest.files().get(ModelLayout.QINT8_AVX512_VNNI).sha256())
                .isEqualTo(sha256(files.get(ModelLayout.QINT8_AVX512_VNNI)));
        assertThat(manifest.files().get(ModelLayout.QINT8_AVX512_VNNI).size())
                .isEqualTo(files.get(ModelLayout.QINT8_AVX512_VNNI).length);
        assertThat(model.loaded().revision()).isEqualTo(REVISION);
        assertThat(model.loaded().variant()).isEqualTo(Variant.INT8);

        // a second instance on the same (now pre-provisioned) directory downloads nothing
        int before = requests.get();
        CrossEncoderModel again = model(config(), Set.of("avx2", "avx_vnni"));
        assertThat(again.check()).isTrue();
        assertThat(requests.get()).isEqualTo(before);
    }

    @Test
    void wrongSizeIsRejectedAndRetriedOnTheNextCheck() {
        wrongSize.put(ModelLayout.QUINT8_AVX2, "999999");
        files.remove(ModelLayout.FP32);
        CrossEncoderModel model = model(config(), Set.of("avx2"));
        assertThat(model.check()).isFalse();
        assertThat(model.isReady()).isFalse();
        assertThat(model.lastError()).contains("size");
        Path dir = tmp.resolve("data/cross-encoder");
        assertThat(dir.resolve(ModelLayout.QUINT8_AVX2)).doesNotExist();
        assertThat(dir.resolve("tmp")).isEmptyDirectory();

        wrongSize.clear();
        assertThat(model.check()).isTrue();
        assertThat(model.lastError()).isNull();
        assertThat(model.loaded().onnxFile()).isEqualTo(ModelLayout.QUINT8_AVX2);
    }

    @Test
    void missingInt8FileAtTheSourceFallsBackToTheOtherInt8FileThenFp32() {
        files.remove(ModelLayout.QINT8_AVX512_VNNI);
        CrossEncoderModel model = model(config(), Set.of("avx512_vnni"));
        assertThat(model.check()).isTrue();
        assertThat(model.loaded().onnxFile()).isEqualTo(ModelLayout.QUINT8_AVX2);

        files.remove(ModelLayout.QUINT8_AVX2);
        CrossEncoderModel other = model(config("model-dir", tmp.resolve("other").toString()), Set.of("avx2"));
        assertThat(other.check()).isTrue();
        assertThat(other.loaded().onnxFile()).isEqualTo(ModelLayout.FP32);
        assertThat(other.loaded().variant()).isEqualTo(Variant.FP32);
        assertThat(ModelDownloader.readManifest(tmp.resolve("other")).orElseThrow().variant()).isEqualTo("fp32");
    }

    @Test
    void sessionFailureFallsBackToFp32() {
        brokenModels.add(ModelLayout.QINT8_AVX512_VNNI);
        brokenModels.add(ModelLayout.QUINT8_AVX2);
        CrossEncoderModel model = model(config(), Set.of("avx_vnni"));
        assertThat(model.check()).isTrue();
        assertThat(model.loaded().onnxFile()).isEqualTo(ModelLayout.FP32);
        assertThat(tmp.resolve("data/cross-encoder").resolve(ModelLayout.FP32)).exists();
    }

    @Test
    void fp32VariantAndCpuWithoutAvx2UseTheFp32File() {
        assertThat(ModelLayout.onnxCandidates(Variant.FP32, Set.of("avx512_vnni"), "amd64"))
                .containsExactly(ModelLayout.FP32);
        assertThat(ModelLayout.onnxCandidates(Variant.INT8, Set.of("sse4_2"), "amd64"))
                .containsExactly(ModelLayout.FP32);
        assertThat(ModelLayout.onnxCandidates(Variant.INT8, Set.of("avx2"), "amd64"))
                .containsExactly(ModelLayout.QUINT8_AVX2, ModelLayout.QINT8_AVX512_VNNI, ModelLayout.FP32);
        assertThat(ModelLayout.onnxCandidates(Variant.INT8, Set.of(), "aarch64"))
                .containsExactly(ModelLayout.QINT8_AVX512_VNNI, ModelLayout.QUINT8_AVX2, ModelLayout.FP32);
        CrossEncoderModel model = model(config("variant", "fp32"), Set.of("avx2"));
        assertThat(model.check()).isTrue();
        assertThat(model.loaded().onnxFile()).isEqualTo(ModelLayout.FP32);
    }

    @Test
    void localModelUrlIsUsedInPlaceWithoutDownloading() throws Exception {
        Path local = tmp.resolve("finetuned");
        for (String f : List.of(ModelLayout.VOCAB, ModelLayout.CONFIG, ModelLayout.TOKENIZER_CONFIG, ModelLayout.FP32)) {
            Files.createDirectories(local.resolve(f).getParent());
            Files.write(local.resolve(f), files.get(f));
        }
        CrossEncoderModel model = model(config("model-url", local.toString()), Set.of("avx2"));
        assertThat(model.check()).isTrue();
        assertThat(model.loaded().dir()).isEqualTo(local);
        assertThat(model.loaded().onnxFile()).isEqualTo(ModelLayout.FP32);   // no int8 file there
        assertThat(requests.get()).isZero();
        assertThat(CrossEncoderModel.localSource("file:" + local)).isEqualTo(local);
        assertThat(CrossEncoderModel.localSource(baseUrl)).isNull();
    }

    @Test
    void bundledReadOnlyDirectoryIsUsedInPlaceWithoutWrites() throws Exception {
        // the layout docker/model/fetch-model.sh writes into the image (/opt/kina/cross-encoder)
        Path bundled = tmp.resolve("opt/kina/cross-encoder");
        for (String f : List.of(ModelLayout.VOCAB, ModelLayout.CONFIG, ModelLayout.TOKENIZER_CONFIG, ModelLayout.FP32,
                ModelLayout.QINT8_AVX512_VNNI, ModelLayout.QUINT8_AVX2)) {
            Files.createDirectories(bundled.resolve(f).getParent());
            Files.write(bundled.resolve(f), files.get(f));
        }
        String manifest = """
                {
                  "source" : "https://huggingface.co/cross-encoder/ms-marco-MiniLM-L6-v2/resolve/%s/",
                  "repo" : "cross-encoder/ms-marco-MiniLM-L6-v2",
                  "revision" : "%s",
                  "variant" : "int8",
                  "variants" : [ "int8", "fp32" ],
                  "provisioned_by" : "docker/model/fetch-model.sh",
                  "downloaded_at" : "2026-10-06T00:00:00Z",
                  "files" : { "vocab.txt" : { "size" : 1, "sha256" : "00" } }
                }
                """.formatted(REVISION, REVISION);
        Files.writeString(bundled.resolve(ModelLayout.MANIFEST), manifest);
        Set<java.nio.file.attribute.PosixFilePermission> readOnly =
                java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x");
        Set<java.nio.file.attribute.PosixFilePermission> writable =
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x");
        Files.setPosixFilePermissions(bundled.resolve("onnx"), readOnly);
        Files.setPosixFilePermissions(bundled, readOnly);
        try {
            CrossEncoderModel vnni = model(config("model-url", bundled.toString(), "auto-download", "false"),
                    Set.of("avx2", "avx_vnni"));
            assertThat(vnni.check()).as(vnni.lastError()).isTrue();
            assertThat(vnni.loaded().dir()).isEqualTo(bundled);
            assertThat(vnni.loaded().onnxFile()).isEqualTo(ModelLayout.QINT8_AVX512_VNNI);
            assertThat(vnni.loaded().revision()).isEqualTo(REVISION);
            assertThat(vnni.modelDir()).isEqualTo(bundled);

            CrossEncoderModel avx2 = model(config("model-url", bundled.toString()), Set.of("avx2"));
            assertThat(avx2.check()).isTrue();
            assertThat(avx2.loaded().onnxFile()).isEqualTo(ModelLayout.QUINT8_AVX2);

            CrossEncoderModel fp32 = model(config("model-url", bundled.toString(), "variant", "fp32"),
                    Set.of("avx_vnni"));
            assertThat(fp32.check()).isTrue();
            assertThat(fp32.loaded().onnxFile()).isEqualTo(ModelLayout.FP32);
            assertThat(fp32.loaded().variant()).isEqualTo(Variant.FP32);

            assertThat(requests.get()).isZero();
            assertThat(bundled.resolve("tmp")).doesNotExist();
            assertThat(bundled.resolve(ModelLayout.MANIFEST)).hasContent(manifest);   // never rewritten
            assertThat(tmp.resolve("data/cross-encoder")).doesNotExist();            // model-dir untouched
        } finally {
            Files.setPosixFilePermissions(bundled, writable);
            Files.setPosixFilePermissions(bundled.resolve("onnx"), writable);
        }
    }

    @Test
    void missingLocalDirectoryStaysNotReadyWithoutNetwork() {
        Path absent = tmp.resolve("opt/kina/cross-encoder");
        CrossEncoderModel model = model(config("model-url", absent.toString(), "auto-download", "false"),
                Set.of("avx2"));
        assertThat(model.check()).isFalse();
        assertThat(model.isReady()).isFalse();
        assertThat(model.lastError()).contains("does not exist").contains(absent.toString());
        assertThat(model.modelDir()).isEqualTo(absent);
        assertThat(requests.get()).isZero();
        assertThat(absent).doesNotExist();
    }

    @Test
    void bundledInt8OnlyDirectoryCannotServeFp32() throws Exception {
        Path bundled = tmp.resolve("int8-only");
        for (String f : List.of(ModelLayout.VOCAB, ModelLayout.CONFIG, ModelLayout.TOKENIZER_CONFIG,
                ModelLayout.QINT8_AVX512_VNNI, ModelLayout.QUINT8_AVX2)) {
            Files.createDirectories(bundled.resolve(f).getParent());
            Files.write(bundled.resolve(f), files.get(f));
        }
        CrossEncoderModel model = model(config("model-url", bundled.toString(), "variant", "fp32"), Set.of("avx2"));
        assertThat(model.check()).isFalse();
        assertThat(model.lastError()).contains(ModelLayout.FP32 + " missing");
        assertThat(requests.get()).isZero();
    }

    @Test
    void filesFromAnotherSourceAreReplaced() throws Exception {
        Path dir = tmp.resolve("data/cross-encoder");
        assertThat(model(config(), Set.of("avx_vnni")).check()).isTrue();
        String otherUrl = baseUrl.replace("/repo/resolve/main/", "/other/resolve/main/");
        server.createContext("/other/resolve/main/", exchange -> {
            String file = exchange.getRequestURI().getPath().substring("/other/resolve/main/".length());
            byte[] body = file.endsWith(".onnx") ? "other-graph".getBytes(StandardCharsets.UTF_8) : files.get(file);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        CrossEncoderModel other = model(config("model-url", otherUrl), Set.of("avx_vnni"));
        assertThat(other.check()).isTrue();
        assertThat(dir.resolve(ModelLayout.QINT8_AVX512_VNNI)).hasContent("other-graph");
        assertThat(ModelDownloader.readManifest(dir).orElseThrow().source()).isEqualTo(otherUrl);
        assertThat(other.loaded().revision()).isNull();   // no X-Repo-Commit from that source
    }

    @Test
    void autoDownloadDisabledWithoutFilesStaysNotReady() {
        CrossEncoderModel model = model(config("auto-download", "false"), Set.of("avx2"));
        assertThat(model.check()).isFalse();
        assertThat(model.lastError()).contains("auto-download disabled");
        assertThat(requests.get()).isZero();
    }

    @Test
    void unreachableSourceKeepsTheModelUnloaded() {
        server.stop(0);
        CrossEncoderModel model = model(config(), Set.of("avx2"));
        assertThat(model.check()).isFalse();
        assertThat(model.isReady()).isFalse();
        assertThat(model.lastError()).isNotBlank();
    }

    @Test
    void repositoryNameComesFromAHuggingFaceUrl() {
        assertThat(ModelDownloader.repository(KinaProperties.CrossEncoder.DEFAULT_MODEL_URL))
                .isEqualTo("cross-encoder/ms-marco-MiniLM-L6-v2");
        assertThat(ModelDownloader.repository(baseUrl)).isNull();
    }

    static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Smallest vocabulary the tokenizer accepts plus the warm-up words. */
    static final class BertTokenizerTestVocab {
        static final List<String> TOKENS = List.of("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]", "warm", "up");
    }
}
