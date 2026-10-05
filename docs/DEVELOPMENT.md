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

Everything in Docker: `docker compose up --build` (services `kina`, `postgres`, `laya-serve`; the Laya image build
downloads PyTorch and takes a while). GPU ranking: `docker compose -f compose.yaml -f compose.cuda.yaml up --build`.
Every environment variable is documented in `.env.example`; `.env` is git-ignored and must never be committed.

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
| `domain` | `Distributor`, `RankingMode`, `PriceBreak`, `Part`, `PartKey`, `ParsedQuery` (+ `Constraint`), `SearchRequest`, `BatchSearchRequest`, response DTOs `SearchResponse`, `BatchSearchResponse`, `DistributorResult`, `PartResponse` (trims prices to 3 brackets), `PriceResponse`, `ParsedQueryResponse` |
| `distributor` | `DistributorClient`, `DistributorSearchPage`, `DistributorException` (+ `Kind.code()`), `DistributorRegistry` |
| `search` | `PartRanker`, `RankingException` (checked, with `Reason`) |
| `cache` | `CacheStatus` |
| `security` | `SecurityConfig` placeholder (permit all) - replaced by the security module |
| `mcp` | `KinaMcpTools` (`@McpTool` methods; currently `ping`) |

Gotchas:

- **Jackson 3**: databind is `tools.jackson.*` (`JsonMapper`, `ObjectMapper`); annotations stay
  `com.fasterxml.jackson.annotation.*`. `@JsonNaming`/`PropertyNamingStrategies` moved to `tools.jackson.databind.*`;
  the DTOs use explicit `@JsonProperty` names instead. Jackson 3 exceptions are unchecked (`JacksonException`).
- `Part` is the cache payload (`cached_parts.payload`): default camelCase JSON, round-trip tested.
- Response wire format is snake_case and pinned by `ResponseJsonTest`; `RankingMode` and `CacheStatus` serialise lower-case.
- MCP: annotations live in `org.springframework.ai.mcp.annotation`; tool beans are plain `@Component`s scanned
  automatically. Tool results are serialised to JSON text content by the MCP server's own Jackson 3 mapper.
- Boot 4 starters: `spring-boot-starter-webmvc`, `-restclient`, `-flyway`, `-jdbc`, `-security-oauth2-client`;
  test slices come from `spring-boot-starter-{webmvc,restclient,jdbc,security}-test`. All are already in the POM.
