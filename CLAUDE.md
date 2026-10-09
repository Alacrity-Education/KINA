# CLAUDE.md

## What KINA is

An MCP server and REST API (Spring Boot 4.1.1, Java 21) that lets Claude search electronic components at LCSC (via the JLCPCB parts database), TME (API v2) and Mouser. Only ships-now stock is returned, except a part requested explicitly by its part number, which is returned even when it is listed without stock (stock 0). TME and Mouser results are cached in PostgreSQL: component metadata is kept (by default forever, `kina.cache.metadata-retention.<DISTRIBUTOR>`), stock and prices expire after 3 days (`kina.cache.ttl`; 1 hour for searches with no parts) and are refreshed when older than 24 hours, else the part is returned with `stale: true`. A search that finds nothing is retried once with a shorter core phrase (`fallback_query`). The cache is also searched by extracted fields (the field index `part_index`, `kina.search.field-index.mode`: `off`, `shadow`, `augment`, `on`; in `on` the cache answers first and the distributor is asked only for what it cannot show), and KINA counts its own requests to Mouser and TME against configurable quotas. Parts are ranked by a deterministic parametric ranker blended 50/50 by rank with an in-process cross-encoder (`cross-encoder/ms-marco-MiniLM-L6-v2` on ONNX Runtime, CPU), with the deterministic order as fallback. KINA is also an OAuth 2.1 authorization server for Claude's remote MCP connector. Login is delegated to the organisation's OIDC provider (Authentik in the reference setup) and access requires membership of configured groups, re-checked at refresh and on bearer requests.

## Where things are documented

- `docs/DESIGN.md` is the binding design: contracts, search and ranking semantics, security, OAuth, schema, distributor details. If you change a shared type or behaviour, change DESIGN.md in the same commit. When code and DESIGN.md disagree, the code is what runs; fix the document.
- `docs/DEVELOPMENT.md`: toolchain, build, local run, Jackson 3 gotchas.
- `docs/API.md`, `docs/OPERATIONS.md`, `README.md`: user-facing docs. Update them when endpoints, variables or defaults change.
- Connector vocabulary (types, gender, positions, rows, pitch, orientation, and the per-distributor phrases) lives in `ConnectorRecognizer` and `DistributorPhraser`. Add new connector types there and in the labelled tests, and document the wording in `docs/DESIGN.md` 3.2 and 3.4.
- USB vocabulary (types, standards and speed classes, pin configurations, features, mounting styles) lives in `UsbVocabulary`. Keep the pin-count normalisation rule (17/18 to 16, 25/26 to 24, 7/8 to 6, 14 stays 14; `Positions` as reported, ranking on `PinConfiguration`) and its tests.
- Form-factor classes (`chip`, `through_hole`, `chassis`, `power_package`, `power_smd`; a hard constraint) live in `FormFactor`; resistor series codes that imply a power rating (Arcol HS, TE THS, Vishay RH, Ohmite TEH, Bourns PWR, Caddock MP9xx, LPS) live in `ResistorSeries`. Add new series there with a labelled test; part numbers only, never datasheets.
- The hard/relaxable policy and the matching rules are declared on `ConstraintKind` (`@Relax`: strategy, ladder order, cost, families; `@Match`: mode, tolerance, weight, group, scope, score and report order; the wanted and actual accessors and custom comparators on the constants). Change them there, not in the ranker or `ConstraintPolicy`. The per-family table in `docs/DESIGN.md` 3.4 is checked against the declarations (`ConstraintTableDocumentationTest`); `ConstraintGoldenTest` holds the scores and reports of 0.5.0 (recapture with `-Dkina.golden.write=true` only for an intended behaviour change).
- Attribute extraction is declared on `PartAttribute` (`@Source`: names, distributors, families, traits, precedence, logic; `@Unit`: symbols, base, prefixes, display). Add a new distributor attribute spelling to the constant's `@Source` names, not to the extractor; custom extraction logic is a small named class in `domain.extract`, using only domain types and `ExtractionContext` (keep that interface narrow). The source table in `docs/DESIGN.md` 3.4 is checked against the declarations (`ExtractionTableDocumentationTest`); `ExtractionGoldenTest` holds the extraction of 0.6.0 (same recapture rule as `ConstraintGoldenTest`).
- The field index (`part_index`, `distributor_phrases`, the LCSC sidecar) is described in `docs/DESIGN.md` 3.2 (field-first flow), 3.8, 8 and 9.3, with its validation in `docs/research/field-search-validation-2026-10-09.md` and the procedure in `docs/DEVELOPMENT.md`. Quota tracking is `docs/DESIGN.md` 3.7 "API quota". The rollout is in `docs/OPERATIONS.md` (Field-based search).
- `.env.example` documents every environment variable. Keep it in sync with `application.yml`.
- Group authorisation and the Authentik end-to-end test (`scripts/e2e/authentik/`, needs Docker, about 70 s) are described in `docs/DESIGN.md` 6, 7.1 to 7.3 and `docs/DEVELOPMENT.md`; the admin guide is `docs/OPERATIONS.md` (Authentik setup).

## Build and test

```bash
./mvnw -q verify                 # compile + all tests; needs Docker (Testcontainers, PostgreSQL 17)
./mvnw -q -DskipTests package    # target/kina.jar
./mvnw test -Dtest=PartsApiTest  # one test class
docker compose up -d --build     # kina + postgres (the ranking model is baked into the image at build time; local runs download it on first start)
```

Always use `./mvnw`. The build must stay free of compiler warnings (`-Xlint:all -Werror`: any compiler warning fails the build).

End-to-end checks against a running compose stack: `python3 scripts/e2e/kina_e2e.py` and `scripts/e2e/prod_smoke.sh` (see `docs/DEVELOPMENT.md`).

Optional live tests: `KINA_CROSS_ENCODER_TEST_MODEL_DIR=<model dir> ./mvnw test -Dtest=CrossEncoderEvaluationTest` (blended NDCG@10 must be at least 0.90 on `docs/research/data/ranking-eval.jsonl`); the Mouser live test needs `KINA_MOUSER_LIVE_TEST=true` and `MOUSER_API_KEY`.

## Module map (`src/main/java/ro/alacrity/kina/`)

| Package | Role |
|---|---|
| `config` | `KinaProperties`, the single `@ConfigurationProperties("kina")` record tree. Extend it; do not bind `kina` twice. |
| `domain` | Records and enums shared everywhere: `Part`, `PriceBreak`, `ParsedQuery`, requests, response DTOs (snake_case wire format). `ConstraintKind` with the `@Relax` and `@Match` annotations (`RelaxStrategy`, `MatchMode`), `PolicyFamily`, `PartFeatures`, `MatchContext`: the constraint model. `ComponentFamily`: every parser family with its parent, policy family and traits (add a family there and its words in `Recognizers`). `PartAttribute` with the `@Source` and `@Unit` annotations, `AttributeLogic`, `ValueDisplay`, `PartSource`, `ExtractionContext`: the attribute extraction model; `domain.extract`: the custom logic classes. |
| `distributor` | `DistributorClient` contract, `DistributorRegistry`, `DistributorException`, `ApiQuotaTracker` (sliding-window counts of requests to Mouser and TME); subpackages `lcsc` (JLCPCB download, read-only connection pool, SQLite FTS5 search, `LcscFieldSearch` and the typed-table sidecar `parts-fts5.index.db`), `tme`, `mouser`. |
| `cache` | `PartCacheRepository`, `SearchCacheRepository`, `PhraseJournalRepository` (the phrase journal `distributor_phrases`), `CacheStatus`, purge job. |
| `search/field` | The field index: `FieldQueryBuilder` and `FieldQuery` (the ordered constraint groups and relaxation steps), `FieldSql` with `PostgresFieldSql` and `SqliteFieldSql` (one set of predicates, two dialects), `PartIndexRepository` (rows are built by `search.PartIndexRows`), `PartIndexReindexer` (background re-index), `FieldSearchShadow`, `PhraseJournalBackfill`, `FieldIndexStatus`. |
| `search` | `FieldFirstSearch` (the `on` flow: field query, journal, relaxation, distributor calls) and `QueryParser`, `ParametricExtractor`, `DeterministicRanker`, `ConstraintPolicy` (reads the declarations, config override, check, hints), `SearchMatchContext` (the vocabularies behind `MatchContext`), `SearchExtractionContext` (the recognisers behind `ExtractionContext`), `RankingService` (blend and fallback), `PartSearchService` (the five-stage sequence: prepare, retrieve, rank, refresh stock, assemble), `ParallelRetrieval`, `DistributorRetriever` (`LcscRetriever`, `CachedDistributorRetriever`), `PageCollector`, `StockRefresher`, `ResponseAssembler`, `CorePhrases`, `PartLookupService`, `DistributorStatusService`. |
| `search/ce` | `CrossEncoderPartRanker` (the `PartRanker`), `CrossEncoderModel` (download, load, hourly retry), `ModelDownloader`, `ModelLayout`, `BertTokenizer`, `OnnxScoringBackend`. |
| `mcp` | `KinaMcpTools`: the `@McpTool` methods (`search_parts`, `search_parts_batch`, `get_part`, `list_distributors`, `ping`). |
| `api` | `PartsController`, `DistributorsController` under `/api/v1`; `ApiExceptionHandler` (problem+json). |
| `security` | `PartPathFirewall` (percent-encoded `%`, backslash and slash in the part-number path of `/api/v1/parts/{distributor}/**` only). Two filter chains in `SecurityConfig` (machine: bearer tokens; web: session/OIDC), dev-mode admin, `AccessTokenService`, OIDC login. `OidcAccessPolicy` (group and e-mail-domain rules, claim lookup), `MembershipVerifier` (re-checks at the provider, grace and fallback rules), `UpstreamTokenCipher` (AES-GCM for the provider's refresh tokens), `OidcUserSynchronizer` (login gate), `BearerTokenAuthenticationFilter`. |
| `oauth` | Metadata, dynamic registration, authorize, token, revoke endpoints, PKCE. `ClientMetadataDocumentResolver` (https `client_id` documents on trusted hosts), `RegistrationRateLimiter`, `OAuthClientMaintenance` (daily cleanup of unused clients). |
| `web` | Thymeleaf tabs: `SearchPageController` (`/`, runs `PartSearchService.search`, view models in `SearchView`), `McpPageController` (`/connect`, connect instructions and tokens; `kina.tokens.ui-enabled=false` turns static tokens off), `StatusPageController` (`/status`), `LoginErrorController` (`/login-denied`), `PublicUrlResolver` (public origin; all emitted URLs go through it). |
| `metrics` | `Metric` (every meter's name, type, help and tag keys; the DESIGN.md 3.7 table is checked against it), `KinaMetrics` facade and `MetricsStore` (in-memory counters, saved to `metrics_counters` every 30 s and restored on startup), gauges from repositories, `QuotaGauges` (the `kina_distributor_quota_*` gauges, computed from `ApiQuotaTracker` on every scrape), `/actuator/prometheus` on the management port (`KINA_METRICS_PORT`, no authentication), `/api/v1/metrics/summary`. |

SQL migrations: `src/main/resources/db/migration` (Flyway, V1 to V15; V14 `part_index`, V15 `distributor_phrases`). Templates: `src/main/resources/templates`.

## Conventions

- Jackson 3: databind is `tools.jackson.*`; annotations stay `com.fasterxml.jackson.annotation.*`. Exceptions are unchecked. DTOs use explicit `@JsonProperty` snake_case names; do not rely on naming strategies.
- Spring beans use `@Autowired` field injection (no constructors, collaborators not final; constructor logic goes in `@PostConstruct`). Unit tests build beans with `TestWiring` (test sources), never with reflection of their own. See `docs/DEVELOPMENT.md`.
- Domain and DTO types are Java records. Lombok is used where it shortens code: `@Slf4j`, `@RequiredArgsConstructor`, `@Builder(toBuilder = true)` on wide records, `@With`, `@Getter`, `@UtilityClass` (the one experimental exception). Not allowed: `@Data` on identity or security types, `@SneakyThrows`, Lombok `val`/`var`, `@Synchronized`, `@Delegate`. `KinaProperties` stays plain records (Spring binding). Lombok 1.18.48+ is required on JDK 24+; see `docs/DEVELOPMENT.md`.
- Persistence is `JdbcClient` and plain SQL. No JPA. Cached parts are JSONB (`Part` as camelCase JSON).
- Group checks must stay at all three points: login (`OidcUserSynchronizer` + `OidcAccessPolicy`), every refresh grant (`MembershipVerifier.checkRefreshGrant`, synchronous) and bearer requests (blocked users get 401; static tokens re-check in the background, never blocking the request). A refusal on refresh is `invalid_grant`. Do not add a path that issues or renews a token without them.
- Never log e-mail addresses (or names) of refused users. Log the provider subject or the user id only.
- Never log or return the encryption key, upstream refresh tokens or ID tokens. A malformed `kina.security.oidc.token-encryption-key` must fail startup.
- A client_id that is an `https` URL is fetched only for hosts in `kina.oauth.trusted-client-metadata-hosts`; keep the 5 s timeout, 1 MB cap and no redirects. Unknown hosts and invalid documents never lead to a redirect.
- Every call KINA makes to the OIDC provider uses the 5 s timeouts of `OidcHttp` and HTTP/1.1.
- Rate limits are retried, not failed: `RateLimitRetry` waits (`Retry-After`, else 2, 4, 8, 16, 30 s with jitter) on 429, on 502/503/504 only with `Retry-After`, and on Mouser's `TooManyRequests`, with a shared per-distributor cool-down. Every wait must stay inside the per-request `Deadline` (`kina.search.max-request-duration`, 2 minutes); never sleep past it. See DESIGN.md 3.6.
- Every external call has an explicit connect and read timeout. A distributor error is isolated to that distributor and becomes the `error` code in its result entry.
- Field index (DESIGN.md 3.8): each constraint rule is declared with `@Indexed` on the `ConstraintKind` (and where a numeric attribute is stored, on the `PartAttribute`), not in the query builder; `IndexTableDocumentationTest` checks the DESIGN.md 3.8 table against the declarations. The SQL is always looser than the Java check (it is a recall filter, `PageCollector.Check` decides); `FieldQuerySupersetTest` guards that, so run it after any change to a rule, a comparator or an extractor. Ratings never filter in SQL (they only order candidates). Never index stock or `in_stock` changes beyond the copied `in_stock` flag (stock updates must stay HOT). Values are stored rounded to 9 significant digits. Bump `ParametricExtractor.INDEX_VERSION` when extraction changes (`IndexVersionTest`). A migration never drops or rewrites `cached_parts` or `cached_searches` rows (`CachePreservationMigrationTest`).
- API quota: `RateLimitRetry` is the only place that counts a request to Mouser or TME (`ApiQuotaTracker.record`, once per attempt, retries included). Quota is never persisted and never enforced; it is in memory, in sliding windows, and only shown (Status tab, `list_distributors`, `kina_distributor_quota_*`).
- LCSC stays SQLite. The typed table lives in the sidecar `parts-fts5.index.db`, never inside the downloaded main file (opened read-only, `immutable`). The sidecar is renamed after the main file and used only when its `index_version` and `source` match; otherwise LCSC takes the FTS path.
- Stock rule: only ships-now stock. Never construct, cache, rank or return a `Part` with stock of 0 or less, with one exception: a part requested explicitly by its part number (a part-number token in the search query, or `get_part`) that the distributor lists without stock is built by the distributor lookup with stock 0, returned with `availability.status: "out_of_stock"`, ranked after every in-stock part, and cached with `in_stock = false`. It is never served to a keyword or parametric search (DESIGN.md 2).
- Prices are stored complete and trimmed to the 3 smallest brackets only when building responses.
- Cached parts store the distributor's attributes only (`Part.asStored()`); `ParametricExtractor.enrich` derives the rest on every read. Never write a derived attribute to `cached_parts`.
- MCP tool parameters are snake_case Java parameter names (compiled with `-parameters`).
- LLM-facing descriptions in `KinaMcpTools` are part of the product; keep them precise.
- The ranking model runs in-process. Never send part data to a third-party inference service.
- Never block startup on the model download: it runs in the background after `ApplicationReadyEvent`, and a failure is logged once and retried hourly.
- Keep the deterministic fallback working. Whenever the model cannot score, return the deterministic order with `ranking: "fallback"` and a `ranking_note`. Search must never fail because of the model.
- Tests: JUnit 5, Mockito, `MockRestServiceServer` and recorded JSON fixtures for distributors, Testcontainers PostgreSQL for DB tests (`@Import(TestcontainersConfiguration.class)`), `RestTestClient` for HTTP. Spring tests must not download the JLCPCB database (`kina.jlcpcb.auto-download=false` in `src/test/resources/config/application.yml`).

## Rules

- Revoking an access token must also revoke its linked OAuth refresh tokens (done in `AccessTokenRepository`); keep it that way. A user who loses group membership gets `revokeAllForUser`.
- Never commit `.env`, API keys, tokens or secrets. Use placeholders in docs and fixtures. Never log bearer tokens or API keys.
- Do not edit the vendored `docs/vendor/tme-api-v2-openapi.json`.
- Change the shared contracts only together with `docs/DESIGN.md`.
- Keep the build warning-free and `./mvnw -q verify` green before committing.
- In Markdown, use plain language and short sentences; no em-dashes.
- Commit messages carry no `Co-Authored-By` trailer (the history was rewritten to remove them).
