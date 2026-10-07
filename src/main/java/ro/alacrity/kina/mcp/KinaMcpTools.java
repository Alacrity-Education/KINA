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
            Search electronic components across distributors (LCSC via the JLCPCB parts database, TME, Mouser) and \
            return ranked, in-stock offers.
            Write the query like a part request: component type plus the parameters that matter, e.g. \
            "10uF X7R 0805 MLCC 25V", "4k7 1% 0603 resistor", "thin film resistor 5.36k 0805 0.1%", \
            "SOT-23 N-channel MOSFET 30V", "LDO 3.3V SOT-23-5", "power inductor 3.3uH 6A Isat 8A DCR < 20mOhm", \
            "120 ohm 100MHz 0603 ferrite bead", "GaN half bridge gate driver 100V", or a manufacturer part number. \
            Values, tolerance, ratings, dielectric, package, mounting (SMD/THT), the technology of a passive (thin \
            film, thick film, metal film, wirewound, current sense; ceramic, tantalum, tantalum polymer, aluminium \
            polymer, polymer, electrolytic, film; multilayer...) and the semiconductor of a MOSFET, transistor or \
            gate driver (GaN, SiC, silicon) are parsed and used for ranking (see "parsed" in the result). \
            A part number in the query (letters and digits mixed, e.g. "uP1966E GaN half bridge gate driver") is \
            listed in parsed.part_numbers: the part whose MPN or distributor part number equals it (or starts with \
            it) is returned first in its distributor if it meets the hard constraints and ratings, and \
            requested_part_found says per distributor whether it is among the in-stock parts (null when the query \
            names no part number); when false, the hint says whether the part is not listed in stock there or why \
            it was left out, and the other parts are keyword matches, not the requested part. \
            query_understood = false (with a hint) means no component type or parameter was recognised: the parts \
            were found by keywords only and match is null; rephrase, or use get_part for a part number.
            Ratings are hard minimums: voltage, current (an inductor's rated current; write Isat or "saturation" for \
            the saturation current), power, maximum temperature (105C) and lifetime (2000h) accept any part rated at \
            least that high, so a 25V request also returns 35V and 50V parts, ranked after an equal 25V part; a \
            voltage above 2x the request (3x for capacitors) ranks clearly lower (a 600V part after the 100-200V \
            parts of a 100V request) but is still returned with match 1.0. For MOSFETs a lower on-resistance \
            (Resistance, R_DS(on)) ranks a little higher. A \
            DCR limit ("DCR < 20mOhm") is a maximum; "low DCR" is a preference (lower DCR ranks higher). A part \
            whose known rating is below the request is never returned by default (counted in excluded_below_spec); \
            pass allow_below_spec=true to see such parts, flagged below_spec: true and listed after every part that \
            meets the request, closest to the target first. A fuse current must match. Ratings are not sent to the \
            distributors' keyword search (distributor_query shows the phrase).
            Hard constraints are NEVER relaxed and never substituted: a part whose known value contradicts one is \
            left out (excluded_by_constraints, per constraint in excluded_by_constraints_detail). Hard for every \
            component: the primary value (resistance, capacitance, inductance, a ferrite bead's impedance at its \
            frequency, a crystal's or oscillator's frequency), mounting (SMD/THT), technology, the package (except \
            for inductors, crystals and oscillators) and the component type: crystals and oscillators (XO, TCXO, \
            VCXO, MEMS) are never mixed; Schottky, standard rectifier/switching, Zener and TVS diodes are different \
            types; MOSFETs and gate drivers (including GaN power stages and half-bridges with an integrated \
            driver) are different types; N-channel vs P-channel and NPN vs PNP; GaN vs SiC vs silicon; fixed vs \
            adjustable regulators and the exact output \
            voltage; the Zener voltage and a crystal's load capacitance are exact; for connectors the type, gender, \
            positions and pitch, for USB connectors the type and the stated pin count (a higher USB standard is \
            accepted, a lower one is not); a bead array or resistor network for a single-element request (write \
            "array", "network" or "4 lines" to ask for one). "polymer aluminium" excludes tantalum polymer; a bare \
            "polymer" accepts both. The form factor is hard for passives: "heatsink", "chassis", "bolt"/"screw \
            mount" or "aluminium housed" asks for a chassis part (chassis or power package such as SOT-227, TO-220, \
            TO-247), a package names its class (SOT-227 power package, 0805 chip, D2PAK power SMD); chip resistors \
            and leaded (axial, radial) bodies are excluded from such requests. A part that does not state an \
            attribute is kept, listed in unverified.
            Packages are imperial, always: a four-digit chip code is the inch code ("0603" is imperial 0603, never \
            metric 0603 = imperial 0201); write "1608 metric" or "3216M" for a metric code. A can capacitor size \
            ("6.3x5.4mm", "D6.3xL5.4mm") matches within 0.2 mm.
            Relaxable (only when nothing else is found, always reported in constraints_relaxed and in each part's \
            mismatches, e.g. "dielectric: X5R instead of X7R"): the dielectric, then the package of an inductor, \
            crystal or oscillator, then a looser tolerance; also a connector's orientation. When a distributor has \
            nothing that meets the hard constraints, its list is empty (exact_matches 0) and a hint (per \
            distributor and for the response) names the constraints that could not be met: no substitutes are \
            returned; try another package or value, or check whether allow_below_spec is the issue.
            Only stock that ships now is returned: parts with only factory stock, on-order or lead-time quantities \
            are never returned. The one exception: a part the query names by its part number that the distributor \
            lists without stock is still returned, after every part in stock (it takes the last place), with stock 0 \
            and availability.status "out_of_stock" (requested_part_found stays false: it cannot ship now). For BOM work always pass quantity (pieces to order, default 1): parts with less \
            stock rank last, low stock and a minimum order quantity far above the quantity cost rank, and each part \
            gets the order price.
            Results are grouped per distributor. Each distributor entry has: total_results = matches the distributor \
            reported (can be far more than returned); fetched = in-stock parts KINA received for the query before \
            any exclusion; excluded_by_constraints and excluded_below_spec = parts of fetched left out; \
            excluded_below_spec_detail = up to 5 of the below-spec parts, closest first, each with part_number, \
            mpn and the failed rating (rating, part_value as the distributor states it, requested); returned = \
            parts in this response (at most max_results and fetched minus the exclusions); out_of_stock_matches = \
            matches the distributor has but cannot ship now (not part of fetched); cache = hit | partial | miss | \
            bypassed | not_applicable (LCSC is a local database) | stale (the live search failed, error is set, and \
            the parts come from the expired cached list); error = null or rate_limited | unavailable | \
            not_configured | timeout | bad_response (a failing distributor never fails the whole search, its list is \
            just empty); distributor_query = null when your text was sent as written, else the phrase KINA sent \
            (ratings left out; connector queries rewritten into the distributor's wording); fallback_query = null, \
            or the relaxed phrase that produced the parts; query_terms_dropped = request terms not sent in that \
            phrase (informational, they are still checked); constraints_relaxed = constraints actually loosened \
            (empty when nothing was relaxed); exact_matches = returned parts with every typed constraint verified \
            and met (free-text words such as "housed" never block it). currencies lists the price currencies (LCSC USD, TME and Mouser EUR); prices are not converted.
            Connector queries: type (pin header, female header, box header, terminal block, JST, USB-C, FPC, \
            RJ45...), gender, number of positions, rows (1x6, 2x3), pitch (2.54mm, 0.1") and orientation (right \
            angle / vertical) are recognised (parsed.connector) and ranked; say them explicitly.
            Rate limits: when a distributor API is rate limited KINA waits and retries instead of failing at once, \
            so a call may take up to 2 minutes; rate_limit_waited_ms reports how long it waited (0 normally).
            Parts (detail "compact", the default) carry rank (1 = best within the distributor), match (0..1), \
            below_spec (only when true), distributor, part_number (the distributor's number, for get_part), \
            manufacturer, manufacturer_id (TME's own id), mpn, description, stock, stock_as_of (when stock and \
            prices were fetched; cached figures older than a day are refreshed before they are returned), stale \
            (only when true: stock and prices are older than 3 days and could not be refreshed; treat them as \
            unconfirmed, availability.status is "stale", and such parts rank below fresh ones), \
            min_order_qty, order_multiple, prices (the 3 smallest quantity brackets), with quantity > 1 also \
            ordered_quantity, unit_price_at_quantity and total_price, availability {status: in_stock | low_stock \
            (fewer than 10 pieces, or fewer than twice the quantity) | limited (fewer than the quantity) | \
            last_units | stale, note}, lifecycle (active | new | supply_constrained | last_time_buy; the last two rank \
            lower), mismatches, unverified, datasheet_url, product_url and the canonical attributes (Capacitance, \
            Resistance, Inductance, Impedance, Voltage, Current or RatedCurrent, SaturationCurrent, DCR, \
            RippleCurrent, ESR, Power, MaxTemperature, Lifetime, Tolerance, Dielectric, Package, Dimensions, \
            Mounting, Technology, FormFactor, OperatingTemperature, Elements, Qualification, Features...). detail \
            "full" adds score, category, package, photo_url, every raw distributor attribute and the \
            distributor-specific extra fields.
            rank orders the list. score (full detail only) is relative to the other candidates, so the last of \
            several good parts can score 0.00. match says how well the part satisfies the typed constraints it \
            states (free-text words do not count): unverified lists \
            the requested constraints the distributor does not state for the part (e.g. ["current"]); they are left \
            out of match, so match 1.0 with a non-empty unverified list is NOT a confirmed fit (check the \
            datasheet), and such parts rank below parts whose constraints are all verified and met. Judge a part by \
            match, mismatches and unverified, not by score.
            ranking = "blended" (deterministic parametric score blended with a cross-encoder relevance model) \
            or "fallback" (deterministic only; ranking_note says why).
            Results are cached for 3 days: calling again with the same query and a larger max_results is served \
            from the cache, and only fetches more from a distributor when the cache holds too few parts.
            attributions lists the data notice of every distributor whose parts the response contains, e.g. "Data \
            powered by TME.eu Data – no guarantee of data accuracy"; when you show TME data to the user, show the \
            TME notice with it.""";

    static final String BATCH_DESCRIPTION = """
            Run several component searches at once (1-20 queries, e.g. every line of a BOM: give each its quantity). \
            Same semantics, cache behaviour and result shape as search_parts; returns {"results": [one search_parts \
            result per query, in request order]}. distributors, bypass_cache, detail and allow_below_spec apply to \
            every query; each query has its own max_results and quantity (pieces to order, default 1). Queries are \
            ranked one after another within an overall ranking budget; queries ranked after it ran out report \
            ranking "fallback". Rate-limit waits share one 2-minute budget for the whole batch.""";

    static final String QUERY_PARAM = """
            Component description or part number, e.g. "10uF X7R 0805", "100nF 50V C0G 0603", "2N7002 SOT-23".""";

    static final String MAX_RESULTS_PARAM = """
            Maximum number of parts returned PER DISTRIBUTOR (1-50, default 10). With 3 distributors up to \
            3 x max_results parts come back. Asking again with a larger value is served from the cache.""";

    static final String DISTRIBUTORS_PARAM = """
            Distributors to search: any of "LCSC", "TME", "MOUSER" (case-insensitive). Default: all configured \
            distributors. A listed distributor that is not configured reports error "not_configured".""";

    static final String QUANTITY_PARAM = """
            Pieces to order (default 1); pass it for BOM work. Parts with less stock rank below parts that can supply \
            it, low stock (under 10 pieces or under twice the quantity) and a minimum order quantity far above it \
            lower the rank, and each part gets ordered_quantity, unit_price_at_quantity and total_price.""";

    static final String ALLOW_BELOW_SPEC_PARAM = """
            Default false: a part whose known rating (voltage, current, saturation current, power, temperature, \
            lifetime; DCR above a stated maximum) is below the request is left out (excluded_below_spec). true: \
            such parts are returned flagged below_spec: true, after every part that meets the request, closest to \
            the target first. Use it only when no compliant part exists and a weaker one is acceptable.""";

    static final String DETAIL_PARAM = """
            "compact" (default): identity, stock, order rules, prices, availability, links, rank/match and the \
            canonical attributes. "full": additionally score, category, package, photo_url, raw distributor attributes and \
            distributor-specific extra fields (larger responses).""";

    static final String GET_PART_DETAIL_PARAM = """
            "full" (default for get_part): every attribute the distributor gives (canonical and raw), photo_url and \
            the distributor-specific extra fields. "compact": the canonical attributes only.""";

    static final String BYPASS_CACHE_PARAM = """
            Default false. true skips the cache lookup and queries the distributors live (fresh stock and prices); \
            the results still refresh the cache. Mouser has a small daily API quota, so use it only when fresh data \
            matters. No effect on LCSC (served from a local JLCPCB database).""";

    @Value("${spring.ai.mcp.server.version:dev}") private String version;
    @Autowired private PartSearchService searchService;
    @Autowired private PartLookupService lookupService;
    @Autowired private DistributorStatusService statusService;
    @Autowired private KinaMetrics metrics = KinaMetrics.NOOP;

    @McpTool(name = "ping", description = "Health check. Returns {\"status\":\"ok\",\"version\":...} when the KINA MCP server is reachable.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
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
            Get the current details of one part by its distributor part number (the part_number field of a \
            search_parts result: LCSC "C15850", TME symbol, Mouser part number such as "603-CC0805KRX7R9BB104"). \
            A manufacturer part number also works (compared ignoring case, spaces and hyphens, so ERA6AEB5361V \
            finds Mouser's ERA-6AEB5361V); part.part_number is then the distributor's own number. \
            Returns {found, distributor, part_number, cache, error, reason, part, attributions}; part has every attribute the \
            distributor gives (detail "full" is the default here: canonical keys such as RippleCurrent, ESR, \
            Impedance, Dimensions, Qualification and Features plus the raw distributor attributes, photo_url and \
            the extra fields; pass detail "compact" for the canonical attributes only) and stock_as_of. found is \
            false when the lookup failed (error says why, reason is null) or the part is not available: reason \
            "not_found" = the distributor does not know the part. reason "out_of_stock" = the distributor lists it \
            but has no stock that ships now: identity {part_number, manufacturer, mpn, description} tells which \
            part it is, and part is returned too (found true) with stock 0, the listed prices and \
            availability.status "out_of_stock", because you asked for it explicitly; when the distributor gives \
            only the identity (a Mouser catalogue part without a Mouser number), found is false and part null. Prices are the 3 smallest quantity brackets; with quantity the part also gets \
            ordered_quantity, unit_price_at_quantity and total_price. Mouser/TME data comes from the cache unless \
            bypass_cache is true; stock and prices older than a day are refreshed first, and when they are older \
            than 3 days and neither a refresh nor a live lookup succeeds the part carries stale: true and \
            availability.status "stale". attributions holds the distributor's data notice (show TME's with TME \
            data).""",
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
            List the distributors KINA can search with their state: configured (credentials present), available, \
            a human-readable detail (for LCSC: JLCPCB database date and part count, or download progress), cached \
            part counts, the Postgres cache statistics (5-day freshness), the ranking configuration (cross-encoder \
            model state) and usage counters since the first start (searches, tool calls, cache rows added and \
            rate-limited calls per distributor, cross-encoder runs). Does not call the distributor APIs.""",
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
