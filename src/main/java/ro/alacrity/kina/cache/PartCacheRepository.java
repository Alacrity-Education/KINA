package ro.alacrity.kina.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.metrics.KinaMetrics;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code cached_parts}: one JSONB payload (the default camelCase Jackson serialisation of {@link Part}) per
 * distributor part number. Rows whose payload cannot be deserialised are logged at WARN and skipped, never thrown.
 *
 * <p>Cache model (DESIGN.md 3.2): the metadata of a row (everything but stock, prices and availability) is kept for the
 * distributor's {@code kina.cache.metadata-retention} ({@code metadata_fetched_at}); its stock and prices carry their
 * own age ({@code stock_fetched_at}, equal to {@link Part#fetchedAt()}). A row a stock refresh found sold out keeps its
 * metadata with {@code in_stock = false} and is never served until a live fetch finds it in stock again; so does a part
 * an explicit part-number lookup found listed without stock ({@link #upsertListed}).
 *
 * <p>Every write also stores the part's {@code type} ({@link KinaMetrics#typeOfPart}, DESIGN.md 3.7); {@link #retype}
 * recomputes it for every row.
 */
@Repository
@Slf4j
public class PartCacheRepository {

    private static final String UPSERT = """
            INSERT INTO cached_parts (distributor, part_number, payload, stock_fetched_at, metadata_fetched_at, in_stock,
                                      type)
            VALUES (?, ?, ?::jsonb, ?, ?, true, ?)
            ON CONFLICT (distributor, part_number)
            DO UPDATE SET payload = EXCLUDED.payload, stock_fetched_at = EXCLUDED.stock_fetched_at,
              metadata_fetched_at = GREATEST(cached_parts.metadata_fetched_at, EXCLUDED.metadata_fetched_at),
              in_stock = true, type = EXCLUDED.type""";

    /** A part listed without ships-now stock: metadata kept, never served ({@code in_stock = false}). */
    private static final String UPSERT_LISTED = """
            INSERT INTO cached_parts (distributor, part_number, payload, stock_fetched_at, metadata_fetched_at, in_stock,
                                      type)
            VALUES (?, ?, ?::jsonb, ?, ?, false, ?)
            ON CONFLICT (distributor, part_number)
            DO UPDATE SET payload = EXCLUDED.payload, stock_fetched_at = EXCLUDED.stock_fetched_at,
              metadata_fetched_at = GREATEST(cached_parts.metadata_fetched_at, EXCLUDED.metadata_fetched_at),
              in_stock = false, type = EXCLUDED.type""";

    private static final String UPDATE_STOCK = """
            UPDATE cached_parts SET payload = ?::jsonb, stock_fetched_at = ?, in_stock = true
            WHERE distributor = ? AND part_number = ?""";

    private static final int BATCH_SIZE = 500;

    @Autowired private JdbcClient jdbc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private Clock clock;
    @Autowired private KinaProperties properties;
    @Autowired private KinaMetrics metrics = KinaMetrics.NOOP;
    /** The field index writer (DESIGN.md 3.8); none in tests that build the repository by hand. */
    @Autowired(required = false) private List<CacheWriteListener> listeners = List.of();
    @Autowired(required = false) private PlatformTransactionManager transactionManager;

    /**
     * Inserts or replaces every part fetched in full from the distributor (metadata, stock and prices) using JDBC
     * batches; parts with {@code stock <= 0} are skipped (and rows holding one are never returned by the finders), so
     * the cache can never serve a part without ships-now stock. {@code stock_fetched_at} and
     * {@code metadata_fetched_at} are the part's own {@link Part#fetchedAt()} (the clock's now when that is null), so
     * re-upserting a part that was loaded from the cache does not make it look fresher than it is; the metadata
     * timestamp never moves back. The new payload replaces the old one (the refetched metadata is current). Every
     * write stores {@link Part#asStored()}: the distributor's attributes, never the derived ones.
     */
    public void upsertAll(Collection<Part> parts) {
        if (parts == null || parts.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        List<Object[]> rows = new ArrayList<>(parts.size());
        List<Part> kept = new ArrayList<>(parts.size());
        Map<Distributor, Set<String>> written = new EnumMap<>(Distributor.class);
        for (Part part : parts) {
            Objects.requireNonNull(part.distributor(), "part.distributor");
            Objects.requireNonNull(part.distributorPartNumber(), "part.distributorPartNumber");
            if (part.stock() <= 0) {
                // stock rule (DESIGN.md 2): a part without ships-now stock is never cached
                log.warn("Not caching {}:{} without ships-now stock", part.distributor(), part.distributorPartNumber());
                continue;
            }
            Instant fetchedAt = part.fetchedAt() != null ? part.fetchedAt() : now;
            Part stored = part.asStored();
            rows.add(new Object[] {part.distributor().name(), part.distributorPartNumber(),
                    jsonMapper.writeValueAsString(stored), utc(fetchedAt), utc(fetchedAt),
                    KinaMetrics.typeOfPart(stored)});
            kept.add(part);
            written.computeIfAbsent(part.distributor(), d -> new LinkedHashSet<>()).add(part.distributorPartNumber());
        }
        Map<Distributor, Long> existing = countExisting(written);
        List<Runnable> work = prepare(kept, true);
        inTransaction(() -> {
            for (int from = 0; from < rows.size(); from += BATCH_SIZE) {
                jdbcTemplate.batchUpdate(UPSERT, rows.subList(from, Math.min(rows.size(), from + BATCH_SIZE)));
            }
            work.forEach(Runnable::run);
        });
        // DESIGN.md 3.7: kina_cache_parts_added_total / kina_cache_parts_refreshed_total
        written.forEach((d, numbers) -> {
            Long before = existing.get(d);
            if (before != null) {
                metrics.cachePartsWritten(d, numbers.size() - before, before);
            }
        });
    }

    /**
     * Writes the parts an explicit part-number lookup found listed without ships-now stock (stock 0; DESIGN.md 2, stock
     * rule): the metadata is kept like any fetched part, the row carries {@code in_stock = false} and the finders never
     * return it. A part with stock is not written here ({@link #upsertAll} is for those).
     */
    public void upsertListed(Collection<Part> parts) {
        if (parts == null || parts.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        List<Object[]> rows = new ArrayList<>(parts.size());
        List<Part> kept = new ArrayList<>(parts.size());
        for (Part part : parts) {
            Objects.requireNonNull(part.distributor(), "part.distributor");
            Objects.requireNonNull(part.distributorPartNumber(), "part.distributorPartNumber");
            if (part.stock() > 0) {
                continue;
            }
            kept.add(part);
            Instant fetchedAt = part.fetchedAt() != null ? part.fetchedAt() : now;
            Part stored = part.asStored();
            rows.add(new Object[] {part.distributor().name(), part.distributorPartNumber(),
                    jsonMapper.writeValueAsString(stored), utc(fetchedAt), utc(fetchedAt),
                    KinaMetrics.typeOfPart(stored)});
        }
        if (!rows.isEmpty()) {
            List<Runnable> work = prepare(kept, false);
            inTransaction(() -> {
                jdbcTemplate.batchUpdate(UPSERT_LISTED, rows);
                work.forEach(Runnable::run);
            });
        }
    }

    /**
     * Recomputes the {@code type} column of every row from its stored payload ({@link KinaMetrics#typeOfPart}; an
     * unreadable payload is {@code unknown}), reading {@code batchSize} rows at a time in key order and writing the
     * changed ones of each batch with one UPDATE. Returns the number of rows whose type changed.
     */
    public int retype(int batchSize) {
        int size = Math.max(1, batchSize);
        int changed = 0;
        String lastDistributor = "";
        String lastPartNumber = "";
        while (true) {
            List<String[]> batch = new ArrayList<>(size);
            jdbc.sql("""
                            SELECT distributor, part_number, payload::text AS payload, type FROM cached_parts
                            WHERE (distributor, part_number) > (?, ?)
                            ORDER BY distributor, part_number LIMIT ?""")
                    .params(lastDistributor, lastPartNumber, size)
                    .query(rs -> {
                        batch.add(new String[] {rs.getString("distributor"), rs.getString("part_number"),
                                rs.getString("payload"), rs.getString("type")});
                    });
            if (batch.isEmpty()) {
                return changed;
            }
            List<String> distributors = new ArrayList<>();
            List<String> partNumbers = new ArrayList<>();
            List<String> types = new ArrayList<>();
            for (String[] row : batch) {
                String type = typeOfPayload(row[2]);
                if (!type.equals(row[3])) {
                    distributors.add(row[0]);
                    partNumbers.add(row[1]);
                    types.add(type);
                }
            }
            if (!types.isEmpty()) {
                changed += jdbc.sql("""
                                UPDATE cached_parts c SET type = u.t
                                FROM unnest(?::text[], ?::text[], ?::text[]) AS u(d, p, t)
                                WHERE c.distributor = u.d AND c.part_number = u.p""")
                        .params(distributors.toArray(String[]::new), partNumbers.toArray(String[]::new),
                                types.toArray(String[]::new))
                        .update();
            }
            String[] last = batch.getLast();
            lastDistributor = last[0];
            lastPartNumber = last[1];
            if (batch.size() < size) {
                return changed;
            }
        }
    }

    private String typeOfPayload(String payload) {
        try {
            return KinaMetrics.typeOfPart(jsonMapper.readValue(payload, Part.class));
        } catch (RuntimeException e) { // JacksonException, or a Part invariant violated by the payload
            return KinaMetrics.UNKNOWN_TYPE;
        }
    }

    /** How many of {@code partNumbers} already have a row, per distributor; a distributor is missing on failure. */
    private Map<Distributor, Long> countExisting(Map<Distributor, Set<String>> partNumbers) {
        Map<Distributor, Long> out = new EnumMap<>(Distributor.class);
        partNumbers.forEach((d, numbers) -> {
            try {
                Long n = jdbc.sql("SELECT count(*) FROM cached_parts WHERE distributor = ? AND part_number = ANY(?)")
                        .params(d.name(), numbers.toArray(String[]::new))
                        .query(Long.class)
                        .single();
                out.put(d, n == null ? 0 : n);
            } catch (RuntimeException e) {
                log.debug("Counting existing cached {} parts failed: {}", d, e.toString());
            }
        });
        return out;
    }

    /**
     * Writes refreshed stock and prices (a {@code DistributorClient.refreshStock} result): the payload and
     * {@code stock_fetched_at} change, {@code metadata_fetched_at} does not. Rows that do not exist are not created.
     */
    public void updateStock(Collection<Part> parts) {
        if (parts == null || parts.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        List<Object[]> rows = new ArrayList<>(parts.size());
        Map<Distributor, List<String>> refreshed = new EnumMap<>(Distributor.class);
        for (Part part : parts) {
            if (part.stock() <= 0) {
                continue;
            }
            refreshed.computeIfAbsent(part.distributor(), d -> new ArrayList<>()).add(part.distributorPartNumber());
            rows.add(new Object[] {jsonMapper.writeValueAsString(part.asStored()),
                    utc(part.fetchedAt() != null ? part.fetchedAt() : now), part.distributor().name(),
                    part.distributorPartNumber()});
        }
        for (int from = 0; from < rows.size(); from += BATCH_SIZE) {
            jdbcTemplate.batchUpdate(UPDATE_STOCK, rows.subList(from, Math.min(rows.size(), from + BATCH_SIZE)));
        }
        refreshed.forEach((d, numbers) -> stockChanged(d, numbers, true));
    }

    /**
     * A stock refresh found the part sold out: the row keeps its metadata but is no longer served
     * ({@code in_stock = false}, {@code stock_fetched_at = now}) until a live fetch finds it in stock again.
     */
    public void markSoldOut(Distributor distributor, String partNumber) {
        jdbc.sql("""
                        UPDATE cached_parts SET in_stock = false, stock_fetched_at = ?
                        WHERE distributor = ? AND part_number = ?""")
                .params(utc(clock.instant()), distributor.name(), partNumber).update();
        stockChanged(distributor, List.of(partNumber), false);
    }

    /** The listeners' work for an upsert of {@code parts}; a listener that fails is skipped (logged). */
    private List<Runnable> prepare(List<Part> parts, boolean inStock) {
        if (parts.isEmpty() || listeners.isEmpty()) {
            return List.of();
        }
        List<Runnable> work = new ArrayList<>(listeners.size());
        for (CacheWriteListener listener : listeners) {
            try {
                work.add(listener.upserting(List.copyOf(parts), inStock));
            } catch (RuntimeException e) {
                log.warn("Preparing the {} work for {} cached parts failed: {}", listener.getClass().getSimpleName(),
                        parts.size(), e.toString());
            }
        }
        return work;
    }

    private void stockChanged(Distributor distributor, List<String> partNumbers, boolean inStock) {
        for (CacheWriteListener listener : listeners) {
            try {
                listener.stockChanged(distributor, partNumbers, inStock);
            } catch (RuntimeException e) {
                log.warn("{} failed on a stock change of {} {} parts: {}", listener.getClass().getSimpleName(),
                        partNumbers.size(), distributor, e.toString());
            }
        }
    }

    /** Runs {@code body} in one transaction (DESIGN.md 3.8: the payload and its index row together). */
    private void inTransaction(Runnable body) {
        if (transactionManager == null) {
            body.run();
            return;
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> body.run());
    }

    /** Cached in-stock parts among {@code partNumbers} whatever the age of their stock, keyed by part number. */
    public Map<String, Part> findInStock(Distributor distributor, Collection<String> partNumbers) {
        return findFresh(distributor, partNumbers, null);
    }

    /**
     * Cached in-stock parts among {@code partNumbers} whose stock was fetched at or after {@code since} (any age when
     * null), keyed by part number, unordered.
     */
    public Map<String, Part> findFresh(Distributor distributor, Collection<String> partNumbers, Instant since) {
        if (partNumbers == null || partNumbers.isEmpty()) {
            return Map.of();
        }
        String[] ids = new LinkedHashSet<>(partNumbers).toArray(String[]::new);
        Map<String, Part> result = new HashMap<>(ids.length * 2);
        jdbc.sql("""
                        SELECT part_number, payload::text AS payload FROM cached_parts
                        WHERE distributor = ? AND part_number = ANY(?) AND in_stock AND stock_fetched_at >= ?""")
                .params(distributor.name(), ids, utc(since == null ? Instant.EPOCH : since))
                .query(rs -> {
                    String partNumber = rs.getString("part_number");
                    readPart(distributor, rs).ifPresent(p -> result.put(partNumber, p));
                });
        return result;
    }

    /** One cached in-stock part whatever the age of its stock, if its payload is readable. */
    public Optional<Part> find(Distributor distributor, String partNumber) {
        return find(distributor, partNumber, null);
    }

    /** One cached in-stock part, if its stock was fetched at or after {@code since} (any age when null). */
    public Optional<Part> find(Distributor distributor, String partNumber, Instant since) {
        List<Optional<Part>> rows = jdbc.sql("""
                        SELECT part_number, payload::text AS payload FROM cached_parts
                        WHERE distributor = ? AND part_number = ? AND in_stock AND stock_fetched_at >= ?""")
                .params(distributor.name(), partNumber, utc(since == null ? Instant.EPOCH : since))
                .query((rs, n) -> readPart(distributor, rs))
                .list();
        return rows.isEmpty() ? Optional.empty() : rows.getFirst();
    }

    /**
     * The row of a part an explicit part-number lookup found listed without ships-now stock ({@link #upsertListed}:
     * {@code in_stock = false}, stock 0 in the payload). Only for the search that names this part number (DESIGN.md 2,
     * stock rule): the caller must check that the query requests it; never use it to fill a keyword search.
     */
    public Optional<Part> findListed(Distributor distributor, String partNumber) {
        List<Optional<Part>> rows = jdbc.sql("""
                        SELECT part_number, payload::text AS payload FROM cached_parts
                        WHERE distributor = ? AND part_number = ? AND NOT in_stock""")
                .params(distributor.name(), partNumber)
                .query((rs, n) -> {
                    try {
                        Part part = jsonMapper.readValue(rs.getString("payload"), Part.class);
                        return part.stock() <= 0 ? Optional.of(part) : Optional.<Part>empty();
                    } catch (RuntimeException e) {
                        log.warn("Skipping unreadable cached_parts payload for {}:{}: {}", distributor, partNumber,
                                e.getMessage());
                        return Optional.<Part>empty();
                    }
                })
                .list();
        return rows.isEmpty() ? Optional.empty() : rows.getFirst();
    }

    /** Deletes one part. */
    public void delete(Distributor distributor, String partNumber) {
        jdbc.sql("DELETE FROM cached_parts WHERE distributor = ? AND part_number = ?")
                .params(distributor.name(), partNumber).update();
    }

    /**
     * Deletes {@code distributor}'s rows whose metadata was last fetched before {@code cutoff} (its
     * {@code kina.cache.metadata-retention} expired); returns the number deleted.
     */
    public int deleteMetadataOlderThan(Distributor distributor, Instant cutoff) {
        return jdbc.sql("DELETE FROM cached_parts WHERE distributor = ? AND metadata_fetched_at < ?")
                .params(distributor.name(), utc(cutoff)).update();
    }

    /**
     * Counts of both cache tables; "fresh" means in stock with stock and prices fetched within {@code kina.cache.ttl} of
     * the clock's now; the oldest fetch is the oldest {@code stock_fetched_at}.
     */
    public CacheStatistics stats() {
        Instant freshSince = clock.instant().minus(properties.cache().ttl());
        Map<Distributor, Long> byDistributor = new EnumMap<>(Distributor.class);
        for (Distributor d : Distributor.values()) {
            byDistributor.put(d, 0L);
        }
        jdbc.sql("SELECT distributor, count(*) AS n FROM cached_parts GROUP BY distributor")
                .query(rs -> {
                    String name = rs.getString("distributor");
                    try {
                        byDistributor.put(Distributor.valueOf(name), rs.getLong("n"));
                    } catch (IllegalArgumentException e) {
                        log.warn("Ignoring cached_parts rows with unknown distributor '{}'", name);
                    }
                });
        record Totals(long parts, long fresh, OffsetDateTime oldest) {
        }
        Totals totals = jdbc.sql("""
                        SELECT count(*) AS parts, count(*) FILTER (WHERE in_stock AND stock_fetched_at >= ?) AS fresh,
                               min(stock_fetched_at) AS oldest
                        FROM cached_parts""")
                .param(utc(freshSince))
                .query((rs, n) -> new Totals(rs.getLong("parts"), rs.getLong("fresh"),
                        rs.getObject("oldest", OffsetDateTime.class)))
                .single();
        Long searches = jdbc.sql("SELECT count(*) FROM cached_searches").query(Long.class).single();
        return new CacheStatistics(totals.parts(), totals.fresh(), searches == null ? 0 : searches, byDistributor,
                totals.oldest() == null ? null : totals.oldest().toInstant());
    }

    private Optional<Part> readPart(Distributor distributor, ResultSet rs) throws SQLException {
        String partNumber = rs.getString("part_number");
        try {
            Part part = jsonMapper.readValue(rs.getString("payload"), Part.class);
            if (part.stock() <= 0) {
                log.warn("Skipping cached_parts row {}:{} without ships-now stock", distributor, partNumber);
                return Optional.empty();
            }
            return Optional.of(part);
        } catch (RuntimeException e) { // JacksonException, or a Part invariant violated by the payload
            log.warn("Skipping unreadable cached_parts payload for {}:{}: {}", distributor, partNumber,
                    e.getMessage());
            return Optional.empty();
        }
    }

    static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
