package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LayaPartRankerTest {

    private static final String URL = "http://laya.test:8000/";
    private static final String BATCH = "http://laya.test:8000/v1/systemone/batch";

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ParsedQuery query = new QueryParser().parse("10uF X7R 0805");
    private final Part first = ParametricExtractorTest.TME_MLCC;
    private final Part second = ParametricExtractorTest.LCSC_MLCC;

    private MockRestServiceServer server;

    private LayaPartRanker ranker(String... extraProperties) {
        String[] kv = new String[4 + extraProperties.length];
        kv[0] = "kina.ranking.laya.url";
        kv[1] = URL;
        kv[2] = "kina.ranking.laya.model";
        kv[3] = "multilingual";
        System.arraycopy(extraProperties, 0, kv, 4, extraProperties.length);
        KinaProperties properties = RankingFixtures.properties(kv);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new LayaPartRanker(properties, new ParametricExtractor(), builder.build());
    }

    private static String response(double... scores) {
        StringBuilder sb = new StringBuilder("{\"results\":[");
        for (int i = 0; i < scores.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"model\":\"laya-rl-agent\",\"answers\":{\"fits\":{\"type\":\"noul\",\"noul\":").append(scores[i])
                    .append(",\"confidence\":0.9}},\"usage\":{\"input_tokens\":80}}");
        }
        return sb.append("],\"total_usage\":{\"input_tokens\":160,\"output_tokens\":0}}").toString();
    }

    @Test
    void sendsOneCompactStatePerCandidateAndMapsScoresInOrder() throws Exception {
        LayaPartRanker ranker = ranker();
        AtomicReference<String> body = new AtomicReference<>();
        server.expect(requestTo(BATCH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(jsonPath("$.model").value("multilingual"))
                .andExpect(jsonPath("$.sort_by_length").value(true))
                .andExpect(jsonPath("$.questions.fits.type").value("noul"))
                .andExpect(jsonPath("$.questions.fits.instructions").value(LayaPartRanker.FITS_INSTRUCTIONS))
                .andExpect(jsonPath("$.questions.length()").value(1))
                .andExpect(jsonPath("$.states.length()").value(2))
                .andExpect(request -> body.set(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess(response(0.99, 0.0123), MediaType.APPLICATION_JSON));

        Map<String, Double> scores = ranker.rank(query, List.of(first, second, first), Duration.ofSeconds(5));

        server.verify();
        assertThat(scores).containsExactly(Map.entry(first.key(), 0.99), Map.entry(second.key(), 0.0123));
        JsonNode states = mapper.readTree(body.get()).get("states");
        String state = states.get(0).stringValue();
        assertThat(state).doesNotContain("\n", "\": ").startsWith("{\"request\":\"10uF X7R 0805\",\"candidate\":{");
        JsonNode parsed = mapper.readTree(state);
        JsonNode candidate = parsed.get("candidate");
        assertThat(candidate.get("manufacturer").stringValue()).isEqualTo("SAMSUNG");
        assertThat(candidate.get("mpn").stringValue()).isEqualTo("CL21B106KAYQNNE");
        assertThat(candidate.get("description").stringValue()).startsWith("Capacitor: ceramic");
        assertThat(candidate.get("package").stringValue()).isEqualTo("0805");
        assertThat(candidate.get("attributes").get("Capacitance").stringValue()).isEqualTo("10uF");
        assertThat(candidate.get("attributes").get("Dielectric").stringValue()).isEqualTo("X7R");
        assertThat(candidate.get("attributes").has("Package")).isFalse();
        assertThat(mapper.readTree(states.get(1).stringValue()).get("candidate").get("attributes")
                .get("Dielectric").stringValue()).isEqualTo("X5R");
    }

    @Test
    void sendsBearerTokenWhenConfigured() throws Exception {
        LayaPartRanker ranker = ranker("kina.ranking.laya.api-key", "secret-key");
        server.expect(requestTo(BATCH))
                .andExpect(header("Authorization", "Bearer secret-key"))
                .andRespond(withSuccess(response(0.5), MediaType.APPLICATION_JSON));
        assertThat(ranker.rank(query, List.of(first), Duration.ofSeconds(5))).containsEntry(first.key(), 0.5);
        server.verify();
    }

    @Test
    void timeoutBecomesRankingExceptionWithinBudget() {
        LayaPartRanker ranker = ranker();
        server.expect(requestTo(BATCH)).andRespond(request -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return withSuccess(response(0.5), MediaType.APPLICATION_JSON).createResponse(request);
        });
        long start = System.nanoTime();
        assertThatThrownBy(() -> ranker.rank(query, List.of(first), Duration.ofMillis(300)))
                .isInstanceOfSatisfying(RankingException.class, e -> {
                    assertThat(e.reason()).isEqualTo(RankingException.Reason.TIMEOUT);
                    assertThat(e.getMessage()).isEqualTo("laya timeout after 300ms");
                });
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1_500));
    }

    @Test
    void httpAndParseErrorsBecomeRankingExceptions() {
        LayaPartRanker ranker = ranker();
        server.expect(ExpectedCount.once(), requestTo(BATCH)).andRespond(withServerError());
        assertReason(ranker, RankingException.Reason.UNAVAILABLE, "laya unavailable: HTTP 500");

        ranker = ranker();
        server.expect(requestTo(BATCH)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertReason(ranker, RankingException.Reason.BUSY, "laya busy: HTTP 429");

        ranker = ranker();
        server.expect(requestTo(BATCH)).andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT));
        assertReason(ranker, RankingException.Reason.BAD_RESPONSE, "laya rejected request: HTTP 422");

        ranker = ranker();
        server.expect(requestTo(BATCH)).andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));
        assertReason(ranker, RankingException.Reason.BAD_RESPONSE, "laya bad response: invalid JSON");

        ranker = ranker();
        server.expect(requestTo(BATCH)).andRespond(withSuccess(response(0.5, 0.4), MediaType.APPLICATION_JSON));
        assertReason(ranker, RankingException.Reason.BAD_RESPONSE, "laya bad response: 2 results for 1 states");

        ranker = ranker();
        server.expect(requestTo(BATCH)).andRespond(withSuccess(
                "{\"results\":[{\"answers\":{\"other\":{\"noul\":0.5}}}]}", MediaType.APPLICATION_JSON));
        assertReason(ranker, RankingException.Reason.BAD_RESPONSE, "laya bad response: missing answers.fits.noul for state 0");
    }

    @Test
    void unreachableServerIsUnavailable() {
        KinaProperties properties = RankingFixtures.properties("kina.ranking.laya.url", "http://127.0.0.1:9");
        LayaPartRanker ranker = new LayaPartRanker(properties, new ParametricExtractor(),
                new StaticListableBeanFactory().getBeanProvider(RestClient.Builder.class));
        assertThatThrownBy(() -> ranker.rank(query, List.of(first), Duration.ofSeconds(3)))
                .isInstanceOfSatisfying(RankingException.class,
                        e -> assertThat(e.reason()).isEqualTo(RankingException.Reason.UNAVAILABLE));
        assertThat(ranker.isHealthy()).isFalse();
    }

    @Test
    void emptyCandidatesAndExhaustedBudget() throws Exception {
        LayaPartRanker ranker = ranker();
        assertThat(ranker.rank(query, List.of(), Duration.ofSeconds(1))).isEmpty();
        assertThatThrownBy(() -> ranker.rank(query, List.of(first), Duration.ZERO))
                .isInstanceOfSatisfying(RankingException.class,
                        e -> assertThat(e.reason()).isEqualTo(RankingException.Reason.TIMEOUT));
        assertThat(ranker.name()).isEqualTo("laya");
    }

    @Test
    void healthIsCachedForThirtySeconds() {
        LayaPartRanker ranker = ranker();
        server.expect(ExpectedCount.once(), requestTo("http://laya.test:8000/health"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));
        assertThat(ranker.isHealthy()).isTrue();
        assertThat(ranker.isHealthy()).isTrue();
        server.verify();

        LayaPartRanker failing = ranker();
        server.expect(ExpectedCount.once(), requestTo("http://laya.test:8000/health")).andRespond(withServerError());
        assertThat(failing.isHealthy()).isFalse();
        assertThat(failing.isHealthy()).isFalse();
        server.verify();
    }

    private void assertReason(LayaPartRanker ranker, RankingException.Reason reason, String message) {
        assertThatThrownBy(() -> ranker.rank(query, List.of(first), Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(RankingException.class, e -> {
                    assertThat(e.reason()).isEqualTo(reason);
                    assertThat(e.getMessage()).isEqualTo(message);
                });
        server.verify();
    }
    // ---- busy laya-serve (DESIGN.md 3.6) ----------------------------------------------------------------------------

    private final List<Duration> sleeps = new CopyOnWriteArrayList<>();

    private LayaPartRanker rankerWithFakeSleeper() {
        KinaProperties properties = RankingFixtures.properties("kina.ranking.laya.url", URL);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new LayaPartRanker(properties, new ParametricExtractor(), builder.build(), sleeps::add,
                Clock.systemUTC());
    }

    @Test
    void busyWithRetryAfterIsRetriedWithinTheRankingBudget() throws Exception {
        LayaPartRanker ranker = rankerWithFakeSleeper();
        server.expect(requestTo(BATCH)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "2"));
        server.expect(requestTo(BATCH)).andRespond(withSuccess(response(0.9, 0.4), MediaType.APPLICATION_JSON));

        Map<String, Double> scores = ranker.rank(query, List.of(first, second), Duration.ofSeconds(18));

        server.verify();
        assertThat(scores).hasSize(2);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2));
    }

    @Test
    void busyWithRetryAfterBeyondTheBudgetFallsBackAtOnce() {
        LayaPartRanker ranker = rankerWithFakeSleeper();
        server.expect(requestTo(BATCH)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "30"));

        assertThatThrownBy(() -> ranker.rank(query, List.of(first, second), Duration.ofSeconds(18)))
                .isInstanceOfSatisfying(RankingException.class,
                        e -> assertThat(e.reason()).isEqualTo(RankingException.Reason.BUSY));
        server.verify();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void busyWithoutRetryAfterIsNotRetried() {
        LayaPartRanker ranker = rankerWithFakeSleeper();
        server.expect(ExpectedCount.once(), requestTo(BATCH)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> ranker.rank(query, List.of(first, second), Duration.ofSeconds(18)))
                .isInstanceOfSatisfying(RankingException.class,
                        e -> assertThat(e.reason()).isEqualTo(RankingException.Reason.BUSY));
        server.verify();
        assertThat(sleeps).isEmpty();
    }
}
