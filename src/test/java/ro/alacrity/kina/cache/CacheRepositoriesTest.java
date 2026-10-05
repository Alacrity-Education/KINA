package ro.alacrity.kina.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import ro.alacrity.kina.TestcontainersConfiguration;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PriceBreak;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CacheRepositoriesTest {

    /** Postgres keeps microseconds; truncate so payload and column timestamps compare equal. */
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Autowired
    PartCacheRepository parts;

    @Autowired
    SearchCacheRepository searches;

    @Autowired
    CacheMaintenance maintenance;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM cached_parts").update();
        jdbc.sql("DELETE FROM cached_searches").update();
    }

    @Test
    void upsertThenUpdateReplacesPayloadAndTimestamp() {
        Part first = part(Distributor.TME, "CL21A106KOQNNNE", 10, NOW.minus(Duration.ofDays(1)));
        parts.upsertAll(List.of(first));
        assertThat(parts.find(Distributor.TME, "CL21A106KOQNNNE", NOW.minus(Duration.ofDays(2)))).contains(first);

        Part second = part(Distributor.TME, "CL21A106KOQNNNE", 999, NOW);
        parts.upsertAll(List.of(second));

        assertThat(parts.find(Distributor.TME, "CL21A106KOQNNNE", NOW)).contains(second);
        Instant column = jdbc.sql("SELECT fetched_at FROM cached_parts WHERE part_number = 'CL21A106KOQNNNE'")
                .query((rs, n) -> rs.getObject(1, java.time.OffsetDateTime.class).toInstant()).single();
        assertThat(column).isEqualTo(NOW);
        assertThat(count("cached_parts")).isEqualTo(1);
        // the payload is the plain camelCase Part JSON
        String payload = jdbc.sql("SELECT payload->>'distributorPartNumber' FROM cached_parts")
                .query(String.class).single();
        assertThat(payload).isEqualTo("CL21A106KOQNNNE");
    }

    @Test
    void findHonoursDistributorAndSince() {
        parts.upsertAll(List.of(part(Distributor.TME, "A", 1, NOW.minus(Duration.ofDays(6)))));
        assertThat(parts.find(Distributor.TME, "A", NOW.minus(Duration.ofDays(5)))).isEmpty();
        assertThat(parts.find(Distributor.TME, "A", NOW.minus(Duration.ofDays(7)))).isPresent();
        assertThat(parts.find(Distributor.MOUSER, "A", NOW.minus(Duration.ofDays(7)))).isEmpty();
        assertThat(parts.find(Distributor.TME, "missing", NOW.minus(Duration.ofDays(7)))).isEmpty();
    }

    @Test
    void findFreshHonoursSince() {
        parts.upsertAll(List.of(
                part(Distributor.MOUSER, "fresh", 5, NOW.minus(Duration.ofHours(1))),
                part(Distributor.MOUSER, "boundary", 5, NOW.minus(Duration.ofDays(5))),
                part(Distributor.MOUSER, "stale", 5, NOW.minus(Duration.ofDays(6))),
                part(Distributor.TME, "fresh", 5, NOW)));

        Map<String, Part> found = parts.findFresh(Distributor.MOUSER, List.of("fresh", "boundary", "stale", "nope"),
                NOW.minus(Duration.ofDays(5)));

        assertThat(found).containsOnlyKeys("fresh", "boundary");
        assertThat(found.get("fresh").distributor()).isEqualTo(Distributor.MOUSER);
        assertThat(parts.findFresh(Distributor.MOUSER, List.of(), NOW.minus(Duration.ofDays(5)))).isEmpty();
    }

    @Test
    void findFreshWithManyIds() {
        List<Part> many = IntStream.range(0, 1200)
                .mapToObj(i -> part(Distributor.TME, "P-" + i, i + 1, NOW)).toList();
        parts.upsertAll(many);
        assertThat(count("cached_parts")).isEqualTo(1200);

        List<String> wanted = new ArrayList<>();
        for (int i = 0; i < 1200; i += 2) {
            wanted.add("P-" + i);
        }
        wanted.add("P-0"); // duplicates are harmless
        wanted.add("absent");

        Map<String, Part> found = parts.findFresh(Distributor.TME, wanted, NOW.minusSeconds(1));
        assertThat(found).hasSize(600);
        assertThat(found.get("P-10").stock()).isEqualTo(11);
    }

    @Test
    void corruptPayloadRowIsSkipped() {
        parts.upsertAll(List.of(part(Distributor.TME, "good", 1, NOW)));
        insertRaw("TME", "bad-enum", "{\"distributor\":\"NOPE\",\"stock\":1}");
        insertRaw("TME", "bad-type", "{\"distributor\":\"TME\",\"stock\":\"many\"}");
        insertRaw("TME", "bad-shape", "[1,2,3]");

        Map<String, Part> found = parts.findFresh(Distributor.TME, List.of("good", "bad-enum", "bad-type", "bad-shape"),
                NOW.minusSeconds(60));

        assertThat(found).containsOnlyKeys("good");
        assertThat(parts.find(Distributor.TME, "bad-type", NOW.minusSeconds(60))).isEmpty();
    }

    @Test
    void stats() {
        parts.upsertAll(List.of(
                part(Distributor.TME, "a", 1, NOW),
                part(Distributor.TME, "b", 1, NOW.minus(Duration.ofDays(6))),
                part(Distributor.MOUSER, "c", 1, NOW.minus(Duration.ofDays(1)))));
        searches.upsert(new CachedSearch(Distributor.TME, "10k 0603", 2, List.of("a", "b"), true, NOW));

        CacheStatistics stats = parts.stats();

        assertThat(stats.parts()).isEqualTo(3);
        assertThat(stats.freshParts()).isEqualTo(2);
        assertThat(stats.searches()).isEqualTo(1);
        assertThat(stats.partsByDistributor())
                .containsEntry(Distributor.TME, 2L)
                .containsEntry(Distributor.MOUSER, 1L)
                .containsEntry(Distributor.LCSC, 0L);
        assertThat(stats.oldestFetch()).isEqualTo(NOW.minus(Duration.ofDays(6)));
    }

    @Test
    void statsOnEmptyCache() {
        CacheStatistics stats = parts.stats();
        assertThat(stats.parts()).isZero();
        assertThat(stats.searches()).isZero();
        assertThat(stats.oldestFetch()).isNull();
    }

    @Test
    void deleteOlderThan() {
        parts.upsertAll(List.of(
                part(Distributor.TME, "old", 1, NOW.minus(Duration.ofDays(20))),
                part(Distributor.TME, "new", 1, NOW)));
        searches.upsert(new CachedSearch(Distributor.TME, "old", null, List.of(), false, NOW.minus(Duration.ofDays(20))));
        searches.upsert(new CachedSearch(Distributor.TME, "new", null, List.of(), false, NOW));

        assertThat(parts.deleteOlderThan(NOW.minus(Duration.ofDays(10)))).isEqualTo(1);
        assertThat(searches.deleteOlderThan(NOW.minus(Duration.ofDays(10)))).isEqualTo(1);
        assertThat(parts.find(Distributor.TME, "new", NOW.minus(Duration.ofDays(30)))).isPresent();
        assertThat(searches.find(Distributor.TME, "old")).isEmpty();
        assertThat(searches.find(Distributor.TME, "new")).isPresent();
    }

    @Test
    void maintenancePurgesRowsOlderThanTwiceTtl() {
        parts.upsertAll(List.of(
                part(Distributor.TME, "11d", 1, NOW.minus(Duration.ofDays(11))),
                part(Distributor.TME, "9d", 1, NOW.minus(Duration.ofDays(9)))));
        searches.upsert(new CachedSearch(Distributor.MOUSER, "q11", 0, List.of(), true, NOW.minus(Duration.ofDays(11))));
        searches.upsert(new CachedSearch(Distributor.MOUSER, "q9", 0, List.of(), true, NOW.minus(Duration.ofDays(9))));

        CacheMaintenance.PurgeResult result = maintenance.purge();

        assertThat(result).isEqualTo(new CacheMaintenance.PurgeResult(1, 1));
        assertThat(count("cached_parts")).isEqualTo(1);
        assertThat(searches.find(Distributor.MOUSER, "q9")).isPresent();
    }

    @Test
    void searchCacheRoundTrip() {
        CachedSearch search = new CachedSearch(Distributor.MOUSER, "10uf x7r 0805", 113,
                List.of("603-CC0805", "81-GRM21BR71A106KA3L", "187-CL21B106KOQNNNE"), false, NOW, 50);
        searches.upsert(search);
        assertThat(searches.find(Distributor.MOUSER, "10uf x7r 0805")).contains(search);
        assertThat(searches.find(Distributor.TME, "10uf x7r 0805")).isEmpty();

        CachedSearch updated = new CachedSearch(Distributor.MOUSER, "10uf x7r 0805", null, List.of(), true,
                NOW.plusSeconds(5));
        searches.upsert(updated);
        CachedSearch read = searches.find(Distributor.MOUSER, "10uf x7r 0805").orElseThrow();
        assertThat(read).isEqualTo(updated);
        assertThat(read.partNumbers()).isEmpty();
        assertThat(read.exhausted()).isTrue();
        assertThat(read.totalResults()).isNull();
        assertThat(read.nextOffset()).isNull();
        assertThat(read.fallbackQuery()).isNull();
        assertThat(count("cached_searches")).isEqualTo(1);

        CachedSearch fallback = new CachedSearch(Distributor.MOUSER, "10uf x7r 0805", 7, List.of("a"), false,
                NOW.plusSeconds(6), 50, "MLCC 10uF 0805");
        searches.upsert(fallback);
        assertThat(searches.find(Distributor.MOUSER, "10uf x7r 0805")).contains(fallback);
        String json = jdbc.sql("SELECT jsonb_typeof(part_numbers) FROM cached_searches").query(String.class).single();
        assertThat(json).isEqualTo("array");
    }

    @Test
    void corruptSearchRowIsTreatedAsMissing() {
        jdbc.sql("""
                        INSERT INTO cached_searches (distributor, query_key, part_numbers, fetched_at)
                        VALUES ('TME', 'bad', '{"not":"an array"}'::jsonb, now())""").update();
        assertThat(searches.find(Distributor.TME, "bad")).isEmpty();
    }

    private void insertRaw(String distributor, String partNumber, String json) {
        jdbc.sql("INSERT INTO cached_parts (distributor, part_number, payload, fetched_at) VALUES (?, ?, ?::jsonb, ?)")
                .params(distributor, partNumber, json, NOW.atOffset(ZoneOffset.UTC))
                .update();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static Part part(Distributor distributor, String partNumber, int stock, Instant fetchedAt) {
        return new Part(distributor, partNumber, "SAMSUNG", "CL21A106KOQNNNE", "MLCC 10uF", "Capacitors", "0805",
                stock, 1, null, List.of(new PriceBreak(1, new BigDecimal("0.10"), "EUR")), null, null,
                "https://example.invalid/" + partNumber, Map.of("Capacitance", "10uF"), Map.of("rohs", "yes"),
                fetchedAt);
    }
}
