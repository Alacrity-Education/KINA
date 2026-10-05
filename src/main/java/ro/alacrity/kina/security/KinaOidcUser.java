package ro.alacrity.kina.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.io.Serial;
import java.util.Collection;
import java.util.Objects;

/** OIDC user of a web session, enriched with the KINA user row it was synchronised to. */
public class KinaOidcUser extends DefaultOidcUser {

    @Serial
    private static final long serialVersionUID = 1L;

    private final KinaPrincipal kinaPrincipal;

    public KinaOidcUser(Collection<? extends GrantedAuthority> authorities, OidcIdToken idToken, OidcUserInfo userInfo,
                        KinaPrincipal kinaPrincipal) {
        super(authorities, idToken, userInfo, "sub");
        this.kinaPrincipal = Objects.requireNonNull(kinaPrincipal);
    }

    public KinaPrincipal kinaPrincipal() {
        return kinaPrincipal;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof KinaOidcUser other && super.equals(other) && kinaPrincipal.equals(other.kinaPrincipal);
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + kinaPrincipal.hashCode();
    }
}
