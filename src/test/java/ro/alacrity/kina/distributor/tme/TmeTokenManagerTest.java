package ro.alacrity.kina.distributor.tme;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.distributor.DistributorException.Kind;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static ro.alacrity.kina.distributor.tme.TmeTestSupport.BASE;
import static ro.alacrity.kina.distributor.tme.TmeTestSupport.expectToken;
import static ro.alacrity.kina.distributor.tme.TmeTestSupport.fixture;
import static ro.alacrity.kina.distributor.tme.TmeTestSupport.get;

class TmeTokenManagerTest {

    private MockRestServiceServer server;
    private TmeTestSupport.MutableClock clock;
    private TmeTokenManager tokens;
    private TmeApi api;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        clock = new TmeTestSupport.MutableClock(Instant.parse("2026-10-05T10:00:00Z"));
        tokens = new TmeTokenManager(restClient, BASE + "/", "my-token", "my-secret", clock);
        api = new TmeApi(restClient, tokens, BASE, "en");
    }

    @Test
    void requestsTokenWithBasicAuthAndClientCredentialsGrant() {
        String basic = "Basic " + Base64.getEncoder().encodeToString("my-token:my-secret".getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo(BASE + "/auth/token"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, basic))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE))
                .andExpect(content().string("grant_type=client_credentials"))
                .andRespond(withSuccess(fixture("token.json"), MediaType.APPLICATION_JSON));

        assertThat(tokens.accessToken()).isEqualTo("REDACTED");
        server.verify();
    }

    @Test
    void cachesTokenUntilThirtySecondsBeforeExpiry() {
        expectToken(server, "first");
        assertThat(tokens.accessToken()).isEqualTo("first");
        clock.advance(Duration.ofSeconds(269));
        assertThat(tokens.accessToken()).isEqualTo("first");
        server.verify();

        server.reset();
        expectToken(server, "second");
        clock.advance(Duration.ofSeconds(2)); // 271 s elapsed: 29 s left -> refresh early
        assertThat(tokens.accessToken()).isEqualTo("second");
        assertThat(tokens.accessToken()).isEqualTo("second");
        server.verify();
    }

    @Test
    void invalidateOnlyDropsTheRejectedToken() {
        expectToken(server, "first");
        assertThat(tokens.accessToken()).isEqualTo("first");
        tokens.invalidate("some-other-token");
        assertThat(tokens.accessToken()).isEqualTo("first");
        server.verify();

        server.reset();
        expectToken(server, "second");
        tokens.invalidate("first");
        assertThat(tokens.accessToken()).isEqualTo("second");
        server.verify();
    }

    @Test
    void rejectedCredentialsAreUnavailable() {
        server.expect(requestTo(BASE + "/auth/token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"E_AUTH_INVALID_CREDENTIALS\",\"message\":\"nope\"}"));

        assertThatThrownBy(() -> tokens.accessToken())
                .isInstanceOfSatisfying(DistributorException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE);
                    assertThat(e.getMessage()).doesNotContain("my-secret");
                });
    }

    @Test
    void retriesOnceWithNewTokenAfter401() {
        expectToken(server, "stale");
        server.expect(get("/products"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer stale"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        expectToken(server, "fresh");
        server.expect(get("/products"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer fresh"))
                .andExpect(header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andRespond(withSuccess(fixture("products.json"), MediaType.APPLICATION_JSON));

        List<TmeResponses.Product> products = api.products(List.of("CL21B106KPQNNNE"), "RO");

        assertThat(products).extracting(TmeResponses.Product::symbol).containsExactly("CL21B106KPQNNNE");
        server.verify();
    }

    @Test
    void retriesAfterTmeAuthErrorCodes() {
        // Live behaviour: an invalid token is HTTP 400 E_AUTH_TOKEN_IS_INVALID, an expired one HTTP 403 E_AUTH_TOKEN_EXPIRED.
        expectToken(server, "stale");
        server.expect(get("/products"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body(fixture("error-token-expired.json")));
        expectToken(server, "fresh");
        server.expect(get("/products"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer fresh"))
                .andRespond(withSuccess(fixture("products.json"), MediaType.APPLICATION_JSON));

        assertThat(api.products(List.of("CL21B106KPQNNNE"), "RO")).hasSize(1);
        server.verify();
    }

    @Test
    void secondAuthFailureIsUnavailable() {
        expectToken(server, "stale");
        server.expect(get("/products"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body(fixture("error-token-invalid.json")));
        expectToken(server, "also-bad");
        server.expect(get("/products"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body(fixture("error-token-invalid.json")));

        assertThatThrownBy(() -> api.products(List.of("X"), "RO"))
                .isInstanceOfSatisfying(DistributorException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE);
                    assertThat(e.getMessage()).contains("E_AUTH_TOKEN_IS_INVALID");
                });
        server.verify();
    }

    @Test
    void tokenIsSharedAcrossCalls() {
        expectToken(server, "only");
        server.expect(get("/products")).andRespond(withSuccess(fixture("products-unknown.json"), MediaType.APPLICATION_JSON));
        server.expect(get("/products")).andRespond(withSuccess(fixture("products-unknown.json"), MediaType.APPLICATION_JSON));

        assertThat(api.products(List.of("A"), "RO")).isEmpty();
        assertThat(api.products(List.of("B"), "RO")).isEmpty();
        server.verify();
    }
}
