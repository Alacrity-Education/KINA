package ro.alacrity.kina.distributor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ro.alacrity.kina.domain.Distributor;

/**
 * Shared rate-limit cool-down of one distributor (DESIGN.md section 3.6). When a call is rate limited, the time until
 * which the distributor should not be called is recorded here; every later call of that distributor first waits until
 * then (or fails fast when that does not fit its request deadline), so concurrent requests do not hammer the API.
 * A successful call clears it. In-memory and thread-safe; times are {@link System#nanoTime()}-like ticker values.
 */
public final class DistributorCooldown {

    private static final Logger log = LoggerFactory.getLogger(DistributorCooldown.class);

    /**
     * Rate limits less than this long after the previous cool-down ended belong to the same window (one WARN per
     * episode of back-to-back retries, not one per retry).
     */
    static final long WINDOW_GAP_NANOS = 60_000_000_000L;

    private final Distributor distributor;
    private boolean active;
    private long untilNanos;
    /** Increments on every {@link #record}; a success only clears the cool-down it did not race with. */
    private long generation;

    public DistributorCooldown(Distributor distributor) {
        this.distributor = distributor;
    }

    /**
     * Extends the cool-down to at least {@code untilNanos}. Logs one WARN per cool-down window: a window ends with a
     * successful call or 60 s after the cool-down ran out; rate limits within a window
     * (retries, concurrent calls) log at DEBUG.
     *
     * @param nowNanos  current ticker time
     * @param reason    short description such as "HTTP 429 with Retry-After 30 s"; never contains credentials
     */
    public void record(long nowNanos, long untilNanos, String reason) {
        boolean newWindow;
        long waitMillis;
        synchronized (this) {
            newWindow = !active || nowNanos - this.untilNanos > WINDOW_GAP_NANOS;
            if (!active || untilNanos - this.untilNanos > 0) {
                this.untilNanos = untilNanos;
            }
            active = true;
            generation++;
            waitMillis = Math.max(0, this.untilNanos - nowNanos) / 1_000_000;
        }
        if (newWindow) {
            log.warn("{} rate limited ({}); cooling down for {} s", RateLimitRetry.displayName(distributor), reason,
                    (waitMillis + 999) / 1000);
        } else {
            log.debug("{} rate limited again ({}); cool-down extended", RateLimitRetry.displayName(distributor), reason);
        }
    }

    /** Nanoseconds left in the cool-down at {@code nowNanos}; zero or negative when there is none. */
    public synchronized long remainingNanos(long nowNanos) {
        return active ? untilNanos(nowNanos) : 0;
    }

    private long untilNanos(long nowNanos) {
        return untilNanos - nowNanos;
    }

    /** The current generation, to pass to {@link #clearAfterSuccess(long)} once the call succeeded. */
    public synchronized long generation() {
        return generation;
    }

    /**
     * Clears the cool-down after a successful call, unless another call recorded a rate limit since
     * {@code generationAtStart} was read.
     */
    public synchronized void clearAfterSuccess(long generationAtStart) {
        if (active && generation == generationAtStart) {
            active = false;
        }
    }

    public Distributor distributor() {
        return distributor;
    }
}
