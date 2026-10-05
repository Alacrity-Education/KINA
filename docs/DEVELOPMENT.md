# KINA development guide

## Toolchain

- **JDK**: any JDK >= 21. The build compiles with `--release 21` (`maven.compiler.release=21`).
  Verified on JDK 27 (OpenJDK 27, 2026-09-15): compile, Mockito/Byte Buddy 1.18.11 and the full test suite
  work without `-Dnet.bytebuddy.experimental`. The Docker build uses Temurin 21.
- **Maven**: always use the wrapper, `./mvnw` (Maven 3.9.16). No system Maven needed.
- **Docker**: needed for Testcontainers (tests start `postgres:17-alpine`) and for compose.

## Build and test

```bash
./mvnw -q verify             # compile + all tests (needs Docker for Testcontainers)
./mvnw -q -DskipTests package   # target/kina.jar
```

Surefire runs with `-XX:+EnableDynamicAgentLoading --enable-native-access=ALL-UNNAMED` to silence JDK agent warnings.
The compiler runs with `-Xlint:all` (minus `processing`, `serial`); keep the build warning-free.

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
background and LCSC reports `unavailable` until it is in place, and the cross-encoder model (~25 MB, DESIGN.md 3.5)
downloads to `/data/cross-encoder`; until it is loaded searches report `ranking: "fallback"` with
`ranking_note: "cross-encoder model not loaded yet"` (`docker compose logs -f kina`; `list_distributors` shows
`ranking.ready`). Ranking threads: `KINA_CROSS_ENCODER_THREADS` in `.env` (default `min(4, cores)`).
Every environment variable is documented in `.env.example`; `.env` is git-ignored and must never be committed.

A second instance next to a running compose stack (e.g. to test ranking changes against the full JLCPCB database without
touching the stack): start a throwaway Postgres on another port, then run the jar with `PORT=8081`,
`SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:<port>/kina`, `KINA_JLCPCB_DATA_DIR` pointing at a directory that
already holds `parts-fts5.db` (it is adopted, not downloaded) and `KINA_CROSS_ENCODER_MODEL_DIR` pointing at a model
directory (downloaded once, then reused; `KINA_CROSS_ENCODER_ENABLED=false` for deterministic ranking only). The
cross-encoder time per query is logged at DEBUG (`LOGGING_LEVEL_RO_ALACRITY_KINA_SEARCH_CE=DEBUG`:
`cross-encoder scored 40 candidates in N ms (T threads, <file>)`). Connector checks: `curl -G localhost:8081/api/v1/parts/search --data-urlencode "q=2x3 female header right
angle" --data-urlencode max_results=5`; each distributor entry shows the phrase it was sent as `distributor_query`.
Mind the Mouser quota (1 000 calls a day): every new query costs one call, plus one when the fallback runs.

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
| `search` | `PartRanker`, `RankingException` (checked, with `Reason`), `QueryParser` (+ `Recognizers`, `ConnectorRecognizer`), `ParametricExtractor`, `DeterministicRanker`, `DistributorPhraser` (connector phrasing per distributor, fallback phrases), `RankingService`, `PartSearchService` (cache, paging, phrase fallback), `PartLookupService`, `DistributorStatusService` |
| `cache` | `CacheStatus`, `PartCacheRepository`, `SearchCacheRepository` (`CachedSearch`), `CacheMaintenance` |
| `security` | `SecurityConfig` (dev/prod filter chains), `DevModeAuthenticationFilter`, `BearerTokenAuthenticationFilter` + `BearerAuthenticationEntryPoint` (401 with `resource_metadata`), `AccessTokenService`/`AccessTokenRepository` (30-day tokens; revoking one also revokes its OAuth refresh tokens), OIDC login (`OidcLoginConfiguration`, `LazyOidcClientRegistrationRepository`, `OidcUserSynchronizer`) |
| `oauth` | OAuth 2.1 authorization server for MCP clients: metadata, `/oauth/register`, `/oauth/authorize` (consent page), `/oauth/token`, `/oauth/revoke`, PKCE |
| `mcp` | `KinaMcpTools`: `search_parts`, `search_parts_batch`, `get_part`, `list_distributors`, `ping` |
| `api` / `web` | `/api/v1` controllers + `ApiExceptionHandler` (RFC 9457 problems); Thymeleaf token UI (`TokenPageController`), `PublicUrlResolver` |

Gotchas:

- **Jackson 3**: databind is `tools.jackson.*` (`JsonMapper`, `ObjectMapper`); annotations stay
  `com.fasterxml.jackson.annotation.*`. `@JsonNaming`/`PropertyNamingStrategies` moved to `tools.jackson.databind.*`;
  the DTOs use explicit `@JsonProperty` names instead. Jackson 3 exceptions are unchecked (`JacksonException`).
- `Part` is the cache payload (`cached_parts.payload`): default camelCase JSON, round-trip tested.
- Response wire format is snake_case and pinned by `ResponseJsonTest`; `RankingMode` and `CacheStatus` serialise lower-case.
- Rate-limit tests never sleep for real: `ro.alacrity.kina.distributor.FakeTime` (test sources) is the ticker, wall clock
  and `RateLimitRetry.Sleeper` in one, and `FakeTime.retry(distributor, 0.5)` builds a jitter-free policy to inject via
  the package-private `MouserApi`/`TmeClient` constructors. The client methods without a `Deadline` never wait on a rate
  limit, so tests calling them see `RATE_LIMITED` at once.
- MCP: annotations live in `org.springframework.ai.mcp.annotation`; tool beans are plain `@Component`s scanned
  automatically. Tool results are serialised to JSON text content by the MCP server's own Jackson 3 mapper.
- Boot 4 starters: `spring-boot-starter-webmvc`, `-restclient`, `-flyway`, `-jdbc`, `-security-oauth2-client`;
  test slices come from `spring-boot-starter-{webmvc,restclient,jdbc,security}-test`. All are already in the POM.

## End-to-end checks

`scripts/e2e/` drives a running stack through its published port exactly as a user or MCP client would
(Python 3.10+, standard library only). Start the stack, wait for `healthy`, then:

```bash
python3 scripts/e2e/kina_e2e.py                     # suites ui, mcp, oauth, forwarded, rest against http://localhost:8080
python3 scripts/e2e/kina_e2e.py mcp rest --report /tmp/kina-e2e.json   # selected suites + JSON report with timings
KINA_URL=http://host:8080 python3 scripts/e2e/kina_e2e.py               # another instance
scripts/e2e/prod_smoke.sh                           # prod-mode smoke in a throwaway second container (port 18080)
docker compose -f compose.yaml -f compose.cuda.yaml config -q          # GPU overlay validates
```

| Suite | What it checks |
|---|---|
| `ui` | dev-mode token page renders; creates two tokens through the form (session cookie + CSRF), reads the plaintext once, sees them listed, revokes one (it gets 401, the other keeps working); POST without CSRF is 403. The first token is used by `mcp` and `rest` (or set `KINA_TOKEN`). |
| `mcp` | `initialize`, `tools/list` (5 tools), `ping`, `search_parts` `10uF X7R 0805` with `max_results` 5 then 20 (second call must be `cache: hit` for Mouser and TME), `search_parts_batch` (2 queries), `get_part` (TME part from the search, unknown LCSC part -> `found: false`), `list_distributors` (3 available, cross-encoder ready, JLCPCB >= 7 M parts); the first `search_parts` must report `ranking: "blended"`, invalid bearer -> 401 |
| `oauth` | the Claude connector flow: 401 challenge with `resource_metadata`, both metadata documents, dynamic registration, `/oauth/authorize` with PKCE S256 + consent (CSRF) -> code, code exchange, no code replay, `tools/list` with the OAuth token, refresh rotation (old pair dead), `/oauth/revoke` of access and refresh tokens, web-UI revocation of an OAuth token also kills its refresh token |
| `forwarded` | with `X-Forwarded-Proto: https` + `X-Forwarded-Host: kina.example.com` every URL in both metadata documents and the `resource_metadata` challenge uses `https://kina.example.com` |
| `rest` | `GET /api/v1/parts/search`, `POST .../search/batch`, TME phrase fallback (`fallback_query`, informational), `GET /api/v1/parts/TME/<symbol>`, 404/400 problem documents, invalid token -> 401, public health |
| `prod` | (via `prod_smoke.sh`) app starts with `KINA_MODE=prod` and dummy OIDC client credentials; `/mcp` and `/api` without a token are 401 (with `resource_metadata`); `GET /` -> `/oauth2/authorization/oidc` -> 302 to the authorization endpoint discovered from `OIDC_ISSUER_URI` (default `https://accounts.google.com`; override `OIDC_ISSUER_URI` and `EXPECTED_AUTH_HOST` for another provider) |

Quota: on a cold cache the dev suites make 2 Mouser calls (one search, one new batch query); REST checks are restricted to
LCSC and TME, and reruns within the cache TTL make no Mouser calls. In dev mode an anonymous `POST /mcp` is served as
the dev admin, so the dev `oauth` suite triggers the 401 challenge with an unknown token; the anonymous 401 is covered
by the `prod` suite. Tokens are never printed (only their 12-character prefix); the suites leave their tokens and
OAuth clients in the database (revoke them on the token page if you care).

Claude Code (verified with Claude Code 2.1.286): `claude mcp add --transport http kina http://localhost:8080/mcp
--header "Authorization: Bearer <token>"` -> `claude mcp list` shows `kina: ... (HTTP) - Connected`, and
`claude -p "Call the ping tool of the kina MCP server" --allowedTools mcp__kina__ping` returns
`{"status":"ok","version":"0.1.0-SNAPSHOT"}`.

## Measured on 2026-10-05

Host: 24 cores, 30 GB RAM, Docker 29 / Compose 5; stack from `compose.yaml` (CPU Laya, `LAYA_THREADS=4`,
`multilingual` checkpoint), full JLCPCB database (7,146,764 parts, source date 2026-09-26).

Memory (`docker stats` after the e2e run):

| Service | RAM | Notes |
|---|---|---|
| `kina` | ~485 MiB of the 2 GiB `mem_limit` | max heap 1.5 GiB (75%); the JLCPCB file is read through the OS page cache, not the heap |
| `postgres` | ~45 MiB | `pgdata` ~50 MB after the e2e run |
| `laya-serve` | ~1.9 GiB | after ranking; idle at ~0% CPU |

Disk: `kina_kina-data` 5.33 GB, `kina_laya-models` 1.5 GB, `laya-serve` image 1.76 GB, `kina` image ~580 MB.

Startup: Spring context ~1.9 s; Flyway V1-V3 on an empty database < 0.1 s; adopting a pre-seeded JLCPCB file
(validation `count(*)`) ~19 s in the background; `laya-serve` with a cached checkpoint healthy in < 30 s.

Search latency (wall clock at the client; the server logs `search '<key>': fetch N ms [...], rank N ms (laya|fallback)`):

| Request | Cold (distributor calls) | Warm (Postgres cache hit) |
|---|---|---|
| MCP `search_parts` `10uF X7R 0805`, 3 distributors, `max_results` 5 | 6.1 s | 4.3 s (fetch 49 ms, Laya rank 4.2 s for 40 candidates) |
| same query, `max_results` 20 (cache + Laya score cache hit) | - | 0.06 s |
| MCP `search_parts_batch`, 2 queries, 3 distributors | 8.5 s | 4.4 s |
| REST `4.7k 1% 0603 resistor`, LCSC + TME | 6.3 s | 4.8 s |
| REST batch, 2 queries, LCSC + TME | 9.1 s | 8.7 s |
| REST `SOT-23 N-channel MOSFET 30V`, TME (0 hits -> fallback `MOSFET 30V SOT-23`, 54 parts) | 8.3 s | 4.1 s |
| `get_part` / `GET /api/v1/parts/TME/<symbol>` (cached part) | - | < 10 ms |

Ranking dominates warm searches: Laya on CPU costs ~90 ms per candidate at `LAYA_THREADS=4` (21 candidates 1.9 s,
40 candidates 3.7-4.3 s) and ~50-60 ms per candidate at `LAYA_THREADS=8` (10 candidates 0.5 s, 33 candidates 2.1 s),
i.e. 8 threads are ~1.5-1.8x faster; everything stays well inside the 18 s ranking budget. LCSC (SQLite FTS5) usually
answers in 50-450 ms but a query whose tokens are short (LIKE clauses) on a cold page cache took 4.1 s
(`1uF 50V X7R 0402`).

