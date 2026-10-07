package ro.alacrity.kina.search;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.ParsedQueryResponse;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Part numbers named in a query (DESIGN.md 3.4 "Requested part numbers"). */
class PartNumbersTest {

    private final QueryParser parser = new QueryParser();

    /** query | part numbers (space separated, as written; empty for none). */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "uP1966E GaN half bridge gate driver | uP1966E",
            "EPC23101 eGaN ePower stage | EPC23101",
            "EPC2302 GaN FET 100V | EPC2302",
            "LMG2100R026 GaN half-bridge | LMG2100R026",
            "ESP32-WROOM-32 wifi bluetooth module with antenna | ESP32-WROOM-32",
            "1N4148 SOD-123 | 1N4148",
            "BC547 NPN TO-92 | BC547",
            "LM358 SOIC-8 | LM358",
            "65-UP1966E | 65-UP1966E",
            "STM32F103C8T6 and AO3400A | STM32F103C8T6 AO3400A",
            // values, units, packages, dielectrics, standards and quantities are no part numbers
            "10uF 25V X7R 0805 | ''",
            "SOT-23 N-channel MOSFET 30V | ''",
            "100nF 0402 X5R 6.3V | ''",
            "RS485 transceiver SOIC-8 | ''",
            "AEC-Q200 10k 0603 resistor | ''",
            "USB3.0 Type-A receptacle | ''",
            "IP67 connector 4 pin | ''",
            "2-channel isolated gate driver | ''",
            "1000pcs 10k resistor | ''",
            "resistor 10k 0603 TCR 25ppm | ''",
            "100-240V power supply 12V | ''",
            "electrolytic capacitor 470uF 35V 105°C 5000h THT | ''",
            "16MHz crystal 18pF 3225 SMD | ''",
            "LQFP-48 MCU | ''",
            "DDR4 memory 8GB | ''",
            "24AWG wire | ''",
            "6.3x5.4mm 100uF capacitor | ''"})
    void partNumbersOfAQuery(String query, String expected) {
        List<String> wanted = expected.isBlank() ? List.of() : Arrays.asList(expected.split(" "));
        ParsedQuery q = parser.parse(query);
        assertThat(q.partNumbers()).as(query).containsExactlyElementsOf(wanted);
        assertThat(q.namesPartNumber()).isEqualTo(!wanted.isEmpty());
    }

    @Test
    void aBarePartNumberIsNamedButNotUnderstood() {
        ParsedQuery q = parser.parse("EPC2302");
        assertThat(q.partNumbers()).containsExactly("EPC2302");
        assertThat(q.understood()).isFalse();
        assertThat(q.keywords()).containsExactly("epc2302");   // still ranked lexically
        assertThat(ParsedQueryResponse.from(q).partNumbers()).containsExactly("EPC2302");
        assertThat(ParsedQueryResponse.from(parser.parse("10uF 0805")).partNumbers()).isNull();
    }

    @Test
    void aPartIsRequestedByItsMpnOrDistributorNumberIgnoringPunctuationAndCase() {
        Part epc2218a = RankingFixtures.part(Distributor.MOUSER, "65-EPC2218A", "EPC", "EPC2218A", "GaN FETs", null,
                null, 10, "1.00", Map.of(), Map.of());
        assertThat(PartNumbers.requests("EPC2218", epc2218a)).isTrue();    // starts with it
        assertThat(PartNumbers.requests("epc2218a", epc2218a)).isTrue();   // case
        assertThat(PartNumbers.requests("65-EPC2218A", epc2218a)).isTrue(); // the distributor part number
        assertThat(PartNumbers.requests("EPC2219", epc2218a)).isFalse();
        Part era = RankingFixtures.part(Distributor.TME, "ERA6AEB5361V", "PANASONIC", "ERA-6AEB5361V", "Resistor",
                null, null, 10, "1.00", Map.of(), Map.of());
        assertThat(PartNumbers.requests("ERA-6AEB5361V", era)).isTrue();
        assertThat(PartNumbers.requests("ERA 6AEB 5361V", era)).isTrue();
        assertThat(PartNumbers.requested(parser.parse("EPC2218 GaN FET"), epc2218a)).isTrue();
        assertThat(PartNumbers.requested(parser.parse("GaN FET 100V"), epc2218a)).isFalse();
    }
}
