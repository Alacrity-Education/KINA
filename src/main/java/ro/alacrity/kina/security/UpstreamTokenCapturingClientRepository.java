package ro.alacrity.kina.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

/**
 * Keeps the login's authorized client in the HTTP session (instead of Spring's default unbounded in-memory map) and
 * hands the provider's refresh token to {@link MembershipVerifier}, which stores it encrypted for membership
 * re-checks. Called by Spring's login filter right after a successful login.
 */
public class UpstreamTokenCapturingClientRepository implements OAuth2AuthorizedClientRepository {

    private final OAuth2AuthorizedClientRepository delegate = new HttpSessionOAuth2AuthorizedClientRepository();
    private final MembershipVerifier membership;

    public UpstreamTokenCapturingClientRepository(MembershipVerifier membership) {
        this.membership = membership;
    }

    @Override
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String clientRegistrationId,
                                                                     Authentication principal,
                                                                     HttpServletRequest request) {
        return delegate.loadAuthorizedClient(clientRegistrationId, principal, request);
    }

    @Override
    public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal,
                                     HttpServletRequest request, HttpServletResponse response) {
        delegate.saveAuthorizedClient(authorizedClient, principal, request, response);
        KinaPrincipal.from(principal).ifPresent(user -> membership.storeUpstreamRefreshToken(user.userId(),
                authorizedClient.getRefreshToken() == null ? null : authorizedClient.getRefreshToken().getTokenValue()));
    }

    @Override
    public void removeAuthorizedClient(String clientRegistrationId, Authentication principal,
                                       HttpServletRequest request, HttpServletResponse response) {
        delegate.removeAuthorizedClient(clientRegistrationId, principal, request, response);
    }
}
