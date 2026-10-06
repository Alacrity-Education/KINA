package ro.alacrity.kina.security;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import ro.alacrity.kina.TestcontainersConfiguration;
import ro.alacrity.kina.oauth.Pkce;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Production mode against an in-process OIDC provider ({@link FakeOidcProvider}) with group authorisation on
 * ({@code required-groups=ElectronicsEngineer}, an encryption key, so upstream refresh tokens are stored and
 * re-checked). Covers the login gate, refresh-time re-checks with every outcome, the grace window, the fallback without
 * an upstream refresh token, the asynchronous re-check of static tokens and the bearer rejection of revoked users.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestEchoController.Endpoint.class})
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
        "kina.security.mode=prod",
        "kina.security.oidc.client-id=" + FakeOidcProvider.CLIENT_ID,
        "kina.security.oidc.client-secret=" + FakeOidcProvider.CLIENT_SECRET,
        "kina.security.oidc.required-groups=ElectronicsEngineer",
        "kina.security.oidc.allowed-email-domains=alacrity.ro",
        "kina.security.oidc.token-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "kina.security.oidc.membership-recheck-interval=1h",
        "kina.security.oidc.membership-grace=4h",
        "kina.security.oidc.relogin-interval-without-recheck=24h"})
class OidcGroupAuthorisationIntegrationTest {

    static final FakeOidcProvider PROVIDER = new FakeOidcProvider();
    static final String GROUP = "ElectronicsEngineer";
    static final String REDIRECT_URI = "http://localhost:33418/callback";
    static final String TOKENS_SENTENCE = "Tokens you created earlier no longer work.";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void issuer(DynamicPropertyRegistry registry) {
        registry.add("kina.security.oidc.issuer-uri", PROVIDER::issuer);
    }

    @AfterAll
    static void stopProvider() {
        PROVIDER.close();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    AccessTokenService accessTokens;

    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void providerBackUp() {
        PROVIDER.setTokenEndpointDown(false);
    }

    // ---- login gate ----------------------------------------------------------------------------------------------

    @Test
    void memberSignsInAndReachesConsent() throws Exception {
        String subject = subject("ana");
        PROVIDER.user(subject, "ana@alacrity.ro", "Staff", GROUP);

        Login login = login(subject);
        assertThat(login.callback().getStatus()).isEqualTo(302);
        assertThat(login.callback().getRedirectedUrl()).doesNotContain("login-denied").doesNotContain("login-error");

        MockHttpServletResponse index = mvc.perform(get("/").session(login.session())).andReturn().getResponse();
        assertThat(index.getStatus()).isEqualTo(200);
        assertThat(index.getContentAsString()).contains("Signed in as User " + subject).contains("Connect KINA");

        String clientId = registerClient();
        MockHttpServletResponse consent = mvc.perform(get("/oauth/authorize").session(login.session())
                .queryParam("response_type", "code").queryParam("client_id", clientId)
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("code_challenge", Pkce.s256(SecureTokens.randomBase64Url(48)))
                .queryParam("code_challenge_method", "S256")).andReturn().getResponse();
        assertThat(consent.getStatus()).isEqualTo(200);
        assertThat(consent.getContentAsString()).contains("value=\"approve\"").contains("User " + subject);

        UserRow row = row(subject);
        assertThat(row.membershipCheckedAt()).isNotNull();
        assertThat(row.accessRevokedAt()).isNull();
        assertThat(row.upstreamRefreshToken()).startsWith("v1.");
        assertThat(PROVIDER.issuedRefreshToken(row.upstreamRefreshToken())).isFalse();
        for (String issued : PROVIDER.liveRefreshTokens()) {
            assertThat(row.upstreamRefreshToken()).doesNotContain(issued);
        }
    }

    @Test
    void nonMemberIsDeniedWithoutSessionAndLosesExistingTokens() throws Exception {
        String subject = subject("eve");
        PROVIDER.user(subject, "eve@alacrity.ro", "Staff");
        // Eve was a member once and still has a static token.
        UUID userId = users.upsert(PROVIDER.issuer(), subject, "eve@alacrity.ro", "Eve", Instant.now()).id();
        String staticToken = accessTokens.create(userId, "script", null, null).plaintext();
        users.markMembershipChecked(userId, Instant.now(), true);
        assertThat(echo(staticToken)).isEqualTo(200);

        Login login = login(subject);
        assertThat(login.callback().getStatus()).isEqualTo(302);
        assertThat(login.callback().getRedirectedUrl()).endsWith("/login-denied?reason=group");

        MockHttpServletResponse denied = mvc.perform(get("/login-denied").param("reason", "group")
                .session(login.session())).andReturn().getResponse();
        assertThat(denied.getStatus()).isEqualTo(403);
        assertThat(denied.getContentAsString()).contains("Access denied")
                .contains("Your account is not in a group that may use KINA (required:").contains(GROUP)
                .as("an existing user was blocked").contains(TOKENS_SENTENCE);

        MockHttpServletResponse index = mvc.perform(get("/").session(login.session())).andReturn().getResponse();
        assertThat(index.getStatus()).isEqualTo(302);
        assertThat(index.getRedirectedUrl()).endsWith("/oauth2/authorization/oidc");

        assertThat(row(subject).accessRevokedAt()).isNotNull();
        assertThat(echo(staticToken)).isEqualTo(401);
    }

    @Test
    void wrongEmailDomainIsDeniedAndThePageNamesTheDomain(CapturedOutput output) throws Exception {
        String subject = subject("mallory");
        PROVIDER.user(subject, "mallory@Gmail.com", GROUP);
        Login login = login(subject);
        assertThat(login.callback().getRedirectedUrl()).endsWith("/login-denied?reason=email_domain");
        String page = deniedPage(login);
        assertThat(page).contains("You signed in with an account from <strong>gmail.com</strong>")
                .contains("but KINA only accepts <strong>alacrity.ro</strong>")
                .as("a new user had no tokens").doesNotContain(TOKENS_SENTENCE);
        assertThat(users.findByIssuerAndSubject(PROVIDER.issuer(), subject)).isEmpty();
        assertThat(output.getOut()).contains("OIDC login denied for subject " + subject + " (issuer "
                + PROVIDER.issuer() + "): e-mail domain gmail.com not allowed").doesNotContain("mallory@");

        // The page shows the details once; a later visit (or a hand-made URL) falls back to the generic text.
        String again = mvc.perform(get("/login-denied").param("reason", "email_domain").session(login.session()))
                .andReturn().getResponse().getContentAsString();
        assertThat(again).doesNotContain("gmail.com").contains("KINA only accepts accounts from");
        assertThat(mvc.perform(get("/login-denied").param("reason", "email")).andReturn().getResponse()
                .getContentAsString()).contains("alacrity.ro");
    }

    @Test
    void loginWithoutEmailClaimSaysTheProviderSentNoAddress(CapturedOutput output) throws Exception {
        String subject = subject("nomail");
        PROVIDER.user(subject, "nomail@alacrity.ro", GROUP);
        PROVIDER.omitEmail(subject);
        Login login = login(subject);
        assertThat(login.callback().getRedirectedUrl()).endsWith("/login-denied?reason=email_missing");
        assertThat(deniedPage(login))
                .contains("Your identity provider did not send an e-mail address for your account")
                .contains("Ask the administrator to enable the e-mail scope for the KINA application.")
                .doesNotContain(TOKENS_SENTENCE);
        assertThat(output.getOut())
                .contains("OIDC login denied for subject " + subject + " (issuer " + PROVIDER.issuer()
                        + "): no e-mail claim in ID token or userinfo (claims present: ")
                .contains("groups, iat, iss, name, nonce, sub")
                .contains("userinfo claims [groups, name, sub]")
                .contains("the token response named no scope")
                .doesNotContain("User " + subject + ",");
    }

    @Test
    void unverifiedEmailIsDenied(CapturedOutput output) throws Exception {
        String subject = subject("unverified");
        PROVIDER.user(subject, "unverified@alacrity.ro", GROUP);
        PROVIDER.setEmailVerified(subject, false);
        Login login = login(subject);
        assertThat(login.callback().getRedirectedUrl()).endsWith("/login-denied?reason=email_unverified");
        assertThat(deniedPage(login)).contains("Your e-mail address is marked as unverified by the identity provider.")
                .doesNotContain(TOKENS_SENTENCE);
        assertThat(output.getOut()).contains("e-mail address not verified").doesNotContain("unverified@alacrity.ro");
    }

    @Test
    void existingUserDeniedForTheirAddressSeesTheTokensSentence() throws Exception {
        String subject = subject("oscar");
        PROVIDER.user(subject, "oscar@alacrity.ro", GROUP);
        PROVIDER.setEmailVerified(subject, "false");
        users.upsert(PROVIDER.issuer(), subject, "oscar@alacrity.ro", "Oscar", Instant.now());
        Login login = login(subject);
        assertThat(login.callback().getRedirectedUrl()).endsWith("/login-denied?reason=email_unverified");
        assertThat(deniedPage(login)).contains("marked as unverified").contains(TOKENS_SENTENCE);
        assertThat(row(subject).accessRevokedAt()).isNotNull();
    }

    // ---- refresh-time re-check -----------------------------------------------------------------------------------

    @Test
    void refreshAfterTheProviderDroppedTheGroupRevokesEverything() throws Exception {
        String subject = subject("bob");
        PROVIDER.user(subject, "bob@alacrity.ro", GROUP);
        Login login = login(subject);
        Tokens tokens = connect(login.session());
        assertThat(tokens.expiresIn()).isEqualTo(3600L);
        UUID userId = row(subject).id();
        String staticToken = accessTokens.create(userId, "script", null, null).plaintext();

        // Within the re-check interval: no provider call.
        int calls = PROVIDER.refreshGrants();
        Tokens fresh = refresh(tokens, 200);
        assertThat(PROVIDER.refreshGrants()).isEqualTo(calls);

        PROVIDER.setGroups(subject, "Staff");
        ageMembershipCheck(subject, Duration.ofHours(2));
        JsonNode error = refreshRaw(fresh, 400);
        assertThat(error.get("error").asString()).isEqualTo("invalid_grant");
        assertThat(PROVIDER.refreshGrants()).isEqualTo(calls + 1);

        assertThat(row(subject).accessRevokedAt()).isNotNull();
        assertThat(row(subject).upstreamRefreshToken()).isNull();
        assertThat(echo(fresh.access())).isEqualTo(401);
        assertThat(echo(staticToken)).isEqualTo(401);
        assertThat(refreshRaw(fresh, 400).get("error").asString()).isEqualTo("invalid_grant");

        // The web session ends as well: the next page view starts a new login.
        MockHttpServletResponse index = mvc.perform(get("/").session(login.session())).andReturn().getResponse();
        assertThat(index.getStatus()).isEqualTo(302);
        assertThat(index.getRedirectedUrl()).doesNotContain("login-denied");

        // Re-added to the group, a new login lifts the block.
        PROVIDER.setGroups(subject, GROUP);
        Login again = login(subject);
        assertThat(again.callback().getRedirectedUrl()).doesNotContain("login-denied");
        assertThat(row(subject).accessRevokedAt()).isNull();
    }

    @Test
    void invalidGrantAtTheProviderRevokesTheUser() throws Exception {
        String subject = subject("gina");
        PROVIDER.user(subject, "gina@alacrity.ro", GROUP);
        Tokens tokens = connect(login(subject).session());
        PROVIDER.revokeGrant(subject);
        ageMembershipCheck(subject, Duration.ofHours(2));
        assertThat(refreshRaw(tokens, 400).get("error").asString()).isEqualTo("invalid_grant");
        assertThat(row(subject).accessRevokedAt()).isNotNull();
        assertThat(echo(tokens.access())).isEqualTo(401);
    }

    @Test
    void groupsFromUserinfoWhenTheRefreshedIdTokenLacksThem() throws Exception {
        String subject = subject("hugo");
        PROVIDER.user(subject, "hugo@alacrity.ro", GROUP);
        Tokens tokens = connect(login(subject).session());
        PROVIDER.setGroupsInIdToken(subject, false);
        ageMembershipCheck(subject, Duration.ofHours(2));
        Instant before = row(subject).membershipCheckedAt();

        Tokens refreshed = refresh(tokens, 200);
        assertThat(row(subject).membershipCheckedAt()).isAfter(before);
        assertThat(echo(refreshed.access())).isEqualTo(200);
    }

    @Test
    void providerUnreachableWithinGraceKeepsAccessThenRefuses() throws Exception {
        String subject = subject("carol");
        PROVIDER.user(subject, "carol@alacrity.ro", GROUP);
        Tokens tokens = connect(login(subject).session());

        PROVIDER.setTokenEndpointDown(true);
        ageMembershipCheck(subject, Duration.ofHours(2));
        Tokens withinGrace = refresh(tokens, 200);
        assertThat(echo(withinGrace.access())).isEqualTo(200);

        ageMembershipCheck(subject, Duration.ofHours(5));
        JsonNode refused = refreshRaw(withinGrace, 400);
        assertThat(refused.get("error").asString()).isEqualTo("invalid_grant");
        assertThat(refused.get("error_description").asString()).contains("unreachable");
        assertThat(row(subject).accessRevokedAt()).as("an outage does not revoke the user").isNull();

        // Back up: the same refresh token works again (it was not rotated by the refused attempt).
        PROVIDER.setTokenEndpointDown(false);
        assertThat(refresh(withinGrace, 200).access()).startsWith("kina_");
    }

    @Test
    void withoutUpstreamRefreshTokenRefreshWorksUntilTheReloginInterval() throws Exception {
        String subject = subject("ivan");
        PROVIDER.user(subject, "ivan@alacrity.ro", GROUP);
        Tokens tokens = connect(login(subject).session());
        jdbc.sql("UPDATE users SET upstream_refresh_token = NULL WHERE subject = ?").param(subject).update();
        int calls = PROVIDER.refreshGrants();

        ageMembershipCheck(subject, Duration.ofHours(23));
        Tokens stillOk = refresh(tokens, 200);
        ageMembershipCheck(subject, Duration.ofHours(25));
        assertThat(refreshRaw(stillOk, 400).get("error_description").asString()).contains("sign in again");
        assertThat(PROVIDER.refreshGrants()).as("no provider call without a token").isEqualTo(calls);
        assertThat(row(subject).accessRevokedAt()).isNull();
    }

    // ---- static tokens -------------------------------------------------------------------------------------------

    @Test
    void staticTokenTriggersABackgroundRecheckThatRevokesARemovedMember() throws Exception {
        String subject = subject("dave");
        PROVIDER.user(subject, "dave@alacrity.ro", GROUP);
        login(subject);
        UUID userId = row(subject).id();
        String staticToken = accessTokens.create(userId, "script", null, null).plaintext();
        assertThat(echo(staticToken)).isEqualTo(200);

        PROVIDER.setGroups(subject);
        ageMembershipCheck(subject, Duration.ofHours(2));
        // Never blocks on the provider: this request is served, the re-check runs in the background.
        assertThat(echo(staticToken)).isEqualTo(200);
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (row(subject).accessRevokedAt() == null && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(row(subject).accessRevokedAt()).isNotNull();
        assertThat(echo(staticToken)).isEqualTo(401);
    }

    @Test
    void staticTokenWithoutUpstreamTokenNeedsARecentLogin() throws Exception {
        String subject = subject("judy");
        PROVIDER.user(subject, "judy@alacrity.ro", GROUP);
        login(subject);
        String staticToken = accessTokens.create(row(subject).id(), "script", null, null).plaintext();
        jdbc.sql("UPDATE users SET upstream_refresh_token = NULL WHERE subject = ?").param(subject).update();
        ageMembershipCheck(subject, Duration.ofHours(23));
        assertThat(echo(staticToken)).isEqualTo(200);
        ageMembershipCheck(subject, Duration.ofHours(25));
        assertThat(echo(staticToken)).isEqualTo(401);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    record Login(MockHttpSession session, MockHttpServletResponse callback) {
    }

    record Tokens(String access, String refresh, String clientId, long expiresIn) {
    }

    record UserRow(UUID id, Instant membershipCheckedAt, Instant accessRevokedAt, String upstreamRefreshToken) {
    }

    /** The denied page as the browser sees it after the redirect (same session, reason from the redirect URL). */
    String deniedPage(Login login) throws Exception {
        MockHttpServletResponse page = mvc.perform(get(URI.create(login.callback().getRedirectedUrl()))
                .session(login.session())).andReturn().getResponse();
        assertThat(page.getStatus()).isEqualTo(403);
        return page.getContentAsString();
    }

    static String subject(String name) {
        return name + "-" + UUID.randomUUID();
    }

    /** Full browser login: KINA -> provider /authorize (scripted user) -> KINA callback, in one MockMvc session. */
    Login login(String subject) throws Exception {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletResponse start = mvc.perform(get("/").session(session)).andReturn().getResponse();
        assertThat(start.getRedirectedUrl()).endsWith("/oauth2/authorization/oidc");
        MockHttpServletResponse toProvider = mvc.perform(get("/oauth2/authorization/oidc").session(session))
                .andReturn().getResponse();
        assertThat(toProvider.getStatus()).isEqualTo(302);
        String authorizeUrl = toProvider.getRedirectedUrl();
        assertThat(authorizeUrl).startsWith(PROVIDER.issuer().replace("/issuer", "/authorize"));
        assertThat(authorizeUrl).contains("offline_access");

        PROVIDER.signInAs(subject);
        HttpResponse<Void> redirect = HTTP.send(HttpRequest.newBuilder(URI.create(authorizeUrl)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(redirect.statusCode()).isEqualTo(302);
        String callback = redirect.headers().firstValue("Location").orElseThrow();
        // A URI (not a template string): the state parameter is already percent-encoded.
        MockHttpServletResponse result = mvc.perform(get(URI.create(callback)).session(session))
                .andReturn().getResponse();
        return new Login(session, result);
    }

    String registerClient() throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/oauth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"redirect_uris\":[\"" + REDIRECT_URI + "\"],\"client_name\":\"Claude\"}"))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(201);
        return JSON.readTree(response.getContentAsString()).get("client_id").asString();
    }

    /** Registers a client, approves consent in the session and redeems the code. */
    Tokens connect(MockHttpSession session) throws Exception {
        String clientId = registerClient();
        String verifier = SecureTokens.randomBase64Url(48);
        MockHttpServletResponse approved = mvc.perform(post("/oauth/authorize").session(session).with(csrf())
                .formField("response_type", "code").formField("client_id", clientId)
                .formField("redirect_uri", REDIRECT_URI).formField("code_challenge", Pkce.s256(verifier))
                .formField("code_challenge_method", "S256").formField("decision", "approve"))
                .andReturn().getResponse();
        assertThat(approved.getStatus()).isEqualTo(302);
        UriComponents location = UriComponentsBuilder.fromUriString(approved.getRedirectedUrl()).build();
        JsonNode body = token(Map.of("grant_type", "authorization_code", "code",
                location.getQueryParams().getFirst("code"), "redirect_uri", REDIRECT_URI, "client_id", clientId,
                "code_verifier", verifier), 200);
        return new Tokens(body.get("access_token").asString(), body.get("refresh_token").asString(), clientId,
                body.get("expires_in").asLong());
    }

    Tokens refresh(Tokens tokens, int expectedStatus) throws Exception {
        JsonNode body = refreshRaw(tokens, expectedStatus);
        return new Tokens(body.get("access_token").asString(), body.get("refresh_token").asString(),
                tokens.clientId(), body.get("expires_in").asLong());
    }

    JsonNode refreshRaw(Tokens tokens, int expectedStatus) throws Exception {
        return token(Map.of("grant_type", "refresh_token", "refresh_token", tokens.refresh(), "client_id",
                tokens.clientId()), expectedStatus);
    }

    JsonNode token(Map<String, String> form, int expectedStatus) throws Exception {
        var request = post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED);
        form.forEach(request::formField);
        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expectedStatus);
        return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    int echo(String token) throws Exception {
        return mvc.perform(get("/api/v1/test-echo").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }

    void ageMembershipCheck(String subject, Duration age) {
        jdbc.sql("UPDATE users SET membership_checked_at = ? WHERE subject = ?")
                .param(Timestamp.from(Instant.now().minus(age)))
                .param(subject)
                .update();
    }

    UserRow row(String subject) {
        return jdbc.sql("""
                        SELECT id, membership_checked_at, access_revoked_at, upstream_refresh_token
                        FROM users WHERE issuer = ? AND subject = ?""")
                .param(PROVIDER.issuer())
                .param(subject)
                .query((rs, n) -> new UserRow(rs.getObject("id", UUID.class),
                        instant(rs.getTimestamp("membership_checked_at")),
                        instant(rs.getTimestamp("access_revoked_at")), rs.getString("upstream_refresh_token")))
                .single();
    }

    static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
