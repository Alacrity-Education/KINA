package ro.alacrity.kina.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;

import java.io.Serial;
import java.util.Collection;

/** Authentication carrying a {@link KinaPrincipal}; created by the bearer filter and the development-mode filter. */
public final class KinaAuthentication extends AbstractAuthenticationToken {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String ROLE_USER = "ROLE_USER";
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final KinaPrincipal principal;

    public KinaAuthentication(KinaPrincipal principal, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        setAuthenticated(true);
    }

    public static KinaAuthentication user(KinaPrincipal principal) {
        return new KinaAuthentication(principal, AuthorityUtils.createAuthorityList(ROLE_USER));
    }

    public static KinaAuthentication admin(KinaPrincipal principal) {
        return new KinaAuthentication(principal, AuthorityUtils.createAuthorityList(ROLE_USER, ROLE_ADMIN));
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public KinaPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return principal.userId().toString();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof KinaAuthentication other && super.equals(other) && principal.equals(other.principal);
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + principal.hashCode();
    }
}
