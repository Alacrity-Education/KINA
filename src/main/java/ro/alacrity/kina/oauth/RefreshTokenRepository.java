package ro.alacrity.kina.oauth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** {@code oauth_refresh_tokens}: refresh tokens, stored as SHA-256 hex, rotated on every use. */
@Repository
public class RefreshTokenRepository {

    private final JdbcClient jdbc;

    public RefreshTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record RefreshToken(String tokenHash, String clientId, UUID userId, UUID accessTokenId, String scope,
                               Instant createdAt, Instant expiresAt, Instant revokedAt) {

        public boolean isActive(Instant now) {
            return revokedAt == null && expiresAt.isAfter(now);
        }
    }

    public void insert(RefreshToken token) {
        jdbc.sql("""
                        INSERT INTO oauth_refresh_tokens (token_hash, client_id, user_id, access_token_id, scope,
                                                          created_at, expires_at)
                        VALUES (:hash, :clientId, :userId, :accessTokenId, :scope, :createdAt, :expiresAt)""")
                .param("hash", token.tokenHash())
                .param("clientId", token.clientId())
                .param("userId", token.userId())
                .param("accessTokenId", token.accessTokenId())
                .param("scope", token.scope())
                .param("createdAt", Timestamp.from(token.createdAt()))
                .param("expiresAt", Timestamp.from(token.expiresAt()))
                .update();
    }

    public Optional<RefreshToken> findByHash(String tokenHash) {
        return jdbc.sql("""
                        SELECT token_hash, client_id, user_id, access_token_id, scope, created_at, expires_at, revoked_at
                        FROM oauth_refresh_tokens WHERE token_hash = ?""")
                .param(tokenHash)
                .query(RefreshTokenRepository::map)
                .optional();
    }

    /** Atomically revokes the token. Returns false when it was already revoked (concurrent rotation / replay). */
    public boolean revoke(String tokenHash, Instant now) {
        return jdbc.sql("UPDATE oauth_refresh_tokens SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL")
                .param(Timestamp.from(now))
                .param(tokenHash)
                .update() > 0;
    }

    private static RefreshToken map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp revoked = rs.getTimestamp("revoked_at");
        return new RefreshToken(
                rs.getString("token_hash"),
                rs.getString("client_id"),
                rs.getObject("user_id", UUID.class),
                rs.getObject("access_token_id", UUID.class),
                rs.getString("scope"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                revoked == null ? null : revoked.toInstant());
    }
}
