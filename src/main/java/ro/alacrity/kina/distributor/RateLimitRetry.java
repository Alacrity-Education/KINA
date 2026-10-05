package ro.alacrity.kina.distributor;

import ro.alacrity.kina.distributor.DistributorException.Kind;
import ro.alacrity.kina.domain.Distributor;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Rate-limit retry policy around every distributor HTTP call (DESIGN.md section 3.6). One instance per distributor
 * client, holding that distributor's shared {@link DistributorCooldown}.
 *
 * <p>An attempt signals "rate limited" by throwing {@link RateLimitedResponse}: HTTP 429; HTTP 502/503/504 only with a
 * {@code Retry-After} header; Mouser's in-body {@code TooManyRequests} error. The call is then retried after
 * {@code Retry-After} (delta-seconds or HTTP-date, at least {@value #MIN_WAIT_SECONDS} s) or, without the header,
 * after an exponential backoff of 2, 4, 8, 16, 30, 30... s with +-20 % jitter, as long as the wait ends before the
 * request {@link Deadline}; otherwise it fails with {@link Kind#RATE_LIMITED}. Before every attempt a running
 * cool-down is waited out (or, when it would outlast the deadline, the call fails fast without an HTTP request).
 * Sleeps happen on the calling (virtual) thread; an interrupt stops the call.
 */
public final class RateLimitRetry {

    static final long MIN_WAIT_SECONDS = 1;
    /** Shortest wait, also the floor for a {@code Retry-After} of 0 or in the past. */
    public static final Duration MIN_WAIT = Duration.ofSeconds(MIN_WAIT_SECONDS);
    /** Backoff without {@code Retry-After}; the last value repeats. */
    public static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(2), Duration.ofSeconds(4),
            Duration.ofSeconds(8), Duration.ofSeconds(16), Duration.ofSeconds(30));
    /** Relative jitter applied to the backoff: +-20 %. */
    public static final double JITTER = 0.2;
    /** Upper bound for a parsed {@code Retry-After} (anything longer can never fit a request deadline anyway). */
    static final Duration MAX_RETRY_AFTER = Duration.ofHours(1);

    /** Sleeps on the calling thread; tests substitute a fake. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /**
     * Thrown by an attempt whose response is a rate limit. Never leaves {@link #call}. Carries no stack trace and must
     * not carry credentials in its message.
     */
    public static final class RateLimitedResponse extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String retryAfter;

        /**
         * @param detail     what happened, e.g. "/search/keyword returned HTTP 429"
         * @param retryAfter raw {@code Retry-After} header value, or null
         */
        public RateLimitedResponse(String detail, String retryAfter) {
            super(detail, null, false, false);
            this.retryAfter = retryAfter;
        }

        public String retryAfter() {
            return retryAfter;
        }
    }

    private final Distributor distributor;
    private final Clock clock;
    private final Sleeper sleeper;
    private final DoubleSupplier random;
    private final DistributorCooldown cooldown;

    public RateLimitRetry(Distributor distributor) {
        this(distributor, Clock.systemUTC(), d -> Thread.sleep(d), () -> ThreadLocalRandom.current().nextDouble());
    }

    /**
     * @param clock   wall clock, only used to turn an HTTP-date {@code Retry-After} into a delay
     * @param sleeper sleeps on the calling thread
     * @param random  uniform values in [0, 1) for the jitter
     */
    public RateLimitRetry(Distributor distributor, Clock clock, Sleeper sleeper, DoubleSupplier random) {
        this.distributor = distributor;
        this.clock = clock;
        this.sleeper = sleeper;
        this.random = random;
        this.cooldown = new DistributorCooldown(distributor);
    }

    public Distributor distributor() {
        return distributor;
    }

    public DistributorCooldown cooldown() {
        return cooldown;
    }

    /**
     * True when an HTTP status is a rate-limit signal: 429 always, 502/503/504 only with a {@code Retry-After} header.
     */
    public static boolean isRateLimitStatus(int status, String retryAfter) {
        if (status == 429) {
            return true;
        }
        return (status == 502 || status == 503 || status == 504) && retryAfter != null && !retryAfter.isBlank();
    }

    /**
     * Runs {@code attempt}, retrying it while it throws {@link RateLimitedResponse} and the wait fits {@code deadline}.
     * Every other exception propagates unchanged.
     *
     * @throws DistributorException {@code RATE_LIMITED} when the next wait would exceed the deadline or the thread was
     *                              interrupted while waiting; carries the waited time
     */
    public <T> T call(Deadline deadline, Supplier<T> attempt) {
        Deadline budget = deadline == null ? Deadline.immediate() : deadline;
        long waitedNanos = 0;
        int backoffStep = 0;
        while (true) {
            waitedNanos += awaitCooldown(budget, waitedNanos);
            long generation = cooldown.generation();
            RateLimitedResponse signal;
            try {
                T result = attempt.get();
                cooldown.clearAfterSuccess(generation);
                return result;
            } catch (RateLimitedResponse e) {
                signal = e;
            }
            long now = budget.nanoTime();
            Duration retryAfter = parseRetryAfter(signal.retryAfter(), clock);
            Duration wait = retryAfter != null ? max(retryAfter, MIN_WAIT) : backoff(backoffStep++, random.getAsDouble());
            long waitNanos = wait.toNanos();
            String reason = signal.getMessage() + (retryAfter != null ? ", Retry-After " + seconds(waitNanos) + " s" : "");
            cooldown.record(now, now + waitNanos, reason);
            if (!budget.fits(waitNanos)) {
                throw new DistributorException(distributor, Kind.RATE_LIMITED, "rate limited by "
                        + displayName(distributor) + " (" + signal.getMessage() + "); waited " + seconds(waitedNanos)
                        + " s, next retry in " + seconds(waitNanos) + " s would exceed the request deadline",
                        null, waitedNanos / 1_000_000);
            }
            sleep(budget, now, waitNanos, waitedNanos);
            waitedNanos += waitNanos;
        }
    }

    /** Waits out a running cool-down; returns the nanoseconds waited. */
    private long awaitCooldown(Deadline deadline, long waitedSoFar) {
        long now = deadline.nanoTime();
        long remaining = cooldown.remainingNanos(now);
        if (remaining <= 0) {
            return 0;
        }
        if (!deadline.fits(remaining)) {
            throw new DistributorException(distributor, Kind.RATE_LIMITED, "rate limited by "
                    + displayName(distributor) + "; cooling down for another " + seconds(remaining)
                    + " s, which would exceed the request deadline (waited " + seconds(waitedSoFar) + " s)",
                    null, waitedSoFar / 1_000_000);
        }
        sleep(deadline, now, remaining, waitedSoFar);
        return remaining;
    }

    private void sleep(Deadline deadline, long now, long waitNanos, long waitedSoFar) {
        deadline.recordWait(now, waitNanos);
        try {
            sleeper.sleep(Duration.ofNanos(waitNanos));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DistributorException(distributor, Kind.RATE_LIMITED, "rate limited by "
                    + displayName(distributor) + "; interrupted while waiting", e, waitedSoFar / 1_000_000);
        }
    }

    /**
     * Backoff for retry {@code step} (0-based) without {@code Retry-After}: 2, 4, 8, 16, 30 s (cap), scaled by
     * {@code 1 + JITTER * (2 * random - 1)}, never below {@link #MIN_WAIT}.
     */
    static Duration backoff(int step, double random) {
        Duration base = BACKOFF.get(Math.min(Math.max(0, step), BACKOFF.size() - 1));
        double factor = 1 + JITTER * (2 * Math.clamp(random, 0.0, 1.0) - 1);
        return max(Duration.ofNanos(Math.round(base.toNanos() * factor)), MIN_WAIT);
    }

    /**
     * Parses a {@code Retry-After} value: delta-seconds ({@code "120"}) or an HTTP-date
     * ({@code "Wed, 21 Oct 2026 07:28:00 GMT"}). A date in the past yields zero. Null when absent or unreadable.
     */
    public static Duration parseRetryAfter(String value, Clock clock) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.strip();
        if (text.chars().allMatch(Character::isDigit)) {
            if (text.length() > 6) {
                return MAX_RETRY_AFTER;
            }
            Duration seconds = Duration.ofSeconds(Long.parseLong(text));
            return seconds.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : seconds;
        }
        try {
            Instant at = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration delay = Duration.between(clock.instant(), at);
            if (delay.isNegative()) {
                return Duration.ZERO;
            }
            return delay.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : delay;
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** "Mouser", "TME", "LCSC" for log and error messages. */
    public static String displayName(Distributor distributor) {
        return switch (distributor) {
            case MOUSER -> "Mouser";
            case TME -> "TME";
            case LCSC -> "LCSC";
        };
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    /** Whole seconds, rounded half up. */
    static String seconds(long nanos) {
        return String.format(Locale.ROOT, "%d", Math.round(Math.max(0, nanos) / 1e9));
    }
}
