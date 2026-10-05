package ro.alacrity.kina.security;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** {@code users} table. Users are identified by (issuer, subject). */
@Repository
public class UserRepository {

    public static final String DEV_ISSUER = "dev";
    public static final String DEV_SUBJECT = "admin";
    public static final String DEV_DISPLAY_NAME = "Development Admin";

    private static final RowMapper<User> MAPPER = (rs, n) -> new User(rs.getObject("id", UUID.class),
            rs.getString("issuer"), rs.getString("subject"), rs.getString("email"), rs.getString("display_name"));

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record User(UUID id, String issuer, String subject, String email, String displayName) {

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
                        RETURNING id, issuer, subject, email, display_name""")
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
        return jdbc.sql("SELECT id, issuer, subject, email, display_name FROM users WHERE id = ?")
                .param(id)
                .query(MAPPER)
                .optional();
    }
}
