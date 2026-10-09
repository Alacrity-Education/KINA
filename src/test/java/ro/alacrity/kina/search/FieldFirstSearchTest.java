package ro.alacrity.kina.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import ro.alacrity.kina.TestWiring;
import ro.alacrity.kina.TestcontainersConfiguration;
import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.cache.CachedSearch;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.cache.PhraseJournalRepository;
import ro.alacrity.kina.cache.SearchCacheRepository;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.distributor.DistributorSearchPage;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.DistributorResult;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PartResponse;
import ro.alacrity.kina.domain.SearchRequest;
import ro.alacrity.kina.domain.SearchResponse;
import ro.alacrity.kina.metrics.KinaMetrics;
import ro.alacrity.kina.search.field.FieldQuery;
import ro.alacrity.kina.search.field.PartIndexRepository;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The field index modes of phase B (DESIGN.md 3.2 "Field-first flow", 3.8): {@code augment} adds the field candidates
 * to a cached list, {@code on} answers from the index and asks the distributor only for the phrases the journal does
 * not know; every case the index cannot answer takes the cached-search path. Real PostgreSQL, a fake distributor whose
 * answer depends on the phrase it is asked.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FieldFirstSearchTest {

    private static final String QUERY = "10uF X7R 0805 25V";

    @Autowired PartCacheRepository partCache;
    @Autowired SearchCacheRepository searchCache;
    @Autowired PhraseJournalRepository journal;
    @Autowired PartIndexRepository index;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper jsonMapper;

    private final ParametricExtractor extractor = new ParametricExtractor();
    private final QueryParser parser = new QueryParser();
    private PartSearchService service;
    private KinaMetrics metrics;
    private PhraseClient mouser;

    /** A distributor whose answer depends on the phrase; counts the phrases it was asked. */
    static final class PhraseClient implements DistributorClient {
        final Map<String, List<Part>> answers = new HashMap<>();
        final List<String> asked = new CopyOnWriteArrayList<>();
        /** The answer of {@link #refreshStock} by part number (missing: unknown). */
        final Map<String, ro.alacrity.kina.distributor.StockUpdate> stock = new HashMap<>();
        final List<String> refreshed = new CopyOnWriteArrayList<>();
        RuntimeException failure;

        @Override
        public Map<String, ro.alacrity.kina.distributor.StockUpdate> refreshStock(List<String> partNumbers,
                ro.alacrity.kina.distributor.Deadline deadline) {
            refreshed.addAll(partNumbers);
            Map<String, ro.alacrity.kina.distributor.StockUpdate> out = new HashMap<>();
            partNumbers.forEach(n -> {
                if (stock.containsKey(n)) {
                    out.put(n, stock.get(n));
                }
            });
            return out;
        }

        PhraseClient answer(String phrase, List<Part> parts) {
            answers.put(QueryParser.normalizeKey(phrase), parts);
            return this;
        }

        @Override
        public Distributor distributor() {
            return Distributor.MOUSER;
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public int maxPageSize() {
            return 50;
        }

        @Override
        public DistributorSearchPage search(String query, int offset, int limit) {
            asked.add(query);
            if (failure != null) {
                throw failure;
            }
            List<Part> all = answers.getOrDefault(QueryParser.normalizeKey(query), List.of());
            int to = Math.min(all.size(), offset + limit);
            return new DistributorSearchPage(offset >= all.size() ? List.of() : all.subList(offset, to), all.size(),
                    to < all.size());
        }

        @Override
        public Optional<Part> getPart(String distributorPartNumber) {
            return Optional.empty();
        }
    }

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM cached_parts").update();
        index.forgetCoverage();
        jdbc.sql("DELETE FROM cached_searches").update();
        jdbc.sql("DELETE FROM distributor_phrases").update();
        mouser = new PhraseClient();
        metrics = mock(KinaMetrics.class);
    }

    @AfterEach
    void shutdown() {
        if (service != null) {
            service.shutdown();
        }
    }

    // ---------------------------------------------------------------- helpers

    /** A 0805 10uF MLCC of the given dielectric and voltage; stock 1000, fetched when cached. */
    private static Part mlcc(String number, String dielectric, int volts) {
        return RankingFixtures.part(Distributor.MOUSER, number, "YAGEO", "MPN-" + number,
                "MLCC 10uF " + volts + "V " + dielectric + " 0805 10%", "Ceramic Capacitors", "0805", 1000, "0.10",
                Map.of(), Map.of()).toBuilder().fetchedAt(null).build();
    }

    private static List<Part> mlccs(String prefix, int count, String dielectric, int volts) {
        List<Part> out = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            out.add(mlcc(prefix + i, dielectric, volts));
        }
        return out;
    }

    private void cache(List<Part> parts) {
        partCache.upsertAll(parts.stream().map(p -> p.toBuilder().fetchedAt(java.time.Instant.now()).build())
                .toList());
    }

    private void service(String mode, String... extra) {
        List<String> kv = new ArrayList<>(List.of("kina.ranking.cross-encoder.enabled", "false",
                "kina.search.field-index.mode", mode));
        kv.addAll(List.of(extra));
        service(RankingFixtures.properties(kv.toArray(String[]::new)), index);
    }

    private void service(KinaProperties props, PartIndexRepository indexBean) {
        if (service != null) {
            service.shutdown();
        }
        RankingService ranking = TestWiring.rankingService(props, TestWiring.deterministicRanker(extractor),
                mock(PartRanker.class), () -> null, TestWiring.scoreCache(Duration.ofHours(1)));
        service = RankingFixtures.searchService(props, TestWiring.registry(List.<DistributorClient>of(mouser)), parser,
                extractor, ranking, partCache, searchCache, Clock.systemUTC(), metrics,
                new RankingFixtures.FieldBeans(indexBean, journal));
    }

    private DistributorResult search(String query, int max, boolean bypass, boolean belowSpec) {
        int before = mouser.asked.size();
        SearchResponse response = service.search(new SearchRequest(query, max, Set.of(Distributor.MOUSER), bypass,
                1, ro.alacrity.kina.domain.ResponseDetail.COMPACT, belowSpec));
        DistributorResult result = PartSearchServiceTest.result(response, Distributor.MOUSER);
        // live_calls is exactly the distributor search calls of this request, in every mode, failed calls included
        assertThat(result.liveCalls()).as("live_calls").isEqualTo(mouser.asked.size() - before);
        return result;
    }

    private DistributorResult search(String query, int max) {
        return search(query, max, false, false);
    }

    /** The phrases the distributor is asked for {@link #QUERY}: the request's phrase, then the relaxation phrases. */
    private List<String> phrases() {
        var parsed = parser.parse(QUERY);
        String phrase = DistributorPhraser.phrase(Distributor.MOUSER, parsed);
        String first = phrase != null ? phrase : parsed.originalText();
        List<String> out = new ArrayList<>(List.of(first));
        DistributorPhraser.ladder(Distributor.MOUSER, parsed, first, ConstraintPolicy.DEFAULTS)
                .forEach(r -> out.add(r.phrase()));
        return out;
    }

    /** The phrase of the ladder step that loosens only the dielectric. */
    private String noDielectricPhrase() {
        var parsed = parser.parse(QUERY);
        String phrase = DistributorPhraser.phrase(Distributor.MOUSER, parsed);
        return DistributorPhraser.ladder(Distributor.MOUSER, parsed, phrase != null ? phrase : parsed.originalText(),
                        ConstraintPolicy.DEFAULTS).stream()
                .filter(r -> r.relaxed().equals(List.of("dielectric"))).findFirst().orElseThrow().phrase();
    }

    private static List<String> numbers(DistributorResult result) {
        return result.parts().stream().map(PartResponse::partNumber).toList();
    }

    // ---------------------------------------------------------------- augment

    @Test
    void augmentAddsTheFieldCandidatesToACachedListWithoutCallingTheDistributor() {
        List<Part> listed = mlccs("L", 2, "X7R", 25);
        List<Part> others = mlccs("O", 4, "X7R", 25);
        cache(listed);
        cache(others);
        searchCache.upsert(new CachedSearch(Distributor.MOUSER, parser.parse(QUERY).normalizedKey(), 40,
                List.of("L1", "L2"), true, java.time.Instant.now(), 50, null, 3, null));

        service("off");
        DistributorResult off = search(QUERY, 10);
        assertThat(off.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(off.fetched()).isEqualTo(2);

        service("augment");
        DistributorResult augmented = search(QUERY, 10);
        assertThat(augmented.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(augmented.fetched()).as("the merged set").isEqualTo(6);
        assertThat(augmented.totalResults()).as("the distributor's figure").isEqualTo(40);
        assertThat(augmented.outOfStockMatches()).isEqualTo(3);
        assertThat(numbers(augmented)).containsExactlyInAnyOrder("L1", "L2", "O1", "O2", "O3", "O4");
        assertThat(augmented.fetchedLive()).isFalse();
        assertThat(mouser.asked).as("no distributor call").isEmpty();
    }

    @Test
    void augmentLeavesAMissAlone() {
        cache(mlccs("O", 4, "X7R", 25));
        mouser.answer(phrases().getFirst(), mlccs("N", 2, "X7R", 25));
        service("augment");
        DistributorResult result = search(QUERY, 10);
        assertThat(result.cache()).isEqualTo(CacheStatus.MISS);
        assertThat(result.fetched()).isEqualTo(2);
        assertThat(mouser.asked).hasSize(1);
    }

    // ---------------------------------------------------------------- on

    @Test
    void enoughCandidatesAtStepZeroAreAnsweredFromTheCacheWithoutACall() {
        cache(mlccs("A", 5, "X7R", 25));
        service("on");
        DistributorResult result = search(QUERY, 3);
        assertThat(result.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(result.fetchedLive()).isFalse();
        assertThat(result.fieldSteps()).isEqualTo(1);
        assertThat(result.returned()).isEqualTo(3);
        assertThat(result.fetched()).isEqualTo(5);
        assertThat(result.fallbackQuery()).isNull();
        assertThat(result.totalResults()).as("no journal row: the index candidate count").isEqualTo(5);
        assertThat(mouser.asked).isEmpty();
        verify(metrics).fieldServed("MOUSER");
        assertThat(journal.count()).isZero();
    }

    @Test
    void notEnoughAtStepZeroCallsTheDistributorWithTheRequestsPhraseThenAnswersFromTheIndex() {
        cache(mlccs("A", 1, "X7R", 25));
        mouser.answer(phrases().getFirst(), mlccs("N", 5, "X7R", 25));
        service("on");
        DistributorResult result = search(QUERY, 3);
        assertThat(mouser.asked).containsExactly(phrases().getFirst());
        assertThat(result.cache()).isEqualTo(CacheStatus.MISS);
        assertThat(result.fetchedLive()).isTrue();
        assertThat(result.fieldSteps()).isEqualTo(1);
        assertThat(result.returned()).isEqualTo(3);
        assertThat(result.totalResults()).as("the distributor's count of this call").isEqualTo(5);
        assertThat(result.fallbackQuery()).isNull();
        verify(metrics).fieldLiveCall("MOUSER", 0);

        PhraseJournalRepository.Entry entry = journal.find(Distributor.MOUSER,
                DistributorPhraser.phraseKey(phrases().getFirst())).orElseThrow();
        assertThat(entry.rawTotal()).isEqualTo(5);
        assertThat(entry.exhausted()).isTrue();
        assertThat(entry.empty()).isFalse();
        assertThat(entry.ladderStep()).isZero();
        assertThat(entry.queryKey()).isEqualTo(parser.parse(QUERY).normalizedKey());
        // the parts are in the cache and the index, and the cached search is kept as the other modes write it
        assertThat(index.coverage().get(Distributor.MOUSER).complete()).isTrue();
        assertThat(searchCache.find(Distributor.MOUSER, parser.parse(QUERY).normalizedKey())).isPresent();

        // the same request again: the cache holds enough now
        DistributorResult again = search(QUERY, 3);
        assertThat(again.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(again.fetchedLive()).isFalse();
        assertThat(again.totalResults()).as("the journal's count for the phrase").isEqualTo(5);
        assertThat(mouser.asked).hasSize(1);
    }

    @Test
    void relaxingOneStepCallsTheRelaxedPhraseAndTheJournalPreventsARepeat() {
        mouser.answer(phrases().getFirst(), mlccs("A", 1, "X7R", 25));
        List<Part> relaxed = new ArrayList<>(mlccs("A", 1, "X7R", 25));
        relaxed.addAll(mlccs("B", 4, "X5R", 25));
        mouser.answer(noDielectricPhrase(), relaxed);
        service("on");

        DistributorResult first = search(QUERY, 3);
        assertThat(mouser.asked).containsExactly(phrases().getFirst(), noDielectricPhrase());
        assertThat(first.cache()).isEqualTo(CacheStatus.PARTIAL);
        assertThat(first.fetchedLive()).isTrue();
        assertThat(first.fieldSteps()).isEqualTo(2);
        assertThat(first.fallbackQuery()).isEqualTo(noDielectricPhrase());
        assertThat(first.constraintsRelaxed()).containsExactly("dielectric");
        assertThat(first.returned()).isEqualTo(3);
        assertThat(numbers(first).getFirst()).as("the exact part first").isEqualTo("A1");
        verify(metrics).fieldLiveCall("MOUSER", 0);
        verify(metrics).fieldLiveCall("MOUSER", 1);
        assertThat(journal.find(Distributor.MOUSER, DistributorPhraser.phraseKey(noDielectricPhrase())).orElseThrow()
                .rawTotal()).isEqualTo(5);

        // again: step 0 is short, the journal knows its phrase; step 1 holds enough: no call
        DistributorResult second = search(QUERY, 3);
        assertThat(mouser.asked).hasSize(2);
        assertThat(second.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(second.fetchedLive()).isFalse();
        assertThat(second.fieldSteps()).isEqualTo(2);
        assertThat(second.fallbackQuery()).isEqualTo(noDielectricPhrase());
        assertThat(second.constraintsRelaxed()).containsExactly("dielectric");
        assertThat(second.totalResults()).as("the journal's count for the served phrase").isEqualTo(5);
        assertThat(numbers(second)).isEqualTo(numbers(first));
        verify(metrics).fieldJournalHit("MOUSER");
    }

    @Test
    void aWordedRequestIsNotAskedAgainWhileItsPhraseIsFresh() {
        // the distributor has only one part: every repeat is short, but nothing is asked twice
        mouser.answer(phrases().getFirst(), mlccs("A", 1, "X7R", 25));
        service("on");
        search(QUERY, 3);
        int calls = mouser.asked.size();
        assertThat(calls).isLessThanOrEqualTo(2);
        search(QUERY, 3);
        search("0805 X7R 10uF 50V", 3);   // the same phrase with another rating
        assertThat(mouser.asked).as("the journal knows every phrase").hasSize(calls);
    }

    @Test
    void theCallCapStopsTheRelaxation() {
        mouser.answer(phrases().getFirst(), mlccs("A", 1, "X7R", 25));
        mouser.answer(noDielectricPhrase(), mlccs("B", 4, "X5R", 25));
        service("on", "kina.search.field-index.max-live-calls-per-distributor", "1");
        DistributorResult result = search(QUERY, 3);
        assertThat(mouser.asked).as("one call, then the cap").containsExactly(phrases().getFirst());
        assertThat(result.cache()).isEqualTo(CacheStatus.MISS);
        assertThat(result.fieldSteps()).isEqualTo(2);
        assertThat(result.returned()).isEqualTo(1);
        assertThat(result.fallbackQuery()).as("the relaxed step was never asked").isNotNull();
    }

    @Test
    void anExhaustedLadderAnswersWithWhatThereIsAndTheHint() {
        service("on");
        DistributorResult result = search(QUERY, 3);
        assertThat(mouser.asked).containsExactly(phrases().getFirst(), noDielectricPhrase());
        assertThat(result.returned()).isZero();
        assertThat(result.fetchedLive()).isTrue();
        assertThat(result.fieldSteps()).isEqualTo(2);
        assertThat(result.hint()).isNotBlank();
        assertThat(journal.find(Distributor.MOUSER, DistributorPhraser.phraseKey(phrases().getFirst())).orElseThrow()
                .empty()).isTrue();
    }

    @Test
    void ratingsAreNeverRelaxedNorFilteredAndBelowSpecIsReportedAsOnTheCachedSearchPath() {
        cache(mlccs("LOW", 4, "X7R", 16));   // 16 V parts for a 25 V request
        service("on");
        DistributorResult strict = search(QUERY, 3);
        assertThat(strict.returned()).as("below spec is never relaxed").isZero();
        assertThat(mouser.asked).as("the distributor was asked").isNotEmpty();
        // the ratings are not filtered in SQL: the Java check leaves the parts out and counts them
        assertThat(strict.excludedBelowSpec()).isEqualTo(4);
        assertThat(strict.excludedBelowSpecDetail()).hasSize(4)
                .allSatisfy(d -> assertThat(d.rating()).isEqualTo("voltage"));
        assertThat(strict.hint()).contains("allow_below_spec");

        jdbc.sql("DELETE FROM distributor_phrases").update();
        mouser.asked.clear();
        DistributorResult below = search(QUERY, 3, false, true);
        assertThat(below.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(below.returned()).isEqualTo(3);
        assertThat(mouser.asked).as("the field query without the ratings found enough").isEmpty();
    }

    @Test
    void bypassCacheReadsNeitherTheIndexNorTheJournalButStillWritesBoth() {
        cache(mlccs("A", 5, "X7R", 25));
        mouser.answer(phrases().getFirst(), mlccs("N", 5, "X7R", 25));
        journal.record(new PhraseJournalRepository.Entry(Distributor.MOUSER,
                DistributorPhraser.phraseKey(phrases().getFirst()), phrases().getFirst(), java.time.Instant.now(), 5, 5,
                true, 0, false, 0, "x"));
        service("on");
        DistributorResult result = search(QUERY, 3, true, false);
        assertThat(result.cache()).isEqualTo(CacheStatus.BYPASSED);
        assertThat(result.fetchedLive()).isTrue();
        assertThat(mouser.asked).containsExactly(phrases().getFirst());
        assertThat(partCache.findInStock(Distributor.MOUSER, List.of("N1", "N5"))).hasSize(2);
        assertThat(index.indexed(Distributor.MOUSER, List.of("N1", "N5"))).hasSize(2);
        assertThat(journal.find(Distributor.MOUSER, DistributorPhraser.phraseKey(phrases().getFirst())).orElseThrow()
                .askedAt()).as("the journal row was rewritten").isAfter(java.time.Instant.now().minusSeconds(30));
        verify(metrics).fieldFallback("MOUSER", "bypass");
    }

    @Test
    void anIncompleteIndexTakesTheCachedSearchPath() {
        cache(mlccs("A", 5, "X7R", 25));
        jdbc.sql("DELETE FROM part_index").update();
        index.forgetCoverage();
        assertThat(index.isComplete(Distributor.MOUSER)).isFalse();
        mouser.answer(phrases().getFirst(), mlccs("N", 5, "X7R", 25));
        service("on");
        DistributorResult result = search(QUERY, 3);
        assertThat(result.cache()).isEqualTo(CacheStatus.MISS);
        assertThat(result.fetched()).as("the list of the call only").isEqualTo(5);
        assertThat(result.fieldSteps()).isZero();
        assertThat(mouser.asked).containsExactly(phrases().getFirst());
        verify(metrics).fieldFallback("MOUSER", "incomplete");
        verify(metrics, never()).fieldServed(any());
    }

    @Test
    void aSqlErrorTakesTheCachedSearchPath() {
        cache(mlccs("A", 5, "X7R", 25));
        PartIndexRepository broken = mock(PartIndexRepository.class);
        when(broken.isComplete(any())).thenReturn(true);
        when(broken.query(any(FieldQuery.class), any(FieldQuery.Step.class), anyInt()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("down"));
        mouser.answer(phrases().getFirst(), mlccs("N", 5, "X7R", 25));
        service(RankingFixtures.properties("kina.ranking.cross-encoder.enabled", "false",
                "kina.search.field-index.mode", "on"), broken);
        DistributorResult result = search(QUERY, 3);
        assertThat(result.cache()).isEqualTo(CacheStatus.MISS);
        assertThat(result.returned()).isEqualTo(3);
        assertThat(mouser.asked).containsExactly(phrases().getFirst());
        verify(metrics).fieldFallback("MOUSER", "sql_error");
    }

    @Test
    void otherModesTakeTheCachedSearchPath() {
        cache(mlccs("A", 5, "X7R", 25));
        mouser.answer(phrases().getFirst(), mlccs("N", 5, "X7R", 25));
        service("off");
        DistributorResult result = search(QUERY, 3);
        assertThat(result.cache()).isEqualTo(CacheStatus.MISS);
        assertThat(result.fieldSteps()).isZero();
        verify(metrics).fieldFallback("MOUSER", "mode");
        // the other modes record the phrase too: the journal is warm when the mode changes to on
        assertThat(journal.find(Distributor.MOUSER, DistributorPhraser.phraseKey(phrases().getFirst()))).isPresent();
    }

    @Test
    void aGenericRequestIsNotAnsweredFromTheIndex() {
        // only the family: the index would return any MOSFET in the cache, the distributor ranks them
        cache(List.of(RankingFixtures.part(Distributor.MOUSER, "Q0", "ACME", "MPN-Q0", "N-channel MOSFET 30V 5A SOT-23",
                "MOSFETs", "SOT-23", 1000, "0.10", Map.of(), Map.of())));
        mouser.answer("mosfet", List.of(RankingFixtures.part(Distributor.MOUSER, "Q1", "ACME", "MPN-Q1",
                "N-channel MOSFET 30V 5A SOT-23", "MOSFETs", "SOT-23", 1000, "0.10", Map.of(), Map.of())));
        service("on");
        DistributorResult result = search("mosfet", 3);
        assertThat(result.cache()).isEqualTo(CacheStatus.MISS);
        assertThat(mouser.asked).containsExactly("mosfet");
        assertThat(result.fieldSteps()).isZero();
        verify(metrics).fieldFallback("MOUSER", "generic");
    }

    @Test
    void aFailedCallServesTheCandidatesOfTheIndexAsStale() {
        cache(mlccs("A", 2, "X7R", 25));
        mouser.failure = new DistributorException(Distributor.MOUSER, DistributorException.Kind.UNAVAILABLE, "down");
        service("on");
        DistributorResult result = search(QUERY, 3);
        assertThat(result.error()).isEqualTo("unavailable");
        assertThat(result.cache()).isEqualTo(CacheStatus.STALE);
        assertThat(result.returned()).isEqualTo(2);
        // the call was made (and spent quota) even though it failed
        assertThat(result.fetchedLive()).isTrue();
        assertThat(result.liveCalls()).isEqualTo(1);
        assertThat(journal.count()).as("a failed call is not journaled").isZero();
    }

    @Test
    void aFailedCallEvaluatesTheRelaxedStepsFromTheIndexWithoutAnotherCall() {
        // step 0 (X7R) holds one part, the relaxed step (any dielectric) four more: the first call fails
        cache(mlccs("A", 1, "X7R", 25));
        cache(mlccs("B", 4, "X5R", 25));
        mouser.failure = new DistributorException(Distributor.MOUSER, DistributorException.Kind.UNAVAILABLE, "down");
        service("on");
        DistributorResult result = search(QUERY, 3);
        assertThat(mouser.asked).as("no call after the failure").containsExactly(phrases().getFirst());
        assertThat(result.error()).isEqualTo("unavailable");
        assertThat(result.cache()).isEqualTo(CacheStatus.STALE);
        assertThat(result.fieldSteps()).isEqualTo(2);
        assertThat(result.returned()).isEqualTo(3);
        assertThat(numbers(result).getFirst()).as("the exact part first").isEqualTo("A1");
        assertThat(result.constraintsRelaxed()).containsExactly("dielectric");
        assertThat(journal.count()).isZero();
    }

    @Test
    void aFailedCallWithNothingFoundServesTheExpiredListAsTheCachedSearchPathDoes() {
        // the index holds only parts below spec; the request's list has expired (older than the TTL)
        cache(mlccs("LOW", 2, "X7R", 16));
        searchCache.upsert(new CachedSearch(Distributor.MOUSER, parser.parse(QUERY).normalizedKey(), 2,
                List.of("LOW1", "LOW2"), true, java.time.Instant.now().minus(Duration.ofDays(10)), 50, null, 0,
                null));
        mouser.failure = new DistributorException(Distributor.MOUSER, DistributorException.Kind.UNAVAILABLE, "down");
        service("off");
        DistributorResult off = search(QUERY, 3);
        service("on");
        DistributorResult on = search(QUERY, 3);
        assertThat(off.cache()).isEqualTo(CacheStatus.STALE);
        assertThat(on.cache()).isEqualTo(off.cache());
        assertThat(on.error()).isEqualTo(off.error()).isEqualTo("unavailable");
        assertThat(on.fetched()).isEqualTo(off.fetched()).isEqualTo(2);
        assertThat(on.excludedBelowSpec()).isEqualTo(off.excludedBelowSpec()).isEqualTo(2);
        assertThat(on.returned()).isZero();
        assertThat(on.fieldSteps()).as("every step was read from the index").isEqualTo(2);
        assertThat(mouser.asked).as("one call per mode").hasSize(2);
    }

    @Test
    void aFailedCallMergesTheExpiredListIntoTheCandidates() {
        // the expired list holds a part the field query does not select (a keyword match): it is served too
        Part accessory = RankingFixtures.part(Distributor.MOUSER, "K1", "ACME", "KIT-1",
                "Evaluation kit for MLCC 0805 X7R 25V", "Development Kits", null, 10, "50.00", Map.of(), Map.of());
        cache(List.of(accessory));
        cache(mlccs("A", 1, "X7R", 25));
        searchCache.upsert(new CachedSearch(Distributor.MOUSER, parser.parse(QUERY).normalizedKey(), 2,
                List.of("A1", "K1"), true, java.time.Instant.now().minus(Duration.ofDays(10)), 50, null, 0, null));
        mouser.failure = new DistributorException(Distributor.MOUSER, DistributorException.Kind.UNAVAILABLE, "down");
        service("off");
        DistributorResult off = search(QUERY, 3);
        service("on");
        DistributorResult on = search(QUERY, 3);
        assertThat(on.cache()).isEqualTo(CacheStatus.STALE);
        assertThat(numbers(on)).containsAll(numbers(off)).contains("A1", "K1");
    }

    @Test
    void theCandidateCapLimitsTheIndexRows() {
        cache(mlccs("A", 6, "X7R", 25));
        service("on", "kina.search.field-index.max-candidates", "4");
        DistributorResult result = search(QUERY, 3);
        assertThat(result.fetched()).as("max-candidates rows of the index").isEqualTo(4);
        assertThat(result.returned()).isEqualTo(3);
        assertThat(mouser.asked).isEmpty();
    }

    @Test
    void aFailedCallWithNothingToServeFailsAsBefore() {
        mouser.failure = new DistributorException(Distributor.MOUSER, DistributorException.Kind.UNAVAILABLE, "down");
        service("on");
        DistributorResult result = search(QUERY, 3);
        assertThat(result.error()).isEqualTo("unavailable");
        assertThat(result.returned()).isZero();
    }

    @Test
    void theResponseCarriesTheNewFields() {
        cache(mlccs("A", 5, "X7R", 25));
        service("on");
        SearchResponse response = service.search(new SearchRequest(QUERY, 3, Set.of(Distributor.MOUSER), false));
        String json = jsonMapper.writeValueAsString(response);
        assertThat(json).contains("\"fetched_live\":false").contains("\"field_steps_tried\":1")
                .contains("\"live_calls\":0").contains("\"cache\":\"hit\"");
    }

    @Test
    void aSearchNamingAPartNumberKeepsARowForItsLookupOutcomes() {
        cache(mlccs("A", 5, "X7R", 25));
        service("on");
        DistributorResult result = search("MPN-A1", 3);
        assertThat(numbers(result)).contains("A1");
        assertThat(searchCache.find(Distributor.MOUSER, parser.parse("MPN-A1").normalizedKey())).isPresent();
        verify(metrics, never()).fieldFallback(eq("MOUSER"), eq("sql_error"));
    }

    @Test
    void aJournalReadFailureTakesTheCachedSearchPathInsteadOfSpendingACall() {
        // step 0 is short (2 of 3), so the flow would look up the journal before calling: the read fails
        cache(mlccs("A", 2, "X7R", 25));
        mouser.answer(phrases().getFirst(), mlccs("N", 5, "X7R", 25));
        PhraseJournalRepository broken = mock(PhraseJournalRepository.class);
        when(broken.find(any(), any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("down"));
        if (service != null) {
            service.shutdown();
        }
        KinaProperties props = RankingFixtures.properties("kina.ranking.cross-encoder.enabled", "false",
                "kina.search.field-index.mode", "on");
        RankingService ranking = TestWiring.rankingService(props, TestWiring.deterministicRanker(extractor),
                mock(PartRanker.class), () -> null, TestWiring.scoreCache(Duration.ofHours(1)));
        service = RankingFixtures.searchService(props, TestWiring.registry(List.<DistributorClient>of(mouser)), parser,
                extractor, ranking, partCache, searchCache, Clock.systemUTC(), metrics,
                new RankingFixtures.FieldBeans(index, broken));
        DistributorResult result = search(QUERY, 3);
        verify(metrics).fieldFallback("MOUSER", "sql_error");
        verify(metrics, never()).fieldLiveCall(any(), anyInt());
        // the cached-search path asks once, as it always did for a query it has no list of
        assertThat(mouser.asked).containsExactly(phrases().getFirst());
        assertThat(result.cache()).isEqualTo(CacheStatus.MISS);
        assertThat(result.returned()).isEqualTo(3);
    }

    @Test
    void aRequestedRatingMakesARequestSelectiveAndPartsMeetingItComeFirst() {
        // the rating is in no filter (ratings only order), but it is stated: the index answers, the 60 V parts first
        List<Part> mosfets = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            mosfets.add(RankingFixtures.part(Distributor.MOUSER, "Q" + i, "ACME", "MPN-Q" + i,
                    "N-channel MOSFET 60V 5A SOT-23", "MOSFETs", "SOT-23", 1000, "0.10", Map.of(), Map.of()));
            mosfets.add(RankingFixtures.part(Distributor.MOUSER, "L" + i, "ACME", "MPN-L" + i,
                    "N-channel MOSFET 20V 5A SOT-23", "MOSFETs", "SOT-23", 1000, "0.10", Map.of(), Map.of()));
        }
        cache(mosfets);
        service("on", "kina.search.field-index.max-candidates", "4");
        DistributorResult result = search("mosfet 60V", 3);
        assertThat(mouser.asked).as("answered from the index").isEmpty();
        assertThat(result.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(result.fetched()).as("the cap holds the four parts that meet the rating").isEqualTo(4);
        assertThat(numbers(result)).allMatch(n -> n.startsWith("Q"));
        verify(metrics, never()).fieldFallback(eq("MOUSER"), eq("generic"));
    }

    @Test
    void withoutTheStatedConstraintRuleAFamilyOnlyRequestIsAnsweredFromTheIndex() {
        List<Part> mosfets = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            mosfets.add(RankingFixtures.part(Distributor.MOUSER, "Q" + i, "ACME", "MPN-Q" + i,
                    "N-channel MOSFET 30V 5A SOT-23", "MOSFETs", "SOT-23", 1000, "0.10", Map.of(), Map.of()));
        }
        cache(mosfets);
        service("on", "kina.search.field-index.require-stated-constraint", "false");
        DistributorResult result = search("mosfet", 3);
        assertThat(result.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(mouser.asked).as("answered from the index").isEmpty();
        assertThat(result.returned()).isEqualTo(3);
        verify(metrics, never()).fieldFallback(eq("MOUSER"), eq("generic"));
    }

    @Test
    void augmentReadsAFamilyOnlyRequestOnlyWithoutTheStatedConstraintRule() {
        List<Part> mosfets = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            mosfets.add(RankingFixtures.part(Distributor.MOUSER, "Q" + i, "ACME", "MPN-Q" + i,
                    "N-channel MOSFET 30V 5A SOT-23", "MOSFETs", "SOT-23", 1000, "0.10", Map.of(), Map.of()));
        }
        cache(mosfets);
        searchCache.upsert(new CachedSearch(Distributor.MOUSER, parser.parse("mosfet").normalizedKey(), 40,
                List.of("Q1"), true, java.time.Instant.now(), 50, null, 0, null));
        service("augment");
        assertThat(search("mosfet", 10).fetched()).as("the rule on: the list only").isEqualTo(1);
        service("augment", "kina.search.field-index.require-stated-constraint", "false");
        assertThat(search("mosfet", 10).fetched()).as("the rule off: the family from the index").isEqualTo(4);
        assertThat(mouser.asked).isEmpty();
    }

    @Test
    void aFreshCachedListOfTheRequestIsServedNextToTheIndex() {
        // the distributor answered the request's phrase with a part the field query does not select (no stated
        // capacitance: a keyword match); the cached-search path serves it from the list, so the field path does too
        Part accessory = RankingFixtures.part(Distributor.MOUSER, "K1", "ACME", "KIT-1",
                "Evaluation kit for MLCC 0805 X7R 25V", "Development Kits", null, 10, "50.00", Map.of(), Map.of());
        cache(List.of(accessory));
        cache(mlccs("A", 2, "X7R", 25));
        searchCache.upsert(new CachedSearch(Distributor.MOUSER, parser.parse(QUERY).normalizedKey(), 3,
                List.of("A1", "K1"), true, java.time.Instant.now(), 50, null, 0, null));
        DistributorResult off;
        service("off");
        off = search(QUERY, 3);
        assertThat(off.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(numbers(off)).containsExactlyInAnyOrder("A1", "K1");
        journal.record(new PhraseJournalRepository.Entry(Distributor.MOUSER,
                DistributorPhraser.phraseKey(phrases().getFirst()), phrases().getFirst(), java.time.Instant.now(), 3,
                50, true, 0, false, 0, parser.parse(QUERY).normalizedKey()));
        service("on");
        DistributorResult on = search(QUERY, 3);
        assertThat(on.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(numbers(on)).containsAll(numbers(off));
        assertThat(numbers(on)).contains("A2");   // and the index adds the parts the list does not hold
        assertThat(mouser.asked).isEmpty();
    }
}
