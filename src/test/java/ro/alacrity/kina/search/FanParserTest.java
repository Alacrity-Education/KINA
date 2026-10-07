package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.ParsedQueryResponse;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Fan requests (DESIGN.md 3.4 "Fans"): the family words, the type and supply, the frame size, the fan units with their
 * conversions, the bearing and the features; {@code axial} and {@code radial} stay capacitor mounting words elsewhere.
 */
class FanParserTest {

    private final QueryParser parser = new QueryParser();

    /** query | fan type | supply | frame size | bearing | features (comma separated) | keywords (space separated). */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', nullValues = "-", value = {
            "40x40x10 fan 12V                      | -      | -  | 40x40x10mm | -     | -      | -",
            "40x40x10mm fan 12V                    | -      | -  | 40x40x10mm | -     | -      | -",
            "fan 40 mm 5V                          | -      | -  | 40mm       | -     | -      | -",
            "120mm axial fan 12V PWM               | axial  | -  | 120mm      | -     | PWM    | -",
            "92x92x25 tubeaxial fan                | axial  | -  | 92x92x25mm | -     | -      | -",
            "radial blower 24V                     | radial | -  | -          | -     | -      | -",
            "centrifugal fan 230V AC               | radial | AC | -          | -     | -      | -",
            "squirrel cage blower 12V              | radial | -  | -          | -     | -      | -",
            "blower 50x15 mm 12V                   | radial | -  | 50x50x15mm | -     | -      | -",
            "DC fan 60x60x25 dual ball bearing     | -      | DC | 60x60x25mm | ball  | -      | -",
            "cooling fan 80mm sleeve bearing       | -      | -  | 80mm       | sleeve| -      | -",
            "fan 120mm fluid dynamic bearing       | -      | -  | 120mm      | fluid dynamic | - | -",
            "fan 140mm hydro bearing 4-wire        | -      | -  | 140mm      | fluid dynamic | 4-wire | -",
            "fan 12V tacho auto restart IP55       | -      | -  | -          | -     | tacho,auto restart,IP55 | -",
            "fan 24V FG locked rotor 3 wire        | -      | -  | -          | -     | tacho,locked rotor,3-wire | -",
            "AC fan 172x150x51 115V                | -      | AC | 172x150x51mm | -   | -      | -",
            "fan 12VDC 40x40x20 quiet              | -      | DC | 40x40x20mm | -     | -      | quiet"})
    void fanAttributesAreRead(String query, String type, String supply, String frame, String bearing,
                              String features, String keywords) {
        ParsedQuery q = parser.parse(query);
        assertThat(q.family()).isEqualTo("fan");
        ParsedQuery.Fan fan = q.fan();
        assertThat(fan).isNotNull();
        assertThat(fan.type()).isEqualTo(type);
        assertThat(fan.supply()).isEqualTo(supply);
        assertThat(fan.frame() == null ? null : fan.frame().display()).isEqualTo(frame);
        assertThat(fan.bearing()).isEqualTo(bearing == null ? null : bearing.strip());
        assertThat(fan.features()).isEqualTo(features == null ? List.of() : Arrays.stream(features.split(","))
                .map(String::strip).toList());
        assertThat(q.keywords()).isEqualTo(keywords == null ? List.of() : List.of(keywords.split(" ")));
        assertThat(q.mounting()).as("axial and radial name the fan type, not a mounting").isNull();
        assertThat(q.partNumbers()).as("a frame size is no part number").isEmpty();
    }

    /** query | kind | value in base units (rpm, m³/h, Pa, dBA) | display. */
    @ParameterizedTest(name = "{0} -> {1} {2}")
    @CsvSource(delimiter = '|', value = {
            "fan 3000rpm                | speed           | 3000      | 3000 rpm",
            "fan 3000 RPM               | speed           | 3000      | 3000 rpm",
            "fan 3k rpm                 | speed           | 3000      | 3000 rpm",
            "fan 3krpm                  | speed           | 3000      | 3000 rpm",
            "fan 2800 r/min             | speed           | 2800      | 2800 rpm",
            "fan 0...2000rpm            | speed           | 2000      | 2000 rpm",
            "fan 1000-2000 rpm          | speed           | 2000      | 2000 rpm",
            "fan 40 CFM                 | airflow         | 67.96044  | 68 m³/h (40 CFM)",
            "fan 40cfm                  | airflow         | 67.96044  | 68 m³/h (40 CFM)",
            "fan 1.2 m3/min             | airflow         | 72        | 72 m³/h (42.4 CFM)",
            "fan 1.2 m³/min             | airflow         | 72        | 72 m³/h (42.4 CFM)",
            "fan 70 m3/h                | airflow         | 70        | 70 m³/h (41.2 CFM)",
            "fan 70m³/h                 | airflow         | 70        | 70 m³/h (41.2 CFM)",
            "fan 600 l/min              | airflow         | 36        | 36 m³/h (21.2 CFM)",
            "fan 50 Pa                  | static_pressure | 50        | 50 Pa (5.1 mmH2O)",
            "fan 50Pa                   | static_pressure | 50        | 50 Pa (5.1 mmH2O)",
            "fan 0.2kPa                 | static_pressure | 200       | 200 Pa (20.4 mmH2O)",
            "fan 2.5 mmH2O              | static_pressure | 24.516625 | 24.5 Pa (2.5 mmH2O)",
            "fan 2.5mmAq                | static_pressure | 24.516625 | 24.5 Pa (2.5 mmH2O)",
            "fan 5 mmH₂O                | static_pressure | 49.03325  | 49 Pa (5 mmH2O)",
            "fan 0.1 inH2O              | static_pressure | 24.9089   | 24.9 Pa (2.54 mmH2O)",
            "fan 25 dBA                 | noise           | 25        | 25 dBA",
            "fan 25dB(A)                | noise           | 25        | 25 dBA",
            "fan 0.2A                   | current         | 0.2       | 200mA",
            "fan 200mA                  | current         | 0.2       | 200mA",
            "fan 24V DC                 | voltage         | 24        | 24V",
            "fan 230V AC                | voltage         | 230       | 230V"})
    void fanUnitsAreConverted(String query, String kind, double value, String display) {
        ParsedQuery.Constraint c = parser.parse(query).constraint(kind);
        assertThat(c).as(kind).isNotNull();
        assertThat(c.value()).isCloseTo(value, within(1e-6));
        assertThat(c.display()).isEqualTo(display);
    }

    @Test
    void fanUnitsAreReadInFanTextsOnly() {
        // the same letters elsewhere: 10pA of an op amp is a current, 80dB no noise rating, rpm a keyword
        assertThat(parser.parse("opamp input bias 10pA").constraint(ParsedQuery.CURRENT).value())
                .isCloseTo(1e-11, within(1e-15));
        assertThat(parser.parse("opamp CMRR 80dB").constraints()).doesNotContainKey(ParsedQuery.NOISE);
        assertThat(parser.parse("motor 3000rpm").constraints()).isEmpty();
        assertThat(parser.parse("fan 10pA").constraint(ParsedQuery.STATIC_PRESSURE)).isNotNull();
    }

    @Test
    void axialAndRadialStayCapacitorMountingWords() {
        ParsedQuery radial = parser.parse("100uF 25V radial electrolytic capacitor");
        assertThat(radial.mounting()).isEqualTo("THT");
        assertThat(radial.fan()).isNull();
        assertThat(parser.parse("axial 1N4007 diode").mounting()).isEqualTo("THT");
    }

    @Test
    void fanWordsYieldToOtherFamilies() {
        assertThat(parser.parse("4 pin fan connector").family()).isEqualTo("connector");
        assertThat(parser.parse("fan driver mosfet SOT-23").family()).isEqualTo("mosfet");
        assertThat(parser.parse("blower").family()).isEqualTo("fan");
        assertThat(parser.parse("fans").family()).isEqualTo("fan");
    }

    @Test
    void theParsedObjectNamesTheFanAttributes() {
        ParsedQueryResponse r = ParsedQueryResponse.from(parser.parse(
                "92x92x25mm DC axial fan 24V 0.3A 3000rpm 40 CFM 2.5 mmH2O 30 dBA ball bearing PWM tacho"));
        assertThat(r.family()).isEqualTo("fan");
        assertThat(r.fanType()).isEqualTo("axial");
        assertThat(r.fanSupply()).isEqualTo("DC");
        assertThat(r.frameSize()).isEqualTo("92x92x25mm");
        assertThat(r.bearing()).isEqualTo("ball");
        assertThat(r.fanFeatures()).containsExactly("PWM", "tacho");
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("voltage", "24V");
        expected.put("current", "300mA");
        expected.put("speed", "3000 rpm");
        expected.put("airflow", "68 m³/h (40 CFM)");
        expected.put("static_pressure", "24.5 Pa (2.5 mmH2O)");
        expected.put("noise", "30 dBA");
        assertThat(r.constraints()).containsExactlyEntriesOf(expected);
        assertThat(r.keywords()).isEmpty();
    }

    @Test
    void framesCompareWithinHalfAMillimetreAndTheDepthWhenBothStateIt() {
        ParsedQuery.Frame wanted = FanVocabulary.frame("40x40x10mm");
        assertThat(wanted.matches(FanVocabulary.frame("40 x 40 x 10.3 mm"))).isTrue();
        assertThat(wanted.matches(FanVocabulary.frame("40x40x10.6mm"))).as("depth within 1 mm").isTrue();
        assertThat(wanted.matches(FanVocabulary.frame("40x40x15mm"))).isFalse();
        assertThat(wanted.matches(FanVocabulary.frame("40x40x20mm"))).isFalse();
        assertThat(wanted.matches(FanVocabulary.frame("40.6x40.6x10mm"))).isFalse();
        assertThat(wanted.matches(FanVocabulary.frame("40mm"))).isTrue();
        assertThat(FanVocabulary.frame("120mm").matches(FanVocabulary.frame("120x120x38mm"))).isTrue();
        assertThat(FanVocabulary.frame("120mm").matches(FanVocabulary.frame("92x92x25mm"))).isFalse();
        assertThat(FanVocabulary.frame("97x94x33mm").matches(FanVocabulary.frame("94x97x33"))).isTrue();
        assertThat(FanVocabulary.frame("25mm×25mm×10mm").display()).isEqualTo("25x25x10mm");
        assertThat(FanVocabulary.frame("50*50*20mm").display()).isEqualTo("50x50x20mm");
        assertThat(FanVocabulary.frame("1x6 2.54mm")).isNull();
        assertThat(FanVocabulary.frame("lead length 300mm")).isNull();
    }
}
