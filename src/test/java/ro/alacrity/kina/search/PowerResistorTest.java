package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static ro.alacrity.kina.search.RankingFixtures.attrs;

/**
 * Power resistor precision (DESIGN.md 3.4 "Power resistors", "Form factor"): power wording, series wattage, form factor
 * classes, technology from the category or series, keyword-free match grading and the operating temperature range.
 */
class PowerResistorTest {

    private final QueryParser parser = new QueryParser();
    private final ParametricExtractor extractor = new ParametricExtractor();
    private final DeterministicRanker ranker = new DeterministicRanker(extractor);

    // ---------------------------------------------------------------- power wording and display

    @ParameterizedTest(name = "{0} -> {1} W")
    @CsvSource(delimiter = '|', value = {
            "Planar Resistors - Chassis Mount 300watts 4.7ohms 5% 12kV | 300",
            "Planar Resistors - Chassis Mount 800watts 4.7ohms 5% 12kV | 800",
            "Wirewound Resistors - Chassis Mount 50watt 10ohms 1% | 50",
            "Wirewound Resistors - Chassis Mount 12.5watt 10ohms .1% | 12.5",
            "resistor 150 watt 4.7 ohm | 150",
            "resistor 150 watts 4.7 ohm | 150",
            "resistor 25W 100 ohm | 25",
            "resistor 25 W 100 ohm | 25",
            "resistor 1.5 kW 10 ohm | 1500",
            "resistor 2kW 10 ohm | 2000",
            "resistor 1 kilowatt 10 ohm | 1000",
            "resistor 250 mW 10k | 0.25",
            "resistor 1/4W 10k | 0.25"})
    void powerWordingIsRead(String text, double watts) {
        Recognizers.Value power = Recognizers.analyze(text).values().get(ParsedQuery.POWER);
        assertThat(power).isNotNull();
        assertThat(power.value()).isCloseTo(watts, within(1e-9));
    }

    @ParameterizedTest(name = "{0} W -> {1}")
    @CsvSource({"250, 250W", "0.5, 0.5W", "0.125, 0.125W", "999, 999W", "1000, 1kW", "1500, 1.5kW", "150, 150W"})
    void powerIsShownInWattsBelowOneKilowatt(double watts, String display) {
        assertThat(Recognizers.display(ParsedQuery.POWER, watts)).isEqualTo(display);
        assertThat(parser.parse("resistor " + display + " 10 ohm").constraint(ParsedQuery.POWER).display())
                .isEqualTo(display);
    }

    @Test
    void tmeKilowattPowerIsShownInWatts() {
        Part ahp = RankingFixtures.tme("AHP250W-4R7F", "ARCOL/OHMITE",
                "Resistor: wire-wound; 4.7Ω; with heatsink; screw; 250W; ±1%", "Power resistors", null,
                attrs("Resistance", "4.7Ω", "Power", "0.25kW", "Tolerance", "±1%", "Kind of resistor", "with heatsink"));
        assertThat(extractor.extract(ahp)).containsEntry("Power", "250W");
        assertThat(extractor.enrich(ahp).attributes()).containsEntry("Power", "250W")
                .containsEntry("Resistance", "4.7Ω");   // other distributor attributes stay as given
    }

    // ---------------------------------------------------------------- series wattage

    @ParameterizedTest(name = "{0} {1} -> {2} W")
    @CsvSource(delimiter = '|', value = {
            "Arcol | HS25 4R7 J | 25",
            "Arcol | HS50 100R J | 50",
            "Arcol | HS100 10R F | 100",
            "Arcol | HSA25 100R J | 25",
            "Arcol | HSC100 100R J | 100",
            "Arcol | HS254R7J | 25",
            "TE Connectivity | THS15100RJ | 15",
            "TE Connectivity | THS2510RJ | 25",
            "TE Connectivity | THS50100RJ | 50",
            "Vishay / Dale | RH-50 10R | 50",
            "Vishay / Dale | RH-25 4R7 | 25",
            "Vishay / Dale | RH0504R700FE02 | 50",
            "Ohmite | TEH100M10R0JE | 100",
            "Ohmite | TEH70M4R70JE | 70",
            "Bourns | PWR220T-20-10R0F | 20",
            "Bourns | PWR263S-35-10R0F | 35",
            "Caddock | MP930-10.0-1% | 30",
            "Caddock | MP9100-10.0-1% | 100",
            "Ohmite | LPS0300H4R70JB | 300",
            "Ohmite | LPS0800H4R70JB | 800"})
    void seriesNamesTheWattage(String manufacturer, String mpn, double watts) {
        assertThat(ResistorSeries.power(manufacturer, mpn)).isEqualTo(watts);
        Part part = RankingFixtures.mouser(mpn, manufacturer, "Wirewound Resistors - Chassis Mount 10 Ohms 1%",
                "Wirewound Resistors", null, Map.of());
        assertThat(extractor.features(part).value(ParsedQuery.POWER)).isEqualTo(watts);
    }

    @ParameterizedTest(name = "{0} {1}: no series wattage")
    @CsvSource(delimiter = '|', value = {
            "Yageo | HS25 4R7 J",                 // HS is Arcol's prefix only
            "Vishay | RCL122510R0FKEG",
            "Ohmite | TGHGV10R0JE",               // TGH: the name gives no wattage
            "Texas Instruments | OPA2134"})
    void otherPartNumbersNameNoWattage(String manufacturer, String mpn) {
        assertThat(ResistorSeries.power(manufacturer, mpn)).isNull();
    }

    @Test
    void aStatedPowerWinsOverTheSeries() {
        Part stated = RankingFixtures.mouser("HS25 100R J", "Arcol", "Wirewound Resistors - Chassis Mount 40W 100 Ohms",
                "Wirewound Resistors", null, Map.of());
        assertThat(extractor.features(stated).value(ParsedQuery.POWER)).isEqualTo(40.0);
        Part capacitor = RankingFixtures.mouser("HS25", "Arcol", "Capacitor 10uF 25V", "Capacitors", null, Map.of());
        assertThat(extractor.features(capacitor).value(ParsedQuery.POWER)).isNull();   // resistors only
    }

    // ---------------------------------------------------------------- form factor

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "150W power resistor 4.7 ohm heatsink mount | chassis",
            "25W 100 ohm aluminium housed chassis mount resistor | chassis",
            "25W 100R aluminum housed resistor | chassis",
            "50W 10R alum housed resistor | chassis",
            "bolt mount resistor 100W 10R | chassis",
            "screw mount resistor 100W 10R | chassis",
            "10k 0805 resistor | ",
            "100uF 450V screw terminal capacitor | ",
            "10k resistor | "})
    void requestWordsNameTheFormFactor(String query, String formFactor) {
        assertThat(parser.parse(query).formFactor()).isEqualTo(formFactor);
    }

    @Test
    void theRequestPackageNamesTheClassWhenItHasOne() {
        ParsedQuery b = parser.parse("300W 10 ohm power resistor SOT-227 heatsink");
        assertThat(b.packageName()).isEqualTo("SOT-227");
        assertThat(b.formFactor()).isEqualTo(FormFactor.CHASSIS);
        assertThat(FormFactor.ofRequest(b, true)).isEqualTo(FormFactor.POWER_PACKAGE);
        assertThat(FormFactor.ofRequest(parser.parse("10k 0805 resistor"), true)).isEqualTo(FormFactor.CHIP);
        assertThat(FormFactor.ofRequest(parser.parse("10uH 0805 inductor"), false)).isNull();   // relaxable package
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "SOT-227 | power_package",
            "TO-220 | power_package",
            "TO-220-2 | power_package",
            "TO-247 | power_package",
            "TO-218 | power_package",
            "TO-126 | power_package",
            "TO-263 | power_smd",
            "D2PAK | power_smd",
            "DPAK | power_smd",
            "TO-252 | power_smd",
            "0805 | chip",
            "2512 | chip",
            "1225 | chip",
            "AXIAL-0.6 | through_hole",
            "SOT-23 | ",
            "Bolt Mount | "})
    void packagesHaveAClass(String packageName, String formFactor) {
        assertThat(FormFactor.ofPackage(packageName)).isEqualTo(formFactor);
    }

    @ParameterizedTest(name = "{1} / {2} / {3} -> {4}")
    @CsvSource(delimiter = '|', value = {
            "resistor | Wirewound Resistors - SMD 10 OHMS 5% | Wirewound Resistors - SMD | | chip",
            "resistor | Thick Film Resistors - SMD 2watt 10ohm 1% 100ppm 1225 | Thick Film Resistors | | chip",
            "resistor | Wirewound Resistors - Through Hole 2watt 10ohms 1% Axial | Wirewound Resistors | | through_hole",
            "resistor | Wirewound Resistors - Through Hole 5watts 10ohms 1% | Wirewound Resistors | | ",
            "resistor | Wirewound Resistors - Chassis Mount 25W 100 ohm 1% Alum Housed | Wirewound Resistors | | chassis",
            "resistor | Planar Resistors - Chassis Mount SOT-227 600W 10 OHM | Planar Resistors | SOT-227 | power_package",
            "resistor | Resistor: wire-wound; 100Ω; with heatsink; screw; 25W; ±5% | Power resistors | | chassis",
            "resistor | 100Ω 50W Aluminum Housed Resistor ±1% | Resistors | Bolt Mount | chassis",
            "resistor | -55℃~+155℃ 100Ω 25W Thick Film Resistor ±1% | Resistors | D2PAK | power_smd",
            "resistor | -55℃~+155℃ 100Ω 25W Cement Resistor ±5% | Resistors / Through Hole Resistors | Plugin | ",
            "capacitor | Aluminium Electrolytic Capacitors - SMD 100uF 25V | Capacitors | | ",
            "capacitor | Aluminium Electrolytic Capacitors - Radial Leaded 100uF 25V | Capacitors | | through_hole"})
    void partsHaveAClass(String family, String description, String category, String packageName, String formFactor) {
        assertThat(FormFactor.ofPart(family, packageName, description, category, packageName)).isEqualTo(formFactor);
    }

    @Test
    void compatibility() {
        assertThat(FormFactor.compatible(FormFactor.CHASSIS, FormFactor.CHASSIS)).isTrue();
        assertThat(FormFactor.compatible(FormFactor.CHASSIS, FormFactor.POWER_PACKAGE)).isTrue();
        assertThat(FormFactor.compatible(FormFactor.POWER_PACKAGE, FormFactor.CHASSIS)).isTrue();
        assertThat(FormFactor.compatible(FormFactor.CHASSIS, FormFactor.CHIP)).isFalse();
        assertThat(FormFactor.compatible(FormFactor.CHASSIS, FormFactor.THROUGH_HOLE)).isFalse();
        assertThat(FormFactor.compatible(FormFactor.CHASSIS, FormFactor.POWER_SMD)).isFalse();
        assertThat(FormFactor.compatible(FormFactor.CHIP, FormFactor.POWER_SMD)).isFalse();
        assertThat(FormFactor.compatible(FormFactor.CHASSIS, null)).isNull();
    }

    @Test
    void formFactorIsHardForPassivesAndNeverRelaxed() {
        for (String family : List.of(ConstraintPolicy.RESISTOR, ConstraintPolicy.CAPACITOR, ConstraintPolicy.INDUCTOR,
                ConstraintPolicy.DEFAULT)) {
            assertThat(ConstraintPolicy.DEFAULT_HARD.get(family)).contains(ConstraintPolicy.FORM_FACTOR);
        }
        assertThat(ConstraintPolicy.RELAXABLE).doesNotContain(ConstraintPolicy.FORM_FACTOR);
        ParsedQuery q = parser.parse("25W 100 ohm aluminium housed chassis mount resistor");
        assertThat(ConstraintPolicy.DEFAULTS.isRelaxable(q, ConstraintPolicy.FORM_FACTOR)).isFalse();
        assertThat(ConstraintPolicy.DEFAULTS.statedHard(q)).contains(ConstraintPolicy.FORM_FACTOR);
    }

    @Test
    void aChipResistorIsExcludedFromAChassisRequestAndAnUnknownClassStaysUnverified() {
        ParsedQuery q = parser.parse("25W 100 ohm aluminium housed chassis mount resistor");
        Part chip = RankingFixtures.mouser("PWR3014W1000JE", "Vishay", "Wirewound Resistors - SMD 100 OHMS 5%",
                "Wirewound Resistors - SMD", null, Map.of());
        Part unknown = RankingFixtures.tme("SQZ25W-100R", "ROYALOHM", "Resistor: wire-wound; 100Ω; 25W; ±5%",
                "THT resistors", null, Map.of());
        Part housed = RankingFixtures.mouser("HS25E3 100R F M145", "Arcol",
                "Wirewound Resistors - Chassis Mount 25W 100 ohm 1% Alum Housed", "Wirewound Resistors", null, Map.of());

        ConstraintPolicy.Result chipCheck = ConstraintPolicy.check(q, extractor.features(chip),
                ConstraintPolicy.DEFAULTS.hardFor(q));
        assertThat(chipCheck.conflicts()).containsExactly(ConstraintPolicy.FORM_FACTOR);
        assertThat(ranker.assess(q, chip).mismatches()).contains("form factor: chip instead of chassis");

        assertThat(ConstraintPolicy.check(q, extractor.features(unknown), ConstraintPolicy.DEFAULTS.hardFor(q))
                .conflict()).isFalse();
        assertThat(ranker.assess(q, unknown).unverified()).containsExactly(ConstraintPolicy.FORM_FACTOR);

        DeterministicRanker.Assessment a = ranker.assess(q, housed);
        assertThat(a.unverified()).isEmpty();
        assertThat(a.mismatches()).isEmpty();
        assertThat(a.match()).isEqualTo(1.0);
        assertThat(extractor.extract(housed)).containsEntry("FormFactor", "chassis").containsEntry("Power", "25W");
    }

    // ---------------------------------------------------------------- technology

    @Test
    void planarResistorsAndOhmiteTghAreThickFilm() {
        Part planar = RankingFixtures.mouser("LPS0300H4R70JB", "Ohmite",
                "Planar Resistors - Chassis Mount 300watts 4.7ohms 5% 12kV", "Planar Resistors - Chassis Mount", null,
                Map.of());
        Part tgh = RankingFixtures.mouser("TGHGV10R0JE", "Ohmite", "Thick Film Resistors - Chassis Mount 10 OHM 5%",
                "Resistors", null, Map.of());
        Part tghNoWords = RankingFixtures.mouser("TGHPV10R0KE", "Ohmite", "Resistors - SOT-227 600W 10 OHM",
                "Resistors", null, Map.of());
        assertThat(extractor.features(planar).technology()).isEqualTo(TechnologyVocabulary.THICK_FILM);
        assertThat(extractor.features(tgh).technology()).isEqualTo(TechnologyVocabulary.THICK_FILM);
        assertThat(extractor.features(tghNoWords).technology()).isEqualTo(TechnologyVocabulary.THICK_FILM);
        // "planar" in a request stays a word: no technology is read from it
        assertThat(parser.parse("planar resistor 300W 4.7 ohm").technology()).isNull();
    }

    @Test
    void tmePowerResistorsCarryNoTechnologyAndStayUnverified() {
        Part lpr = RankingFixtures.tme("LPR10R0F", "TE Connectivity", "Resistor: power; 10Ω; 20W; ±1%",
                "Power resistors", null, attrs("Type of resistor", "power", "Resistance", "10Ω", "Power", "20W"));
        assertThat(extractor.features(lpr).technology()).isNull();
        ParsedQuery q = parser.parse("thick film resistor 10 ohm 20W");
        assertThat(ranker.assess(q, lpr).unverified()).contains("technology");
    }

    // ---------------------------------------------------------------- match grading

    @Test
    void freeTextWordsRankButNeverGrade() {
        Part withWord = RankingFixtures.mouser("KAL25FB100R", "Vishay / Dale",
                "Wirewound Resistors - Chassis Mount 100Ohms 25W 1% Aluminum", "Wirewound Resistors", null, Map.of());
        Part withoutWord = RankingFixtures.mouser("KAL25FB100S", "Vishay / Dale",
                "Wirewound Resistors - Chassis Mount 100Ohms 25W 1%", "Wirewound Resistors", null, Map.of());
        ParsedQuery wordy = parser.parse("25W 100 ohm resistor aluminum zebra");
        assertThat(wordy.keywords()).contains("aluminum", "zebra");
        DeterministicRanker.Assessment a = ranker.assess(wordy, withWord);
        DeterministicRanker.Assessment b = ranker.assess(wordy, withoutWord);
        assertThat(a.match()).isEqualTo(1.0);
        assertThat(b.match()).isEqualTo(1.0);           // "zebra" and "aluminum" are not typed constraints
        assertThat(a.score()).isGreaterThan(b.score()); // but the words still rank
        assertThat(new RankingService.RankedPart(withoutWord, 0.1, b.match(), b.mismatches(), b.unverified(),
                b.isBelowSpec()).exact()).isTrue();
    }

    // ---------------------------------------------------------------- thermal fields

    @Test
    void operatingTemperatureRangeAndMaximumFromParametersAndText() {
        Part tme = RankingFixtures.tme("AHP50W-100RJ", "ARCOL/OHMITE", "Resistor: wire-wound; 100Ω; 50W",
                "Power resistors", null, attrs("Operating temperature", "-55...155°C"));
        assertThat(extractor.extract(tme)).containsEntry("OperatingTemperature", "-55...155°C")
                .containsEntry("MaxTemperature", "155°C");
        Part tmeMax = RankingFixtures.tme("X", "ACME", "Resistor: wire-wound; 100Ω; 50W", "Power resistors", null,
                attrs("Max. operating temperature", "250°C"));
        assertThat(extractor.extract(tmeMax)).containsEntry("MaxTemperature", "250°C")
                .doesNotContainKey("OperatingTemperature");
        Part lcsc = RankingFixtures.lcsc("C1", "Vishay", "LTO150F4R700JTE3",
                "-55℃~+175℃ 150W 4.7Ω Thick Film Resistor ±350ppm/℃ ±5%", "Resistors / Through Hole Resistors",
                "TO-247", Map.of());
        assertThat(extractor.extract(lcsc)).containsEntry("OperatingTemperature", "-55...175°C")
                .containsEntry("MaxTemperature", "175°C");
        Part mouser = RankingFixtures.mouser("X", "ACME", "Wirewound Resistors - 25W 100 ohm +155C", "Resistors", null,
                Map.of());
        assertThat(extractor.extract(mouser)).containsEntry("MaxTemperature", "155°C")
                .doesNotContainKey("OperatingTemperature");
    }

    // ---------------------------------------------------------------- arrays (chip code x count)

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "MLCC 10uF 25V 0805 X7R 10% | ",
            "Multilayer Ceramic Capacitors MLCC - SMD/SMT 10uF 25V 10% 0805 X5R | ",
            "Multilayer Ceramic Capacitors MLCC - SMD/SMT 1uF 16V 0603 X7S | ",
            "Resistor network 62.5mW ±5% 10kΩ 0603x4 | 4",
            "Resistor network 0402x8 10kΩ | 8"})
    void chipCodeTimesCountIsAnArrayOnlyWithoutALetterAfterIt(String description, Integer elements) {
        String family = description.contains("Resistor") ? "resistor" : "capacitor";
        Part part = RankingFixtures.part(Distributor.MOUSER, "P", "ACME", "MPN", description,
                family.equals("resistor") ? "Resistor Networks" : "Capacitors", null, 100, "0.1", Map.of(), Map.of());
        assertThat(extractor.features(part).elements()).isEqualTo(elements);
    }
}
