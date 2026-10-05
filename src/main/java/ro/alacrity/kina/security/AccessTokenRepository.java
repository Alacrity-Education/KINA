package ro.alacrity.kina.security;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@code access_tokens} table. Only SHA-256 hashes of tokens are stored. */
@Repository
@RequiredArgsConstructor
public class AccessTokenRepository {

    private static final String COLUMNS = "id, user_id, name, token_prefix, scope, oauth_client_id, created_at, "
            + "expires_at, revoked_at, last_used_at";

    private static final RowMapper<AccessToken> MAPPER = AccessTokenRepository::map;

    private final JdbcClient jdbc;

    /** Stored metadata of an access token (never the plaintext, never the hash). */
    public record AccessToken(UUID id, UUID userId, String name, String tokenPrefix, String scope,
                              String oauthClientId, Instant createdAt, Instant expiresAt, Instant revokedAt,
                              Instant lastUsedAt) {

        public boolean isRevoked() {
            return revokedAt != null;
        }

        public boolean isExpired(Instant now) {
            return !expiresAt.isAfter(now);
        }

        public boolean isActive(Instant now) {
            return !isRevoked() && !isExpired(now);
        }

        /** {@code active}, {@code expired} or {@code revoked}. */
        public String status(Instant now) {
            return isRevoked() ? "revoked" : isExpired(now) ? "expired" : "active";
        }
    }

    public void insert(AccessToken token, String tokenHash) {
        jdbc.sql("""
                        INSERT INTO access_tokens (id, user_id, name, token_hash, token_prefix, scope, oauth_client_id,
                                                   created_at, expires_at)
                        VALUES (:id, :userId, :name, :hash, :prefix, :scope, :clientId, :createdAt, :expiresAt)""")
                .param("id", token.id())
                .param("userId", token.userId())
                .param("name", token.name())
                .param("hash", tokenHash)
                .param("prefix", token.tokenPrefix())
                .param("scope", token.scope())
                .param("clientId", token.oauthClientId())
                .param("createdAt", Timestamp.from(token.createdAt()))
                .param("expiresAt", Timestamp.from(token.expiresAt()))
                .update();
    }

    public Optional<AccessToken> findByHash(String tokenHash) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM access_tokens WHERE token_hash = ?")
                .param(tokenHash)
                .query(MAPPER)
                .optional();
    }

    public Optional<AccessToken> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM access_tokens WHERE id = ?")
                .param(id)
                .query(MAPPER)
                .optional();
    }

    /** All tokens of a user, newest first. */
    public List<AccessToken> findByUser(UUID userId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM access_tokens WHERE user_id = ? ORDER BY created_at DESC, id")
                .param(userId)
                .query(MAPPER)
                .list();
    }

    /**
     * Sets {@code revoked_at} when not yet revoked and revokes every OAuth refresh token issued together with this
     * access token, so an OAuth client cannot mint a new access token after the user revoked it. Returns true when
     * the access token row changed.
     */
    @Transactional
    public boolean revoke(UUID id, Instant now) {
        boolean changed = jdbc.sql("UPDATE access_tokens SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL")
                .param(Timestamp.from(now))
                .param(id)
                .update() > 0;
        revokeLinkedRefreshTokens(id, now);
        return changed;
    }

    /** Like {@link #revoke(UUID, Instant)} but only for a token owned by {@code userId}. */
    @Transactional
    public boolean revokeForUser(UUID id, UUID userId, Instant now) {
        boolean changed = jdbc.sql(
                        "UPDATE access_tokens SET revoked_at = ? WHERE id = ? AND user_id = ? AND revoked_at IS NULL")
                .param(Timestamp.from(now))
                .param(id)
                .param(userId)
                .update() > 0;
        if (changed) {
            revokeLinkedRefreshTokens(id, now);
        }
        return changed;
    }

    /**
     * Revokes every active access token and every active OAuth refresh token of the user (group membership lost or
     * the identity provider rejected the user's grant). Returns the number of access tokens revoked.
     */
    @Transactional
    public int revokeAllForUser(UUID userId, Instant now) {
        int revoked = jdbc.sql("UPDATE access_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL")
                .param(Timestamp.from(now))
                .param(userId)
                .update();
        jdbc.sql("UPDATE oauth_refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL")
                .param(Timestamp.from(now))
                .param(userId)
                .update();
        return revoked;
    }

    private void revokeLinkedRefreshTokens(UUID accessTokenId, Instant now) {
        jdbc.sql("UPDATE oauth_refresh_tokens SET revoked_at = ? WHERE access_token_id = ? AND revoked_at IS NULL")
                .param(Timestamp.from(now))
                .param(accessTokenId)
                .update();
    }

    /** Records usage unless it was recorded after {@code notAfter} (throttling). Returns true when updated. */
    public boolean touchLastUsed(UUID id, Instant now, Instant notAfter) {
        return jdbc.sql("""
                        UPDATE access_tokens SET last_used_at = :now
                        WHERE id = :id AND (last_used_at IS NULL OR last_used_at < :threshold)""")
                .param("now", Timestamp.from(now))
                .param("id", id)
                .param("threshold", Timestamp.from(notAfter))
                .update() > 0;
    }

    private static AccessToken map(ResultSet rs, int rowNum) throws SQLException {
        return new AccessToken(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("name"),
                rs.getString("token_prefix"),
                rs.getString("scope"),
                rs.getString("oauth_client_id"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("expires_at")),
                instant(rs.getTimestamp("revoked_at")),
                instant(rs.getTimestamp("last_used_at")));
    }

    static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
