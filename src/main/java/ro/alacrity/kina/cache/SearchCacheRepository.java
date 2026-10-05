package ro.alacrity.kina.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ro.alacrity.kina.domain.Distributor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static ro.alacrity.kina.cache.PartCacheRepository.utc;

/**
 * {@code cached_searches}: the ordered part-number list per (distributor, normalised query key). Freshness is
 * decided by the caller from {@link CachedSearch#fetchedAt()}. Unreadable rows are logged at WARN and treated as
 * missing.
 */
@Repository
public class SearchCacheRepository {

    private static final Logger log = LoggerFactory.getLogger(SearchCacheRepository.class);

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public SearchCacheRepository(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    public Optional<CachedSearch> find(Distributor distributor, String queryKey) {
        List<Optional<CachedSearch>> rows = jdbc.sql("""
                        SELECT total_results, part_numbers::text AS part_numbers, exhausted, fetched_at
                        FROM cached_searches WHERE distributor = ? AND query_key = ?""")
                .params(distributor.name(), queryKey)
                .query((rs, n) -> {
                    int total = rs.getInt("total_results");
                    Integer totalResults = rs.wasNull() ? null : total;
                    try {
                        String[] partNumbers = jsonMapper.readValue(rs.getString("part_numbers"), String[].class);
                        return Optional.of(new CachedSearch(distributor, queryKey, totalResults,
                                List.of(partNumbers), rs.getBoolean("exhausted"),
                                rs.getObject("fetched_at", OffsetDateTime.class).toInstant()));
                    } catch (RuntimeException e) {
                        log.warn("Skipping unreadable cached_searches row for {} '{}': {}", distributor, queryKey,
                                e.getMessage());
                        return Optional.<CachedSearch>empty();
                    }
                })
                .list();
        return rows.isEmpty() ? Optional.empty() : rows.getFirst();
    }

    /** Inserts or replaces the row for {@code (distributor, queryKey)}; {@code part_numbers} is a JSONB array. */
    public void upsert(CachedSearch search) {
        jdbc.sql("""
                        INSERT INTO cached_searches
                          (distributor, query_key, total_results, part_numbers, exhausted, fetched_at)
                        VALUES (?, ?, ?, ?::jsonb, ?, ?)
                        ON CONFLICT (distributor, query_key) DO UPDATE SET
                          total_results = EXCLUDED.total_results, part_numbers = EXCLUDED.part_numbers,
                          exhausted = EXCLUDED.exhausted, fetched_at = EXCLUDED.fetched_at""")
                .params(search.distributor().name(), search.queryKey(), search.totalResults(),
                        jsonMapper.writeValueAsString(search.partNumbers()), search.exhausted(),
                        utc(search.fetchedAt()))
                .update();
    }

    /** Deletes rows fetched before {@code cutoff}; returns the number deleted. */
    public int deleteOlderThan(Instant cutoff) {
        return jdbc.sql("DELETE FROM cached_searches WHERE fetched_at < ?").param(utc(cutoff)).update();
    }
}
