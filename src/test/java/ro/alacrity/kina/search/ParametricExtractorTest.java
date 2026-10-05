package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.domain.Part;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static ro.alacrity.kina.search.RankingFixtures.part;

class ParametricExtractorTest {

    private final ParametricExtractor extractor = new ParametricExtractor();

    static final Part MOUSER_MLCC = RankingFixtures.mouser("CC0805KKX7R8BB106", "YAGEO",
            "Multilayer Ceramic Capacitors MLCC - SMD/SMT 10uF 25V X7R 10% 0805",
            "Multilayer Ceramic Capacitors MLCC - SMD/SMT", null,
            RankingFixtures.attrs("Capacitance", "10 uF", "Voltage Rating DC", "25 VDC", "Tolerance", "10 %",
                    "Dielectric", "X7R", "Case Code - in", "0805", "Case Code - mm", "2012",
                    "Package / Case", "0805 (2012 metric)", "Termination Style", "SMD/SMT"));

    static final Part TME_MLCC = RankingFixtures.tme("CL21B106KAYQNNE", "SAMSUNG",
            "Capacitor: ceramic; MLCC; 10uF; 25V; X7R; ±10%; SMD; 0805", "MLCC SMD capacitors", "0805",
            RankingFixtures.attrs("Capacitance", "10µF", "Operating voltage", "25V", "Tolerance", "±10%",
                    "Dielectric", "X7R", "Case - inch", "0805", "Case - mm", "2012", "Mounting", "SMD"));

    static final Part LCSC_MLCC = RankingFixtures.lcsc("C15850", "SAMSUNG", "CL21A106KAYNNNE",
            "25V 10uF X5R ±10% 0805 Multilayer Ceramic Capacitors MLCC - SMD/SMT ROHS",
            "Capacitors/Multilayer Ceramic Capacitors MLCC - SMD/SMT", "0805",
            RankingFixtures.attrs("Capacitance", "10uF", "Voltage Rated", "25V", "Tolerance", "±10%",
                    "Temperature Coefficient", "X5R"));

    @Test
    void mouserMlcc() {
        assertThat(extractor.extract(MOUSER_MLCC)).containsExactlyEntriesOf(RankingFixtures.attrs(
                "Capacitance", "10uF", "Voltage", "25V", "Tolerance", "10%", "Dielectric", "X7R", "Package", "0805",
                "Mounting", "SMD", "Family", "capacitor"));
    }

    @Test
    void tmeMlcc() {
        assertThat(extractor.extract(TME_MLCC)).containsExactlyEntriesOf(RankingFixtures.attrs(
                "Capacitance", "10uF", "Voltage", "25V", "Tolerance", "10%", "Dielectric", "X7R", "Package", "0805",
                "Mounting", "SMD", "Family", "capacitor"));
    }

    @Test
    void lcscMlcc() {
        assertThat(extractor.extract(LCSC_MLCC)).containsExactlyEntriesOf(RankingFixtures.attrs(
                "Capacitance", "10uF", "Voltage", "25V", "Tolerance", "10%", "Dielectric", "X5R", "Package", "0805",
                "Mounting", "SMD", "Family", "capacitor"));
    }

    @Test
    void resistorsFromAllDistributorsAreComparable() {
        Part mouser = RankingFixtures.mouser("RC0805FR-0710KL", "YAGEO", "Thick Film Resistors - SMD 1/8W 10K ohm 1% 0805",
                "Thick Film Resistors - SMD", null,
                RankingFixtures.attrs("Resistance", "10 kOhms", "Power Rating", "125 mW (1/8 W)", "Tolerance", "1 %",
                        "Case Code - in", "0805"));
        Part tme = RankingFixtures.tme("RC0805FR-0710KL", "YAGEO",
                "Resistor: thick film; SMD; 0805; 10kΩ; 125mW; ±1%; -55÷155°C", "SMD resistors", "0805",
                RankingFixtures.attrs("Resistance", "10kΩ", "Power", "0.125W", "Tolerance", "±1%", "Case - inch", "0805",
                        "Mounting", "SMD"));
        Part lcsc = RankingFixtures.lcsc("C17414", "UNI-ROYAL", "0805W8F1002T5E",
                "125mW Thick Film Resistors 150V ±100ppm/°C ±1% 10kΩ 0805 Chip Resistor - Surface Mount ROHS",
                "Resistors/Chip Resistor - Surface Mount", "0805", Map.of());

        Map<String, String> expected = RankingFixtures.attrs("Resistance", "10kohm", "Power", "125mW",
                "Tolerance", "1%", "Package", "0805", "Mounting", "SMD", "Family", "resistor");
        assertThat(extractor.extract(mouser)).containsExactlyEntriesOf(expected);
        assertThat(extractor.extract(tme)).containsExactlyEntriesOf(expected);
        // LCSC description also carries the 150V working voltage
        assertThat(extractor.extract(lcsc)).containsAllEntriesOf(expected).containsEntry("Voltage", "150V");
    }

    @Test
    void descriptionOnlyDiode() {
        Part diode = part("SS34", "Schottky Diodes & Rectifiers 40V 3A SMA", null, null, Map.of());
        assertThat(extractor.extract(diode)).containsExactlyEntriesOf(RankingFixtures.attrs(
                "Voltage", "40V", "Current", "3A", "Package", "SMA", "Mounting", "SMD", "Family", "schottky"));
    }

    @Test
    void crystalPackageAndFrequency() {
        Part crystal = RankingFixtures.lcsc("C9002", "YXC", "X322512MSB4SI", "12MHz ±20ppm 20pF SMD3225-4P Crystals ROHS",
                "Crystals, Oscillators, Resonators/Crystals", "SMD3225-4P", Map.of("Frequency", "12MHz"));
        Map<String, String> extracted = extractor.extract(crystal);
        assertThat(extracted).containsEntry("Frequency", "12MHz").containsEntry("Package", "3225")
                .containsEntry("Family", "crystal");
    }

    @Test
    void distributorAttributesTakePrecedenceOverDescription() {
        Part p = part("X", "Capacitor 10uF 25V X5R 1206", null, null,
                RankingFixtures.attrs("Voltage Rating DC", "50 VDC", "Dielectric", "X7R", "Case Code - in", "0805"));
        Map<String, String> extracted = extractor.extract(p);
        assertThat(extracted).containsEntry("Voltage", "50V").containsEntry("Dielectric", "X7R")
                .containsEntry("Package", "0805").containsEntry("Capacitance", "10uF");
    }

    @Test
    void metricCaseCodeIsConvertedAndMetricPackageFieldUsedForPassives() {
        Part tmeMetricOnly = part("X", "Capacitor: ceramic; MLCC; 1uF", "MLCC SMD capacitors", null,
                Map.of("Case - mm", "3216"));
        assertThat(extractor.extract(tmeMetricOnly)).containsEntry("Package", "1206");
        Part unknownPackage = part("Y", "Some module", null, "Module-24", Map.of());
        assertThat(extractor.extract(unknownPackage)).containsEntry("Package", "Module-24");
    }

    @Test
    void icPackagesAreRecognisedFromThePackageField() {
        Part opamp = part("LM358DR", "Operational Amplifiers - Op Amps Dual", null, "SOIC-8", Map.of());
        assertThat(extractor.extract(opamp)).containsEntry("Package", "SOIC-8").containsEntry("Family", "opamp")
                .containsEntry("Mounting", "SMD");
        Part mosfet = part("2N7002", "MOSFET N-Channel 60V 300mA", null, "SOT-23-3", Map.of());
        assertThat(extractor.extract(mosfet)).containsEntry("Package", "SOT-23-3")
                .containsEntry("Voltage", "60V").containsEntry("Current", "300mA");
        assertThat(Recognizers.packageKey("SOT-23-3")).isEqualTo(Recognizers.packageKey("SOT-23"));
        assertThat(Recognizers.packageKey("SOP-8")).isEqualTo(Recognizers.packageKey("SOIC-8"));
        assertThat(Recognizers.packageKey("DPAK")).isEqualTo(Recognizers.packageKey("TO-252"));
        assertThat(Recognizers.packageKey("DO-214AC")).isEqualTo(Recognizers.packageKey("SMA"));
    }

    @Test
    void enrichAddsComparableKeysAndKeepsDistributorValues() {
        Part enriched = extractor.enrich(MOUSER_MLCC);
        assertThat(enriched.attributes())
                .containsEntry("Capacitance", "10 uF")          // distributor value kept
                .containsEntry("Voltage Rating DC", "25 VDC")
                .containsEntry("Voltage", "25V")
                .containsEntry("Package", "0805")
                .containsEntry("Family", "capacitor");
        assertThat(enriched).usingRecursiveComparison().ignoringFields("attributes").isEqualTo(MOUSER_MLCC);
        assertThat(MOUSER_MLCC.attributes()).doesNotContainKey("Voltage");
    }
}
