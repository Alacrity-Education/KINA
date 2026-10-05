package ro.alacrity.kina.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.RateLimitRetry;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PartKey;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Scores candidates with the local Laya "System 1" server (DESIGN.md section 3.5): one compact JSON state per
 * candidate, the single {@value #FITS} {@code noul} question, raw {@code answers.fits.noul} per part key.
 * Part data is only ever sent to {@code kina.ranking.laya.url}.
 *
 * <p>Timeouts: connect {@value #CONNECT_TIMEOUT_SECONDS}s; the read timeout is the remaining budget, enforced by a
 * bounded wait on the call (the in-flight JDK HTTP request is cancelled on expiry).
 *
 * <p>Busy: an HTTP 503 with {@code Retry-After} (laya-serve's queue is full) is retried after that delay when it fits
 * the remaining ranking budget (not the request's two-minute cap, DESIGN.md 3.6); otherwise, and for 429 or a 503
 * without the header, the ranking falls back as before.
 */
@Component
public class LayaPartRanker implements PartRanker {

    private static final Logger log = LoggerFactory.getLogger(LayaPartRanker.class);

    /** Question id; the raw score is {@code answers.fits.noul}. */
    public static final String FITS = "fits";
    /** Question type. */
    public static final String QUESTION_TYPE = "noul";
    /** Question text, identical for every state. */
    public static final String FITS_INSTRUCTIONS = "The candidate electronic component satisfies every requirement "
            + "stated in the request: component type, value, tolerance, voltage or current rating, dielectric or "
            + "technology, package or footprint and mounting type.";
    /** Candidate fields serialised into each state, in order. */
    public static final List<String> STATE_CANDIDATE_FIELDS =
            List.of("manufacturer", "mpn", "description", "package", "attributes");
    static final String BATCH_PATH = "/v1/systemone/batch";
    static final String HEALTH_PATH = "/health";
    static final int CONNECT_TIMEOUT_SECONDS = 2;
    static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(2);
    static final Duration HEALTH_CACHE = Duration.ofSeconds(30);

    private final KinaProperties.Laya laya;
    private final ParametricExtractor extractor;
    private final RestClient restClient;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final String baseUrl;
    private final RateLimitRetry.Sleeper sleeper;
    private final Clock clock;

    private record Health(boolean healthy, long checkedAtNanos) {
    }

    private volatile Health health;

    @Autowired
    public LayaPartRanker(KinaProperties properties, ParametricExtractor extractor,
                          ObjectProvider<RestClient.Builder> builders) {
        this(properties, extractor, builders.getIfAvailable(RestClient::builder)
                .requestFactory(requestFactory(properties.ranking().timeout()))
                .build());
    }

    /** For tests: uses the given client as is (e.g. bound to {@code MockRestServiceServer}). */
    LayaPartRanker(KinaProperties properties, ParametricExtractor extractor, RestClient restClient) {
        this(properties, extractor, restClient, d -> Thread.sleep(d), Clock.systemUTC());
    }

    /** For tests: also replaces the sleeper used for {@code Retry-After} waits. */
    LayaPartRanker(KinaProperties properties, ParametricExtractor extractor, RestClient restClient,
                   RateLimitRetry.Sleeper sleeper, Clock clock) {
        this.sleeper = sleeper;
        this.clock = clock;
        this.laya = properties.ranking().laya();
        this.extractor = extractor;
        this.restClient = restClient;
        String url = laya.url() == null ? "" : laya.url().trim();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static JdkClientHttpRequestFactory requestFactory(Duration maxReadTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        // Upper bound only; the per-call budget is enforced by callWithin().
        factory.setReadTimeout(maxReadTimeout.isPositive() ? maxReadTimeout : Duration.ofSeconds(18));
        return factory;
    }

    @Override
    public String name() {
        return "laya";
    }

    @Override
    public Map<String, Double> rank(ParsedQuery query, List<Part> candidates, Duration budget)
            throws RankingException {
        if (candidates.isEmpty()) {
            return Map.of();
        }
        if (budget == null || !budget.isPositive()) {
            throw new RankingException(RankingException.Reason.TIMEOUT, "laya timeout: no time budget left");
        }
        Map<String, Part> unique = new LinkedHashMap<>();
        candidates.forEach(p -> unique.putIfAbsent(PartKey.of(p), p));
        List<String> keys = new ArrayList<>(unique.keySet());
        String body = requestBody(query, unique.values());

        long started = System.nanoTime();
        long deadline = started + budget.toNanos();
        String response;
        boolean first = true;
        while (true) {
            // the first call gets the whole budget, so timeout messages name the configured budget
            Duration remaining = first ? budget : Duration.ofNanos(deadline - System.nanoTime());
            first = false;
            if (!remaining.isPositive()) {
                throw new RankingException(RankingException.Reason.TIMEOUT, "laya timeout after " + format(budget));
            }
            try {
                response = callWithin(remaining, () -> {
                    RestClient.RequestBodySpec spec = restClient.post()
                            .uri(baseUrl + BATCH_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON);
                    if (laya.hasApiKey()) {
                        spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + laya.apiKey());
                    }
                    return spec.body(body).retrieve().body(String.class);
                });
                break;
            } catch (RankingException e) {
                Duration retryAfter = busyRetryAfter(e);
                if (retryAfter == null || retryAfter.toNanos() >= deadline - System.nanoTime()) {
                    throw e;
                }
                log.debug("laya busy, retrying in {}", format(retryAfter));
                try {
                    sleeper.sleep(retryAfter);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RankingException(RankingException.Reason.BUSY, "laya busy: interrupted while waiting",
                            interrupted);
                }
            }
        }
        Map<String, Double> scores = parseScores(response, keys);
        log.debug("laya scored {} candidates in {} ms", keys.size(), (System.nanoTime() - started) / 1_000_000);
        return scores;
    }

    /** Request body: {@code {"states": [...], "questions": {"fits": {...}}, "model": ..., "sort_by_length": true}}. */
    String requestBody(ParsedQuery query, Iterable<Part> parts) {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode states = root.putArray("states");
        for (Part part : parts) {
            states.add(state(query, part));
        }
        ObjectNode fits = root.putObject("questions").putObject(FITS);
        fits.put("type", QUESTION_TYPE);
        fits.put("instructions", FITS_INSTRUCTIONS);
        root.put("model", laya.model());
        root.put("sort_by_length", true);
        return mapper.writeValueAsString(root);
    }

    /** One state as compact JSON: {@code {"request": <query>, "candidate": {manufacturer, mpn, ...}}}. */
    String state(ParsedQuery query, Part part) {
        ObjectNode state = mapper.createObjectNode();
        state.put("request", query.originalText());
        ObjectNode candidate = state.putObject("candidate");
        Map<String, String> comparable = extractor.extract(part);
        for (String field : STATE_CANDIDATE_FIELDS) {
            switch (field) {
                case "manufacturer" -> putIfNotBlank(candidate, field, part.manufacturer());
                case "mpn" -> putIfNotBlank(candidate, field, part.manufacturerPartNumber());
                case "description" -> putIfNotBlank(candidate, field, part.description());
                case "category" -> putIfNotBlank(candidate, field, part.category());
                case "package" -> putIfNotBlank(candidate, field, comparable.getOrDefault(ParametricExtractor.PACKAGE,
                        part.packageName()));
                case "attributes" -> {
                    ObjectNode attributes = candidate.putObject(field);
                    comparable.forEach((k, v) -> {
                        if (!k.equals(ParametricExtractor.PACKAGE)) {
                            attributes.put(k, v);
                        }
                    });
                }
                default -> throw new IllegalStateException("unknown state field " + field);
            }
        }
        return mapper.writeValueAsString(state);
    }

    private static void putIfNotBlank(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    private Map<String, Double> parseScores(String response, List<String> keys) throws RankingException {
        JsonNode root;
        try {
            root = mapper.readTree(response == null ? "" : response);
        } catch (JacksonException e) {
            throw new RankingException(RankingException.Reason.BAD_RESPONSE, "laya bad response: invalid JSON", e);
        }
        JsonNode results = root == null ? null : root.get("results");
        if (results == null || !results.isArray()) {
            throw new RankingException(RankingException.Reason.BAD_RESPONSE, "laya bad response: no results array");
        }
        if (results.size() != keys.size()) {
            throw new RankingException(RankingException.Reason.BAD_RESPONSE,
                    "laya bad response: " + results.size() + " results for " + keys.size() + " states");
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            JsonNode noul = results.get(i).path("answers").path(FITS).path(QUESTION_TYPE);
            if (!noul.isNumber() || Double.isNaN(noul.doubleValue())) {
                throw new RankingException(RankingException.Reason.BAD_RESPONSE,
                        "laya bad response: missing answers.fits.noul for state " + i);
            }
            scores.put(keys.get(i), Math.clamp(noul.doubleValue(), 0.0, 1.0));
        }
        return scores;
    }

    /** The {@code Retry-After} delay of a busy HTTP 503 (at least 1 s), or null when the failure is anything else. */
    private Duration busyRetryAfter(RankingException e) {
        if (e.reason() != RankingException.Reason.BUSY
                || !(e.getCause() instanceof RestClientResponseException http) || http.getStatusCode().value() != 503
                || http.getResponseHeaders() == null) {
            return null;
        }
        Duration retryAfter = RateLimitRetry.parseRetryAfter(
                http.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER), clock);
        if (retryAfter == null) {
            return null;
        }
        return retryAfter.compareTo(RateLimitRetry.MIN_WAIT) < 0 ? RateLimitRetry.MIN_WAIT : retryAfter;
    }

    /** True when {@code GET {url}/health} answered 2xx within 2 s; cached for 30 s. Never throws. */
    public boolean isHealthy() {
        Health current = health;
        long now = System.nanoTime();
        if (current != null && now - current.checkedAtNanos() < HEALTH_CACHE.toNanos()) {
            return current.healthy();
        }
        boolean healthy;
        try {
            callWithin(HEALTH_TIMEOUT, () -> restClient.get().uri(baseUrl + HEALTH_PATH).retrieve().toBodilessEntity());
            healthy = true;
        } catch (RankingException | RuntimeException e) {
            log.debug("laya health check failed: {}", e.getMessage());
            healthy = false;
        }
        health = new Health(healthy, System.nanoTime());
        return healthy;
    }

    /** Runs the HTTP call on a virtual thread and waits at most {@code budget}; cancels the request on expiry. */
    private <T> T callWithin(Duration budget, Callable<T> call) throws RankingException {
        FutureTask<T> task = new FutureTask<>(call);
        Thread.ofVirtual().name("laya-call").start(task);
        try {
            return task.get(budget.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            throw new RankingException(RankingException.Reason.TIMEOUT, "laya timeout after " + format(budget), e);
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new RankingException(RankingException.Reason.TIMEOUT, "laya call interrupted", e);
        } catch (ExecutionException e) {
            throw translate(e.getCause() == null ? e : e.getCause(), budget);
        }
    }

    private static RankingException translate(Throwable cause, Duration budget) {
        if (cause instanceof RestClientResponseException http) {
            int status = http.getStatusCode().value();
            if (status == 429 || status == 503) {
                return new RankingException(RankingException.Reason.BUSY, "laya busy: HTTP " + status, cause);
            }
            if (status >= 500) {
                return new RankingException(RankingException.Reason.UNAVAILABLE, "laya unavailable: HTTP " + status,
                        cause);
            }
            return new RankingException(RankingException.Reason.BAD_RESPONSE, "laya rejected request: HTTP " + status,
                    cause);
        }
        if (cause instanceof ResourceAccessException access) {
            Throwable root = access.getCause();
            if (root instanceof HttpTimeoutException || root instanceof SocketTimeoutException) {
                return new RankingException(RankingException.Reason.TIMEOUT, "laya timeout after " + format(budget),
                        cause);
            }
            String detail = root != null && root.getMessage() != null ? root.getMessage()
                    : root != null ? root.getClass().getSimpleName() : String.valueOf(access.getMessage());
            return new RankingException(RankingException.Reason.UNAVAILABLE, "laya unavailable: " + detail, cause);
        }
        if (cause instanceof JacksonException) {
            return new RankingException(RankingException.Reason.BAD_RESPONSE, "laya bad response: invalid JSON", cause);
        }
        return new RankingException(RankingException.Reason.UNAVAILABLE,
                "laya unavailable: " + cause.getClass().getSimpleName(), cause);
    }

    /** "18s", "1.5s", "250ms". */
    static String format(Duration d) {
        long millis = d.toMillis();
        if (millis < 1000) {
            return millis + "ms";
        }
        if (millis % 1000 == 0) {
            return (millis / 1000) + "s";
        }
        return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
    }
}
