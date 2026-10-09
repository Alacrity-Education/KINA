package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ro.alacrity.kina.TestWiring;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.cache.SearchCacheRepository;
import ro.alacrity.kina.config.KinaProperties;
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
import static org.mockito.Mockito.mock;
import static ro.alacrity.kina.search.RankingFixtures.attrs;

/**
 * LED matching through the declarations only (DESIGN.md 3.4 "LEDs"): the LED type, the colour, the wavelength within
 * 10 nm, the package (LED package names are never metric chip codes) and the mounting are hard; the forward voltage is
 * a maximum, the current and the luminous intensity minimums; the colour temperature, the viewing angle and the lens
 * relaxable mismatches. Then the distributor phrasing and the recorded searches (fixtures/leds) end to end.
 */
class LedRankingTest {

    private final QueryParser parser = new QueryParser();
    private final ParametricExtractor extractor = new ParametricExtractor();
    private final DeterministicRanker ranker = TestWiring.deterministicRanker(extractor);
    private final ConstraintPolicy policy = ConstraintPolicy.DEFAULTS;

    private static Part tmeLed(String symbol, String description, String... attributes) {
        return RankingFixtures.tme(symbol, "KINGBRIGHT", description, "SMD colour LEDs", null, attrs(attributes));
    }

    private static Part lcscLed(String code, String description, String category, String packageName) {
        return RankingFixtures.lcsc(code, "ACME", code, description, "Optoelectronics / " + category, packageName,
                attrs());
    }

    private ConstraintPolicy.Result check(String query, Part part) {
        return policy.check(parser.parse(query), extractor.features(part));
    }

    private DeterministicRanker.Assessment assess(String query, Part part) {
        return ranker.assess(parser.parse(query), part);
    }

    // ---------------------------------------------------------------- the policy row

    @Test
    void theLedRowIsDeclared() {
        ParsedQuery q = parser.parse("0603 red LED 20mA");
        assertThat(ConstraintPolicy.policyFamily(q)).isEqualTo("led");
        assertThat(policy.hardFor(q)).containsExactlyInAnyOrder("type", "package", "mounting", "led type", "colour",
                "wavelength");
        assertThat(policy.isRelaxable(q, "lens")).isTrue();
        assertThat(policy.isRelaxable(q, "viewing angle")).isTrue();
        assertThat(policy.isRelaxable(q, "colour temperature")).isTrue();
        assertThat(policy.isRelaxable(q, "colour")).isFalse();
        // relaxable for LEDs only
        assertThat(policy.isRelaxable(parser.parse("10uF 25V 0805"), "lens")).isFalse();
        // a diode request still takes an LED and the reverse (as before LEDs had a row of their own)
        assertThat(check("LED 0603 red", RankingFixtures.lcsc("C1", "ACME", "D1", "Diodes", "Diodes / Diodes",
                "0603", attrs())).conflict()).isFalse();
    }

    // ---------------------------------------------------------------- hard: colour, type, wavelength, package

    @ParameterizedTest(name = "{0} vs {1}")
    @CsvSource(delimiter = '|', value = {
            "red LED 0603         | Red              | true",
            "red LED 0603         | Emerald Green    | false",
            "green LED 0603       | Yellow Green     | true",
            "white LED 0603       | Warm White       | true",
            "white LED 0603       | Cool White       | true",
            "yellow LED 0603      | Amber            | true",
            "RGB LED 0603         | Red              | false",
            "red LED 0603         | Red, Yellow Green | false",
            "orange LED 0603      | Red              | false"})
    void theColourIsAHardType(String query, String colour, boolean kept) {
        Part part = lcscLed("C1", "120° 2V 20mA Discrete Diode " + colour + " Water Clear", "LED Indication - Discrete",
                "0603");
        assertThat(check(query, part).conflict()).isEqualTo(!kept);
        if (!kept) {
            assertThat(check(query, part).conflicts()).containsExactly("colour");
        }
    }

    @Test
    void aWhiteLedThatDoesNotSayWhichWhiteIsUnverifiedForAWarmWhiteRequest() {
        Part white = lcscLed("C1", "120° 3.2V 20mA White Water Clear", "LED Indication - Discrete", "2835");
        assertThat(check("warm white LED 2835", white).conflict()).isFalse();
        assertThat(assess("warm white LED 2835", white).unverified()).contains("colour");
        Part bare = lcscLed("C2", null, "LED Indication - Discrete", "0603");
        assertThat(assess("red LED 0603", bare).unverified()).contains("colour");
        assertThat(check("red LED 0603", bare).conflict()).isFalse();
    }

    @ParameterizedTest(name = "{0} vs {1}")
    @CsvSource(delimiter = '|', value = {
            "LED 625nm 0603 | 630nm | Red   | true",
            "LED 625nm 0603 | 640nm | Red   | false",
            "LED 470nm 0603 | 465nm | Blue  | true",
            "LED 470nm 0603 | 525nm | Green | false"})
    void theWavelengthIsHardWithin10NmAndImpliesTheColourBand(String query, String nm, String colour, boolean kept) {
        Part part = lcscLed("C1", "120° 2V 20mA " + nm + " Discrete Diode " + colour, "LED Indication - Discrete",
                "0603");
        assertThat(check(query, part).conflict()).isEqualTo(!kept);
        if (!kept) {
            assertThat(check(query, part).conflicts()).contains("wavelength");
        }
        assertThat(parser.parse(query).led().colour()).as("the band of the wavelength").isNotNull();
    }

    @Test
    void ledPackageNamesAreNeverConvertedAndCompareAsPackages() {
        Part p5050 = lcscLed("C1", "RGB Water Clear", "RGB LEDs", "SMD5050-6P");
        Part p3528 = lcscLed("C2", "RGB Water Clear", "RGB LEDs", "SMD3528-4P");
        Part lamp = lcscLed("C3", "5mm round lamp head White", "LED Indication - Discrete", "Plugin,D=5mm");
        assertThat(extractor.extract(p5050)).containsEntry("Package", "5050").containsEntry("Mounting", "SMD");
        assertThat(extractor.extract(lamp)).containsEntry("Package", "5mm").containsEntry("Mounting", "THT");
        assertThat(check("RGB LED 5050", p5050).conflict()).isFalse();
        assertThat(check("RGB LED 5050", p3528).conflicts()).containsExactly("package");
        assertThat(check("white LED 5mm", lamp).conflict()).isFalse();
        assertThat(check("white LED 3mm", lamp).conflicts()).containsExactly("package");
        // a chip package implies surface mount: the THT lamp also contradicts the mounting (v0.16)
        assertThat(check("white LED 0603", lamp).conflicts()).containsExactly("package", "mounting");
        // TME "Case - mm: 5050" and the package field PLCC6: the size code wins
        Part tme = tmeLed("L1", "LED; SMD; 5050,PLCC6; RGB", "Case - mm", "5050", "LED colour", "RGB");
        assertThat(extractor.extract(tme)).containsEntry("Package", "5050");
    }

    @Test
    void anAddressableLedAnswersOnlyAnAddressableRequestAndNoEmitterAnswersAny() {
        Part ws = lcscLed("C1", "5mm 5mm 800Kbit/s Water Clear", "RGB LEDs(Built-in IC)", "SMD5050-4P");
        Part rgb = lcscLed("C2", "RGB Water Clear", "RGB LEDs", "SMD5050-6P");
        Part strip = tmeLed("S1", "LED tape; RGB; Case (mm): 5050; LED/m: 60", "LED colour", "RGB");
        assertThat(check("RGB LED 5050", ws).conflicts()).containsExactly("led type");
        assertThat(check("WS2812B 5050", rgb).conflicts()).containsExactly("led type");
        assertThat(check("WS2812B 5050", ws).conflict()).isFalse();
        assertThat(check("RGB LED 5050", strip).conflicts()).containsExactly("led type");
        assertThat(assess("RGB LED 5050", ws).mismatches()).contains("led type: addressable instead of LED");
    }

    // ---------------------------------------------------------------- ratings

    @Test
    void theForwardVoltageIsAMaximumCurrentAndIntensityMinimums() {
        Part led = tmeLed("L1", "LED; red; SMD; 0603; 18÷54mcd; 2÷2.4VDC; 130°; 20mA", "LED colour", "red",
                "Operating voltage", "2...2.4V DC", "LED current", "20mA", "Luminosity", "18...54mcd",
                "Case - inch", "0603");
        assertThat(assess("red LED 0603 Vf 2.5V 20mA 50mcd", led).belowSpec()).isEmpty();
        assertThat(assess("red LED 0603 Vf 2.2V", led).belowSpec()).containsExactly("forward voltage");
        assertThat(assess("red LED 0603 Vf 2.2V", led).mismatches()).contains("forward voltage: 2.4V above 2.2V");
        assertThat(assess("red LED 0603 30mA", led).belowSpec()).containsExactly("current");
        assertThat(assess("red LED 0603 100mcd", led).belowSpec()).containsExactly("luminous intensity");
        assertThat(assess("red LED 0603 10mA", led).belowSpec()).as("a 20 mA LED works at 10 mA").isEmpty();
    }

    // ---------------------------------------------------------------- relaxable: colour temperature, angle, lens

    @Test
    void colourTemperatureViewingAngleAndLensAreMismatchesNeverExclusions() {
        Part led = tmeLed("L1", "LED; white warm; 5mm; 30°", "LED colour", "white warm", "LED diameter", "5mm",
                "Colour temperature", "2700-3200K", "Viewing angle", "30°", "LED lens", "transparent");
        String query = "warm white LED 5mm 4000K 120° diffused";
        assertThat(check(query, led).conflict()).isFalse();
        assertThat(assess(query, led).mismatches()).contains("colour temperature: 2950 K instead of 4000 K",
                "viewing angle: 30° instead of 120°", "lens: clear instead of diffused");
        assertThat(assess("warm white LED 5mm 3000K 40°", led).mismatches()).isEmpty();
        assertThat(assess("warm white LED 5mm right angle", led).mismatches()).isEmpty();
    }

    @Test
    void theHintNamesTheLedConstraints() {
        ParsedQuery q = parser.parse("0805 blue LED 470nm");
        assertThat(policy.statedHard(q)).containsExactly("package", "colour", "wavelength");
        assertThat(ConstraintPolicy.describe(q)).isEqualTo("blue led in package 0805");
        assertThat(policy.hint(q, List.of("TME"), Map.of("colour", 3), 0, false)).isEqualTo(
                "No in-stock blue led in package 0805 at TME; colour, package and wavelength are never relaxed. "
                        + "No substitutes are returned; try another package.");
    }

    // ---------------------------------------------------------------- phrasing

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "0603 red LED 20mA      | \"LED Indication - Discrete\" 0603 Red >=20mA | LED 0603 red               | LED 0603 red",
            "5mm white LED diffused | \"LED Indication - Discrete\" 5mm White       | LED 5mm white diffused     | LED 5mm white diffused",
            "0805 blue LED 470nm    | \"LED Indication - Discrete\" 0805 Blue       | LED 0805 blue              | LED 0805 blue",
            "RGB LED 5050           | \"RGB LEDs\" 5050                             | LED RGB 5050               | -",
            "IR LED 940nm 5mm       | \"Infrared LED Emitters\" 5mm                 | IR transmitter 5mm 940nm   | infrared emitter 940nm 5mm",
            "WS2812B 5050           | \"RGB LEDs\" 5050                             | LED WS2812B 5050           | WS2812B LED 5050",
            "UV LED 395nm 3535      | \"Ultraviolet LEDs\" 3535                     | LED UV 3535 395nm          | -"})
    void ledsArePhrasedInEachDistributorsWording(String query, String lcsc, String tme, String mouser) {
        ParsedQuery q = parser.parse(query);
        assertThat(DistributorPhraser.phrase(Distributor.LCSC, q)).isEqualTo(lcsc);
        assertThat(DistributorPhraser.phrase(Distributor.TME, q)).isEqualTo(tme)
                .hasSizeLessThanOrEqualTo(DistributorPhraser.TME_MAX_LENGTH);
        assertThat(DistributorPhraser.phrase(Distributor.MOUSER, q)).as("null: the text is sent verbatim")
                .isEqualTo("-".equals(mouser) ? null : mouser);
    }

    @Test
    void theLadderLoosensTheLensAndKeepsColourAndPackage() {
        ParsedQuery q = parser.parse("5mm white LED diffused 120°");
        String sent = DistributorPhraser.phrase(Distributor.TME, q);
        assertThat(DistributorPhraser.ladder(Distributor.TME, q, sent)).singleElement().satisfies(step -> {
            assertThat(step.phrase()).isEqualTo("LED 5mm white");
            assertThat(step.relaxed()).containsExactly("lens");
        });
    }

    // ---------------------------------------------------------------- the recorded searches

    private SearchResponse search(String query, String fixture) {
        Clock clock = Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC);
        List<PartSearchServiceTest.FakeClient> clients = new ArrayList<>();
        RecordedSearches.load(LedExtractionTest.FAMILY, fixture).forEach((d, parts) -> {
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
    void recordedRed0603() {
        SearchResponse response = search("0603 red LED 20mA", "0603-red-led-20ma");
        for (Distributor d : Distributor.values()) {
            DistributorResult r = PartSearchServiceTest.result(response, d);
            assertThat(r.parts()).as(d.name()).isNotEmpty().allSatisfy(p -> {
                assertThat(p.attributes().get("LedColour")).isIn("red", null);
                assertThat(p.attributes().get("Package")).isIn("0603", null);
            });
        }
        // TME's red/green bi-colour LEDs are no red LEDs
        Part bicolour = RecordedSearches.part(LedExtractionTest.FAMILY, "0603-red-led-20ma", Distributor.TME,
                "HSMF-C165");
        assertThat(check("0603 red LED 20mA", bicolour).conflicts()).containsExactly("colour");
    }

    @Test
    void recordedRgb5050LeavesOutStripsAndAddressableLeds() {
        SearchResponse response = search("RGB LED 5050", "rgb-led-5050");
        for (Distributor d : Distributor.values()) {
            DistributorResult r = PartSearchServiceTest.result(response, d);
            assertThat(r.parts()).as(d.name()).allSatisfy(p -> {
                assertThat(p.attributes().get("LedType")).isIn("indicator", "high power");
                assertThat(p.attributes().get("LedColour")).isIn("RGB", null);
            });
        }
        // TME answered "LED RGB 5050" with LED tapes only: every one is left out
        DistributorResult tme = PartSearchServiceTest.result(response, Distributor.TME);
        assertThat(tme.parts()).isEmpty();
        assertThat(tme.excludedByConstraintsDetail()).containsEntry("led type", 60);
        assertThat(PartSearchServiceTest.result(response, Distributor.MOUSER).parts()).extracting(PartResponse::mpn)
                .doesNotContain("LTST-E563CEGBW", "SMTLA5050RGB");
    }

    @Test
    void recordedInfraredFiveMillimetre() {
        SearchResponse response = search("IR LED 940nm 5mm", "ir-led-940nm-5mm");
        for (Distributor d : Distributor.values()) {
            DistributorResult r = PartSearchServiceTest.result(response, d);
            assertThat(r.parts()).as(d.name()).isNotEmpty().allSatisfy(p -> {
                assertThat(p.attributes().get("LedColour")).isIn("IR", null);
                assertThat(p.attributes().get("Package")).isIn("5mm", null);
            });
        }
        // an 850 nm emitter is another wavelength
        Part ir850 = lcscLed("C1", "1.5V 100mA 850nm", "Infrared LED Emitters", "Plugin,D=5mm");
        assertThat(check("IR LED 940nm 5mm", ir850).conflicts()).containsExactly("wavelength");
    }
}
