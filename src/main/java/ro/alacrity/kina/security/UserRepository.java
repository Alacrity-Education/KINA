package ro.alacrity.kina.security;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** {@code users} table. Users are identified by (issuer, subject). */
@Repository
@RequiredArgsConstructor
public class UserRepository {

    public static final String DEV_ISSUER = "dev";
    public static final String DEV_SUBJECT = "admin";
    public static final String DEV_DISPLAY_NAME = "Development Admin";

    private static final String COLUMNS = "id, issuer, subject, email, display_name, access_revoked_at, "
            + "membership_checked_at, upstream_refresh_token IS NOT NULL AS has_upstream_refresh_token";

    private static final RowMapper<User> MAPPER = (rs, n) -> new User(rs.getObject("id", UUID.class),
            rs.getString("issuer"), rs.getString("subject"), rs.getString("email"), rs.getString("display_name"),
            instant(rs.getTimestamp("access_revoked_at")), instant(rs.getTimestamp("membership_checked_at")),
            rs.getBoolean("has_upstream_refresh_token"));

    private final JdbcClient jdbc;

    /**
     * A user row. {@code accessRevokedAt} is set while the user is blocked (failed group check or the provider
     * rejected the stored grant); {@code membershipCheckedAt} is the last successful membership check.
     */
    public record User(UUID id, String issuer, String subject, String email, String displayName,
                       Instant accessRevokedAt, Instant membershipCheckedAt, boolean hasUpstreamRefreshToken) {

        public User(UUID id, String issuer, String subject, String email, String displayName) {
            this(id, issuer, subject, email, displayName, null, null, false);
        }

        public boolean isRevoked() {
            return accessRevokedAt != null;
        }

        public KinaPrincipal toPrincipal() {
            return new KinaPrincipal(id, displayName, null);
        }
    }

    /**
     * Inserts or updates the user identified by (issuer, subject); email and display name are refreshed when given.
     * {@code loginAt} (nullable) is recorded as {@code last_login_at}.
     */
    public User upsert(String issuer, String subject, String email, String displayName, Instant loginAt) {
        return jdbc.sql("""
                        INSERT INTO users (id, issuer, subject, email, display_name, last_login_at)
                        VALUES (:id, :issuer, :subject, :email, :displayName, :loginAt)
                        ON CONFLICT (issuer, subject) DO UPDATE SET
                          email = COALESCE(EXCLUDED.email, users.email),
                          display_name = COALESCE(EXCLUDED.display_name, users.display_name),
                          last_login_at = COALESCE(EXCLUDED.last_login_at, users.last_login_at)
                        RETURNING\s""" + COLUMNS)
                .param("id", UUID.randomUUID())
                .param("issuer", issuer)
                .param("subject", subject)
                .param("email", email)
                .param("displayName", displayName)
                .param("loginAt", loginAt == null ? null : Timestamp.from(loginAt))
                .query(MAPPER)
                .single();
    }

    /** The development-mode admin row ({@code issuer='dev', subject='admin'}), created when missing. */
    public User devAdmin() {
        return upsert(DEV_ISSUER, DEV_SUBJECT, null, DEV_DISPLAY_NAME, null);
    }

    public Optional<User> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE id = ?")
                .param(id)
                .query(MAPPER)
                .optional();
    }

    public Optional<User> findByIssuerAndSubject(String issuer, String subject) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE issuer = ? AND subject = ?")
                .param(issuer)
                .param(subject)
                .query(MAPPER)
                .optional();
    }

    /**
     * Records a successful membership check: sets {@code membership_checked_at} and clears {@code access_revoked_at}
     * when {@code clearRevocation} (an interactive login that passed every check).
     */
    public void markMembershipChecked(UUID id, Instant checkedAt, boolean clearRevocation) {
        jdbc.sql("UPDATE users SET membership_checked_at = :at"
                        + (clearRevocation ? ", access_revoked_at = NULL" : "") + " WHERE id = :id")
                .param("at", Timestamp.from(checkedAt))
                .param("id", id)
                .update();
    }

    /** Blocks the user (keeps an earlier revocation time) and forgets the upstream refresh token. */
    public void markRevoked(UUID id, Instant revokedAt) {
        jdbc.sql("""
                        UPDATE users SET access_revoked_at = COALESCE(access_revoked_at, :at),
                                         upstream_refresh_token = NULL,
                                         upstream_refresh_token_updated_at = :at
                        WHERE id = :id""")
                .param("at", Timestamp.from(revokedAt))
                .param("id", id)
                .update();
    }

    /** Stores (or with {@code null} clears) the encrypted upstream refresh token. */
    public void storeUpstreamRefreshToken(UUID id, String ciphertext, Instant updatedAt) {
        jdbc.sql("""
                        UPDATE users SET upstream_refresh_token = :token, upstream_refresh_token_updated_at = :at
                        WHERE id = :id""")
                .param("token", ciphertext)
                .param("at", Timestamp.from(updatedAt))
                .param("id", id)
                .update();
    }

    /** The encrypted upstream refresh token, if one is stored. */
    public Optional<String> upstreamRefreshToken(UUID id) {
        return jdbc.sql("SELECT upstream_refresh_token FROM users WHERE id = ?")
                .param(id)
                .query((rs, n) -> rs.getString(1))
                .list().stream()
                .filter(token -> token != null && !token.isEmpty())
                .findFirst();
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
