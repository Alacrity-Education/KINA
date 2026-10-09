package ro.alacrity.kina.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
import ro.alacrity.kina.domain.PartLookupResponse;
import ro.alacrity.kina.domain.PartResponse;
import ro.alacrity.kina.domain.ResponseDetail;
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
public class PartsController {

    @Autowired private PartSearchService searchService;
    @Autowired private PartLookupService lookupService;

    /**
     * {@code GET /api/v1/parts/search?q=&max_results=&distributors=LCSC,TME&bypass_cache=&quantity=&detail=
     * &allow_below_spec=}.
     */
    @GetMapping("/search")
    public SearchResponse search(
            @RequestParam("q") @NotBlank String q,
            @RequestParam(name = "max_results", required = false) @Min(1) @Max(SearchRequest.MAX_MAX_RESULTS)
            Integer maxResults,
            @RequestParam(name = "distributors", required = false) List<String> distributors,
            @RequestParam(name = "bypass_cache", defaultValue = "false") boolean bypassCache,
            @RequestParam(name = "quantity", required = false) @Min(1) @Max(SearchRequest.MAX_QUANTITY)
            Integer quantity,
            @RequestParam(name = "detail", required = false) String detail,
            @RequestParam(name = "allow_below_spec", defaultValue = "false") boolean allowBelowSpec,
            Authentication authentication) {
        Set<Distributor> selected = parseDistributors(distributors);
        log.debug("search '{}' by {}", q, user(authentication));
        return searchService.search(SearchRequest.of(q, maxResults, selected, bypassCache, quantity,
                ResponseDetail.parse(detail), allowBelowSpec));
    }

    /** {@code POST /api/v1/parts/search/batch} with a snake_case {@link BatchSearchRequest} body. */
    @PostMapping("/search/batch")
    public BatchSearchResponse searchBatch(@Valid @RequestBody BatchSearchRequest request,
                                           Authentication authentication) {
        log.debug("batch search of {} queries by {}", request.queries().size(), user(authentication));
        return searchService.searchBatch(request);
    }

    /**
     * {@code GET /api/v1/parts/{distributor}/{partNumber}?bypass_cache=&quantity=&detail=} ({@code detail} defaults to
     * {@code full}: every attribute of the one part). The part number is the rest of the path, so TME symbols
     * containing {@code /} work unencoded. The request firewall and Tomcat stay strict: a percent-encoded {@code %},
     * slash or backslash is refused (HTTP 400); such part numbers use {@link #getPartByParameter} (DESIGN.md 5).
     */
    @GetMapping("/{distributor}/{*partNumber}")
    public PartResponse getPart(@PathVariable("distributor") String distributor,
                                @PathVariable("partNumber") String partNumber,
                                @RequestParam(name = "bypass_cache", defaultValue = "false") boolean bypassCache,
                                @RequestParam(name = "quantity", required = false) @Min(1)
                                @Max(SearchRequest.MAX_QUANTITY) Integer quantity,
                                @RequestParam(name = "detail", required = false) String detail,
                                Authentication authentication) {
        String number = partNumber.startsWith("/") ? partNumber.substring(1) : partNumber;
        return lookup(distributor, number, bypassCache, quantity, detail, authentication);
    }

    /**
     * {@code GET /api/v1/parts/{distributor}?part_number=&bypass_cache=&quantity=&detail=}: the same lookup as
     * {@link #getPart} with the part number in a query parameter, the documented form for part numbers with characters
     * a path does not carry plainly ({@code %}, {@code \}, {@code /}, {@code +}, spaces): percent-encode the value
     * ({@code +} as {@code %2B}, a plain {@code +} in a query string is a space).
     */
    @GetMapping(value = "/{distributor}", params = "part_number")
    public PartResponse getPartByParameter(@PathVariable("distributor") String distributor,
                                           @RequestParam("part_number") String partNumber,
                                           @RequestParam(name = "bypass_cache", defaultValue = "false")
                                           boolean bypassCache,
                                           @RequestParam(name = "quantity", required = false) @Min(1)
                                           @Max(SearchRequest.MAX_QUANTITY) Integer quantity,
                                           @RequestParam(name = "detail", required = false) String detail,
                                           Authentication authentication) {
        return lookup(distributor, partNumber, bypassCache, quantity, detail, authentication);
    }

    /**
     * {@code GET /api/v1/parts/lookup?distributor=&part_number=&bypass_cache=&quantity=&detail=}: the same lookup as
     * {@link #getPart} with the part number in a query parameter, so any part number works percent-encoded
     * ({@code %25}, {@code %2F}, {@code %5C}), which the path refuses.
     */
    @GetMapping("/lookup")
    public PartResponse lookupPart(@RequestParam("distributor") String distributor,
                                   @RequestParam("part_number") String partNumber,
                                   @RequestParam(name = "bypass_cache", defaultValue = "false") boolean bypassCache,
                                   @RequestParam(name = "quantity", required = false) @Min(1)
                                   @Max(SearchRequest.MAX_QUANTITY) Integer quantity,
                                   @RequestParam(name = "detail", required = false) String detail,
                                   Authentication authentication) {
        return lookup(distributor, partNumber, bypassCache, quantity, detail, authentication);
    }

    private PartResponse lookup(String distributor, String number, boolean bypassCache, Integer quantity,
                                String detail, Authentication authentication) {
        Distributor d = Distributor.parse(distributor);
        if (number.isBlank()) {
            throw new IllegalArgumentException("part number must not be blank");
        }
        log.debug("get {} {} by {}", d, number, user(authentication));
        PartLookupResponse result = lookupService.lookup(d, number, bypassCache, quantity == null ? 1 : quantity,
                ResponseDetail.parse(detail, ResponseDetail.FULL));
        if (!result.found() || result.part() == null) {
            throw new PartNotFoundException(d, number.strip(), result.reason(), result.identity());
        }
        return result.part();
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
