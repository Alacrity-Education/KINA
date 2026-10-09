package ro.alacrity.kina.distributor.lcsc;

import ro.alacrity.kina.TestWiring;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.search.ParametricExtractor;

import java.nio.file.Path;
import java.time.Duration;

/** Wiring of the LCSC beans on a test database file, with the pool and the typed table (phase B of the field search). */
public final class LcscTestSupport {

    private LcscTestSupport() {
    }

    /** {@code kina.jlcpcb.*} for {@code file}. */
    public static KinaProperties properties(Path file, int poolSize, boolean fieldIndex, Duration poolWait) {
        Path absolute = file.toAbsolutePath();
        return TestWiring.properties("kina.jlcpcb.data-dir", absolute.getParent().toString(),
                "kina.jlcpcb.library", absolute.getFileName().toString(),
                "kina.jlcpcb.pool-size", Integer.toString(poolSize),
                "kina.jlcpcb.pool-wait", poolWait.toMillis() + "ms",
                "kina.jlcpcb.field-index.enabled", Boolean.toString(fieldIndex),
                "kina.jlcpcb.field-index.threads", "2");
    }

    /** A search on {@code file} with {@code poolSize} connections; the typed table attaches when enabled and current. */
    public static JlcpcbSqliteSearch search(Path file, int poolSize, boolean fieldIndex, Duration poolWait) {
        return TestWiring.wire(new JlcpcbSqliteSearch(), "properties", properties(file, poolSize, fieldIndex, poolWait));
    }

    /** As {@link #search(Path, int, boolean, Duration)} with 4 connections and the default wait. */
    public static JlcpcbSqliteSearch search(Path file, boolean fieldIndex) {
        return search(file, 4, fieldIndex, Duration.ofSeconds(10));
    }

    /** The builder of the typed table (two threads). */
    public static JlcpcbFieldIndexBuilder builder(Path file) {
        return TestWiring.wire(new JlcpcbFieldIndexBuilder(), "properties",
                properties(file, 4, true, Duration.ofSeconds(10)), "extractor", new ParametricExtractor());
    }

    /** Builds the sidecar of {@code file} next to it and returns its path. */
    public static Path buildIndex(Path file) throws Exception {
        Path sidecar = FieldIndexFile.sidecar(file.toAbsolutePath());
        builder(file).buildAndWarm(file.toAbsolutePath(), sidecar);
        return sidecar;
    }

    /** The field query of LCSC on {@code search}. */
    public static LcscFieldSearch fieldSearch(JlcpcbSqliteSearch search) {
        return TestWiring.wire(new LcscFieldSearch(), "search", search);
    }
}
