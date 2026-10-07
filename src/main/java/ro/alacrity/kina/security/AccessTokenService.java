package ro.alacrity.kina.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.security.AccessTokenRepository.AccessToken;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Issues, validates and revokes KINA access tokens (DESIGN.md section 6). Plaintext format {@code kina_} + 43
 * base64url characters (32 random bytes). Only the SHA-256 hex digest is stored; the plaintext is returned exactly once
 * from {@link #create} and is never logged.
 */
@Service
public class AccessTokenService {

    public static final String TOKEN_PREFIX = "kina_";
    public static final int DISPLAY_PREFIX_LENGTH = 12;
    public static final int MAX_NAME_LENGTH = 100;
    static final Duration LAST_USED_THROTTLE = Duration.ofMinutes(1);
    private static final Pattern FORMAT = Pattern.compile("^kina_[A-Za-z0-9_-]{43}$");

    @Autowired private AccessTokenRepository repository;
    @Autowired private KinaProperties properties;
    private Clock clock = Clock.systemUTC();

    /** A freshly issued token: {@code plaintext} must be shown to the user once and then discarded. */
    public record IssuedToken(String plaintext, AccessToken token) {

        @Override
        public String toString() {
            return "IssuedToken[id=" + token.id() + ", prefix=" + token.tokenPrefix() + "]";
        }
    }

    public Duration validity() {
        return properties.tokens().validity();
    }

    /** Generates a new plaintext token ({@code kina_} + 43 base64url chars). */
    public static String generatePlaintext() {
        return TOKEN_PREFIX + SecureTokens.randomBase64Url(32);
    }

    /** SHA-256 hex of the plaintext token, as stored in {@code access_tokens.token_hash}. */
    public static String hash(String plaintext) {
        return SecureTokens.sha256Hex(plaintext);
    }

    public static boolean hasValidFormat(String plaintext) {
        return plaintext != null && FORMAT.matcher(plaintext).matches();
    }

    /**
     * Creates a token for {@code userId}. {@code oauthClientId} is null for tokens created in the web UI.
     */
    public IssuedToken create(UUID userId, String name, String scope, String oauthClientId) {
        return create(userId, name, scope, oauthClientId, validity());
    }

    /** Like {@link #create(UUID, String, String, String)} with an explicit lifetime (OAuth access tokens). */
    public IssuedToken create(UUID userId, String name, String scope, String oauthClientId, Duration lifetime) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Token name is required");
        }
        String trimmed = name.strip();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            trimmed = trimmed.substring(0, MAX_NAME_LENGTH);
        }
        String plaintext = generatePlaintext();
        Instant now = now();
        AccessToken token = new AccessToken(UUID.randomUUID(), userId, trimmed,
                plaintext.substring(0, DISPLAY_PREFIX_LENGTH), scope, oauthClientId, now, now.plus(lifetime), null,
                null);
        repository.insert(token, hash(plaintext));
        return new IssuedToken(plaintext, token);
    }

    /**
     * Validates a presented plaintext token: known, unexpired, unrevoked. Records {@code last_used_at} at most once
     * per minute.
     */
    public Optional<AccessToken> validate(String plaintext) {
        if (!hasValidFormat(plaintext)) {
            return Optional.empty();
        }
        Instant now = now();
        Optional<AccessToken> found = repository.findByHash(hash(plaintext)).filter(t -> t.isActive(now));
        found.ifPresent(token -> {
            Instant threshold = now.minus(LAST_USED_THROTTLE);
            if (token.lastUsedAt() == null || token.lastUsedAt().isBefore(threshold)) {
                repository.touchLastUsed(token.id(), now, threshold);
            }
        });
        return found;
    }

    /** Looks up a token by its plaintext without validity checks or usage tracking (for revocation). */
    public Optional<AccessToken> find(String plaintext) {
        if (!hasValidFormat(plaintext)) {
            return Optional.empty();
        }
        return repository.findByHash(hash(plaintext));
    }

    public List<AccessToken> list(UUID userId) {
        return repository.findByUser(userId);
    }

    /** Revokes a token owned by {@code userId}. Returns false when not found, not owned or already revoked. */
    public boolean revoke(UUID userId, UUID tokenId) {
        return repository.revokeForUser(tokenId, userId, now());
    }

    /** Revokes a token regardless of owner (OAuth refresh rotation and RFC 7009 revocation). */
    public boolean revoke(UUID tokenId) {
        return repository.revoke(tokenId, now());
    }

    /** Revokes every access and OAuth refresh token of the user. Returns the number of access tokens revoked. */
    public int revokeAllForUser(UUID userId) {
        return repository.revokeAllForUser(userId, now());
    }

    private Instant now() {
        // Postgres TIMESTAMPTZ has microsecond precision; truncate so values round-trip exactly.
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
