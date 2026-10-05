package ro.alacrity.kina.distributor.lcsc;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.domain.PriceBreak;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JlcpcbPriceParserTest {

    @Test
    void parsesBracketsAscending() {
        List<PriceBreak> prices = JlcpcbPriceParser.parse("1-199:0.013,200-599:0.011,600-:0.010");
        assertThat(prices).containsExactly(
                new PriceBreak(1, new BigDecimal("0.013"), "USD"),
                new PriceBreak(200, new BigDecimal("0.011"), "USD"),
                new PriceBreak(600, new BigDecimal("0.010"), "USD"));
    }

    @Test
    void sortsOutOfOrderAndIgnoresDuplicates() {
        List<PriceBreak> prices = JlcpcbPriceParser.parse("600-:0.010, 1-199:0.013 ,1-5:0.5");
        assertThat(prices).extracting(PriceBreak::quantity).containsExactly(1, 600);
        assertThat(prices.getFirst().unitPrice()).isEqualByComparingTo("0.013");
    }

    @Test
    void singleOpenBracket() {
        assertThat(JlcpcbPriceParser.parse("1-:0.0500")).containsExactly(new PriceBreak(1, new BigDecimal("0.0500"), "USD"));
    }

    @Test
    void toleratesEmptyAndGarbage() {
        assertThat(JlcpcbPriceParser.parse(null)).isEmpty();
        assertThat(JlcpcbPriceParser.parse("")).isEmpty();
        assertThat(JlcpcbPriceParser.parse("   ")).isEmpty();
        assertThat(JlcpcbPriceParser.parse("n/a")).isEmpty();
        assertThat(JlcpcbPriceParser.parse("abc-:x,::,-:,0-10:1.0,-5:2")).isEmpty();
        assertThat(JlcpcbPriceParser.parse("garbage,10-99:0.5,more:garbage"))
                .containsExactly(new PriceBreak(10, new BigDecimal("0.5"), "USD"));
    }
}
