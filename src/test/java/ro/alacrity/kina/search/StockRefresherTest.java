package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.TestWiring;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.search.RankingService.RankedPart;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stale demotion and the match class (DESIGN.md 3.2 "Stock and prices", 3.3 "Match class", v0.15.1): a part that
 * confirms none of the stated parameters ({@code match} 0) never ranks above one that confirms some, stale or not;
 * stale parts are demoted only within their match class.
 */
class StockRefresherTest {

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    private static final Instant FRESH = NOW.minus(Duration.ofHours(1));
    private static final Instant STALE = NOW.minus(Duration.ofDays(4));
    private static final String QUERY = "4.7k 1% 0603 resistor";

    private final ParametricExtractor extractor = new ParametricExtractor();
    private final KinaProperties props = RankingFixtures.properties("kina.ranking.cross-encoder.enabled", "false");
    private final RankingService ranking = TestWiring.rankingService(props, TestWiring.deterministicRanker(extractor),
            org.mockito.Mockito.mock(PartRanker.class), () -> null, TestWiring.scoreCache(Duration.ofHours(1)));
    private final StockRefresher refresher = TestWiring.wire(new StockRefresher(), "properties", props);

    private static Part resistor(String number, String value, Instant fetchedAt) {
        return RankingFixtures.part(Distributor.MOUSER, number, "YAGEO", "RC0603FR-07" + value + "L",
                "Thick Film Resistors - SMD " + value + " OHM 1% 1/10W 0603", "Thick Film Resistors - SMD", "0603", 5000,
                "0.01", Map.of("Resistance", value + " Ohms", "Tolerance", "1 %"), Map.of()).toBuilder()
                .fetchedAt(fetchedAt).build();
    }

    /** A TE part number with nothing the extractor can read: family unknown, nothing stated confirmed. */
    private static Part connector(String number, Instant fetchedAt) {
        return RankingFixtures.part(Distributor.MOUSER, number, "TE Connectivity", number.substring(4),
                "TE Connectivity " + number.substring(4), null, null, 900, "1.00", Map.of(), Map.of()).toBuilder()
                .fetchedAt(fetchedAt).build();
    }

    private List<RankedPart> ranked(Part... parts) {
        ParsedQuery parsed = new QueryParser().parse(QUERY);
        Map<Distributor, List<Part>> fetched = new EnumMap<>(Distributor.class);
        fetched.put(Distributor.MOUSER, List.of(parts));
        return ranking.rank(parsed, fetched, Duration.ofSeconds(5)).byDistributor().get(Distributor.MOUSER);
    }

    private static List<String> numbers(List<RankedPart> list) {
        return list.stream().map(r -> r.part().distributorPartNumber()).toList();
    }

    @Test
    void theConnectorsOfTheIncidentConfirmNothingAndTheResistorsConfirmEverything() {
        List<RankedPart> list = ranked(resistor("603-RC0603FR-074K7L", "4.7K", STALE),
                connector("571-1-2199298-2", FRESH));
        Map<String, Double> match = new java.util.HashMap<>();
        list.forEach(r -> match.put(r.part().distributorPartNumber(), r.match()));
        assertThat(match.get("603-RC0603FR-074K7L")).isEqualTo(1.0);
        assertThat(match.get("571-1-2199298-2")).isEqualTo(0.0);
    }

    /** The incident: two fresh TE connectors (match 0) above stale 4.7k 0603 resistors (match 1.0). */
    @Test
    void freshPartsThatConfirmNothingNeverPassStaleExactMatches() {
        List<RankedPart> list = ranked(connector("571-1-2199298-2", FRESH), connector("571-2-1445057-2", FRESH),
                resistor("603-RC0603FR-074K7L", "4.7K", STALE), resistor("603-RC0603FR-104K7L", "4.7K", STALE));
        assertThat(numbers(list).subList(0, 2)).as("ranking: the match class first")
                .containsExactlyInAnyOrder("603-RC0603FR-074K7L", "603-RC0603FR-104K7L");

        List<RankedPart> demoted = refresher.demoteStale(list, NOW);
        assertThat(numbers(demoted)).containsExactly(numbers(list).get(0), numbers(list).get(1),
                numbers(list).get(2), numbers(list).get(3));
        assertThat(demoted.subList(0, 2)).allMatch(r -> r.match() != null && r.match() > 0);
        assertThat(demoted.subList(2, 4)).allMatch(r -> r.match() != null && r.match() == 0);
    }

    @Test
    void staleDemotionStillAppliesWithinAMatchClass() {
        Part staleExact = resistor("S-EXACT", "4.7K", STALE);
        Part freshExact = resistor("F-EXACT", "4.7K", FRESH);
        Part staleConnector = connector("571-OLD-1", STALE);
        Part freshConnector = connector("571-NEW-1", FRESH);
        List<RankedPart> list = new ArrayList<>(ranked(staleExact, freshExact, staleConnector, freshConnector));

        List<RankedPart> demoted = refresher.demoteStale(list, NOW);
        assertThat(numbers(demoted)).containsExactly("F-EXACT", "S-EXACT", "571-NEW-1", "571-OLD-1");
        // the stale parts lose the penalty (reported at least 0); the scores of the fresh ones are unchanged
        assertThat(demoted.get(1).score()).isZero();
        assertThat(demoted.get(3).score()).isZero();
    }

    @Test
    void aStalePartWithAPartialMatchStillRanksAboveFreshPartsThatConfirmNothing() {
        RankedPart partial = new RankedPart(resistor("S-PARTIAL", "4.7K", STALE), 0.9, 0.5,
                List.of("tolerance: 5% instead of 1%"), List.of(), false);
        RankedPart fresh = new RankedPart(connector("571-FRESH-2", FRESH), 0.8, 0.0, List.of(), List.of("resistance"),
                false);
        RankedPart freshBelowSpec = new RankedPart(resistor("F-BELOW", "4.7K", FRESH), 0.7, 0.6, List.of(),
                List.of(), true);
        List<RankedPart> demoted = refresher.demoteStale(List.of(partial, freshBelowSpec, fresh), NOW);
        assertThat(numbers(demoted)).as("meeting the request before below spec, then the match class")
                .containsExactly("S-PARTIAL", "F-BELOW", "571-FRESH-2");

        // not graded (query not understood): one class, the stale part goes last
        RankedPart ungraded = new RankedPart(resistor("S-UNGRADED", "4.7K", STALE), 0.9, null);
        RankedPart freshUngraded = new RankedPart(connector("571-FRESH-3", FRESH), 0.8, null);
        assertThat(numbers(refresher.demoteStale(List.of(ungraded, freshUngraded), NOW)))
                .containsExactly("571-FRESH-3", "S-UNGRADED");
    }
}
