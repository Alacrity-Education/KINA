package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ro.alacrity.kina.TestWiring;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.cache.SearchCacheRepository;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.Absence;
import ro.alacrity.kina.domain.ConstraintKind;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.DistributorResult;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PartResponse;
import ro.alacrity.kina.domain.ResponseDetail;
import ro.alacrity.kina.domain.SearchRequest;
import ro.alacrity.kina.domain.SearchResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static ro.alacrity.kina.search.RankingFixtures.attrs;

/**
 * Fan matching through the declarations only (DESIGN.md 3.4 "Fans"): the fan type, the frame size and the supply
 * voltage are hard; current and noise are maximums, airflow and static pressure minimums; the speed is a relaxable
 * mismatch outside 15 %; the bearing a relaxable preference; a missing PWM or tacho is a mismatch (absence
 * PENALIZE), the other features score only. Then the distributor phrasing
 * and the recorded searches (fixtures/fans) end to end with the deterministic ranking.
 */
class FanRankingTest {

    private final QueryParser parser = new QueryParser();
    private final ParametricExtractor extractor = new ParametricExtractor();
    private final DeterministicRanker ranker = TestWiring.deterministicRanker(extractor);
    private final ConstraintPolicy policy = ConstraintPolicy.DEFAULTS;

    private static Part tmeFan(String symbol, String description, String... attributes) {
        return RankingFixtures.tme(symbol, "SUNON", description, "DC12V Fans", null, attrs(attributes));
    }

    private ConstraintPolicy.Result check(String query, Part part) {
        return policy.check(parser.parse(query), extractor.features(part));
    }

    private DeterministicRanker.Assessment assess(String query, Part part) {
        return ranker.assess(parser.parse(query), part);
    }

    // ---------------------------------------------------------------- the policy row

    @Test
    void theFanRowIsDeclared() {
        ParsedQuery q = parser.parse("40x40x10 fan 12V");
        assertThat(ConstraintPolicy.policyFamily(q)).isEqualTo("fan");
        assertThat(policy.hardFor(q)).containsExactlyInAnyOrder("type", "voltage", "mounting", "fan type",
                "frame size");
        assertThat(policy.isRelaxable(q, "speed")).isTrue();
        assertThat(policy.isRelaxable(q, "bearing")).isTrue();
        assertThat(policy.isRelaxable(q, "frame size")).isFalse();
        // speed and bearing are relaxable for fans only
        ParsedQuery capacitor = parser.parse("10uF 25V 0805");
        assertThat(policy.isRelaxable(capacitor, "speed")).isFalse();
        assertThat(policy.isRelaxable(capacitor, "bearing")).isFalse();
        assertThat(ConstraintKind.isExactRating(ParsedQuery.VOLTAGE, "fan")).isTrue();
        assertThat(ConstraintKind.isExactRating(ParsedQuery.CURRENT, "fan")).isFalse();
        assertThat(ConstraintKind.isMinimumRating(ParsedQuery.CURRENT, "fan")).isFalse();
        assertThat(ConstraintKind.isMinimumRating(ParsedQuery.CURRENT, "capacitor")).isTrue();
    }

    // ---------------------------------------------------------------- hard: type, frame size, voltage

    @Test
    void anAxialRequestNeverReturnsABlowerAndTheReverse() {
        Part blower = tmeFan("PF75302B1", "Fan: DC; 75x75x30mm; blower; 24VDC; 26.34m3/h; 42dBA; ball; 3400rpm",
                "Kind of fan", "blower", "Supply voltage", "24V DC");
        Part axial = tmeFan("MF40101V1", "Fan: DC; 40x40x10mm; axial; 24VDC; 13.52m3/h; 27.3dBA; Vapo; 7000rpm",
                "Kind of fan", "axial", "Supply voltage", "24V DC");
        assertThat(check("axial fan 24V", blower).conflicts()).containsExactly("fan type");
        assertThat(check("radial blower 24V", axial).conflicts()).containsExactly("fan type");
        assertThat(check("centrifugal fan 24V", blower).conflict()).isFalse();
        assertThat(check("fan 24V", blower).conflict()).as("a request without a type takes both").isFalse();
        assertThat(assess("axial fan 24V", blower).mismatches()).contains("fan type: DC radial instead of axial");
        // AC or DC when both state it
        assertThat(check("AC fan 24V", axial).conflicts()).containsExactly("fan type");
    }

    @Test
    void aPartWithoutTypeOrFrameStaysUnverified() {
        Part bare = RankingFixtures.lcsc("C1", "SHENG FENG", "SF12025SM24", null,
                "Industrial control electrical / Cooling fan", "-", attrs());
        ParsedQuery q = parser.parse("radial blower 24V 120mm");
        assertThat(check("radial blower 24V 120mm", bare).conflict()).as("JLCPCB says only Cooling fan: axial")
                .isTrue();
        // a request for a DC fan against a part that says fan and no supply: the type check is unverified
        ParsedQuery dc = parser.parse("DC fan 24V 120mm");
        assertThat(ranker.assess(dc, bare).unverified()).contains("fan type", "frame size", "voltage");
        assertThat(policy.check(dc, extractor.features(bare)).conflict()).isFalse();
        assertThat(ranker.assess(q, bare).unverified()).doesNotContain("fan type");
    }

    @ParameterizedTest(name = "{0} vs {1}")
    @CsvSource(delimiter = '|', value = {
            "40x40x10 fan 12V | 40x40x10mm | true",
            "40x40x10 fan 12V | 40x40x10.6mm | true",
            "40x40x10 fan 12V | 40x40x20mm | false",
            "40x40x10 fan 12V | 50x50x10mm | false",
            "fan 40mm 12V     | 40x40x20mm | true",
            "fan 120mm 12V    | 120x120x38mm | true",
            "fan 120mm 12V    | 92x92x25mm | false"})
    void theFrameSizeIsHard(String query, String frame, boolean kept) {
        Part part = tmeFan("F1", "Fan: DC; " + frame + "; axial; 12VDC", "Fan dimensions", frame, "Supply voltage",
                "12V DC");
        assertThat(check(query, part).conflict()).isEqualTo(!kept);
        if (!kept) {
            assertThat(check(query, part).conflicts()).containsExactly("frame size");
        }
    }

    @ParameterizedTest(name = "{0} for {1}")
    @CsvSource(delimiter = '|', value = {"12V | 12V DC | true", "12V | 24V DC | false", "12V | 5V DC | false",
            "24V | 24V DC | true", "12V | 12.2V DC | true", "12V | 13.8V DC | false"})
    void theSupplyVoltageIsExact(String wanted, String supply, boolean kept) {
        Part part = tmeFan("F1", "Fan: DC; 40x40x10mm; axial; " + supply.replace(" DC", "DC"), "Supply voltage",
                supply, "Operating voltage", "4.5...13.8V DC");
        ConstraintPolicy.Result r = check("fan 40x40x10 " + wanted, part);
        assertThat(r.conflict()).isEqualTo(!kept);
        if (!kept) {
            assertThat(r.conflicts()).containsExactly("voltage");
        }
    }

    // ---------------------------------------------------------------- ratings: maximums and minimums

    @Test
    void currentAndNoiseAreMaximumsAirflowAndPressureMinimums() {
        Part fan = tmeFan("F1", "Fan: DC; 40x40x10mm; axial; 12VDC", "Supply voltage", "12V DC", "Current rating",
                "68mA", "Noise level", "23dBA", "Fan efficiency", "11.99m<sup>3</sup>/h", "Static pressure",
                "3.05mm H<sub>2</sub>O");
        // 11.99 m³/h is 7.06 CFM, 3.05 mmH2O is 29.9 Pa
        assertThat(assess("fan 12V 0.1A 25dBA 7 CFM 25 Pa", fan).belowSpec()).isEmpty();
        assertThat(assess("fan 12V 50mA", fan).belowSpec()).containsExactly("current");
        assertThat(assess("fan 12V 20dBA", fan).belowSpec()).containsExactly("noise");
        assertThat(assess("fan 12V 10 CFM", fan).belowSpec()).containsExactly("airflow");
        assertThat(assess("fan 12V 4 mmH2O", fan).belowSpec()).containsExactly("static pressure");
        assertThat(assess("fan 12V 10 CFM", fan).mismatches())
                .contains("airflow: 12 m³/h (7.06 CFM) below 17 m³/h (10 CFM)");
        assertThat(assess("fan 12V 50mA", fan).mismatches()).contains("current: 68mA above 50mA");
        // a fan drawing less, a quieter one, more airflow: all meet the request
        assertThat(assess("fan 12V 0.2A 30dBA 5 CFM", fan).match()).isEqualTo(1.0);
    }

    // ---------------------------------------------------------------- relaxable: speed, bearing; features

    @ParameterizedTest(name = "{0} rpm")
    @CsvSource({"3000, true", "3400, true", "2600, true", "3500, false", "6000, false"})
    void theSpeedMatchesWithin15PercentAndNeverExcludes(int rpm, boolean within) {
        Part fan = tmeFan("F1", "Fan: DC; 40x40x10mm; axial; 5VDC", "Supply voltage", "5V DC",
                "Rotational rate/speed", rpm + "rpm");
        DeterministicRanker.Assessment a = assess("fan 5V 3000rpm", fan);
        assertThat(check("fan 5V 3000rpm", fan).conflict()).isFalse();
        assertThat(a.belowSpec()).isEmpty();
        assertThat(a.mismatches().stream().anyMatch(m -> m.startsWith("speed:"))).isEqualTo(!within);
        if (!within) {
            assertThat(a.mismatches()).contains("speed: " + rpm + " rpm instead of 3000 rpm");
        }
    }

    @Test
    void theBearingIsAPreference() {
        Part ball = tmeFan("B1", "Fan: DC; 40x40x10mm; axial; 12VDC; ball", "Supply voltage", "12V DC",
                "Kind of Bearing", "ball");
        Part sleeve = tmeFan("S1", "Fan: DC; 40x40x10mm; axial; 12VDC; slide", "Supply voltage", "12V DC",
                "Kind of Bearing", "slide");
        String query = "40x40x10 fan 12V ball bearing";
        assertThat(check(query, sleeve).conflict()).isFalse();
        assertThat(assess(query, sleeve).mismatches()).contains("bearing: sleeve instead of ball");
        assertThat(assess(query, ball).score()).isGreaterThan(assess(query, sleeve).score());
    }

    @Test
    void theOtherFeaturesScoreOnly() {
        Part restart = tmeFan("R1", "Fan: DC; 120x120x25mm; axial; 12VDC; ball", "Supply voltage", "12V DC",
                "Additional functions", "autorestart");
        Part plain = tmeFan("P1", "Fan: DC; 120x120x25mm; axial; 12VDC; ball", "Supply voltage", "12V DC");
        String query = "120mm axial fan 12V auto restart";
        assertThat(assess(query, restart).score()).isGreaterThan(assess(query, plain).score());
        assertThat(assess(query, plain).match()).as("absence OK: never graded").isEqualTo(1.0);
        assertThat(assess(query, plain).mismatches()).isEmpty();
    }

    // ---------------------------------------------------------------- PWM and tacho: a missing one is penalised

    @Test
    void pwmAndTachoArePenalisedWhenMissing() {
        assertThat(ConstraintKind.FAN_PWM.match().absence()).isEqualTo(Absence.PENALIZE);
        assertThat(ConstraintKind.FAN_TACHO.match().absence()).isEqualTo(Absence.PENALIZE);
        assertThat(ConstraintKind.FAN_FEATURES.match().absence()).isEqualTo(Absence.OK);
    }

    @Test
    void aPwmRequestAgainstAPwmFanMatches() {
        Part pwm = RankingFixtures.mouser("PFR1212", "Delta", "DC Fans Fan, 120x38mm, 12VDC, Ball, 4x Lead Wires, "
                + "Tach/PWM", "DC Fans", null, attrs());
        DeterministicRanker.Assessment a = assess("120mm axial fan 12V PWM", pwm);
        assertThat(a.match()).isEqualTo(1.0);
        assertThat(a.mismatches()).isEmpty();
    }

    @Test
    void aPwmRequestAgainstAThreeWireFanIsAMismatchNeverAnExclusion() {
        Part pwm = tmeFan("P4", "Fan: DC; 120x120x25mm; axial; 12VDC; ball; PWM", "Supply voltage", "12V DC",
                "Leads", "leads x4");
        Part threeWire = tmeFan("T3", "Fan: DC; 120x120x25mm; axial; 12VDC; ball", "Supply voltage", "12V DC",
                "Leads", "leads x3");
        String query = "12025 fan 12V PWM";
        DeterministicRanker.Assessment a = assess(query, threeWire);
        assertThat(a.mismatches()).containsExactly("feature: PWM missing");
        assertThat(a.match()).isLessThan(1.0);
        assertThat(check(query, threeWire).conflict()).as("never excluded").isFalse();
        assertThat(assess(query, pwm).match()).isEqualTo(1.0);
        assertThat(assess(query, pwm).score() - a.score()).as("the weight earned and lost")
                .isCloseTo(2 * ConstraintKind.FAN_PWM.weight(), within(1e-9));
    }

    @Test
    void aPwmRequestAgainstAFanStatingNothingIsTheSameMismatch() {
        Part bare = tmeFan("B0", "Fan: DC; 120x120x25mm; axial; 12VDC; ball", "Supply voltage", "12V DC");
        Part threeWire = tmeFan("T3", "Fan: DC; 120x120x25mm; axial; 12VDC; ball", "Supply voltage", "12V DC",
                "Leads", "leads x3");
        String query = "12025 fan 12V PWM";
        DeterministicRanker.Assessment a = assess(query, bare);
        assertThat(a.mismatches()).containsExactly("feature: PWM missing");
        assertThat(a.match()).isEqualTo(assess(query, threeWire).match());
        assertThat(check(query, bare).conflict()).isFalse();
    }

    @Test
    void aWireCountRequestAsksForItsSignals() {
        Part fourWire = tmeFan("P4", "Fan: DC; 40x40x10mm; axial; 12VDC", "Supply voltage", "12V DC", "Leads",
                "leads x4");
        Part threeWire = tmeFan("T3", "Fan: DC; 40x40x10mm; axial; 12VDC", "Supply voltage", "12V DC", "Leads",
                "leads x3");
        Part twoWire = tmeFan("T2", "Fan: DC; 40x40x10mm; axial; 12VDC", "Supply voltage", "12V DC", "Leads",
                "leads x2");
        assertThat(assess("4-wire fan 12V", fourWire).mismatches()).isEmpty();
        assertThat(assess("4-wire fan 12V", threeWire).mismatches()).containsExactly("feature: PWM missing");
        assertThat(assess("4-wire fan 12V", twoWire).mismatches())
                .containsExactly("feature: PWM missing", "feature: tacho missing");
        assertThat(assess("3-wire fan 12V", threeWire).mismatches()).isEmpty();
        assertThat(assess("3-wire fan 12V", fourWire).mismatches()).as("a 4-wire fan has a tacho too").isEmpty();
        assertThat(assess("3-wire fan 12V", twoWire).mismatches()).containsExactly("feature: tacho missing");
        // a stated feature wins over the wire count (distributor data, no safeguards)
        Part threeWirePwm = tmeFan("X3", "Fan: DC; 40x40x10mm; axial; 12VDC; PWM", "Supply voltage", "12V DC",
                "Leads", "leads x3");
        assertThat(assess("40x40x10 fan 12V PWM", threeWirePwm).mismatches()).isEmpty();
    }

    @Test
    void aTachoRequestNamesItsWords() {
        Part fg = tmeFan("F1", "Fan: DC; 40x40x10mm; axial; 12VDC", "Supply voltage", "12V DC", "Signal output",
                "F type");
        Part bare = tmeFan("B1", "Fan: DC; 40x40x10mm; axial; 12VDC", "Supply voltage", "12V DC");
        for (String query : List.of("40x40x10 fan 12V tacho", "40x40x10 fan 12V tach", "40x40x10 fan 12V tachometer",
                "40x40x10 fan 12V FG", "40x40x10 fan 12V speed signal", "40x40x10 fan 12V sensor")) {
            assertThat(parser.parse(query).fan().features()).as(query).contains(ParsedQuery.TACHO);
            assertThat(assess(query, fg).mismatches()).as(query).isEmpty();
            assertThat(assess(query, bare).mismatches()).as(query).containsExactly("feature: tacho missing");
        }
        assertThat(parser.parse("40x40x10 fan 12V lock sensor").fan().features()).doesNotContain(ParsedQuery.TACHO);
    }

    @Test
    void theHintNamesTheFanConstraints() {
        ParsedQuery q = parser.parse("40x40x10 DC axial fan 12V");
        assertThat(policy.statedHard(q)).containsExactly("voltage", "fan type", "frame size");
        assertThat(ConstraintPolicy.describe(q)).isEqualTo("12V DC axial 40x40x10mm fan");
        assertThat(policy.hint(q, List.of("MOUSER"), Map.of("frame size", 3), 0, false)).isEqualTo(
                "No in-stock 12V DC axial 40x40x10mm fan at MOUSER; frame size, voltage and fan type are never "
                        + "relaxed. No substitutes are returned; try another value.");
    }

    // ---------------------------------------------------------------- phrasing

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "40x40x10 fan 12V        | \"Cooling fan\" | fan DC axial 12V 40x40x10  | DC fan 40x40x10 12V",
            "120mm axial fan 12V PWM | \"Cooling fan\" | fan DC axial 12V 120x120   | DC fan 120x120 12V",
            "radial blower 24V       | \"Cooling fan\" | fan DC blower 24V          | blower 24V",
            "fan 5V 3000rpm          | \"Cooling fan\" | fan DC axial 5V            | DC fan 5V",
            "blower 50x15 mm 12V     | \"Cooling fan\" | fan DC blower 12V 50x50x15 | blower 12V 50mm",
            "AC fan 230V 120mm       | \"Cooling fan\" | fan AC axial 230V 120x120  | AC fan 120x120 230V",
            "fan 92x92x25 24V 40 CFM sleeve bearing | \"Cooling fan\" | fan DC axial 24V 92x92x25 sleeve bearing"
                    + " | DC fan 92x92x25 24V sleeve bearing"})
    void fansArePhrasedInEachDistributorsWording(String query, String lcsc, String tme, String mouser) {
        ParsedQuery q = parser.parse(query);
        assertThat(DistributorPhraser.phrase(Distributor.LCSC, q)).isEqualTo(lcsc);
        assertThat(DistributorPhraser.phrase(Distributor.TME, q)).isEqualTo(tme)
                .hasSizeLessThanOrEqualTo(DistributorPhraser.TME_MAX_LENGTH);
        assertThat(DistributorPhraser.phrase(Distributor.MOUSER, q)).isEqualTo(mouser);
    }

    @Test
    void theLadderLoosensTheBearingAndKeepsTheHardWords() {
        ParsedQuery q = parser.parse("fan 40x40x10 12V ball bearing 3000rpm");
        String sent = DistributorPhraser.phrase(Distributor.MOUSER, q);
        assertThat(sent).isEqualTo("DC fan 40x40x10 12V ball bearing");
        List<DistributorPhraser.Relaxation> ladder = DistributorPhraser.ladder(Distributor.MOUSER, q, sent);
        assertThat(ladder).singleElement().satisfies(step -> {
            assertThat(step.phrase()).isEqualTo("DC fan 40x40x10 12V");
            assertThat(step.relaxed()).containsExactly("speed", "bearing");
        });
        assertThat(DistributorPhraser.ladder(Distributor.TME, parser.parse("40x40x10 fan 12V"),
                "fan DC axial 12V 40x40x10")).as("nothing relaxable stated: no step").isEmpty();
    }

    // ---------------------------------------------------------------- the recorded searches

    private SearchResponse search(String query) {
        Clock clock = Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC);
        List<PartSearchServiceTest.FakeClient> clients = new ArrayList<>();
        FanFixtures.load(FanFixtures.QUERIES.get(query)).forEach((d, parts) -> {
            PartSearchServiceTest.FakeClient client = new PartSearchServiceTest.FakeClient(d);
            client.raw.addAll(parts);
            clients.add(client);
        });
        KinaProperties props = RankingFixtures.properties("kina.ranking.cross-encoder.enabled", "false");
        RankingService ranking = TestWiring.rankingService(props, TestWiring.deterministicRanker(extractor),
                mock(PartRanker.class), () -> null, TestWiring.scoreCache(Duration.ofHours(1)));
        PartSearchService service = RankingFixtures.searchService(props,
                TestWiring.registry(List.copyOf(clients)), parser, extractor, ranking,
                mock(PartCacheRepository.class), mock(SearchCacheRepository.class), clock);
        return service.search(new SearchRequest(query, 50, Set.of(), false, 1, ResponseDetail.FULL));
    }

    private static DistributorResult result(SearchResponse response, Distributor d) {
        return PartSearchServiceTest.result(response, d);
    }

    @Test
    void recorded40x40x10At12V() {
        SearchResponse response = search("40x40x10 fan 12V");
        for (Distributor d : List.of(Distributor.TME, Distributor.MOUSER)) {
            DistributorResult r = result(response, d);
            assertThat(r.parts()).as(d.name()).isNotEmpty().allSatisfy(p -> {
                // ebm-papst "10-14VDC" states a range, no supply voltage: unverified, it ranks last
                assertThat(p.attributes().get("Voltage")).isIn("12V", null);
                assertThat(p.attributes().get("FrameSize")).isIn("40x40x10mm", "40x40x10.6mm");
            });
            assertThat(r.parts().getFirst().unverified()).isEmpty();
        }
        assertThat(result(response, Distributor.MOUSER).parts()).last()
                .satisfies(p -> assertThat(p.unverified()).containsExactly("voltage"));
        // the 50 mm and 60 mm JLCPCB fans are no 40 mm fans
        DistributorResult lcsc = result(response, Distributor.LCSC);
        assertThat(lcsc.parts()).isEmpty();
        assertThat(lcsc.excludedByConstraintsDetail()).containsEntry("frame size", 2);
        assertThat(lcsc.hint()).contains("frame size");
    }

    @Test
    void recorded120mmAxialPwm() {
        SearchResponse response = search("120mm axial fan 12V PWM");
        DistributorResult mouser = result(response, Distributor.MOUSER);
        assertThat(mouser.parts()).isNotEmpty().allSatisfy(p -> {
            assertThat(p.attributes().get("FrameSize")).startsWith("120x120");
            assertThat(p.attributes()).containsEntry("FanType", "axial").containsEntry("Voltage", "12V");
        });
        // the PWM fans come first among equals
        assertThat(mouser.parts().getFirst().attributes().getOrDefault("Features", "")).contains("PWM");
        assertThat(result(response, Distributor.TME).parts()).allSatisfy(p ->
                assertThat(p.attributes().get("FrameSize")).startsWith("120x120"));
    }

    @Test
    void recordedRadialBlowerAt24V() {
        SearchResponse response = search("radial blower 24V");
        DistributorResult mouser = result(response, Distributor.MOUSER);
        assertThat(mouser.parts()).isNotEmpty().allSatisfy(p -> {
            assertThat(p.attributes()).containsEntry("FanType", "radial").containsEntry("Voltage", "24V");
        });
        assertThat(mouser.parts()).extracting(PartResponse::mpn).doesNotContain("CFM-A225V-231-445");
        assertThat(mouser.excludedByConstraintsDetail()).containsKey("fan type");
        assertThat(result(response, Distributor.TME).parts()).isNotEmpty()
                .allSatisfy(p -> assertThat(p.attributes()).containsEntry("FanType", "radial"));
    }

    @Test
    void recordedFiveVoltFansAt3000Rpm() {
        SearchResponse response = search("fan 5V 3000rpm");
        for (Distributor d : List.of(Distributor.TME, Distributor.MOUSER)) {
            DistributorResult r = result(response, d);
            assertThat(r.parts()).as(d.name()).isNotEmpty()
                    .allSatisfy(p -> assertThat(p.attributes()).containsEntry("Voltage", "5V"));
            // a part within 15 % of 3000 rpm, or one that does not state its speed, ranks above a faster one
            PartResponse first = r.parts().getFirst();
            assertThat(first.mismatches()).as(d + " " + first.mpn()).noneMatch(m -> m.startsWith("speed:"));
            assertThat(r.parts()).anySatisfy(p -> assertThat(p.mismatches()).anyMatch(m -> m.startsWith("speed:")));
        }
    }
}
