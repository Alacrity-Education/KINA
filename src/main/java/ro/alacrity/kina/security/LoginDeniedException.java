package ro.alacrity.kina.security;

import lombok.Getter;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import java.io.Serial;

/**
 * An OIDC login refused by {@link OidcAccessPolicy}. The OAuth2 error code stays {@link OidcAccessPolicy#ERROR_GROUP} or
 * {@link OidcAccessPolicy#ERROR_EMAIL_DOMAIN}; {@link #getDenial()} adds what the denied page shows.
 */
@Getter
public final class LoginDeniedException extends OAuth2AuthenticationException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final LoginDenial denial;

    LoginDeniedException(OidcAccessPolicy.Decision decision, boolean existingUserBlocked, String description) {
        super(new OAuth2Error(decision.errorCode(), description, null), description);
        this.denial = new LoginDenial(decision.reason().code(), decision.domain(), existingUserBlocked);
    }
}
