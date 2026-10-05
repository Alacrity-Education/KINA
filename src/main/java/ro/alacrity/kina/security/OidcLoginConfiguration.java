package ro.alacrity.kina.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import ro.alacrity.kina.config.KinaProperties;

/**
 * Production mode only: OIDC login beans, built programmatically from {@code kina.security.oidc.*}
 * ({@code OIDC_ISSUER_URI}, {@code OIDC_CLIENT_ID}, {@code OIDC_CLIENT_SECRET}). Not using
 * {@code spring.security.oauth2.client.*} properties keeps development mode free of any OIDC configuration and lets
 * discovery happen lazily (see {@link LazyOidcClientRegistrationRepository}).
 */
@Configuration(proxyBeanMethods = false)
@Conditional(DevModeCondition.Prod.class)
public class OidcLoginConfiguration {

    @Bean
    ClientRegistrationRepository clientRegistrationRepository(KinaProperties properties) {
        return new LazyOidcClientRegistrationRepository(properties.security().oidc());
    }

    @Bean
    OidcUserSynchronizer oidcUserSynchronizer(UserRepository users) {
        return new OidcUserSynchronizer(users);
    }
}
