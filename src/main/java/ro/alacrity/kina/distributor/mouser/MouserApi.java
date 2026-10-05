package ro.alacrity.kina.distributor.mouser;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import ro.alacrity.kina.distributor.Deadline;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.distributor.DistributorException.Kind;
import ro.alacrity.kina.distributor.RateLimitRetry;
import ro.alacrity.kina.distributor.RateLimitRetry.RateLimitedResponse;
import ro.alacrity.kina.domain.Distributor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Thin wrapper around the two Mouser Search API v1 endpoints used by KINA. The API key travels in the query string
 * only and is never logged: every URL or message that might contain it goes through {@link #mask(String)}.
 *
 * <p>Rate limits (HTTP 429, 502/503/504 with {@code Retry-After}, or a {@code TooManyRequests} error in the body) are
 * retried by {@link RateLimitRetry} within the caller's {@link Deadline} and become {@code RATE_LIMITED} when it runs
 * out. Every failure surfaces as a {@link DistributorException}: other entries in {@code Errors}, other 4xx or an unreadable body -> {@code BAD_RESPONSE};
 * connect/read timeouts -> {@code TIMEOUT}; 5xx and I/O errors -> {@code UNAVAILABLE}.
 */
public class MouserApi {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(MouserApi.class);
    private static final Pattern API_KEY_PARAM = Pattern.compile("(?i)(apiKey=)[^&\\s\"']*");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_ERROR_BODY = 300;

    private final RestClient restClient;
    private final String apiKey;
    private final RateLimitRetry retry;

    /**
     * Uses the builder as is (tests bind a {@code MockRestServiceServer} to it); production code goes through
     * {@link #create(RestClient.Builder, String, String)} which installs the timeouts.
     */
    MouserApi(RestClient.Builder builder, String baseUrl, String apiKey) {
        this(builder, baseUrl, apiKey, new RateLimitRetry(Distributor.MOUSER));
    }

    /** As {@link #MouserApi(RestClient.Builder, String, String)} with an explicit retry policy (tests fake its sleeper). */
    MouserApi(RestClient.Builder builder, String baseUrl, String apiKey, RateLimitRetry retry) {
        this.restClient = builder.clone().baseUrl(stripTrailingSlash(baseUrl)).build();
        this.apiKey = apiKey;
        this.retry = retry;
    }

    /** JDK {@code HttpClient} with a 5 s connect timeout and a 10 s read timeout. */
    public static MouserApi create(RestClient.Builder builder, String baseUrl, String apiKey) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return new MouserApi(builder.clone().requestFactory(requestFactory), baseUrl, apiKey);
    }

    /** {@code POST /search/keyword}; rate limits fail fast. */
    public MouserSearchResponse searchByKeyword(String keyword, int records, int startingRecord) {
        return searchByKeyword(keyword, records, startingRecord, Deadline.immediate());
    }

    /** {@code POST /search/keyword}; rate limits are waited for within {@code deadline}. */
    public MouserSearchResponse searchByKeyword(String keyword, int records, int startingRecord, Deadline deadline) {
        var body = new KeywordBody(new KeywordRequest(keyword, records, startingRecord, "InStock", "false"));
        return post("/search/keyword", body, deadline);
    }

    /** {@code POST /search/partnumber} with {@code partSearchOptions = "Exact"}; rate limits fail fast. */
    public MouserSearchResponse searchByPartNumber(String partNumber) {
        return searchByPartNumber(partNumber, Deadline.immediate());
    }

    /**
     * {@code POST /search/partnumber} with {@code partSearchOptions = "Exact"} (verified against the live API); rate
     * limits are waited for within {@code deadline}.
     */
    public MouserSearchResponse searchByPartNumber(String partNumber, Deadline deadline) {
        var body = new PartNumberBody(new PartNumberRequest(partNumber, "Exact"));
        return post("/search/partnumber", body, deadline);
    }

    private MouserSearchResponse post(String path, Object body, Deadline deadline) {
        String json;
        try {
            json = JSON.writeValueAsString(body);
        } catch (RuntimeException e) {
            throw translate(path, e);
        }
        return retry.call(deadline, () -> postOnce(path, json));
    }

    private MouserSearchResponse postOnce(String path, String json) {
        String uriTemplate = path + "?apiKey={apiKey}";
        try {
            return restClient.post()
                    .uri(uriTemplate, apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(json)
                    .exchange((request, clientResponse) -> {
                        HttpStatusCode status = clientResponse.getStatusCode();
                        String retryAfter = clientResponse.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
                        byte[] bytes = clientResponse.getBody().readAllBytes();
                        return readResponse(path, status, retryAfter, new String(bytes, StandardCharsets.UTF_8));
                    });
        } catch (DistributorException | RateLimitedResponse e) {
            throw e;
        } catch (RuntimeException e) {
            throw translate(path, e);
        }
    }

    private static MouserSearchResponse readResponse(String path, HttpStatusCode status, String retryAfter,
                                                     String text) {
        if (RateLimitRetry.isRateLimitStatus(status.value(), retryAfter)) {
            throw new RateLimitedResponse(path + " returned HTTP " + status.value(), retryAfter);
        }
        if (status.is5xxServerError()) {
            throw error(Kind.UNAVAILABLE, path + " returned HTTP " + status.value());
        }
        MouserSearchResponse response;
        try {
            response = text.isBlank() ? null : JSON.readValue(text, MouserSearchResponse.class);
        } catch (JacksonException e) {
            response = null;
        }
        if (response != null && !response.errors().isEmpty()) {
            checkErrors(path, response, retryAfter);
        }
        if (!status.is2xxSuccessful()) {
            throw error(Kind.BAD_RESPONSE, path + " returned HTTP " + status.value() + ": " + abbreviate(mask(text)));
        }
        if (response == null) {
            throw error(Kind.BAD_RESPONSE, path + " returned an unreadable body: " + abbreviate(mask(text)));
        }
        return response;
    }

    private static void checkErrors(String path, MouserSearchResponse response, String retryAfter) {
        if (response.errors().isEmpty()) {
            return;
        }
        boolean rateLimited = response.errors().stream()
                .anyMatch(e -> e != null && "TooManyRequests".equalsIgnoreCase(e.code()));
        StringBuilder message = new StringBuilder(path).append(" returned errors:");
        for (MouserError e : response.errors()) {
            if (e != null) {
                message.append(" [").append(e.code()).append("] ").append(e.message());
                if (e.propertyName() != null) {
                    message.append(" (").append(e.propertyName()).append(')');
                }
            }
        }
        if (rateLimited) {
            throw new RateLimitedResponse(path + " returned the TooManyRequests error", retryAfter);
        }
        throw error(Kind.BAD_RESPONSE, mask(message.toString()));
    }

    private static DistributorException translate(String path, RuntimeException e) {
        // The original exception is deliberately not attached as cause: Spring's messages contain the full URL.
        String detail = e.getClass().getSimpleName() + ": " + mask(String.valueOf(e.getMessage()));
        if (hasTimeoutCause(e)) {
            log.debug("Mouser {} timed out: {}", path, detail);
            return error(Kind.TIMEOUT, path + " timed out (" + detail + ")");
        }
        if (e instanceof JacksonException) {
            return error(Kind.BAD_RESPONSE, path + " request could not be serialised (" + detail + ")");
        }
        log.debug("Mouser {} failed: {}", path, detail);
        return error(Kind.UNAVAILABLE, path + " failed (" + detail + ")");
    }

    private static boolean hasTimeoutCause(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                return true;
            }
            if (t instanceof IOException && t.getMessage() != null && t.getMessage().toLowerCase(Locale.ROOT).contains("timed out")) {
                return true;
            }
        }
        return false;
    }

    private static DistributorException error(Kind kind, String message) {
        return new DistributorException(Distributor.MOUSER, kind, message);
    }

    /** Replaces the value of any {@code apiKey=} query parameter with {@code ***}. */
    static String mask(String text) {
        return text == null ? null : API_KEY_PARAM.matcher(text).replaceAll("$1***");
    }

    private static String abbreviate(String text) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= MAX_ERROR_BODY ? oneLine : oneLine.substring(0, MAX_ERROR_BODY) + "...";
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    record KeywordBody(@JsonProperty("SearchByKeywordRequest") KeywordRequest request) {
    }

    record KeywordRequest(
            @JsonProperty("keyword") String keyword,
            @JsonProperty("records") int records,
            @JsonProperty("startingRecord") int startingRecord,
            @JsonProperty("searchOptions") String searchOptions,
            @JsonProperty("searchWithYourSignUpLanguage") String searchWithYourSignUpLanguage) {
    }

    record PartNumberBody(@JsonProperty("SearchByPartRequest") PartNumberRequest request) {
    }

    record PartNumberRequest(
            @JsonProperty("mouserPartNumber") String mouserPartNumber,
            @JsonProperty("partSearchOptions") String partSearchOptions) {
    }
}
