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
        assertThat(decision.reason()).isEqualTo(OidcAccessPolicy.Reason.GROUP);
        assertThat(decision.detail()).contains("missing");
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

    // ---- distinct e-mail reasons ---------------------------------------------------------------------------------

    static final OidcAccessPolicy DOMAIN = new OidcAccessPolicy("groups", List.of(), List.of("alacrity.ro"));

    @Test
    void missingEmailIsItsOwnReasonAndListsClaimNamesOnly() {
        OidcAccessPolicy.Decision decision = DOMAIN.evaluateLogin(
                Map.of("sub", "s1", "name", "Ana Pop", "preferred_username", "ana"), Map.of("sub", "s1", "nickname", "a"));
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(OidcAccessPolicy.Reason.EMAIL_MISSING);
        assertThat(decision.reason().code()).isEqualTo("email_missing");
        assertThat(decision.errorCode()).isEqualTo(OidcAccessPolicy.ERROR_EMAIL_DOMAIN);
        assertThat(decision.domain()).isNull();
        assertThat(decision.detail()).contains("no e-mail claim in ID token or userinfo")
                .contains("claims present: name, nickname, preferred_username, sub")
                .doesNotContain("Ana Pop").doesNotContain("s1");
        // Blank and non-string values do not count as an address.
        assertThat(DOMAIN.evaluateLogin(Map.of("email", "  "), Map.of("email", List.of("x@alacrity.ro"))).reason())
                .isEqualTo(OidcAccessPolicy.Reason.EMAIL_MISSING);
        assertThat(DOMAIN.evaluateLogin(Map.of("email", "ana"), null).reason())
                .isEqualTo(OidcAccessPolicy.Reason.EMAIL_MISSING);
        // A blank ID token value falls back to userinfo.
        assertThat(DOMAIN.evaluateLogin(Map.of("email", ""), Map.of("email", "ana@alacrity.ro")).allowed()).isTrue();
    }

    @Test
    void unverifiedEmailIsItsOwnReason() {
        for (Object flag : List.of(false, "false", "FALSE")) {
            OidcAccessPolicy.Decision decision = DOMAIN.evaluateLogin(
                    Map.of("email", "ana@alacrity.ro", "email_verified", flag), null);
            assertThat(decision.allowed()).as(flag.toString()).isFalse();
            assertThat(decision.reason()).isEqualTo(OidcAccessPolicy.Reason.EMAIL_UNVERIFIED);
            assertThat(decision.errorCode()).isEqualTo(OidcAccessPolicy.ERROR_EMAIL_DOMAIN);
            assertThat(decision.detail()).doesNotContain("ana@");
        }
        assertThat(DOMAIN.evaluateLogin(Map.of("sub", "1"), Map.of("email", "ana@alacrity.ro", "email_verified",
                "false")).reason()).as("userinfo").isEqualTo(OidcAccessPolicy.Reason.EMAIL_UNVERIFIED);
        assertThat(DOMAIN.evaluateLogin(Map.of("email", "ana@alacrity.ro", "email_verified", "true"), null).allowed())
                .isTrue();
    }

    @Test
    void wrongDomainCarriesTheLowerCaseDomain() {
        OidcAccessPolicy.Decision decision = DOMAIN.evaluateLogin(Map.of("email", "Eve@Example.ORG"), null);
        assertThat(decision.reason()).isEqualTo(OidcAccessPolicy.Reason.EMAIL_DOMAIN);
        assertThat(decision.reason().code()).isEqualTo("email_domain");
        assertThat(decision.domain()).isEqualTo("example.org");
        assertThat(decision.detail()).isEqualTo("e-mail domain example.org not allowed").doesNotContain("Eve");
        // Wrong domain wins over unverified: the address is refused either way.
        assertThat(DOMAIN.evaluateLogin(Map.of("email", "eve@gmail.com", "email_verified", false), null).reason())
                .isEqualTo(OidcAccessPolicy.Reason.EMAIL_DOMAIN);
    }

    @Test
    void upperCaseAddressAndConfiguredDomainWithLeadingAt() {
        OidcAccessPolicy policy = new OidcAccessPolicy("groups", List.of(), List.of(" @Alacrity.RO "));
        assertThat(policy.allowedEmailDomains()).containsExactly("alacrity.ro");
        assertThat(policy.evaluateLogin(Map.of("email", "ANA@ALACRITY.RO"), null).allowed()).isTrue();
        assertThat(policy.evaluateLogin(Map.of("email", "ana@alacrity.ro "), null).allowed()).isTrue();
    }

    @Test
    void preferredUsernameFallbackOnlyWhenEnabled() {
        Map<String, Object> claims = Map.of("sub", "1", "preferred_username", "ana@alacrity.ro");
        assertThat(DOMAIN.evaluateLogin(claims, null).reason()).as("off by default")
                .isEqualTo(OidcAccessPolicy.Reason.EMAIL_MISSING);
        assertThat(DOMAIN.evaluateLogin(claims, null).detail()).contains("preferred_username/upn is off");
        assertThat(DOMAIN.email(claims, null)).isEmpty();

        OidcAccessPolicy on = new OidcAccessPolicy("groups", List.of(), List.of("alacrity.ro"), true);
        assertThat(on.evaluateLogin(claims, null).allowed()).isTrue();
        assertThat(on.email(claims, null)).contains("ana@alacrity.ro");
        assertThat(on.evaluateLogin(Map.of("preferred_username", "ana"), null).reason())
                .as("no @").isEqualTo(OidcAccessPolicy.Reason.EMAIL_MISSING);
        assertThat(on.evaluateLogin(Map.of("preferred_username", "ana"), Map.of("upn", "ana@alacrity.ro")).allowed())
                .as("upn").isTrue();
        assertThat(on.evaluateLogin(Map.of("preferred_username", "eve@gmail.com"), null).domain())
                .isEqualTo("gmail.com");
        // The email claim wins over the fallback.
        assertThat(on.evaluateLogin(Map.of("email", "eve@gmail.com", "preferred_username", "ana@alacrity.ro"), null)
                .reason()).isEqualTo(OidcAccessPolicy.Reason.EMAIL_DOMAIN);
    }

    @Test
    void reasonCodesAndPolicyDescription() {
        assertThat(OidcAccessPolicy.Reason.fromCode("group")).isEqualTo(OidcAccessPolicy.Reason.GROUP);
        assertThat(OidcAccessPolicy.Reason.fromCode("email")).isNull();
        assertThat(OidcAccessPolicy.Reason.GROUP.errorCode()).isEqualTo(OidcAccessPolicy.ERROR_GROUP);
        assertThat(new OidcAccessPolicy("groups", List.of("G"), List.of("alacrity.ro"), true).describe())
                .isEqualTo("groups claim 'groups', required groups [G], allowed e-mail domains [alacrity.ro], "
                        + "e-mail from preferred_username/upn on");
    }
}
