package ro.alacrity.kina.distributor.lcsc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The pool of read-only connections of {@link JlcpcbSqliteSearch} (DESIGN.md 9.3). */
class JlcpcbSqlitePoolTest {

    @TempDir
    Path dir;

    JlcpcbSqliteSearch search;

    @AfterEach
    void tearDown() {
        if (search != null) {
            search.close();
        }
    }

    @Test
    void connectionsAreReadOnly() throws Exception {
        Path file = JlcpcbTestDatabase.create(dir.resolve("parts-fts5.db"));
        search = LcscTestSupport.search(file, 3, false, Duration.ofSeconds(5));
        assertThat(search.isAvailable()).isTrue();
        assertThat(search.idleConnections()).isEqualTo(3);
        assertThatThrownBy(() -> search.withConnection(null, c -> {
            c.createStatement().execute("CREATE TABLE x (a)");
            return null;
        })).isInstanceOf(SQLException.class);
        assertThat(search.idleConnections()).isEqualTo(3);   // a failed query gives the connection back
    }

    @Test
    void concurrentSearchesAllSucceedAndReturnTheirConnections() throws Exception {
        Path file = JlcpcbTestDatabase.create(dir.resolve("parts-fts5.db"), JlcpcbTestDatabase.withUsb());
        search = LcscTestSupport.search(file, 4, false, Duration.ofSeconds(10));
        List<String> queries = List.of("10uF X7R 0805", "10k resistor 0805", "x7r capacitor 0402", "1k 5% resistor",
                "female header 1x6", "Type-C 16P", "RP2040", "10k ohm 0603");
        List<Integer> expected = new ArrayList<>();
        for (String q : queries) {
            expected.add(search.search(q, 0, 50).total());
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int offset = t;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 50; i++) {
                        int q = (i + offset) % queries.size();
                        if (search.search(queries.get(q), 0, 50).total() != expected.get(q)) {
                            return false;
                        }
                    }
                    return true;
                }));
            }
            for (Future<Boolean> f : futures) {
                assertThat(f.get(60, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(search.idleConnections()).isEqualTo(4);
    }

    @Test
    void searchesRunOnDifferentConnectionsAtTheSameTime() throws Exception {
        Path file = JlcpcbTestDatabase.create(dir.resolve("parts-fts5.db"));
        search = LcscTestSupport.search(file, 3, false, Duration.ofSeconds(5));
        CountDownLatch inside = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                futures.add(pool.submit(() -> search.withConnection(null, c -> {
                    inside.countDown();
                    try {
                        assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return c;
                })));
            }
            assertThat(inside.await(10, TimeUnit.SECONDS)).as("three borrowers at once").isTrue();
            assertThat(search.idleConnections()).isZero();
            release.countDown();
            List<Object> connections = new ArrayList<>();
            for (Future<Object> f : futures) {
                connections.add(f.get(10, TimeUnit.SECONDS));
            }
            assertThat(connections).doesNotHaveDuplicates();
        } finally {
            pool.shutdownNow();
        }
        assertThat(search.idleConnections()).isEqualTo(3);
    }

    @Test
    void waitForAFreeConnectionIsBounded() throws Exception {
        Path file = JlcpcbTestDatabase.create(dir.resolve("parts-fts5.db"));
        search = LcscTestSupport.search(file, 1, false, Duration.ofMillis(200));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> {
            try {
                search.withConnection(null, c -> {
                    held.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        try {
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();
            assertThatThrownBy(() -> search.search("10uF X7R 0805", 0, 5)).isInstanceOf(SQLException.class)
                    .hasMessageContaining("no free JLCPCB connection within 200 ms");
            long millis = (System.nanoTime() - started) / 1_000_000;
            assertThat(millis).isBetween(150L, 3000L);
            // a caller with its own, shorter wait
            started = System.nanoTime();
            assertThatThrownBy(() -> search.withConnection(Duration.ofMillis(30), c -> 1)).isInstanceOf(SQLException.class);
            assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1000L);
        } finally {
            release.countDown();
            holder.join(10_000);
        }
        assertThat(search.search("10uF X7R 0805", 0, 5).total()).isEqualTo(1);   // the pool recovered
    }

    @Test
    void swapUnderLoadReplacesTheFileWithoutFailedSearches() throws Exception {
        Path file = JlcpcbTestDatabase.create(dir.resolve("parts-fts5.db"));
        Path next = JlcpcbTestDatabase.create(dir.resolve("next.db"), List.of(JlcpcbTestDatabase.row("C1", "Capacitors",
                "MLCC", "M1", "0805", "M", "Basic", "10uF 25V X7R", "1-:0.1", "10"), JlcpcbTestDatabase.row("C2",
                "Capacitors", "MLCC", "M2", "0805", "M", "Basic", "10uF 50V X7R", "1-:0.1", "20")));
        search = LcscTestSupport.search(file, 4, false, Duration.ofSeconds(10));
        assertThat(search.search("10uF X7R 0805", 0, 50).total()).isEqualTo(1);

        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger runs = new AtomicInteger();
        AtomicBoolean sawNew = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> workers = new ArrayList<>();
            for (int t = 0; t < 6; t++) {
                workers.add(pool.submit(() -> {
                    while (!stop.get()) {
                        try {
                            int total = search.search("10uF X7R 0805", 0, 50).total();
                            runs.incrementAndGet();
                            if (total == 2) {
                                sawNew.set(true);
                            } else if (total != 1) {
                                failures.incrementAndGet();
                            }
                        } catch (SQLException | RuntimeException e) {
                            failures.incrementAndGet();
                        }
                    }
                }));
            }
            Thread.sleep(100);
            search.replaceDatabase(() -> Files.move(next, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!sawNew.get() && System.nanoTime() < until) {
                Thread.sleep(10);
            }
            stop.set(true);
            for (Future<?> w : workers) {
                w.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(failures).hasValue(0);
        assertThat(runs.get()).isPositive();
        assertThat(sawNew).isTrue();
        assertThat(search.idleConnections()).isEqualTo(4);
    }

    @Test
    void reopenClosesAndReopensEveryConnection() throws Exception {
        Path file = JlcpcbTestDatabase.create(dir.resolve("parts-fts5.db"));
        search = LcscTestSupport.search(file, 3, false, Duration.ofSeconds(5));
        List<Object> before = new ArrayList<>();
        search.withConnection(null, c -> before.add(c));
        search.reopen();
        assertThat(search.idleConnections()).isEqualTo(3);
        List<Object> after = new ArrayList<>();
        search.withConnection(null, c -> after.add(c));
        assertThat(after).doesNotContainAnyElementsOf(before);
        assertThat(search.search("10uF X7R 0805", 0, 5).total()).isEqualTo(1);   // kina_value is registered again
    }
}
