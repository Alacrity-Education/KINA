package ro.alacrity.kina.oauth;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.oauth.ClientMetadataDocument.InvalidDocumentException;
import ro.alacrity.kina.oauth.OAuthClientRepository.OAuthClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientMetadataDocumentTest {

    static final String CLAUDE_CODE_URL = "https://claude.ai/oauth/claude-code-client-metadata";
    /** Claude Code's published document as fetched on 2026-10-05. */
    static final String CLAUDE_CODE_DOC = """
            {"client_id":"https://claude.ai/oauth/claude-code-client-metadata","client_name":"Claude Code",
             "client_uri":"https://claude.ai","redirect_uris":["http://localhost/callback","http://127.0.0.1/callback"],
             "grant_types":["authorization_code","refresh_token"],"response_types":["code"],
             "token_endpoint_auth_method":"none"}""";
    static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    @Test
    void parsesClaudeCodeDocument() {
        OAuthClient client = ClientMetadataDocument.parse(CLAUDE_CODE_URL, CLAUDE_CODE_DOC, NOW);
        assertThat(client.clientId()).isEqualTo(CLAUDE_CODE_URL);
        assertThat(client.metadataUrl()).isEqualTo(CLAUDE_CODE_URL);
        assertThat(client.isMetadataDocumentClient()).isTrue();
        assertThat(client.isPublic()).isTrue();
        assertThat(client.clientSecretHash()).isNull();
        assertThat(client.displayName()).isEqualTo("Claude Code");
        assertThat(client.redirectUris()).containsExactly("http://localhost/callback", "http://127.0.0.1/callback");
        assertThat(client.grantTypes()).containsExactly("authorization_code", "refresh_token");
        assertThat(client.responseTypes()).containsExactly("code");
        assertThat(client.metadata()).containsEntry("client_uri", "https://claude.ai");
    }

    @Test
    void defaultsAndIgnoredGrantTypes() {
        String url = "https://claude.ai/oauth/web";
        OAuthClient client = ClientMetadataDocument.parse(url, """
                {"client_id":"https://claude.ai/oauth/web","redirect_uris":["https://claude.ai/api/mcp/auth_callback"],
                 "grant_types":["authorization_code","urn:ietf:params:oauth:grant-type:jwt-bearer"]}""", NOW);
        assertThat(client.grantTypes()).containsExactly("authorization_code");
        assertThat(client.tokenEndpointAuthMethod()).isEqualTo("none");
        assertThat(client.displayName()).as("name falls back to the host").isEqualTo("claude.ai");
    }

    @Test
    void rejectsInvalidDocuments() {
        String url = "https://claude.ai/c.json";
        List<String> invalid = List.of(
                "not json",
                "[]",
                "{\"redirect_uris\":[\"https://a.example/cb\"]}",
                "{\"client_id\":\"https://claude.ai/other.json\",\"redirect_uris\":[\"https://a.example/cb\"]}",
                "{\"client_id\":\"" + url + "\"}",
                "{\"client_id\":\"" + url + "\",\"redirect_uris\":[]}",
                "{\"client_id\":\"" + url + "\",\"redirect_uris\":[\"javascript:alert(1)\"]}",
                "{\"client_id\":\"" + url + "\",\"redirect_uris\":[\"http://evil.example/cb\"]}",
                "{\"client_id\":\"" + url + "\",\"redirect_uris\":\"https://a.example/cb\"}",
                "{\"client_id\":\"" + url + "\",\"redirect_uris\":[\"https://a.example/cb\"],\"client_secret\":\"s\"}",
                "{\"client_id\":\"" + url + "\",\"redirect_uris\":[\"https://a.example/cb\"],"
                        + "\"token_endpoint_auth_method\":\"client_secret_basic\"}",
                "{\"client_id\":\"" + url + "\",\"redirect_uris\":[\"https://a.example/cb\"],"
                        + "\"token_endpoint_auth_method\":\"private_key_jwt\"}",
                "{\"client_id\":\"" + url + "\",\"redirect_uris\":[\"https://a.example/cb\"],"
                        + "\"grant_types\":[\"refresh_token\"]}",
                "{\"client_id\":\"" + url + "\",\"redirect_uris\":[\"https://a.example/cb\"],"
                        + "\"response_types\":[\"token\"]}",
                "{\"client_id\":\"" + url + "\",\"redirect_uris\":[\"https://a.example/cb\"],\"client_name\":5}");
        for (String doc : invalid) {
            assertThatThrownBy(() -> ClientMetadataDocument.parse(url, doc, NOW)).as(doc)
                    .isInstanceOf(InvalidDocumentException.class);
        }
    }

    @Test
    void validatesTheClientIdUrl() {
        assertThat(ClientMetadataDocument.validateUrl(CLAUDE_CODE_URL).getHost()).isEqualTo("claude.ai");
        assertThat(ClientMetadataDocument.validateUrl("https://claude.ai:8443/c.json?v=1").getPort()).isEqualTo(8443);
        for (String bad : List.of("http://claude.ai/c.json", "https://claude.ai", "https://claude.ai/",
                "https://user:pw@claude.ai/c.json", "https://claude.ai/c.json#x", "https://claude.ai/a/../c.json",
                "https://claude.ai/./c.json", "https:///c.json", "https://claude ai/c.json")) {
            assertThatThrownBy(() -> ClientMetadataDocument.validateUrl(bad)).as(bad)
                    .isInstanceOf(InvalidDocumentException.class);
        }
        assertThat(ClientMetadataDocument.isMetadataUrl("HTTPS://claude.ai/x")).isTrue();
        assertThat(ClientMetadataDocument.isMetadataUrl("abcDEF123")).isFalse();
        assertThat(ClientMetadataDocument.isMetadataUrl(null)).isFalse();
    }

    @Test
    void redirectUrisMatchExactlyExceptLoopbackPorts() {
        List<String> registered = List.of("http://localhost/callback", "http://127.0.0.1/callback",
                "https://claude.ai/api/mcp/auth_callback");
        assertThat(ClientMetadataDocument.redirectUriAllowed(registered, "https://claude.ai/api/mcp/auth_callback"))
                .isTrue();
        assertThat(ClientMetadataDocument.redirectUriAllowed(registered, "http://localhost:3118/callback")).isTrue();
        assertThat(ClientMetadataDocument.redirectUriAllowed(registered, "http://127.0.0.1:50001/callback")).isTrue();
        assertThat(ClientMetadataDocument.redirectUriAllowed(registered, "http://localhost:3118/other")).isFalse();
        assertThat(ClientMetadataDocument.redirectUriAllowed(registered, "http://localhost:3118/callback?x=1"))
                .isFalse();
        assertThat(ClientMetadataDocument.redirectUriAllowed(registered, "https://claude.ai:444/api/mcp/auth_callback"))
                .as("ports matter for https").isFalse();
        assertThat(ClientMetadataDocument.redirectUriAllowed(registered, "https://claude.ai/api/mcp/auth_callback/"))
                .isFalse();
        assertThat(ClientMetadataDocument.redirectUriAllowed(registered, "http://evil.example:80/callback")).isFalse();
        assertThat(ClientMetadataDocument.redirectUriAllowed(List.of("http://127.0.0.1/callback"),
                "http://localhost:9/callback")).as("loopback host must match").isFalse();

        assertThat(ClientMetadataDocument.isLoopbackRedirect("http://localhost:3118/callback")).isTrue();
        assertThat(ClientMetadataDocument.isLoopbackRedirect("https://claude.ai/api/mcp/auth_callback")).isFalse();
    }

    @Test
    void hostAllowlist() {
        List<String> trusted = List.of("claude.ai", "claude.com", "*.anthropic.com");
        assertThat(ClientMetadataDocumentResolver.hostTrusted(trusted, "claude.ai")).isTrue();
        assertThat(ClientMetadataDocumentResolver.hostTrusted(trusted, "CLAUDE.AI")).isTrue();
        assertThat(ClientMetadataDocumentResolver.hostTrusted(trusted, "claude.ai.")).isTrue();
        assertThat(ClientMetadataDocumentResolver.hostTrusted(trusted, "api.anthropic.com")).isTrue();
        assertThat(ClientMetadataDocumentResolver.hostTrusted(trusted, "a.b.anthropic.com")).isTrue();
        assertThat(ClientMetadataDocumentResolver.hostTrusted(trusted, "anthropic.com")).as("apex not covered").isFalse();
        assertThat(ClientMetadataDocumentResolver.hostTrusted(trusted, "evilanthropic.com")).isFalse();
        assertThat(ClientMetadataDocumentResolver.hostTrusted(trusted, "claude.ai.evil.com")).isFalse();
        assertThat(ClientMetadataDocumentResolver.hostTrusted(trusted, "sub.claude.ai")).isFalse();
        assertThat(ClientMetadataDocumentResolver.hostTrusted(trusted, null)).isFalse();
        assertThat(ClientMetadataDocumentResolver.hostTrusted(List.of(), "claude.ai")).isFalse();
    }
}
