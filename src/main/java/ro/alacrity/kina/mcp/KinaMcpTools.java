package ro.alacrity.kina.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.domain.BatchSearchRequest;
import ro.alacrity.kina.domain.BatchSearchResponse;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.DistributorStatusResponse;
import ro.alacrity.kina.domain.PartLookupResponse;
import ro.alacrity.kina.domain.ResponseDetail;
import ro.alacrity.kina.domain.SearchRequest;
import ro.alacrity.kina.domain.SearchResponse;
import ro.alacrity.kina.metrics.KinaMetrics;
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
            Search electronic components at LCSC, TME and Mouser and return ranked offers that ship now, grouped per \
            distributor.
            Write the query like a part request, component type plus the parameters that matter: "10uF X7R 0805 MLCC \
            25V", "4k7 1% 0603 resistor", "SOT-23 N-channel MOSFET 30V", "power inductor 3.3uH Isat 8A DCR < \
            20mOhm", "uP1966E GaN half bridge gate driver", "40x40x10 fan 12V 3000rpm", "0603 red LED 20mA", \
            "SPDT toggle switch solder lug".
            Ratings (voltage, current, power, temperature, lifetime) are minimums: 25V also returns 35V and 50V \
            parts, after the 25V ones (a fan's supply voltage is exact, its current and noise are maximums). A part \
            whose known rating is below the request is never returned (counted in excluded_below_spec); \
            allow_below_spec=true shows such parts, flagged below_spec and listed last.
            Hard constraints are never relaxed or substituted: the value, package, mounting, technology, component \
            type and polarity, connector type, gender, positions and pitch. A part that contradicts one is left out; \
            one that does not state it is kept, listed in unverified. An empty or all-unverified list comes with a \
            hint: try another value or package. Only the dielectric, a looser tolerance, \
            the package of an inductor, crystal or oscillator and a connector's orientation are relaxed, and only \
            when nothing else is found (constraints_relaxed, mismatches).
            Packages are imperial ("0603"); write "1608 metric" for a metric code.
            A part number in the query (parsed.part_numbers) is returned first when it is listed \
            and meets the request; requested_part_found is false otherwise and the hint says why. A requested part \
            listed without stock is still returned, last, with stock 0 and availability.status "out_of_stock".
            Judge a part by match (0..1, the confirmed share of the typed constraints; an unstated hard one counts \
            against it), mismatches and unverified, not by score: a non-empty unverified list is not a confirmed fit. \
            rank orders the list.
            Per distributor read fetched, excluded_by_constraints(_detail), excluded_below_spec(_detail), \
            exact_matches, cache, error (only that distributor failed) and hint. query_understood=false means keyword \
            matching only (match is null): rephrase, or use get_part for a part number.
            For BOM work pass quantity (pieces to order): parts that cannot supply it rank last and each part gets \
            the order price. detail="full" adds score and the raw distributor attributes. Under rate limits KINA \
            waits, so a call can take up to 2 minutes (rate_limit_waited_ms). Results are cached for 3 days \
            (live_calls 0 and fetched_live false: no distributor call; field_steps_tried: steps read from the \
            index).
            Field reference: docs/API.md in the KINA repository.""";

    static final String BATCH_DESCRIPTION = """
            Run several component searches at once (1-20 queries, e.g. every line of a BOM: give each its quantity). \
            Same semantics, cache behaviour and result shape as search_parts; returns {"results": [one search_parts \
            result per query, in request order]}. distributors, bypass_cache, detail and allow_below_spec apply to \
            every query; each query has its own max_results and quantity. Queries ranked after the overall ranking \
            budget ran out report ranking "fallback". Rate-limit waits share one 2-minute budget for the whole \
            batch.""";

    static final String QUERY_PARAM = """
            Component description or part number, e.g. "10uF X7R 0805", "100nF 50V C0G 0603", "2N7002 SOT-23".""";

    static final String MAX_RESULTS_PARAM = """
            Maximum number of parts returned PER DISTRIBUTOR (1-50, default 10).""";

    static final String DISTRIBUTORS_PARAM = """
            Any of "LCSC", "TME", "MOUSER" (case-insensitive). Default: all configured distributors.""";

    static final String QUANTITY_PARAM = """
            Pieces to order (default 1). Ranks by whether stock and minimum order fit, and adds \
            ordered_quantity, unit_price_at_quantity and total_price to each part.""";

    static final String ALLOW_BELOW_SPEC_PARAM = """
            Default false. true also returns parts whose known rating is below the request, flagged below_spec: \
            true and listed after every compliant part, closest first. Use it only when no compliant part exists.""";

    static final String DETAIL_PARAM = """
            "compact" (default) or "full" (adds score, category, package, photo_url, the raw distributor \
            attributes and extra fields).""";

    static final String GET_PART_DETAIL_PARAM = """
            "full" (default here): every attribute, canonical and raw, photo_url and extra fields. "compact": the \
            canonical attributes only.""";

    static final String BYPASS_CACHE_PARAM = """
            Default false. true queries the distributors live for fresh stock and prices. Mouser has a small daily \
            quota, so use it only when fresh data matters. No effect on LCSC.""";

    @Value("${spring.ai.mcp.server.version:dev}") private String version;
    @Autowired private PartSearchService searchService;
    @Autowired private PartLookupService lookupService;
    @Autowired private DistributorStatusService statusService;
    @Autowired private KinaMetrics metrics = KinaMetrics.NOOP;

    @McpTool(name = "ping", description = "Health check. Returns {\"status\":\"ok\",\"version\":...} when the KINA MCP server is reachable.",
            annotations = @McpTool.McpAnnotations(title = "Check the KINA server", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Ping ping() {
        return metrics.toolCall("ping", () -> new Ping("ok", version));
    }

    @McpTool(name = "search_parts", description = SEARCH_DESCRIPTION,
            annotations = @McpTool.McpAnnotations(title = "Search electronic components", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public SearchResponse searchParts(
            @McpToolParam(description = QUERY_PARAM) String query,
            @McpToolParam(description = MAX_RESULTS_PARAM, required = false) Integer max_results,
            @McpToolParam(description = DISTRIBUTORS_PARAM, required = false) List<String> distributors,
            @McpToolParam(description = BYPASS_CACHE_PARAM, required = false) Boolean bypass_cache,
            @McpToolParam(description = QUANTITY_PARAM, required = false) Integer quantity,
            @McpToolParam(description = DETAIL_PARAM, required = false) String detail,
            @McpToolParam(description = ALLOW_BELOW_SPEC_PARAM, required = false) Boolean allow_below_spec) {
        return metrics.toolCall("search_parts", () -> searchService.search(SearchRequest.of(query, max_results,
                parseDistributors(distributors), bypass_cache, quantity(quantity), ResponseDetail.parse(detail),
                allow_below_spec)));
    }

    @McpTool(name = "search_parts_batch", description = BATCH_DESCRIPTION,
            annotations = @McpTool.McpAnnotations(title = "Search several components", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public BatchSearchResponse searchPartsBatch(
            @McpToolParam(description = "1-20 searches, each {\"query\": \"...\", \"max_results\": 10, "
                    + "\"quantity\": 1}; max_results is per distributor (1-50, default 10), quantity the pieces to "
                    + "order (default 1).") List<BatchQuery> queries,
            @McpToolParam(description = DISTRIBUTORS_PARAM, required = false) List<String> distributors,
            @McpToolParam(description = BYPASS_CACHE_PARAM, required = false) Boolean bypass_cache,
            @McpToolParam(description = DETAIL_PARAM, required = false) String detail,
            @McpToolParam(description = ALLOW_BELOW_SPEC_PARAM, required = false) Boolean allow_below_spec) {
        return metrics.toolCall("search_parts_batch", () -> searchBatch(queries, distributors, bypass_cache, detail,
                allow_below_spec));
    }

    private BatchSearchResponse searchBatch(List<BatchQuery> queries, List<String> distributors, Boolean bypass_cache,
                                            String detail, Boolean allow_below_spec) {
        if (queries == null || queries.isEmpty()) {
            throw new IllegalArgumentException("queries must contain 1-" + BatchSearchRequest.MAX_QUERIES
                    + " entries");
        }
        List<SearchRequest> requests = queries.stream()
                .map(q -> SearchRequest.of(q == null ? null : q.query(), q == null ? null : q.maxResults(), null,
                        null, q == null ? null : quantity(q.quantity()), null))
                .toList();
        return searchService.searchBatch(BatchSearchRequest.of(requests, parseDistributors(distributors),
                bypass_cache, ResponseDetail.parse(detail), allow_below_spec));
    }

    @McpTool(name = "get_part", description = """
            Get the current details of one part by distributor part number (the part_number of a search_parts \
            result, e.g. LCSC "C15850") or by manufacturer part number (case, spaces and hyphens ignored; \
            part.part_number is then the distributor's own). Returns {found, distributor, part_number, cache, error, \
            reason, part}; detail "full" is the default here. A part listed without stock that ships now is still \
            returned: found true, reason "out_of_stock", stock 0. found is false when the distributor does not know \
            the part (reason "not_found"), gives only its identity (reason "out_of_stock", part null) or the lookup \
            failed (error says why).
            Field reference: docs/API.md in the KINA repository.""",
            annotations = @McpTool.McpAnnotations(title = "Get one part", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public PartLookupResponse getPart(
            @McpToolParam(description = "\"LCSC\", \"TME\" or \"MOUSER\" (case-insensitive).") String distributor,
            @McpToolParam(description = "Distributor part number, or the manufacturer part number.")
            String part_number,
            @McpToolParam(description = BYPASS_CACHE_PARAM, required = false) Boolean bypass_cache,
            @McpToolParam(description = QUANTITY_PARAM, required = false) Integer quantity,
            @McpToolParam(description = GET_PART_DETAIL_PARAM, required = false) String detail) {
        return metrics.toolCall("get_part", () -> lookup(distributor, part_number, bypass_cache, quantity, detail));
    }

    private PartLookupResponse lookup(String distributor, String part_number, Boolean bypass_cache, Integer quantity,
                                      String detail) {
        Distributor d = Distributor.parse(distributor);
        try {
            return lookupService.lookup(d, part_number, bypass_cache != null && bypass_cache,
                    quantity(quantity) == null ? 1 : quantity(quantity),
                    ResponseDetail.parse(detail, ResponseDetail.FULL));
        } catch (DistributorException e) {
            return PartLookupResponse.notFound(d, part_number == null ? null : part_number.strip(), null,
                    e.errorCode());
        }
    }

    @McpTool(name = "list_distributors", description = """
            List the distributors KINA can search with their state: configured, available, a detail line (for LCSC \
            the JLCPCB database date and part count, or the download progress), cached part counts and, for Mouser \
            and TME, the API requests used against the per-minute and per-day quota; also the cache statistics, \
            the ranking model state, the field index state (field_index; for LCSC jlcpcb.field_index, the typed \
            table) and usage counters. Does not call the distributor APIs.""",
            annotations = @McpTool.McpAnnotations(title = "List distributors", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public DistributorStatusResponse listDistributors() {
        return metrics.toolCall("list_distributors", () -> statusService.status().withMetrics(metrics.summary()));
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

    /** A quantity clamped to 1..{@value SearchRequest#MAX_QUANTITY}; null stays null (default 1). */
    static Integer quantity(Integer quantity) {
        return quantity == null ? null : Math.clamp(quantity, 1, SearchRequest.MAX_QUANTITY);
    }

    public record Ping(@JsonProperty("status") String status, @JsonProperty("version") String version) {
    }

    /** One entry of {@code search_parts_batch.queries}. */
    public record BatchQuery(
            @JsonProperty("query") @JsonPropertyDescription("Component description or part number.") String query,
            @JsonProperty("max_results") @JsonPropertyDescription("Parts per distributor, 1-50, default 10.")
            Integer maxResults,
            @JsonProperty("quantity") @JsonPropertyDescription("Pieces to order, default 1.") Integer quantity) {
    }
}
