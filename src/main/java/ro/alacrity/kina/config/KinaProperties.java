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

    /**
     * {@code kina.ranking.*} (DESIGN.md 3.3).
     *
     * @param timeout       budget for ranking one query (deterministic + cross-encoder); 40 candidates take about
     *                      0.1 to 0.35 s, so the default leaves a wide margin inside the 20 s requirement
     * @param batchTimeout  overall ranking budget of one {@code search_parts_batch}
     * @param scoreCacheTtl lifetime of cached raw cross-encoder scores (normalised query + part key)
     */
    public record Ranking(
            @DefaultValue("5s") Duration timeout,
            @DefaultValue("60s") Duration batchTimeout,
            @DefaultValue("1h") Duration scoreCacheTtl,
            @DefaultValue CrossEncoder crossEncoder) {
    }

    /**
     * {@code kina.ranking.cross-encoder.*}: the in-process MiniLM cross-encoder blended with the deterministic ranker
     * (DESIGN.md 3.5).
     *
     * @param enabled           false: deterministic ranking only ({@code ranking: "fallback"})
     * @param variant           {@code int8} (quantised, about 2x faster, CPU-specific file) or {@code fp32}
     * @param modelDir          where the model files live / are downloaded to
     * @param modelUrl          base of the model files: a Hugging Face {@code resolve/<revision>/} URL or any HTTP(S)
     *                          directory with the same layout, or a local directory (used in place, no download)
     * @param threads           ONNX Runtime intra-op threads; 0 = {@code min(4, availableProcessors)}
     * @param maxConcurrent     concurrent scoring calls; further calls wait within their ranking budget
     * @param batchSize         pairs per inference call
     * @param maxSequenceLength tokens per pair, {@code [CLS] query [SEP] document [SEP]} (the document is cut first)
     * @param maxCandidates     candidates scored per query, shared proportionally between distributors
     * @param weight            {@code final = (1 - weight) * ranknorm(deterministic) + weight * ranknorm(model)}
     * @param checkInterval     how often a missing or failed model is retried
     * @param downloadTimeout   upper bound for downloading one model file
     * @param autoDownload      false: never download, only use files already present (tests, air-gapped hosts)
     */
    public record CrossEncoder(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("int8") Variant variant,
            @DefaultValue("./data/cross-encoder") Path modelDir,
            @DefaultValue(DEFAULT_MODEL_URL) String modelUrl,
            @DefaultValue("0") int threads,
            @DefaultValue("2") int maxConcurrent,
            @DefaultValue("16") int batchSize,
            @DefaultValue("256") int maxSequenceLength,
            @DefaultValue("40") int maxCandidates,
            @DefaultValue("0.5") double weight,
            @DefaultValue("1h") Duration checkInterval,
            @DefaultValue("10m") Duration downloadTimeout,
            @DefaultValue("true") boolean autoDownload) {

        public static final String DEFAULT_MODEL_URL =
                "https://huggingface.co/cross-encoder/ms-marco-MiniLM-L6-v2/resolve/main/";

        public enum Variant { FP32, INT8 }

        /** Configured threads, or {@code min(4, availableProcessors)} when 0 or negative. */
        public int effectiveThreads() {
            return threads > 0 ? threads : Math.min(4, Runtime.getRuntime().availableProcessors());
        }

        /** {@link #modelDir()} made absolute and normalised ({@code /data/jlcpcb/../cross-encoder} -&gt; {@code /data/cross-encoder}). */
        public Path resolvedModelDir() {
            return modelDir.toAbsolutePath().normalize();
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
