package ro.alacrity.kina.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import ro.alacrity.kina.TestWiring;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Discovery is lazy and cached, but while membership re-checks want {@code offline_access} and the provider does not
 * list it, discovery runs again at the first login after {@link LazyOidcClientRegistrationRepository#REDISCOVER_AFTER},
 * so the scope added at the provider later takes effect without a restart (DESIGN.md 6, OPERATIONS.md).
 */
class LazyOidcClientRegistrationRepositoryTest {

    private static final String ISSUER = "https://auth.example.com/application/o/kina/";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-14T18:00:00Z"));
    private final AtomicInteger discoveries = new AtomicInteger();
    private volatile List<String> scopesSupported = List.of("openid", "profile", "email");
    private volatile boolean providerDown;
    private final MembershipVerifier membership = mock(MembershipVerifier.class);

    @BeforeEach
    void rechecksWanted() {
        when(membership.storesUpstreamTokens()).thenReturn(true);
    }

    private LazyOidcClientRegistrationRepository repository() {
        Function<String, ClientRegistration.Builder> discovery = issuer -> {
            discoveries.incrementAndGet();
            if (providerDown) {
                throw new IllegalStateException("connection refused");
            }
            return ClientRegistration.withRegistrationId("discovered")
                    .clientId("placeholder")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/placeholder")
                    .authorizationUri(issuer + "authorize/")
                    .tokenUri(issuer + "token/")
                    .providerConfigurationMetadata(Map.of("scopes_supported", scopesSupported));
        };
        return TestWiring.wire(new LazyOidcClientRegistrationRepository(),
                "properties", TestWiring.properties("kina.security.oidc.issuer-uri", ISSUER,
                        "kina.security.oidc.client-id", "kina", "kina.security.oidc.client-secret", "secret"),
                "membership", membership, "discovery", discovery, "clock", clock);
    }

    @Test
    void offlineAccessAddedAtTheProviderLaterIsRequestedWithoutARestart() {
        LazyOidcClientRegistrationRepository repository = repository();
        ClientRegistration first = repository.findByRegistrationId("oidc");
        assertThat(first.getScopes()).containsExactly("openid", "profile", "email");
        assertThat(repository.findByRegistrationId("oidc")).isSameAs(first);
        clock.advance(LazyOidcClientRegistrationRepository.REDISCOVER_AFTER.minusSeconds(1));
        assertThat(repository.findByRegistrationId("oidc")).as("not due yet").isSameAs(first);
        assertThat(discoveries).hasValue(1);

        scopesSupported = List.of("openid", "profile", "email", "offline_access");
        clock.advance(Duration.ofSeconds(1));
        ClientRegistration second = repository.findByRegistrationId("oidc");
        assertThat(second.getScopes()).containsExactly("openid", "profile", "email", "offline_access");
        assertThat(discoveries).hasValue(2);

        clock.advance(Duration.ofDays(1));
        assertThat(repository.findByRegistrationId("oidc")).as("kept once the scope is there").isSameAs(second);
        assertThat(discoveries).hasValue(2);
    }

    @Test
    void aFailedRediscoveryKeepsTheCurrentRegistrationAndWaitsAgain() {
        LazyOidcClientRegistrationRepository repository = repository();
        ClientRegistration first = repository.findByRegistrationId("oidc");
        providerDown = true;
        clock.advance(LazyOidcClientRegistrationRepository.REDISCOVER_AFTER);
        assertThat(repository.findByRegistrationId("oidc")).isSameAs(first);
        assertThat(discoveries).hasValue(2);
        clock.advance(Duration.ofMinutes(5));
        assertThat(repository.findByRegistrationId("oidc")).as("no retry storm").isSameAs(first);
        assertThat(discoveries).hasValue(2);

        providerDown = false;
        scopesSupported = List.of("openid", "profile", "email", "offline_access");
        clock.advance(Duration.ofMinutes(5));
        assertThat(repository.findByRegistrationId("oidc").getScopes()).contains("offline_access");
        assertThat(discoveries).hasValue(3);
    }

    @Test
    void noRediscoveryWhenRechecksAreOffOrTheScopeIsThereFromTheStart() {
        when(membership.storesUpstreamTokens()).thenReturn(false);
        LazyOidcClientRegistrationRepository off = repository();
        ClientRegistration registration = off.findByRegistrationId("oidc");
        clock.advance(Duration.ofDays(1));
        assertThat(off.findByRegistrationId("oidc")).isSameAs(registration);
        assertThat(registration.getScopes()).doesNotContain("offline_access");
        assertThat(discoveries).hasValue(1);

        when(membership.storesUpstreamTokens()).thenReturn(true);
        scopesSupported = List.of("openid", "profile", "email", "offline_access");
        LazyOidcClientRegistrationRepository on = repository();
        ClientRegistration withScope = on.findByRegistrationId("oidc");
        assertThat(withScope.getScopes()).contains("offline_access");
        clock.advance(Duration.ofDays(1));
        assertThat(on.findByRegistrationId("oidc")).isSameAs(withScope);
        assertThat(discoveries).hasValue(2);
        assertThat(on.findByRegistrationId("other")).isNull();
    }

    private static final class MutableClock extends Clock {

        private volatile Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
