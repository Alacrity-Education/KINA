package ro.alacrity.kina.distributor.lcsc;

import ro.alacrity.kina.TestWiring;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.search.ConstraintPolicy;
import ro.alacrity.kina.search.ParametricExtractor;
import ro.alacrity.kina.search.QueryParser;
import ro.alacrity.kina.search.field.FieldQuery;
import ro.alacrity.kina.search.field.FieldQueryBuilder;
import ro.alacrity.kina.search.field.FieldSql;
import ro.alacrity.kina.search.field.SqliteFieldSql;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Live check of the typed table against a real JLCPCB file (not a JUnit test; run it with {@code java -cp}, see
 * DESIGN.md 9.3): {@code build <main-file>} builds {@code <library>.index.db} next to it and prints the timings;
 * {@code query <main-file>} times the field queries (the first run, then the median of five) and prints their plans.
 */
public final class JlcpcbFieldIndexLive {

    private static final List<String> QUERIES = List.of("10uF X7R 0805 25V", "4.7k 1% 0603",
            "female header 1x6 right angle", "USB-C receptacle 16 pin");

    private JlcpcbFieldIndexLive() {
    }

    public static void main(String[] args) throws Exception {
        Path main = Path.of(args[1]).toAbsolutePath();
        KinaProperties properties = TestWiring.properties("kina.jlcpcb.data-dir", main.getParent().toString(),
                "kina.jlcpcb.library", main.getFileName().toString(), "kina.jlcpcb.field-index.enabled", "true");
        if (args[0].equals("build")) {
            JlcpcbFieldIndexBuilder builder = TestWiring.wire(new JlcpcbFieldIndexBuilder(), "properties", properties,
                    "extractor", new ParametricExtractor());
            Path target = FieldIndexFile.sidecar(main);
            long t0 = System.nanoTime();
            JlcpcbFieldIndexBuilder.Built built = builder.buildAndWarm(main, target);
            System.out.printf("built %s: %d rows, %d skipped, extraction %s, indexes %s, total %d s, %d MB%n", target,
                    built.rows(), built.skipped(), built.extraction(), built.indexes(),
                    (System.nanoTime() - t0) / 1_000_000_000L, built.sizeBytes() / (1024 * 1024));
            return;
        }
        if (args[0].equals("bench")) {
            bench(main, 20);
            return;
        }
        if (args[0].equals("smallbench")) {
            Path small = JlcpcbTestDatabase.create(java.nio.file.Files.createTempDirectory("kina-small").resolve("p.db"),
                    JlcpcbTestDatabase.withUsb());
            bench(small, 1000);
            return;
        }
        JlcpcbSqliteSearch search = TestWiring.wire(new JlcpcbSqliteSearch(), "properties", properties);
        LcscFieldSearch field = TestWiring.wire(new LcscFieldSearch(), "search", search);
        System.out.println("typed table available: " + field.available() + " " + search.fieldIndex());
        QueryParser parser = new QueryParser();
        for (String text : QUERIES) {
            ParsedQuery parsed = parser.parse(text);
            FieldQuery query = FieldQueryBuilder.build(parsed, ConstraintPolicy.DEFAULTS, Distributor.LCSC, false);
            FieldQuery.Step step = query.step(0);
            List<Long> times = new ArrayList<>();
            int total = 0;
            int parts = 0;
            for (int i = 0; i < 6; i++) {
                long t0 = System.nanoTime();
                LcscFieldSearch.Candidates c = field.candidates(query, step, 40, Duration.ofSeconds(30));
                times.add((System.nanoTime() - t0) / 1000);
                total = c.total();
                parts = c.parts().size();
            }
            List<Long> warm = new ArrayList<>(times.subList(1, 6));
            Collections.sort(warm);
            System.out.printf("%n%s%n  field step 0: total=%d parts=%d first=%.1f ms warm median=%.1f ms%n", text, total,
                    parts, times.get(0) / 1000.0, warm.get(2) / 1000.0);
            plan(search, "confirmed count", SqliteFieldSql.CONFIRMED.count(query, step));
            plan(search, "confirmed candidates", SqliteFieldSql.CONFIRMED.candidates(query, step, 40));
            long t0 = System.nanoTime();
            JlcpcbSqliteSearch.Result fts = search.search(text, 0, 40);
            long first = (System.nanoTime() - t0) / 1000;
            t0 = System.nanoTime();
            search.search(text, 0, 40);
            long second = (System.nanoTime() - t0) / 1000;
            System.out.printf("  FTS path: total=%d mode=%s first=%.1f ms second=%.1f ms%n", fts.total(), fts.mode(),
                    first / 1000.0, second / 1000.0);
            FieldSql.Statement count = SqliteFieldSql.INSTANCE.count(query, step);
            FieldSql.Statement select = SqliteFieldSql.INSTANCE.candidates(query, step, 40);
            System.out.println("  SQL: " + select.sql());
            plan(search, "count", count);
            plan(search, "candidates", select);
        }
        search.close();
    }

    /** 8 threads, 20 searches each over 8 different queries, for several pool sizes (study 9.4: 20/s with one connection). */
    private static void bench(Path main, int perThread) throws Exception {
        List<String> queries = List.of("10uF X7R 0805", "4.7k 0603", "female header 1x6", "100nF 0402 capacitor",
                "10k ohm 1% 0603", "usb type-c connector", "schottky diode SMA 40V 1A", "N-channel MOSFET SOT-23 30V");
        for (int poolSize : new int[] {1, 2, 4, 8}) {
            KinaProperties properties = TestWiring.properties("kina.jlcpcb.data-dir", main.getParent().toString(),
                    "kina.jlcpcb.library", main.getFileName().toString(), "kina.jlcpcb.field-index.enabled", "true",
                    "kina.jlcpcb.pool-size", Integer.toString(poolSize), "kina.jlcpcb.pool-wait", "60s");
            JlcpcbSqliteSearch search = TestWiring.wire(new JlcpcbSqliteSearch(), "properties", properties);
            LcscFieldSearch field = TestWiring.wire(new LcscFieldSearch(), "search", search);
            QueryParser parser = new QueryParser();
            for (String queryKind : field.available() ? List.of("fts", "typed") : List.of("fts")) {
                for (String q : queries) {   // warm up
                    run(search, field, parser, queryKind, q);
                }
                int runs = 8 * perThread;
                long t0 = System.nanoTime();
                java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
                List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < 8; t++) {
                    int offset = t;
                    futures.add(pool.submit(() -> {
                        for (int i = 0; i < perThread; i++) {
                            run(search, field, parser, queryKind, queries.get((i + offset) % queries.size()));
                        }
                        return null;
                    }));
                }
                for (java.util.concurrent.Future<?> f : futures) {
                    f.get();
                }
                pool.shutdown();
                double seconds = (System.nanoTime() - t0) / 1e9;
                System.out.printf("pool-size %d, %-5s: %d searches in %.2f s = %.1f searches/s%n", poolSize, queryKind,
                        runs, seconds, runs / seconds);
            }
            search.close();
        }
    }

    private static void run(JlcpcbSqliteSearch search, LcscFieldSearch field, QueryParser parser, String kind,
                            String text) throws Exception {
        if (kind.equals("fts")) {
            search.search(text, 0, 40);
        } else {
            FieldQuery query = FieldQueryBuilder.build(parser.parse(text), ConstraintPolicy.DEFAULTS, Distributor.LCSC,
                    false);
            field.candidates(query, query.step(0), 40, Duration.ofSeconds(60));
        }
    }

    private static void plan(JlcpcbSqliteSearch search, String label, FieldSql.Statement statement) throws Exception {
        search.withConnection(null, c -> {
            try (PreparedStatement ps = c.prepareStatement("EXPLAIN QUERY PLAN " + statement.sql())) {
                for (int i = 0; i < statement.params().size(); i++) {
                    ps.setObject(i + 1, statement.params().get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        System.out.println("  plan " + label + ": " + rs.getString(4));
                    }
                }
            }
            return null;
        });
    }
}
