package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.TestWiring;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.cache.SearchCacheRepository;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.distributor.Deadline;
import ro.alacrity.kina.distributor.DistributorSearchPage;
import ro.alacrity.kina.distributor.PartLookupResult;
import ro.alacrity.kina.distributor.mouser.MouserPart;
import ro.alacrity.kina.distributor.mouser.MouserPartMapper;
import ro.alacrity.kina.distributor.mouser.MouserSearchResponse;
import ro.alacrity.kina.domain.Availability;
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

    /**
     * Mouser serving one recorded keyword search (the in-stock records, like {@code MouserClient}) and the recorded
     * part-number lookups ({@code mouser-partnumber-<number>.json}; any other number is not found).
     */
    static class RecordedMouser implements DistributorClient {
        final MouserSearchResponse keyword;
        final List<String> queries = new ArrayList<>();
        final List<String> lookups = new ArrayList<>();
        private final MouserPartMapper mapper = new MouserPartMapper();

        RecordedMouser(String keywordFixture) {
            this.keyword = fixture(keywordFixture);
        }

        /** As {@code MouserClient.lookup}: by Mouser part number, then by MPN; the listed part when none is in stock. */
        @Override
        public PartLookupResult lookup(String partNumber, Deadline deadline) {
            lookups.add(partNumber);
            String name = "mouser-partnumber-" + partNumber.toUpperCase(java.util.Locale.ROOT) + ".json";
            if (GanAuditFixtureTest.class.getResource("/fixtures/gan-audit/" + name) == null) {
                return PartLookupResult.notFound();
            }
            List<MouserPart> matches = fixture(name).searchResults().parts()
                    .stream().filter(p -> PartLookupResult.samePartNumber(partNumber, p.mouserPartNumber())
                            || PartLookupResult.samePartNumber(partNumber, p.manufacturerPartNumber())).toList();
            for (MouserPart p : matches) {
                Optional<Part> part = mapper.map(p, NOW);
                if (part.isPresent()) {
                    return PartLookupResult.found(part.get());
                }
            }
            return matches.stream().findFirst().map(p -> PartLookupResult.outOfStock(new PartLookupResult.Identity(
                            p.mouserPartNumber(), p.manufacturer(), p.manufacturerPartNumber(), p.description()),
                            matches.stream().map(m -> mapper.mapListed(m, NOW)).flatMap(Optional::stream).findFirst()
                                    .orElse(null)))
                    .orElseGet(PartLookupResult::notFound);
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
        return search(mouser, query, allowBelowSpec, 5);
    }

    final PartCacheRepository partCache = mock(PartCacheRepository.class);

    SearchResponse search(DistributorClient mouser, String query, boolean allowBelowSpec, int maxResults) {
        KinaProperties props = RankingFixtures.properties("kina.ranking.cross-encoder.enabled", "false");
        ParametricExtractor extractor = new ParametricExtractor();
        RankingService ranking = new RankingService(props, new DeterministicRanker(extractor),
                mock(PartRanker.class), () -> null, new RankingScoreCache(Duration.ofHours(1)));
        PartSearchService service = new PartSearchService(props, TestWiring.registry(List.of(mouser)),
                new QueryParser(), extractor, ranking, partCache, mock(SearchCacheRepository.class), clock);
        return service.search(new SearchRequest(query, maxResults, Set.of(Distributor.MOUSER), false, 1,
                ResponseDetail.FULL, allowBelowSpec));
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

    // ---- uP1966E GaN half bridge gate driver: not in the keyword results, found by its part number, first

    @Test
    void up1966e_isLookedUpAndRankedFirst() {
        RecordedMouser mouserClient = new RecordedMouser("mouser-keyword-up1966e.json");
        SearchResponse response = search(mouserClient, "uP1966E GaN half bridge gate driver", false);

        assertThat(response.queryUnderstood()).isTrue();
        assertThat(response.parsed().family()).isEqualTo(Recognizers.GATE_DRIVER);
        assertThat(response.parsed().technology()).isEqualTo(TechnologyVocabulary.GAN);
        assertThat(response.parsed().partNumbers()).containsExactly("uP1966E");
        // the keyword search of Mouser does not bring it: one part-number lookup does
        assertThat(mouserClient.lookups).containsExactly("uP1966E");
        DistributorResult mouser = mouser(response);
        assertThat(mouser.parts().getFirst().partNumber()).isEqualTo("65-UP1966E");
        assertThat(mouser.parts().getFirst().stock()).isEqualTo(3685);
        assertThat(mouser.requestedPartFound()).isTrue();
        assertThat(mouser.hint()).isNull();
        assertThat(mouser.fetched()).isEqualTo(51);   // 50 keyword results and the looked-up part
        // the CoolGaN Drive HB listed under "GaN FETs" without the word "driver" is a MOSFET: excluded by type
        assertThat(mpns(mouser)).doesNotContain("IGI60L1111B1MXUMA1");
        assertThat(mouser.excludedByConstraintsDetail()).containsKey("type");
        org.mockito.Mockito.verify(partCache).upsertAll(org.mockito.ArgumentMatchers.argThat(
                (java.util.Collection<Part> parts) -> parts.stream()
                        .anyMatch(p -> p.distributorPartNumber().equals("65-UP1966E"))));
    }

    @Test
    void up1966e_withoutTheLookupTheHintSaysItIsNotListed() {
        RecordedMouser mouserClient = new RecordedMouser("mouser-keyword-up1966e.json") {
            @Override
            public PartLookupResult lookup(String partNumber, Deadline deadline) {
                return PartLookupResult.notFound();
            }
        };
        DistributorResult mouser = mouser(search(mouserClient, "uP1966E GaN half bridge gate driver", false));
        assertThat(mouser.requestedPartFound()).isFalse();
        assertThat(mouser.hint()).isEqualTo(
                "uP1966E is not listed in stock at MOUSER; the parts below are keyword matches.");
    }

    // ---- EPC23101 eGaN ePower stage: listed without stock (an engineering sample), shown last with stock 0

    @Test
    void epc23101_isNotInStockAndTheHintSaysSo() {
        DistributorResult mouser = mouser(search(new RecordedMouser("mouser-keyword-epc23101.json"),
                "EPC23101 eGaN ePower stage", false));

        assertThat(mouser.parts()).extracting(PartResponse::partNumber).containsExactly("65-EPC2152",
                "65-EPC23101-ES");
        PartResponse listed = mouser.parts().getLast();
        assertThat(listed.stock()).isZero();
        assertThat(listed.availability().status()).isEqualTo(Availability.OUT_OF_STOCK);
        assertThat(listed.availability().note()).isEqualTo(
                "Out of stock at MOUSER; shown because the part number was requested explicitly.");
        assertThat(mouser.fetched()).isEqualTo(1);   // the listed part is not part of fetched
        assertThat(mouser.exactMatches()).isLessThanOrEqualTo(1);
        assertThat(mouser.requestedPartFound()).isFalse();
        assertThat(mouser.hint()).isEqualTo("EPC23101 is not in stock at MOUSER: 65-EPC23101-ES is listed without "
                + "stock and shown last, with stock 0; the parts below are keyword matches.");
        // cached with in_stock = false, never as an in-stock part
        org.mockito.Mockito.verify(partCache).upsertListed(org.mockito.ArgumentMatchers.argThat(
                (java.util.Collection<Part> parts) -> parts.size() == 1
                        && parts.iterator().next().distributorPartNumber().equals("65-EPC23101-ES")));
        org.mockito.Mockito.verify(partCache, org.mockito.Mockito.never()).upsertAll(
                org.mockito.ArgumentMatchers.argThat((java.util.Collection<Part> parts) -> parts.stream()
                        .anyMatch(p -> p.stock() <= 0)));
    }

    // ---- EPC2302 GaN FET 100V: the 600 V half-bridge below the 100 V parts

    @Test
    void epc2302_the600VPartRanksBelowThe100VParts() {
        DistributorResult mouser = mouser(search(new RecordedMouser("mouser-keyword-epc2302.json"),
                "EPC2302 GaN FET 100V", false, 50));
        List<String> order = mpns(mouser);
        // the three 100 V parts first, then the 600 V half-bridge (match 1.0: ratings are minimums), then the parts
        // that state no voltage (unverified, a lower tier); Mouser's "2.6m" / "185 m" stay unread
        assertThat(order.subList(0, 3)).containsExactlyInAnyOrder("IGKA23S101SXTSA1", "RTP100E005G1FL-TR",
                "RTP100E2P6G1FL-TR");
        assertThat(order.get(3)).isEqualTo("IGI60L1111B1MXUMA1");
        PartResponse v600 = mouser.parts().get(3);
        assertThat(v600.match()).isEqualTo(1.0);
        assertThat(v600.attributes()).containsEntry("Voltage", "600V");
        assertThat(mouser.parts().subList(0, 3)).allSatisfy(p -> {
            assertThat(p.attributes()).containsEntry("Voltage", "100V").doesNotContainKey("Resistance");
            assertThat(p.score()).isGreaterThan(v600.score());
        });
        // then the parts that state no voltage, and last the requested EPC2302, listed without stock
        assertThat(mouser.parts().subList(4, mouser.parts().size() - 1)).allSatisfy(p ->
                assertThat(p.unverified()).contains("voltage"));
        assertThat(mouser.parts().getLast().partNumber()).isEqualTo("65-EPC2302");
        assertThat(mouser.parts().getLast().stock()).isZero();
        // the RF HEMTs rated 48 V are below spec, named with their rating
        assertThat(mouser.excludedBelowSpec()).isPositive();
        assertThat(mouser.excludedBelowSpecDetail()).extracting(BelowSpecPart::rating).containsOnly("voltage");
        assertThat(mouser.requestedPartFound()).isFalse();   // listed without stock only
    }

    @Test
    void epc2302_theListedPartTakesTheLastPlaceBelowEveryPartInStock() {
        DistributorResult mouser = mouser(search(new RecordedMouser("mouser-keyword-epc2302.json"),
                "EPC2302 GaN FET 100V", false));

        assertThat(mouser.parts()).hasSize(5);
        assertThat(mpns(mouser).subList(0, 4)).doesNotContain("EPC2302");
        PartResponse listed = mouser.parts().getLast();
        assertThat(listed.partNumber()).isEqualTo("65-EPC2302");
        assertThat(listed.rank()).isEqualTo(5);
        assertThat(listed.stock()).isZero();
        assertThat(listed.availability().status()).isEqualTo(Availability.OUT_OF_STOCK);
        assertThat(listed.prices()).isNotEmpty();   // prices as listed
        assertThat(listed.attributes()).containsEntry("Voltage", "100V").containsEntry("Resistance", "1.8mohm");
        assertThat(mouser.requestedPartFound()).isFalse();
        assertThat(mouser.hint()).startsWith("EPC2302 is not in stock at MOUSER: 65-EPC2302 is listed without stock");
    }

    @Test
    void aSearchWithoutThePartNumberNeverServesTheListedPart() {
        RecordedMouser mouserClient = new RecordedMouser("mouser-keyword-epc2302.json");
        DistributorResult mouser = mouser(search(mouserClient, "GaN FET 100V", false, 50));

        assertThat(mouserClient.lookups).isEmpty();
        assertThat(mouser.parts()).allSatisfy(p -> assertThat(p.stock()).isPositive());
        assertThat(mouser.requestedPartFound()).isNull();
    }
}
