package ro.alacrity.kina.oauth;

import org.springframework.http.HttpStatus;

import java.io.Serial;

/** An OAuth error response (RFC 6749 section 5.2, RFC 7591 section 3.2.2). */
public class OAuthException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String INVALID_REQUEST = "invalid_request";
    public static final String INVALID_CLIENT = "invalid_client";
    public static final String INVALID_GRANT = "invalid_grant";
    public static final String UNAUTHORIZED_CLIENT = "unauthorized_client";
    public static final String UNSUPPORTED_GRANT_TYPE = "unsupported_grant_type";
    public static final String INVALID_REDIRECT_URI = "invalid_redirect_uri";
    public static final String INVALID_CLIENT_METADATA = "invalid_client_metadata";

    private final String error;
    private final HttpStatus status;

    public OAuthException(String error, String description) {
        this(error, description, INVALID_CLIENT.equals(error) ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST);
    }

    public OAuthException(String error, String description, HttpStatus status) {
        super(description);
        this.error = error;
        this.status = status;
    }

    public String error() {
        return error;
    }

    public String description() {
        return getMessage();
    }

    public HttpStatus status() {
        return status;
    }
}
