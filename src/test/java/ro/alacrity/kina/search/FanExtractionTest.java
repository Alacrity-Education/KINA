package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static ro.alacrity.kina.search.RankingFixtures.attrs;

/**
 * Fan attributes read from the recorded TME parameters and the Mouser and LCSC descriptions (DESIGN.md 3.4 "Fans",
 * "Attribute sources"): type, supply, frame size, voltage, current, speed, airflow, static pressure, noise, bearing
 * and features.
 */
class FanExtractionTest {

    private final ParametricExtractor extractor = new ParametricExtractor();

    /** fixture | distributor | mpn | expected canonical attributes ("Key=value;..."). */
    @ParameterizedTest(name = "{1} {2}")
    @CsvSource(delimiter = '|', value = {
            // TME: parameters (Kind of fan, Type of fan, Fan dimensions, Supply voltage, Fan efficiency with <sup>,
            // Static pressure with <sub>, Kind of Bearing, Additional functions, Leads)
            "40x40x10-fan-12v | TME | MF40101V1-1000U-A99 | Voltage=12V;Current=51mA;Speed=7000 rpm;"
                    + "Airflow=13.5 m³/h (7.96 CFM);StaticPressure=47.4 Pa (4.83 mmH2O);Noise=27.3 dBA;Family=fan;"
                    + "FanType=axial;FanSupply=DC;FrameSize=40x40x10mm;Bearing=vapo;Features=auto restart, 2-wire",
            "40x40x10-fan-12v | TME | EE40101S2-1000U-999 | Speed=6100 rpm;Bearing=sleeve;Noise=23 dBA",
            // "Rotational rate/speed: 4200 (±10%)rpm"
            "120mm-axial-fan-12v-pwm | TME | PMD1212PMB1-A(2).GN | Speed=4200 rpm;FrameSize=120x120x38mm;"
                    + "Current=1.6A;Bearing=ball",
            // a PWM fan's speed range "0...2000rpm" is its rated speed, 2000 rpm; "Leads: 4pin", "IP rating: IP68"
            "120mm-axial-fan-12v-pwm | TME | AK-FN109 | Speed=2000 rpm;Features=PWM, tacho, 4-wire, IP68",
            "radial-blower-24v | TME | PF75302B1-1B00U-A99 | FanType=radial;FanSupply=DC;FrameSize=75x75x30mm;"
                    + "Voltage=24V;Speed=3400 rpm;Airflow=26.3 m³/h (15.5 CFM);Bearing=ball",
            // "Signal output: F type" is a tacho signal, "leads x3" a 3-wire fan
            "fan-5v-3000rpm | TME | MF40200V1-1000U-G99 | Voltage=5V;Features=tacho, auto restart, 3-wire",
            // Mouser: the category and the description only (0.25"H2O is inches of water)
            "40x40x10-fan-12v | MOUSER | MF40101VX-1000U-A99 | Voltage=12V;Airflow=16.8 m³/h (9.9 CFM);"
                    + "StaticPressure=62.3 Pa (6.35 mmH2O);FanType=axial;FanSupply=DC;FrameSize=40x40x10mm;"
                    + "Bearing=vapo;Features=auto restart",
            "40x40x10-fan-12v | MOUSER | CFM-4010-13-10 | FrameSize=40x40x10.6mm;Airflow=17 m³/h (10 CFM)",
            // "120x38mm": a square frame and its depth; "4x Lead Wires", "Tach/PWM"
            "120mm-axial-fan-12v-pwm | MOUSER | PFR1212DHE-SP00 | FrameSize=120x120x38mm;Bearing=ball;"
                    + "Features=PWM, tacho, 4-wire",
            "fan-5v-3000rpm | MOUSER | AFB02505LA-CF00 | Speed=5000 rpm;Noise=18 dBA;Bearing=ball;"
                    + "Features=tacho, 3-wire",
            "fan-5v-3000rpm | MOUSER | OD4010-05HB | Current=190mA;Bearing=ball;Speed=6000 rpm",
            "radial-blower-24v | MOUSER | 109BD24HC2 | FanType=radial;FanSupply=DC;FrameSize=76x76x30mm;"
                    + "Features=tacho",
            "radial-blower-24v | MOUSER | CBM-979433S-230-507-20 | FanType=radial;FrameSize=97x94x33mm;Voltage=24V",
            "radial-blower-24v | MOUSER | CFM-A225V-231-445 | FanType=axial;FrameSize=120x120x25mm",
            // LCSC: the JLCPCB description ("17.8dB(A)", "25mm×25mm×10mm", "8500RPM")
            "fan-5v-3000rpm | LCSC | SFS2510SL5 | Voltage=5V;Current=140mA;Power=0.7W;Speed=8500 rpm;"
                    + "Airflow=1.72 m³/h (1.01 CFM);Noise=17.8 dBA;FanType=axial;FanSupply=DC;FrameSize=25x25x10mm",
            "40x40x10-fan-12v | LCSC | SF6025SM12 | FrameSize=60x60x25mm;Speed=4000 rpm;Noise=34.3 dBA"})
    void fanAttributesAreRead(String fixture, Distributor distributor, String mpn, String expected) {
        Map<String, String> wanted = new LinkedHashMap<>();
        for (String pair : expected.split(";")) {
            String[] kv = pair.split("=", 2);
            wanted.put(kv[0].strip(), kv[1].strip());
        }
        assertThat(extractor.extract(FanFixtures.part(fixture, distributor, mpn))).containsAllEntriesOf(wanted);
    }

    @Test
    void everyRecordedFanStatesItsTypeAndTheBlowerCategoriesAreRadial() {
        for (Part p : FanFixtures.all()) {
            ParametricExtractor.Features f = extractor.features(p);
            if (!"fan".equals(f.family())) {
                assertThat(f.fan()).as(p.manufacturerPartNumber()).isNull();
                continue;
            }
            assertThat(f.fan()).as(p.manufacturerPartNumber()).isNotNull();
            assertThat(f.fan().type()).as(p.manufacturerPartNumber()).isNotNull();
            assertThat(f.mounting()).as("a fan is neither SMD nor THT").isNull();
            String category = p.category() == null ? "" : p.category();
            String description = p.description() == null ? "" : p.description();
            if (category.startsWith("Blowers") || description.contains("blower")) {
                assertThat(f.fan().type()).as(p.manufacturerPartNumber()).isEqualTo(ParsedQuery.RADIAL);
            }
            if (p.distributor() == Distributor.TME && category.contains("Fans")) {
                assertThat(f.fan().frame()).as(p.manufacturerPartNumber()).isNotNull();
                assertThat(f.fan().supply()).as(p.manufacturerPartNumber()).isNotNull();
                assertThat(f.value(ParsedQuery.VOLTAGE)).as(p.manufacturerPartNumber()).isNotNull();
            }
        }
    }

    @Test
    void enrichedFansReadTheSameAttributes() {
        for (Part p : FanFixtures.all()) {
            ParametricExtractor.Features raw = extractor.features(p);
            ParametricExtractor.Features enriched = extractor.features(extractor.enrich(p));
            assertThat(enriched.values()).as(p.manufacturerPartNumber()).isEqualTo(raw.values());
            assertThat(enriched.fan()).as(p.manufacturerPartNumber()).isEqualTo(raw.fan());
            // a description's voltages read once more as the derived Voltage: the same values
            assertThat(java.util.Set.copyOf(enriched.voltages())).as(p.manufacturerPartNumber())
                    .isEqualTo(java.util.Set.copyOf(raw.voltages()));
        }
    }

    @Test
    void theSupplyVoltageIsTheExactVoltageNotTheOperatingRange() {
        Part tme = FanFixtures.part("40x40x10-fan-12v", Distributor.TME, "MF40101V1-1000U-A99");
        ParametricExtractor.Features f = extractor.features(tme);
        assertThat(tme.attributes()).containsEntry("Operating voltage", "4.5...13.8V DC");
        assertThat(f.voltages()).containsExactly(12.0);
    }

    @Test
    void theRecordedNonFansHaveNoFanAttributes() {
        // the first LCSC phrase ("Cooling fan" 24V) relaxed to any part stating 24 V
        Part varistor = FanFixtures.part("radial-blower-24v", Distributor.LCSC, "0603-24V-0.2PF");
        assertThat(extractor.extract(varistor)).doesNotContainKeys("FanType", "FrameSize", "Speed", "Airflow");
    }

    @Test
    void fanUnitsAndWordsStayOutOfOtherFamilies() {
        Part capacitor = RankingFixtures.mouser("UVR1H101MPD", "Nichicon", "Aluminum Electrolytic Capacitors - Radial "
                + "Leaded 50volts 100uF 20% 3000rpm 40CFM", "Aluminum Electrolytic Capacitors - Radial Leaded", null,
                attrs());
        Map<String, String> extracted = extractor.extract(capacitor);
        assertThat(extracted).containsEntry("Mounting", "THT").doesNotContainKeys("Speed", "Airflow", "FanType");
        Part opamp = RankingFixtures.tme("OPA1", "TI", "IC: operational amplifier; 80dB; 10pA", "Operational amplifiers",
                null, attrs("Noise", "5nV/√Hz", "Bearing", "ball"));
        assertThat(extractor.extract(opamp)).doesNotContainKeys("Noise", "Bearing", "StaticPressure");
    }

    @Test
    void theExtractedKeysAreCanonical() {
        List<String> keys = FanFixtures.all().stream().flatMap(p -> extractor.extract(p).keySet().stream()).distinct()
                .toList();
        assertThat(ParametricExtractor.CANONICAL_KEYS).containsAll(keys);
    }
}
