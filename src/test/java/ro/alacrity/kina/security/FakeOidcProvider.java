package ro.alacrity.kina.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process OpenID Connect provider for integration tests ({@code com.sun.net.httpserver.HttpServer}): discovery,
 * JWKS with a generated RSA key, {@code /authorize} that immediately redirects back with a code for the scripted user,
 * {@code /token} (authorization code and rotating refresh tokens, RS256 ID tokens with a {@code groups} claim) and
 * {@code /userinfo}. Users, their groups and failure modes are controlled by the test.
 */
public final class FakeOidcProvider implements AutoCloseable {

    public static final String CLIENT_ID = "kina-test-client";
    public static final String CLIENT_SECRET = "kina-test-secret";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A scripted identity. {@code groupsInIdToken=false}: groups only via userinfo. */
    public static final class User {
        final String subject;
        final String email;
        volatile List<String> groups;
        volatile boolean groupsInIdToken = true;
        volatile boolean grantRevoked;

        User(String subject, String email, List<String> groups) {
            this.subject = subject;
            this.email = email;
            this.groups = groups;
        }
    }

    private record Pending(String subject, String nonce) {
    }

    private final HttpServer server;
    private final RSAKey key;
    private final String issuer;
    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final Map<String, Pending> codes = new ConcurrentHashMap<>();
    private final Map<String, String> accessTokens = new ConcurrentHashMap<>();
    private final Map<String, String> refreshTokens = new ConcurrentHashMap<>();
    private final AtomicInteger refreshGrants = new AtomicInteger();
    private volatile String nextSubject;
    private volatile boolean tokenEndpointDown;

    public FakeOidcProvider() {
        try {
            key = new RSAKeyGenerator(2048).keyID("test-key-1").generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (JOSEException | IOException e) {
            throw new IllegalStateException(e);
        }
        issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/issuer";
        server.createContext("/", this::handle);
        server.start();
    }

    public String issuer() {
        return issuer;
    }

    public User user(String subject, String email, String... groups) {
        User user = new User(subject, email, List.of(groups));
        users.put(subject, user);
        return user;
    }

    /** The user the next {@code /authorize} request signs in. */
    public void signInAs(String subject) {
        nextSubject = subject;
    }

    public void setGroups(String subject, String... groups) {
        users.get(subject).groups = List.of(groups);
    }

    public void setGroupsInIdToken(String subject, boolean inIdToken) {
        users.get(subject).groupsInIdToken = inIdToken;
    }

    public void revokeGrant(String subject) {
        users.get(subject).grantRevoked = true;
    }

    /** {@code true}: the token endpoint answers 503 (provider unreachable). */
    public void setTokenEndpointDown(boolean down) {
        tokenEndpointDown = down;
    }

    public int refreshGrants() {
        return refreshGrants.get();
    }

    /** True when {@code value} is a refresh token this provider issued (to check KINA never stores it in clear). */
    public boolean issuedRefreshToken(String value) {
        return refreshTokens.containsKey(value);
    }

    public List<String> liveRefreshTokens() {
        return new ArrayList<>(refreshTokens.keySet());
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---- HTTP ------------------------------------------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            switch (path) {
                case "/issuer/.well-known/openid-configuration" -> json(exchange, 200, discovery());
                case "/jwks" -> json(exchange, 200, new JWKSet(key.toPublicJWK()).toJSONObject());
                case "/authorize" -> authorize(exchange);
                case "/token" -> token(exchange);
                case "/userinfo" -> userinfo(exchange);
                default -> json(exchange, 404, Map.of("error", "not_found"));
            }
        } catch (RuntimeException | JOSEException e) {
            json(exchange, 500, Map.of("error", "server_error", "error_description", e.toString()));
        } finally {
            exchange.close();
        }
    }

    private Map<String, Object> discovery() {
        String base = issuer.substring(0, issuer.length() - "/issuer".length());
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("issuer", issuer);
        doc.put("authorization_endpoint", base + "/authorize");
        doc.put("token_endpoint", base + "/token");
        doc.put("userinfo_endpoint", base + "/userinfo");
        doc.put("jwks_uri", base + "/jwks");
        doc.put("response_types_supported", List.of("code"));
        doc.put("subject_types_supported", List.of("public"));
        doc.put("id_token_signing_alg_values_supported", List.of("RS256"));
        doc.put("scopes_supported", List.of("openid", "profile", "email", "offline_access"));
        doc.put("token_endpoint_auth_methods_supported", List.of("client_secret_basic"));
        doc.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
        doc.put("claims_supported", List.of("sub", "email", "name", "groups"));
        return doc;
    }

    private void authorize(HttpExchange exchange) throws IOException {
        Map<String, String> query = parse(exchange.getRequestURI().getRawQuery());
        String code = "code-" + UUID.randomUUID();
        codes.put(code, new Pending(nextSubject, query.get("nonce")));
        String location = query.get("redirect_uri") + "?code=" + encode(code) + "&state=" + encode(query.get("state"));
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
    }

    private void token(HttpExchange exchange) throws IOException, JOSEException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Basic ")) {
            json(exchange, 401, Map.of("error", "invalid_client"));
            return;
        }
        Map<String, String> form = parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String grantType = form.get("grant_type");
        if ("authorization_code".equals(grantType)) {
            Pending pending = codes.remove(form.get("code"));
            if (pending == null || !users.containsKey(pending.subject())) {
                json(exchange, 400, Map.of("error", "invalid_grant"));
                return;
            }
            json(exchange, 200, tokens(users.get(pending.subject()), pending.nonce()));
        } else if ("refresh_token".equals(grantType)) {
            refreshGrants.incrementAndGet();
            if (tokenEndpointDown) {
                json(exchange, 503, Map.of("error", "temporarily_unavailable"));
                return;
            }
            String subject = refreshTokens.remove(form.getOrDefault("refresh_token", ""));
            if (subject == null || users.get(subject).grantRevoked) {
                json(exchange, 400, Map.of("error", "invalid_grant"));
                return;
            }
            json(exchange, 200, tokens(users.get(subject), null));
        } else {
            json(exchange, 400, Map.of("error", "unsupported_grant_type"));
        }
    }

    private Map<String, Object> tokens(User user, String nonce) throws JOSEException {
        String accessToken = "at-" + UUID.randomUUID();
        String refreshToken = "rt-" + UUID.randomUUID();
        accessTokens.put(accessToken, user.subject);
        refreshTokens.put(refreshToken, user.subject);
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(user.subject)
                .audience(CLIENT_ID)
                .claim("azp", CLIENT_ID)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("email", user.email)
                .claim("email_verified", true)
                .claim("name", "User " + user.subject);
        if (nonce != null) {
            claims.claim("nonce", nonce);
        }
        if (user.groupsInIdToken) {
            claims.claim("groups", user.groups);
        }
        SignedJWT idToken = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                claims.build());
        idToken.sign(new RSASSASigner(key));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", accessToken);
        body.put("token_type", "Bearer");
        body.put("expires_in", 3600);
        body.put("refresh_token", refreshToken);
        body.put("id_token", idToken.serialize());
        return body;
    }

    private void userinfo(HttpExchange exchange) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String subject = authorization == null ? null : accessTokens.get(authorization.replaceFirst("^Bearer ", ""));
        if (subject == null) {
            json(exchange, 401, Map.of("error", "invalid_token"));
            return;
        }
        User user = users.get(subject);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sub", user.subject);
        body.put("email", user.email);
        body.put("email_verified", true);
        body.put("name", "User " + user.subject);
        body.put("groups", user.groups);
        json(exchange, 200, body);
    }

    private static void json(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    static Map<String, String> parse(String encoded) {
        Map<String, String> result = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return result;
        }
        for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            result.put(name, value);
        }
        return result;
    }

    private static String encode(String value) {
        return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
