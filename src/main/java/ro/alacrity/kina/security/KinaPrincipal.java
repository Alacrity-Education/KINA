package ro.alacrity.kina.security;

import org.springframework.security.core.Authentication;

import java.io.Serial;
import java.io.Serializable;
import java.util.Optional;
import java.util.UUID;

/**
 * The authenticated KINA user. {@code tokenId} is set when the request was authenticated with a bearer access token,
 * null for web sessions (OIDC login or the development admin).
 */
public record KinaPrincipal(UUID userId, String displayName, UUID tokenId) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Resolves the KINA principal from any authentication this application creates (bearer, dev admin, OIDC). */
    public static Optional<KinaPrincipal> from(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof KinaPrincipal kina) {
            return Optional.of(kina);
        }
        if (principal instanceof KinaOidcUser oidcUser) {
            return Optional.of(oidcUser.kinaPrincipal());
        }
        return Optional.empty();
    }
}
