package ro.alacrity.kina.search;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
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
@Component
final class ResponseAssembler {

    @Autowired private KinaProperties properties;
    @Autowired private ParametricExtractor extractor;
    @Autowired private RankingService ranking;
    @Autowired private StockRefresher staleness;
    @Autowired private Clock clock;

    /** {@code photos}: also fill {@code photo_url} in compact detail (the web Search tab's thumbnails). */
    SearchResponse assemble(Prepared prepared, Map<Distributor, Fetched> fetched,
                            RankedResults ranked, String note, boolean photos) {
        SearchRequest request = prepared.request();
        ParsedQuery parsed = prepared.parsed();
        ConstraintPolicy policy = ConstraintPolicy.of(ranking);
        boolean understood = parsed.understood();
        int lowStockThreshold = properties.search().lowStockThreshold();
        Instant now = clock.instant();
        List<DistributorResult> results = new ArrayList<>();
        List<String> empty = new ArrayList<>();
        Map<String, Integer> emptyExcluded = new java.util.LinkedHashMap<>();
        int emptyBelowSpec = 0;
        List<String> unconfirmed = new ArrayList<>();
        Map<String, Integer> unconfirmedNames = new java.util.LinkedHashMap<>();
        Map<String, Integer> unconfirmedExcluded = new java.util.LinkedHashMap<>();
        int unconfirmedBelowSpec = 0;
        boolean anyConfirmed = false;
        for (Distributor distributor : prepared.distributors()) {
            Fetched f = fetched.get(distributor);
            if (f == null) {
                continue;
            }
            List<RankedPart> rankedParts = ranked.byDistributor().getOrDefault(distributor, List.of());
            int returned = Math.min(prepared.maxResults(), rankedParts.size());
            List<RankedPart> top = withListedPart(rankedParts, returned);
            List<PartResponse> parts = new ArrayList<>(returned);
            for (int i = 0; i < returned; i++) {
                RankedPart rp = top.get(i);
                Map<String, String> canonical = request.detail() == ResponseDetail.FULL ? null
                        : extractor.extract(rp.part());
                PartResponse part = PartResponse.of(rp.part(), new PartResponse.Ranking(i + 1,
                                roundScore(rp.score()), rp.match(), rp.mismatches(), rp.unverified(), rp.belowSpec()),
                        request.quantity(), request.detail(), canonical, lowStockThreshold,
                        staleness.isStale(rp.part(), now), now);
                parts.add(photos ? part.toBuilder().photoUrl(rp.part().photoUrl()).build() : part);
            }
            Integer exact = understood
                    ? (int) top.stream().filter(r -> r.exact() && r.part().stock() > 0).count() : null;
            Map<String, Integer> detail = ranked.excludedDetailBy(distributor);
            String hint = null;
            if (understood && parts.isEmpty() && f.error() == null) {
                // nothing satisfies the hard constraints (DESIGN.md 3.2 "Empty after the hard set"): no substitutes
                hint = policy.hint(parsed, List.of(distributor.name()), detail,
                        ranked.excludedBelowSpecBy(distributor), request.allowBelowSpec());
                empty.add(distributor.name());
                detail.forEach((k, v) -> emptyExcluded.merge(k, v, Integer::sum));
                emptyBelowSpec += ranked.excludedBelowSpecBy(distributor);
            } else if (understood && f.error() == null && exact != null && exact == 0) {
                Map<String, Integer> names = unconfirmed(top, returned);
                if (!names.isEmpty()) {
                    // parts are returned, but every one leaves a stated constraint unverified: none confirms the
                    // request (DESIGN.md 3.2 "Unconfirmed parts"); they stay flagged, the hint says so
                    hint = policy.unconfirmedHint(parsed, List.of(distributor.name()), byFrequency(names), detail,
                            ranked.excludedBelowSpecBy(distributor), request.allowBelowSpec());
                    unconfirmed.add(distributor.name());
                    names.forEach((k, v) -> unconfirmedNames.merge(k, v, Integer::sum));
                    detail.forEach((k, v) -> unconfirmedExcluded.merge(k, v, Integer::sum));
                    unconfirmedBelowSpec += ranked.excludedBelowSpecBy(distributor);
                }
            }
            anyConfirmed |= exact != null && exact > 0;
            Boolean requestedFound = null;
            if (parsed.namesPartNumber() && !(f.error() != null && top.isEmpty())) {
                List<String> missing = parsed.partNumbers().stream()
                        .filter(n -> top.stream().noneMatch(r -> r.part().stock() > 0
                                && PartNumbers.requests(n, r.part()))).toList();
                requestedFound = missing.isEmpty();
                String requestedHint = missing.isEmpty() ? null
                        : requestedHint(distributor, missing, ranked.excludedRequestedBy(distributor), top,
                        request.allowBelowSpec());
                hint = requestedHint == null ? hint : hint == null ? requestedHint : requestedHint + " " + hint;
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
                    .requestedPartFound(requestedFound)
                    .excludedBelowSpecDetail(ranked.belowSpecDetailBy(distributor))
                    .fetchedLive(fetchedLive(f))
                    .liveCalls(liveCalls(f))
                    .fieldSteps(f.fieldSteps() == null ? 0 : f.fieldSteps())
                    .build());
        }
        String hint = !understood ? SearchResponse.NOT_UNDERSTOOD_HINT
                : !empty.isEmpty() ? policy.hint(parsed, empty, emptyExcluded, emptyBelowSpec, request.allowBelowSpec())
                : !unconfirmed.isEmpty() && !anyConfirmed
                ? policy.unconfirmedHint(parsed, unconfirmed, byFrequency(unconfirmedNames), unconfirmedExcluded,
                        unconfirmedBelowSpec, request.allowBelowSpec())
                : null;
        return new SearchResponse(parsed.originalText(), ParsedQueryResponse.from(parsed), ranked.mode(), note,
                results, understood, hint, SearchResponse.currenciesOf(results));
    }

    /**
     * The constraints the returned in-stock parts leave unverified, with how many parts leave each, when every one of
     * them leaves at least one (none confirms the request); empty otherwise.
     */
    static Map<String, Integer> unconfirmed(List<RankedPart> top, int returned) {
        Map<String, Integer> names = new java.util.LinkedHashMap<>();
        boolean any = false;
        for (int i = 0; i < returned; i++) {
            RankedPart r = top.get(i);
            if (r.part().stock() <= 0) {
                continue;
            }
            if (r.unverified().isEmpty()) {
                return Map.of();
            }
            any = true;
            r.unverified().forEach(n -> names.merge(n, 1, Integer::sum));
        }
        return any ? names : Map.of();
    }

    /** The keys of {@code counts}, the largest count first (ties in insertion order). */
    private static List<String> byFrequency(Map<String, Integer> counts) {
        return counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey).toList();
    }

    /**
     * Whether the search called the distributor: {@code live_calls > 0} when the calls are counted (always in a
     * search), else as the retrieval says, else from the cache status ({@code miss}, {@code partial} and
     * {@code bypassed} called it).
     */
    static boolean fetchedLive(Fetched f) {
        if (f.liveCalls() != null) {
            return f.liveCalls() > 0;
        }
        if (f.fetchedLive() != null) {
            return f.fetchedLive();
        }
        return f.cache() == ro.alacrity.kina.cache.CacheStatus.MISS
                || f.cache() == ro.alacrity.kina.cache.CacheStatus.PARTIAL
                || f.cache() == ro.alacrity.kina.cache.CacheStatus.BYPASSED;
    }

    /** The distributor search calls of the retrieval ({@code live_calls}): 0 when not counted. */
    static int liveCalls(Fetched f) {
        return f.liveCalls() == null ? 0 : f.liveCalls();
    }

    /**
     * The hint of a distributor whose parts do not include a part number the query names (DESIGN.md 3.4 "Requested
     * part numbers"): per missing part number, why it was left out when the distributor listed it ({@code EPC2218 is
     * listed at MOUSER but was left out: voltage 80V below 100V}), else that it is not listed in stock there; then what
     * the parts are.
     */
    static String requestedHint(Distributor distributor, List<String> missing,
                                List<RankingService.ExcludedRequest> leftOut, List<RankedPart> returned,
                                boolean allowBelowSpec) {
        List<String> sentences = new ArrayList<>();
        boolean others = returned.stream().anyMatch(r -> r.part().stock() > 0);
        for (String number : missing) {
            RankedPart listed = returned.stream()
                    .filter(r -> r.part().stock() <= 0 && PartNumbers.requests(number, r.part())).findFirst()
                    .orElse(null);
            if (listed != null) {
                sentences.add(number + " is not in stock at " + distributor.name() + ": " + listed.part()
                        .distributorPartNumber() + " is listed without stock and shown last, with stock 0");
                continue;
            }
            RankingService.ExcludedRequest why = leftOut.stream().filter(e -> e.partNumber().equals(number))
                    .findFirst().orElse(null);
            if (why == null) {
                sentences.add(number + " is not listed in stock at " + distributor.name());
                continue;
            }
            String part = why.part().manufacturerPartNumber() != null ? why.part().manufacturerPartNumber()
                    : why.part().distributorPartNumber();
            boolean rating = why.reason().contains(" below ") || why.reason().contains(" above ");
            sentences.add(number + " is listed at " + distributor.name() + " (" + part + ") but was left out: "
                    + (rating ? why.reason() + (allowBelowSpec ? "" : " (pass \"allow_below_spec\": true to see it)")
                    : "it contradicts the requested " + why.reason()));
        }
        return String.join("; ", sentences) + (others ? "; the parts below are keyword matches." : ".");
    }

    /**
     * The top {@code returned} parts; when a requested part listed without stock (stock 0) ranks below them, it takes
     * the last place (with at least 2 places), so an explicit part-number search shows it while it stays below every
     * part in stock.
     */
    static List<RankedPart> withListedPart(List<RankedPart> ranked, int returned) {
        List<RankedPart> top = ranked.subList(0, returned);
        if (returned < 2 || ranked.size() <= returned || top.stream().anyMatch(r -> r.part().stock() <= 0)) {
            return top;
        }
        return ranked.subList(returned, ranked.size()).stream().filter(r -> r.part().stock() <= 0).findFirst()
                .map(listed -> {
                    List<RankedPart> out = new ArrayList<>(top.subList(0, returned - 1));
                    out.add(listed);
                    return List.copyOf(out);
                })
                .orElse(top);
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
