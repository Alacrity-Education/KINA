package ro.alacrity.kina.distributor.tme;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ro.alacrity.kina.config.KinaProperties;
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
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    /** {@code phrase} length bounds from the OpenAPI document. */
    static final int MIN_PHRASE_LENGTH = 2;
    static final int MAX_PHRASE_LENGTH = 40;

    private final KinaProperties.Tme properties;
    private final TmeApi api;
    private final Clock clock;
    private final Executor executor;
    private final AtomicBoolean filesDisabled = new AtomicBoolean(false);

    @Autowired
    public TmeClient(KinaProperties properties) {
        this(properties.distributors().tme(), defaultRestClient(), Clock.systemUTC(),
                task -> Thread.ofVirtual().name("tme-fetch").start(task));
    }

    TmeClient(KinaProperties.Tme properties, RestClient restClient, Clock clock, Executor executor) {
        this(properties, restClient, clock, executor, new RateLimitRetry(Distributor.TME));
    }

    TmeClient(KinaProperties.Tme properties, RestClient restClient, Clock clock, Executor executor,
              RateLimitRetry retry) {
        this.properties = properties;
        this.clock = clock;
        this.executor = executor;
        TmeTokenManager tokens = new TmeTokenManager(restClient, properties.baseUrl(),
                nullToEmpty(properties.token()), nullToEmpty(properties.secret()), clock, retry);
        this.api = new TmeApi(restClient, tokens, properties.baseUrl(), properties.language(), retry);
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
        return properties.isConfigured();
    }

    @Override
    public int maxPageSize() {
        return Math.clamp(properties.maxResultsPerSearch(), 1, TmeApi.MAX_SEARCH_LIMIT);
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

        TmeResponses.SearchResponse response = api.search(phrase, properties.country(), pageSize, page, deadline);
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
        return new DistributorSearchPage(parts, total, hasMore);
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
     * {@code OUT_OF_STOCK}.
     */
    @Override
    public PartLookupResult lookup(String partNumber, Deadline deadline) throws DistributorException {
        requireConfigured();
        if (partNumber == null || partNumber.isBlank()) {
            return PartLookupResult.notFound();
        }
        String symbol = partNumber.strip();
        List<TmeResponses.Product> products = api.products(List.of(symbol), properties.country(), deadline).stream()
                .filter(p -> p.symbol() != null && p.symbol().equalsIgnoreCase(symbol))
                .limit(1)
                .toList();
        if (products.isEmpty()) {
            List<String> mpns = new ArrayList<>(new LinkedHashSet<>(List.of(symbol,
                    PartLookupResult.normalize(symbol))));
            mpns.removeIf(String::isBlank);
            products = api.productsByMpn(mpns, properties.country(), deadline).stream()
                    .filter(p -> PartLookupResult.samePartNumber(symbol, p.symbol())
                            || p.manufacturerSymbols() != null && p.manufacturerSymbols().stream()
                            .anyMatch(m -> PartLookupResult.samePartNumber(symbol, m)))
                    .toList();
        }
        if (products.isEmpty()) {
            return PartLookupResult.notFound();
        }
        List<Part> parts = enrich(products, deadline);
        if (!parts.isEmpty()) {
            return PartLookupResult.found(parts.getFirst());
        }
        TmeResponses.Product p = products.getFirst();
        return PartLookupResult.outOfStock(new PartLookupResult.Identity(p.symbol(),
                p.manufacturer() == null ? null : p.manufacturer().name(),
                p.manufacturerSymbols() == null ? null : p.manufacturerSymbols().stream()
                        .filter(m -> m != null && !m.isBlank()).findFirst().orElse(null),
                p.description()));
    }

    /** Fetches stock/prices, parameters and datasheets for the products and maps the in-stock ones, keeping order. */
    private List<Part> enrich(List<TmeResponses.Product> products, Deadline deadline) {
        if (products.isEmpty()) {
            return List.of();
        }
        List<String> symbols = new ArrayList<>(new LinkedHashSet<>(products.stream()
                .map(TmeResponses.Product::symbol).filter(s -> s != null && !s.isBlank()).toList()));
        if (symbols.isEmpty()) {
            return List.of();
        }
        CompletableFuture<List<TmeResponses.ProductData>> dataFuture =
                async(() -> api.data(symbols, properties.country(), properties.currency(), deadline));
        CompletableFuture<List<TmeResponses.ProductParameters>> parametersFuture =
                async(() -> api.parameters(symbols, properties.country(), deadline));
        CompletableFuture<Map<String, String>> datasheetsFuture = async(() -> datasheets(symbols, deadline));

        Map<String, TmeResponses.ProductData> data = bySymbol(join(dataFuture), TmeResponses.ProductData::symbol);
        Map<String, TmeResponses.ProductParameters> parameters =
                bySymbol(join(parametersFuture), TmeResponses.ProductParameters::symbol);
        Map<String, String> datasheets = join(datasheetsFuture);

        Instant now = clock.instant();
        List<Part> parts = new ArrayList<>(products.size());
        for (TmeResponses.Product product : products) {
            String symbol = product.symbol();
            if (symbol == null) {
                continue;
            }
            TmePartMapper.toPart(product, data.get(symbol), parameters.get(symbol), datasheets.get(symbol), now,
                            properties.excludedStatuses())
                    .ifPresent(parts::add);
        }
        return parts;
    }

    /**
     * Datasheet URL per symbol from {@code /products/files}. Never fails the search: when the endpoint is refused for
     * this account (a 4xx other than auth/rate limiting) it is disabled with a single WARN; other failures are
     * logged and yield no datasheets for this call.
     */
    private Map<String, String> datasheets(List<String> symbols, Deadline deadline) {
        if (filesDisabled.get()) {
            return Map.of();
        }
        try {
            Map<String, String> result = new HashMap<>();
            for (TmeResponses.ProductFiles files : api.files(symbols, properties.country(), deadline)) {
                String url = TmePartMapper.datasheetUrl(files);
                if (files.symbol() != null && url != null) {
                    result.putIfAbsent(files.symbol(), url);
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
