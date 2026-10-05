package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ro.alacrity.kina.domain.ParsedQuery;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class QueryParserTest {

    private final QueryParser parser = new QueryParser();

    /**
     * query | family | constraints ("kind=display;...") | dielectric | package | mounting | keywords (space separated).
     * Empty strings mean "absent".
     */
    static Stream<Arguments> table() {
        return Stream.of(
                row("10uF X7R 0805", "capacitor", "capacitance=10uF", "X7R", "0805", "", ""),
                row("10µF 25V X7R ±10% 0805", "capacitor", "capacitance=10uF;voltage=25V;tolerance=10%", "X7R", "0805", "", ""),
                row("4k7 0603 1%", "resistor", "resistance=4.7kohm;tolerance=1%", "", "0603", "", ""),
                row("100nF 50V X7R 0402", "capacitor", "capacitance=100nF;voltage=50V", "X7R", "0402", "", ""),
                row("10k resistor 0805 1% 1/8W", "resistor", "resistance=10kohm;power=125mW;tolerance=1%", "", "0805", "", ""),
                row("2.2uH 1A inductor 0806", "inductor", "inductance=2.2uH;current=1A", "", "0806", "", ""),
                row("SOT-23 NPN transistor 40V", "transistor", "voltage=40V", "", "SOT-23", "", "npn"),
                row("1N4148 SOD-123", "diode", "", "", "SOD-123", "", "1n4148"),
                row("LM358 SOIC-8", "", "", "", "SOIC-8", "", "lm358"),
                row("ESP32-WROOM-32", "", "", "", "", "", "esp32-wroom-32"),
                row("C0G 22pF 50V 0402", "capacitor", "capacitance=22pF;voltage=50V", "C0G", "0402", "", ""),
                row("100 ohm 0805", "resistor", "resistance=100ohm", "", "0805", "", ""),
                row("10R 1206", "resistor", "resistance=10ohm", "", "1206", "", ""),
                row("2R2 2512 1W", "resistor", "resistance=2.2ohm;power=1W", "", "2512", "", ""),
                row("Schottky diode 40V 3A SMA", "schottky", "voltage=40V;current=3A", "", "SMA", "", ""),
                row("3.3V LDO SOT-223 1A", "regulator", "voltage=3.3V;current=1A", "", "SOT-223", "", "ldo"),
                row("USB-C connector 16 pin", "connector", "", "", "", "", ""),
                row("12MHz crystal 3225", "crystal", "frequency=12MHz", "", "3225", "", ""),
                row("TVS diode 5V SOD-323", "tvs", "voltage=5V", "", "SOD-323", "", ""),
                row("0.1uF 16V X5R 0201", "capacitor", "capacitance=100nF;voltage=16V", "X5R", "0201", "", ""),
                row("NP0 100pF 0603", "capacitor", "capacitance=100pF", "C0G", "0603", "", ""),
                row("10uF 2012 MLCC", "capacitor", "capacitance=10uF", "", "0805", "", "mlcc"),
                row("4u7 0805", "capacitor", "capacitance=4.7uF", "", "0805", "", ""),
                row("4u7 inductor 1210", "inductor", "inductance=4.7uH", "", "1210", "", ""),
                row("N-channel MOSFET 30V 5A SOT-23", "mosfet", "voltage=30V;current=5A", "", "SOT-23", "", "n-channel"),
                row("1k 1% THT resistor", "resistor", "resistance=1kohm;tolerance=1%", "", "", "THT", ""),
                row("LED red 0603 SMD", "led", "", "", "0603", "SMD", "red"),
                row("10 uF 25 V", "capacitor", "capacitance=10uF;voltage=25V", "", "", "", ""),
                row("47uF 35V electrolytic capacitor through hole", "capacitor", "capacitance=47uF;voltage=35V", "", "", "THT", ""),
                row("100mΩ 2512 shunt", "resistor", "resistance=100mohm", "", "2512", "", ""),
                row("1MΩ 0402", "resistor", "resistance=1Mohm", "", "0402", "", ""),
                row("TO-220 MOSFET", "mosfet", "", "", "TO-220", "", ""),
                row("ferrite bead 600 ohm 0603", "ferrite", "resistance=600ohm", "", "0603", "", ""),
                row("op amp SOIC-8", "opamp", "", "", "SOIC-8", "", ""),
                row("32.768kHz crystal", "crystal", "frequency=32.768kHz", "", "", "", ""),
                row("Zener 5.1V SOD-123", "zener", "voltage=5.1V", "", "SOD-123", "", ""),
                row("±5% 10k 0402", "resistor", "resistance=10kohm;tolerance=5%", "", "0402", "", ""),
                row("2,2uF 0603", "capacitor", "capacitance=2.2uF", "", "0603", "", ""),
                row("STM32F103C8T6 LQFP-48", "", "", "", "LQFP-48", "", "stm32f103c8t6"),
                row("2012", "", "", "", "", "", "2012"),
                row("10uF 10V Y5V 1206 SMT", "capacitor", "capacitance=10uF;voltage=10V", "Y5V", "1206", "SMD", ""),
                row("4n7 50V", "capacitor", "capacitance=4.7nF;voltage=50V", "", "", "", ""),
                row("R47 1206", "resistor", "resistance=470mohm", "", "1206", "", ""),
                row("2N7002 SOT23", "", "", "", "SOT-23", "", "2n7002"),
                row("AMS1117-3.3 regulator SOT-223", "regulator", "", "", "SOT-223", "", "ams1117-3.3"),
                row("relay 5V coil", "relay", "voltage=5V", "", "", "", "coil"),
                row("100nF 0402 or 0603", "capacitor", "capacitance=100nF", "", "0402", "", "0603")
        );
    }

    private static Arguments row(String query, String family, String constraints, String dielectric, String pkg,
                                 String mounting, String keywords) {
        Map<String, String> expected = new LinkedHashMap<>();
        if (!constraints.isEmpty()) {
            for (String pair : constraints.split(";")) {
                String[] kv = pair.split("=", 2);
                expected.put(kv[0], kv[1]);
            }
        }
        List<String> kw = keywords.isEmpty() ? List.of() : Arrays.asList(keywords.split(" "));
        return Arguments.of(query, blankToNull(family), expected, blankToNull(dielectric), blankToNull(pkg),
                blankToNull(mounting), kw);
    }

    private static String blankToNull(String s) {
        return s.isEmpty() ? null : s;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("table")
    void parsesQuery(String query, String family, Map<String, String> constraints, String dielectric, String pkg,
                     String mounting, List<String> keywords) {
        ParsedQuery parsed = parser.parse(query);

        Map<String, String> actual = new LinkedHashMap<>();
        parsed.constraints().forEach((kind, c) -> actual.put(kind, c.display()));
        assertThat(parsed.originalText()).isEqualTo(query);
        assertThat(parsed.family()).as("family").isEqualTo(family);
        assertThat(actual).as("constraints").containsExactlyInAnyOrderEntriesOf(constraints);
        assertThat(parsed.dielectric()).as("dielectric").isEqualTo(dielectric);
        assertThat(parsed.packageName()).as("package").isEqualTo(pkg);
        assertThat(parsed.mounting()).as("mounting").isEqualTo(mounting);
        assertThat(parsed.keywords()).as("keywords").containsExactlyElementsOf(keywords);
    }

    /**
     * query | type | gender | positions | rows | pitch (mm) | orientation | mounting | keywords; empty = absent.
     */
    static Stream<Arguments> connectors() {
        return Stream.of(
                connector("90 degree dupont style female pin header 90 degree THT pins 6 position",
                        "female header", "female", "6", "", "2.54", "right angle", "THT", ""),
                connector("dupont female 1x6 right angle", "female header", "female", "6", "1", "2.54", "right angle", "", ""),
                connector("6 pin JST XH connector 2.5mm", "wire-to-board", "", "6", "", "2.5", "", "", ""),
                connector("USB-C receptacle 16 pin SMD", "usb-c", "female", "16", "", "", "", "SMD", ""),
                connector("2x5 box header 2.54mm", "box header", "male", "10", "2", "2.54", "", "", ""),
                connector("screw terminal block 2 position 5.08mm", "terminal block", "", "2", "", "5.08", "", "", ""),
                connector("1x40 pin header 2.54mm straight", "pin header", "male", "40", "1", "2.54", "vertical", "", ""),
                connector("2.54mm 2x20 female header", "female header", "female", "40", "2", "2.54", "", "", ""),
                connector("RJ45 jack with magnetics", "rj45", "female", "", "", "", "", "", "magnetics"),
                connector("2x3 female header right angle", "female header", "female", "6", "2", "", "right angle", "", ""),
                connector("female header 6 pos 90°", "female header", "female", "6", "", "", "right angle", "", ""),
                connector("0.1\" header 1x8 vertical", "header", "", "8", "1", "2.54", "vertical", "", ""),
                connector("pin socket 1*6 angled", "female header", "female", "6", "1", "", "right angle", "", ""),
                connector("micro USB receptacle SMD", "micro usb", "female", "", "", "", "", "SMD", ""),
                connector("FPC connector 0.5mm 24 pin horizontal", "fpc", "", "24", "", "0.5", "right angle", "", ""),
                connector("DB9 male connector", "d-sub", "male", "", "", "", "", "", ""),
                connector("4 circuits wire to board PH 2.0mm", "wire-to-board", "", "4", "", "2", "", "", ""),
                connector("6-way connector 3.5mm pitch", "connector", "", "6", "", "3.5", "", "", ""),
                connector("dual row pin header 20 pins 2 mm", "pin header", "male", "20", "2", "2", "", "", ""),
                connector("IC socket DIP-8", "ic socket", "female", "", "", "", "", "", ""),
                connector("header 1*6 2.54 180°", "header", "", "6", "1", "2.54", "vertical", "", ""),
                connector("barrel jack 2.1mm", "barrel jack", "female", "", "", "", "", "", "2.1mm"));
    }

    private static Arguments connector(String query, String type, String gender, String positions, String rows,
                                       String pitch, String orientation, String mounting, String keywords) {
        return Arguments.of(query, type, blankToNull(gender), positions.isEmpty() ? null : Integer.valueOf(positions),
                rows.isEmpty() ? null : Integer.valueOf(rows), pitch.isEmpty() ? null : Double.valueOf(pitch),
                blankToNull(orientation), blankToNull(mounting),
                keywords.isEmpty() ? List.of() : Arrays.asList(keywords.split(" ")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("connectors")
    void parsesConnectorQuery(String query, String type, String gender, Integer positions, Integer rows, Double pitch,
                              String orientation, String mounting, List<String> keywords) {
        ParsedQuery parsed = parser.parse(query);

        assertThat(parsed.isConnector()).as("connector").isTrue();
        assertThat(parsed.family()).as("family").isEqualTo("connector");
        ParsedQuery.Connector c = parsed.connector();
        assertThat(c.type()).as("type").isEqualTo(type);
        assertThat(c.gender()).as("gender").isEqualTo(gender);
        assertThat(c.positions()).as("positions").isEqualTo(positions);
        assertThat(c.rows()).as("rows").isEqualTo(rows);
        assertThat(c.pitchMm()).as("pitch").isEqualTo(pitch);
        assertThat(c.orientation()).as("orientation").isEqualTo(orientation);
        assertThat(parsed.mounting()).as("mounting").isEqualTo(mounting);
        assertThat(parsed.keywords()).as("keywords").containsExactlyElementsOf(keywords);
    }

    @Test
    void connectorDetailsAndNoise() {
        ParsedQuery failing = parser.parse("90 degree dupont style female pin header 90 degree THT pins 6 position");
        assertThat(failing.connector().pitchImplied()).isTrue();     // Dupont implies 2.54 mm
        assertThat(failing.constraints()).isEmpty();
        assertThat(parser.parse("6 pin JST XH connector 2.5mm").connector().series()).isEqualTo("XH");
        assertThat(parser.parse("JST PH 4 pin").connector().pitchMm()).isEqualTo(2.0);   // series pitch
        assertThat(parser.parse("JST PH 4 pin").connector().pitchImplied()).isTrue();
        assertThat(parser.parse("2x5 box header 2.54mm").connector().pitchImplied()).isFalse();
        // ratings stay ordinary constraints
        assertThat(parser.parse("female header 1x6 3A").constraint(ParsedQuery.CURRENT).display()).isEqualTo("3A");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notConnectors")
    void pinCountsOfIcsAreNotConnectorPositions(String query, String family, String pkg) {
        ParsedQuery parsed = parser.parse(query);
        assertThat(parsed.isConnector()).isFalse();
        assertThat(parsed.connector()).isNull();
        assertThat(parsed.family()).isEqualTo(family);
        assertThat(parsed.packageName()).isEqualTo(pkg);
    }

    static Stream<Arguments> notConnectors() {
        return Stream.of(
                Arguments.of("8 pin SOIC op amp", "opamp", "SOIC"),
                Arguments.of("LQFP-48 MCU", "mcu", "LQFP-48"),
                Arguments.of("SOT-23-6 LDO", "regulator", "SOT-23-6"),
                Arguments.of("SOIC-8 8 pin EEPROM", null, "SOIC-8"),
                Arguments.of("10uF X7R 0805", "capacitor", "0805"),
                Arguments.of("NE555 DIP-8", null, "DIP-8"));
    }

    @Test
    void valuesAreInSiBaseUnits() {
        ParsedQuery q = parser.parse("10µF 25V ±10% 0805");
        assertThat(q.constraint(ParsedQuery.CAPACITANCE).value()).isCloseTo(10e-6, within(1e-15));
        assertThat(q.constraint(ParsedQuery.VOLTAGE).value()).isEqualTo(25.0);
        assertThat(q.constraint(ParsedQuery.TOLERANCE).value()).isEqualTo(10.0);
        assertThat(parser.parse("4k7").constraint(ParsedQuery.RESISTANCE).value()).isCloseTo(4700, within(1e-9));
        assertThat(parser.parse("2R2").constraint(ParsedQuery.RESISTANCE).value()).isCloseTo(2.2, within(1e-12));
        assertThat(parser.parse("1/4W").constraint(ParsedQuery.POWER).value()).isEqualTo(0.25);
        assertThat(parser.parse("12MHz crystal").constraint(ParsedQuery.FREQUENCY).value()).isEqualTo(12e6);
        assertThat(parser.parse("100mA").constraint(ParsedQuery.CURRENT).value()).isCloseTo(0.1, within(1e-12));
    }

    @Test
    void normalizedKey() {
        assertThat(parser.parse("  10µF   X7R\t0805 ").normalizedKey()).isEqualTo("10uf x7r 0805");
        assertThat(parser.parse("100Ω ±1%").normalizedKey()).isEqualTo("100ohm ±1%");
        assertThat(parser.parse("4.7kΩ").normalizedKey()).isEqualTo(QueryParser.normalizeKey("4.7KOHM"));
        // NFKC: full-width digits and letters, micro sign vs Greek mu
        assertThat(QueryParser.normalizeKey("１０μＦ")).isEqualTo("10uf");
    }

    @Test
    void emptyQuery() {
        ParsedQuery q = parser.parse(null);
        assertThat(q.originalText()).isEmpty();
        assertThat(q.normalizedKey()).isEmpty();
        assertThat(q.constraints()).isEmpty();
        assertThat(q.keywords()).isEmpty();
        assertThat(q.family()).isNull();
    }

    // ------------------------------------------------------------------ USB connectors (DESIGN.md 3.4)

    /**
     * query | usb type | connector type | gender | positions | pin configuration (* = implied) | shield pins |
     * standard | mounting style | features (comma separated) | orientation | mounting; empty = absent.
     */
    static Stream<Arguments> usbConnectors() {
        return Stream.of(
                usb("USB-C receptacle 16 pin SMD USB 2.0", "Type-C", "usb-c", "female", "16", "16", "", "USB 2.0", "", "", "", "SMD"),
                usb("USB Type-C 24 pin USB 3.1 receptacle horizontal", "Type-C", "usb-c", "female", "24", "24", "", "USB 3.x", "", "", "right angle", ""),
                usb("USBC socket 16P", "Type-C", "usb-c", "female", "16", "16", "", "", "", "", "", ""),
                usb("Type C female connector 24P vertical", "Type-C", "usb-c", "female", "24", "24", "", "", "", "", "vertical", ""),
                usb("TypeC receptacle 6P", "Type-C", "usb-c", "female", "6", "6", "", "", "", "", "", ""),
                usb("USB 2.0 Type-C receptacle", "Type-C", "usb-c", "female", "", "16*", "", "USB 2.0", "", "", "", ""),
                usb("USB 3.2 Gen 2 Type-C receptacle", "Type-C", "usb-c", "female", "", "24*", "", "USB 3.2 Gen 2", "", "", "", ""),
                usb("USB 3.2 Gen 2x2 USB-C receptacle", "Type-C", "usb-c", "female", "", "24*", "", "USB 3.2 Gen 2x2", "", "", "", ""),
                usb("USB 3.1 Gen 1 type-c connector", "Type-C", "usb-c", "", "", "24*", "", "USB 3.2 Gen 1", "", "", "", ""),
                usb("USB4 USB-C receptacle", "Type-C", "usb-c", "female", "", "24*", "", "USB4", "", "", "", ""),
                usb("Thunderbolt 3 USB-C receptacle", "Type-C", "usb-c", "female", "", "24*", "", "Thunderbolt 3", "", "", "", ""),
                usb("USB-C 6 pin power only", "Type-C", "usb-c", "", "6", "6", "", "", "", "power only", "", ""),
                usb("USB-C receptacle charging only", "Type-C", "usb-c", "female", "", "6*", "", "", "", "power only", "", ""),
                usb("USB-C PD receptacle 24 pin", "Type-C", "usb-c", "female", "24", "24", "", "", "", "PD", "", ""),
                usb("mid-mount USB-C 16P", "Type-C", "usb-c", "", "16", "16", "", "", "mid-mount", "mid-mount", "", ""),
                usb("USB-C receptacle mid mount 16 pin", "Type-C", "usb-c", "female", "16", "16", "", "", "mid-mount", "mid-mount", "", ""),
                usb("USB-C receptacle sunken 16 pin", "Type-C", "usb-c", "female", "16", "16", "", "", "mid-mount", "mid-mount", "", ""),
                usb("USB-C receptacle top mount 16 pin", "Type-C", "usb-c", "female", "16", "16", "", "", "top-mount", "top-mount", "", ""),
                usb("USB-C receptacle 16 pin hybrid", "Type-C", "usb-c", "female", "16", "16", "", "", "hybrid", "hybrid", "", ""),
                usb("USB-C receptacle 16 pin SMD+THT", "Type-C", "usb-c", "female", "16", "16", "", "", "hybrid", "hybrid", "", ""),
                usb("USB-C receptacle 16 pin through-hole shell", "Type-C", "usb-c", "female", "16", "16", "", "", "hybrid", "through-hole shell", "", ""),
                usb("waterproof USB-C receptacle IP67", "Type-C", "usb-c", "female", "", "", "", "", "", "waterproof,IP67", "", ""),
                usb("USB-C receptacle IP68 24 pin", "Type-C", "usb-c", "female", "24", "24", "", "", "", "waterproof,IP68", "", ""),
                usb("USB-C receptacle with board lock 16 pin", "Type-C", "usb-c", "female", "16", "16", "", "", "", "board lock", "", ""),
                usb("USB-C receptacle 16 pin locating pegs 4 legs", "Type-C", "usb-c", "female", "16", "16", "", "", "", "board lock,4 legs", "", ""),
                usb("USB-C receptacle right angle 16 pin", "Type-C", "usb-c", "female", "16", "16", "", "", "", "", "right angle", ""),
                usb("USB-C receptacle upright 16 pin", "Type-C", "usb-c", "female", "16", "16", "", "", "", "", "vertical", ""),
                usb("USB-C plug 24 pin", "Type-C", "usb-c", "male", "24", "24", "", "", "", "", "", ""),
                usb("USB-C male 24 pin", "Type-C", "usb-c", "male", "24", "24", "", "", "", "", "", ""),
                usb("USB-C jack 16 pin", "Type-C", "usb-c", "female", "16", "16", "", "", "", "", "", ""),
                // shell / shield pins counted: 17/18 -> 16, 25/26 -> 24, 8 -> 6; 14 stays 14
                usb("17 pin USB-C", "Type-C", "usb-c", "", "17", "16", "1", "", "", "", "", ""),
                usb("USB-C receptacle 18P", "Type-C", "usb-c", "female", "18", "16", "2", "", "", "", "", ""),
                usb("USB-C receptacle 26 pin", "Type-C", "usb-c", "female", "26", "24", "2", "", "", "", "", ""),
                usb("USB-C receptacle 14 pin", "Type-C", "usb-c", "female", "14", "14", "", "", "", "", "", ""),
                usb("USB-C receptacle 16+2P", "Type-C", "usb-c", "female", "18", "16", "2", "", "", "", "", ""),
                usb("micro USB B receptacle 5 pin SMD", "Micro-B", "micro usb", "female", "5", "5", "", "", "", "", "", "SMD"),
                usb("microUSB socket", "Micro-B", "micro usb", "female", "", "", "", "", "", "", "", ""),
                usb("Micro-USB B connector", "Micro-B", "micro usb", "", "", "", "", "", "", "", "", ""),
                usb("USB 3.0 Micro-B receptacle", "Micro-B", "micro usb", "female", "", "10*", "", "USB 3.2 Gen 1", "", "", "", ""),
                usb("micro USB AB receptacle", "Micro-AB", "micro usb", "female", "", "", "", "", "", "", "", ""),
                usb("mini USB B receptacle THT", "Mini-B", "usb", "female", "", "", "", "", "", "", "", "THT"),
                usb("USB-A receptacle", "Type-A", "usb", "female", "", "", "", "", "", "", "", ""),
                usb("USB A plug 4 pin", "Type-A", "usb", "male", "4", "4", "", "", "", "", "", ""),
                usb("USB 3.0 Type-A receptacle THT", "Type-A", "usb", "female", "", "9*", "", "USB 3.2 Gen 1", "", "", "", "THT"),
                usb("USB 3.0 Type-A receptacle 9 pin", "Type-A", "usb", "female", "9", "9", "", "USB 3.2 Gen 1", "", "", "", ""),
                usb("USB-B receptacle THT", "Type-B", "usb", "female", "", "", "", "", "", "", "", "THT"),
                usb("USB C socket 16 SMT", "Type-C", "usb-c", "female", "16", "16", "", "", "", "", "", "SMD"));
    }

    private static Arguments usb(String query, String usbType, String type, String gender, String positions,
                                 String configuration, String shield, String standard, String style, String features,
                                 String orientation, String mounting) {
        return Arguments.of(query, usbType, type, blankToNull(gender), positions.isEmpty() ? null : Integer.valueOf(positions),
                configuration, shield.isEmpty() ? null : Integer.valueOf(shield), blankToNull(standard),
                blankToNull(style), features.isEmpty() ? List.of() : Arrays.asList(features.split(",")),
                blankToNull(orientation), blankToNull(mounting));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("usbConnectors")
    void parsesUsbConnectorQuery(String query, String usbType, String type, String gender, Integer positions,
                                 String configuration, Integer shield, String standard, String style,
                                 List<String> features, String orientation, String mounting) {
        ParsedQuery parsed = parser.parse(query);

        assertThat(parsed.isConnector()).as("connector").isTrue();
        ParsedQuery.Connector c = parsed.connector();
        assertThat(c.isUsb()).isTrue();
        assertThat(c.usbType()).as("usb type").isEqualTo(usbType);
        assertThat(c.type()).as("type").isEqualTo(type);
        assertThat(c.gender()).as("gender").isEqualTo(gender);
        assertThat(c.positions()).as("positions").isEqualTo(positions);
        Integer expectedConfiguration = configuration.isEmpty() ? null : Integer.valueOf(configuration.replace("*", ""));
        assertThat(c.pinConfiguration()).as("pin configuration").isEqualTo(expectedConfiguration);
        assertThat(c.pinConfigurationImplied()).as("implied").isEqualTo(configuration.endsWith("*"));
        assertThat(c.shieldPinsCounted()).as("shield pins").isEqualTo(shield);
        assertThat(c.usbStandard()).as("standard").isEqualTo(standard);
        assertThat(c.mountingStyle()).as("mounting style").isEqualTo(style);
        assertThat(c.features()).as("features").containsExactlyElementsOf(features);
        assertThat(c.orientation()).as("orientation").isEqualTo(orientation);
        assertThat(parsed.mounting()).as("mounting").isEqualTo(mounting);
        assertThat(parsed.keywords()).as("keywords").isEmpty();
    }

    @Test
    void usbStandardsHaveCanonicalSpeedClasses() {
        assertThat(parser.parse("USB 2.0 Type-C receptacle").connector().usbSpeedGbps()).isEqualTo(0.48);
        // USB 3.0 == USB 3.1 Gen 1 == USB 3.2 Gen 1 (5 Gbps)
        for (String q : new String[]{"USB 3.0 Type-C receptacle", "USB 3.1 Gen 1 Type-C receptacle",
                "USB 3.2 Gen 1 Type-C receptacle", "USB-C receptacle 5Gbps"}) {
            assertThat(parser.parse(q).connector().usbStandard()).as(q).isEqualTo("USB 3.2 Gen 1");
            assertThat(parser.parse(q).connector().usbSpeedGbps()).as(q).isEqualTo(5.0);
        }
        // USB 3.1 Gen 2 == USB 3.2 Gen 2 (10 Gbps)
        for (String q : new String[]{"USB 3.1 Gen 2 Type-C receptacle", "USB 3.2 Gen 2 Type-C receptacle",
                "USB-C receptacle 10Gbps"}) {
            assertThat(parser.parse(q).connector().usbStandard()).as(q).isEqualTo("USB 3.2 Gen 2");
            assertThat(parser.parse(q).connector().usbSpeedGbps()).as(q).isEqualTo(10.0);
        }
        assertThat(parser.parse("USB 3.2 Gen 2x2 Type-C receptacle").connector().usbSpeedGbps()).isEqualTo(20.0);
        assertThat(parser.parse("USB4 Type-C receptacle").connector().usbSpeedGbps()).isEqualTo(40.0);
        assertThat(parser.parse("Thunderbolt 4 Type-C receptacle").connector().usbSpeedGbps()).isEqualTo(40.0);
        // "USB 3.1" without a generation: the 3.x class, 5 Gbps minimum; "Gen 2x2" is not a 2x2 grid
        assertThat(parser.parse("USB 3.1 Type-C receptacle").connector().usbStandard()).isEqualTo("USB 3.x");
        assertThat(parser.parse("USB 3.2 Gen 2x2 Type-C receptacle").connector().rows()).isNull();
        // the parsed response exposes the USB fields
        var response = ro.alacrity.kina.domain.ParsedQueryResponse.from(parser.parse("17 pin USB-C receptacle"))
                .connector();
        assertThat(response.usbType()).isEqualTo("Type-C");
        assertThat(response.positions()).isEqualTo(17);
        assertThat(response.pinConfiguration()).isEqualTo(16);
        assertThat(response.shieldPinsCounted()).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("usbNotConnectors")
    void usbProductsThatAreNotConnectors(String query) {
        ParsedQuery parsed = parser.parse(query);
        assertThat(parsed.isConnector()).as(query).isFalse();
        assertThat(parsed.connector()).isNull();
    }

    static Stream<String> usbNotConnectors() {
        return Stream.of("USB to UART bridge IC", "USB ESD protection diode", "USB 5V 2A power adapter",
                "USB-C PD controller", "USB Type-C cable 1m", "USB 2.0 hub IC", "USB 3.0 ESD protection array",
                "USB-C to HDMI adapter", "CH340C USB serial chip");
    }
}
