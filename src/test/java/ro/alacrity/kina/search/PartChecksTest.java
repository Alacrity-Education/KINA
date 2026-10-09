package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.TestWiring;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A part is checked once per request (review B2): the retrieval's checks ({@link PartChecks}) are the ranker's, for the
 * same part instance and the same query only.
 */
class PartChecksTest {

    private final QueryParser parser = new QueryParser();
    private final ParametricExtractor extractor = new ParametricExtractor();

    private static Part mlcc(String number) {
        return RankingFixtures.part(Distributor.MOUSER, number, "YAGEO", "MPN-" + number, "MLCC 10uF 25V X7R 0805 10%",
                "Ceramic Capacitors", "0805", 1000, "0.10", Map.of(), Map.of());
    }

    @Test
    void aPartIsCheckedOncePerInstanceAndQuery() {
        ParsedQuery query = parser.parse("10uF X7R 0805");
        PartChecks checks = new PartChecks(query);
        AtomicInteger computed = new AtomicInteger();
        Part part = mlcc("A1");
        ConstraintPolicy.Result result = new ConstraintPolicy.Result(List.of(), false);
        for (int i = 0; i < 3; i++) {
            checks.check(query, part, () -> {
                computed.incrementAndGet();
                return result;
            });
        }
        assertThat(computed).as("once for the instance").hasValue(1);
        checks.check(query, part.toBuilder().stock(5).build(), () -> {
            computed.incrementAndGet();
            return result;
        });
        assertThat(computed).as("again for a new copy of the part (fresh stock)").hasValue(2);
        checks.check(parser.parse("10uF X5R 0805"), part, () -> {
            computed.incrementAndGet();
            return result;
        });
        assertThat(computed).as("never for another query").hasValue(3);
    }

    @Test
    void theRankerReusesTheChecksOfTheRequest() {
        ParsedQuery query = parser.parse("10uF X7R 0805");
        RankingService ranking = TestWiring.rankingService(TestWiring.properties("kina.ranking.cross-encoder.enabled",
                "false"), TestWiring.deterministicRanker(extractor), mock(PartRanker.class), () -> null,
                TestWiring.scoreCache(Duration.ofHours(1)));
        Part a = extractor.enrich(mlcc("A1"));
        Part b = extractor.enrich(mlcc("B1"));
        PartChecks checks = new PartChecks(query);
        // the retrieval found A1 contradicting a hard constraint: the ranker does not check it again
        checks.check(query, a, () -> new ConstraintPolicy.Result(List.of("package"), true));
        RankingService.RankedResults ranked = ranking.rank(query, Map.of(Distributor.MOUSER, List.of(a, b)), null,
                new RankingService.RankOptions(1, false, checks));
        assertThat(ranked.byDistributor().get(Distributor.MOUSER)).extracting(r -> r.part().distributorPartNumber())
                .containsExactly("B1");
        assertThat(ranked.excludedBy(Distributor.MOUSER)).isEqualTo(1);
    }
}
