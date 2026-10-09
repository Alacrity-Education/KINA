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
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.cache.PhraseJournalRepository;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.search.ParametricExtractor;
import ro.alacrity.kina.search.QueryParser;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cache survives V14, V15 (the phrase journal) and the backfills (DESIGN.md 3.8 "Cache preservation"): a database migrated to
 * V1 gets cached rows in the old shape (derived attributes in the payload, an unreadable payload), is migrated to V13
 * and gets rows in the V13 shape (sold-out rows, untyped rows, Mouser parts without a package); then V14 runs. Every
 * {@code cached_parts} and {@code cached_searches} row must be byte-identical before and after V14 and after the
 * re-index, and the re-index must cover every row, in stock or not.
 */
class CachePreservationMigrationTest {

    private static final Path DUMP = Path.of("src/test/resources/fixtures/cache-dump");

    private static PostgreSQLContainer postgres;
    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
        postgres.start();
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
        jdbc = JdbcClient.create(dataSource);
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load();
    }

    @Test
    void v14AndTheBackfillLeaveTheCacheByteIdenticalAndCoverEveryRow() throws IOException {
        flyway("1").migrate();
        run("pre-v8.sql");
        flyway("13").migrate();
        run("v13.sql");

        List<String> parts = snapshot("cached_parts");
        List<String> searches = snapshot("cached_searches");
        assertThat(parts).hasSize(29);
        assertThat(searches).hasSize(2);

        flyway("14").migrate();
        assertThat(jdbc.sql("SELECT max(version::int) FROM flyway_schema_history WHERE success")
                .query(Integer.class).single()).isEqualTo(14);
        assertThat(snapshot("cached_parts")).as("cached_parts after V14").isEqualTo(parts);
        assertThat(snapshot("cached_searches")).as("cached_searches after V14").isEqualTo(searches);
        assertThat(jdbc.sql("SELECT count(*) FROM part_index").query(Long.class).single()).isZero();

        // V15 only adds the phrase journal; V16 only adds the metadata hash columns (NULL) and two covering indexes
        flyway("16").migrate();
        assertThat(jdbc.sql("SELECT max(version::int) FROM flyway_schema_history WHERE success")
                .query(Integer.class).single()).isEqualTo(16);
        assertThat(snapshot("cached_parts")).as("cached_parts after V16").isEqualTo(parts);
        assertThat(snapshot("cached_searches")).as("cached_searches after V16").isEqualTo(searches);
        assertThat(jdbc.sql("SELECT count(*) FROM cached_parts WHERE metadata_md5 IS NOT NULL").query(Long.class)
                .single()).isZero();

        PartIndexRepository index = repository();
        PartCacheRepository cache = TestWiring.wire(new PartCacheRepository(), "jdbc", jdbc,
                "jdbcTemplate", new JdbcTemplate(dataSource), "jsonMapper", JsonMapper.builder().build(),
                "clock", Clock.systemUTC());
        Map<Distributor, PartIndexRepository.Coverage> before = index.coverage();
        assertThat(before.values()).allMatch(c -> !c.complete());
        assertThat(index.isComplete(Distributor.TME)).isFalse();
        assertThat(index.isComplete(Distributor.MOUSER)).isFalse();

        // the startup job: the cache's metadata hashes first (only that column), then the index rows
        assertThat(cache.backfillMetadataHashes(7)).isEqualTo(29);
        assertThat(cache.backfillMetadataHashes(7)).as("a second run fills nothing").isZero();
        assertThat(index.backfillMetadataHashes()).as("no index rows yet").isZero();
        PartIndexRepository.ReindexReport report = index.reindexStale(ParametricExtractor.INDEX_VERSION, 7);
        assertThat(report.total()).isEqualTo(29);
        assertThat(report.unreadable()).isEqualTo(1);
        Map<Distributor, PartIndexRepository.Coverage> after = index.coverage();
        assertThat(after.values()).allMatch(PartIndexRepository.Coverage::complete);
        assertThat(index.countByDistributor().get(Distributor.TME)).isEqualTo(after.get(Distributor.TME).cachedParts());
        assertThat(index.countByDistributor().get(Distributor.MOUSER))
                .isEqualTo(after.get(Distributor.MOUSER).cachedParts());
        // the stock flag is copied: sold-out rows stay indexed, never served
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM cached_parts c JOIN part_index i USING (distributor, part_number)
                        WHERE c.in_stock <> i.in_stock""").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM part_index WHERE NOT in_stock").query(Long.class).single())
                .isEqualTo(4);
        // a second pass finds nothing to do
        assertThat(index.reindexStale(ParametricExtractor.INDEX_VERSION, 7).total()).isZero();

        assertThat(snapshot("cached_parts")).as("cached_parts after the backfill").isEqualTo(parts);
        assertThat(snapshot("cached_searches")).as("cached_searches after the backfill").isEqualTo(searches);

        // an index written before V16 (no hashes) whose stock refresh rewrote one payload (the v0.15.0 defect): the
        // rows built from the payload the cache holds get the hash without extraction, only the other is re-indexed
        jdbc.sql("UPDATE part_index SET metadata_md5 = NULL").update();
        jdbc.sql("UPDATE cached_parts SET metadata_md5 = NULL").update();
        String refreshed = jdbc.sql("""
                        SELECT part_number FROM cached_parts
                        WHERE distributor = 'MOUSER' AND in_stock AND payload ->> 'stock' IS NOT NULL
                        ORDER BY part_number LIMIT 1""")
                .query(String.class).single();
        jdbc.sql("""
                        UPDATE cached_parts SET payload = jsonb_set(payload, '{stock}', '4321')
                        WHERE distributor = 'MOUSER' AND part_number = ?""").param(refreshed).update();
        List<String> refreshedParts = snapshot("cached_parts");
        index.forgetCoverage();
        assertThat(index.isComplete(Distributor.MOUSER)).isFalse();
        assertThat(cache.backfillMetadataHashes(7)).isEqualTo(29);
        assertThat(index.backfillMetadataHashes()).isEqualTo(28);
        PartIndexRepository.ReindexReport repaired = index.reindexStale(ParametricExtractor.INDEX_VERSION, 7);
        assertThat(repaired.total()).as("only the row built from an older payload").isEqualTo(1);
        assertThat(index.coverage().values()).allMatch(PartIndexRepository.Coverage::complete);
        assertThat(snapshot("cached_parts")).as("only the hash column was written").isEqualTo(refreshedParts);

        // the journal backfill only reads cached_searches
        assertThat(jdbc.sql("SELECT count(*) FROM distributor_phrases").query(Long.class).single()).isZero();

        PhraseJournalRepository journal = TestWiring.wire(new PhraseJournalRepository(), "jdbc", jdbc);
        PhraseJournalBackfill backfill = TestWiring.wire(new PhraseJournalBackfill(),
                "properties", TestWiring.properties(), "jdbc", jdbc, "journal", journal, "parser", new QueryParser(),
                "clock", Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC));
        // only the search younger than 2 x kina.cache.ttl is history worth keeping (the other would be purged)
        assertThat(backfill.run()).isEqualTo(2);
        assertThat(backfill.run()).as("a second run adds nothing").isZero();
        PhraseJournalRepository.Entry fallback = journal.find(Distributor.MOUSER, "100nf").orElseThrow();
        assertThat(fallback.rawTotal()).isEqualTo(120);
        assertThat(fallback.nextOffset()).isEqualTo(50);
        assertThat(fallback.outOfStock()).isEqualTo(2);
        assertThat(fallback.askedAt()).isEqualTo(Instant.parse("2026-10-06T08:00:00Z"));
        assertThat(fallback.ladderStep()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM distributor_phrases WHERE distributor = 'TME'")
                .query(Long.class).single()).isZero();
        assertThat(snapshot("cached_parts")).as("cached_parts after the journal backfill").isEqualTo(refreshedParts);
        assertThat(snapshot("cached_searches")).as("cached_searches after the journal backfill").isEqualTo(searches);
    }

    private static PartIndexRepository repository() {
        return TestWiring.wire(new PartIndexRepository(), "jdbc", jdbc, "jdbcTemplate", new JdbcTemplate(dataSource),
                "extractor", new ParametricExtractor(), "jsonMapper", JsonMapper.builder().build(),
                "clock", Clock.systemUTC(), "transactionManager", new DataSourceTransactionManager(dataSource));
    }

    private static void run(String file) throws IOException {
        for (String line : Files.readAllLines(DUMP.resolve(file), StandardCharsets.UTF_8)) {
            if (!line.isBlank() && !line.startsWith("--")) {
                jdbc.sql(line).update();
            }
        }
    }

    /**
     * Every row of {@code table} as its JSON text, in key order, without the column V16 adds (it starts NULL and the
     * startup job fills it: every other column must stay byte-identical).
     */
    private static List<String> snapshot(String table) {
        String key = table.equals("cached_parts") ? "distributor, part_number" : "distributor, query_key";
        return jdbc.sql("SELECT (to_jsonb(t) - 'metadata_md5')::text FROM " + table + " t ORDER BY " + key)
                .query(String.class).list();
    }
}
