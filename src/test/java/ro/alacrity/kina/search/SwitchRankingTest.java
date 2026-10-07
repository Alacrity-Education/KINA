package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ro.alacrity.kina.TestWiring;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.cache.SearchCacheRepository;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.ConstraintKind;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.DistributorResult;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
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
import static org.mockito.Mockito.mock;
import static ro.alacrity.kina.search.RankingFixtures.attrs;

/**
 * Switch matching through the declarations only (DESIGN.md 3.4 "Switches"): the switch type, contacts, function,
 * termination class, size, cut-out, positions and illumination are hard; current, voltage (AC or DC), IP code and life
 * minimums; the force a relaxable mismatch outside 20 %. Then the distributor phrasing and the recorded searches
 * (fixtures/switches) end to end.
 */
class SwitchRankingTest {

    private final QueryParser parser = new QueryParser();
    private final ParametricExtractor extractor = new ParametricExtractor();
    private final DeterministicRanker ranker = TestWiring.deterministicRanker(extractor);
    private final ConstraintPolicy policy = ConstraintPolicy.DEFAULTS;

    private static Part tmeSwitch(String symbol, String type, String description, String... attributes) {
        List<String> all = new ArrayList<>(List.of("Type of switch", type));
        all.addAll(List.of(attributes));
        return RankingFixtures.tme(symbol, "ACME", description, "Switches", null, attrs(all.toArray(String[]::new)));
    }

    private ConstraintPolicy.Result check(String query, Part part) {
        return policy.check(parser.parse(query), extractor.features(part));
    }

    private DeterministicRanker.Assessment assess(String query, Part part) {
        return ranker.assess(parser.parse(query), part);
    }

    // ---------------------------------------------------------------- the policy row

    @Test
    void theSwitchRowIsDeclared() {
        ParsedQuery q = parser.parse("tactile switch 6x6 SMD");
        assertThat(ConstraintPolicy.policyFamily(q)).isEqualTo("switch");
        assertThat(policy.hardFor(q)).containsExactlyInAnyOrder("type", "package", "mounting", "switch type",
                "contacts", "switch function", "termination", "switch size", "hole diameter", "switch positions",
                "illumination");
        assertThat(policy.isRelaxable(q, "force")).isTrue();
        assertThat(policy.isRelaxable(parser.parse("10uF 25V 0805"), "force")).isFalse();
        assertThat(ConstraintKind.isExactRating(ParsedQuery.VOLTAGE, "switch")).isFalse();
        assertThat(ConstraintKind.isMinimumRating(ParsedQuery.CURRENT, "switch")).isTrue();
    }

    // ---------------------------------------------------------------- hard: type, contacts, function, termination

    @Test
    void aTactileRequestNeverReturnsAToggleAndAPushbuttonRequestTakesTactileSwitches() {
        Part toggle = tmeSwitch("T1", "toggle", "Switch: toggle; SPDT; ON-ON; Leads: for PCB", "Mounting", "THT");
        Part tactile = tmeSwitch("K1", "tactile", "Microswitch TACT; SPST-NO; 6x6mm", "Mounting", "SMT");
        assertThat(check("tactile switch THT", toggle).conflicts()).containsExactly("switch type");
        assertThat(check("pushbutton switch SMD", tactile).conflict()).isFalse();
        assertThat(check("switch SPDT THT", toggle).conflict()).as("no type: every mechanical switch").isFalse();
        Part analog = RankingFixtures.mouser("TS5A3159", "TI", "Analog Switch ICs 1-Ohm SPDT Analog Switch",
                "Analog Switch ICs", "SOT-23-6", attrs());
        assertThat(check("switch SPDT", analog).conflicts()).contains("switch type");
    }

    @ParameterizedTest(name = "{0} vs {1}")
    @CsvSource(delimiter = '|', value = {
            "toggle switch SPDT     | SPDT    | true",
            "toggle switch SPDT     | SPST    | false",
            "toggle switch SPST     | DPST    | false",
            "toggle switch DPDT     | 2P2T    | true",
            "toggle switch SPST     | SPST-NO | true",
            "toggle switch SPST-NO  | SPST-NC | false",
            "toggle switch SPST-NC  | SPST    | true"})
    void theContactsAreHard(String query, String contacts, boolean kept) {
        Part part = tmeSwitch("T1", "toggle", "Switch: toggle; " + contacts, "Contacts configuration", contacts);
        assertThat(check(query, part).conflict()).isEqualTo(!kept);
        if (!kept) {
            assertThat(check(query, part).conflicts()).containsExactly("contacts");
        }
    }

    @ParameterizedTest(name = "{0} vs {1}")
    @CsvSource(delimiter = '|', value = {
            "pushbutton momentary    | OFF-(ON)      | true",
            "pushbutton momentary    | ON-OFF        | false",
            "pushbutton latching     | ON-OFF        | true",
            "pushbutton latching     | OFF-(ON)      | false",
            "toggle switch ON-OFF-ON | ON-OFF-ON     | true",
            "toggle switch ON-OFF-ON | ON-ON         | false",
            "toggle switch momentary | (ON)-OFF-(ON) | true"})
    void momentaryAndLatchingAreHard(String query, String function, boolean kept) {
        Part part = tmeSwitch("P1", query.startsWith("toggle") ? "toggle" : "push-button", "Switch",
                "Switching method", function);
        assertThat(check(query, part).conflict()).isEqualTo(!kept);
        if (!kept) {
            assertThat(check(query, part).conflicts()).containsExactly("switch function");
        }
    }

    @Test
    void aPcbRequestNeverReturnsAPanelSwitchWithSolderLugsAndTheReverse() {
        Part lugs = tmeSwitch("T1", "toggle", "Switch: toggle; SPDT; ON-ON", "Leads", "for soldering", "Mounting",
                "on panel");
        Part pcb = tmeSwitch("T2", "toggle", "Switch: toggle; SPDT; ON-ON", "Leads", "for PCB", "Mounting", "THT");
        Part quick = tmeSwitch("T3", "toggle", "Switch: toggle; SPDT; ON-ON", "Leads", "connectors", "Mounting",
                "on panel");
        assertThat(check("toggle switch SPDT THT", lugs).conflicts()).containsExactly("termination");
        assertThat(check("toggle switch SPDT for wire soldering", pcb).conflicts()).containsExactly("termination");
        assertThat(check("toggle switch SPDT solder lug", quick).conflicts()).containsExactly("termination");
        assertThat(check("toggle switch SPDT panel mount", quick).conflict()).isFalse();
        assertThat(check("toggle switch SPDT panel mount", pcb).conflicts()).containsExactly("termination");
        assertThat(assess("toggle switch SPDT solder lug", pcb).mismatches())
                .contains("termination: PCB instead of solder lug");
    }

    @Test
    void sizeCutOutPositionsAndIlluminationAreHardWhenStated() {
        Part tact = tmeSwitch("K1", "tactile", "Microswitch TACT; SPST-NO; SMT; 6x6mm", "Body dimensions",
                "6x6x4.3mm", "Mounting", "SMT");
        assertThat(check("tactile switch 6x6 SMD", tact).conflict()).isFalse();
        assertThat(check("tactile switch 6x6x5 SMD", tact).conflicts()).containsExactly("switch size");
        assertThat(check("tactile switch 12x12 SMD", tact).conflicts()).containsExactly("switch size");
        Part button = tmeSwitch("P1", "push-button", "Switch: push-button; SPST; OFF-(ON); Cutout: Ø16mm",
                "Illumination", "none", "Mounting", "on panel");
        assertThat(check("pushbutton 16mm panel", button).conflict()).isFalse();
        assertThat(check("pushbutton 12mm panel", button).conflicts()).containsExactly("hole diameter");
        assertThat(check("illuminated pushbutton 16mm panel", button).conflicts()).containsExactly("illumination");
        Part dip = tmeSwitch("D1", "DIP-SWITCH", "Switch: DIP-SWITCH; Poles number: 8; ON-OFF; Pos: 2",
                "Poles number", "8", "Number of positions", "2");
        assertThat(check("DIP switch 8 position", dip).conflict()).isFalse();
        assertThat(check("DIP switch 4 position", dip).conflicts()).containsExactly("switch positions");
    }

    // ---------------------------------------------------------------- ratings

    @Test
    void ratingsAreMinimumsAndAcOrDcIsPartOfTheMatch() {
        Part dcOnly = tmeSwitch("T1", "toggle", "Switch: toggle; SPDT; 20A/12VDC", "DC contacts rating @R",
                "20A / 12V DC");
        Part both = tmeSwitch("T2", "toggle", "Switch: toggle; SPDT; 3A/250VAC; 4A/30VDC", "AC contacts rating @R",
                "3A / 250V AC", "DC contacts rating @R", "4A / 30V DC");
        assertThat(assess("toggle switch 250VAC", dcOnly).belowSpec()).containsExactly("voltage");
        assertThat(assess("toggle switch 250VAC", dcOnly).mismatches()).contains("voltage: 12V DC instead of 250V AC");
        assertThat(assess("toggle switch 12V DC", dcOnly).belowSpec()).isEmpty();
        assertThat(assess("toggle switch 250VAC 2A", both).belowSpec()).isEmpty();
        assertThat(assess("toggle switch 48VDC", both).belowSpec()).containsExactly("voltage");
        assertThat(assess("toggle switch 5A", both).belowSpec()).containsExactly("current");
        assertThat(assess("toggle switch 100V", both).belowSpec()).as("no AC or DC: the largest rating").isEmpty();
    }

    @Test
    void ipCodeAndLifeAreMinimums() {
        Part sealed = tmeSwitch("K1", "tactile", "Microswitch TACT; IP67", "IP rating", "IP67",
                "Mechanical durability", "100000 cycles");
        assertThat(assess("tactile switch IP65", sealed).belowSpec()).isEmpty();
        assertThat(assess("tactile switch IP68", sealed).belowSpec()).containsExactly("ip rating");
        assertThat(assess("tactile switch 50000 cycles", sealed).belowSpec()).isEmpty();
        assertThat(assess("tactile switch 1,000,000 cycles", sealed).belowSpec()).containsExactly("life");
        Part ip40 = tmeSwitch("K2", "tactile", "Microswitch TACT; IP40", "IP rating", "IP40");
        assertThat(assess("tactile switch sealed", ip40).belowSpec()).containsExactly("ip rating");
    }

    @Test
    void theForceIsAMismatchOutside20PercentAndNeverExcludes() {
        Part part = tmeSwitch("K1", "tactile", "Microswitch TACT", "Operating force", "2.55N");
        assertThat(check("tactile switch 160gf", part).conflict()).isFalse();
        assertThat(assess("tactile switch 160gf", part).mismatches())
                .contains("force: 2.55 N (260 gf) instead of 1.57 N (160 gf)");
        assertThat(assess("tactile switch 250gf", part).mismatches()).noneMatch(m -> m.startsWith("force"));
    }

    @Test
    void theHintNamesTheSwitchConstraints() {
        ParsedQuery q = parser.parse("SPDT toggle switch panel mount solder lug");
        assertThat(policy.statedHard(q)).containsExactly("switch type", "contacts", "termination");
        assertThat(ConstraintPolicy.describe(q)).isEqualTo("toggle SPDT panel-mount solder lug switch");
        assertThat(policy.hint(q, List.of("LCSC"), Map.of("termination", 50), 0, false)).isEqualTo(
                "No in-stock toggle SPDT panel-mount solder lug switch at LCSC; termination, switch type and contacts "
                        + "are never relaxed. No substitutes are returned; try another wording.");
    }

    // ---------------------------------------------------------------- phrasing

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "tactile switch 6x6 SMD                    | \"Tactile Switches\" 6x6mm SMD | microswitch TACT 6x6 SMT    | -",
            "SPDT toggle switch panel mount solder lug | \"Toggle Switches\" SPDT       | toggle switch SPDT          | toggle switch SPDT solder lug",
            "SPST momentary pushbutton 12mm panel      | \"Pushbutton Switches\" SPST   | push-button switch SPST     | pushbutton switch SPST momentary 12mm",
            "DIP switch 8 position                     | \"DIP Switches\"               | DIP-SWITCH 8                | -",
            "slide switch SPDT THT                     | \"Slide Switches\" SPDT THT    | -                           | -",
            "micro switch 1 Form C quick connect       | \"Limit Switches\" SPDT        | microswitch SNAP ACTION SPDT | snap action switch SPDT quick connect",
            "toggle switch ON-OFF-ON 250VAC            | \"Toggle Switches\"            | toggle switch ON-OFF-ON     | toggle switch ON-OFF-ON"})
    void switchesArePhrasedInEachDistributorsWording(String query, String lcsc, String tme, String mouser) {
        ParsedQuery q = parser.parse(query);
        assertThat(DistributorPhraser.phrase(Distributor.LCSC, q)).isEqualTo(lcsc);
        assertThat(DistributorPhraser.phrase(Distributor.TME, q)).as("null: the text is sent verbatim")
                .isEqualTo("-".equals(tme) ? null : tme);
        assertThat(DistributorPhraser.phrase(Distributor.MOUSER, q)).isEqualTo("-".equals(mouser) ? null : mouser);
    }

    // ---------------------------------------------------------------- the recorded searches

    private SearchResponse search(String query, String fixture) {
        Clock clock = Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC);
        List<PartSearchServiceTest.FakeClient> clients = new ArrayList<>();
        RecordedSearches.load(SwitchExtractionTest.FAMILY, fixture).forEach((d, parts) -> {
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

    @Test
    void recordedToggleWithSolderLugs() {
        SearchResponse response = search("SPDT toggle switch panel mount solder lug",
                "spdt-toggle-switch-panel-mount-solder-lug");
        for (Distributor d : List.of(Distributor.TME, Distributor.MOUSER)) {
            DistributorResult r = PartSearchServiceTest.result(response, d);
            assertThat(r.parts()).as(d.name()).isNotEmpty().allSatisfy(p -> {
                assertThat(p.attributes().get("SwitchType")).isEqualTo("toggle");
                assertThat(p.attributes().get("Contacts")).isIn("SPDT", null);
                assertThat(p.attributes().get("Termination")).isIn("solder lug", "panel", null);
            });
        }
        // TME's PCB, screw and quick connect toggles are left out
        assertThat(PartSearchServiceTest.result(response, Distributor.TME).excludedByConstraintsDetail())
                .containsKey("termination");
    }

    @Test
    void recordedTactile6x6Smd() {
        SearchResponse response = search("tactile switch 6x6 SMD", "tactile-switch-6x6-smd");
        for (Distributor d : List.of(Distributor.LCSC, Distributor.MOUSER)) {
            DistributorResult r = PartSearchServiceTest.result(response, d);
            assertThat(r.parts()).as(d.name()).isNotEmpty().allSatisfy(p -> {
                assertThat(p.attributes().get("SwitchType")).isEqualTo("tactile");
                String size = p.attributes().get("SwitchSize");
                assertThat(size == null || size.startsWith("6x6") || size.startsWith("6.1x6")).as(size).isTrue();
                assertThat(p.attributes().get("Mounting")).isIn("SMD", null);
            });
        }
    }

    @Test
    void recordedDipSwitchesWithEightSwitches() {
        SearchResponse response = search("DIP switch 8 position", "dip-switch-8-position");
        for (Distributor d : List.of(Distributor.TME, Distributor.MOUSER)) {
            DistributorResult r = PartSearchServiceTest.result(response, d);
            assertThat(r.parts()).as(d.name()).isNotEmpty().allSatisfy(p -> {
                assertThat(p.attributes().get("SwitchType")).isEqualTo("DIP");
                assertThat(p.attributes().get("SwitchPositions")).isIn("8", null);
            });
        }
        // Mouser's coded rotary switches are another type
        assertThat(PartSearchServiceTest.result(response, Distributor.MOUSER).excludedByConstraintsDetail())
                .containsKey("switch type");
    }
}
