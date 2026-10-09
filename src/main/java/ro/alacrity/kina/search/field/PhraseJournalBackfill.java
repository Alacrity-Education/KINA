package ro.alacrity.kina.search.field;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.cache.PhraseJournalRepository;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.search.ConstraintPolicy;
import ro.alacrity.kina.search.DistributorPhraser;
import ro.alacrity.kina.search.QueryParser;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fills the phrase journal ({@code distributor_phrases}, migration V15) from the existing {@code cached_searches} rows,
 * so the history counts as already asked when the field-first search starts ({@code kina.search.field-index.mode=on}):
 * once in the background after {@link ApplicationReadyEvent} (never blocking startup; only in that mode). It only reads
 * {@code cached_searches} and only adds journal rows ({@link PhraseJournalRepository#recordIfAbsent}: an answer the
 * searches recorded is never replaced), so running it again changes nothing.
 *
 * <p>A cached search of query {@code q} with no {@code fallback_query} asked the phrase {@code q} was sent as
 * ({@link DistributorPhraser#phrase}, else the text) and answered {@code total_results}, {@code next_offset},
 * {@code exhausted} and the out-of-stock count at {@code fetched_at}. One with a {@code fallback_query} asked the
 * ladder phrases up to it: each earlier phrase found nothing that met the request (their counts are unknown), the
 * fallback phrase carries the row's counts. Only rows younger than {@code 2 x kina.cache.ttl} are read (the purge
 * deletes older journal rows).
 */
@Slf4j
@Component
public class PhraseJournalBackfill {

    private static final int BATCH = 500;

    @Autowired private KinaProperties properties;
    @Autowired private JdbcClient jdbc;
    @Autowired private PhraseJournalRepository journal;
    @Autowired private QueryParser parser;
    @Autowired private Clock clock;

    private final AtomicBoolean running = new AtomicBoolean();

    /** After startup, in {@code on} mode: one background run. */
    @EventListener(ApplicationReadyEvent.class)
    void onApplicationReady() {
        if (properties.search().fieldIndex().mode() == KinaProperties.FieldIndexMode.ON) {
            Thread.ofVirtual().name("phrase-journal-backfill").start(this::run);
        }
    }

    private record Row(Distributor distributor, String queryKey, Instant fetchedAt, Integer totalResults,
                       Integer nextOffset, boolean exhausted, String fallbackQuery, Integer outOfStock,
                       boolean empty) {
    }

    /** One run; returns the journal rows added (0 when another run is in progress or it failed). Never throws. */
    public int run() {
        if (!running.compareAndSet(false, true)) {
            return 0;
        }
        try {
            long start = System.nanoTime();
            Instant since = clock.instant().minus(properties.cache().ttl().multipliedBy(2));
            int added = 0;
            int rows = 0;
            String lastDistributor = "";
            String lastKey = "";
            while (true) {
                List<Row> batch = new ArrayList<>(BATCH);
                jdbc.sql("""
                                SELECT distributor, query_key, fetched_at, total_results, next_offset, exhausted,
                                       fallback_query, out_of_stock_matches,
                                       jsonb_array_length(part_numbers) = 0 AS empty
                                FROM cached_searches
                                WHERE (distributor, query_key) > (?, ?) AND fetched_at >= ?
                                ORDER BY distributor, query_key LIMIT ?""")
                        .params(lastDistributor, lastKey, since.atOffset(java.time.ZoneOffset.UTC), BATCH)
                        .query(rs -> {
                            Distributor d;
                            try {
                                d = Distributor.valueOf(rs.getString("distributor"));
                            } catch (IllegalArgumentException e) {
                                return;
                            }
                            int total = rs.getInt("total_results");
                            Integer totalResults = rs.wasNull() ? null : total;
                            int offset = rs.getInt("next_offset");
                            Integer nextOffset = rs.wasNull() ? null : offset;
                            int oos = rs.getInt("out_of_stock_matches");
                            Integer outOfStock = rs.wasNull() ? null : oos;
                            batch.add(new Row(d, rs.getString("query_key"),
                                    rs.getObject("fetched_at", java.time.OffsetDateTime.class).toInstant(),
                                    totalResults, nextOffset, rs.getBoolean("exhausted"),
                                    rs.getString("fallback_query"), outOfStock, rs.getBoolean("empty")));
                        });
                if (batch.isEmpty()) {
                    break;
                }
                for (Row row : batch) {
                    rows++;
                    try {
                        added += backfill(row);
                    } catch (RuntimeException e) {
                        log.debug("Journal backfill of {} '{}' failed: {}", row.distributor(), row.queryKey(),
                                e.toString());
                    }
                }
                Row last = batch.getLast();
                lastDistributor = last.distributor().name();
                lastKey = last.queryKey();
                if (batch.size() < BATCH) {
                    break;
                }
            }
            log.info("Phrase journal backfill: {} cached searches read, {} phrases added; {} ms", rows, added,
                    (System.nanoTime() - start) / 1_000_000);
            return added;
        } catch (RuntimeException e) {
            log.warn("Phrase journal backfill failed; the history is only partly in the journal: {}", e.toString());
            return 0;
        } finally {
            running.set(false);
        }
    }

    /** The journal rows of one cached search; returns how many were added. */
    private int backfill(Row row) {
        ParsedQuery parsed = parser.parse(row.queryKey());
        String phrase = DistributorPhraser.phrase(row.distributor(), parsed);
        String sent = phrase != null ? phrase : parsed.originalText();
        int added = 0;
        if (row.fallbackQuery() == null) {
            return add(row, sent, 0, row.totalResults(), row.nextOffset(), row.exhausted(), row.outOfStock(),
                    row.empty());
        }
        // the phrase of the request, then each ladder phrase up to the one the list was built from
        List<DistributorPhraser.Relaxation> ladder = DistributorPhraser.ladder(row.distributor(), parsed, sent,
                ConstraintPolicy.DEFAULTS);
        String fallbackKey = DistributorPhraser.phraseKey(row.fallbackQuery());
        int upTo = -1;
        for (int i = 0; i < ladder.size(); i++) {
            if (DistributorPhraser.phraseKey(ladder.get(i).phrase()).equals(fallbackKey)) {
                upTo = i;
                break;
            }
        }
        added += add(row, sent, 0, null, null, false, null, false);
        for (int i = 0; i < upTo; i++) {
            added += add(row, ladder.get(i).phrase(), i + 1, null, null, false, null, false);
        }
        return added + add(row, row.fallbackQuery(), Math.max(1, upTo + 1), row.totalResults(), row.nextOffset(), row.exhausted(),
                row.outOfStock(), row.empty());
    }

    private int add(Row row, String phrase, int step, Integer total, Integer nextOffset, boolean exhausted,
                    Integer outOfStock, boolean empty) {
        String key = DistributorPhraser.phraseKey(phrase);
        if (key.isBlank()) {
            return 0;
        }
        return journal.recordIfAbsent(new PhraseJournalRepository.Entry(row.distributor(), key, phrase,
                row.fetchedAt(), total, nextOffset, exhausted, outOfStock, empty, step, row.queryKey())) ? 1 : 0;
    }
}
