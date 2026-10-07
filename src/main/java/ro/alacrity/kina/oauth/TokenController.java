package ro.alacrity.kina.oauth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.metrics.KinaMetrics;
import ro.alacrity.kina.oauth.AuthorizationCodeRepository.AuthorizationCode;
import ro.alacrity.kina.oauth.OAuthClientRepository.OAuthClient;
import ro.alacrity.kina.oauth.RefreshTokenRepository.RefreshToken;
import ro.alacrity.kina.security.AccessTokenService;
import ro.alacrity.kina.security.AccessTokenService.IssuedToken;
import ro.alacrity.kina.security.MembershipVerifier;
import ro.alacrity.kina.security.SecureTokens;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Token endpoint (RFC 6749 / OAuth 2.1): {@code authorization_code} with mandatory PKCE and {@code refresh_token} with
 * rotation. Access tokens are regular KINA access tokens ({@link AccessTokenService}, {@code
 * kina.oauth.access-token-validity} (1 hour), named {@code MCP: <client_name>}); refresh tokens live in
 * {@code oauth_refresh_tokens} ({@code kina.oauth.refresh-token-validity}, 30 days). Refresh grants re-check the user's
 * group membership ({@link MembershipVerifier}).
 */
@RestController
public class TokenController extends OAuthEndpointSupport {

    public static final String GRANT_AUTHORIZATION_CODE = "authorization_code";
    public static final String GRANT_REFRESH_TOKEN = "refresh_token";
    public static final String REFRESH_TOKEN_PREFIX = "kina_rt_";

    @Autowired private ClientAuthenticator clientAuthenticator;
    @Autowired private AuthorizationCodeRepository codes;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private AccessTokenService accessTokens;
    @Autowired private OAuthClientRepository clients;
    @Autowired private MembershipVerifier membership;
    @Autowired private TransactionTemplate transactions;
    @Autowired private KinaProperties properties;
    @Autowired private KinaMetrics metrics = KinaMetrics.NOOP;
    private final Clock clock = Clock.systemUTC();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TokenResponse(@JsonProperty("access_token") String accessToken,
                                @JsonProperty("token_type") String tokenType,
                                @JsonProperty("expires_in") long expiresIn,
                                @JsonProperty("refresh_token") String refreshToken,
                                @JsonProperty("scope") String scope) {

        @Override
        public String toString() {
            return "TokenResponse[tokenType=" + tokenType + ", expiresIn=" + expiresIn + ", scope=" + scope + "]";
        }
    }

    @PostMapping(path = "/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TokenResponse> token(@RequestParam MultiValueMap<String, String> params,
                                               HttpServletRequest request) {
        String grantType = single(params, "grant_type");
        if (grantType == null) {
            throw new OAuthException(OAuthException.INVALID_REQUEST, "grant_type is required");
        }
        OAuthClient client = clientAuthenticator.authenticate(request, params);
        if (!GRANT_AUTHORIZATION_CODE.equals(grantType) && !GRANT_REFRESH_TOKEN.equals(grantType)) {
            throw new OAuthException(OAuthException.UNSUPPORTED_GRANT_TYPE, "Unsupported grant_type: " + grantType);
        }
        if (!client.grantTypes().contains(grantType)) {
            throw new OAuthException(OAuthException.UNAUTHORIZED_CLIENT,
                    "Client is not allowed to use grant_type " + grantType);
        }
        TokenResponse response = GRANT_AUTHORIZATION_CODE.equals(grantType)
                ? authorizationCode(client, params)
                : refreshToken(client, params);
        metrics.oauthTokenIssued(grantType);
        return ResponseEntity.ok().headers(noStore()).body(response);
    }

    private TokenResponse authorizationCode(OAuthClient client, MultiValueMap<String, String> params) {
        String code = single(params, "code");
        String codeVerifier = single(params, "code_verifier");
        String redirectUri = single(params, "redirect_uri");
        if (code == null) {
            throw new OAuthException(OAuthException.INVALID_REQUEST, "code is required");
        }
        if (codeVerifier == null) {
            throw new OAuthException(OAuthException.INVALID_REQUEST, "code_verifier is required (PKCE)");
        }
        Instant now = now();
        String codeHash = SecureTokens.sha256Hex(code);
        AuthorizationCode stored = codes.findByHash(codeHash)
                .orElseThrow(() -> invalidGrant("Invalid authorization code"));
        if (!stored.clientId().equals(client.clientId())) {
            throw invalidGrant("Authorization code was issued to another client");
        }
        // Single use: the first redemption attempt consumes the code (committed on its own, before any check that
        // could fail), so a failed PKCE attempt cannot be retried with another verifier.
        if (stored.usedAt() != null || !codes.markUsed(codeHash, now)) {
            throw invalidGrant("Authorization code already used");
        }
        if (!stored.expiresAt().isAfter(now)) {
            throw invalidGrant("Authorization code expired");
        }
        if (redirectUri != null && !redirectUri.equals(stored.redirectUri())) {
            throw invalidGrant("redirect_uri does not match the authorization request");
        }
        if (!Pkce.verify(codeVerifier, stored.codeChallenge(), stored.codeChallengeMethod())) {
            throw invalidGrant("PKCE verification failed");
        }
        if (membership.isBlocked(stored.userId())) {
            throw invalidGrant("Access to KINA was revoked; sign in again");
        }
        return issue(client, stored.userId(), stored.scope(), now);
    }

    private TokenResponse refreshToken(OAuthClient client, MultiValueMap<String, String> params) {
        String presented = single(params, "refresh_token");
        if (presented == null) {
            throw new OAuthException(OAuthException.INVALID_REQUEST, "refresh_token is required");
        }
        String requestedScope = single(params, "scope");
        Instant now = now();
        String hash = SecureTokens.sha256Hex(presented);
        RefreshToken stored = refreshTokens.findByHash(hash).orElseThrow(() -> invalidGrant("Invalid refresh token"));
        if (!stored.clientId().equals(client.clientId())) {
            throw invalidGrant("Refresh token was issued to another client");
        }
        if (!stored.isActive(now)) {
            throw invalidGrant("Refresh token expired or revoked");
        }
        if (requestedScope != null && !scopeSubset(requestedScope, stored.scope())) {
            throw new OAuthException("invalid_scope", "Requested scope exceeds the original grant");
        }
        // Group authorisation (DESIGN.md 7.1): re-verify membership with the identity provider when due. Checked
        // before rotation; a refusal is invalid_grant, which makes Claude ask the user to reconnect.
        MembershipVerifier.Verdict verdict = membership.checkRefreshGrant(stored.userId());
        if (!verdict.allowed()) {
            throw invalidGrant(verdict.description());
        }
        // Rotation: atomically revoke the presented token (a concurrent second use loses) and its access token.
        if (!refreshTokens.revoke(hash, now)) {
            throw invalidGrant("Refresh token expired or revoked");
        }
        if (stored.accessTokenId() != null) {
            accessTokens.revoke(stored.accessTokenId());
        }
        return issue(client, stored.userId(), stored.scope(), now);
    }

    private TokenResponse issue(OAuthClient client, UUID userId, String scope, Instant now) {
        TokenResponse response = transactions.execute(status -> issueInTransaction(client, userId, scope, now));
        if (response == null) {
            throw new IllegalStateException("Token issuance returned no result");
        }
        return response;
    }

    private TokenResponse issueInTransaction(OAuthClient client, UUID userId, String scope, Instant now) {
        IssuedToken access = accessTokens.create(userId, "MCP: " + client.displayName(), scope, client.clientId(),
                properties.oauth().accessTokenValidity());
        clients.touchLastUsed(client.clientId(), now);
        String refreshPlaintext = null;
        if (client.grantTypes().contains(GRANT_REFRESH_TOKEN)) {
            refreshPlaintext = REFRESH_TOKEN_PREFIX + SecureTokens.randomBase64Url(32);
            refreshTokens.insert(new RefreshToken(SecureTokens.sha256Hex(refreshPlaintext), client.clientId(), userId,
                    access.token().id(), scope, now, now.plus(properties.oauth().refreshTokenValidity()), null));
        }
        long expiresIn = Duration.between(now, access.token().expiresAt()).toSeconds();
        return new TokenResponse(access.plaintext(), "Bearer", Math.max(expiresIn, 0), refreshPlaintext, scope);
    }

    private static boolean scopeSubset(String requested, String granted) {
        List<String> grantedScopes = granted == null ? List.of() : List.of(granted.trim().split("\\s+"));
        for (String scope : requested.trim().split("\\s+")) {
            if (!scope.isEmpty() && !grantedScopes.contains(scope)) {
                return false;
            }
        }
        return true;
    }

    private static OAuthException invalidGrant(String description) {
        return new OAuthException(OAuthException.INVALID_GRANT, description);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** A single-valued form parameter; repeated parameters are an {@code invalid_request} (RFC 6749 section 3.2). */
    static String single(MultiValueMap<String, String> params, String name) {
        List<String> values = params.get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() > 1) {
            throw new OAuthException(OAuthException.INVALID_REQUEST, "Parameter " + name + " is repeated");
        }
        String value = values.getFirst();
        return value == null || value.isEmpty() ? null : value;
    }
}
