package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.ParsedQueryResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * LED requests (DESIGN.md 3.4 "LEDs"): the family words, the colour (with the band a wavelength implies), the lens, the
 * LED type, the orientation, the LED package names (never metric chip codes) and the LED units.
 */
class LedParserTest {

    private final QueryParser parser = new QueryParser();

    /** query | colour | lens | type | orientation | package | mounting | keywords (space separated). */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', nullValues = "-", value = {
            "0603 red LED 20mA                  | red           | -        | -           | -            | 0603     | -   | -",
            "5mm white LED diffused             | white         | diffused | -           | -            | 5mm      | -   | -",
            "0805 blue LED 470nm                | blue          | -        | -           | -            | 0805     | -   | -",
            "RGB LED 5050                       | RGB           | -        | -           | -            | 5050     | -   | -",
            "IR LED 940nm 5mm                   | IR            | -        | -           | -            | 5mm      | -   | -",
            "infrared LED 850nm                 | IR            | -        | -           | -            | -        | -   | -",
            "LED 625nm 0603                     | red           | -        | -           | -            | 0603     | -   | -",
            "LED 590nm 0805                     | yellow        | -        | -           | -            | 0805     | -   | -",
            "LED 525nm                          | green         | -        | -           | -            | -        | -   | -",
            "UV LED 395nm 3535                  | UV            | -        | -           | -            | 3535     | -   | -",
            "ultraviolet LED 405nm              | UV            | -        | -           | -            | -        | -   | -",
            "warm white LED 3000K 2835          | warm white    | -        | -           | -            | 2835     | -   | -",
            "neutral white LED 4000 K 3030      | neutral white | -        | -           | -            | 3030     | -   | -",
            "cool white LED 6500K 5730          | cool white    | -        | -           | -            | 5730     | -   | -",
            "RGBW LED 5050                      | RGBW          | -        | -           | -            | 5050     | -   | -",
            "bicolor LED red green 3mm          | bi-colour     | -        | -           | -            | 3mm      | -   | -",
            "amber LED PLCC-2                   | amber         | -        | -           | -            | PLCC-2   | -   | -",
            "orange LED 3528                    | orange        | -        | -           | -            | 3528     | -   | -",
            "yellow green LED 0603              | yellow green  | -        | -           | -            | 0603     | -   | -",
            "pink LED 1206                      | pink          | -        | -           | -            | 1206     | -   | -",
            "water clear red LED 5mm            | red           | clear    | -           | -            | 5mm      | -   | -",
            "red LED 3mm tinted                 | red           | tinted   | -           | -            | 3mm      | -   | -",
            "milky white LED 5mm                | white         | diffused | -           | -            | 5mm      | -   | -",
            "frosted blue LED 5mm               | blue          | diffused | -           | -            | 5mm      | -   | -",
            "T-1 3/4 amber LED                  | amber         | -        | -           | -            | 5mm      | -   | -",
            "T-1 red LED                        | red           | -        | -           | -            | 3mm      | -   | -",
            "2x5x7 rectangular green LED        | green         | -        | -           | -            | 2x5x7mm  | -   | rectangular",
            "1.8mm red LED                      | red           | -        | -           | -            | 1.8mm    | -   | -",
            "10mm white LED                     | white         | -        | -           | -            | 10mm     | -   | -",
            "WS2812B 5050                       | -             | -        | addressable | -            | 5050     | -   | -",
            "addressable RGB LED SK6812 3535    | RGB           | -        | addressable | -            | 3535     | -   | -",
            "high power white LED 1W            | white         | -        | high power  | -            | -        | -   | -",
            "LED strip 12V white                | white         | -        | strip       | -            | -        | -   | -",
            "right angle green LED 0805         | green         | -        | -           | right angle  | 0805     | -   | -",
            "side view LED red 0603             | red           | -        | -           | right angle  | 0603     | -   | -",
            "reverse mount LED 1206 blue        | blue          | -        | -           | reverse mount | 1206    | -   | -",
            "SMD LED red                        | red           | -        | -           | -            | -        | SMD | -",
            "THT LED green 5mm                  | green         | -        | -           | -            | 5mm      | THT | -",
            "status LED 0603 green              | green         | -        | -           | -            | 0603     | -   | -",
            "PCB LED red                        | red           | -        | -           | -            | -        | -   | -",
            "panel indicator red                | red           | -        | -           | -            | -        | -   | panel"})
    void ledAttributesAreRead(String query, String colour, String lens, String type, String orientation, String pkg,
                              String mounting, String keywords) {
        ParsedQuery q = parser.parse(query);
        assertThat(q.family()).isEqualTo("led");
        ParsedQuery.Led led = q.led();
        assertThat(led).isNotNull();
        assertThat(led.colour()).isEqualTo(colour);
        assertThat(led.lens()).isEqualTo(lens);
        assertThat(led.type()).isEqualTo(type);
        assertThat(led.orientation()).isEqualTo(orientation);
        assertThat(q.packageName()).isEqualTo(pkg);
        assertThat(q.mounting()).isEqualTo(mounting);
        assertThat(q.keywords()).isEqualTo(keywords == null ? List.of() : List.of(keywords.split(" ")));
        assertThat(q.constraints()).as("a voltage of an LED request is its forward voltage")
                .doesNotContainKey(ParsedQuery.VOLTAGE);
    }

    /** query | kind | value in base units (nm, K, V, A, cd, lm, degrees) | display. */
    @ParameterizedTest(name = "{0} -> {1} {2}")
    @CsvSource(delimiter = '|', value = {
            "LED 625nm                 | wavelength          | 625     | 625nm",
            "LED 470 nm                | wavelength          | 470     | 470nm",
            "LED 3000K                 | colour_temperature  | 3000    | 3000 K",
            "LED 6500 K                | colour_temperature  | 6500    | 6500 K",
            "LED 2.0V                  | forward_voltage     | 2       | 2V",
            "LED Vf 3.2V               | forward_voltage     | 3.2     | 3.2V",
            "LED 20mA                  | current             | 0.02    | 20mA",
            "LED If 20 mA              | current             | 0.02    | 20mA",
            "LED 150mA high power      | current             | 0.15    | 150mA",
            "LED 200mcd                | luminous_intensity  | 0.2     | 200mcd",
            "LED 2000 mcd              | luminous_intensity  | 2       | 2cd",
            "LED 20 lm                 | luminous_flux       | 20      | 20lm",
            "LED 120°                  | viewing_angle       | 120     | 120°",
            "LED 30 deg                | viewing_angle       | 30      | 30°",
            "LED 120 degrees           | viewing_angle       | 120     | 120°"})
    void ledUnitsAreRead(String query, String kind, double value, String display) {
        ParsedQuery.Constraint c = parser.parse(query).constraint(kind);
        assertThat(c).as(kind).isNotNull();
        assertThat(c.value()).isCloseTo(value, within(1e-9));
        assertThat(c.display()).isEqualTo(display);
    }

    @Test
    void ledPackageNamesAreNeverMetricChipCodes() {
        for (String code : List.of("3528", "5050", "2835", "3014", "5730", "3030")) {
            assertThat(parser.parse("LED " + code).packageName()).as(code).isEqualTo(code);
        }
        // the imperial rule stays for chip codes: 0603 is imperial 0603, 1608 metric is 0603
        assertThat(parser.parse("LED 0603 red").packageName()).isEqualTo("0603");
        assertThat(parser.parse("LED 1608 metric red").packageName()).isEqualTo("0603");
        assertThat(Recognizers.samePackage("5050", "PLCC-6")).as("an LED size and a PLCC package").isNull();
        assertThat(Recognizers.samePackage("5mm", "3mm")).isFalse();
        assertThat(Recognizers.samePackage("5050", "3528")).isFalse();
        assertThat(Recognizers.samePackage("0603", "5050")).isFalse();
    }

    @Test
    void ledUnitsAreReadInLedTextsOnly() {
        // 120° is a temperature elsewhere, 3000K a resistance, 20 lm no flux
        assertThat(parser.parse("resistor 3000K").constraint(ParsedQuery.RESISTANCE).value())
                .isCloseTo(3e6, within(1e-3));
        assertThat(parser.parse("capacitor 105°").constraint(ParsedQuery.TEMPERATURE)).isNotNull();
        assertThat(parser.parse("LED 85°C").constraint(ParsedQuery.TEMPERATURE).value()).isCloseTo(85, within(1e-9));
        assertThat(parser.parse("LED 85°C").constraints()).doesNotContainKey(ParsedQuery.VIEWING_ANGLE);
    }

    @Test
    void ledWordsYieldToOtherFamilies() {
        assertThat(parser.parse("MOSFET to switch a 12V LED strip from a 3.3V GPIO").family()).isEqualTo("mosfet");
        assertThat(parser.parse("LED driver 1A buck").led().type()).isEqualTo("driver");
        assertThat(parser.parse("laser diode 650nm").family()).isEqualTo("diode");
        assertThat(parser.parse("photodiode 940nm").family()).isNull();
        assertThat(parser.parse("ws2812b").family()).isEqualTo("led");
        assertThat(parser.parse("ws2812b").keywords()).isEmpty();
    }

    @Test
    void theParsedObjectNamesTheLedAttributes() {
        ParsedQueryResponse r = ParsedQueryResponse.from(parser.parse(
                "warm white LED 2835 3000K 120° 20mA Vf 3.2V 20 lm diffused right angle"));
        assertThat(r.family()).isEqualTo("led");
        assertThat(r.packageName()).isEqualTo("2835");
        assertThat(r.led().colour()).isEqualTo("warm white");
        assertThat(r.led().lens()).isEqualTo("diffused");
        assertThat(r.led().type()).isNull();
        assertThat(r.led().orientation()).isEqualTo("right angle");
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("current", "20mA");
        expected.put("colour_temperature", "3000 K");
        expected.put("forward_voltage", "3.2V");
        expected.put("luminous_flux", "20lm");
        expected.put("viewing_angle", "120°");
        assertThat(r.constraints()).containsExactlyEntriesOf(expected);
        assertThat(r.keywords()).isEmpty();
    }

    @Test
    void theColourOfALedTextLeavesTheLensColourOut() {
        assertThat(LedVocabulary.colour("468nm 470nm Blue Frosted White Lens")).isEqualTo("blue");
        assertThat(LedVocabulary.colour("Top-mount White Yellow Lens")).isEqualTo("white");
        assertThat(LedVocabulary.colour("Green Lens Yellow Green")).isEqualTo("yellow green");
        assertThat(LedVocabulary.colour("Discrete Diode Frosted Red Lens Red")).isEqualTo("red");
        assertThat(LedVocabulary.colour("Discrete Diode Red, Yellow Green Water Clear")).isEqualTo("bi-colour");
        assertThat(LedVocabulary.colour("Red, Green, Blue")).isEqualTo("RGB");
        assertThat(LedVocabulary.colour("Emerald Green Top-mount Water Clear")).isEqualTo("green");
        assertThat(LedVocabulary.colour("infrared")).isEqualTo("IR");
        assertThat(LedVocabulary.colour("IR=10uA")).isNull();
        assertThat(LedVocabulary.band(625)).isEqualTo("red");
        assertThat(LedVocabulary.band(940)).isEqualTo("IR");
        assertThat(LedVocabulary.band(570)).isNull();
    }
}
