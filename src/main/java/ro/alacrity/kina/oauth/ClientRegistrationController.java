package ro.alacrity.kina.oauth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ro.alacrity.kina.oauth.OAuthClientRepository.OAuthClient;
import ro.alacrity.kina.security.SecureTokens;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Dynamic client registration (RFC 7591), anonymous. Validation rules: DESIGN.md section 7.
 */
@RestController
public class ClientRegistrationController extends OAuthEndpointSupport {

    public static final String AUTH_NONE = "none";
    public static final String AUTH_CLIENT_SECRET_BASIC = "client_secret_basic";
    public static final String AUTH_CLIENT_SECRET_POST = "client_secret_post";

    static final Set<String> AUTH_METHODS = Set.of(AUTH_NONE, AUTH_CLIENT_SECRET_BASIC, AUTH_CLIENT_SECRET_POST);
    static final Set<String> GRANT_TYPES = Set.of(TokenController.GRANT_AUTHORIZATION_CODE,
            TokenController.GRANT_REFRESH_TOKEN);
    static final int MAX_REDIRECT_URIS = 20;
    static final int MAX_CLIENT_NAME = 200;
    private static final Set<String> FORBIDDEN_SCHEMES = Set.of("javascript", "data", "file", "vbscript", "about",
            "blob", "ftp", "ws", "wss");
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");
    /** Registration fields stored in dedicated columns; everything else goes to {@code metadata}. */
    private static final Set<String> KNOWN_FIELDS = Set.of("redirect_uris", "client_name", "token_endpoint_auth_method",
            "grant_types", "response_types", "scope", "client_id", "client_secret", "client_id_issued_at",
            "client_secret_expires_at");

    private static final Logger log = LoggerFactory.getLogger(ClientRegistrationController.class);

    private final OAuthClientRepository clients;

    public ClientRegistrationController(OAuthClientRepository clients) {
        this.clients = clients;
    }

    @PostMapping(path = "/oauth/register", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, Object> request) {
        List<String> redirectUris = stringList(request.get("redirect_uris"), "redirect_uris",
                OAuthException.INVALID_REDIRECT_URI);
        if (redirectUris == null || redirectUris.isEmpty()) {
            throw new OAuthException(OAuthException.INVALID_REDIRECT_URI, "redirect_uris is required");
        }
        if (redirectUris.size() > MAX_REDIRECT_URIS) {
            throw new OAuthException(OAuthException.INVALID_REDIRECT_URI, "Too many redirect_uris");
        }
        redirectUris.forEach(ClientRegistrationController::validateRedirectUri);

        String authMethod = optionalString(request.get("token_endpoint_auth_method"), "token_endpoint_auth_method");
        if (authMethod == null) {
            authMethod = AUTH_NONE;
        }
        if (!AUTH_METHODS.contains(authMethod)) {
            throw new OAuthException(OAuthException.INVALID_CLIENT_METADATA,
                    "Unsupported token_endpoint_auth_method: " + authMethod);
        }

        List<String> grantTypes = stringList(request.get("grant_types"), "grant_types",
                OAuthException.INVALID_CLIENT_METADATA);
        if (grantTypes == null || grantTypes.isEmpty()) {
            grantTypes = List.of(TokenController.GRANT_AUTHORIZATION_CODE, TokenController.GRANT_REFRESH_TOKEN);
        }
        for (String grantType : grantTypes) {
            if (!GRANT_TYPES.contains(grantType)) {
                throw new OAuthException(OAuthException.INVALID_CLIENT_METADATA, "Unsupported grant type: " + grantType);
            }
        }
        if (!grantTypes.contains(TokenController.GRANT_AUTHORIZATION_CODE)) {
            throw new OAuthException(OAuthException.INVALID_CLIENT_METADATA,
                    "grant_types must include authorization_code");
        }

        List<String> responseTypes = stringList(request.get("response_types"), "response_types",
                OAuthException.INVALID_CLIENT_METADATA);
        if (responseTypes == null || responseTypes.isEmpty()) {
            responseTypes = List.of("code");
        }
        if (!responseTypes.equals(List.of("code"))) {
            throw new OAuthException(OAuthException.INVALID_CLIENT_METADATA, "Only response_types [\"code\"] is supported");
        }

        String clientName = optionalString(request.get("client_name"), "client_name");
        if (clientName != null) {
            clientName = clientName.strip();
            if (clientName.length() > MAX_CLIENT_NAME) {
                throw new OAuthException(OAuthException.INVALID_CLIENT_METADATA, "client_name is too long");
            }
            if (clientName.isEmpty()) {
                clientName = null;
            }
        }
        String scope = optionalString(request.get("scope"), "scope");

        Map<String, Object> extra = new LinkedHashMap<>();
        request.forEach((key, value) -> {
            if (!KNOWN_FIELDS.contains(key)) {
                extra.put(key, value);
            }
        });

        String clientId = SecureTokens.randomBase64Url(24); // 32 base64url characters
        String clientSecret = AUTH_NONE.equals(authMethod) ? null : SecureTokens.randomBase64Url(32);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OAuthClient client = new OAuthClient(clientId, clientSecret == null ? null : SecureTokens.sha256Hex(clientSecret),
                clientName, List.copyOf(new LinkedHashSet<>(redirectUris)), List.copyOf(new LinkedHashSet<>(grantTypes)),
                responseTypes, authMethod, scope, extra, now);
        clients.insert(client);
        log.info("Registered OAuth client {} ({}, auth method {})", clientId, client.displayName(), authMethod);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("client_id", clientId);
        if (clientSecret != null) {
            response.put("client_secret", clientSecret);
        }
        response.put("client_id_issued_at", now.getEpochSecond());
        response.put("client_secret_expires_at", 0);
        response.put("redirect_uris", client.redirectUris());
        if (clientName != null) {
            response.put("client_name", clientName);
        }
        response.put("token_endpoint_auth_method", authMethod);
        response.put("grant_types", client.grantTypes());
        response.put("response_types", client.responseTypes());
        if (scope != null) {
            response.put("scope", scope);
        }
        response.putAll(extra);
        return ResponseEntity.status(HttpStatus.CREATED).headers(noStore()).body(response);
    }

    @Override
    String malformedRequestError() {
        return OAuthException.INVALID_CLIENT_METADATA;
    }

    /**
     * Absolute, no fragment; {@code https}, {@code http} only for loopback hosts (any port), or a custom
     * (private-use) scheme. Dangerous schemes are rejected.
     */
    static void validateRedirectUri(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new OAuthException(OAuthException.INVALID_REDIRECT_URI, "Malformed redirect URI: " + value);
        }
        if (!uri.isAbsolute() || uri.getScheme() == null) {
            throw new OAuthException(OAuthException.INVALID_REDIRECT_URI, "Redirect URI must be absolute: " + value);
        }
        if (uri.getRawFragment() != null || value.contains("#")) {
            throw new OAuthException(OAuthException.INVALID_REDIRECT_URI,
                    "Redirect URI must not contain a fragment: " + value);
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        switch (scheme) {
            case "https" -> {
                if (uri.getHost() == null || uri.getHost().isBlank()) {
                    throw new OAuthException(OAuthException.INVALID_REDIRECT_URI, "Redirect URI needs a host: " + value);
                }
            }
            case "http" -> {
                String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
                if (host == null || !LOOPBACK_HOSTS.contains(host)) {
                    throw new OAuthException(OAuthException.INVALID_REDIRECT_URI,
                            "http redirect URIs are only allowed for localhost/127.0.0.1: " + value);
                }
            }
            default -> {
                if (FORBIDDEN_SCHEMES.contains(scheme)) {
                    throw new OAuthException(OAuthException.INVALID_REDIRECT_URI,
                            "Redirect URI scheme not allowed: " + value);
                }
            }
        }
    }

    private static List<String> stringList(Object value, String field, String error) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            throw new OAuthException(error, field + " must be an array of strings");
        }
        List<String> result = new ArrayList<>();
        for (Object element : list) {
            if (!(element instanceof String s) || s.isBlank()) {
                throw new OAuthException(error, field + " must be an array of strings");
            }
            result.add(s);
        }
        return result;
    }

    private static String optionalString(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s)) {
            throw new OAuthException(OAuthException.INVALID_CLIENT_METADATA, field + " must be a string");
        }
        return s;
    }
}
