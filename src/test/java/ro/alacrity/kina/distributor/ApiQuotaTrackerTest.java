package ro.alacrity.kina.distributor;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.TestWiring;
import ro.alacrity.kina.distributor.ApiQuotaTracker.Window;
import ro.alacrity.kina.domain.Distributor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The in-memory API quota (DESIGN.md 3.7): sliding windows, bounded memory, observed throttling. */
class ApiQuotaTrackerTest {

    private static final Instant START = Instant.parse("2026-10-09T10:00:00Z");

    /** A clock the test moves. */
    private static final class MutableClock extends Clock {
        private volatile Instant now = START;

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
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    private final MutableClock clock = new MutableClock();
    private final ApiQuotaTracker tracker = tracker();

    private ApiQuotaTracker tracker(String... properties) {
        return TestWiring.wire(new ApiQuotaTracker(), "properties", TestWiring.properties(properties),
                "clock", clock);
    }

    @Test
    void limitsAreTheDocumentedDefaults() {
        assertThat(tracker.limit(Distributor.MOUSER, Window.MINUTE)).isEqualTo(30);
        assertThat(tracker.limit(Distributor.MOUSER, Window.DAY)).isEqualTo(1000);
        assertThat(tracker.limit(Distributor.TME, Window.MINUTE)).isEqualTo(30);
        assertThat(tracker.limit(Distributor.TME, Window.DAY)).isEqualTo(2000);
        assertThat(tracker.limit(Distributor.LCSC, Window.DAY)).isZero();
    }

    @Test
    void limitsComeFromTheConfiguration() {
        ApiQuotaTracker configured = tracker("kina.distributors.mouser.quota.per-minute", "5",
                "kina.distributors.mouser.quota.per-day", "50", "kina.distributors.tme.quota.per-day", "70");
        assertThat(configured.limit(Distributor.MOUSER, Window.MINUTE)).isEqualTo(5);
        assertThat(configured.limit(Distributor.MOUSER, Window.DAY)).isEqualTo(50);
        assertThat(configured.limit(Distributor.TME, Window.MINUTE)).isEqualTo(30);
        assertThat(configured.limit(Distributor.TME, Window.DAY)).isEqualTo(70);
    }

    @Test
    void theWindowsSlide() {
        for (int i = 0; i < 15; i++) {
            tracker.record(Distributor.MOUSER);
        }
        assertThat(tracker.used(Distributor.MOUSER, Window.MINUTE)).isEqualTo(15);
        assertThat(tracker.usage(Distributor.MOUSER, Window.MINUTE).text()).isEqualTo("15/30");

        clock.advance(Duration.ofSeconds(59));
        tracker.record(Distributor.MOUSER);
        assertThat(tracker.used(Distributor.MOUSER, Window.MINUTE)).isEqualTo(16);

        clock.advance(Duration.ofSeconds(1));   // the first 15 are exactly 60 s old: outside the window
        assertThat(tracker.used(Distributor.MOUSER, Window.MINUTE)).isEqualTo(1);
        assertThat(tracker.used(Distributor.MOUSER, Window.DAY)).isEqualTo(16);
        assertThat(tracker.remaining(Distributor.MOUSER, Window.MINUTE)).isEqualTo(29);

        clock.advance(Duration.ofHours(24).minusSeconds(60));   // the 15 are 24 h old, the last one is not
        assertThat(tracker.used(Distributor.MOUSER, Window.DAY)).isEqualTo(1);
        clock.advance(Duration.ofSeconds(60));
        assertThat(tracker.used(Distributor.MOUSER, Window.DAY)).isZero();
        assertThat(tracker.held(Distributor.MOUSER)).isZero();
    }

    @Test
    void distributorsAreCountedApartAndLcscIsIgnored() {
        tracker.record(Distributor.MOUSER);
        tracker.record(Distributor.LCSC);
        assertThat(tracker.used(Distributor.MOUSER, Window.DAY)).isEqualTo(1);
        assertThat(tracker.used(Distributor.TME, Window.DAY)).isZero();
        assertThat(tracker.used(Distributor.LCSC, Window.DAY)).isZero();
        assertThat(tracker.snapshot(Distributor.LCSC)).isNull();
        assertThat(tracker.snapshot()).containsOnlyKeys(Distributor.MOUSER, Distributor.TME);
    }

    @Test
    void memoryIsBoundedByTheDailyLimitPlusAMargin() {
        ApiQuotaTracker small = tracker("kina.distributors.mouser.quota.per-day", "50");
        for (int i = 0; i < 10_000; i++) {
            small.record(Distributor.MOUSER);
        }
        assertThat(small.held(Distributor.MOUSER)).isEqualTo(50 + ApiQuotaTracker.MARGIN_MIN);
        // an overrun stays visible in the used count
        assertThat(small.used(Distributor.MOUSER, Window.DAY)).isEqualTo(150);
        assertThat(small.remaining(Distributor.MOUSER, Window.DAY)).isZero();
        assertThat(small.used(Distributor.MOUSER, Window.MINUTE)).isEqualTo(150);
    }

    @Test
    void throttledUntilEndsWhenTheWaitIsOver() {
        assertThat(tracker.throttledUntil(Distributor.MOUSER)).isNull();
        tracker.throttle(Distributor.MOUSER, Duration.ofSeconds(30));
        assertThat(tracker.throttledUntil(Distributor.MOUSER)).isEqualTo(START.plusSeconds(30));
        assertThat(tracker.snapshot(Distributor.MOUSER).throttledUntil()).isEqualTo(START.plusSeconds(30));
        assertThat(tracker.throttledUntil(Distributor.TME)).isNull();

        tracker.throttle(Distributor.MOUSER, Duration.ofSeconds(5));   // a shorter wait never shortens it
        assertThat(tracker.throttledUntil(Distributor.MOUSER)).isEqualTo(START.plusSeconds(30));

        clock.advance(Duration.ofSeconds(30));
        assertThat(tracker.throttledUntil(Distributor.MOUSER)).isNull();
        tracker.throttle(Distributor.LCSC, Duration.ofSeconds(30));   // ignored
        assertThat(tracker.throttledUntil(Distributor.LCSC)).isNull();
    }

    @Test
    void concurrentRecordsAreAllCounted() throws Exception {
        int threads = 8;
        int each = 100;
        ApiQuotaTracker big = tracker("kina.distributors.mouser.quota.per-day", "100000");
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            workers.add(Thread.ofVirtual().unstarted(() -> {
                try {
                    start.await();
                    for (int i = 0; i < each; i++) {
                        big.record(Distributor.MOUSER);
                        big.used(Distributor.MOUSER, Window.MINUTE);
                        big.snapshot();
                    }
                } catch (Throwable e) {
                    failure.set(e);
                }
            }));
        }
        workers.forEach(Thread::start);
        start.countDown();
        for (Thread w : workers) {
            w.join();
        }
        assertThat(failure.get()).isNull();
        assertThat(big.used(Distributor.MOUSER, Window.DAY)).isEqualTo(threads * each);
    }

    @Test
    void retryPolicyCountsEveryAttempt() {
        FakeTime time = new FakeTime(START);
        ApiQuotaTracker counted = TestWiring.wire(new ApiQuotaTracker(), "properties",
                TestWiring.properties(), "clock", time.clock());
        RateLimitRetry retry = time.retry(Distributor.TME, 0.5).quota(counted);
        int[] attempts = {0};

        String result = retry.call(time.deadline(Duration.ofMinutes(1)), () -> {
            if (++attempts[0] < 3) {
                throw new RateLimitRetry.RateLimitedResponse("HTTP 429", "2");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(counted.used(Distributor.TME, Window.MINUTE)).isEqualTo(3);
        assertThat(counted.throttledUntil(Distributor.TME)).isNull();   // the waits are over
    }
}
