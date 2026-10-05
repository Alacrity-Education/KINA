package ro.alacrity.kina.oauth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Shared error handling of the JSON OAuth endpoints. Handlers are local to the controllers (inherited), so they win
 * over any global {@code @ControllerAdvice}. Every response carries {@code Cache-Control: no-store}.
 */
abstract class OAuthEndpointSupport {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OAuthError(@JsonProperty("error") String error,
                             @JsonProperty("error_description") String errorDescription) {
    }

    static HttpHeaders noStore() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        headers.setPragma("no-cache");
        return headers;
    }

    @ExceptionHandler(OAuthException.class)
    ResponseEntity<OAuthError> handleOAuthException(OAuthException e) {
        HttpHeaders headers = noStore();
        if (e.status() == HttpStatus.UNAUTHORIZED) {
            headers.set(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"kina\"");
        }
        return ResponseEntity.status(e.status()).headers(headers).body(new OAuthError(e.error(), e.description()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    ResponseEntity<OAuthError> handleUnreadable(Exception e) {
        return ResponseEntity.badRequest().headers(noStore())
                .body(new OAuthError(malformedRequestError(), "Malformed request body"));
    }

    /** Error code used for unreadable bodies. */
    String malformedRequestError() {
        return OAuthException.INVALID_REQUEST;
    }
}
