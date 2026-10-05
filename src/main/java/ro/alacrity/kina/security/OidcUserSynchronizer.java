package ro.alacrity.kina.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Production OIDC login: loads the OIDC user with Spring's standard {@code OidcUserService}, applies the group and
 * e-mail-domain policy ({@link OidcAccessPolicy}), upserts {@code users(issuer, subject, email, display_name,
 * last_login_at)} and returns a {@link KinaOidcUser} that carries the {@link KinaPrincipal}. Works with any compliant
 * provider: only standard claims plus the configured groups claim are read.
 * <p>
 * A denied identity is not signed in: an {@link OAuth2AuthenticationException} with
 * {@link OidcAccessPolicy#ERROR_GROUP} or {@link OidcAccessPolicy#ERROR_EMAIL_DOMAIN} ends the login on the
 * {@code /login-denied} page, and an existing user row is blocked ({@code access_revoked_at}, all tokens revoked).
 */
@Slf4j
public class OidcUserSynchronizer implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
    private final UserRepository users;
    private final OidcAccessPolicy policy;
    private final MembershipVerifier membership;
    private final Clock clock;

    public OidcUserSynchronizer(UserRepository users, OidcAccessPolicy policy, MembershipVerifier membership) {
        this(OidcHttp.oidcUserService(), users, policy, membership, Clock.systemUTC());
    }

    public OidcUserSynchronizer(OAuth2UserService<OidcUserRequest, OidcUser> delegate, UserRepository users,
                                OidcAccessPolicy policy, MembershipVerifier membership, Clock clock) {
        this.delegate = delegate;
        this.users = users;
        this.policy = policy;
        this.membership = membership;
        this.clock = clock;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        OidcUser oidcUser = delegate.loadUser(request);
        String issuer = oidcUser.getIssuer() != null ? oidcUser.getIssuer().toString()
                : request.getClientRegistration().getProviderDetails().getIssuerUri();
        OidcAccessPolicy.Decision decision = policy.evaluateLogin(oidcUser.getIdToken().getClaims(),
                oidcUser.getUserInfo() == null ? null : oidcUser.getUserInfo().getClaims());
        if (!decision.allowed()) {
            // Log the subject only, never the e-mail address or the claims.
            log.warn("OIDC login denied for subject {} (issuer {}): {}", oidcUser.getSubject(), issuer,
                    decision.reason());
            users.findByIssuerAndSubject(issuer, oidcUser.getSubject())
                    .ifPresent(user -> membership.recordDeniedLogin(user.id()));
            throw new OAuth2AuthenticationException(new OAuth2Error(decision.errorCode(),
                    "Access to KINA requires membership in " + String.join(" or ", policy.requiredGroups()), null));
        }
        KinaPrincipal principal = synchronize(issuer, oidcUser);
        membership.recordSuccessfulLogin(principal.userId());
        Set<GrantedAuthority> authorities = new LinkedHashSet<>(oidcUser.getAuthorities());
        authorities.add(new SimpleGrantedAuthority(KinaAuthentication.ROLE_USER));
        return new KinaOidcUser(authorities, oidcUser.getIdToken(), oidcUser.getUserInfo(), principal);
    }

    /** Upserts the user row and returns its principal. */
    public KinaPrincipal synchronize(String issuer, OidcUser oidcUser) {
        String subject = oidcUser.getSubject();
        String email = blankToNull(oidcUser.getEmail());
        String displayName = firstNonBlank(oidcUser.getFullName(), oidcUser.getPreferredUsername(), email, subject);
        return users.upsert(issuer, subject, email, displayName, clock.instant()).toPrincipal();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
