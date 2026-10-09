package ro.alacrity.kina.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.Deadline;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.distributor.DistributorRegistry;
import ro.alacrity.kina.distributor.StockUpdate;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.metrics.KinaMetrics;
import ro.alacrity.kina.search.RankingService.RankedPart;
import ro.alacrity.kina.search.RankingService.RankedResults;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Stock refresh stage of a search (DESIGN.md 3.2 "Stock refresh"): refreshes the cached stock and prices of the parts
 * about to be returned and moves stale parts below the fresh ones. Built by {@link PartSearchService}.
 */
@Slf4j
@Component
final class StockRefresher {

    /** Rounds of refreshing the parts about to be returned (a sold-out part pulls the next one into the top). */
    static final int STOCK_REFRESH_ROUNDS = 2;

    @Autowired private KinaProperties properties;
    @Autowired private DistributorRegistry registry;
    @Autowired private PartCacheRepository partCache;
    @Autowired private Clock clock;
    @Autowired private KinaMetrics metrics = KinaMetrics.NOOP;

    RankedResults refresh(Prepared prepared, RankedResults ranked, Deadline deadline) {
        Instant now = clock.instant();
        Instant staleBefore = now.minus(properties.cache().stockTtl());
        Map<Distributor, List<RankedPart>> out = new EnumMap<>(Distributor.class);
        boolean changed = false;
        for (Map.Entry<Distributor, List<RankedPart>> entry : ranked.byDistributor().entrySet()) {
            Distributor distributor = entry.getKey();
            List<RankedPart> list = entry.getValue();
            if (!DistributorRetriever.usesPostgresCache(distributor) || list.isEmpty()) {
                out.put(distributor, list);
                continue;
            }
            Optional<DistributorClient> client = registry.find(distributor).filter(DistributorClient::isConfigured);
            List<RankedPart> kept = new ArrayList<>(list);
            Set<String> attempted = new HashSet<>();
            for (int round = 0; client.isPresent() && round < STOCK_REFRESH_ROUNDS && deadline.remainingNanos() > 0;
                 round++) {
                // the top max_results, and a requested part listed without stock (it may take the last place, and it
                // may be back in stock: the same 24 h rule)
                List<RankedPart> current = kept;
                int top = Math.min(prepared.maxResults(), current.size());
                List<String> due = java.util.stream.IntStream.range(0, current.size())
                        .filter(i -> i < top || current.get(i).part().stock() <= 0)
                        .mapToObj(i -> current.get(i).part())
                        .filter(p -> p.fetchedAt() == null || p.fetchedAt().isBefore(staleBefore))
                        .map(Part::distributorPartNumber)
                        .filter(attempted::add)
                        .toList();
                if (due.isEmpty()) {
                    break;
                }
                Map<String, StockUpdate> updates;
                try {
                    updates = client.get().refreshStock(due, deadline);
                } catch (DistributorException e) {
                    log.info("{} stock refresh of {} parts failed, keeping the cached figures: {}", distributor,
                            due.size(), e.getMessage());
                    metrics.stockRefreshed(distributor, "failed", due.size());
                    break;
                } catch (RuntimeException e) {
                    log.warn("{} stock refresh failed unexpectedly", distributor, e);
                    metrics.stockRefreshed(distributor, "failed", due.size());
                    break;
                }
                List<Part> refreshed = new ArrayList<>();
                List<Part> stillListed = new ArrayList<>();
                List<String> soldOut = new ArrayList<>();
                List<RankedPart> next = new ArrayList<>(kept.size());
                for (RankedPart r : kept) {
                    StockUpdate update = updates == null ? null : updates.get(r.part().distributorPartNumber());
                    if (update == null) {
                        next.add(r);
                    } else if (update.stock() <= 0 && r.part().stock() <= 0) {
                        // a requested part listed without stock stays listed, with its new prices and age
                        Part p = r.part().toBuilder().stock(0)
                                .prices(update.prices().isEmpty() ? r.part().prices() : update.prices())
                                .fetchedAt(now).build();
                        stillListed.add(p);
                        next.add(new RankedPart(p, r.score(), r.match(), r.mismatches(), r.unverified(),
                                r.belowSpec()));
                    } else if (update.stock() <= 0) {
                        soldOut.add(r.part().distributorPartNumber());
                    } else {
                        Part p = r.part().toBuilder().stock(update.stock())
                                .prices(update.prices().isEmpty() ? r.part().prices() : update.prices())
                                .fetchedAt(now).build();
                        refreshed.add(p);
                        next.add(new RankedPart(p, r.score(), r.match(), r.mismatches(), r.unverified(),
                                r.belowSpec()));
                    }
                }
                kept = next;
                changed = true;
                log.info("{} stock refresh: {} parts updated, {} sold out", distributor, refreshed.size(),
                        soldOut.size());
                metrics.stockRefreshed(distributor, "ok", refreshed.size() + stillListed.size());
                metrics.stockRefreshed(distributor, "out_of_stock", soldOut.size());
                metrics.stockRefreshed(distributor, "failed",
                        due.size() - refreshed.size() - stillListed.size() - soldOut.size());
                writeBack(distributor, refreshed, soldOut);
                if (!stillListed.isEmpty()) {
                    try {
                        partCache.upsertListed(stillListed);
                    } catch (RuntimeException e) {
                        log.warn("Writing refreshed listed {} parts failed: {}", distributor, e.toString());
                    }
                }
                if (soldOut.isEmpty()) {
                    break;
                }
            }
            List<RankedPart> ordered = demoteStale(kept, now);
            changed |= ordered != kept;
            out.put(distributor, List.copyOf(ordered));
        }
        return changed ? ranked.withParts(out) : ranked;
    }

    /**
     * Moves the parts whose stock and prices are stale ({@link #isStale}) below the fresh ones of their group: each
     * stale part's score is lowered by {@code kina.cache.stale-rank-penalty} (reported at least 0) and it is placed
     * before the first part of a later group, or the first fresh part of its own group that scores lower; stale parts
     * keep their relative order. The groups, in order: the match class first (a part that confirms some stated
     * parameter, then one that confirms none: {@code match} 0, DESIGN.md 3.3), then meeting the request before below
     * spec. A stale part is never moved below a part of a later group, so a fresh part that confirms nothing never
     * passes a stale exact match. With the default penalty of 1.0 every stale part ends up after every fresh part of
     * its group. Returns {@code list} itself when nothing is stale.
     */
    List<RankedPart> demoteStale(List<RankedPart> list, Instant now) {
        if (list.stream().noneMatch(r -> isStale(r.part(), now))) {
            return list;
        }
        double penalty = properties.cache().staleRankPenalty();
        List<RankedPart> out = new ArrayList<>(list.size());
        List<RankedPart> stale = new ArrayList<>();
        for (RankedPart r : list) {
            (isStale(r.part(), now) ? stale : out).add(r);
        }
        for (RankedPart r : stale) {
            double adjusted = r.score() - penalty;
            int group = group(r);
            // a later-group part counts only after the last part of this group or an earlier one (a requested part
            // that leads the list keeps its place whatever its group)
            int lastOfGroup = -1;
            for (int i = 0; i < out.size(); i++) {
                if (group(out.get(i)) <= group) {
                    lastOfGroup = i;
                }
            }
            int at = out.size();
            for (int i = 0; i < out.size(); i++) {
                RankedPart other = out.get(i);
                boolean laterGroup = i > lastOfGroup && group(other) > group;
                boolean sameGroupLower = group(other) == group && !isStale(other.part(), now)
                        && other.score() < adjusted;
                if (laterGroup || sameGroupLower) {
                    at = i;
                    break;
                }
            }
            out.add(at, r.withScore(Math.max(0, adjusted)));
        }
        return out;
    }

    /**
     * The group of a ranked part for {@link #demoteStale}: the match class (0 confirms some stated parameter or is not
     * graded, 2 confirms none), plus 1 below spec.
     */
    private static int group(RankedPart r) {
        return (RankingService.confirmsNothing(r.match()) ? 2 : 0) + (r.belowSpec() ? 1 : 0);
    }

    private void writeBack(Distributor distributor, List<Part> refreshed, List<String> soldOut) {
        try {
            partCache.updateStock(refreshed);
            soldOut.forEach(number -> partCache.markSoldOut(distributor, number));
        } catch (RuntimeException e) {
            log.warn("Writing refreshed {} stock to the cache failed: {}", distributor, e.toString());
        }
    }

    /**
     * True when the part's stock and prices are older than {@code kina.cache.ttl} (Mouser and TME only: LCSC is read
     * from its local database).
     */
    boolean isStale(Part part, Instant now) {
        return DistributorRetriever.usesPostgresCache(part.distributor()) && part.fetchedAt() != null
                && part.fetchedAt().isBefore(now.minus(properties.cache().ttl()));
    }
}
