package ro.alacrity.kina.web;

import lombok.experimental.UtilityClass;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;
import ro.alacrity.kina.security.KinaPrincipal;

/**
 * The tabs of the web UI (DESIGN.md section 6, "Web UI"): Search ({@code /}), MCP ({@code /connect}) and Status
 * ({@code /status}). The header fragment marks the tab named by the {@code tab} model attribute.
 */
@UtilityClass
class WebTabs {

    static final String SEARCH = "search";
    static final String MCP = "mcp";
    static final String STATUS = "status";

    /** A view of one tab with the signed-in user's display name. */
    static ModelAndView view(String template, String tab, KinaPrincipal user) {
        ModelAndView view = new ModelAndView(template);
        view.addObject("tab", tab);
        view.addObject("user", user.displayName());
        return view;
    }

    static KinaPrincipal user(Authentication authentication) {
        return KinaPrincipal.from(authentication)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in"));
    }
}
