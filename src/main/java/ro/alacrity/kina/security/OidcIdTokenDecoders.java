package ro.alacrity.kina.security;

import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decoders for ID tokens of the login and of membership re-checks. Like Spring's {@link OidcIdTokenDecoderFactory}
 * (same claim conversion and {@link OidcIdTokenValidator}), but the JWKS download has explicit timeouts and the
 * accepted asymmetric signature algorithms come from the provider's discovery document
 * ({@code id_token_signing_alg_values_supported}; RS256 when it lists none). Decoders are cached per registration.
 */
public class OidcIdTokenDecoders implements JwtDecoderFactory<ClientRegistration> {

    private final Map<String, JwtDecoder> decoders = new ConcurrentHashMap<>();

    @Override
    public JwtDecoder createDecoder(ClientRegistration registration) {
        String key = registration.getRegistrationId() + "|" + registration.getProviderDetails().getJwkSetUri();
        return decoders.computeIfAbsent(key, k -> build(registration));
    }

    private static JwtDecoder build(ClientRegistration registration) {
        String jwkSetUri = registration.getProviderDetails().getJwkSetUri();
        if (jwkSetUri == null || jwkSetUri.isBlank()) {
            throw new IllegalStateException("The OIDC provider publishes no jwks_uri");
        }
        Set<SignatureAlgorithm> algorithms = algorithms(registration);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .restOperations(OidcHttp.jwksRestTemplate())
                .jwsAlgorithms(set -> set.addAll(algorithms))
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(),
                new OidcIdTokenValidator(registration)));
        decoder.setClaimSetConverter(OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter());
        return decoder;
    }

    static Set<SignatureAlgorithm> algorithms(ClientRegistration registration) {
        Set<SignatureAlgorithm> result = new LinkedHashSet<>();
        Object advertised = registration.getProviderDetails().getConfigurationMetadata()
                .get("id_token_signing_alg_values_supported");
        if (advertised instanceof Collection<?> values) {
            for (Object value : values) {
                SignatureAlgorithm algorithm = value == null ? null : SignatureAlgorithm.from(value.toString());
                if (algorithm != null) {
                    result.add(algorithm);
                }
            }
        }
        if (result.isEmpty()) {
            result.add(SignatureAlgorithm.RS256);
        }
        return result;
    }
}
