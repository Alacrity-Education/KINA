package ro.alacrity.kina.distributor.lcsc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.search.ConstraintPolicy;
import ro.alacrity.kina.search.ParametricExtractor;
import ro.alacrity.kina.search.QueryParser;
import ro.alacrity.kina.search.field.FieldQuery;
import ro.alacrity.kina.search.field.FieldQueryBuilder;
import ro.alacrity.kina.search.field.FieldSql;
import ro.alacrity.kina.search.field.SqliteFieldSql;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The typed in-stock table of the sidecar: the build, the attach rules and the field query (DESIGN.md 9.3). */
class LcscFieldIndexTest {

    private static final QueryParser PARSER = new QueryParser();
    private static final Duration WAIT = Duration.ofSeconds(10);

    @TempDir
    Path dir;

    JlcpcbSqliteSearch search;

    @AfterEach
    void tearDown() {
        if (search != null) {
            search.close();
        }
    }

    private static FieldQuery query(String text) {
        return FieldQueryBuilder.build(PARSER.parse(text), ConstraintPolicy.DEFAULTS, Distributor.LCSC);
    }

    private Path database(List<JlcpcbRow> rows) throws Exception {
        return JlcpcbTestDatabase.create(dir.resolve("parts-fts5.db"), rows);
    }

    private static List<String> numbers(LcscFieldSearch.Candidates candidates) {
        return candidates.parts().stream().map(Part::distributorPartNumber).toList();
    }

    // ------------------------------------------------------------------ build

    @Test
    void buildsTheTypedTableOfTheInStockRowsAndLeavesTheMainFileAlone() throws Exception {
        List<JlcpcbRow> rows = JlcpcbTestDatabase.typed();
        Path main = database(rows);
        byte[] before = Files.readAllBytes(main);
        long inStock = rows.stream().filter(r -> r.stockQuantity() > 0).count();

        Path sidecar = FieldIndexFile.sidecar(main);
        assertThat(sidecar.getFileName()).hasToString("parts-fts5.index.db");
        JlcpcbFieldIndexBuilder.Built built = LcscTestSupport.builder(main).buildAndWarm(main, sidecar);

        assertThat(built.rows()).isEqualTo(inStock);
        assertThat(built.skipped()).isZero();
        assertThat(built.sizeBytes()).isPositive();
        assertThat(Files.readAllBytes(main)).isEqualTo(before);   // nothing is written to the live file

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + sidecar);
                Statement st = c.createStatement()) {
            Map<String, String> meta = new LinkedHashMap<>();
            try (ResultSet rs = st.executeQuery("SELECT key, value FROM kina_meta")) {
                while (rs.next()) {
                    meta.put(rs.getString(1), rs.getString(2));
                }
            }
            assertThat(meta).containsEntry("index_version", Integer.toString(ParametricExtractor.INDEX_VERSION))
                    .containsEntry("rows", Long.toString(inStock)).containsKeys("built_at", "source");
            assertThat(count(st, "SELECT count(*) FROM part_index")).isEqualTo(inStock);
            assertThat(count(st, "SELECT count(*) FROM part_index WHERE part_number = 'C99999'")).isZero();   // no stock
            assertThat(count(st, "SELECT count(*) FROM part_index WHERE part_number IN ('C60001', 'C60002')"))
                    .isEqualTo(2);   // blank descriptions are indexed too
            assertThat(count(st, "SELECT count(*) FROM sqlite_stat1")).isPositive();   // ANALYZE ran
            Set<String> indexes = new HashSet<>();
            try (ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index'")) {
                while (rs.next()) {
                    indexes.add(rs.getString(1));
                }
            }
            assertThat(indexes).contains("part_index_cap", "part_index_res", "part_index_pos", "part_index_usb",
                    "part_index_fam", "part_index_fts", "part_index_pkg");
            // fts_rowid points at the row of the FTS table, stock is the ships-now stock
            try (Connection m = JlcpcbSqliteSearch.openReadOnly(main);
                    PreparedStatement ps = m.prepareStatement("SELECT \"LCSC Part\" FROM parts WHERE rowid = ?");
                    ResultSet rs = st.executeQuery("SELECT fts_rowid, part_number, stock FROM part_index")) {
                int checked = 0;
                while (rs.next()) {
                    String number = rs.getString(2);
                    int stock = rs.getInt(3);
                    ps.setLong(1, rs.getLong(1));
                    try (ResultSet row = ps.executeQuery()) {
                        assertThat(row.next()).isTrue();
                        assertThat(row.getString(1)).isEqualTo(number);
                    }
                    JlcpcbRow source = rows.stream().filter(r -> r.lcscPart().equals(number)).findFirst().orElseThrow();
                    assertThat(stock).isEqualTo(source.stockQuantity());
                    checked++;
                }
                assertThat(checked).isEqualTo(inStock);
            }
        }
    }

    private static long count(Statement st, String sql) throws java.sql.SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    // ------------------------------------------------------------------ attach rules

    @Test
    void theTableIsAttachedOnlyWhenEnabledAndCurrent() throws Exception {
        Path main = database(JlcpcbTestDatabase.typed());
        Path sidecar = LcscTestSupport.buildIndex(main);

        search = LcscTestSupport.search(main, false);   // disabled: the sidecar is ignored, today's behaviour
        assertThat(search.fieldIndexAvailable()).isFalse();
        search.close();

        search = LcscTestSupport.search(main, true);
        assertThat(search.fieldIndexAvailable()).isTrue();
        assertThat(search.fieldIndex().rows()).isEqualTo(
                JlcpcbTestDatabase.typed().stream().filter(r -> r.stockQuantity() > 0).count());
        search.close();

        // an older extractor version: no typed table, the FTS path serves
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + sidecar); Statement st = c.createStatement()) {
            st.execute("UPDATE kina_meta SET value = '0' WHERE key = 'index_version'");
        }
        search = LcscTestSupport.search(main, true);
        assertThat(search.isAvailable()).isTrue();
        assertThat(search.fieldIndexAvailable()).isFalse();
        assertThat(search.search("10uF X7R 0805", 0, 10).total()).isPositive();
        search.close();
    }

    @Test
    void aSidecarOfAnotherMainFileIsNotUsed() throws Exception {
        Path main = database(JlcpcbTestDatabase.typed());
        LcscTestSupport.buildIndex(main);
        // a new main file arrives (renamed in), the sidecar still describes the old one
        Path next = JlcpcbTestDatabase.create(dir.resolve("next.db"), JlcpcbTestDatabase.withUsb());
        Files.move(next, main, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        search = LcscTestSupport.search(main, true);
        assertThat(search.isAvailable()).isTrue();
        assertThat(search.fieldIndexAvailable()).isFalse();
    }

    @Test
    void aMissingSidecarMeansNoTypedTable() throws Exception {
        Path main = database(JlcpcbTestDatabase.typed());
        search = LcscTestSupport.search(main, true);
        assertThat(search.isAvailable()).isTrue();
        assertThat(search.fieldIndexAvailable()).isFalse();
        assertThat(LcscTestSupport.fieldSearch(search).available()).isFalse();
    }

    // ------------------------------------------------------------------ field query

    @Test
    void theFieldQueryFindsTheRowsThatStateTheRequestAndKeepsTheOnesThatDoNotState() throws Exception {
        Path main = database(JlcpcbTestDatabase.typed());
        LcscTestSupport.buildIndex(main);
        search = LcscTestSupport.search(main, true);
        LcscFieldSearch field = LcscTestSupport.fieldSearch(search);
        assertThat(field.available()).isTrue();

        FieldQuery query = query("10uF X7R 0805");
        LcscFieldSearch.Candidates all = field.candidates(query, query.step(0), 40, WAIT);
        List<String> found = numbers(all);
        // 10uF X7R 0805 in stock: C15851 and C60004; the X5R (C15850) and 4.7uF rows do not match; C99999 has no stock
        assertThat(found).contains("C15851", "C60004").doesNotContain("C15850", "C60003", "C99999", "C1525");
        // the blank-description capacitor states nothing: never excluded, ranked after the confirmed ones
        assertThat(found).contains("C60001");
        assertThat(found.indexOf("C60001")).isGreaterThan(found.indexOf("C15851"));
        assertThat(all.confirmedOnly()).isFalse();
        assertThat(all.total()).isEqualTo(found.size());
        // the mapped parts carry the row of the JLCPCB file
        Part part = all.parts().stream().filter(p -> p.distributorPartNumber().equals("C15851")).findFirst().orElseThrow();
        assertThat(part.manufacturerPartNumber()).isEqualTo("CL21B106KOQNNNE");
        assertThat(part.stock()).isEqualTo(800000);
        assertThat(part.prices()).isNotEmpty();
    }

    @Test
    void enoughConfirmedRowsAreTheCandidatesOrderedByStock() throws Exception {
        Path main = database(JlcpcbTestDatabase.typedWithMany(300));
        LcscTestSupport.buildIndex(main);
        search = LcscTestSupport.search(main, true);
        LcscFieldSearch field = LcscTestSupport.fieldSearch(search);

        FieldQuery query = query("10uF X7R 0805 25V");
        LcscFieldSearch.Candidates c = field.candidates(query, query.step(0), 40, WAIT);
        assertThat(c.confirmedOnly()).isTrue();
        assertThat(c.total()).isEqualTo(300);   // the bulk capacitors, counted exactly
        assertThat(c.parts()).hasSize(40);
        assertThat(c.parts()).allMatch(p -> p.distributorPartNumber().startsWith("C7"));
        List<Integer> stocks = c.parts().stream().map(Part::stock).toList();
        assertThat(stocks).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(stocks.getFirst()).isEqualTo(1299);

        // a larger window than the confirmed rows: the rows with unstated attributes complete it
        LcscFieldSearch.Candidates wide = field.candidates(query, query.step(0), 400, WAIT);
        assertThat(wide.confirmedOnly()).isFalse();
        assertThat(wide.parts().size()).isGreaterThan(300);
        assertThat(numbers(wide).subList(0, 300)).allMatch(n -> n.startsWith("C7"));
    }

    @Test
    void relaxedStepsAreSupersets() throws Exception {
        Path main = database(JlcpcbTestDatabase.typed());
        LcscTestSupport.buildIndex(main);
        search = LcscTestSupport.search(main, true);
        LcscFieldSearch field = LcscTestSupport.fieldSearch(search);

        FieldQuery query = query("10uF X7R 0805 25V");
        List<FieldQuery.Step> steps = query.steps();
        assertThat(steps.size()).isGreaterThan(1);
        Set<String> previous = new HashSet<>();
        for (FieldQuery.Step step : steps) {
            Set<String> now = new HashSet<>(numbers(field.candidates(query, step, 1000, WAIT)));
            assertThat(now).containsAll(previous);
            previous = now;
        }
    }

    @Test
    void theConfirmedStatementIsExactlyTheConfirmedRowsOfTheSuperset() throws Exception {
        Path main = database(JlcpcbTestDatabase.typedWithMany(50));
        LcscTestSupport.buildIndex(main);
        search = LcscTestSupport.search(main, true);
        for (String text : List.of("10uF X7R 0805 25V", "4.7k 1% 0603", "10k resistor 0805", "100nF 0402 capacitor",
                "female header 1x6 right angle", "USB-C receptacle 16 pin", "Type-C 16P", "RP2040")) {
            FieldQuery query = query(text);
            for (FieldQuery.Step step : query.steps()) {
                FieldSql.Statement all = SqliteFieldSql.INSTANCE.candidates(query, step, 100000);
                FieldSql.Statement confirmed = SqliteFieldSql.CONFIRMED.candidates(query, step, 100000);
                // "stated" of the superset is the number of stated attributes, "rated" the requested ratings the
                // part meets: the first tier of the order (all stated, every rating met) is the confirmed rows
                List<Object[]> rows = run(all);
                String sql = all.sql();
                String stated = sql.substring("SELECT fts_rowid, part_number, ".length(), sql.indexOf(" AS stated"));
                int maxStated = stated.split("\\) \\+ \\(").length;
                int ratings = query.ratings().size();
                Set<String> expected = new HashSet<>();
                rows.stream().filter(r -> ((Number) r[1]).intValue() == maxStated && ((Number) r[2]).intValue() == ratings)
                        .forEach(r -> expected.add((String) r[0]));
                Set<String> actual = new HashSet<>();
                run(confirmed).forEach(r -> actual.add((String) r[0]));
                assertThat(actual).as("%s step %d", text, step.index()).isEqualTo(expected);
                assertThat(count(SqliteFieldSql.CONFIRMED.count(query, step))).isEqualTo(actual.size());
            }
        }
    }

    private long count(FieldSql.Statement statement) throws Exception {
        return search.withConnection(null, c -> {
            try (PreparedStatement ps = c.prepareStatement(statement.sql())) {
                bind(ps, statement.params());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    /** (part_number, stated, rated) of the rows of a candidates statement. */
    private List<Object[]> run(FieldSql.Statement statement) throws Exception {
        return search.withConnection(null, c -> {
            List<Object[]> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(statement.sql())) {
                bind(ps, statement.params());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Object[] {rs.getString(2), rs.getInt(3), rs.getInt(5)});
                    }
                }
            }
            return out;
        });
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws java.sql.SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    // ------------------------------------------------------------------ plans

    @Test
    void theConfirmedQueriesSeekAnIndexInsteadOfScanningTheTable() throws Exception {
        Path main = database(JlcpcbTestDatabase.typedWithMany(3000));
        LcscTestSupport.buildIndex(main);
        search = LcscTestSupport.search(main, true);
        // the value, package and positions indexes lead with the family: every confirmed query is an index seek
        // (which one depends on the selectivity the ANALYZE statistics show; the full file picks the value index)
        for (String text : List.of("10uF X7R 0805 25V", "4.7k 1% 0603")) {
            FieldQuery query = query(text);
            for (FieldSql.Statement statement : List.of(SqliteFieldSql.CONFIRMED.count(query, query.step(0)),
                    SqliteFieldSql.CONFIRMED.candidates(query, query.step(0), 40))) {
                assertThat(plan(statement)).as(text).contains("SEARCH part_index USING INDEX part_index_")
                        .doesNotContain("SCAN part_index");
            }
        }
        // connectors and USB seek on the positions and USB type indexes
        for (String text : List.of("female header 1x6 right angle", "USB-C receptacle 16 pin")) {
            FieldQuery query = query(text);
            String plan = plan(SqliteFieldSql.CONFIRMED.count(query, query.step(0)));
            assertThat(plan).as(text).contains("SEARCH part_index USING INDEX").doesNotContain("SCAN part_index");
        }
    }

    private String plan(FieldSql.Statement statement) throws Exception {
        return search.withConnection(null, c -> {
            StringBuilder plan = new StringBuilder();
            try (PreparedStatement ps = c.prepareStatement("EXPLAIN QUERY PLAN " + statement.sql())) {
                bind(ps, statement.params());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        plan.append(rs.getString(4)).append('\n');
                    }
                }
            }
            return plan.toString();
        });
    }

    @Test
    void rowsOfUnknownFamilyAreLeftToTheFtsSearch() throws Exception {
        // a fan with a blank description (family from its category only) and well-stocked 12 V parts of a family the
        // parser does not know: the typed candidates hold the fan only, the unknown rows would flood the window with
        // parts the ranker cannot tell from the request (validation 2026-10-09)
        List<ro.alacrity.kina.distributor.lcsc.JlcpcbRow> rows = new ArrayList<>(JlcpcbTestDatabase.typed());
        rows.add(JlcpcbTestDatabase.row("C729744", "Industrial control electrical", "Cooling fan", "FAN-BLANK", "-",
                "Fanmaker", "Extended", "", "1-:2.0", "10"));
        for (int i = 0; i < 50; i++) {
            rows.add(JlcpcbTestDatabase.row("C8" + String.format("%05d", i), "Power Management", "Odd Converters",
                    "ODD-" + i, "SOT-23-6", "Maker", "Extended", "-40℃~+85℃ 12V 150kHz 2A Odd", "1-:0.1",
                    Integer.toString(100000 + i)));
        }
        Path main = database(rows);
        LcscTestSupport.buildIndex(main);
        search = LcscTestSupport.search(main, true);
        LcscFieldSearch field = LcscTestSupport.fieldSearch(search);

        FieldQuery query = query("40x40x10 fan 12V");
        LcscFieldSearch.Candidates candidates = field.candidates(query, query.step(0), 40, WAIT);
        assertThat(numbers(candidates)).containsExactly("C729744");
        assertThat(candidates.total()).isEqualTo(1);
    }

    @Test
    void partsMatchingASoftKindComeFirst() throws Exception {
        // rows is soft (never a filter): a 2x3 right-angle female header with more stock used to come before the 1x6
        // ones the request names (validation 2026-10-09)
        Path main = database(JlcpcbTestDatabase.typed());
        LcscTestSupport.buildIndex(main);
        search = LcscTestSupport.search(main, true);
        LcscFieldSearch field = LcscTestSupport.fieldSearch(search);

        FieldQuery query = query("female header 1x6 right angle");
        assertThat(query.soft()).isNotEmpty();
        List<String> found = numbers(field.candidates(query, query.step(0), 4, WAIT));
        assertThat(found).hasSize(4).contains("C2897388").doesNotContain("C2897423");
    }
}
