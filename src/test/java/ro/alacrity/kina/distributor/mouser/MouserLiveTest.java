package ro.alacrity.kina.distributor.mouser;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.DistributorSearchPage;
import ro.alacrity.kina.domain.Part;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One real call against the Mouser API (costs one call of the 1 000/day quota). Runs only when both
 * {@code KINA_MOUSER_LIVE_TEST=true} and {@code MOUSER_API_KEY} are set, so a normal {@code ./mvnw verify} never
 * touches the live API even when the key is exported in the shell.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "KINA_MOUSER_LIVE_TEST", matches = "true")
@EnabledIfEnvironmentVariable(named = "MOUSER_API_KEY", matches = ".+")
class MouserLiveTest {

    @Test
    void searchesTheLiveApi() {
        String key = System.getenv("MOUSER_API_KEY").strip();
        String base = "https://api.mouser.com/api/v1";
        MouserClient client = new MouserClient(new KinaProperties.Mouser(key, base, 50, 1),
                MouserApi.create(RestClient.builder(), base, key), Clock.systemUTC());

        // offset 1 -> startingRecord 2: expect the 2nd and 3rd results of "10uF X7R 0805"
        DistributorSearchPage page = client.search("10uF X7R 0805", 1, 2);

        System.out.println("Mouser live: total=" + page.totalResults() + " hasMore=" + page.hasMore() + " parts="
                + page.parts().stream().map(p -> p.distributorPartNumber() + "/" + p.stock() + "/" + p.prices().size()
                + " prices").toList());
        assertThat(page.totalResults()).isPositive();
        assertThat(page.hasMore()).isTrue();
        assertThat(page.parts()).isNotEmpty().hasSizeLessThanOrEqualTo(2)
                .allSatisfy(p -> assertThat(p.stock()).isPositive())
                .extracting(Part::prices).allSatisfy(prices -> assertThat(prices).isNotEmpty());
    }
}
