package ro.alacrity.kina.search;

import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.DistributorResult;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.ParsedQueryResponse;
import ro.alacrity.kina.domain.PartResponse;
import ro.alacrity.kina.domain.ResponseDetail;
import ro.alacrity.kina.domain.SearchRequest;
import ro.alacrity.kina.domain.SearchResponse;
import ro.alacrity.kina.search.RankingService.RankedPart;
import ro.alacrity.kina.search.RankingService.RankedResults;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Assembly stage of a search: turns the fetched and ranked parts into the {@link SearchResponse} (per-distributor
 * entries, hints, relaxed constraints). Built by {@link PartSearchService}.
 */
final class ResponseAssembler {

    private final KinaProperties properties;
    private final ParametricExtractor extractor;
    private final RankingService ranking;
    private final StockRefresher staleness;
    private final Clock clock;

    ResponseAssembler(KinaProperties properties, ParametricExtractor extractor, RankingService ranking,
                      StockRefresher staleness, Clock clock) {
        this.properties = properties;
        this.extractor = extractor;
        this.ranking = ranking;
        this.staleness = staleness;
        this.clock = clock;
    }

    /** The hard / relaxable constraint table ({@link RankingService#policy()}; the defaults when not available). */
    static ConstraintPolicy policyOf(RankingService ranking) {
        ConstraintPolicy p = ranking == null ? null : ranking.policy();
        return p == null ? ConstraintPolicy.DEFAULTS : p;
    }

    SearchResponse assemble(Prepared prepared, Map<Distributor, Fetched> fetched,
                            RankedResults ranked, String note) {
        SearchRequest request = prepared.request();
        ParsedQuery parsed = prepared.parsed();
        ConstraintPolicy policy = policyOf(ranking);
        boolean understood = parsed.understood();
        int lowStockThreshold = properties.search().lowStockThreshold();
        Instant now = clock.instant();
        List<DistributorResult> results = new ArrayList<>();
        List<String> empty = new ArrayList<>();
        Map<String, Integer> emptyExcluded = new java.util.LinkedHashMap<>();
        int emptyBelowSpec = 0;
        for (Distributor distributor : prepared.distributors()) {
            Fetched f = fetched.get(distributor);
            if (f == null) {
                continue;
            }
            List<RankedPart> rankedParts = ranked.byDistributor().getOrDefault(distributor, List.of());
            int returned = Math.min(prepared.maxResults(), rankedParts.size());
            List<RankedPart> top = rankedParts.subList(0, returned);
            List<PartResponse> parts = new ArrayList<>(returned);
            for (int i = 0; i < returned; i++) {
                RankedPart rp = top.get(i);
                Map<String, String> canonical = request.detail() == ResponseDetail.FULL ? null
                        : extractor.extract(rp.part());
                parts.add(PartResponse.of(rp.part(), new PartResponse.Ranking(i + 1, roundScore(rp.score()),
                                rp.match(), rp.mismatches(), rp.unverified(), rp.belowSpec()),
                        request.quantity(), request.detail(), canonical, lowStockThreshold,
                        staleness.isStale(rp.part(), now), now));
            }
            Integer exact = understood ? (int) top.stream().filter(RankedPart::exact).count() : null;
            Map<String, Integer> detail = ranked.excludedDetailBy(distributor);
            String hint = null;
            if (understood && parts.isEmpty() && f.error() == null) {
                // nothing satisfies the hard constraints (DESIGN.md 3.2 "Empty after the hard set"): no substitutes
                hint = policy.hint(parsed, List.of(distributor.name()), detail,
                        ranked.excludedBelowSpecBy(distributor), request.allowBelowSpec());
                empty.add(distributor.name());
                detail.forEach((k, v) -> emptyExcluded.merge(k, v, Integer::sum));
                emptyBelowSpec += ranked.excludedBelowSpecBy(distributor);
            }
            results.add(DistributorResult.builder()
                    .distributor(distributor)
                    .totalResults(f.totalResults())
                    .fetched(f.parts().size())
                    .returned(returned)
                    .cache(f.cache())
                    .error(f.error())
                    .parts(parts)
                    .fallbackQuery(f.fallbackQuery())
                    .rateLimitWaitedMs(f.rateLimitWaitedMs())
                    .distributorQuery(f.distributorQuery())
                    .excludedByConstraints(ranked.excludedBy(distributor))
                    .excludedByConstraintsDetail(detail)
                    .excludedBelowSpec(ranked.excludedBelowSpecBy(distributor))
                    .outOfStockMatches(f.outOfStockMatches())
                    .queryTermsDropped(f.queryTermsDropped())
                    .constraintsRelaxed(actuallyRelaxed(relaxable(parsed, f.constraintsRelaxed(), policy), top))
                    .exactMatches(exact)
                    .hint(hint)
                    .build());
        }
        String hint = !understood ? SearchResponse.NOT_UNDERSTOOD_HINT
                : empty.isEmpty() ? null
                : policy.hint(parsed, empty, emptyExcluded, emptyBelowSpec, request.allowBelowSpec());
        return new SearchResponse(parsed.originalText(), ParsedQueryResponse.from(parsed), ranked.mode(), note,
                results, understood, hint, SearchResponse.currenciesOf(results));
    }

    /**
     * The loosened constraints that may be reported as relaxed: those the policy lets relax for the request's family
     * (a hard constraint or a rating is never relaxed, even when LCSC's database search dropped its term: the ranker
     * excludes the parts that miss it).
     */
    static List<String> relaxable(ParsedQuery parsed, List<String> loosened, ConstraintPolicy policy) {
        if (loosened == null || loosened.isEmpty()) {
            return List.of();
        }
        return loosened.stream().filter(name -> policy.isRelaxable(parsed, name)).toList();
    }

    /**
     * Of the constraints a relaxation loosened (the ladder step of Mouser and TME, the terms LCSC's database search
     * dropped), those the returned parts really miss: a mismatch or an unverified constraint of that name. A step that
     * drops the dielectric and the package may still return parts with the requested dielectric, and LCSC drops terms
     * one at a time, so a loosened term is not necessarily one the results compromise (DESIGN.md 3.2).
     */
    static List<String> actuallyRelaxed(List<String> dropped, List<RankedPart> returned) {
        if (dropped == null || dropped.isEmpty()) {
            return List.of();
        }
        return dropped.stream()
                .filter(name -> returned.stream().anyMatch(r -> r.unverified().contains(name)
                        || r.mismatches().stream().anyMatch(m -> m.startsWith(name + ":"))))
                .toList();
    }

    static double roundScore(double score) {
        return Math.round(score * 1e4) / 1e4;
    }
}
