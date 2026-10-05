package ro.alacrity.kina.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Development mode only: a request matched by {@code matcher} that carries no credentials (no authentication yet and no
 * bearer header) is authenticated as the development admin ({@code users} row {@code dev/admin}). Presented bearer
 * tokens are still validated by {@link BearerTokenAuthenticationFilter}. Not a Spring bean on purpose.
 */
public class DevModeAuthenticationFilter extends OncePerRequestFilter {

    private final RequestMatcher matcher;
    private final DevAdminProvider admin;
    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();
    private final SecurityContextRepository contextRepository = new RequestAttributeSecurityContextRepository();

    public DevModeAuthenticationFilter(RequestMatcher matcher, DevAdminProvider admin) {
        this.matcher = matcher;
        this.admin = admin;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !matcher.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication current = holder.getContext().getAuthentication();
        boolean unauthenticated = current == null || current instanceof AnonymousAuthenticationToken
                || !current.isAuthenticated();
        if (unauthenticated && BearerTokenAuthenticationFilter.bearerToken(request).isEmpty()) {
            SecurityContext context = holder.createEmptyContext();
            context.setAuthentication(KinaAuthentication.admin(admin.principal()));
            holder.setContext(context);
            contextRepository.saveContext(context, request, response);
        }
        chain.doFilter(request, response);
    }
}
