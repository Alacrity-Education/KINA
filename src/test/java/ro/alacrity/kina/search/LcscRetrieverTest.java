package ro.alacrity.kina.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.alacrity.kina.TestWiring;
import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.Deadline;
import ro.alacrity.kina.distributor.lcsc.JlcpcbSqliteSearch;
import ro.alacrity.kina.distributor.lcsc.JlcpcbTestDatabase;
import ro.alacrity.kina.distributor.lcsc.LcscClient;
import ro.alacrity.kina.distributor.lcsc.LcscTestSupport;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.SearchRequest;
import ro.alacrity.kina.metrics.KinaMetrics;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** {@link LcscRetriever}: the typed field query, the FTS fallback and the merge (DESIGN.md 9.3). */
class LcscRetrieverTest {

    private static final ParametricExtractor EXTRACTOR = new ParametricExtractor();
    private static final QueryParser PARSER = new QueryParser();

    @TempDir
    Path dir;

    JlcpcbSqliteSearch search;
    LcscClient client;
    LcscRetriever retriever;
    RankingService ranking;

    @AfterEach
    void tearDown() {
        if (search != null) {
            search.close();
        }
    }

    /** The retriever on a database of {@code rows}; the typed table is built and used only when {@code typed}. */
    private void setUp(List<ro.alacrity.kina.distributor.lcsc.JlcpcbRow> rows, boolean typed) throws Exception {
        Path main = JlcpcbTestDatabase.create(dir.resolve("parts-fts5.db"), rows);
        if (typed) {
            LcscTestSupport.buildIndex(main);
        }
        wire(main, typed);
    }

    private void wire(Path main, boolean typed) {
        search = LcscTestSupport.search(main, typed);
        client = spy(TestWiring.lcscClient(search));
        KinaProperties props = TestWiring.properties();
        ranking = TestWiring.rankingService(props, TestWiring.deterministicRanker(EXTRACTOR), null,
                () -> null, TestWiring.scoreCache(props.ranking().scoreCacheTtl()));
        PageCollector pages = TestWiring.wire(new PageCollector(), "extractor", EXTRACTOR, "clock",
                Clock.systemUTC(), "metrics", KinaMetrics.NOOP);
        RequestedLookup requested = TestWiring.wire(new RequestedLookup(), "extractor", EXTRACTOR, "clock",
                Clock.systemUTC());
        retriever = TestWiring.wire(new LcscRetriever(), "properties", props, "pages", pages, "ranking", ranking,
                "requested", requested, "extractor", EXTRACTOR, "fieldSearch", LcscTestSupport.fieldSearch(search));
    }

    private Fetched retrieve(String text, int maxResults) {
        ParsedQuery parsed = PARSER.parse(text);
        Prepared prepared = new Prepared(SearchRequest.of(text, maxResults, Set.of(Distributor.LCSC), false), parsed,
                maxResults, Set.of(Distributor.LCSC));
        DistributorBudget budget = new DistributorBudget(Deadline.after(Duration.ofSeconds(30)), Duration.ofSeconds(30));
        return retriever.retrieve(client, prepared, new Progress(CacheStatus.NOT_APPLICABLE), budget);
    }

    private static List<String> numbers(Fetched fetched) {
        return fetched.parts().stream().map(Part::distributorPartNumber).toList();
    }

    @Test
    void theTypedQueryServesAloneWhenItFindsTheWindow() throws Exception {
        setUp(JlcpcbTestDatabase.typedWithMany(100), true);

        Fetched fetched = retrieve("10uF X7R 0805 25V", 10);

        // the candidate window, and the sample rows below 25 V that state every attribute: no place, for the ranker
        assertThat(numbers(fetched).stream().filter(n -> n.startsWith("C7"))).hasSize(40);
        assertThat(numbers(fetched).subList(0, 40)).allMatch(n -> n.startsWith("C7"));
        assertThat(fetched.totalResults()).isEqualTo(102);   // the typed count of the confirmed rows (ratings stated)
        assertThat(fetched.cache()).isEqualTo(CacheStatus.NOT_APPLICABLE);
        assertThat(fetched.constraintsRelaxed()).isEmpty();
        verify(client, never()).search(anyString(), anyInt(), anyInt());
        // the parts are enriched like every part of the search path
        assertThat(fetched.parts().getFirst().attributes()).isNotEmpty();
    }

    @Test
    void withoutATypedTableTheFtsPathServesAsBefore() throws Exception {
        setUp(JlcpcbTestDatabase.typedWithMany(100), false);

        Fetched fetched = retrieve("10uF X7R 0805 25V", 10);

        verify(client, times(1)).search(anyString(), anyInt(), anyInt());
        assertThat(numbers(fetched)).isNotEmpty();
        assertThat(fetched.totalResults()).isEqualTo(100);   // the FTS count of the same rows
    }

    @Test
    void aRequestWithoutTypedConstraintsUsesTheFtsSearch() throws Exception {
        setUp(JlcpcbTestDatabase.typed(), true);

        Fetched fetched = retrieve("RP2040", 10);

        verify(client, times(1)).search(anyString(), anyInt(), anyInt());
        assertThat(numbers(fetched)).contains("C2040");
    }

    @Test
    void whenTheLadderIsExhaustedTheFtsSearchFillsTheWindowAndThePartsAreMerged() throws Exception {
        setUp(JlcpcbTestDatabase.typed(), true);
        // the typed table misses one part the FTS table has: it is merged in from the FTS search
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + search.indexFile());
                Statement st = c.createStatement()) {
            st.execute("DELETE FROM part_index WHERE part_number = 'C15851'");
        }
        search.reopen();
        assertThat(search.fieldIndexAvailable()).isTrue();

        Fetched fetched = retrieve("10uF X7R 0805", 10);

        // fewer typed candidates than the window at every step: today's search ran for the rest
        verify(client, times(1)).search(anyString(), anyInt(), anyInt());
        List<String> found = numbers(fetched);
        assertThat(found).contains("C15851", "C60004");   // C60004 typed, C15851 from the FTS search
        assertThat(found).doesNotHaveDuplicates();
    }

    @Test
    void aFailingTypedQueryFallsBackToTheFtsSearch() throws Exception {
        Path main = JlcpcbTestDatabase.create(dir.resolve("parts-fts5.db"), JlcpcbTestDatabase.typed());
        Path sidecar = LcscTestSupport.buildIndex(main);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + sidecar); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE part_index RENAME TO part_index_gone");
            st.execute("CREATE TABLE part_index (a)");   // the metadata is fine, the table is not what the query reads
        }
        wire(main, true);

        Fetched fetched = retrieve("10uF X7R 0805", 10);

        assertThat(numbers(fetched)).contains("C15851");
        verify(client, times(1)).search(anyString(), anyInt(), anyInt());
    }

    @Test
    void relaxationStepsReportWhatTheyLeftOut() throws Exception {
        setUp(JlcpcbTestDatabase.typed(), true);

        // 4.7uF X7R 0805: only an X5R exists; the dielectric is the first constraint of the ladder to go
        Fetched fetched = retrieve("4.7uF X7R 0805", 10);

        assertThat(numbers(fetched)).contains("C60003");
        assertThat(fetched.constraintsRelaxed()).contains("dielectric");
    }

    @Test
    void theTypedStepsOfAFreeTextQueryAreRecognised() {
        var query = ro.alacrity.kina.search.field.FieldQueryBuilder.build(PARSER.parse("RP2040"),
                ConstraintPolicy.DEFAULTS, Distributor.LCSC);
        assertThat(LcscRetriever.constrains(query)).isFalse();
        var capacitor = ro.alacrity.kina.search.field.FieldQueryBuilder.build(PARSER.parse("10uF X7R 0805"),
                ConstraintPolicy.DEFAULTS, Distributor.LCSC);
        assertThat(LcscRetriever.constrains(capacitor)).isTrue();
    }

    @Test
    void partsBelowSpecAreHandedToTheRankerWithoutAPlaceAndCountedAsOnTheFtsPath() throws Exception {
        // ratings are not filtered in SQL (DESIGN.md 3.8): 10 V parts for a 25 V request reach the Java check, take no
        // place in the window and are excluded and counted by the ranker (excluded_below_spec)
        List<ro.alacrity.kina.distributor.lcsc.JlcpcbRow> rows = new java.util.ArrayList<>(JlcpcbTestDatabase.typed());
        for (int i = 0; i < 10; i++) {
            rows.add(JlcpcbTestDatabase.row("C83" + String.format("%04d", i), "Capacitors",
                    "Multilayer Ceramic Capacitors MLCC - SMD/SMT", "OK-" + i, "0805", "Maker", "Extended",
                    "10uF 25V X7R ±10%", "1-:0.02", Integer.toString(1000 + i)));
            rows.add(JlcpcbTestDatabase.row("C84" + String.format("%04d", i), "Capacitors",
                    "Multilayer Ceramic Capacitors MLCC - SMD/SMT", "LOW-" + i, "0805", "Maker", "Extended",
                    "10uF 10V X7R ±10%", "1-:0.02", Integer.toString(1_000_000 + i)));
        }
        setUp(rows, true);

        Fetched fetched = retrieve("10uF X7R 0805 25V", 10);

        assertThat(numbers(fetched)).as("the compliant parts and the below-spec ones")
                .contains("C830000", "C830009", "C840000", "C840009");
        RankingService.RankedResults ranked = ranking.rank(PARSER.parse("10uF X7R 0805 25V"),
                Map.of(Distributor.LCSC, fetched.parts()), Duration.ZERO);
        assertThat(ranked.excludedBelowSpecBy(Distributor.LCSC)).isGreaterThanOrEqualTo(10);
        assertThat(ranked.belowSpecDetailBy(Distributor.LCSC)).isNotEmpty()
                .allSatisfy(d -> assertThat(d.rating()).isEqualTo("voltage"));
        assertThat(ranked.byDistributor().get(Distributor.LCSC)).extracting(r -> r.part().distributorPartNumber())
                .noneMatch(n -> n.startsWith("C84"));
    }

    @Test
    void candidatesTheJavaCheckLeavesOutDoNotTakeAPlaceInTheWindow() throws Exception {
        // the SQL range of 4.7k is wider than the Java check: 4.75k rows are confirmed by SQL and left out by Java; with
        // the most stock they used to fill the window and end the search short (validation 2026-10-09)
        List<ro.alacrity.kina.distributor.lcsc.JlcpcbRow> rows = new java.util.ArrayList<>(JlcpcbTestDatabase.typed());
        for (int i = 0; i < 50; i++) {
            rows.add(JlcpcbTestDatabase.row("C81" + String.format("%04d", i), "Resistors",
                    "Chip Resistor - Surface Mount", "R47-" + i, "0603", "Maker", "Extended",
                    "-55℃~+155℃ 100mW 4.7kΩ 75V Thick Film Resistor ±1% ±100ppm/℃", "1-:0.01",
                    Integer.toString(1000 + i)));
        }
        for (int i = 0; i < 10; i++) {
            rows.add(JlcpcbTestDatabase.row("C82" + String.format("%04d", i), "Resistors",
                    "Chip Resistor - Surface Mount", "R475-" + i, "0603", "Maker", "Extended",
                    "-55℃~+155℃ 100mW 4.75kΩ 75V Thick Film Resistor ±1% ±100ppm/℃", "1-:0.01",
                    Integer.toString(1_000_000 + i)));
        }
        setUp(rows, true);

        Fetched fetched = retrieve("4.7k 1% 0603 resistor", 10);

        assertThat(fetched.parts()).hasSize(40);   // the candidate window, all of them returnable
        assertThat(numbers(fetched)).noneMatch(n -> n.startsWith("C82"));
    }
}
