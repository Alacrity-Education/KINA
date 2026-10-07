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
 * LED attributes read from the recorded TME parameters and the Mouser and LCSC descriptions (DESIGN.md 3.4 "LEDs",
 * "Attribute sources"): colour, lens, LED type, orientation, package, wavelength, forward voltage, current, luminous
 * intensity, viewing angle and colour temperature.
 */
class LedExtractionTest {

    static final String FAMILY = "leds";
    static final List<String> FIXTURES = List.of("0603-red-led-20ma", "5mm-white-led-diffused", "0805-blue-led-470nm",
            "rgb-led-5050", "ir-led-940nm-5mm");

    private final ParametricExtractor extractor = new ParametricExtractor();

    /** fixture | distributor | mpn | expected canonical attributes ("Key=value;..."). */
    @ParameterizedTest(name = "{1} {2}")
    @CsvSource(delimiter = '|', value = {
            // TME parameters: "LED colour", "LED lens" = transparent, "Luminosity" = 18...54mcd (the upper end),
            // "Operating voltage" = 2...2.4V DC (the forward voltage, its upper end), "Case - inch" = 0603
            "0603-red-led-20ma | TME | LTST-C191KRKT | Package=0603;Mounting=SMD;LedColour=red;LensType=clear;"
                    + "LedType=indicator;Wavelength=631nm;ForwardVoltage=2.4V;Current=20mA;LuminousIntensity=54mcd;"
                    + "ViewingAngle=130°",
            "0805-blue-led-470nm | TME | QBLP631-IB | Package=0805;LedColour=blue;Wavelength=470nm;"
                    + "LuminousIntensity=200mcd;ForwardVoltage=3.7V;ViewingAngle=140°",
            // "white warm", "Colour temperature" = 2700-3200K (the centre), "LED version" = blinking
            "5mm-white-led-diffused | TME | OSM5DS5B62A | Package=5mm;Mounting=THT;LedColour=warm white;"
                    + "LensType=diffused;LedType=blinking;ColourTemperature=2950 K;LuminousIntensity=2.18cd",
            // "Case - mm" = 5050 before the package field PLCC6; an LED module and a programmable tape are no LEDs
            "rgb-led-5050 | TME | HC-F12V-WS2811-7515 | Package=5050;LedColour=RGB;LedType=strip",
            "rgb-led-5050 | TME | S010060CB3SB7/C1 | Package=5050;LedColour=RGB;LedType=strip",
            "ir-led-940nm-5mm | TME | LTE-5228A | Package=5mm;Mounting=THT;LedColour=IR;LensType=clear;"
                    + "Wavelength=940nm;ForwardVoltage=1.6V;ViewingAngle=40°",
            // Mouser: the category and the description only ("Infrared Emitters", "T1 3/4", "+/-17deg." a half angle)
            "ir-led-940nm-5mm | MOUSER | TSAL6102 | Family=led;LedColour=IR;Wavelength=940nm;ViewingAngle=10°;"
                    + "Package=5mm;Mounting=THT;LedType=high power",
            "ir-led-940nm-5mm | MOUSER | TSAL6200 | Family=led;Wavelength=940nm;ViewingAngle=34°;Package=5mm",
            "ir-led-940nm-5mm | MOUSER | INL-5AHIR30 | Package=5mm;Mounting=THT;LedColour=IR",
            "rgb-led-5050 | MOUSER | LTST-E563CEGBW | Package=5050;LedColour=RGB;LedType=addressable",
            "rgb-led-5050 | MOUSER | 587-2056-247F | Package=5050;LedType=addressable",
            "rgb-led-5050 | MOUSER | AB-FA00506-19700-XA1 | LedType=strip",
            "5mm-white-led-diffused | MOUSER | PLP5-2-2.5MM-D | LedType=accessory;LensType=diffused",
            "0603-red-led-20ma | MOUSER | APHB1608Y2R2C-AMT | LedColour=bi-colour",
            "0603-red-led-20ma | MOUSER | LSM0603412V | Package=0603;LedColour=red;ForwardVoltage=2V;"
                    + "LuminousIntensity=100mcd",
            // LCSC: the JLCPCB description ("1.6V~2.6V", "615nm~630nm", "Frosted Yellow Lens Ice Blue")
            "0603-red-led-20ma | LCSC | NCD0603R1 | Package=0603;LedColour=red;ForwardVoltage=2.6V;"
                    + "Wavelength=622.5nm;Current=25mA;LuminousIntensity=220mcd;ViewingAngle=130°",
            "0805-blue-led-470nm | LCSC | E6C0805TKAY1UDA | LedColour=blue;LensType=diffused;LuminousIntensity=800mcd",
            "5mm-white-led-diffused | LCSC | MHL5013UWDT | Package=5mm;Mounting=THT;LedColour=white;"
                    + "LensType=diffused;LuminousIntensity=1.82cd;ViewingAngle=40°",
            "rgb-led-5050 | LCSC | S6-5050RGBTA | Package=5050;Mounting=SMD;LedColour=RGB",
            "ir-led-940nm-5mm | LCSC | IR333-A | Package=5mm;Mounting=THT;LedColour=IR;Wavelength=940nm;"
                    + "ForwardVoltage=1.2V"})
    void ledAttributesAreRead(String fixture, Distributor distributor, String mpn, String expected) {
        Map<String, String> wanted = new LinkedHashMap<>();
        for (String pair : expected.split(";")) {
            String[] kv = pair.split("=", 2);
            wanted.put(kv[0].strip(), kv[1].strip());
        }
        assertThat(extractor.extract(RecordedSearches.part(FAMILY, fixture, distributor, mpn)))
                .containsAllEntriesOf(wanted);
    }

    @Test
    void theReverseVoltageIsNoForwardVoltage() {
        // JLCPCB "100mA 11mW/sr@IF=20mA 150mW 5V 940nm": 5V is the reverse voltage, the radiant intensity no power
        Part ir = RecordedSearches.part(FAMILY, "ir-led-940nm-5mm", Distributor.LCSC, "IR333/H0/L10");
        assertThat(extractor.extract(ir)).doesNotContainKey(ParametricExtractor.FORWARD_VOLTAGE)
                .containsEntry(ParametricExtractor.CURRENT, "100mA");
    }

    @Test
    void everyRecordedLedStatesItsTypeAndTheJlcpcbCategoriesAreRead() {
        for (Part p : RecordedSearches.all(FAMILY, FIXTURES)) {
            ParametricExtractor.Features f = extractor.features(p);
            if (!"led".equals(f.family())) {
                assertThat(f.led()).as(p.manufacturerPartNumber()).isNull();
                continue;
            }
            assertThat(f.led().type()).as(p.manufacturerPartNumber()).isNotNull();
            if (p.distributor() == Distributor.LCSC) {
                // every returned JLCPCB LED states its colour (or its category does)
                assertThat(f.led().colour()).as(p.manufacturerPartNumber()).isNotNull();
            }
        }
        assertThat(LedVocabulary.type("Optoelectronics / RGB LEDs(Built-in IC)", true)).isEqualTo("addressable");
        assertThat(LedVocabulary.type("Optoelectronics / LED Indication - Discrete", true)).isEqualTo("indicator");
        assertThat(LedVocabulary.type("Optocouplers & LEDs & Infrared / Infrared Receivers", true))
                .isEqualTo("receiver");
    }

    @Test
    void aLampNeedsAThroughHoleWordInADescription() {
        // JLCPCB lists the body of an SMD LED as "5mm 5mm"; "5mm round lamp head" is a lamp
        assertThat(LedVocabulary.word(ro.alacrity.kina.domain.Vocabulary.LED_PACKAGE,
                "-40℃~+85℃ 0.6mA 1.6mm 1000mcd~1400mcd 120° 3.5V~5.5V 5mm 5mm 620nm~630nm Water Clear")).isNull();
        assertThat(LedVocabulary.word(ro.alacrity.kina.domain.Vocabulary.LED_PACKAGE,
                "1.82cd 20mA 3.2V 40° 5mm round lamp head")).isEqualTo("5mm");
        assertThat(LedVocabulary.word(ro.alacrity.kina.domain.Vocabulary.LED_PACKAGE, "SMD-4P,5x5mm")).isEqualTo("5050");
        assertThat(LedVocabulary.word(ro.alacrity.kina.domain.Vocabulary.LED_PACKAGE, "5 mm (T-1 3/4)"))
                .isEqualTo("5mm");
    }
}
