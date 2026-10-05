package ro.alacrity.kina.distributor.mouser;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MouserPriceParserTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "1,40 €        | 1.40",
            "0,853 €       | 0.853",
            "0,57 €        | 0.57",
            "$0.10         | 0.10",
            "$1.5          | 1.5",
            "1.234,56 €    | 1234.56",
            "1,234.56      | 1234.56",
            "$1,234.56     | 1234.56",
            "1.234.567,8 € | 1234567.8",
            "1,234,567     | 1234567",
            "1,2345        | 12345",
            "1.234.567     | 1234567",
            "12 €          | 12",
            "€ 1 234,50    | 1234.50",
            ",5 €          | 0.5",
            "5. USD        | 5",
    })
    void parsesLocaleFormattedPrices(String raw, String expected) {
        assertThat(MouserPriceParser.parse(raw)).hasValueSatisfying(
                value -> assertThat(value).isEqualByComparingTo(new BigDecimal(expected)));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "€", "Call for price", "abc", ",", ".", ".,.", "1,2.3,4"})
    void garbageIsEmpty(String raw) {
        assertThat(MouserPriceParser.parse(raw)).isEmpty();
    }
}
