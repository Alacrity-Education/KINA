package ro.alacrity.kina.distributor.mouser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.Deadline;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.distributor.DistributorException.Kind;
import ro.alacrity.kina.distributor.DistributorSearchPage;
import ro.alacrity.kina.distributor.FakeTime;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;

import java.io.IOException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MouserClientTest {

    private static final String API_KEY = "secret-test-key-1234";
    private static final String BASE = "https://api.mouser.test/api/v1";
    private static final String KEYWORD_URL = BASE + "/search/keyword?apiKey=" + API_KEY;
    private static final String PART_URL = BASE + "/search/partnumber?apiKey=" + API_KEY;
    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    private MockRestServiceServer server;
    private MouserClient client;

    @BeforeEach
    void setUp() {
        client = client(50);
    }

    private final FakeTime time = new FakeTime(NOW);

    private MouserClient client(int maxResultsPerSearch) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        MouserApi api = new MouserApi(builder, BASE + "/", API_KEY, time.retry(Distributor.MOUSER, 0.5));
        return new MouserClient(new KinaProperties.Mouser(API_KEY, BASE, maxResultsPerSearch, 1), api,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final String TOO_MANY_REQUESTS_BODY = """
            {"Errors":[{"Id":0,"Code":"TooManyRequests","Message":"Maximum calls per minute exceeded.",
              "ResourceKey":"TooManyRequests","PropertyName":null}],"SearchResults":null}
            """;

    private static String keywordBody(String keyword, int records, int startingRecord) {
        return """
                {"SearchByKeywordRequest":{"keyword":"%s","records":%d,"startingRecord":%d,
                 "searchOptions":"InStock","searchWithYourSignUpLanguage":"false"}}
                """.formatted(keyword, records, startingRecord);
    }

    @Test
    void searchMapsTheRecordedResponse() {
        server.expect(requestTo(KEYWORD_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(keywordBody("10uF X7R 0805", 5, 1), JsonCompareMode.STRICT))
                .andRespond(withSuccess(MouserFixtures.text(MouserFixtures.KEYWORD), MediaType.APPLICATION_JSON));

        DistributorSearchPage page = client.search("  10uF X7R 0805 ", 0, 5);

        server.verify();
        assertThat(page.totalResults()).isEqualTo(113);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.parts()).extracting(Part::distributorPartNumber).containsExactly(
                "603-CC0805MKX77BB106", "581-0805X7R106KT1AT", "603-AC0805KKX7R5BB16", "603-CC805KKX7R5BB106",
                "603-CC0805KKX77BB106");
        assertThat(page.parts()).allSatisfy(p -> assertThat(p.fetchedAt()).isEqualTo(NOW));
    }

    @Test
    void searchClampsTheLimitAndTranslatesTheOffset() {
        server.expect(requestTo(KEYWORD_URL))
                .andExpect(content().json(keywordBody("x", 50, 51), JsonCompareMode.STRICT))
                .andRespond(withSuccess(MouserFixtures.text(MouserFixtures.KEYWORD), MediaType.APPLICATION_JSON));

        client.search("x", 50, 200);

        server.verify();
    }

    @Test
    void searchRespectsMaxResultsPerSearch() {
        MouserClient small = client(20);
        server.expect(requestTo(KEYWORD_URL))
                .andExpect(content().json(keywordBody("x", 20, 1), JsonCompareMode.STRICT))
                .andRespond(withSuccess(MouserFixtures.text(MouserFixtures.KEYWORD), MediaType.APPLICATION_JSON));

        small.search("x", 0, 40);

        server.verify();
        // the orchestrator pages by maxPageSize(): it must equal the records one call returns
        assertThat(small.maxPageSize()).isEqualTo(20);
        assertThat(client(50).maxPageSize()).isEqualTo(50);
        assertThat(client(0).maxPageSize()).isEqualTo(50);
    }

    @Test
    void searchDropsOutOfStockPartsButCountsThemForPaging() {
        String body = MouserFixtures.text(MouserFixtures.KEYWORD)
                .replace("\"AvailabilityInStock\": \"15614\"", "\"AvailabilityInStock\": \"0\"")
                .replace("\"NumberOfResult\": 113", "\"NumberOfResult\": 10");
        server.expect(requestTo(KEYWORD_URL))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        DistributorSearchPage page = client.search("10uF X7R 0805", 5, 5);

        assertThat(page.parts()).hasSize(4).extracting(Part::distributorPartNumber)
                .doesNotContain("603-AC0805KKX7R5BB16");
        assertThat(page.totalResults()).isEqualTo(10);
        assertThat(page.hasMore()).as("5 + 5 raw parts reach the reported total").isFalse();
    }

    @Test
    void searchWithMoreResultsAvailable() {
        String body = MouserFixtures.text(MouserFixtures.KEYWORD).replace("\"NumberOfResult\": 113", "\"NumberOfResult\": 11");
        server.expect(requestTo(KEYWORD_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThat(client.search("q", 5, 5).hasMore()).isTrue();
    }

    @Test
    void emptySearchResult() {
        server.expect(requestTo(KEYWORD_URL)).andRespond(withSuccess(
                "{\"Errors\":[],\"SearchResults\":{\"NumberOfResult\":0,\"Parts\":[]}}", MediaType.APPLICATION_JSON));

        DistributorSearchPage page = client.search("nothing matches", 0, 10);

        assertThat(page.parts()).isEmpty();
        assertThat(page.totalResults()).isZero();
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void blankQueryOrZeroLimitMakesNoCall() {
        assertThat(client.search(" ", 0, 10)).isEqualTo(DistributorSearchPage.empty());
        assertThat(client.search("x", 0, 0)).isEqualTo(DistributorSearchPage.empty());
        server.verify();
    }

    @Test
    void http429IsRateLimited() {
        server.expect(requestTo(KEYWORD_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertKind(() -> client.search("x", 0, 10), Kind.RATE_LIMITED);
    }

    @Test
    void tooManyRequestsErrorIsRateLimited() {
        server.expect(requestTo(KEYWORD_URL)).andRespond(withSuccess(TOO_MANY_REQUESTS_BODY, MediaType.APPLICATION_JSON));

        assertKind(() -> client.search("x", 0, 10), Kind.RATE_LIMITED);
    }

    // ---- rate limiting (DESIGN.md 3.6) ------------------------------------------------------------------------------

    @Test
    void http429ThenSuccessWaitsRetryAfterAndReportsTheWait() {
        server.expect(requestTo(KEYWORD_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "3"));
        server.expect(requestTo(KEYWORD_URL))
                .andRespond(withSuccess(MouserFixtures.text(MouserFixtures.KEYWORD), MediaType.APPLICATION_JSON));
        Deadline deadline = time.deadline(Duration.ofMinutes(2));

        DistributorSearchPage page = client.search("10uF X7R 0805", 0, 5, deadline);

        server.verify();
        assertThat(page.parts()).hasSize(5);
        assertThat(time.sleeps()).containsExactly(Duration.ofSeconds(3));
        assertThat(deadline.rateLimitWaitedMillis()).isEqualTo(3_000);
    }

    @Test
    void http429WithoutRetryAfterBacksOff() {
        server.expect(requestTo(KEYWORD_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(KEYWORD_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(KEYWORD_URL))
                .andRespond(withSuccess(MouserFixtures.text(MouserFixtures.KEYWORD), MediaType.APPLICATION_JSON));

        assertThat(client.search("x", 0, 5, time.deadline(Duration.ofMinutes(2))).parts()).isNotEmpty();

        server.verify();
        assertThat(time.sleeps()).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4));
    }

    @Test
    void retryAfterBeyondTheDeadlineIsRateLimitedAtOnceAndCoolsDownLaterCalls() {
        server.expect(requestTo(KEYWORD_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "300"));

        DistributorException e = assertKind(() -> client.search("x", 0, 10, time.deadline(Duration.ofMinutes(2))),
                Kind.RATE_LIMITED);

        server.verify();
        assertThat(time.sleeps()).isEmpty();
        assertThat(e.getMessage()).contains("next retry in 300 s would exceed the request deadline");
        assertNoKey(e);
        // the shared cool-down keeps a concurrent/next request from calling Mouser at all
        DistributorException next = assertKind(() -> client.getPart("603-CC0805MKX77BB106",
                time.deadline(Duration.ofMinutes(2))), Kind.RATE_LIMITED);
        assertThat(next.getMessage()).contains("cooling down");
        server.verify();
    }

    @Test
    void inBodyTooManyRequestsThenSuccessIsRetried() {
        server.expect(requestTo(KEYWORD_URL)).andRespond(withSuccess(TOO_MANY_REQUESTS_BODY, MediaType.APPLICATION_JSON));
        server.expect(requestTo(KEYWORD_URL))
                .andRespond(withSuccess(MouserFixtures.text(MouserFixtures.KEYWORD), MediaType.APPLICATION_JSON));
        Deadline deadline = time.deadline(Duration.ofMinutes(2));

        assertThat(client.search("x", 0, 5, deadline).totalResults()).isEqualTo(113);

        server.verify();
        assertThat(time.sleeps()).containsExactly(Duration.ofSeconds(2));
        assertThat(deadline.rateLimitWaitedMillis()).isEqualTo(2_000);
    }

    @Test
    void serviceUnavailableIsRetriedOnlyWithRetryAfter() {
        server.expect(requestTo(PART_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "5"));
        server.expect(requestTo(PART_URL))
                .andRespond(withSuccess(MouserFixtures.text(MouserFixtures.PART_NUMBER), MediaType.APPLICATION_JSON));

        assertThat(client.getPart("603-CC0805MKX77BB106", time.deadline(Duration.ofMinutes(2)))).isPresent();
        server.verify();
        assertThat(time.sleeps()).containsExactly(Duration.ofSeconds(5));

        MouserClient other = client(50);
        server.expect(requestTo(PART_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertKind(() -> other.getPart("603-CC0805MKX77BB106", time.deadline(Duration.ofMinutes(2))), Kind.UNAVAILABLE);
        server.verify();
    }

    @Test
    void withoutADeadlineRateLimitsFailFast() {
        server.expect(requestTo(KEYWORD_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "1"));

        assertKind(() -> client.search("x", 0, 10), Kind.RATE_LIMITED);
        assertThat(time.sleeps()).isEmpty();
    }

    @Test
    void errorListIsBadResponseWithTheMessage() {
        server.expect(requestTo(KEYWORD_URL))
                .andRespond(withSuccess(MouserFixtures.text(MouserFixtures.ERROR_INVALID_KEY), MediaType.APPLICATION_JSON));

        DistributorException e = assertKind(() -> client.search("x", 0, 10), Kind.BAD_RESPONSE);
        assertThat(e.getMessage()).contains("Invalid unique identifier.").contains("API Key");
    }

    @Test
    void errorListWithHttp400IsBadResponse() {
        server.expect(requestTo(KEYWORD_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"Errors\":[{\"Id\":0,\"Code\":\"InvalidValue\",\"Message\":\"records must be <= 50\"}]}"));

        DistributorException e = assertKind(() -> client.search("x", 0, 10), Kind.BAD_RESPONSE);
        assertThat(e.getMessage()).contains("records must be <= 50");
    }

    @Test
    void unreadableBodyIsBadResponse() {
        server.expect(requestTo(KEYWORD_URL)).andRespond(withSuccess("<html>oops</html>", MediaType.TEXT_HTML));

        assertKind(() -> client.search("x", 0, 10), Kind.BAD_RESPONSE);
    }

    @Test
    void missingSearchResultsIsBadResponse() {
        server.expect(requestTo(KEYWORD_URL)).andRespond(withSuccess("{\"Errors\":[]}", MediaType.APPLICATION_JSON));

        assertKind(() -> client.search("x", 0, 10), Kind.BAD_RESPONSE);
    }

    @Test
    void serverErrorIsUnavailable() {
        server.expect(requestTo(KEYWORD_URL)).andRespond(withServerError());

        assertKind(() -> client.search("x", 0, 10), Kind.UNAVAILABLE);
    }

    @Test
    void ioErrorIsUnavailableAndDoesNotLeakTheKey() {
        server.expect(requestTo(KEYWORD_URL)).andRespond(withException(new IOException("connection reset")));

        DistributorException e = assertKind(() -> client.search("x", 0, 10), Kind.UNAVAILABLE);
        assertNoKey(e);
    }

    @Test
    void readTimeoutIsTimeout() {
        server.expect(requestTo(KEYWORD_URL)).andRespond(withException(new HttpTimeoutException("request timed out")));

        assertNoKey(assertKind(() -> client.search("x", 0, 10), Kind.TIMEOUT));
    }

    @Test
    void connectTimeoutIsTimeout() {
        server.expect(requestTo(PART_URL)).andRespond(withException(new HttpConnectTimeoutException("connect timed out")));

        assertKind(() -> client.getPart("603-CC0805MKX77BB106"), Kind.TIMEOUT);
    }

    @Test
    void getPartUsesTheExactPartNumberSearch() {
        server.expect(requestTo(PART_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"SearchByPartRequest":{"mouserPartNumber":"603-CC0805MKX77BB106","partSearchOptions":"Exact"}}
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess(MouserFixtures.text(MouserFixtures.PART_NUMBER), MediaType.APPLICATION_JSON));

        Optional<Part> part = client.getPart("603-CC0805MKX77BB106");

        server.verify();
        assertThat(part).hasValueSatisfying(p -> {
            assertThat(p.distributorPartNumber()).isEqualTo("603-CC0805MKX77BB106");
            assertThat(p.manufacturerPartNumber()).isEqualTo("CC0805MKX7R7BB106");
            assertThat(p.stock()).isEqualTo(76689);
        });
    }

    @Test
    void getPartFallsBackToTheManufacturerPartNumber() {
        server.expect(requestTo(PART_URL))
                .andRespond(withSuccess(MouserFixtures.text(MouserFixtures.PART_NUMBER), MediaType.APPLICATION_JSON));

        assertThat(client.getPart("cc0805mkx7r7bb106")).map(Part::distributorPartNumber).contains("603-CC0805MKX77BB106");
    }

    @Test
    void getPartIgnoresNonMatchingResults() {
        server.expect(requestTo(PART_URL))
                .andRespond(withSuccess(MouserFixtures.text(MouserFixtures.PART_NUMBER), MediaType.APPLICATION_JSON));

        assertThat(client.getPart("603-OTHER")).isEmpty();
    }

    @Test
    void getPartOutOfStockIsEmpty() {
        String body = MouserFixtures.text(MouserFixtures.PART_NUMBER)
                .replace("\"AvailabilityInStock\": \"76689\"", "\"AvailabilityInStock\": \"0\"");
        server.expect(requestTo(PART_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThat(client.getPart("603-CC0805MKX77BB106")).isEmpty();
    }

    @Test
    void getPartRateLimited() {
        server.expect(requestTo(PART_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertKind(() -> client.getPart("603-CC0805MKX77BB106"), Kind.RATE_LIMITED);
    }

    @Test
    void notConfiguredWithoutApiKey() {
        for (String key : new String[] {null, "", "   "}) {
            MouserClient unconfigured = new MouserClient(
                    new KinaProperties(null, null, null, null, null, null, null,
                            new KinaProperties.Distributors(new KinaProperties.Mouser(key, BASE, 50, 1), null), null),
                    RestClient.builder());

            assertThat(unconfigured.isConfigured()).isFalse();
            assertThat(unconfigured.distributor()).isEqualTo(Distributor.MOUSER);
            assertKind(() -> unconfigured.search("x", 0, 10), Kind.NOT_CONFIGURED);
            assertKind(() -> unconfigured.getPart("603-CC0805MKX77BB106"), Kind.NOT_CONFIGURED);
        }
    }

    @Test
    void configuredWithApiKey() {
        MouserClient configured = new MouserClient(
                new KinaProperties(null, null, null, null, null, null, null,
                        new KinaProperties.Distributors(new KinaProperties.Mouser("k", BASE, 50, 1), null), null),
                RestClient.builder());

        assertThat(configured.isConfigured()).isTrue();
    }

    @Test
    void masksTheApiKey() {
        assertThat(MouserApi.mask("I/O error on POST request for \"https://x/search/keyword?apiKey=abc-123&y=1\": boom"))
                .isEqualTo("I/O error on POST request for \"https://x/search/keyword?apiKey=***&y=1\": boom");
    }

    private static DistributorException assertKind(Runnable call, Kind kind) {
        DistributorException[] caught = new DistributorException[1];
        assertThatThrownBy(call::run).isInstanceOfSatisfying(DistributorException.class, e -> {
            assertThat(e.kind()).isEqualTo(kind);
            assertThat(e.distributor()).isEqualTo(Distributor.MOUSER);
            caught[0] = e;
        });
        return caught[0];
    }

    private static void assertNoKey(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            assertThat(String.valueOf(t.getMessage())).doesNotContain(API_KEY);
        }
    }
}
