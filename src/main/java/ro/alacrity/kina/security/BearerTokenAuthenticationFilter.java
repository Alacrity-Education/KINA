package ro.alacrity.kina.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import ro.alacrity.kina.security.AccessTokenRepository.AccessToken;

import java.io.IOException;
import java.util.Optional;

/**
 * Authenticates {@code Authorization: Bearer kina_...} on the protected machine endpoints ({@code /api/**},
 * {@code /mcp/**}). A presented but invalid token is rejected immediately with 401 (also in development mode);
 * a missing token leaves the request unauthenticated (production: 401 by the entry point; development: the
 * {@link DevModeAuthenticationFilter} falls back to the admin). Tokens of blocked users are invalid
 * ({@link MembershipVerifier}). Not a Spring bean on purpose (it must not be
 * registered as a servlet filter).
 */
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final RequestMatcher matcher;
    private final AccessTokenService tokens;
    private final UserRepository users;
    private final MembershipVerifier membership;
    private final AuthenticationEntryPoint entryPoint;
    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();
    private final SecurityContextRepository contextRepository = new RequestAttributeSecurityContextRepository();

    public BearerTokenAuthenticationFilter(RequestMatcher matcher, AccessTokenService tokens, UserRepository users,
                                           MembershipVerifier membership, AuthenticationEntryPoint entryPoint) {
        this.matcher = matcher;
        this.tokens = tokens;
        this.users = users;
        this.membership = membership;
        this.entryPoint = entryPoint;
    }

    /**
     * The bearer token of the request, or empty when no {@code Bearer} authorization header is present. A header of
     * just {@code Bearer} (the servlet container strips the trailing space of {@code "Bearer "}) is a presented, empty
     * token: it is rejected as invalid instead of being treated as "no credentials" (which would fall back to the
     * development admin).
     */
    static Optional<String> bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            return Optional.empty();
        }
        String value = header.strip();
        if (value.equalsIgnoreCase(BEARER.strip())) {
            return Optional.of("");
        }
        if (!value.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return Optional.empty();
        }
        return Optional.of(value.substring(BEARER.length()).strip());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !matcher.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> presented = bearerToken(request);
        if (presented.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        Optional<KinaPrincipal> principal = tokens.validate(presented.get()).flatMap(this::toPrincipal);
        if (principal.isEmpty()) {
            holder.clearContext();
            entryPoint.commence(request, response,
                    new BearerAuthenticationEntryPoint.InvalidBearerTokenException("Invalid bearer token"));
            return;
        }
        SecurityContext context = holder.createEmptyContext();
        context.setAuthentication(KinaAuthentication.user(principal.get()));
        holder.setContext(context);
        contextRepository.saveContext(context, request, response);
        chain.doFilter(request, response);
    }

    /**
     * The principal of a valid token, unless its user is blocked ({@code access_revoked_at}) or, for a static web-UI
     * token under group authorisation, the user's membership can no longer be vouched for (DESIGN.md 7.6; the
     * re-check itself runs in the background). OAuth access tokens live one hour and are re-checked at refresh.
     */
    private Optional<KinaPrincipal> toPrincipal(AccessToken token) {
        return users.findById(token.userId())
                .filter(user -> token.oauthClientId() != null ? !user.isRevoked() : membership.allowsStaticToken(user))
                .map(user -> new KinaPrincipal(user.id(), user.displayName(), token.id()));
    }
}
