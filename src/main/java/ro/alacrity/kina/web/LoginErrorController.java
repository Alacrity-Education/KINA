package ro.alacrity.kina.web;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

/**
 * Landing page after a failed OIDC login or a logout (production mode). Kept separate from the login entry point so a
 * failing provider cannot cause a redirect loop.
 */
@Controller
public class LoginErrorController {

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
