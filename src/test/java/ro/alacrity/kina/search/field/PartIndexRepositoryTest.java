package ro.alacrity.kina.search.field;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import ro.alacrity.kina.TestWiring;
import ro.alacrity.kina.TestcontainersConfiguration;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.cache.PartMetadataHash;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PriceBreak;
import ro.alacrity.kina.metrics.KinaMetrics;
import ro.alacrity.kina.search.ParametricExtractor;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * When an index row is current (DESIGN.md 3.8, v0.15.1): the metadata hash of both sides decides, never the volatile
 * stock, prices or timestamps of the payload; the hash of an index row is the one of the part it was built from; a
 * single cache write keeps the coverage snapshot; the coverage reads no payload; the periodic sweep repairs a stale row.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PartIndexRepositoryTest {

    @Autowired PartCacheRepository partCache;
    @Autowired PartIndexRepository index;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper jsonMapper;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM cached_parts").update();
        index.forgetCoverage();
    }

    private static Part part(String number) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("Resistance", "4.7 kOhms");
        attributes.put("Tolerance", "1 %");
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("lifecycle_status", "New Product");
        extra.put("factory_stock", 76689);
        extra.put("ratio", 1.50);
        extra.put("exact", new BigDecimal("2.50"));
        return new Part(Distributor.MOUSER, number, "YAGEO", "RC0603FR-074K7L", "Thick Film Resistors 4.7k 1% 0603",
                "Thick Film Resistors - SMD", "0603", 5000, 1, 1,
                List.of(new PriceBreak(1, new BigDecimal("0.10"), "EUR")), null, null,
                "https://example.invalid/" + number, attributes, extra, Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    /** Rewrites the cache row without the listener (any writer that changes the payload), with its new hash. */
    private void rewrite(Part part) {
        String json = jsonMapper.writeValueAsString(part.asStored());
        jdbc.sql("UPDATE cached_parts SET payload = ?::jsonb, metadata_md5 = ? WHERE distributor = ? AND part_number = ?")
                .params(json, PartMetadataHash.ofPayload(json), part.distributor().name(), part.distributorPartNumber())
                .update();
    }

    private long stale() {
        return index.coverage().get(Distributor.MOUSER).stale();
    }

    @Test
    void theWriteTimeHashIsTheHashOfTheStoredPayload() {
        Part part = part("H1");
        partCache.upsertAll(List.of(part));
        String written = jdbc.sql("SELECT metadata_md5 FROM cached_parts WHERE part_number = 'H1'")
                .query(String.class).single();
        String stored = jdbc.sql("SELECT payload::text FROM cached_parts WHERE part_number = 'H1'")
                .query(String.class).single();
        assertThat(written).isEqualTo(PartMetadataHash.ofPayload(stored)).isEqualTo(PartMetadataHash.of(jsonMapper,
                part));
        assertThat(jdbc.sql("SELECT metadata_md5 FROM part_index WHERE part_number = 'H1'").query(String.class)
                .single()).isEqualTo(written);

        // the backfill of a row written before V16 computes the same hash from payload::text
        jdbc.sql("UPDATE cached_parts SET metadata_md5 = NULL").update();
        assertThat(partCache.backfillMetadataHashes(10)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT metadata_md5 FROM cached_parts WHERE part_number = 'H1'").query(String.class)
                .single()).isEqualTo(written);
    }

    @Test
    void stockPricesAndFetchedAtDoNotChangeTheHashTheMetadataDoes() {
        Part part = part("H2");
        String hash = PartMetadataHash.of(jsonMapper, part);
        assertThat(PartMetadataHash.of(jsonMapper, part.toBuilder().stock(1).fetchedAt(Instant.EPOCH)
                .prices(List.of(new PriceBreak(10, new BigDecimal("9.99"), "EUR"))).minimumOrderQuantity(10)
                .build())).isEqualTo(hash);
        assertThat(PartMetadataHash.of(jsonMapper, part.toBuilder().description("other").build())).isNotEqualTo(hash);
        assertThat(PartMetadataHash.of(jsonMapper, part.toBuilder().attributes(Map.of("Resistance", "10 kOhms"))
                .build())).isNotEqualTo(hash);
        assertThat(PartMetadataHash.of(jsonMapper, part.toBuilder().extra(Map.of()).build())).isNotEqualTo(hash);
        assertThat(PartMetadataHash.ofPayload("{\"extra\":{\"a\":1.50,\"b\":[1E1]}}"))
                .as("number forms and key order").isEqualTo(PartMetadataHash.ofPayload(
                        "{\"extra\":{\"b\":[10],\"a\":1.5}}"));
    }

    @Test
    void aPayloadChangeLimitedToStockPricesAndFetchedAtKeepsTheRowCurrentAMetadataChangeDoesNot() {
        Part part = part("S1");
        partCache.upsertAll(List.of(part));
        assertThat(stale()).isZero();

        rewrite(part.toBuilder().stock(42).fetchedAt(Instant.now().plusSeconds(60))
                .prices(List.of(new PriceBreak(1, new BigDecimal("0.07"), "EUR"))).build());
        assertThat(stale()).as("stock, prices and fetchedAt only").isZero();

        rewrite(part.toBuilder().description("Thick Film Resistors 4.7k 1% 0603 AEC-Q200").build());
        assertThat(stale()).as("a new description").isEqualTo(1);
        assertThat(index.reindexStale(ParametricExtractor.INDEX_VERSION, 10).total()).isEqualTo(1);
        assertThat(stale()).isZero();

        rewrite(part.toBuilder().attributes(Map.of("Resistance", "10 kOhms")).build());
        assertThat(stale()).as("a new attribute").isEqualTo(1);
    }

    @Test
    void aStockRefreshThroughTheRepositoryKeepsTheRowCurrentAndDoesNotRewriteIt() {
        Part part = part("S2");
        partCache.upsertAll(List.of(part));
        Instant indexedAt = jdbc.sql("SELECT indexed_at FROM part_index WHERE part_number = 'S2'")
                .query(java.time.OffsetDateTime.class).single().toInstant();
        partCache.updateStock(List.of(part.toBuilder().stock(1234).fetchedAt(Instant.now().plusSeconds(5)).build()));
        assertThat(stale()).isZero();
        assertThat(jdbc.sql("SELECT indexed_at FROM part_index WHERE part_number = 'S2'")
                .query(java.time.OffsetDateTime.class).single().toInstant()).as("unchanged row not rewritten")
                .isEqualTo(indexedAt);

        // a sold-out row and its return keep the index in step
        partCache.markSoldOut(Distributor.MOUSER, "S2");
        assertThat(stale()).isZero();
        partCache.updateStock(List.of(part.toBuilder().stock(10).build()));
        assertThat(stale()).isZero();
        assertThat(jdbc.sql("SELECT in_stock FROM part_index WHERE part_number = 'S2'").query(Boolean.class)
                .single()).isTrue();

        // a lookup that brings new metadata re-indexes the part in the same write
        partCache.updateStock(List.of(part.toBuilder().description("Thick Film Resistors 4k7 0603").build()));
        assertThat(stale()).isZero();
    }

    /** Review A, finding 3: a row built from an older part never looks current after a newer payload was written. */
    @Test
    void theHashOfAnIndexRowIsTheOneOfThePartItWasBuiltFrom() {
        Part old = part("R1");
        partCache.upsertAll(List.of(old));
        List<PartIndexRow> built = index.rows(List.of(old), true);
        // a concurrent writer replaces the metadata between the build and the write of the row
        rewrite(old.toBuilder().description("Thick Film Resistors 10k 1% 0603")
                .attributes(Map.of("Resistance", "10 kOhms")).build());
        index.write(built);
        assertThat(stale()).as("old features under the new payload are stale").isEqualTo(1);
        assertThat(index.reindexStale(ParametricExtractor.INDEX_VERSION, 10).total()).isEqualTo(1);
        assertThat(stale()).isZero();
    }

    /** Review A, finding 2: a single cache write keeps the snapshot; the coverage reads no payload. */
    @Test
    void aSingleWriteKeepsTheCoverageSnapshotAndTheCoverageReadsNoPayload() {
        partCache.upsertAll(List.of(part("C1")));
        assertThat(index.isComplete(Distributor.MOUSER)).isTrue();
        // a row removed behind the repository's back: the snapshot still says complete ...
        jdbc.sql("DELETE FROM part_index WHERE part_number = 'C1'").update();
        // ... and a write of another part does not drop it (it is current by construction)
        partCache.upsertAll(List.of(part("C2")));
        assertThat(index.isComplete(Distributor.MOUSER)).isTrue();
        index.forgetCoverage();
        assertThat(index.isComplete(Distributor.MOUSER)).isFalse();

        String plan = String.join("\n", jdbc.sql("EXPLAIN (VERBOSE) " + PartIndexRepository.COVERAGE_SQL)
                .param(ParametricExtractor.INDEX_VERSION).query(String.class).list());
        assertThat(plan).as("no cached payload is read or hashed").doesNotContain("c.payload")
                .doesNotContain("md5(");
    }

    @Test
    void thePeriodicSweepRepairsAStaleRowAndCountsIt() throws NoSuchMethodException {
        partCache.upsertAll(List.of(part("W1"), part("W2")));
        jdbc.sql("UPDATE part_index SET metadata_md5 = 'nope' WHERE part_number = 'W2'").update();
        assertThat(stale()).isEqualTo(1);

        KinaMetrics metrics = mock(KinaMetrics.class);
        PartIndexReindexer reindexer = TestWiring.wire(new PartIndexReindexer(), "properties", TestWiring.properties(),
                "index", index, "cache", partCache, "metrics", metrics);
        reindexer.sweep();
        assertThat(stale()).isZero();
        verify(metrics).fieldIndexSweepRepaired("MOUSER", 1);
        verify(metrics).fieldIndexReindexed("MOUSER", 1);

        // the startup run is not a sweep: its rows are not counted as repaired
        jdbc.sql("UPDATE part_index SET metadata_md5 = 'nope' WHERE part_number = 'W1'").update();
        KinaMetrics startup = mock(KinaMetrics.class);
        TestWiring.wire(reindexer, "metrics", startup);
        assertThat(reindexer.run()).isPresent();
        verify(startup).fieldIndexReindexed("MOUSER", 1);
        verify(startup, never()).fieldIndexSweepRepaired("MOUSER", 1);

        Scheduled scheduled = PartIndexReindexer.class.getDeclaredMethod("sweep").getAnnotation(Scheduled.class);
        assertThat(scheduled.fixedDelayString()).isEqualTo("${kina.search.field-index.reindex-interval:1h}");
        assertThat(TestWiring.properties().search().fieldIndex().reindexInterval())
                .isEqualTo(java.time.Duration.ofHours(1));
    }
}
