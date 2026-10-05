package ro.alacrity.kina.distributor.lcsc;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.distributor.lcsc.JlcpcbQuery.Kind;
import ro.alacrity.kina.distributor.lcsc.JlcpcbQuery.Term;

import static org.assertj.core.api.Assertions.assertThat;

class JlcpcbQueryTest {

    @Test
    void classifiesTokens() {
        JlcpcbQuery q = JlcpcbQuery.parse("10uF X7R 0805 capacitors SOT-23 Samsung");
        assertThat(q.terms()).containsExactly(
                new Term("10uF", Kind.VALUE),
                new Term("X7R", Kind.DIELECTRIC),
                new Term("0805", Kind.PACKAGE),
                new Term("capacitor", Kind.FAMILY),
                new Term("SOT-23", Kind.PACKAGE),
                new Term("Samsung", Kind.KEYWORD));
    }

    @Test
    void normalisesOhmMicroAndRkm() {
        assertThat(texts("10k ohm")).isEqualTo("10kΩ");
        assertThat(texts("10kohm")).isEqualTo("10kΩ");
        assertThat(texts("10K Ω")).isEqualTo("10kΩ");
        assertThat(texts("4k7")).isEqualTo("4.7k");
        assertThat(texts("2R2")).isEqualTo("2.2Ω");
        assertThat(texts("10R")).isEqualTo("10Ω");
        assertThat(texts("4u7")).isEqualTo("4.7u");
        assertThat(texts("3V3")).isEqualTo("3.3V");
        assertThat(texts("5V0")).isEqualTo("5V");
        assertThat(texts("10µF")).isEqualTo("10uF");
        assertThat(texts("10 uF")).isEqualTo("10uF");
        assertThat(texts("±5%")).isEqualTo("5%");
        assertThat(texts("+/-1%")).isEqualTo("1%");
        assertThat(texts("10k")).isEqualTo("10k");
    }

    @Test
    void dropsOperatorsStopWordsAndPunctuation() {
        assertThat(texts("I need a (resistor) AND \"0805\" OR NOT NEAR, please")).isEqualTo("resistor 0805");
        assertThat(texts("op amp")).isEqualTo("amplifier");
        assertThat(texts("res res RES")).isEqualTo("resistor");
        assertThat(JlcpcbQuery.parse("   ").isEmpty()).isTrue();
        assertThat(JlcpcbQuery.parse(null).isEmpty()).isTrue();
    }

    @Test
    void pureNumbersAndMpnsAreNotValues() {
        assertThat(JlcpcbQuery.parse("2N7002").terms()).containsExactly(new Term("2N7002", Kind.KEYWORD));
        assertThat(JlcpcbQuery.parse("1N4148").terms()).containsExactly(new Term("1N4148", Kind.KEYWORD));
        assertThat(JlcpcbQuery.parse("100").terms()).containsExactly(new Term("100", Kind.KEYWORD));
    }

    private static String texts(String query) {
        return String.join(" ", JlcpcbQuery.parse(query).terms().stream().map(Term::text).toList());
    }
}
