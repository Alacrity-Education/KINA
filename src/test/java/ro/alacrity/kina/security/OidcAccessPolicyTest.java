package ro.alacrity.kina.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OidcAccessPolicyTest {

    static final OidcAccessPolicy POLICY = new OidcAccessPolicy("groups", List.of("ElectronicsEngineer"), List.of());

    @Test
    void memberInIdTokenIsAllowed() {
        assertThat(POLICY.evaluateLogin(Map.of("groups", List.of("Staff", "ElectronicsEngineer")), null).allowed())
                .isTrue();
    }

    @Test
    void idTokenClaimWinsOverUserinfo() {
        // The ID token carries the claim without the group: userinfo is not consulted.
        OidcAccessPolicy.Decision decision = POLICY.evaluateGroups(Map.of("groups", List.of("Staff")),
                Map.of("groups", List.of("ElectronicsEngineer")));
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.errorCode()).isEqualTo(OidcAccessPolicy.ERROR_GROUP);
    }

    @Test
    void userinfoIsUsedWhenTheIdTokenLacksTheClaim() {
        assertThat(POLICY.evaluateGroups(Map.of("sub", "1"), Map.of("groups", List.of("ElectronicsEngineer")))
                .allowed()).isTrue();
        assertThat(POLICY.evaluateGroups(Map.of("sub", "1"), Map.of("groups", List.of("Other"))).allowed()).isFalse();
    }

    @Test
    void missingClaimEverywhereIsDenied() {
        OidcAccessPolicy.Decision decision = POLICY.evaluateGroups(Map.of("sub", "1"), Map.of("sub", "1"));
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("missing");
        assertThat(POLICY.evaluateGroups(Map.of("sub", "1"), null).allowed()).isFalse();
    }

    @Test
    void matchingIgnoresCaseAndPathPrefix() {
        assertThat(POLICY.evaluateGroups(Map.of("groups", List.of("electronicsengineer")), null).allowed()).isTrue();
        assertThat(POLICY.evaluateGroups(Map.of("groups", List.of("/ElectronicsEngineer")), null).allowed()).isTrue();
        assertThat(POLICY.evaluateGroups(Map.of("groups", List.of("ElectronicsEngineers")), null).allowed()).isFalse();
        assertThat(POLICY.evaluateGroups(Map.of("groups", List.of("/team/ElectronicsEngineer")), null).allowed())
                .isFalse();
    }

    @Test
    void singleStringClaimAndNonStringElements() {
        assertThat(POLICY.evaluateGroups(Map.of("groups", "ElectronicsEngineer"), null).allowed()).isTrue();
        assertThat(POLICY.evaluateGroups(Map.of("groups", List.of(42, Map.of("name", "ElectronicsEngineer"))), null)
                .allowed()).isFalse();
        assertThat(POLICY.evaluateGroups(Map.of("groups", 7), Map.of("groups", List.of("ElectronicsEngineer")))
                .allowed()).as("unusable ID token claim falls back to userinfo").isTrue();
    }

    @Test
    void nestedAndNamespacedClaims() {
        OidcAccessPolicy nested = new OidcAccessPolicy("realm_access.roles", List.of("ElectronicsEngineer"), List.of());
        assertThat(nested.evaluateGroups(Map.of("realm_access", Map.of("roles", List.of("ElectronicsEngineer"))), null)
                .allowed()).isTrue();
        assertThat(nested.evaluateGroups(Map.of("realm_access", List.of("x")), null).allowed()).isFalse();

        OidcAccessPolicy namespaced = new OidcAccessPolicy("https://example.com/groups",
                List.of("ElectronicsEngineer"), List.of());
        assertThat(namespaced.evaluateGroups(Map.of("https://example.com/groups", List.of("ElectronicsEngineer")),
                null).allowed()).isTrue();
    }

    @Test
    void anyOfSeveralRequiredGroupsSuffices() {
        OidcAccessPolicy policy = new OidcAccessPolicy("groups", List.of("A", "B"), List.of());
        assertThat(policy.evaluateGroups(Map.of("groups", List.of("B")), null).allowed()).isTrue();
        assertThat(policy.evaluateGroups(Map.of("groups", List.of("C")), null).allowed()).isFalse();
    }

    @Test
    void noRequiredGroupsMeansNoGroupCheck() {
        OidcAccessPolicy open = new OidcAccessPolicy("groups", List.of(), List.of());
        assertThat(open.checksGroups()).isFalse();
        assertThat(open.evaluateLogin(Map.of("sub", "1"), null).allowed()).isTrue();
    }

    @Test
    void emailDomainCheck() {
        OidcAccessPolicy policy = new OidcAccessPolicy("groups", List.of(), List.of("Alacrity.ro", "@example.org"));
        assertThat(policy.evaluateLogin(Map.of("email", "ana@alacrity.ro", "email_verified", true), null).allowed())
                .isTrue();
        assertThat(policy.evaluateLogin(Map.of("email", "bob@EXAMPLE.org"), null).allowed()).isTrue();
        assertThat(policy.evaluateLogin(Map.of("sub", "1"), Map.of("email", "ana@alacrity.ro")).allowed()).isTrue();

        OidcAccessPolicy.Decision other = policy.evaluateLogin(Map.of("email", "eve@gmail.com"), null);
        assertThat(other.allowed()).isFalse();
        assertThat(other.errorCode()).isEqualTo(OidcAccessPolicy.ERROR_EMAIL_DOMAIN);
        assertThat(policy.evaluateLogin(Map.of("email", "eve@alacrity.ro.evil.com"), null).allowed()).isFalse();
        assertThat(policy.evaluateLogin(Map.of("email", "eve@sub.alacrity.ro"), null).allowed()).isFalse();
        assertThat(policy.evaluateLogin(Map.of("sub", "1"), null).allowed()).as("no email").isFalse();
        assertThat(policy.evaluateLogin(Map.of("email", "ana@alacrity.ro", "email_verified", false), null).allowed())
                .as("unverified").isFalse();
    }

    @Test
    void domainIsCheckedBeforeGroups() {
        OidcAccessPolicy policy = new OidcAccessPolicy("groups", List.of("G"), List.of("alacrity.ro"));
        assertThat(policy.evaluateLogin(Map.of("email", "eve@gmail.com", "groups", List.of("G")), null).errorCode())
                .isEqualTo(OidcAccessPolicy.ERROR_EMAIL_DOMAIN);
        assertThat(policy.evaluateLogin(Map.of("email", "ana@alacrity.ro", "groups", List.of("H")), null).errorCode())
                .isEqualTo(OidcAccessPolicy.ERROR_GROUP);
    }
}
