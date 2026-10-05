package ro.alacrity.kina.oauth;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import ro.alacrity.kina.oauth.OAuthClientRepository.OAuthClient;
import ro.alacrity.kina.security.SecureTokens;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Client authentication at the token and revocation endpoints (RFC 6749 section 2.3): {@code client_secret_basic}
 * (HTTP Basic with URL-encoded id and secret), {@code client_secret_post} (form parameters) or {@code none} (public
 * clients identify themselves with {@code client_id}). A client registered with a secret must present it. A
 * {@code client_id} that is an {@code https} URL is a Client ID Metadata Document client (public, trusted hosts only).
 */
@Component
@RequiredArgsConstructor
public class ClientAuthenticator {

    private static final String BASIC = "Basic ";

    private final OAuthClientLookup clients;

    public OAuthClient authenticate(HttpServletRequest request, MultiValueMap<String, String> params) {
        String clientId;
        String secret;
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String formClientId = TokenController.single(params, "client_id");
        String formSecret = TokenController.single(params, "client_secret");
        if (header != null && header.regionMatches(true, 0, BASIC, 0, BASIC.length())) {
            if (formSecret != null) {
                throw new OAuthException(OAuthException.INVALID_REQUEST,
                        "Multiple client authentication methods used");
            }
            String[] credentials = decodeBasic(header.substring(BASIC.length()).strip());
            clientId = credentials[0];
            secret = credentials[1];
            if (formClientId != null && !formClientId.equals(clientId)) {
                throw new OAuthException(OAuthException.INVALID_CLIENT, "client_id mismatch");
            }
        } else {
            clientId = formClientId;
            secret = formSecret;
        }
        if (clientId == null || clientId.isBlank()) {
            throw new OAuthException(OAuthException.INVALID_CLIENT, "Client authentication failed");
        }
        OAuthClient client = clients.findOptional(clientId)
                .orElseThrow(() -> new OAuthException(OAuthException.INVALID_CLIENT, "Client authentication failed"));
        if (!client.isPublic()) {
            if (secret == null || secret.isEmpty() || !SecureTokens.constantTimeEquals(
                    SecureTokens.sha256Hex(secret), client.clientSecretHash())) {
                throw new OAuthException(OAuthException.INVALID_CLIENT, "Client authentication failed");
            }
        }
        return client;
    }

    private static String[] decodeBasic(String encoded) {
        try {
            String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon < 0) {
                throw new OAuthException(OAuthException.INVALID_CLIENT, "Malformed Basic credentials");
            }
            return new String[]{
                    URLDecoder.decode(decoded.substring(0, colon), StandardCharsets.UTF_8),
                    URLDecoder.decode(decoded.substring(colon + 1), StandardCharsets.UTF_8)};
        } catch (IllegalArgumentException e) {
            throw new OAuthException(OAuthException.INVALID_CLIENT, "Malformed Basic credentials");
        }
    }
}
