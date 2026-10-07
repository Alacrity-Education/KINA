package ro.alacrity.kina.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import ro.alacrity.kina.TestWiring;
import ro.alacrity.kina.TestcontainersConfiguration;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Production mode with a fake, unreachable OIDC issuer: the context must start without contacting it (discovery is
 * lazy), machine endpoints accept bearer tokens only and web pages redirect to the OIDC login.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestEchoController.Endpoint.class})
@TestPropertySource(properties = {
        "kina.security.mode=prod",
        "kina.security.oidc.issuer-uri=http://127.0.0.1:9/fake-issuer",
        "kina.security.oidc.client-id=kina-test",
        "kina.security.oidc.client-secret=not-a-real-secret"})
class ProdModeSecurityTest {

    static final String EXPECTED_CHALLENGE =
            "Bearer realm=\"kina\", resource_metadata=\"http://localhost/.well-known/oauth-protected-resource\"";

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokenService tokens;

    @Autowired
    UserRepository users;

    @Autowired
    ClientRegistrationRepository clientRegistrations;

    @Autowired
    MembershipVerifier membership;

    @Test
    void unauthenticatedMcpRequestGetsBearerChallenge() throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo(EXPECTED_CHALLENGE);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("\"status\":401");
    }

    @Test
    void invalidTokenAddsErrorParameter() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/api/v1/test-echo")
                .header(HttpHeaders.AUTHORIZATION, "Bearer kina_" + "y".repeat(43))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo(EXPECTED_CHALLENGE + ", error=\"invalid_token\"");
    }

    @Test
    void emptyBearerTokenIsAnInvalidToken() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/api/v1/test-echo")
                .header(HttpHeaders.AUTHORIZATION, "Bearer")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo(EXPECTED_CHALLENGE + ", error=\"invalid_token\"");
    }

    @Test
    void basicAuthIsNotAcceptedOnApi() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/api/v1/test-echo")
                .header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo(EXPECTED_CHALLENGE);
    }

    @Test
    void validTokenAuthenticates() throws Exception {
        UUID userId = users.upsert("https://idp.example.com", "sub-" + UUID.randomUUID(), "a@example.com", "Alice",
                null).id();
        String token = tokens.create(userId, "test", null, null).plaintext();

        MockHttpServletResponse response = mvc.perform(get("/api/v1/test-echo")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains(userId.toString()).contains("Alice");
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();

        MockHttpServletResponse mcp = mvc.perform(post("/mcp")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")).andReturn().getResponse();
        assertThat(mcp.getStatus()).isEqualTo(200);
    }

    @Test
    void webPagesRedirectToOidcLogin() throws Exception {
        for (String path : new String[]{"/", "/oauth/authorize?client_id=x"}) {
            MockHttpServletResponse response = mvc.perform(get(path)).andReturn().getResponse();
            assertThat(response.getStatus()).as(path).isEqualTo(302);
            assertThat(response.getRedirectedUrl()).as(path).endsWith("/oauth2/authorization/oidc");
        }
        assertThat(((LazyOidcClientRegistrationRepository) clientRegistrations).discoveryAttempted()).isFalse();
    }

    @Test
    void publicEndpointsStayPublic() throws Exception {
        assertThat(mvc.perform(get("/.well-known/oauth-protected-resource")).andReturn().getResponse().getStatus())
                .isEqualTo(200);
        assertThat(mvc.perform(get("/.well-known/oauth-authorization-server")).andReturn().getResponse().getStatus())
                .isEqualTo(200);
        // actuator lives on the management port (DESIGN.md 3.7, PrometheusEndpointTest); the main port has none
        assertThat(mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(post("/oauth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"redirect_uris\":[\"https://claude.ai/api/mcp/auth_callback\"]}"))
                .andReturn().getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void unreachableIssuerFailsLoginWithoutBreakingTheApp() throws Exception {
        LazyOidcClientRegistrationRepository repository = TestWiring.wire(new LazyOidcClientRegistrationRepository(),
                "properties", TestWiring.properties("kina.security.oidc.issuer-uri", "http://127.0.0.1:9/fake-issuer",
                        "kina.security.oidc.client-id", "id", "kina.security.oidc.client-secret", "secret"),
                "membership", membership);
        assertThat(repository.findByRegistrationId("oidc")).isNull();
        assertThat(repository.discoveryAttempted()).isTrue();
        assertThat(repository.findByRegistrationId("other")).isNull();
    }
}
