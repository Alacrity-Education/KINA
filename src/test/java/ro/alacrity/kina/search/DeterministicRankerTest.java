package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
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
        assertThat(higher).isCloseTo(exact, within(1e-9));
        assertThat(lower).isCloseTo(exact - 2 * DeterministicRanker.W_RATING, within(1e-9));
    }

    @Test
    void toleranceMustBeAtMostTheRequestedOne() {
        String q = "10k 1% 0805";
        Part tight = part("T", "Resistor 10kΩ ±0.5% 0805", null, "0805", Map.of());
        Part exact = part("E", "Resistor 10kΩ ±1% 0805", null, "0805", Map.of());
        Part loose = part("L", "Resistor 10kΩ ±5% 0805", null, "0805", Map.of());
        assertThat(score(q, tight)).isCloseTo(score(q, exact), within(1e-9));
        assertThat(score(q, loose)).isCloseTo(score(q, exact) - 2 * DeterministicRanker.W_TOLERANCE, within(1e-9));
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
        assertThat(score("10uF X7R 0805", mlcc("M", "10uF", "25V", "X7R", "±10%", "2012")))
                .isCloseTo(score("10uF X7R 0805", mlcc("I", "10uF", "25V", "X7R", "±10%", "0805")), within(1e-9));
        double np0 = score("22pF C0G 0402", mlcc("N", "22pF", "50V", "NP0", "±5%", "0402"));
        double c0g = score("22pF NP0 0402", mlcc("C", "22pF", "50V", "C0G", "±5%", "0402"));
        double x7r = score("22pF C0G 0402", mlcc("X", "22pF", "50V", "X7R", "±5%", "0402"));
        assertThat(np0).isCloseTo(c0g, within(1e-9));
        assertThat(x7r).isCloseTo(np0 - 2 * DeterministicRanker.W_DIELECTRIC, within(1e-9));
        double sot = score("NPN transistor SOT-23", part("BC847", "Bipolar Transistors - BJT NPN 45V", null,
                "SOT-23-3", Map.of()));
        double sotOther = score("NPN transistor SOT-23", part("BC847W", "Bipolar Transistors - BJT NPN 45V", null,
                "SOT-323", Map.of()));
        assertThat(sot).isGreaterThan(sotOther + DeterministicRanker.W_PACKAGE);   // the mismatch is clamped at 0
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
}
