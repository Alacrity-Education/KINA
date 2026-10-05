package ro.alacrity.kina.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
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

/**
 * {@code cached_parts}: one JSONB payload (the default camelCase Jackson serialisation of {@link Part}) per
 * distributor part number. Rows whose payload cannot be deserialised are logged at WARN and skipped, never thrown.
 */
@Repository
@Slf4j
public class PartCacheRepository {

    private static final String UPSERT = """
            INSERT INTO cached_parts (distributor, part_number, payload, fetched_at)
            VALUES (?, ?, ?::jsonb, ?)
            ON CONFLICT (distributor, part_number)
            DO UPDATE SET payload = EXCLUDED.payload, fetched_at = EXCLUDED.fetched_at""";

    private static final int BATCH_SIZE = 500;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final Duration ttl;

    public PartCacheRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate, JsonMapper jsonMapper, Clock clock,
                               KinaProperties properties) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.ttl = properties.cache().ttl();
    }

    /**
     * Inserts or replaces every part using JDBC batches; parts with {@code stock <= 0} are skipped (and rows holding
     * one are never returned by the finders), so the cache can never serve a part without ships-now stock. {@code fetched_at} is the part's own
     * {@link Part#fetchedAt()} (the clock's now when that is null), so re-upserting a part that was loaded from
     * the cache does not make it look fresher than it is.
     */
    public void upsertAll(Collection<Part> parts) {
        if (parts == null || parts.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        List<Object[]> rows = new ArrayList<>(parts.size());
        for (Part part : parts) {
            Objects.requireNonNull(part.distributor(), "part.distributor");
            Objects.requireNonNull(part.distributorPartNumber(), "part.distributorPartNumber");
            if (part.stock() <= 0) {
                // stock rule (DESIGN.md 2): a part without ships-now stock is never cached
                log.warn("Not caching {}:{} without ships-now stock", part.distributor(), part.distributorPartNumber());
                continue;
            }
            Instant fetchedAt = part.fetchedAt() != null ? part.fetchedAt() : now;
            rows.add(new Object[] {part.distributor().name(), part.distributorPartNumber(),
                    jsonMapper.writeValueAsString(part), utc(fetchedAt)});
        }
        for (int from = 0; from < rows.size(); from += BATCH_SIZE) {
            jdbcTemplate.batchUpdate(UPSERT, rows.subList(from, Math.min(rows.size(), from + BATCH_SIZE)));
        }
    }

    /** Fresh ({@code fetched_at >= since}) cached parts among {@code partNumbers}, keyed by part number, unordered. */
    public Map<String, Part> findFresh(Distributor distributor, Collection<String> partNumbers, Instant since) {
        if (partNumbers == null || partNumbers.isEmpty()) {
            return Map.of();
        }
        String[] ids = new LinkedHashSet<>(partNumbers).toArray(String[]::new);
        Map<String, Part> result = new HashMap<>(ids.length * 2);
        jdbc.sql("""
                        SELECT part_number, payload::text AS payload FROM cached_parts
                        WHERE distributor = ? AND part_number = ANY(?) AND fetched_at >= ?""")
                .params(distributor.name(), ids, utc(since))
                .query(rs -> {
                    String partNumber = rs.getString("part_number");
                    readPart(distributor, rs).ifPresent(p -> result.put(partNumber, p));
                });
        return result;
    }

    /** One cached part, if it was fetched at or after {@code since} and its payload is readable. */
    public Optional<Part> find(Distributor distributor, String partNumber, Instant since) {
        List<Optional<Part>> rows = jdbc.sql("""
                        SELECT part_number, payload::text AS payload FROM cached_parts
                        WHERE distributor = ? AND part_number = ? AND fetched_at >= ?""")
                .params(distributor.name(), partNumber, utc(since))
                .query((rs, n) -> readPart(distributor, rs))
                .list();
        return rows.isEmpty() ? Optional.empty() : rows.getFirst();
    }

    /** Deletes rows fetched before {@code cutoff}; returns the number deleted. */
    public int deleteOlderThan(Instant cutoff) {
        return jdbc.sql("DELETE FROM cached_parts WHERE fetched_at < ?").param(utc(cutoff)).update();
    }

    /** Counts of both cache tables; "fresh" means fetched within {@code kina.cache.ttl} of the clock's now. */
    public CacheStatistics stats() {
        Instant freshSince = clock.instant().minus(ttl);
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
                        SELECT count(*) AS parts, count(*) FILTER (WHERE fetched_at >= ?) AS fresh,
                               min(fetched_at) AS oldest
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
