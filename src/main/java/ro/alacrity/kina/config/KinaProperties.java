package ro.alacrity.kina.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.time.Duration;

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

    public record Security(@DefaultValue("dev") Mode mode) {
    }

    public record Tokens(@DefaultValue("30d") Duration validity) {
    }

    public record OAuth(@DefaultValue("90d") Duration refreshTokenValidity) {
    }

    public record Cache(@DefaultValue("5d") Duration ttl) {
    }

    public record Search(
            @DefaultValue("40") int candidateWindow,
            @DefaultValue("10") int defaultMaxResults,
            @DefaultValue("50") int maxMaxResults,
            @DefaultValue("12s") Duration distributorTimeout) {
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
            @DefaultValue("0.6") double weight) {

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
            @DefaultValue("3") int maxPagesPerSearch) {

        public boolean isConfigured() {
            return token != null && !token.isBlank() && secret != null && !secret.isBlank();
        }

        @Override
        public String toString() {
            return "Tme[token=" + (token == null || token.isBlank() ? "" : "***") + ", secret="
                    + (secret == null || secret.isBlank() ? "" : "***") + ", country=" + country + ", currency="
                    + currency + ", language=" + language + ", baseUrl=" + baseUrl + ", maxResultsPerSearch="
                    + maxResultsPerSearch + ", maxPagesPerSearch=" + maxPagesPerSearch + "]";
        }
    }

    public record Jlcpcb(
            @DefaultValue("./data/jlcpcb") Path dataDir,
            @DefaultValue("parts-fts5.db") String library,
            @DefaultValue("https://bouni.github.io/kicad-jlcpcb-tools/") String baseUrl,
            @DefaultValue("5d") Duration refreshAfter,
            @DefaultValue("1h") Duration checkInterval,
            @DefaultValue("200") int maxResultsPerSearch) {

        /** {@code <data-dir>/<library>}. */
        public Path databaseFile() {
            return dataDir.resolve(library);
        }
    }
}
