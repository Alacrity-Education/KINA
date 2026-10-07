package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.cache.SearchCacheRepository;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.distributor.DistributorRegistry;
import ro.alacrity.kina.distributor.DistributorSearchPage;
import ro.alacrity.kina.distributor.mouser.MouserPartMapper;
import ro.alacrity.kina.distributor.mouser.MouserSearchResponse;
import ro.alacrity.kina.domain.BelowSpecPart;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.DistributorResult;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PartResponse;
import ro.alacrity.kina.domain.ResponseDetail;
import ro.alacrity.kina.domain.SearchRequest;
import ro.alacrity.kina.domain.SearchResponse;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The five Mouser probes of the GaN power stage / gate driver audit (2026-10-07), on the Mouser responses recorded live
 * that day ({@code fixtures/gan-audit}: the keyword search of the phrase KINA sends, and the part-number lookups),
 * mapped by the real {@link MouserPartMapper} and ranked deterministically (cross-encoder off).
 */
class GanAuditFixtureTest {

    static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    /** A recorded Mouser response. */
    static MouserSearchResponse fixture(String name) {
        try (InputStream in = GanAuditFixtureTest.class.getResourceAsStream("/fixtures/gan-audit/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("missing fixture " + name);
            }
            return JSON.readValue(in.readAllBytes(), MouserSearchResponse.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Mouser serving one recorded keyword search (the in-stock records, like {@code MouserClient}). */
    static class RecordedMouser implements DistributorClient {
        final MouserSearchResponse keyword;
        final List<String> queries = new ArrayList<>();
        private final MouserPartMapper mapper = new MouserPartMapper();

        RecordedMouser(String keywordFixture) {
            this.keyword = fixture(keywordFixture);
        }

        @Override
        public Distributor distributor() {
            return Distributor.MOUSER;
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public int maxPageSize() {
            return 50;
        }

        @Override
        public DistributorSearchPage search(String query, int offset, int limit) {
            queries.add(query);
            List<Part> parts = new ArrayList<>();
            keyword.searchResults().parts().forEach(p -> mapper.map(p, NOW).ifPresent(parts::add));
            int total = keyword.searchResults().numberOfResult();
            return new DistributorSearchPage(parts, total, false,
                    keyword.searchResults().parts().size() - parts.size());
        }

        @Override
        public Optional<Part> getPart(String distributorPartNumber) {
            return Optional.empty();
        }
    }

    SearchResponse search(DistributorClient mouser, String query, boolean allowBelowSpec) {
        KinaProperties props = RankingFixtures.properties("kina.ranking.cross-encoder.enabled", "false");
        ParametricExtractor extractor = new ParametricExtractor();
        RankingService ranking = new RankingService(props, new DeterministicRanker(extractor),
                mock(PartRanker.class), () -> null, new RankingScoreCache(Duration.ofHours(1)));
        PartSearchService service = new PartSearchService(props, new DistributorRegistry(List.of(mouser)),
                new QueryParser(), extractor, ranking, mock(PartCacheRepository.class),
                mock(SearchCacheRepository.class), clock);
        return service.search(new SearchRequest(query, 5, Set.of(Distributor.MOUSER), false, 1, ResponseDetail.FULL,
                allowBelowSpec));
    }

    static DistributorResult mouser(SearchResponse response) {
        return PartSearchServiceTest.result(response, Distributor.MOUSER);
    }

    static List<String> mpns(DistributorResult result) {
        return result.parts().stream().map(PartResponse::mpn).toList();
    }

    // ---- EPC2218 GaN FET 100V: the only hit is below spec, and the response names it

    @Test
    void epc2218_theExcludedPartIsNamedWithItsRating() {
        SearchResponse response = search(new RecordedMouser("mouser-keyword-epc2218.json"), "EPC2218 GaN FET 100V",
                false);

        assertThat(response.parsed().partNumbers()).containsExactly("EPC2218");
        DistributorResult mouser = mouser(response);
        assertThat(mouser.fetched()).isEqualTo(1);
        assertThat(mouser.excludedBelowSpec()).isEqualTo(1);
        assertThat(mouser.excludedBelowSpecDetail()).containsExactly(
                new BelowSpecPart("65-EPC2218A", "EPC2218A", "voltage", "80V", "100V"));
        assertThat(mouser.requestedPartFound()).isFalse();
        assertThat(mouser.hint()).startsWith("EPC2218 is listed at MOUSER (EPC2218A) but was left out: voltage 80V "
                + "below 100V (pass \"allow_below_spec\": true to see it).")
                .contains("1 part was left out for a rating below the request");
    }

    @Test
    void epc2218_theNewFieldsAreSnakeCaseInJson() {
        SearchResponse response = search(new RecordedMouser("mouser-keyword-epc2218.json"), "EPC2218 GaN FET 100V",
                false);
        String json = JSON.writeValueAsString(response);
        assertThat(json).contains("\"part_numbers\":[\"EPC2218\"]", "\"requested_part_found\":false",
                "\"excluded_below_spec_detail\":[{\"part_number\":\"65-EPC2218A\",\"mpn\":\"EPC2218A\","
                        + "\"rating\":\"voltage\",\"part_value\":\"80V\",\"requested\":\"100V\"}]");
        assertThat(JSON.writeValueAsString(search(new RecordedMouser("mouser-keyword-epc2218.json"),
                "GaN FET 100V", false))).contains("\"requested_part_found\":null").doesNotContain("part_numbers");
    }

    @Test
    void epc2218_withAllowBelowSpecTheRequestedPartIsReturnedFirst() {
        DistributorResult mouser = mouser(search(new RecordedMouser("mouser-keyword-epc2218.json"),
                "EPC2218 GaN FET 100V", true));

        assertThat(mpns(mouser)).containsExactly("EPC2218A");
        assertThat(mouser.parts().getFirst().belowSpec()).isTrue();
        assertThat(mouser.requestedPartFound()).isTrue();
        assertThat(mouser.excludedBelowSpecDetail()).isEmpty();
    }

    // ---- EPC2619 GaN FET 80V: the requested part, its R_DS(on) read

    @Test
    void epc2619_isFoundWithItsOnResistance() {
        DistributorResult mouser = mouser(search(new RecordedMouser("mouser-keyword-epc2619.json"),
                "EPC2619 GaN FET 80V", false));

        assertThat(mpns(mouser)).containsExactly("EPC2619");
        assertThat(mouser.requestedPartFound()).isTrue();
        assertThat(mouser.hint()).isNull();
        PartResponse part = mouser.parts().getFirst();
        assertThat(part.attributes()).containsEntry("Resistance", "3.3mohm").containsEntry("Voltage", "100V")
                .containsEntry("Technology", "GaN");
        assertThat(part.match()).isEqualTo(1.0);
    }

    // ---- uP1966E GaN half bridge gate driver: parsed as a GaN gate driver request

    @Test
    void up1966e_isAGanGateDriverRequestAndMosfetsAreExcluded() {
        SearchResponse response = search(new RecordedMouser("mouser-keyword-up1966e.json"),
                "uP1966E GaN half bridge gate driver", false);

        assertThat(response.queryUnderstood()).isTrue();
        assertThat(response.parsed().family()).isEqualTo(Recognizers.GATE_DRIVER);
        assertThat(response.parsed().technology()).isEqualTo(TechnologyVocabulary.GAN);
        assertThat(response.parsed().partNumbers()).containsExactly("uP1966E");
        DistributorResult mouser = mouser(response);
        // the CoolGaN Drive HB listed under "GaN FETs" without the word "driver" is a MOSFET: excluded by type
        assertThat(mpns(mouser)).doesNotContain("IGI60L1111B1MXUMA1");
        assertThat(mouser.excludedByConstraintsDetail()).containsKey("type");
        assertThat(mouser.requestedPartFound()).isFalse();
        assertThat(mouser.hint()).isEqualTo(
                "uP1966E is not listed in stock at MOUSER; the parts below are keyword matches.");
    }

    // ---- EPC23101 eGaN ePower stage: only another power stage, and the response says so

    @Test
    void epc23101_isNotListedAndTheHintSaysSo() {
        DistributorResult mouser = mouser(search(new RecordedMouser("mouser-keyword-epc23101.json"),
                "EPC23101 eGaN ePower stage", false));

        assertThat(mpns(mouser)).containsExactly("EPC2152");
        assertThat(mouser.requestedPartFound()).isFalse();
        assertThat(mouser.hint()).isEqualTo(
                "EPC23101 is not listed in stock at MOUSER; the parts below are keyword matches.");
    }
}
