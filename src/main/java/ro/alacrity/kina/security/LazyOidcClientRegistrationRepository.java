package ro.alacrity.kina.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.config.KinaProperties;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Holds the single OIDC client registration ({@code oidc}) built from {@code kina.security.oidc.*} through issuer
 * discovery ({@code <issuer>/.well-known/openid-configuration}). Discovery is <b>lazy</b>: it runs on the first login
 * attempt, not at startup, so KINA starts (and serves bearer-authenticated API/MCP traffic) while the provider is
 * unreachable. A failed discovery is logged and retried on a later login attempt (at most every 10 seconds); during
 * that time the login request fails with an error instead of hanging. No provider-specific code.
 */
@Slf4j
@Component("clientRegistrationRepository")
@Conditional(DevModeCondition.Prod.class)
public class LazyOidcClientRegistrationRepository implements ClientRegistrationRepository {

    public static final String REGISTRATION_ID = "oidc";
    static final Duration RETRY_AFTER = Duration.ofSeconds(10);
    static final String OFFLINE_ACCESS = "offline_access";

    @Autowired private KinaProperties properties;
    @Autowired private MembershipVerifier membership;
    private Function<String, ClientRegistration.Builder> discovery = ClientRegistrations::fromIssuerLocation;
    private Clock clock = Clock.systemUTC();
    private KinaProperties.Oidc oidc;
    private volatile ClientRegistration registration;
    private Instant nextAttempt = Instant.MIN;
    private volatile boolean discoveryAttempted;

    @PostConstruct
    void init() {
        oidc = properties.security().oidc();
        if (oidc == null || !oidc.isConfigured()) {
            throw new IllegalStateException("kina.security.mode=prod requires OIDC_ISSUER_URI and OIDC_CLIENT_ID "
                    + "(kina.security.oidc.issuer-uri / client-id)");
        }
    }

    /**
     * {@code openid profile email}, the configured extra scopes and, when membership re-checks are enabled (required
     * groups and an encryption key) and the provider lists it in {@code scopes_supported}, {@code offline_access}
     * (needed for a refresh token at most providers).
     */
    Set<String> scopes(ClientRegistration discovered) {
        Set<String> scopes = new LinkedHashSet<>(List.of("openid", "profile", "email"));
        scopes.addAll(oidc.extraScopes());
        if (membership.storesUpstreamTokens() && !scopes.contains(OFFLINE_ACCESS)) {
            Object supported = discovered.getProviderDetails().getConfigurationMetadata().get("scopes_supported");
            if (supported instanceof Collection<?> values && values.contains(OFFLINE_ACCESS)) {
                scopes.add(OFFLINE_ACCESS);
            } else {
                log.warn("Group re-checks need a refresh token from the OIDC provider, but its discovery document does "
                        + "not list offline_access in scopes_supported; if no refresh token is issued, users must sign "
                        + "in again every {} (kina.security.oidc.relogin-interval-without-recheck)",
                        oidc.reloginIntervalWithoutRecheck());
            }
        }
        return scopes;
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
                ClientRegistration discovered = discovery.apply(oidc.issuerUri())
                        .registrationId(REGISTRATION_ID)
                        .clientId(oidc.clientId())
                        .clientSecret(oidc.clientSecret() == null ? "" : oidc.clientSecret())
                        .scope("openid", "profile", "email")
                        .redirectUri("{baseUrl}/login/oauth2/code/" + REGISTRATION_ID)
                        .clientName("OIDC")
                        .build();
                Set<String> scopes = scopes(discovered);
                registration = ClientRegistration.withClientRegistration(discovered).scope(scopes).build();
                log.info("OIDC discovery succeeded for issuer {}; requesting scopes {}", oidc.issuerUri(), scopes);
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
