package ro.alacrity.kina.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Production OIDC login: loads the OIDC user with Spring's standard {@link OidcUserService}, upserts
 * {@code users(issuer, subject, email, display_name, last_login_at)} and returns a {@link KinaOidcUser} that carries the
 * {@link KinaPrincipal}. Works with any compliant provider: only standard claims are read.
 */
public class OidcUserSynchronizer implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
    private final UserRepository users;
    private final Clock clock;

    public OidcUserSynchronizer(UserRepository users) {
        this(new OidcUserService(), users, Clock.systemUTC());
    }

    public OidcUserSynchronizer(OAuth2UserService<OidcUserRequest, OidcUser> delegate, UserRepository users,
                                Clock clock) {
        this.delegate = delegate;
        this.users = users;
        this.clock = clock;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        OidcUser oidcUser = delegate.loadUser(request);
        String issuer = oidcUser.getIssuer() != null ? oidcUser.getIssuer().toString()
                : request.getClientRegistration().getProviderDetails().getIssuerUri();
        KinaPrincipal principal = synchronize(issuer, oidcUser);
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
