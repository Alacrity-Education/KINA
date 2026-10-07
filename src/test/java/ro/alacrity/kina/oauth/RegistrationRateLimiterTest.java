package ro.alacrity.kina.oauth;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.TestWiring;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class RegistrationRateLimiterTest {

    @Test
    void allowsTheLimitPerMinuteThenAsksToRetry() {
        AtomicLong clock = new AtomicLong(0);
        RegistrationRateLimiter limiter = limiter(3, clock::get);
        assertThat(limiter.tryAcquire("1.2.3.4")).isEmpty();
        assertThat(limiter.tryAcquire("1.2.3.4")).isEmpty();
        assertThat(limiter.tryAcquire("1.2.3.4")).isEmpty();
        // One token comes back every 20 s.
        assertThat(limiter.tryAcquire("1.2.3.4").getAsLong()).isBetween(20L, 21L);

        clock.addAndGet(Duration.ofSeconds(15).toNanos());
        assertThat(limiter.tryAcquire("1.2.3.4").getAsLong()).isBetween(5L, 6L);
        clock.addAndGet(Duration.ofSeconds(6).toNanos());
        assertThat(limiter.tryAcquire("1.2.3.4")).isEmpty();
        assertThat(limiter.tryAcquire("1.2.3.4")).isPresent();
    }

    @Test
    void bucketsArePerClientAndRefillToTheLimitOnly() {
        AtomicLong clock = new AtomicLong(0);
        RegistrationRateLimiter limiter = limiter(2, clock::get);
        limiter.tryAcquire("a");
        limiter.tryAcquire("a");
        assertThat(limiter.tryAcquire("a")).isPresent();
        assertThat(limiter.tryAcquire("b")).isEmpty();

        clock.addAndGet(Duration.ofHours(1).toNanos());
        assertThat(limiter.tryAcquire("a")).isEmpty();
        assertThat(limiter.tryAcquire("a")).isEmpty();
        assertThat(limiter.tryAcquire("a")).as("no burst beyond the limit after idling").isPresent();
    }

    @Test
    void zeroDisablesTheLimit() {
        RegistrationRateLimiter limiter = limiter(0, () -> 0L);
        for (int i = 0; i < 1000; i++) {
            assertThat(limiter.tryAcquire("x")).isEmpty();
        }
    }

    static RegistrationRateLimiter limiter(int perMinute, LongSupplier nanoTime) {
        return TestWiring.wire(new RegistrationRateLimiter(),
                "properties", TestWiring.properties("kina.oauth.register-rate-limit-per-minute", "" + perMinute),
                "nanoTime", nanoTime);
    }
}
