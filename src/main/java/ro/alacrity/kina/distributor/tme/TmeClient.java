package ro.alacrity.kina.distributor.tme;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.ApiQuotaTracker;
import ro.alacrity.kina.distributor.Deadline;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.distributor.DistributorException.Kind;
import ro.alacrity.kina.distributor.DistributorSearchPage;
import ro.alacrity.kina.distributor.PartLookupResult;
import ro.alacrity.kina.distributor.RateLimitRetry;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * TME distributor client (API v2, OAuth2 client credentials; DESIGN.md section 9.2).
 *
 * <p>Search: one {@code /products/search} call per page (filtered to in-stock products), then {@code /products/data}
 * (prices, stock), {@code /products/parameters} and {@code /products/files} (datasheets) for the page's symbols in
 * parallel, batches of at most 50 symbols. Parts with {@code stock_quantity <= 0} are dropped.
 *
 * <p>Paging: TME pages are 1-based with a fixed page size ({@link #maxPageSize()} = {@code max-results-per-search}
 * clamped to the live-verified maximum {@code limit} of 100). A 0-based {@code offset} maps to
 * {@code page = offset / pageSize + 1} and an in-page skip of {@code offset % pageSize}.
 *
 * <p>Rate limits: every call (token included) goes through one shared {@link RateLimitRetry}, so a cool-down seen by
 * one request also holds back the others (DESIGN.md 3.6).
 */
@Component
@Slf4j
public class TmeClient implements DistributorClient {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(20);
    /** {@code phrase} length bounds from the OpenAPI document. */
    static final int MIN_PHRASE_LENGTH = 2;
    static final int MAX_PHRASE_LENGTH = 40;

    @Autowired private KinaProperties properties;
    /** Counts the HTTP requests (token requests included) against the quota (DESIGN.md 3.7); null without Spring. */
    @Autowired private ApiQuotaTracker quota;
    private RestClient restClient = defaultRestClient();
    private Clock clock = Clock.systemUTC();
    private Executor executor = task -> Thread.ofVirtual().name("tme-fetch").start(task);
    private RateLimitRetry retry = new RateLimitRetry(Distributor.TME);
    private final AtomicBoolean filesDisabled = new AtomicBoolean(false);
    private KinaProperties.Tme config;
    private TmeApi api;

    @PostConstruct
    void init() {
        config = properties.distributors().tme();
        if (quota != null) {
            retry.quota(quota);
        }
        TmeTokenManager tokens = new TmeTokenManager(restClient, config.baseUrl(),
                nullToEmpty(config.token()), nullToEmpty(config.secret()), clock, retry);
        api = new TmeApi(restClient, tokens, config.baseUrl(), config.language(), retry);
    }

    static RestClient defaultRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public Distributor distributor() {
        return Distributor.TME;
    }

    @Override
    public boolean isConfigured() {
        return config.isConfigured();
    }

    @Override
    public int maxPageSize() {
        return Math.clamp(config.maxResultsPerSearch(), 1, TmeApi.MAX_SEARCH_LIMIT);
    }

    @Override
    public DistributorSearchPage search(String query, int offset, int limit) throws DistributorException {
        return search(query, offset, limit, Deadline.immediate());
    }

    /** Waits for TME rate limits (HTTP 429) within {@code deadline} (DESIGN.md 3.6). */
    @Override
    public DistributorSearchPage search(String query, int offset, int limit, Deadline deadline)
            throws DistributorException {
        requireConfigured();
        String phrase = phrase(query);
        if (limit <= 0) {
            return DistributorSearchPage.empty();
        }
        int pageSize = maxPageSize();
        int safeOffset = Math.max(0, offset);
        int page = safeOffset / pageSize + 1;
        int skip = safeOffset % pageSize;

        TmeResponses.SearchResponse response = api.search(phrase, config.country(), pageSize, page, deadline);
        TmeResponses.SearchData data = response.data();
        List<TmeResponses.Product> elements = data == null || data.products() == null
                || data.products().elements() == null ? List.of() : data.products().elements();
        TmeResponses.Counters counters = data == null ? null : data.counters();
        int total = counters != null && counters.count() != null ? counters.count() : elements.size();
        int pages = counters != null && counters.pages() != null ? counters.pages() : page;

        int from = Math.min(skip, elements.size());
        int to = (int) Math.min((long) skip + limit, elements.size());
        List<Part> parts = enrich(elements.subList(from, to), deadline);
        boolean hasMore = to < elements.size() || page < pages;
        // matched records without ships-now stock (stock 0 despite filter[in_stock], or a status that does not ship)
        return new DistributorSearchPage(parts, total, hasMore, (to - from) - parts.size());
    }

    @Override
    public Optional<Part> getPart(String distributorPartNumber) throws DistributorException {
        return getPart(distributorPartNumber, Deadline.immediate());
    }

    @Override
    public Optional<Part> getPart(String distributorPartNumber, Deadline deadline) throws DistributorException {
        return lookup(distributorPartNumber, deadline).asOptional();
    }

    /**
     * {@code /products} by symbol; on a miss, once more by manufacturer part number ({@code mpns[]} with the input as
     * written and with letters and digits only, so {@code ERA-6AEB5361V} finds TME's {@code ERA6AEB5361V}), accepting a
     * product whose symbol or manufacturer symbol equals the input after {@link PartLookupResult#normalize}. A listed
     * product without ships-now stock ({@code stock_quantity} 0, or an excluded {@code product_status}) is
     * {@code OUT_OF_STOCK}, with the listed part (stock 0).
     */
    @Override
    public PartLookupResult lookup(String partNumber, Deadline deadline) throws DistributorException {
        requireConfigured();
        if (partNumber == null || partNumber.isBlank()) {
            return PartLookupResult.notFound();
        }
        String symbol = partNumber.strip();
        List<String> variants = PartLookupResult.variants(symbol);
        List<TmeResponses.Product> products = List.of();
        for (String variant : variants) {
            products = refusedAsMissing(() -> api.products(List.of(variant), config.country(), deadline)).stream()
                    .filter(p -> p.symbol() != null && (p.symbol().equalsIgnoreCase(variant)
                            || PartLookupResult.samePartNumber(symbol, p.symbol())))
                    .limit(1)
                    .toList();
            if (!products.isEmpty()) {
                break;
            }
        }
        if (products.isEmpty()) {
            List<String> mpns = new ArrayList<>(new LinkedHashSet<>(variants));
            mpns.add(PartLookupResult.normalize(symbol));
            mpns = new ArrayList<>(new LinkedHashSet<>(mpns));
            mpns.removeIf(String::isBlank);
            List<String> candidates = mpns;
            products = refusedAsMissing(() -> api.productsByMpn(candidates, config.country(), deadline)).stream()
                    .filter(p -> PartLookupResult.samePartNumber(symbol, p.symbol())
                            || p.manufacturerSymbols() != null && p.manufacturerSymbols().stream()
                            .anyMatch(m -> PartLookupResult.samePartNumber(symbol, m)))
                    .toList();
        }
        if (products.isEmpty()) {
            return PartLookupResult.notFound();
        }
        List<Part> parts = enrich(products, deadline, true);
        Optional<Part> inStock = parts.stream().filter(part -> part.stock() > 0).findFirst();
        if (inStock.isPresent()) {
            return PartLookupResult.found(inStock.get());
        }
        TmeResponses.Product p = products.getFirst();
        return PartLookupResult.outOfStock(new PartLookupResult.Identity(p.symbol(),
                p.manufacturer() == null ? null : p.manufacturer().name(),
                p.manufacturerSymbols() == null ? null : p.manufacturerSymbols().stream()
                        .filter(m -> m != null && !m.isBlank()).findFirst().orElse(null),
                p.description()), parts.stream().filter(part -> part.distributorPartNumber().equals(p.symbol()))
                .findFirst().orElse(null));
    }

    /** {@code /products/data} for the symbols, 50 per call (DESIGN.md 3.2 "Stock refresh"). */
    @Override
    public Map<String, ro.alacrity.kina.distributor.StockUpdate> refreshStock(List<String> symbols,
                                                                             Deadline deadline) {
        requireConfigured();
        List<String> wanted = symbols == null ? List.of() : symbols.stream()
                .filter(s -> s != null && !s.isBlank()).distinct().toList();
        if (wanted.isEmpty()) {
            return Map.of();
        }
        Map<String, ro.alacrity.kina.distributor.StockUpdate> out = new HashMap<>();
        for (TmeResponses.ProductData data : api.data(wanted, config.country(), config.currency(), deadline)) {
            ro.alacrity.kina.distributor.StockUpdate update = TmePartMapper.stockUpdate(data);
            if (update != null) {
                out.putIfAbsent(data.symbol(), update);
            }
        }
        return out;
    }

    /**
     * A lookup TME refuses as invalid input ({@code E_INPUT_PARAMS_VALIDATION_ERROR}, e.g. "Some characters are not
     * permitted in symbols[0]") found nothing: an empty list, not {@code bad_response}.
     */
    private static List<TmeResponses.Product> refusedAsMissing(Supplier<List<TmeResponses.Product>> call) {
        try {
            return call.get();
        } catch (DistributorException e) {
            if (e.kind() == Kind.BAD_RESPONSE && e.getMessage() != null
                    && e.getMessage().contains("E_INPUT_PARAMS_VALIDATION_ERROR")) {
                log.debug("TME refused a part number as invalid input: {}", e.getMessage());
                return List.of();
            }
            throw e;
        }
    }

    /** Fetches stock/prices, parameters and datasheets for the products and maps the in-stock ones, keeping order. */
    private List<Part> enrich(List<TmeResponses.Product> products, Deadline deadline) {
        return enrich(products, deadline, false);
    }

    /**
     * As {@link #enrich(List, Deadline)}; with {@code listed} a product without ships-now stock is mapped too, with
     * stock 0 ({@link TmePartMapper#toListedPart}: a lookup of an explicitly requested part number).
     */
    private List<Part> enrich(List<TmeResponses.Product> products, Deadline deadline, boolean listed) {
        if (products.isEmpty()) {
            return List.of();
        }
        List<String> symbols = new ArrayList<>(new LinkedHashSet<>(products.stream()
                .map(TmeResponses.Product::symbol).filter(s -> s != null && !s.isBlank()).toList()));
        if (symbols.isEmpty()) {
            return List.of();
        }
        CompletableFuture<List<TmeResponses.ProductData>> dataFuture =
                async(() -> api.data(symbols, config.country(), config.currency(), deadline));
        CompletableFuture<List<TmeResponses.ProductParameters>> parametersFuture =
                async(() -> api.parameters(symbols, config.country(), deadline));
        CompletableFuture<Map<String, TmePartMapper.Datasheet>> datasheetsFuture =
                async(() -> datasheets(symbols, deadline));

        Map<String, TmeResponses.ProductData> data = bySymbol(join(dataFuture), TmeResponses.ProductData::symbol);
        Map<String, TmeResponses.ProductParameters> parameters =
                bySymbol(join(parametersFuture), TmeResponses.ProductParameters::symbol);
        Map<String, TmePartMapper.Datasheet> datasheets = join(datasheetsFuture);

        Instant now = clock.instant();
        List<Part> parts = new ArrayList<>(products.size());
        for (TmeResponses.Product product : products) {
            String symbol = product.symbol();
            if (symbol == null) {
                continue;
            }
            Optional<Part> part = TmePartMapper.toPart(product, data.get(symbol), parameters.get(symbol),
                    datasheets.get(symbol), now, config.excludedStatuses());
            if (part.isEmpty() && listed) {
                part = TmePartMapper.toListedPart(product, data.get(symbol), parameters.get(symbol),
                        datasheets.get(symbol), now);
            }
            part.ifPresent(parts::add);
        }
        return parts;
    }

    /**
     * Datasheet URL per symbol from {@code /products/files}. Never fails the search: when the endpoint is refused for
     * this account (a 4xx other than auth/rate limiting) it is disabled with a single WARN; other failures are
     * logged and yield no datasheets for this call.
     */
    private Map<String, TmePartMapper.Datasheet> datasheets(List<String> symbols, Deadline deadline) {
        if (filesDisabled.get()) {
            return Map.of();
        }
        try {
            Map<String, TmePartMapper.Datasheet> result = new HashMap<>();
            for (TmeResponses.ProductFiles files : api.files(symbols, config.country(), deadline)) {
                TmePartMapper.Datasheet sheet = TmePartMapper.datasheet(files);
                if (files.symbol() != null && sheet != null) {
                    result.putIfAbsent(files.symbol(), sheet);
                }
            }
            return result;
        } catch (DistributorException e) {
            if (e.kind() == Kind.BAD_RESPONSE) {
                if (filesDisabled.compareAndSet(false, true)) {
                    log.warn("TME /products/files is not usable for this account; continuing without datasheets: {}",
                            e.getMessage());
                }
            } else {
                log.debug("TME /products/files failed; continuing without datasheets: {}", e.getMessage());
            }
            return Map.of();
        }
    }

    private <T> CompletableFuture<T> async(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, executor);
    }

    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new DistributorException(Distributor.TME, Kind.UNAVAILABLE, "TME request failed", e.getCause());
        }
    }

    private static <T> Map<String, T> bySymbol(List<T> elements, java.util.function.Function<T, String> symbol) {
        Map<String, T> map = new HashMap<>();
        for (T element : elements) {
            String key = symbol.apply(element);
            if (key != null) {
                map.putIfAbsent(key, element);
            }
        }
        return map;
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw DistributorException.notConfigured(Distributor.TME);
        }
    }

    /**
     * Normalises the query into a TME {@code phrase} (2..40 characters): collapses whitespace and shortens longer
     * queries at a word boundary.
     */
    static String phrase(String query) {
        String phrase = query == null ? "" : query.strip().replaceAll("\\s+", " ");
        if (phrase.length() > MAX_PHRASE_LENGTH) {
            int cut = phrase.lastIndexOf(' ', MAX_PHRASE_LENGTH);
            phrase = (cut >= MIN_PHRASE_LENGTH ? phrase.substring(0, cut) : phrase.substring(0, MAX_PHRASE_LENGTH)).strip();
        }
        if (phrase.length() < MIN_PHRASE_LENGTH) {
            throw new DistributorException(Distributor.TME, Kind.BAD_RESPONSE,
                    "TME search phrase must have at least " + MIN_PHRASE_LENGTH + " characters");
        }
        return phrase;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
