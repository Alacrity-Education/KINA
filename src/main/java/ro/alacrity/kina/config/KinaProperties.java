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
        @DefaultValue Jlcpcb jlcpcb,
        @DefaultValue Metrics metrics
) {

    @ConstructorBinding
    public KinaProperties {
        metrics = metrics == null ? new Metrics(Duration.ofSeconds(30)) : metrics;
    }

    /** Without {@code metrics} (tests that build the tree by hand): the defaults. */
    public KinaProperties(String publicBaseUrl, Security security, Tokens tokens, OAuth oauth, Cache cache,
                          Search search, Ranking ranking, Distributors distributors, Jlcpcb jlcpcb) {
        this(publicBaseUrl, security, tokens, oauth, cache, search, ranking, distributors, jlcpcb, null);
    }

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

        /**
         * True when group authorisation is enforced: production mode and at least one required group. Development
         * mode never enforces it (the development admin has no groups).
         */
        public boolean enforcesGroups() {
            return mode == Mode.PROD && oidc != null && !oidc.requiredGroups().isEmpty();
        }
    }

    /**
     * {@code kina.security.oidc.*}: generic OIDC provider used for web login in production mode, plus the group
     * authorisation settings (DESIGN.md 7.1).
     *
     * @param groupsClaim                    claim holding the user's groups; read from the ID token first, then from
     *                                       userinfo; a dotted path ({@code realm_access.roles}) reaches nested claims
     * @param requiredGroups                 a user must be in at least one of these groups (case-insensitive); empty
     *                                       disables the group check
     * @param allowedEmailDomains            optional: the {@code email} claim must end in one of these domains
     * @param emailFromPreferredUsername     when no {@code email} claim is present, take the address from
     *                                       {@code preferred_username} or {@code upn} if it contains {@code @}
     * @param requireVerifiedEmail           with allowed e-mail domains: refuse an address the provider marks as
     *                                       unverified ({@code email_verified=false}); false trusts the provider's
     *                                       domain-restricted login and only checks the domain
     * @param extraScopes                    scopes requested in addition to {@code openid profile email}
     * @param tokenEncryptionKey             base64 of 32 bytes; AES-GCM key for stored upstream refresh tokens; unset:
     *                                       upstream refresh tokens are not stored (fallback: periodic re-login)
     * @param membershipRecheckInterval      maximum age of a membership check before it is repeated
     * @param membershipGrace                how long after the last successful check access continues while the
     *                                       provider is unreachable
     * @param reloginIntervalWithoutRecheck  without a stored upstream refresh token: how long after the last
     *                                       interactive login refresh grants (and static tokens) keep working
     */
    public record Oidc(String issuerUri, String clientId, String clientSecret,
                       @DefaultValue("groups") String groupsClaim,
                       @DefaultValue List<String> requiredGroups,
                       @DefaultValue List<String> allowedEmailDomains,
                       @DefaultValue("false") boolean emailFromPreferredUsername,
                       @DefaultValue List<String> extraScopes,
                       String tokenEncryptionKey,
                       @DefaultValue("1h") Duration membershipRecheckInterval,
                       @DefaultValue("4h") Duration membershipGrace,
                       @DefaultValue("24h") Duration reloginIntervalWithoutRecheck,
                       @DefaultValue("true") boolean requireVerifiedEmail) {

        @ConstructorBinding
        public Oidc {
            groupsClaim = groupsClaim == null || groupsClaim.isBlank() ? "groups" : groupsClaim.strip();
            requiredGroups = cleaned(requiredGroups);
            allowedEmailDomains = cleaned(allowedEmailDomains);
            extraScopes = cleaned(extraScopes);
            membershipRecheckInterval = membershipRecheckInterval == null ? Duration.ofHours(1)
                    : membershipRecheckInterval;
            membershipGrace = membershipGrace == null ? Duration.ofHours(4) : membershipGrace;
            reloginIntervalWithoutRecheck = reloginIntervalWithoutRecheck == null ? Duration.ofHours(24)
                    : reloginIntervalWithoutRecheck;
        }

        /** Issuer and client only; every group-authorisation setting at its default (no group check). */
        public Oidc(String issuerUri, String clientId, String clientSecret) {
            this(issuerUri, clientId, clientSecret, null, null, null, false, null, null, null, null, null, true);
        }

        /** Every setting except {@code requireVerifiedEmail}, which is on (the default). */
        public Oidc(String issuerUri, String clientId, String clientSecret, String groupsClaim,
                    List<String> requiredGroups, List<String> allowedEmailDomains, boolean emailFromPreferredUsername,
                    List<String> extraScopes, String tokenEncryptionKey, Duration membershipRecheckInterval,
                    Duration membershipGrace, Duration reloginIntervalWithoutRecheck) {
            this(issuerUri, clientId, clientSecret, groupsClaim, requiredGroups, allowedEmailDomains,
                    emailFromPreferredUsername, extraScopes, tokenEncryptionKey, membershipRecheckInterval,
                    membershipGrace, reloginIntervalWithoutRecheck, true);
        }

        public boolean isConfigured() {
            return issuerUri != null && !issuerUri.isBlank() && clientId != null && !clientId.isBlank();
        }

        public boolean hasTokenEncryptionKey() {
            return tokenEncryptionKey != null && !tokenEncryptionKey.isBlank();
        }

        private static List<String> cleaned(List<String> values) {
            return values == null ? List.of()
                    : values.stream().filter(v -> v != null && !v.isBlank()).map(String::strip).toList();
        }

        @Override
        public String toString() {
            return "Oidc[issuerUri=" + issuerUri + ", clientId=" + clientId + ", clientSecret="
                    + (clientSecret == null || clientSecret.isBlank() ? "" : "***") + ", groupsClaim=" + groupsClaim
                    + ", requiredGroups=" + requiredGroups + ", allowedEmailDomains=" + allowedEmailDomains
                    + ", emailFromPreferredUsername=" + emailFromPreferredUsername + ", extraScopes=" + extraScopes
                    + ", tokenEncryptionKey=" + (hasTokenEncryptionKey() ? "***" : "")
                    + ", membershipRecheckInterval=" + membershipRecheckInterval + ", membershipGrace="
                    + membershipGrace + ", reloginIntervalWithoutRecheck=" + reloginIntervalWithoutRecheck
                    + ", requireVerifiedEmail=" + requireVerifiedEmail + "]";
        }
    }

    /**
     * {@code kina.tokens.*}: personal (static) access tokens created in the web UI.
     *
     * @param validity  lifetime of a token created in the web UI
     * @param uiEnabled false: {@code /} only explains that access goes through Claude's connector and
     *                  {@code POST /tokens} is 404
     */
    public record Tokens(@DefaultValue("30d") Duration validity, @DefaultValue("true") boolean uiEnabled) {
    }

    /**
     * {@code kina.oauth.*}: the OAuth authorization server for MCP clients (DESIGN.md 7).
     *
     * @param refreshTokenValidity           lifetime of a refresh token (rotated on every use)
     * @param accessTokenValidity            lifetime of an access token issued by {@code /oauth/token}
     * @param trustedClientMetadataHosts     hosts whose {@code https://} client IDs are accepted as Client ID
     *                                       Metadata Documents; {@code *.example.com} matches every subdomain
     * @param clientMetadataCache            how long a fetched metadata document is reused
     * @param autoApproveTrustedClients      skip the consent page for metadata-document clients (non-loopback
     *                                       redirect URIs only)
     * @param unusedClientRetention          dynamically registered clients unused for this long and without live
     *                                       tokens are deleted
     * @param registerRateLimitPerMinute     {@code POST /oauth/register} requests per client IP and minute; 0 or
     *                                       less disables the limit
     */
    public record OAuth(@DefaultValue("30d") Duration refreshTokenValidity,
                        @DefaultValue("1h") Duration accessTokenValidity,
                        @DefaultValue({"claude.ai", "claude.com", "*.anthropic.com"})
                        List<String> trustedClientMetadataHosts,
                        @DefaultValue("1h") Duration clientMetadataCache,
                        @DefaultValue("true") boolean autoApproveTrustedClients,
                        @DefaultValue("90d") Duration unusedClientRetention,
                        @DefaultValue("30") int registerRateLimitPerMinute) {

        public static final List<String> DEFAULT_TRUSTED_HOSTS = List.of("claude.ai", "claude.com", "*.anthropic.com");

        @ConstructorBinding
        public OAuth {
            refreshTokenValidity = refreshTokenValidity == null ? Duration.ofDays(30) : refreshTokenValidity;
            accessTokenValidity = accessTokenValidity == null ? Duration.ofHours(1) : accessTokenValidity;
            trustedClientMetadataHosts = trustedClientMetadataHosts == null ? DEFAULT_TRUSTED_HOSTS
                    : trustedClientMetadataHosts.stream().filter(h -> h != null && !h.isBlank()).map(String::strip)
                    .toList();
            clientMetadataCache = clientMetadataCache == null ? Duration.ofHours(1) : clientMetadataCache;
            unusedClientRetention = unusedClientRetention == null ? Duration.ofDays(90) : unusedClientRetention;
        }
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
     * {@code kina.metrics.*} (DESIGN.md 3.7).
     *
     * @param saveInterval how often the counters are saved to {@code metrics_counters} and the database gauges are
     *                     recomputed (read by the {@code @Scheduled} methods through the same property)
     */
    public record Metrics(@DefaultValue("30s") Duration saveInterval) {
    }

    /**
     * {@code kina.search.*}.
     *
     * @param distributorTimeout active-work budget of one distributor fetch; time spent waiting on a rate limit does
     *                           not count (DESIGN.md 3.6)
     * @param maxRequestDuration hard cap on one incoming request ({@code search_parts}, a whole batch, {@code get_part})
     *                           within which rate-limited distributor calls may wait and retry
     * @param strictConstraints  stated request attributes that exclude a part whose known value contradicts them
     *                           ({@code mounting}, {@code technology}; DESIGN.md 3.4 "Strict constraints"); a part that
     *                           does not state the attribute stays but ranks below known matches
     * @param quantity           ranking penalties for an order quantity above 1
     * @param lifecycle          ranking penalties for last-time-buy and supply-constrained parts
     */
    public record Search(
            @DefaultValue("40") int candidateWindow,
            @DefaultValue("10") int defaultMaxResults,
            @DefaultValue("50") int maxMaxResults,
            @DefaultValue("12s") Duration distributorTimeout,
            @DefaultValue("2m") Duration maxRequestDuration,
            @DefaultValue({"mounting", "technology"}) List<String> strictConstraints,
            @DefaultValue Quantity quantity,
            @DefaultValue Lifecycle lifecycle) {

        @ConstructorBinding
        public Search {
            strictConstraints = strictConstraints == null ? List.of() : strictConstraints.stream()
                    .filter(c -> c != null && !c.isBlank())
                    .map(c -> c.strip().toLowerCase(java.util.Locale.ROOT)).toList();
            quantity = quantity == null ? new Quantity(0.3, 0.15) : quantity;
            lifecycle = lifecycle == null ? new Lifecycle(0.1, 0.03) : lifecycle;
        }

        /** Without strict constraints and with the default quantity penalties (tests). */
        public Search(int candidateWindow, int defaultMaxResults, int maxMaxResults, Duration distributorTimeout,
                      Duration maxRequestDuration) {
            this(candidateWindow, defaultMaxResults, maxMaxResults, distributorTimeout, maxRequestDuration,
                    List.of("mounting", "technology"), null, null);
        }
    }

    /**
     * {@code kina.search.quantity.*}: deterministic-score deductions for an order of more than one piece (DESIGN.md 3.4
     * "Quantity"); a quantity of 1 changes nothing.
     *
     * @param stockShortfallPenalty deduction for a part with fewer pieces in stock than the quantity (such a part also
     *                              ranks below every part that has enough)
     * @param moqPenalty            largest deduction for a minimum order quantity above the quantity
     *                              ({@code moqPenalty * min(1, log10(moq / quantity) / 2)})
     */
    public record Quantity(@DefaultValue("0.3") double stockShortfallPenalty, @DefaultValue("0.15") double moqPenalty) {
    }

    /**
     * {@code kina.search.lifecycle.*}: deterministic-score deductions by the part's {@code lifecycle} (DESIGN.md 3.4).
     *
     * @param lastTimeBuyPenalty       {@code last_time_buy}: TME {@code AVAILABLE_WHILE_STOCKS_LAST}, Mouser end of
     *                                 life, obsolete, not recommended for new designs
     * @param supplyConstrainedPenalty {@code supply_constrained}: TME {@code HARDLY_AVAILABLE}
     */
    public record Lifecycle(@DefaultValue("0.1") double lastTimeBuyPenalty,
                            @DefaultValue("0.03") double supplyConstrainedPenalty) {
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
            @DefaultValue({"CANNOT_BE_ORDERED", "ONLY_FOR_SPECIAL_ORDER", "EXTERNAL_WAREHOUSE", "NOT_IN_OFFER",
                    "PRODUCT_BLOCKED", "INVALID", "BLOCKED_FOR_ZBL_*"})
            List<String> excludedStatuses) {

        /** {@code product_status} values that mean the part does not ship now; such parts are dropped. */
        public static final List<String> DEFAULT_EXCLUDED_STATUSES =
                List.of("CANNOT_BE_ORDERED", "ONLY_FOR_SPECIAL_ORDER", "EXTERNAL_WAREHOUSE", "NOT_IN_OFFER",
                        "PRODUCT_BLOCKED", "INVALID", "BLOCKED_FOR_ZBL_*");

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
