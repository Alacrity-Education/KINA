package ro.alacrity.kina.oauth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PkceTest {

    /** RFC 7636 appendix B. */
    static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    @Test
    void s256MatchesRfcVector() {
        assertThat(Pkce.s256(VERIFIER)).isEqualTo(CHALLENGE);
        assertThat(Pkce.verify(VERIFIER, CHALLENGE, "S256")).isTrue();
    }

    @Test
    void rejectsWrongVerifierOrMethod() {
        assertThat(Pkce.verify(VERIFIER.replace('d', 'e'), CHALLENGE, "S256")).isFalse();
        assertThat(Pkce.verify(VERIFIER, CHALLENGE, "plain")).isFalse();
        assertThat(Pkce.verify(VERIFIER, VERIFIER, "plain")).isFalse();
        assertThat(Pkce.verify(null, CHALLENGE, "S256")).isFalse();
        assertThat(Pkce.verify(VERIFIER, null, "S256")).isFalse();
    }

    @Test
    void validatesVerifierSyntax() {
        assertThat(Pkce.isValidVerifier("a".repeat(42))).isFalse();
        assertThat(Pkce.isValidVerifier("a".repeat(43))).isTrue();
        assertThat(Pkce.isValidVerifier("a".repeat(128))).isTrue();
        assertThat(Pkce.isValidVerifier("a".repeat(129))).isFalse();
        assertThat(Pkce.isValidVerifier("a".repeat(42) + "+")).isFalse();
        assertThat(Pkce.isValidVerifier("aA0-._~".repeat(7))).isTrue();
        assertThat(Pkce.verify("short", Pkce.s256("short"), "S256")).isFalse();
    }

    @Test
    void validatesChallengeSyntax() {
        assertThat(Pkce.isValidS256Challenge(CHALLENGE)).isTrue();
        assertThat(Pkce.isValidS256Challenge(CHALLENGE + "=")).isFalse();
        assertThat(Pkce.isValidS256Challenge("abc")).isFalse();
    }
}
