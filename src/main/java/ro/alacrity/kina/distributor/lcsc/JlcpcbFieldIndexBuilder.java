package ro.alacrity.kina.distributor.lcsc;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.sqlite.SQLiteConfig;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.search.ParametricExtractor;
import ro.alacrity.kina.search.PartIndexRows;
import ro.alacrity.kina.search.field.PartIndexRow;
import ro.alacrity.kina.search.field.SqlitePartIndex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds the typed table of the in-stock JLCPCB rows into the sidecar file (DESIGN.md 9.3, field search phase B). The
 * main file is only read. The in-stock rows are streamed in batches, mapped with {@link LcscPartMapper}, enriched and
 * turned into {@code part_index} rows by {@link PartIndexRows} on {@code kina.jlcpcb.field-index.threads} threads, and
 * inserted in order by the calling thread (reading and inserting overlap with the extraction). Then the indexes and
 * {@code ANALYZE}, {@code kina_meta} (version, row count, build time, fingerprint of the main file), and
 * {@link #warm} reads the hot parts of both files so the page cache is warm before the files are renamed into place.
 */
@Component
@Slf4j
public class JlcpcbFieldIndexBuilder {

    /** Rows read, extracted and inserted per batch. */
    static final int BATCH = 4096;

    @Autowired private KinaProperties properties;
    @Autowired private ParametricExtractor extractor;

    /**
     * What a build produced.
     *
     * @param rows       rows in the typed table
     * @param skipped    in-stock rows left out (no LCSC number, or extraction failed)
     * @param extraction time of the scan, extraction and inserts
     * @param indexes    time of the indexes and {@code ANALYZE}
     * @param sizeBytes  size of the sidecar
     */
    public record Built(long rows, long skipped, Duration extraction, Duration indexes, long sizeBytes) {
    }

    private record Item(long rowid, JlcpcbRow row) {
    }

    private record Mapped(List<PartIndexRow> rows, List<Long> rowids, List<Integer> stocks, int skipped) {
    }

    /**
     * Builds {@code target} from {@code main} (any previous {@code target} is replaced), then warms both files.
     *
     * @throws IOException when a file cannot be read or written
     * @throws InterruptedException when the calling thread is interrupted (the partial target is deleted)
     */
    public Built build(Path main, Path target) throws IOException, InterruptedException {
        int threads = properties.jlcpcb().fieldIndex().effectiveThreads();
        Files.deleteIfExists(target);
        boolean success = false;
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "jlcpcb-index");
            t.setDaemon(true);
            return t;
        });
        try (Connection source = JlcpcbSqliteSearch.openReadOnly(main); Connection out = openWritable(target)) {
            String fingerprint = FieldIndexFile.fingerprint(main, source);
            long started = System.nanoTime();
            out.setAutoCommit(false);
            SqlitePartIndex.createTable(out, FieldIndexFile.TABLE);
            AtomicLong skipped = new AtomicLong();
            long rows = fill(source, out, pool, threads, skipped);
            out.commit();
            Duration extraction = Duration.ofNanos(System.nanoTime() - started);
            log.info("Typed table: {} in-stock rows extracted and inserted in {} ({} threads, {} skipped)", rows,
                    extraction, threads, skipped.get());

            started = System.nanoTime();
            SqlitePartIndex.createIndexes(out, FieldIndexFile.TABLE);
            try (Statement st = out.createStatement()) {
                st.execute("ANALYZE");
                st.execute("CREATE TABLE " + FieldIndexFile.META + " (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            }
            writeMeta(out, rows, fingerprint);
            out.commit();
            Duration indexes = Duration.ofNanos(System.nanoTime() - started);
            log.info("Typed table: indexes and ANALYZE in {}", indexes);
            success = true;
            return new Built(rows, skipped.get(), extraction, indexes, 0);
        } catch (SQLException e) {
            throw new IOException("Building the typed table of " + main + " failed: " + e.getMessage(), e);
        } finally {
            pool.shutdownNow();
            if (!success) {
                Files.deleteIfExists(target);
            }
        }
    }

    /** {@link #build} plus {@link #warm}; the size of the sidecar is set. */
    public Built buildAndWarm(Path main, Path target) throws IOException, InterruptedException {
        Built built = build(main, target);
        warm(main, target);
        return new Built(built.rows(), built.skipped(), built.extraction(), built.indexes(), Files.size(target));
    }

    /** Marks the end of the batches for the writer. */
    private static final Future<Mapped> END = java.util.concurrent.CompletableFuture.completedFuture(null);

    /**
     * Reads the in-stock rows in batches (this thread), maps them on {@code pool} and inserts the mapped batches in
     * order on a writer thread, so reading, extraction and inserting overlap.
     */
    private long fill(Connection source, Connection out, ExecutorService pool, int threads, AtomicLong skipped)
            throws SQLException, InterruptedException, IOException {
        String select = "SELECT rowid, " + JlcpcbSqliteSearch.COLUMNS + " FROM parts WHERE CAST(\"Stock\" AS INTEGER) > 0";
        BlockingQueue<Future<Mapped>> queue = new ArrayBlockingQueue<>(threads * 3);
        ExecutorService writerPool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "jlcpcb-index-writer");
            t.setDaemon(true);
            return t;
        });
        Future<Long> writer = writerPool.submit(() -> write(out, queue, skipped));
        try (Statement st = source.createStatement(); ResultSet rs = st.executeQuery(select)) {
            List<Item> batch = new ArrayList<>(BATCH);
            while (rs.next()) {
                batch.add(new Item(rs.getLong(1), JlcpcbSqliteSearch.row(rs, 1)));
                if (batch.size() >= BATCH) {
                    List<Item> work = batch;
                    batch = new ArrayList<>(BATCH);
                    enqueue(queue, pool.submit(() -> map(work)), writer);
                }
            }
            if (!batch.isEmpty()) {
                List<Item> work = batch;
                enqueue(queue, pool.submit(() -> map(work)), writer);
            }
            enqueue(queue, END, writer);
            return writer.get();
        } catch (ExecutionException e) {
            throw new IOException("typed table insert failed: " + e.getCause(), e.getCause());
        } finally {
            writer.cancel(true);
            writerPool.shutdownNow();
        }
    }

    private static void enqueue(BlockingQueue<Future<Mapped>> queue, Future<Mapped> item, Future<Long> writer)
            throws InterruptedException {
        while (!queue.offer(item, 1, TimeUnit.SECONDS)) {
            if (writer.isDone()) {
                return;   // the writer failed; its exception surfaces from writer.get()
            }
        }
    }

    private long write(Connection out, BlockingQueue<Future<Mapped>> queue, AtomicLong skipped)
            throws InterruptedException, IOException, SQLException {
        long rows = 0;
        long sinceCommit = 0;
        while (true) {
            Future<Mapped> next = queue.take();
            if (next == END) {
                return rows;
            }
            Mapped mapped = take(next);
            SqlitePartIndex.insert(out, FieldIndexFile.TABLE, mapped.rows(), mapped.rowids(), mapped.stocks());
            skipped.addAndGet(mapped.skipped());
            rows += mapped.rows().size();
            sinceCommit += mapped.rows().size();
            if (sinceCommit >= 100_000) {
                out.commit();
                sinceCommit = 0;
            }
        }
    }

    private static Mapped take(Future<Mapped> future) throws InterruptedException, IOException {
        try {
            return future.get();
        } catch (ExecutionException e) {
            throw new IOException("extraction failed: " + e.getCause(), e.getCause());
        }
    }

    private Mapped map(List<Item> items) {
        List<PartIndexRow> rows = new ArrayList<>(items.size());
        List<Long> rowids = new ArrayList<>(items.size());
        List<Integer> stocks = new ArrayList<>(items.size());
        int skipped = 0;
        Instant now = Instant.now();
        for (Item item : items) {
            try {
                Part part = LcscPartMapper.map(item.row(), now).orElse(null);
                if (part == null) {
                    skipped++;
                    continue;
                }
                rows.add(PartIndexRows.of(extractor, part, true));
                rowids.add(item.rowid());
                stocks.add(part.stock());
            } catch (RuntimeException e) {
                skipped++;
                log.debug("Typed table: {} not indexed: {}", item.row().lcscPart(), e.toString());
            }
        }
        return new Mapped(rows, rowids, stocks, skipped);
    }

    private static void writeMeta(Connection out, long rows, String fingerprint) throws SQLException {
        try (PreparedStatement ps = out.prepareStatement("INSERT INTO " + FieldIndexFile.META + " VALUES (?, ?)")) {
            String[][] entries = {{"index_version", Integer.toString(ParametricExtractor.INDEX_VERSION)},
                    {"rows", Long.toString(rows)}, {"built_at", Instant.now().toString()}, {"source", fingerprint}};
            for (String[] e : entries) {
                ps.setString(1, e[0]);
                ps.setString(2, e[1]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** A read-write connection for the build: no journal and no syncs, the file is rebuilt if the build is cut short. */
    private static Connection openWritable(Path file) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.OFF);
        config.setSynchronous(SQLiteConfig.SynchronousMode.OFF);
        config.setCacheSize(-262_144);
        config.setTempStore(SQLiteConfig.TempStore.FILE);
        return config.createConnection("jdbc:sqlite:" + file.toAbsolutePath());
    }

    /**
     * Reads the hot parts of both files so the page cache is warm before they are renamed into place: the typed
     * table, its indexes of the biggest families and a few FTS5 matches (the first search after a swap otherwise reads
     * the cold file, study 12.2). Failures are logged and ignored.
     */
    public void warm(Path main, Path sidecar) throws InterruptedException {
        long started = System.nanoTime();
        try (Connection c = JlcpcbSqliteSearch.openPooled(main)) {
            FieldIndexFile.attach(c, sidecar);
            List<String> families = new ArrayList<>();
            try (Statement st = c.createStatement()) {
                query(st, "SELECT count(*) FROM " + FieldIndexFile.SCHEMA + "." + FieldIndexFile.TABLE);
                try (ResultSet rs = st.executeQuery("SELECT family FROM " + FieldIndexFile.TABLE
                        + " WHERE family IS NOT NULL GROUP BY family ORDER BY count(*) DESC LIMIT 8")) {
                    while (rs.next()) {
                        families.add(rs.getString(1));
                    }
                }
                for (String family : families) {
                    try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM " + FieldIndexFile.TABLE
                            + " WHERE family = ? AND package_key IS NOT NULL")) {
                        ps.setString(1, family);
                        ps.executeQuery().close();
                    }
                }
                for (String match : List.of("\"x7r\" AND \"0805\"", "\"resistor\" AND \"0603\"", "\"header\"",
                        "\"type-c\"", "\"ohm\"")) {
                    try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM parts WHERE parts MATCH ?")) {
                        ps.setString(1, match);
                        ps.executeQuery().close();
                    }
                }
                query(st, "SELECT count(*) FROM parts WHERE rowid IN (SELECT fts_rowid FROM " + FieldIndexFile.TABLE
                        + " ORDER BY stock DESC LIMIT 2000)");
            }
            log.info("Typed table: page cache warmed in {} ms", (System.nanoTime() - started) / 1_000_000);
        } catch (SQLException e) {
            log.warn("Warming the typed table failed: {}", e.getMessage());
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("warm-up interrupted");
        }
    }

    private static void query(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
        }
    }
}
