package ro.alacrity.kina.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.metrics.KinaMetrics;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Production OIDC login: loads the OIDC user with Spring's standard {@code OidcUserService}, applies the group and
 * e-mail-domain policy ({@link OidcAccessPolicy}), upserts {@code users(issuer, subject, email, display_name,
 * last_login_at)} and returns a {@link KinaOidcUser} that carries the {@link KinaPrincipal}. Works with any compliant
 * provider: only standard claims plus the configured groups claim are read.
 * <p>
 * A denied identity is not signed in: a {@link LoginDeniedException} (error code {@link OidcAccessPolicy#ERROR_GROUP}
 * or {@link OidcAccessPolicy#ERROR_EMAIL_DOMAIN}, plus the reason) ends the login on the {@code /login-denied} page,
 * and an existing user row is blocked ({@code access_revoked_at}, all tokens revoked). The refusal is logged at WARN
 * (subject, reason, domain) and the claim names the provider sent at INFO; never addresses or claim values.
 */
@Slf4j
@Component
@Conditional(DevModeCondition.Prod.class)
public class OidcUserSynchronizer implements OAuth2UserService<OidcUserRequest, OidcUser> {

    @Autowired private UserRepository users;
    @Autowired private OidcAccessPolicy policy;
    @Autowired private MembershipVerifier membership;
    @Autowired private KinaMetrics metrics = KinaMetrics.NOOP;
    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate = OidcHttp.oidcUserService();
    private final Clock clock = Clock.systemUTC();

    @Override
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        OidcUser oidcUser = delegate.loadUser(request);
        String issuer = oidcUser.getIssuer() != null ? oidcUser.getIssuer().toString()
                : request.getClientRegistration().getProviderDetails().getIssuerUri();
        Map<String, Object> idClaims = oidcUser.getIdToken().getClaims();
        Map<String, Object> userInfoClaims = oidcUser.getUserInfo() == null ? null : oidcUser.getUserInfo().getClaims();
        OidcAccessPolicy.Decision decision = policy.evaluateLogin(idClaims, userInfoClaims);
        if (!decision.allowed()) {
            // Log the subject only, never the e-mail address or claim values (the detail holds names and domains).
            log.warn("OIDC login denied for subject {} (issuer {}): {}", oidcUser.getSubject(), issuer,
                    decision.detail());
            log.info("OIDC login denied for subject {}: ID token claims {}; {}; {}", oidcUser.getSubject(),
                    new TreeSet<>(idClaims.keySet()), userInfoDiagnosis(request, userInfoClaims),
                    scopeDiagnosis(request));
            boolean blocked = users.findByIssuerAndSubject(issuer, oidcUser.getSubject())
                    .map(user -> {
                        membership.recordDeniedLogin(user.id());
                        return true;
                    })
                    .orElse(false);
            metrics.loginDenied(decision.reason() == null ? null : decision.reason().code());
            throw new LoginDeniedException(decision, blocked, decision.reason() == OidcAccessPolicy.Reason.GROUP
                    ? "Access to KINA requires membership in " + String.join(" or ", policy.requiredGroups())
                    : "Access to KINA requires a verified e-mail address from "
                    + String.join(" or ", policy.allowedEmailDomains()));
        }
        KinaPrincipal principal = synchronize(issuer, oidcUser);
        membership.recordSuccessfulLogin(principal.userId());
        metrics.loginSucceeded();
        Set<GrantedAuthority> authorities = new LinkedHashSet<>(oidcUser.getAuthorities());
        authorities.add(new SimpleGrantedAuthority(KinaAuthentication.ROLE_USER));
        return new KinaOidcUser(authorities, oidcUser.getIdToken(), oidcUser.getUserInfo(), principal);
    }

    /** Whether userinfo was fetched and which claim names it returned; names only. */
    static String userInfoDiagnosis(OidcUserRequest request, Map<String, Object> userInfoClaims) {
        if (userInfoClaims != null) {
            return "userinfo claims " + new TreeSet<>(userInfoClaims.keySet());
        }
        String uri = request.getClientRegistration().getProviderDetails().getUserInfoEndpoint().getUri();
        return uri == null || uri.isBlank()
                ? "userinfo not fetched (the provider's discovery document has no userinfo_endpoint)"
                : "userinfo not fetched";
    }

    /** The scopes the token response granted, with a hint when it named none or lacks {@code email}. */
    static String scopeDiagnosis(OidcUserRequest request) {
        Set<String> scopes = new TreeSet<>(request.getAccessToken().getScopes());
        if (scopes.isEmpty()) {
            return "the token response named no scope (the provider did not confirm which scopes it granted)";
        }
        return "granted scopes " + scopes + (scopes.contains("email") ? ""
                : " (no email scope granted: the provider sends no e-mail claims)");
    }

    /** Upserts the user row and returns its principal. */
    public KinaPrincipal synchronize(String issuer, OidcUser oidcUser) {
        String subject = oidcUser.getSubject();
        String email = policy.email(oidcUser.getIdToken().getClaims(),
                oidcUser.getUserInfo() == null ? null : oidcUser.getUserInfo().getClaims()).orElse(null);
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

}
