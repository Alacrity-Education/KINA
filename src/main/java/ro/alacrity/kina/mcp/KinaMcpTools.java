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
import ro.alacrity.kina.domain.ResponseDetail;
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
            return ranked, in-stock offers.
            Write the query like a part request: component type plus the parameters that matter, e.g. \
            "10uF X7R 0805 MLCC 25V", "4k7 1% 0603 resistor", "thin film resistor 5.36k 0805 0.1%", \
            "SOT-23 N-channel MOSFET 30V", "LDO 3.3V SOT-23-5", "power inductor 3.3uH 6A Isat 8A DCR < 20mOhm", \
            "120 ohm 100MHz 0603 ferrite bead", or a manufacturer part number. Values, tolerance, ratings, \
            dielectric, package, mounting (SMD/THT) and the technology of a passive (thin film, thick film, metal \
            film, wirewound, current sense; ceramic, tantalum, tantalum polymer, aluminium polymer, polymer, \
            electrolytic, film; multilayer...) are parsed and used for ranking (see "parsed" in the result).
            Ratings are minimums: voltage, current (an inductor's rated current; write Isat or "saturation" for the \
            saturation current), power, maximum temperature (105C) and lifetime (2000h) accept any part rated at \
            least that high, so a 25V request also returns 35V and 50V parts, ranked after an equal 25V part. A \
            DCR limit ("DCR < 20mOhm") is a maximum; "low DCR" is a preference (lower DCR ranks higher). A \
            regulator or Zener voltage and a fuse current must match. Ratings are not sent to the distributors' \
            keyword search, so distributor_query shows the phrase without them.
            Mounting (SMD/THT) and technology are strict when stated: a part whose known mounting or technology \
            contradicts the request is left out (counted in excluded_by_constraints), a part that does not state it \
            stays but ranks below known matches. "polymer aluminium" excludes tantalum polymer; a bare "polymer" \
            accepts both.
            Only stock that ships now is returned: parts with only factory stock, on-order or lead-time quantities \
            are never returned. Pass quantity (pieces to order, default 1) to rank parts that cannot supply it lower \
            (stock below the quantity, or a minimum order quantity far above it) and to get the order price.
            Results are grouped per distributor. Each distributor entry has: total_results = how many matches the \
            distributor reported for the query (can be far more than returned); fetched = how many in-stock parts \
            KINA holds for the query and ranked; returned = min(max_results, fetched) = parts in this response; \
            cache = hit | partial | miss | bypassed | not_applicable (LCSC is a local database); error = null or \
            rate_limited | unavailable | not_configured | timeout | bad_response (a failing distributor never \
            fails the whole search, its list is just empty); distributor_query = null when your query text was \
            sent as written, else the distributor-specific phrase KINA sent instead (ratings left out; connector \
            queries such as "female header 1x6 right angle 2.54mm" rewritten into each distributor's wording); \
            fallback_query = null, or the relaxed phrase that found the parts after the first phrase found no \
            in-stock part (ratings, then tolerance, then everything but the core values, package and family are \
            dropped); excluded_by_constraints = parts left out by a strict constraint; out_of_stock_matches = \
            matches the distributor has but cannot ship now (the part exists; it is not returned).
            Connector queries: type (pin header, female header, box header, terminal block, JST, USB-C, FPC, \
            RJ45...), gender, number of positions, rows (1x6, 2x3), pitch (2.54mm, 0.1") and orientation (right \
            angle / vertical) are recognised (parsed.connector) and ranked; say them explicitly.
            Rate limits: when a distributor API is rate limited KINA waits and retries instead of failing at once, \
            so a call may take up to 2 minutes; rate_limit_waited_ms on the distributor entry reports how long it \
            waited (0 normally). error rate_limited means the limit outlasted that budget; parts fetched before \
            are still returned.
            Parts (detail "compact", the default) carry rank (1 = best within the distributor), score (0..1), match \
            (0..1), distributor, part_number (the distributor's number, for get_part), manufacturer, \
            manufacturer_id (TME), mpn (the manufacturer's part number), description, stock, min_order_qty, \
            order_multiple, prices (only the 3 smallest quantity brackets: qty, unit_price, currency), with \
            quantity > 1 also ordered_quantity (raised to the minimum order quantity and the order multiple), \
            unit_price_at_quantity and total_price, availability {status: in_stock | limited (fewer ship now than \
            the quantity) | last_units | supply_constrained | special_order | external_warehouse, note}, \
            datasheet_url, product_url and the canonical attributes (Capacitance, Resistance, Inductance, \
            Impedance, Voltage, Current or RatedCurrent, SaturationCurrent, DCR, Power, MaxTemperature, Lifetime, \
            Tolerance, Dielectric, Package, Mounting, Technology...). detail "full" adds category, package, \
            photo_url, every raw distributor attribute and the distributor-specific extra fields; use it only \
            when you need them. score orders the list; it is relative to the other candidates, so the last of \
            several good parts can score 0.00. match says how well the part satisfies the stated parameters, 1.0 = \
            every stated parameter matches (a parameter the distributor does not state counts as not matched); \
            judge a part by match, not by score.
            ranking = "blended" (deterministic parametric score blended with a cross-encoder relevance model) \
            or "fallback" (deterministic only; ranking_note says why).
            Results are cached for 5 days: calling again with the same query and a larger max_results is served \
            from the cache, and only fetches more from a distributor when the cache holds too few parts.""";

    static final String BATCH_DESCRIPTION = """
            Run several component searches at once (1-20 queries, e.g. every line of a BOM). Same semantics, \
            cache behaviour and result shape as search_parts; returns {"results": [one search_parts result per \
            query, in request order]}. distributors, bypass_cache and detail apply to every query; each query has \
            its own max_results and quantity (pieces to order, default 1). Queries are ranked one after another \
            within an overall ranking budget; queries ranked after it ran out report ranking "fallback". \
            Rate-limit waits share one 2-minute budget for the whole batch.""";

    static final String QUERY_PARAM = """
            Component description or part number, e.g. "10uF X7R 0805", "100nF 50V C0G 0603", "2N7002 SOT-23".""";

    static final String MAX_RESULTS_PARAM = """
            Maximum number of parts returned PER DISTRIBUTOR (1-50, default 10). With 3 distributors up to \
            3 x max_results parts come back. Asking again with a larger value is served from the cache.""";

    static final String DISTRIBUTORS_PARAM = """
            Distributors to search: any of "LCSC", "TME", "MOUSER" (case-insensitive). Default: all configured \
            distributors. A listed distributor that is not configured reports error "not_configured".""";

    static final String QUANTITY_PARAM = """
            Pieces to order (default 1). Parts with less stock rank below parts that can supply it, a minimum order \
            quantity far above it lowers the rank, and each part gets ordered_quantity, unit_price_at_quantity and \
            total_price.""";

    static final String DETAIL_PARAM = """
            "compact" (default): identity, stock, order rules, prices, availability, links, match/score and the \
            canonical attributes. "full": additionally category, package, photo_url, raw distributor attributes and \
            distributor-specific extra fields (larger responses).""";

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
            @McpToolParam(description = BYPASS_CACHE_PARAM, required = false) Boolean bypass_cache,
            @McpToolParam(description = QUANTITY_PARAM, required = false) Integer quantity,
            @McpToolParam(description = DETAIL_PARAM, required = false) String detail) {
        return searchService.search(SearchRequest.of(query, max_results, parseDistributors(distributors),
                bypass_cache, quantity(quantity), ResponseDetail.parse(detail)));
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
            @McpToolParam(description = DETAIL_PARAM, required = false) String detail) {
        if (queries == null || queries.isEmpty()) {
            throw new IllegalArgumentException("queries must contain 1-" + BatchSearchRequest.MAX_QUERIES
                    + " entries");
        }
        List<SearchRequest> requests = queries.stream()
                .map(q -> SearchRequest.of(q == null ? null : q.query(), q == null ? null : q.maxResults(), null,
                        null, q == null ? null : quantity(q.quantity()), null))
                .toList();
        return searchService.searchBatch(BatchSearchRequest.of(requests, parseDistributors(distributors),
                bypass_cache, ResponseDetail.parse(detail)));
    }

    @McpTool(name = "get_part", description = """
            Get the current details of one part by its distributor part number (the part_number field of a \
            search_parts result: LCSC "C15850", TME symbol, Mouser part number such as "603-CC0805KRX7R9BB104"). \
            A manufacturer part number also works (compared ignoring case, spaces and hyphens, so ERA6AEB5361V \
            finds Mouser's ERA-6AEB5361V); part.part_number is then the distributor's own number. \
            Returns {found, distributor, part_number, cache, error, reason, part}. found is false when the lookup \
            failed (error says why, reason is null) or the part is not available: reason "not_found" = the \
            distributor does not know the part; reason "out_of_stock" = the distributor lists it but has no stock \
            that ships now, and identity {part_number, manufacturer, mpn, description} tells which part it is (no \
            stock or prices). Prices are the 3 smallest quantity brackets; with quantity the part also gets \
            ordered_quantity, unit_price_at_quantity and total_price. detail "compact" (default) returns the canonical \
            attributes only, "full" also every raw distributor attribute, photo_url and the extra fields. Mouser/TME data \
            comes from the 5-day cache unless bypass_cache is true.""",
            annotations = @McpTool.McpAnnotations(title = "Get one part", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public PartLookupResponse getPart(
            @McpToolParam(description = "\"LCSC\", \"TME\" or \"MOUSER\" (case-insensitive).") String distributor,
            @McpToolParam(description = "Distributor part number, or the manufacturer part number.")
            String part_number,
            @McpToolParam(description = BYPASS_CACHE_PARAM, required = false) Boolean bypass_cache,
            @McpToolParam(description = QUANTITY_PARAM, required = false) Integer quantity,
            @McpToolParam(description = DETAIL_PARAM, required = false) String detail) {
        Distributor d = Distributor.parse(distributor);
        try {
            return lookupService.lookup(d, part_number, bypass_cache != null && bypass_cache,
                    quantity(quantity) == null ? 1 : quantity(quantity),
                    ResponseDetail.parse(detail));
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
