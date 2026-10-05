# KINA design specification

KINA is an MCP server and HTTP API that lets an LLM (Claude) search electronic components across
three distributors: **LCSC** (served from the JLCPCB parts database), **TME** (API v2) and **Mouser**.
This document is the binding contract for the implementation. `task.md` holds the original
requirements; where this document is more specific, follow this document.

## 1. Stack

| Concern | Decision |
|---|---|
| Language / build | Java 21 (`maven.compiler.release=21`), Maven with wrapper (`./mvnw`), single module |
| Framework | Spring Boot **4.1.1** (parent POM), Spring Framework 7, Jackson **3** (`tools.jackson.*`; annotations stay `com.fasterxml.jackson.annotation.*`) |
| MCP | Spring AI **2.0.1** BOM, `spring-ai-starter-mcp-server-webmvc` (MCP Java SDK 2.0.0, Jackson 3), Streamable HTTP transport, **stateless** protocol, endpoint `/mcp`; tool annotations `org.springframework.ai.mcp.annotation.McpTool`/`McpToolParam` |
| Web | Spring MVC, Thymeleaf for the small token UI, Spring Security, `spring-boot-starter-security-oauth2-client` for OIDC login (Boot 4 deprecates the old `spring-boot-starter-oauth2-client` name) |
| Persistence | PostgreSQL 17, Flyway migrations, `JdbcClient` (no JPA), JSONB for cached payloads |
| JLCPCB data | `org.xerial:sqlite-jdbc` reading the downloaded FTS5 database read-only |
| HTTP clients | Spring `RestClient` on the JDK `HttpClient`, explicit connect/read timeouts everywhere |
| Concurrency | `spring.threads.virtual.enabled=true`; distributor fetches run in parallel on virtual threads |
| Tests | JUnit 5, Mockito, `MockRestServiceServer` / recorded JSON fixtures, Testcontainers 2.x PostgreSQL (`org.testcontainers:testcontainers-postgresql`, class `org.testcontainers.postgresql.PostgreSQLContainer`) for repository and context tests |
| Packaging | Multi-stage `Dockerfile`, `compose.yaml` with services `kina`, `postgres`, `laya-serve`; `compose.cuda.yaml` GPU overlay |

Verify every starter artifact name against the Spring Boot 4.1.1 `spring-boot-dependencies` BOM
before writing the POM (Boot 4 renamed several starters, e.g. `spring-boot-starter-webmvc`,
`spring-boot-starter-flyway`, `spring-boot-starter-jdbc`). When an API is uncertain, download the
sources (`./mvnw dependency:sources`) and read them rather than guessing.

Base package: `ro.alacrity.kina`.

```
ro.alacrity.kina
├── KinaApplication
├── config/          KinaProperties (@ConfigurationProperties("kina")), HTTP client beans, Jackson, forwarded headers
├── domain/          Distributor, Part, PriceBreak, ParsedQuery, SearchRequest, SearchResponse DTOs, RankingMode
├── distributor/     DistributorClient, DistributorSearchPage, DistributorException, DistributorRegistry
│   ├── mouser/      MouserClient, MouserProperties, response records, MouserPartMapper
│   ├── tme/         TmeClient, TmeTokenManager, TmeProperties, response records, TmePartMapper
│   └── lcsc/        JlcpcbDatabaseManager (download/refresh), JlcpcbSqliteSearch, LcscClient, JlcpcbPriceParser
├── cache/           PartCacheRepository, SearchCacheRepository, CacheProperties
├── search/          QueryParser, ParametricExtractor, DeterministicRanker, PartRanker, LayaPartRanker,
│                    RankingService, RankingScoreCache, PartSearchService, PartLookupService, DistributorStatusService
├── mcp/             KinaMcpTools (@McpTool methods)
├── api/             PartsController, DistributorsController (/api/v1), ApiExceptionHandler (ProblemDetail)
├── security/        SecurityConfig, SecurityProperties, DevModeAuthenticationFilter, BearerTokenAuthenticationFilter,
│                    AccessTokenService, AccessTokenRepository, UserRepository, OidcUserSynchronizer, KinaPrincipal
├── oauth/           OAuthMetadataController, ClientRegistrationController, AuthorizationController, TokenController,
│                    RevocationController, OAuthClientRepository, AuthorizationCodeRepository, RefreshTokenRepository, Pkce
└── web/             TokenPageController (Thymeleaf), PublicUrlResolver
```

## 2. Domain model (exact contracts)

```java
public enum Distributor { LCSC, TME, MOUSER }

public enum RankingMode { LAYA, FALLBACK }

public record PriceBreak(int quantity, BigDecimal unitPrice, String currency) {}

/** A single in-stock offer from one distributor. Never construct one with stock <= 0. */
public record Part(
    Distributor distributor,
    String distributorPartNumber,      // LCSC "Cxxxxx", TME symbol, Mouser part number
    String manufacturer,
    String manufacturerPartNumber,
    String description,
    String category,                   // distributor category path, may be null
    String packageName,                // "0805", "SOT-23", may be null
    int stock,                         // quantity that ships now; always > 0
    Integer minimumOrderQuantity,      // null when unknown
    Integer orderMultiple,             // null when unknown
    List<PriceBreak> prices,           // ascending by quantity, complete list as fetched
    String datasheetUrl,
    String photoUrl,                   // null when the distributor gives none (LCSC)
    String productUrl,
    Map<String, String> attributes,    // parametric attributes, insertion-ordered, e.g. "Capacitance" -> "10uF"
    Map<String, Object> extra,         // distributor specific details (lifecycle, RoHS, library type, lead time...)
    Instant fetchedAt
) {}
```

Price breaks are stored complete; they are trimmed to the **3 smallest quantity brackets** only
when building responses (`SearchResponse`/`PartResponse`).

### Distributor client contract

```java
public interface DistributorClient {
    Distributor distributor();
    boolean isConfigured();            // false when credentials/database are missing -> distributor reported as unavailable
    int maxPageSize();                 // Mouser 50, TME = max-results-per-search (default 60, API max 100), LCSC 200
    /** Returns in-stock parts only (stock > 0), in the distributor's own relevance order.
     *  offset is 0-based. totalResults is the distributor-reported total for the query. */
    DistributorSearchPage search(String query, int offset, int limit) throws DistributorException;
    Optional<Part> getPart(String distributorPartNumber) throws DistributorException;
}

public record DistributorSearchPage(List<Part> parts, int totalResults, boolean hasMore) {}

public class DistributorException extends RuntimeException {
    public enum Kind { NOT_CONFIGURED, UNAVAILABLE, RATE_LIMITED, BAD_RESPONSE, TIMEOUT }
    ...
}
```

Stock rule (all distributors): only quantity that ships now counts. "Expected", "on order",
"factory stock" and lead-time quantities are ignored. Parts with no ships-now stock are **never**
returned, cached or ranked.

## 3. Search semantics

### 3.1 Request

```
SearchRequest(String query, int maxResults /*1..50, default 10*/, Set<Distributor> distributors /*default: all configured*/, boolean bypassCache)
BatchSearchRequest(List<SearchRequest> queries /*1..20*/, Set<Distributor> distributors, boolean bypassCache)
```

### 3.2 Per-distributor fetch with cache

Normalised query key: trim, collapse whitespace, lower-case, Unicode NFKC, `µ` -> `u`, `Ω` -> `ohm`.
Freshness: `kina.cache.ttl` default `5d`; anything younger is fresh.

Fetch window per distributor: `window = max(maxResults, kina.search.candidate-window /*default 40*/)`
capped by `kina.distributors.<name>.max-results-per-search` (Mouser default 50 = one API call,
TME default 60, LCSC default 200).

Algorithm (`PartSearchService.fetchDistributor`; every requested distributor runs on its own virtual thread,
bounded by `kina.search.distributor-timeout`):

1. `bypassCache == false`: read `cached_searches(distributor, query_key)`. When it is fresh, load its parts from
   `cached_parts` (fresh rows only); if any part is missing or stale, go to step 2 with `offset = 0` (`MISS`).
   - the list is sufficient (`exhausted`, or `part_numbers.size >= window`, or `part_numbers.size >= maxResults`)
     -> status `HIT`, no distributor call. The `>= maxResults` clause keeps a repeat of the same query from
     re-querying a distributor whose single allowed page held fewer than `window` in-stock parts (Mouser quota).
   - fresh but shorter than `maxResults` and not exhausted ("further querying is needed"): keep the cached list and
     fetch more pages starting at `offset = next_offset` (the raw distributor record offset stored with the list;
     the part count when unknown), append -> status `PARTIAL`.
   - missing or stale -> step 2 with `offset = 0`, status `MISS`.
2. Call `DistributorClient.search(query, offset, limit)` page by page with `limit = maxPageSize()` (the first page is
   shortened so pages end on a page boundary) and `offset += limit`, until `window` in-stock parts are collected,
   the distributor reports no more results (`hasMore == false`), `max-pages-per-search` (Mouser 1, TME 3, LCSC 1) is
   hit, or the next page would not finish before the distributor deadline. Paging is driven by **raw record offsets**,
   not by the number of parts kept: distributors drop records without ships-now stock (live: TME reported 32 in-stock
   matches for "10uF X7R 0805" of which 26 were kept), so `part_numbers.size` is not a valid resume offset.
   LCSC is queried once with `limit = window`.
   Every fetched part gets `fetchedAt = now` and is enriched with `ParametricExtractor.enrich` before it is cached or
   ranked (Mouser and LCSC deliver almost no parametric attributes).
3. Upsert the newly fetched parts into `cached_parts` (payload = JSON of `Part`, `fetched_at = part.fetchedAt`) and
   the ordered part-number list + `total_results` + `exhausted` + `next_offset` into `cached_searches`
   (`fetched_at = now`; a `PARTIAL` extension keeps the list's original `fetched_at`). Cache read/write failures are
   logged and never fail the search. `bypassCache == true` skips step 1 but still performs step 3 (status `BYPASSED`).
4. Distributor failures never fail the whole search: the distributor entry carries
   `error` (`"rate_limited"`, `"unavailable"`, `"not_configured"`, `"timeout"`, `"bad_response"` = `DistributorException.Kind.code()`).
   The part list is empty, except that parts already in hand are kept and ranked: the cached list when extending a
   `PARTIAL` search fails, pages fetched before a later page failed, and pages fetched before the timeout. Unexpected
   exceptions map to `unavailable`. A requested distributor without a configured client reports `not_configured` with
   cache status `not_applicable`; with no `distributors` given only configured ones are searched.

LCSC parts are read from the SQLite file and are **not** written to `cached_parts`/`cached_searches`
(the SQLite database is the cache). Only Mouser and TME use the Postgres cache.

### 3.3 Ranking

```java
public interface PartRanker {
    /** Scores candidates for one query. Returns a score in [0,1] per candidate key
     *  (distributor + ":" + distributorPartNumber). Throws on failure/timeout; caller falls back. */
    Map<String, Double> rank(ParsedQuery query, List<Part> candidates, Duration budget) throws RankingException;
    String name();   // "laya"
}
```

`RankingService.rank(ParsedQuery, Map<Distributor, List<Part>> fetched, Duration budget)`:

1. `DeterministicRanker.score(ParsedQuery, Part)` for every part (section 3.4). Sort per distributor.
2. Candidate set for Laya: top `kina.ranking.laya.max-candidates` (default 40) across requested
   distributors, shared proportionally (at least 5 per distributor that has results). Candidates
   whose score is already cached in `RankingScoreCache` (in-memory, key = query_key + part key,
   TTL `kina.ranking.score-cache-ttl` default 1h, max 50 000 entries) are not re-sent.
3. Acquire the Laya semaphore (`kina.ranking.laya.max-concurrent-requests`, default 1) with a
   bounded wait; the wait counts inside the budget (`kina.ranking.timeout`, default 18s).
4. Call `PartRanker.rank`. Laya scores are **rank-normalised within the candidate set** (best = 1.0, worst = 0.0,
   ties share a value) before blending, because the raw probabilities cluster near 1.0. Final score =
   `(1 - w) * deterministic + w * layaNormalised`, `w = kina.ranking.laya.weight` (default **0.2**). Parts not sent to
   Laya are ordered after the Laya-ranked ones by deterministic score. Ties: deterministic score, then stock desc, then
   lowest unit price asc. The deterministic ranker is the primary signal by design: see "Measured zero-shot quality" in 3.5.
5. On any failure, timeout, or `kina.ranking.laya.enabled=false`: order by deterministic score and
   report `RankingMode.FALLBACK` with a short `rankingNote` (e.g. `"laya timeout after 18s"`).

Batch search fetches the queries in parallel (at most 4 queries at a time, to respect distributor rate limits),
then ranks each query independently through the same path (sequentially through the semaphore), each with
`min(kina.ranking.timeout, remaining batch budget)`. The ranking phase has `kina.ranking.batch-timeout` (default
60s); queries reached after it expired are ranked with a zero budget (fallback ranking, `ranking_note`
`"batch ranking budget of 60s exhausted"`; Laya scores already in `RankingScoreCache` are still used).

### 3.4 Deterministic ranking and query parsing

`QueryParser.parse(String) -> ParsedQuery` extracts, case-insensitively:

- component family keywords: capacitor/MLCC/cap, resistor/res, inductor, ferrite, diode, Schottky,
  Zener, LED, MOSFET/FET, transistor/BJT/NPN/PNP, LDO/regulator, op amp/opamp, comparator, MCU,
  crystal/oscillator, connector, fuse, TVS/ESD, relay, switch
- value with SI prefix and unit, including RKM notation (`4k7`, `4u7`, `10R`, `2R2`):
  capacitance (`pF nF uF µF mF F`), resistance (`Ω ohm R`, `k`, `M`, `m`), inductance (`nH uH mH H`),
  voltage (`V`, `kV`, `mV`), current (`A`, `mA`, `uA`), power (`W`, `mW`), frequency (`Hz kHz MHz`)
- tolerance (`±5%`, `5%`, `1%`), dielectric (`X7R X5R C0G NP0 Y5V X7S X6S X8R`),
  package (`0201 0402 0603 0805 1206 1210 1812 2010 2220 2512`, `SOT-23 SOT-23-5 SOT-223 SOT-89 SOD-123 SOD-323 SOD-523
  TO-220 TO-252 TO-263 DPAK D2PAK SOIC-8 SOP-8 TSSOP-20 MSOP QFN-32 DFN LQFP-48 TQFP-64 BGA ...` via regex),
  metric case codes (`2012` etc. only when a family keyword says MLCC/resistor), mounting (`SMD SMT THT through-hole`)
- remaining tokens are free text keywords.

`ParsedQuery` holds the original text, normalised key, the extracted constraints (typed, with SI
values normalised to base units as `double`), and the free-text tokens.

`ParametricExtractor.extract(Part) -> Map<String,String>` applies the same recognisers to the
part's description and attribute values, so parts from all three distributors expose comparable
`Capacitance`, `Resistance`, `Inductance`, `Voltage`, `Current`, `Power`, `Tolerance`, `Dielectric`,
`Package`, `Mounting` keys. Distributor attributes (TME parameters, Mouser ProductAttributes) take
precedence over description parsing.

`DeterministicRanker.score(ParsedQuery, Part) -> double in [0,1]` (weights configurable in code constants):

| Signal | Weight | Rule |
|---|---|---|
| primary value (C/R/L) | 0.30 | exact match within 1% -> full; different -> -0.30 penalty; unknown -> 0 |
| package | 0.20 | exact match (treat `0805` == `2012` metric); mismatch -> -0.20 |
| dielectric / technology | 0.15 | exact; `C0G` == `NP0`; mismatch -> -0.15 |
| voltage / current / power rating | 0.10 | part rating >= requested -> full; lower -> -0.10 |
| tolerance | 0.10 | part tolerance <= requested -> full; looser -> -0.10 |
| family keyword present in description/category | 0.05 | |
| lexical: share of free-text tokens found in mpn/description/attributes | 0.10 | |
| tie-break bonuses | up to 0.05 | log10(stock) scaled, has price, JLCPCB "Basic"/"Preferred" library |

Clamp to [0,1].

### 3.5 Laya ranker (`LayaPartRanker`)

Endpoint `POST {kina.ranking.laya.url}/v1/systemone/batch` (default `http://laya-serve:8000`),
optional bearer `kina.ranking.laya.api-key`, `model = kina.ranking.laya.model` (default `multilingual`),
`sort_by_length = true`, connect timeout 2s, read timeout = remaining budget.

One state per candidate, serialised as compact JSON:

```json
{"request": "10uF X7R 0805 MLCC", "candidate": {"manufacturer": "YAGEO", "mpn": "CC0805MKX7R7BB106",
 "description": "...", "package": "0805", "attributes": {"Capacitance": "10uF", "Voltage": "16V", "Dielectric": "X7R", "Tolerance": "20%"}}}
```

Question (identical for every state; a single question halves the CPU time compared with two):

```json
{
  "fits": {"type": "noul", "instructions": "The candidate electronic component satisfies every requirement stated in the request: component type, value, tolerance, voltage or current rating, dielectric or technology, package or footprint and mounting type."}
}
```

Raw score per candidate = `answers.fits.noul`; `RankingService` rank-normalises it (section 3.3). Make the question text
and the state field list configurable in code constants so a fine-tuned checkpoint can be swapped in.
Never send part data anywhere except the configured local Laya URL.

**Measured zero-shot quality (2026-10-05, laya 0.3.27, `multilingual` checkpoint, CPU, 8 threads):** 40 states x 2
questions took 6.5 s (163 ms/state); 40 x 1 question about 3.5 s. On a 10-candidate labelled set for
"10uF X7R 0805 MLCC ceramic capacitor" (3 true matches, 7 distractors) the `fits` probability was 0.996 for matches
and 0.78 on average for distractors, but a 10k resistor scored 0.98 and the X5R variant 0.01; only 2 of the 3 matches
ranked in the top 3. `score`, `choice` and per-attribute `noul` shapes, plain-text states and the `typed-decisions`
checkpoint were all worse or equal. Conclusion: zero-shot Laya is a weak secondary signal, hence the low default
weight, rank normalisation and the deterministic ranker as primary. The labelled set lives in the test fixtures
(`LayaRankerEvaluationTest`, runs only when `KINA_LAYA_TEST_URL` is set) so a fine-tuned checkpoint can be re-evaluated.
A warm Laya container for local experiments can be started with the compose file (`laya-serve` service).

## 4. MCP tools

Server name `kina`, version from the build. Tools (JSON Schema generated from the method
parameters; descriptions are read by the LLM, keep them precise):

| Tool | Parameters | Returns |
|---|---|---|
| `search_parts` | `query` (string, required), `max_results` (int 1..50, default 10, per distributor), `distributors` (array of `LCSC\|TME\|MOUSER`, default all configured), `bypass_cache` (bool, default false: skip cache lookup, still refresh the cache) | `SearchResponse` |
| `search_parts_batch` | `queries` (array of `{query, max_results}`, 1..20), `distributors`, `bypass_cache` | `{ "results": [SearchResponse...] }` |
| `get_part` | `distributor` (case-insensitive), `part_number`, `bypass_cache` | `PartLookupResponse` `{found, distributor, part_number, cache, error, part}`; unknown/out-of-stock parts and distributor failures return `found: false` (with `error` for failures) instead of a tool error |
| `list_distributors` | none | `DistributorStatusResponse`: per distributor `configured`, `available`, `detail` (LCSC: JLCPCB file, part count, source date, download state), `uses_cache`, `cached_parts`, `max_results_per_search`, `jlcpcb{...}` (LCSC); `cache{ttl, parts, fresh_parts, searches, oldest_fetch}`; `ranking{laya_enabled, laya_healthy, model, max_candidates, weight, timeout}`. Never calls the Mouser/TME APIs |
| `ping` | none | `{"status":"ok","version":"<build version>"}` (wiring/health check, already implemented) |

`SearchResponse` JSON (snake_case):

```json
{
  "query": "10uF X7R 0805",
  "parsed": {"family": "capacitor", "capacitance": "10uF", "dielectric": "X7R", "package": "0805", "keywords": []},
  "ranking": "laya",
  "ranking_note": null,
  "distributors": [
    {
      "distributor": "MOUSER",
      "total_results": 113,
      "fetched": 50,
      "returned": 10,
      "cache": "hit",
      "error": null,
      "parts": [
        {"rank": 1, "score": 0.93, "distributor": "MOUSER", "part_number": "603-CC0805MKX77BB106",
         "manufacturer": "YAGEO", "mpn": "CC0805MKX7R7BB106", "description": "...", "category": "...",
         "package": "0805", "stock": 76689, "min_order_qty": 1, "order_multiple": 1,
         "prices": [{"qty": 1, "unit_price": 1.40, "currency": "EUR"}, {"qty": 10, "unit_price": 0.853, "currency": "EUR"}, {"qty": 50, "unit_price": 0.631, "currency": "EUR"}],
         "datasheet_url": "...", "photo_url": "...", "product_url": "...",
         "attributes": {"Capacitance": "10uF", "...": "..."}, "extra": {"lifecycle_status": null, "rohs": "RoHS Compliant"}}
      ]
    }
  ]
}
```

`total_results` is what the distributor reported for the query (in-stock where the API can filter),
`fetched` is how many in-stock parts KINA holds for the query, `returned` is `min(max_results, fetched)`.
`max_results` is clamped to `1..kina.search.max-max-results` (MCP; the REST API rejects out-of-range values with 400).
Tool parameter names are the Java parameter names (`-parameters`), so the tool methods use snake_case parameters.

## 5. HTTP API

| Method & path | Notes |
|---|---|
| `GET /api/v1/parts/search?q=&max_results=&distributors=LCSC,TME&bypass_cache=` | `SearchResponse` |
| `POST /api/v1/parts/search/batch` | body `BatchSearchRequest` (snake_case), returns `{results: [...]}` |
| `GET /api/v1/parts/{distributor}/{*partNumber}?bypass_cache=` | `PartResponse`; the part number is the rest of the path, so TME symbols containing `/` work unencoded; 404 problem when unknown or not in stock |
| `GET /api/v1/distributors` | same as `list_distributors` |
| `GET /actuator/health`, `GET /actuator/info` | public |

Distributor names are case-insensitive everywhere (query, path and JSON body). Errors use RFC 9457
`application/problem+json` (`ApiExceptionHandler`, `@RestControllerAdvice(basePackages = "ro.alacrity.kina.api")`), types
`urn:kina:problem:{validation|unknown-distributor|not-found|distributor-error|internal}`: validation 400 (with
`errors[{field, message}]`), unknown distributor 400, not found 404, lookup failures 503 (`not_configured`,
`unavailable`) / 429 / 504 / 502 with an `error` property, anything else 500 without internals.
`/api/**` and `/mcp/**` require a bearer token
(section 6), except in development mode where missing credentials fall back to the dev admin.

## 6. Security

`kina.security.mode = dev | prod` (env `KINA_MODE`, default `dev`).

**Development mode**: every request without credentials is authenticated as the fake admin
(`users` row issuer `dev`, subject `admin`, display name `Development Admin`, created on startup).
Presented bearer tokens are still validated. No login page.

**Production mode**: web pages require OIDC login through `spring-boot-starter-oauth2-client`,
registration id `oidc`, provider configured only by `OIDC_ISSUER_URI`, `OIDC_CLIENT_ID`,
`OIDC_CLIENT_SECRET` (discovery via `/.well-known/openid-configuration`, scopes `openid profile email`).
No provider-specific code (`spring-boot-starter-security-oauth2-client`). On login `OidcUserSynchronizer` upserts `users(issuer, subject, email, display_name, last_login_at)`.
`/api/**` and `/mcp/**` accept bearer tokens only.

**Access tokens** (`AccessTokenService`): plaintext `kina_` + 43 base64url chars from 32 random
bytes; stored as SHA-256 hex in `access_tokens.token_hash`; `token_prefix` = first 12 chars for display;
validity `kina.tokens.validity` default `30d`; shown to the user exactly once. `last_used_at` is
updated at most once per minute per token. Revocation sets `revoked_at`. The same table and service
issue the OAuth access tokens (`oauth_client_id` set, name `MCP: <client_name>`).

**Bearer filter**: `Authorization: Bearer <token>` -> lookup by hash -> must be unexpired and
unrevoked -> `KinaPrincipal(userId, displayName, tokenId)` with `ROLE_USER`. Failures return 401 with
`WWW-Authenticate: Bearer realm="kina", resource_metadata="<public>/.well-known/oauth-protected-resource"`
(plus `error="invalid_token"` when a token was presented). This header is what makes Claude's MCP
connector discover the authorization server.

**Public origin**: `server.forward-headers-strategy=framework` so `X-Forwarded-Proto/Host/Port/Prefix`
are honoured; `PublicUrlResolver` returns `kina.public-base-url` when set, otherwise the request's
forwarded origin. All metadata, redirect URIs and `resource_metadata` values use it.

**Web UI** (Thymeleaf, CSRF on): `GET /` lists the current user's tokens (name, prefix, created,
expires, last used, revoked), `POST /tokens` creates one (name required) and renders the plaintext
once, `POST /tokens/{id}/revoke`. Keep the pages plain and dependency-free (inline CSS).

## 7. OAuth 2.1 authorization server for MCP clients

All endpoints are relative to the public origin.

| Endpoint | Behaviour |
|---|---|
| `GET /.well-known/oauth-protected-resource` and `/.well-known/oauth-protected-resource/mcp` | `{"resource": "<public>/mcp", "authorization_servers": ["<public>"], "bearer_methods_supported": ["header"], "scopes_supported": ["kina"], "resource_name": "KINA"}` |
| `GET /.well-known/oauth-authorization-server`, `/.well-known/oauth-authorization-server/mcp`, `/.well-known/openid-configuration` | `issuer`, `authorization_endpoint=/oauth/authorize`, `token_endpoint=/oauth/token`, `registration_endpoint=/oauth/register`, `revocation_endpoint=/oauth/revoke`, `response_types_supported=["code"]`, `response_modes_supported=["query"]`, `grant_types_supported=["authorization_code","refresh_token"]`, `code_challenge_methods_supported=["S256"]`, `token_endpoint_auth_methods_supported=["none","client_secret_basic","client_secret_post"]`, `scopes_supported=["kina"]` |
| `POST /oauth/register` (RFC 7591, anonymous) | Accepts `redirect_uris` (required, absolute, no fragment; `https`, `http://localhost`/`127.0.0.1` any port, or custom schemes), `client_name`, `token_endpoint_auth_method` (default `none`), `grant_types` (default `["authorization_code","refresh_token"]`), `response_types` (`["code"]`), `scope`. Returns 201 with `client_id`, `client_secret` (only when auth method is not `none`), `client_id_issued_at`, `client_secret_expires_at: 0` and the echoed metadata. Clients are stored in `oauth_clients`. |
| `GET /oauth/authorize` | Requires `response_type=code`, registered `client_id`, exact `redirect_uri` match, `code_challenge` + `code_challenge_method=S256` (PKCE mandatory), optional `scope`, `state`, `resource`. Requires an authenticated user (dev: automatic admin; prod: OIDC login, then return). Renders a consent page (client name, user, Approve/Deny). Approve -> authorization code (32 random bytes base64url, SHA-256 stored, 10 min validity, bound to client, redirect URI, user, challenge, scope, resource) -> 302 `redirect_uri?code=&state=`. Deny -> `error=access_denied`. Invalid client or redirect URI -> error page, never a redirect. |
| `POST /oauth/token` (form-encoded) | `grant_type=authorization_code`: verify client (secret if registered with one; public clients need none), code unused and unexpired, `redirect_uri` equal, PKCE `S256(code_verifier) == code_challenge`; mark code used; issue access token (30 days, via `AccessTokenService`) and refresh token (`kina.oauth.refresh-token-validity` default `90d`, `oauth_refresh_tokens`). Response `{"access_token","token_type":"Bearer","expires_in","refresh_token","scope"}`. `grant_type=refresh_token`: rotate (revoke old refresh token and its access token, issue new pair). Errors follow RFC 6749 (`invalid_request`, `invalid_client` (401), `invalid_grant`, `unsupported_grant_type`). |
| `POST /oauth/revoke` (RFC 7009) | Revokes an access or refresh token belonging to the authenticated client; always 200. |

`resource` (RFC 8707) is stored and echoed; a value outside the public origin is logged, not rejected.

## 8. Database schema (Flyway `V1__init.sql`)

```sql
CREATE TABLE users (
  id            UUID PRIMARY KEY,
  issuer        TEXT NOT NULL,
  subject       TEXT NOT NULL,
  email         TEXT,
  display_name  TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_login_at TIMESTAMPTZ,
  UNIQUE (issuer, subject)
);

CREATE TABLE access_tokens (
  id              UUID PRIMARY KEY,
  user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name            TEXT NOT NULL,
  token_hash      TEXT NOT NULL UNIQUE,
  token_prefix    TEXT NOT NULL,
  scope           TEXT,
  oauth_client_id TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at      TIMESTAMPTZ NOT NULL,
  revoked_at      TIMESTAMPTZ,
  last_used_at    TIMESTAMPTZ
);
CREATE INDEX access_tokens_user_idx ON access_tokens (user_id);

CREATE TABLE oauth_clients (
  client_id                  TEXT PRIMARY KEY,
  client_secret_hash         TEXT,
  client_name                TEXT,
  redirect_uris              JSONB NOT NULL,
  grant_types                JSONB NOT NULL,
  response_types             JSONB NOT NULL,
  token_endpoint_auth_method TEXT NOT NULL,
  scope                      TEXT,
  metadata                   JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE oauth_authorization_codes (
  code_hash             TEXT PRIMARY KEY,
  client_id             TEXT NOT NULL REFERENCES oauth_clients(client_id) ON DELETE CASCADE,
  user_id               UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  redirect_uri          TEXT NOT NULL,
  scope                 TEXT,
  resource              TEXT,
  code_challenge        TEXT NOT NULL,
  code_challenge_method TEXT NOT NULL,
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at            TIMESTAMPTZ NOT NULL,
  used_at               TIMESTAMPTZ
);

CREATE TABLE oauth_refresh_tokens (
  token_hash      TEXT PRIMARY KEY,
  client_id       TEXT NOT NULL REFERENCES oauth_clients(client_id) ON DELETE CASCADE,
  user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  access_token_id UUID REFERENCES access_tokens(id) ON DELETE SET NULL,
  scope           TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at      TIMESTAMPTZ NOT NULL,
  revoked_at      TIMESTAMPTZ
);

CREATE TABLE cached_parts (
  distributor TEXT NOT NULL,
  part_number TEXT NOT NULL,
  payload     JSONB NOT NULL,
  fetched_at  TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (distributor, part_number)
);
CREATE INDEX cached_parts_fetched_idx ON cached_parts (fetched_at);

CREATE TABLE cached_searches (
  distributor   TEXT NOT NULL,
  query_key     TEXT NOT NULL,
  total_results INTEGER,
  part_numbers  JSONB NOT NULL,
  exhausted     BOOLEAN NOT NULL DEFAULT false,
  fetched_at    TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (distributor, query_key)
);
-- V2__cached_search_next_offset.sql: raw distributor offset where the next page starts (NULL = unknown)
ALTER TABLE cached_searches ADD COLUMN next_offset INTEGER;

CREATE TABLE jlcpcb_database (
  id            INTEGER PRIMARY KEY CHECK (id = 1),
  library       TEXT NOT NULL,
  file_path     TEXT NOT NULL,
  downloaded_at TIMESTAMPTZ NOT NULL,
  size_bytes    BIGINT,
  part_count    BIGINT,
  source_date   TEXT
);
```

## 9. Distributor details (verified against the live APIs on 2026-10-05)

### 9.1 Mouser (`distributor/mouser`)

- Config: `kina.distributors.mouser.api-key` (`MOUSER_API_KEY`), `base-url=https://api.mouser.com/api/v1`,
  `max-results-per-search=50`, `max-pages-per-search=1` (daily quota is 1 000 calls, 30/min).
- Keyword search: `POST {base}/search/keyword?apiKey=...` JSON
  `{"SearchByKeywordRequest":{"keyword":q,"records":n (<=50),"startingRecord":offset + 1,"searchOptions":"InStock","searchWithYourSignUpLanguage":"false"}}`
  (`startingRecord` is **1-based**, verified live: 1 returns results #1.., 3 returns #3..).
  Response `{"Errors":[...],"SearchResults":{"NumberOfResult":113,"Parts":[...]}}`.
- Part lookup: `POST {base}/search/partnumber` with `{"SearchByPartRequest":{"mouserPartNumber":pn,"partSearchOptions":"Exact"}}`
  (verify the option value against the API; fall back to filtering the result by `MouserPartNumber`).
- Part fields observed: `Availability` ("76689 In Stock"), `AvailabilityInStock` ("76689"), `AvailabilityOnOrder` (ignored),
  `FactoryStock` (ignored), `DataSheetUrl`, `Description`, `ImagePath`, `Category`, `LeadTime`, `LifecycleStatus`,
  `Manufacturer`, `ManufacturerPartNumber`, `Min`, `Mult`, `MouserPartNumber`, `ProductAttributes[{AttributeName,AttributeValue}]`,
  `PriceBreaks[{Quantity, Price:"1,40 €", Currency:"EUR"}]`, `ProductDetailUrl`, `ROHSStatus`, `SuggestedReplacement`,
  `SalesMaximumOrderQty`, `ProductCompliance`, `TradeCompliance`.
- Stock = integer parsed from `AvailabilityInStock` (digits only); drop the part when <= 0.
- Prices are locale formatted (`"1,40 €"`, `"0,853 €"`, `"$0.10"`): strip everything except digits, `,` and `.`;
  when both separators appear the last one is the decimal separator; a lone `,` is a decimal separator
  when followed by 1-3 digits at the end. Currency from `Currency`.
- Attributes: `ProductAttributes` by name (repeated names joined with `", "`). Extra: `lifecycle_status`, `rohs`,
  `lead_time`, `factory_stock`, `category`, `suggested_replacement`, `reeling`, `sales_maximum_order_qty`, `availability_on_order`.
- Errors: HTTP 429 or `Errors[].Code == "TooManyRequests"` -> `RATE_LIMITED`; other non-empty `Errors` -> `BAD_RESPONSE`.

### 9.2 TME API v2 (`distributor/tme`)

The account's token only works with **API v2** (OAuth2 client credentials); the legacy HMAC API answers
`E_AUTHORIZATION_FAILED`. The OpenAPI document is vendored at `docs/vendor/tme-api-v2-openapi.json`.

- Config: `kina.distributors.tme.token` (`TME_TOKEN`), `secret` (`TME_APPLICATION_SECRET`), `country` (`COUNTRY`, default `RO`),
  `currency` (default `EUR`), `language` (default `en`), `base-url=https://api.tme.eu`, `max-results-per-search=60`, `max-pages-per-search=3`.
- Token: `POST {base}/auth/token`, header `Authorization: Basic base64(token:secret)`, body `grant_type=client_credentials`
  -> `{"access_token","token_type":"Bearer","expires_in":300,"refresh_token"}`. `TmeTokenManager` caches the token and
  requests a new one when fewer than 30 s remain (ignore the refresh token; client credentials are cheap).
- Search: `GET {base}/products/search?phrase=<q>&scope[]=products&scope[]=counters&filter[in_stock]=true&country=RO&limit=<n>&page=<p>`
  with `Accept-Language: en`. Verified live: `limit` max 100 (101 -> 400), `phrase` must be 2-40 characters (longer
  queries are shortened at a word boundary), `filter[in_stock]=true` makes `counters.count` an in-stock total. Auth
  failures are HTTP 400 `E_AUTH_TOKEN_IS_INVALID` / 403 `E_AUTH_TOKEN_EXPIRED` (not 401): drop the token, retry once. Response
  `data.products.elements[]` with `symbol`, `product_status[]` (e.g. `HARDLY_AVAILABLE`), `category{id,name}`,
  `manufacturer_symbols[]`, `manufacturer{id,name}`, `description`, `multiples`, `minimal_amount`, `unit`, `packing`,
  `assets.primary_photo{prime,thumbnail,high_resolution}` (protocol-relative URLs: prefix `https:`), and
  `data.counters{pages,count,page}`.
- Prices and stock: `GET {base}/products/data?symbols[]=...&scope[]=prices&scope[]=stock&country=RO&currency=EUR`
  (max 50 symbols). Elements: `stock_quantity`, `prices.elements[{amount,price,special}]`, `prices.currency`, `prices.type` ("NET"),
  `prices.tax{type,rate}`. Stock = `stock_quantity`; drop when <= 0.
- Parameters: `GET {base}/products/parameters?symbols[]=...&country=RO` (batch of up to 50) ->
  `data.elements[{symbol, parameters.elements[{id,name,values[{id,value}]}]}]`; map name -> values joined with `", "`.
- `productUrl = https://www.tme.eu/en/details/<symbol>/`, `datasheetUrl` = first `/products/files` document with
  `type == "DTE"` (prefer PDF; one call per page, <= 50 symbols), `photoUrl = https:` + `assets.primary_photo.prime`.
- Extra: `product_status`, `category_id`, `manufacturer_id`, `unit`, `packing`, `price_type`, `tax_rate`.
- Products whose `product_status` contains one of `kina.distributors.tme.excluded-statuses` (default
  `CANNOT_BE_ORDERED`, `ONLY_FOR_SPECIAL_ORDER`, `EXTERNAL_WAREHOUSE`, compared case-insensitively) do not ship now
  and are dropped by `TmePartMapper`. `product_status` stays in `extra`.
- Errors: `{"code":"E_INPUT_PARAMS_VALIDATION_ERROR",...}` -> `BAD_RESPONSE`; 401 -> refresh token once and retry; 429 -> `RATE_LIMITED`.

### 9.3 LCSC via the JLCPCB parts database (`distributor/lcsc`)

- Config: `kina.jlcpcb.data-dir` (default `./data/jlcpcb`, in Docker `/data/jlcpcb`), `library` (default `parts-fts5.db`;
  alternatives `current-parts-fts5.db`, `basic-parts-fts5.db`), `base-url=https://bouni.github.io/kicad-jlcpcb-tools/`,
  `refresh-after=5d`, `check-interval=1h`, `max-results-per-search=200`.
- Download (same technique as kicad-jlcpcb-tools): read `<base>/chunk_num_fts5.txt` (for `parts-fts5.db`; the sentinel is
  `chunk_num_<name-without-.db>.txt` for the other libraries, e.g. `chunk_num_current_parts_fts5.txt`) -> integer N;
  download `<base>/<library>.zip.001` .. `.NNN` (80 MB chunks, zero-padded to 3 digits) into a temp directory, concatenate
  them into `<library>.zip`, extract the single entry, verify it opens as SQLite and `SELECT count(*) FROM parts` works,
  then atomically move it to `<data-dir>/<library>`; record `jlcpcb_database(id=1, downloaded_at=now, ...)`; delete temp files.
- Refresh policy: at startup, download in the background when the file is missing or `downloaded_at` is older than
  `refresh-after`. A scheduled task (`check-interval`) re-checks. While no database is available the LCSC client reports
  `UNAVAILABLE` ("JLCPCB database not downloaded yet"); searches on other distributors proceed. Swapping the file takes a
  write lock; queries take read locks; SQLite is opened read-only (`jdbc:sqlite:<path>?mode=ro`, `open_mode=1`).
- Schema (SQLite): `parts` is an **FTS5 virtual table with the trigram tokenizer** and columns
  `"LCSC Part", "First Category", "Second Category", "MFR.Part", "Package", "Solder Joint", "Manufacturer", "Library Type",
  "Description", "Datasheet", "Price", "Stock"` (`Solder Joint`, `Datasheet`, `Price`, `Stock` are unindexed). Also tables
  `categories("First Category","Second Category")`, `meta(filename,size,partcount,date,last_update)`, `mapping`.
- Query: tokens of 3+ characters go into `parts MATCH '"tok1" AND "tok2" ...'` (escape embedded double quotes by doubling);
  shorter tokens (`1k`, `5%`) become `"Description" LIKE '%tok%'` clauses; always `CAST("Stock" AS INTEGER) > 0`;
  `ORDER BY rank, CAST("Stock" AS INTEGER) DESC LIMIT <window>`. `totalResults` = `COUNT(*)` of the same predicate.
  Part lookup: `WHERE "LCSC Part" = ?`.
- Mapping: `distributorPartNumber = "LCSC Part"`, `manufacturer`, `manufacturerPartNumber = "MFR.Part"`, `description`,
  `category = "First Category" + " / " + "Second Category"`, `packageName = "Package"`, `stock = int("Stock")`,
  `prices` parsed from `"Price"` strings like `1-199:0.0068,200-999:0.0056,1000-:0.0043` (`qty = lower bound`, USD),
  `datasheetUrl = "Datasheet"`, `photoUrl = null`, `productUrl = https://www.lcsc.com/product-detail/<LCSC>.html`,
  `attributes` via `ParametricExtractor` on the description, `extra = {library_type, solder_joints, second_category, jlcpcb_url: https://jlcpcb.com/partdetail/<LCSC>}`.
  `minimumOrderQuantity`/`orderMultiple` are null (not in the database).

## 10. Configuration (`application.yml`, environment-driven)

```yaml
spring:
  application.name: kina
  threads.virtual.enabled: true
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/kina}
    username: ${SPRING_DATASOURCE_USERNAME:kina}
    password: ${SPRING_DATASOURCE_PASSWORD:kina}
  flyway.enabled: true
  ai.mcp.server:                 # property names verified against spring-ai-autoconfigure-mcp-server-common 2.0.1
    name: kina
    version: "@project.version@"  # Maven resource filtering
    type: SYNC
    protocol: STATELESS           # SSE | STREAMABLE | STATELESS
    streamable-http.mcp-endpoint: /mcp
server:
  port: ${PORT:8080}
  forward-headers-strategy: framework
management.endpoints.web.exposure.include: health,info
kina:
  public-base-url: ${KINA_PUBLIC_BASE_URL:}
  security.mode: ${KINA_MODE:dev}
  tokens.validity: 30d
  oauth.refresh-token-validity: 90d
  cache.ttl: 5d
  search:
    candidate-window: 40
    default-max-results: 10
    max-max-results: 50
    distributor-timeout: 12s
  ranking:
    timeout: 18s
    batch-timeout: 60s
    score-cache-ttl: 1h
    laya:
      enabled: ${KINA_LAYA_ENABLED:true}
      url: ${LAYA_URL:http://localhost:8000}
      api-key: ${LAYA_API_KEY:}
      model: ${KINA_LAYA_MODEL:multilingual}
      max-candidates: 40
      max-concurrent-requests: ${KINA_LAYA_MAX_CONCURRENT:1}
      weight: 0.2
  distributors:
    mouser: { api-key: "${MOUSER_API_KEY:}", base-url: https://api.mouser.com/api/v1, max-results-per-search: 50, max-pages-per-search: 1 }
    tme:    { token: "${TME_TOKEN:}", secret: "${TME_APPLICATION_SECRET:}", country: "${COUNTRY:RO}", currency: EUR, language: en, base-url: https://api.tme.eu, max-results-per-search: 60, max-pages-per-search: 3,
              excluded-statuses: [CANNOT_BE_ORDERED, ONLY_FOR_SPECIAL_ORDER, EXTERNAL_WAREHOUSE] }
  jlcpcb: { data-dir: "${KINA_JLCPCB_DATA_DIR:./data/jlcpcb}", library: "${KINA_JLCPCB_LIBRARY:parts-fts5.db}", base-url: https://bouni.github.io/kicad-jlcpcb-tools/, refresh-after: 5d, check-interval: 1h, max-results-per-search: 200,
            auto-download: true }   # false in src/test/resources/config/application.yml
```

Production OIDC (only read when `kina.security.mode=prod`):
`spring.security.oauth2.client.provider.oidc.issuer-uri=${OIDC_ISSUER_URI}`,
`spring.security.oauth2.client.registration.oidc.{client-id,client-secret}`, `scope=openid,profile,email`,
`redirect-uri={baseUrl}/login/oauth2/code/oidc`. Use a profile or conditional configuration so a missing issuer does
not break development mode startup.

## 11. Docker

- `Dockerfile`: stage 1 `maven:3.9-eclipse-temurin-21` (copy `pom.xml`, `./mvnw`, `.mvn`, run `dependency:go-offline`,
  then copy `src`, `package -DskipTests`); stage 2 `eclipse-temurin:21-jre`, non-root user, `/data` volume,
  `HEALTHCHECK` on `/actuator/health`, `ENTRYPOINT ["java","-jar","/app/kina.jar"]`.
- `compose.yaml` services:
  - `kina`: build `.`, ports `8080:8080`, `env_file: .env`, environment for datasource/Laya/JLCPCB dir, volume `kina-data:/data`,
    `depends_on: postgres (healthy)`; Laya is not a hard dependency (fallback ranking).
  - `postgres`: `postgres:17-alpine`, `POSTGRES_DB/USER/PASSWORD=kina`, volume `pgdata`, healthcheck `pg_isready`.
  - `laya-serve`: build from the Laya git repository (`context: https://github.com/NandhaKishorM/laya.git#v0.3.27`,
    args `TORCH_INDEX=cpu`), `command: ["laya-serve"]`, environment `LAYA_DEVICE=cpu`, `LAYA_MODELS=multilingual`,
    `LAYA_DEFAULT_MODEL=multilingual`, `LAYA_PRELOAD=1`, `LAYA_THREADS=${LAYA_THREADS:-4}`, `OMP_NUM_THREADS=${LAYA_THREADS:-4}`,
    `LAYA_MAX_CONCURRENT=${KINA_LAYA_MAX_CONCURRENT:-1}`, volume `laya-models:/home/laya/.cache/huggingface`,
    healthcheck `GET /health`; no host port by default (internal only).
- `compose.cuda.yaml`: overlay for `laya-serve` with `TORCH_INDEX=cu128`, `LAYA_DEVICE=cuda` and the NVIDIA device reservation.
- `.env.example` documenting every variable; `.env` is git-ignored.

## 12. Quality bar

- `./mvnw -q verify` must pass: unit tests for the query parser, parametric extractor, deterministic ranker, Mouser price
  parsing and mapping (JSON fixtures), TME mapping and token refresh (`MockRestServiceServer`), JLCPCB price parsing and
  SQLite search (build a tiny FTS5 database in the test), Laya request/response mapping and fallback, PKCE, token hashing,
  OAuth metadata/register/authorize/token flow (MockMvc), bearer filter; Testcontainers-backed repository tests.
- No secrets in code, logs or test fixtures. Never log bearer tokens or API keys.
- Every external call has a timeout. Every distributor error is isolated per distributor.
