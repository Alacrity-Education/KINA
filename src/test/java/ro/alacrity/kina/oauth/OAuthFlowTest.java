package ro.alacrity.kina.oauth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import ro.alacrity.kina.security.DevModeIntegrationTest;
import ro.alacrity.kina.security.SecureTokens;
import ro.alacrity.kina.security.UserRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@DevModeIntegrationTest
class OAuthFlowTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String REDIRECT_URI = "http://localhost:33418/callback";

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    // ---- metadata ------------------------------------------------------------------------------------------------

    @Test
    void protectedResourceMetadata() throws Exception {
        for (String path : List.of("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp")) {
            JsonNode doc = json(mvc.perform(get(path)).andReturn().getResponse(), 200);
            assertThat(doc.get("resource").asString()).isEqualTo("http://localhost/mcp");
            assertThat(doc.get("authorization_servers").get(0).asString()).isEqualTo("http://localhost");
            assertThat(doc.get("bearer_methods_supported").get(0).asString()).isEqualTo("header");
            assertThat(doc.get("scopes_supported").get(0).asString()).isEqualTo("kina");
            assertThat(doc.get("resource_name").asString()).isEqualTo("KINA");
        }
    }

    @Test
    void authorizationServerMetadata() throws Exception {
        for (String path : List.of("/.well-known/oauth-authorization-server",
                "/.well-known/oauth-authorization-server/mcp", "/.well-known/openid-configuration")) {
            JsonNode doc = json(mvc.perform(get(path)).andReturn().getResponse(), 200);
            assertThat(doc.get("issuer").asString()).isEqualTo("http://localhost");
            assertThat(doc.get("authorization_endpoint").asString()).isEqualTo("http://localhost/oauth/authorize");
            assertThat(doc.get("token_endpoint").asString()).isEqualTo("http://localhost/oauth/token");
            assertThat(doc.get("registration_endpoint").asString()).isEqualTo("http://localhost/oauth/register");
            assertThat(doc.get("revocation_endpoint").asString()).isEqualTo("http://localhost/oauth/revoke");
            assertThat(strings(doc.get("response_types_supported"))).containsExactly("code");
            assertThat(strings(doc.get("response_modes_supported"))).containsExactly("query");
            assertThat(strings(doc.get("grant_types_supported")))
                    .containsExactly("authorization_code", "refresh_token");
            assertThat(strings(doc.get("code_challenge_methods_supported"))).containsExactly("S256");
            assertThat(strings(doc.get("token_endpoint_auth_methods_supported")))
                    .containsExactly("none", "client_secret_basic", "client_secret_post");
            assertThat(strings(doc.get("scopes_supported"))).containsExactly("kina");
        }
    }

    @Test
    void metadataUsesForwardedHeaders() throws Exception {
        JsonNode doc = json(mvc.perform(get("/.well-known/oauth-authorization-server")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "kina.example.com")
                .header("X-Forwarded-Port", "443")
                .header("X-Forwarded-Prefix", "/parts")).andReturn().getResponse(), 200);
        assertThat(doc.get("issuer").asString()).isEqualTo("https://kina.example.com/parts");
        assertThat(doc.get("token_endpoint").asString()).isEqualTo("https://kina.example.com/parts/oauth/token");

        JsonNode resource = json(mvc.perform(get("/.well-known/oauth-protected-resource")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "kina.example.com")).andReturn().getResponse(), 200);
        assertThat(resource.get("resource").asString()).isEqualTo("https://kina.example.com/mcp");

        MockHttpServletResponse unauthorized = mvc.perform(get("/api/v1/test-echo")
                .header(HttpHeaders.AUTHORIZATION, "Bearer kina_invalid")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "kina.example.com")).andReturn().getResponse();
        assertThat(unauthorized.getStatus()).isEqualTo(401);
        assertThat(unauthorized.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo(
                "Bearer realm=\"kina\", resource_metadata=\"https://kina.example.com/.well-known/oauth-protected-resource\", "
                        + "error=\"invalid_token\"");
    }

    @Test
    void corsPreflightOnPublicEndpoints() throws Exception {
        MockHttpServletResponse response = mvc.perform(options("/oauth/token")
                .header(HttpHeaders.ORIGIN, "https://claude.ai")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo("https://claude.ai");
    }

    // ---- registration --------------------------------------------------------------------------------------------

    @Test
    void registersPublicClient() throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/oauth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"redirect_uris":["https://claude.ai/api/mcp/auth_callback"],"client_name":"Claude",
                         "token_endpoint_auth_method":"none","grant_types":["authorization_code","refresh_token"],
                         "response_types":["code"],"scope":"kina","client_uri":"https://claude.ai"}""")).andReturn()
                .getResponse();
        JsonNode body = json(response, 201);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(body.get("client_id").asString()).matches("^[A-Za-z0-9_-]{32}$");
        assertThat(body.has("client_secret")).isFalse();
        assertThat(body.get("client_id_issued_at").asLong()).isPositive();
        assertThat(body.get("client_secret_expires_at").asInt()).isZero();
        assertThat(strings(body.get("redirect_uris"))).containsExactly("https://claude.ai/api/mcp/auth_callback");
        assertThat(body.get("client_name").asString()).isEqualTo("Claude");
        assertThat(body.get("token_endpoint_auth_method").asString()).isEqualTo("none");
        assertThat(body.get("client_uri").asString()).isEqualTo("https://claude.ai");
    }

    @Test
    void registersConfidentialClientWithDefaults() throws Exception {
        JsonNode body = json(mvc.perform(post("/oauth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"redirect_uris\":[\"myapp://cb\"],\"token_endpoint_auth_method\":\"client_secret_basic\"}"))
                .andReturn().getResponse(), 201);
        assertThat(body.get("client_secret").asString()).hasSize(43);
        assertThat(strings(body.get("grant_types"))).containsExactly("authorization_code", "refresh_token");
        assertThat(strings(body.get("response_types"))).containsExactly("code");
    }

    @Test
    void rejectsInvalidRedirectUris() throws Exception {
        for (String uris : List.of("[\"http://evil.example.com/cb\"]", "[\"https://x.example.com/cb#frag\"]",
                "[\"/relative\"]", "[\"javascript:alert(1)\"]", "[]")) {
            JsonNode body = json(mvc.perform(post("/oauth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"redirect_uris\":" + uris + "}")).andReturn().getResponse(), 400);
            assertThat(body.get("error").asString()).as(uris).isEqualTo("invalid_redirect_uri");
        }
        JsonNode missing = json(mvc.perform(post("/oauth/register")
                .contentType(MediaType.APPLICATION_JSON).content("{\"client_name\":\"x\"}")).andReturn().getResponse(), 400);
        assertThat(missing.get("error").asString()).isEqualTo("invalid_redirect_uri");
        JsonNode badMethod = json(mvc.perform(post("/oauth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"redirect_uris\":[\"http://127.0.0.1:9/cb\"],\"token_endpoint_auth_method\":\"private_key_jwt\"}"))
                .andReturn().getResponse(), 400);
        assertThat(badMethod.get("error").asString()).isEqualTo("invalid_client_metadata");
    }

    // ---- authorization code flow ---------------------------------------------------------------------------------

    @Test
    void fullAuthorizationCodeFlow() throws Exception {
        String clientId = registerPublicClient("Claude");
        String verifier = SecureTokens.randomBase64Url(48);

        MockHttpServletResponse consent = mvc.perform(get("/oauth/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("code_challenge", Pkce.s256(verifier))
                .queryParam("code_challenge_method", "S256")
                .queryParam("scope", "kina")
                .queryParam("state", "st&te 1")
                .queryParam("resource", "http://localhost/mcp")).andReturn().getResponse();
        assertThat(consent.getStatus()).isEqualTo(200);
        String page = consent.getContentAsString();
        assertThat(page).contains("Authorize").contains("Claude").contains("Development Admin")
                .contains("name=\"_csrf\"").contains("value=\"approve\"").contains("value=\"deny\"");

        String code = approve(clientId, verifier, "st&te 1");

        JsonNode tokens = token(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT_URI,
                "client_id", clientId, "code_verifier", verifier), 200);
        String accessToken = tokens.get("access_token").asString();
        assertThat(accessToken).matches("^kina_[A-Za-z0-9_-]{43}$");
        assertThat(tokens.get("token_type").asString()).isEqualTo("Bearer");
        assertThat(tokens.get("expires_in").asLong()).isBetween(30L * 86400 - 60, 30L * 86400);
        assertThat(tokens.get("refresh_token").asString()).startsWith("kina_rt_");
        assertThat(tokens.get("scope").asString()).isEqualTo("kina");

        JsonNode echo = json(mvc.perform(get("/api/v1/test-echo")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)).andReturn().getResponse(), 200);
        assertThat(echo.get("user_id").asString()).isEqualTo(users.devAdmin().id().toString());
        assertThat(echo.get("token_id").isNull()).isFalse();
        assertThat(strings(echo.get("authorities"))).containsExactly("ROLE_USER");

        // Code reuse -> invalid_grant
        JsonNode reuse = token(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT_URI,
                "client_id", clientId, "code_verifier", verifier), 400);
        assertThat(reuse.get("error").asString()).isEqualTo("invalid_grant");
    }

    @Test
    void wrongVerifierIsInvalidGrant() throws Exception {
        String clientId = registerPublicClient("Verifier test");
        String verifier = SecureTokens.randomBase64Url(48);
        String code = approve(clientId, verifier, null);

        JsonNode error = token(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT_URI,
                "client_id", clientId, "code_verifier", SecureTokens.randomBase64Url(48)), 400);
        assertThat(error.get("error").asString()).isEqualTo("invalid_grant");

        // The code was consumed by the failed attempt.
        JsonNode retry = token(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT_URI,
                "client_id", clientId, "code_verifier", verifier), 400);
        assertThat(retry.get("error").asString()).isEqualTo("invalid_grant");
    }

    @Test
    void tokenEndpointErrors() throws Exception {
        String clientId = registerPublicClient("Errors");
        assertThat(token(Map.of("grant_type", "password", "client_id", clientId), 400).get("error").asString())
                .isEqualTo("unsupported_grant_type");
        assertThat(token(Map.of("client_id", clientId), 400).get("error").asString()).isEqualTo("invalid_request");
        assertThat(token(Map.of("grant_type", "authorization_code", "code", "x", "client_id", "unknown"), 401)
                .get("error").asString()).isEqualTo("invalid_client");
        assertThat(token(Map.of("grant_type", "authorization_code", "client_id", clientId, "code_verifier",
                SecureTokens.randomBase64Url(48)), 400).get("error").asString()).isEqualTo("invalid_request");
        assertThat(token(Map.of("grant_type", "authorization_code", "code", "nope", "client_id", clientId,
                "code_verifier", SecureTokens.randomBase64Url(48)), 400).get("error").asString())
                .isEqualTo("invalid_grant");
    }

    @Test
    void confidentialClientMustAuthenticate() throws Exception {
        JsonNode registration = json(mvc.perform(post("/oauth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"redirect_uris\":[\"" + REDIRECT_URI + "\"],\"client_name\":\"Confidential\","
                        + "\"token_endpoint_auth_method\":\"client_secret_basic\"}")).andReturn().getResponse(), 201);
        String clientId = registration.get("client_id").asString();
        String secret = registration.get("client_secret").asString();
        String verifier = SecureTokens.randomBase64Url(48);
        String code = approve(clientId, verifier, null);

        MockHttpServletResponse unauthenticated = mvc.perform(post("/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .formField("grant_type", "authorization_code").formField("code", code)
                .formField("client_id", clientId).formField("code_verifier", verifier)).andReturn().getResponse();
        assertThat(json(unauthenticated, 401).get("error").asString()).isEqualTo("invalid_client");

        String basic = Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse ok = mvc.perform(post("/oauth/token")
                .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .formField("grant_type", "authorization_code").formField("code", code)
                .formField("redirect_uri", REDIRECT_URI).formField("code_verifier", verifier)).andReturn().getResponse();
        assertThat(json(ok, 200).get("access_token").asString()).startsWith("kina_");
        assertThat(ok.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    void refreshTokenRotation() throws Exception {
        String clientId = registerPublicClient("Refresh");
        String verifier = SecureTokens.randomBase64Url(48);
        String code = approve(clientId, verifier, null);
        JsonNode first = token(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT_URI,
                "client_id", clientId, "code_verifier", verifier), 200);
        String access1 = first.get("access_token").asString();
        String refresh1 = first.get("refresh_token").asString();

        JsonNode second = token(Map.of("grant_type", "refresh_token", "refresh_token", refresh1, "client_id", clientId),
                200);
        String access2 = second.get("access_token").asString();
        String refresh2 = second.get("refresh_token").asString();
        assertThat(access2).isNotEqualTo(access1);
        assertThat(refresh2).isNotEqualTo(refresh1);
        assertThat(second.get("scope").asString()).isEqualTo("kina");

        // Old pair is dead, new pair works.
        assertThat(echoStatus(access1)).isEqualTo(401);
        assertThat(echoStatus(access2)).isEqualTo(200);
        assertThat(token(Map.of("grant_type", "refresh_token", "refresh_token", refresh1, "client_id", clientId), 400)
                .get("error").asString()).isEqualTo("invalid_grant");

        // Another client cannot use the refresh token.
        String otherClient = registerPublicClient("Other");
        assertThat(token(Map.of("grant_type", "refresh_token", "refresh_token", refresh2, "client_id", otherClient),
                400).get("error").asString()).isEqualTo("invalid_grant");
        assertThat(token(Map.of("grant_type", "refresh_token", "refresh_token", refresh2, "client_id", clientId), 200)
                .get("access_token").asString()).startsWith("kina_");
    }

    @Test
    void revocation() throws Exception {
        String clientId = registerPublicClient("Revoke");
        String verifier = SecureTokens.randomBase64Url(48);
        JsonNode tokens = token(Map.of("grant_type", "authorization_code", "code", approve(clientId, verifier, null),
                "redirect_uri", REDIRECT_URI, "client_id", clientId, "code_verifier", verifier), 200);
        String access = tokens.get("access_token").asString();
        String refresh = tokens.get("refresh_token").asString();

        // Unknown token and a token of another client: 200, nothing revoked.
        String otherClient = registerPublicClient("Other");
        assertThat(revoke(otherClient, access)).isEqualTo(200);
        assertThat(revoke(clientId, "kina_unknown")).isEqualTo(200);
        assertThat(echoStatus(access)).isEqualTo(200);

        assertThat(revoke(clientId, access)).isEqualTo(200);
        assertThat(echoStatus(access)).isEqualTo(401);

        // Revoking the refresh token makes it unusable.
        assertThat(revoke(clientId, refresh)).isEqualTo(200);
        assertThat(token(Map.of("grant_type", "refresh_token", "refresh_token", refresh, "client_id", clientId), 400)
                .get("error").asString()).isEqualTo("invalid_grant");

        // Unknown client -> 401
        assertThat(revoke("nope", access)).isEqualTo(401);
    }

    @Test
    void denyRedirectsWithAccessDenied() throws Exception {
        String clientId = registerPublicClient("Deny");
        MockHttpServletResponse response = mvc.perform(post("/oauth/authorize").with(csrf())
                .formField("response_type", "code").formField("client_id", clientId)
                .formField("redirect_uri", REDIRECT_URI)
                .formField("code_challenge", Pkce.s256(SecureTokens.randomBase64Url(48)))
                .formField("code_challenge_method", "S256").formField("state", "abc")
                .formField("decision", "deny")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(302);
        UriComponents location = UriComponentsBuilder.fromUriString(response.getRedirectedUrl()).build();
        assertThat(location.getQueryParams().getFirst("error")).isEqualTo("access_denied");
        assertThat(location.getQueryParams().getFirst("state")).isEqualTo("abc");
    }

    @Test
    void authorizeValidation() throws Exception {
        String clientId = registerPublicClient("Validation");
        // Unknown client and unregistered redirect URI: error page, never a redirect.
        MockHttpServletResponse unknown = mvc.perform(get("/oauth/authorize").queryParam("response_type", "code")
                .queryParam("client_id", "unknown").queryParam("redirect_uri", REDIRECT_URI)).andReturn().getResponse();
        assertThat(unknown.getStatus()).isEqualTo(400);
        assertThat(unknown.getRedirectedUrl()).isNull();
        assertThat(unknown.getContentAsString()).contains("Unknown client");

        MockHttpServletResponse badRedirect = mvc.perform(get("/oauth/authorize").queryParam("response_type", "code")
                .queryParam("client_id", clientId).queryParam("redirect_uri", "https://evil.example.com/cb"))
                .andReturn().getResponse();
        assertThat(badRedirect.getStatus()).isEqualTo(400);
        assertThat(badRedirect.getRedirectedUrl()).isNull();

        // Missing PKCE: redirect with invalid_request.
        MockHttpServletResponse noPkce = mvc.perform(get("/oauth/authorize").queryParam("response_type", "code")
                .queryParam("client_id", clientId).queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("state", "s1")).andReturn().getResponse();
        assertThat(noPkce.getStatus()).isEqualTo(302);
        assertThat(noPkce.getRedirectedUrl()).startsWith(REDIRECT_URI + "?error=invalid_request")
                .contains("state=s1");

        MockHttpServletResponse plain = mvc.perform(get("/oauth/authorize").queryParam("response_type", "code")
                .queryParam("client_id", clientId).queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("code_challenge", "a".repeat(43)).queryParam("code_challenge_method", "plain"))
                .andReturn().getResponse();
        assertThat(plain.getRedirectedUrl()).startsWith(REDIRECT_URI + "?error=invalid_request");

        MockHttpServletResponse token = mvc.perform(get("/oauth/authorize").queryParam("response_type", "token")
                .queryParam("client_id", clientId).queryParam("redirect_uri", REDIRECT_URI))
                .andReturn().getResponse();
        assertThat(token.getRedirectedUrl()).startsWith(REDIRECT_URI + "?error=unsupported_response_type");
    }

    @Test
    void consentPostRequiresCsrf() throws Exception {
        String clientId = registerPublicClient("Csrf");
        MockHttpServletResponse response = mvc.perform(post("/oauth/authorize")
                .formField("response_type", "code").formField("client_id", clientId)
                .formField("redirect_uri", REDIRECT_URI)
                .formField("code_challenge", Pkce.s256(SecureTokens.randomBase64Url(48)))
                .formField("code_challenge_method", "S256").formField("decision", "approve")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    // ---- bearer / dev mode ---------------------------------------------------------------------------------------

    @Test
    void devModeFallsBackToAdminWithoutCredentials() throws Exception {
        JsonNode echo = json(mvc.perform(get("/api/v1/test-echo")).andReturn().getResponse(), 200);
        assertThat(echo.get("display_name").asString()).isEqualTo("Development Admin");
        assertThat(echo.get("user_id").asString()).isEqualTo(users.devAdmin().id().toString());
        assertThat(echo.get("token_id").isNull()).isTrue();
    }

    @Test
    void devModeStillRejectsInvalidBearerTokens() throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/mcp")
                .header(HttpHeaders.AUTHORIZATION, "Bearer kina_" + "x".repeat(43))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).contains("error=\"invalid_token\"");
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    String registerPublicClient(String name) throws Exception {
        JsonNode body = json(mvc.perform(post("/oauth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"redirect_uris\":[\"" + REDIRECT_URI + "\"],\"client_name\":\"" + name + "\"}"))
                .andReturn().getResponse(), 201);
        return body.get("client_id").asString();
    }

    /** Approves the consent form and returns the authorization code from the redirect. */
    String approve(String clientId, String verifier, String state) throws Exception {
        var request = post("/oauth/authorize").with(csrf())
                .formField("response_type", "code").formField("client_id", clientId)
                .formField("redirect_uri", REDIRECT_URI).formField("code_challenge", Pkce.s256(verifier))
                .formField("code_challenge_method", "S256").formField("scope", "kina")
                .formField("decision", "approve");
        if (state != null) {
            request.formField("state", state);
        }
        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(302);
        String location = response.getRedirectedUrl();
        assertThat(location).startsWith(REDIRECT_URI + "?code=");
        UriComponents uri = UriComponentsBuilder.fromUriString(location).build();
        if (state != null) {
            assertThat(java.net.URLDecoder.decode(uri.getQueryParams().getFirst("state"), StandardCharsets.UTF_8))
                    .isEqualTo(state);
        } else {
            assertThat(uri.getQueryParams().containsKey("state")).isFalse();
        }
        return uri.getQueryParams().getFirst("code");
    }

    JsonNode token(Map<String, String> form, int expectedStatus) throws Exception {
        var request = post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED);
        form.forEach(request::formField);
        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        return json(response, expectedStatus);
    }

    int revoke(String clientId, String token) throws Exception {
        return mvc.perform(post("/oauth/revoke").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .formField("client_id", clientId).formField("token", token)).andReturn().getResponse().getStatus();
    }

    int echoStatus(String accessToken) throws Exception {
        return mvc.perform(get("/api/v1/test-echo").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andReturn().getResponse().getStatus();
    }

    static JsonNode json(MockHttpServletResponse response, int expectedStatus) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expectedStatus);
        return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    static List<String> strings(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }
}
