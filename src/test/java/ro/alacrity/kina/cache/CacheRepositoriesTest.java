package ro.alacrity.kina.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import ro.alacrity.kina.TestWiring;
import ro.alacrity.kina.TestcontainersConfiguration;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PriceBreak;

import java.math.BigDecimal;
import java.time.Clock;
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
        assertThat(column("stock_fetched_at", "CL21A106KOQNNNE")).isEqualTo(NOW);
        assertThat(column("metadata_fetched_at", "CL21A106KOQNNNE")).isEqualTo(NOW);
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
    void partsWithoutShipsNowStockAreNeverCachedOrServed() {
        parts.upsertAll(List.of(part(Distributor.TME, "in-stock", 3, NOW), part(Distributor.TME, "zero", 0, NOW)));
        assertThat(count("cached_parts")).isEqualTo(1);

        // a row written by an older version (or by hand) is not served
        insertRaw("TME", "legacy-zero", "{\"distributor\":\"TME\",\"distributorPartNumber\":\"legacy-zero\","
                + "\"stock\":0,\"fetchedAt\":\"" + NOW + "\"}");
        assertThat(parts.findFresh(Distributor.TME, List.of("in-stock", "zero", "legacy-zero"), NOW.minusSeconds(60)))
                .containsOnlyKeys("in-stock");
        assertThat(parts.find(Distributor.TME, "legacy-zero", NOW.minusSeconds(60))).isEmpty();
    }

    @Test
    void aListedPartOfAnExplicitLookupIsCachedNotInStockAndNeverServed() {
        // DESIGN.md 2: a part requested by its part number and listed without stock keeps its metadata, in_stock false
        parts.upsertListed(List.of(part(Distributor.MOUSER, "65-EPC2302", 0, NOW),
                part(Distributor.MOUSER, "in-stock", 5, NOW)));
        assertThat(count("cached_parts")).isEqualTo(1);   // a part with stock is not written by upsertListed
        assertThat(jdbc.sql("SELECT in_stock FROM cached_parts WHERE part_number = '65-EPC2302'")
                .query(Boolean.class).single()).isFalse();
        assertThat(parts.find(Distributor.MOUSER, "65-EPC2302")).isEmpty();
        assertThat(parts.findInStock(Distributor.MOUSER, List.of("65-EPC2302"))).isEmpty();

        // a later fetch that finds it in stock serves it again
        parts.upsertAll(List.of(part(Distributor.MOUSER, "65-EPC2302", 7, NOW)));
        assertThat(parts.find(Distributor.MOUSER, "65-EPC2302")).hasValueSatisfying(p ->
                assertThat(p.stock()).isEqualTo(7));
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
    void deleteMetadataOlderThanIsPerDistributor() {
        parts.upsertAll(List.of(
                part(Distributor.MOUSER, "old", 1, NOW.minus(Duration.ofDays(20))),
                part(Distributor.MOUSER, "new", 1, NOW),
                part(Distributor.TME, "old", 1, NOW.minus(Duration.ofDays(20)))));

        assertThat(parts.deleteMetadataOlderThan(Distributor.MOUSER, NOW.minus(Duration.ofDays(10)))).isEqualTo(1);
        assertThat(parts.find(Distributor.MOUSER, "new")).isPresent();
        assertThat(parts.find(Distributor.MOUSER, "old")).isEmpty();
        assertThat(parts.find(Distributor.TME, "old")).isPresent();
    }

    @Test
    void maintenanceKeepsMetadataForeverByDefaultAndPurgesSearchListsAfterTwiceTtl() {
        parts.upsertAll(List.of(
                part(Distributor.TME, "400d", 1, NOW.minus(Duration.ofDays(400))),
                part(Distributor.MOUSER, "400d", 1, NOW.minus(Duration.ofDays(400)))));
        searches.upsert(new CachedSearch(Distributor.MOUSER, "q7", 0, List.of(), true, NOW.minus(Duration.ofDays(7))));
        searches.upsert(new CachedSearch(Distributor.MOUSER, "q5", 0, List.of(), true, NOW.minus(Duration.ofDays(5))));

        CacheMaintenance.PurgeResult result = maintenance.purge();

        // default ttl 3d: lists older than 6 days go; metadata retention defaults to forever for every distributor
        assertThat(result).isEqualTo(new CacheMaintenance.PurgeResult(0, 1));
        assertThat(count("cached_parts")).isEqualTo(2);
        assertThat(searches.find(Distributor.MOUSER, "q5")).isPresent();
        assertThat(searches.find(Distributor.MOUSER, "q7")).isEmpty();
    }

    /** {@code kina.cache.metadata-retention.MOUSER=3d}: Mouser metadata is purged after 3 days, TME's survives. */
    @Test
    void mouserMetadataPurgedAfterItsRetentionWhileTmeMetadataSurvives() {
        KinaProperties props = new Binder(new MapConfigurationPropertySource(Map.of(
                "kina.cache.metadata-retention.MOUSER", "3d", "kina.cache.metadata-retention.TME", "forever")))
                .bindOrCreate("kina", Bindable.of(KinaProperties.class));
        CacheMaintenance mouser3d = TestWiring.wire(new CacheMaintenance(), "parts", parts, "searches", searches,
                "clock", Clock.fixed(NOW, ZoneOffset.UTC), "properties", props);
        parts.upsertAll(List.of(
                part(Distributor.MOUSER, "M-4d", 1, NOW.minus(Duration.ofDays(4))),
                part(Distributor.MOUSER, "M-2d", 1, NOW.minus(Duration.ofDays(2))),
                part(Distributor.TME, "T-400d", 1, NOW.minus(Duration.ofDays(400)))));
        // a stock refresh does not extend the metadata's life
        parts.updateStock(List.of(part(Distributor.MOUSER, "M-4d", 9, NOW)));

        assertThat(mouser3d.purge().parts()).isEqualTo(1);
        assertThat(parts.find(Distributor.MOUSER, "M-4d")).isEmpty();
        assertThat(parts.find(Distributor.MOUSER, "M-2d")).isPresent();
        assertThat(parts.find(Distributor.TME, "T-400d")).isPresent();
    }

    @Test
    void updateStockKeepsTheMetadataTimestampAndOnlyTouchesExistingRows() {
        Instant old = NOW.minus(Duration.ofDays(5));
        parts.upsertAll(List.of(part(Distributor.TME, "A", 5, old)));
        parts.updateStock(List.of(part(Distributor.TME, "A", 77, NOW), part(Distributor.TME, "absent", 1, NOW)));

        assertThat(parts.find(Distributor.TME, "A").orElseThrow().stock()).isEqualTo(77);
        assertThat(column("stock_fetched_at", "A")).isEqualTo(NOW);
        assertThat(column("metadata_fetched_at", "A")).isEqualTo(old);
        assertThat(count("cached_parts")).isEqualTo(1);
    }

    @Test
    void soldOutRowKeepsItsMetadataButIsNotServedUntilFetchedAgain() {
        parts.upsertAll(List.of(part(Distributor.MOUSER, "S", 5, NOW.minus(Duration.ofDays(1)))));
        parts.markSoldOut(Distributor.MOUSER, "S");

        assertThat(count("cached_parts")).isEqualTo(1);
        assertThat(parts.find(Distributor.MOUSER, "S")).isEmpty();
        assertThat(parts.findInStock(Distributor.MOUSER, List.of("S"))).isEmpty();
        assertThat(parts.stats().freshParts()).isZero();

        parts.upsertAll(List.of(part(Distributor.MOUSER, "S", 3, NOW)));
        assertThat(parts.find(Distributor.MOUSER, "S").orElseThrow().stock()).isEqualTo(3);
    }

    @Test
    void findInStockIgnoresTheStockAge() {
        parts.upsertAll(List.of(part(Distributor.TME, "ancient", 2, NOW.minus(Duration.ofDays(300)))));
        assertThat(parts.findInStock(Distributor.TME, List.of("ancient"))).containsOnlyKeys("ancient");
        assertThat(parts.find(Distributor.TME, "ancient")).isPresent();
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
        jdbc.sql("""
                        INSERT INTO cached_parts (distributor, part_number, payload, stock_fetched_at, metadata_fetched_at)
                        VALUES (?, ?, ?::jsonb, ?, ?)""")
                .params(distributor, partNumber, json, NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC))
                .update();
    }

    private Instant column(String name, String partNumber) {
        return jdbc.sql("SELECT " + name + " FROM cached_parts WHERE part_number = ?").param(partNumber)
                .query((rs, n) -> rs.getObject(1, java.time.OffsetDateTime.class).toInstant()).single();
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
