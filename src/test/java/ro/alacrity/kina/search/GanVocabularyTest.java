package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * GaN power stage and gate driver vocabulary (audit of 2026-10-07): the gate driver family, the GaN / SiC / silicon
 * technology of transistors, and the on-resistance of MOSFETs written in milliohm. Part texts are Mouser's, recorded
 * live on 2026-10-07 ({@code fixtures/gan-audit}).
 */
class GanVocabularyTest {

    private final QueryParser parser = new QueryParser();
    private final ParametricExtractor extractor = new ParametricExtractor();

    private static Part mouser(String mpn, String category, String description) {
        return RankingFixtures.mouser(mpn, "ACME", category + " " + description, category, null, Map.of());
    }

    // ---- gate driver family

    @Test
    void gateDriverWordsNameTheGateDriverFamily() {
        for (String q : new String[] {"uP1966E GaN half bridge gate driver", "half-bridge driver 100V",
                "MOSFET gate driver SOIC-8", "EPC23101 eGaN ePower stage", "GaN power stage 80V",
                "IGBT driver", "half bridge GaN with integrated driver"}) {
            assertThat(parser.parse(q).family()).as(q).isEqualTo(Recognizers.GATE_DRIVER);
        }
        // "FET" alone stays a MOSFET; "driver" alone names no family
        assertThat(parser.parse("EPC2302 GaN FET 100V").family()).isEqualTo("mosfet");
        assertThat(parser.parse("LED driver 1A").family()).isEqualTo("led");
    }

    @Test
    void gateDriverPartsComeFromCategoryOrHalfBridgeWithDriver() {
        assertThat(extractor.features(mouser("EPC2152", "Gate Drivers",
                "EPC eGaN IC, 80 V, 15 A Integrated DrGaN Symetrical Half-Bridge Power Stage")).family())
                .isEqualTo(Recognizers.GATE_DRIVER);
        assertThat(extractor.features(mouser("ADUM4221-1ARIZ-RL", "Galvanically Isolated Gate Drivers",
                "Iso 1/2 Bridge Drv w PWM input UVLO 4.5V")).family()).isEqualTo(Recognizers.GATE_DRIVER);
        // listed under "GaN FETs", but a half-bridge with an integrated gate driver
        assertThat(extractor.features(mouser("IGI60L2727B1MXUMA1", "GaN FETs", "270 mohm / 600 V GaN transistor in "
                + "half-bridge configuration with integrated level-shift gate driver and bootstrap diode")).family())
                .isEqualTo(Recognizers.GATE_DRIVER);
        // a single GaN FET stays a MOSFET
        assertThat(extractor.features(mouser("EPC2619", "GaN FETs",
                "EPC eGaN FET,100 V, 3.3 milliohm typ  at  5 V, LGA 2.5 x 1.5")).family()).isEqualTo("mosfet");
    }

    @Test
    void aMosfetRequestExcludesGateDriversAndTheReverse() {
        Part driver = mouser("MASTERGAN4LTR", "Gate Drivers",
                "600 V half-bridge enhancement mode GaN HEMT with high voltage driver");
        Part fet = mouser("EPC2619", "GaN FETs", "EPC eGaN FET,100 V, 3.3 milliohm typ  at  5 V, LGA 2.5 x 1.5");
        assertThat(ConstraintPolicy.DEFAULTS.check(parser.parse("GaN FET 100V"), extractor.features(driver)).conflicts())
                .containsExactly("type");
        assertThat(ConstraintPolicy.DEFAULTS.check(parser.parse("GaN half bridge gate driver"),
                extractor.features(fet)).conflicts()).containsExactly("type");
    }

    @Test
    void familyTokenIsSpelledForDistributors() {
        assertThat(Recognizers.familyToken("GaN gate driver 100V", Recognizers.GATE_DRIVER)).isEqualTo("gate driver");
        assertThat(Recognizers.familyToken("eGaN ePower stage", Recognizers.GATE_DRIVER)).isEqualTo("power stage");
    }

    // ---- technology

    @Test
    void ganSicAndSiliconAreTransistorTechnologies() {
        assertThat(parser.parse("GaN FET 100V").technology()).isEqualTo(TechnologyVocabulary.GAN);
        assertThat(parser.parse("GaN HEMT 650V").technology()).isEqualTo(TechnologyVocabulary.GAN);
        assertThat(parser.parse("eGaN FET 100V").technology()).isEqualTo(TechnologyVocabulary.GAN);
        assertThat(parser.parse("SiC MOSFET 1200V TO-247").technology()).isEqualTo(TechnologyVocabulary.SIC);
        assertThat(parser.parse("silicon carbide MOSFET 1200V").technology()).isEqualTo(TechnologyVocabulary.SIC);
        assertThat(parser.parse("silicon MOSFET 100V").technology()).isEqualTo(TechnologyVocabulary.SILICON);
        assertThat(parser.parse("GaN half bridge gate driver").technology()).isEqualTo(TechnologyVocabulary.GAN);
        // the technology words leave the keywords; part numbers starting with GAN are no technology
        assertThat(parser.parse("GaN FET 100V").keywords()).doesNotContain("gan");
        assertThat(parser.parse("N-channel MOSFET 30V SOT-23").technology()).isNull();
        assertThat(extractor.features(mouser("GAN140-650EBEZ", "GaN FETs", "GAN140-650EBE/SOT8074/DFN8080-"))
                .technology()).isEqualTo(TechnologyVocabulary.GAN);
        assertThat(extractor.features(mouser("GRF0020", "GaN FETs",
                "Unmatched Discrete GaN-on-SiC HEMT 30W PSAT at 50V")).technology()).isEqualTo(TechnologyVocabulary.GAN);
        assertThat(extractor.features(mouser("SCT3080KLGC11", "SiC MOSFETs", "N-channel 1200V 31A")).technology())
                .isEqualTo(TechnologyVocabulary.SIC);
        assertThat(extractor.features(RankingFixtures.mouser("BSC010N04LS", "Infineon", "MOSFETs N-Ch 40V",
                "MOSFETs", null, RankingFixtures.attrs("Technology", "Si"))).technology())
                .isEqualTo(TechnologyVocabulary.SILICON);
    }

    @Test
    void aGanRequestExcludesSicAndSiliconParts() {
        ParsedQuery q = parser.parse("GaN FET 100V");
        Part sic = mouser("SCT3080KLGC11", "SiC MOSFETs", "N-channel 1200V 31A");
        Part unknown = mouser("BSC010N04LS", "MOSFETs", "N-Ch 40V");
        assertThat(ConstraintPolicy.DEFAULTS.check(q, extractor.features(sic)).conflicts())
                .containsExactly("technology");
        assertThat(ConstraintPolicy.DEFAULTS.check(q, extractor.features(unknown)).conflict()).isFalse();
        assertThat(ConstraintPolicy.DEFAULTS.hardFor(q)).contains("technology");
    }

    // ---- on-resistance

    @Test
    void onResistanceInMilliohmIsReadForMosfets() {
        assertThat(resistance(mouser("EPC2619", "GaN FETs",
                "EPC eGaN FET,100 V, 3.3 milliohm typ  at  5 V, LGA 2.5 x 1.5"))).isCloseTo(3.3e-3, within(1e-12));
        assertThat(resistance(mouser("EPC2218A", "GaN FETs",
                "EPC eGaN FET,80 V, 3.2 milliohm  at  5 V, LGA 3.5 x 1.95"))).isCloseTo(3.2e-3, within(1e-12));
        assertThat(resistance(mouser("IGT60R070D1", "GaN FETs", "CoolGaN 600 V 270 mohm"))).isCloseTo(0.27,
                within(1e-12));
        assertThat(resistance(mouser("NV6117", "MOSFETs", "GaNFast Single 650V 120mOhm PQFN 56")))
                .isCloseTo(0.12, within(1e-12));
        // Mouser's lost ohm sign; of a list the first value
        assertThat(resistance(mouser("TEST1", "GaN FETs", "650-V 170/248-m? GaN FET"))).isCloseTo(0.17,
                within(1e-12));
        assertThat(resistance(mouser("TEST2", "GaN FETs", "600-V 70-m? GaN FET"))).isCloseTo(0.07, within(1e-12));
        // a bare "m" is not read
        assertThat(extractor.features(mouser("RTP100E2P6G1FL-TR", "GaN FETs", "100V 2.6m LV GaN FET in 5x6 RQFN"))
                .measure(ParsedQuery.RESISTANCE)).isNull();
        assertThat(extractor.features(mouser("IGKA23S101SXTSA1", "GaN FETs",
                "CoolGaN Transistor 100 V G3 in WLCSP 0.9x0.9, 185 m")).measure(ParsedQuery.RESISTANCE)).isNull();
        // the voltage is unaffected
        assertThat(extractor.features(mouser("EPC2218A", "GaN FETs",
                "EPC eGaN FET,80 V, 3.2 milliohm  at  5 V, LGA 3.5 x 1.95")).value(ParsedQuery.VOLTAGE))
                .isEqualTo(80.0);
        assertThat(extractor.extract(mouser("EPC2619", "GaN FETs",
                "EPC eGaN FET,100 V, 3.3 milliohm typ  at  5 V, LGA 2.5 x 1.5")))
                .containsEntry("Resistance", "3.3mohm").containsEntry("Voltage", "100V")
                .containsEntry("Technology", "GaN").containsEntry("Family", "mosfet");
    }

    @Test
    void milliohmInAQueryIsTheOnResistance() {
        ParsedQuery q = parser.parse("GaN FET 100V 3 milliohm");
        assertThat(q.constraint(ParsedQuery.RESISTANCE).value()).isCloseTo(3e-3, within(1e-12));
        assertThat(q.keywords()).doesNotContain("3", "milliohm");
    }

    private double resistance(Part part) {
        return extractor.features(part).value(ParsedQuery.RESISTANCE);
    }
}
