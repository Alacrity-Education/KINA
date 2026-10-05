package ro.alacrity.kina.oauth;

import ro.alacrity.kina.security.SecureTokens;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Pattern;

/** PKCE (RFC 7636), {@code S256} only. */
public final class Pkce {

    public static final String S256 = "S256";

    /** code_verifier = 43*128unreserved; S256 challenges are always 43 base64url characters. */
    private static final Pattern VERIFIER = Pattern.compile("^[A-Za-z0-9\\-._~]{43,128}$");
    private static final Pattern S256_CHALLENGE = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private Pkce() {
    }

    /** {@code BASE64URL(SHA256(ASCII(code_verifier)))} without padding. */
    public static String s256(String codeVerifier) {
        byte[] digest = SecureTokens.sha256(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    public static boolean isValidVerifier(String codeVerifier) {
        return codeVerifier != null && VERIFIER.matcher(codeVerifier).matches();
    }

    public static boolean isValidS256Challenge(String codeChallenge) {
        return codeChallenge != null && S256_CHALLENGE.matcher(codeChallenge).matches();
    }

    /** True when {@code codeVerifier} is well-formed and {@code S256(codeVerifier) == codeChallenge}. */
    public static boolean verify(String codeVerifier, String codeChallenge, String method) {
        if (!S256.equals(method) || !isValidVerifier(codeVerifier) || codeChallenge == null) {
            return false;
        }
        return MessageDigest.isEqual(s256(codeVerifier).getBytes(StandardCharsets.US_ASCII),
                codeChallenge.getBytes(StandardCharsets.US_ASCII));
    }
}
