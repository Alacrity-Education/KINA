package ro.alacrity.kina.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.DistributorRegistry;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ResponseDetail;
import ro.alacrity.kina.domain.SearchRequest;
import ro.alacrity.kina.domain.SearchResponse;
import ro.alacrity.kina.search.PartSearchService;

import java.io.Serial;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * The Search tab of the web UI (DESIGN.md section 6, "Web UI"), the default page: {@code GET /} and {@code /search}.
 * A plain GET form (bookmarkable, no CSRF); with {@code q} present it runs {@link PartSearchService#search}, the same
 * path the MCP tool and the REST API use, and renders the response. Distributor text is always escaped
 * ({@code th:text}); links are rendered only for http(s) URLs ({@link SearchView}).
 */
@Controller
@Slf4j
public class SearchPageController {

    /** The {@code max_results} choices; those above {@code kina.search.max-max-results} are left out. */
    static final int[] MAX_RESULTS_CHOICES = {1, 3, 5, 10, 20, 50};
    static final int MAX_QUERY_LENGTH = 300;

    @Autowired private DistributorRegistry registry;
    @Autowired private PartSearchService searchService;
    @Autowired private PublicUrlResolver urls;
    @Autowired private KinaProperties properties;

    /** One distributor checkbox of the form. */
    public record DistributorOption(String name, boolean configured, boolean checked) {
    }

    /** The form values, as submitted or the defaults. */
    public record Form(String q, int maxResults, String detail, int quantity, boolean bypassCache,
                       boolean allowBelowSpec) {
    }

    /** Thrown for a form value that cannot be used; its message is shown on the page. */
    static final class FormException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        FormException(String message) {
            super(message);
        }
    }

    @GetMapping({"/", "/search"})
    public ModelAndView search(@RequestParam(name = "q", required = false) String q,
                               @RequestParam(name = "distributors", required = false) List<String> distributors,
                               @RequestParam(name = "max_results", required = false) String maxResults,
                               @RequestParam(name = "detail", required = false) String detail,
                               @RequestParam(name = "quantity", required = false) String quantity,
                               @RequestParam(name = "bypass_cache", required = false) String bypassCache,
                               @RequestParam(name = "allow_below_spec", required = false) String allowBelowSpec,
                               Authentication authentication) {
        ModelAndView view = WebTabs.view("search", WebTabs.SEARCH, WebTabs.user(authentication));
        view.addObject("mcpUrl", urls.mcpUrl());
        view.addObject("tokensEnabled", properties.tokens().uiEnabled());
        view.addObject("maxResultsChoices", IntStream.of(MAX_RESULTS_CHOICES)
                .filter(n -> n <= properties.search().maxMaxResults()).boxed().toList());
        Set<Distributor> configured = registry.configured();
        int defaultMax = properties.search().defaultMaxResults();
        Form form = new Form(q, intOr(maxResults, defaultMax), "full".equalsIgnoreCase(detail) ? "full" : "compact",
                intOr(quantity, 1), flag(bypassCache), flag(allowBelowSpec));
        view.addObject("form", form);
        Set<Distributor> checked = configured;
        try {
            checked = distributors(distributors, configured);
            if (q == null) {
                return view;
            }
            SearchRequest request = request(form, maxResults, quantity, detail, checked);
            long started = System.nanoTime();
            SearchResponse response = searchService.search(request);
            view.addObject("result", response);
            view.addObject("elapsedMs", (System.nanoTime() - started) / 1_000_000);
            view.addObject("parsedItems", SearchView.parsedItems(response.parsed()));
            view.addObject("sections", response.distributors().stream().map(SearchView::section).toList());
        } catch (FormException | IllegalArgumentException e) {
            view.addObject("error", e.getMessage());
            view.setStatus(HttpStatus.BAD_REQUEST);
        } catch (RuntimeException e) {
            log.warn("Web search failed: {}", e.toString());
            view.addObject("error", "The search failed. Please try again.");
        } finally {
            Set<Distributor> shown = checked;
            view.addObject("distributorOptions", Arrays.stream(Distributor.values())
                    .map(d -> new DistributorOption(d.name(), configured.contains(d), shown.contains(d)))
                    .toList());
        }
        return view;
    }

    private SearchRequest request(Form form, String maxResults, String quantity, String detail,
                                  Set<Distributor> distributors) {
        String query = form.q() == null ? "" : form.q().strip();
        if (query.isEmpty()) {
            throw new FormException("Please enter what you are looking for.");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new FormException("The query must be at most " + MAX_QUERY_LENGTH + " characters.");
        }
        int max = parse(maxResults, properties.search().defaultMaxResults(), "max_results");
        if (max < 1 || max > Math.min(SearchRequest.MAX_MAX_RESULTS, properties.search().maxMaxResults())) {
            throw new FormException("max_results must be between 1 and "
                    + Math.min(SearchRequest.MAX_MAX_RESULTS, properties.search().maxMaxResults()) + ".");
        }
        int qty = parse(quantity, 1, "quantity");
        if (qty < 1 || qty > SearchRequest.MAX_QUANTITY) {
            throw new FormException("quantity must be between 1 and " + SearchRequest.MAX_QUANTITY + ".");
        }
        return new SearchRequest(query, max, distributors, form.bypassCache(), qty, ResponseDetail.parse(detail),
                form.allowBelowSpec());
    }

    /** The checked distributors; none checked (or none sent) means every configured one, as in the API. */
    private static Set<Distributor> distributors(List<String> names, Set<Distributor> configured) {
        if (names == null || names.stream().allMatch(n -> n == null || n.isBlank())) {
            return configured;
        }
        EnumSet<Distributor> result = EnumSet.noneOf(Distributor.class);
        for (String name : names) {
            for (String part : name.split(",")) {
                if (!part.isBlank()) {
                    result.add(Distributor.parse(part));
                }
            }
        }
        return result;
    }

    private static int parse(String value, int fallback, String name) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new FormException(name + " must be a whole number.");
        }
    }

    /** The value for redisplay: the number when it is one, else the fallback. */
    private static int intOr(String value, int fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean flag(String value) {
        if (value == null) {
            return false;
        }
        String v = value.strip().toLowerCase(Locale.ROOT);
        return v.equals("true") || v.equals("on") || v.equals("1");
    }
}
