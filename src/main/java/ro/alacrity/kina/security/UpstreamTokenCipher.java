package ro.alacrity.kina.security;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * AES-256-GCM encryption of the identity provider's refresh tokens stored in {@code users.upstream_refresh_token}.
 * The key is {@code kina.security.oidc.token-encryption-key} ({@code KINA_TOKEN_ENCRYPTION_KEY}): base64 of exactly 32
 * random bytes ({@code openssl rand -base64 32}). Stored form: {@code v1.} + base64url(12-byte IV || ciphertext || 16-byte
 * tag). The associated data binds a ciphertext to its row (the user id), so a value copied to another user's row does
 * not decrypt. When no key is configured the cipher is disabled and upstream refresh tokens are not stored.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class UpstreamTokenCipher {

    static final String VERSION_PREFIX = "v1.";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    /** A cipher for the configured key, or a disabled one when the key is blank. Throws on a malformed key. */
    public static UpstreamTokenCipher fromBase64Key(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            return new UpstreamTokenCipher(null);
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("kina.security.oidc.token-encryption-key (KINA_TOKEN_ENCRYPTION_KEY) is not "
                    + "valid base64; generate one with: openssl rand -base64 32");
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException("kina.security.oidc.token-encryption-key (KINA_TOKEN_ENCRYPTION_KEY) must be "
                    + "base64 of exactly 32 bytes (got " + raw.length + "); generate one with: openssl rand -base64 32");
        }
        return new UpstreamTokenCipher(new SecretKeySpec(raw, "AES"));
    }

    public boolean isEnabled() {
        return key != null;
    }

    /** Encrypts {@code plaintext} bound to {@code associatedData}; requires {@link #isEnabled()}. */
    public String encrypt(String plaintext, String associatedData) {
        requireEnabled();
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
            return VERSION_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    /**
     * Decrypts a value produced by {@link #encrypt}; empty when it was encrypted with another key or for other
     * associated data, or is malformed. Never throws for bad input.
     */
    public Optional<String> decrypt(String stored, String associatedData) {
        requireEnabled();
        if (stored == null || !stored.startsWith(VERSION_PREFIX)) {
            return Optional.empty();
        }
        try {
            byte[] in = Base64.getUrlDecoder().decode(stored.substring(VERSION_PREFIX.length()));
            if (in.length <= IV_BYTES + TAG_BITS / 8) {
                return Optional.empty();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES);
            return Optional.of(new String(plain, StandardCharsets.UTF_8));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // wrong key, wrong associated data (AEADBadTagException), tampered or malformed value
            return Optional.empty();
        }
    }

    private void requireEnabled() {
        if (key == null) {
            throw new IllegalStateException("No token encryption key configured");
        }
    }

    @Override
    public String toString() {
        return "UpstreamTokenCipher[enabled=" + isEnabled() + "]";
    }
}
