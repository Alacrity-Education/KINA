package ro.alacrity.kina.distributor;

import ro.alacrity.kina.domain.Distributor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Fake time for rate-limit tests: one source for the nanosecond ticker, the wall clock and a sleeper that advances
 * both instead of sleeping. Records every sleep.
 */
public final class FakeTime implements LongSupplier, RateLimitRetry.Sleeper {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final Instant start;
    private final List<Duration> sleeps = new CopyOnWriteArrayList<>();
    private volatile boolean interruptNextSleep;

    public FakeTime(Instant start) {
        this.start = start;
    }

    @Override
    public long getAsLong() {
        return nanos.get();
    }

    @Override
    public void sleep(Duration duration) throws InterruptedException {
        if (interruptNextSleep) {
            interruptNextSleep = false;
            throw new InterruptedException("test interrupt");
        }
        sleeps.add(duration);
        advance(duration);
    }

    public void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

    public void interruptNextSleep() {
        interruptNextSleep = true;
    }

    public List<Duration> sleeps() {
        return List.copyOf(sleeps);
    }

    public Duration totalSlept() {
        return sleeps.stream().reduce(Duration.ZERO, Duration::plus);
    }

    /** Wall clock that moves with the ticker. */
    public Clock clock() {
        long origin = nanos.get();
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return start.plusNanos(nanos.get() - origin);
            }
        };
    }

    public Deadline deadline(Duration budget) {
        return Deadline.after(budget, this);
    }

    /** A retry policy on this fake time with jitter fixed at {@code random} (0.5 = no jitter). */
    public RateLimitRetry retry(Distributor distributor, double random) {
        return new RateLimitRetry(distributor, clock(), this, () -> random);
    }
}
