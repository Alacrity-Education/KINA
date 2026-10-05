package ro.alacrity.kina.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.security.UserRepository.User;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Group authorisation after login (DESIGN.md 7.1): stores the identity provider's refresh token at login and
 * re-verifies the user's group membership against the provider when the last check is older than
 * {@code kina.security.oidc.membership-recheck-interval}.
 * <p>
 * Re-check: the stored (AES-GCM encrypted) upstream refresh token is redeemed at the provider's token endpoint
 * (discovery), the rotated refresh token is stored, and the groups claim is read from the new ID token or, when the ID
 * token lacks it, from userinfo. Outcomes:
 * <ul>
 *   <li>member: {@code membership_checked_at} is updated;</li>
 *   <li>not a member, or the provider answers {@code invalid_grant}: the user is revoked (every access and refresh
 *   token, {@code access_revoked_at} set);</li>
 *   <li>provider unreachable (network error, timeout, 5xx, other errors): access continues while the last successful
 *   check is younger than {@code membership-grace};</li>
 *   <li>no upstream refresh token (no encryption key, or the provider issued none): access continues while the last
 *   interactive login is younger than {@code relogin-interval-without-recheck}; then a new login is required.</li>
 * </ul>
 * Enforcement points: {@code /oauth/token} refresh grants (synchronous, {@link #checkRefreshGrant}) and the bearer
 * filter for static web-UI tokens (asynchronous re-check, {@link #allowsStaticToken}). Only active when
 * {@link KinaProperties.Security#enforcesGroups()}; otherwise only {@code access_revoked_at} is honoured.
 * <p>
 * Logs carry the user id and subject, never e-mail addresses, tokens or claims.
 */
@Component
@Slf4j
public class MembershipVerifier {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };
    private static final int LOCK_STRIPES = 64;
    private static final String REGISTRATION_ID = LazyOidcClientRegistrationRepository.REGISTRATION_ID;

    private final boolean enforced;
    private final KinaProperties.Oidc oidc;
    private final OidcAccessPolicy policy;
    private final UpstreamTokenCipher cipher;
    private final UserRepository users;
    private final AccessTokenService tokens;
    private final ObjectProvider<ClientRegistrationRepository> registrations;
    private final JwtDecoderFactory<ClientRegistration> idTokenDecoders;
    private final HttpClient http;
    private final Clock clock;
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    /** Users whose last re-check found the provider unreachable (cleared by a successful check). */
    private final Map<UUID, Instant> unreachableSince = new ConcurrentHashMap<>();

    @Autowired
    public MembershipVerifier(KinaProperties properties, UserRepository users, AccessTokenService tokens,
                              ObjectProvider<ClientRegistrationRepository> registrations,
                              ObjectProvider<JwtDecoderFactory<ClientRegistration>> idTokenDecoders) {
        this(properties, users, tokens, registrations, idTokenDecoders.getIfAvailable(OidcIdTokenDecoders::new),
                OidcHttp.httpClient(), Clock.systemUTC());
    }

    MembershipVerifier(KinaProperties properties, UserRepository users, AccessTokenService tokens,
                       ObjectProvider<ClientRegistrationRepository> registrations,
                       JwtDecoderFactory<ClientRegistration> idTokenDecoders, HttpClient http, Clock clock) {
        this.enforced = properties.security().enforcesGroups();
        this.oidc = properties.security().oidc();
        this.policy = new OidcAccessPolicy(oidc);
        this.cipher = UpstreamTokenCipher.fromBase64Key(oidc.tokenEncryptionKey());
        this.users = users;
        this.tokens = tokens;
        this.registrations = registrations;
        this.idTokenDecoders = idTokenDecoders;
        this.http = http;
        this.clock = clock;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
        if (enforced) {
            log.info("Group authorisation enabled: required groups {} (claim '{}'), re-check every {}, grace {}",
                    oidc.requiredGroups(), oidc.groupsClaim(), oidc.membershipRecheckInterval(),
                    oidc.membershipGrace());
            if (!cipher.isEnabled()) {
                log.warn("KINA_TOKEN_ENCRYPTION_KEY (kina.security.oidc.token-encryption-key) is not set: the identity "
                        + "provider's refresh tokens are not stored, so KINA cannot re-check group membership while "
                        + "the user is away. OAuth refresh grants are refused, and static tokens stop working, {} after "
                        + "the user's last interactive login (which re-checks the group). Set a key "
                        + "(openssl rand -base64 32) to re-check membership server-side instead.",
                        oidc.reloginIntervalWithoutRecheck());
            }
        }
    }

    /** Result of an enforcement decision; {@code description} explains a refusal (safe to show to clients). */
    public record Verdict(boolean allowed, String description) {

        static final Verdict ALLOW = new Verdict(true, null);

        static Verdict deny(String description) {
            return new Verdict(false, description);
        }
    }

    enum RecheckOutcome { MEMBER, NOT_MEMBER, GRANT_INVALID, UNAVAILABLE, NO_UPSTREAM_TOKEN, REVOKED }

    public boolean isEnforced() {
        return enforced;
    }

    /** True when upstream refresh tokens are requested (offline_access) and stored. */
    public boolean storesUpstreamTokens() {
        return enforced && cipher.isEnabled();
    }

    // ---- login -------------------------------------------------------------------------------------------------

    /** A login passed every check: the membership is fresh and an earlier revocation is lifted. */
    public void recordSuccessfulLogin(UUID userId) {
        users.markMembershipChecked(userId, now(), true);
        unreachableSince.remove(userId);
    }

    /** A login was denied (group or e-mail domain): blocks an existing user and revokes all their tokens. */
    public void recordDeniedLogin(UUID userId) {
        revoke(userId);
    }

    /** Stores the provider's refresh token issued at login (encrypted); a no-op unless re-checks are enabled. */
    public void storeUpstreamRefreshToken(UUID userId, String refreshToken) {
        if (!storesUpstreamTokens()) {
            return;
        }
        String ciphertext = refreshToken == null || refreshToken.isBlank() ? null
                : cipher.encrypt(refreshToken, userId.toString());
        if (ciphertext == null) {
            log.warn("The identity provider issued no refresh token for user {}; membership cannot be re-checked "
                    + "server-side (does the provider allow the offline_access scope for this client?)", userId);
        }
        users.storeUpstreamRefreshToken(userId, ciphertext, now());
    }

    // ---- enforcement -------------------------------------------------------------------------------------------

    /** True when the user is blocked ({@code access_revoked_at}) or unknown. Cheap; no provider call. */
    public boolean isBlocked(UUID userId) {
        return users.findById(userId).map(User::isRevoked).orElse(true);
    }

    /**
     * {@code /oauth/token} refresh grant: synchronous re-check when due. Must be called before the presented refresh
     * token is rotated.
     */
    public Verdict checkRefreshGrant(UUID userId) {
        Optional<User> found = users.findById(userId);
        if (found.isEmpty() || found.get().isRevoked()) {
            return Verdict.deny("Access to KINA was revoked; sign in again");
        }
        if (!enforced) {
            return Verdict.ALLOW;
        }
        User user = found.get();
        Instant now = now();
        if (isFresh(user.membershipCheckedAt(), now)) {
            return Verdict.ALLOW;
        }
        RecheckOutcome outcome = recheck(userId);
        return switch (outcome) {
            case MEMBER -> Verdict.ALLOW;
            case NOT_MEMBER, GRANT_INVALID, REVOKED ->
                    Verdict.deny("Group membership required for KINA could not be confirmed; access revoked");
            case UNAVAILABLE -> within(user.membershipCheckedAt(), oidc.membershipGrace(), now) ? Verdict.ALLOW
                    : Verdict.deny("The identity provider is unreachable and the last membership check is older "
                    + "than the grace period; sign in again later");
            case NO_UPSTREAM_TOKEN -> within(user.membershipCheckedAt(), oidc.reloginIntervalWithoutRecheck(), now)
                    ? Verdict.ALLOW
                    : Verdict.deny("Group membership must be re-confirmed; sign in again");
        };
    }

    /**
     * Bearer filter, static web-UI token: never blocks on the provider. When a re-check is due it runs in the
     * background (at most one per user); the request is allowed unless the user is revoked, the last successful check
     * is older than the grace period and the latest re-check found the provider unreachable, or (no upstream refresh
     * token) the last login is older than the re-login interval.
     */
    public boolean allowsStaticToken(User user) {
        if (user.isRevoked()) {
            return false;
        }
        if (!enforced) {
            return true;
        }
        Instant now = now();
        Instant checked = user.membershipCheckedAt();
        if (isFresh(checked, now)) {
            return true;
        }
        if (user.hasUpstreamRefreshToken() && cipher.isEnabled()) {
            recheckAsync(user.id());
            if (checked == null) {
                return false;
            }
            boolean unreachable = unreachableSince.containsKey(user.id());
            return !unreachable || within(checked, oidc.membershipGrace(), now);
        }
        return within(checked, oidc.reloginIntervalWithoutRecheck(), now);
    }

    /** Starts a background re-check unless one is already running for the user. */
    void recheckAsync(UUID userId) {
        if (!inFlight.add(userId)) {
            return;
        }
        Thread.ofVirtual().name("membership-recheck").start(() -> {
            try {
                recheck(userId);
            } catch (RuntimeException e) {
                log.warn("Background membership re-check for user {} failed: {}", userId, e.toString());
            } finally {
                inFlight.remove(userId);
            }
        });
    }

    /** True while a background re-check runs for the user (tests). */
    boolean recheckInFlight(UUID userId) {
        return inFlight.contains(userId);
    }

    // ---- re-check ----------------------------------------------------------------------------------------------

    /**
     * Re-verifies membership at the provider and applies the outcome (update, or revoke). Serialised per user so
     * concurrent callers never redeem the same upstream refresh token twice.
     */
    RecheckOutcome recheck(UUID userId) {
        ReentrantLock lock = locks[Math.floorMod(userId.hashCode(), LOCK_STRIPES)];
        lock.lock();
        try {
            Optional<User> found = users.findById(userId);
            if (found.isEmpty() || found.get().isRevoked()) {
                return RecheckOutcome.REVOKED;
            }
            User user = found.get();
            if (isFresh(user.membershipCheckedAt(), now())) {
                return RecheckOutcome.MEMBER; // another caller just checked
            }
            RecheckOutcome outcome = askProvider(user);
            switch (outcome) {
                case MEMBER -> {
                    users.markMembershipChecked(userId, now(), false);
                    unreachableSince.remove(userId);
                }
                case NOT_MEMBER, GRANT_INVALID -> {
                    log.warn("Membership re-check: user {} (subject {}) {}; revoking all tokens", userId,
                            user.subject(), outcome == RecheckOutcome.NOT_MEMBER
                                    ? "is no longer in a required group" : "was rejected by the identity provider");
                    revoke(userId);
                }
                case UNAVAILABLE -> unreachableSince.putIfAbsent(userId, now());
                default -> {
                }
            }
            return outcome;
        } finally {
            lock.unlock();
        }
    }

    private RecheckOutcome askProvider(User user) {
        if (!cipher.isEnabled() || !user.hasUpstreamRefreshToken()) {
            return RecheckOutcome.NO_UPSTREAM_TOKEN;
        }
        Optional<String> refreshToken = users.upstreamRefreshToken(user.id())
                .flatMap(stored -> cipher.decrypt(stored, user.id().toString()));
        if (refreshToken.isEmpty()) {
            log.warn("Stored upstream refresh token of user {} cannot be decrypted (was the encryption key changed?)",
                    user.id());
            return RecheckOutcome.NO_UPSTREAM_TOKEN;
        }
        ClientRegistrationRepository repository = registrations.getIfAvailable();
        ClientRegistration registration = repository == null ? null
                : repository.findByRegistrationId(REGISTRATION_ID);
        if (registration == null) {
            log.warn("Membership re-check for user {}: OIDC provider discovery is not available", user.id());
            return RecheckOutcome.UNAVAILABLE;
        }
        try {
            ProviderResponse token = refresh(registration, refreshToken.get());
            if (token.status() >= 500) {
                log.warn("Membership re-check for user {}: token endpoint answered {}", user.id(), token.status());
                return RecheckOutcome.UNAVAILABLE;
            }
            if (token.status() != 200) {
                Object error = token.body().get("error");
                if ("invalid_grant".equals(error)) {
                    return RecheckOutcome.GRANT_INVALID;
                }
                log.warn("Membership re-check for user {}: token endpoint answered {} {}", user.id(), token.status(),
                        error);
                return RecheckOutcome.UNAVAILABLE;
            }
            Object rotated = token.body().get("refresh_token");
            if (rotated instanceof String newRefreshToken && !newRefreshToken.isBlank()) {
                users.storeUpstreamRefreshToken(user.id(), cipher.encrypt(newRefreshToken, user.id().toString()),
                        now());
            }
            Map<String, Object> idClaims = null;
            if (token.body().get("id_token") instanceof String idToken) {
                Jwt jwt = idTokenDecoders.createDecoder(registration).decode(idToken);
                if (!user.subject().equals(jwt.getSubject())) {
                    log.warn("Membership re-check for user {}: refreshed ID token has another subject", user.id());
                    return RecheckOutcome.UNAVAILABLE;
                }
                idClaims = jwt.getClaims();
            }
            Map<String, Object> userInfoClaims = null;
            if (idClaims == null || !policy.hasGroupsClaim(idClaims)) {
                String userInfoUri = registration.getProviderDetails().getUserInfoEndpoint().getUri();
                Object accessToken = token.body().get("access_token");
                if (userInfoUri != null && !userInfoUri.isBlank() && accessToken instanceof String bearer) {
                    ProviderResponse userInfo = userInfo(userInfoUri, bearer);
                    if (userInfo.status() != 200) {
                        log.warn("Membership re-check for user {}: userinfo answered {}", user.id(), userInfo.status());
                        return RecheckOutcome.UNAVAILABLE;
                    }
                    if (!user.subject().equals(userInfo.body().get("sub"))) {
                        log.warn("Membership re-check for user {}: userinfo returned another subject", user.id());
                        return RecheckOutcome.UNAVAILABLE;
                    }
                    userInfoClaims = userInfo.body();
                }
            }
            return policy.evaluateGroups(idClaims, userInfoClaims).allowed() ? RecheckOutcome.MEMBER
                    : RecheckOutcome.NOT_MEMBER;
        } catch (IOException | JwtException | JacksonException | IllegalStateException e) {
            log.warn("Membership re-check for user {}: identity provider unreachable or invalid response: {}",
                    user.id(), e.getClass().getSimpleName());
            return RecheckOutcome.UNAVAILABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RecheckOutcome.UNAVAILABLE;
        }
    }

    record ProviderResponse(int status, Map<String, Object> body) {
    }

    private ProviderResponse refresh(ClientRegistration registration, String refreshToken)
            throws IOException, InterruptedException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                        registration.getProviderDetails().getTokenUri()))
                .timeout(OidcHttp.READ_TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded");
        ClientAuthenticationMethod method = registration.getClientAuthenticationMethod();
        String secret = registration.getClientSecret() == null ? "" : registration.getClientSecret();
        if (ClientAuthenticationMethod.CLIENT_SECRET_BASIC.equals(method) && !secret.isEmpty()) {
            String credentials = encode(registration.getClientId()) + ":" + encode(secret);
            request.header("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        } else {
            form.put("client_id", registration.getClientId());
            if (!secret.isEmpty() && !ClientAuthenticationMethod.NONE.equals(method)) {
                form.put("client_secret", secret);
            }
        }
        String body = form.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpResponse<String> response = http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new ProviderResponse(response.statusCode(), parse(response.body()));
    }

    private ProviderResponse userInfo(String uri, String accessToken) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
                .timeout(OidcHttp.READ_TIMEOUT)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new ProviderResponse(response.statusCode(), parse(response.body()));
    }

    private static Map<String, Object> parse(String body) {
        if (body == null || body.isBlank() || !body.strip().startsWith("{")) {
            return Map.of();
        }
        return JSON.readValue(body, OBJECT_MAP);
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private void revoke(UUID userId) {
        users.markRevoked(userId, now());
        tokens.revokeAllForUser(userId);
        unreachableSince.remove(userId);
    }

    private boolean isFresh(Instant checkedAt, Instant now) {
        return within(checkedAt, oidc.membershipRecheckInterval(), now);
    }

    private static boolean within(Instant since, Duration window, Instant now) {
        return since != null && !since.plus(window).isBefore(now);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
