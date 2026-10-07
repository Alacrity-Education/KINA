package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.search.RankingService.RankedPart;
import ro.alacrity.kina.search.RankingService.RankedResults;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Ranking rules of the GaN audit (DESIGN.md 3.3 and 3.4): the requested part first. */
class GanRankingTest {

    private final QueryParser parser = new QueryParser();
    private final ParametricExtractor extractor = new ParametricExtractor();

    private RankingService ranking() {
        KinaProperties props = RankingFixtures.properties("kina.ranking.cross-encoder.enabled", "false");
        return new RankingService(props, new DeterministicRanker(extractor), mock(PartRanker.class), () -> null,
                new RankingScoreCache(Duration.ofHours(1)));
    }

    static Part fet(String mpn, String description) {
        return RankingFixtures.mouser(mpn, "ACME", "GaN FETs " + description, "GaN FETs", null, Map.of());
    }

    private List<String> order(String query, List<Part> parts) {
        RankedResults ranked = ranking().rank(parser.parse(query), Map.of(Distributor.MOUSER, parts), null);
        return ranked.byDistributor().get(Distributor.MOUSER).stream().map(r -> r.part().manufacturerPartNumber())
                .toList();
    }

    // ---- requested part first

    @Test
    void theRequestedPartRanksFirstWhateverItsScore() {
        Part exact = fet("EPC2619", "EPC eGaN FET,100 V, 3.3 milliohm typ at 5 V");
        // states nothing: unverified voltage, a lower tier and a lower score than every other part
        Part requested = fet("EPC2302", "EPC eGaN FET");
        Part other = fet("RTP100E005G1FL-TR", "100V 5m LV GaN FET in 3x3 RQFN");
        RankedResults ranked = ranking().rank(parser.parse("EPC2302 GaN FET 100V"),
                Map.of(Distributor.MOUSER, List.of(exact, other, requested)), null);
        List<RankedPart> list = ranked.byDistributor().get(Distributor.MOUSER);
        assertThat(list).extracting(r -> r.part().manufacturerPartNumber())
                .containsExactly("EPC2302", "EPC2619", "RTP100E005G1FL-TR");
        assertThat(list.getFirst().score()).isEqualTo(1.0);
        assertThat(list.getFirst().unverified()).contains("voltage");   // still graded honestly
        // without the part number the requested part is last (unverified voltage: a lower tier)
        assertThat(order("GaN FET 100V", List.of(exact, other, requested)).getLast()).isEqualTo("EPC2302");
    }

    @Test
    void theRequestedPartIsStillExcludedByAHardConstraint() {
        Part driver = RankingFixtures.mouser("EPC2302", "ACME", "Gate Drivers 100 V half-bridge", "Gate Drivers",
                null, Map.of());
        RankedResults ranked = ranking().rank(parser.parse("EPC2302 GaN FET 100V"),
                Map.of(Distributor.MOUSER, List.of(driver, fet("EPC2619", "EPC eGaN FET,100 V"))), null);
        assertThat(ranked.byDistributor().get(Distributor.MOUSER)).extracting(r -> r.part().manufacturerPartNumber())
                .containsExactly("EPC2619");
        assertThat(ranked.excludedRequestedBy(Distributor.MOUSER)).singleElement()
                .satisfies(e -> {
                    assertThat(e.partNumber()).isEqualTo("EPC2302");
                    assertThat(e.reason()).isEqualTo("type");
                });
    }

    @Test
    void belowSpecDetailNamesTheClosestPartsFirst() {
        RankedResults ranked = ranking().rank(parser.parse("GaN FET 100V"), Map.of(Distributor.MOUSER, List.of(
                fet("A40", "EPC eGaN FET,40 V"), fet("A80", "EPC eGaN FET,80 V"), fet("A100", "EPC eGaN FET,100 V"),
                fet("A65", "EPC eGaN FET,65 V"), fet("A15", "EPC eGaN FET,15 V"), fet("A30", "EPC eGaN FET,30 V"),
                fet("A60", "EPC eGaN FET,60 V"))), null);
        assertThat(ranked.excludedBelowSpecBy(Distributor.MOUSER)).isEqualTo(6);
        assertThat(ranked.belowSpecDetailBy(Distributor.MOUSER)).hasSize(RankingService.MAX_BELOW_SPEC_DETAIL)
                .extracting(b -> b.mpn() + "=" + b.partValue() + "<" + b.requested())
                .containsExactly("A80=80V<100V", "A65=65V<100V", "A60=60V<100V", "A40=40V<100V", "A30=30V<100V");
    }
}
