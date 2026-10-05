package ro.alacrity.kina.oauth;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import ro.alacrity.kina.TestcontainersConfiguration;
import ro.alacrity.kina.oauth.OAuthClientRepository.OAuthClient;
import ro.alacrity.kina.oauth.RefreshTokenRepository.RefreshToken;
import ro.alacrity.kina.security.AccessTokenService;
import ro.alacrity.kina.security.SecureTokens;
import ro.alacrity.kina.security.TestEchoController;
import ro.alacrity.kina.security.UserRepository;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static ro.alacrity.kina.oauth.OAuthFlowTest.json;
import static ro.alacrity.kina.oauth.OAuthFlowTest.strings;

/**
 * Development mode with the hardening switches turned to testable values: Client ID Metadata Documents served by an
 * in-process HTTP server (its host is the only trusted one; plain http is allowed for this test resolver only), a
 * registration rate limit of 3 per minute, the token UI disabled, blocked users and the cleanup of unused clients.
 */
@SpringBootTest(properties = {
        "kina.security.mode=dev",
        "kina.oauth.register-rate-limit-per-minute=3",
        "kina.tokens.ui-enabled=false"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestEchoController.Endpoint.class, OAuthHardeningTest.Documents.class})
class OAuthHardeningTest {

    static final String CLAUDE_CALLBACK = "https://claude.ai/api/mcp/auth_callback";

    static HttpServer documentServer;
    static String base;
    static final Map<String, String> DOCUMENTS = new ConcurrentHashMap<>();
    static final Map<String, AtomicInteger> FETCHES = new ConcurrentHashMap<>();

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    AccessTokenService accessTokens;

    @Autowired
    RefreshTokenRepository refreshTokens;

    @Autowired
    OAuthClientRepository clients;

    @Autowired
    OAuthClientMaintenance maintenance;

    @Autowired
    JdbcClient jdbc;

    /** Test resolver: trusts only the in-process document server and accepts its http URLs. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Documents {

        @Bean
        @Primary
        ClientMetadataDocumentResolver testClientMetadataDocumentResolver(OAuthClientRepository clients) {
            return new ClientMetadataDocumentResolver(clients, List.of("127.0.0.1"), Duration.ofHours(1),
                    ClientMetadataDocumentResolver.defaultHttpClient(), true, Clock.systemUTC());
        }
    }

    @BeforeAll
    static void startDocumentServer() throws IOException {
        documentServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        documentServer.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            FETCHES.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
            String body = DOCUMENTS.get(path);
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : bytes.length);
            if (body != null) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        documentServer.start();
        base = "http://127.0.0.1:" + documentServer.getAddress().getPort();
        DOCUMENTS.put("/claude.json", document("/claude.json", "Claude", CLAUDE_CALLBACK));
        DOCUMENTS.put("/cli.json", document("/cli.json", "Claude Code", "http://localhost/callback"));
        DOCUMENTS.put("/mismatch.json", """
                {"client_id":"https://claude.ai/someone-else","redirect_uris":["https://claude.ai/api/mcp/auth_callback"]}""");
    }

    @AfterAll
    static void stopDocumentServer() {
        documentServer.stop(0);
    }

    static String document(String path, String name, String redirectUri) {
        return """
                {"client_id":"%s","client_name":"%s","redirect_uris":["%s"],
                 "grant_types":["authorization_code","refresh_token"],"response_types":["code"],
                 "token_endpoint_auth_method":"none"}""".formatted(base + path, name, redirectUri);
    }

    // ---- Client ID Metadata Documents ----------------------------------------------------------------------------

    @Test
    void metadataAdvertisesClientIdMetadataDocuments() throws Exception {
        JsonNode doc = json(mvc.perform(get("/.well-known/oauth-authorization-server")).andReturn().getResponse(), 200);
        assertThat(doc.get("client_id_metadata_document_supported").asBoolean()).isTrue();
        assertThat(strings(doc.get("token_endpoint_auth_methods_supported"))).contains("none");
        assertThat(strings(doc.get("scopes_supported"))).containsExactly("kina");
    }

    @Test
    void trustedMetadataDocumentClientSkipsConsentAndGetsOneHourTokens() throws Exception {
        String clientId = base + "/claude.json";
        String verifier = SecureTokens.randomBase64Url(48);
        MockHttpServletResponse authorize = mvc.perform(get("/oauth/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", CLAUDE_CALLBACK)
                .queryParam("code_challenge", Pkce.s256(verifier))
                .queryParam("code_challenge_method", "S256")
                .queryParam("state", "s-1")
                .queryParam("resource", "http://localhost/mcp")).andReturn().getResponse();
        assertThat(authorize.getStatus()).as(authorize.getContentAsString()).isEqualTo(302);
        UriComponents location = UriComponentsBuilder.fromUriString(authorize.getRedirectedUrl()).build();
        assertThat(authorize.getRedirectedUrl()).startsWith(CLAUDE_CALLBACK + "?code=");
        assertThat(location.getQueryParams().getFirst("state")).isEqualTo("s-1");

        JsonNode tokens = token(Map.of("grant_type", "authorization_code", "code",
                location.getQueryParams().getFirst("code"), "redirect_uri", CLAUDE_CALLBACK, "client_id", clientId,
                "code_verifier", verifier), 200);
        assertThat(tokens.get("expires_in").asLong()).isEqualTo(3600L);
        String access = tokens.get("access_token").asString();
        assertThat(mvc.perform(get("/api/v1/test-echo").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(accessTokens.find(access).orElseThrow().name()).isEqualTo("MCP: Claude");

        JsonNode refreshed = token(Map.of("grant_type", "refresh_token", "refresh_token",
                tokens.get("refresh_token").asString(), "client_id", clientId), 200);
        assertThat(refreshed.get("expires_in").asLong()).isEqualTo(3600L);

        OAuthClient stored = clients.findById(clientId).orElseThrow();
        assertThat(stored.metadataUrl()).isEqualTo(clientId);
        assertThat(stored.redirectUris()).containsExactly(CLAUDE_CALLBACK);
        assertThat(jdbc.sql("SELECT last_used_at FROM oauth_clients WHERE client_id = ?").param(clientId)
                .query(java.sql.Timestamp.class).single()).isNotNull();
        assertThat(FETCHES.get("/claude.json").get()).as("document cached").isEqualTo(1);
    }

    @Test
    void loopbackRedirectOfATrustedClientStillShowsConsent() throws Exception {
        MockHttpServletResponse consent = mvc.perform(get("/oauth/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", base + "/cli.json")
                .queryParam("redirect_uri", "http://localhost:47011/callback")
                .queryParam("code_challenge", Pkce.s256(SecureTokens.randomBase64Url(48)))
                .queryParam("code_challenge_method", "S256")).andReturn().getResponse();
        assertThat(consent.getStatus()).isEqualTo(200);
        assertThat(consent.getContentAsString()).contains("Authorize").contains("Claude Code")
                .contains("value=\"approve\"").contains("a program on this computer").contains("1 hour");
    }

    @Test
    void untrustedOrInvalidDocumentsAreErrorPagesNeverRedirects() throws Exception {
        for (String clientId : List.of("https://evil.example.com/client.json", base + "/mismatch.json",
                base + "/missing.json")) {
            MockHttpServletResponse response = mvc.perform(get("/oauth/authorize")
                    .queryParam("response_type", "code")
                    .queryParam("client_id", clientId)
                    .queryParam("redirect_uri", CLAUDE_CALLBACK)
                    .queryParam("code_challenge", Pkce.s256(SecureTokens.randomBase64Url(48)))
                    .queryParam("code_challenge_method", "S256")).andReturn().getResponse();
            assertThat(response.getStatus()).as(clientId).isEqualTo(400);
            assertThat(response.getRedirectedUrl()).as(clientId).isNull();
        }
        MockHttpServletResponse untrusted = mvc.perform(get("/oauth/authorize")
                .queryParam("client_id", "https://evil.example.com/client.json")).andReturn().getResponse();
        assertThat(untrusted.getContentAsString()).contains("not trusted");
        assertThat(FETCHES).doesNotContainKey("/client.json");

        // Redirect URI not in the document: error page.
        MockHttpServletResponse wrongRedirect = mvc.perform(get("/oauth/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", base + "/claude.json")
                .queryParam("redirect_uri", "https://claude.ai/api/mcp/other")).andReturn().getResponse();
        assertThat(wrongRedirect.getStatus()).isEqualTo(400);
        assertThat(wrongRedirect.getRedirectedUrl()).isNull();

        assertThat(token(Map.of("grant_type", "authorization_code", "code", "x", "client_id",
                "https://evil.example.com/client.json", "code_verifier", SecureTokens.randomBase64Url(48)), 401)
                .get("error").asString()).isEqualTo("invalid_client");
    }

    // ---- registration rate limit ---------------------------------------------------------------------------------

    @Test
    void registrationIsRateLimitedPerClientIp() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(register("198.51.100.7").getStatus()).isEqualTo(201);
        }
        MockHttpServletResponse limited = register("198.51.100.7");
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(limited.getHeader(HttpHeaders.RETRY_AFTER))).isBetween(1L, 60L);
        assertThat(json(limited, 429).get("error").asString()).isEqualTo("too_many_requests");
        assertThat(register("198.51.100.8").getStatus()).as("another client IP").isEqualTo(201);
    }

    MockHttpServletResponse register(String clientIp) throws Exception {
        return mvc.perform(post("/oauth/register")
                .header("X-Forwarded-For", clientIp)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"redirect_uris\":[\"" + CLAUDE_CALLBACK + "\"],\"client_name\":\"Claude\"}"))
                .andReturn().getResponse();
    }

    // ---- token UI switch -----------------------------------------------------------------------------------------

    @Test
    void disabledTokenUiExplainsTheConnectorAndRefusesCreation() throws Exception {
        MockHttpServletResponse index = mvc.perform(get("/")).andReturn().getResponse();
        assertThat(index.getStatus()).isEqualTo(200);
        assertThat(index.getContentAsString()).contains("Connect KINA through Claude")
                .contains("http://localhost/mcp").contains("disabled").doesNotContain("Create token");
        assertThat(mvc.perform(post("/tokens").with(csrf()).formField("name", "script")).andReturn().getResponse()
                .getStatus()).isEqualTo(404);
    }

    // ---- blocked users -------------------------------------------------------------------------------------------

    @Test
    void blockedUsersTokensAreRejectedAndRevokeAllIncludesRefreshTokens() throws Exception {
        UUID userId = users.upsert("https://idp.example.com", "blocked-" + UUID.randomUUID(), null, "Blocked",
                null).id();
        String token = accessTokens.create(userId, "script", null, null).plaintext();
        assertThat(echo(token).getStatus()).isEqualTo(200);

        users.markRevoked(userId, Instant.now());
        MockHttpServletResponse rejected = echo(token);
        assertThat(rejected.getStatus()).isEqualTo(401);
        assertThat(rejected.getHeader(HttpHeaders.WWW_AUTHENTICATE)).contains("error=\"invalid_token\"");

        String clientId = registerClientDirectly(Instant.now());
        var oauthToken = accessTokens.create(userId, "MCP: x", "kina", clientId);
        String refreshHash = SecureTokens.sha256Hex("kina_rt_" + UUID.randomUUID());
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        refreshTokens.insert(new RefreshToken(refreshHash, clientId, userId, oauthToken.token().id(), "kina", now,
                now.plus(Duration.ofDays(30)), null));
        assertThat(accessTokens.revokeAllForUser(userId)).isEqualTo(2);
        assertThat(refreshTokens.findByHash(refreshHash).orElseThrow().revokedAt()).isNotNull();
        assertThat(accessTokens.validate(oauthToken.plaintext())).isEmpty();
    }

    MockHttpServletResponse echo(String token) throws Exception {
        return mvc.perform(get("/api/v1/test-echo").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse();
    }

    // ---- cleanup of unused registered clients --------------------------------------------------------------------

    @Test
    void cleanupDeletesOnlyOldUnusedRegisteredClients() {
        Instant old = Instant.now().minus(Duration.ofDays(120)).truncatedTo(ChronoUnit.MICROS);
        String unused = registerClientDirectly(old);
        String withLiveToken = registerClientDirectly(old);
        String recent = registerClientDirectly(Instant.now().truncatedTo(ChronoUnit.MICROS));
        String metadataClient = "https://claude.ai/cleanup-" + UUID.randomUUID();
        clients.upsertMetadataDocumentClient(new OAuthClient(metadataClient, null, "Doc", List.of(CLAUDE_CALLBACK),
                List.of("authorization_code"), List.of("code"), "none", null, Map.of(), old, metadataClient));
        accessTokens.create(users.devAdmin().id(), "MCP: live", "kina", withLiveToken);

        assertThat(maintenance.cleanup()).isGreaterThanOrEqualTo(1);

        assertThat(clients.findById(unused)).isEmpty();
        assertThat(clients.findById(withLiveToken)).isPresent();
        assertThat(clients.findById(recent)).isPresent();
        assertThat(clients.findById(metadataClient)).isPresent();
    }

    String registerClientDirectly(Instant createdAt) {
        String clientId = SecureTokens.randomBase64Url(24);
        clients.insert(new OAuthClient(clientId, null, "Direct", List.of(CLAUDE_CALLBACK),
                List.of("authorization_code", "refresh_token"), List.of("code"), "none", null, Map.of(), createdAt));
        return clientId;
    }

    JsonNode token(Map<String, String> form, int expectedStatus) throws Exception {
        var request = post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED);
        form.forEach(request::formField);
        return json(mvc.perform(request).andReturn().getResponse(), expectedStatus);
    }
}
