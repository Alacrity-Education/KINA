package ro.alacrity.kina.security;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpstreamTokenCipherTest {

    static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Test
    void roundTripsAndNeverStoresPlaintext() {
        UpstreamTokenCipher cipher = UpstreamTokenCipher.fromBase64Key(randomKey());
        String token = "upstream-refresh-token-" + "x".repeat(200);

        String sealed = cipher.encrypt(token, "user-1");

        assertThat(cipher.isEnabled()).isTrue();
        assertThat(sealed).startsWith("v1.").doesNotContain(token).doesNotContain("upstream");
        assertThat(cipher.decrypt(sealed, "user-1")).contains(token);
        // a fresh IV every time
        assertThat(cipher.encrypt(token, "user-1")).isNotEqualTo(sealed);
    }

    @Test
    void associatedDataBindsTheCiphertextToItsRow() {
        UpstreamTokenCipher cipher = UpstreamTokenCipher.fromBase64Key(randomKey());
        String sealed = cipher.encrypt("secret", "user-1");
        assertThat(cipher.decrypt(sealed, "user-2")).isEmpty();
    }

    @Test
    void wrongKeyTamperingAndGarbageDecryptToEmpty() {
        UpstreamTokenCipher cipher = UpstreamTokenCipher.fromBase64Key(randomKey());
        String sealed = cipher.encrypt("secret", "u");
        assertThat(UpstreamTokenCipher.fromBase64Key(randomKey()).decrypt(sealed, "u")).isEmpty();

        char[] chars = sealed.toCharArray();
        int i = chars.length - 5;
        chars[i] = chars[i] == 'A' ? 'B' : 'A';
        assertThat(cipher.decrypt(new String(chars), "u")).isEmpty();

        assertThat(cipher.decrypt("v1.!!!", "u")).isEmpty();
        assertThat(cipher.decrypt("v1.AAAA", "u")).isEmpty();
        assertThat(cipher.decrypt("plaintext-token", "u")).isEmpty();
        assertThat(cipher.decrypt(null, "u")).isEmpty();
    }

    @Test
    void blankKeyDisablesTheCipher() {
        UpstreamTokenCipher disabled = UpstreamTokenCipher.fromBase64Key("  ");
        assertThat(disabled.isEnabled()).isFalse();
        assertThat(UpstreamTokenCipher.fromBase64Key(null).isEnabled()).isFalse();
        assertThatThrownBy(() -> disabled.encrypt("x", "u")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void malformedKeysFailFastWithoutEchoingTheKey() {
        assertThatThrownBy(() -> UpstreamTokenCipher.fromBase64Key("not base64 %%%"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("openssl rand -base64 32");
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> UpstreamTokenCipher.fromBase64Key(shortKey))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("exactly 32 bytes")
                .hasMessageNotContaining(shortKey);
    }
}
