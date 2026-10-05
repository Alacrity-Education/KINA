package ro.alacrity.kina.distributor.lcsc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manual check against a real JLCPCB database (e.g. {@code basic-parts-fts5.db}); skipped unless
 * {@code KINA_JLCPCB_TEST_DB} points to the file:
 * {@code KINA_JLCPCB_TEST_DB=/path/basic-parts-fts5.db ./mvnw -q test -Dtest=JlcpcbRealDatabaseManualTest}.
 */
@EnabledIfEnvironmentVariable(named = "KINA_JLCPCB_TEST_DB", matches = ".+")
class JlcpcbRealDatabaseManualTest {

    @Test
    void searchesRealDatabase() throws Exception {
        Path file = Path.of(System.getenv("KINA_JLCPCB_TEST_DB"));
        System.out.println(JlcpcbDatabaseValidator.validate(file));
        JlcpcbSqliteSearch search = new JlcpcbSqliteSearch(file);
        try {
            for (String q : List.of("10uF X7R 0805", "100nF 0402 capacitor", "10k resistor 0805", "4k7 0603",
                    "10k ohm 1% 0603", "1k 5%", "AMS1117-3.3", "3V3 LDO regulator SOT-223", "STM32F103C8T6",
                    "N-channel MOSFET SOT-23 30V", "I need a low noise op amp in SOIC-8 for audio",
                    "schottky diode SMA 40V 1A", "usb type-c connector")) {
                long t0 = System.nanoTime();
                JlcpcbSqliteSearch.Result r = search.search(q, 0, 5);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                System.out.printf("%-50s total=%-5d mode=%-10s %4d ms%n", q, r.total(), r.mode(), ms);
                r.rows().forEach(row -> System.out.printf("    %-9s %-24s %-14s %s [%s]%n", row.lcscPart(),
                        row.mfrPart(), row.packageName(), row.description(), row.stock()));
            }
            assertThat(search.findByLcsc("C1525")).isPresent();
        } finally {
            search.close();
        }
    }
}
