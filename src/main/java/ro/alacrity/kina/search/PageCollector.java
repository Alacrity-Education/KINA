package ro.alacrity.kina.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.distributor.ApiQuotaTracker;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.distributor.DistributorSearchPage;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.metrics.KinaMetrics;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Paging stage of a search (DESIGN.md 3.2): reads the pages of one query from a distributor until enough parts that
 * meet the request are held. Built by {@link PartSearchService}.
 */
@Slf4j
@Component
final class PageCollector {

    /**
     * Pages read beyond {@code max-pages-per-search} while every match so far was out of stock (DESIGN.md 3.2): the
     * part exists, the next page may hold a shippable one.
     */
    static final int EXTRA_OUT_OF_STOCK_PAGES = 2;

    @Autowired private ParametricExtractor extractor;
    @Autowired private Clock clock;
    @Autowired private KinaMetrics metrics = KinaMetrics.NOOP;

    record Collected(List<Part> all, List<Part> fetched, Integer totalResults, boolean exhausted, int nextOffset,
                     String error, List<String> relaxed, List<String> droppedKeywords, int meeting) {

        Fetched toFetched(Distributor distributor, CacheStatus cache) {
            return toFetched(distributor, cache, null);
        }

        Fetched toFetched(Distributor distributor, CacheStatus cache, String fallbackQuery) {
            return new Fetched(distributor, all, totalResults, cache, error, fallbackQuery)
                    .withConstraintsRelaxed(relaxed).withDroppedKeywords(droppedKeywords);
        }
    }

    /**
     * Which collected parts meet the request ({@link #meets}) and which of those have every requested rating
     * verified ({@link #confirmed}).
     */
    record Check(java.util.function.Predicate<Part> meets, java.util.function.Predicate<Part> confirmed) {

        static Check of(RankingService ranking, ParsedQuery parsed) {
            return new Check(p -> meets(ranking, parsed, p), p -> confirmed(ranking, parsed, p));
        }

        /**
         * True when the part meets the request (DESIGN.md 3.2): no known attribute contradicts a strict constraint
         * and no known rating is below the request. Paging and the relaxation ladder go on until a part meets it.
         */
        static boolean meets(RankingService ranking, ParsedQuery parsed, Part part) {
            RankingService.Verdict v = verdict(ranking, parsed, part);
            return v == RankingService.Verdict.MEETS || v == RankingService.Verdict.UNVERIFIED_RATING;
        }

        /**
         * True when the part meets the request with every requested rating stated: paging stops only for such parts.
         */
        static boolean confirmed(RankingService ranking, ParsedQuery parsed, Part part) {
            return verdict(ranking, parsed, part) == RankingService.Verdict.MEETS;
        }

        static RankingService.Verdict verdict(RankingService ranking, ParsedQuery parsed, Part part) {
            try {
                RankingService.Verdict v = ranking.verdict(parsed, part);
                return v == null ? RankingService.Verdict.MEETS : v;
            } catch (RuntimeException e) {
                log.warn("checking {} against '{}' failed", part.key(), parsed.normalizedKey(), e);
                return RankingService.Verdict.MEETS;
            }
        }

        /** True when the part would be returned: it meets the request, or it is only below spec and that is allowed. */
        static boolean returnable(RankingService ranking, ParsedQuery parsed, Part part, boolean allowBelowSpec) {
            RankingService.Verdict v = verdict(ranking, parsed, part);
            return v != RankingService.Verdict.CONSTRAINT && (allowBelowSpec || v != RankingService.Verdict.BELOW_SPEC);
        }
    }

    /**
     * Pages through {@link DistributorClient#search} from {@code offset} until {@code window} parts are held and at
     * least one of them meets the request with its ratings verified ({@code meets}; DESIGN.md 3.2: a rating is never
     * in the phrase, so the parts that satisfy it may sit on later pages), the distributor reports no more results,
     * {@code maxPages} pages were requested or the next page would not fit within {@code deadline}. Pages have the distributor's
     * {@link DistributorClient#maxPageSize()} (the first one is shortened to end on a page boundary) because
     * distributors drop parts without ships-now stock: paging is driven by raw record offsets, not by the number of
     * parts kept. A failure on the first page propagates; a failure on a later page keeps what was collected and
     * reports the error code. {@code family} is the parser family of the request, the {@code type} tag of the page
     * metrics (DESIGN.md 3.7).
     */
    Collected collect(DistributorClient client, String query, int offset, int window, int maxPages,
                      List<Part> existing, Progress progress, DistributorBudget deadline, Check meets,
                      String family) {
        Distributor distributor = client.distributor();
        boolean pagedByRecords = DistributorRetriever.usesPostgresCache(distributor);
        int outOfStock = 0;
        List<String> relaxed = List.of();
        List<String> droppedKeywords = List.of();
        int pageSize = Math.max(1, client.maxPageSize());
        List<Part> all = new ArrayList<>(existing);
        List<Part> fetched = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        existing.forEach(p -> seen.add(p.distributorPartNumber()));
        int meeting = (int) existing.stream().filter(meets.meets()).count();
        int confirmed = (int) existing.stream().filter(meets.confirmed()).count();
        Integer total = progress.totalResults;
        boolean hasMore = true;
        int next = Math.max(0, offset);
        int pages = 0;
        long lastPageNanos = 0;
        String error = null;
        // while every match so far is out of stock, up to EXTRA_OUT_OF_STOCK_PAGES more pages (DESIGN.md 3.2)
        while ((pages < maxPages || all.isEmpty() && outOfStock > 0 && pages < maxPages + EXTRA_OUT_OF_STOCK_PAGES)
                && hasMore && (all.size() < window || confirmed == 0)) {
            if (pages > 0 && deadline.remainingNanos() < lastPageNanos) {
                log.debug("{}: no time for another page of '{}'", distributor, query);
                break;
            }
            int limit = pagedByRecords ? pageSize - next % pageSize : Math.min(window, pageSize);
            long started = System.nanoTime();
            long waitedBefore = deadline.rateLimitWaitedNanos();
            DistributorSearchPage page;
            if (ApiQuotaTracker.isTracked(client.distributor())) {
                progress.liveCalls.incrementAndGet();   // counted when made: a failed call spent quota too
            }
            try {
                page = client.search(query, next, limit, deadline.deadline());
            } catch (DistributorException e) {
                if (pages == 0) {
                    throw e;
                }
                log.info("{}: page {} of '{}' failed, keeping {} parts: {}", distributor, pages + 1, query,
                        all.size(), e.getMessage());
                error = e.errorCode();
                break;
            }
            // active time of the page: rate-limit waits do not predict how long the next page takes
            lastPageNanos = Math.max(0, System.nanoTime() - started - (deadline.rateLimitWaitedNanos() - waitedBefore));
            pages++;
            metrics.distributorPage(distributor, family, System.nanoTime() - started, page.parts().size());
            next += limit;
            total = page.totalResults();
            hasMore = page.hasMore();
            outOfStock += page.outOfStock();
            if (!page.relaxed().isEmpty()) {
                relaxed = page.relaxed();
            }
            if (!page.droppedKeywords().isEmpty()) {
                droppedKeywords = page.droppedKeywords();
            }
            progress.outOfStock += page.outOfStock();
            Instant now = clock.instant();
            for (Part part : page.parts()) {
                if (part == null || part.stock() <= 0 || !seen.add(part.distributorPartNumber())) {
                    continue;
                }
                Part enriched = extractor.enrich(
                        part.fetchedAt() == null ? part.toBuilder().fetchedAt(now).build() : part);
                all.add(enriched);
                fetched.add(enriched);
                if (meets.meets().test(enriched)) {
                    meeting++;
                    if (meets.confirmed().test(enriched)) {
                        confirmed++;
                    }
                }
            }
            progress.parts = List.copyOf(all);
            progress.totalResults = total;
        }
        return new Collected(all, fetched, total, !hasMore, next, error, relaxed, droppedKeywords, meeting);
    }
}
