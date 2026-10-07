package ro.alacrity.kina.oauth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.config.KinaProperties;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.function.LongSupplier;

/**
 * In-memory token bucket per client IP for {@code POST /oauth/register}: {@code kina.oauth.register-rate-limit-per-
 * minute} requests (default 30) per minute, refilled continuously; 0 or less disables the limit. The client IP is
 * {@code request.getRemoteAddr()}, which reflects {@code X-Forwarded-For} only when forwarded headers are trusted
 * ({@code server.forward-headers-strategy=framework}, KINA's default behind its reverse proxy). Idle buckets expire
 * after 10 minutes, bounding memory.
 */
@Component
public class RegistrationRateLimiter {

    @Autowired private KinaProperties properties;
    private LongSupplier nanoTime = System::nanoTime;
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .maximumSize(100_000)
            .build();

    private static final class Bucket {
        double tokens;
        long updatedNanos;

        Bucket(double tokens, long updatedNanos) {
            this.tokens = tokens;
            this.updatedNanos = updatedNanos;
        }
    }

    /**
     * Takes one request for {@code clientKey}. Empty when allowed; otherwise the number of seconds after which the
     * next request is allowed (for {@code Retry-After}).
     */
    public OptionalLong tryAcquire(String clientKey) {
        int perMinute = properties.oauth().registerRateLimitPerMinute();
        if (perMinute <= 0) {
            return OptionalLong.empty();
        }
        long now = nanoTime.getAsLong();
        double refillPerNano = perMinute / (double) Duration.ofMinutes(1).toNanos();
        Bucket bucket = buckets.get(clientKey == null ? "unknown" : clientKey, k -> new Bucket(perMinute, now));
        synchronized (bucket) {
            bucket.tokens = Math.min(perMinute, bucket.tokens + (now - bucket.updatedNanos) * refillPerNano);
            bucket.updatedNanos = now;
            if (bucket.tokens >= 1) {
                bucket.tokens -= 1;
                return OptionalLong.empty();
            }
            double missingNanos = (1 - bucket.tokens) / refillPerNano;
            return OptionalLong.of(Math.max(1, (long) Math.ceil(missingNanos / 1_000_000_000d)));
        }
    }
}
