package ro.alacrity.kina.search;

import ro.alacrity.kina.distributor.Deadline;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Time budget of one distributor fetch (DESIGN.md 3.2 and 3.6): {@code kina.search.distributor-timeout} of active work,
 * extended by the time the fetch spent waiting on rate limits, but never beyond the request deadline.
 */
final class DistributorBudget {

    private final Deadline deadline;
    private final long activeDeadlineNanos;

    /**
     * @param request       the request deadline; the fetch gets its own {@link Deadline#fork()} of it
     * @param activeTimeout {@code kina.search.distributor-timeout}
     */
    DistributorBudget(Deadline request, Duration activeTimeout) {
        this.deadline = request.fork();
        this.activeDeadlineNanos = deadline.nanoTime() + activeTimeout.toNanos();
    }

    /** The fork of the request deadline to hand to the distributor client (its rate-limit waits are recorded on it). */
    Deadline deadline() {
        return deadline;
    }

    /** {@code min(request deadline, start + distributor-timeout + rate-limit waits)} as a ticker value. */
    long deadlineNanos() {
        long extended = activeDeadlineNanos + deadline.rateLimitWaitedNanos();
        return extended - deadline.deadlineNanos() < 0 ? extended : deadline.deadlineNanos();
    }

    long remainingNanos() {
        return deadlineNanos() - deadline.nanoTime();
    }

    long rateLimitWaitedNanos() {
        return deadline.rateLimitWaitedNanos();
    }

    long rateLimitWaitedMillis() {
        return deadline.rateLimitWaitedMillis();
    }

    /**
     * Waits for {@code future} until the (moving) budget deadline plus {@code grace}. A rate-limit wait recorded while
     * waiting extends the wait; the request deadline never does.
     *
     * @throws TimeoutException when the budget ran out (the caller cancels the future)
     */
    <T> T await(Future<T> future, Duration grace) throws InterruptedException, ExecutionException, TimeoutException {
        while (true) {
            long until = deadlineNanos();
            long waitNanos = Math.max(0, until - deadline.nanoTime()) + grace.toNanos();
            try {
                return future.get(waitNanos, TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                if (deadlineNanos() - until <= 0) {
                    throw e;
                }
                // a rate-limit wait moved the deadline: keep waiting
            }
        }
    }
}
