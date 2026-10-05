package ro.alacrity.kina.web;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import ro.alacrity.kina.config.KinaProperties;

/**
 * Public origin of KINA as seen by clients. {@code kina.public-base-url} wins when set; otherwise the origin (and
 * context path) of the current request, which already reflects {@code X-Forwarded-Proto/Host/Port/Prefix} because
 * {@code server.forward-headers-strategy=framework} installs Spring's {@code ForwardedHeaderFilter}.
 * Every emitted URL (OAuth metadata, redirects, {@code resource_metadata}) goes through this class.
 */
@Component
@RequiredArgsConstructor
public class PublicUrlResolver {

    private final KinaProperties properties;

    /** Base URL without trailing slash, e.g. {@code https://kina.example.com}. Requires a current request unless configured. */
    public String baseUrl() {
        if (properties.hasPublicBaseUrl()) {
            return stripTrailingSlash(properties.publicBaseUrl().strip());
        }
        return stripTrailingSlash(ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString());
    }

    /** {@code baseUrl() + path}; {@code path} must start with {@code /}. */
    public String url(String path) {
        return baseUrl() + path;
    }

    /** The MCP endpoint, also the OAuth protected resource identifier. */
    public String mcpUrl() {
        return url("/mcp");
    }

    public String protectedResourceMetadataUrl() {
        return url("/.well-known/oauth-protected-resource");
    }

    private static String stripTrailingSlash(String url) {
        String result = url;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
