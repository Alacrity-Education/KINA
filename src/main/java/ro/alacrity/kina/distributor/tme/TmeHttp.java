package ro.alacrity.kina.distributor.tme;

import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.distributor.DistributorException.Kind;
import ro.alacrity.kina.domain.Distributor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared plumbing for TME calls: raw exchange, JSON decoding and the error mapping of DESIGN.md 9.2. */
final class TmeHttp {

    /** Lenient mapper for TME payloads. */
    static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private TmeHttp() {
    }

    /** Status code and body bytes of a completed exchange. */
    record Response(int status, byte[] body) {

        boolean isSuccess() {
            return status >= 200 && status < 300;
        }

        String bodySnippet() {
            String text = new String(body, StandardCharsets.UTF_8).strip();
            return text.length() > 300 ? text.substring(0, 300) + "..." : text;
        }
    }

    /** Executes the request, returning status and body; transport failures become {@link DistributorException}. */
    static Response exchange(RestClient.RequestHeadersSpec<?> request, String what) {
        try {
            return request.exchange((req, res) -> new Response(res.getStatusCode().value(),
                    StreamUtils.copyToByteArray(res.getBody())));
        } catch (RestClientException e) {
            throw transportFailure(e, what);
        }
    }

    static DistributorException transportFailure(RestClientException e, String what) {
        if (e instanceof ResourceAccessException && isTimeout(e)) {
            return new DistributorException(Distributor.TME, Kind.TIMEOUT, what + " timed out", e);
        }
        return new DistributorException(Distributor.TME, Kind.UNAVAILABLE, what + " failed: " + e.getMessage(), e);
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    static <T> T decode(Response response, Class<T> type, String what) {
        try {
            T value = MAPPER.readValue(response.body(), type);
            if (value == null) {
                throw new DistributorException(Distributor.TME, Kind.BAD_RESPONSE, what + " returned an empty body");
            }
            return value;
        } catch (JacksonException e) {
            throw new DistributorException(Distributor.TME, Kind.BAD_RESPONSE,
                    what + " returned an unreadable body: " + e.getOriginalMessage(), e);
        }
    }

    /** Parses a TME error body; null when the body is not one. */
    static TmeResponses.ErrorResponse error(Response response) {
        try {
            TmeResponses.ErrorResponse error = MAPPER.readValue(response.body(), TmeResponses.ErrorResponse.class);
            return error != null && (error.code() != null || error.message() != null) ? error : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    /**
     * True for an authentication failure: HTTP 401, or TME's {@code E_AUTH*} codes (an invalid or expired bearer token
     * is answered with HTTP 400 {@code E_AUTH_TOKEN_IS_INVALID}).
     */
    static boolean isAuthFailure(Response response, TmeResponses.ErrorResponse error) {
        if (response.status() == 401) {
            return true;
        }
        return error != null && error.code() != null && error.code().toUpperCase(Locale.ROOT).startsWith("E_AUTH");
    }

    /** Maps a non-2xx response (not an auth failure that is still retryable) to a {@link DistributorException}. */
    static DistributorException failure(Response response, TmeResponses.ErrorResponse error, String what) {
        int status = response.status();
        String detail = describe(response, error);
        String code = error == null || error.code() == null ? "" : error.code().toUpperCase(Locale.ROOT);
        Kind kind;
        if (status == 429 || code.contains("TOO_MANY") || code.contains("RATE_LIMIT")) {
            kind = Kind.RATE_LIMITED;
        } else if (isAuthFailure(response, error)) {
            kind = Kind.UNAVAILABLE;
        } else if (status >= 500) {
            kind = Kind.UNAVAILABLE;
        } else {
            kind = Kind.BAD_RESPONSE;
        }
        return new DistributorException(Distributor.TME, kind, what + " failed with HTTP " + status + ": " + detail);
    }

    private static String describe(Response response, TmeResponses.ErrorResponse error) {
        if (error == null) {
            return response.bodySnippet();
        }
        StringBuilder sb = new StringBuilder();
        if (error.code() != null) {
            sb.append(error.code());
        }
        if (error.message() != null) {
            sb.append(sb.isEmpty() ? "" : " ").append(error.message());
        }
        List<String> details = new ArrayList<>();
        JsonNode data = error.errorData();
        if (data != null && data.isArray()) {
            for (JsonNode item : data) {
                String field = item.path("field").asString("");
                String message = item.path("message").asString("");
                if (!message.isEmpty()) {
                    details.add(field.isEmpty() ? message : field + ": " + message);
                }
            }
        }
        if (!details.isEmpty()) {
            sb.append(" (").append(String.join("; ", details)).append(')');
        }
        return sb.toString();
    }
}
