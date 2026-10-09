package ro.alacrity.kina.distributor;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.Distributor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory count of the HTTP requests KINA sends to the Mouser and TME APIs (DESIGN.md 3.7, 9.1, 9.2), against the
 * configured quotas ({@code kina.distributors.<name>.quota}). Two sliding windows per distributor, 60 seconds and 24
 * hours: the real limits reset at times KINA does not know, and a sliding window never reports less than a fixed
 * window would. Nothing is persisted: a restart starts at 0, so after a restart the numbers can be lower than the
 * distributor's own count.
 *
 * <p>Every request counts once ({@link RateLimitRetry} calls {@link #record} for each attempt, retries and token
 * requests included, before the response is read, so a timeout counts too). LCSC has no quota (local database).
 * Memory is bounded: each distributor keeps at most the daily limit plus {@link #MARGIN_MIN} (or 10 %) timestamps.
 * Throttling observed from the distributor (HTTP 429, {@code TooManyRequests}) is kept as {@code throttledUntil}.
 */
@Component
public class ApiQuotaTracker {

    /** The two windows. */
    public enum Window {
        MINUTE("minute", Duration.ofSeconds(60)),
        DAY("day", Duration.ofHours(24));

        private final String label;
        private final Duration length;

        Window(String label, Duration length) {
            this.label = label;
            this.length = length;
        }

        /** The label used in JSON and as the {@code window} tag. */
        public String label() {
            return label;
        }

        public Duration length() {
            return length;
        }
    }

    /** Distributors with a quota to track. */
    public static final List<Distributor> TRACKED = List.of(Distributor.MOUSER, Distributor.TME);

    /** Entries kept beyond the daily limit, so an overrun stays visible. */
    static final int MARGIN_MIN = 100;

    /** Requests used in a window and its limit. */
    public record Usage(@JsonProperty("used") int used, @JsonProperty("limit") int limit) {

        public int remaining() {
            return Math.max(0, limit - used);
        }

        /** {@code 15/30}. */
        public String text() {
            return used + "/" + limit;
        }
    }

    /**
     * The quota state of one distributor.
     *
     * @param throttledUntil the end of the latest rate limit the distributor answered with, null when none is running
     */
    public record Snapshot(
            @JsonProperty("minute") Usage minute,
            @JsonProperty("day") Usage day,
            @JsonProperty("throttled_until") @JsonInclude(JsonInclude.Include.ALWAYS) Instant throttledUntil) {
    }

    private static final class State {
        final Deque<Long> stamps = new ArrayDeque<>();
        long throttledUntilMillis;
    }

    @Autowired private KinaProperties properties;
    @Autowired private Clock clock;

    private final Map<Distributor, State> states = new EnumMap<>(Distributor.class);
    private final Map<Distributor, int[]> limits = new EnumMap<>(Distributor.class);

    @PostConstruct
    void init() {
        for (Distributor d : TRACKED) {
            states.put(d, new State());
            limits.put(d, readLimits(d));
        }
    }

    private int[] readLimits(Distributor d) {
        KinaProperties.Distributors all = properties == null ? null : properties.distributors();
        KinaProperties.Quota q = null;
        if (all != null) {
            q = d == Distributor.MOUSER
                    ? (all.mouser() == null ? null : all.mouser().quota())
                    : (all.tme() == null ? null : all.tme().quota());
        }
        KinaProperties.Quota defaults = d == Distributor.MOUSER ? KinaProperties.Quota.MOUSER_DEFAULT
                : KinaProperties.Quota.TME_DEFAULT;
        KinaProperties.Quota use = q == null ? defaults : q;
        return new int[] {use.perMinute(), use.perDay()};
    }

    /** True for the distributors with a quota (Mouser, TME). */
    public static boolean isTracked(Distributor distributor) {
        return TRACKED.contains(distributor);
    }

    /** Counts one HTTP request to {@code distributor}'s API. Ignored for LCSC. */
    public void record(Distributor distributor) {
        State s = states.get(distributor);
        if (s == null) {
            return;
        }
        long now = clock.millis();
        int cap = capacity(distributor);
        synchronized (s) {
            prune(s, now);
            s.stamps.addLast(now);
            while (s.stamps.size() > cap) {
                s.stamps.removeFirst();
            }
        }
    }

    /** Requests of the last {@code window}. */
    public int used(Distributor distributor, Window window) {
        State s = states.get(distributor);
        if (s == null) {
            return 0;
        }
        long now = clock.millis();
        synchronized (s) {
            prune(s, now);
            if (window == Window.DAY) {
                return s.stamps.size();
            }
            long from = now - Window.MINUTE.length().toMillis();
            int n = 0;
            var it = s.stamps.descendingIterator();
            while (it.hasNext() && it.next() > from) {
                n++;
            }
            return n;
        }
    }

    /** The configured limit of {@code window}; 0 for a distributor without quota. */
    public int limit(Distributor distributor, Window window) {
        int[] l = limits.get(distributor);
        return l == null ? 0 : (window == Window.MINUTE ? l[0] : l[1]);
    }

    /** Requests left in the window, never below 0. */
    public int remaining(Distributor distributor, Window window) {
        return Math.max(0, limit(distributor, window) - used(distributor, window));
    }

    /** The window's used count and limit. */
    public Usage usage(Distributor distributor, Window window) {
        return new Usage(used(distributor, window), limit(distributor, window));
    }

    /** Remembers that {@code distributor} answered with a rate limit that lasts {@code wait}. */
    public void throttle(Distributor distributor, Duration wait) {
        State s = states.get(distributor);
        if (s == null || wait == null) {
            return;
        }
        long until = clock.millis() + Math.max(0, wait.toMillis());
        synchronized (s) {
            s.throttledUntilMillis = Math.max(s.throttledUntilMillis, until);
        }
    }

    /** When the latest observed rate limit ends; null when none is running. */
    public Instant throttledUntil(Distributor distributor) {
        State s = states.get(distributor);
        if (s == null) {
            return null;
        }
        long now = clock.millis();
        synchronized (s) {
            return s.throttledUntilMillis > now ? Instant.ofEpochMilli(s.throttledUntilMillis) : null;
        }
    }

    /** The state of {@code distributor}; null for LCSC. */
    public Snapshot snapshot(Distributor distributor) {
        if (!isTracked(distributor)) {
            return null;
        }
        return new Snapshot(usage(distributor, Window.MINUTE), usage(distributor, Window.DAY),
                throttledUntil(distributor));
    }

    /** The state of every tracked distributor, in {@link Distributor} order. */
    public Map<Distributor, Snapshot> snapshot() {
        Map<Distributor, Snapshot> out = new EnumMap<>(Distributor.class);
        for (Distributor d : TRACKED) {
            out.put(d, snapshot(d));
        }
        return out;
    }

    /** Timestamps held for {@code distributor} (tests: memory stays bounded). */
    int held(Distributor distributor) {
        State s = states.get(distributor);
        synchronized (s) {
            return s.stamps.size();
        }
    }

    private int capacity(Distributor distributor) {
        int day = limit(distributor, Window.DAY);
        return day + Math.max(MARGIN_MIN, day / 10);
    }

    private static void prune(State s, long now) {
        long from = now - Window.DAY.length().toMillis();
        while (!s.stamps.isEmpty() && s.stamps.peekFirst() <= from) {
            s.stamps.removeFirst();
        }
    }
}
