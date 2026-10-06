package ro.alacrity.kina.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The operator hints logged at INFO on a denied login: claim names only, userinfo and scope diagnosis. */
class OidcUserSynchronizerDiagnosisTest {

    @Test
    void userinfoFetchedListsNamesOnly() {
        assertThat(OidcUserSynchronizer.userInfoDiagnosis(request("https://idp/userinfo", Set.of()),
                Map.of("sub", "s", "email", "ana@alacrity.ro")))
                .isEqualTo("userinfo claims [email, sub]");
    }

    @Test
    void userinfoNotFetchedExplainsAMissingEndpoint() {
        assertThat(OidcUserSynchronizer.userInfoDiagnosis(request(null, Set.of()), null))
                .isEqualTo("userinfo not fetched (the provider's discovery document has no userinfo_endpoint)");
        assertThat(OidcUserSynchronizer.userInfoDiagnosis(request("https://idp/userinfo", Set.of()), null))
                .isEqualTo("userinfo not fetched");
    }

    @Test
    void scopeHints() {
        assertThat(OidcUserSynchronizer.scopeDiagnosis(request("u", Set.of())))
                .startsWith("the token response named no scope");
        assertThat(OidcUserSynchronizer.scopeDiagnosis(request("u", Set.of("openid", "profile"))))
                .isEqualTo("granted scopes [openid, profile] (no email scope granted: the provider sends no e-mail "
                        + "claims)");
        assertThat(OidcUserSynchronizer.scopeDiagnosis(request("u", Set.of("openid", "email"))))
                .isEqualTo("granted scopes [email, openid]");
    }

    private static OidcUserRequest request(String userInfoUri, Set<String> scopes) {
        ClientRegistration registration = ClientRegistration.withRegistrationId("oidc")
                .clientId("kina")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://kina/login/oauth2/code/oidc")
                .authorizationUri("https://idp/authorize")
                .tokenUri("https://idp/token")
                .userInfoUri(userInfoUri)
                .jwkSetUri("https://idp/jwks")
                .build();
        Instant now = Instant.now();
        OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "at", now,
                now.plusSeconds(60), scopes);
        OidcIdToken idToken = new OidcIdToken("id", now, now.plusSeconds(60), Map.of("sub", "s"));
        return new OidcUserRequest(registration, token, idToken);
    }
}
