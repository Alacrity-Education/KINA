package ro.alacrity.kina.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.security.AccessTokenRepository.AccessToken;
import ro.alacrity.kina.security.AccessTokenService;
import ro.alacrity.kina.security.AccessTokenService.IssuedToken;
import ro.alacrity.kina.security.KinaPrincipal;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Token web UI (DESIGN.md section 6): list the current user's access tokens, create one (plaintext shown exactly once),
 * revoke. Server-rendered Thymeleaf, CSRF-protected forms, no JavaScript. Static tokens are for scripts and for Claude
 * Code on machines without a browser; Claude itself connects through OAuth. {@code kina.tokens.ui-enabled=false}
 * replaces the page with an explanation and makes {@code POST /tokens} a 404.
 */
@Controller
public class TokenPageController {

    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    @Autowired private AccessTokenService tokens;
    @Autowired private PublicUrlResolver urls;
    @Autowired private KinaProperties properties;

    /** One row of the token table, pre-formatted for display. */
    public record TokenRow(UUID id, String name, String prefix, String created, String expires, String lastUsed,
                           String status, boolean oauth) {

        public boolean active() {
            return "active".equals(status);
        }
    }

    @GetMapping("/")
    public ModelAndView index(Authentication authentication) {
        KinaPrincipal user = user(authentication);
        if (!properties.tokens().uiEnabled()) {
            ModelAndView view = new ModelAndView("tokens/disabled");
            view.addObject("user", user.displayName());
            view.addObject("mcpUrl", urls.mcpUrl());
            return view;
        }
        return indexView(user, null, null, HttpStatus.OK);
    }

    @PostMapping("/tokens")
    public ModelAndView create(@RequestParam(name = "name", required = false) String name,
                               Authentication authentication, HttpServletResponse response) {
        if (!properties.tokens().uiEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        KinaPrincipal user = user(authentication);
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty()) {
            return indexView(user, "Please enter a name for the token.", name, HttpStatus.BAD_REQUEST);
        }
        if (trimmed.length() > AccessTokenService.MAX_NAME_LENGTH) {
            return indexView(user, "The name must be at most " + AccessTokenService.MAX_NAME_LENGTH + " characters.",
                    name, HttpStatus.BAD_REQUEST);
        }
        IssuedToken issued = tokens.create(user.userId(), trimmed, null, null);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        ModelAndView view = new ModelAndView("tokens/created");
        view.addObject("user", user.displayName());
        view.addObject("name", issued.token().name());
        view.addObject("token", issued.plaintext());
        view.addObject("expires", TIME.format(issued.token().expiresAt()));
        view.addObject("mcpUrl", urls.mcpUrl());
        view.addObject("baseUrl", urls.baseUrl());
        return view;
    }

    @PostMapping("/tokens/{id}/revoke")
    public String revoke(@PathVariable("id") UUID id, Authentication authentication, RedirectAttributes redirect) {
        KinaPrincipal user = user(authentication);
        if (tokens.revoke(user.userId(), id)) {
            redirect.addFlashAttribute("message", "Token revoked.");
        }
        return "redirect:/";
    }

    private ModelAndView indexView(KinaPrincipal user, String error, String name, HttpStatus status) {
        Instant now = Instant.now();
        List<TokenRow> rows = tokens.list(user.userId()).stream().map(token -> row(token, now)).toList();
        ModelAndView view = new ModelAndView("tokens/index");
        view.addObject("user", user.displayName());
        view.addObject("tokens", rows);
        view.addObject("error", error);
        view.addObject("name", name);
        view.addObject("validityDays", tokens.validity().toDays());
        view.addObject("mcpUrl", urls.mcpUrl());
        view.setStatus(status);
        return view;
    }

    private static TokenRow row(AccessToken token, Instant now) {
        return new TokenRow(token.id(), token.name(), token.tokenPrefix() + "…", TIME.format(token.createdAt()),
                TIME.format(token.expiresAt()), token.lastUsedAt() == null ? "never" : TIME.format(token.lastUsedAt()),
                token.status(now), token.oauthClientId() != null);
    }

    private static KinaPrincipal user(Authentication authentication) {
        return KinaPrincipal.from(authentication)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in"));
    }
}
