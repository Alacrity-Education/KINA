package ro.alacrity.kina.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import ro.alacrity.kina.TestcontainersConfiguration;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.search.DistributorPhraser;
import ro.alacrity.kina.search.field.PhraseJournalBackfill;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The phrase journal (V15, DESIGN.md 3.2 "Field-first flow"): a row per asked phrase, fresh for {@code kina.cache.ttl}
 * (an empty answer for {@code empty-result-ttl}), purged with the cached searches (2 x ttl), filled once from the
 * cached searches of the history without replacing an answer a search recorded.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PhraseJournalRepositoryTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final Duration TTL = Duration.ofDays(3);
    private static final Duration EMPTY_TTL = Duration.ofHours(1);

    @Autowired PhraseJournalRepository journal;
    @Autowired SearchCacheRepository searches;
    @Autowired CacheMaintenance maintenance;
    @Autowired PhraseJournalBackfill backfill;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM distributor_phrases").update();
        jdbc.sql("DELETE FROM cached_searches").update();
    }

    private static PhraseJournalRepository.Entry entry(String phrase, Instant at, boolean empty) {
        return new PhraseJournalRepository.Entry(Distributor.MOUSER, DistributorPhraser.phraseKey(phrase), phrase, at,
                120, 50, false, 3, empty, 0, "q");
    }

    @Test
    void anEntryIsStoredAndReplaced() {
        journal.record(entry("10uF X7R 0805", NOW, false));
        PhraseJournalRepository.Entry read = journal.find(Distributor.MOUSER, "0805 10uf x7r").orElseThrow();
        assertThat(read.phrase()).isEqualTo("10uF X7R 0805");
        assertThat(read.rawTotal()).isEqualTo(120);
        assertThat(read.nextOffset()).isEqualTo(50);
        assertThat(read.outOfStock()).isEqualTo(3);
        assertThat(read.exhausted()).isFalse();
        assertThat(read.askedAt()).isEqualTo(NOW);
        assertThat(journal.find(Distributor.TME, "0805 10uf x7r")).as("per distributor").isEmpty();

        journal.record(new PhraseJournalRepository.Entry(Distributor.MOUSER, "0805 10uf x7r", "10uF X7R 0805",
                NOW.plusSeconds(5), null, null, true, null, false, 1, "other"));
        read = journal.find(Distributor.MOUSER, "0805 10uf x7r").orElseThrow();
        assertThat(read.rawTotal()).isNull();
        assertThat(read.exhausted()).isTrue();
        assertThat(read.ladderStep()).isEqualTo(1);
        assertThat(journal.count()).isEqualTo(1);
    }

    @Test
    void aPhraseIsFreshForTheTtlAndAnEmptyAnswerForTheShorterEmptyTtl() {
        PhraseJournalRepository.Entry twoHours = entry("a", NOW.minus(Duration.ofHours(2)), false);
        PhraseJournalRepository.Entry twoHoursEmpty = entry("b", NOW.minus(Duration.ofHours(2)), true);
        PhraseJournalRepository.Entry fourDays = entry("c", NOW.minus(Duration.ofDays(4)), false);
        assertThat(twoHours.isFresh(NOW, TTL, EMPTY_TTL)).isTrue();
        assertThat(twoHoursEmpty.isFresh(NOW, TTL, EMPTY_TTL)).as("empty answers expire after the empty ttl").isFalse();
        assertThat(entry("d", NOW.minus(Duration.ofMinutes(30)), true).isFresh(NOW, TTL, EMPTY_TTL)).isTrue();
        assertThat(fourDays.isFresh(NOW, TTL, EMPTY_TTL)).isFalse();
    }

    @Test
    void theBackfillNeverReplacesAnAnswerThatASearchRecorded() {
        journal.record(entry("10uF X7R 0805", NOW, false));
        assertThat(journal.recordIfAbsent(entry("10uF X7R 0805", NOW.minus(Duration.ofDays(1)), true))).isFalse();
        assertThat(journal.find(Distributor.MOUSER, "0805 10uf x7r").orElseThrow().empty()).isFalse();
        assertThat(journal.recordIfAbsent(entry("other", NOW, false))).isTrue();
    }

    @Test
    void theCachedSearchesOfTheHistoryBecomeAskedPhrases() {
        searches.upsert(new CachedSearch(Distributor.MOUSER, "10uf x7r 0805", 90, List.of("A", "B"), false,
                NOW.minus(Duration.ofHours(5)), 50, null, 4));
        searches.upsert(new CachedSearch(Distributor.TME, "22uf 1206 x5r", 0, List.of(), true,
                NOW.minus(Duration.ofMinutes(10)), 0));
        searches.upsert(new CachedSearch(Distributor.MOUSER, "10uf x7r 0805 old", 10, List.of("A"), true,
                NOW.minus(Duration.ofDays(8))));

        assertThat(backfill.run()).as("the search older than 2 x ttl is left out").isEqualTo(2);
        PhraseJournalRepository.Entry mouser = journal.find(Distributor.MOUSER,
                DistributorPhraser.phraseKey("10uf x7r 0805")).orElseThrow();
        assertThat(mouser.rawTotal()).isEqualTo(90);
        assertThat(mouser.nextOffset()).isEqualTo(50);
        assertThat(mouser.outOfStock()).isEqualTo(4);
        assertThat(mouser.empty()).isFalse();
        assertThat(mouser.askedAt()).isEqualTo(NOW.minus(Duration.ofHours(5)));
        assertThat(mouser.isFresh(NOW, TTL, EMPTY_TTL)).isTrue();
        assertThat(journal.find(Distributor.TME, DistributorPhraser.phraseKey("22uf 1206 x5r")).orElseThrow().empty())
                .isTrue();
    }

    @Test
    void thePurgeDeletesPhrasesOlderThanTwiceTheTtlLikeTheCachedSearches() {
        journal.record(entry("old", NOW.minus(Duration.ofDays(7)), false));
        journal.record(entry("recent", NOW.minus(Duration.ofDays(5)), false));
        searches.upsert(new CachedSearch(Distributor.MOUSER, "q7", 0, List.of(), true, NOW.minus(Duration.ofDays(7))));

        CacheMaintenance.PurgeResult result = maintenance.purge();
        assertThat(result.phrases()).isEqualTo(1);
        assertThat(result.searches()).isEqualTo(1);
        assertThat(journal.find(Distributor.MOUSER, DistributorPhraser.phraseKey("old"))).isEmpty();
        assertThat(journal.find(Distributor.MOUSER, DistributorPhraser.phraseKey("recent"))).isPresent();
    }
}
