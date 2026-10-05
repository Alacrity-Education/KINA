package ro.alacrity.kina.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.domain.BatchSearchRequest;
import ro.alacrity.kina.domain.BatchSearchResponse;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.DistributorStatusResponse;
import ro.alacrity.kina.domain.PartLookupResponse;
import ro.alacrity.kina.domain.SearchRequest;
import ro.alacrity.kina.domain.SearchResponse;
import ro.alacrity.kina.search.DistributorStatusService;
import ro.alacrity.kina.search.PartLookupService;
import ro.alacrity.kina.search.PartSearchService;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * MCP tools exposed on {@code /mcp} (DESIGN.md section 4). Methods annotated with {@link McpTool} are picked up
 * by the Spring AI annotation scanner (stateless server, sync). Parameter names are the JSON argument names
 * (compiled with {@code -parameters}), hence the snake_case Java parameter names. Results are DTO records that the
 * MCP server serialises to JSON text content.
 */
@Component
public class KinaMcpTools {

    static final String SEARCH_DESCRIPTION = """
            Search electronic components across distributors (LCSC via the JLCPCB parts database, TME, Mouser) and \
            return ranked, in-stock offers with full details.
            Write the query like a part request: component type plus the parameters that matter, e.g. \
            "10uF X7R 0805 MLCC 25V", "4k7 1% 0603 resistor", "SOT-23 N-channel MOSFET 30V", "LDO 3.3V SOT-23-5", \
            or a manufacturer part number. Values, tolerance, voltage, dielectric and package are parsed and used \
            for ranking (see "parsed" in the result).
            Only stock that ships now is returned: parts with only factory stock, on-order or lead-time quantities \
            are never returned.
            Results are grouped per distributor. Each distributor entry has: total_results = how many matches the \
            distributor reported for the query (can be far more than returned); fetched = how many in-stock parts \
            KINA holds for the query and ranked; returned = min(max_results, fetched) = parts in this response; \
            cache = hit | partial | miss | bypassed | not_applicable (LCSC is a local database); error = null or \
            rate_limited | unavailable | not_configured | timeout | bad_response (a failing distributor never \
            fails the whole search, its list is just empty); distributor_query = null when your query text was \
            sent as written, else the distributor-specific phrase KINA sent instead (connector queries such as \
            "female header 1x6 right angle 2.54mm" are rewritten into each distributor's wording); \
            fallback_query = null, or the shorter phrase KINA tried because that search found nothing.
            Connector queries: type (pin header, female header, box header, terminal block, JST, USB-C, FPC, \
            RJ45...), gender, number of positions, rows (1x6, 2x3), pitch (2.54mm, 0.1") and orientation (right \
            angle / vertical) are recognised (parsed.connector) and ranked; say them explicitly.
            Rate limits: when a distributor API is rate limited KINA waits and retries instead of failing at once, \
            so a call may take up to 2 minutes; rate_limit_waited_ms on the distributor entry reports how long it \
            waited (0 normally). error rate_limited means the limit outlasted that budget; parts fetched before \
            are still returned.
            Parts carry rank (1 = best within the distributor), score (0..1), distributor part_number, \
            manufacturer, mpn, description, package, stock, min_order_qty, order_multiple, prices (only the 3 \
            smallest quantity brackets: qty, unit_price, currency), datasheet_url, photo_url (when the distributor \
            has one), product_url, parametric attributes and distributor-specific extra fields.
            ranking = "blended" (deterministic parametric score blended with a cross-encoder relevance model) \
            or "fallback" (deterministic only; ranking_note says why).
            Results are cached for 5 days: calling again with the same query and a larger max_results is served \
            from the cache, and only fetches more from a distributor when the cache holds too few parts.""";

    static final String BATCH_DESCRIPTION = """
            Run several component searches at once (1-20 queries, e.g. every line of a BOM). Same semantics, \
            cache behaviour and result shape as search_parts; returns {"results": [one search_parts result per \
            query, in request order]}. distributors and bypass_cache apply to every query; each query has its own \
            max_results. Queries are ranked one after another within an overall ranking budget; queries ranked \
            after it ran out report ranking "fallback". Rate-limit waits share one 2-minute budget for the whole \
            batch.""";

    static final String QUERY_PARAM = """
            Component description or part number, e.g. "10uF X7R 0805", "100nF 50V C0G 0603", "2N7002 SOT-23".""";

    static final String MAX_RESULTS_PARAM = """
            Maximum number of parts returned PER DISTRIBUTOR (1-50, default 10). With 3 distributors up to \
            3 x max_results parts come back. Asking again with a larger value is served from the cache.""";

    static final String DISTRIBUTORS_PARAM = """
            Distributors to search: any of "LCSC", "TME", "MOUSER" (case-insensitive). Default: all configured \
            distributors. A listed distributor that is not configured reports error "not_configured".""";

    static final String BYPASS_CACHE_PARAM = """
            Default false. true skips the cache lookup and queries the distributors live (fresh stock and prices); \
            the results still refresh the cache. Mouser has a small daily API quota, so use it only when fresh data \
            matters. No effect on LCSC (served from a local JLCPCB database).""";

    private final String version;
    private final PartSearchService searchService;
    private final PartLookupService lookupService;
    private final DistributorStatusService statusService;

    /** Explicit constructor: the {@code @Value} parameter must not rely on Lombok copying field annotations. */
    public KinaMcpTools(@Value("${spring.ai.mcp.server.version:dev}") String version,
                        PartSearchService searchService,
                        PartLookupService lookupService,
                        DistributorStatusService statusService) {
        this.version = version;
        this.searchService = searchService;
        this.lookupService = lookupService;
        this.statusService = statusService;
    }

    @McpTool(name = "ping", description = "Health check. Returns {\"status\":\"ok\",\"version\":...} when the KINA MCP server is reachable.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public Ping ping() {
        return new Ping("ok", version);
    }

    @McpTool(name = "search_parts", description = SEARCH_DESCRIPTION,
            annotations = @McpTool.McpAnnotations(title = "Search electronic components", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public SearchResponse searchParts(
            @McpToolParam(description = QUERY_PARAM) String query,
            @McpToolParam(description = MAX_RESULTS_PARAM, required = false) Integer max_results,
            @McpToolParam(description = DISTRIBUTORS_PARAM, required = false) List<String> distributors,
            @McpToolParam(description = BYPASS_CACHE_PARAM, required = false) Boolean bypass_cache) {
        return searchService.search(SearchRequest.of(query, max_results, parseDistributors(distributors),
                bypass_cache));
    }

    @McpTool(name = "search_parts_batch", description = BATCH_DESCRIPTION,
            annotations = @McpTool.McpAnnotations(title = "Search several components", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public BatchSearchResponse searchPartsBatch(
            @McpToolParam(description = "1-20 searches, each {\"query\": \"...\", \"max_results\": 10}; "
                    + "max_results is per distributor (1-50, default 10).") List<BatchQuery> queries,
            @McpToolParam(description = DISTRIBUTORS_PARAM, required = false) List<String> distributors,
            @McpToolParam(description = BYPASS_CACHE_PARAM, required = false) Boolean bypass_cache) {
        if (queries == null || queries.isEmpty()) {
            throw new IllegalArgumentException("queries must contain 1-" + BatchSearchRequest.MAX_QUERIES
                    + " entries");
        }
        List<SearchRequest> requests = queries.stream()
                .map(q -> SearchRequest.of(q == null ? null : q.query(), q == null ? null : q.maxResults(), null,
                        null))
                .toList();
        return searchService.searchBatch(BatchSearchRequest.of(requests, parseDistributors(distributors),
                bypass_cache));
    }

    @McpTool(name = "get_part", description = """
            Get the current details of one part by its distributor part number (the part_number field of a \
            search_parts result: LCSC "C15850", TME symbol, Mouser part number such as "603-CC0805KRX7R9BB104"). \
            Returns {found, distributor, part_number, cache, error, part}; found is false when the distributor \
            does not know the part, has no stock that ships now, or failed (error then says why). Prices are the \
            3 smallest quantity brackets. Mouser/TME data comes from the 5-day cache unless bypass_cache is true.""",
            annotations = @McpTool.McpAnnotations(title = "Get one part", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public PartLookupResponse getPart(
            @McpToolParam(description = "\"LCSC\", \"TME\" or \"MOUSER\" (case-insensitive).") String distributor,
            @McpToolParam(description = "Distributor part number (not the manufacturer part number).")
            String part_number,
            @McpToolParam(description = BYPASS_CACHE_PARAM, required = false) Boolean bypass_cache) {
        Distributor d = Distributor.parse(distributor);
        try {
            return lookupService.lookup(d, part_number, bypass_cache != null && bypass_cache);
        } catch (DistributorException e) {
            return PartLookupResponse.notFound(d, part_number == null ? null : part_number.strip(), null,
                    e.errorCode());
        }
    }

    @McpTool(name = "list_distributors", description = """
            List the distributors KINA can search with their state: configured (credentials present), available, \
            a human-readable detail (for LCSC: JLCPCB database date and part count, or download progress), cached \
            part counts, the Postgres cache statistics (5-day freshness) and the ranking configuration (cross-encoder \
            model state). Does not call the distributor APIs.""",
            annotations = @McpTool.McpAnnotations(title = "List distributors", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public DistributorStatusResponse listDistributors() {
        return statusService.status();
    }

    static Set<Distributor> parseDistributors(List<String> names) {
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        Set<Distributor> out = EnumSet.noneOf(Distributor.class);
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            for (String part : name.split(",")) {
                if (!part.isBlank()) {
                    out.add(Distributor.parse(part));
                }
            }
        }
        return out;
    }

    public record Ping(@JsonProperty("status") String status, @JsonProperty("version") String version) {
    }

    /** One entry of {@code search_parts_batch.queries}. */
    public record BatchQuery(
            @JsonProperty("query") @JsonPropertyDescription("Component description or part number.") String query,
            @JsonProperty("max_results") @JsonPropertyDescription("Parts per distributor, 1-50, default 10.")
            Integer maxResults) {
    }
}
