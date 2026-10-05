package ro.alacrity.kina.oauth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** {@code oauth_authorization_codes}: single-use authorization codes, stored as SHA-256 hex. */
@Repository
public class AuthorizationCodeRepository {

    /** Expired codes are purged this long after expiry. */
    private static final Duration RETENTION = Duration.ofDays(1);

    private final JdbcClient jdbc;

    public AuthorizationCodeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record AuthorizationCode(String codeHash, String clientId, UUID userId, String redirectUri, String scope,
                                    String resource, String codeChallenge, String codeChallengeMethod,
                                    Instant createdAt, Instant expiresAt, Instant usedAt) {
    }

    public void insert(AuthorizationCode code) {
        jdbc.sql("DELETE FROM oauth_authorization_codes WHERE expires_at < ?")
                .param(Timestamp.from(code.createdAt().minus(RETENTION)))
                .update();
        jdbc.sql("""
                        INSERT INTO oauth_authorization_codes (code_hash, client_id, user_id, redirect_uri, scope, resource,
                                                               code_challenge, code_challenge_method, created_at, expires_at)
                        VALUES (:hash, :clientId, :userId, :redirectUri, :scope, :resource, :challenge, :method,
                                :createdAt, :expiresAt)""")
                .param("hash", code.codeHash())
                .param("clientId", code.clientId())
                .param("userId", code.userId())
                .param("redirectUri", code.redirectUri())
                .param("scope", code.scope())
                .param("resource", code.resource())
                .param("challenge", code.codeChallenge())
                .param("method", code.codeChallengeMethod())
                .param("createdAt", Timestamp.from(code.createdAt()))
                .param("expiresAt", Timestamp.from(code.expiresAt()))
                .update();
    }

    public Optional<AuthorizationCode> findByHash(String codeHash) {
        return jdbc.sql("""
                        SELECT code_hash, client_id, user_id, redirect_uri, scope, resource, code_challenge,
                               code_challenge_method, created_at, expires_at, used_at
                        FROM oauth_authorization_codes WHERE code_hash = ?""")
                .param(codeHash)
                .query(AuthorizationCodeRepository::map)
                .optional();
    }

    /** Atomically marks the code used. Returns false when it was already used (replay). */
    public boolean markUsed(String codeHash, Instant now) {
        return jdbc.sql("UPDATE oauth_authorization_codes SET used_at = ? WHERE code_hash = ? AND used_at IS NULL")
                .param(Timestamp.from(now))
                .param(codeHash)
                .update() > 0;
    }

    private static AuthorizationCode map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp used = rs.getTimestamp("used_at");
        return new AuthorizationCode(
                rs.getString("code_hash"),
                rs.getString("client_id"),
                rs.getObject("user_id", UUID.class),
                rs.getString("redirect_uri"),
                rs.getString("scope"),
                rs.getString("resource"),
                rs.getString("code_challenge"),
                rs.getString("code_challenge_method"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                used == null ? null : used.toInstant());
    }
}
