package ro.alacrity.kina.oauth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.alacrity.kina.oauth.OAuthClientRepository.OAuthClient;
import ro.alacrity.kina.oauth.RefreshTokenRepository.RefreshToken;
import ro.alacrity.kina.security.AccessTokenService;
import ro.alacrity.kina.security.SecureTokens;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Token revocation (RFC 7009). The client authenticates like at the token endpoint; an access or refresh token that
 * belongs to that client is revoked (a refresh token together with its access token). Unknown tokens and tokens of
 * other clients are ignored. The response is always 200 once the client is authenticated.
 */
@RestController
public class RevocationController extends OAuthEndpointSupport {

    private final ClientAuthenticator clientAuthenticator;
    private final RefreshTokenRepository refreshTokens;
    private final AccessTokenService accessTokens;

    public RevocationController(ClientAuthenticator clientAuthenticator, RefreshTokenRepository refreshTokens,
                                AccessTokenService accessTokens) {
        this.clientAuthenticator = clientAuthenticator;
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
    }

    @PostMapping(path = "/oauth/revoke", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> revoke(@RequestParam MultiValueMap<String, String> params,
                                       HttpServletRequest request) {
        String token = TokenController.single(params, "token");
        OAuthClient client = clientAuthenticator.authenticate(request, params);
        if (token == null) {
            throw new OAuthException(OAuthException.INVALID_REQUEST, "token is required");
        }
        if (!revokeRefreshToken(client, token)) {
            accessTokens.find(token)
                    .filter(accessToken -> client.clientId().equals(accessToken.oauthClientId()))
                    .ifPresent(accessToken -> accessTokens.revoke(accessToken.id()));
        }
        return ResponseEntity.ok().headers(noStore()).build();
    }

    private boolean revokeRefreshToken(OAuthClient client, String token) {
        if (!token.startsWith(TokenController.REFRESH_TOKEN_PREFIX)) {
            return false;
        }
        String hash = SecureTokens.sha256Hex(token);
        Optional<RefreshToken> stored = refreshTokens.findByHash(hash)
                .filter(refreshToken -> refreshToken.clientId().equals(client.clientId()));
        stored.ifPresent(refreshToken -> {
            refreshTokens.revoke(hash, Instant.now().truncatedTo(ChronoUnit.MICROS));
            if (refreshToken.accessTokenId() != null) {
                accessTokens.revoke(refreshToken.accessTokenId());
            }
        });
        return stored.isPresent();
    }
}
