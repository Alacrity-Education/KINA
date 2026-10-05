package ro.alacrity.kina.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import ro.alacrity.kina.config.KinaProperties;

/**
 * Production mode only: OIDC login beans, built programmatically from {@code kina.security.oidc.*}
 * ({@code OIDC_ISSUER_URI}, {@code OIDC_CLIENT_ID}, {@code OIDC_CLIENT_SECRET}, group authorisation settings). Not using
 * {@code spring.security.oauth2.client.*} properties keeps development mode free of any OIDC configuration and lets
 * discovery happen lazily (see {@link LazyOidcClientRegistrationRepository}).
 */
@Configuration(proxyBeanMethods = false)
@Conditional(DevModeCondition.Prod.class)
public class OidcLoginConfiguration {

    @Bean
    ClientRegistrationRepository clientRegistrationRepository(KinaProperties properties,
                                                              MembershipVerifier membership) {
        return new LazyOidcClientRegistrationRepository(properties.security().oidc(),
                membership.storesUpstreamTokens());
    }

    /** ID token decoding of the login (picked up by Spring's OAuth2 login) and of membership re-checks. */
    @Bean
    JwtDecoderFactory<ClientRegistration> oidcIdTokenDecoderFactory() {
        return new OidcIdTokenDecoders();
    }

    @Bean
    OidcAccessPolicy oidcAccessPolicy(KinaProperties properties) {
        return new OidcAccessPolicy(properties.security().oidc());
    }

    @Bean
    OidcUserSynchronizer oidcUserSynchronizer(UserRepository users, OidcAccessPolicy policy,
                                              MembershipVerifier membership) {
        return new OidcUserSynchronizer(users, policy, membership);
    }
}
