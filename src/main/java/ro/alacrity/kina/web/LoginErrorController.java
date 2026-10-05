package ro.alacrity.kina.web;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import ro.alacrity.kina.config.KinaProperties;

/**
 * Landing page after a failed OIDC login or a logout (production mode). Kept separate from the login entry point so a
 * failing provider cannot cause a redirect loop.
 */
@Controller
@RequiredArgsConstructor
public class LoginErrorController {

    private final KinaProperties properties;

    /**
     * The identity provider authenticated the user but KINA refused them (DESIGN.md 7.1): not in a required group, or
     * an e-mail domain that is not allowed. No session is established.
     */
    @GetMapping("/login-denied")
    public ModelAndView loginDenied(@RequestParam(name = "reason", required = false) String reason) {
        ModelAndView view = new ModelAndView("login-denied");
        view.addObject("emailDomain", "email".equals(reason));
        view.addObject("groups", properties.security().oidc().requiredGroups());
        view.addObject("domains", properties.security().oidc().allowedEmailDomains());
        view.setStatus(HttpStatus.FORBIDDEN);
        return view;
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
