package ro.alacrity.kina.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.distributor.Deadline;
import ro.alacrity.kina.distributor.RateLimitRetry;
import ro.alacrity.kina.domain.BatchSearchResponse;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.DistributorResult;
import ro.alacrity.kina.domain.MetricsSummary;
import ro.alacrity.kina.domain.RankingMode;
import ro.alacrity.kina.domain.SearchResponse;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KinaMetricsTest {

    final MetricsStore store = new MetricsStore(new SimpleMeterRegistry());
    final KinaMetrics metrics = new KinaMetrics(store);

    static SearchResponse response(RankingMode mode, String note, DistributorResult... results) {
        return new SearchResponse("q", null, mode, note, List.of(results));
    }

    static DistributorResult result(Distributor distributor, CacheStatus cache, String error, int returned) {
        return DistributorResult.builder().distributor(distributor).cache(cache).error(error).returned(returned)
                .fetched(returned).build();
    }

    long value(String name, String... tags) {
        return store.get(MetricKey.of(name, tags));
    }

    @Test
    void searchCountsRequestQueryOutcomesCacheStatusesAndReturnedParts() {
        metrics.searchCompleted(response(RankingMode.BLENDED, null,
                result(Distributor.MOUSER, CacheStatus.HIT, null, 5),
                result(Distributor.TME, CacheStatus.MISS, "rate_limited", 0),
                result(Distributor.LCSC, CacheStatus.NOT_APPLICABLE, null, 10)), 2_000_000L);

        assertThat(value("kina.searches")).isEqualTo(1);
        assertThat(value("kina.search.queries")).isEqualTo(1);
        assertThat(value("kina.distributor.calls", "distributor", "MOUSER", "outcome", "ok")).isEqualTo(1);
        assertThat(value("kina.distributor.calls", "distributor", "TME", "outcome", "rate_limited")).isEqualTo(1);
        assertThat(value("kina.distributor.calls", "distributor", "LCSC", "outcome", "ok")).isEqualTo(1);
        assertThat(value("kina.cache.search.lookups", "distributor", "MOUSER", "status", "hit")).isEqualTo(1);
        assertThat(value("kina.cache.search.lookups", "distributor", "TME", "status", "miss")).isEqualTo(1);
        assertThat(store.sumBy("kina.cache.search.lookups", "distributor")).doesNotContainKey("LCSC");
        assertThat(value("kina.parts.returned", "distributor", "LCSC")).isEqualTo(10);
        assertThat(value("kina.search.duration:count")).isEqualTo(1);
        assertThat(value("kina.search.duration:nanos")).isEqualTo(2_000_000L);
        assertThat(store.sum("kina.ranking.fallback")).isZero();
    }

    @Test
    void batchCountsOneRequestAndEveryQuery() {
        metrics.batchCompleted(new BatchSearchResponse(List.of(
                response(RankingMode.FALLBACK, "cross-encoder model not loaded yet",
                        result(Distributor.MOUSER, CacheStatus.PARTIAL, null, 1)),
                response(RankingMode.FALLBACK, "batch ranking budget of 60s exhausted",
                        result(Distributor.MOUSER, CacheStatus.BYPASSED, "timeout", 0)))), 10);

        assertThat(value("kina.searches")).isEqualTo(1);
        assertThat(value("kina.search.queries")).isEqualTo(2);
        assertThat(value("kina.ranking.fallback", "reason", "unavailable")).isEqualTo(1);
        assertThat(value("kina.ranking.fallback", "reason", "batch_budget")).isEqualTo(1);
        assertThat(value("kina.distributor.calls", "distributor", "MOUSER", "outcome", "timeout")).isEqualTo(1);
    }

    @Test
    void fallbackReasonsAreBounded() {
        assertThat(KinaMetrics.fallbackReason("cross-encoder disabled")).isEqualTo("disabled");
        assertThat(KinaMetrics.fallbackReason("cross-encoder busy: no free slot within 5s")).isEqualTo("busy");
        assertThat(KinaMetrics.fallbackReason("cross-encoder timeout after 5s")).isEqualTo("timeout");
        assertThat(KinaMetrics.fallbackReason("cross-encoder failed: no scores")).isEqualTo("failed");
        assertThat(KinaMetrics.fallbackReason(null)).isEqualTo("failed");
    }

    @Test
    void toolCallsAndErrorsAreCounted() {
        assertThat(metrics.toolCall("ping", () -> "ok")).isEqualTo("ok");
        assertThatThrownBy(() -> metrics.toolCall("search_parts", () -> {
            throw new IllegalArgumentException("query must not be blank");
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(value("kina.tool.calls", "tool", "ping")).isEqualTo(1);
        assertThat(value("kina.tool.calls", "tool", "search_parts")).isEqualTo(1);
        assertThat(value("kina.tool.errors", "tool", "search_parts")).isEqualTo(1);
        assertThat(value("kina.tool.errors", "tool", "ping")).isZero();
    }

    @Test
    void rateLimitEventsArriveThroughTheRetryListener() {
        RateLimitRetry retry = new RateLimitRetry(Distributor.MOUSER, Clock.systemUTC(), d -> { }, () -> 0.5);
        AtomicInteger attempts = new AtomicInteger();
        metrics.attach();
        try {
            String result = retry.call(Deadline.after(Duration.ofMinutes(1)), () -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new RateLimitRetry.RateLimitedResponse("HTTP 429", "1");
                }
                return "ok";
            });
            assertThat(result).isEqualTo("ok");
        } finally {
            metrics.detach();
        }
        RateLimitRetry.removeListener(metrics); // idempotent

        assertThat(value("kina.distributor.rate.limited.responses", "distributor", "MOUSER")).isEqualTo(1);
        // the backoff, plus the cool-down that the fake sleeper did not wait out
        assertThat(value("kina.distributor.rate.limit.waits", "distributor", "MOUSER")).isBetween(1L, 2L);
    }

    @Test
    void summaryHoldsTheKeyCounters() {
        metrics.searchCompleted(response(RankingMode.BLENDED, null), 1);
        metrics.toolCall("search_parts", () -> 1);
        metrics.cachePartsWritten(Distributor.TME, 3, 2);
        metrics.rateLimited(Distributor.MOUSER);
        metrics.crossEncoderRun(40, 1_000_000);
        metrics.loginDenied("group");
        metrics.oauthTokenIssued("refresh_token");
        metrics.membershipRecheck("NOT_MEMBER");
        metrics.jlcpcbDownload("ok");
        metrics.distributorPage(Distributor.LCSC, 5, 7);

        MetricsSummary summary = metrics.summary();
        assertThat(summary.searches()).isEqualTo(1);
        assertThat(summary.searchQueries()).isEqualTo(1);
        assertThat(summary.toolCalls()).containsExactly(Map.entry("search_parts", 1L));
        assertThat(summary.cacheAdded()).containsExactly(Map.entry("TME", 3L));
        assertThat(summary.rateLimitedCalls()).containsExactly(Map.entry("MOUSER", 1L));
        assertThat(summary.crossEncoderExecutions()).isEqualTo(1);
        assertThat(value("kina.cache.parts.refreshed", "distributor", "TME")).isEqualTo(2);
        assertThat(value("kina.cross.encoder.candidates")).isEqualTo(40);
        assertThat(value("kina.logins", "outcome", "denied")).isEqualTo(1);
        assertThat(value("kina.login.denied", "reason", "group")).isEqualTo(1);
        assertThat(value("kina.oauth.tokens.issued", "grant", "refresh_token")).isEqualTo(1);
        assertThat(value("kina.membership.rechecks", "outcome", "not_member")).isEqualTo(1);
        assertThat(value("kina.jlcpcb.downloads", "outcome", "ok")).isEqualTo(1);
        assertThat(value("kina.parts.fetched", "distributor", "LCSC")).isEqualTo(7);
    }

    @Test
    void aBrokenResponseNeverThrows() {
        metrics.searchCompleted(null, 1);
        metrics.batchCompleted(null, 1);

        assertThat(value("kina.searches")).isEqualTo(2);
    }
}
