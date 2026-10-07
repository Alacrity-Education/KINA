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
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

/**
 * Ranking rules of the GaN audit (DESIGN.md 3.3 and 3.4): the requested part first, the voltage overshoot penalty
 * (above 2x the request, above 3x for capacitors) and the R_DS(on) preference.
 */
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

    @Test
    void aRequestedPartListedWithoutStockRanksAfterEveryPartInStockAndIsNeverCounted() {
        Part listed = fet("EPC2302", "EPC eGaN FET,100 V, 1.8 milliohm").toBuilder().stock(0).build();
        Part below = fet("EPC2218A", "EPC eGaN FET,80 V, 3.2 milliohm");
        Part other = fet("EPC2619", "EPC eGaN FET,100 V, 3.3 milliohm");
        RankedResults ranked = ranking().rank(parser.parse("EPC2302 GaN FET 100V"),
                Map.of(Distributor.MOUSER, List.of(listed, below, other)), null,
                new RankingService.RankOptions(1, true));
        assertThat(ranked.byDistributor().get(Distributor.MOUSER)).extracting(r -> r.part().manufacturerPartNumber())
                .containsExactly("EPC2619", "EPC2218A", "EPC2302");   // after the below-spec part too
        // a listed part that fails a rating is left out without being counted (it is not part of fetched)
        RankedResults strict = ranking().rank(parser.parse("EPC2302 GaN FET 200V"),
                Map.of(Distributor.MOUSER, List.of(listed, other)), null);
        assertThat(strict.excludedBelowSpecBy(Distributor.MOUSER)).isEqualTo(1);   // EPC2619 only
        assertThat(strict.belowSpecDetailBy(Distributor.MOUSER)).extracting(b -> b.mpn()).containsExactly("EPC2619");
        assertThat(strict.excludedRequestedBy(Distributor.MOUSER)).singleElement()
                .satisfies(e -> assertThat(e.reason()).isEqualTo("voltage 100V below 200V"));
    }

    // ---- voltage overshoot

    private DeterministicRanker.Assessment assess(String query, Part part) {
        return new DeterministicRanker(extractor).assess(parser.parse(query), part);
    }

    private static Part mlcc(String volts) {
        return RankingFixtures.mouser("C-" + volts, "ACME", "Multilayer Ceramic Capacitors MLCC - SMD/SMT 10uF "
                + volts + " X7R 10% 0805", "Multilayer Ceramic Capacitors MLCC - SMD/SMT", null, Map.of());
    }

    @Test
    void aCapacitorIsPenalisedOnlyAbove3xTheRequestedVoltage() {
        assertThat(assess("10uF 25V X7R 0805", mlcc("50V")).overshoot()).isZero();   // 2x
        assertThat(assess("10uF 25V X7R 0805", mlcc("75V")).overshoot()).isZero();   // 3x
        DeterministicRanker.Assessment hundred = assess("10uF 25V X7R 0805", mlcc("100V"));   // 4x
        assertThat(hundred.overshoot()).isCloseTo(0.25 * Math.log(4.0 / 3) / Math.log(2), within(1e-12));
        assertThat(hundred.match()).isEqualTo(1.0);   // still a full match: ratings are minimums
        assertThat(hundred.isBelowSpec()).isFalse();
        assertThat(hundred.score()).isLessThan(assess("10uF 25V X7R 0805", mlcc("75V")).score());
    }

    @Test
    void everyOtherFamilyIsPenalisedAbove2xGrowingPerOctaveUpToACap() {
        assertThat(assess("GaN FET 100V", fet("V200", "EPC eGaN FET,200 V")).overshoot()).isZero();   // 2x
        assertThat(assess("GaN FET 100V", fet("V300", "EPC eGaN FET,300 V")).overshoot())
                .isCloseTo(0.25 * Math.log(1.5) / Math.log(2), within(1e-12));
        assertThat(assess("GaN FET 100V", fet("V600", "CoolGaN 600 V G5")).overshoot())
                .isCloseTo(0.25 * Math.log(3) / Math.log(2), within(1e-12));
        assertThat(assess("GaN FET 100V", fet("V1200", "CoolGaN 1200 V")).overshoot()).isEqualTo(0.5);   // capped
        assertThat(assess("GaN FET 100V", fet("V1200", "CoolGaN 1200 V")).match()).isEqualTo(1.0);
        // only the voltage declares an overshoot: a 10x current costs the small closeness preference only
        assertThat(assess("inductor 10uH 1A", RankingFixtures.mouser("L10", "ACME",
                "Fixed Inductors 10uH 10A", "Fixed Inductors", null, Map.of())).overshoot()).isZero();
    }

    @Test
    void a600VPartRanksBelowThe100To200VPartsEvenWhenTheModelPrefersIt() throws Exception {
        Part v100 = fet("V100", "EPC eGaN FET,100 V");
        Part v200 = fet("V200", "EPC eGaN FET,200 V");
        Part v600 = fet("V600", "CoolGaN 600 V G5");
        // deterministic
        assertThat(order("GaN FET 100V", List.of(v600, v200, v100))).containsExactly("V100", "V200", "V600");
        // blended: the model puts the 600 V part first
        KinaProperties props = RankingFixtures.properties();
        PartRanker model = mock(PartRanker.class);
        org.mockito.Mockito.when(model.rank(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(Map.of(ro.alacrity.kina.domain.PartKey.of(v600), 9.0,
                ro.alacrity.kina.domain.PartKey.of(v200), 5.0, ro.alacrity.kina.domain.PartKey.of(v100), 1.0));
        RankingService blended = new RankingService(props, new DeterministicRanker(extractor), model, () -> null,
                new RankingScoreCache(Duration.ofHours(1)));
        RankedResults ranked = blended.rank(parser.parse("GaN FET 100V"),
                Map.of(Distributor.MOUSER, List.of(v600, v200, v100)), Duration.ofSeconds(5));
        assertThat(ranked.mode()).isEqualTo(ro.alacrity.kina.domain.RankingMode.BLENDED);
        assertThat(ranked.byDistributor().get(Distributor.MOUSER)).extracting(r -> r.part().manufacturerPartNumber())
                .last().isEqualTo("V600");
    }

    // ---- R_DS(on) preference

    @Test
    void aLowerOnResistanceRanksHigherAmongMosfetsThatMeetTheRequest() {
        Part low = fet("LOW", "EPC eGaN FET,100 V, 1.8 milliohm at 5 V");
        Part high = fet("HIGH", "EPC eGaN FET,100 V, 5 milliohm at 5 V");
        Part none = fet("NONE", "EPC eGaN FET,100 V");
        assertThat(order("GaN FET 100V", List.of(none, high, low))).containsExactly("LOW", "HIGH", "NONE");
        DeterministicRanker.Assessment a = assess("GaN FET 100V", low);
        assertThat(a.match()).isEqualTo(assess("GaN FET 100V", none).match()).isEqualTo(1.0);   // score only
        assertThat(a.score()).isGreaterThan(assess("GaN FET 100V", high).score());
        // not for other families: a resistor's resistance is its value, not a preference
        assertThat(ro.alacrity.kina.domain.ConstraintKind.LOW_RDS_ON.wanted(parser.parse("10k 0603 resistor")))
                .isNull();
    }
}
