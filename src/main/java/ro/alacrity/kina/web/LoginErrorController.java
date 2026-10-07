package ro.alacrity.kina.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.security.LoginDenial;
import ro.alacrity.kina.security.OidcAccessPolicy;

/**
 * Landing page after a failed OIDC login or a logout (production mode). Kept separate from the login entry point so a
 * failing provider cannot cause a redirect loop.
 */
@Controller
public class LoginErrorController {

    @Autowired private KinaProperties properties;

    /**
     * The identity provider authenticated the user but KINA refused them (DESIGN.md 7.1). {@code reason} is one of
     * {@code email_missing}, {@code email_unverified}, {@code email_domain} or {@code group} ({@code email} is the
     * older, unspecific form of the e-mail reasons). The offending domain and whether an existing user was blocked come
     * only from the {@link LoginDenial} the login failure left in this browser's session (shown once), never from the
     * URL. No session is established.
     */
    @GetMapping("/login-denied")
    public ModelAndView loginDenied(@RequestParam(name = "reason", required = false) String reason,
                                    HttpServletRequest request) {
        LoginDenial denial = takeDenial(request);
        String effective = reason;
        if (denial != null && (reason == null || reason.equals(denial.reason()))) {
            effective = denial.reason();
        } else {
            denial = null;
        }
        if (OidcAccessPolicy.Reason.fromCode(effective) == null && !"email".equals(effective)) {
            effective = OidcAccessPolicy.Reason.GROUP.code();
        }
        ModelAndView view = new ModelAndView("login-denied");
        view.addObject("reason", effective);
        view.addObject("domain", denial == null ? null : denial.domain());
        view.addObject("tokensRevoked", denial != null && denial.existingUserBlocked());
        view.addObject("groups", properties.security().oidc().requiredGroups());
        view.addObject("domains", properties.security().oidc().allowedEmailDomains());
        view.setStatus(HttpStatus.FORBIDDEN);
        return view;
    }

    private static LoginDenial takeDenial(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(LoginDenial.SESSION_ATTRIBUTE) instanceof LoginDenial denial)) {
            return null;
        }
        session.removeAttribute(LoginDenial.SESSION_ATTRIBUTE);
        return denial;
    }

    @GetMapping("/login-error")
    public ModelAndView loginError(@RequestParam(name = "logout", required = false) String logout) {
        ModelAndView view = new ModelAndView("oauth/error");
        if (logout != null) {
            view.addObject("title", "Signed out");
            view.addObject("message", "You have been signed out of KINA.");
        } else {
            view.addObject("title", "Sign-in failed");
            view.addObject("message", "Signing in with the identity provider failed. Try again later or contact the "
                    + "administrator.");
            view.setStatus(HttpStatus.UNAUTHORIZED);
        }
        view.addObject("retry", true);
        return view;
    }
}
