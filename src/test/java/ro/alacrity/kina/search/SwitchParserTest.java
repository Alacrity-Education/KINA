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
 * Switch requests (DESIGN.md 3.4 "Switches"): the family words and their guards ("switching", switch ICs), the switch
 * type, contacts, function, termination class, size and cut-out, positions, illumination, orientation, AC or DC and
 * the switch units.
 */
class SwitchParserTest {

    private final QueryParser parser = new QueryParser();

    /** query | type | contacts | function | termination | size | hole | positions | keywords (space separated). */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', nullValues = "-", value = {
            "tactile switch 6x6 SMD                     | tactile     | -       | -              | PCB           | 6x6mm     | -    | - | -",
            "tact switch 6x6x4.3 THT                    | tactile     | -       | -              | PCB           | 6x6x4.3mm | -    | - | -",
            "tactile switch 12x12                       | tactile     | -       | -              | -             | 12x12mm   | -    | - | -",
            "tactile 6x6 4.3mm height                   | tactile     | -       | -              | -             | 6x6x4.3mm | -    | - | -",
            "tactile 12mm                               | tactile     | -       | -              | -             | 12x12mm   | -    | - | -",
            "SPDT toggle switch panel mount solder lug  | toggle      | SPDT    | -              | solder lug    | -         | -    | - | -",
            "toggle switch for wire soldering ON-OFF-ON | toggle      | -       | ON-OFF-ON      | solder lug    | -         | -    | - | -",
            "toggle switch DPDT (ON)-OFF-(ON)           | toggle      | DPDT    | (ON)-OFF-(ON)  | -             | -         | -    | - | -",
            "SPST momentary pushbutton 12mm panel       | pushbutton  | SPST    | momentary      | panel         | -         | 12mm | - | -",
            "push button latching 16mm                  | pushbutton  | -       | latching       | -             | -         | 16mm | - | -",
            "pushbutton switch 22mm hole quick connect  | pushbutton  | -       | -              | quick connect | -         | 22mm | - | -",
            "DIP switch 8 position                      | DIP         | -       | -              | -             | -         | -    | 8 | -",
            "DIP-switch 4 way SMD                       | DIP         | -       | -              | PCB           | -         | -    | 4 | -",
            "slide switch SPDT THT                      | slide       | SPDT    | -              | PCB           | -         | -    | - | -",
            "rocker switch DPST ON-OFF snap-in          | rocker      | DPST    | ON-OFF         | panel         | -         | -    | - | -",
            "rotary switch 1P12T 12 position            | rotary      | SP12T   | -              | -             | -         | -    | 12 | -",
            "micro switch 1 Form C quick connect        | snap action | SPDT    | -              | quick connect | -         | -    | - | -",
            "limit switch SPST-NO screw terminals       | snap action | SPST-NO | -              | screw         | -         | -    | - | -",
            "keylock switch SPST                        | keylock     | SPST    | -              | -             | -         | -    | - | -",
            "reed switch normally open SPST             | reed        | SPST-NO | -              | -             | -         | -    | - | -",
            "switch 2P2T                                | -           | DPDT    | -              | -             | -         | -    | - | -",
            "switch 1xNO wire leads                     | -           | SPST-NO | -              | wire leads    | -         | -    | - | -",
            "navigation switch 5-way SMD                | navigation  | -       | -              | PCB           | -         | -    | 5 | -",
            "detector switch SMD                        | detector    | -       | -              | PCB           | -         | -    | - | -",
            "membrane switch                            | membrane    | -       | -              | -             | -         | -    | - | -"})
    void switchAttributesAreRead(String query, String type, String contacts, String function, String termination,
                                 String size, String hole, Integer positions, String keywords) {
        ParsedQuery q = parser.parse(query);
        assertThat(q.family()).isEqualTo("switch");
        ParsedQuery.Switch sw = q.sw();
        assertThat(sw).isNotNull();
        assertThat(sw.type()).isEqualTo(type == null ? null : type.strip());
        assertThat(sw.contacts() == null ? null : sw.contacts().display()).isEqualTo(contacts);
        assertThat(sw.function()).isEqualTo(function);
        assertThat(sw.termination()).isEqualTo(termination);
        assertThat(sw.size() == null ? null : sw.size().display()).isEqualTo(size);
        assertThat(sw.holeDiameter() == null ? null : java.math.BigDecimal.valueOf(sw.holeDiameter())
                .stripTrailingZeros().toPlainString() + "mm").isEqualTo(hole);
        assertThat(sw.positions()).isEqualTo(positions);
        assertThat(q.keywords()).isEqualTo(keywords == null ? List.of() : List.of(keywords.split(" ")));
        assertThat(q.packageName()).as("DIP is a switch type, no package").isNull();
    }

    /** query | illuminated | colour | orientation | supply. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', nullValues = "-", value = {
            "illuminated pushbutton red LED              | true | red  | -           | -",
            "pushbutton switch with blue ring 16mm       | true | blue | -           | -",
            "tactile switch right angle SMD              | -    | -    | right angle | -",
            "tactile switch side actuated                | -    | -    | right angle | -",
            "tactile switch top actuated                 | -    | -    | vertical    | -",
            "rocker switch 250VAC 16A                    | -    | -    | -           | AC",
            "toggle switch 12V DC 5A                     | -    | -    | -           | DC",
            "toggle switch 12V                           | -    | -    | -           | -"})
    void illuminationOrientationAndSupplyAreRead(String query, Boolean illuminated, String colour, String orientation,
                                                 String supply) {
        ParsedQuery.Switch sw = parser.parse(query).sw();
        assertThat(sw.illuminated()).isEqualTo(illuminated);
        assertThat(sw.illuminationColour()).isEqualTo(colour);
        assertThat(sw.orientation()).isEqualTo(orientation);
        assertThat(sw.voltageSupply()).isEqualTo(supply);
    }

    /** query | kind | value in base units (N, cycles, IP digits, V, A) | display. */
    @ParameterizedTest(name = "{0} -> {1} {2}")
    @CsvSource(delimiter = '|', value = {
            "tactile switch 160gf                | force      | 1.569064   | 1.57 N (160 gf)",
            "tactile switch 1.6N                 | force      | 1.6        | 1.6 N (163 gf)",
            "tactile switch 250 gf               | force      | 2.4516625  | 2.45 N (250 gf)",
            "tactile switch 100000 cycles        | life       | 100000     | 100000 cycles",
            "tactile switch 100,000 cycles       | life       | 100000     | 100000 cycles",
            "tactile switch 1,000,000 cycles     | life       | 1000000    | 1000000 cycles",
            "tactile switch 100k cycles          | life       | 100000     | 100000 cycles",
            "tactile switch 20 thousand cycles   | life       | 20000      | 20000 cycles",
            "slide switch 10000 times            | life       | 10000      | 10000 cycles",
            "tactile switch IP67                 | ip_rating  | 67         | IP67",
            "tactile switch IPX7                 | ip_rating  | 7          | IPX7",
            "tactile switch sealed               | ip_rating  | 67         | IP67",
            "rocker switch 10A 250VAC            | voltage    | 250        | 250V",
            "tactile switch 50mA                 | current    | 0.05       | 50mA"})
    void switchUnitsAreRead(String query, String kind, double value, String display) {
        ParsedQuery.Constraint c = parser.parse(query).constraint(kind);
        assertThat(c).as(kind).isNotNull();
        assertThat(c.value()).isCloseTo(value, within(1e-6));
        assertThat(c.display()).isEqualTo(display);
    }

    @Test
    void switchingAndSwitchIcsNameNoSwitch() {
        assertThat(parser.parse("switching regulator 5V").family()).isEqualTo("regulator");
        assertThat(parser.parse("switching diode SOD-123").family()).isEqualTo("diode");
        assertThat(parser.parse("switch mode power supply 12V").family()).isNull();
        assertThat(parser.parse("analog switch SPDT SOT-23-6").family()).isNull();
        assertThat(parser.parse("load switch 2A").family()).isNull();
        assertThat(parser.parse("Hall switch SOT-23").family()).isNull();
        assertThat(parser.parse("MOSFET to switch a 12V LED strip from a 3.3V GPIO").family()).isEqualTo("mosfet");
        assertThat(parser.parse("switch with LED").family()).isEqualTo("switch");
        assertThat(parser.parse("pushbutton switch with red LED").family()).isEqualTo("switch");
        assertThat(parser.parse("fan switch").family()).isEqualTo("switch");
        assertThat(parser.parse("micro USB B receptacle 5 pin SMD").family()).isEqualTo("connector");
    }

    @Test
    void switchUnitsAreReadInSwitchTextsOnly() {
        // 1.6N is a capacitance elsewhere, cycles no unit
        assertThat(parser.parse("capacitor 1.6n").constraint(ParsedQuery.CAPACITANCE)).isNotNull();
        assertThat(parser.parse("relay 100000 cycles").constraints()).doesNotContainKey(ParsedQuery.LIFE);
    }

    @Test
    void theParsedObjectNamesTheSwitchAttributes() {
        ParsedQueryResponse r = ParsedQueryResponse.from(parser.parse(
                "illuminated pushbutton switch SPST momentary 16mm solder lug 3A 250VAC IP67 green LED"));
        assertThat(r.family()).isEqualTo("switch");
        assertThat(r.sw().type()).isEqualTo("pushbutton");
        assertThat(r.sw().contacts()).isEqualTo("SPST");
        assertThat(r.sw().function()).isEqualTo("momentary");
        assertThat(r.sw().termination()).isEqualTo("solder lug");
        assertThat(r.sw().holeDiameter()).isEqualTo("16mm");
        assertThat(r.sw().illuminated()).isTrue();
        assertThat(r.sw().illuminationColour()).isEqualTo("green");
        assertThat(r.sw().voltageSupply()).isEqualTo("AC");
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("voltage", "250V");
        expected.put("current", "3A");
        expected.put("ip_rating", "IP67");
        assertThat(r.constraints()).containsExactlyEntriesOf(expected);
        assertThat(r.keywords()).isEmpty();
    }

    @Test
    void contactsFunctionsAndTerminationsCompareByTheirRules() {
        ParsedQuery.Contacts spst = SwitchVocabulary.contacts("SPST");
        assertThat(spst.grade(SwitchVocabulary.contacts("SPST-NO"))).isEqualTo(1.0);
        assertThat(SwitchVocabulary.contacts("SPST-NO").grade(SwitchVocabulary.contacts("SPST-NC"))).isEqualTo(-1.0);
        assertThat(SwitchVocabulary.contacts("SPST-NO").grade(spst)).as("NO asked, not stated").isNull();
        assertThat(spst.grade(SwitchVocabulary.contacts("SPDT"))).isEqualTo(-1.0);
        assertThat(ParsedQuery.Switch.functionGrade("momentary", "OFF-(ON)")).isEqualTo(1.0);
        assertThat(ParsedQuery.Switch.functionGrade("momentary", "ON-(OFF)")).isEqualTo(1.0);
        assertThat(ParsedQuery.Switch.functionGrade("momentary", "ON-OFF")).isEqualTo(-1.0);
        assertThat(ParsedQuery.Switch.functionGrade("latching", "ON-ON")).isEqualTo(1.0);
        assertThat(ParsedQuery.Switch.functionGrade("ON-OFF-ON", "ON-ON")).isEqualTo(-1.0);
        assertThat(ParsedQuery.Switch.functionGrade("momentary", "ON-OFF-(ON)")).as("mixed").isNull();
        assertThat(ParsedQuery.Switch.terminationGrade("PCB", "solder lug")).isEqualTo(-1.0);
        assertThat(ParsedQuery.Switch.terminationGrade("solder lug", "PCB")).isEqualTo(-1.0);
        assertThat(ParsedQuery.Switch.terminationGrade("panel", "quick connect")).isEqualTo(1.0);
        assertThat(ParsedQuery.Switch.terminationGrade("solder lug", "panel")).isNull();
        assertThat(ParsedQuery.Switch.terminationGrade("solder lug", "screw")).isEqualTo(-1.0);
        assertThat(ParsedQuery.Switch.typeGrade("pushbutton", "tactile")).isEqualTo(1.0);
        assertThat(ParsedQuery.Switch.typeGrade("tactile", "pushbutton")).isEqualTo(-1.0);
        assertThat(ParsedQuery.Switch.typeGrade("switch", "toggle")).isEqualTo(1.0);
        assertThat(ParsedQuery.Switch.typeGrade("switch", "IC")).isEqualTo(-1.0);
    }
}
