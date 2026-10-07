package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.ConstraintKind;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.ParsedQueryResponse;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PriceBreak;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Units whose case carries meaning, labelled values (lifetime, impedance, rated and saturation current, DCR,
 * temperature), strict constraints, minimum ratings in phrases and ranking, the relaxation ladder and the quantity
 * (DESIGN.md 3.2 and 3.4).
 */
class AuditFindingsTest {

    private final QueryParser parser = new QueryParser();
    private final ParametricExtractor extractor = new ParametricExtractor();
    private final DeterministicRanker ranker = new DeterministicRanker(extractor);

    private Map<String, String> parsed(String query) {
        return ParsedQueryResponse.from(parser.parse(query)).constraints();
    }

    // ---- units and labelled values in queries ---------------------------------------------------------------------

    @Test
    void hoursAreNotHenryAndMilliIsNotMega() {
        assertThat(parsed("100uF 63V 2000h electrolytic")).containsEntry("lifetime", "2000h")
                .doesNotContainKey("inductance");
        assertThat(parsed("100uF 63V 5000 hours")).containsEntry("lifetime", "5000h");
        // an upper-case H is a henry value, except on a capacitor (Mouser "105C 3000H")
        assertThat(parsed("1H choke")).containsEntry("inductance", "1H");
        assertThat(parsed("100uF capacitor 105C 3000H")).containsEntry("lifetime", "3000h")
                .containsEntry("temperature", "105°C").doesNotContainKey("inductance");
        // prefixed: always henry, either case
        assertThat(parsed("10uh inductor")).containsEntry("inductance", "10uH");
        assertThat(parser.parse("10mA").constraint(ParsedQuery.CURRENT).value()).isCloseTo(0.01, within(1e-12));
        assertThat(parser.parse("10MA").constraint(ParsedQuery.CURRENT).value()).isCloseTo(1e7, within(1e-3));
        assertThat(parser.parse("10mohm").constraint(ParsedQuery.RESISTANCE).value()).isCloseTo(0.01, within(1e-12));
        assertThat(parser.parse("10Mohm").constraint(ParsedQuery.RESISTANCE).value()).isCloseTo(1e7, within(1e-3));
        assertThat(parser.parse("4.7mH inductor").constraint(ParsedQuery.INDUCTANCE).value())
                .isCloseTo(4.7e-3, within(1e-12));
        // the one tolerated spelling: millihertz never occurs in component data
        assertThat(parsed("100mhz crystal")).containsEntry("frequency", "100MHz");
        assertThat(parsed("10uF 25 V 105°C 2000h")).containsEntry("voltage", "25V")
                .containsEntry("temperature", "105°C").containsEntry("lifetime", "2000h");
    }

    @Test
    void ferriteImpedanceInductorCurrentsAndDcrInQueries() {
        assertThat(parsed("120 ohm 100MHz 0603 ferrite bead")).containsExactlyEntriesOf(
                RankingFixtures.attrs("impedance", "120ohm @100MHz"));
        ParsedQuery q = parser.parse("120 ohm 100MHz 0603 ferrite bead");
        assertThat(q.constraint(ParsedQuery.IMPEDANCE).condition()).isEqualTo(100e6);

        ParsedQuery inductor = parser.parse("power inductor 3.3uH 6A SMD shielded low DCR");
        assertThat(inductor.constraint(ParsedQuery.CURRENT).display()).isEqualTo("6A");
        assertThat(inductor.constraint(ParsedQuery.SATURATION_CURRENT)).isNull();
        assertThat(inductor.prefers(ParsedQuery.LOW_DCR)).isTrue();
        assertThat(inductor.keywords()).containsExactly("power", "shielded");

        assertThat(parsed("inductor 4.7uH Isat 8A DCR < 20mΩ")).containsEntry("saturation_current", "8A")
                .containsEntry("dcr", "20mohm").doesNotContainKey("current");
        assertThat(parsed("4.7uH 8A saturation current DCR 20mΩ max")).containsEntry("saturation_current", "8A")
                .containsEntry("dcr", "20mohm");
        assertThat(parsed("4.7uH inductor 3A Isat 5A")).containsEntry("current", "3A")
                .containsEntry("saturation_current", "5A");
    }

    @Test
    void saturationCurrentInWords() {
        ParsedQuery q = parser.parse("2.2uH saturation current 10A");
        assertThat(q.constraint(ParsedQuery.SATURATION_CURRENT).display()).isEqualTo("10A");
        assertThat(q.constraint(ParsedQuery.CURRENT)).isNull();
        assertThat(q.keywords()).isEmpty();
    }

    // ---- part attributes ------------------------------------------------------------------------------------------

    @Test
    void lifetimeIsNeverInductance() {
        Part samwha = RankingFixtures.tme("RC1C107M6L006VR", "SAMWHA",
                "Capacitor: electrolytic; SMD; 100uF; 16VDC; Ø6.3x5.8mm; ±20%; 1000h", "SMD electrolytic capacitors",
                null, RankingFixtures.attrs("Type of capacitor", "electrolytic", "Capacitance", "100µF",
                        "Operating voltage", "16V DC", "Service life", "1000h", "Operating temperature", "-55...105°C",
                        "Mounting", "SMD"));
        assertThat(extractor.extract(samwha)).containsEntry("Lifetime", "1000h").containsEntry("MaxTemperature", "105°C")
                .containsEntry("Voltage", "16V").doesNotContainKey("Inductance");

        Part mouser = RankingFixtures.mouser("MAL215097803E3", "Vishay",
                "Aluminium Electrolytic Capacitors - SMD 100UF 63V 105C 3000H", "Aluminium Electrolytic Capacitors - SMD",
                null, Map.of());
        assertThat(extractor.extract(mouser)).containsEntry("Lifetime", "3000h").containsEntry("MaxTemperature", "105°C")
                .containsEntry("Mounting", "SMD").doesNotContainKey("Inductance");

        Part lcsc = RankingFixtures.lcsc("C487384", "Lelon", "VKMD1001J101MV",
                "-55℃~+105℃ 10000hrs@105℃ 100uF 10mm 63V 8mm 902mA@100kHz ±20%",
                "Capacitors / Aluminum Electrolytic Capacitors - SMD", "SMD,D8xL10mm", Map.of());
        // KEMET truncates the voltage at Mouser: "10Volt", "6.3Vo", "10Vol", "10 Vo"
        for (String volts : List.of("10Volt", "10Vol", "10 Vo")) {
            Part kemet = RankingFixtures.mouser("C1206C226K8RACTU", "KEMET",
                    "Multilayer Ceramic Capacitors MLCC - SMD/SMT " + volts + " 22uF X7R 1206 10%",
                    "Multilayer Ceramic Capacitors MLCC - SMD/SMT", null, Map.of());
            assertThat(extractor.extract(kemet)).as(volts).containsEntry("Voltage", "10V");
        }
        assertThat(extractor.extract(lcsc)).containsEntry("Lifetime", "10000h @105°C")
                .containsEntry("MaxTemperature", "105°C").containsEntry("Mounting", "SMD")
                .doesNotContainKey("Current");   // 902mA@100kHz is the ripple current, not a rating
        for (Part p : List.of(samwha, mouser, lcsc)) {
            assertThat(extractor.extract(extractor.enrich(p))).as(p.manufacturerPartNumber())
                    .isEqualTo(extractor.extract(p));
        }
    }

    @Test
    void ferriteBeadsHaveImpedanceAndDcrButNoResistance() {
        Part lcsc = RankingFixtures.lcsc("C85831", "Murata", "BLM18KG121TN1D", "1 120Ω@100MHz 30mΩ 3A ±25%",
                "Filters/EMI Optimization / Ferrite Beads", "0603", Map.of());
        assertThat(extractor.extract(lcsc)).containsEntry("Impedance", "120ohm @100MHz")
                .containsEntry("DCR", "30mohm").containsEntry("RatedCurrent", "3A")
                .doesNotContainKeys("Resistance", "Current");
        // extracting again from the enriched part (cached parts carry the canonical keys) gives the same values
        assertThat(extractor.extract(extractor.enrich(lcsc))).isEqualTo(extractor.extract(lcsc));

        Part tme = RankingFixtures.tme("LCBA-121", "FERROCORE",
                "Ferrite: bead; Imp.@ 100MHz: 120Ω; SMD; 2.5A; 0603; R: 100mΩ", "Ferrite beads", "0603",
                RankingFixtures.attrs("Type of ferrite", "bead", "Impedance at 100MHz", "120Ω", "Operating current",
                        "2.5A", "Resistance", "0.1Ω", "Case - inch", "0603"));
        assertThat(extractor.extract(tme)).containsEntry("Impedance", "120ohm @100MHz").containsEntry("DCR", "100mohm")
                .containsEntry("RatedCurrent", "2.5A").doesNotContainKeys("Resistance", "Current");

        Part mouser = RankingFixtures.mouser("MI0603M121R-10", "Laird",
                "Ferrite Beads 120ohms 100MHz 2.5A Monolithic 0603 SMD", "Ferrite Beads", null, Map.of());
        assertThat(extractor.extract(mouser)).containsEntry("Impedance", "120ohm @100MHz")
                .containsEntry("RatedCurrent", "2.5A").doesNotContainKeys("Resistance", "Frequency");
        assertThat(extractor.extract(extractor.enrich(mouser))).isEqualTo(extractor.extract(mouser));
        assertThat(extractor.extract(extractor.enrich(tme))).isEqualTo(extractor.extract(tme));
    }

    @Test
    void tmeFerriteAndInductorParametersAsVerifiedLive() {
        Part bead = RankingFixtures.tme("BLM31KN121SN1L", "MURATA", "Ferrite: bead; 120Ω; SMD; 4A; 1206", "Ferrites",
                "1206", RankingFixtures.attrs("Type of ferrite", "bead", "Impedance at 100MHz", "120Ω",
                        "Operating current", "4A", "Resistance", "9mΩ", "Case - inch", "1206"));
        assertThat(extractor.extract(bead)).containsEntry("Impedance", "120ohm @100MHz").containsEntry("DCR", "9mohm")
                .containsEntry("RatedCurrent", "4A").doesNotContainKey("Resistance");
        ParsedQuery q = parser.parse("120 ohm 100MHz 1206 ferrite bead");
        assertThat(ranker.assess(q, bead).match()).isEqualTo(1.0);   // the 9 mΩ DCR is never compared with 120 Ω

        Part ihlp = RankingFixtures.tme("IHLP2525CZER2R2M01", "VISHAY", "Inductor: wire; SMD; 2.2uH; 8A; R: 18mΩ",
                "SMD power inductors", null, RankingFixtures.attrs("Inductance", "2.2µH", "Operating current", "8A",
                        "Resistance", "18mΩ", "Saturation current", "", "Inductor features",
                        "high current, low DCR/uH value, shielded"));
        Part hcma = RankingFixtures.tme("HCMA0703-2R2-R", "EATON", "Inductor: wire; SMD; 2.2uH; 8A; R: 18mΩ",
                "SMD power inductors", null, RankingFixtures.attrs("Inductance", "2.2µH", "Operating current", "8A",
                        "Saturation current", "14A", "Resistance", "18mΩ"));
        assertThat(extractor.extract(ihlp)).containsEntry("RatedCurrent", "8A").containsEntry("DCR", "18mohm")
                .doesNotContainKeys("SaturationCurrent", "Resistance");
        ParsedQuery isat = parser.parse("2.2uH saturation current 10A");
        // I_sat is compared with I_sat only: unknown is unverified (not in match), the 8A rated current is no mismatch
        assertThat(ranker.assess(isat, hcma).match()).isEqualTo(1.0);
        assertThat(ranker.assess(isat, ihlp).unverified()).containsExactly("saturation current");
        assertThat(ranker.assess(isat, ihlp).complete()).isFalse();
        assertThat(ranker.assess(isat, hcma).complete()).isTrue();
        assertThat(ranker.score(isat, hcma)).isGreaterThan(ranker.score(isat, ihlp));
        assertThat(DeterministicRanker.mismatches(isat, extractor.features(ihlp))).isEmpty();
    }

    @Test
    void inductorsSeparateRatedAndSaturationCurrentAndDcr() {
        Part tme = RankingFixtures.tme("HCM0703-3R3-R", "EATON",
                "Inductor: wire; SMD; 3.3uH; Ioper: 6A; R: 28mΩ; ±20%; HCM0703", "SMD power inductors", null,
                RankingFixtures.attrs("Type of inductor", "wire", "Inductance", "3.3µH", "Operating current", "6A",
                        "Saturation current", "13.5A", "Resistance", "28mΩ", "Mounting", "SMD"));
        assertThat(extractor.extract(tme)).containsEntry("RatedCurrent", "6A")
                .containsEntry("SaturationCurrent", "13.5A").containsEntry("DCR", "28mohm")
                .doesNotContainKeys("Resistance", "Current");

        // JLCPCB lists rated and saturation current without labels: the lower one is the (conservative) rated one
        Part lcsc = RankingFixtures.lcsc("C207839", "Sunlord", "SLO0630H3R3MTT",
                "12A 24mΩ 3.3uH 6A Magnetic Shielded Inductor ±20%", "Inductors/Coils/Transformers / Power Inductors",
                "SMD,7.3x6.6mm", Map.of());
        assertThat(extractor.extract(lcsc)).containsEntry("RatedCurrent", "6A").containsEntry("DCR", "24mohm")
                .doesNotContainKeys("Resistance", "SaturationCurrent");

        Part mouser = RankingFixtures.mouser("744311330", "Wurth",
                "Power Inductors - SMD WE-HCI 3.3uH 6.5A DCR=17.2mOhms AEC-Q200", "Power Inductors - SMD", null,
                Map.of());
        assertThat(extractor.extract(mouser)).containsEntry("RatedCurrent", "6.5A").containsEntry("DCR", "17.2mohm")
                .containsEntry("Inductance", "3.3uH");
        Part isat = RankingFixtures.mouser("X", "ACME", "Power Inductor 3.3uH Isat 9A Irms 6A DCR 12mOhm",
                "Power Inductors - SMD", null, Map.of());
        assertThat(extractor.extract(isat)).containsEntry("SaturationCurrent", "9A").containsEntry("RatedCurrent", "6A")
                .containsEntry("DCR", "12mohm");
        for (Part p : List.of(tme, lcsc, mouser, isat)) {
            assertThat(extractor.extract(extractor.enrich(p))).as(p.manufacturerPartNumber())
                    .isEqualTo(extractor.extract(p));
        }
    }

    // ---- ranking ----------------------------------------------------------------------------------------------------

    private static Part inductor(String number, String description) {
        return RankingFixtures.mouser(number, "ACME", description, "Power Inductors - SMD", null, Map.of());
    }

    @Test
    void inductorRatingsDcrLimitAndLowDcrPreference() {
        ParsedQuery q = parser.parse("power inductor 3.3uH 6A SMD shielded low DCR");
        double lowDcr = ranker.score(q, inductor("A", "Power Inductors - SMD 3.3uH 6A DCR=10mOhms shielded"));
        double highDcr = ranker.score(q, inductor("B", "Power Inductors - SMD 3.3uH 6A DCR=40mOhms shielded"));
        double stronger = ranker.score(q, inductor("C", "Power Inductors - SMD 3.3uH 8A DCR=40mOhms shielded"));
        double weaker = ranker.score(q, inductor("D", "Power Inductors - SMD 3.3uH 4A DCR=10mOhms shielded"));
        assertThat(lowDcr).isGreaterThan(highDcr);
        assertThat(highDcr).isGreaterThan(stronger);   // 8A satisfies 6A, the exact rating is preferred slightly
        assertThat(stronger).isGreaterThan(weaker);     // 4A does not
        assertThat(ranker.assess(q, inductor("C", "Power Inductors - SMD 3.3uH 8A DCR=40mOhms shielded")).match())
                .isGreaterThan(ranker.assess(q, inductor("D", "Power Inductors - SMD 3.3uH 4A DCR=10mOhms shielded"))
                        .match());

        ParsedQuery limit = parser.parse("inductor 3.3uH Isat 8A DCR < 20mΩ");
        double ok = ranker.score(limit, inductor("E", "Power Inductor 3.3uH Isat 9A DCR 15mOhm"));
        double tooHigh = ranker.score(limit, inductor("F", "Power Inductor 3.3uH Isat 9A DCR 25mOhm"));
        double saturates = ranker.score(limit, inductor("G", "Power Inductor 3.3uH Isat 6A DCR 15mOhm"));
        assertThat(ok).isGreaterThan(tooHigh).isGreaterThan(saturates - 1e-9);
        assertThat(ok - tooHigh).isCloseTo(ConstraintKind.VOLTAGE_RATING.weight(), within(1e-9));
    }

    @Test
    void ferriteImpedanceIsThePrimaryValueAtItsTestFrequency() {
        ParsedQuery q = parser.parse("120 ohm 100MHz 0603 ferrite bead");
        Part same = RankingFixtures.mouser("A", "ACME", "Ferrite Beads 120ohms 100MHz 2A 0603", "Ferrite Beads", null,
                Map.of());
        Part otherFrequency = RankingFixtures.mouser("B", "ACME", "Ferrite Beads 120ohms 1MHz 2A 0603",
                "Ferrite Beads", null, Map.of());
        Part other = RankingFixtures.mouser("C", "ACME", "Ferrite Beads 600ohms 100MHz 2A 0603", "Ferrite Beads",
                null, Map.of());
        assertThat(ranker.assess(q, same).match()).isEqualTo(1.0);
        assertThat(ranker.assess(q, otherFrequency).match()).isLessThan(0.5);
        assertThat(ranker.assess(q, other).match()).isLessThan(0.5);
        assertThat(ranker.score(q, same)).isGreaterThan(ranker.score(q, otherFrequency) + 0.4)
                .isGreaterThan(ranker.score(q, other) + 0.4);
    }

    private static Part capacitor(String number, String description, String category, String packageName) {
        return RankingFixtures.part(Distributor.LCSC, number, "ACME", number, description, category, packageName,
                1000, "0.10", Map.of(), Map.of());
    }

    @Test
    void mountingAndTechnologyAreStrict() {
        List<String> strict = List.of("mounting", "technology");
        ParsedQuery q = parser.parse("100uF 16V polymer aluminium capacitor SMD");
        Part smd = capacitor("A", "-55℃~+105℃ 2000hrs@105℃ 100uF 16V Polarized Polymer ±20%",
                "Capacitors / Polymer Aluminum Capacitors", "SMD,D6.3xL5.8mm");
        Part tht = capacitor("B", "-55℃~+105℃ 2000hrs 100uF 16V ±20% 插件,D6.3xL8mm",
                "Capacitors / Polymer Aluminum Capacitors", "插件,D6.3xL8mm");
        Part tantalumPolymer = RankingFixtures.mouser("T521", "KEMET", "Tantalum Capacitors - Polymer 16V 100uF 20% SMD",
                "Tantalum Capacitors - Polymer", null, Map.of());
        Part electrolytic = RankingFixtures.mouser("EEE", "Panasonic", "Aluminium Electrolytic Capacitors - SMD 100uF 16V",
                "Aluminium Electrolytic Capacitors - SMD", null, Map.of());
        Part unknownMounting = RankingFixtures.mouser("RSS", "Nichicon", "Aluminium Organic Polymer Capacitors 100UF 16V",
                "Aluminium Organic Polymer Capacitors", null, Map.of());
        assertThat(ranker.check(q, smd, strict)).isEqualTo(DeterministicRanker.ConstraintCheck.MATCH);
        assertThat(ranker.check(q, tht, strict)).isEqualTo(DeterministicRanker.ConstraintCheck.CONFLICT);
        assertThat(ranker.check(q, tantalumPolymer, strict)).isEqualTo(DeterministicRanker.ConstraintCheck.CONFLICT);
        assertThat(ranker.check(q, electrolytic, strict)).isEqualTo(DeterministicRanker.ConstraintCheck.CONFLICT);
        assertThat(ranker.check(q, unknownMounting, strict)).isEqualTo(DeterministicRanker.ConstraintCheck.UNKNOWN);
        assertThat(ranker.check(q, tht, List.of())).isEqualTo(DeterministicRanker.ConstraintCheck.MATCH);
        // TME says "polymer" in the parameter and OS-CON in the description: aluminium polymer
        Part oscon = RankingFixtures.tme("25SVF100M", "PANASONIC",
                "Capacitor: polymer; 100uF; 25VDC; SMD; OS-CON SVF; ±20%; Ø8x6.9mm", "Polymer capacitors", null,
                RankingFixtures.attrs("Type of capacitor", "polymer", "Mounting", "SMD"));
        assertThat(extractor.extract(oscon)).containsEntry("Technology", "aluminium polymer");
        assertThat(ranker.check(q, oscon, strict)).isEqualTo(DeterministicRanker.ConstraintCheck.MATCH);
        // a bare "polymer" accepts aluminium and tantalum polymer
        ParsedQuery polymer = parser.parse("100uF 16V polymer capacitor SMD");
        assertThat(ranker.check(polymer, tantalumPolymer, strict)).isEqualTo(DeterministicRanker.ConstraintCheck.MATCH);
        assertThat(ranker.check(polymer, smd, strict)).isEqualTo(DeterministicRanker.ConstraintCheck.MATCH);
    }

    private RankingService rankingService(String... properties) {
        KinaProperties props = RankingFixtures.properties(properties);
        return new RankingService(props, ranker, new RankingServiceTest.FakeRanker(),
                () -> RankingServiceTest.READY, new RankingScoreCache(props.ranking().scoreCacheTtl()));
    }

    @Test
    void contradictingPartsAreExcludedAndUnknownOnesRankLast() {
        ParsedQuery q = parser.parse("100uF 16V polymer aluminium capacitor SMD");
        Part unknown = RankingFixtures.part(Distributor.MOUSER, "U", "Nichicon", "U",
                "Aluminium Organic Polymer Capacitors 100UF 16V 20%", "Aluminium Organic Polymer Capacitors", null,
                900_000, "0.05", Map.of(), Map.of());
        Part smd = RankingFixtures.part(Distributor.MOUSER, "S", "Panasonic", "S",
                "Aluminium Organic Polymer Capacitors SMD 100UF 16V 20%", "Aluminium Organic Polymer Capacitors", null,
                10, "0.50", Map.of(), Map.of());
        Part tht = RankingFixtures.part(Distributor.MOUSER, "T", "Panasonic", "T",
                "Aluminium Organic Polymer Capacitors Radial 100UF 16V 20%", "Aluminium Organic Polymer Capacitors",
                null, 900_000, "0.05", Map.of(), Map.of());
        Map<Distributor, List<Part>> fetched = new EnumMap<>(Distributor.class);
        fetched.put(Distributor.MOUSER, List.of(unknown, tht, smd));

        for (RankingService service : List.of(rankingService(),
                rankingService("kina.ranking.cross-encoder.enabled", "false"))) {
            RankingService.RankedResults results = service.rank(q, fetched, Duration.ofSeconds(5));
            List<RankingService.RankedPart> ranked = results.byDistributor().get(Distributor.MOUSER);
            assertThat(ranked).extracting(r -> r.part().distributorPartNumber()).containsExactly("S", "U");
            assertThat(results.excludedBy(Distributor.MOUSER)).isEqualTo(1);
            assertThat(ranked.get(0).score()).isGreaterThanOrEqualTo(ranked.get(1).score());
        }
        // a family whose hard constraints leave out the mounting excludes nothing
        RankingService.RankedResults lenient = rankingService("kina.search.hard-constraints.capacitor", "value")
                .rank(q, fetched, Duration.ofSeconds(5));
        assertThat(lenient.excludedBy(Distributor.MOUSER)).isZero();
        assertThat(lenient.byDistributor().get(Distributor.MOUSER)).hasSize(3);
        // the deprecated strict-constraints still turns the mounting off (with a warning)
        RankingService.RankedResults legacy = rankingService("kina.search.strict-constraints", "technology")
                .rank(q, fetched, Duration.ofSeconds(5));
        assertThat(legacy.excludedBy(Distributor.MOUSER)).isZero();
    }

    @Test
    void quantityRanksShortStockAndLargeMinimumOrdersLower() {
        ParsedQuery q = parser.parse("100nF X7R 0603 50V MLCC");
        String description = "MLCC 100nF 50V X7R 0603 10%";
        Part twoInStock = new Part(Distributor.TME, "TWO", "ACME", "TWO", description, null, "0603", 2, 1, 1,
                List.of(new PriceBreak(1, new BigDecimal("0.01"), "EUR")), null, null, null, Map.of(), Map.of(),
                RankingFixtures.FETCHED);
        Part reel = new Part(Distributor.TME, "REEL", "Samsung", "REEL", description, null, "0603", 500_000, 10_000,
                10_000, List.of(new PriceBreak(10_000, new BigDecimal("0.002"), "EUR")), null, null, null, Map.of(),
                Map.of(), RankingFixtures.FETCHED);
        Part plenty = new Part(Distributor.TME, "OK", "ACME", "OK", description, null, "0603", 400, 1, 1,
                List.of(new PriceBreak(1, new BigDecimal("0.02"), "EUR")), null, null, null, Map.of(), Map.of(),
                RankingFixtures.FETCHED);
        Map<Distributor, List<Part>> fetched = new EnumMap<>(Distributor.class);
        fetched.put(Distributor.TME, List.of(twoInStock, reel, plenty));
        RankingService service = rankingService("kina.ranking.cross-encoder.enabled", "false");

        assertThat(service.rank(q, fetched, null, 25).byDistributor().get(Distributor.TME))
                .extracting(r -> r.part().distributorPartNumber()).containsExactly("OK", "REEL", "TWO");
        // one piece: the 10 000-piece minimum order costs the reel its full MOQ penalty, the 2-piece part the
        // low-stock penalty (before, a quantity of 1 changed nothing and the reel ranked first)
        assertThat(service.rank(q, fetched, null, 1).byDistributor().get(Distributor.TME).getFirst().part()
                .distributorPartNumber()).isEqualTo("OK");
        // moqWeight * min(1, log10(moq / quantity) / 3): complete at 1000x, also for one piece
        assertThat(DeterministicRanker.quantityPenalty(reel, 1, 0.3, 0.15)).isCloseTo(0.15, within(1e-9));
        assertThat(DeterministicRanker.quantityPenalty(reel, 100, 0.3, 0.15)).isCloseTo(0.10, within(1e-9));
        assertThat(DeterministicRanker.quantityPenalty(plenty, 1, 0.3, 0.15)).isZero();
        assertThat(DeterministicRanker.quantityPenalty(twoInStock, 25, 0.3, 0.15)).isCloseTo(0.3, within(1e-9));
    }

    @Test
    void mismatchesNameEverySubstitution() {
        ParsedQuery q = parser.parse("22uF X7R 1206 25V MLCC");
        Part x5r1210 = capacitor("A", "16V 22uF X5R ±10%", "Capacitors / Multilayer Ceramic Capacitors MLCC - SMD/SMT",
                "1210");
        assertThat(ranker.assess(q, x5r1210).mismatches()).containsExactly("package: 1210 instead of 1206",
                "dielectric: X5R instead of X7R", "voltage: 16V below 25V");
        Part exact = capacitor("B", "50V 22uF X7R ±10%", "Capacitors / Multilayer Ceramic Capacitors MLCC - SMD/SMT",
                "1206");
        assertThat(ranker.assess(q, exact).mismatches()).isEmpty();   // 50V satisfies the 25V minimum
        assertThat(ranker.assess(q, exact).match()).isEqualTo(1.0);
        assertThat(ranker.assess(parser.parse("10uF 1% 0805"), capacitor("C", "10uF 25V X5R ±10%", "Capacitors",
                "0805")).mismatches()).containsExactly("tolerance: 10% instead of 1%");
    }

    @Test
    void lastTimeBuyAndSupplyConstrainedPartsRankLower() {
        ParsedQuery q = parser.parse("100nF X7R 0603 50V MLCC");
        Part active = RankingFixtures.part(Distributor.TME, "ACTIVE", "ACME", "ACTIVE", "MLCC 100nF 50V X7R 0603",
                null, "0603", 1000, "0.01", Map.of(), Map.of("product_status", List.of()));
        Part lastBuy = RankingFixtures.part(Distributor.TME, "LTB", "ACME", "LTB", "MLCC 100nF 50V X7R 0603",
                null, "0603", 1000, "0.01", Map.of(), Map.of("product_status", List.of("AVAILABLE_WHILE_STOCKS_LAST")));
        Part hardly = RankingFixtures.part(Distributor.TME, "HARD", "ACME", "HARD", "MLCC 100nF 50V X7R 0603",
                null, "0603", 1000, "0.01", Map.of(), Map.of("product_status", List.of("HARDLY_AVAILABLE")));
        Map<Distributor, List<Part>> fetched = new EnumMap<>(Distributor.class);
        fetched.put(Distributor.TME, List.of(lastBuy, hardly, active));
        assertThat(rankingService("kina.ranking.cross-encoder.enabled", "false").rank(q, fetched, null)
                .byDistributor().get(Distributor.TME)).extracting(r -> r.part().distributorPartNumber())
                .containsExactly("ACTIVE", "HARD", "LTB");
    }

    @Test
    void twoPiecesInStockNeverRankFirstForTenWhenOthersCanSupply() {
        ParsedQuery q = parser.parse("power inductor 2.2uH 8A SMD shielded");
        Part ihlp = RankingFixtures.part(Distributor.TME, "IHLP2525CZER2R2M01", "VISHAY", "IHLP2525CZER2R2M01",
                "Inductor: wire; SMD; 2.2uH; 8A; R: 18mΩ; shielded", "SMD power inductors", null, 2, "1.20",
                Map.of(), Map.of());
        Part hcma = RankingFixtures.part(Distributor.TME, "HCMA1104-2R2-R", "EATON", "HCMA1104-2R2-R",
                "Inductor: wire; SMD; 2.2uH; 11A; R: 7mΩ", "SMD power inductors", null, 300, "0.90", Map.of(),
                Map.of());
        Part weak = RankingFixtures.part(Distributor.TME, "HCMA0703-2R2-R", "EATON", "HCMA0703-2R2-R",
                "Inductor: wire; SMD; 2.2uH; 6A; R: 25mΩ", "SMD power inductors", null, 300, "0.90", Map.of(),
                Map.of());
        Map<Distributor, List<Part>> fetched = new EnumMap<>(Distributor.class);
        fetched.put(Distributor.TME, List.of(ihlp, weak, hcma));
        for (RankingService service : List.of(rankingService(),
                rankingService("kina.ranking.cross-encoder.enabled", "false"))) {
            for (int quantity : new int[]{10, 1}) {
                // two pieces cannot supply ten (tier) and are low stock for one (penalty): the 11 A part ranks first;
                // the 6 A part is below the 8 A request and left out
                RankingService.RankedResults r = service.rank(q, fetched, Duration.ofSeconds(5), quantity);
                assertThat(r.byDistributor().get(Distributor.TME)).extracting(p -> p.part().distributorPartNumber())
                        .containsExactly("HCMA1104-2R2-R", "IHLP2525CZER2R2M01");
                assertThat(r.excludedBelowSpecBy(Distributor.TME)).isEqualTo(1);
            }
        }
    }

    // ---- phrases and the relaxation ladder --------------------------------------------------------------------------

    @Test
    void ratingsNeverReachADistributorPhrase() {
        ParsedQuery mlcc = parser.parse("22uF X7R 1206 25V MLCC");
        assertThat(DistributorPhraser.phrase(Distributor.TME, mlcc)).isEqualTo("22uF X7R 1206 MLCC");
        assertThat(DistributorPhraser.phrase(Distributor.MOUSER, mlcc)).isEqualTo("22uF X7R 1206 MLCC");
        // LCSC (a local database) checks the minimum exactly instead
        assertThat(DistributorPhraser.phrase(Distributor.LCSC, mlcc)).isEqualTo("22uF X7R 1206 MLCC >=25V");
        assertThat(DistributorPhraser.phrase(Distributor.TME, parser.parse("100uF 16V polymer aluminium capacitor SMD")))
                .isEqualTo("100uF polymer capacitor SMD");
        assertThat(DistributorPhraser.phrase(Distributor.LCSC, parser.parse("100uF 16V polymer aluminium capacitor SMD")))
                .isEqualTo("100uF \"Polymer Aluminum\" capacitor SMD >=16V");
        assertThat(DistributorPhraser.phrase(Distributor.MOUSER,
                parser.parse("power inductor 3.3uH 6A SMD shielded low DCR")))
                .isEqualTo("power inductor 3.3uH SMD shielded");
        assertThat(DistributorPhraser.phrase(Distributor.TME, parser.parse("inductor 4.7uH Isat 8A DCR < 20mΩ")))
                .isEqualTo("inductor 4.7uH");
        assertThat(DistributorPhraser.phrase(Distributor.MOUSER, parser.parse("2.2uH saturation current 10A")))
                .isEqualTo("2.2uH");
        assertThat(DistributorPhraser.phrase(Distributor.MOUSER, parser.parse("MOSFET 30V"))).isNull();
        assertThat(DistributorPhraser.phrase(Distributor.MOUSER, parser.parse("10 uF 25 V 105°C 2000h capacitor")))
                .isEqualTo("10 uF capacitor");
        // specifications, not ratings: kept
        assertThat(DistributorPhraser.phrase(Distributor.TME, parser.parse("LDO 3.3V SOT-23-5"))).isNull();
        assertThat(DistributorPhraser.phrase(Distributor.TME, parser.parse("fuse 2A 1206"))).isNull();
        assertThat(DistributorPhraser.phrase(Distributor.TME, parser.parse("Zener 5.1V SOD-123"))).isNull();
        // a package code next to a rating is not a number with a unit
        assertThat(DistributorPhraser.phrase(Distributor.TME, parser.parse("100nF 0603 50V"))).isEqualTo("100nF 0603");
    }

    @Test
    void relaxationLadderDropsRatingsThenDielectricThenPackageThenTolerance() {
        ParsedQuery q = parser.parse("22uF X7R 1206 25V 10% MLCC");
        // the core "MLCC 22uF X7R 1206 10%" has the words of the rating-free phrase and is skipped; the package of a
        // capacitor is hard and stays in every phrase (DESIGN.md 3.4)
        assertThat(DistributorPhraser.ladder(Distributor.TME, q, q.originalText())).containsExactly(
                new DistributorPhraser.Relaxation("22uF X7R 1206 10% MLCC", List.of()),
                new DistributorPhraser.Relaxation("MLCC 22uF 1206 10%", List.of("dielectric")),
                new DistributorPhraser.Relaxation("MLCC 22uF 1206", List.of("dielectric", "tolerance")));
        String sent = DistributorPhraser.phrase(Distributor.MOUSER, q);
        assertThat(DistributorPhraser.relaxations(Distributor.MOUSER, q, sent)).containsExactly(
                "MLCC 22uF 1206 10%", "MLCC 22uF 1206");
        ParsedQuery same = parser.parse("22uF X7R 1206 25V MLCC");
        assertThat(DistributorPhraser.relaxations(Distributor.TME, same, DistributorPhraser.phrase(Distributor.TME, same)))
                .containsExactly("MLCC 22uF 1206");
        // an inductor's package is relaxable: dielectric (none), then the package, then the tolerance
        ParsedQuery inductor = parser.parse("10uH 20% inductor 0805 1A");
        assertThat(DistributorPhraser.ladder(Distributor.TME, inductor, "10uH 20% inductor 0805")).containsExactly(
                new DistributorPhraser.Relaxation("inductor 10uH 20%", List.of("package")),
                new DistributorPhraser.Relaxation("inductor 10uH", List.of("package", "tolerance")));
        // a reworded core with the same constraints loosens nothing
        ParsedQuery mosfet = parser.parse("SOT-23 N-channel MOSFET 30V");
        assertThat(DistributorPhraser.ladder(Distributor.TME, mosfet, "SOT-23 N-channel MOSFET")).containsExactly(
                new DistributorPhraser.Relaxation("MOSFET SOT-23", List.of()));
        assertThat(DistributorPhraser.relaxations(Distributor.LCSC, q, q.originalText())).isEmpty();
    }
}
