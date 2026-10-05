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
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.oauth.AuthorizationCodeRepository.AuthorizationCode;
import ro.alacrity.kina.oauth.OAuthClientRepository.OAuthClient;
import ro.alacrity.kina.security.KinaPrincipal;
import ro.alacrity.kina.security.MembershipVerifier;
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
 * consent page (skipped for trusted Client ID Metadata Document clients, see {@link #autoApproves}); Approve issues a
 * 10-minute single-use code bound to client, redirect URI, user, PKCE challenge, scope
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

    private final OAuthClientLookup clients;
    private final AuthorizationCodeRepository codes;
    private final PublicUrlResolver urls;
    private final MembershipVerifier membership;
    private final boolean autoApproveTrustedClients;
    private final Duration accessTokenValidity;
    private final Duration refreshTokenValidity;

    public AuthorizationController(OAuthClientLookup clients, AuthorizationCodeRepository codes,
                                   PublicUrlResolver urls, MembershipVerifier membership, KinaProperties properties) {
        this.clients = clients;
        this.codes = codes;
        this.urls = urls;
        this.membership = membership;
        this.autoApproveTrustedClients = properties.oauth().autoApproveTrustedClients();
        this.accessTokenValidity = properties.oauth().accessTokenValidity();
        this.refreshTokenValidity = properties.oauth().refreshTokenValidity();
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
            if (autoApproves(request)) {
                // Trusted Client ID Metadata Document client (e.g. Claude's published identity): the redirect URI
                // comes from a document on an allowlisted host, so the consent page is skipped.
                log.info("Auto-approved metadata-document client {} for user {}", request.client().clientId(),
                        user.userId());
                return issueCode(request, user);
            }
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
            view.addObject("metadataHost", request.client().isMetadataDocumentClient()
                    ? URI.create(request.client().clientId()).getHost() : null);
            view.addObject("loopbackRedirect", ClientMetadataDocument.isLoopbackRedirect(request.redirectUri()));
            view.addObject("accessTokenValidity", human(accessTokenValidity));
            view.addObject("refreshTokenValidity", human(refreshTokenValidity));
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
            log.info("User {} authorized OAuth client {}", user.userId(), request.client().clientId());
            return issueCode(request, user);
        } catch (ErrorPageException e) {
            return errorPage(e.getMessage());
        } catch (RedirectErrorException e) {
            return redirectError(e);
        }
    }

    /** Issues a single-use code for an approved request and redirects to the client. */
    private ModelAndView issueCode(AuthorizationRequest request, KinaPrincipal user) {
        String code = SecureTokens.randomBase64Url(32);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        codes.insert(new AuthorizationCode(SecureTokens.sha256Hex(code), request.client().clientId(),
                user.userId(), request.redirectUri(), request.scope(), request.resource(), request.codeChallenge(),
                request.codeChallengeMethod(), now, now.plus(CODE_VALIDITY), null));
        Map<String, String> query = new LinkedHashMap<>();
        query.put("code", code);
        if (request.state() != null) {
            query.put("state", request.state());
        }
        return redirect(request.redirectUri(), query);
    }

    /**
     * Consent is skipped only for Client ID Metadata Document clients (their host is allowlisted) when
     * {@code kina.oauth.auto-approve-trusted-clients} is on and the redirect URI is not a loopback address: any local
     * program can listen on a loopback port and claim a trusted client's identity (MCP spec, CIMD security
     * considerations), so loopback redirects keep the consent page.
     */
    private boolean autoApproves(AuthorizationRequest request) {
        return autoApproveTrustedClients && request.client().isMetadataDocumentClient()
                && !ClientMetadataDocument.isLoopbackRedirect(request.redirectUri());
    }

    AuthorizationRequest validate(MultiValueMap<String, String> params) {
        String clientId = singleForPage(params, "client_id");
        if (clientId == null) {
            throw new ErrorPageException("The request has no client_id.");
        }
        OAuthClient client;
        try {
            client = clients.find(clientId);
        } catch (OAuthClientLookup.UnknownClientException e) {
            throw new ErrorPageException(e.getMessage());
        }
        String redirectUri = singleForPage(params, "redirect_uri");
        if (redirectUri == null) {
            if (client.redirectUris().size() != 1) {
                throw new ErrorPageException("The request has no redirect_uri.");
            }
            redirectUri = client.redirectUris().getFirst();
        } else if (client.isMetadataDocumentClient()
                ? !ClientMetadataDocument.redirectUriAllowed(client.redirectUris(), redirectUri)
                : !client.redirectUris().contains(redirectUri)) {
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

    private KinaPrincipal currentUser(Authentication authentication) {
        KinaPrincipal user = KinaPrincipal.from(authentication)
                .orElseThrow(() -> new ErrorPageException("You are not signed in."));
        if (membership.isBlocked(user.userId())) {
            throw new ErrorPageException("Your access to KINA has been revoked. Sign in again; access requires "
                    + "membership in the required group.");
        }
        return user;
    }

    /** {@code 1 hour}, {@code 30 days}, {@code 90 minutes}. */
    static String human(Duration duration) {
        if (duration.toDays() > 0 && duration.equals(Duration.ofDays(duration.toDays()))) {
            return duration.toDays() + (duration.toDays() == 1 ? " day" : " days");
        }
        if (duration.toHours() > 0 && duration.equals(Duration.ofHours(duration.toHours()))) {
            return duration.toHours() + (duration.toHours() == 1 ? " hour" : " hours");
        }
        return duration.toMinutes() + " minutes";
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
