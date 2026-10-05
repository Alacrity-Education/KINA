package ro.alacrity.kina.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Payload of {@code list_distributors} / {@code GET /api/v1/distributors}.
 *
 * @param distributors one entry per distributor, in {@link Distributor} order
 * @param cache        Postgres component cache statistics (Mouser and TME); null when the database cannot be read
 * @param ranking      ranking configuration and Laya health
 */
public record DistributorStatusResponse(
        @JsonProperty("distributors") List<DistributorStatus> distributors,
        @JsonProperty("cache") CacheSummary cache,
        @JsonProperty("ranking") RankingSummary ranking
) {

    public DistributorStatusResponse {
        distributors = distributors == null ? List.of() : List.copyOf(distributors);
    }

    /**
     * One distributor.
     *
     * @param configured          credentials (Mouser, TME) are set; always true for LCSC
     * @param available           searches can be served right now (Mouser/TME: same as configured, no live probe;
     *                            LCSC: the JLCPCB database is open)
     * @param detail              human-readable state
     * @param usesCache           whether results are cached in Postgres (false for LCSC: its database is the cache)
     * @param cachedParts         {@code cached_parts} rows for this distributor; null for LCSC or when unknown
     * @param maxResultsPerSearch largest number of in-stock parts fetched for one query
     * @param jlcpcb              JLCPCB database state (LCSC only)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DistributorStatus(
            @JsonProperty("distributor") Distributor distributor,
            @JsonProperty("configured") boolean configured,
            @JsonProperty("available") boolean available,
            @JsonProperty("detail") String detail,
            @JsonProperty("uses_cache") boolean usesCache,
            @JsonProperty("cached_parts") Long cachedParts,
            @JsonProperty("max_results_per_search") int maxResultsPerSearch,
            @JsonProperty("jlcpcb") JlcpcbSummary jlcpcb
    ) {
    }

    /** State of the JLCPCB parts database that serves LCSC. */
    public record JlcpcbSummary(
            @JsonProperty("available") boolean available,
            @JsonProperty("library") String library,
            @JsonProperty("downloaded_at") Instant downloadedAt,
            @JsonProperty("source_date") String sourceDate,
            @JsonProperty("part_count") Long partCount,
            @JsonProperty("downloading") boolean downloading,
            @JsonProperty("last_error") String lastError
    ) {
    }

    /**
     * Postgres cache statistics.
     *
     * @param ttl         freshness window (ISO-8601 duration, e.g. {@code PT120H})
     * @param parts       {@code cached_parts} rows
     * @param freshParts  rows younger than {@code ttl}
     * @param searches    {@code cached_searches} rows
     * @param oldestFetch oldest {@code cached_parts.fetched_at}
     */
    public record CacheSummary(
            @JsonProperty("ttl") String ttl,
            @JsonProperty("parts") long parts,
            @JsonProperty("fresh_parts") long freshParts,
            @JsonProperty("searches") long searches,
            @JsonProperty("oldest_fetch") Instant oldestFetch
    ) {
    }

    /** Ranking configuration and Laya health. */
    public record RankingSummary(
            @JsonProperty("laya_enabled") boolean layaEnabled,
            @JsonProperty("laya_healthy") boolean layaHealthy,
            @JsonProperty("model") String model,
            @JsonProperty("max_candidates") int maxCandidates,
            @JsonProperty("weight") double weight,
            @JsonProperty("timeout") String timeout
    ) {
    }
}
