package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Switch attributes read from the recorded TME parameters and the Mouser and LCSC descriptions (DESIGN.md 3.4
 * "Switches", "Attribute sources"): type, contacts, function, termination class, size, cut-out, positions,
 * illumination, AC and DC ratings, IP code, life and force.
 */
class SwitchExtractionTest {

    static final String FAMILY = "switches";
    static final List<String> FIXTURES = List.of("tactile-switch-6x6-smd", "spdt-toggle-switch-panel-mount-solder-lug",
            "spst-momentary-pushbutton-12mm-panel", "dip-switch-8-position", "slide-switch-spdt-tht");

    private final ParametricExtractor extractor = new ParametricExtractor();

    /** fixture | distributor | mpn | expected canonical attributes ("Key=value;..."). */
    @ParameterizedTest(name = "{1} {2}")
    @CsvSource(delimiter = '|', value = {
            // TME parameters: "Type of switch", "Contacts configuration", "Switching method", "Leads", "Mounting",
            // "Body dimensions", "AC contacts rating @R" = 2A / 250V AC, "Mechanical durability" = 30000 cycles
            "slide-switch-spdt-tht | TME | MSS13ASP | SwitchType=slide;Contacts=SPDT;SwitchFunction=ON-ON;"
                    + "Termination=PCB;Mounting=THT;SwitchSize=12.7x6.6x6.35mm;VoltageAC=250V;Current=2A;"
                    + "Life=30000 cycles",
            // "Poles number" = 8 is the number of switches of a DIP switch ("Number of positions" = 2 is each one's)
            "dip-switch-8-position | TME | DM-08-V | SwitchType=DIP;SwitchPositions=8;Mounting=SMD;Termination=PCB;"
                    + "VoltageDC=50V;Force=7.8 N (795 gf);Life=2000 cycles",
            // "Leads": for soldering on panel, connectors, M3 screws, "(ON)-OFF"
            "spdt-toggle-switch-panel-mount-solder-lug | TME | 636H/2 | Termination=solder lug;VoltageAC=250V;"
                    + "VoltageDC=12V;Current=15A",
            "spdt-toggle-switch-panel-mount-solder-lug | TME | C3910BA | Termination=quick connect;IpRating=IP40",
            "spdt-toggle-switch-panel-mount-solder-lug | TME | KN3(C)102AA2 | Termination=screw",
            "spdt-toggle-switch-panel-mount-solder-lug | TME | 5236AB | Termination=PCB;Mounting=THT",
            "spdt-toggle-switch-panel-mount-solder-lug | TME | 1TL1-6 | SwitchFunction=(ON)-OFF;IpRating=IP67;"
                    + "VoltageAC=230V;VoltageDC=28V",
            // "Illumination: none", "Illumin: LED", "Cutout: Ø12mm", "ON-(OFF)" (a normally closed pushbutton)
            "spst-momentary-pushbutton-12mm-panel | TME | PB-1B-DC-2-B | SwitchType=pushbutton;Contacts=SPST;"
                    + "SwitchFunction=OFF-(ON);Illuminated=no;IpRating=IP65;Termination=quick connect",
            "spst-momentary-pushbutton-12mm-panel | TME | PB-1B-DC-2-RIL | Illuminated=yes",
            "spst-momentary-pushbutton-12mm-panel | TME | R13-566A2-01 | HoleDiameter=12mm;SwitchFunction=OFF-(ON)",
            "spst-momentary-pushbutton-12mm-panel | TME | SB221NC | SwitchFunction=ON-(OFF);Termination=solder lug",
            // Mouser: the category and the description only ("6X6X4.3mm 160gF", "8POS SPST", "Off-None-On Solder Lug")
            "tactile-switch-6x6-smd | MOUSER | B3S-1000P | SwitchType=tactile;SwitchFunction=momentary;"
                    + "SwitchSize=6x6x4.3mm;Force=1.57 N (160 gf)",
            "tactile-switch-6x6-smd | MOUSER | FSMIJM63BW04 | SwitchSize=6x6mm;Mounting=SMD;Termination=PCB;"
                    + "Illuminated=yes;Force=2.55 N (260 gf)",
            "dip-switch-8-position | MOUSER | DM08 | SwitchType=DIP;Contacts=SPST;SwitchPositions=8;VoltageDC=24V;"
                    + "Current=25mA",
            "dip-switch-8-position | MOUSER | 206-8E | SwitchPositions=8",
            "spdt-toggle-switch-panel-mount-solder-lug | MOUSER | T102SHZQE | SwitchType=toggle;Contacts=SPDT;"
                    + "SwitchFunction=OFF-ON;Termination=solder lug",
            "spst-momentary-pushbutton-12mm-panel | MOUSER | IPR3FAD3-104-X1203 | SwitchType=pushbutton;"
                    + "SwitchFunction=momentary;Termination=wire leads;IpRating=IP67;VoltageDC=28V",
            // LCSC: the package field ("SMD-4P,6x6mm") and the JLCPCB description ("10000 times", "PC Pin", "插件")
            "tactile-switch-6x6-smd | LCSC | LXW-TS-6X6X85-SMD | SwitchType=tactile;SwitchSize=6x6mm;Mounting=SMD;"
                    + "Termination=PCB;SwitchFunction=momentary",
            "slide-switch-spdt-tht | LCSC | SS-12F15-G5 | SwitchType=slide;Contacts=SPDT;Mounting=THT;Termination=PCB;"
                    + "Life=10000 cycles;Current=500mA"})
    void switchAttributesAreRead(String fixture, Distributor distributor, String mpn, String expected) {
        Map<String, String> wanted = new LinkedHashMap<>();
        for (String pair : expected.split(";")) {
            String[] kv = pair.split("=", 2);
            wanted.put(kv[0].strip(), kv[1].strip());
        }
        assertThat(extractor.extract(RecordedSearches.part(FAMILY, fixture, distributor, mpn)))
                .containsAllEntriesOf(wanted);
    }

    @Test
    void everyRecordedSwitchStatesItsType() {
        for (Part p : RecordedSearches.all(FAMILY, FIXTURES)) {
            ParametricExtractor.Features f = extractor.features(p);
            if ("switch".equals(f.family())) {
                assertThat(f.sw()).as(p.manufacturerPartNumber()).isNotNull();
                assertThat(f.sw().type()).as(p.manufacturerPartNumber()).isNotNull();
            }
        }
    }

    @Test
    void switchIcsAndSensorsAreNoMechanicalSwitchInAnyFamily() {
        Part analog = RankingFixtures.mouser("TS5A3159", "Texas Instruments", "Analog Switch ICs 1-Ohm SPDT Analog Switch",
                "Analog Switch ICs", "SOT-23-6", Map.of());
        ParametricExtractor.Features f = extractor.features(analog);
        assertThat(f.family()).isNotEqualTo("switch");
        assertThat(f.sw().type()).isEqualTo("IC");
        Part hall = RankingFixtures.lcsc("C123", "ACME", "HS1", "Hall switch", "Magnetic Sensors / Hall Switches", "SOT-23",
                Map.of());
        assertThat(extractor.features(hall).sw().type()).isEqualTo("sensor");
        Part resistor = RankingFixtures.lcsc("C25804", "ACME", "R1", "10kΩ ±1% 100mW",
                "Resistors / Chip Resistor - Surface Mount", "0603", Map.of());
        assertThat(extractor.features(resistor).sw()).isNull();
    }
}
