package ro.alacrity.kina.distributor.tme;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import ro.alacrity.kina.distributor.Deadline;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.distributor.RateLimitRetry;
import ro.alacrity.kina.distributor.RateLimitRetry.RateLimitedResponse;
import ro.alacrity.kina.domain.Distributor;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Thin wrapper over the TME API v2 GET endpoints. Adds {@code Authorization: Bearer}, {@code Accept} and
 * {@code Accept-Language}; repeats array parameters as {@code symbols[]=A&symbols[]=B}. On an authentication failure
 * the token is invalidated and the call retried once. Symbol lists longer than {@link #MAX_SYMBOLS} are split into
 * batches. Every HTTP exchange goes through {@link RateLimitRetry}: rate limits are waited for within the caller's
 * {@link Deadline} (DESIGN.md 3.6).
 */
final class TmeApi {

    /** Maximum {@code symbols[]} per call for /products, /products/data, /products/parameters and /products/files. */
    static final int MAX_SYMBOLS = 50;

    /** Largest {@code limit} /products/search accepts (live: 101 -> "This value should be between 1 and 100."). */
    static final int MAX_SEARCH_LIMIT = 100;

    private final RestClient restClient;
    private final TmeTokenManager tokens;
    private final String baseUrl;
    private final String language;
    private final RateLimitRetry retry;

    TmeApi(RestClient restClient, TmeTokenManager tokens, String baseUrl, String language) {
        this(restClient, tokens, baseUrl, language, new RateLimitRetry(Distributor.TME));
    }

    TmeApi(RestClient restClient, TmeTokenManager tokens, String baseUrl, String language, RateLimitRetry retry) {
        this.retry = retry;
        this.restClient = restClient;
        this.tokens = tokens;
        this.baseUrl = TmeTokenManager.stripTrailingSlash(baseUrl);
        this.language = language == null || language.isBlank() ? "en" : language;
    }

    /** {@code GET /products/search?phrase=&scope[]=products&scope[]=counters&filter[in_stock]=true&country=&limit=&page=}. */
    TmeResponses.SearchResponse search(String phrase, String country, int limit, int page, Deadline deadline) {
        List<Map.Entry<String, String>> params = new ArrayList<>();
        params.add(Map.entry("phrase", phrase));
        params.add(Map.entry("scope[]", "products"));
        params.add(Map.entry("scope[]", "counters"));
        params.add(Map.entry("filter[in_stock]", "true"));
        params.add(Map.entry("country", country));
        params.add(Map.entry("limit", Integer.toString(limit)));
        params.add(Map.entry("page", Integer.toString(page)));
        return get("/products/search", params, TmeResponses.SearchResponse.class, deadline);
    }

    /** {@code GET /products?symbols[]=...&country=}; rate limits fail fast. */
    List<TmeResponses.Product> products(List<String> symbols, String country) {
        return products(symbols, country, Deadline.immediate());
    }

    /** {@code GET /products?symbols[]=...&country=}. */
    List<TmeResponses.Product> products(List<String> symbols, String country, Deadline deadline) {
        return batched(symbols, batch -> {
            List<Map.Entry<String, String>> params = symbolParams(batch);
            params.add(Map.entry("country", country));
            TmeResponses.ProductsResponse response =
                    get("/products", params, TmeResponses.ProductsResponse.class, deadline);
            return response.data() == null ? null : response.data().elements();
        });
    }

    /** {@code GET /products/data?symbols[]=...&scope[]=prices&scope[]=stock&country=&currency=}. */
    List<TmeResponses.ProductData> data(List<String> symbols, String country, String currency, Deadline deadline) {
        return batched(symbols, batch -> {
            List<Map.Entry<String, String>> params = symbolParams(batch);
            params.add(Map.entry("scope[]", "prices"));
            params.add(Map.entry("scope[]", "stock"));
            params.add(Map.entry("country", country));
            params.add(Map.entry("currency", currency));
            TmeResponses.DataResponse response = get("/products/data", params, TmeResponses.DataResponse.class, deadline);
            return response.data() == null ? null : response.data().elements();
        });
    }

    /** {@code GET /products/parameters?symbols[]=...&country=}. */
    List<TmeResponses.ProductParameters> parameters(List<String> symbols, String country, Deadline deadline) {
        return batched(symbols, batch -> {
            List<Map.Entry<String, String>> params = symbolParams(batch);
            params.add(Map.entry("country", country));
            TmeResponses.ParametersResponse response =
                    get("/products/parameters", params, TmeResponses.ParametersResponse.class, deadline);
            return response.data() == null ? null : response.data().elements();
        });
    }

    /** {@code GET /products/files?symbols[]=...&country=}. */
    List<TmeResponses.ProductFiles> files(List<String> symbols, String country, Deadline deadline) {
        return batched(symbols, batch -> {
            List<Map.Entry<String, String>> params = symbolParams(batch);
            params.add(Map.entry("country", country));
            TmeResponses.FilesResponse response = get("/products/files", params, TmeResponses.FilesResponse.class, deadline);
            return response.data() == null ? null : response.data().elements();
        });
    }

    private static List<Map.Entry<String, String>> symbolParams(List<String> symbols) {
        List<Map.Entry<String, String>> params = new ArrayList<>(symbols.size() + 4);
        for (String symbol : symbols) {
            params.add(Map.entry("symbols[]", symbol));
        }
        return params;
    }

    private static <T> List<T> batched(List<String> symbols, Function<List<String>, List<T>> call) {
        List<T> result = new ArrayList<>();
        for (int from = 0; from < symbols.size(); from += MAX_SYMBOLS) {
            List<T> elements = call.apply(symbols.subList(from, Math.min(from + MAX_SYMBOLS, symbols.size())));
            if (elements != null) {
                result.addAll(elements);
            }
        }
        return result;
    }

    /** Authenticated GET with one retry after an authentication failure and rate-limit retries within {@code deadline}. */
    <T> T get(String path, List<Map.Entry<String, String>> params, Class<T> type, Deadline deadline) {
        URI uri = uri(path, params);
        String what = "TME " + path;
        String token = tokens.accessToken(deadline);
        TmeHttp.Response response = execute(uri, token, what, deadline);
        if (!response.isSuccess()) {
            TmeResponses.ErrorResponse error = TmeHttp.error(response);
            if (TmeHttp.isAuthFailure(response, error)) {
                tokens.invalidate(token);
                response = execute(uri, tokens.accessToken(deadline), what, deadline);
            }
        }
        if (!response.isSuccess()) {
            throw TmeHttp.failure(response, TmeHttp.error(response), what);
        }
        return TmeHttp.decode(response, type, what);
    }

    private TmeHttp.Response execute(URI uri, String token, String what, Deadline deadline)
            throws DistributorException {
        return retry.call(deadline, () -> {
            TmeHttp.Response response = TmeHttp.exchange(restClient.get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(HttpHeaders.ACCEPT_LANGUAGE, language)
                    .accept(MediaType.APPLICATION_JSON), what);
            if (response.isRateLimited()) {
                throw new RateLimitedResponse(what + " returned HTTP " + response.status(), response.retryAfter());
            }
            return response;
        });
    }

    private URI uri(String path, List<Map.Entry<String, String>> params) {
        StringBuilder sb = new StringBuilder(baseUrl).append(path);
        char separator = '?';
        for (Map.Entry<String, String> param : params) {
            if (param.getValue() == null || param.getValue().isEmpty()) {
                continue;
            }
            sb.append(separator)
                    .append(URLEncoder.encode(param.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(param.getValue(), StandardCharsets.UTF_8));
            separator = '&';
        }
        return URI.create(sb.toString());
    }
}
