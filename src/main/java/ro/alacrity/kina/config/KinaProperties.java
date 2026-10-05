package ro.alacrity.kina.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Every {@code kina.*} setting (DESIGN.md section 10). Defaults here mirror {@code application.yml} so tests
 * and partial configurations still bind. Records holding secrets mask them in {@code toString()}.
 */
@ConfigurationProperties("kina")
public record KinaProperties(
        String publicBaseUrl,
        @DefaultValue Security security,
        @DefaultValue Tokens tokens,
        @DefaultValue OAuth oauth,
        @DefaultValue Cache cache,
        @DefaultValue Search search,
        @DefaultValue Ranking ranking,
        @DefaultValue Distributors distributors,
        @DefaultValue Jlcpcb jlcpcb
) {

    /** {@code kina.public-base-url} if non-blank. */
    public boolean hasPublicBaseUrl() {
        return publicBaseUrl != null && !publicBaseUrl.isBlank();
    }

    public enum Mode { DEV, PROD }

    public record Security(@DefaultValue("dev") Mode mode, @DefaultValue Oidc oidc) {

        @ConstructorBinding
        public Security {
        }

        /** Convenience for tests and code that only cares about the mode. */
        public Security(Mode mode) {
            this(mode, new Oidc(null, null, null));
        }
    }

    /** {@code kina.security.oidc.*}: generic OIDC provider used for web login in production mode. */
    public record Oidc(String issuerUri, String clientId, String clientSecret) {

        public boolean isConfigured() {
            return issuerUri != null && !issuerUri.isBlank() && clientId != null && !clientId.isBlank();
        }

        @Override
        public String toString() {
            return "Oidc[issuerUri=" + issuerUri + ", clientId=" + clientId + ", clientSecret="
                    + (clientSecret == null || clientSecret.isBlank() ? "" : "***") + "]";
        }
    }

    public record Tokens(@DefaultValue("30d") Duration validity) {
    }

    public record OAuth(@DefaultValue("90d") Duration refreshTokenValidity) {
    }

    /**
     * {@code kina.cache.*}.
     *
     * @param ttl            freshness of cached Mouser/TME searches and parts
     * @param emptyResultTtl freshness of a cached search that found no in-stock part (a transient distributor glitch
     *                       or a new listing should not hide parts for the whole {@code ttl})
     */
    public record Cache(@DefaultValue("5d") Duration ttl, @DefaultValue("1h") Duration emptyResultTtl) {
    }

    /**
     * {@code kina.search.*}.
     *
     * @param distributorTimeout active-work budget of one distributor fetch; time spent waiting on a rate limit does
     *                           not count (DESIGN.md 3.6)
     * @param maxRequestDuration hard cap on one incoming request ({@code search_parts}, a whole batch, {@code get_part})
     *                           within which rate-limited distributor calls may wait and retry
     */
    public record Search(
            @DefaultValue("40") int candidateWindow,
            @DefaultValue("10") int defaultMaxResults,
            @DefaultValue("50") int maxMaxResults,
            @DefaultValue("12s") Duration distributorTimeout,
            @DefaultValue("2m") Duration maxRequestDuration) {
    }

    public record Ranking(
            @DefaultValue("18s") Duration timeout,
            @DefaultValue("60s") Duration batchTimeout,
            @DefaultValue("1h") Duration scoreCacheTtl,
            @DefaultValue Laya laya) {
    }

    public record Laya(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("http://localhost:8000") String url,
            String apiKey,
            @DefaultValue("multilingual") String model,
            @DefaultValue("40") int maxCandidates,
            @DefaultValue("1") int maxConcurrentRequests,
            @DefaultValue("0.2") double weight) {

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        @Override
        public String toString() {
            return "Laya[enabled=" + enabled + ", url=" + url + ", apiKey=" + (hasApiKey() ? "***" : "") + ", model="
                    + model + ", maxCandidates=" + maxCandidates + ", maxConcurrentRequests=" + maxConcurrentRequests
                    + ", weight=" + weight + "]";
        }
    }

    public record Distributors(@DefaultValue Mouser mouser, @DefaultValue Tme tme) {
    }

    public record Mouser(
            String apiKey,
            @DefaultValue("https://api.mouser.com/api/v1") String baseUrl,
            @DefaultValue("50") int maxResultsPerSearch,
            @DefaultValue("1") int maxPagesPerSearch) {

        public boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank();
        }

        @Override
        public String toString() {
            return "Mouser[apiKey=" + (isConfigured() ? "***" : "") + ", baseUrl=" + baseUrl
                    + ", maxResultsPerSearch=" + maxResultsPerSearch + ", maxPagesPerSearch=" + maxPagesPerSearch + "]";
        }
    }

    public record Tme(
            String token,
            String secret,
            @DefaultValue("RO") String country,
            @DefaultValue("EUR") String currency,
            @DefaultValue("en") String language,
            @DefaultValue("https://api.tme.eu") String baseUrl,
            @DefaultValue("60") int maxResultsPerSearch,
            @DefaultValue("3") int maxPagesPerSearch,
            @DefaultValue({"CANNOT_BE_ORDERED", "ONLY_FOR_SPECIAL_ORDER", "EXTERNAL_WAREHOUSE"})
            List<String> excludedStatuses) {

        /** {@code product_status} values that mean the part does not ship now; such parts are dropped. */
        public static final List<String> DEFAULT_EXCLUDED_STATUSES =
                List.of("CANNOT_BE_ORDERED", "ONLY_FOR_SPECIAL_ORDER", "EXTERNAL_WAREHOUSE");

        public Tme {
            excludedStatuses = excludedStatuses == null ? DEFAULT_EXCLUDED_STATUSES : List.copyOf(excludedStatuses);
        }

        public boolean isConfigured() {
            return token != null && !token.isBlank() && secret != null && !secret.isBlank();
        }

        @Override
        public String toString() {
            return "Tme[token=" + (token == null || token.isBlank() ? "" : "***") + ", secret="
                    + (secret == null || secret.isBlank() ? "" : "***") + ", country=" + country + ", currency="
                    + currency + ", language=" + language + ", baseUrl=" + baseUrl + ", maxResultsPerSearch="
                    + maxResultsPerSearch + ", maxPagesPerSearch=" + maxPagesPerSearch + ", excludedStatuses="
                    + excludedStatuses + "]";
        }
    }

    public record Jlcpcb(
            @DefaultValue("./data/jlcpcb") Path dataDir,
            @DefaultValue("parts-fts5.db") String library,
            @DefaultValue("https://bouni.github.io/kicad-jlcpcb-tools/") String baseUrl,
            @DefaultValue("5d") Duration refreshAfter,
            @DefaultValue("1h") Duration checkInterval,
            @DefaultValue("200") int maxResultsPerSearch,
            @DefaultValue("true") boolean autoDownload) {

        /** {@code <data-dir>/<library>}. */
        public Path databaseFile() {
            return dataDir.resolve(library);
        }
    }
}
