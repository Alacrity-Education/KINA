package ro.alacrity.kina.oauth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.oauth.ClientMetadataDocument.InvalidDocumentException;
import ro.alacrity.kina.oauth.OAuthClientRepository.OAuthClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Resolves Client ID Metadata Document clients ({@code client_id} is an {@code https} URL). Only URLs whose host is
 * on {@code kina.oauth.trusted-client-metadata-hosts} are fetched (allowlist = SSRF protection and trust policy);
 * fetches have a 5 s timeout, follow no redirects and read at most 1 MB. A valid document is cached for
 * {@code kina.oauth.client-metadata-cache} and persisted to {@code oauth_clients} ({@code metadata_url} set), which the
 * authorization codes and refresh tokens reference. When a cached document expired and the host is unreachable, the
 * last persisted copy is used so token refreshes keep working through a short outage of the document host.
 */
@Component
@Slf4j
public class ClientMetadataDocumentResolver {

    static final Duration TIMEOUT = Duration.ofSeconds(5);
    static final int MAX_BYTES = 1024 * 1024;

    @Autowired private OAuthClientRepository clients;
    @Autowired private KinaProperties properties;
    private final HttpClient http = defaultHttpClient();
    private final Clock clock = Clock.systemUTC();
    /** Also accept {@code http://} client ids (tests with an in-process document server only; never in production). */
    boolean allowHttp;
    private List<String> trustedHosts;
    private Cache<String, OAuthClient> cache;

    /** An untrusted host, an invalid URL or document, or an unreachable host without a persisted copy. */
    public static final class UnresolvableClientException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        UnresolvableClientException(String message) {
            super(message);
        }
    }

    @PostConstruct
    void init() {
        trustedHosts = properties.oauth().trustedClientMetadataHosts().stream()
                .map(h -> h.strip().toLowerCase(Locale.ROOT)).toList();
        cache = Caffeine.newBuilder().expireAfterWrite(properties.oauth().clientMetadataCache()).maximumSize(1_000)
                .build();
    }

    static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** True when {@code clientId} is a metadata document URL (handled here rather than by registration lookup). */
    public boolean handles(String clientId) {
        return ClientMetadataDocument.isMetadataUrl(clientId)
                || (allowHttp && clientId != null && clientId.regionMatches(true, 0, "http://", 0, 7));
    }

    /** True when {@code host} matches the allowlist: exact (case-insensitive) or {@code *.suffix} for subdomains. */
    public static boolean hostTrusted(List<String> patterns, String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        for (String pattern : patterns) {
            String p = pattern.strip().toLowerCase(Locale.ROOT);
            if (p.startsWith("*.")) {
                String suffix = p.substring(1); // ".anthropic.com"
                if (normalized.endsWith(suffix) && normalized.length() > suffix.length()) {
                    return true;
                }
            } else if (normalized.equals(p)) {
                return true;
            }
        }
        return false;
    }

    /** Resolves the client, fetching its document when not cached. */
    public OAuthClient resolve(String clientId) {
        URI uri = validate(clientId);
        OAuthClient cached = cache.getIfPresent(clientId);
        if (cached != null) {
            return cached;
        }
        String body;
        try {
            body = fetch(uri);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Optional<OAuthClient> persisted = clients.findById(clientId).filter(OAuthClient::isMetadataDocumentClient);
            if (persisted.isPresent()) {
                log.warn("Client metadata document {} unreachable ({}); using the persisted copy", clientId,
                        e.getClass().getSimpleName());
                return persisted.get();
            }
            throw new UnresolvableClientException("The client metadata document could not be fetched");
        }
        OAuthClient client;
        try {
            client = ClientMetadataDocument.parse(clientId, body, now());
        } catch (InvalidDocumentException e) {
            log.warn("Rejected client metadata document {}: {}", clientId, e.getMessage());
            throw new UnresolvableClientException(e.getMessage());
        }
        clients.upsertMetadataDocumentClient(client);
        OAuthClient stored = clients.findById(clientId).orElse(client);
        cache.put(clientId, stored);
        log.info("Resolved client metadata document {} ({})", clientId, stored.displayName());
        return stored;
    }

    private URI validate(String clientId) {
        URI uri;
        try {
            if (allowHttp && clientId.regionMatches(true, 0, "http://", 0, 7)) {
                uri = ClientMetadataDocument.validateUrl("https://" + clientId.substring(7));
                uri = URI.create(clientId);
            } else {
                uri = ClientMetadataDocument.validateUrl(clientId);
            }
        } catch (InvalidDocumentException e) {
            throw new UnresolvableClientException(e.getMessage());
        }
        if (!hostTrusted(trustedHosts, uri.getHost())) {
            log.info("Refused client metadata document {}: host not in kina.oauth.trusted-client-metadata-hosts",
                    clientId);
            throw new UnresolvableClientException("The client's metadata document host is not trusted by this "
                    + "server");
        }
        return uri;
    }

    /** GET with a 5 s budget for the whole exchange (headers and body) and a 1 MB body cap. */
    private String fetch(URI uri) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            if (response.statusCode() >= 500) {
                throw new IOException("HTTP " + response.statusCode());
            }
            if (response.statusCode() != 200) {
                throw new UnresolvableClientException("The client metadata document returned HTTP "
                        + response.statusCode());
            }
            CompletableFuture<byte[]> read = CompletableFuture.supplyAsync(() -> {
                try {
                    return in.readNBytes(MAX_BYTES + 1);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }, task -> Thread.ofVirtual().name("client-metadata-read").start(task));
            byte[] bytes;
            try {
                bytes = read.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                read.cancel(true);
                throw new IOException("Timed out reading the client metadata document");
            } catch (ExecutionException e) {
                throw new IOException("Reading the client metadata document failed", e.getCause());
            }
            if (bytes.length > MAX_BYTES) {
                throw new UnresolvableClientException("The client metadata document is larger than 1 MB");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
