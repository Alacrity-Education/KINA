package ro.alacrity.kina.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Production web chain: ends the web session of a user who was blocked after signing in (membership re-check or a
 * denied login elsewhere set {@code access_revoked_at}). The request is redirected to itself, which starts a new OIDC
 * login and with it a fresh group check. Not a Spring bean on purpose (it must not be registered as a servlet filter).
 */
public class RevokedUserSessionFilter extends OncePerRequestFilter {

    private final UserRepository users;
    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

    public RevokedUserSessionFilter(UserRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = holder.getContext().getAuthentication();
        Optional<KinaPrincipal> principal = authentication != null && authentication.getPrincipal() instanceof KinaOidcUser
                ? KinaPrincipal.from(authentication) : Optional.empty();
        if (principal.isPresent()
                && users.findById(principal.get().userId()).map(UserRepository.User::isRevoked).orElse(true)) {
            holder.clearContext();
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            if ("GET".equals(request.getMethod())) {
                String query = request.getQueryString();
                response.sendRedirect(request.getRequestURI() + (query == null ? "" : "?" + query));
            } else {
                response.sendRedirect(request.getContextPath() + "/");
            }
            return;
        }
        chain.doFilter(request, response);
    }
}
