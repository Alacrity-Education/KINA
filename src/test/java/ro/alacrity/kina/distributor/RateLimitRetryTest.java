package ro.alacrity.kina.distributor;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.distributor.DistributorException.Kind;
import ro.alacrity.kina.distributor.RateLimitRetry.RateLimitedResponse;
import ro.alacrity.kina.domain.Distributor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitRetryTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    private final FakeTime time = new FakeTime(NOW);
    private final RateLimitRetry retry = time.retry(Distributor.MOUSER, 0.5);

    /** Answers with the queued outcomes: a String is a success, a RateLimitedResponse is thrown. */
    private static Supplier<String> responses(Object... outcomes) {
        Deque<Object> queue = new ArrayDeque<>(List.of(outcomes));
        return () -> {
            Object next = queue.removeFirst();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return (String) next;
        };
    }

    private static RateLimitedResponse limited(String retryAfter) {
        return new RateLimitedResponse("/search/keyword returned HTTP 429", retryAfter);
    }

    // ---- Retry-After ------------------------------------------------------------------------------------------------

    @Test
    void retryAfterSecondsIsWaitedThenTheCallSucceeds() {
        Deadline deadline = time.deadline(Duration.ofMinutes(2));

        String result = retry.call(deadline, responses(limited("7"), "ok"));

        assertThat(result).isEqualTo("ok");
        assertThat(time.sleeps()).containsExactly(Duration.ofSeconds(7));
        assertThat(deadline.rateLimitWaitedMillis()).isEqualTo(7_000);
        assertThat(retry.cooldown().remainingNanos(time.getAsLong())).as("cleared after success").isLessThanOrEqualTo(0);
    }

    @Test
    void retryAfterHttpDateIsConvertedWithTheClock() {
        String date = "Mon, 05 Oct 2026 12:00:20 GMT"; // NOW + 20 s

        retry.call(time.deadline(Duration.ofMinutes(2)), responses(limited(date), "ok"));

        assertThat(time.sleeps()).containsExactly(Duration.ofSeconds(20));
    }

    @Test
    void parsesRetryAfterForms() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        assertThat(RateLimitRetry.parseRetryAfter("120", clock)).isEqualTo(Duration.ofSeconds(120));
        assertThat(RateLimitRetry.parseRetryAfter(" 0 ", clock)).isEqualTo(Duration.ZERO);
        assertThat(RateLimitRetry.parseRetryAfter("Mon, 05 Oct 2026 12:01:30 GMT", clock))
                .isEqualTo(Duration.ofSeconds(90));
        assertThat(RateLimitRetry.parseRetryAfter("Mon, 05 Oct 2026 11:00:00 GMT", clock)).isEqualTo(Duration.ZERO);
        assertThat(RateLimitRetry.parseRetryAfter("99999999999", clock)).isEqualTo(RateLimitRetry.MAX_RETRY_AFTER);
        assertThat(RateLimitRetry.parseRetryAfter("soon", clock)).isNull();
        assertThat(RateLimitRetry.parseRetryAfter("-5", clock)).isNull();
        assertThat(RateLimitRetry.parseRetryAfter(null, clock)).isNull();
        assertThat(RateLimitRetry.parseRetryAfter(" ", clock)).isNull();
    }

    @Test
    void retryAfterBelowOneSecondIsRaisedToTheMinimum() {
        retry.call(time.deadline(Duration.ofMinutes(2)), responses(limited("0"), "ok"));

        assertThat(time.sleeps()).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void retryAfterBeyondTheDeadlineFailsFastWithoutSleeping() {
        Deadline deadline = time.deadline(Duration.ofSeconds(30));

        assertThatThrownBy(() -> retry.call(deadline, responses(limited("45"))))
                .isInstanceOfSatisfying(DistributorException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED);
                    assertThat(e.distributor()).isEqualTo(Distributor.MOUSER);
                    assertThat(e.getMessage()).contains("rate limited by Mouser", "waited 0 s",
                            "next retry in 45 s would exceed the request deadline");
                    assertThat(e.rateLimitWaitedMillis()).isZero();
                });
        assertThat(time.sleeps()).isEmpty();
    }

    // ---- backoff ----------------------------------------------------------------------------------------------------

    @Test
    void backoffWithoutRetryAfterIs2_4_8_16_30Capped() {
        Object[] outcomes = new Object[8];
        for (int i = 0; i < 7; i++) {
            outcomes[i] = limited(null);
        }
        outcomes[7] = "ok";
        Deadline deadline = time.deadline(Duration.ofMinutes(5));

        assertThat(retry.call(deadline, responses(outcomes))).isEqualTo("ok");

        assertThat(time.sleeps()).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8),
                Duration.ofSeconds(16), Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(30));
        assertThat(deadline.rateLimitWaitedMillis()).isEqualTo(120_000);
    }

    @Test
    void backoffJitterStaysWithinTwentyPercent() {
        assertThat(RateLimitRetry.backoff(0, 0.0)).isEqualTo(Duration.ofMillis(1600));
        assertThat(RateLimitRetry.backoff(0, 1.0)).isEqualTo(Duration.ofMillis(2400));
        assertThat(RateLimitRetry.backoff(4, 0.0)).isEqualTo(Duration.ofSeconds(24));
        assertThat(RateLimitRetry.backoff(9, 1.0)).isEqualTo(Duration.ofSeconds(36));
        for (int step = 0; step < 8; step++) {
            Duration base = RateLimitRetry.BACKOFF.get(Math.min(step, 4));
            for (double r = 0; r < 1; r += 0.05) {
                Duration d = RateLimitRetry.backoff(step, r);
                assertThat(d).isBetween(Duration.ofNanos((long) (base.toNanos() * 0.8)),
                        Duration.ofNanos((long) (base.toNanos() * 1.2)));
            }
        }
    }

    @Test
    void jitterComesFromTheRandomSource() {
        RateLimitRetry jittery = time.retry(Distributor.TME, 0.0);

        jittery.call(time.deadline(Duration.ofMinutes(2)), responses(limited(null), limited(null), "ok"));

        assertThat(time.sleeps()).containsExactly(Duration.ofMillis(1600), Duration.ofMillis(3200));
    }

    // ---- deadline ---------------------------------------------------------------------------------------------------

    @Test
    void retriesWhileTheWaitFitsThenFailsWithTheWaitedTime() {
        // 2 + 4 + 8 + 16 = 30 s fit into 40 s; the next 30 s backoff does not
        Deadline deadline = time.deadline(Duration.ofSeconds(40));
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retry.call(deadline, () -> {
            attempts.incrementAndGet();
            throw limited(null);
        })).isInstanceOfSatisfying(DistributorException.class, e -> {
            assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED);
            assertThat(e.rateLimitWaitedMillis()).isEqualTo(30_000);
            assertThat(e.getMessage()).contains("waited 30 s, next retry in 30 s would exceed the request deadline");
        });
        assertThat(attempts).hasValue(5);
        assertThat(time.totalSlept()).isEqualTo(Duration.ofSeconds(30));
        assertThat(deadline.rateLimitWaitedMillis()).isEqualTo(30_000);
    }

    @Test
    void immediateDeadlineNeverWaits() {
        assertThatThrownBy(() -> retry.call(Deadline.immediate(), responses(limited("1"))))
                .isInstanceOfSatisfying(DistributorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED));
        assertThat(time.sleeps()).isEmpty();
    }

    @Test
    void otherExceptionsPropagateUnchanged() {
        DistributorException failure = new DistributorException(Distributor.MOUSER, Kind.UNAVAILABLE, "boom");

        assertThatThrownBy(() -> retry.call(time.deadline(Duration.ofMinutes(2)), () -> {
            throw failure;
        })).isSameAs(failure);
        assertThat(time.sleeps()).isEmpty();
    }

    @Test
    void interruptionStopsTheWait() {
        time.interruptNextSleep();
        try {
            assertThatThrownBy(() -> retry.call(time.deadline(Duration.ofMinutes(2)), responses(limited("5"), "ok")))
                    .isInstanceOfSatisfying(DistributorException.class, e -> {
                        assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED);
                        assertThat(e.getMessage()).contains("interrupted");
                    });
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // clear for the next test
        }
    }

    @Test
    void realSleeperStopsOnInterrupt() {
        RateLimitRetry real = new RateLimitRetry(Distributor.TME);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> real.call(Deadline.after(Duration.ofMinutes(2)), responses(limited("30"), "ok")))
                    .isInstanceOfSatisfying(DistributorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED));
        } finally {
            Thread.interrupted();
        }
    }

    // ---- shared cool-down -------------------------------------------------------------------------------------------

    @Test
    void cooldownMakesTheNextCallWaitBeforeItsRequest() {
        assertThatThrownBy(() -> retry.call(time.deadline(Duration.ofSeconds(5)), responses(limited("20"))))
                .isInstanceOf(DistributorException.class);

        // a second request with enough budget waits out the cool-down first, then calls once
        AtomicInteger calls = new AtomicInteger();
        Deadline second = time.deadline(Duration.ofMinutes(2));
        String result = retry.call(second, () -> {
            calls.incrementAndGet();
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(1);
        assertThat(time.sleeps()).containsExactly(Duration.ofSeconds(20));
        assertThat(second.rateLimitWaitedMillis()).isEqualTo(20_000);
    }

    @Test
    void cooldownBeyondTheDeadlineFailsFastWithoutARequest() {
        assertThatThrownBy(() -> retry.call(time.deadline(Duration.ofSeconds(5)), responses(limited("60"))))
                .isInstanceOf(DistributorException.class);
        time.advance(Duration.ofSeconds(10));

        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> retry.call(time.deadline(Duration.ofSeconds(30)), () -> {
            calls.incrementAndGet();
            return "ok";
        })).isInstanceOfSatisfying(DistributorException.class, e -> {
            assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED);
            assertThat(e.getMessage()).contains("cooling down for another 50 s");
        });
        assertThat(calls).as("no request while cooling down").hasValue(0);
        assertThat(time.sleeps()).isEmpty();
    }

    @Test
    void successClearsTheCooldownButNotOneRecordedMeanwhile() {
        DistributorCooldown cooldown = new DistributorCooldown(Distributor.TME);
        long now = time.getAsLong();
        cooldown.record(now, now + Duration.ofSeconds(10).toNanos(), "HTTP 429");
        long generation = cooldown.generation();
        cooldown.clearAfterSuccess(generation);
        assertThat(cooldown.remainingNanos(now)).isLessThanOrEqualTo(0);

        cooldown.record(now, now + Duration.ofSeconds(10).toNanos(), "HTTP 429");
        long before = cooldown.generation();
        cooldown.record(now, now + Duration.ofSeconds(5).toNanos(), "HTTP 429 again"); // concurrent call, shorter
        cooldown.clearAfterSuccess(before);
        assertThat(cooldown.remainingNanos(now)).as("kept, and never shortened").isEqualTo(Duration.ofSeconds(10).toNanos());
    }

    @Test
    void rateLimitStatuses() {
        assertThat(RateLimitRetry.isRateLimitStatus(429, null)).isTrue();
        assertThat(RateLimitRetry.isRateLimitStatus(503, "5")).isTrue();
        assertThat(RateLimitRetry.isRateLimitStatus(502, "5")).isTrue();
        assertThat(RateLimitRetry.isRateLimitStatus(504, "5")).isTrue();
        assertThat(RateLimitRetry.isRateLimitStatus(503, null)).isFalse();
        assertThat(RateLimitRetry.isRateLimitStatus(503, " ")).isFalse();
        assertThat(RateLimitRetry.isRateLimitStatus(500, "5")).isFalse();
        assertThat(RateLimitRetry.isRateLimitStatus(200, "5")).isFalse();
    }

    // ---- deadline accounting ----------------------------------------------------------------------------------------

    @Test
    void overlappingWaitsOfOneFetchAreCountedOnce() {
        Deadline deadline = time.deadline(Duration.ofMinutes(2));
        long now = time.getAsLong();
        deadline.recordWait(now, Duration.ofSeconds(4).toNanos());
        deadline.recordWait(now + Duration.ofSeconds(1).toNanos(), Duration.ofSeconds(2).toNanos()); // inside
        deadline.recordWait(now + Duration.ofSeconds(2).toNanos(), Duration.ofSeconds(4).toNanos()); // +2 s
        deadline.recordWait(now + Duration.ofSeconds(10).toNanos(), Duration.ofSeconds(1).toNanos()); // separate

        assertThat(deadline.rateLimitWaitedMillis()).isEqualTo(7_000);
        assertThat(deadline.fork().rateLimitWaitedMillis()).as("forks count their own waits").isZero();
        assertThat(deadline.fork().deadlineNanos()).isEqualTo(deadline.deadlineNanos());
    }
}
