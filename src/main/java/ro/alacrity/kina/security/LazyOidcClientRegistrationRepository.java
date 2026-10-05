package ro.alacrity.kina.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import ro.alacrity.kina.config.KinaProperties;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;

/**
 * Holds the single OIDC client registration ({@code oidc}) built from {@code kina.security.oidc.*} through issuer
 * discovery ({@code <issuer>/.well-known/openid-configuration}). Discovery is <b>lazy</b>: it runs on the first login
 * attempt, not at startup, so KINA starts (and serves bearer-authenticated API/MCP traffic) while the provider is
 * unreachable. A failed discovery is logged and retried on a later login attempt (at most every 10 seconds); during
 * that time the login request fails with an error instead of hanging. No provider-specific code.
 */
public class LazyOidcClientRegistrationRepository implements ClientRegistrationRepository {

    public static final String REGISTRATION_ID = "oidc";
    static final Duration RETRY_AFTER = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(LazyOidcClientRegistrationRepository.class);

    private final KinaProperties.Oidc oidc;
    private final Function<String, ClientRegistration.Builder> discovery;
    private final Clock clock;
    private volatile ClientRegistration registration;
    private Instant nextAttempt = Instant.MIN;
    private volatile boolean discoveryAttempted;

    public LazyOidcClientRegistrationRepository(KinaProperties.Oidc oidc) {
        this(oidc, ClientRegistrations::fromIssuerLocation, Clock.systemUTC());
    }

    LazyOidcClientRegistrationRepository(KinaProperties.Oidc oidc,
                                         Function<String, ClientRegistration.Builder> discovery, Clock clock) {
        if (oidc == null || !oidc.isConfigured()) {
            throw new IllegalStateException("kina.security.mode=prod requires OIDC_ISSUER_URI and OIDC_CLIENT_ID "
                    + "(kina.security.oidc.issuer-uri / client-id)");
        }
        this.oidc = oidc;
        this.discovery = discovery;
        this.clock = clock;
    }

    /** True once discovery has been tried (the issuer was contacted). */
    boolean discoveryAttempted() {
        return discoveryAttempted;
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        if (!REGISTRATION_ID.equals(registrationId)) {
            return null;
        }
        ClientRegistration current = registration;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (registration != null) {
                return registration;
            }
            Instant now = clock.instant();
            if (now.isBefore(nextAttempt)) {
                return null;
            }
            discoveryAttempted = true;
            try {
                registration = discovery.apply(oidc.issuerUri())
                        .registrationId(REGISTRATION_ID)
                        .clientId(oidc.clientId())
                        .clientSecret(oidc.clientSecret() == null ? "" : oidc.clientSecret())
                        .scope("openid", "profile", "email")
                        .redirectUri("{baseUrl}/login/oauth2/code/" + REGISTRATION_ID)
                        .clientName("OIDC")
                        .build();
                log.info("OIDC discovery succeeded for issuer {}", oidc.issuerUri());
                return registration;
            } catch (RuntimeException e) {
                nextAttempt = now.plus(RETRY_AFTER);
                log.warn("OIDC discovery for issuer {} failed (will retry on a later login): {}", oidc.issuerUri(),
                        e.getMessage());
                return null;
            }
        }
    }
}
