# KINA development guide

## Toolchain

- **JDK**: any JDK >= 21. The build compiles with `--release 21` (`maven.compiler.release=21`).
  Verified on JDK 27 (OpenJDK 27, 2026-09-15): compile, Mockito/Byte Buddy 1.18.11 and the full test suite
  work without `-Dnet.bytebuddy.experimental`. The Docker build uses Temurin 21.
- **Maven**: always use the wrapper, `./mvnw` (Maven 3.9.16). No system Maven needed.
- **Docker**: needed for Testcontainers (tests start `postgres:17-alpine`) and for compose.
- **Lombok**: 1.18.48 (`lombok.version` overrides the 1.18.46 managed by Boot 4.1.1, which cannot run inside the
  JDK 27 compiler). In the IDE, enable annotation processing and use the Lombok plugin (bundled with IntelliJ IDEA;
  Eclipse and VS Code need the Lombok agent or extension).

## Build and test

```bash
./mvnw -q verify             # compile + all tests (needs Docker for Testcontainers)
./mvnw -q -DskipTests package   # target/kina.jar
```

Surefire runs with `-XX:+EnableDynamicAgentLoading --enable-native-access=ALL-UNNAMED` to silence JDK agent warnings.
The compiler runs with `-Xlint:all` (minus `processing`, `serial`) and `-Werror`, so any compiler warning in main or test code fails the
build. Fix the cause; do not suppress it.

Annotation processors run from an explicit `annotationProcessorPaths` list in the POM: Lombok first, then
`spring-boot-configuration-processor`. JDK 23 and later no longer run processors found on the class path, so a new
processor must be added to that list. On JDK 24 and later the `jdk24-plus` profile forks `javac` with
`-J--sun-misc-unsafe-memory-access=allow`, because Lombok still calls `sun.misc.Unsafe` and the JDK would print a
deprecation warning on every compile. JDK 21 (the Docker build) does not know that option and does not use the profile.

Spring tests that need a database: `@SpringBootTest` + `@Import(TestcontainersConfiguration.class)`
(`src/test/java/ro/alacrity/kina/TestcontainersConfiguration.java`, `@ServiceConnection` PostgreSQL 17).
HTTP tests: `@AutoConfigureRestTestClient` + `RestTestClient` (Boot 4 module `spring-boot-resttestclient`).

## Run locally

```bash
cp .env.example .env                      # fill MOUSER_API_KEY, TME_TOKEN, TME_APPLICATION_SECRET
docker run -d --name kina-pg -p 5433:5432 -e POSTGRES_PASSWORD=kina -e POSTGRES_USER=kina -e POSTGRES_DB=kina postgres:17-alpine
set -a; . ./.env; set +a
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/kina ./mvnw spring-boot:run   # or java -jar target/kina.jar
```

Everything in Docker: `docker compose up -d --build` (project name `kina`, services `kina` and `postgres`). Wait for
`docker compose ps` to show both services `healthy`; on a fresh volume the JLCPCB database (~5.3 GB) downloads in the
background and LCSC reports `unavailable` until it is in place. The cross-encoder model is baked into the image at
build time (`/opt/kina/cross-encoder`, DESIGN.md 3.5 and 11) and loads about 0.2 s after startup; until then searches
report `ranking: "fallback"` with `ranking_note: "cross-encoder model not loaded yet"` (`docker compose logs -f kina`;
`list_distributors` shows `ranking.ready`). Ranking threads: `KINA_CROSS_ENCODER_THREADS` in `.env` (default
`min(4, cores)`).
Every environment variable is documented in `.env.example`; `.env` is git-ignored and must never be committed.

A second instance next to a running compose stack (e.g. to test ranking changes against the full JLCPCB database without
touching the stack): start a throwaway Postgres on another port, then run the jar with `PORT=8081`,
`SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:<port>/kina`, `KINA_JLCPCB_DATA_DIR` pointing at a directory that
already holds `parts-fts5.db` (it is adopted, not downloaded) and `KINA_CROSS_ENCODER_MODEL_URL` pointing at a model
directory (used in place; `KINA_CROSS_ENCODER_ENABLED=false` for deterministic ranking only). The
cross-encoder time per query is logged at DEBUG (`LOGGING_LEVEL_RO_ALACRITY_KINA_SEARCH_CE=DEBUG`:
`cross-encoder scored 40 candidates in N ms (T threads, <file>)`). Connector checks: `curl -G localhost:8081/api/v1/parts/search --data-urlencode "q=2x3 female header right
angle" --data-urlencode max_results=5`; each distributor entry shows the phrase it was sent as `distributor_query`.
Mind the Mouser quota (1 000 calls a day): every new query costs one call, plus one when the fallback runs.

### Ranking model: local runs and the image

Local runs (`./mvnw spring-boot:run`, `java -jar`) keep the download mode: on the first start KINA downloads the
int8 file matching the CPU plus the tokenizer files from `KINA_CROSS_ENCODER_MODEL_URL` (default Hugging Face
`resolve/main/`) into `./data/cross-encoder` (`KINA_CROSS_ENCODER_MODEL_DIR`) and retries hourly on failure.
`KINA_CROSS_ENCODER_AUTO_DOWNLOAD=false` turns that off. To pre-fetch the exact files the image bundles (pinned
revision, verified hashes) and use them in place:

```bash
docker/model/fetch-model.sh ./data/cross-encoder-pinned        # POSIX sh, curl, sha256sum; CROSS_ENCODER_VARIANTS=int8 to skip fp32
KINA_CROSS_ENCODER_MODEL_URL=$PWD/data/cross-encoder-pinned ./mvnw spring-boot:run
```

The Docker image differs: the `model` stage of the `Dockerfile` runs the same script at build time, so the container
never downloads the model (`KINA_CROSS_ENCODER_MODEL_URL=/opt/kina/cross-encoder`,
`KINA_CROSS_ENCODER_AUTO_DOWNLOAD=false`). Build variants:

```bash
docker compose build --build-arg CROSS_ENCODER_VARIANTS=int8 kina     # about 90 MB smaller, no fp32 file
# a fine-tuned model from scripts/ranking/finetune_cross_encoder.sh, served over HTTP during the build
(cd data/cross-encoder-finetuned && sha256sum config.json tokenizer_config.json vocab.txt onnx/*.onnx) \
  > docker/model/finetuned.sha256
(cd data/cross-encoder-finetuned && python3 -m http.server 8000) &
docker build --network host -t kina:latest --build-arg CROSS_ENCODER_SOURCE=http://127.0.0.1:8000/ \
  --build-arg CROSS_ENCODER_SHA256_FILE=finetuned.sha256 --build-arg CROSS_ENCODER_REVISION=finetuned-synth .
```

A custom source needs its own hash file in `docker/model/` or `--build-arg CROSS_ENCODER_SKIP_VERIFY=1`. Do not commit
model binaries (`*.onnx` is git-ignored). To check the offline guarantee, run the image on an internal Docker network
(`docker network create --internal ...`) with Postgres: `list_distributors` must show `ranking.ready: true` and
`model_dir: /opt/kina/cross-encoder`, and the log must contain no download line.

MCP smoke test (stateless Streamable HTTP, no `initialize` needed):

```bash
curl -s -X POST localhost:8080/mcp -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## Contracts

Shared types are fixed by `docs/DESIGN.md`; change them only together with that document.

| Package (`ro.alacrity.kina.`) | Contents |
|---|---|
| `config` | `KinaProperties` - the single `@ConfigurationProperties("kina")` record tree for every `kina.*` key (do not add a second binding for the same prefix; extend this record) |
| `domain` | `Distributor`, `RankingMode`, `PriceBreak`, `Part`, `PartKey`, `ParsedQuery` (+ `Constraint`, `Connector`), `SearchRequest`, `BatchSearchRequest`, response DTOs `SearchResponse`, `BatchSearchResponse`, `DistributorResult`, `PartResponse` (trims prices to 3 brackets), `PriceResponse`, `ParsedQueryResponse` |
| `distributor` | `DistributorClient`, `DistributorSearchPage`, `DistributorException` (+ `Kind.code()`, `rateLimitWaitedMillis()`), `DistributorRegistry`; rate limiting (DESIGN.md 3.6): `Deadline` (request deadline + rate-limit wait accounting), `RateLimitRetry` (retry policy around every Mouser/TME HTTP call), `DistributorCooldown` (shared per-distributor cool-down) |
| `distributor.{mouser,tme,lcsc}` | `MouserClient`, `TmeClient` (+ `TmeTokenManager`), `LcscClient` over the JLCPCB SQLite file (`JlcpcbDatabaseManager` downloads/adopts it) |
| `search/ce` | `CrossEncoderPartRanker` (the `PartRanker`), `CrossEncoderModel` (download, load, retry), `ModelDownloader`, `ModelLayout`, `BertTokenizer`, `ScoringBackend` / `OnnxScoringBackend` (ONNX Runtime) |
| `search` | `PartRanker`, `RankingException` (checked, with `Reason`), `QueryParser` (+ `Recognizers`, `ConnectorRecognizer`), `ParametricExtractor`, `DeterministicRanker`, `DistributorPhraser` (connector phrasing per distributor, fallback phrases), `RankingService`, `PartSearchService` (the sequence), `ParallelRetrieval`, `LcscRetriever` and `CachedDistributorRetriever` (cache, phrase fallback), `PageCollector` (paging), `StockRefresher`, `ResponseAssembler`, `CorePhrases`, `PartLookupService`, `DistributorStatusService` |
| `cache` | `CacheStatus`, `PartCacheRepository`, `SearchCacheRepository` (`CachedSearch`), `CacheMaintenance` |
| `security` | `SecurityConfig` (dev/prod filter chains, login failure routing), `DevModeAuthenticationFilter`, `BearerTokenAuthenticationFilter` + `BearerAuthenticationEntryPoint` (401 with `resource_metadata`), `AccessTokenService`/`AccessTokenRepository` (static tokens 30 days, OAuth tokens 1 hour; revoking one also revokes its OAuth refresh tokens; `revokeAllForUser`), OIDC login (`OidcLoginConfiguration`, `LazyOidcClientRegistrationRepository`, `OidcUserSynchronizer`, `OidcIdTokenDecoders`, `OidcHttp` timeouts), group authorisation (`OidcAccessPolicy` claim/domain rules, `MembershipVerifier` re-checks, `UpstreamTokenCipher` AES-GCM, `UpstreamTokenCapturingClientRepository`, `RevokedUserSessionFilter`) |
| `oauth` | OAuth 2.1 authorization server for MCP clients: metadata, `/oauth/register` (+ `RegistrationRateLimiter`), `/oauth/authorize` (consent page, auto-approval of trusted metadata-document clients), `/oauth/token`, `/oauth/revoke`, PKCE; Client ID Metadata Documents (`ClientMetadataDocument` rules, `ClientMetadataDocumentResolver` fetch/trust/cache, `OAuthClientLookup`); `OAuthClientMaintenance` (daily cleanup of unused registered clients) |
| `mcp` | `KinaMcpTools`: `search_parts`, `search_parts_batch`, `get_part`, `list_distributors`, `ping` |
| `api` / `web` | `/api/v1` controllers + `ApiExceptionHandler` (RFC 9457 problems); Thymeleaf tabs (`SearchPageController` at `/`, `McpPageController` at `/connect`, `StatusPageController` at `/status`), `PublicUrlResolver` |

Dependency injection: Spring beans take their collaborators as `@Autowired private Foo foo;` fields (not final),
grouped at the top of the class. This is a deliberate choice for shorter classes. There are no injection constructors
and no setters; a package-private setter is the fallback only where a field cannot be injected. Logic that used to run
in a constructor (validation, derived values, listener registration, starting executors) lives in a `@PostConstruct`
method, and `@PreDestroy` stops what it started. Classes whose default is a no-op until Spring injects the real bean
keep it on the field (`@Autowired private KinaMetrics metrics = KinaMetrics.NOOP;`). Circular references stay
disabled. Unit tests build beans with `ro.alacrity.kina.TestWiring` (test sources): `wire(bean, "field", value, ...)`
sets fields by name with `ReflectionTestUtils.setField`, so a renamed field fails loudly, and then runs the
`@PostConstruct` methods; typed helpers such as `metricsStore(registry)`, `registry(clients)`, `rankingService(...)` and
`properties(...)` cover the beans that tests build often. `RankingFixtures.searchService(...)` (search test package)
assembles `PartSearchService` and its package-private stages the same way.
Records, DTOs, value objects and `KinaProperties` keep their constructors. So do the security filters, the bearer
entry point and `UpstreamTokenCapturingClientRepository`, which `SecurityConfig` creates itself (as beans, Spring Boot
would register the filters for every request), and `OidcAccessPolicy`, a value built from the OIDC settings.

Lombok (configured in `lombok.config` at the repository root: `config.stopBubbling`, `@lombok.Generated` on
generated code for coverage tools, logger field `log`, and `@Qualifier` / `@Value` copied from fields to generated
constructor parameters):

- Allowed: `@Slf4j` (field `log`, never a hand-written `LoggerFactory.getLogger`); `@RequiredArgsConstructor` on
  classes that are not Spring beans when a constructor only assigns final fields (keep explicit constructors that
  validate, derive values or delegate to another constructor; `access = AccessLevel.PACKAGE` or `PRIVATE` only for a
  sole constructor);
  `@Builder` / `@Builder(toBuilder = true)` on wide records (`Part`, `ParsedQuery`, `ParsedQuery.Connector`,
  `DistributorResult`, `PartResponse`, `ParsedQueryResponse.ConnectorResponse`, `RankingSummary`); use `toBuilder()`
  or `@With` to copy a record with one field changed; `@UtilityClass` for static-only helpers (declare members
  `static` anyway, so static imports keep working); `@Getter` / `@Setter` / `@AllArgsConstructor` for trivial members
  of mutable helpers; `@Value`, `@EqualsAndHashCode` and `@ToString` only where they equal the hand-written code.
- Forbidden: `@Data` on anything with identity or security semantics, `@SneakyThrows`, `val` / `var` (Java `var` is
  fine), `@Synchronized`, `@Delegate`, `@Cleanup`, `@NonNull` on parameters (keep `Objects.requireNonNull`), and other
  `lombok.experimental` features (`@UtilityClass` is the one exception; `@Accessors(fluent = true)` is not allowed, so
  record-style accessors such as `kind()` stay hand-written).
- Never change a record's component order and never replace a canonical record constructor: Jackson deserialises
  records through it and the SQL mappers depend on the order. `@Builder` on a record only adds `builder()` (and
  `toBuilder()`); keep `@JsonCreator` factories such as `SearchRequest.of`.
- Keep hand-written `toString()` methods that mask secrets (`KinaProperties`, token responses, `IssuedToken`,
  `UpstreamTokenCipher`). `KinaProperties` stays Lombok-free: Spring binds its records through their constructors.
- Lombok annotations go directly above the class declaration, after the Spring annotations.

Gotchas:

- **Jackson 3**: databind is `tools.jackson.*` (`JsonMapper`, `ObjectMapper`); annotations stay
  `com.fasterxml.jackson.annotation.*`. `@JsonNaming`/`PropertyNamingStrategies` moved to `tools.jackson.databind.*`;
  the DTOs use explicit `@JsonProperty` names instead. Jackson 3 exceptions are unchecked (`JacksonException`).
- `Part` is the cache payload (`cached_parts.payload`): default camelCase JSON, round-trip tested.
- Response wire format is snake_case and pinned by `ResponseJsonTest`; `RankingMode` and `CacheStatus` serialise lower-case.
- Rate-limit tests never sleep for real: `ro.alacrity.kina.distributor.FakeTime` (test sources) is the ticker, wall clock
  and `RateLimitRetry.Sleeper` in one, and `FakeTime.retry(distributor, 0.5)` builds a jitter-free policy to inject through
  the package-private `MouserApi` constructor or the `retry` field of `TmeClient` (`TestWiring.wire`). The client methods without a `Deadline` never wait on a rate
  limit, so tests calling them see `RATE_LIMITED` at once.
- MCP: annotations live in `org.springframework.ai.mcp.annotation`; tool beans are plain `@Component`s scanned
  automatically. Tool results are serialised to JSON text content by the MCP server's own Jackson 3 mapper.
- Group authorisation tests: `OidcGroupAuthorisationIntegrationTest` runs production mode against
  `FakeOidcProvider` (test sources; in-process `HttpServer` with discovery, JWKS from a generated RSA key, an
  `/authorize` that redirects straight back for the scripted user, rotating refresh tokens, userinfo, switches for
  "group removed", "grant revoked", "token endpoint down" and "groups only in userinfo"). The issuer URL is dynamic,
  so it comes from `@DynamicPropertySource`; time is simulated by ageing `users.membership_checked_at` in SQL. Replay
  the provider's redirect into MockMvc with `get(URI)`, not a template string (the `state` value is already encoded).
- `OAuthHardeningTest` has its own context (rate limit 3, token UI off) and replaces `ClientMetadataDocumentResolver`
  with a `@Primary` instance that trusts `127.0.0.1` and accepts `http` client IDs, so metadata documents can be served
  by an in-process `HttpServer`. Production code never accepts `http` client IDs. The shared test configuration sets
  `kina.oauth.register-rate-limit-per-minute` very high because many tests register clients from the same address.
- The JDK `HttpClient` tries an `h2c` upgrade on plain `http`; Authentik's server then loses POST bodies. Clients that
  talk to identity providers or metadata hosts pin `HttpClient.Version.HTTP_1_1`.
- Boot 4 starters: `spring-boot-starter-webmvc`, `-restclient`, `-flyway`, `-jdbc`, `-security-oauth2-client`;
  test slices come from `spring-boot-starter-{webmvc,restclient,jdbc,security}-test`. All are already in the POM.

## End-to-end checks

`scripts/e2e/` drives a running stack through its published port exactly as a user or MCP client would
(Python 3.10+, standard library only). Start the stack, wait for `healthy`, then:

```bash
python3 scripts/e2e/kina_e2e.py                     # suites ui, mcp, oauth, forwarded, rest, metrics against http://localhost:8080
                                                     # (actuator on the management port, --metrics http://localhost:9090)
python3 scripts/e2e/kina_e2e.py mcp rest --report /tmp/kina-e2e.json   # selected suites + JSON report with timings
KINA_URL=http://host:8080 python3 scripts/e2e/kina_e2e.py               # another instance
scripts/e2e/prod_smoke.sh                           # prod-mode smoke in a throwaway second container (port 18080)
```

| Suite | What it checks |
|---|---|
| `ui` | dev-mode tabs: `/` shows the three tabs and the search form, an LCSC search (`/?q=10k+0603+resistor&distributors=LCSC`, no Mouser or TME quota) renders result rows, `/status` shows the version and the cache-by-type table; the MCP tab (`/connect`) renders; creates two tokens through the form (session cookie + CSRF), reads the plaintext once, sees them listed, revokes one (it gets 401, the other keeps working); POST without CSRF is 403. The first token is used by `mcp` and `rest` (or set `KINA_TOKEN`). |
| `mcp` | `initialize`, `tools/list` (5 tools), `ping`, `search_parts` `10uF X7R 0805` with `max_results` 5 then 20 (second call must be `cache: hit` for Mouser and TME), `search_parts_batch` (2 queries), `get_part` (TME part from the search, unknown LCSC part -> `found: false`), `list_distributors` (3 available, cross-encoder ready, JLCPCB >= 7 M parts); the first `search_parts` must report `ranking: "blended"`, invalid bearer -> 401 |
| `oauth` | the Claude connector flow: 401 challenge with `resource_metadata`, both metadata documents, dynamic registration, `/oauth/authorize` with PKCE S256 + consent (CSRF) -> code, code exchange, no code replay, `tools/list` with the OAuth token, refresh rotation (old pair dead), `/oauth/revoke` of access and refresh tokens, web-UI revocation of an OAuth token also kills its refresh token |
| `forwarded` | with `X-Forwarded-Proto: https` + `X-Forwarded-Host: kina.example.com` every URL in both metadata documents and the `resource_metadata` challenge uses `https://kina.example.com` |
| `rest` | `GET /api/v1/parts/search`, `POST .../search/batch`, TME phrase fallback (`fallback_query`, informational), `GET /api/v1/parts/TME/<symbol>`, 404/400 problem documents, invalid token -> 401, public health; at LCSC (no quota) a part number in the query (`1N4148W SOD-123`: `parsed.part_numbers`, the part first, `requested_part_found` true; an unknown one: false and a hint naming it) and `excluded_below_spec_detail` (a lifetime request names the parts left out with the failed rating). Every search response is also checked for the response shape: the counts add up, a part with stock 0 only when the query names it and only after every part in stock, `requested_part_found` null exactly when no part number is named, a `hint` whenever it is false |
| `prod` | (via `prod_smoke.sh`) app starts with `KINA_MODE=prod` and dummy OIDC client credentials; `/mcp` and `/api` without a token are 401 (with `resource_metadata`); `GET /` -> `/oauth2/authorization/oidc` -> 302 to the authorization endpoint discovered from `OIDC_ISSUER_URI` (default `https://accounts.google.com`; override `OIDC_ISSUER_URI` and `EXPECTED_AUTH_HOST` for another provider) |

### Group authorisation against a real Authentik

`scripts/e2e/authentik/` runs a disposable Authentik 2026.8.3 (server + worker + PostgreSQL; Authentik no longer needs
Redis) next to a throwaway PostgreSQL for KINA, configures it through the API and drives the real login over HTTP:

```bash
./mvnw -q -DskipTests package                                   # the driver runs target/kina.jar
python3 scripts/e2e/authentik/kina_authentik_e2e.py             # about 70 s on a warm image cache, then removes everything
python3 scripts/e2e/authentik/kina_authentik_e2e.py --keep      # leave Authentik (localhost:19000) and KINA (18080) running
python3 scripts/e2e/authentik/kina_authentik_e2e.py --reuse     # reuse a kept Authentik and its .env
```

- The driver writes `scripts/e2e/authentik/.env` with fresh random secrets (`PG_PASS`, `AUTHENTIK_SECRET_KEY`,
  `AUTHENTIK_BOOTSTRAP_PASSWORD`, `AUTHENTIK_BOOTSTRAP_TOKEN`; git-ignored, deleted at the end unless `--keep`) and runs
  `docker compose -f scripts/e2e/authentik/compose.yaml up -d` (project `kina-authentik-e2e`, Authentik on
  `127.0.0.1:19000`, KINA's database on `127.0.0.1:15432`).
- `bootstrap.py` (also runnable alone with `AUTHENTIK_URL` and `AUTHENTIK_BOOTSTRAP_TOKEN`) creates the group
  `ElectronicsEngineer` and a second group `KinaGuests`, users `e2e-member`, `e2e-nonmember`, `e2e-guest` with random
  passwords, a scope mapping `groups`, a confidential OAuth2/OIDC provider (`grant_types` authorization_code +
  refresh_token, which Authentik 2026.8 requires when the provider is created through the API; strict redirect URI
  `http://localhost:18080/login/oauth2/code/oidc`; self-signed RS256 key; scopes openid, email, profile,
  offline_access, groups; implicit consent), the application `kina` and bindings of both groups.
- KINA runs from `target/kina.jar` in prod mode with `OIDC_REQUIRED_GROUPS=ElectronicsEngineer`,
  `OIDC_EXTRA_SCOPES=groups` and a random `KINA_TOKEN_ENCRYPTION_KEY`; log in `scripts/e2e/authentik/out/kina.log`.
- Checks (15): member login through Authentik's identification and password stages (flow executor API) -> KINA consent
  -> code -> tokens (`expires_in` 3600) -> MCP `tools/list`; upstream refresh token stored as `v1.` ciphertext;
  refresh within the interval; refresh with an aged check re-verified at Authentik; member removed from the group in
  Authentik -> next refresh `invalid_grant`, access token 401, `access_revoked_at` set; non-member refused by
  Authentik's binding ("Permission denied"), no KINA session; `e2e-guest` (bound group, not `ElectronicsEngineer`)
  passes Authentik and is refused by KINA (`/login-denied`, 403).
- Result on 2026-10-05: 15/15. Authentik answers the refresh of a removed member with tokens whose `groups` lack the
  group (it does not re-evaluate the application binding on refresh), so KINA's claim check does the revocation.

Quota: on a cold cache the dev suites make 2 Mouser calls (one search, one new batch query); REST checks are restricted to
LCSC and TME, and reruns within the cache TTL make no Mouser calls. In dev mode an anonymous `POST /mcp` is served as
the dev admin, so the dev `oauth` suite triggers the 401 challenge with an unknown token; the anonymous 401 is covered
by the `prod` suite. Tokens are never printed (only their 12-character prefix); the suites leave their tokens and
OAuth clients in the database (revoke them in the MCP tab, `/connect`, if you care).

Claude Code normally connects through OAuth (`claude mcp add --transport http kina <url>/mcp`, then `/mcp` to sign
in); a static token is for machines without a browser. Static-token check (verified with Claude Code 2.1.286):
`claude mcp add --transport http kina http://localhost:8080/mcp --header "Authorization: Bearer <token>"` -> `claude mcp list` shows `kina: ... (HTTP) - Connected`, and
`claude -p "Call the ping tool of the kina MCP server" --allowedTools mcp__kina__ping` returns
`{"status":"ok","version":"0.1.0-SNAPSHOT"}`.

## Measured on 2026-10-05

Host: Intel Core Ultra 9 285K (8 P-cores + 16 E-cores, AVX2 + AVX-VNNI, no AVX-512), 30 GB RAM, Docker 29 / Compose 5;
full JLCPCB database (7,146,764 parts, source date 2026-09-26).

Compose stack (`docker stats` after the e2e run, measured before the cross-encoder was added; its native memory is an
estimate in the notes):

| Service | RAM | Notes |
|---|---|---|
| `kina` | ~485 MiB of the 2 GiB `mem_limit` | max heap 1.5 GiB (75%); the JLCPCB file is read through the OS page cache, not the heap; the cross-encoder session adds native memory outside the heap (about 100-200 MB int8, estimate) |
| `postgres` | ~45 MiB | `pgdata` ~50 MB after the e2e run |

Disk: `kina_kina-data` 5.33 GB JLCPCB database. `kina` image ~580 MB before the ONNX Runtime jar (53 MB, native
libraries for Linux x64/aarch64, macOS and Windows; `kina.jar` is now 115 MB); measured 2026-10-06 with the bundled
model: the model layer is 138 MB (two int8 files of 23 MB, fp32 91 MB), `docker image inspect` size 695 MB before and
962 MB after (containerd image store), `docker save` archive 231 MB before and 360 MB after.

Startup: Spring context ~2 s; Flyway V1-V6 on an empty database < 0.1 s; adopting a pre-seeded JLCPCB file
(validation `count(*)`) ~19 s in the background; cross-encoder load from the bundled directory (session creation,
warm-up) ~0.2 s in the background. A local run's first start (download of vocab, configs and the 23 MB int8 file from
Hugging Face) took 3.6 s.

Ranking, local jar (`taskset -c 0-7`, `KINA_CROSS_ENCODER_THREADS` default = 4, int8 `model_qint8_avx512_vnni.onnx`,
all three distributors; the server logs `search '<key>': fetch N ms [...], rank N ms (blended|fallback)` and, at DEBUG,
`cross-encoder scored N candidates in M ms`):

| Query (`max_results` 10) | fetch (cold) | cross-encoder, 40 candidates | rank total | ranking |
|---|---|---|---|---|
| `10uF X7R 0805` (first search after start) | 1962 ms | 245 ms | 294 ms | blended |
| `SOT-23 N-channel MOSFET 30V` | 2881 ms | 264 ms | 293 ms | blended |
| `LM358 SOIC-8` | 2916 ms | 211 ms | 226 ms | blended |
| `16MHz crystal 3225 SMD` | 1702 ms | 230 ms | 245 ms | blended |
| four further queries (JIT warm) | 1.1-4.1 s | 113-206 ms | 130-230 ms | blended |
| `10uF X7R 0805` again (Postgres and score cache hit) | 35 ms | not called | 16 ms | blended |

`CrossEncoderEvaluationTest` (32 queries of `ranking-eval.jsonl`, 20-40 candidates each, 4 threads, no CPU pinning):
int8 median 125 ms / max 256 ms per query, fp32 median 256 ms / max 476 ms. The rest of a search is distributor time:
Mouser and TME answer in 1-4 s, LCSC (SQLite FTS5) usually in 50-450 ms, but a query whose tokens are short (LIKE
clauses) on a cold page cache took 4.1 s (`1uF 50V X7R 0402`).

