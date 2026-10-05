package ro.alacrity.kina.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import ro.alacrity.kina.web.PublicUrlResolver;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 401 for {@code /api/**} and {@code /mcp/**}: RFC 9457 problem body plus
 * {@code WWW-Authenticate: Bearer realm="kina", resource_metadata="<public>/.well-known/oauth-protected-resource"}
 * ({@code error="invalid_token"} added when a token was presented). The {@code resource_metadata} parameter is how
 * MCP clients (Claude's connector) discover the authorization server.
 */
public class BearerAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PublicUrlResolver urls;

    public BearerAuthenticationEntryPoint(PublicUrlResolver urls) {
        this.urls = urls;
    }

    /** Signals that a bearer token was presented but is unknown, expired or revoked. */
    public static class InvalidBearerTokenException extends AuthenticationException {

        public InvalidBearerTokenException(String message) {
            super(message);
        }
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        boolean invalidToken = authException instanceof InvalidBearerTokenException;
        StringBuilder header = new StringBuilder("Bearer realm=\"kina\", resource_metadata=\"")
                .append(urls.protectedResourceMetadataUrl()).append('"');
        if (invalidToken) {
            header.append(", error=\"invalid_token\"");
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, header.toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", "Unauthorized");
        problem.put("status", 401);
        problem.put("detail", invalidToken
                ? "The access token is invalid, expired or revoked."
                : "A bearer access token is required.");
        problem.put("instance", request.getRequestURI());
        response.getOutputStream().write(JSON.writeValueAsBytes(problem));
    }
}
