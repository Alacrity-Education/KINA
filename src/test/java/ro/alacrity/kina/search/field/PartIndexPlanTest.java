package ro.alacrity.kina.search.field;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import ro.alacrity.kina.TestWiring;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.search.ConstraintPolicy;
import ro.alacrity.kina.search.ParametricExtractor;
import ro.alacrity.kina.search.QueryParser;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The evaluation queries use the indexes they were made for (review A8; DESIGN.md 3.8, 9.3), checked with
 * {@code EXPLAIN} on 40 000 synthetic rows in PostgreSQL and in an in-memory SQLite table of the same shape. The
 * superset form ({@code col IS NULL OR col BETWEEN}) cannot use the partial value indexes ({@code WHERE col IS NOT
 * NULL}); it reads the family through {@code part_index_fam_idx}. The stated-only form, which
 * {@link PartIndexRepository#query} runs first, seeks the value index of the request's primary value, and when it fills
 * the limit it returns exactly the superset form's first rows.
 */
class PartIndexPlanTest {

    /** The evaluation queries and the partial value index each one's stated form must seek. */
    private static final Map<String, String> QUERIES = Map.of(
            "10uF X7R 0805 25V", "part_index_cap_idx",
            "4.7k 1% 0603 resistor", "part_index_res_idx",
            "10uH inductor 0805", "part_index_ind_idx",
            "ferrite bead 600 ohm 0603", "part_index_imp_idx");
    private static final int ROWS = 40_000;

    private static PostgreSQLContainer postgres;
    private static JdbcClient jdbc;
    private static PartIndexRepository index;
    private static Connection sqlite;
    private final QueryParser parser = new QueryParser();

    @BeforeAll
    static void start() throws Exception {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
        postgres.start();
        DriverManagerDataSource ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(ds);
        jdbc.sql("""
                INSERT INTO cached_parts (distributor, part_number, payload, stock_fetched_at, metadata_fetched_at,
                                          in_stock, type, metadata_md5)
                SELECT 'MOUSER', 'P' || g, '{}'::jsonb, now(), now(), true, 'unknown', 'x'
                FROM generate_series(1, ?) g""").param(ROWS).update();
        jdbc.sql("INSERT INTO part_index (distributor, part_number, extractor_version, indexed_at, payload_md5, "
                + "in_stock, family, package_key, package_readable, capacitance_f, resistance_ohm, inductance_h, "
                + "impedance_ohm, dielectric, tolerance_pct, voltage_v, metadata_md5) SELECT 'MOUSER', 'P' || g, ?, "
                + "now(), '', true, " + synthetic("power", "ARRAY", "", "true") + ", 'x' FROM generate_series(1, ?) g")
                .params(ParametricExtractor.INDEX_VERSION, ROWS).update();
        jdbc.sql("ANALYZE part_index").update();
        index = TestWiring.wire(new PartIndexRepository(), "jdbc", jdbc, "jdbcTemplate", new JdbcTemplate(ds),
                "extractor", new ParametricExtractor(), "jsonMapper", JsonMapper.builder().build(),
                "clock", Clock.systemUTC(), "transactionManager", new DataSourceTransactionManager(ds));

        sqlite = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = sqlite.createStatement()) {
            st.execute("CREATE VIRTUAL TABLE parts USING fts5(search_text, tokenize = 'trigram')");
        }
        SqlitePartIndex.create(sqlite, "part_index");
        try (Statement st = sqlite.createStatement()) {
            st.execute("WITH RECURSIVE n(g) AS (SELECT 1 UNION ALL SELECT g + 1 FROM n WHERE g < " + ROWS + ") "
                    + "INSERT INTO part_index (fts_rowid, distributor, part_number, extractor_version, in_stock, "
                    + "family, package_key, package_readable, capacitance_f, resistance_ohm, inductance_h, "
                    + "impedance_ohm, dielectric, tolerance_pct, voltage_v, stock) SELECT g, 'LCSC', 'C' || g, "
                    + ParametricExtractor.INDEX_VERSION + ", 1, " + synthetic("pow", "json_array", "->>", "1") + ", g "
                    + "FROM n");
            st.execute("ANALYZE");
        }
    }

    /**
     * The synthetic columns of row {@code g}: ten families, four packages, a primary value for the passives (one row in
     * seven of a family does not state it), a dielectric, a tolerance and a voltage.
     */
    private static String synthetic(String power, String array, String element, String yes) {
        String pick = element.isEmpty() ? "(%s[%s])[1 + %s]" : "(%s(%s) " + element + " (%s))";
        return String.join(", ",
                pick.formatted(array, "'capacitor','resistor','inductor','ferrite','led','connector','mosfet',"
                        + "'diode','regulator','switch'", "g % 10"),
                pick.formatted(array, "'0402','0603','0805','1206'", "(g / 10) % 4"),
                yes,
                "CASE WHEN g % 10 = 0 AND g % 7 <> 0 THEN " + power + "(10, -(4 + g % 9)) * (1 + g % 13) END",
                "CASE WHEN g % 10 = 1 AND g % 7 <> 0 THEN " + power + "(10, g % 7) * (1 + g % 13) END",
                "CASE WHEN g % 10 = 2 AND g % 7 <> 0 THEN " + power + "(10, -(4 + g % 5)) * (1 + g % 13) END",
                "CASE WHEN g % 10 = 3 AND g % 7 <> 0 THEN 10 * (1 + g % 97) END",
                "CASE WHEN g % 10 = 0 THEN " + pick.formatted(array, "'x7r','x5r','c0g'", "g % 3") + " END",
                "CASE WHEN g % 10 = 1 THEN " + pick.formatted(array, "1, 5, 0.1", "g % 3") + " END",
                "CASE WHEN g % 10 = 0 THEN " + pick.formatted(array, "6.3, 10, 16, 25, 50", "g % 5") + " END");
    }

    @AfterAll
    static void stop() throws Exception {
        if (sqlite != null) {
            sqlite.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    private FieldQuery query(String text, Distributor distributor) {
        return FieldQueryBuilder.build(parser.parse(text), ConstraintPolicy.DEFAULTS, distributor);
    }

    private static String postgresPlan(FieldSql.Statement statement) {
        return String.join("\n", jdbc.sql("EXPLAIN " + statement.sql()).params(statement.params())
                .query(String.class).list());
    }

    @Test
    void postgresReadsTheFamilyForTheSupersetAndSeeksTheValueIndexForTheStatedForm() {
        QUERIES.forEach((text, valueIndex) -> {
            FieldQuery query = query(text, Distributor.MOUSER);
            String superset = postgresPlan(PostgresFieldSql.INSTANCE.select(query, query.step(0), 100));
            assertThat(superset).as("%s superset", text).contains("part_index_fam_idx")
                    .doesNotContain("Seq Scan");
            String stated = postgresPlan(PostgresFieldSql.STATED.select(query, query.step(0), 100));
            assertThat(stated).as("%s stated", text).contains(valueIndex).doesNotContain("Seq Scan");
        });
    }

    @Test
    void theStatedFormReturnsTheSupersetsFirstRowsWhenItFillsTheLimit() {
        for (String text : List.of("10uF X7R 0805 25V", "4.7k 1% 0603 resistor", "capacitor 0805", "resistor 0603",
                "100nF capacitor 0402 50V")) {
            FieldQuery query = query(text, Distributor.MOUSER);
            for (int limit : List.of(1, 5, 50)) {
                FieldSql.Statement superset = PostgresFieldSql.INSTANCE.select(query, query.step(0), limit);
                List<String> expected = jdbc.sql(superset.sql()).params(superset.params())
                        .query((rs, n) -> rs.getString(2)).list();
                List<String> actual = index.query(query, query.step(0), limit).stream()
                        .map(PartIndexRepository.Hit::partNumber).toList();
                assertThat(actual).as("%s, limit %d", text, limit).isEqualTo(expected);
            }
        }
    }

    @Test
    void sqliteSeeksAnIndexForTheStatedForm() throws Exception {
        for (String text : QUERIES.keySet()) {
            FieldQuery query = query(text, Distributor.LCSC);
            for (FieldSql.Statement statement : List.of(SqliteFieldSql.CONFIRMED.count(query, query.step(0)),
                    SqliteFieldSql.CONFIRMED.candidates(query, query.step(0), 40))) {
                assertThat(sqlitePlan(statement)).as(text).contains("SEARCH part_index USING INDEX part_index_")
                        .doesNotContain("SCAN part_index");
            }
        }
    }

    private static String sqlitePlan(FieldSql.Statement statement) throws Exception {
        StringBuilder plan = new StringBuilder();
        try (PreparedStatement ps = sqlite.prepareStatement("EXPLAIN QUERY PLAN " + statement.sql())) {
            for (int i = 0; i < statement.params().size(); i++) {
                ps.setObject(i + 1, statement.params().get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    plan.append(rs.getString(4)).append('\n');
                }
            }
        }
        return plan.toString();
    }
}
