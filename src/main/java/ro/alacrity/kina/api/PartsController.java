package ro.alacrity.kina.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.alacrity.kina.domain.BatchSearchRequest;
import ro.alacrity.kina.domain.BatchSearchResponse;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.PartResponse;
import ro.alacrity.kina.domain.SearchRequest;
import ro.alacrity.kina.domain.SearchResponse;
import ro.alacrity.kina.search.PartLookupService;
import ro.alacrity.kina.search.PartSearchService;
import ro.alacrity.kina.security.KinaPrincipal;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Part search and lookup (DESIGN.md section 5). Errors are RFC 9457 problem details ({@link ApiExceptionHandler}). */
@RestController
@RequestMapping("/api/v1/parts")
@Slf4j
@RequiredArgsConstructor
public class PartsController {

    private final PartSearchService searchService;
    private final PartLookupService lookupService;

    /** {@code GET /api/v1/parts/search?q=&max_results=&distributors=LCSC,TME&bypass_cache=}. */
    @GetMapping("/search")
    public SearchResponse search(
            @RequestParam("q") @NotBlank String q,
            @RequestParam(name = "max_results", required = false) @Min(1) @Max(SearchRequest.MAX_MAX_RESULTS)
            Integer maxResults,
            @RequestParam(name = "distributors", required = false) List<String> distributors,
            @RequestParam(name = "bypass_cache", defaultValue = "false") boolean bypassCache,
            Authentication authentication) {
        Set<Distributor> selected = parseDistributors(distributors);
        log.debug("search '{}' by {}", q, user(authentication));
        return searchService.search(SearchRequest.of(q, maxResults, selected, bypassCache));
    }

    /** {@code POST /api/v1/parts/search/batch} with a snake_case {@link BatchSearchRequest} body. */
    @PostMapping("/search/batch")
    public BatchSearchResponse searchBatch(@Valid @RequestBody BatchSearchRequest request,
                                           Authentication authentication) {
        log.debug("batch search of {} queries by {}", request.queries().size(), user(authentication));
        return searchService.searchBatch(request);
    }

    /**
     * {@code GET /api/v1/parts/{distributor}/{partNumber}?bypass_cache=}. The part number is the rest of the path,
     * so TME symbols containing {@code /} work unencoded.
     */
    @GetMapping("/{distributor}/{*partNumber}")
    public PartResponse getPart(@PathVariable("distributor") String distributor,
                                @PathVariable("partNumber") String partNumber,
                                @RequestParam(name = "bypass_cache", defaultValue = "false") boolean bypassCache,
                                Authentication authentication) {
        Distributor d = Distributor.parse(distributor);
        String number = partNumber.startsWith("/") ? partNumber.substring(1) : partNumber;
        if (number.isBlank()) {
            throw new IllegalArgumentException("part number must not be blank");
        }
        log.debug("get {} {} by {}", d, number, user(authentication));
        return lookupService.getPart(d, number, bypassCache)
                .orElseThrow(() -> new PartNotFoundException(d, number.strip()));
    }

    static Set<Distributor> parseDistributors(List<String> names) {
        Set<Distributor> out = EnumSet.noneOf(Distributor.class);
        if (names != null) {
            for (String name : names) {
                if (name == null) {
                    continue;
                }
                for (String token : name.split(",")) {
                    if (!token.isBlank()) {
                        out.add(Distributor.parse(token));
                    }
                }
            }
        }
        return out;
    }

    static Object user(Authentication authentication) {
        return KinaPrincipal.from(authentication).map(KinaPrincipal::userId).orElse(null);
    }
}
