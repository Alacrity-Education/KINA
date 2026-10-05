package ro.alacrity.kina.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test-only protected endpoint {@code GET /api/v1/test-echo} returning the authenticated {@link KinaPrincipal}.
 * Registered explicitly with {@code @Import}; the enclosing {@code @TestConfiguration} keeps it out of component
 * scanning for other test contexts.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestEchoController {

    @RestController
    public static class Endpoint {

        @GetMapping("/api/v1/test-echo")
        public Map<String, Object> echo(Authentication authentication) {
            KinaPrincipal principal = KinaPrincipal.from(authentication).orElseThrow();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("user_id", principal.userId().toString());
            body.put("display_name", principal.displayName());
            body.put("token_id", principal.tokenId() == null ? null : principal.tokenId().toString());
            body.put("authorities", authentication.getAuthorities().stream().map(Object::toString).sorted().toList());
            return body;
        }
    }
}
