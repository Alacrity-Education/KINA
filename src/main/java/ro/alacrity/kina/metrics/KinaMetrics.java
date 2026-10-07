package ro.alacrity.kina.metrics;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.distributor.RateLimitRetry;
import ro.alacrity.kina.domain.BatchSearchResponse;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.DistributorResult;
import ro.alacrity.kina.domain.MetricsSummary;
import ro.alacrity.kina.domain.RankingMode;
import ro.alacrity.kina.domain.SearchResponse;
import ro.alacrity.kina.search.QueryParser;

import java.util.Locale;
import java.util.function.Supplier;

import static ro.alacrity.kina.metrics.Metric.API_REQUESTS;
import static ro.alacrity.kina.metrics.Metric.CACHE_PARTS_ADDED;
import static ro.alacrity.kina.metrics.Metric.CACHE_PARTS_REFRESHED;
import static ro.alacrity.kina.metrics.Metric.CACHE_SEARCH_LOOKUPS;
import static ro.alacrity.kina.metrics.Metric.CACHE_STOCK_REFRESHES;
import static ro.alacrity.kina.metrics.Metric.CROSS_ENCODER_CANDIDATES;
import static ro.alacrity.kina.metrics.Metric.CROSS_ENCODER_DURATION;
import static ro.alacrity.kina.metrics.Metric.CROSS_ENCODER_EXECUTIONS;
import static ro.alacrity.kina.metrics.Metric.DISTRIBUTOR_CALLS;
import static ro.alacrity.kina.metrics.Metric.DISTRIBUTOR_DURATION;
import static ro.alacrity.kina.metrics.Metric.JLCPCB_DOWNLOADS;
import static ro.alacrity.kina.metrics.Metric.LOGINS;
import static ro.alacrity.kina.metrics.Metric.LOGIN_DENIED;
import static ro.alacrity.kina.metrics.Metric.MEMBERSHIP_RECHECKS;
import static ro.alacrity.kina.metrics.Metric.OAUTH_TOKENS_ISSUED;
import static ro.alacrity.kina.metrics.Metric.PARTS_FETCHED;
import static ro.alacrity.kina.metrics.Metric.PARTS_RETURNED;
import static ro.alacrity.kina.metrics.Metric.RANKING_FALLBACK;
import static ro.alacrity.kina.metrics.Metric.RATE_LIMITED_RESPONSES;
import static ro.alacrity.kina.metrics.Metric.RATE_LIMIT_WAITS;
import static ro.alacrity.kina.metrics.Metric.SEARCHES;
import static ro.alacrity.kina.metrics.Metric.SEARCH_DURATION;
import static ro.alacrity.kina.metrics.Metric.SEARCH_QUERIES;
import static ro.alacrity.kina.metrics.Metric.TOOL_CALLS;
import static ro.alacrity.kina.metrics.Metric.TOOL_ERRORS;

/**
 * The instrumentation facade (DESIGN.md 3.7): one short call per event from the business code, which never fails
 * because of a metric. Values live in the {@link MetricsStore} and are persisted by {@link MetricsPersistence}.
 * {@link #NOOP} is the default of every instrumented class, so classes built without Spring (tests) need no metrics.
 */
@Slf4j
public class KinaMetrics implements RateLimitRetry.Listener {

    /** Counts in memory only, registers nothing; the default before the Spring bean is injected. */
    /** The {@code type} tag of a query whose component family the parser did not recognise. */
    public static final String UNKNOWN_TYPE = "unknown";

    public static final KinaMetrics NOOP = new KinaMetrics(new MetricsStore(null));

    private final MetricsStore store;

    public KinaMetrics(MetricsStore store) {
        this.store = store;
    }

    public MetricsStore store() {
        return store;
    }

    /** Receives the rate-limit events of every {@link RateLimitRetry} of this process. */
    @PostConstruct
    void attach() {
        RateLimitRetry.listener(this);
    }

    @PreDestroy
    void detach() {
        RateLimitRetry.removeListener(this);
    }

    // ---- search ---------------------------------------------------------------------------------------------------

    /** One single search answered in {@code nanos}. */
    public void searchCompleted(SearchResponse response, long nanos) {
        safely(() -> {
            store.increment(SEARCHES.key());
            store.record(SEARCH_DURATION.key(), nanos);
            query(response);
        });
    }

    /** One batch answered in {@code nanos}: one search request, one query per result. */
    public void batchCompleted(BatchSearchResponse response, long nanos) {
        safely(() -> {
            store.increment(SEARCHES.key());
            store.record(SEARCH_DURATION.key(), nanos);
            if (response != null) {
                response.results().forEach(this::query);
            }
        });
    }

    private void query(SearchResponse response) {
        String type = typeOf(response);
        store.increment(SEARCH_QUERIES.key(type));
        if (response == null) {
            return;
        }
        for (DistributorResult result : response.distributors()) {
            if (result == null || result.distributor() == null) {
                continue;
            }
            String distributor = result.distributor().name();
            store.increment(DISTRIBUTOR_CALLS.key(distributor, result.error() == null ? "ok" : result.error(), type));
            CacheStatus cache = result.cache();
            if (cache != null && cache != CacheStatus.NOT_APPLICABLE) {
                store.increment(CACHE_SEARCH_LOOKUPS.key(distributor, cache.jsonValue(), type));
            }
            store.add(PARTS_RETURNED.key(distributor, type), result.returned());
        }
        if (response.ranking() == RankingMode.FALLBACK) {
            store.increment(RANKING_FALLBACK.key(fallbackReason(response.rankingNote())));
        }
    }

    /** The {@code type} tag of a search: the parser family of its query, {@value #UNKNOWN_TYPE} when none. */
    static String typeOf(SearchResponse response) {
        return typeOf(response == null || response.parsed() == null ? null : response.parsed().family());
    }

    /**
     * The {@code type} tag for a parser family (DESIGN.md 3.7): the family in lower case when it is one of {@link
     * QueryParser#families()}, else {@value #UNKNOWN_TYPE}, so the tag set stays bounded.
     */
    public static String typeOf(String family) {
        if (family == null) {
            return UNKNOWN_TYPE;
        }
        String type = family.toLowerCase(Locale.ROOT);
        return QueryParser.families().contains(type) ? type : UNKNOWN_TYPE;
    }

    /**
     * The {@code reason} tag of a fallback ranking from its {@code ranking_note}: {@code disabled},
     * {@code unavailable} (model not loaded), {@code busy}, {@code batch_budget}, {@code timeout}, {@code failed}.
     */
    static String fallbackReason(String note) {
        if (note == null || note.isBlank()) {
            return "failed";
        }
        String text = note.toLowerCase(Locale.ROOT);
        if (text.contains("disabled")) {
            return "disabled";
        }
        if (text.contains("not loaded")) {
            return "unavailable";
        }
        if (text.contains("busy")) {
            return "busy";
        }
        if (text.contains("batch ranking budget")) {
            return "batch_budget";
        }
        if (text.contains("timeout")) {
            return "timeout";
        }
        return "failed";
    }

    /**
     * One distributor search page received: {@code parts} in-stock parts in {@code nanos}, for a query of the parser
     * family {@code family} (null when none; see {@link #typeOf(String)}).
     */
    public void distributorPage(Distributor distributor, String family, long nanos, int parts) {
        safely(() -> {
            String name = distributor.name();
            store.record(DISTRIBUTOR_DURATION.key(name), nanos);
            store.add(PARTS_FETCHED.key(name, typeOf(family)), parts);
        });
    }

    @Override
    public void rateLimited(Distributor distributor) {
        safely(() -> store.increment(RATE_LIMITED_RESPONSES.key(distributor.name())));
    }

    @Override
    public void waited(Distributor distributor) {
        safely(() -> store.increment(RATE_LIMIT_WAITS.key(distributor.name())));
    }

    // ---- cache ----------------------------------------------------------------------------------------------------

    /** Rows written to {@code cached_parts}: {@code added} new ones, {@code refreshed} overwritten ones. */
    public void cachePartsWritten(Distributor distributor, long added, long refreshed) {
        safely(() -> {
            store.add(CACHE_PARTS_ADDED.key(distributor.name()), added);
            store.add(CACHE_PARTS_REFRESHED.key(distributor.name()), refreshed);
        });
    }

    /** Stock outcome of a refresh of {@code parts} cached parts: {@code ok}, {@code failed} or {@code out_of_stock}. */
    public void stockRefreshed(Distributor distributor, String outcome, long parts) {
        if (parts <= 0) {
            return;
        }
        safely(() -> store.add(CACHE_STOCK_REFRESHES.key(distributor.name(), outcome), parts));
    }

    // ---- ranking --------------------------------------------------------------------------------------------------

    /** One cross-encoder run that scored {@code candidates} parts in {@code nanos}. */
    public void crossEncoderRun(int candidates, long nanos) {
        safely(() -> {
            store.increment(CROSS_ENCODER_EXECUTIONS.key());
            store.add(CROSS_ENCODER_CANDIDATES.key(), candidates);
            store.record(CROSS_ENCODER_DURATION.key(), nanos);
        });
    }

    // ---- MCP and API ----------------------------------------------------------------------------------------------

    /** Runs one MCP tool call, counting it and, when it throws, its error. */
    public <T> T toolCall(String tool, Supplier<T> call) {
        safely(() -> store.increment(TOOL_CALLS.key(tool)));
        try {
            return call.get();
        } catch (RuntimeException e) {
            safely(() -> store.increment(TOOL_ERRORS.key(tool)));
            throw e;
        }
    }

    /** One REST API request, {@code endpoint} being the matched path pattern. */
    public void apiRequest(String endpoint) {
        safely(() -> store.increment(API_REQUESTS.key(endpoint)));
    }

    // ---- security -------------------------------------------------------------------------------------------------

    public void loginSucceeded() {
        safely(() -> store.increment(LOGINS.key("ok")));
    }

    /** A refused login; {@code reason} is the {@code /login-denied} reason code. */
    public void loginDenied(String reason) {
        safely(() -> {
            store.increment(LOGINS.key("denied"));
            store.increment(LOGIN_DENIED.key(reason));
        });
    }

    /** An access token issued by {@code /oauth/token} for {@code grant} ({@code authorization_code}, ...). */
    public void oauthTokenIssued(String grant) {
        safely(() -> store.increment(OAUTH_TOKENS_ISSUED.key(grant)));
    }

    /** A membership re-check that asked the identity provider, by outcome ({@code member}, {@code not_member}...). */
    public void membershipRecheck(String outcome) {
        safely(() -> store.increment(MEMBERSHIP_RECHECKS.key(
                outcome == null ? null : outcome.toLowerCase(Locale.ROOT))));
    }

    // ---- JLCPCB ---------------------------------------------------------------------------------------------------

    /** A finished JLCPCB download attempt: {@code ok}, {@code failed} or {@code interrupted}. */
    public void jlcpcbDownload(String outcome) {
        safely(() -> store.increment(JLCPCB_DOWNLOADS.key(outcome)));
    }

    // ---- reading --------------------------------------------------------------------------------------------------

    /** The key counters for {@code list_distributors} and {@code /api/v1/metrics/summary}. */
    public MetricsSummary summary() {
        return new MetricsSummary(store.sum(SEARCHES), store.sum(SEARCH_QUERIES), store.sumBy(TOOL_CALLS, "tool"),
                store.sumBy(CACHE_PARTS_ADDED, "distributor"), store.sumBy(RATE_LIMITED_RESPONSES, "distributor"),
                store.sum(CROSS_ENCODER_EXECUTIONS), store.sumBy(SEARCH_QUERIES, "type"));
    }

    private static void safely(Runnable update) {
        try {
            update.run();
        } catch (RuntimeException e) {
            log.debug("Metric update failed: {}", e.toString());
        }
    }
}
