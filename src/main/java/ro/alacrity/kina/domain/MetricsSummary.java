package ro.alacrity.kina.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Key counters of {@code list_distributors} ({@code metrics}) and {@code GET /api/v1/metrics/summary} (DESIGN.md 3.7).
 * The values are the persisted Prometheus counters, so they count since the first start of this database, not since
 * the last restart.
 *
 * @param searches               search requests (a batch counts once)
 * @param searchQueries          search queries (every query of a batch)
 * @param toolCalls              MCP tool calls per tool name
 * @param cacheAdded             new {@code cached_parts} rows per distributor
 * @param rateLimitedCalls       distributor HTTP calls answered with a rate limit, per distributor
 * @param crossEncoderExecutions cross-encoder model runs
 */
public record MetricsSummary(
        @JsonProperty("searches") long searches,
        @JsonProperty("search_queries") long searchQueries,
        @JsonProperty("tool_calls") Map<String, Long> toolCalls,
        @JsonProperty("cache_added") Map<String, Long> cacheAdded,
        @JsonProperty("rate_limited_calls") Map<String, Long> rateLimitedCalls,
        @JsonProperty("cross_encoder_executions") long crossEncoderExecutions
) {

    public MetricsSummary {
        toolCalls = sorted(toolCalls);
        cacheAdded = sorted(cacheAdded);
        rateLimitedCalls = sorted(rateLimitedCalls);
    }

    private static Map<String, Long> sorted(Map<String, Long> values) {
        return values == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(values));
    }
}
