package ro.alacrity.kina.security;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import ro.alacrity.kina.TestcontainersConfiguration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * {@code kina.security.oidc.require-verified-email=false}: a provider that sends {@code email_verified=false} (Authentik's
 * default {@code email} mapping) no longer blocks a login whose domain is allowed; the domain check still applies.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kina.security.mode=prod",
        "kina.security.oidc.client-id=" + FakeOidcProvider.CLIENT_ID,
        "kina.security.oidc.client-secret=" + FakeOidcProvider.CLIENT_SECRET,
        "kina.security.oidc.required-groups=ElectronicsEngineer",
        "kina.security.oidc.allowed-email-domains=alacrity.ro",
        "kina.security.oidc.require-verified-email=false"})
class OidcUnverifiedEmailAllowedIntegrationTest {

    static final FakeOidcProvider PROVIDER = new FakeOidcProvider();
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

    @Test
    void unverifiedEmailWithAnAllowedDomainSignsIn() throws Exception {
        String subject = "unverified-" + UUID.randomUUID();
        PROVIDER.user(subject, "unverified@alacrity.ro", "ElectronicsEngineer");
        PROVIDER.setEmailVerified(subject, false);

        MockHttpSession session = new MockHttpSession();
        MockHttpServletResponse callback = login(session, subject);
        assertThat(callback.getStatus()).isEqualTo(302);
        assertThat(callback.getRedirectedUrl()).doesNotContain("login-denied").doesNotContain("login-error");
        MockHttpServletResponse index = mvc.perform(get("/").session(session)).andReturn().getResponse();
        assertThat(index.getStatus()).isEqualTo(200);
        assertThat(index.getContentAsString()).contains("Signed in as User " + subject);
    }

    @Test
    void wrongDomainIsStillDenied() throws Exception {
        String subject = "outsider-" + UUID.randomUUID();
        PROVIDER.user(subject, "outsider@example.org", "ElectronicsEngineer");
        PROVIDER.setEmailVerified(subject, false);

        MockHttpServletResponse callback = login(new MockHttpSession(), subject);
        assertThat(callback.getRedirectedUrl()).endsWith("/login-denied?reason=email_domain");
    }

    /** Browser login: KINA -> provider /authorize (scripted user) -> KINA callback, in one MockMvc session. */
    MockHttpServletResponse login(MockHttpSession session, String subject) throws Exception {
        mvc.perform(get("/").session(session));
        MockHttpServletResponse toProvider = mvc.perform(get("/oauth2/authorization/oidc").session(session))
                .andReturn().getResponse();
        assertThat(toProvider.getStatus()).isEqualTo(302);
        PROVIDER.signInAs(subject);
        HttpResponse<Void> redirect = HTTP.send(
                HttpRequest.newBuilder(URI.create(toProvider.getRedirectedUrl())).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(redirect.statusCode()).isEqualTo(302);
        String callback = redirect.headers().firstValue("Location").orElseThrow();
        return mvc.perform(get(URI.create(callback)).session(session)).andReturn().getResponse();
    }
}
