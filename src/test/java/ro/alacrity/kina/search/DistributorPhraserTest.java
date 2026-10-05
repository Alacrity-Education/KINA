package ro.alacrity.kina.search;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DistributorPhraserTest {

    static final String FAILING = "90 degree dupont style female pin header 90 degree THT pins 6 position";

    private final QueryParser parser = new QueryParser();

    /** query | LCSC phrase | TME phrase | Mouser phrase. */
    static Stream<Arguments> phrases() {
        return Stream.of(
                Arguments.of(FAILING, "\"Female Header\" 6P \"Right Angle\" 2.54mm", "pin strips female 6 angled",
                        "female header 6 pos right angle"),
                Arguments.of("2x3 female header right angle", "\"Female Header\" 2x3P \"Right Angle\"",
                        "pin strips female 2x3 angled", "female header 6 pos right angle"),
                Arguments.of("1x40 pin header 2.54mm straight", "\"Pin Header\" 1x40P 2.54mm",
                        "pin header male 40 straight 2.54mm", "male header 40 pos 2.54mm vertical"),
                Arguments.of("1x40 pin header 2.54mm straight THT", "\"Pin Header\" 1x40P 2.54mm \"Through Hole\"",
                        "pin header male 40 straight 2.54mm", "male header 40 pos 2.54mm vertical"),
                Arguments.of("6 pin JST XH connector 2.5mm", "\"Wire To Board\" XH 6P 2.5mm", "wire-board XH 6 2.5mm",
                        "JST XH 6 pos 2.5mm"),
                Arguments.of("USB-C receptacle 16 pin SMD", "\"USB Connectors\" Type-C \"Surface Mount\"",
                        "USB C socket SMT", "USB type C receptacle SMD"),
                Arguments.of("2x5 box header 2.54mm", "\"IDC Header\" 2x5P 2.54mm", "IDC male 2x5 2.54mm",
                        "shrouded header 10 pos 2.54mm"),
                Arguments.of("screw terminal block 2 position 5.08mm", "\"Terminal Block\" 2P 5.08mm",
                        "terminal block 2 5.08mm", "terminal block 2 pos 5.08mm"),
                Arguments.of("RJ45 jack with magnetics", "RJ45 magnetics", "RJ45 socket magnetics",
                        "RJ45 jack magnetics"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("phrases")
    void connectorQueriesUseTheDistributorsWording(String query, String lcsc, String tme, String mouser) {
        ParsedQuery parsed = parser.parse(query);
        assertThat(DistributorPhraser.phrase(Distributor.LCSC, parsed)).isEqualTo(lcsc);
        assertThat(DistributorPhraser.phrase(Distributor.TME, parsed)).isEqualTo(tme)
                .hasSizeLessThanOrEqualTo(DistributorPhraser.TME_MAX_LENGTH);
        assertThat(DistributorPhraser.phrase(Distributor.MOUSER, parsed)).isEqualTo(mouser);
    }

    @Test
    void otherQueriesAreSentVerbatim() {
        for (String query : new String[]{"10uF X7R 0805", "SOT-23 N-channel MOSFET 30V", "LM358 SOIC-8",
                "8 pin SOIC op amp", "ESP32-WROOM-32"}) {
            ParsedQuery parsed = parser.parse(query);
            for (Distributor d : Distributor.values()) {
                assertThat(DistributorPhraser.phrase(d, parsed)).as(d + " " + query).isNull();
            }
        }
        // a phrase equal to the user's text is not reported as a rewrite
        assertThat(DistributorPhraser.phrase(Distributor.MOUSER, parser.parse("USB type C receptacle"))).isNull();
    }

    @Test
    void tmePhrasesStayWithinFortyCharacters() {
        ParsedQuery parsed = parser.parse("2x20 female header 2.54mm right angle gold plated high temperature");
        String tme = DistributorPhraser.phrase(Distributor.TME, parsed);
        assertThat(tme).startsWith("pin strips female 2x20 angled 2.54mm").hasSizeLessThanOrEqualTo(40);
    }

    @Test
    void connectorFallbacks() {
        ParsedQuery failing = parser.parse(FAILING);
        assertThat(DistributorPhraser.fallback(Distributor.TME, failing, "pin strips female 6 angled"))
                .isEqualTo("pin strips female 6");
        assertThat(DistributorPhraser.fallback(Distributor.MOUSER, failing, "female header 6 pos right angle"))
                .isEqualTo("female header right angle");
        assertThat(DistributorPhraser.fallback(Distributor.MOUSER, new QueryParser().parse("1x40 pin header 2.54mm"),
                "male header 40 pos 2.54mm")).isEqualTo("male header 2.54mm");
        assertThat(DistributorPhraser.fallback(Distributor.LCSC, failing, "x")).isNull();
        // nothing shorter to try
        assertThat(DistributorPhraser.fallback(Distributor.TME, parser.parse("USB-C receptacle 16 pin"),
                "USB C socket")).isNull();
    }

    @Test
    void keywordOnlyQueriesFallBackToTheirMostInformativeTokens() {
        ParsedQuery esp = parser.parse("ESP32-WROOM-32 wifi bluetooth module with antenna");
        assertThat(DistributorPhraser.fallback(Distributor.TME, esp, esp.originalText()))
                .isEqualTo("ESP32-WROOM-32 wifi bluetooth antenna");
        ParsedQuery long_ = parser.parse("nice cheap small ATmega328P microcontroller board arduino compatible bootloader");
        String core = DistributorPhraser.keywordCore(long_);
        assertThat(core.split(" ")).hasSizeBetween(DistributorPhraser.MIN_FALLBACK_TOKENS,
                DistributorPhraser.MAX_FALLBACK_TOKENS).contains("ATmega328P", "mcu").doesNotContain("nice", "cheap", "small");
        // the parametric core keeps precedence
        assertThat(DistributorPhraser.fallback(Distributor.MOUSER, parser.parse("SOT-23 N-channel MOSFET 30V"),
                "SOT-23 N-channel MOSFET 30V")).isEqualTo("MOSFET 30V SOT-23");
        // short queries have nothing to drop
        assertThat(DistributorPhraser.keywordCore(parser.parse("ESP32 module wifi"))).isNull();
        assertThat(DistributorPhraser.fallback(Distributor.TME, parser.parse("10uF X7R 0805"), "10uF X7R 0805"))
                .isNull();
    }

    /** USB requests: query | LCSC phrase | TME phrase | Mouser phrase (never a pin count, except Mouser power-only). */
    static Stream<Arguments> usbPhrases() {
        return Stream.of(
                Arguments.of("USB-C receptacle 16 pin SMD USB 2.0", "\"USB Connectors\" Type-C \"USB 2.0\" \"Surface Mount\"",
                        "USB C socket SMT 2.0", "USB type C receptacle SMD 2.0"),
                Arguments.of("USB Type-C 24 pin USB 3.1 receptacle horizontal",
                        "\"USB Connectors\" Type-C \"USB 3\" \"Right Angle\"", "USB C socket horizontal",
                        "USB type C receptacle right angle 3.1"),
                Arguments.of("17 pin USB-C receptacle", "\"USB Connectors\" Type-C", "USB C socket",
                        "USB type C receptacle"),
                Arguments.of("micro USB B receptacle 5 pin SMD", "\"USB Connectors\" Micro-B \"Surface Mount\"",
                        "USB B micro socket SMT", "micro USB receptacle SMD"),
                Arguments.of("USB-C 6 pin power only", "\"USB Connectors\" Type-C", "USB C socket charging",
                        "USB type C receptacle 6 pos power only"),
                Arguments.of("USB 3.0 Type-A receptacle THT", "\"USB Connectors\" Type-A \"USB 3\" \"Through Hole\"",
                        "USB A socket THT 3.0", "USB type A receptacle THT 3.0"),
                Arguments.of("USB-C plug 24 pin", "\"USB Connectors\" Type-C Male", "USB C plug", "USB type C plug"),
                Arguments.of("waterproof USB-C receptacle IP67", "\"USB Connectors\" Type-C waterproof", "USB C socket IP67",
                        "USB type C receptacle IP67"),
                Arguments.of("mid-mount USB-C 16P", "\"USB Connectors\" Type-C mid-mount", "USB C socket middle",
                        "USB type C receptacle mid"),
                Arguments.of("USB4 Type-C receptacle vertical", "\"USB Connectors\" Type-C USB4 Vertical",
                        "USB C socket vertical", "USB type C receptacle vertical USB4"),
                Arguments.of("USB-C receptacle 16 pin board lock", "\"USB Connectors\" Type-C \"board lock\"", "USB C socket",
                        "USB type C receptacle"),
                Arguments.of("mini USB B receptacle", "\"USB Connectors\" Mini-B", "USB B mini socket", "mini USB receptacle"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("usbPhrases")
    void usbQueriesUseTheDistributorsWordingWithoutPinCounts(String query, String lcsc, String tme, String mouser) {
        ParsedQuery parsed = parser.parse(query);
        assertThat(DistributorPhraser.phrase(Distributor.LCSC, parsed)).isEqualTo(lcsc);
        assertThat(DistributorPhraser.phrase(Distributor.TME, parsed)).isEqualTo(tme)
                .hasSizeLessThanOrEqualTo(DistributorPhraser.TME_MAX_LENGTH);
        assertThat(DistributorPhraser.phrase(Distributor.MOUSER, parsed)).isEqualTo(mouser);
    }

    @Test
    void usbFallbacksAreTheTypeWords() {
        ParsedQuery q = parser.parse("USB-C receptacle 16 pin SMD USB 2.0");
        assertThat(DistributorPhraser.fallback(Distributor.TME, q, "USB C socket SMT 2.0")).isEqualTo("USB C socket");
        assertThat(DistributorPhraser.fallback(Distributor.MOUSER, q, "USB type C receptacle SMD 2.0"))
                .isEqualTo("USB type C receptacle");
        assertThat(DistributorPhraser.fallback(Distributor.MOUSER, parser.parse("USB-C plug"), "USB type C plug")).isNull();
        // the LCSC phrase is understood by JlcpcbQuery: category filter, Type-C/TypeC, "USB 2.0"/"USB2.0"
        var lcsc = ro.alacrity.kina.distributor.lcsc.JlcpcbQuery.parse(DistributorPhraser.phrase(Distributor.LCSC, q));
        assertThat(lcsc.terms()).extracting(t -> t.kind().name() + ":" + String.join("|", t.phrases()))
                .containsExactly("CATEGORY:USB Connector", "FAMILY:Type-C|TypeC", "FEATURE:USB 2.0|USB2.0",
                        "MOUNTING:SMD|SMT|Surface Mount");
    }
}
