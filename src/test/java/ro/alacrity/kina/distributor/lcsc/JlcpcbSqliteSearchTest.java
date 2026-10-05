package ro.alacrity.kina.distributor.lcsc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.alacrity.kina.distributor.lcsc.JlcpcbSqliteSearch.MatchMode;
import ro.alacrity.kina.distributor.lcsc.JlcpcbSqliteSearch.Result;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JlcpcbSqliteSearchTest {

    @TempDir
    Path dir;

    JlcpcbSqliteSearch search;

    @BeforeEach
    void setUp() throws SQLException {
        search = new JlcpcbSqliteSearch(JlcpcbTestDatabase.create(dir.resolve("parts-fts5.db")));
    }

    @AfterEach
    void tearDown() {
        search.close();
    }

    @Test
    void matchesAllTermsAndFiltersStock() throws SQLException {
        Result result = search.search("10uF X7R 0805", 0, 50);
        // C15850 is X5R, C99999 matches but has no stock
        assertThat(lcsc(result)).containsExactly("C15851");
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.mode()).isEqualTo(MatchMode.ALL);
    }

    @Test
    void trigramMatchIsCaseInsensitiveAndSpansColumns() throws SQLException {
        // "capacitor" only appears in the category columns, "x7r" in the description, "0402" in Package
        assertThat(lcsc(search.search("x7r capacitor 0402", 0, 50))).containsExactly("C1525");
    }

    @Test
    void valueTermsRespectNumberBoundaries() throws SQLException {
        // trigram "10k" is a substring of "510kΩ"; the value check keeps only the 10kΩ part
        assertThat(lcsc(search.search("10k resistor 0805", 0, 50))).containsExactly("C17414");
        assertThat(lcsc(search.search("10k ohm 0805", 0, 50))).containsExactly("C17414");
    }

    @Test
    void shortTokensUseLikeOnDescription() throws SQLException {
        // "1k" and "5%" are too short for trigram MATCH -> Description LIKE (with escaped %)
        Result result = search.search("1k 5% resistor", 0, 50);
        assertThat(lcsc(result)).containsExactly("C25000");
        assertThat(result.mode()).isEqualTo(MatchMode.ALL);
        // only short tokens: no MATCH at all
        assertThat(lcsc(search.search("1k", 0, 50))).containsExactly("C25000");
    }

    @Test
    void ordersByRankThenStock() throws SQLException {
        search.close();
        Path file = JlcpcbTestDatabase.create(dir.resolve("same.db"), List.of(
                JlcpcbTestDatabase.row("C1001", "Diodes", "Zener", "BZT52", "SOD-123", "M", "Basic", "Zener diode", "1-:0.1", "10"),
                JlcpcbTestDatabase.row("C1002", "Diodes", "Zener", "BZT52", "SOD-123", "M", "Basic", "Zener diode", "1-:0.1", "3000"),
                JlcpcbTestDatabase.row("C1003", "Diodes", "Zener", "BZT52", "SOD-123", "M", "Basic", "Zener diode", "1-:0.1", "200"),
                JlcpcbTestDatabase.row("C1004", "Diodes", "Zener", "BZT52", "SOD-123", "M", "Basic", "Zener diode", "1-:0.1", "0")));
        search = new JlcpcbSqliteSearch(file);

        Result result = search.search("zener SOD-123", 0, 50);
        assertThat(lcsc(result)).containsExactly("C1002", "C1003", "C1001");
        assertThat(result.total()).isEqualTo(3);

        Result page = search.search("zener SOD-123", 1, 1);
        assertThat(lcsc(page)).containsExactly("C1003");
        assertThat(page.total()).isEqualTo(3);
    }

    @Test
    void relaxesToParametricTerms() throws SQLException {
        Result result = search.search("please find me a nice 10uF X7R capacitor for decoupling", 0, 50);
        assertThat(result.mode()).isEqualTo(MatchMode.PARAMETRIC);
        assertThat(lcsc(result)).containsExactly("C15851");
    }

    @Test
    void relaxesToOrSemantics() throws SQLException {
        // the dead term "zzzzz" occurs nowhere: dropped first, the remaining terms still match
        Result result = search.search("RP2040 raspberry zzzzz", 0, 50);
        assertThat(result.mode()).isEqualTo(MatchMode.RELAXED);
        assertThat(result.dropped()).containsExactly("zzzzz");
        assertThat(lcsc(result)).containsExactly("C2040");

        // parametric terms that cannot all match still yield candidates (ranked by BM25, then stock)
        Result any = search.search("100nF 0805 Murata", 0, 50);
        assertThat(any.mode()).isEqualTo(MatchMode.ANY);
        assertThat(lcsc(any)).contains("C1525", "C15850", "C17414").doesNotContain("C99999");
    }

    // ---------------------------------------------------------------- connectors

    private void useConnectorDatabase() throws SQLException {
        search.close();
        search = new JlcpcbSqliteSearch(JlcpcbTestDatabase.create(dir.resolve("connectors.db"),
                JlcpcbTestDatabase.withConnectors()));
    }

    @Test
    void distributorPhraseUsesTheCategoryColumnPositionsOrientationAndPitch() throws SQLException {
        useConnectorDatabase();
        Result result = search.search("\"Female Header\" 6P \"Right Angle\" 2.54mm", 0, 50);
        assertThat(result.mode()).isEqualTo(MatchMode.ALL);
        // not: 2 mm pitch, 1x16P ("6P" is checked at a number boundary), out of stock, straight, male pin headers
        assertThat(lcsc(result)).containsExactlyInAnyOrder("C2897388", "C5333441", "C50878477", "C2897423",
                "C5142239");
        assertThat(result.total()).isEqualTo(5);

        Result rows = search.search("\"Female Header\" 1x6P \"Right Angle\" 2.54mm", 0, 50);
        assertThat(lcsc(rows)).containsExactlyInAnyOrder("C2897388", "C5333441", "C50878477", "C5142239");
    }

    @Test
    void categoryPhraseIsAColumnFilter() throws SQLException {
        useConnectorDatabase();
        JlcpcbQuery.Term category = JlcpcbQuery.parse("\"Female Header\"").terms().getFirst();
        assertThat(category.kind()).isEqualTo(JlcpcbQuery.Kind.CATEGORY);
        assertThat(JlcpcbSqliteSearch.matchExpression(category)).isEqualTo("\"Second Category\" : \"Female Header\"");
        // "Pin Header" appears in the descriptions of male headers only; the category decides
        assertThat(lcsc(search.search("\"Pin Header\" 1x40P", 0, 50))).containsExactly("C2337", "C2334");
        // no female 1x40P header: no conjunction matches, only the OR fallback does
        assertThat(search.search("\"Female Header\" 1x40P", 0, 50).mode()).isEqualTo(MatchMode.ANY);
    }

    @Test
    void mountingSynonymsMapToTheDatabaseVocabulary() throws SQLException {
        useConnectorDatabase();
        // THT -> "Through Hole" / "Plugin", SMD -> "Surface Mount" / "SMD"
        assertThat(lcsc(search.search("pin header 1x40 THT", 0, 50))).containsExactly("C2337");
        assertThat(lcsc(search.search("female header 1x6 SMD", 0, 50))).containsExactly("C5142239");
        // right angle THT: JLCPCB writes only "Right Angle", so the THT term is not required
        Result rightAngle = search.search("female header 1x6 right angle THT 2.54mm", 0, 50);
        assertThat(rightAngle.mode()).isEqualTo(MatchMode.ALL);
        assertThat(lcsc(rightAngle)).contains("C2897388", "C5333441", "C50878477");
    }

    @Test
    void theFailingUserQueryFindsRightAngleFemaleSixPinHeaders() throws SQLException {
        useConnectorDatabase();
        Result result = search.search("90 degree dupont style female pin header 90 degree THT pins 6 position", 0, 50);
        assertThat(lcsc(result)).containsExactlyInAnyOrder("C2897388", "C5333441", "C50878477", "C2897423",
                "C5142239", "C2906055");
        assertThat(lcsc(result)).doesNotContain("C2337", "C2334", "C2897404");
    }

    @Test
    void dropsTermsOneAtATimeLeastInformativeFirst() throws SQLException {
        useConnectorDatabase();
        // no straight THT 1x6 female header: the mounting term goes first, then the pitch would
        Result result = search.search("\"Female Header\" 1x6P 2.54mm \"Through Hole\" Copper", 0, 50);
        assertThat(result.mode()).isEqualTo(MatchMode.RELAXED);
        assertThat(result.dropped()).containsExactly("Copper", "Through Hole");
        assertThat(lcsc(result)).containsExactlyInAnyOrder("C2897388", "C5333441", "C50878477", "C5142239");
        // the step that found rows defines total and page
        Result page = search.search("\"Female Header\" 1x6P 2.54mm \"Through Hole\" Copper", 1, 2);
        assertThat(page.total()).isEqualTo(result.total());
        assertThat(lcsc(page)).containsExactlyElementsOf(lcsc(result).subList(1, 3));

        assertThat(JlcpcbSqliteSearch.leastInformative(JlcpcbQuery.parse("\"Female Header\" 6P \"Right Angle\" 2.54mm"
                + " THT gold").terms()).text()).isEqualTo("gold");
        assertThat(JlcpcbSqliteSearch.leastInformative(JlcpcbQuery.parse("\"Female Header\" 6P \"Right Angle\" 2.54mm")
                .terms()).text()).isEqualTo("Right Angle");
    }

    @Test
    void noMatchAndEmptyQueries() throws SQLException {
        assertThat(search.search("GRM21BR71C106KE11L", 0, 50).rows()).isEmpty();   // only out-of-stock match
        assertThat(search.search("", 0, 50).total()).isZero();
        assertThat(search.search("the and or", 0, 50).total()).isZero();
    }

    @Test
    void quotesAndOperatorsAreSafe() throws SQLException {
        assertThat(JlcpcbSqliteSearch.quote("a\"b")).isEqualTo("\"a\"\"b\"");
        assertThat(lcsc(search.search("x\"dual", 0, 50))).containsExactly("C2040");
        assertThat(lcsc(search.search("resistor AND 0603 OR NOT * ^ NEAR(", 0, 50))).containsExactly("C25804");
        assertThat(search.search("50%_\\ x", 0, 50).rows()).isEmpty();
    }

    @Test
    void findByLcsc() throws SQLException {
        assertThat(search.findByLcsc("c1525")).get().extracting(JlcpcbRow::mfrPart).isEqualTo("CL05B104KO5NNNC");
        assertThat(search.findByLcsc("C99999")).get().extracting(JlcpcbRow::stockQuantity).isEqualTo(0);
        assertThat(search.findByLcsc("C152")).isEmpty();    // trigram MATCH narrows, equality decides
        assertThat(search.findByLcsc("C424242")).isEmpty();
        assertThat(search.findByLcsc("C1")).isEmpty();
        assertThat(search.findByLcsc(" ")).isEmpty();
    }

    @Test
    void opensReadOnly() throws SQLException {
        try (Connection c = JlcpcbSqliteSearch.openReadOnly(dir.resolve("parts-fts5.db"));
                Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.execute("CREATE TABLE x (a)")).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void unavailableWithoutFileAndReopensAfterSwap() throws Exception {
        JlcpcbSqliteSearch missing = new JlcpcbSqliteSearch(dir.resolve("missing.db"));
        assertThat(missing.isAvailable()).isFalse();
        assertThatThrownBy(() -> missing.search("resistor", 0, 10)).isInstanceOf(IllegalStateException.class);

        Path replacement = JlcpcbTestDatabase.create(dir.resolve("new.db"), List.of(JlcpcbTestDatabase.row(
                "C7", "Resistors", "Chip", "R1", "0402", "M", "Basic", "1kΩ", "1-:0.1", "5")));
        missing.replaceDatabase(() -> java.nio.file.Files.move(replacement, dir.resolve("missing.db")));
        assertThat(missing.isAvailable()).isTrue();
        assertThat(lcsc(missing.search("resistor", 0, 10))).containsExactly("C7");
        missing.close();
    }

    @Test
    void containsValueChecksPrecedingCharacter() {
        assertThat(JlcpcbSqliteSearch.containsValue("50V 10kΩ ±1%", "10k")).isTrue();
        assertThat(JlcpcbSqliteSearch.containsValue("10KΩ", "10k")).isTrue();
        assertThat(JlcpcbSqliteSearch.containsValue("510kΩ", "10k")).isFalse();
        assertThat(JlcpcbSqliteSearch.containsValue("1.10kΩ 510kΩ 10kΩ", "10k")).isTrue();
        assertThat(JlcpcbSqliteSearch.containsValue("±0.5%", "5%")).isFalse();
        assertThat(JlcpcbSqliteSearch.containsValue("±5%", "5%")).isTrue();
        assertThat(JlcpcbSqliteSearch.containsValue(null, "5%")).isFalse();
    }

    private static List<String> lcsc(Result result) {
        return result.rows().stream().map(JlcpcbRow::lcscPart).toList();
    }
}
