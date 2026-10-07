package ro.alacrity.kina.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import ro.alacrity.kina.TestcontainersConfiguration;
import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PartLookupResponse;
import ro.alacrity.kina.domain.ResponseDetail;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cache stores the distributor's attributes only and every read derives the rest with the running extractor
 * (DESIGN.md 3.2 "Cache model"); rows cached before that are still served, and migration V11 makes them derive
 * {@link ParametricExtractor#DERIVED_ONLY_KEYS} afresh.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CachedAttributesTest {

    private static final Path V11 = Path.of("src/main/resources/db/migration/V11__cached_parts_derived_attributes.sql");
    private static final String MPN = "IGI60L2727B1MXUMA1";
    /** Mouser lists this GaN half-bridge with an integrated driver under "GaN FETs": a gate driver since 0.5. */
    private static final Part RAW = RankingFixtures.mouser(MPN, "Infineon", "270 mohm / 600 V GaN transistor in "
            + "half-bridge configuration with integrated level-shift gate driver and bootstrap diode", "GaN FETs",
            null, Map.of("Mounting Style", "SMD/SMT", "Technology", "GaN"));

    @Autowired
    PartCacheRepository cache;

    @Autowired
    PartLookupService lookup;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper jsonMapper;

    private final ParametricExtractor extractor = new ParametricExtractor();

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM cached_parts").update();
    }

    @Test
    void newRowsStoreTheDistributorAttributesOnly() {
        Part enriched = extractor.enrich(RAW.toBuilder().fetchedAt(Instant.now()).build());
        assertThat(enriched.attributes()).containsEntry("Family", "gate driver");

        cache.upsertAll(List.of(enriched));

        assertThat(storedAttributes()).isEqualTo(RAW.attributes());
        assertThat(full().attributes()).containsEntry("Family", "gate driver").containsEntry("Technology", "GaN")
                .containsEntry("Mounting Style", "SMD/SMT");
    }

    @Test
    void aRowCachedBeforeIsServedAndDerivesAfreshAfterTheMigration() throws IOException {
        // as the previous release stored it: distributor and derived attributes in one map, Family of that time
        Map<String, String> legacy = new LinkedHashMap<>(RAW.attributes());
        legacy.put("Mounting", "SMD");
        legacy.put("Family", "mosfet");
        legacy.put("Polarity", "N-channel");
        insertLegacy(RAW.toBuilder().attributes(legacy).fetchedAt(Instant.now()).build());

        PartLookupResponse before = lookup.lookup(Distributor.MOUSER, "M-" + MPN, false, 1, ResponseDetail.FULL);
        assertThat(before.found()).isTrue();
        assertThat(before.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(before.part().attributes()).containsEntry("Family", "mosfet").containsEntry("Technology", "GaN");

        jdbc.sql(Files.readString(V11, StandardCharsets.UTF_8)).update();

        assertThat(storedAttributes()).doesNotContainKey("Family").containsEntry("Mounting", "SMD")
                .containsEntry("Polarity", "N-channel").containsEntry("Mounting Style", "SMD/SMT");
        assertThat(full().attributes()).containsEntry("Family", "gate driver").containsEntry("Technology", "GaN")
                .containsEntry("Mounting", "SMD");
    }

    @Test
    void theMigrationRemovesExactlyTheDerivedOnlyKeys() throws IOException {
        String sql = Files.readString(V11, StandardCharsets.UTF_8);
        Matcher arrays = Pattern.compile("ARRAY\\[([^\\]]*)]").matcher(sql);
        int found = 0;
        while (arrays.find()) {
            Set<String> keys = new LinkedHashSet<>();
            Pattern.compile("'([^']+)'").matcher(arrays.group(1)).results().forEach(r -> keys.add(r.group(1)));
            assertThat(keys).containsExactlyInAnyOrderElementsOf(ParametricExtractor.DERIVED_ONLY_KEYS);
            found++;
        }
        assertThat(found).isEqualTo(2);
    }

    private ro.alacrity.kina.domain.PartResponse full() {
        PartLookupResponse response = lookup.lookup(Distributor.MOUSER, "M-" + MPN, false, 1, ResponseDetail.FULL);
        assertThat(response.found()).isTrue();
        assertThat(response.cache()).isEqualTo(CacheStatus.HIT);
        return response.part();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> storedAttributes() {
        String json = jdbc.sql("SELECT payload -> 'attributes' FROM cached_parts WHERE part_number = ?")
                .param("M-" + MPN).query(String.class).single();
        return jsonMapper.readValue(json, Map.class);
    }

    private void insertLegacy(Part part) {
        jdbc.sql("""
                        INSERT INTO cached_parts (distributor, part_number, payload, stock_fetched_at,
                                                  metadata_fetched_at, in_stock)
                        VALUES (?, ?, ?::jsonb, ?, ?, true)""")
                .params(part.distributor().name(), part.distributorPartNumber(), jsonMapper.writeValueAsString(part),
                        part.fetchedAt().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC),
                        part.fetchedAt().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC))
                .update();
    }
}
