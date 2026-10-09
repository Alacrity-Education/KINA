package ro.alacrity.kina.search.field;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ro.alacrity.kina.cache.CacheWriteListener;
import ro.alacrity.kina.cache.PartMetadataHash;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.search.ParametricExtractor;
import ro.alacrity.kina.search.PartIndexRows;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code part_index} (migration V14, DESIGN.md 3.8): one row per {@code cached_parts} row with the features the Java
 * check reads. Written in the transaction of every cache write ({@link CacheWriteListener}, under a savepoint so an
 * index failure never fails the cache write), kept in step with stock changes ({@link #markStock}), rebuilt by the
 * re-index job and its hourly sweep ({@link #reindexStale}) and read by the field query ({@link #query}).
 *
 * <p>A row is <b>current</b> when it was written by the running extractor ({@link ParametricExtractor#INDEX_VERSION})
 * from the metadata {@code cached_parts} holds now ({@code metadata_md5} equal on both sides, {@link PartMetadataHash}:
 * stock, prices and timestamps are not part of it) with the same {@code in_stock}. A distributor is <b>complete</b>
 * ({@link #isComplete}) when every one of its {@code cached_parts} rows has a current index row; until then a caller
 * must not answer from the index and keeps the cached-search path, so no cached part becomes unreachable.
 */
@Slf4j
@Repository
public class PartIndexRepository implements CacheWriteListener {

    /** How long a computed coverage is reused by {@link #isComplete}. */
    static final long COVERAGE_TTL_MILLIS = 30_000;

    /**
     * The coverage per distributor: compares the stored hashes, version and stock flag only (V16's covering indexes
     * {@code cached_parts_index_state_idx} and {@code part_index_state_idx}), never a payload.
     */
    static final String COVERAGE_SQL = """
            SELECT c.distributor, count(*) AS cached, count(i.part_number) AS indexed,
                   count(i.part_number) FILTER (WHERE i.extractor_version >= ? AND i.in_stock = c.in_stock
                       AND i.metadata_md5 = c.metadata_md5) AS current
            FROM cached_parts c
            LEFT JOIN part_index i ON i.distributor = c.distributor AND i.part_number = c.part_number
            GROUP BY c.distributor ORDER BY c.distributor""";

    @Autowired private JdbcClient jdbc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ParametricExtractor extractor;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private Clock clock;
    @Autowired(required = false) private PlatformTransactionManager transactionManager;

    private final AtomicReference<CoverageSnapshot> coverage = new AtomicReference<>();

    /** One hit of a field query, in the query's order. */
    public record Hit(Distributor distributor, String partNumber, boolean confirmed) {
    }

    /**
     * The index of one distributor against its cache.
     *
     * @param cachedParts {@code cached_parts} rows, in stock or not
     * @param indexed     {@code part_index} rows
     * @param current     index rows that are current (version, payload and stock flag)
     */
    public record Coverage(Distributor distributor, long cachedParts, long indexed, long current) {

        /** Every cached part has a current index row. */
        public boolean complete() {
            return current >= cachedParts;
        }

        /** Cached parts without a current index row. */
        public long stale() {
            return Math.max(0, cachedParts - current);
        }
    }

    private record CoverageSnapshot(Map<Distributor, Coverage> byDistributor, long atMillis) {
    }

    /** What one re-index pass wrote, per distributor; {@code unreadable} payloads got a placeholder row. */
    public record ReindexReport(Map<Distributor, Long> written, long unreadable, long millis) {

        public long total() {
            return written.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    // ---------------------------------------------------------------- writes

    @Override
    public Runnable upserting(Collection<Part> parts, boolean inStock) {
        List<PartIndexRow> rows = rows(parts, inStock);
        return rows.isEmpty() ? NOTHING : () -> writeIsolated(rows);
    }

    @Override
    public void stockChanged(Distributor distributor, Collection<String> partNumbers, boolean inStock) {
        markStock(distributor, partNumbers, inStock);
    }

    /** Writes the index rows of {@code parts} (stock flag: ships-now stock) in a transaction of their own. */
    public void upsertFrom(Collection<Part> parts) {
        List<PartIndexRow> rows = new ArrayList<>();
        rows.addAll(rows(parts.stream().filter(p -> p.stock() > 0).toList(), true));
        rows.addAll(rows(parts.stream().filter(p -> p.stock() <= 0).toList(), false));
        write(rows);
    }

    /**
     * Copies a stock change's {@code in_stock} to the index rows (under a savepoint of the caller's transaction, if
     * any); never fails (logged).
     */
    public void markStock(Distributor distributor, Collection<String> partNumbers, boolean inStock) {
        if (partNumbers == null || partNumbers.isEmpty()) {
            return;
        }
        try {
            isolated(() -> jdbc.sql("""
                            UPDATE part_index SET in_stock = ?
                            WHERE distributor = ? AND part_number = ANY(?) AND in_stock <> ?""")
                    .params(inStock, distributor.name(), partNumbers.toArray(String[]::new), inStock)
                    .update());
        } catch (RuntimeException e) {
            log.warn("Marking {} {} index rows {} failed: {}", partNumbers.size(), distributor,
                    inStock ? "in stock" : "sold out", e.toString());
        }
    }

    /**
     * The rows of {@code parts}, each with the metadata hash of the part it was built from; a part the extractor fails
     * on is skipped (logged; the re-index sweep retries it).
     */
    List<PartIndexRow> rows(Collection<Part> parts, boolean inStock) {
        List<PartIndexRow> rows = new ArrayList<>(parts.size());
        for (Part part : parts) {
            try {
                rows.add(PartIndexRows.of(extractor, part, inStock).toBuilder()
                        .metadataMd5(PartMetadataHash.of(jsonMapper, part)).build());
            } catch (RuntimeException e) {
                log.warn("Indexing {} failed: {}", part.key(), e.toString());
            }
        }
        return rows;
    }

    /** Writes {@code rows} under a savepoint of the caller's transaction; a failure is logged, never thrown. */
    void writeIsolated(List<PartIndexRow> rows) {
        try {
            isolated(() -> write(rows));
        } catch (RuntimeException e) {
            log.warn("Writing {} index rows failed (the re-index sweep retries them): {}", rows.size(),
                    e.toString());
        }
    }

    /** Runs {@code body} under a savepoint of the caller's transaction (on its own without one). */
    private void isolated(Runnable body) {
        if (transactionManager == null) {
            body.run();
            return;
        }
        TransactionTemplate nested = new TransactionTemplate(transactionManager);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        nested.executeWithoutResult(status -> body.run());
    }

    /**
     * Inserts or replaces {@code rows}, sorted by key; a row whose part is not in {@code cached_parts} is skipped, and
     * an unchanged row is not rewritten ({@code IS DISTINCT FROM}). The coverage snapshot is kept: a row written with
     * its part's metadata hash in the cache write's transaction is current by construction.
     */
    public void write(List<PartIndexRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<PartIndexRow> sorted = new ArrayList<>(rows);
        sorted.sort((a, b) -> a.distributor() != b.distributor() ? a.distributor().name()
                .compareTo(b.distributor().name()) : a.partNumber().compareTo(b.partNumber()));
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        List<Object[]> batch = new ArrayList<>(sorted.size());
        for (PartIndexRow row : sorted) {
            batch.add(PartIndexSql.params(row, now, jsonMapper));
        }
        for (int from = 0; from < batch.size(); from += 500) {
            jdbcTemplate.batchUpdate(PartIndexSql.UPSERT, batch.subList(from, Math.min(batch.size(), from + 500)));
        }
    }

    /**
     * Copies {@code cached_parts.metadata_md5} to the index rows that have none (written before V16) and were built
     * from the payload the cache holds now ({@code payload_md5}): their features are those of that payload, so no
     * extraction is needed. Rows built from an older payload stay without a hash and are re-indexed. Returns the rows
     * filled.
     */
    public long backfillMetadataHashes() {
        long filled = jdbc.sql("""
                        UPDATE part_index i SET metadata_md5 = c.metadata_md5
                        FROM cached_parts c
                        WHERE c.distributor = i.distributor AND c.part_number = i.part_number
                          AND i.metadata_md5 IS NULL AND c.metadata_md5 IS NOT NULL
                          AND i.payload_md5 = md5(c.payload::text)""")
                .update();
        if (filled > 0) {
            coverage.set(null);
        }
        return filled;
    }

    // ---------------------------------------------------------------- re-index

    /**
     * Re-indexes every {@code cached_parts} row whose index row is missing or not current at {@code version}, in key
     * order, {@code batchSize} rows at a time: in-stock and sold-out rows alike (the index copies {@code in_stock}).
     * A payload that cannot be read gets a placeholder row (no attributes): it counts as covered, and the Java check
     * never returns it (the cache path skips an unreadable payload).
     */
    public ReindexReport reindexStale(int version, int batchSize) {
        long start = System.nanoTime();
        int size = Math.max(1, batchSize);
        Map<Distributor, Long> written = new EnumMap<>(Distributor.class);
        long unreadable = 0;
        String lastDistributor = "";
        String lastPartNumber = "";
        while (true) {
            record Stale(String distributor, String partNumber, String payload, boolean inStock) {
            }
            List<Stale> batch = jdbc.sql("""
                            SELECT c.distributor, c.part_number, c.payload::text AS payload, c.in_stock
                            FROM cached_parts c
                            LEFT JOIN part_index i ON i.distributor = c.distributor AND i.part_number = c.part_number
                            WHERE (c.distributor, c.part_number) > (?, ?)
                              AND (i.part_number IS NULL OR i.extractor_version < ? OR i.in_stock <> c.in_stock
                                   OR c.metadata_md5 IS NULL OR i.metadata_md5 IS DISTINCT FROM c.metadata_md5)
                            ORDER BY c.distributor, c.part_number LIMIT ?""")
                    .params(lastDistributor, lastPartNumber, version, size)
                    .query((rs, n) -> new Stale(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)))
                    .list();
            if (batch.isEmpty()) {
                break;
            }
            List<PartIndexRow> rows = new ArrayList<>(batch.size());
            for (Stale s : batch) {
                Distributor distributor;
                try {
                    distributor = Distributor.valueOf(s.distributor());
                } catch (IllegalArgumentException e) {
                    continue;
                }
                PartIndexRow row = null;
                try {
                    Part part = jsonMapper.readValue(s.payload(), Part.class);
                    if (distributor == part.distributor() && s.partNumber().equals(part.distributorPartNumber())) {
                        row = PartIndexRows.of(extractor, part, s.inStock());
                    }
                } catch (RuntimeException e) {
                    log.debug("Unreadable cached_parts payload {}:{}: {}", s.distributor(), s.partNumber(),
                            e.toString());
                }
                if (row == null) {
                    unreadable++;
                    row = PartIndexRow.builder().distributor(distributor).partNumber(s.partNumber())
                            .extractorVersion(ParametricExtractor.INDEX_VERSION).inStock(s.inStock()).build();
                }
                // the hash of the payload the row was built from: if the cache row changes after this read, its hash
                // differs and the row stays stale until the next pass (review A, finding 3)
                rows.add(row.toBuilder().metadataMd5(PartMetadataHash.ofPayload(s.payload())).build());
                written.merge(distributor, 1L, Long::sum);
            }
            write(rows);
            Stale last = batch.getLast();
            lastDistributor = last.distributor();
            lastPartNumber = last.partNumber();
            if (batch.size() < size) {
                break;
            }
        }
        coverage.set(null);
        return new ReindexReport(written, unreadable, (System.nanoTime() - start) / 1_000_000);
    }

    // ---------------------------------------------------------------- reads

    /** {@code part_index} rows per distributor (every distributor present, 0 when none). */
    public Map<Distributor, Long> countByDistributor() {
        Map<Distributor, Long> out = new EnumMap<>(Distributor.class);
        for (Distributor d : Distributor.values()) {
            out.put(d, 0L);
        }
        jdbc.sql("SELECT distributor, count(*) FROM part_index GROUP BY distributor")
                .query(rs -> {
                    try {
                        out.put(Distributor.valueOf(rs.getString(1)), rs.getLong(2));
                    } catch (IllegalArgumentException e) {
                        // a row of an unknown distributor is ignored
                    }
                });
        return out;
    }

    /** The index against the cache, per distributor with cached parts (computed now). */
    public Map<Distributor, Coverage> coverage() {
        Map<Distributor, Coverage> out = new LinkedHashMap<>();
        jdbc.sql(COVERAGE_SQL)
                .param(ParametricExtractor.INDEX_VERSION)
                .query(rs -> {
                    try {
                        Distributor d = Distributor.valueOf(rs.getString("distributor"));
                        out.put(d, new Coverage(d, rs.getLong("cached"), rs.getLong("indexed"),
                                rs.getLong("current")));
                    } catch (IllegalArgumentException e) {
                        // ignored
                    }
                });
        coverage.set(new CoverageSnapshot(Map.copyOf(out), clock.millis()));
        return out;
    }

    /**
     * True when every cached part of {@code distributor} has a current index row (computed at most every
     * {@value #COVERAGE_TTL_MILLIS} ms); false on a database failure. A distributor without cached parts is complete.
     */
    public boolean isComplete(Distributor distributor) {
        try {
            CoverageSnapshot s = coverage.get();
            Map<Distributor, Coverage> byDistributor = s != null && clock.millis() - s.atMillis() < COVERAGE_TTL_MILLIS
                    ? s.byDistributor() : coverage();
            Coverage c = byDistributor.get(distributor);
            return c == null || c.complete();
        } catch (RuntimeException e) {
            log.debug("Reading the field index coverage failed: {}", e.toString());
            return false;
        }
    }

    /**
     * Drops the coverage snapshot, so the next {@link #isComplete} computes it (after a change no cache write made:
     * the re-index, or rows removed by hand).
     */
    public void forgetCoverage() {
        coverage.set(null);
    }

    /** Which of {@code partNumbers} of {@code distributor} have an index row. */
    public Set<String> indexed(Distributor distributor, Collection<String> partNumbers) {
        if (partNumbers.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql(
                        "SELECT part_number FROM part_index WHERE distributor = ? AND part_number = ANY(?)")
                .params(distributor.name(), partNumbers.toArray(String[]::new)).query(String.class).list());
    }

    /** The hits of the unrelaxed step of {@code query}, at most {@code limit}. */
    public List<Hit> query(FieldQuery query, int limit) {
        return query(query, query.step(0), limit);
    }

    /** The hits of one step of {@code query}, at most {@code limit}, confirmed first, then by key. */
    public List<Hit> query(FieldQuery query, FieldQuery.Step step, int limit) {
        return query(query, step, limit, null);
    }

    /** The hits of one step of {@code query} among the part numbers {@code among} (null: all), at most {@code limit}. */
    public List<Hit> query(FieldQuery query, FieldQuery.Step step, int limit, List<String> among) {
        FieldSql.Statement statement = PostgresFieldSql.INSTANCE.select(query, step, limit, among);
        return jdbc.sql(statement.sql()).params(statement.params())
                .query((rs, n) -> new Hit(Distributor.valueOf(rs.getString(1)), rs.getString(2), rs.getBoolean(3)))
                .list();
    }
}
