package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The {@code ranking.model} name shown by {@code list_distributors}. */
class DistributorStatusServiceTest {

    @TempDir
    Path tmp;

    @Test
    void modelNameComesFromTheUrlOrTheManifestOfALocalDirectory() throws Exception {
        assertThat(DistributorStatusService.modelName(
                "https://huggingface.co/cross-encoder/ms-marco-MiniLM-L6-v2/resolve/main/"))
                .isEqualTo("cross-encoder/ms-marco-MiniLM-L6-v2");

        Path bundled = tmp.resolve("cross-encoder");
        Files.createDirectories(bundled);
        assertThat(DistributorStatusService.modelName(bundled.toString())).isEqualTo(bundled.toString());

        Files.writeString(bundled.resolve("model.json"),
                "{\"repo\":\"cross-encoder/ms-marco-MiniLM-L6-v2\",\"variants\":[\"int8\",\"fp32\"]}");
        assertThat(DistributorStatusService.modelName(bundled.toString()))
                .isEqualTo("cross-encoder/ms-marco-MiniLM-L6-v2");
        assertThat(DistributorStatusService.modelName(tmp.resolve("absent").toString()))
                .isEqualTo(tmp.resolve("absent").toString());
        assertThat(DistributorStatusService.modelName(null)).isNull();
    }
}
