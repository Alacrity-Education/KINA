package ro.alacrity.kina.oauth;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;
import ro.alacrity.kina.oauth.AuthorizationCodeRepository.AuthorizationCode;
import ro.alacrity.kina.oauth.OAuthClientRepository.OAuthClient;
import ro.alacrity.kina.security.KinaPrincipal;
import ro.alacrity.kina.security.SecureTokens;
import ro.alacrity.kina.web.PublicUrlResolver;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Authorization endpoint (OAuth 2.1, PKCE {@code S256} mandatory). Runs in the web security chain, so the user is
 * authenticated first (production: OIDC login, then the original request resumes; development: the admin). Shows a
 * consent page; Approve issues a 10-minute single-use code bound to client, redirect URI, user, PKCE challenge, scope
 * and resource. Errors are redirected to the client only after client and redirect URI have been validated; otherwise
 * an error page is shown (never an open redirect).
 */
@Controller
public class AuthorizationController {

    static final Duration CODE_VALIDITY = Duration.ofMinutes(10);
    static final String CONSENT_VIEW = "oauth/consent";
    static final String ERROR_VIEW = "oauth/error";
    /** Request parameters carried from the consent page back to the approval POST. */
    static final List<String> FORWARDED_PARAMETERS = List.of("response_type", "client_id", "redirect_uri",
            "code_challenge", "code_challenge_method", "scope", "state", "resource");

    private static final Logger log = LoggerFactory.getLogger(AuthorizationController.class);

    private final OAuthClientRepository clients;
    private final AuthorizationCodeRepository codes;
    private final PublicUrlResolver urls;

    public AuthorizationController(OAuthClientRepository clients, AuthorizationCodeRepository codes,
                                   PublicUrlResolver urls) {
        this.clients = clients;
        this.codes = codes;
        this.urls = urls;
    }

    /** A validated authorization request. */
    record AuthorizationRequest(OAuthClient client, String redirectUri, String codeChallenge,
                                String codeChallengeMethod, String scope, String state, String resource) {
    }

    /** Problem that must not be redirected (unknown client / redirect URI): rendered as an error page. */
    static final class ErrorPageException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        ErrorPageException(String message) {
            super(message);
        }
    }

    /** Problem reported to the client by redirect (client and redirect URI are valid). */
    static final class RedirectErrorException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String redirectUri;
        private final String error;
        private final String state;

        RedirectErrorException(String redirectUri, String error, String description, String state) {
            super(description);
            this.redirectUri = redirectUri;
            this.error = error;
            this.state = state;
        }
    }

    @GetMapping("/oauth/authorize")
    public ModelAndView authorize(@RequestParam MultiValueMap<String, String> params, Authentication authentication,
                                  HttpServletResponse response) {
        try {
            AuthorizationRequest request = validate(params);
            KinaPrincipal user = currentUser(authentication);
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            Map<String, String> hidden = new LinkedHashMap<>();
            for (String name : FORWARDED_PARAMETERS) {
                String value = params.getFirst(name);
                if (value != null && !value.isEmpty()) {
                    hidden.put(name, value);
                }
            }
            ModelAndView view = new ModelAndView(CONSENT_VIEW);
            view.addObject("clientName", request.client().displayName());
            view.addObject("clientId", request.client().clientId());
            view.addObject("redirectTarget", redirectTarget(request.redirectUri()));
            view.addObject("userName", user.displayName());
            view.addObject("scope", request.scope());
            view.addObject("params", hidden);
            return view;
        } catch (ErrorPageException e) {
            return errorPage(e.getMessage());
        } catch (RedirectErrorException e) {
            return redirectError(e);
        }
    }

    @PostMapping("/oauth/authorize")
    public ModelAndView decide(@RequestParam MultiValueMap<String, String> params,
                               @RequestParam(name = "decision", required = false) String decision,
                               Authentication authentication) {
        try {
            AuthorizationRequest request = validate(params);
            KinaPrincipal user = currentUser(authentication);
            if (!"approve".equals(decision)) {
                throw new RedirectErrorException(request.redirectUri(), "access_denied",
                        "The user denied the request", request.state());
            }
            String code = SecureTokens.randomBase64Url(32);
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            codes.insert(new AuthorizationCode(SecureTokens.sha256Hex(code), request.client().clientId(),
                    user.userId(), request.redirectUri(), request.scope(), request.resource(), request.codeChallenge(),
                    request.codeChallengeMethod(), now, now.plus(CODE_VALIDITY), null));
            log.info("User {} authorized OAuth client {}", user.userId(), request.client().clientId());
            Map<String, String> query = new LinkedHashMap<>();
            query.put("code", code);
            if (request.state() != null) {
                query.put("state", request.state());
            }
            return redirect(request.redirectUri(), query);
        } catch (ErrorPageException e) {
            return errorPage(e.getMessage());
        } catch (RedirectErrorException e) {
            return redirectError(e);
        }
    }

    AuthorizationRequest validate(MultiValueMap<String, String> params) {
        String clientId = singleForPage(params, "client_id");
        if (clientId == null) {
            throw new ErrorPageException("The request has no client_id.");
        }
        OAuthClient client = clients.findById(clientId)
                .orElseThrow(() -> new ErrorPageException("Unknown client. Register the client first."));
        String redirectUri = singleForPage(params, "redirect_uri");
        if (redirectUri == null) {
            if (client.redirectUris().size() != 1) {
                throw new ErrorPageException("The request has no redirect_uri.");
            }
            redirectUri = client.redirectUris().getFirst();
        } else if (!client.redirectUris().contains(redirectUri)) {
            throw new ErrorPageException("The redirect_uri is not registered for this client.");
        }
        String state = singleForPage(params, "state");

        // From here on, errors are returned to the (validated) redirect URI.
        for (Map.Entry<String, List<String>> entry : params.entrySet()) {
            if (FORWARDED_PARAMETERS.contains(entry.getKey()) && entry.getValue().size() > 1) {
                throw new RedirectErrorException(redirectUri, OAuthException.INVALID_REQUEST,
                        "Parameter " + entry.getKey() + " is repeated", state);
            }
        }
        String responseType = params.getFirst("response_type");
        if (responseType == null || responseType.isEmpty()) {
            throw new RedirectErrorException(redirectUri, OAuthException.INVALID_REQUEST, "response_type is required",
                    state);
        }
        if (!"code".equals(responseType)) {
            throw new RedirectErrorException(redirectUri, "unsupported_response_type",
                    "Only response_type=code is supported", state);
        }
        if (!client.grantTypes().contains(TokenController.GRANT_AUTHORIZATION_CODE)) {
            throw new RedirectErrorException(redirectUri, OAuthException.UNAUTHORIZED_CLIENT,
                    "Client may not use the authorization code grant", state);
        }
        String codeChallenge = params.getFirst("code_challenge");
        String method = params.getFirst("code_challenge_method");
        if (codeChallenge == null || codeChallenge.isEmpty()) {
            throw new RedirectErrorException(redirectUri, OAuthException.INVALID_REQUEST,
                    "code_challenge is required (PKCE)", state);
        }
        if (!Pkce.S256.equals(method)) {
            throw new RedirectErrorException(redirectUri, OAuthException.INVALID_REQUEST,
                    "code_challenge_method must be S256", state);
        }
        if (!Pkce.isValidS256Challenge(codeChallenge)) {
            throw new RedirectErrorException(redirectUri, OAuthException.INVALID_REQUEST,
                    "Malformed code_challenge", state);
        }
        String resource = params.getFirst("resource");
        if (resource != null && resource.isEmpty()) {
            resource = null;
        }
        if (resource != null && !resource.startsWith(urls.baseUrl())) {
            log.info("OAuth client {} requested resource {} outside the public origin {}", clientId, resource,
                    urls.baseUrl());
        }
        // KINA has a single scope; requested scopes are not rejected, the granted scope is always "kina"
        // (RFC 6749 section 3.3 allows granting a different scope; it is echoed in the token response).
        return new AuthorizationRequest(client, redirectUri, codeChallenge, method, OAuthMetadataController.SCOPE,
                state, resource);
    }

    private static KinaPrincipal currentUser(Authentication authentication) {
        return KinaPrincipal.from(authentication)
                .orElseThrow(() -> new ErrorPageException("You are not signed in."));
    }

    private static String singleForPage(MultiValueMap<String, String> params, String name) {
        List<String> values = params.get(name);
        if (values == null || values.isEmpty() || values.getFirst() == null || values.getFirst().isEmpty()) {
            return null;
        }
        if (values.size() > 1) {
            throw new ErrorPageException("Parameter " + name + " is repeated.");
        }
        return values.getFirst();
    }

    private static String redirectTarget(String redirectUri) {
        try {
            URI uri = URI.create(redirectUri);
            return uri.getHost() != null ? uri.getScheme() + "://" + uri.getAuthority() : uri.getScheme() + ":";
        } catch (IllegalArgumentException e) {
            return redirectUri;
        }
    }

    private static ModelAndView errorPage(String message) {
        ModelAndView view = new ModelAndView(ERROR_VIEW);
        view.addObject("title", "Authorization request rejected");
        view.addObject("message", message);
        view.setStatus(HttpStatus.BAD_REQUEST);
        return view;
    }

    private static ModelAndView redirectError(RedirectErrorException e) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("error", e.error);
        query.put("error_description", e.getMessage());
        if (e.state != null) {
            query.put("state", e.state);
        }
        return redirect(e.redirectUri, query);
    }

    /** 302 to {@code redirectUri} with {@code query} appended (strictly percent-encoded). */
    static ModelAndView redirect(String redirectUri, Map<String, String> query) {
        StringBuilder url = new StringBuilder(redirectUri);
        char separator = redirectUri.contains("?") ? '&' : '?';
        for (Map.Entry<String, String> entry : query.entrySet()) {
            url.append(separator).append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
            separator = '&';
        }
        RedirectView view = new RedirectView(url.toString());
        view.setExpandUriTemplateVariables(false);
        view.setExposeModelAttributes(false);
        view.setPropagateQueryParams(false);
        view.setStatusCode(HttpStatus.FOUND);
        return new ModelAndView(view);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
