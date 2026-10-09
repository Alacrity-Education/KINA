package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import ro.alacrity.kina.TestWiring;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.ApiQuotaTracker;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.DistributorStatusResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The {@code ranking.model} name shown by {@code list_distributors}. */
class DistributorStatusServiceTest {

    @TempDir
    Path tmp;

    @Test
    @SuppressWarnings("unchecked")
    void mouserAndTmeCarryTheQuotaAndLcscDoesNot() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-09T10:00:00Z"), ZoneOffset.UTC);
        KinaProperties properties = TestWiring.properties("kina.distributors.mouser.quota.per-minute", "7");
        ApiQuotaTracker tracker = TestWiring.wire(new ApiQuotaTracker(), "properties", properties, "clock", clock);
        for (int i = 0; i < 3; i++) {
            tracker.record(Distributor.MOUSER);
        }
        tracker.throttle(Distributor.TME, Duration.ofSeconds(30));
        RankingService ranking = mock(RankingService.class);
        when(ranking.status()).thenReturn(new RankingService.RankingStatus(false, "int8", null, null, false, 40,
                0.5, null, 1, null));
        DistributorStatusService service = TestWiring.wire(new DistributorStatusService(), "properties", properties,
                "registry", TestWiring.registry(List.of()), "partCache", mock(PartCacheRepository.class),
                "ranking", ranking, "jlcpcb", mock(ObjectProvider.class), "fieldIndex", mock(ObjectProvider.class),
                "journal", mock(ObjectProvider.class), "quota", tracker);

        DistributorStatusResponse response = service.status();

        DistributorStatusResponse.DistributorStatus mouser = response.distributors().get(2);
        DistributorStatusResponse.DistributorStatus tme = response.distributors().get(1);
        assertThat(mouser.distributor()).isEqualTo(Distributor.MOUSER);
        assertThat(mouser.quota().minute()).isEqualTo(new ApiQuotaTracker.Usage(3, 7));
        assertThat(mouser.quota().day()).isEqualTo(new ApiQuotaTracker.Usage(3, 1000));
        assertThat(mouser.quota().throttledUntil()).isNull();
        assertThat(tme.quota().day()).isEqualTo(new ApiQuotaTracker.Usage(0, 2000));
        assertThat(tme.quota().throttledUntil()).isEqualTo(Instant.parse("2026-10-09T10:00:30Z"));
        assertThat(response.distributors().get(0).distributor()).isEqualTo(Distributor.LCSC);
        assertThat(response.distributors().get(0).quota()).isNull();

        JsonNode json = JsonMapper.builder().build().readTree(JsonMapper.builder().build().writeValueAsString(response));
        JsonNode quota = json.path("distributors").get(2).path("quota");
        assertThat(quota.path("minute").path("used").asInt()).isEqualTo(3);
        assertThat(quota.path("minute").path("limit").asInt()).isEqualTo(7);
        assertThat(quota.path("day").path("limit").asInt()).isEqualTo(1000);
        assertThat(quota.has("throttled_until")).isTrue();
        assertThat(quota.path("throttled_until").isNull()).isTrue();
        assertThat(json.path("distributors").get(0).has("quota")).isFalse();
    }

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
