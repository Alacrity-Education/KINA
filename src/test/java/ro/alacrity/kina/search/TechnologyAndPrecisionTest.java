package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.ParsedQueryResponse;
import ro.alacrity.kina.domain.Part;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static ro.alacrity.kina.search.RankingFixtures.attrs;

/**
 * Leading-dot tolerances, the passive technology attribute, the package read from the part number and the match grade,
 * with the real strings of the live "Thin film resistor, 5.36k 0805 0.1%" test (2026-10-05).
 */
class TechnologyAndPrecisionTest {

    private static final String QUERY = "Thin film resistor, 5.36k 0805 0.1%";

    private final QueryParser parser = new QueryParser();
    private final ParametricExtractor extractor = new ParametricExtractor();
    private final DeterministicRanker ranker = new DeterministicRanker(extractor);

    // ---- real distributor records -----------------------------------------------------------------------------------

    static final Part LCSC_THIN = RankingFixtures.lcsc("C1854569", "Vishay Intertech", "TNPW08055K36BEEA",
            "-55℃~+155℃ 125mW 150V 5.36kΩ Thin Film Resistor ±0.1% ±25ppm/℃",
            "Resistors / Chip Resistor - Surface Mount", "0805", Map.of());
    static final Part TME_THIN = RankingFixtures.tme("ERA6AEB5361V", "PANASONIC",
            "Resistor: thin film; 5.36kΩ; SMD; 0805; 125mW; ±0.1%; ERA6AEB", "SMD resistors", "0805",
            attrs("Type of resistor", "thin film", "Resistance", "5.36kΩ", "Tolerance", "±0.1%", "Case - inch", "0805"));
    static final Part MOUSER_VISHAY = RankingFixtures.mouser("TNPW08055K36BEEA", "Vishay / Dale",
            "Thin Film Resistors - SMD 5.36Kohms .1% 25ppm", "Thin Film Resistors - SMD", null, Map.of());
    static final Part MOUSER_TE = RankingFixtures.mouser("RN73C2A5K36BTDF", "TE Connectivity",
            "Thin Film Resistors - SMD 0.1W .1% 10PPM 5.36K", "Thin Film Resistors - SMD", null, Map.of());
    static final Part MOUSER_THICK = RankingFixtures.mouser("RC0805FR-075K36L", "YAGEO",
            "Thick Film Resistors - SMD 5.36K OHM 1% 1/8W", "Thick Film Resistors - SMD", null, Map.of());

    // ---- tolerance ------------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {".1%|0.1", "±.5%|0.5", "0.1%|0.1", "±0.1%|0.1", "+/-.25%|0.25", "5%|5"})
    void toleranceAcceptsLeadingDotDecimals(String written, double percent) {
        ParsedQuery parsed = parser.parse("5.36k 0805 " + written);
        assertThat(parsed.constraint(ParsedQuery.TOLERANCE).value()).isCloseTo(percent, within(1e-9));
        assertThat(parsed.keywords()).isEmpty();
    }

    @Test
    void mouserLeadingDotToleranceIsExtracted() {
        assertThat(extractor.extract(MOUSER_VISHAY)).containsEntry("Tolerance", "0.1%")
                .containsEntry("Resistance", "5.36kohm");
        assertThat(extractor.extract(MOUSER_TE)).containsEntry("Tolerance", "0.1%").containsEntry("Power", "100mW");
        assertThat(Recognizers.value(".5k", "resistor").value()).isCloseTo(500, within(1e-9));
    }

    // ---- technology: query ----------------------------------------------------------------------------------------

    @Test
    void parsesTheLiveQuery() {
        ParsedQuery parsed = parser.parse(QUERY);
        assertThat(parsed.family()).isEqualTo("resistor");
        assertThat(parsed.technology()).isEqualTo("thin film");
        assertThat(parsed.packageName()).isEqualTo("0805");
        assertThat(parsed.constraint(ParsedQuery.RESISTANCE).display()).isEqualTo("5.36kohm");
        assertThat(parsed.constraint(ParsedQuery.TOLERANCE).display()).isEqualTo("0.1%");
        assertThat(parsed.keywords()).isEmpty();   // "thin" and "film" are the technology now
        assertThat(ParsedQueryResponse.from(parsed).technology()).isEqualTo("thin film");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "thick film 10k 0603|thick film",
            "10k 1% 0805 thin-film|thin film",
            "metal film resistor 1k|metal film",
            "carbon film resistor 1k|carbon film",
            "metal oxide resistor 2W 100R|metal oxide",
            "wire-wound resistor 5W 10R|wirewound",
            "wirewound 10R 5W|wirewound",
            "metal foil resistor 10k 0.01%|metal foil",
            "metal strip resistor 10mΩ 2512|metal strip",
            "100mΩ 2512 shunt|current sense",
            "current sense resistor 10mR 2512|current sense",
            "tantalum capacitor 10uF 16V|tantalum",
            "tantalum polymer 100uF 6.3V|tantalum polymer",
            "polymer capacitor 100uF 16V|polymer",
            "aluminium electrolytic 100uF 25V|aluminium electrolytic",
            "47uF 35V electrolytic capacitor|aluminium electrolytic",
            "film capacitor 100nF 100V|film",
            "polypropylene capacitor 100nF 400V|polypropylene",
            "MKP 100nF 400V capacitor|polypropylene",
            "PET capacitor 100nF 63V|polyester",
            "PPS film capacitor 22nF 1210|PPS",
            "supercapacitor 1F 5.5V|supercapacitor",
            "ceramic capacitor 100nF 0805|ceramic",
            "multilayer inductor 10uH 0603|multilayer",
            "wire-wound inductor 10uH|wirewound",
            "thin film inductor 2.7nH 0402|thin film",
            "10uF X7R 0805 MLCC|null",
            "4.7k 1% 0603 resistor|null",
            "thin film 0805|null",
            "female header thin film|null"})
    void parsesTechnology(String query, String technology) {
        assertThat(parser.parse(query).technology()).as(query).isEqualTo(technology);
    }

    // ---- technology: parts ------------------------------------------------------------------------------------------

    @Test
    void lcscTechnologyFromDescriptionOrCategory() {
        assertThat(extractor.extract(LCSC_THIN)).containsEntry("Technology", "thin film")
                .containsEntry("Tolerance", "0.1%").containsEntry("Package", "0805");
        Part tantalum = RankingFixtures.lcsc("C7171", "KYOCERA AVX", "TAJA106K016RNJ",
                "-55℃~+125℃ 10uF 16V 3Ω@100kHz ±10%", "Capacitors / Tantalum Capacitors", "CASE-A-3216-18(mm)",
                Map.of());
        Part pet = RankingFixtures.lcsc("C1", "KEMET", "R82EC3220DQ70J",
                "-55℃~+105℃ 100V 220nF Metallized Polyester ±5%", "Capacitors / Film Capacitors", "Plugin,P=5mm",
                Map.of());
        Part cbb = RankingFixtures.lcsc("C2", "Ltec", "MPP104J2G1305085LC", "-40℃~+85℃ 100nF 400V ±5% 插件,P=10mm",
                "Capacitors / Polypropylene Film Capacitors (CBB)", "Plugin,P=10mm", Map.of());
        Part multilayer = RankingFixtures.lcsc("C3", "FH", "VHF160808H1N8ST",
                "1.8nH 120mΩ 400mA 6GHz 8@100MHz Multilayer inductor、High-frequency inductor",
                "Inductors/Coils/Transformers / Inductors (SMD)", "0603", Map.of());
        Part sense = RankingFixtures.lcsc("C4", "UNI-ROYAL", "MS122WF450MT4E",
                "2W 45mΩ Current Sense Resistor SMD ±1% ±30ppm/℃",
                "Resistors / Current Sense Resistors / Shunt Resistors", "2512", Map.of());
        assertThat(extractor.extract(tantalum)).containsEntry("Technology", "tantalum");
        assertThat(extractor.extract(pet)).containsEntry("Technology", "polyester");
        assertThat(extractor.extract(cbb)).containsEntry("Technology", "polypropylene");
        assertThat(extractor.extract(multilayer)).containsEntry("Technology", "multilayer");
        assertThat(extractor.extract(sense)).containsEntry("Technology", "current sense");
    }

    @Test
    void tmeTechnologyFromParameters() {
        assertThat(extractor.extract(TME_THIN)).containsEntry("Technology", "thin film")
                .containsEntry("Tolerance", "0.1%").containsEntry("Package", "0805");
        Part wire = RankingFixtures.tme("DE0704-10", "FERROCORE", "Inductor: wire; SMD; 10uH; 1.84A; R: 49mΩ; ±20%",
                "Inductors", null, attrs("Type of inductor", "wire", "Inductance", "10uH"));
        Part strip = RankingFixtures.tme("WSLP2512R0100FEA", "VISHAY",
                "Resistor: metal strip; 10mΩ; current shunt,sensing; SMD; 2512; 3W", "SMD resistors", "2512",
                attrs("Kind of resistor", "current shunt, sensing", "Type of resistor", "metal strip"));
        Part tantalumPolymer = RankingFixtures.tme("T520D107M010ATE018", "KEMET",
                "Capacitor: tantalum-polymer; low ESR; 100uF; 10VDC; 100uA; D; 2917", "Polymer - tantalum capacitors",
                null, attrs("Type of capacitor", "tantalum-polymer", "Kind of capacitor", "low ESR"));
        Part supercap = RankingFixtures.tme("BCE005R5V105FS", "KYOCERA AVX", "Supercapacitor; THT; 1F; 5.5VDC; ±20%",
                "Supercapacitors", null, attrs("Type of capacitor", "supercapacitor"));
        Part electrolytic = RankingFixtures.tme("EWH1EV101E11OT", "LELON",
                "Capacitor: electrolytic; THT; 100uF; 25VDC; Ø6.3x11mm; Pitch: 2.5mm", "THT electrolytic capacitors",
                null, attrs("Type of capacitor", "electrolytic"));
        assertThat(extractor.extract(wire)).containsEntry("Technology", "wirewound");
        // the construction wins over the application ("Kind of resistor: current shunt, sensing" comes first)
        assertThat(extractor.extract(strip)).containsEntry("Technology", "metal strip");
        assertThat(extractor.extract(tantalumPolymer)).containsEntry("Technology", "tantalum polymer");
        assertThat(extractor.extract(supercap)).containsEntry("Technology", "supercapacitor");
        assertThat(extractor.extract(electrolytic)).containsEntry("Technology", "aluminium electrolytic");
    }

    @Test
    void mouserTechnologyFromCategoryAndDescription() {
        assertThat(extractor.extract(MOUSER_VISHAY)).containsEntry("Technology", "thin film");
        assertThat(extractor.extract(MOUSER_THICK)).containsEntry("Technology", "thick film");
        Part solid = RankingFixtures.mouser("TAJB106K016UNJ", "KYOCERA AVX",
                "Tantalum Capacitors - Solid SMD 16V 10uF 10% 1210 ES R = 2.8 Ohm", "Tantalum Capacitors - Solid SMD",
                null, Map.of());
        Part polymer = RankingFixtures.mouser("T521V107M016ATE050", "KEMET",
                "Tantalum Capacitors - Polymer 16V 100uF 2917 20% ESR=50mOhms", "Tantalum Capacitors - Polymer", null,
                Map.of());
        Part aluPolymer = RankingFixtures.mouser("RSS1C101MCN1GS", "Nichicon",
                "Aluminium Organic Polymer Capacitors 100UF 16V 20%", "Aluminium Organic Polymer Capacitors", null,
                Map.of());
        Part film = RankingFixtures.mouser("R82EC3100Z370J", "KEMET",
                "Film Capacitors 100V 0.1 uF 105C 5% 2 Pin LS=5 mm AEC-Q200", "Film Capacitors", null, Map.of());
        Part wirewound = RankingFixtures.mouser("PWR3014W10R0JE", "Bourns", "Wirewound Resistors - SMD 10 OHMS 5%",
                "Wirewound Resistors - SMD", null, Map.of());
        assertThat(extractor.extract(solid)).containsEntry("Technology", "tantalum");
        assertThat(extractor.extract(polymer)).containsEntry("Technology", "tantalum polymer");
        assertThat(extractor.extract(aluPolymer)).containsEntry("Technology", "aluminium polymer");
        assertThat(extractor.extract(film)).containsEntry("Technology", "film");
        assertThat(extractor.extract(wirewound)).containsEntry("Technology", "wirewound");
    }

    @Test
    void noTechnologyOutsideThePassives() {
        Part mosfet = RankingFixtures.mouser("AO3400A", "AOS", "MOSFET N-CH 30V 5.7A SOT-23 thin film", "MOSFETs",
                "SOT-23", Map.of());
        assertThat(extractor.extract(mosfet)).doesNotContainKey("Technology");
    }

    @Test
    void technologyCompatibility() {
        assertThat(TechnologyVocabulary.compare("thin film", "thin film")).isEqualTo(1);
        assertThat(TechnologyVocabulary.compare("thin film", "thick film")).isEqualTo(-1);
        assertThat(TechnologyVocabulary.compare("thin film", null)).isZero();
        assertThat(TechnologyVocabulary.compare("film", "polypropylene")).isEqualTo(1);
        assertThat(TechnologyVocabulary.compare("polypropylene", "film")).isZero();
        assertThat(TechnologyVocabulary.compare("polypropylene", "polyester")).isEqualTo(-1);
        assertThat(TechnologyVocabulary.compare("tantalum", "tantalum polymer")).isEqualTo(1);
        assertThat(TechnologyVocabulary.compare("polymer", "tantalum polymer")).isEqualTo(1);
        assertThat(TechnologyVocabulary.compare("tantalum polymer", "tantalum")).isZero();
        assertThat(TechnologyVocabulary.compare("current sense", "metal strip")).isEqualTo(1);
        assertThat(TechnologyVocabulary.compare("current sense", "thick film")).isZero();
        assertThat(TechnologyVocabulary.compare("ceramic", "tantalum")).isEqualTo(-1);
    }

    // ---- package from the part number -------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} ({1})")
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "TNPW08055K36BEEA|Vishay / Dale|0805",
            "CRCW08055K36FKEA|Vishay|0805",
            "CRCW0402100KFKED|Vishay|0402",
            "RC0805FR-075K36L|YAGEO|0805",
            "RT0805BRD075K36L|YAGEO|0805",
            "AC0603FR-0710KL|YAGEO|0603",
            "RN73C2A5K36BTDF|TE Connectivity|0805",
            "RN73C1E10KBTD|TE Connectivity|0402",
            "RN73C1J5K36BTD|TE Connectivity|0603",
            "RN73C2B10KBTD|TE Connectivity|1206",
            "C0805C106K4RACTU|KEMET|0805",
            "C0603C104K5RACTU|KEMET|0603",
            "C0603C0G1E2R7CT00NN|TDK|null",
            "CL21B106KAYQNNE|SAMSUNG|null",
            "RC0402F163CS|Samsung Electro-Mechanics|null",
            "RT0603R-182-M|SUSUMU|null",
            "ERJ-6ENF5361V|Panasonic|null",
            "ERA-6AEB5361V|Panasonic|null",
            "GRM21BR71C106KE11L|Murata|null",
            "LM0805ABC|ACME|null",
            "08055C104KAT2A|KYOCERA AVX|null"})
    void packageFromPartNumber(String mpn, String manufacturer, String expected) {
        assertThat(ParametricExtractor.packageFromPartNumber(mpn, manufacturer)).isEqualTo(expected);
    }

    @Test
    void packageFromThePartNumberOnlyWhenNothingElseStatesOne() {
        assertThat(extractor.extract(MOUSER_VISHAY)).containsEntry("Package", "0805").containsEntry("Mounting", "SMD");
        assertThat(extractor.extract(MOUSER_TE)).containsEntry("Package", "0805");
        // a package the distributor states is never overridden, recognised or not
        Part stated = RankingFixtures.mouser("TNPW08055K36BEEA", "Vishay", "Thin Film Resistors - SMD 5.36Kohms",
                "Thin Film Resistors - SMD", null, attrs("Case Code - in", "1206"));
        Part unrecognised = RankingFixtures.mouser("TNPW08055K36BEEA", "Vishay", "Thin Film Resistors - SMD 5.36Kohms",
                "Thin Film Resistors - SMD", "Mini case", Map.of());
        assertThat(extractor.extract(stated)).containsEntry("Package", "1206");
        assertThat(extractor.extract(unrecognised)).containsEntry("Package", "Mini case");
        // not for other families
        Part notPassive = RankingFixtures.mouser("RC0805-LDO", "YAGEO", "LDO regulator 3.3V", "LDO Voltage Regulators",
                null, Map.of());
        assertThat(extractor.extract(notPassive)).doesNotContainKey("Package");
    }

    // ---- ranking and match ------------------------------------------------------------------------------------------

    @Test
    void thinFilmExactOutranksThickFilmExactAndUnknownIsNotPenalised() {
        ParsedQuery q = parser.parse("thin film resistor 5.36k 0805 1%");
        Part thin = RankingFixtures.mouser("TNPW08055K36FEEA", "Vishay", "Thin Film Resistors - SMD 5.36K 1% 0805",
                "Thin Film Resistors - SMD", "0805", Map.of());
        Part thick = RankingFixtures.mouser("CRCW08055K36FKEA", "Vishay", "Thick Film Resistors - SMD 5.36K 1% 0805",
                "Thick Film Resistors - SMD", "0805", Map.of());
        Part unknown = RankingFixtures.mouser("XYZ08055K36", "ACME", "Resistors - SMD 5.36K 1% 0805", "Resistors",
                "0805", Map.of());

        DeterministicRanker.Assessment a = ranker.assess(q, thin);
        DeterministicRanker.Assessment b = ranker.assess(q, thick);
        DeterministicRanker.Assessment c = ranker.assess(q, unknown);
        assertThat(a.match()).isEqualTo(1.0);
        assertThat(b.match()).isLessThan(c.match());
        // the unknown technology is unverified: left out of match, listed, and the part is not complete
        assertThat(c.match()).isEqualTo(1.0);
        assertThat(c.unverified()).containsExactly("technology");
        assertThat(a.complete()).isTrue();
        assertThat(c.complete()).isFalse();
        // without the clamp the gap is 2 x W_TECHNOLOGY; check the raw signal through the unknown part
        assertThat(c.score() - b.score()).isCloseTo(DeterministicRanker.W_TECHNOLOGY, within(1e-9));
        assertThat(b.score()).isLessThan(c.score());
        assertThat(c.score()).isLessThanOrEqualTo(a.score());
    }

    @Test
    void theLiveMouserPartsNowMatchCompletely() {
        ParsedQuery q = parser.parse(QUERY);
        for (Part p : new Part[]{MOUSER_VISHAY, MOUSER_TE, LCSC_THIN, TME_THIN}) {
            assertThat(ranker.assess(q, p).match()).as(p.manufacturerPartNumber()).isEqualTo(1.0);
        }
        DeterministicRanker.Assessment thick = ranker.assess(q, MOUSER_THICK);
        assertThat(thick.match()).isLessThan(0.7);   // technology and tolerance mismatch
    }

    @Test
    void matchIsAbsoluteWhileScoresAreRelative() {
        ParsedQuery q = parser.parse("10uF 25V X7R 0805");
        Part full = RankingFixtures.part("FULL", "Capacitor: ceramic; MLCC; 10uF; 25V; X7R; ±10%; SMD; 0805",
                "MLCC SMD capacitors", "0805", Map.of());
        Part noVoltage = RankingFixtures.part("NOV", "Capacitor: ceramic; MLCC; 10uF; X7R; ±10%; SMD; 0805",
                "MLCC SMD capacitors", "0805", Map.of());
        assertThat(ranker.assess(q, full).match()).isEqualTo(1.0);
        // the unstated voltage is unverified: it is left out of both sides of match, which is then 1.0 but not a
        // confirmed fit (unverified is non-empty)
        DeterministicRanker.Assessment a = ranker.assess(q, noVoltage);
        assertThat(a.match()).isEqualTo(1.0);
        assertThat(a.unverified()).containsExactly("voltage");
        assertThat(a.complete()).isFalse();
        // a wrong voltage is verified and fails: (0.30 + 0.20 + 0.15 - 0.10 + 0.05) / 0.80
        Part tenVolt = RankingFixtures.part("10V", "Capacitor: ceramic; MLCC; 10uF; 10V; X7R; ±10%; SMD; 0805",
                "MLCC SMD capacitors", "0805", Map.of());
        DeterministicRanker.Assessment low = ranker.assess(q, tenVolt);
        assertThat(low.match()).isCloseTo(0.60 / 0.80, within(1e-9));
        assertThat(low.belowSpec()).containsExactly("voltage");
        assertThat(low.belowSpecDistance()).isCloseTo(Math.log(2.5), within(1e-9));
    }

    @Test
    void connectorMatchGrade() {
        ParsedQuery q = parser.parse("female header 1x6 right angle 2.54mm");
        Part exact = RankingFixtures.lcsc("C2897388", "HCTL", "PM254-1-06-W-8.5",
                "-40℃~+105℃ 1 1x6P 2.54mm 3A 6P 8.5mm Copper alloy Right Angle Side Square Hole",
                "Connectors / Female Headers", "Push-Pull,P=2.54mm", Map.of());
        assertThat(ranker.assess(q, exact).match()).isEqualTo(1.0);
    }

    // ---- phrasing ---------------------------------------------------------------------------------------------------

    @Test
    void technologyWordsInTheDistributorsSpelling() {
        ParsedQuery live = parser.parse(QUERY);
        assertThat(DistributorPhraser.phrase(Distributor.LCSC, live)).isEqualTo(
                "\"Thin Film\" resistor, 5.36k 0805 0.1%");
        assertThat(DistributorPhraser.phrase(Distributor.MOUSER, live)).isNull();   // already Mouser's words
        assertThat(DistributorPhraser.phrase(Distributor.TME, live)).isNull();

        ParsedQuery wire = parser.parse("wire-wound resistor 5W 10R");
        // the power rating is a minimum and stays out of the phrase
        assertThat(DistributorPhraser.phrase(Distributor.TME, wire)).isEqualTo("wirewound resistor 10R");
        assertThat(DistributorPhraser.phrase(Distributor.MOUSER, wire)).isEqualTo("wirewound resistor 10R");
        ParsedQuery alu = parser.parse("aluminium electrolytic 100uF 25V");
        assertThat(DistributorPhraser.phrase(Distributor.TME, alu)).isEqualTo("electrolytic 100uF");
        // LCSC checks the minimum itself (a local database): 35V and 50V parts match ">=25V"
        assertThat(DistributorPhraser.phrase(Distributor.LCSC, alu)).isEqualTo("\"Aluminum Electrolytic\" 100uF >=25V");
        // no own spelling: verbatim except the rating
        assertThat(DistributorPhraser.phrase(Distributor.MOUSER, parser.parse("tantalum 10uF 16V")))
                .isEqualTo("tantalum 10uF");
        assertThat(DistributorPhraser.phrase(Distributor.MOUSER, parser.parse("tantalum 10uF"))).isNull();
        assertThat(DistributorPhraser.phrase(Distributor.LCSC, parser.parse("4.7k 1% 0603 resistor"))).isNull();
    }
}
