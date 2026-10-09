package ro.alacrity.kina.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ro.alacrity.kina.domain.Distributor;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static ro.alacrity.kina.cache.PartCacheRepository.utc;

/**
 * {@code distributor_phrases} (migration V15, DESIGN.md 3.2 "Field-first flow" and 8): the phrases a distributor was
 * already asked, and when. One row per (distributor, normalised phrase); asking a phrase again replaces its row. The
 * field-first search skips the distributor call of a step whose phrase has a fresh row.
 */
@Slf4j
@Repository
public class PhraseJournalRepository {

    @Autowired private JdbcClient jdbc;

    /**
     * One answered phrase.
     *
     * @param phraseKey   the phrase normalised like a query key
     * @param phrase      the phrase as sent
     * @param askedAt     when the distributor answered
     * @param rawTotal    the distributor's result count for the phrase, null when unknown
     * @param nextOffset  the raw record offset where the next page starts, null when unknown
     * @param exhausted   true when the distributor had no more records
     * @param outOfStock  records matched without ships-now stock, null when unknown
     * @param empty       true when no in-stock part came back
     * @param ladderStep  0 for the phrase of the request, n for the n-th relaxation phrase
     * @param queryKey    the normalised query that asked it, null when unknown
     */
    public record Entry(Distributor distributor, String phraseKey, String phrase, Instant askedAt, Integer rawTotal,
                        Integer nextOffset, boolean exhausted, Integer outOfStock, boolean empty, int ladderStep,
                        String queryKey) {

        /**
         * Fresh for {@code ttl}, except an empty answer, which is fresh only for {@code emptyTtl} (whichever is
         * shorter), the rule of a cached search list.
         */
        public boolean isFresh(Instant now, Duration ttl, Duration emptyTtl) {
            Duration limit = empty && emptyTtl.compareTo(ttl) < 0 ? emptyTtl : ttl;
            return !askedAt.isBefore(now.minus(limit));
        }
    }

    public Optional<Entry> find(Distributor distributor, String phraseKey) {
        return jdbc.sql("""
                        SELECT phrase, asked_at, raw_total, next_offset, exhausted, out_of_stock, empty, ladder_step,
                               query_key
                        FROM distributor_phrases WHERE distributor = ? AND phrase_key = ?""")
                .params(distributor.name(), phraseKey)
                .query((rs, n) -> {
                    int total = rs.getInt("raw_total");
                    Integer rawTotal = rs.wasNull() ? null : total;
                    int offset = rs.getInt("next_offset");
                    Integer nextOffset = rs.wasNull() ? null : offset;
                    int oos = rs.getInt("out_of_stock");
                    Integer outOfStock = rs.wasNull() ? null : oos;
                    return new Entry(distributor, phraseKey, rs.getString("phrase"),
                            rs.getObject("asked_at", java.time.OffsetDateTime.class).toInstant(), rawTotal,
                            nextOffset, rs.getBoolean("exhausted"), outOfStock, rs.getBoolean("empty"),
                            rs.getInt("ladder_step"), rs.getString("query_key"));
                })
                .optional();
    }

    /** Inserts or replaces the row of {@code (distributor, phraseKey)}. */
    public void record(Entry entry) {
        jdbc.sql("""
                        INSERT INTO distributor_phrases
                          (distributor, phrase_key, phrase, asked_at, raw_total, next_offset, exhausted, out_of_stock,
                           empty, ladder_step, query_key)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (distributor, phrase_key) DO UPDATE SET
                          phrase = EXCLUDED.phrase, asked_at = EXCLUDED.asked_at, raw_total = EXCLUDED.raw_total,
                          next_offset = EXCLUDED.next_offset, exhausted = EXCLUDED.exhausted,
                          out_of_stock = EXCLUDED.out_of_stock, empty = EXCLUDED.empty,
                          ladder_step = EXCLUDED.ladder_step, query_key = EXCLUDED.query_key""")
                .params(entry.distributor().name(), entry.phraseKey(), entry.phrase(), utc(entry.askedAt()),
                        entry.rawTotal(), entry.nextOffset(), entry.exhausted(), entry.outOfStock(), entry.empty(),
                        entry.ladderStep(), entry.queryKey())
                .update();
    }

    /**
     * Inserts the row unless the phrase already has one (the history a backfill adds never replaces an answer the
     * searches recorded). Returns true when a row was added.
     */
    public boolean recordIfAbsent(Entry entry) {
        return jdbc.sql("""
                        INSERT INTO distributor_phrases
                          (distributor, phrase_key, phrase, asked_at, raw_total, next_offset, exhausted, out_of_stock,
                           empty, ladder_step, query_key)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (distributor, phrase_key) DO NOTHING""")
                .params(entry.distributor().name(), entry.phraseKey(), entry.phrase(), utc(entry.askedAt()),
                        entry.rawTotal(), entry.nextOffset(), entry.exhausted(), entry.outOfStock(), entry.empty(),
                        entry.ladderStep(), entry.queryKey())
                .update() > 0;
    }

    /** Rows in the journal. */
    public long count() {
        return jdbc.sql("SELECT count(*) FROM distributor_phrases").query(Long.class).single();
    }

    /** Deletes the rows asked before {@code cutoff}; returns the number deleted. */
    public int deleteOlderThan(Instant cutoff) {
        return jdbc.sql("DELETE FROM distributor_phrases WHERE asked_at < ?").param(utc(cutoff)).update();
    }
}
