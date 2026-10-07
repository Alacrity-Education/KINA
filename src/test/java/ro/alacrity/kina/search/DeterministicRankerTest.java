package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.domain.ConstraintKind;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static ro.alacrity.kina.search.RankingFixtures.attrs;
import static ro.alacrity.kina.search.RankingFixtures.part;

class DeterministicRankerTest {

    private final QueryParser parser = new QueryParser();
    private final DeterministicRanker ranker = new DeterministicRanker(new ParametricExtractor());

    private static Part mlcc(String mpn, String capacitance, String voltage, String dielectric, String tolerance,
                             String pkg) {
        return part(mpn, "Capacitor: ceramic; MLCC; " + capacitance + "; " + voltage + "; " + dielectric + "; "
                + tolerance + "; SMD; " + pkg, "MLCC SMD capacitors", pkg, Map.of());
    }

    private double score(String query, Part part) {
        return ranker.score(parser.parse(query), part);
    }

    @Test
    void trueMatchOutranksWrongValuePackageDielectricAndFamily() {
        String q = "10uF 25V X7R ±10% 0805 MLCC";
        double match = score(q, mlcc("MATCH", "10uF", "25V", "X7R", "±10%", "0805"));
        double wrongValue = score(q, mlcc("VALUE", "1uF", "25V", "X7R", "±10%", "0805"));
        double wrongPackage = score(q, mlcc("PKG", "10uF", "25V", "X7R", "±10%", "1206"));
        double wrongDielectric = score(q, mlcc("DIEL", "10uF", "25V", "X5R", "±10%", "0805"));
        double wrongFamily = score(q, part("RES", "Resistor: thick film; SMD; 0805; 10kΩ; 125mW; ±1%",
                "SMD resistors", "0805", Map.of()));

        assertThat(match).isGreaterThan(0.9);
        assertThat(List.of(wrongValue, wrongPackage, wrongDielectric, wrongFamily)).allSatisfy(s -> {
            assertThat(s).isLessThan(match - 0.25);
            assertThat(s).isBetween(0.0, 1.0);
        });
    }

    @Test
    void ratingMustBeAtLeastTheRequestedOne() {
        String q = "10uF 25V X7R 0805";
        double exact = score(q, mlcc("A", "10uF", "25V", "X7R", "±10%", "0805"));
        double higher = score(q, mlcc("B", "10uF", "50V", "X7R", "±10%", "0805"));
        double lower = score(q, mlcc("C", "10uF", "16V", "X7R", "±10%", "0805"));
        double sixtyThree = score(q, mlcc("D", "10uF", "63V", "X7R", "±10%", "0805"));
        double hundred = score(q, mlcc("E", "10uF", "100V", "X7R", "±10%", "0805"));
        // a higher rating satisfies the minimum; the closest rating is preferred by a small margin (score only)
        assertThat(higher).isCloseTo(exact - DeterministicRanker.W_RATING_EXCESS / 2, within(1e-9));
        assertThat(exact).isGreaterThan(higher);
        assertThat(higher).isGreaterThan(sixtyThree);
        assertThat(sixtyThree).isGreaterThan(hundred);
        assertThat(hundred).isGreaterThan(lower);
        assertThat(lower).isCloseTo(exact - 2 * ConstraintKind.VOLTAGE_RATING.weight(), within(1e-9));
        // the match grade does not prefer: every part at or above the rating matches fully
        assertThat(ranker.assess(parser.parse(q), mlcc("B", "10uF", "50V", "X7R", "±10%", "0805")).match())
                .isEqualTo(1.0);
    }

    @Test
    void toleranceMustBeAtMostTheRequestedOne() {
        String q = "10k 1% 0805";
        Part tight = part("T", "Resistor 10kΩ ±0.5% 0805", null, "0805", Map.of());
        Part exact = part("E", "Resistor 10kΩ ±1% 0805", null, "0805", Map.of());
        Part loose = part("L", "Resistor 10kΩ ±5% 0805", null, "0805", Map.of());
        assertThat(score(q, tight)).isCloseTo(score(q, exact), within(1e-9));
        assertThat(score(q, loose)).isCloseTo(score(q, exact) - 2 * ConstraintKind.TOLERANCE.weight(), within(1e-9));
    }

    @Test
    void valueMatchesWithinOnePercentAcrossUnits() {
        String q = "4k7 0603";
        Part sameInOhms = part("A", "Thick film resistor 4700 ohm 0603", null, "0603", Map.of());
        Part sameInKohm = part("B", "Resistor", null, "0603", attrs("Resistance", "4.7 kOhms"));
        Part off = part("C", "Resistor 4.99kΩ 0603", null, "0603", Map.of());
        assertThat(score(q, sameInOhms)).isCloseTo(score(q, sameInKohm), within(1e-9));
        assertThat(score(q, off)).isLessThan(score(q, sameInOhms) - 0.5);
    }

    @Test
    void packageEquivalenceAndC0gEqualsNp0() {
        assertThat(score("10uF X7R 0805", mlcc("M", "10uF", "25V", "X7R", "±10%", "2012 metric")))
                .isCloseTo(score("10uF X7R 0805", mlcc("I", "10uF", "25V", "X7R", "±10%", "0805")), within(1e-9));
        double np0 = score("22pF C0G 0402", mlcc("N", "22pF", "50V", "NP0", "±5%", "0402"));
        double c0g = score("22pF NP0 0402", mlcc("C", "22pF", "50V", "C0G", "±5%", "0402"));
        double x7r = score("22pF C0G 0402", mlcc("X", "22pF", "50V", "X7R", "±5%", "0402"));
        assertThat(np0).isCloseTo(c0g, within(1e-9));
        assertThat(x7r).isCloseTo(np0 - 2 * ConstraintKind.DIELECTRIC.weight(), within(1e-9));
        double sot = score("NPN transistor SOT-23", part("BC847", "Bipolar Transistors - BJT NPN 45V", null,
                "SOT-23-3", Map.of()));
        double sotOther = score("NPN transistor SOT-23", part("BC847W", "Bipolar Transistors - BJT NPN 45V", null,
                "SOT-323", Map.of()));
        assertThat(sot).isGreaterThan(sotOther + ConstraintKind.PACKAGE.weight());   // the mismatch is clamped at 0
    }

    @Test
    void familyAndKeywords() {
        String q = "Schottky diode 40V 3A SMA";
        double schottky = score(q, part("SS34", "Schottky Diodes & Rectifiers 40V 3A SMA", null, null, Map.of()));
        double generic = score(q, part("S3A", "Rectifiers 50V 3A SMA", null, null, Map.of()));
        double tvs = score(q, part("SMAJ40A", "TVS Diodes 40V SMA", null, null, Map.of()));
        assertThat(schottky).isGreaterThan(generic).isGreaterThan(tvs);

        double withKeyword = score("ESP32-WROOM-32", part("ESP32-WROOM-32E-N4", "WiFi module", null, null, Map.of()));
        double without = score("ESP32-WROOM-32", part("NRF52840", "BLE module", null, null, Map.of()));
        assertThat(withKeyword - without).isCloseTo(DeterministicRanker.W_LEXICAL, within(1e-9));
    }

    @Test
    void regulatorOutputVoltageMustMatch() {
        String q = "3.3V LDO SOT-223";
        Part v33 = part("AMS1117-3.3", "LDO Voltage Regulators 3.3V 1A SOT-223", null, "SOT-223",
                attrs("Output Voltage", "3.3 V"));
        Part v50 = part("AMS1117-5.0", "LDO Voltage Regulators 5V 1A SOT-223", null, "SOT-223",
                attrs("Output Voltage", "5 V"));
        assertThat(score(q, v33)).isGreaterThan(score(q, v50));
    }

    @Test
    void tieBreakPrefersStockPriceAndJlcpcbLibraryAndIsCapped() {
        ParsedQuery q = parser.parse("10uF X7R 0805");
        Part base = RankingFixtures.part(Distributor.LCSC, "C1", "ACME", "X", "10uF X7R 0805", null, "0805", 10,
                null, Map.of(), Map.of());
        Part stocked = RankingFixtures.part(Distributor.LCSC, "C2", "ACME", "X", "10uF X7R 0805", null, "0805",
                1_000_000, "0.01", Map.of(), Map.of("library_type", "Basic"));
        assertThat(ranker.score(q, stocked) - ranker.score(q, base)).isPositive()
                .isLessThanOrEqualTo(DeterministicRanker.W_TIE_BREAK);
        assertThat(DeterministicRanker.tieBreak(stocked)).isCloseTo(DeterministicRanker.W_TIE_BREAK, within(1e-9));
    }

    @Test
    void scoresAreClampedAndStable() {
        ParsedQuery q = parser.parse("10uF 25V X7R ±10% 0805");
        Part allWrong = part("W", "Capacitor 1uF 6.3V Y5V ±20% 1206", "Capacitors", "1206", Map.of());
        assertThat(ranker.score(q, allWrong)).isEqualTo(0.0);
        Part match = mlcc("M", "10uF", "25V", "X7R", "±10%", "0805");
        double first = ranker.score(q, match);
        for (int i = 0; i < 5; i++) {
            assertThat(ranker.score(q, match)).isEqualTo(first);
        }
        assertThat(first).isLessThanOrEqualTo(1.0);
        assertThat(ranker.score(parser.parse(""), match)).isBetween(0.0, DeterministicRanker.W_TIE_BREAK);
    }

    // ---------------------------------------------------------------- connectors

    private static Part lcscHeader(String code, String layout, int positions, String orientation, String category) {
        return RankingFixtures.lcsc(code, "HCTL", "MPN-" + code, "-40℃~+105℃ " + layout + " 2.54mm 3A "
                + positions + "P 8.5mm Copper alloy " + orientation + " Square Hole", "Connectors / " + category,
                "P=2.54mm", Map.of());
    }

    @Test
    void connectorFeaturesOrderFemaleRightAngleHeaders() {
        String q = "female header 1x6 right angle 2.54mm";
        double exact = score(q, lcscHeader("C1", "1 1x6P", 6, "Right Angle Side", "Female Headers"));
        double dualRow = score(q, lcscHeader("C2", "2 2x3P", 6, "Right Angle Side", "Female Headers"));
        double straight = score(q, lcscHeader("C4", "1 1x6P", 6, "Vertical", "Female Headers"));
        double tenPin = score(q, lcscHeader("C3", "1 1x10P", 10, "Right Angle Side", "Female Headers"));
        double male = score(q, lcscHeader("C5", "1 1x6P", 6, "Right Angle", "Pin Headers"));
        double unrelated = score(q, part("RES", "Resistor: thick film; SMD; 0805; 10kΩ", "SMD resistors", "0805",
                Map.of()));

        // positions/rows/gender/orientation/pitch/type: +0.30 / -0.10 / ±0.20 / ±0.15 / ±0.15 / ±0.10
        assertThat(exact).isGreaterThan(dualRow);
        assertThat(dualRow).isGreaterThan(straight);
        assertThat(straight).isGreaterThan(tenPin);
        assertThat(straight).isGreaterThan(male);
        assertThat(tenPin).isGreaterThan(unrelated);
        assertThat(male).isGreaterThan(unrelated);
        assertThat(exact - dualRow).isCloseTo(ConstraintKind.ROWS.weight(), within(1e-9));
        assertThat(exact - straight).isCloseTo(2 * ConstraintKind.ORIENTATION.weight(), within(1e-9));
        assertThat(exact - tenPin).isCloseTo(2 * ConstraintKind.POSITIONS.weight(), within(1e-9));
        assertThat(exact - male).isCloseTo(2 * ConstraintKind.GENDER.weight() + 2 * ConstraintKind.CONNECTOR_TYPE.weight(),
                within(1e-9));
        assertThat(exact).isGreaterThan(0.9);
    }

    @Test
    void failingQueryRanksRightAngleFemaleSixPinHeadersFirst() {
        String q = "90 degree dupont style female pin header 90 degree THT pins 6 position";
        Part femaleRa = lcscHeader("C2897388", "1 1x6P", 6, "Right Angle Side", "Female Headers");
        Part maleStraight = RankingFixtures.lcsc("C2337", "BOOMELE", "2.54-1*6P", "1 1x6P 2.54mm 3A 6P Pin Header"
                + " Through Hole 插件,P=2.54mm", "Connectors / Pin Headers", "Plugin,P=2.54mm", Map.of());
        Part tme = RankingFixtures.tme("DS1024-1*6RF1", "CONNFLY",
                "Connector: pin strips; socket; female; PIN: 6; THT; angled 90°; 3A", "Pin headers", null, Map.of());
        Part dualRow = lcscHeader("C2897423", "2 2x3P", 6, "Right Angle Side", "Female Headers");
        assertThat(score(q, femaleRa)).isGreaterThan(score(q, dualRow));
        // positions unspecified rows: single-row parts are mildly preferred
        assertThat(score(q, femaleRa) - score(q, dualRow))
                .isCloseTo(ConstraintKind.SINGLE_ROW.weight(), within(1e-9));
        assertThat(score(q, dualRow)).isGreaterThan(score(q, maleStraight) + 0.5);
        assertThat(score(q, tme)).isGreaterThan(0.85);
    }

    @Test
    void pitchIsComparedInMillimetresAndUnknownAttributesAreNeutral() {
        Part metric = lcscHeader("C1", "1 1x6P", 6, "Right Angle Side", "Female Headers");
        assertThat(score("0.1\" female header 1x6 right angle", metric))
                .isCloseTo(score("2.54mm female header 1x6 right angle", metric), within(1e-9));
        Part twoMm = RankingFixtures.lcsc("C9", "HCTL", "M", "1 1x6P 2mm 6P Right Angle", "Connectors / Female Headers",
                "P=2mm", Map.of());
        assertThat(score("2.54mm female header 1x6 right angle", metric)
                - score("2.54mm female header 1x6 right angle", twoMm))
                .isCloseTo(2 * ConstraintKind.PITCH.weight(), within(1e-9));
        // nothing known about the part's connector attributes: no penalty, only the family signal
        Part bare = RankingFixtures.lcsc("C8", "ACME", "M", "", "Connectors / Connectors", null, Map.of());
        ParsedQuery query = parser.parse("female header 1x6 right angle 2.54mm");
        assertThat(DeterministicRanker.connectorScore(query, new ParametricExtractor().features(bare))).isZero();
    }

    @Test
    void connectorWeightsReplaceThePrimaryValue() {
        // a 16-pin USB-C query does not use the C/R/L signal, and SMD vs THT counts
        String q = "USB-C receptacle 16 pin SMD";
        Part smd = RankingFixtures.lcsc("C2765186", "SHOU HAN", "TYPE-C 16PIN", "16P 3A 5V Black Female Surface Mount,"
                + " Right Angle Type-C", "Connectors / USB Connectors", "SMD", Map.of());
        Part tht = RankingFixtures.tme("USB4085-GF-A", "GCT", "Connector: USB C; socket; THT; PIN: 16; horizontal",
                "USB & IEEE1394 connectors", null, Map.of());
        assertThat(score(q, smd) - score(q, tht)).isCloseTo(2 * ConstraintKind.USB_MOUNTING.weight(),
                within(0.011));   // tie-break (library type) differs by up to 0.01
        assertThat(ConstraintKind.POSITIONS.weight() + ConstraintKind.GENDER.weight() + ConstraintKind.ORIENTATION.weight()
                + ConstraintKind.PITCH.weight() + ConstraintKind.CONNECTOR_TYPE.weight()).isCloseTo(0.90, within(1e-9));
    }

    // ------------------------------------------------------------------ USB connectors

    private static Part usbC(String code, String description) {
        return RankingFixtures.lcsc(code, "ACME", "M-" + code, description, "Connectors / USB Connectors", "SMD",
                Map.of());
    }

    private static final Part TYPE_C_16 = usbC("C16", "1 16P 5A Black Female Surface Mount, Right Angle Type-C USB 2.0");
    private static final Part TYPE_C_17 = usbC("C17", "1 17P 5A Black Female Surface Mount, Right Angle Type-C USB 2.0");
    private static final Part TYPE_C_18 = usbC("C18", "1 18P 5A Black Female Surface Mount, Right Angle Type-C USB 2.0");
    private static final Part TYPE_C_14 = usbC("C14", "1 14P 5A Black Female Surface Mount, Right Angle Type-C USB 2.0");
    private static final Part TYPE_C_24 = usbC("C24", "1 24P 5A Black Female Surface Mount, Right Angle Type-C USB 3.1");
    private static final Part TYPE_C_25 = usbC("C25", "1 25P 5A Black Female Surface Mount, Right Angle Type-C USB 3.1");
    private static final Part TYPE_C_26 = usbC("C26", "1 26P 5A Black Female Surface Mount, Right Angle Type-C USB 3.1");
    private static final Part TYPE_C_6 = usbC("C6", "1 3A 5V 6P Black Female Surface Mount, Right Angle Type-C");
    private static final Part MICRO_B = usbC("C5", "1 1A 5P Black Female Micro-B Surface Mount, Right Angle USB 2.0");
    /** LCSC labels many 16P Type-C parts "USB 3.1" (physically USB 2.0). */
    private static final Part TYPE_C_16_LABELLED_31 = usbC("C161",
            "1 16P 5A Black Female Surface Mount, Right Angle Type-C USB 3.1");

    @Test
    void usbC16PinUsb20Request() {
        String q = "USB-C receptacle 16 pin SMD USB 2.0";
        // 17P/18P parts count 1-2 shell pins: the same 16-pin configuration, full credit, tied with 16P
        assertThat(score(q, TYPE_C_17)).isEqualTo(score(q, TYPE_C_16));
        assertThat(score(q, TYPE_C_18)).isEqualTo(score(q, TYPE_C_16));
        assertThat(score(q, TYPE_C_16)).isGreaterThan(score(q, TYPE_C_14));
        assertThat(score(q, TYPE_C_14)).isGreaterThan(score(q, TYPE_C_24));   // 14P is USB 2.0, 24P a higher class
        assertThat(score(q, TYPE_C_24)).isGreaterThan(score(q, TYPE_C_6));    // power only: no data
        assertThat(score(q, TYPE_C_6)).isGreaterThan(score(q, MICRO_B));      // a Micro-B never beats a Type-C
        // the ranked order
        List<Part> ranked = List.of(MICRO_B, TYPE_C_6, TYPE_C_24, TYPE_C_14, TYPE_C_18, TYPE_C_17, TYPE_C_16).stream()
                .sorted((a, b) -> Double.compare(score(q, b), score(q, a))).toList();
        assertThat(ranked.subList(0, 3)).containsExactlyInAnyOrder(TYPE_C_16, TYPE_C_17, TYPE_C_18);
        assertThat(ranked.subList(3, 7)).containsExactly(TYPE_C_14, TYPE_C_24, TYPE_C_6, MICRO_B);
        // the USB signals: type + pins + standard + gender + mounting
        ParsedQuery query = parser.parse(q);
        ParametricExtractor extractor = new ParametricExtractor();
        assertThat(DeterministicRanker.connectorScore(query, extractor.features(TYPE_C_17)))
                .isCloseTo(ConstraintKind.USB_TYPE.weight() + ConstraintKind.PIN_CONFIGURATION.weight()
                        + ConstraintKind.USB_STANDARD.weight() + ConstraintKind.USB_GENDER.weight()
                        + ConstraintKind.USB_MOUNTING.weight(), within(1e-9));
        // higher speed class than requested: half credit for the standard, wrong pins
        assertThat(DeterministicRanker.connectorScore(query, extractor.features(TYPE_C_24)))
                .isCloseTo(ConstraintKind.USB_TYPE.weight() - ConstraintKind.PIN_CONFIGURATION.weight()
                        + ConstraintKind.USB_STANDARD.weight() / 2 + ConstraintKind.USB_GENDER.weight()
                        + ConstraintKind.USB_MOUNTING.weight(), within(1e-9));
        assertThat(DeterministicRanker.connectorScore(query, extractor.features(MICRO_B)))
                .isCloseTo(-ConstraintKind.USB_TYPE.weight() - ConstraintKind.PIN_CONFIGURATION.weight()
                        + ConstraintKind.USB_STANDARD.weight() + ConstraintKind.USB_GENDER.weight()
                        + ConstraintKind.USB_MOUNTING.weight(), within(1e-9));
    }

    @Test
    void usbC24PinUsb31Request() {
        String q = "USB Type-C 24 pin USB 3.1";
        assertThat(score(q, TYPE_C_25)).isEqualTo(score(q, TYPE_C_24));
        assertThat(score(q, TYPE_C_26)).isEqualTo(score(q, TYPE_C_24));
        for (Part lower : List.of(TYPE_C_16, TYPE_C_17, TYPE_C_16_LABELLED_31, TYPE_C_6, MICRO_B)) {
            assertThat(score(q, TYPE_C_26)).as(lower.description()).isGreaterThan(score(q, lower));
        }
        // a 16P "USB 3.1" label is USB 2.0: standard and pins mismatch like any 16-pin part
        assertThat(score(q, TYPE_C_16_LABELLED_31)).isEqualTo(score(q, TYPE_C_16));
    }

    @Test
    void pinConfigurationImpliedByTheStandardCountsHalf() {
        // "USB 2.0 Type-C" implies 16 pins, "USB 3.1 Type-C" 24; the pin signal then has half weight
        String usb2 = "USB 2.0 Type-C receptacle";
        assertThat(score(usb2, TYPE_C_16)).isGreaterThan(score(usb2, TYPE_C_24));
        assertThat(score(usb2, TYPE_C_24)).isGreaterThan(score(usb2, TYPE_C_6));
        assertThat(score(usb2, TYPE_C_16) - score(usb2, TYPE_C_14)).isCloseTo(ConstraintKind.PIN_CONFIGURATION.weight(),
                within(1e-9));   // +W/2 vs -W/2
        String usb3 = "USB 3.1 Type-C receptacle";
        assertThat(score(usb3, TYPE_C_24)).isGreaterThan(score(usb3, TYPE_C_16_LABELLED_31));
        assertThat(score(usb3, TYPE_C_24)).isGreaterThan(score(usb3, TYPE_C_16));
        // power-only request: 6P first
        String power = "USB-C receptacle power only";
        assertThat(score(power, TYPE_C_6)).isGreaterThan(score(power, TYPE_C_16));
    }

    @Test
    void usbMountingStyleAndFeatures() {
        Part midMount = usbC("CM", "1 16P 5A Black Female Laminated board Type-C USB 2.0");
        Part topMount = RankingFixtures.lcsc("CT", "ACME", "M-CT", "Connector: USB C; socket; SMT; PIN: 16; horizontal",
                "Connectors / USB Connectors", "SMD", RankingFixtures.attrs("Connector variant", "top board mount"));
        String q = "mid-mount USB-C 16P";
        assertThat(score(q, midMount) - score(q, topMount)).isCloseTo(2 * ConstraintKind.USB_MOUNTING.weight(),
                within(1e-9));
        // a hybrid part (SMD + through-hole shell) counts half for an SMD request
        Part hybrid = usbC("CH", "1 16P 5A Black Female Hybrid SMT/THT Right Angle Type-C USB 2.0");
        Double half = ConstraintKind.usbMounting(null, "SMD",
                new ParametricExtractor().features(hybrid).connector(), "SMD");
        assertThat(half).isEqualTo(0.5);
        // requested features present earn a small bonus
        Part sealed = usbC("CW", "1 16P 5A Black Female Surface Mount, Right Angle Type-C USB 2.0 with O-ring");
        String waterproof = "waterproof USB-C receptacle 16 pin";
        assertThat(score(waterproof, sealed) - score(waterproof, TYPE_C_16)).isCloseTo(ConstraintKind.WATERPROOF.weight(),
                within(1e-9));
        // gender: a plug request ranks plugs first
        Part plug = usbC("CP", "1 24P 5A Black Clamping plate Male Type-C USB 3.1");
        assertThat(score("USB-C plug 24 pin", plug)).isGreaterThan(score("USB-C plug 24 pin", TYPE_C_24));
        // a non-USB connector is a type mismatch for a USB request
        Part header = RankingFixtures.lcsc("CX", "ACME", "M", "1x16P 2.54mm Female Header Surface Mount",
                "Connectors / Female Headers", "SMD", Map.of());
        assertThat(score("USB-C receptacle 16 pin SMD", TYPE_C_16)).isGreaterThan(score("USB-C receptacle 16 pin SMD", header) + 0.5);
    }
}
