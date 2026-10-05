package ro.alacrity.kina.search.ce;

import ro.alacrity.kina.config.KinaProperties.CrossEncoder.Variant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * File layout of a cross-encoder model directory (the Hugging Face layout of
 * {@code cross-encoder/ms-marco-MiniLM-L6-v2}, also written by {@code scripts/ranking/finetune_cross_encoder.sh}):
 * <pre>
 * vocab.txt  config.json  tokenizer_config.json  model.json (KINA's manifest)
 * onnx/model.onnx                      fp32
 * onnx/model_qint8_avx512_vnni.onnx    int8, signed activations (AVX-512 VNNI; identical to the arm64 file)
 * onnx/model_quint8_avx2.onnx          int8, unsigned activations (AVX2)
 * </pre>
 */
public final class ModelLayout {

    public static final String VOCAB = "vocab.txt";
    public static final String CONFIG = "config.json";
    public static final String TOKENIZER_CONFIG = "tokenizer_config.json";
    public static final String MANIFEST = "model.json";
    public static final String FP32 = "onnx/model.onnx";
    public static final String QINT8_AVX512_VNNI = "onnx/model_qint8_avx512_vnni.onnx";
    public static final String QUINT8_AVX2 = "onnx/model_quint8_avx2.onnx";
    /** Files every variant needs besides the ONNX graph. */
    public static final List<String> COMMON_FILES = List.of(VOCAB, CONFIG, TOKENIZER_CONFIG);

    private ModelLayout() {
    }

    /**
     * ONNX files to try for {@code variant}, best first. int8: the file matching the CPU (VNNI, i.e. AVX-512 VNNI or
     * AVX-VNNI, or ARM: signed int8; AVX2 without VNNI: unsigned int8), then the other int8 file, then fp32 as the
     * last resort; x86 without AVX2: fp32 only. On the reference host (AVX-VNNI) both int8 files take the same time,
     * and the signed one is closer to fp32 (NDCG@10 0.878 alone vs 0.867; fp32 0.874).
     */
    public static List<String> onnxCandidates(Variant variant, Set<String> cpuFlags, String arch) {
        List<String> out = new ArrayList<>();
        if (variant == Variant.INT8) {
            String a = arch == null ? "" : arch.toLowerCase(Locale.ROOT);
            boolean arm = a.contains("aarch64") || a.contains("arm");
            if (cpuFlags.contains("avx512_vnni") || cpuFlags.contains("avx_vnni") || arm) {
                out.add(QINT8_AVX512_VNNI);
                out.add(QUINT8_AVX2);
            } else if (cpuFlags.contains("avx2")) {
                out.add(QUINT8_AVX2);
                out.add(QINT8_AVX512_VNNI);
            }
        }
        out.add(FP32);
        return List.copyOf(out);
    }

    /** CPU feature flags from {@code /proc/cpuinfo} (lower-case); empty when unknown (not Linux). */
    public static Set<String> cpuFlags() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"), StandardCharsets.UTF_8)) {
                if (line.startsWith("flags") || line.startsWith("Features")) {
                    int colon = line.indexOf(':');
                    if (colon >= 0) {
                        return Set.copyOf(List.of(line.substring(colon + 1).trim().toLowerCase(Locale.ROOT).split("\\s+")));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // unknown CPU: fall through
        }
        return Set.of();
    }

    /** The variant an ONNX file name belongs to. */
    public static Variant variantOf(String onnxFile) {
        return FP32.equals(onnxFile) ? Variant.FP32 : Variant.INT8;
    }
}
