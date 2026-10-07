package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.TestWiring;
import ro.alacrity.kina.domain.Part;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
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
                "Mounting", "SMD", "Family", "capacitor", "Technology", "ceramic"));
    }

    @Test
    void tmeMlcc() {
        assertThat(extractor.extract(TME_MLCC)).containsExactlyEntriesOf(RankingFixtures.attrs(
                "Capacitance", "10uF", "Voltage", "25V", "Tolerance", "10%", "Dielectric", "X7R", "Package", "0805",
                "Mounting", "SMD", "Family", "capacitor", "Technology", "ceramic"));
    }

    @Test
    void lcscMlcc() {
        assertThat(extractor.extract(LCSC_MLCC)).containsExactlyEntriesOf(RankingFixtures.attrs(
                "Capacitance", "10uF", "Voltage", "25V", "Tolerance", "10%", "Dielectric", "X5R", "Package", "0805",
                "Mounting", "SMD", "Family", "capacitor", "Technology", "ceramic"));
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

        Map<String, String> expected = RankingFixtures.attrs("Resistance", "10kohm", "Power", "0.125W",
                "Tolerance", "1%", "Package", "0805", "Mounting", "SMD", "Family", "resistor", "Technology", "thick film");
        assertThat(extractor.extract(mouser)).containsExactlyEntriesOf(expected);
        // TME's description also states the operating temperature range (-55÷155°C)
        Map<String, String> tmeExpected = new java.util.LinkedHashMap<>(expected);
        tmeExpected.put("MaxTemperature", "155°C");
        tmeExpected.put("OperatingTemperature", "-55...155°C");
        assertThat(extractor.extract(tme)).containsExactlyInAnyOrderEntriesOf(tmeExpected);
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
        Part tmeMosfet = part("AO3402", "Transistor: N-MOSFET; unipolar; 30V; 3.2A; 0.9W; SOT23",
                "SMD N channel transistors", null, Map.of());
        assertThat(extractor.extract(tmeMosfet)).containsEntry("Family", "mosfet");
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
        assertThat(enriched).usingRecursiveComparison().ignoringFields("attributes", "derivedAttributes")
                .isEqualTo(MOUSER_MLCC);
        assertThat(MOUSER_MLCC.attributes()).doesNotContainKey("Voltage");
        // the added keys are listed, so the cache stores the distributor's attributes only
        assertThat(enriched.derivedAttributes()).containsExactlyInAnyOrder("Voltage", "Package", "Mounting", "Family",
                "Technology");
        assertThat(enriched.asStored().attributes()).isEqualTo(MOUSER_MLCC.attributes());
        assertThat(enriched.asStored().derivedAttributes()).isEmpty();
    }

    @Test
    void enrichingAnEnrichedPartDerivesTheSameAttributesAgain() {
        Part enriched = extractor.enrich(MOUSER_MLCC);
        assertThat(extractor.enrich(enriched)).isEqualTo(enriched);
        assertThat(extractor.enrich(enriched.asStored())).isEqualTo(enriched);
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

    // ------------------------------------------------------------------ USB connectors (real strings, 2026-10-05)

    private static Map<String, String> usbKeys(Map<String, String> attributes) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : List.of("ConnectorType", "Gender", "Positions", "Orientation", "Mounting", "UsbType",
                "UsbStandard", "UsbSpeedGbps", "PinConfiguration", "ShieldPinsCounted", "MountingStyle", "Waterproof",
                "Features")) {
            if (attributes.containsKey(key)) {
                out.put(key, attributes.get(key));
            }
        }
        return out;
    }

    private Map<String, String> lcscUsb(String mpn, String description, String packageName) {
        return usbKeys(extractor.extract(RankingFixtures.lcsc("C1", "X", mpn, description,
                "Connectors / USB Connectors", packageName, Map.of())));
    }

    @Test
    void lcscUsbDescriptions() {
        // JLCPCB labels many 16P Type-C parts "USB 3.1": 16 contacts carry no SuperSpeed pairs -> USB 2.0
        assertThat(lcscUsb("TYPE-C-F02C-1BBWC2.1", "-40℃~+85℃ 1 1.63mm 10,000 Cycles 16P 5A 5V 6.5mm Black Female "
                + "Laminated board Type-C USB 3.1", "SMD")).containsExactlyEntriesOf(RankingFixtures.attrs(
                "ConnectorType", "usb-c", "Gender", "female", "Positions", "16", "Mounting", "SMD", "UsbType", "Type-C",
                "UsbStandard", "USB 2.0", "UsbSpeedGbps", "0.48", "PinConfiguration", "16", "MountingStyle", "mid-mount",
                "Features", "mid-mount"));
        assertThat(lcscUsb("TYPEC-324GC-ACP24", "-40℃~+85℃ 1 10000 times 24P 5A 5V 7.9mm Black Female Surface Mount, "
                + "Right Angle Type-C USB 3.1", "SMD")).containsExactlyEntriesOf(RankingFixtures.attrs(
                "ConnectorType", "usb-c", "Gender", "female", "Positions", "24", "Orientation", "right angle",
                "Mounting", "SMD", "UsbType", "Type-C", "UsbStandard", "USB 3.x", "UsbSpeedGbps", "5",
                "PinConfiguration", "24", "MountingStyle", "SMD"));
        assertThat(lcscUsb("HH 16P TYPE-C (Y495)", "-40℃~+85℃ 1 16P 20V 3,000 Cycles 3A 7.35mm Black Female Surface "
                + "Mount, Right Angle Type-C USB 2.0 With Locating Pins", "SMD"))
                .containsEntry("UsbStandard", "USB 2.0").containsEntry("PinConfiguration", "16")
                .containsEntry("Features", "board lock");
        // 6P Type-C: power only, whatever the label says
        assertThat(lcscUsb("YTC-TC6-150", "-20℃~+85℃ 1 10000 times 3A 5V 5mm 6P Female Surface Mount, Right Angle "
                + "Type-C", "SMD")).containsEntry("PinConfiguration", "6").containsEntry("Features", "power only")
                .doesNotContainKey("UsbStandard");
        // sealing only in the part number
        assertThat(lcscUsb("TYPE-C 6PFS 4J-H7.5 IPX8", "-20℃~+85℃ 1 30V 3A 5 thousand cycles 6P Black Female Surface "
                + "Mount, Right Angle Type-C USB 3.1", "SMD")).containsEntry("Waterproof", "IPX8")
                .doesNotContainKey("UsbStandard").containsEntry("Features", "waterproof, IPX8, power only");
        assertThat(lcscUsb("920-E52A2021S10100", "-30℃~+80℃ 1 1A 5.15mm 5P Black Female Micro-B Surface Mount, Right "
                + "Angle USB 2.0", "SMD")).containsEntry("ConnectorType", "micro usb").containsEntry("UsbType", "Micro-B")
                .containsEntry("UsbStandard", "USB 2.0").containsEntry("PinConfiguration", "5");
        assertThat(lcscUsb("HC-USB3.0-L168-WP", "-25℃~+70℃ 1 1,500 Cycles 1.5A 16.8mm 30V 9P Blue Female Right Angle "
                + "Type-A USB 3.0 弯插", "Push-Pull")).containsEntry("ConnectorType", "usb").containsEntry("UsbType", "Type-A")
                .containsEntry("UsbStandard", "USB 3.2 Gen 1").containsEntry("PinConfiguration", "9")
                .containsEntry("Orientation", "right angle");
        // unlabelled Type-A 4P is USB 2.0 by its contacts
        assertThat(lcscUsb("U-G-O4DD-W-1", "1 18.75mm 1A 30V 4P Black Laminated board Male Type-A USB 2.0 插件", "Plugin"))
                .containsEntry("Gender", "male").containsEntry("UsbStandard", "USB 2.0").containsEntry("Mounting", "THT");
        assertThat(lcscUsb("TYPE-C 24P GTJB", "-40℃~+85℃ 1 10 thousand cycles 20V 24P 5A Clamping plate Male Type-C",
                "SMD")).containsEntry("Gender", "male").containsEntry("PinConfiguration", "24")
                .doesNotContainKey("UsbStandard").containsEntry("Features", "straddle-mount");
        assertThat(lcscUsb("PTCFW-H22F-027", "-25℃~+85℃ 1 1.68mm 10 thousand cycles 24P 48V 5A 7.9mm Black Female "
                + "Surface Mount, Right Angle Type-C USB4 With Locating Pins", "SMD"))
                .containsEntry("UsbStandard", "USB4").containsEntry("UsbSpeedGbps", "40");
    }

    @Test
    void shellPinsCountedInThePinCount() {
        // 17P/18P -> 16, 25P/26P -> 24 (distributors that count shell/shield pins); 14 stays 14
        for (String[] row : new String[][]{{"17", "16", "1"}, {"18", "16", "2"}, {"25", "24", "1"},
                {"26", "24", "2"}, {"7", "6", "1"}, {"8", "6", "2"}, {"13", "12", "1"}}) {
            Map<String, String> keys = lcscUsb("X", "1 " + row[0] + "P 5A Female Surface Mount, Right Angle Type-C", "SMD");
            assertThat(keys).as(row[0]).containsEntry("Positions", row[0]).containsEntry("PinConfiguration", row[1])
                    .containsEntry("ShieldPinsCounted", row[2]);
        }
        assertThat(lcscUsb("X", "1 14P 5A Female Surface Mount, Right Angle Type-C USB 2.0", "SMD"))
                .containsEntry("PinConfiguration", "14").doesNotContainKey("ShieldPinsCounted");
        // Micro-B 6/7 -> 5, Type-A 5/6 -> 4, USB 3.0 Type-A 10/11 -> 9
        assertThat(lcscUsb("X", "1 7P Female Micro-B Surface Mount", "SMD")).containsEntry("PinConfiguration", "5")
                .containsEntry("ShieldPinsCounted", "2");
        assertThat(lcscUsb("X", "1 5P Female Type-A USB 2.0", "Plugin")).containsEntry("PinConfiguration", "4")
                .containsEntry("ShieldPinsCounted", "1");
        assertThat(lcscUsb("X", "1 11P Female Type-A USB 3.0", "Plugin")).containsEntry("PinConfiguration", "9")
                .containsEntry("ShieldPinsCounted", "2");
        // the only shell-counted Type-C in the JLCPCB database: package "SMD-26P" (C9900163433, TYPE-C-24)
        assertThat(lcscUsb("TYPE-C-24", "Type-C not ROHS", "SMD-26P")).containsEntry("Positions", "26")
                .containsEntry("PinConfiguration", "24").containsEntry("ShieldPinsCounted", "2");
        // TME "PIN: 17" (Number of pins parameter) and Mouser "8 Positions" (10178589-00011LF, a 6-pin power-only part)
        assertThat(usbKeys(extractor.extract(RankingFixtures.tme("X17", "GCT", "Connector: USB C; socket; SMT; PIN: 17",
                "USB & IEEE1394 connectors", null, RankingFixtures.attrs("Type of connector", "USB C",
                        "Number of pins", "17", "Version", "USB 2.0")))))
                .containsEntry("Positions", "17").containsEntry("PinConfiguration", "16")
                .containsEntry("ShieldPinsCounted", "1").containsEntry("UsbStandard", "USB 2.0");
        assertThat(usbKeys(extractor.extract(RankingFixtures.mouser("10178589-00011LF", "Amphenol",
                "USB Connectors USB C Receptacle Right Angle 8 Positions G/F Sink 0.8mm IPX5", "USB Connectors", null,
                Map.of())))).containsEntry("Positions", "8").containsEntry("PinConfiguration", "6")
                .containsEntry("ShieldPinsCounted", "2").containsEntry("MountingStyle", "mid-mount")
                .containsEntry("Waterproof", "IPX5").containsEntry("Gender", "female");
    }

    @Test
    void explicitPinSumsInUsbDescriptions() {
        // forms found in the JLCPCB database: stacked ports, combos, pins + legs
        assertThat(lcscUsb("907-322A1101Y10210", "1.5A USB 2.0 2 Straight 4P+4P Female -55℃~+85℃ Type-A Plugin",
                "Plugin")).containsEntry("Positions", "8").containsEntry("PinConfiguration", "4")
                .containsEntry("Features", "multi-port");
        assertThat(lcscUsb("MU-221", "-30℃~+85℃ 11mm 2 20V 4P+14P 5A Female Through Hole Type-A、Type-C USB 2.0 插件",
                "Plugin")).containsEntry("UsbType", "Type-C").containsEntry("PinConfiguration", "14")
                .containsEntry("Features", "multi-port");
        assertThat(lcscUsb("2171800001", "-40℃~+85℃ 1 10000 times 30V 5A 8P+16P Black Female Type-C USB 2.0", "SMD"))
                .containsEntry("Positions", "24").containsEntry("PinConfiguration", "16")
                .containsEntry("UsbStandard", "USB 2.0").doesNotContainKey("Features");
        assertThat(lcscUsb("HX TYPE-C-2P+4J", "-30℃~+80℃ 1 2P+4J 3A 5V Black Female Right Angle Type-C", "SMD"))
                .containsEntry("Positions", "2").containsEntry("PinConfiguration", "2")
                .containsEntry("Features", "4 legs, power only");
        // a user's "16+2P": 16 signal contacts plus 2 shell pins
        assertThat(lcscUsb("X", "Type-C 16+2P Female", "SMD")).containsEntry("Positions", "18")
                .containsEntry("PinConfiguration", "16").containsEntry("ShieldPinsCounted", "2");
    }

    @Test
    void tmeUsbParameters() {
        // real TME parameters (2026-10-05, /products/parameters)
        Part midMount = RankingFixtures.tme("USB4500-03-0-A", "GCT",
                "Connector: USB C; socket; SMT; PIN: 16; horizontal; USB 2.0; 5A", "USB & IEEE1394 connectors", null,
                RankingFixtures.attrs("Version", "USB 2.0", "Connector variant", "middle board mount", "Number of pins",
                        "16", "Current rating", "5A", "Electrical mounting", "SMT", "Type of connector", "USB C",
                        "Connector", "socket", "Spatial orientation", "horizontal", "Rated voltage", "48V DC"));
        assertThat(usbKeys(extractor.extract(midMount))).containsExactlyEntriesOf(RankingFixtures.attrs(
                "ConnectorType", "usb-c", "Gender", "female", "Positions", "16", "Orientation", "right angle",
                "Mounting", "SMD", "UsbType", "Type-C", "UsbStandard", "USB 2.0", "UsbSpeedGbps", "0.48",
                "PinConfiguration", "16", "MountingStyle", "mid-mount", "Features", "mid-mount"));
        Part charging = RankingFixtures.tme("USB4736-GF-A-KIT", "GCT",
                "Connector: USB C; socket; SMT; PIN: 6; top board mount; IP67; 3A", "USB & IEEE1394 connectors", null,
                RankingFixtures.attrs("Type of connector", "USB C", "Connector", "socket", "Electrical mounting", "SMT",
                        "Number of pins", "6", "Connector variant", "top board mount", "IP rating", "IP67",
                        "Current rating", "3A", "Connectors application", "only for charging (6p)"));
        assertThat(usbKeys(extractor.extract(charging))).containsEntry("PinConfiguration", "6")
                .containsEntry("Waterproof", "IP67").containsEntry("MountingStyle", "top-mount")
                .containsEntry("Features", "waterproof, power only, top-mount, IP67").doesNotContainKey("UsbStandard");
        Part hybrid = RankingFixtures.tme("USB4056-03-A", "GCT",
                "Connector: USB C; socket; hybrid SMT/THT; PIN: 24; horizontal; 5A", "USB & IEEE1394 connectors", null,
                RankingFixtures.attrs("Type of connector", "USB C", "Connector", "socket", "Electrical mounting",
                        "hybrid SMT/THT", "Number of pins", "24", "Spatial orientation", "horizontal", "Version", "USB 3.2"));
        assertThat(usbKeys(extractor.extract(hybrid))).containsEntry("MountingStyle", "hybrid")
                .containsEntry("UsbStandard", "USB 3.x").containsEntry("PinConfiguration", "24");
        // "Data transfer rate" beats "Version": USB 4.0 + 20Gbps + Gen.2x2 is a 20 Gbps part
        Part gen2x2 = RankingFixtures.tme("CX90B1-24P/C", "HIROSE",
                "Connector: USB C; socket; CX; on PCBs; SMT; PIN: 24; horizontal; 5A", null, null,
                RankingFixtures.attrs("Type of connector", "USB C", "Connector", "socket", "Electrical mounting", "SMT",
                        "Number of pins", "24", "Version", "USB 4.0", "Data transfer rate", "20Gbps",
                        "Connector variant", "Gen.2x2"));
        assertThat(usbKeys(extractor.extract(gen2x2))).containsEntry("UsbStandard", "USB 3.2 Gen 2x2")
                .containsEntry("UsbSpeedGbps", "20");
        // TME "Version: USB 3.0" on a 16-pin part with "Data transfer rate: 0.48Gbps"
        Part cx90m = RankingFixtures.tme("CX90M-16P/C", "HIROSE",
                "Connector: USB C; socket; CX; on PCBs; SMT; PIN: 16; horizontal; 6A", null, null,
                RankingFixtures.attrs("Version", "USB 3.0", "Data transfer rate", "0.48Gbps", "Number of pins", "16",
                        "Electrical mounting", "SMT", "Type of connector", "USB C", "Connector", "socket"));
        assertThat(usbKeys(extractor.extract(cx90m))).containsEntry("UsbStandard", "USB 2.0");
        Part micro = RankingFixtures.tme("USB3131-30-0230-A", "GCT",
                "Connector: USB B micro; socket; THT; PIN: 5; straight; USB 2.0; 1.8A", "USB & IEEE1394 connectors", null,
                RankingFixtures.attrs("Type of connector", "USB B micro", "Connector", "socket", "Electrical mounting",
                        "THT", "Number of pins", "5", "Spatial orientation", "straight", "Version", "USB 2.0"));
        assertThat(usbKeys(extractor.extract(micro))).containsEntry("UsbType", "Micro-B")
                .containsEntry("ConnectorType", "micro usb").containsEntry("Orientation", "vertical")
                .containsEntry("Mounting", "THT");
        // TME cables and adapters are not board connectors
        Part cable = RankingFixtures.tme("80034", "BASEUS", "Cable; USB C plug,USB C plug 90° up/down; 1m; black; 10Gbps; 60W",
                "USB cables and adapters", null, Map.of());
        assertThat(extractor.extract(cable)).doesNotContainKey("ConnectorType").doesNotContainKey("UsbType");
        Part adapter = RankingFixtures.tme("USB-18", "ESPERANZA", "Adapter; USB A socket,USB C plug; Thread: M22; 1÷10mm",
                "USB & IEEE1394 connectors", null, Map.of());
        assertThat(extractor.extract(adapter)).doesNotContainKey("ConnectorType");
        Part supply = RankingFixtures.tme("51710", "GOOBAY", "Power supply: switching; 5VDC; 4.7A; 108W; Out: USB A socket,USB C",
                "Plug-in Power Supplies", null, Map.of());
        assertThat(extractor.extract(supply)).doesNotContainKey("ConnectorType");
    }

    @Test
    void mouserUsbDescriptions() {
        // Mouser's search returns no USB ProductAttributes (only Packaging / Standard Pack Qty): description only
        Map<String, String> uj20 = usbKeys(extractor.extract(RankingFixtures.mouser("UJ20-C-H-G-MSMT-4-P16-TR",
                "Same Sky", "USB Connectors Type C,2.0, Horizontal, Gold plated 3u, Mid Surface Mount 1.86mm, 16 pin, T&R",
                "USB Connectors", null, RankingFixtures.attrs("Packaging", "Reel, Cut Tape"))));
        assertThat(uj20).containsEntry("UsbType", "Type-C").containsEntry("UsbStandard", "USB 2.0")
                .containsEntry("PinConfiguration", "16").containsEntry("MountingStyle", "mid-mount")
                .containsEntry("Orientation", "right angle");
        // "3u" gold plating and "6.5H" height are not a capacitance / inductance of a connector
        Part plated = RankingFixtures.mouser("UJ20-C-H-G-MSMT-4-P16-TR", "Same Sky",
                "USB Connectors Type C,2.0, Horizontal, Gold plated 3u, Mid Surface Mount 1.86mm, 16 pin, T&R",
                "USB Connectors", null, Map.of());
        assertThat(extractor.extract(plated)).doesNotContainKey("Capacitance");
        assertThat(extractor.extract(RankingFixtures.mouser("UJ20-C-V-C-1-SMT-TR", "Same Sky",
                "USB Connectors USB Jack 2.0, Type-C, Vertical, Copper Alloy, Surface Mount, 6.5H, T&R", "USB Connectors",
                null, Map.of()))).doesNotContainKey("Inductance").containsEntry("UsbStandard", "USB 2.0")
                .containsEntry("Gender", "female").containsEntry("Orientation", "vertical");
        assertThat(usbKeys(extractor.extract(RankingFixtures.mouser("UJ31-CH-G-SMT-TR-67", "Same Sky",
                "USB Connectors Type C, USB 3.2 Gen 2x1, 10 Gbps, 20 Vdc, 5 A, Horizontal, Hybrid Mount Mounting Style, "
                        + "Hybrid Mount Contact Pin Type, Gold Flash, 24 Pins, IP67, USB Receptacle", "USB Connectors", null,
                Map.of())))).containsEntry("UsbStandard", "USB 3.2 Gen 2").containsEntry("UsbSpeedGbps", "10")
                .containsEntry("PinConfiguration", "24").containsEntry("MountingStyle", "hybrid")
                .containsEntry("Waterproof", "IP67").containsEntry("Gender", "female");
        assertThat(usbKeys(extractor.extract(RankingFixtures.mouser("CX90MW9-24P", "Hirose",
                "USB Connectors Receptacle, USB4, 24pos., 5A, right angle", "USB Connectors", null, Map.of()))))
                .containsEntry("UsbStandard", "USB4").containsEntry("Positions", "24")
                .containsEntry("UsbType", "Type-C");   // USB4 exists only on Type-C
        assertThat(usbKeys(extractor.extract(RankingFixtures.mouser("217184-0001", "Molex",
                "USB Connectors Mid-Mnt DR SMT 24Ckt Type C Rec.", "USB Connectors", null, Map.of()))))
                .containsEntry("Positions", "24").containsEntry("MountingStyle", "mid-mount")
                .containsEntry("Gender", "female");
        assertThat(usbKeys(extractor.extract(RankingFixtures.mouser("UJC-HP-3-SMT-TR", "Same Sky",
                "USB Connectors Type C, Power Only, 20 Vdc, 3 A, Horizontal, Surface Mount Anchor Pins Mounting Style, "
                        + "Surface Mount Contact Pin Type, Gold Flash, Long Tabs, 6 Pins, Receptacle", "USB Connectors",
                null, Map.of())))).containsEntry("PinConfiguration", "6").containsEntry("Features", "power only");
        assertThat(usbKeys(extractor.extract(RankingFixtures.mouser("UJ2-MIBH-4-SMT-TR", "Same Sky",
                "USB Connectors USB 2.0 micro B jack 5 pin Horizontal SMT", "USB Connectors", null, Map.of()))))
                .containsEntry("UsbType", "Micro-B").containsEntry("PinConfiguration", "5")
                .containsEntry("UsbStandard", "USB 2.0").containsEntry("Gender", "female");
        // "5 Vdc" is no pin count
        assertThat(usbKeys(extractor.extract(RankingFixtures.mouser("UJ2-MIBH-G-SMT-TR", "Same Sky",
                "USB Connectors Micro B, USB 2.0, 480 Mbps, 5 Vdc, 1.8 A, Right Angle, Surface Mount Mounting Style, "
                        + "Surface Mount Contact Pin Type, Black Insulator, USB Receptacle", "USB Connectors", null,
                Map.of())))).doesNotContainKey("Positions").doesNotContainKey("PinConfiguration")
                .containsEntry("UsbStandard", "USB 2.0");
        assertThat(usbKeys(extractor.extract(RankingFixtures.mouser("USB-A3-S-RA", "Adam Tech",
                "USB Connectors USB 3.0 TYPE A FML RIGHT ANGLE T/H", "USB Connectors", null, Map.of()))))
                .containsEntry("UsbType", "Type-A").containsEntry("UsbStandard", "USB 3.2 Gen 1")
                .containsEntry("Gender", "female").containsEntry("Orientation", "right angle");
        assertThat(usbKeys(extractor.extract(RankingFixtures.mouser("DX07P024AJ5R1500", "JAE",
                "USB Connectors Type C USB 3.1 Gen 2 Slim Plug", "USB Connectors", null, Map.of()))))
                .containsEntry("Gender", "male").containsEntry("UsbStandard", "USB 3.2 Gen 2");
        assertThat(usbKeys(extractor.extract(RankingFixtures.mouser("USB4960-00-C", "GCT",
                "USB Connectors USB C 2.0, 16P, Receptacle, Vertical, SMT & TH stakes, H = 6.50mm,  T&R", "USB Connectors",
                null, Map.of())))).containsEntry("MountingStyle", "hybrid").containsEntry("PinConfiguration", "16")
                .containsEntry("UsbStandard", "USB 2.0").containsEntry("Orientation", "vertical");
    }

    private static Part jlcpcb(String code, String mpn, String description, String category, String pkg) {
        return RankingFixtures.lcsc(code, "ACME", mpn, description, category, pkg, Map.of());
    }

    /**
     * JLCPCB descriptions list voltages unlabelled and text-sorted: a MOSFET's gate threshold or a diode's forward voltage
     * often comes first. Transistors and diodes are rated by the largest voltage (real rows of the JLCPCB database).
     */
    @Test
    void lcscTransistorAndDiodeVoltageIsTheLargestStated() {
        String mosfets = "Transistors/Thyristors / MOSFETs";
        assertThat(extractor.extract(jlcpcb("C20917", "AO3400A",
                "-55℃~+150℃ 1 N-channel 1.45V 1.4W 30V 48mΩ@2.5V 5.7A 50pF 630pF 75pF 7nC@10V N-Channel", mosfets,
                "SOT-23"))).containsEntry("Voltage", "30V").containsEntry("Package", "SOT-23");
        assertThat(extractor.extract(jlcpcb("C181087", "SI2302",
                "-55℃~+150℃ 1 N-channel 1.2V 10nC@4.5V 110mΩ@2.5V 120pF 20V 237pF 3A 400mW 45pF", mosfets,
                "SOT-23"))).containsEntry("Voltage", "20V");
        assertThat(extractor.extract(jlcpcb("C8545", "2N7002",
                "1 N-channel 115mA 2.5V 225mW 25pF 50pF 5pF 5Ω@10V 60V N-Channel ±20V", mosfets, "SOT-23")))
                .containsEntry("Voltage", "60V");
        assertThat(extractor.extract(jlcpcb("C15127", "AO3401A",
                "1 P-Channel 14nC@10V 30V 47mΩ@10V、60mΩ@4.5V、85mΩ@2.5V 4A 55pF 645pF 80pF 900mV", mosfets, "SOT-23")))
                .containsEntry("Voltage", "30V");
        assertThat(extractor.extract(jlcpcb("C52895", "BSS138",
                "-55℃~+150℃ 1 N-channel 1.5V 2.4nC@25V 220mA 27pF 3.5Ω@10V 360mW 50V 6pF", mosfets, "SOT-23")))
                .containsEntry("Voltage", "50V");
        assertThat(extractor.extract(jlcpcb("C2099", "1N4148W",
                "1.25V@150mA 100V 150mA 1uA@75V 2A 4ns 500mW Standalone", "Diodes / Switching Diodes", "SOD-123")))
                .containsEntry("Voltage", "100V");
        assertThat(extractor.extract(jlcpcb("C8678", "SS34",
                "-55℃~+125℃ 3A 40V 500uA@40V 550mV@3A Independent", "Diodes / Schottky Diodes", "SMA(DO-214AC)")))
                .containsEntry("Voltage", "40V");
        assertThat(extractor.extract(jlcpcb("C77336", "MBR0530",
                "1 Independent 30V 5.5A 500mA 550mV@500mA 80uA@30V", "Diodes / Schottky Diodes", "SOD-123")))
                .containsEntry("Voltage", "30V");
        // only a threshold voltage: no rating rather than a wrong one
        assertThat(extractor.extract(jlcpcb("C1", "X", "1 N-channel 1.2V 3A 400mW", mosfets, "SOT-23")))
                .doesNotContainKey("Voltage");
    }

    /** Zener and regulator voltages are specifications: the largest-voltage rule does not apply to them. */
    @Test
    void lcscZenerAndRegulatorVoltagesUnchanged() {
        Part zener = jlcpcb("C2117", "BZT52C5V1",
                "-55℃~+150℃ 1 Independent 2uA 4.8V~5.4V 480Ω 5.1V 500mW 60Ω", "Diodes / Zener Diodes", "SOD-123");
        assertThat(extractor.features(zener).voltages()).containsExactly(5.1);
        assertThat(extractor.features(zener).family()).isEqualTo("zener");
        Part ldo = jlcpcb("C6186", "AMS1117-3.3", "-40℃~+125℃ 0.003%Vout 1 1.1V@(800mA) 15V 1A 3.3V 5mA "
                        + "72dB@(120Hz) Fixed Over Current Protection、Short Circuit Protection、Thermal shutdown Positive",
                "Power Management (PMIC) / Voltage Regulators - Linear, Low Drop Out (LDO) Regulators", "SOT-223");
        assertThat(extractor.features(ldo).voltages()).contains(3.3, 15.0);
    }

    /** {@code SOT-23 N-channel MOSFET 30V}: JLCPCB's 30 V N-channel parts meet the request (live: 37 of 40 excluded). */
    @Test
    void lcscMosfetMeetsThirtyVoltRequest() {
        ro.alacrity.kina.domain.ParsedQuery query = new QueryParser().parse("SOT-23 N-channel MOSFET 30V");
        Part ao3400 = jlcpcb("C20917", "AO3400A",
                "-55℃~+150℃ 1 N-channel 1.45V 1.4W 30V 48mΩ@2.5V 5.7A 50pF 630pF 75pF 7nC@10V N-Channel",
                "Transistors/Thyristors / MOSFETs", "SOT-23");
        DeterministicRanker.Assessment a = TestWiring.deterministicRanker(extractor).assess(query, ao3400);
        assertThat(a.mismatches()).isEmpty();
        assertThat(a.belowSpec()).isEmpty();
        assertThat(a.unverified()).doesNotContain("voltage");
        assertThat(ConstraintPolicy.DEFAULTS.check(query, extractor.features(ao3400)).conflicts()).isEmpty();
    }
}
