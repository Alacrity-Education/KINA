package ro.alacrity.kina.metrics;

import java.util.Map;

/**
 * Micrometer names of KINA's meters (DESIGN.md 3.7) and their Prometheus help texts. Prometheus names replace the dots
 * with underscores; counters get {@code _total}, timers {@code _seconds_count} and {@code _seconds_sum}, the JLCPCB age
 * gauge {@code _seconds}.
 */
public final class MetricNames {

    private MetricNames() {
    }

    // ---- counters (persisted) -------------------------------------------------------------------------------------
    public static final String SEARCHES = "kina.searches";
    public static final String SEARCH_QUERIES = "kina.search.queries";
    public static final String PARTS_RETURNED = "kina.parts.returned";
    public static final String PARTS_FETCHED = "kina.parts.fetched";
    public static final String DISTRIBUTOR_CALLS = "kina.distributor.calls";
    public static final String RATE_LIMITED_RESPONSES = "kina.distributor.rate.limited.responses";
    public static final String RATE_LIMIT_WAITS = "kina.distributor.rate.limit.waits";
    public static final String CACHE_PARTS_ADDED = "kina.cache.parts.added";
    public static final String CACHE_PARTS_REFRESHED = "kina.cache.parts.refreshed";
    public static final String CACHE_SEARCH_LOOKUPS = "kina.cache.search.lookups";
    public static final String CACHE_STOCK_REFRESHES = "kina.cache.stock.refreshes";
    public static final String CROSS_ENCODER_EXECUTIONS = "kina.cross.encoder.executions";
    public static final String CROSS_ENCODER_CANDIDATES = "kina.cross.encoder.candidates";
    public static final String RANKING_FALLBACK = "kina.ranking.fallback";
    public static final String TOOL_CALLS = "kina.tool.calls";
    public static final String TOOL_ERRORS = "kina.tool.errors";
    public static final String API_REQUESTS = "kina.api.requests";
    public static final String LOGINS = "kina.logins";
    public static final String LOGIN_DENIED = "kina.login.denied";
    public static final String OAUTH_TOKENS_ISSUED = "kina.oauth.tokens.issued";
    public static final String MEMBERSHIP_RECHECKS = "kina.membership.rechecks";
    public static final String JLCPCB_DOWNLOADS = "kina.jlcpcb.downloads";

    // ---- timers (count and total time persisted) ------------------------------------------------------------------
    public static final String SEARCH_DURATION = "kina.search.duration";
    public static final String DISTRIBUTOR_DURATION = "kina.distributor.duration";
    public static final String CROSS_ENCODER_DURATION = "kina.cross.encoder.duration";

    // ---- gauges (recomputed, not persisted) -----------------------------------------------------------------------
    public static final String CACHE_PARTS = "kina.cache.parts";
    public static final String CACHE_PARTS_FRESH = "kina.cache.parts.fresh";
    public static final String CACHE_PARTS_STALE = "kina.cache.parts.stale";
    public static final String CACHE_PARTS_STALE_STOCK = "kina.cache.parts.stale.stock";
    public static final String CACHE_SEARCHES = "kina.cache.searches";
    public static final String USERS_KNOWN = "kina.users.known";
    public static final String USERS_REVOKED = "kina.users.revoked";
    public static final String TOKENS_ACTIVE = "kina.tokens.active";
    public static final String JLCPCB_DATABASE_PARTS = "kina.jlcpcb.database.parts";
    public static final String JLCPCB_DATABASE_AGE = "kina.jlcpcb.database.age";

    /** Prometheus {@code # HELP} texts. */
    static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
            Map.entry(SEARCHES, "Search requests (one per search_parts call, batch or REST search)"),
            Map.entry(SEARCH_QUERIES, "Search queries, every query of a batch counted"),
            Map.entry(PARTS_RETURNED, "Parts returned in search responses"),
            Map.entry(PARTS_FETCHED, "In-stock parts received from distributor search pages"),
            Map.entry(DISTRIBUTOR_CALLS, "Distributor fetches of a search query by outcome (the result's error code)"),
            Map.entry(RATE_LIMITED_RESPONSES, "Distributor HTTP calls answered with a rate limit"),
            Map.entry(RATE_LIMIT_WAITS, "Waits for a distributor rate limit or cool-down before a retry"),
            Map.entry(CACHE_PARTS_ADDED, "New rows written to cached_parts"),
            Map.entry(CACHE_PARTS_REFRESHED, "Existing cached_parts rows re-fetched and overwritten"),
            Map.entry(CACHE_SEARCH_LOOKUPS, "Search cache use per distributor fetch by status"),
            Map.entry(CACHE_STOCK_REFRESHES, "Cached parts whose stock and prices were refreshed, by outcome (ok, "
                    + "failed, out_of_stock)"),
            Map.entry(CROSS_ENCODER_EXECUTIONS, "Cross-encoder (MiniLM) model runs"),
            Map.entry(CROSS_ENCODER_CANDIDATES, "Candidates scored by the cross-encoder"),
            Map.entry(RANKING_FALLBACK, "Search queries ranked with the deterministic fallback, by reason"),
            Map.entry(TOOL_CALLS, "MCP tool calls"),
            Map.entry(TOOL_ERRORS, "MCP tool calls that failed with an error"),
            Map.entry(API_REQUESTS, "REST API requests by endpoint pattern"),
            Map.entry(LOGINS, "Interactive OIDC logins by outcome"),
            Map.entry(LOGIN_DENIED, "Refused OIDC logins by reason"),
            Map.entry(OAUTH_TOKENS_ISSUED, "Access tokens issued by /oauth/token by grant type"),
            Map.entry(MEMBERSHIP_RECHECKS, "Group membership re-checks at the identity provider by outcome"),
            Map.entry(JLCPCB_DOWNLOADS, "JLCPCB database downloads by outcome"),
            Map.entry(SEARCH_DURATION, "Time to answer a search request (single or batch)"),
            Map.entry(DISTRIBUTOR_DURATION, "Time of one distributor search page, rate-limit waits included"),
            Map.entry(CROSS_ENCODER_DURATION, "Time of one cross-encoder run"),
            Map.entry(CACHE_PARTS, "Rows in cached_parts"),
            Map.entry(CACHE_PARTS_FRESH, "Rows in cached_parts in stock with stock and prices younger than kina.cache.ttl"),
            Map.entry(CACHE_PARTS_STALE, "Rows in cached_parts kept for their metadata only (stock and prices older than "
                    + "kina.cache.ttl, or sold out)"),
            Map.entry(CACHE_PARTS_STALE_STOCK, "Rows in cached_parts in stock whose stock and prices are older than "
                    + "kina.cache.ttl (returned with stale: true unless a refresh succeeds)"),
            Map.entry(CACHE_SEARCHES, "Rows in cached_searches"),
            Map.entry(USERS_KNOWN, "Users that are not blocked"),
            Map.entry(USERS_REVOKED, "Users blocked by a failed group check"),
            Map.entry(TOKENS_ACTIVE, "Access tokens neither revoked nor expired"),
            Map.entry(JLCPCB_DATABASE_PARTS, "Parts in the JLCPCB database (0 when unknown)"),
            Map.entry(JLCPCB_DATABASE_AGE, "Age of the JLCPCB database download (0 when unknown)"));

    /** The help text of a meter, or null. */
    static String description(String name) {
        return DESCRIPTIONS.get(name);
    }

    /**
     * The Prometheus name of a counter or timer as exported ({@code kina.searches} -&gt; {@code kina_searches_total},
     * {@code kina.search.duration} -&gt; {@code kina_search_duration_seconds}).
     */
    public static String prometheusName(String name, boolean timer) {
        return name.replace('.', '_') + (timer ? "_seconds" : "_total");
    }
}
