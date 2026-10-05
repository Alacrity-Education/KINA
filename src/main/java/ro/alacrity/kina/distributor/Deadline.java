package ro.alacrity.kina.distributor;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * The hard deadline of one incoming request ({@code kina.search.max-request-duration}, DESIGN.md section 3.6) plus the
 * time one distributor fetch spent waiting on rate limits.
 *
 * <p>The orchestrator creates one deadline per request ({@link #after(Duration)}) and hands every distributor fetch its
 * own {@link #fork()}: forks share the hard deadline but count their rate-limit waits separately. {@link RateLimitRetry}
 * records a wait with {@link #recordWait(long, long)} <em>before</em> it sleeps, so the orchestrator can extend the
 * distributor's active-work budget ({@code kina.search.distributor-timeout}) by the waited time while the sleep is in
 * progress. Overlapping waits of parallel calls of the same fetch (TME data/parameters/files) are counted once.
 *
 * <p>Times are {@link System#nanoTime()} values (or the injected ticker's). Thread-safe.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class Deadline {

    private final long deadlineNanos;
    private final LongSupplier ticker;
    private final Object lock = new Object();
    private long waitedNanos;
    private long coveredUntilNanos;
    private boolean anyWait;


    /** A deadline {@code budget} from now (negative budgets count as zero). */
    public static Deadline after(Duration budget) {
        return after(budget, System::nanoTime);
    }

    /** A deadline {@code budget} from now on {@code ticker} (a {@link System#nanoTime()}-like source; tests fake it). */
    public static Deadline after(Duration budget, LongSupplier ticker) {
        long nanos = budget == null || budget.isNegative() ? 0 : saturatedNanos(budget);
        return new Deadline(ticker.getAsLong() + nanos, ticker);
    }

    /**
     * A deadline that has already passed: rate limits are not waited for, they fail fast with {@code RATE_LIMITED}.
     * Used by the {@link DistributorClient} methods without a deadline parameter.
     */
    public static Deadline immediate() {
        return after(Duration.ZERO);
    }

    /** Same hard deadline and ticker, with its own (empty) rate-limit wait accounting. */
    public Deadline fork() {
        return new Deadline(deadlineNanos, ticker);
    }

    /** Current time on this deadline's ticker. */
    public long nanoTime() {
        return ticker.getAsLong();
    }

    /** The hard deadline as a ticker value. */
    public long deadlineNanos() {
        return deadlineNanos;
    }

    /** Nanoseconds left until the hard deadline; zero or negative once it has passed. */
    public long remainingNanos() {
        return deadlineNanos - nanoTime();
    }

    /** Time left until the hard deadline, never negative. */
    public Duration remaining() {
        return Duration.ofNanos(Math.max(0, remainingNanos()));
    }

    public boolean isExpired() {
        return remainingNanos() <= 0;
    }

    /** True when a wait of {@code waitNanos} starting now ends no later than the hard deadline. */
    public boolean fits(long waitNanos) {
        return waitNanos <= remainingNanos();
    }

    /**
     * Records a rate-limit wait of {@code waitNanos} starting at {@code startNanos}. Waits overlapping an already
     * recorded one only add the part that extends beyond it.
     */
    public void recordWait(long startNanos, long waitNanos) {
        if (waitNanos <= 0) {
            return;
        }
        long end = startNanos + waitNanos;
        synchronized (lock) {
            if (!anyWait) {
                anyWait = true;
                coveredUntilNanos = end;
                waitedNanos += waitNanos;
                return;
            }
            if (end - coveredUntilNanos <= 0) {
                return; // fully covered by an earlier wait
            }
            long from = startNanos - coveredUntilNanos > 0 ? startNanos : coveredUntilNanos;
            waitedNanos += end - from;
            coveredUntilNanos = end;
        }
    }

    /** Rate-limit time recorded on this deadline (including a wait that is still in progress). */
    public long rateLimitWaitedNanos() {
        synchronized (lock) {
            return waitedNanos;
        }
    }

    public long rateLimitWaitedMillis() {
        return rateLimitWaitedNanos() / 1_000_000;
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE / 4;
        }
    }

    @Override
    public String toString() {
        return "Deadline[remaining=" + remaining() + ", rateLimitWaited=" + Duration.ofNanos(rateLimitWaitedNanos()) + "]";
    }
}
