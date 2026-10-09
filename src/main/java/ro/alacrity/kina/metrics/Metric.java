package ro.alacrity.kina.metrics;

import java.util.List;

/**
 * KINA's meters (DESIGN.md 3.7): the Micrometer name, the meter type, the Prometheus help text and the tag keys of
 * each, declared once. Business code passes only the tag values ({@link #key(String...)}); the DESIGN.md table is
 * checked against these declarations ({@code MetricDocumentationTest}). Prometheus names replace the dots with
 * underscores; counters get {@code _total}, timers {@code _seconds} ({@code _count}, {@code _sum}), the JLCPCB age
 * gauge {@code _seconds}.
 */
public enum Metric {

    // ---- counters (persisted) -------------------------------------------------------------------------------------
    SEARCHES("kina.searches", Type.COUNTER,
            "Search requests (one per search_parts call, batch or REST search)"),
    SEARCH_QUERIES("kina.search.queries", Type.COUNTER,
            "Search queries, every query of a batch counted", "type"),
    PARTS_RETURNED("kina.parts.returned", Type.COUNTER,
            "Parts returned in search responses", "distributor", "type"),
    PARTS_FETCHED("kina.parts.fetched", Type.COUNTER,
            "In-stock parts received from distributor search pages", "distributor", "type"),
    DISTRIBUTOR_CALLS("kina.distributor.calls", Type.COUNTER,
            "Distributor fetches of a search query by outcome (the result's error code)",
            "distributor", "outcome", "type"),
    RATE_LIMITED_RESPONSES("kina.distributor.rate.limited.responses", Type.COUNTER,
            "Distributor HTTP calls answered with a rate limit", "distributor"),
    RATE_LIMIT_WAITS("kina.distributor.rate.limit.waits", Type.COUNTER,
            "Waits for a distributor rate limit or cool-down before a retry", "distributor"),
    CACHE_PARTS_ADDED("kina.cache.parts.added", Type.COUNTER,
            "New rows written to cached_parts", "distributor"),
    CACHE_PARTS_REFRESHED("kina.cache.parts.refreshed", Type.COUNTER,
            "Existing cached_parts rows re-fetched and overwritten", "distributor"),
    CACHE_SEARCH_LOOKUPS("kina.cache.search.lookups", Type.COUNTER,
            "Search cache use per distributor fetch by status", "distributor", "status", "type"),
    CACHE_STOCK_REFRESHES("kina.cache.stock.refreshes", Type.COUNTER,
            "Cached parts whose stock and prices were refreshed, by outcome (ok, failed, out_of_stock)",
            "distributor", "outcome"),
    CROSS_ENCODER_EXECUTIONS("kina.cross.encoder.executions", Type.COUNTER,
            "Cross-encoder (MiniLM) model runs"),
    CROSS_ENCODER_CANDIDATES("kina.cross.encoder.candidates", Type.COUNTER,
            "Candidates scored by the cross-encoder"),
    RANKING_FALLBACK("kina.ranking.fallback", Type.COUNTER,
            "Search queries ranked with the deterministic fallback, by reason", "reason"),
    TOOL_CALLS("kina.tool.calls", Type.COUNTER, "MCP tool calls", "tool"),
    TOOL_ERRORS("kina.tool.errors", Type.COUNTER, "MCP tool calls that failed with an error", "tool"),
    API_REQUESTS("kina.api.requests", Type.COUNTER, "REST API requests by endpoint pattern", "endpoint"),
    LOGINS("kina.logins", Type.COUNTER, "Interactive OIDC logins by outcome", "outcome"),
    LOGIN_DENIED("kina.login.denied", Type.COUNTER, "Refused OIDC logins by reason", "reason"),
    OAUTH_TOKENS_ISSUED("kina.oauth.tokens.issued", Type.COUNTER,
            "Access tokens issued by /oauth/token by grant type", "grant"),
    MEMBERSHIP_RECHECKS("kina.membership.rechecks", Type.COUNTER,
            "Group membership re-checks at the identity provider by outcome", "outcome"),
    JLCPCB_DOWNLOADS("kina.jlcpcb.downloads", Type.COUNTER, "JLCPCB database downloads by outcome", "outcome"),
    METRICS_BACKFILL_RUNS("kina.metrics.backfill.runs", Type.COUNTER, "Metrics backfill runs by outcome (ok, failed)",
            "outcome"),
    METRICS_BACKFILL_MOVED("kina.metrics.backfill.moved", Type.COUNTER,
            "Counts the metrics backfill moved from type=unknown to a typed series, by counter name", "name"),
    FIELD_INDEX_REINDEXED("kina.field.index.reindexed", Type.COUNTER,
            "part_index rows written by the field index re-index job", "distributor"),
    FIELD_INDEX_SWEEP_REPAIRED("kina.field.index.sweep.repaired", Type.COUNTER,
            "part_index rows the periodic re-index sweep found not current and rewrote (should stay 0)",
            "distributor"),
    FIELD_SHADOW_QUERIES("kina.field.shadow.queries", Type.COUNTER,
            "Shadow field queries by outcome (ok, dropped, incomplete, failed)", "distributor", "outcome"),
    FIELD_SHADOW_CANDIDATES("kina.field.shadow.candidates", Type.COUNTER,
            "Candidates the shadow field queries returned (unrelaxed step)", "distributor"),
    FIELD_SHADOW_DROPPED("kina.field.shadow.dropped", Type.COUNTER,
            "Returnable parts the shadow field query would have dropped (must stay 0)", "distributor"),
    FIELD_SERVED("kina.field.served", Type.COUNTER,
            "Searches answered from the field index without a distributor call (cache hit)", "distributor"),
    FIELD_LIVE_CALLS("kina.field.live.calls", Type.COUNTER,
            "Distributor calls of the field-first flow by relaxation step (0: the request's phrase)",
            "distributor", "step"),
    FIELD_JOURNAL_HITS("kina.field.journal.hits", Type.COUNTER,
            "Steps of the field-first flow whose phrase the journal had already asked (no call)", "distributor"),
    FIELD_FALLBACKS("kina.field.fallbacks", Type.COUNTER,
            "Searches that took the cached-search path instead of the field-first flow, by reason",
            new TagValues("reason", FieldFallback.codes()), "distributor", "reason"),

    // ---- timers (count and total time persisted) ------------------------------------------------------------------
    SEARCH_DURATION("kina.search.duration", Type.TIMER, "Time to answer a search request (single or batch)"),
    DISTRIBUTOR_DURATION("kina.distributor.duration", Type.TIMER,
            "Time of one distributor search page, rate-limit waits included", "distributor"),
    CROSS_ENCODER_DURATION("kina.cross.encoder.duration", Type.TIMER, "Time of one cross-encoder run"),

    // ---- gauges (recomputed, not persisted) -----------------------------------------------------------------------
    CACHE_PARTS("kina.cache.parts", Type.GAUGE, "Rows in cached_parts by component type", "distributor", "type"),
    CACHE_PARTS_FRESH("kina.cache.parts.fresh", Type.GAUGE,
            "Rows in cached_parts in stock with stock and prices younger than kina.cache.ttl", "distributor"),
    CACHE_PARTS_STALE("kina.cache.parts.stale", Type.GAUGE,
            "Rows in cached_parts kept for their metadata only (stock and prices older than kina.cache.ttl, or sold "
                    + "out)", "distributor"),
    CACHE_PARTS_STALE_STOCK("kina.cache.parts.stale.stock", Type.GAUGE,
            "Rows in cached_parts in stock whose stock and prices are older than kina.cache.ttl (returned with "
                    + "stale: true unless a refresh succeeds)", "distributor"),
    CACHE_SEARCHES("kina.cache.searches", Type.GAUGE, "Rows in cached_searches by component type", "distributor",
            "type"),
    USERS_KNOWN("kina.users.known", Type.GAUGE, "Users that are not blocked"),
    USERS_REVOKED("kina.users.revoked", Type.GAUGE, "Users blocked by a failed group check"),
    TOKENS_ACTIVE("kina.tokens.active", Type.GAUGE, "Access tokens neither revoked nor expired"),
    JLCPCB_DATABASE_PARTS("kina.jlcpcb.database.parts", Type.GAUGE, "Parts in the JLCPCB database (0 when unknown)"),
    JLCPCB_DATABASE_AGE("kina.jlcpcb.database.age", Type.GAUGE,
            "Age of the JLCPCB database download (0 when unknown)"),
    METRICS_BACKFILL_LAST_RUN("kina.metrics.backfill.last.run", Type.GAUGE,
            "End of the last successful metrics backfill run, Unix epoch (0 when never)"),
    DISTRIBUTOR_QUOTA_USED("kina.distributor.quota.used", Type.GAUGE,
            "API requests KINA sent to the distributor in the sliding window (in memory, not persisted)",
            "distributor", "window"),
    DISTRIBUTOR_QUOTA_LIMIT("kina.distributor.quota.limit", Type.GAUGE,
            "Configured API request limit of the window (kina.distributors.*.quota)", "distributor", "window"),
    DISTRIBUTOR_QUOTA_THROTTLED_UNTIL("kina.distributor.quota.throttled.until", Type.GAUGE,
            "End of the rate limit the distributor last answered with, Unix epoch (0 when not throttled)",
            "distributor");

    /** The meter types. */
    public enum Type { COUNTER, TIMER, GAUGE }

    /**
     * The closed set of values a tag takes ({@link FieldFallback} for the {@code reason} of
     * {@link #FIELD_FALLBACKS}): listed in the help text and checked against DESIGN.md.
     */
    public record TagValues(String tag, List<String> values) {

        public TagValues {
            values = List.copyOf(values);
        }
    }

    private final String meterName;
    private final Type type;
    private final String help;
    private final List<String> tags;
    private final TagValues tagValues;

    Metric(String meterName, Type type, String help, String... tags) {
        this(meterName, type, help, null, tags);
    }

    Metric(String meterName, Type type, String help, TagValues tagValues, String... tags) {
        this.meterName = meterName;
        this.type = type;
        this.help = tagValues == null ? help : help + " (" + String.join(", ", tagValues.values()) + ")";
        this.tags = List.of(tags);
        this.tagValues = tagValues;
        if (tagValues != null && !this.tags.contains(tagValues.tag())) {
            throw new IllegalArgumentException(meterName + " has no tag " + tagValues.tag());
        }
    }

    /** The declared values of one tag, null when the tag values are open (a distributor, a family). */
    public TagValues tagValues() {
        return tagValues;
    }

    /** The Micrometer name ({@code kina.searches}), also the {@code name} column of {@code metrics_counters}. */
    public String meterName() {
        return meterName;
    }

    public Type type() {
        return type;
    }

    /** The Prometheus {@code # HELP} text. */
    public String help() {
        return help;
    }

    /** The tag keys, in the order {@link #key(String...)} takes their values. */
    public List<String> tags() {
        return tags;
    }

    /**
     * The key of one series: the tag values in the order of {@link #tags()}
     * ({@code DISTRIBUTOR_CALLS.key("MOUSER", "ok", "capacitor")}).
     *
     * @throws IllegalArgumentException when the number of values is not the number of tags
     */
    public MetricKey key(String... values) {
        if (values.length != tags.size()) {
            throw new IllegalArgumentException(this + " takes the tags " + tags + ", got " + values.length + " values");
        }
        String[] pairs = new String[values.length * 2];
        for (int i = 0; i < values.length; i++) {
            pairs[2 * i] = tags.get(i);
            pairs[2 * i + 1] = values[i];
        }
        return MetricKey.of(meterName, pairs);
    }

    /** The base unit of a gauge ({@code seconds} for the JLCPCB age and the last backfill run), else null. */
    public String baseUnit() {
        return this == JLCPCB_DATABASE_AGE || this == METRICS_BACKFILL_LAST_RUN
                || this == DISTRIBUTOR_QUOTA_THROTTLED_UNTIL ? "seconds" : null;
    }

    /**
     * The exported name: {@code kina_searches_total}, {@code kina_search_duration_seconds}, {@code kina_cache_parts}.
     */
    public String prometheusName() {
        return switch (type) {
            case COUNTER -> prometheusName(meterName, false);
            case TIMER -> prometheusName(meterName, true);
            case GAUGE -> meterName.replace('.', '_') + (baseUnit() == null ? "" : "_" + baseUnit());
        };
    }

    /** The help text of a meter name, null for a name that is none of these. */
    static String help(String meterName) {
        for (Metric metric : values()) {
            if (metric.meterName.equals(meterName)) {
                return metric.help;
            }
        }
        return null;
    }

    /**
     * The Prometheus name of a counter or timer as exported ({@code kina.searches} -&gt; {@code kina_searches_total},
     * {@code kina.search.duration} -&gt; {@code kina_search_duration_seconds}).
     */
    public static String prometheusName(String meterName, boolean timer) {
        return meterName.replace('.', '_') + (timer ? "_seconds" : "_total");
    }
}
