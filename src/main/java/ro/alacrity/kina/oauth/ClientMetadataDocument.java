package ro.alacrity.kina.oauth;

import ro.alacrity.kina.oauth.OAuthClientRepository.OAuthClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * OAuth Client ID Metadata Documents (CIMD; MCP authorization spec 2025-11-25, draft-ietf-oauth-client-id-metadata-
 * document): the client uses an {@code https} URL as its {@code client_id}; the document at that URL describes it.
 * This class holds the pure rules (URL shape, document validation, redirect URI matching); fetching and trust live in
 * {@link ClientMetadataDocumentResolver}.
 */
public final class ClientMetadataDocument {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");
    /** Fields that must not appear in a metadata document (shared-secret authentication). */
    private static final Set<String> FORBIDDEN_FIELDS = Set.of("client_secret", "client_secret_expires_at");
    private static final Set<String> COPIED_FIELDS = Set.of("client_uri", "logo_uri", "tos_uri", "policy_uri",
            "software_id", "software_version");

    private ClientMetadataDocument() {
    }

    /** A rejected {@code client_id} URL or document; the message is safe to show. */
    public static final class InvalidDocumentException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public InvalidDocumentException(String message) {
            super(message);
        }
    }

    /** True when {@code clientId} is meant as a metadata document URL (starts with {@code https://}). */
    public static boolean isMetadataUrl(String clientId) {
        return clientId != null && clientId.regionMatches(true, 0, "https://", 0, 8);
    }

    /**
     * Validates a {@code client_id} URL: {@code https}, a host, a path other than {@code /}, no user info, no fragment,
     * no {@code .} or {@code ..} path segments. Returns the parsed URI.
     */
    public static URI validateUrl(String clientId) {
        URI uri;
        try {
            uri = new URI(clientId);
        } catch (URISyntaxException e) {
            throw new InvalidDocumentException("client_id is not a valid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new InvalidDocumentException("client_id URL must use https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new InvalidDocumentException("client_id URL needs a host");
        }
        if (uri.getRawUserInfo() != null) {
            throw new InvalidDocumentException("client_id URL must not contain user info");
        }
        if (uri.getRawFragment() != null || clientId.contains("#")) {
            throw new InvalidDocumentException("client_id URL must not contain a fragment");
        }
        String path = uri.getRawPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            throw new InvalidDocumentException("client_id URL must contain a path");
        }
        for (String segment : path.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new InvalidDocumentException("client_id URL must not contain dot segments");
            }
        }
        return uri;
    }

    /**
     * Parses and validates a fetched document for {@code clientId}: a JSON object whose {@code client_id} equals the
     * URL exactly, with non-empty valid {@code redirect_uris}, {@code token_endpoint_auth_method} {@code none} (the
     * default; KINA supports no asymmetric client authentication and the spec forbids shared secrets),
     * {@code grant_types} including {@code authorization_code} (default {@code ["authorization_code"]}; types KINA does
     * not support are ignored) and {@code response_types} including {@code code}.
     */
    public static OAuthClient parse(String clientId, String body, Instant now) {
        JsonNode doc;
        try {
            doc = JSON.readTree(body);
        } catch (JacksonException e) {
            throw new InvalidDocumentException("Client metadata document is not valid JSON");
        }
        if (doc == null || !doc.isObject()) {
            throw new InvalidDocumentException("Client metadata document must be a JSON object");
        }
        JsonNode documentId = doc.get("client_id");
        if (documentId == null || !documentId.isString() || !clientId.equals(documentId.asString())) {
            throw new InvalidDocumentException("Client metadata document client_id does not match its URL");
        }
        for (String forbidden : FORBIDDEN_FIELDS) {
            if (doc.has(forbidden)) {
                throw new InvalidDocumentException("Client metadata document must not contain " + forbidden);
            }
        }
        List<String> redirectUris = strings(doc, "redirect_uris");
        if (redirectUris == null || redirectUris.isEmpty()) {
            throw new InvalidDocumentException("Client metadata document has no redirect_uris");
        }
        if (redirectUris.size() > ClientRegistrationController.MAX_REDIRECT_URIS) {
            throw new InvalidDocumentException("Client metadata document has too many redirect_uris");
        }
        try {
            redirectUris.forEach(ClientRegistrationController::validateRedirectUri);
        } catch (OAuthException e) {
            throw new InvalidDocumentException(e.description());
        }
        String authMethod = text(doc, "token_endpoint_auth_method");
        if (authMethod != null && !ClientRegistrationController.AUTH_NONE.equals(authMethod)) {
            throw new InvalidDocumentException("Unsupported token_endpoint_auth_method " + authMethod
                    + " (KINA accepts metadata-document clients with \"none\" only)");
        }
        List<String> grantTypes = strings(doc, "grant_types");
        if (grantTypes == null || grantTypes.isEmpty()) {
            grantTypes = List.of(TokenController.GRANT_AUTHORIZATION_CODE);
        }
        List<String> supported = grantTypes.stream().filter(ClientRegistrationController.GRANT_TYPES::contains)
                .distinct().toList();
        if (!supported.contains(TokenController.GRANT_AUTHORIZATION_CODE)) {
            throw new InvalidDocumentException("Client metadata document grant_types must include authorization_code");
        }
        List<String> responseTypes = strings(doc, "response_types");
        if (responseTypes != null && !responseTypes.isEmpty() && !responseTypes.contains("code")) {
            throw new InvalidDocumentException("Client metadata document response_types must include code");
        }
        String name = text(doc, "client_name");
        if (name != null) {
            name = name.strip();
            if (name.length() > ClientRegistrationController.MAX_CLIENT_NAME) {
                name = name.substring(0, ClientRegistrationController.MAX_CLIENT_NAME);
            }
            if (name.isEmpty()) {
                name = null;
            }
        }
        if (name == null) {
            name = URI.create(clientId).getHost();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (String field : COPIED_FIELDS) {
            String value = text(doc, field);
            if (value != null) {
                metadata.put(field, value);
            }
        }
        return new OAuthClient(clientId, null, name, List.copyOf(new LinkedHashSet<>(redirectUris)), supported,
                List.of("code"), ClientRegistrationController.AUTH_NONE, text(doc, "scope"), metadata, now, clientId);
    }

    /**
     * Matches a requested {@code redirect_uri} against the document's {@code redirect_uris}: exact string comparison,
     * except that a loopback {@code http} redirect URI may use any port (RFC 8252 section 7.3; native clients such as
     * Claude Code publish {@code http://localhost/callback} and listen on an ephemeral port).
     */
    public static boolean redirectUriAllowed(List<String> registered, String requested) {
        if (registered.contains(requested)) {
            return true;
        }
        URI candidate = loopback(requested);
        if (candidate == null) {
            return false;
        }
        for (String entry : registered) {
            URI allowed = loopback(entry);
            if (allowed != null && allowed.getHost().equalsIgnoreCase(candidate.getHost())
                    && java.util.Objects.equals(allowed.getRawPath(), candidate.getRawPath())
                    && java.util.Objects.equals(allowed.getRawQuery(), candidate.getRawQuery())) {
                return true;
            }
        }
        return false;
    }

    /** True for an {@code http} redirect URI on a loopback host (consent is still shown for those). */
    public static boolean isLoopbackRedirect(String redirectUri) {
        return loopback(redirectUri) != null;
    }

    private static URI loopback(String value) {
        try {
            URI uri = new URI(value);
            if ("http".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
                return uri;
            }
        } catch (URISyntaxException e) {
            return null;
        }
        return null;
    }

    private static String text(JsonNode doc, String field) {
        JsonNode node = doc.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw new InvalidDocumentException("Client metadata document field " + field + " must be a string");
        }
        return node.asString();
    }

    private static List<String> strings(JsonNode doc, String field) {
        JsonNode node = doc.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw new InvalidDocumentException("Client metadata document field " + field + " must be an array");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode element : node) {
            if (!element.isString() || element.asString().isBlank()) {
                throw new InvalidDocumentException("Client metadata document field " + field
                        + " must contain strings");
            }
            result.add(element.asString());
        }
        return result;
    }
}
