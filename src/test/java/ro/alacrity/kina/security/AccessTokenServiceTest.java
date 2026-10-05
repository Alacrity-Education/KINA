package ro.alacrity.kina.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import ro.alacrity.kina.security.AccessTokenRepository.AccessToken;
import ro.alacrity.kina.security.AccessTokenService.IssuedToken;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DevModeIntegrationTest
class AccessTokenServiceTest {

    @Autowired
    AccessTokenRepository repository;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcClient jdbc;

    MutableClock clock;
    AccessTokenService service;
    UUID userId;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        service = new AccessTokenService(repository, Duration.ofDays(30), clock);
        userId = users.upsert("test", "user-" + UUID.randomUUID(), "u@example.com", "Test User", null).id();
    }

    @Test
    void plaintextFormatAndHashStorage() {
        IssuedToken issued = service.create(userId, "laptop", null, null);

        assertThat(issued.plaintext()).matches("^kina_[A-Za-z0-9_-]{43}$");
        assertThat(AccessTokenService.hasValidFormat(issued.plaintext())).isTrue();
        assertThat(issued.token().tokenPrefix()).isEqualTo(issued.plaintext().substring(0, 12));
        assertThat(issued.token().expiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(30)));
        assertThat(issued.toString()).doesNotContain(issued.plaintext());

        String storedHash = jdbc.sql("SELECT token_hash FROM access_tokens WHERE id = ?")
                .param(issued.token().id()).query(String.class).single();
        assertThat(storedHash).isEqualTo(AccessTokenService.hash(issued.plaintext()))
                .matches("^[0-9a-f]{64}$")
                .doesNotContain(issued.plaintext());
        // Known SHA-256 vector.
        assertThat(SecureTokens.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        // Two tokens never collide.
        assertThat(service.create(userId, "other", null, null).plaintext()).isNotEqualTo(issued.plaintext());
    }

    @Test
    void validatesActiveTokensOnly() {
        IssuedToken issued = service.create(userId, "laptop", null, null);

        assertThat(service.validate(issued.plaintext())).map(AccessToken::id).contains(issued.token().id());
        assertThat(service.validate("kina_" + "A".repeat(43))).isEmpty();
        assertThat(service.validate("not-a-token")).isEmpty();
        assertThat(service.validate(null)).isEmpty();
    }

    @Test
    void expiresAfterValidity() {
        IssuedToken issued = service.create(userId, "laptop", null, null);

        clock.advance(Duration.ofDays(30).minusSeconds(1));
        assertThat(service.validate(issued.plaintext())).isPresent();
        clock.advance(Duration.ofSeconds(1));
        assertThat(service.validate(issued.plaintext())).isEmpty();
        assertThat(service.list(userId)).singleElement()
                .satisfies(t -> assertThat(t.status(clock.instant())).isEqualTo("expired"));
    }

    @Test
    void revokeOnlyByOwner() {
        IssuedToken issued = service.create(userId, "laptop", null, null);
        UUID otherUser = users.upsert("test", "other-" + UUID.randomUUID(), null, "Other", null).id();

        assertThat(service.revoke(otherUser, issued.token().id())).isFalse();
        assertThat(service.validate(issued.plaintext())).isPresent();

        assertThat(service.revoke(userId, issued.token().id())).isTrue();
        assertThat(service.revoke(userId, issued.token().id())).isFalse();
        assertThat(service.validate(issued.plaintext())).isEmpty();
        assertThat(service.list(userId)).singleElement()
                .satisfies(t -> assertThat(t.status(clock.instant())).isEqualTo("revoked"));
    }

    @Test
    void lastUsedIsThrottledToOncePerMinute() {
        IssuedToken issued = service.create(userId, "laptop", null, null);
        UUID id = issued.token().id();
        Instant first = clock.instant();

        service.validate(issued.plaintext());
        assertThat(lastUsed(id)).isEqualTo(first);

        clock.advance(Duration.ofSeconds(59));
        service.validate(issued.plaintext());
        assertThat(lastUsed(id)).isEqualTo(first);

        clock.advance(Duration.ofSeconds(2));
        service.validate(issued.plaintext());
        assertThat(lastUsed(id)).isEqualTo(first.plusSeconds(61));
    }

    @Test
    void nameIsRequired() {
        assertThatThrownBy(() -> service.create(userId, "  ", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Instant lastUsed(UUID id) {
        return repository.findById(id).orElseThrow().lastUsedAt();
    }
}
