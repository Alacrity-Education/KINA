package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.domain.Part;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
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

    // ---------------------------------------------------------------- connectors

    private static Map<String, String> connectorKeys(Map<String, String> extracted) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : new String[]{"Family", "ConnectorType", "Series", "Gender", "Positions", "Rows", "Pitch",
                "Orientation", "Mounting"}) {
            if (extracted.containsKey(key)) {
                out.put(key, extracted.get(key));
            }
        }
        return out;
    }

    @Test
    void lcscFemaleHeadersFromDescriptionPackageAndCategory() {
        Part hctl = RankingFixtures.lcsc("C2897388", "HCTL", "PM254-1-06-W-8.5",
                "-40℃~+105℃ 1 1x6P 2.54mm 3A 6P 8.5mm Copper alloy Right Angle Side Square Hole",
                "Connectors / Female Headers", "Push-Pull,P=2.54mm", Map.of());
        Part cjt = RankingFixtures.lcsc("C5333441", "CJT", "A2541HWR-6P",
                "-40℃~+105℃ 1 1x6P 2.54mm 2.5mm 250V 3.2mm 3A 6P Brass Gold Right Angle Side Square Hole 弯插,P=2.54mm",
                "Connectors / Female Headers", "Push-Pull,P=2.54mm", Map.of());
        Part hanxia = RankingFixtures.lcsc("C50878477", "hanxia", "HX FH254-01-06-W-H8.5",
                "-40℃~+105℃ 1 1kV 1x6P 2.54mm 3.2mm 3A 6P 8.5mm Gold Phosphor bronze Right Angle Side Square Hole 弯插,P=2.54mm",
                "Connectors / Female Headers", "Push-Pull,P=2.54mm", Map.of());
        Map<String, String> expected = RankingFixtures.attrs("Family", "connector", "ConnectorType", "female header",
                "Gender", "female", "Positions", "6", "Rows", "1", "Pitch", "2.54mm", "Orientation", "right angle");
        assertThat(connectorKeys(extractor.extract(hctl))).containsExactlyEntriesOf(expected);
        assertThat(connectorKeys(extractor.extract(cjt))).containsExactlyEntriesOf(expected);
        assertThat(connectorKeys(extractor.extract(hanxia))).containsExactlyEntriesOf(expected);
        assertThat(extractor.extract(hctl)).containsEntry("Current", "3A");
    }

    @Test
    void lcscVocabulary() {
        Part straightMale = RankingFixtures.lcsc("C2337", "BOOMELE", "2.54-1*40PStraight pin",
                "-25℃~+85℃ 1 1x40P 2.54mm 2.5mm 3A 3mm 40P 6mm Black Brass Gold Pin Header Through Hole 插件,P=2.54mm",
                "Connectors / Pin Headers", "Plugin,P=2.54mm", Map.of());
        assertThat(connectorKeys(extractor.extract(straightMale))).containsExactlyEntriesOf(RankingFixtures.attrs(
                "Family", "connector", "ConnectorType", "pin header", "Gender", "male", "Positions", "40", "Rows", "1",
                "Pitch", "2.54mm", "Orientation", "vertical", "Mounting", "THT"));
        Part dualFemale = RankingFixtures.lcsc("C2897404", "HCTL", "PM254-2-03-Z-8.5",
                "-40℃~+105℃ 2 2.54mm 2.54mm 2x3P 3A 6P 8.5mm Copper alloy Square Hole Through Hole Top",
                "Connectors / Female Headers", "Plugin,P=2.54mm", Map.of());
        assertThat(extractor.extract(dualFemale)).containsEntry("Positions", "6").containsEntry("Rows", "2")
                .containsEntry("Mounting", "THT").containsEntry("Gender", "female");
        Part smdRightAngle = RankingFixtures.lcsc("C5142239", "XKB", "X6511FRS-06-C85D30",
                "-40℃~+105℃ 1 1x6P 2.54mm 3A 6P 8.5mm Side Square Hole Surface Mount, Right Angle SMD,P=2.54mm,卧贴",
                "Connectors / Female Headers", "SMD,P=2.54mm,Surface Mount，Right Angle", Map.of());
        assertThat(extractor.extract(smdRightAngle)).containsEntry("Mounting", "SMD")
                .containsEntry("Orientation", "right angle");
        Part pitchFromPackage = RankingFixtures.lcsc("C3411", "BOOMELE", "2.0-1*40PStraight Pin Header",
                "-55℃~+105℃ 1 1x40P 2.7mm 2mm 2mm 3A 40P 4mm Black Brass Gold Pin Header Through Hole 插件,P=2mm",
                "Connectors / Pin Headers", "Plugin,P=2mm", Map.of());
        assertThat(extractor.extract(pitchFromPackage)).containsEntry("Pitch", "2mm");
        Part socket = RankingFixtures.lcsc("C72124", "Ckmtw", "DS1009-08AT1NX-0A2",
                "-20℃~+105℃ 2.54mm 7.62mm 8P DIP Square Hole Tin", "Connectors / IC / Transistor Socket", "DIP-8",
                Map.of());
        assertThat(extractor.extract(socket)).containsEntry("ConnectorType", "ic socket")
                .containsEntry("Positions", "8").containsEntry("Gender", "female");
        Part usbC = RankingFixtures.lcsc("C2765186", "SHOU HAN", "TYPE-C 16PIN 2MD(073)",
                "-25℃~+85℃ 1 10 thousand cycles 16P 3A 5V Black Female Surface Mount, Right Angle Type-C",
                "Connectors / USB Connectors", "SMD", Map.of());
        assertThat(connectorKeys(extractor.extract(usbC))).containsExactlyEntriesOf(RankingFixtures.attrs(
                "Family", "connector", "ConnectorType", "usb-c", "Gender", "female", "Positions", "16",
                "Orientation", "right angle", "Mounting", "SMD"));
        Part jst = RankingFixtures.lcsc("C144397", "JST", "B6B-XH-A(LF)(SN)",
                "-25℃~+85℃ 1 17.4mm 1x6P 2.5mm 250V 3A 5.75mm 6 6P 7mm Brass K Pin PA66 Through Hole Tin UL94V-0 White XH 插件,P=2.5mm",
                "Connectors / Wire To Board Connector", "Plugin,P=2.5mm", Map.of());
        assertThat(extractor.extract(jst)).containsEntry("ConnectorType", "wire-to-board")
                .containsEntry("Series", "XH").containsEntry("Positions", "6").containsEntry("Pitch", "2.5mm");
    }

    @Test
    void tmeConnectorsFromDescription() {
        Part angled = RankingFixtures.tme("DS1024-1*6RF1", "CONNFLY",
                "Connector: pin strips; socket; female; PIN: 6; THT; angled 90°; 3A", "Pin headers", null, Map.of());
        assertThat(connectorKeys(extractor.extract(angled))).containsExactlyEntriesOf(RankingFixtures.attrs(
                "Family", "connector", "ConnectorType", "female header", "Gender", "female", "Positions", "6",
                "Orientation", "right angle", "Mounting", "THT"));
        Part male = RankingFixtures.tme("ZL201-40G", "CONNFLY",
                "Connector: pin strips; pin header; male; PIN: 40; THT; straight", "Pin headers", null, Map.of());
        assertThat(extractor.extract(male)).containsEntry("ConnectorType", "pin header").containsEntry("Gender", "male")
                .containsEntry("Positions", "40").containsEntry("Orientation", "vertical");
        // Samtec "socket" with male pins: the explicit gender wins
        Part samtec = RankingFixtures.tme("ESQ-120-14-G-D", "SAMTEC",
                "Connector: pin strips; socket; male; PIN: 40; straight; THT; 2.54mm", "Board-to-board connectors", null,
                Map.of());
        assertThat(extractor.extract(samtec)).containsEntry("Gender", "male").containsEntry("Pitch", "2.54mm");
    }

    @Test
    void tmeConnectorParametersFromTheVendoredFixture() throws IOException {
        // real /products/parameters response (2026-10-05)
        Part rightAngle = RankingFixtures.tme("ZL263-6SG", "CONNFLY",
                "Connector: pin strips; socket; female; PIN: 6; THT; angled 90°; 3A", "Pin headers", null,
                tmeParameters("ZL263-6SG"));
        assertThat(connectorKeys(extractor.extract(rightAngle))).containsExactlyEntriesOf(RankingFixtures.attrs(
                "Family", "connector", "ConnectorType", "female header", "Gender", "female", "Positions", "6",
                "Rows", "1", "Pitch", "2.54mm", "Orientation", "right angle", "Mounting", "THT"));
        // parameters alone (no description) are enough
        Part dual = RankingFixtures.tme("SSW-103-02-G-D-RA", "SAMTEC", null, "Board-to-board connectors", null,
                tmeParameters("SSW-103-02-G-D-RA"));
        assertThat(connectorKeys(extractor.extract(dual))).containsExactlyEntriesOf(RankingFixtures.attrs(
                "Family", "connector", "ConnectorType", "female header", "Gender", "female", "Positions", "6",
                "Rows", "2", "Pitch", "2.54mm", "Orientation", "right angle", "Mounting", "THT"));
        Part jst = RankingFixtures.tme("B6B-XH-A", "JST", null, "Raster signal connectors 2,50mm", null,
                tmeParameters("B6B-XH-A"));
        assertThat(extractor.extract(jst)).containsEntry("ConnectorType", "wire-to-board").containsEntry("Series", "XH")
                .containsEntry("Gender", "male").containsEntry("Pitch", "2.5mm").containsEntry("Orientation", "vertical");
        Part usb = RankingFixtures.tme("USB4105-GF-A", "GCT", null, "USB & IEEE1394 connectors", null,
                tmeParameters("USB4105-GF-A"));
        assertThat(extractor.extract(usb)).containsEntry("ConnectorType", "usb-c").containsEntry("Positions", "16")
                .containsEntry("Mounting", "SMD").containsEntry("Orientation", "right angle");
    }

    /** Parameters of one symbol from the vendored TME fixture, as TmePartMapper maps them (values joined). */
    private static Map<String, String> tmeParameters(String symbol) throws IOException {
        try (InputStream in = ParametricExtractorTest.class.getResourceAsStream(
                "/fixtures/tme/parameters-connectors.json")) {
            JsonNode root = JsonMapper.builder().build().readTree(in);
            Map<String, String> out = new LinkedHashMap<>();
            for (JsonNode element : root.path("data").path("elements")) {
                if (!symbol.equals(element.path("symbol").asString())) {
                    continue;
                }
                for (JsonNode parameter : element.path("parameters").path("elements")) {
                    StringBuilder values = new StringBuilder();
                    for (JsonNode value : parameter.path("values")) {
                        if (!values.isEmpty()) {
                            values.append(", ");
                        }
                        values.append(value.path("value").asString());
                    }
                    out.put(parameter.path("name").asString(), values.toString());
                }
            }
            return out;
        }
    }

    @Test
    void mouserConnectorsFromAttributesAndDescription() {
        Part attributes = RankingFixtures.mouser("SSW-106-02-T-S-RA", "Samtec",
                "Headers & Wire Housings", "Headers & Wire Housings", null,
                RankingFixtures.attrs("Product", "Sockets", "Number of Positions", "6 Position", "Pitch", "2.54 mm",
                        "Contact Gender", "Socket (Female)", "Mounting Angle", "Right Angle", "Number of Rows", "1 Row",
                        "Mounting Style", "Through Hole"));
        assertThat(connectorKeys(extractor.extract(attributes))).containsExactlyEntriesOf(RankingFixtures.attrs(
                "Family", "connector", "ConnectorType", "female header", "Gender", "female", "Positions", "6",
                "Rows", "1", "Pitch", "2.54mm", "Orientation", "right angle", "Mounting", "THT"));
        Part description = RankingFixtures.mouser("HTST-105-04-F-D-RA", "Samtec",
                "Headers & Wire Housings 10 POS 2.54MM RA Female Receptacle", "Headers & Wire Housings", null, Map.of());
        assertThat(connectorKeys(extractor.extract(description))).containsExactlyEntriesOf(RankingFixtures.attrs(
                "Family", "connector", "ConnectorType", "female header", "Gender", "female", "Positions", "10",
                "Pitch", "2.54mm", "Orientation", "right angle"));
        Part genderless = RankingFixtures.mouser("X", "ACME", "Headers & Wire Housings 6 Positions Vertical",
                "Headers & Wire Housings", null, Map.of());
        assertThat(extractor.extract(genderless)).containsEntry("ConnectorType", "header")
                .doesNotContainKey("Gender").containsEntry("Orientation", "vertical");
    }

    @Test
    void mouserAbbreviations() {
        Part molex = RankingFixtures.mouser("22-16-2061", "Molex", "Headers & Wire Housings 6P RT ANGL PCB RECEP",
                "Headers & Wire Housings", null, Map.of());
        assertThat(connectorKeys(extractor.extract(molex))).containsExactlyEntriesOf(RankingFixtures.attrs(
                "Family", "connector", "ConnectorType", "female header", "Gender", "female", "Positions", "6",
                "Orientation", "right angle"));
        Part strip = RankingFixtures.mouser("SMH-106-02-L-S", "Samtec",
                "Headers & Wire Housings .100 Horizontal Surface Mount Socket Strip", "Headers & Wire Housings", null,
                Map.of());
        assertThat(extractor.extract(strip)).containsEntry("ConnectorType", "female header")
                .containsEntry("Pitch", "2.54mm").containsEntry("Orientation", "right angle")
                .containsEntry("Mounting", "SMD");
        Part circuits = RankingFixtures.mouser("22-15-2116", "Molex", "Headers & Wire Housings 2.54MM BOARD CONN RA 11 CKT Tin",
                "Headers & Wire Housings", null, Map.of());
        assertThat(extractor.extract(circuits)).containsEntry("Positions", "11").containsEntry("Pitch", "2.54mm")
                .containsEntry("Orientation", "right angle");
        Part harwin = RankingFixtures.mouser("M22-6540642R", "Harwin", "Headers & Wire Housings M22 6 WAY SIL HORIZ SMT SKT T&R",
                "Headers & Wire Housings", null, Map.of());
        assertThat(connectorKeys(extractor.extract(harwin))).containsExactlyEntriesOf(RankingFixtures.attrs(
                "Family", "connector", "ConnectorType", "female header", "Gender", "female", "Positions", "6",
                "Rows", "1", "Orientation", "right angle", "Mounting", "SMD"));
        Part wurth = RankingFixtures.mouser("613006143121", "Wurth", "Headers & Wire Housings WR-PHD 2.54mm Socket Header 6 pins",
                "Headers & Wire Housings", null, Map.of());
        assertThat(extractor.extract(wurth)).containsEntry("ConnectorType", "female header").containsEntry("Positions", "6");
    }

    @Test
    void icsAreNotConnectors() {
        Part opamp = part("LM358DR", "Operational Amplifiers - Op Amps Dual 8 pin", null, "SOIC-8", Map.of());
        assertThat(extractor.extract(opamp)).doesNotContainKeys("Positions", "ConnectorType")
                .containsEntry("Family", "opamp");
        Part mcu = RankingFixtures.lcsc("C2040", "Raspberry Pi", "RP2040", "133MHz 264KB ARM Cortex-M0+ USB 1.1",
                "Embedded Processors & Controllers / Microcontrollers (MCU/MPU/SOC)", "LQFP-56", Map.of());
        assertThat(extractor.extract(mcu)).doesNotContainKeys("Positions", "ConnectorType");
    }
}
