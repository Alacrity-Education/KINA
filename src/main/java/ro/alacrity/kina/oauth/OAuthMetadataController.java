package ro.alacrity.kina.oauth;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.web.PublicUrlResolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovery documents: OAuth 2.0 Protected Resource Metadata (RFC 9728) for {@code /mcp} and Authorization Server
 * Metadata (RFC 8414). All URLs are derived from the public origin ({@link PublicUrlResolver}).
 */
@RestController
public class OAuthMetadataController {

    public static final String SCOPE = "kina";

    private final PublicUrlResolver urls;
    private final boolean clientMetadataDocuments;

    public OAuthMetadataController(PublicUrlResolver urls, KinaProperties properties) {
        this.urls = urls;
        this.clientMetadataDocuments = !properties.oauth().trustedClientMetadataHosts().isEmpty();
    }

    @GetMapping(path = {"/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> protectedResource() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("resource", urls.mcpUrl());
        metadata.put("authorization_servers", List.of(urls.baseUrl()));
        metadata.put("bearer_methods_supported", List.of("header"));
        metadata.put("scopes_supported", List.of(SCOPE));
        metadata.put("resource_name", "KINA");
        return metadata;
    }

    @GetMapping(path = {"/.well-known/oauth-authorization-server", "/.well-known/oauth-authorization-server/mcp",
            "/.well-known/openid-configuration"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> authorizationServer() {
        List<String> authMethods = List.of(ClientRegistrationController.AUTH_NONE,
                ClientRegistrationController.AUTH_CLIENT_SECRET_BASIC, ClientRegistrationController.AUTH_CLIENT_SECRET_POST);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issuer", urls.baseUrl());
        metadata.put("authorization_endpoint", urls.url("/oauth/authorize"));
        metadata.put("token_endpoint", urls.url("/oauth/token"));
        metadata.put("registration_endpoint", urls.url("/oauth/register"));
        metadata.put("revocation_endpoint", urls.url("/oauth/revoke"));
        metadata.put("response_types_supported", List.of("code"));
        metadata.put("response_modes_supported", List.of("query"));
        metadata.put("grant_types_supported", List.of(TokenController.GRANT_AUTHORIZATION_CODE,
                TokenController.GRANT_REFRESH_TOKEN));
        metadata.put("code_challenge_methods_supported", List.of(Pkce.S256));
        metadata.put("token_endpoint_auth_methods_supported", authMethods);
        metadata.put("revocation_endpoint_auth_methods_supported", authMethods);
        metadata.put("scopes_supported", List.of(SCOPE));
        // Client ID Metadata Documents (MCP authorization 2025-11-25): https client_id URLs on trusted hosts.
        metadata.put("client_id_metadata_document_supported", clientMetadataDocuments);
        return metadata;
    }
}
