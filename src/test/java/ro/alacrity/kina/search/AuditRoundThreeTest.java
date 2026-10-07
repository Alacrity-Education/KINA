package ro.alacrity.kina.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.cache.CachedSearch;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.cache.SearchCacheRepository;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.Deadline;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.distributor.DistributorRegistry;
import ro.alacrity.kina.distributor.DistributorSearchPage;
import ro.alacrity.kina.distributor.StockUpdate;
import ro.alacrity.kina.domain.Availability;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.DistributorResult;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PartKey;
import ro.alacrity.kina.domain.PartResponse;
import ro.alacrity.kina.domain.PriceBreak;
import ro.alacrity.kina.domain.ResponseDetail;
import ro.alacrity.kina.domain.SearchRequest;
import ro.alacrity.kina.domain.SearchResponse;
import ro.alacrity.kina.metrics.KinaMetrics;
import ro.alacrity.kina.metrics.Metric;
import ro.alacrity.kina.metrics.MetricsStore;
import ro.alacrity.kina.search.RankingService.RankOptions;
import ro.alacrity.kina.search.RankingService.RankedPart;
import ro.alacrity.kina.search.RankingService.RankedResults;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The third audit round (2026-10-06): ratings never relaxed downward, arrays, relaxation semantics and order, counts,
 * the cache safeguard, unverified constraints, low stock and MOQ, lifecycle versus availability, keyword-only queries,
 * stock age and refresh, and the get_part attributes of can capacitors. The search-level tests run the real
 * deterministic ranker (cross-encoder disabled) behind fake distributors.
 */
class AuditRoundThreeTest {

    static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    final QueryParser parser = new QueryParser();
    final ParametricExtractor extractor = new ParametricExtractor();
    final DeterministicRanker deterministic = new DeterministicRanker(extractor);
    final Map<String, Part> cachedParts = new ConcurrentHashMap<>();
    final Set<String> soldOut = ConcurrentHashMap.newKeySet();
    final Map<String, CachedSearch> cachedSearches = new ConcurrentHashMap<>();
    PartSearchService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------------

    /** A distributor whose results depend on the phrase; null entries are records without ships-now stock. */
    static final class PhraseClient implements DistributorClient {
        final Distributor distributor;
        final Map<String, List<Part>> byPhrase = new HashMap<>();
        final List<String> queries = new CopyOnWriteArrayList<>();
        final List<Integer> offsets = new CopyOnWriteArrayList<>();
        final List<List<String>> refreshed = new CopyOnWriteArrayList<>();
        final Map<String, StockUpdate> stock = new HashMap<>();
        int pageSize = 50;
        /** Thrown by every search call when set (the distributor is down). */
        RuntimeException searchFailure;
        /** Thrown by every stock refresh when set. */
        RuntimeException refreshFailure;

        PhraseClient(Distributor distributor) {
            this.distributor = distributor;
        }

        PhraseClient on(String phrase, Part... parts) {
            byPhrase.put(phrase, java.util.Arrays.asList(parts));
            return this;
        }

        @Override
        public Distributor distributor() {
            return distributor;
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public int maxPageSize() {
            return pageSize;
        }

        @Override
        public DistributorSearchPage search(String query, int offset, int limit) {
            queries.add(query);
            offsets.add(offset);
            if (searchFailure != null) {
                throw searchFailure;
            }
            List<Part> raw = byPhrase.getOrDefault(query, List.of());
            int to = Math.min(raw.size(), offset + limit);
            List<Part> page = offset >= raw.size() ? List.of() : raw.subList(offset, to);
            List<Part> parts = page.stream().filter(p -> p != null).toList();
            return new DistributorSearchPage(parts, raw.size(), to < raw.size(), page.size() - parts.size());
        }

        @Override
        public Optional<Part> getPart(String distributorPartNumber) {
            return Optional.empty();
        }

        @Override
        public Map<String, StockUpdate> refreshStock(List<String> partNumbers, Deadline deadline) {
            refreshed.add(List.copyOf(partNumbers));
            if (refreshFailure != null) {
                throw refreshFailure;
            }
            Map<String, StockUpdate> out = new HashMap<>();
            partNumbers.forEach(n -> {
                if (stock.containsKey(n)) {
                    out.put(n, stock.get(n));
                }
            });
            return out;
        }
    }

    static Part part(Distributor d, String number, String description, String category, int stock,
                     Map<String, String> attributes, Map<String, Object> extra, Instant fetchedAt) {
        return new Part(d, number, "ACME", number, description, category, null, stock, 1, 1,
                List.of(new PriceBreak(1, new BigDecimal("0.10"), d == Distributor.LCSC ? "USD" : "EUR")), null, null,
                "https://example.invalid/" + number, attributes, extra, fetchedAt);
    }

    static Part mlcc(Distributor d, String number, String voltage, String dielectric) {
        return part(d, number, "Capacitor: ceramic; MLCC; 22uF; " + voltage + "; " + dielectric + "; ±10%; SMD; 1206",
                "MLCC SMD capacitors", 1000, Map.of(), Map.of(), NOW);
    }

    PartSearchService service(List<? extends DistributorClient> clients, String... properties) {
        List<String> kv = new ArrayList<>(List.of("kina.ranking.cross-encoder.enabled", "false"));
        kv.addAll(List.of(properties));
        KinaProperties props = RankingFixtures.properties(kv.toArray(String[]::new));
        PartCacheRepository partCache = mock(PartCacheRepository.class);
        doAnswer(inv -> {
            Collection<Part> parts = inv.getArgument(0);
            parts.forEach(p -> cachedParts.put(p.key(), p));
            return null;
        }).when(partCache).upsertAll(anyCollection());
        doAnswer(inv -> {
            cachedParts.remove(PartKey.of(inv.getArgument(0), inv.getArgument(1)));
            return null;
        }).when(partCache).delete(any(), anyString());
        doAnswer(inv -> {
            Collection<Part> parts = inv.getArgument(0);
            parts.forEach(p -> cachedParts.put(p.key(), p));
            return null;
        }).when(partCache).updateStock(anyCollection());
        doAnswer(inv -> {
            soldOut.add(PartKey.of(inv.getArgument(0), inv.getArgument(1)));
            return null;
        }).when(partCache).markSoldOut(any(), anyString());
        when(partCache.findInStock(any(), anyCollection())).thenAnswer(inv -> {
            Distributor d = inv.getArgument(0);
            Collection<String> numbers = inv.getArgument(1);
            Map<String, Part> out = new HashMap<>();
            for (String n : numbers) {
                Part p = cachedParts.get(PartKey.of(d, n));
                if (p != null && !soldOut.contains(p.key())) {
                    out.put(n, p);
                }
            }
            return out;
        });
        when(partCache.findFresh(any(), anyCollection(), any())).thenAnswer(inv -> {
            Distributor d = inv.getArgument(0);
            Collection<String> numbers = inv.getArgument(1);
            Instant since = inv.getArgument(2);
            Map<String, Part> out = new HashMap<>();
            for (String n : numbers) {
                Part p = cachedParts.get(PartKey.of(d, n));
                if (p != null && !p.fetchedAt().isBefore(since)) {
                    out.put(n, p);
                }
            }
            return out;
        });
        SearchCacheRepository searchCache = mock(SearchCacheRepository.class);
        when(searchCache.find(any(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(cachedSearches.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        doAnswer(inv -> {
            CachedSearch s = inv.getArgument(0);
            cachedSearches.put(s.distributor() + "|" + s.queryKey(), s);
            return null;
        }).when(searchCache).upsert(any());
        doAnswer(inv -> {
            cachedSearches.remove(inv.getArgument(0) + "|" + inv.getArgument(1));
            return null;
        }).when(searchCache).delete(any(), anyString());
        RankingService ranking = new RankingService(props, deterministic, new RankingServiceTest.FakeRanker(),
                () -> RankingServiceTest.READY, new RankingScoreCache(props.ranking().scoreCacheTtl()));
        service = new PartSearchService(props, new DistributorRegistry(List.copyOf(clients)), parser, extractor,
                ranking, partCache, searchCache, Clock.fixed(NOW, ZoneOffset.UTC));
        return service;
    }

    RankingService ranking(RankingServiceTest.FakeRanker ce, String... properties) {
        KinaProperties props = RankingFixtures.properties(properties);
        return new RankingService(props, deterministic, ce, () -> RankingServiceTest.READY,
                new RankingScoreCache(props.ranking().scoreCacheTtl()));
    }

    static Map<Distributor, List<Part>> fetched(Distributor d, Part... parts) {
        Map<Distributor, List<Part>> m = new EnumMap<>(Distributor.class);
        m.put(d, List.of(parts));
        return m;
    }

    static List<String> numbers(List<RankedPart> ranked) {
        return ranked.stream().map(r -> r.part().distributorPartNumber()).toList();
    }

    static DistributorResult result(SearchResponse response, Distributor d) {
        return response.distributors().stream().filter(r -> r.distributor() == d).findFirst().orElseThrow();
    }

    static SearchRequest request(String query, int maxResults, boolean allowBelowSpec, Distributor... distributors) {
        return new SearchRequest(query, maxResults, Set.of(distributors), false, 1, ResponseDetail.COMPACT,
                allowBelowSpec);
    }

    // ---- 1. ratings are never relaxed downward --------------------------------------------------------------------

    @Test
    void belowSpecPartsAreExcludedByDefaultAndOrderedByClosenessWhenAllowed() {
        ParsedQuery q = parser.parse("22uF X7R 1206 25V MLCC");
        Part v10 = mlcc(Distributor.TME, "V10", "10V", "X7R");
        Part v16 = mlcc(Distributor.TME, "V16", "16V", "X7R");
        Part v63 = mlcc(Distributor.TME, "V6.3", "6.3V", "X7R");
        Part x5r = mlcc(Distributor.TME, "X5R25", "25V", "X5R");
        Part ok = mlcc(Distributor.TME, "X7R50", "50V", "X7R");
        // the model prefers the 6.3 V part: it must never be interleaved with the parts that meet the request
        RankingServiceTest.FakeRanker ce = new RankingServiceTest.FakeRanker();
        ce.scores.put(v63.key(), 9.0);
        ce.scores.put(v10.key(), 5.0);
        ce.scores.put(v16.key(), 1.0);
        RankingService ranking = ranking(ce);

        RankedResults strict = ranking.rank(q, fetched(Distributor.TME, v10, v16, v63, x5r, ok), Duration.ofSeconds(5));
        assertThat(numbers(strict.byDistributor().get(Distributor.TME))).containsExactly("X7R50", "X5R25");
        assertThat(strict.excludedBelowSpecBy(Distributor.TME)).isEqualTo(3);
        assertThat(strict.excludedBy(Distributor.TME)).isZero();
        assertThat(ranking.exclusion(q, v10)).isEqualTo(RankingService.Exclusion.BELOW_SPEC);
        assertThat(ranking.exclusion(q, ok)).isNull();

        RankedResults allowed = ranking.rank(q, fetched(Distributor.TME, v10, v16, v63, x5r, ok), Duration.ofSeconds(5),
                new RankOptions(1, true));
        List<RankedPart> parts = allowed.byDistributor().get(Distributor.TME);
        // 16 V is closest to 25 V, then 10 V, then 6.3 V, whatever the model says
        assertThat(numbers(parts)).containsExactly("X7R50", "X5R25", "V16", "V10", "V6.3");
        assertThat(parts).extracting(RankedPart::belowSpec).containsExactly(false, false, true, true, true);
        assertThat(parts.get(2).mismatches()).containsExactly("voltage: 16V below 25V");
        assertThat(allowed.excludedBelowSpecBy(Distributor.TME)).isZero();
        assertThat(parts).isSortedAccordingTo((a, b) -> Double.compare(b.score(), a.score()));
    }

    @Test
    void everyStatedRatingIsAHardLimitAndDcrAMaximum() {
        ParsedQuery q = parser.parse("power inductor 4.7uH 3A DCR < 50mOhm 125°C");
        Part ok = part(Distributor.MOUSER, "OK", "Power Inductors - SMD 4.7uH 3.5A DCR=30mOhms", "Power Inductors",
                1000, Map.of("Maximum Operating Temperature", "+ 125 C"), Map.of(), NOW);
        Part dcr = part(Distributor.MOUSER, "DCR", "Power Inductors - SMD 4.7uH 3.5A DCR=80mOhms", "Power Inductors",
                1000, Map.of("Maximum Operating Temperature", "+ 125 C"), Map.of(), NOW);
        Part hot = part(Distributor.MOUSER, "HOT", "Power Inductors - SMD 4.7uH 3.5A DCR=30mOhms", "Power Inductors",
                1000, Map.of("Maximum Operating Temperature", "+ 85 C"), Map.of(), NOW);
        assertThat(deterministic.assess(q, ok).isBelowSpec()).isFalse();
        assertThat(deterministic.assess(q, dcr).belowSpec()).containsExactly("dcr");
        assertThat(deterministic.assess(q, hot).belowSpec()).containsExactly("temperature");
        // a regulator voltage must match but is not a minimum: a mismatch, never below spec
        ParsedQuery ldo = parser.parse("LDO 3.3V SOT-23-5");
        Part ldo5 = part(Distributor.MOUSER, "LDO5", "LDO Voltage Regulators 5V 300mA SOT-23-5", "LDO Voltage Regulators",
                1000, Map.of(), Map.of(), NOW);
        assertThat(deterministic.assess(ldo, ldo5).isBelowSpec()).isFalse();
        assertThat(deterministic.assess(ldo, ldo5).mismatches()).contains("voltage: 5V instead of 3.3V");
    }

    @Test
    void tmeRelaxesTheDielectricToFindRatedPartsAndPagesOnFirst() {
        // live 2026-10-06: TME holds only 6.3-16 V X7R 22uF 1206 parts but 25 V X5R ones
        PhraseClient tme = new PhraseClient(Distributor.TME)
                .on("22uF X7R 1206 MLCC", mlcc(Distributor.TME, "X7R10", "10V", "X7R"),
                        mlcc(Distributor.TME, "X7R16", "16V", "X7R"))
                .on("MLCC 22uF 1206", mlcc(Distributor.TME, "X7R10", "10V", "X7R"),
                        mlcc(Distributor.TME, "GRM31CR61E226KE15L", "25V", "X5R"),
                        mlcc(Distributor.TME, "X5R35", "35V", "X5R"));
        service(List.of(tme));

        DistributorResult t = result(service.search(request("22uF X7R 1206 25V MLCC", 5, false, Distributor.TME)),
                Distributor.TME);

        assertThat(tme.queries).containsExactly("22uF X7R 1206 MLCC", "MLCC 22uF 1206");
        assertThat(t.fallbackQuery()).isEqualTo("MLCC 22uF 1206");
        assertThat(t.constraintsRelaxed()).containsExactly("dielectric");
        assertThat(t.parts()).extracting(PartResponse::partNumber).containsExactly("GRM31CR61E226KE15L", "X5R35");
        assertThat(t.parts().getFirst().mismatches()).containsExactly("dielectric: X5R instead of X7R");
        assertThat(t.excludedBelowSpec()).isEqualTo(1);
        assertThat(t.fetched()).isEqualTo(3);

        // with allow_below_spec the 10 V part of the relaxed search is returned too, flagged, after the others
        DistributorResult allowed = result(service.search(request("22uF X7R 1206 25V MLCC", 5, true,
                Distributor.TME)), Distributor.TME);
        assertThat(allowed.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(allowed.parts()).extracting(PartResponse::partNumber)
                .containsExactly("GRM31CR61E226KE15L", "X5R35", "X7R10");
        assertThat(allowed.parts().getLast().belowSpec()).isTrue();
        assertThat(allowed.parts().getFirst().belowSpec()).isNull();
        assertThat(allowed.excludedBelowSpec()).isZero();
    }

    @Test
    void onlyTheLoosenedConstraintsTheReturnedPartsMissAreReported() {
        // the dielectric step finds nothing; dielectric + tolerance finds an X7R part with a looser tolerance
        PhraseClient tme = new PhraseClient(Distributor.TME)
                .on("10uF X7R 1210 5% MLCC", part(Distributor.TME, "LOW", "MLCC 10uF 50V X7R 1210 5%", "MLCC", 1000,
                        Map.of(), Map.of(), NOW))
                .on("MLCC 10uF 1210", part(Distributor.TME, "GRM32ER72A106KA35L", "MLCC 10uF 100V X7R 1210 10%",
                        "MLCC", 1000, Map.of(), Map.of(), NOW));
        service(List.of(tme));

        DistributorResult t = result(service.search(request("10uF 100V X7R 1210 5% MLCC", 5, false,
                Distributor.TME)), Distributor.TME);

        assertThat(tme.queries).containsExactly("10uF X7R 1210 5% MLCC", "MLCC 10uF 1210 5%", "MLCC 10uF 1210");
        assertThat(t.fallbackQuery()).isEqualTo("MLCC 10uF 1210");
        // the step loosened dielectric and tolerance; the returned part is X7R, so only the tolerance is reported
        assertThat(t.constraintsRelaxed()).containsExactly("tolerance");
        assertThat(cachedSearches.get(Distributor.TME + "|" + QueryParser.normalizeKey("10uF 100V X7R 1210 5% MLCC"))
                .constraintsRelaxed()).containsExactly("dielectric", "tolerance");
        assertThat(t.parts().getFirst().mismatches()).containsExactly("tolerance: 10% instead of 5%");
    }

    @Test
    void thePackageOfACapacitorIsNeverRelaxed() {
        // live 2026-10-06: TME has no in-stock 10uF 100V 1210 part; "MLCC 10uF" would find a 100 V X7R part in 2220
        PhraseClient tme = new PhraseClient(Distributor.TME)
                .on("10uF X7R 1210 MLCC", part(Distributor.TME, "LOW", "MLCC 10uF 50V X7R 1210", "MLCC", 1000,
                        Map.of(), Map.of(), NOW))
                .on("MLCC 10uF", part(Distributor.TME, "22201C106KAT2A", "MLCC 10uF 100V X7R 2220", "MLCC", 1000,
                        Map.of(), Map.of(), NOW));
        service(List.of(tme));

        SearchResponse response = service.search(request("10uF 100V X7R 1210 MLCC", 5, false, Distributor.TME));
        DistributorResult t = result(response, Distributor.TME);

        // no rung drops the package: the 2220 part is never fetched, nothing is substituted
        assertThat(tme.queries).containsExactly("10uF X7R 1210 MLCC", "MLCC 10uF 1210");
        assertThat(t.parts()).isEmpty();
        assertThat(t.exactMatches()).isZero();
        assertThat(t.excludedBelowSpec()).isEqualTo(1);
        assertThat(t.constraintsRelaxed()).isEmpty();
        assertThat(t.hint()).startsWith("No in-stock 10uF capacitor in package 1210 at TME;")
                .contains("never relaxed").contains("allow_below_spec").contains("No substitutes");
        assertThat(response.hint()).isEqualTo(t.hint());
    }

    @Test
    void aWrongPrimaryValueExcludesThePart() {
        // live 2026-10-06: TME "ferrite 120ohm" returned ferrite cores specified at 25 MHz, match 0.0
        Part core = part(Distributor.TME, "RRC-16-9-28-M2", "Ferrite: core; 120Ω @25MHz; 1206", "Ferrites", 90,
                Map.of("Impedance at 25MHz", "120Ω"), Map.of(), NOW);
        Part weak = part(Distributor.TME, "BEAD3A", "Ferrite: bead; 120Ω; SMD; 3A; 1206", "Ferrite - beads", 1000,
                Map.of("Impedance at 100MHz", "120Ω", "Operating current", "3A"), Map.of(), NOW);
        PhraseClient tme = new PhraseClient(Distributor.TME)
                .on("120 ohm 100MHz 1206 ferrite bead", weak)
                .on("ferrite 120ohm 1206", core);
        service(List.of(tme));
        ParsedQuery q = parser.parse("120 ohm 100MHz 1206 ferrite bead 6A");
        assertThat(service.meetsRequest(q, core)).isFalse();

        DistributorResult t = result(service.search(request("120 ohm 100MHz 1206 ferrite bead 6A", 5, false,
                Distributor.TME)), Distributor.TME);

        // nothing meets the request on any rung: the least relaxed result is kept, its 3 A bead is below spec; the
        // impedance at 25 MHz of the core is a different primary value: excluded, never returned
        assertThat(tme.queries).contains("ferrite 120ohm 1206");
        assertThat(t.fallbackQuery()).isNull();
        assertThat(t.parts()).isEmpty();
        assertThat(t.excludedBelowSpec()).isEqualTo(1);
        assertThat(service.policy().check(q, extractor.features(core)).conflicts()).containsExactly("impedance");
    }

    @Test
    void tmeReadsFurtherPagesBeforeRelaxingAnything() {
        List<Part> raw = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            raw.add(mlcc(Distributor.TME, "LOW" + i, "10V", "X7R"));
        }
        raw.add(mlcc(Distributor.TME, "RATED", "25V", "X7R"));
        PhraseClient tme = new PhraseClient(Distributor.TME).on("22uF X7R 1206 MLCC", raw.toArray(Part[]::new));
        tme.pageSize = 5;
        service(List.of(tme), "kina.search.candidate-window", "5");

        DistributorResult t = result(service.search(request("22uF X7R 1206 25V MLCC", 5, false, Distributor.TME)),
                Distributor.TME);

        // the window (5) was full after the first page, but nothing met the 25 V minimum: page 2 before relaxing
        assertThat(tme.offsets).containsExactly(0, 5);
        assertThat(tme.queries).containsOnly("22uF X7R 1206 MLCC");
        assertThat(t.fallbackQuery()).isNull();
        assertThat(t.constraintsRelaxed()).isEmpty();
        assertThat(t.parts()).extracting(PartResponse::partNumber).containsExactly("RATED");
        assertThat(t.exactMatches()).isEqualTo(1);
    }

    // ---- 2. arrays ----------------------------------------------------------------------------------------------------

    @Test
    void arraysAndNetworksAreExcludedUnlessRequested() {
        Part array = RankingFixtures.tme("BLA31BD121SN4D", "MURATA",
                "Ferrite: bead; array; Imp.@ 100MHz: 120Ω; SMD; 0.15A; 1206; R: 0.3Ω", "Ferrite - beads", "1206",
                RankingFixtures.attrs("Kind of ferrite", "array", "Type of ferrite", "bead", "Impedance at 100MHz",
                        "120Ω", "Operating current", "6A"));
        Part single = RankingFixtures.tme("BLM31KN121SN1L", "MURATA", "Ferrite: bead; 120Ω; SMD; 6A; 1206",
                "Ferrite - beads", "1206", RankingFixtures.attrs("Type of ferrite", "bead", "Impedance at 100MHz",
                        "120Ω", "Operating current", "6A"));
        Part network = RankingFixtures.lcsc("C29718", "UNI-ROYAL", "4D03WGJ0103T5E", "62.5mW ±5% 10kΩ 0603x4",
                "Resistors / Resistor Networks, Arrays", "0603x4", Map.of());
        assertThat(extractor.extract(array)).containsEntry("Elements", "array");
        assertThat(extractor.extract(single)).doesNotContainKey("Elements");
        assertThat(extractor.extract(network)).containsEntry("Elements", "4");
        assertThat(extractor.extract(extractor.enrich(array))).isEqualTo(extractor.extract(array));
        assertThat(parser.parse("120 ohm 100MHz 1206 ferrite bead 6A").elements()).isNull();
        assertThat(parser.parse("120 ohm 1206 ferrite bead array 4 lines").elements()).isEqualTo(4);
        assertThat(parser.parse("10k resistor network 0603").elements()).isEqualTo(ParsedQuery.ANY_ELEMENTS);

        RankingService ranking = ranking(new RankingServiceTest.FakeRanker(), "kina.ranking.cross-encoder.enabled",
                "false");
        RankedResults single6A = ranking.rank(parser.parse("120 ohm 100MHz 1206 ferrite bead 6A"),
                fetched(Distributor.TME, array, single), null);
        assertThat(numbers(single6A.byDistributor().get(Distributor.TME))).containsExactly("BLM31KN121SN1L");
        assertThat(single6A.excludedBy(Distributor.TME)).isEqualTo(1);
        RankedResults wanted = ranking.rank(parser.parse("120 ohm 100MHz 1206 ferrite bead array"),
                fetched(Distributor.TME, array, single), null);
        assertThat(numbers(wanted.byDistributor().get(Distributor.TME))).containsExactly("BLA31BD121SN4D",
                "BLM31KN121SN1L");
        assertThat(wanted.byDistributor().get(Distributor.TME).getLast().mismatches())
                .contains("elements: single instead of array");
        // the strict constraint can be switched off: the array stays, with a mismatch
        RankedResults lenient = ranking(new RankingServiceTest.FakeRanker(), "kina.ranking.cross-encoder.enabled",
                "false", "kina.search.strict-constraints", "mounting").rank(
                parser.parse("120 ohm 100MHz 1206 ferrite bead"), fetched(Distributor.TME, array, single), null);
        assertThat(lenient.byDistributor().get(Distributor.TME)).hasSize(2);
        assertThat(lenient.byDistributor().get(Distributor.TME).getLast().mismatches())
                .contains("elements: array instead of single");
    }

    // ---- 3. constraints_relaxed versus query_terms_dropped -------------------------------------------------------------

    @Test
    void ratingsLeftOutOfThePhraseAreNotRelaxedConstraints() {
        PhraseClient mouser = new PhraseClient(Distributor.MOUSER).on("10uF X7R 1210 MLCC",
                part(Distributor.MOUSER, "M1", "Multilayer Ceramic Capacitors MLCC - SMD/SMT 1210 100V 10uF X7R 10%",
                        "MLCC", 500, Map.of(), Map.of(), NOW));
        service(List.of(mouser));

        DistributorResult m = result(service.search(request("10uF 100V X7R 1210 MLCC", 5, false,
                Distributor.MOUSER)), Distributor.MOUSER);

        assertThat(m.queryTermsDropped()).containsExactly("voltage");
        assertThat(m.constraintsRelaxed()).isEmpty();
        assertThat(m.exactMatches()).isEqualTo(1);
    }

    @Test
    void lcscReportsOnlyTheDroppedConstraintsTheReturnedPartsMiss() {
        Part x5r = RankingFixtures.lcsc("C1", "Samsung", "CL31A226KAHNNNE", "25V 22uF X5R ±10% 1206",
                "Capacitors / Multilayer Ceramic Capacitors MLCC - SMD/SMT", "1206", Map.of());
        RankedPart missing = new RankedPart(x5r, 1.0, 0.8, List.of("dielectric: X5R instead of X7R"), List.of(),
                false);
        // dropped by the database search one at a time: only the dielectric is really missed by the parts
        assertThat(ResponseAssembler.actuallyRelaxed(List.of("voltage", "dielectric", "package"), List.of(missing)))
                .containsExactly("dielectric");
        assertThat(ResponseAssembler.actuallyRelaxed(List.of(), List.of(missing))).isEmpty();
    }

    // ---- 4. counts -----------------------------------------------------------------------------------------------------

    @Test
    void countsAddUpOnASyntheticPage() {
        String query = "100uF 16V polymer aluminium capacitor SMD";
        List<Part> raw = new ArrayList<>();
        raw.add(null);   // two records without ships-now stock
        raw.add(null);
        for (int i = 0; i < 4; i++) {   // tantalum polymer: contradicts the stated technology
            raw.add(part(Distributor.TME, "TA" + i, "Capacitor: tantalum-polymer; 100uF; 16V; SMD",
                    "Tantalum capacitors", 1000, Map.of(), Map.of(), NOW));
        }
        for (int i = 0; i < 3; i++) {   // 10 V: below the 16 V minimum
            raw.add(part(Distributor.TME, "LOW" + i, "Capacitor: polymer; aluminium; 100uF; 10V; SMD",
                    "Polymer capacitors", 1000, Map.of(), Map.of(), NOW));
        }
        for (int i = 0; i < 3; i++) {
            raw.add(part(Distributor.TME, "OK" + i, "Capacitor: polymer; aluminium; 100uF; 16V; SMD",
                    "Polymer capacitors", 1000, Map.of(), Map.of(), NOW));
        }
        PhraseClient tme = new PhraseClient(Distributor.TME).on("100uF polymer capacitor SMD", raw.toArray(Part[]::new));
        service(List.of(tme));

        for (int maxResults : new int[]{10, 2}) {
            DistributorResult t = result(service.search(request(query, maxResults, false, Distributor.TME)),
                    Distributor.TME);
            assertThat(t.totalResults()).isEqualTo(12);
            assertThat(t.outOfStockMatches()).isEqualTo(2);            // not part of fetched
            assertThat(t.fetched()).isEqualTo(10);                      // every in-stock part received
            assertThat(t.excludedByConstraints()).isEqualTo(4);         // a subset of fetched
            assertThat(t.excludedBelowSpec()).isEqualTo(3);             // a subset of fetched
            assertThat(t.returned()).isEqualTo(Math.min(maxResults, 3))
                    .isLessThanOrEqualTo(t.fetched() - t.excludedByConstraints() - t.excludedBelowSpec());
            assertThat(t.parts()).hasSize(t.returned());
        }
    }

    // ---- 5. cache safeguard ------------------------------------------------------------------------------------------

    @Test
    void aCachedSearchWhosePartsAreAllExcludedIsAMissAndNeverStored() {
        String query = "22uF X7R 1206 25V MLCC";
        String key = QueryParser.normalizeKey(query);
        Part low = mlcc(Distributor.MOUSER, "LOW", "10V", "X7R");
        Part rated = mlcc(Distributor.MOUSER, "RATED", "25V", "X7R");
        // a fresh hit (written by an older version) that would return nothing
        cachedParts.put(low.key(), low);
        cachedSearches.put(Distributor.MOUSER + "|" + key, new CachedSearch(Distributor.MOUSER, key, 1,
                List.of("LOW"), true, NOW.minus(Duration.ofHours(1)), 1, null, 0));
        PhraseClient mouser = new PhraseClient(Distributor.MOUSER).on("22uF X7R 1206 MLCC", low, rated);
        service(List.of(mouser));

        DistributorResult m = result(service.search(request(query, 5, false, Distributor.MOUSER)),
                Distributor.MOUSER);

        assertThat(m.cache()).isEqualTo(CacheStatus.MISS);
        assertThat(m.parts()).extracting(PartResponse::partNumber).containsExactly("RATED");
        assertThat(cachedSearches.get(Distributor.MOUSER + "|" + key).partNumbers()).containsExactly("LOW", "RATED");

        // with allow_below_spec the same hit is served (the 10 V part is returnable, flagged)
        cachedSearches.put(Distributor.MOUSER + "|" + key, new CachedSearch(Distributor.MOUSER, key, 1,
                List.of("LOW"), true, NOW.minus(Duration.ofHours(1)), 1, null, 0));
        DistributorResult allowed = result(service.search(request(query, 5, true, Distributor.MOUSER)),
                Distributor.MOUSER);
        assertThat(allowed.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(allowed.parts().getFirst().belowSpec()).isTrue();

        // a live search whose parts all fail the request is not stored as a search (only its parts are cached)
        PhraseClient only = new PhraseClient(Distributor.TME).on("22uF X7R 1206 MLCC",
                mlcc(Distributor.TME, "T10", "10V", "X7R"));
        service.shutdown();
        service(List.of(only));
        DistributorResult t = result(service.search(request(query, 5, false, Distributor.TME)), Distributor.TME);
        assertThat(t.parts()).isEmpty();
        assertThat(t.excludedBelowSpec()).isEqualTo(1);
        assertThat(cachedSearches).doesNotContainKey(Distributor.TME + "|" + key);
        assertThat(cachedParts).containsKey(PartKey.of(Distributor.TME, "T10"));
    }

    // ---- 7. unverified constraints ------------------------------------------------------------------------------------

    @Test
    void unverifiedConstraintsAreListedLeftOutOfMatchAndRankBelowVerifiedParts() {
        ParsedQuery q = parser.parse("inductor 2.2uH 8A SMD");
        Part jrpi = RankingFixtures.tme("JRPI0804M-2R2M", "JANTEK", "Inductor: wire; SMD; 2.2uH; ±20%; M; 8.8x8.4x3.8mm",
                "Inductors", null, RankingFixtures.attrs("Inductance", "2.2µH", "Type of inductor", "wire",
                        "Body dimensions", "8.8x8.4x3.8mm"));
        Part verified = RankingFixtures.tme("HCMA1104-2R2-R", "EATON", "Inductor: wire; SMD; 2.2uH; 11A; R: 7mΩ",
                "Inductors", null, RankingFixtures.attrs("Inductance", "2.2µH", "Operating current", "11A"));
        RankingServiceTest.FakeRanker ce = new RankingServiceTest.FakeRanker();
        ce.scores.put(jrpi.key(), 10.0);   // the model prefers the unverified part
        RankedResults r = ranking(ce).rank(q, fetched(Distributor.TME, jrpi, verified), Duration.ofSeconds(5));

        List<RankedPart> parts = r.byDistributor().get(Distributor.TME);
        assertThat(numbers(parts)).containsExactly("HCMA1104-2R2-R", "JRPI0804M-2R2M");
        RankedPart unverified = parts.getLast();
        assertThat(unverified.unverified()).containsExactly("current");
        assertThat(unverified.match()).isEqualTo(1.0);   // only verified constraints count
        assertThat(unverified.exact()).isFalse();
        assertThat(parts.getFirst().exact()).isTrue();
        assertThat(extractor.extract(jrpi)).containsEntry("Dimensions", "8.8 x 8.4 x 3.8mm");
    }

    // ---- 8. low stock and MOQ; 9. lifecycle versus availability ----------------------------------------------------------

    @Test
    void lowStockAndLargeMinimumOrdersLoseScoreEvenForOnePiece() {
        ParsedQuery q = parser.parse("22uF X7R 1206 25V MLCC");
        Part ok = mlcc(Distributor.TME, "OK", "25V", "X7R");
        Part twoPieces = mlcc(Distributor.TME, "TWO", "25V", "X7R").toBuilder().stock(2).build();
        Part reel = mlcc(Distributor.TME, "CS3216X7R226K250NRI", "25V", "X7R").toBuilder()
                .minimumOrderQuantity(2000).build();
        RankingServiceTest.FakeRanker ce = new RankingServiceTest.FakeRanker();
        ce.scores.put(twoPieces.key(), 5.0);
        ce.scores.put(reel.key(), 5.0);
        RankingService ranking = ranking(ce);
        List<RankedPart> parts = ranking.rank(q, fetched(Distributor.TME, twoPieces, reel, ok), Duration.ofSeconds(5))
                .byDistributor().get(Distributor.TME);

        assertThat(numbers(parts).getFirst()).isEqualTo("OK");
        // the blended score itself carries the penalties: 0.3 low stock, 0.3 for a 2000x MOQ
        Map<String, Double> score = new HashMap<>();
        parts.forEach(p -> score.put(p.part().distributorPartNumber(), p.score()));
        assertThat(score.get("TWO")).isLessThan(score.get("OK"));
        assertThat(score.get("CS3216X7R226K250NRI")).isLessThan(score.get("OK"));
        assertThat(ranking.penalty(twoPieces, 1)).isCloseTo(0.3, within(1e-9));
        assertThat(ranking.penalty(reel, 1)).isCloseTo(0.3, within(1e-9));
        // an MOQ of 10 for one piece costs a third of it
        assertThat(ranking.penalty(ok.toBuilder().minimumOrderQuantity(10).build(), 1)).isCloseTo(0.1, within(1e-9));
        assertThat(ranking.penalty(ok, 1)).isZero();
        assertThat(Availability.of(twoPieces, 1, false).status()).isEqualTo(Availability.LOW_STOCK);
        assertThat(Availability.of(ok, 1, false).status()).isEqualTo(Availability.IN_STOCK);
        // twice the quantity is still low
        assertThat(Availability.of(ok.toBuilder().stock(15).build(), 10, false).status())
                .isEqualTo(Availability.LOW_STOCK);
        // the threshold is configurable
        assertThat(ranking(ce, "kina.search.low-stock-threshold", "1").penalty(twoPieces, 1)).isZero();
    }

    @Test
    void supplyConstrainedAndLastTimeBuyLowerTheFinalScoreButNotTheAvailability() {
        ParsedQuery q = parser.parse("100nF X7R 0603 50V MLCC");
        Part active = RankingFixtures.part(Distributor.TME, "ACTIVE", "ACME", "ACTIVE", "MLCC 100nF 50V X7R 0603",
                null, "0603", 1000, "0.01", Map.of(), Map.of("product_status", List.of()));
        Part hardly = RankingFixtures.part(Distributor.TME, "HARD", "ACME", "HARD", "MLCC 100nF 50V X7R 0603",
                null, "0603", 1000, "0.01", Map.of(), Map.of("product_status", List.of("HARDLY_AVAILABLE")));
        Part lastBuy = RankingFixtures.part(Distributor.TME, "LTB", "ACME", "LTB", "MLCC 100nF 50V X7R 0603",
                null, "0603", 1000, "0.01", Map.of(), Map.of("product_status", List.of("AVAILABLE_WHILE_STOCKS_LAST")));
        RankingServiceTest.FakeRanker ce = new RankingServiceTest.FakeRanker();
        ce.scores.put(lastBuy.key(), 3.0);   // the model prefers the last-time-buy part
        ce.scores.put(hardly.key(), 2.0);
        ce.scores.put(active.key(), 1.0);
        List<RankedPart> parts = ranking(ce).rank(q, fetched(Distributor.TME, lastBuy, hardly, active),
                Duration.ofSeconds(5)).byDistributor().get(Distributor.TME);
        Map<String, Double> score = new HashMap<>();
        parts.forEach(p -> score.put(p.part().distributorPartNumber(), p.score()));
        // det ranks are equal before the penalties; the blend minus 0.05 / 0.1 must lower the flagged parts
        assertThat(score.get("HARD")).isLessThan(0.5 * 1.0 + 0.5 * 0.5);
        assertThat(score.get("LTB")).isLessThan(0.5 * 0.0 + 0.5 * 1.0 + 1e-9);
        assertThat(Availability.of(hardly, 1, false).status()).isEqualTo(Availability.IN_STOCK);
        assertThat(Availability.lifecycleOf(hardly)).isEqualTo(Availability.SUPPLY_CONSTRAINED);
        assertThat(Availability.of(lastBuy, 1, false).status()).isEqualTo(Availability.LAST_UNITS);
        assertThat(Availability.lifecycleOf(lastBuy)).isEqualTo(Availability.LAST_TIME_BUY);
        // configurable: without the penalty the supply-constrained part keeps its blended score
        List<RankedPart> free = ranking(ce, "kina.search.lifecycle.supply-constrained-penalty", "0",
                "kina.search.lifecycle.last-time-buy-penalty", "0").rank(q, fetched(Distributor.TME, lastBuy, hardly,
                active), Duration.ofSeconds(5)).byDistributor().get(Distributor.TME);
        assertThat(numbers(free).getFirst()).isEqualTo("LTB");
    }

    // ---- 10. keyword-only queries ------------------------------------------------------------------------------------

    @Test
    void aQueryWithoutParametricUnderstandingHasNoMatchGrade() {
        PhraseClient lcsc = new PhraseClient(Distributor.LCSC).on("asdfqwerty zz9",
                part(Distributor.LCSC, "C1", "ZX-PH2.0-ZZ9P wire to board", "Connectors", 100, Map.of(), Map.of(), NOW));
        service(List.of(lcsc));

        SearchResponse response = service.search(request("asdfqwerty zz9", 5, false, Distributor.LCSC));

        assertThat(response.queryUnderstood()).isFalse();
        assertThat(response.hint()).contains("keywords only");
        DistributorResult l = result(response, Distributor.LCSC);
        assertThat(l.exactMatches()).isNull();
        assertThat(l.parts()).isNotEmpty().allSatisfy(p -> assertThat(p.match()).isNull());
        assertThat(parser.parse("10uF X7R 0805").understood()).isTrue();
        assertThat(parser.parse("SMD").understood()).isTrue();
        assertThat(response.currencies()).containsExactly("USD");
    }

    // ---- 11. stock age and refresh -------------------------------------------------------------------------------------

    @Test
    void staleCachedStockOfTheReturnedPartsIsRefreshedAndSoldOutPartsDropOut() {
        String query = "10uF X7R 0805 MLCC";
        String key = QueryParser.normalizeKey(query);
        Instant old = NOW.minus(Duration.ofDays(2));
        List<String> numbers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Part p = part(Distributor.MOUSER, "M" + i, "Multilayer Ceramic Capacitors MLCC 10uF 25V X7R 0805",
                    "MLCC", 1000 - i, Map.of(), Map.of(), old);
            cachedParts.put(p.key(), p);
            numbers.add(p.distributorPartNumber());
        }
        cachedSearches.put(Distributor.MOUSER + "|" + key, new CachedSearch(Distributor.MOUSER, key, 4, numbers,
                true, NOW.minus(Duration.ofDays(2)), 4, null, 0, List.of()));
        PhraseClient mouser = new PhraseClient(Distributor.MOUSER);
        mouser.stock.put("M0", new StockUpdate(0, List.of()));   // sold out since
        mouser.stock.put("M1", new StockUpdate(42, List.of(new PriceBreak(1, new BigDecimal("0.20"), "EUR"))));
        mouser.stock.put("M2", new StockUpdate(7, List.of()));
        service(List.of(mouser));

        DistributorResult m = result(service.search(request(query, 2, false, Distributor.MOUSER)),
                Distributor.MOUSER);

        assertThat(m.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(mouser.queries).isEmpty();                       // no keyword search
        // only the parts about to be returned are refreshed; M0 sold out, so M2 moves up and is refreshed too
        assertThat(mouser.refreshed).containsExactly(List.of("M0", "M1"), List.of("M2"));
        assertThat(m.parts()).extracting(PartResponse::partNumber).containsExactly("M1", "M2");
        assertThat(m.parts().getFirst().stock()).isEqualTo(42);
        assertThat(m.parts().getFirst().stockAsOf()).isEqualTo(NOW);
        assertThat(m.parts().getFirst().prices().getFirst().unitPrice()).isEqualByComparingTo("0.20");
        assertThat(m.parts().get(1).availability().status()).isEqualTo(Availability.LOW_STOCK);
        // the sold-out part keeps its metadata in the cache, marked sold out (never served)
        assertThat(cachedParts).containsKey(PartKey.of(Distributor.MOUSER, "M0"));
        assertThat(soldOut).containsExactly(PartKey.of(Distributor.MOUSER, "M0"));
        assertThat(m.parts()).allSatisfy(p -> assertThat(p.stale()).isNull());
        assertThat(cachedParts.get(PartKey.of(Distributor.MOUSER, "M1")).fetchedAt()).isEqualTo(NOW);
        assertThat(cachedParts.get(PartKey.of(Distributor.MOUSER, "M3")).fetchedAt()).isEqualTo(old);
    }

    @Test
    void stockRefreshOutcomesAndFetchedPagesReachTheMetrics() {
        String query = "10uF X7R 0805 MLCC";
        String key = QueryParser.normalizeKey(query);
        Instant old = NOW.minus(Duration.ofDays(2));
        List<String> numbers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Part p = part(Distributor.MOUSER, "M" + i, "Multilayer Ceramic Capacitors MLCC 10uF 25V X7R 0805",
                    "MLCC", 1000 - i, Map.of(), Map.of(), old);
            cachedParts.put(p.key(), p);
            numbers.add(p.distributorPartNumber());
        }
        cachedSearches.put(Distributor.MOUSER + "|" + key, new CachedSearch(Distributor.MOUSER, key, 4, numbers,
                true, NOW.minus(Duration.ofDays(2)), 4, null, 0, List.of()));
        PhraseClient mouser = new PhraseClient(Distributor.MOUSER);
        mouser.stock.put("M0", new StockUpdate(0, List.of()));
        mouser.stock.put("M1", new StockUpdate(42, List.of()));
        mouser.stock.put("M2", new StockUpdate(7, List.of()));
        MetricsStore store = new MetricsStore(null);
        service(List.of(mouser)).setMetrics(new KinaMetrics(store));

        service.search(request(query, 2, false, Distributor.MOUSER));

        assertThat(store.get(Metric.CACHE_STOCK_REFRESHES.key("MOUSER", "ok"))).isEqualTo(2);
        assertThat(store.get(Metric.CACHE_STOCK_REFRESHES.key("MOUSER", "out_of_stock"))).isEqualTo(1);
        assertThat(store.sum(Metric.PARTS_FETCHED)).isZero();   // served from the cache: no page was fetched

        // a cache miss fetches a page of three parts from the distributor
        cachedSearches.clear();
        PhraseClient live = new PhraseClient(Distributor.MOUSER);
        service.shutdown();
        service(List.of(live)).setMetrics(new KinaMetrics(store));
        service.search(request(query, 2, false, Distributor.MOUSER));
        String phrase = live.queries.getFirst();
        live.queries.clear();
        cachedSearches.clear();
        live.on(phrase, mlcc(Distributor.MOUSER, "L0", "25V", "X7R"), mlcc(Distributor.MOUSER, "L1", "25V", "X7R"),
                mlcc(Distributor.MOUSER, "L2", "25V", "X7R"));

        service.search(request(query, 2, false, Distributor.MOUSER));

        assertThat(store.sum(Metric.PARTS_FETCHED)).isEqualTo(3);
    }

    // ---- 6. get_part attributes of can capacitors; 12. datasheets, currencies ---------------------------------------------

    @Test
    void canCapacitorsReportRippleCurrentImpedanceDimensionsQualificationAndFeatures() {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("Manufacturer", "PANASONIC");
        attrs.put("Type of capacitor", "electrolytic");
        attrs.put("Kind of capacitor", "low ESR");
        attrs.put("Mounting", "SMD");
        attrs.put("Capacitance", "100µF");
        attrs.put("Tolerance", "±20%");
        attrs.put("Operating voltage", "16V DC");
        attrs.put("Case", "D");
        attrs.put("Body dimensions", "Ø6.3x5.8mm");
        attrs.put("Diameter", "6.3mm");
        attrs.put("Height", "5.8mm");
        attrs.put("Operating temperature", "-55...105°C");
        attrs.put("Service life", "2000h");
        attrs.put("Operating current", "0.24A");
        attrs.put("Impedance", "0.36Ω");
        attrs.put("Conform to the norm", "AEC-Q200");
        attrs.put("Manufacturer series", "FK");
        Part p = RankingFixtures.tme("EEEFK1C101P", "PANASONIC",
                "Capacitor: electrolytic; low ESR; SMD; 100uF; 16VDC; Ø6.3x5.8mm", "SMD electrolytic capacitors", "D",
                attrs);

        Map<String, String> canonical = extractor.extract(p);
        assertThat(canonical).containsEntry("RippleCurrent", "240mA").containsEntry("Impedance", "360mohm")
                .containsEntry("Dimensions", "D6.3 x 5.8mm").containsEntry("Package", "D6.3 x 5.8mm")
                .containsEntry("Case", "D").containsEntry("Qualification", "AEC-Q200")
                .containsEntry("Features", "low ESR").containsEntry("Voltage", "16V")
                .containsEntry("Technology", "aluminium electrolytic").doesNotContainKey("Current");
        assertThat(extractor.extract(extractor.enrich(p))).isEqualTo(canonical);

        // LCSC polymer: the ESR at its test frequency from the description; the package field gives the size
        Part polymer = RankingFixtures.lcsc("C2", "Lelon", "OVZ101M1CTR-0608",
                "100uF 16V 15mΩ@100kHz 2000hrs@105℃ SMD,D6.3xL7.7mm", "Capacitors / Polymer Aluminum Capacitors",
                "SMD,D6.3xL7.7mm", Map.of());
        assertThat(extractor.extract(polymer)).containsEntry("ESR", "15mohm @100kHz")
                .containsEntry("Package", "D6.3 x 7.7mm").doesNotContainKey("Case");
        // an MLCC keeps its chip package
        assertThat(extractor.extract(mlcc(Distributor.TME, "X", "25V", "X7R"))).containsEntry("Package", "1206")
                .doesNotContainKeys("Dimensions", "Case");
    }

    @Test
    void searchResponsesListTheirCurrencies() {
        PhraseClient lcsc = new PhraseClient(Distributor.LCSC).on("10uF X7R 0805 MLCC",
                part(Distributor.LCSC, "C1", "10uF 25V X7R 0805", "Capacitors", 100, Map.of(), Map.of(), NOW));
        PhraseClient tme = new PhraseClient(Distributor.TME).on("10uF X7R 0805 MLCC",
                part(Distributor.TME, "T1", "MLCC 10uF 25V X7R 0805", "Capacitors", 100, Map.of(), Map.of(), NOW));
        service(List.of(lcsc, tme));

        SearchResponse response = service.search(request("10uF X7R 0805 MLCC", 5, false, Distributor.LCSC,
                Distributor.TME));

        assertThat(response.currencies()).containsExactly("EUR", "USD");
        assertThat(response.distributors()).allSatisfy(d -> assertThat(d.parts()).allSatisfy(p ->
                assertThat(p.stockAsOf()).isEqualTo(NOW)));
    }

    // ---- 13. cache model: metadata kept, stock and prices expire (DESIGN.md 3.2 "Cache model") -----------------------

    static final String MLCC_QUERY = "10uF X7R 0805 MLCC";

    /** Caches {@code count} Mouser MLCCs whose stock was fetched at {@code stockAge} in a list fetched at {@code listAt}. */
    private List<String> cacheMouserList(int count, Instant stockAt, Instant listAt) {
        String key = QueryParser.normalizeKey(MLCC_QUERY);
        List<String> numbers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Part p = part(Distributor.MOUSER, "M" + i, "Multilayer Ceramic Capacitors MLCC 10uF 25V X7R 0805",
                    "MLCC", 1000 - i, Map.of(), Map.of(), stockAt);
            cachedParts.put(p.key(), p);
            numbers.add(p.distributorPartNumber());
        }
        cachedSearches.put(Distributor.MOUSER + "|" + key, new CachedSearch(Distributor.MOUSER, key, count, numbers,
                true, listAt, count, null, 0, List.of()));
        return numbers;
    }

    @Test
    void refreshFailingWithinTheTtlServesTheCachedFiguresUnmarked() {
        Instant twoDays = NOW.minus(Duration.ofDays(2));   // older than stock-ttl (24h), within ttl (3d)
        cacheMouserList(3, twoDays, twoDays);
        PhraseClient mouser = new PhraseClient(Distributor.MOUSER);
        mouser.refreshFailure = new ro.alacrity.kina.distributor.DistributorException(Distributor.MOUSER,
                ro.alacrity.kina.distributor.DistributorException.Kind.RATE_LIMITED, "quota");
        service(List.of(mouser));

        DistributorResult m = result(service.search(request(MLCC_QUERY, 3, false, Distributor.MOUSER)),
                Distributor.MOUSER);

        assertThat(m.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(mouser.refreshed).hasSize(1);
        assertThat(m.parts()).hasSize(3).allSatisfy(p -> {
            assertThat(p.stale()).isNull();
            assertThat(p.stockAsOf()).isEqualTo(twoDays);
            assertThat(p.availability().status()).isNotEqualTo(Availability.STALE);
        });
        assertThat(cachedParts.get(PartKey.of(Distributor.MOUSER, "M0")).fetchedAt()).isEqualTo(twoDays);
    }

    @Test
    void refreshFailingBeyondTheTtlMarksThePartsStaleAndRanksThemBelowFreshOnes() {
        Instant oneDay = NOW.minus(Duration.ofHours(20));
        Instant fourDays = NOW.minus(Duration.ofDays(4));
        cacheMouserList(4, oneDay, oneDay);
        // M0 and M1 (the distributor's best two) still carry stock figures from four days ago
        for (String n : List.of("M0", "M1")) {
            String k = PartKey.of(Distributor.MOUSER, n);
            cachedParts.put(k, cachedParts.get(k).toBuilder().fetchedAt(fourDays).build());
        }
        PhraseClient mouser = new PhraseClient(Distributor.MOUSER);
        mouser.refreshFailure = new ro.alacrity.kina.distributor.DistributorException(Distributor.MOUSER,
                ro.alacrity.kina.distributor.DistributorException.Kind.UNAVAILABLE, "down");
        service(List.of(mouser));

        DistributorResult m = result(service.search(request(MLCC_QUERY, 4, false, Distributor.MOUSER)),
                Distributor.MOUSER);

        assertThat(mouser.refreshed).containsExactly(List.of("M0", "M1"));
        assertThat(m.parts()).extracting(PartResponse::partNumber).containsExactly("M2", "M3", "M0", "M1");
        assertThat(m.parts()).extracting(PartResponse::stale).containsExactly(null, null, true, true);
        PartResponse stale = m.parts().get(2);
        assertThat(stale.availability().status()).isEqualTo(Availability.STALE);
        assertThat(stale.availability().note()).startsWith("Stock and price were last confirmed on "
                + fourDays.toString().substring(0, 10) + " (4 days ago) and could not be refreshed").contains(
                "Last known stock: ").doesNotContain("Ships now");
        assertThat(stale.stockAsOf()).isEqualTo(fourDays);
        assertThat(stale.score()).isNull();   // compact detail: rank carries the order
        assertThat(stale.rank()).isEqualTo(3);
    }

    @Test
    void staleRankPenaltyIsConfigurable() {
        Instant fourDays = NOW.minus(Duration.ofDays(4));
        cacheMouserList(3, NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofHours(1)));
        String k = PartKey.of(Distributor.MOUSER, "M0");
        cachedParts.put(k, cachedParts.get(k).toBuilder().fetchedAt(fourDays).build());
        PhraseClient mouser = new PhraseClient(Distributor.MOUSER);
        mouser.refreshFailure = new ro.alacrity.kina.distributor.DistributorException(Distributor.MOUSER,
                ro.alacrity.kina.distributor.DistributorException.Kind.UNAVAILABLE, "down");
        service(List.of(mouser), "kina.cache.stale-rank-penalty", "0");

        DistributorResult m = result(service.search(request(MLCC_QUERY, 3, false, Distributor.MOUSER)),
                Distributor.MOUSER);

        // no penalty: the stale part keeps its place, still flagged
        assertThat(m.parts().getFirst().partNumber()).isEqualTo("M0");
        assertThat(m.parts().getFirst().stale()).isTrue();
    }

    @Test
    void refreshSucceedingUpdatesStockPricesAndTheStockTimestamp() {
        Instant fourDays = NOW.minus(Duration.ofDays(4));
        cacheMouserList(2, fourDays, NOW.minus(Duration.ofHours(1)));
        PhraseClient mouser = new PhraseClient(Distributor.MOUSER);
        mouser.stock.put("M0", new StockUpdate(55, List.of(new PriceBreak(1, new BigDecimal("0.30"), "EUR"))));
        mouser.stock.put("M1", new StockUpdate(66, List.of()));
        service(List.of(mouser));

        DistributorResult m = result(service.search(request(MLCC_QUERY, 2, false, Distributor.MOUSER)),
                Distributor.MOUSER);

        assertThat(m.parts()).extracting(PartResponse::stock).containsExactly(55, 66);
        assertThat(m.parts()).allSatisfy(p -> {
            assertThat(p.stale()).isNull();
            assertThat(p.stockAsOf()).isEqualTo(NOW);
        });
        assertThat(m.parts().getFirst().prices().getFirst().unitPrice()).isEqualByComparingTo("0.30");
        assertThat(cachedParts.get(PartKey.of(Distributor.MOUSER, "M1")).fetchedAt()).isEqualTo(NOW);
    }

    @Test
    void liveSearchFailureServesTheExpiredListMarkedStale() {
        Instant fiveDays = NOW.minus(Duration.ofDays(5));
        cacheMouserList(2, fiveDays, fiveDays);   // list and stock older than the 3 day ttl
        PhraseClient mouser = new PhraseClient(Distributor.MOUSER);
        mouser.searchFailure = new ro.alacrity.kina.distributor.DistributorException(Distributor.MOUSER,
                ro.alacrity.kina.distributor.DistributorException.Kind.RATE_LIMITED, "quota exhausted");
        mouser.refreshFailure = mouser.searchFailure;
        service(List.of(mouser));

        SearchResponse response = service.search(request(MLCC_QUERY, 2, false, Distributor.MOUSER));
        DistributorResult m = result(response, Distributor.MOUSER);

        assertThat(m.cache()).isEqualTo(CacheStatus.STALE);
        assertThat(m.error()).isEqualTo("rate_limited");
        assertThat(m.parts()).hasSize(2).allSatisfy(p -> {
            assertThat(p.stale()).isTrue();
            assertThat(p.availability().status()).isEqualTo(Availability.STALE);
        });
        assertThat(response.attributions()).containsExactly("Product data provided by Mouser Electronics");
    }

    @Test
    void expiredListWithKnownMetadataIsSearchedAgainAndTheRowsUpdated() {
        Instant fiveDays = NOW.minus(Duration.ofDays(5));
        cacheMouserList(2, fiveDays, fiveDays);
        PhraseClient mouser = new PhraseClient(Distributor.MOUSER);
        Part again = part(Distributor.MOUSER, "M0", "Multilayer Ceramic Capacitors MLCC 10uF 25V X7R 0805 updated",
                "MLCC", 500, Map.of(), Map.of(), null);
        mouser.byPhrase.put(MLCC_QUERY, List.of(again));
        service(List.of(mouser));

        DistributorResult m = result(service.search(request(MLCC_QUERY, 2, false, Distributor.MOUSER)),
                Distributor.MOUSER);

        assertThat(m.cache()).isEqualTo(CacheStatus.MISS);
        assertThat(mouser.queries).isNotEmpty();
        assertThat(m.parts()).extracting(PartResponse::partNumber).containsExactly("M0");
        Part row = cachedParts.get(PartKey.of(Distributor.MOUSER, "M0"));
        assertThat(row.description()).endsWith("updated");
        assertThat(row.fetchedAt()).isEqualTo(NOW);
    }

    @Test
    void lcscPartsAreNeverStale() {
        Part old = part(Distributor.LCSC, "C1", "25V 10uF X7R ±10% 0805 Multilayer Ceramic Capacitors MLCC",
                "Capacitors/MLCC", 1000, Map.of(), Map.of(), NOW.minus(Duration.ofDays(30)));
        PhraseClient lcsc = new PhraseClient(Distributor.LCSC).on(MLCC_QUERY, old);
        service(List.of(lcsc));
        assertThat(service.isStale(old, NOW)).isFalse();
        SearchResponse response = service.search(request(MLCC_QUERY, 2, false, Distributor.LCSC));
        assertThat(response.attributions())
                .containsExactly("LCSC parts from the JLCPCB parts database (kicad-jlcpcb-tools)");
    }
}
