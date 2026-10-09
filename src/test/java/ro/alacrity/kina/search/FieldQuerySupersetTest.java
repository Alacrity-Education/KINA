package ro.alacrity.kina.search;

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
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.ConstraintKind;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PartKey;
import ro.alacrity.kina.search.field.FieldQuery;
import ro.alacrity.kina.search.field.FieldQueryBuilder;
import ro.alacrity.kina.search.field.FieldSql;
import ro.alacrity.kina.search.field.PartIndexRepository;
import ro.alacrity.kina.search.field.PartIndexRow;
import ro.alacrity.kina.search.field.SqliteFieldSql;
import ro.alacrity.kina.search.field.SqlitePartIndex;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The superset invariant of the field index (DESIGN.md 3.8, study risk R1): SQL is a recall filter, Java the judge.
 * Every part of a pool (every candidate of {@code docs/research/data/ranking-eval.jsonl}, the parts of the recorded LED,
 * switch, fan and power-resistor searches) is cached and indexed by the real writer and re-index job; then, for every
 * query (the 41 evaluation queries, the queries of the recordings and a list of extra queries) and every relaxation
 * step, every pool part the Java check keeps ({@code PageCollector.Check.returnable}, and no
 * mismatch on the ladder kinds still in the step) must be returned by the SQL, in the PostgreSQL dialect and in the
 * SQLite dialect (an in-memory {@code part_index} built from the same rows). Parts the extractor reads poorly (blank
 * LCSC descriptions, Mouser parts without a package) are part of the pool and must stay reachable. A per-query report is
 * written to {@code target/field-superset-report.txt}.
 *
 * <p>Two optional system properties extend the run with outside data, e.g. the cache of a production dump
 * ({@code scripts/e2e/field-search}, docs/research/field-search-validation-2026-10-09.md):
 * {@code kina.superset.pool} names a file with one stored part per line ({@code cached_parts.payload}),
 * {@code kina.superset.queries} a file with one query per line. Without them the test runs on the repository data only.
 */
class FieldQuerySupersetTest {

    private static final Path DATASET = Path.of("docs/research/data/ranking-eval.jsonl");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final List<String> FIXTURE_DIRS = List.of("leds", "switches", "fans", "power-resistors");
    /** Queries beyond the evaluation set and the recordings: every family and constraint kind at least once. */
    private static final List<String> EXTRA = List.of(
            "16MHz crystal 18pF 3225 SMD", "LDO 5V SOT-23-5", "adjustable regulator SOT-223", "P-channel MOSFET SOT-23",
            "TVS diode 5V SOD-323", "1x6 male pin header vertical 2.54mm THT", "terminal block 2 position 5.08mm",
            "USB-C receptacle 16 pin hybrid", "micro USB B receptacle 5 pin SMD", "USB-C receptacle 16 pin SMD USB 2.0",
            "resistor 10k 0603 TCR 25ppm", "100uF 25V low ESR", "10k 0603 1% thin film",
            "ferrite bead 600 ohm 100MHz 0603 2A", "10uF 25V X5R 0805", "1uF 50V 0603 C0G", "RJ45 jack",
            "100 ohm 1W 2512 resistor", "16MHz oscillator 3.3V", "Zener 3.3V 500mW SOD-123",
            "electrolytic capacitor 470uF 35V 105°C 5000h THT", "10uH inductor 1210 Isat 2A DCR < 100mOhm",
            "female header 2x10 2.54mm vertical SMD", "Schottky 40V 1A SOD-123", "1k resistor array 0603 4 elements",
            "chassis mount resistor 50W", "tantalum 47uF 10V 1206", "4.7k 1% 0603 resistor thin film",
            "10uF X7R 0805 25V", "40mm fan 12V", "red LED 0603", "tactile switch 6x6mm SMD",
            // free text: keywords inside words, short keywords, part numbers
            "samsung 10uF 0805 25V ceramic capacitor", "yageo 10k 0603 resistor automotive", "murata 100nF X7R 0402 gcm",
            "wurth ferrite 600 ohm 0603 high current", "GRM21BR71A106KE51L", "ESP32-WROOM-32 wifi module with antenna",
            "buck converter 3A adjustable", "tdk mlcc 4.7uF 0603 16V");

    private static PostgreSQLContainer postgres;
    private static PartIndexRepository index;
    private static JdbcClient postgresJdbc;
    private static Connection sqlite;
    private static final ParametricExtractor EXTRACTOR = new ParametricExtractor();
    private static final QueryParser PARSER = new QueryParser();
    private static final Map<String, Part> POOL = new LinkedHashMap<>();
    private static final Map<String, Part> ENRICHED = new LinkedHashMap<>();
    private static final List<String> QUERIES = new ArrayList<>();
    private static RankingService ranking;
    private static final java.util.concurrent.atomic.AtomicLong TEXT_CHECKS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong TEXT_EXPECTED =
            new java.util.concurrent.atomic.AtomicLong();

    @BeforeAll
    static void build() throws Exception {
        load();
        KinaProperties props = TestWiring.properties();
        ranking = TestWiring.rankingService(props, TestWiring.deterministicRanker(EXTRACTOR), null, () -> null,
                TestWiring.scoreCache(props.ranking().scoreCacheTtl()));
        POOL.forEach((key, part) -> ENRICHED.put(key, EXTRACTOR.enrich(part)));

        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
        postgres.start();
        DriverManagerDataSource ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        JdbcClient jdbc = JdbcClient.create(ds);
        postgresJdbc = jdbc;
        OffsetDateTime now = Instant.parse("2026-10-08T00:00:00Z").atOffset(ZoneOffset.UTC);
        for (Part part : POOL.values()) {
            String json = MAPPER.writeValueAsString(part.asStored());
            jdbc.sql("""
                            INSERT INTO cached_parts (distributor, part_number, payload, stock_fetched_at,
                                                      metadata_fetched_at, in_stock, type, metadata_md5)
                            VALUES (?, ?, ?::jsonb, ?, ?, true, 'unknown', ?)""")
                    .params(part.distributor().name(), part.distributorPartNumber(), json, now, now,
                            ro.alacrity.kina.cache.PartMetadataHash.ofPayload(json))
                    .update();
        }
        index = TestWiring.wire(new PartIndexRepository(), "jdbc", jdbc, "jdbcTemplate", new JdbcTemplate(ds),
                "extractor", EXTRACTOR, "jsonMapper", MAPPER, "clock", Clock.systemUTC(),
                "transactionManager", new DataSourceTransactionManager(ds));
        assertThat(index.reindexStale(ParametricExtractor.INDEX_VERSION, 500).total()).isEqualTo(POOL.size());
        assertThat(index.coverage().values()).allMatch(PartIndexRepository.Coverage::complete);

        sqlite = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (var s = sqlite.createStatement()) {
            s.execute("CREATE VIRTUAL TABLE parts USING fts5(search_text, tokenize = 'trigram')");
        }
        SqlitePartIndex.create(sqlite, "part_index");
        List<PartIndexRow> rows = new ArrayList<>();
        List<Long> rowids = new ArrayList<>();
        long rowid = 0;
        try (PreparedStatement ps = sqlite.prepareStatement("INSERT INTO parts (rowid, search_text) VALUES (?, ?)")) {
            for (Part part : POOL.values()) {
                PartIndexRow row = PartIndexRows.of(EXTRACTOR, part, true);
                rows.add(row);
                rowids.add(++rowid);
                ps.setLong(1, rowid);
                ps.setString(2, row.searchText());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        SqlitePartIndex.insert(sqlite, "part_index", rows, rowids);
    }

    @AfterAll
    static void stop() throws SQLException {
        if (sqlite != null) {
            sqlite.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void theFieldQueryReturnsEveryPartTheJavaCheckKeeps() throws IOException {
        List<String> report = new ArrayList<>();
        report.add(String.format("%-52s %6s %6s %6s %6s %6s %6s", "query", "pool", "java", "pg", "sqlite", "step0",
                "gaps"));
        Set<String> gapParts = gapParts();
        // the queries are independent: checked in parallel (the Java check is the slow part), reported in order
        List<Result> results = QUERIES.parallelStream().map(text -> check(text, gapParts)).toList();
        List<String> violations = new ArrayList<>();
        long gapsReturned = 0;
        for (Result r : results) {
            report.add(r.line());
            violations.addAll(r.violations());
            gapsReturned += r.gaps();
        }
        report.add("gap parts in the pool (blank LCSC description or Mouser without a package): " + gapParts.size()
                + "; returned by the Java check over all queries: " + gapsReturned);
        report.add("steps with free text checked: " + TEXT_CHECKS.get() + "; parts expected in them: "
                + TEXT_EXPECTED.get());
        Path out = Path.of("target/field-superset-report.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, report, StandardCharsets.UTF_8);
        report.forEach(System.out::println);
        assertThat(gapParts).as("the pool holds parts the extractor reads poorly").hasSizeGreaterThan(20);
        assertThat(QUERIES).hasSizeGreaterThanOrEqualTo(41 + EXTRA.size());
        assertThat(TEXT_CHECKS.get()).as("steps with free text").isGreaterThan(10);
        assertThat(TEXT_EXPECTED.get()).as("parts the free-text steps must return").isGreaterThan(100);
        assertThat(violations).as("parts the Java check keeps that the field query drops").isEmpty();
    }

    private record Result(String line, List<String> violations, long gaps) {
    }

    /**
     * One query: every step (with free text too), both dialects. Ratings are never filtered in SQL (they only order),
     * so the SQL is the same with and without {@code allow_below_spec} and must keep every part the Java check keeps
     * with it (a superset of the parts it keeps without): a part below spec reaches the check, which excludes and
     * counts it.
     */
    private static Result check(String text, Set<String> gapParts) {
        ParsedQuery parsed = PARSER.parse(text);
        List<String> violations = new ArrayList<>();
        FieldQuery query = FieldQueryBuilder.build(parsed, ConstraintPolicy.DEFAULTS, null);
        for (FieldQuery.Step step : query.steps()) {
            step.predicates().stream().filter(p -> p.kind() != null && p.kind().isRating()
                            && p.kind().generalStrategy() == ro.alacrity.kina.domain.RelaxStrategy.BELOW_SPEC)
                    .forEach(p -> violations.add(text + " step " + step.index() + ": a rating filters ("
                            + p.kind() + ")"));
        }
        Set<String> returnable = new LinkedHashSet<>();
        int returnableStrict = 0;
        for (Map.Entry<String, Part> e : ENRICHED.entrySet()) {
            if (PageCollector.Check.returnable(ranking, parsed, e.getValue(), true)) {
                returnable.add(e.getKey());
                if (PageCollector.Check.returnable(ranking, parsed, e.getValue(), false)) {
                    returnableStrict++;
                }
            }
        }
        Set<String> pgRelaxed = null;
        Set<String> liteRelaxed = null;
        for (FieldQuery.Step step : query.steps()) {
            // with free text in the step, a part must also state every keyword the way the ranker's lexical score
            // finds it (a substring of its text) and carry a requested part number as an MPN prefix
            boolean withText = query.group(FieldQuery.Role.K.name()) != null
                    && !step.dropped().contains(FieldQuery.Role.K.name());
            List<ConstraintKind> ladder = query.groups(FieldQuery.Role.L).stream()
                    .filter(g -> !step.dropped().contains(g.name())).flatMap(g -> g.kinds().stream())
                    .toList();
            Set<String> expected = new TreeSet<>();
            for (String key : returnable) {
                if (meetsLadder(parsed, ENRICHED.get(key), ladder)
                        && (!withText || statesText(parsed, ENRICHED.get(key)))) {
                    expected.add(key);
                }
            }
            if (withText) {
                TEXT_CHECKS.incrementAndGet();
                TEXT_EXPECTED.addAndGet(expected.size());
            }
            Set<String> pg = postgres(query, step);
            Set<String> lite = sqlite(query, step);
            Set<String> missingPg = new TreeSet<>(expected);
            missingPg.removeAll(pg);
            Set<String> missingLite = new TreeSet<>(expected);
            missingLite.removeAll(lite);
            if (!missingPg.isEmpty() || !missingLite.isEmpty()) {
                violations.add(text + " (step " + step.index()
                        + " without " + step.dropped() + "): Postgres misses " + missingPg + ", SQLite misses "
                        + missingLite);
            }
            if (!lite.equals(pg)) {
                violations.add(text + " step " + step.index() + ": the dialects differ");
            }
            // PostgreSQL reads the first tier with the stated-only form: its first rows are the superset form's
            for (int limit : List.of(1, 3)) {
                FieldSql.Statement top = ro.alacrity.kina.search.field.PostgresFieldSql.INSTANCE.select(query, step,
                        limit);
                List<String> expectedTop = postgresJdbc.sql(top.sql()).params(top.params())
                        .query((rs, n) -> PartKey.of(Distributor.valueOf(rs.getString(1)), rs.getString(2))).list();
                List<String> actualTop = index.query(query, step, limit).stream()
                        .map(h -> PartKey.of(h.distributor(), h.partNumber())).toList();
                if (!actualTop.equals(expectedTop)) {
                    violations.add(text + " step " + step.index() + ": the first " + limit + " rows differ");
                }
            }
            // the stated-only form is exactly the first tier of the superset form: the rows that state every
            // requested column (review A9: one renderer for both forms)
            Set<String> stated = sqlite(SqliteFieldSql.CONFIRMED, query, step, false);
            if (!stated.equals(sqlite(SqliteFieldSql.INSTANCE, query, step, true)) || !lite.containsAll(stated)) {
                violations.add(text + " step " + step.index() + ": the stated-only rows are not the confirmed ones");
            }
            pgRelaxed = pg;
            liteRelaxed = lite;
        }
        int step0 = postgres(query, query.step(0)).size();
        long gapsReturned = returnable.stream().filter(gapParts::contains).count();
        String line = String.format("%-52s %6d %6d %6d %6d %6d %6d", abbreviate(text), POOL.size(),
                returnableStrict, pgRelaxed == null ? -1 : pgRelaxed.size(),
                liteRelaxed == null ? -1 : liteRelaxed.size(), step0, gapsReturned);
        return new Result(line, violations, gapsReturned);
    }

    @Test
    void partsWithoutIndexedAttributesStayReachable() {
        // a blank LCSC description: no value, no package, no family words; every rule keeps its NULL columns
        for (String key : gapParts()) {
            Part part = ENRICHED.get(key);
            ParsedQuery parsed = PARSER.parse("10uF 0805 25V X7R");
            if (!PageCollector.Check.returnable(ranking, parsed, part, false)) {
                continue;
            }
            FieldQuery query = FieldQueryBuilder.build(parsed, ConstraintPolicy.DEFAULTS, part.distributor());
            assertThat(postgres(query, query.relaxed())).as(key).contains(key);
            assertThat(sqlite(query, query.relaxed())).as(key).contains(key);
        }
    }

    /**
     * True when the part states every keyword of the request the way the ranker's lexical score finds it
     * ({@code features.text()} contains it) and, for a request that names part numbers, its MPN starts with one.
     */
    private static boolean statesText(ParsedQuery parsed, Part part) {
        String text = EXTRACTOR.features(part).text();
        if (!parsed.keywords().stream().allMatch(text::contains)) {
            return false;
        }
        if (parsed.partNumbers().isEmpty()) {
            return true;
        }
        String mpn = FieldVocabulary.normalize(part.manufacturerPartNumber());
        return parsed.partNumbers().stream().map(FieldVocabulary::normalize).anyMatch(mpn::startsWith);
    }

    /** True when no ladder kind of {@code ladder} is a known mismatch of the part. */
    private static boolean meetsLadder(ParsedQuery parsed, Part part, List<ConstraintKind> ladder) {
        if (ladder.isEmpty()) {
            return true;
        }
        SearchMatchContext context = new SearchMatchContext(parsed, EXTRACTOR.features(part));
        for (ConstraintKind kind : ladder) {
            Double g = kind.compare(context);
            if (g != null && g < 0) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> postgres(FieldQuery query, FieldQuery.Step step) {
        Set<String> out = new HashSet<>();
        index.query(query, step, POOL.size() + 1).forEach(h -> out.add(PartKey.of(h.distributor(), h.partNumber())));
        return out;
    }

    private static Set<String> sqlite(FieldQuery query, FieldQuery.Step step) {
        return sqlite(SqliteFieldSql.INSTANCE, query, step, false);
    }

    /** The rows of one step in {@code dialect}; with {@code confirmedOnly} only those its order puts first. */
    private static Set<String> sqlite(SqliteFieldSql dialect, FieldQuery query, FieldQuery.Step step,
                                      boolean confirmedOnly) {
        FieldSql.Statement statement = dialect.select(query, step, POOL.size() + 1);
        Set<String> out = new HashSet<>();
        synchronized (FieldQuerySupersetTest.class) {
            try {
                read(statement, out, confirmedOnly);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }

    private static void read(FieldSql.Statement statement, Set<String> out, boolean confirmedOnly)
            throws SQLException {
        try (PreparedStatement ps = sqlite.prepareStatement(statement.sql())) {
            for (int i = 0; i < statement.params().size(); i++) {
                ps.setObject(i + 1, statement.params().get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (!confirmedOnly || rs.getInt(3) == 1) {
                        out.add(PartKey.of(Distributor.valueOf(rs.getString(1)), rs.getString(2)));
                    }
                }
            }
        }
    }

    /** Pool parts with a blank LCSC description or a Mouser part without a package (study 12.3, risk R3). */
    private static Set<String> gapParts() {
        Set<String> out = new TreeSet<>();
        POOL.forEach((key, part) -> {
            boolean blank = part.distributor() == Distributor.LCSC
                    && (part.description() == null || part.description().isBlank());
            boolean noPackage = part.distributor() == Distributor.MOUSER
                    && EXTRACTOR.features(ENRICHED.get(key)).packageName() == null;
            if (blank || noPackage) {
                out.add(key);
            }
        });
        return out;
    }

    private static void load() throws IOException, URISyntaxException {
        for (String line : Files.readAllLines(DATASET, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = MAPPER.readTree(line);
            QUERIES.add(node.get("query").asString());
            for (JsonNode c : node.get("candidates")) {
                Part part = MAPPER.treeToValue(c.get("part"), Part.class);
                POOL.putIfAbsent(PartKey.of(part), part);
            }
        }
        for (String dir : FIXTURE_DIRS) {
            URL url = FieldQuerySupersetTest.class.getResource("/fixtures/" + dir);
            assertThat(url).as(dir).isNotNull();
            try (Stream<Path> files = Files.list(Path.of(url.toURI()))) {
                for (Path file : files.sorted().toList()) {
                    JsonNode root;
                    try (InputStream in = Files.newInputStream(file)) {
                        root = MAPPER.readTree(in);
                    }
                    if (root.get("query") != null) {
                        QUERIES.add(root.get("query").asString());
                    }
                    JsonNode parts = root.get("parts");
                    List<JsonNode> nodes = new ArrayList<>();
                    if (parts != null && parts.isArray()) {
                        parts.forEach(nodes::add);
                    } else if (parts != null) {
                        parts.properties().forEach(e -> e.getValue().forEach(nodes::add));
                    }
                    for (JsonNode n : nodes) {
                        Part part = MAPPER.treeToValue(n, Part.class);
                        if (part.distributor() != null && part.distributorPartNumber() != null) {
                            POOL.putIfAbsent(PartKey.of(part), part);
                        }
                    }
                }
            }
        }
        QUERIES.addAll(EXTRA);
        String pool = System.getProperty("kina.superset.pool");
        if (pool != null && !pool.isBlank()) {
            for (String line : Files.readAllLines(Path.of(pool), StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    Part part = MAPPER.readValue(line, Part.class);
                    POOL.putIfAbsent(PartKey.of(part), part);
                }
            }
        }
        String queries = System.getProperty("kina.superset.queries");
        if (queries != null && !queries.isBlank()) {
            Files.readAllLines(Path.of(queries), StandardCharsets.UTF_8).stream().map(String::strip)
                    .filter(q -> !q.isEmpty() && !QUERIES.contains(q)).forEach(QUERIES::add);
        }
    }

    private static String abbreviate(String s) {
        return s.length() <= 52 ? s : s.substring(0, 49) + "...";
    }
}
