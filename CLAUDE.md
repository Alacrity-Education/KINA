# CLAUDE.md

## What KINA is

An MCP server and REST API (Spring Boot 4.1.1, Java 21) that lets Claude search electronic components at LCSC (via the JLCPCB parts database), TME (API v2) and Mouser. Only ships-now stock is returned. TME and Mouser results are cached in PostgreSQL for 5 days. Parts are ranked by a deterministic parametric ranker blended (weight 0.2) with a local Laya sidecar, with a deterministic fallback. KINA is also an OAuth 2.1 authorization server for Claude's remote MCP connector.

## Where things are documented

- `docs/DESIGN.md` is the binding design: contracts, search and ranking semantics, security, OAuth, schema, distributor details. If you change a shared type or behaviour, change DESIGN.md in the same commit. When code and DESIGN.md disagree, the code is what runs; fix the document.
- `docs/DEVELOPMENT.md`: toolchain, build, local run, Jackson 3 gotchas.
- `docs/API.md`, `docs/OPERATIONS.md`, `README.md`: user-facing docs. Update them when endpoints, variables or defaults change.
- `task.md`: the original requirements.
- `.env.example` documents every environment variable. Keep it in sync with `application.yml`.

## Build and test

```bash
./mvnw -q verify                 # compile + all tests; needs Docker (Testcontainers, PostgreSQL 17)
./mvnw -q -DskipTests package    # target/kina.jar
./mvnw test -Dtest=PartsApiTest  # one test class
docker compose up -d --build     # kina + postgres + laya-serve
```

Always use `./mvnw`. The build must stay free of compiler warnings (`-Xlint:all`).

Optional live tests: `KINA_LAYA_TEST_URL=http://127.0.0.1:8001 ./mvnw test -Dtest=LayaRankerEvaluationTest`; the Mouser live test needs `KINA_MOUSER_LIVE_TEST=true` and `MOUSER_API_KEY`.

## Module map (`src/main/java/ro/alacrity/kina/`)

| Package | Role |
|---|---|
| `config` | `KinaProperties`, the single `@ConfigurationProperties("kina")` record tree. Extend it; do not bind `kina` twice. |
| `domain` | Records and enums shared everywhere: `Part`, `PriceBreak`, `ParsedQuery`, requests, response DTOs (snake_case wire format). |
| `distributor` | `DistributorClient` contract, `DistributorRegistry`, `DistributorException`; subpackages `lcsc` (JLCPCB download, SQLite FTS5 search), `tme`, `mouser`. |
| `cache` | `PartCacheRepository`, `SearchCacheRepository`, `CacheStatus`, purge job. |
| `search` | `QueryParser`, `ParametricExtractor`, `DeterministicRanker`, `LayaPartRanker` (`PartRanker`), `RankingService`, `PartSearchService`, `PartLookupService`, `DistributorStatusService`. |
| `mcp` | `KinaMcpTools`: the `@McpTool` methods (`search_parts`, `search_parts_batch`, `get_part`, `list_distributors`, `ping`). |
| `api` | `PartsController`, `DistributorsController` under `/api/v1`; `ApiExceptionHandler` (problem+json). |
| `security` | Two filter chains in `SecurityConfig` (machine: bearer tokens; web: session/OIDC), dev-mode admin, `AccessTokenService`, OIDC login. |
| `oauth` | Metadata, dynamic registration, authorize, token, revoke endpoints, PKCE. |
| `web` | `TokenPageController` (Thymeleaf token UI), `PublicUrlResolver` (public origin; all emitted URLs go through it). |

SQL migrations: `src/main/resources/db/migration` (Flyway). Templates: `src/main/resources/templates`.

## Conventions

- Jackson 3: databind is `tools.jackson.*`; annotations stay `com.fasterxml.jackson.annotation.*`. Exceptions are unchecked. DTOs use explicit `@JsonProperty` snake_case names; do not rely on naming strategies.
- Domain and DTO types are Java records. No Lombok.
- Persistence is `JdbcClient` and plain SQL. No JPA. Cached parts are JSONB (`Part` as camelCase JSON).
- Every external call has an explicit connect and read timeout. A distributor error is isolated to that distributor and becomes the `error` code in its result entry.
- Stock rule: only ships-now stock. Never construct, cache, rank or return a `Part` with stock of 0 or less.
- Prices are stored complete and trimmed to the 3 smallest brackets only when building responses.
- MCP tool parameters are snake_case Java parameter names (compiled with `-parameters`).
- LLM-facing descriptions in `KinaMcpTools` are part of the product; keep them precise.
- The Laya sidecar is local only. Never send part data to a third-party inference service. Keep the deterministic fallback working.
- Tests: JUnit 5, Mockito, `MockRestServiceServer` and recorded JSON fixtures for distributors, Testcontainers PostgreSQL for DB tests (`@Import(TestcontainersConfiguration.class)`), `RestTestClient` for HTTP. Spring tests must not download the JLCPCB database (`kina.jlcpcb.auto-download=false` in `src/test/resources/config/application.yml`).

## Rules

- Never commit `.env`, API keys, tokens or secrets. Use placeholders in docs and fixtures. Never log bearer tokens or API keys.
- Do not edit the vendored `docs/vendor/tme-api-v2-openapi.json`.
- Change the shared contracts only together with `docs/DESIGN.md`.
- Keep the build warning-free and `./mvnw -q verify` green before committing.
- In Markdown, use plain language and short sentences; no em-dashes.
- Commit messages end with the co-author trailer given by the session.
