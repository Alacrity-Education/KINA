package ro.alacrity.kina.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ro.alacrity.kina.domain.Distributor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ro.alacrity.kina.cache.PartCacheRepository.utc;

/**
 * {@code cached_searches}: the ordered part-number list per (distributor, normalised query key). Freshness is
 * decided by the caller from {@link CachedSearch#fetchedAt()}. Unreadable rows are logged at WARN and treated as
 * missing.
 */
@Repository
@Slf4j
@RequiredArgsConstructor
public class SearchCacheRepository {

    private static final tools.jackson.core.type.TypeReference<Map<String, CachedSearch.RequestedPart>> REQUESTED_TYPE =
            new tools.jackson.core.type.TypeReference<>() {
            };

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public Optional<CachedSearch> find(Distributor distributor, String queryKey) {
        List<Optional<CachedSearch>> rows = jdbc.sql("""
                        SELECT total_results, part_numbers::text AS part_numbers, exhausted, fetched_at, next_offset,
                               fallback_query, out_of_stock_matches, constraints_relaxed::text AS constraints_relaxed,
                               requested_parts::text AS requested_parts
                        FROM cached_searches WHERE distributor = ? AND query_key = ?""")
                .params(distributor.name(), queryKey)
                .query((rs, n) -> {
                    int total = rs.getInt("total_results");
                    Integer totalResults = rs.wasNull() ? null : total;
                    int offset = rs.getInt("next_offset");
                    Integer nextOffset = rs.wasNull() ? null : offset;
                    int outOfStock = rs.getInt("out_of_stock_matches");
                    Integer outOfStockMatches = rs.wasNull() ? null : outOfStock;
                    try {
                        String[] partNumbers = jsonMapper.readValue(rs.getString("part_numbers"), String[].class);
                        String relaxedJson = rs.getString("constraints_relaxed");
                        List<String> relaxed = relaxedJson == null ? null
                                : List.of(jsonMapper.readValue(relaxedJson, String[].class));
                        String requestedJson = rs.getString("requested_parts");
                        Map<String, CachedSearch.RequestedPart> requested = requestedJson == null ? null
                                : jsonMapper.readValue(requestedJson, REQUESTED_TYPE);
                        return Optional.of(new CachedSearch(distributor, queryKey, totalResults,
                                List.of(partNumbers), rs.getBoolean("exhausted"),
                                rs.getObject("fetched_at", OffsetDateTime.class).toInstant(), nextOffset,
                                rs.getString("fallback_query"), outOfStockMatches, relaxed, requested));
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
                          (distributor, query_key, total_results, part_numbers, exhausted, fetched_at, next_offset,
                           fallback_query, out_of_stock_matches, constraints_relaxed, requested_parts)
                        VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                        ON CONFLICT (distributor, query_key) DO UPDATE SET
                          total_results = EXCLUDED.total_results, part_numbers = EXCLUDED.part_numbers,
                          exhausted = EXCLUDED.exhausted, fetched_at = EXCLUDED.fetched_at,
                          next_offset = EXCLUDED.next_offset, fallback_query = EXCLUDED.fallback_query,
                          out_of_stock_matches = EXCLUDED.out_of_stock_matches,
                          constraints_relaxed = EXCLUDED.constraints_relaxed,
                          requested_parts = EXCLUDED.requested_parts""")
                .params(search.distributor().name(), search.queryKey(), search.totalResults(),
                        jsonMapper.writeValueAsString(search.partNumbers()), search.exhausted(),
                        utc(search.fetchedAt()), search.nextOffset(), search.fallbackQuery(),
                        search.outOfStockMatches(), search.constraintsRelaxed() == null ? null
                                : jsonMapper.writeValueAsString(search.constraintsRelaxed()),
                        search.requestedParts() == null ? null
                                : jsonMapper.writeValueAsString(search.requestedParts()))
                .update();
    }

    /**
     * Records the outcome of requested part-number lookups on an existing row (DESIGN.md 3.2 "Requested part
     * numbers"): the in-stock part numbers found are appended to {@code part_numbers} and {@code outcomes} merged into
     * {@code requested_parts}; {@code fetched_at} is unchanged, so the outcome lives as long as the list. No-op when
     * the row does not exist (a search that is not cached).
     *
     * @return true when a row was updated
     */
    public boolean recordRequested(Distributor distributor, String queryKey, List<String> appendPartNumbers,
                                   Map<String, CachedSearch.RequestedPart> outcomes) {
        return jdbc.sql("""
                        UPDATE cached_searches
                           SET part_numbers = part_numbers || ?::jsonb,
                               requested_parts = COALESCE(requested_parts, '{}'::jsonb) || ?::jsonb
                         WHERE distributor = ? AND query_key = ?""")
                .params(jsonMapper.writeValueAsString(appendPartNumbers == null ? List.of() : appendPartNumbers),
                        jsonMapper.writeValueAsString(outcomes == null ? Map.of() : outcomes), distributor.name(),
                        queryKey)
                .update() > 0;
    }

    /** Deletes the row for {@code (distributor, queryKey)} (a search that must not be served from the cache). */
    public void delete(Distributor distributor, String queryKey) {
        jdbc.sql("DELETE FROM cached_searches WHERE distributor = ? AND query_key = ?")
                .params(distributor.name(), queryKey).update();
    }

    /** Deletes rows fetched before {@code cutoff}; returns the number deleted. */
    public int deleteOlderThan(Instant cutoff) {
        return jdbc.sql("DELETE FROM cached_searches WHERE fetched_at < ?").param(utc(cutoff)).update();
    }
}
