package ro.alacrity.kina.distributor.tme;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import ro.alacrity.kina.distributor.Deadline;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.distributor.DistributorException.Kind;
import ro.alacrity.kina.distributor.RateLimitRetry;
import ro.alacrity.kina.distributor.RateLimitRetry.RateLimitedResponse;
import ro.alacrity.kina.domain.Distributor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * OAuth2 client-credentials token cache for TME API v2: {@code POST {base}/auth/token} with
 * {@code Authorization: Basic base64(token:secret)} and {@code grant_type=client_credentials}. Tokens live 300 s; a
 * new one is requested when fewer than 30 s remain. The refresh token is ignored (client credentials are cheap).
 * Thread-safe; never logs token values. A rate-limited token request is retried through {@link RateLimitRetry} within
 * the caller's {@link Deadline}; waiting for the lock is bounded by the same deadline.
 */
@Slf4j
final class TmeTokenManager {

    static final Duration REFRESH_MARGIN = Duration.ofSeconds(30);
    /** Minimum wait for the token lock (covers an ordinary token request of another thread). */
    static final Duration LOCK_WAIT_FLOOR = Duration.ofSeconds(15);
    private static final long DEFAULT_EXPIRES_IN_SECONDS = 300;

    private final RestClient restClient;
    private final String tokenUri;
    private final String basicAuthorization;
    private final Clock clock;
    private final RateLimitRetry retry;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile CachedToken current;

    private record CachedToken(String value, Instant refreshAfter) {
    }

    TmeTokenManager(RestClient restClient, String baseUrl, String token, String secret, Clock clock) {
        this(restClient, baseUrl, token, secret, clock, new RateLimitRetry(Distributor.TME));
    }

    TmeTokenManager(RestClient restClient, String baseUrl, String token, String secret, Clock clock,
                    RateLimitRetry retry) {
        this.retry = retry;
        this.restClient = restClient;
        this.tokenUri = stripTrailingSlash(baseUrl) + "/auth/token";
        this.basicAuthorization = "Basic " + Base64.getEncoder()
                .encodeToString((token + ":" + secret).getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    /** A valid access token, requesting a new one when none is cached or it expires within 30 s; rate limits fail fast. */
    String accessToken() {
        return accessToken(Deadline.immediate());
    }

    /** A valid access token; a rate-limited token request is retried within {@code deadline}. */
    String accessToken(Deadline deadline) {
        CachedToken token = current;
        if (isFresh(token)) {
            return token.value();
        }
        lockWithin(deadline);
        try {
            token = current;
            if (!isFresh(token)) {
                token = requestToken(deadline);
                current = token;
            }
            return token.value();
        } finally {
            lock.unlock();
        }
    }

    /** Drops {@code rejected} from the cache (after the API refused it); a concurrently refreshed token is kept. */
    void invalidate(String rejected) {
        lock.lock();
        try {
            CachedToken token = current;
            if (token != null && token.value().equals(rejected)) {
                current = null;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Another thread may hold the lock while it waits on a rate limit: wait for it at most until the deadline (and at
     * least briefly, so calls without a deadline still share one token request).
     */
    private void lockWithin(Deadline deadline) {
        long waitNanos = Math.max(deadline.remainingNanos(), LOCK_WAIT_FLOOR.toNanos());
        try {
            if (!lock.tryLock(waitNanos, TimeUnit.NANOSECONDS)) {
                throw new DistributorException(Distributor.TME, Kind.RATE_LIMITED,
                        "TME token request is waiting on a rate limit beyond the request deadline");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DistributorException(Distributor.TME, Kind.TIMEOUT, "interrupted while waiting for the TME token");
        }
    }

    private boolean isFresh(CachedToken token) {
        return token != null && clock.instant().isBefore(token.refreshAfter());
    }

    private CachedToken requestToken(Deadline deadline) {
        String what = "TME token request";
        TmeHttp.Response response = retry.call(deadline, () -> {
            TmeHttp.Response r = TmeHttp.exchange(restClient.post()
                    .uri(tokenUri)
                    .header(HttpHeaders.AUTHORIZATION, basicAuthorization)
                    .accept(MediaType.APPLICATION_JSON)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("grant_type=client_credentials"), what);
            if (r.isRateLimited()) {
                throw new RateLimitedResponse(what + " returned HTTP " + r.status(), r.retryAfter());
            }
            return r;
        });
        if (!response.isSuccess()) {
            TmeResponses.ErrorResponse error = TmeHttp.error(response);
            DistributorException failure = TmeHttp.failure(response, error, what);
            if (TmeHttp.isAuthFailure(response, error) || response.status() == 400 || response.status() == 403) {
                // Rejected credentials are a configuration problem, not a bad request: report the distributor unavailable.
                failure = new DistributorException(Distributor.TME, Kind.UNAVAILABLE,
                        "TME rejected the API credentials (HTTP " + response.status() + ")");
            }
            log.warn("{}", failure.getMessage());
            throw failure;
        }
        TmeResponses.TokenResponse body = TmeHttp.decode(response, TmeResponses.TokenResponse.class, what);
        if (body.accessToken() == null || body.accessToken().isBlank()) {
            throw new DistributorException(Distributor.TME, Kind.BAD_RESPONSE, what + " returned no access_token");
        }
        long expiresIn = body.expiresIn() == null || body.expiresIn() <= 0 ? DEFAULT_EXPIRES_IN_SECONDS : body.expiresIn();
        Duration lifetime = Duration.ofSeconds(expiresIn);
        Duration margin = REFRESH_MARGIN.compareTo(lifetime.dividedBy(2)) < 0 ? REFRESH_MARGIN : lifetime.dividedBy(2);
        Instant refreshAfter = clock.instant().plus(lifetime).minus(margin);
        log.debug("Obtained a TME access token valid for {} s", expiresIn);
        return new CachedToken(body.accessToken(), refreshAfter);
    }

    static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
