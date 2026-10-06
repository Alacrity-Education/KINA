package ro.alacrity.kina.security;

import java.io.Serializable;

/**
 * What the {@code /login-denied} page may show about a refused login (DESIGN.md 7.1): the reason code, the offending
 * e-mail domain ({@code email_domain} only) and whether an existing user was blocked by this refusal (only then do
 * earlier tokens stop working). Never an address or another claim value. Handed from the login failure handler to the
 * page through the HTTP session and removed when the page is shown.
 *
 * @param reason              {@link OidcAccessPolicy.Reason#code()}
 * @param domain              lower-case domain of the refused address, or null
 * @param existingUserBlocked true when the identity matched an existing user that is now blocked
 */
public record LoginDenial(String reason, String domain, boolean existingUserBlocked) implements Serializable {

    /** Session attribute holding the denial of the last failed login. */
    public static final String SESSION_ATTRIBUTE = LoginDenial.class.getName();
}
