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
| Ranking model | In-process cross-encoder `cross-encoder/ms-marco-MiniLM-L6-v2` (Apache-2.0) on ONNX Runtime for Java `com.microsoft.onnxruntime:onnxruntime:1.30.0` (CPU, bundled native library), own BERT WordPiece tokenizer (section 3.5) |
| Packaging | Multi-stage `Dockerfile`, `compose.yaml` with services `kina`, `postgres` |

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
├── search/          QueryParser, ParametricExtractor, DeterministicRanker, PartRanker, RankingService,
│   │                RankingScoreCache, PartSearchService, PartLookupService, DistributorStatusService
│   └── ce/          CrossEncoderPartRanker, CrossEncoderModel (download/load), ModelDownloader, ModelLayout,
│                    BertTokenizer, ScoringBackend, OnnxScoringBackend
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

public enum RankingMode { BLENDED, FALLBACK }   // JSON "blended" / "fallback"

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
    /** Same, but rate-limited calls may wait and retry within the request deadline (section 3.6).
     *  Defaults delegate to the methods above (LCSC has no rate limits); Mouser and TME override them,
     *  and their deadline-less methods use Deadline.immediate() (rate limits fail fast). */
    default DistributorSearchPage search(String query, int offset, int limit, Deadline deadline) { ... }
    default Optional<Part> getPart(String distributorPartNumber, Deadline deadline) { ... }
}

public record DistributorSearchPage(List<Part> parts, int totalResults, boolean hasMore) {}

public class DistributorException extends RuntimeException {
    public enum Kind { NOT_CONFIGURED, UNAVAILABLE, RATE_LIMITED, BAD_RESPONSE, TIMEOUT }
    public long rateLimitWaitedMillis();   // time the failed call waited on rate limits (0 if none)
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
Freshness: `kina.cache.ttl` default `5d`; anything younger is fresh. Exception: a cached search whose part list is
**empty** is fresh only for `kina.cache.empty-result-ttl` (default `1h`, the shorter of the two applies), so a transient
distributor glitch or a newly stocked part is not hidden for five days.

Fetch window per distributor: `window = max(maxResults, kina.search.candidate-window /*default 40*/)`
capped by `kina.distributors.<name>.max-results-per-search` (Mouser default 50 = one API call,
TME default 60, LCSC default 200).

Algorithm (`PartSearchService.fetchDistributor`; every requested distributor runs on its own virtual thread,
bounded by `kina.search.distributor-timeout` of active work; time spent waiting on a rate limit is added to that
budget, but never beyond the request deadline `kina.search.max-request-duration`, section 3.6):

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
   hit, or the next page would not finish before the distributor deadline (estimated from the previous page's active
   time, rate-limit waits excluded). Paging is driven by **raw record offsets**,
   not by the number of parts kept: distributors drop records without ships-now stock (live: TME reported 32 in-stock
   matches for "10uF X7R 0805" of which 26 were kept), so `part_numbers.size` is not a valid resume offset.
   LCSC is queried once with `limit = window`.
   **Phrase fallback** (Mouser and TME only, not LCSC): when the first fetch of the sent query (the user's text, or the
   distributor phrase below) succeeds with **zero** in-stock parts and the deadline has not passed, the search is
   retried once with a shorter phrase (`DistributorPhraser.fallback`):
   - connector queries: TME the type words with the positions (`pin strips female 6`), Mouser the type words with the
     written pitch and the orientation (`female header right angle`, `male header 2.54mm`);
   - otherwise the query's parametric core (`PartSearchService.corePhrase`): the family word as written in the query
     (`MOSFET`, `MLCC`, `LDO`...), the parsed values except tolerance (display form, e.g. `30V`, `10uF`), the dielectric
     and the package, in that order, e.g. `"SOT-23 N-channel MOSFET 30V"` -> `"MOSFET 30V SOT-23"`;
   - keyword-only queries (no value/dielectric/package): the 3 to 5 most informative tokens in query order
     (`DistributorPhraser.keywordCore`): the family word, recognised values and packages, part-number-like tokens with
     letters and digits, then longer words; filler words (`nice`, `cheap`, `module`, `with`...) never, e.g.
     `"ESP32-WROOM-32 wifi bluetooth module with antenna"` -> `"ESP32-WROOM-32 wifi bluetooth antenna"`.
   No retry when there is no shorter phrase: the core would be a single term or is not shorter than the query, a
   keyword-only query has 3 or fewer tokens, or the phrase equals what was sent. The retry's result (even if also
   empty) is what gets cached, under the original query key, together with the phrase (`cached_searches.fallback_query`);
   a `PARTIAL` extension pages on with that phrase. The distributor entry reports it as `fallback_query` (null when the
   sent query found parts). A failure of the retry is reported as the distributor's `error` and nothing is cached.
   TME's 40-character phrase limit is applied by the client as for any query.
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
   exceptions map to `unavailable`. A rate limit is not an immediate error: the call waits and retries (section 3.6);
   `rate_limited` is reported only when the next retry would end after the request deadline. Every distributor entry
   reports `rate_limit_waited_ms` (0 when it did not wait, also on a cache hit). A requested distributor without a configured client reports `not_configured` with
   cache status `not_applicable`; with no `distributors` given only configured ones are searched.

LCSC parts are read from the SQLite file and are **not** written to `cached_parts`/`cached_searches`
(the SQLite database is the cache). Only Mouser and TME use the Postgres cache.

**Distributor phrasing** (`search.DistributorPhraser`, connector queries only). A query that `QueryParser` recognises as
a connector request (section 3.4) is not sent verbatim: each distributor gets the wording its search understands, as
the primary query of step 2 (and of a `PARTIAL` extension). Every other query is sent as written. The cache key stays
the user's normalised query; the phrase is a pure function of the parsed query, so a cache hit reports it again.

| Distributor | Rules | Example for `90 degree dupont style female pin header 90 degree THT pins 6 position` |
|---|---|---|
| LCSC | quoted category phrase (`"Female Header"`, `"Pin Header"`, `"Header"`, `"IDC Header"`, `"IC Socket"`, `"Terminal Block"`, `"Wire To Board"` + series, `"USB Connectors"` + `Type-C`/`Micro-B`, `"FPC"`, `RJ45`, `"D-Sub"` + gender, `"DC Power"`); positions `RxNP` when the rows are known, else `NP`; `"Right Angle"`; pitch (`2.54mm`, also when implied); mounting `"Through Hole"` (not for right angle: JLCPCB writes only "Right Angle" there) or `"Surface Mount"` (+ `Vertical`); up to 2 free-text keywords last | `"Female Header" 6P "Right Angle" 2.54mm` |
| TME | TME description wording, most informative first, at most 40 characters (a token that does not fit is skipped): type (`pin strips female`, `pin header male`, `IDC male`, `terminal block`, `wire-board XH`, `USB C socket`, `FFC/FPC`, `RJ45 socket`, `D-Sub female`, `DC supply socket`), positions (`6`, or `2x3` for several rows), orientation (`angled`/`straight`; `horizontal`/`vertical` for USB and FFC/FPC), the pitch only when written (not implied), keywords | `pin strips female 6 angled` |
| Mouser | type (`female header`, `male header`, `header`, `shrouded header`, `terminal block`, `JST XH`, `USB type C receptacle`, `micro USB receptacle`, `FPC connector`, `RJ45 jack`, `D-Sub female`, `DC power jack`), positions as `N pos`, pitch only when written, orientation (`right angle`/`vertical`), mounting for non-header types, keywords. Verified live: `female header 6 pos right angle` finds 6-pin right-angle female headers, `... 6 position ...` matched 9 modular jacks | `female header 6 pos right angle` |

Each distributor entry reports the phrase as `distributor_query` (null when the user's text was sent verbatim);
`fallback_query` keeps its meaning (the shorter phrase sent after the first one found nothing).

### 3.3 Ranking

```java
public interface PartRanker {
    /** Scores candidates for one query. Returns a raw score per candidate key (distributor + ":" +
     *  distributorPartNumber), higher = more relevant; only the order matters. Throws RankingException
     *  (reason TIMEOUT, UNAVAILABLE, BUSY, FAILED, DISABLED) on failure/timeout; the caller falls back. */
    Map<String, Double> rank(ParsedQuery query, List<Part> candidates, Duration budget) throws RankingException;
    String name();   // "cross-encoder"
}
```

The only implementation is `CrossEncoderPartRanker` (section 3.5). The decision behind it is
`docs/research/ranking-evaluation-2026-10-05.md` (section 9): the deterministic ranker stays the primary signal, the
cross-encoder is a second signal in a 50/50 **rank** blend, the deterministic order is the fallback.

`RankingService.rank(ParsedQuery, Map<Distributor, List<Part>> fetched, Duration budget)`:

1. `DeterministicRanker.score(ParsedQuery, Part)` for every part (section 3.4). Sort per distributor.
2. Candidate set: the deterministic top `kina.ranking.cross-encoder.max-candidates` (default 40) across the requested
   distributors, shared proportionally (at least 5 per distributor that has results). Candidates whose raw model score
   is already in `RankingScoreCache` (in-memory, key = normalised query key + part key, TTL
   `kina.ranking.score-cache-ttl` default 1h, max 50 000 entries, cleared when a model is loaded) are not re-scored.
3. Call `PartRanker.rank` for the rest with the remaining budget (`kina.ranking.timeout`, default **5s**; 40 candidates
   take about 0.1 to 0.35 s, so the 20 s requirement holds with a wide margin). The ranker itself bounds concurrency
   (`max-concurrent`, default 2); the wait for a slot counts inside the budget.
4. **Rank-normalise both signals within the candidate set** (dense rank of the score rounded to 4 decimals; best 1.0,
   worst 0.0, linear in between; ties share a value; a single distinct value maps to 1.0):
   `final = (1 - w) * ranknorm(deterministic) + w * ranknorm(crossEncoder)`, `w = kina.ranking.cross-encoder.weight`
   (default **0.5**). Rank-normalising the deterministic side matters: its good candidates sit in a narrow band
   (about 0.7 to 0.95), so adding a rank-normalised model score to the raw deterministic score lets the model reshuffle
   the top (study, section 7). Parts outside the candidate set follow the candidates of their distributor, ordered by
   deterministic score, with score `lowest candidate score of the distributor * deterministic`. Ties: deterministic
   score, then stock desc, then lowest unit price asc. Response `ranking: "blended"`.
5. Whenever the cross-encoder cannot score, order by deterministic score (score = deterministic score) and report
   `RankingMode.FALLBACK` (`ranking: "fallback"`) with a `rankingNote`:
   `"cross-encoder disabled"` (`kina.ranking.cross-encoder.enabled=false`), `"cross-encoder model not loaded yet"`
   (still downloading/loading, or the download failed), `"cross-encoder timeout after 5s"` (names the ranking budget),
   `"cross-encoder timeout: budget exhausted"` (no budget left before the call), `"cross-encoder busy: no free slot
   within 5s"`, `"cross-encoder failed: <reason>"`. Fully cached candidate sets are blended even while the model is
   not loaded.

Batch search fetches the queries in parallel (at most 4 queries at a time, to respect distributor rate limits),
then ranks each query independently through the same path, each with `min(kina.ranking.timeout, remaining batch
budget)`. The ranking phase has `kina.ranking.batch-timeout` (default 60s); queries reached after it expired are ranked
with a zero budget (fallback ranking, `ranking_note` `"batch ranking budget of 60s exhausted"`; scores already in
`RankingScoreCache` are still used).

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
  metric case codes (`2012` etc. only when a family keyword says MLCC/resistor), mounting (`SMD SMT THT through-hole`,
  JLCPCB `插件` = THT, `卧贴` = SMD)
- connector attributes (`search.ConnectorRecognizer`, `ParsedQuery.Connector`, see below)
- remaining tokens are free text keywords.

**Connectors.** A query is a connector request (`ParsedQuery.isConnector()`, family `connector`) when it contains
connector words (a type below, `connector`, `header`, `socket`, `plug`, `jack`, `receptacle`, `dupont`, `JST`...) and
neither an IC/discrete package (`SOIC-8`, `LQFP-48`, `SOT-23-6`; allowed for IC sockets) nor another explicitly named
family (op amp, LDO, MCU...). So `8 pin SOIC op amp`, `LQFP-48 MCU` and `SOT-23-6 LDO` are not connectors. The
recognised spans are removed before the generic recognisers run, so `90 degree`, `degree(s)`, `style`, `dupont`,
`pins`, `position` do not remain as keywords. `ParsedQuery.Connector` (all fields nullable):

| Field | Recognised wording |
|---|---|
| `type` | `pin header` (male: `pin header`, `male header`, `pin strips`, `terminal strip`), `female header` (`female header`, `socket strip`, `pin socket`, `socket header`, `female pin header`, `dupont female`), `header` (gender unknown: `header`, `dupont`, Mouser "Headers & Wire Housings"), `box header` (`box header`, `shrouded header`, `IDC header`, TME `IDC; male`), `idc socket`, `ic socket` (`IC socket`, `DIP socket`, `IC / Transistor Socket`), `terminal block` (`terminal block`, `screw terminal`), `wire-to-board` (`JST`, `wire to board`, `wire-board`, with series `XH PH GH SH ZH EH VH`), `usb-c`, `micro usb`, `usb`, `fpc` (`FPC`, `FFC`), `rj45` (`RJ45`, `8P8C`, `modular jack`), `d-sub` (`D-sub`, `DB9`, `DE-9`), `barrel jack` (`barrel jack`, `DC jack`, `DC supply`), else `connector` |
| `gender` | `male`/`female` (explicit words win, earliest first; also Mouser `FEM`, `FML`); weak: `receptacle`, `RECEP`, `RCPT`, `socket`, `SKT`, `jack` = female, `plug` = male; else implied by the type (pin/box header male, female header/IC socket female). The type is refined by the gender: `female` + `pin header` = `female header` |
| `positions` | `6-position`, `6 position(s)`, `6 pos`, `6 pin(s)`, `6-pin`, `6P`, `6 way`, `6 circuits`, `6 CKT`, `6 contacts`, `PIN: 6`, `Number of pins: 6`; rows x pins gives the total (`2x3` = 6) |
| `rows` | `1x6`, `2x3`, `1*6`, `2×20`, `single row`, `dual row`, `2 row`, Mouser `SIL`/`DIL` |
| `pitchMm` | `2.54mm`, `2.54 mm pitch`, `P=2.54mm` (LCSC), `0.1"`, `0.1 inch`, `100 mil`, `.100`, a bare `2.54`/`1.27`/`5.08`...; in descriptions only standard pitches count (0.5 ... 10.16 mm, so the `8.5mm` height is ignored); `pitchImplied` for `dupont` (2.54 mm) and JST series (XH 2.5, PH 2.0, GH 1.25, SH 1.0, ZH 1.5 mm) |
| `orientation` | `right angle` for `right angle(d)`, `RA`, `R/A`, `RT ANGL`, `90 degree(s)`, `90°`, `90*`, `angled`, `horizontal`, `HORIZ`, `side entry`, `弯插`; `vertical` for `vertical`, `straight`, `180°`, `top entry`, `直插`, and JLCPCB headers whose description says `插件` without a right-angle word |

`ParsedQuery` holds the original text, normalised key, the extracted constraints (typed, with SI
values normalised to base units as `double`), and the free-text tokens.

`ParametricExtractor.extract(Part) -> Map<String,String>` applies the same recognisers to the
part's description and attribute values, so parts from all three distributors expose comparable
`Capacitance`, `Resistance`, `Inductance`, `Voltage`, `Current`, `Power`, `Tolerance`, `Dielectric`,
`Package`, `Mounting` keys. Distributor attributes (TME parameters, Mouser ProductAttributes) take
precedence over description parsing.

Connector parts additionally get `Family=connector`, `ConnectorType`, `Series`, `Gender`, `Positions`, `Rows`, `Pitch`
(`2.54mm`) and `Orientation` (`right angle`/`vertical`). A part is a connector when its category names one
(`Connectors / Female Headers`, TME `Pin headers`, Mouser `Headers & Wire Housings`, `IC / Transistor Socket`), when it
has connector attributes, or when its description names a specific connector type; never when it has an IC package
(except IC sockets). Precedence: attributes (TME `Type of connector`, `Connector`, `Kind of connector` (gender),
`Number of pins`, `Connector pinout layout` (`1x6`), `Contacts pitch`, `Spatial orientation`, `Electrical mounting`,
`Manufacturer series`, verified on the live API; Mouser `Number of Positions`, `Number of Rows`, `Pitch`, `Gender`,
`Contact Gender`, `Mounting Angle`, `Orientation`), then the description with the package field (LCSC
`1x6P 2.54mm ... Right Angle 弯插,P=2.54mm`, package `Push-Pull,P=2.54mm`; TME `pin strips; socket; female; PIN: 6;
THT; angled 90°`; Mouser `6P RT ANGL PCB RECEP`, `10 POS 2.54MM RA Female Receptacle`), then the last category segment
(`Female Headers` gives `Gender=female`, `ConnectorType=female header`). An explicit gender in the description beats the
category (TME files female sockets under "Pin headers"; Samtec "socket; male" is male). Mounting falls back to the
JLCPCB words `插件`/`Plugin` (THT) and `卧贴` (SMD).

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

For connector queries (`ParsedQuery.isConnector()`) the primary value signal is replaced by connector signals
(`DeterministicRanker.connectorScore`; constants next to the others). Each applies only when both the query and the
part know the attribute; an unknown attribute scores 0, never a penalty. Package, dielectric, ratings (e.g. `3A`),
family, lexical and tie-break signals stay as above.

| Signal | Weight | Rule |
|---|---|---|
| positions (`W_POSITIONS`) | 0.30 | same total -> +0.30; different -> -0.30 |
| rows (`W_ROWS`) | -0.10 | both known and different (`1x6` vs `2x3`: same positions, -0.10); same -> 0 |
| rows unspecified (`W_ROWS_UNSPECIFIED`) | -0.08 | header query with positions but no rows, part with more than one row (a "6 position header" is usually 1x6) |
| gender (`W_GENDER`) | 0.20 | same -> +0.20; different -> -0.20 |
| orientation (`W_ORIENTATION`) | 0.15 | right angle vs vertical: same -> +0.15; different -> -0.15 |
| pitch (`W_PITCH`) | 0.15 | within 0.03 mm (2.54 mm == 0.1") -> +0.15; different -> -0.15 |
| connector type (`W_CONNECTOR_TYPE`) | 0.10 | same -> +0.10; different (female header vs pin header vs IC socket) -> -0.10; a gender-less `header` is compatible with pin/female/box headers and `connector`/`usb` with anything (0) |
| mounting (`W_CONNECTOR_MOUNTING`) | 0.05 | query THT/SMD vs part mounting: same -> +0.05; different -> -0.05 |

With these weights, for `female header 1x6 right angle 2.54mm`: 1x6 female right angle (0.90 + family + tie-break) >
2x3 female right angle (-0.10) > 1x6 female straight (-0.30) > 1x10 female right angle (-0.60) = 1x6 male right angle
(-0.60, gender and type) > unrelated parts.

### 3.5 Cross-encoder ranker (`search/ce`)

**Model.** `cross-encoder/ms-marco-MiniLM-L6-v2` (BERT, 6 layers, 22.7 M parameters, Apache-2.0), run inside the KINA
JVM with ONNX Runtime for Java (CPU). No sidecar, no HTTP hop, part data never leaves the process.

**Measured (study `docs/research/ranking-evaluation-2026-10-05.md`, 32 labelled queries, 1259 candidates, NDCG@10):**

| method | NDCG@10 | ms per query, 40 candidates (8 threads, median / max) |
|---|---|---|
| deterministic ranker alone | 0.892 (0.898 with connector-aware parsing) | < 5 |
| cross-encoder alone, fp32 / int8 | 0.874 / 0.877 | 165 / 335; int8 85 / 200 |
| rank blend 0.5 det / 0.5 cross-encoder (shipped) | **0.913** (+0.021, 95% CI +0.003 to +0.041) | |
| same, cross-encoder fine-tuned on synthetic rubric labels + real labels (2-fold) | 0.917 | |
| previous production blend (det + 0.2 x rank-normalised decision-model score, removed) | 0.823 | 2400 |

Largest gains on discretes, ICs and connectors (words the parametric parser does not model: RS-485 vs CAN, `1x4P`,
unidirectional); passives and vague requests are not hurt. The Java implementation reproduces the study exactly
(`CrossEncoderEvaluationTest`, fp32: cross-encoder alone 0.874 with identical per-category numbers).

**Pair text.** Query = the user's text as received (`ParsedQuery.originalText()`). Document = the study's
`candidate_text` (`scripts/research/common.py`): non-empty parts joined with `" | "`:
`manufacturer | mpn | description | category | "package " + packageName | "k: v; k: v"` where the attributes are the
distributor attributes followed by the comparable attributes `ParametricExtractor.enrich` adds. Example:
`YAGEO | CC0805KKX7R7BB106 | 10uF 16V X7R ±10% | Capacitors / Multilayer Ceramic Capacitors MLCC - SMD/SMT | package 0805 | Capacitance: 10uF; Voltage: 16V; Tolerance: 10%; Dielectric: X7R; Package: 0805; Mounting: SMD; Family: capacitor`.

**Tokenizer (`BertTokenizer`).** BERT uncased WordPiece in Java, token-for-token equal to the Hugging Face fast
tokenizer of the model (`BertTokenizerTest`, 143 fixtures from `scripts/ranking/make_tokenizer_fixtures.py` with µ, Ω,
℃, ±, Chinese LCSC descriptions, `1x6P`, `2.54mm`, accents, full-width forms, control characters, literal special
tokens): clean text (drop control/format characters, whitespace to space), spaces around CJK ideographs, NFD and
accent removal, lower-case; split on whitespace and punctuation; greedy longest-match WordPiece with `##`, `[UNK]` for
unknown or over-long (> 100 characters) words. Pairs are `[CLS] query [SEP] document [SEP]` with `token_type_ids`
0/1 and an all-ones `attention_mask`, truncated "longest first" (the document loses tokens first) to
`max-sequence-length` (default 256; the longest pair of the evaluation set is 252 tokens, so this equals the study's
512).

**Inference (`CrossEncoderPartRanker`, `OnnxScoringBackend`).** One shared `OrtSession` (`run` is thread-safe),
`intraOpNumThreads = kina.ranking.cross-encoder.threads` (0 = `min(4, availableProcessors)`), inter-op 1, all graph
optimisations. Pairs are scored in batches of `batch-size` (16), padded to the longest pair of the batch; the single
logit per pair is the raw score. At most `max-concurrent` (2) calls run at once; callers wait for a slot within their
budget. The budget is checked between batches and an inference running past it is terminated
(`RunOptions.setTerminate`). Health: `isReady()`, `status()` (variant, ONNX file, model dir, revision, threads, last
error, average latency); one warm-up pair runs after loading. Each call logs at DEBUG
`cross-encoder scored N candidates in M ms (T threads, <file>)`.

**Model files (`CrossEncoderModel`, `ModelDownloader`, `ModelLayout`).** Directory `kina.ranking.cross-encoder.model-dir`
(env `KINA_CROSS_ENCODER_MODEL_DIR`; default `${KINA_JLCPCB_DATA_DIR}/../cross-encoder`, i.e. `/data/cross-encoder` in
Docker and `./data/cross-encoder` locally), layout of the Hugging Face repository:

```
vocab.txt  config.json  tokenizer_config.json  model.json (KINA manifest)
onnx/model.onnx                     fp32, 91 MB
onnx/model_qint8_avx512_vnni.onnx   int8 (signed), 23 MB; also used on AVX-VNNI and ARM CPUs
onnx/model_quint8_avx2.onnx         int8 (unsigned), 23 MB; AVX2 CPUs without VNNI
```

- Variant `kina.ranking.cross-encoder.variant` = `int8` (default) or `fp32`. int8 tries the file matching the CPU
  (`/proc/cpuinfo` flags), then the other int8 file, then fp32; a file that is missing at the source (404) or fails to
  create a session or run the warm-up is skipped. x86 without AVX2 uses fp32. Measured in Java on the reference host
  (AVX-VNNI, 4 threads, 40 candidates): signed int8 0.878 alone / 0.913 blended, median 125 ms, max 256 ms; unsigned
  int8 0.867 / 0.911, same speed; fp32 0.874 / 0.913, median 256 ms, max 476 ms.
- Startup never blocks: after `ApplicationReadyEvent` a virtual thread downloads missing files from
  `kina.ranking.cross-encoder.model-url` (default `https://huggingface.co/cross-encoder/ms-marco-MiniLM-L6-v2/resolve/main/`),
  loads the tokenizer and the session and warms it up. Each file is streamed to `<dir>/tmp/*.part`, its size checked
  against `X-Linked-Size` or `Content-Length`, its SHA-256 against `X-Linked-Etag` (LFS files), then moved atomically
  into place. `model.json` records `source`, `repo`, `revision` (`X-Repo-Commit`), `variant`, `onnx_file`,
  `downloaded_at` and per-file size and SHA-256. Timeouts: connect 10 s, `download-timeout` (10 min) per file.
- Files already present are used as they are (pre-provisioned or offline directory). `auto-download: false` never
  downloads (tests).
- `model-url` may point to any HTTP(S) directory with the same layout, or to a local directory (absolute path or
  `file:` URI), which is used in place (e.g. a fine-tuned model from `scripts/ranking/finetune_cross_encoder.sh`).
- A failed attempt is logged once at WARN (again only when the reason changes) and retried every `check-interval`
  (1h); until then searches report `ranking: "fallback"`, note `"cross-encoder model not loaded yet"`.
- Memory: the int8 session adds roughly 100 to 200 MB of native memory to the JVM process (fp32 about 150 to 250 MB).

**Evaluation.** `CrossEncoderEvaluationTest` (runs only when `KINA_CROSS_ENCODER_TEST_MODEL_DIR` names a model
directory; `KINA_CROSS_ENCODER_TEST_VARIANT` = int8|fp32; `KINA_CROSS_ENCODER_TEST_SCORES_DIR` writes score files
for `scripts/research/evaluate.py`) ranks every query of `docs/research/data/ranking-eval.jsonl` through the
production `RankingService` and asserts blended NDCG@10 >= the deterministic ranker's and >= 0.90.

### 3.6 Rate limiting

Distributor APIs that answer with a rate limit are waited for and retried instead of failing the request at once,
within a hard per-request cap.

**Request deadline.** Every incoming request gets one deadline = start + `kina.search.max-request-duration`
(default `2m`): one `search_parts` / `GET /api/v1/parts/search`, one whole `search_parts_batch` /
`POST .../search/batch` (all queries share it), one `get_part` / `GET /api/v1/parts/{distributor}/{partNumber}`. It is a
`ro.alacrity.kina.distributor.Deadline` (`System.nanoTime()`-based); each distributor fetch gets its own `fork()` (same
deadline, separate wait accounting) and passes it to `DistributorClient.search/getPart(..., Deadline)`.

**Budgets.** `kina.search.distributor-timeout` (12 s) still bounds a fetch's *active* work. The distributor deadline is
`min(request deadline, fetch start + distributor-timeout + rate-limit time waited)`: the client records each wait on
its `Deadline` *before* sleeping, and the orchestrator (`DistributorBudget.await`) keeps waiting while a recorded wait
moves the deadline. Overlapping waits of parallel calls of one fetch (TME data/parameters/files) count once. The
ranking budgets (`kina.ranking.timeout` 18 s, `batch-timeout` 60 s) are unchanged and come after the fetch phase, so a
search that waited the full two minutes can still spend up to the ranking budget afterwards.

**Retry policy (`RateLimitRetry`, one per distributor client, around every Mouser and TME HTTP call: Mouser keyword
and part-number search; TME `/auth/token`, `/products/search`, `/products`, `/products/data`, `/products/parameters`,
`/products/files`).**

- Triggers: HTTP 429; HTTP 502, 503 and 504 **only with** a `Retry-After` header (without it they stay `unavailable`,
  an outage is not waited for); Mouser's in-body error `Errors[].Code == "TooManyRequests"` on HTTP 200. TME documents no
  throttling error code (`docs/vendor/tme-api-v2-openapi.json` has none), so TME triggers on the statuses only.
- Wait: `Retry-After` as delta-seconds or HTTP-date (RFC 1123, converted with the wall clock), at least 1 s; without the
  header exponential backoff 2, 4, 8, 16, 30, 30, ... s (cap 30 s), each multiplied by a uniform jitter factor in
  [0.8, 1.2] and never below 1 s.
- Retry while `now + wait <= request deadline`; otherwise fail with `RATE_LIMITED`, message e.g.
  `rate limited by Mouser (/search/keyword returned HTTP 429); waited 84 s, next retry in 30 s would exceed the request deadline`;
  `DistributorException.rateLimitWaitedMillis()` carries the waited time. The retry count is bounded only by the
  deadline.
- Sleeps happen on the calling virtual thread; an interrupt (the orchestrator cancelling a timed-out fetch) stops the
  call with `RATE_LIMITED` and the interrupt flag restored.
- Calls without a deadline (`search(q, o, l)`, `getPart(pn)`) use `Deadline.immediate()`: no waiting, `RATE_LIMITED` at
  once (the pre-3.6 behaviour).

**Shared cool-down (`DistributorCooldown`, in memory, per distributor, thread-safe).** Every rate-limit signal records
`cooldownUntil = now + wait` (extended, never shortened). Before every attempt a call first waits until `cooldownUntil`
when that fits its own deadline, otherwise it fails fast with `RATE_LIMITED` without sending a request. A successful
call clears the cool-down (unless another call recorded a new rate limit meanwhile). This keeps concurrent requests
(and the parallel TME enrichment calls) from hammering a throttled API. One WARN is logged per cool-down episode
(`Mouser rate limited (/search/keyword returned HTTP 429, Retry-After 30 s); cooling down for 30 s`); retries within
it, and new rate limits less than 60 s after it ended, log at DEBUG. API keys and tokens never appear in these
messages (Mouser messages are masked, TME messages contain only paths).

**TME token.** The token request is retried like any other call; a thread waiting for the token lock while another
thread's token request sleeps on a rate limit waits at most until its own deadline (at least 15 s), then fails with
`RATE_LIMITED`.

**Reporting.** `DistributorResult.rate_limit_waited_ms` (snake_case, always present, 0 when none) is the time that
distributor's fetch spent waiting on rate limits (including the cool-down wait). When the budget runs out the entry
reports `error: "rate_limited"` and keeps the parts fetched before (earlier pages; the cached list of a `PARTIAL`
extension). A fetch whose active work exceeds its budget still reports `timeout`.

**Server-side timeouts.** Requests can now take up to two minutes plus ranking. The stateless MCP transport
(`WebMvcStatelessServerTransport`) blocks on the tool call without a timeout; `spring.ai.mcp.server.request-timeout` is
set to `3m` anyway (it is not applied in `STATELESS` mode by spring-ai 2.0.1, but would be by a session-based
protocol). The REST controllers are synchronous (no `spring.mvc.async.request-timeout` involved) and Tomcat's
`connection-timeout` only limits reading the request, not processing it. Clients and reverse proxies in front of KINA
need a read timeout above two minutes (plus the ranking budget) to see such answers.

## 4. MCP tools

Server name `kina`, version from the build. Tools (JSON Schema generated from the method
parameters; descriptions are read by the LLM, keep them precise):

| Tool | Parameters | Returns |
|---|---|---|
| `search_parts` | `query` (string, required), `max_results` (int 1..50, default 10, per distributor), `distributors` (array of `LCSC\|TME\|MOUSER`, default all configured), `bypass_cache` (bool, default false: skip cache lookup, still refresh the cache) | `SearchResponse` |
| `search_parts_batch` | `queries` (array of `{query, max_results}`, 1..20), `distributors`, `bypass_cache` | `{ "results": [SearchResponse...] }` |
| `get_part` | `distributor` (case-insensitive), `part_number`, `bypass_cache` | `PartLookupResponse` `{found, distributor, part_number, cache, error, part}`; unknown/out-of-stock parts and distributor failures return `found: false` (with `error` for failures) instead of a tool error |
| `list_distributors` | none | `DistributorStatusResponse`: per distributor `configured`, `available`, `detail` (LCSC: JLCPCB file, part count, source date, download state), `uses_cache`, `cached_parts`, `max_results_per_search`, `jlcpcb{...}` (LCSC); `cache{ttl, parts, fresh_parts, searches, oldest_fetch}`; `ranking{mode, cross_encoder_enabled, ready, model, model_variant, model_revision, model_dir, threads, avg_latency_ms, last_error, max_candidates, weight, timeout}`. Never calls the Mouser/TME APIs |
| `ping` | none | `{"status":"ok","version":"<build version>"}` (wiring/health check, already implemented) |

`SearchResponse` JSON (snake_case):

```json
{
  "query": "10uF X7R 0805",
  "parsed": {"family": "capacitor", "capacitance": "10uF", "dielectric": "X7R", "package": "0805", "keywords": []},
  "ranking": "blended",
  "ranking_note": null,
  "distributors": [
    {
      "distributor": "MOUSER",
      "total_results": 113,
      "fetched": 50,
      "returned": 10,
      "cache": "hit",
      "error": null,
      "fallback_query": null,
      "rate_limit_waited_ms": 0,
      "distributor_query": null,
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
`distributor_query` is the distributor-specific phrase KINA sent instead of the user's text (connector queries,
section 3.2 "Distributor phrasing"), null when the text was sent verbatim. `fallback_query` is the shorter phrase actually
sent to the distributor when the first query found nothing (section 3.2), otherwise null. For a connector query
`parsed` also carries `connector`, e.g. `{"type": "female header", "gender": "female", "positions": 6, "rows": 1,
"pitch": "2.54mm", "orientation": "right angle"}` (unknown attributes omitted; absent for other queries). `rate_limit_waited_ms` is how long the distributor's fetch waited on rate limits
(section 3.6), 0 normally. The `search_parts` tool description tells the LLM that a rate-limited distributor can make
the call take up to two minutes.
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
updated at most once per minute per token. Revocation sets `revoked_at` and, in the same transaction, revokes every
`oauth_refresh_tokens` row whose `access_token_id` is that token (`AccessTokenRepository.revoke*`): a user revoking an
OAuth-issued token in the web UI, or a client revoking its access token at `/oauth/revoke`, must not leave a refresh
token that mints a new one. The same table and service
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
| `POST /oauth/revoke` (RFC 7009) | Revokes an access or refresh token belonging to the authenticated client; always 200. Revoking a refresh token also revokes its current access token; revoking an access token also revokes the refresh token issued with it. |

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
-- V3__cached_search_fallback_query.sql: core phrase searched instead of the query (phrase fallback), NULL otherwise
ALTER TABLE cached_searches ADD COLUMN fallback_query TEXT;

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
- Errors: HTTP 429, HTTP 502/503/504 with `Retry-After`, or `Errors[].Code == "TooManyRequests"` (HTTP 200) are rate
  limits: retried within the request deadline (section 3.6, Mouser allows 30 calls/min), `RATE_LIMITED` when it runs
  out; other non-empty `Errors` -> `BAD_RESPONSE`; other 5xx -> `UNAVAILABLE`.

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
- Errors: `{"code":"E_INPUT_PARAMS_VALIDATION_ERROR",...}` -> `BAD_RESPONSE`; 401 -> refresh token once and retry;
  429 (and 502/503/504 with `Retry-After`) on any endpoint, the token request included -> retried within the request
  deadline (section 3.6), `RATE_LIMITED` when it runs out. The OpenAPI document defines no throttling `E_*` code.

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
  `refresh-after`. A file that exists without a `jlcpcb_database` row (pre-seeded or restored volume, fresh Postgres) is
  **adopted**: validated (`SELECT count(*) FROM parts`, about 20 s for the full 7.1 M-part file), registered with
  `downloaded_at` = the file's mtime, logged as `Adopted existing JLCPCB database ... (N parts, modified ...)`; an
  unusable file is re-downloaded.
  The data directory must be writable by uid 10001 for later refreshes (the image creates `/data/jlcpcb` owned by
  `kina`, so a new named volume inherits that; a file copied in as root only needs to be readable if the directory is
  writable). A scheduled task (`check-interval`) re-checks. While no database is available the LCSC client reports
  `UNAVAILABLE` ("JLCPCB database not downloaded yet"); searches on other distributors proceed. Swapping the file takes a
  write lock; queries take read locks; SQLite is opened read-only (`jdbc:sqlite:<path>?mode=ro`, `open_mode=1`).
- Schema (SQLite): `parts` is an **FTS5 virtual table with the trigram tokenizer** and columns
  `"LCSC Part", "First Category", "Second Category", "MFR.Part", "Package", "Solder Joint", "Manufacturer", "Library Type",
  "Description", "Datasheet", "Price", "Stock"` (`Solder Joint`, `Datasheet`, `Price`, `Stock` are unindexed). Also tables
  `categories("First Category","Second Category")`, `meta(filename,size,partcount,date,last_update)`, `mapping`.
- Query (`JlcpcbQuery`): tokens of 3+ characters go into `parts MATCH '"tok1" AND "tok2" ...'` (escape embedded double
  quotes by doubling); shorter tokens (`1k`, `5%`, `6P`, `XH`) become `"Description" LIKE '%tok%'` clauses; values,
  positions and pitches must also occur at a number boundary (`kina_value`: `10k` not in `510kΩ`, `6P` not in `16P`;
  positions also in `MFR.Part`, pitches also in `Package`); always `CAST("Stock" AS INTEGER) > 0`;
  `ORDER BY rank, CAST("Stock" AS INTEGER) DESC LIMIT <window>`. `totalResults` = `COUNT(*)` of the same predicate.
  Part lookup: `WHERE "LCSC Part" = ?`.
- Vocabulary mapping before querying: a double-quoted phrase is one term. Connector category phrases (quoted, or the
  words `female header(s)`, `female pin header`, `pin header(s)`, `terminal block`, `screw terminal`, `IC socket`) are
  matched as a column filter, e.g. `"Second Category" : "Female Header"` (matches `Female Headers` and
  `Pin Header & Female Header`), `"Second Category" : ("IC Socket" OR "Transistor Socket")`. Mounting:
  `THT`, `PTH`, `through hole` -> `("Through Hole" OR "Plugin" OR "THT")`; `SMD`, `SMT`, `surface mount` ->
  `("SMD" OR "SMT" OR "Surface Mount")`. Orientation: `right angle`, `90°`, `90 degree`, `angled`, `horizontal` ->
  `"Right Angle"` (a THT term is then dropped: JLCPCB right-angle THT headers never say "Through Hole");
  `vertical`, `straight` -> `("Vertical" OR "Straight")`. Positions: `1x6`, `2*3`, `1x6P` -> `1x6P`; `6P` (upper-case),
  `6 pin`, `6-pos`, `6 position` -> `6P` (a lower-case `22p` stays a capacitance). `2.54 mm` -> `2.54mm`. Stop words
  include `dupont`, `style`, `degree(s)`, `pins`, `position(s)`. The 2-character CJK words (`弯插`, `插件`) cannot be
  trigram-matched and are only used by the extractor.
- Relaxation (`JlcpcbSqliteSearch`): `ALL` (every term) first. When it has no in-stock match, `RELAXED`: (1) remove
  the dead terms, i.e. terms that occur nowhere in the database (one `MATCH ... LIMIT 1` probe per matchable term,
  stock ignored, so it stops at the first hit; e.g. `dupont`, misspellings), and retry; (2) drop the least informative
  remaining term and retry, one term at a time, while at least 2 terms remain. Drop order by kind: free-text keyword,
  mounting, orientation, pitch, package, dielectric, value, positions, family word, category; within a kind the last
  term of the query first. A step whose predicate has no MATCH (LIKE-only, a full scan) is skipped. A step whose
  terms are exactly the parametric terms is reported as `PARAMETRIC`. Then `PARAMETRIC` (values, packages,
  dielectrics, family and connector terms) when not tried yet, then `ANY` (every matchable term OR-ed, no
  value-boundary check, BM25 order). The first step with a non-zero count defines both `totalResults` and the page,
  so paging is stable; the result carries the mode and the dropped terms (debug log).
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
    request-timeout: 3m           # above kina.search.max-request-duration (unused by STATELESS, set defensively)
server:
  port: ${PORT:8080}
  forward-headers-strategy: framework
management.endpoints.web.exposure.include: health,info
kina:
  public-base-url: ${KINA_PUBLIC_BASE_URL:}
  security.mode: ${KINA_MODE:dev}
  tokens.validity: 30d
  oauth.refresh-token-validity: 90d
  cache:
    ttl: 5d
    empty-result-ttl: 1h       # cached searches with no in-stock part
  search:
    candidate-window: 40
    default-max-results: 10
    max-max-results: 50
    distributor-timeout: 12s     # active work per distributor fetch; rate-limit waits do not count
    max-request-duration: 2m     # hard cap per request (search, whole batch, get_part) incl. rate-limit waits
  ranking:
    timeout: 5s                  # per query (deterministic + cross-encoder)
    batch-timeout: 60s
    score-cache-ttl: 1h
    cross-encoder:               # section 3.5
      enabled: ${KINA_CROSS_ENCODER_ENABLED:true}
      variant: ${KINA_CROSS_ENCODER_VARIANT:int8}          # int8 | fp32
      model-dir: "${KINA_CROSS_ENCODER_MODEL_DIR:${KINA_JLCPCB_DATA_DIR:./data/jlcpcb}/../cross-encoder}"
      model-url: "${KINA_CROSS_ENCODER_MODEL_URL:https://huggingface.co/cross-encoder/ms-marco-MiniLM-L6-v2/resolve/main/}"
      threads: ${KINA_CROSS_ENCODER_THREADS:0}              # 0 = min(4, available processors)
      max-concurrent: 2
      batch-size: 16
      max-sequence-length: 256
      max-candidates: 40
      weight: 0.5
      check-interval: 1h
      download-timeout: 10m
      auto-download: true        # false in src/test/resources/config/application.yml
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
  then copy `src`, `package -DskipTests`); stage 2 `eclipse-temurin:21-jre`, non-root user `kina` (uid 10001), `/data`
  volume, `HEALTHCHECK` on `/actuator/health` (curl), `ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED",
  "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/kina.jar"]` (native access for sqlite-jdbc;
  heap sized from the container memory limit; extra flags via `JAVA_TOOL_OPTIONS`). The ONNX Runtime jar bundles its
  native library (linux-x64/aarch64), extracted to the temp directory at first use; it loads in `eclipse-temurin:21-jre`
  as the non-root user.
- `compose.yaml`: top-level `name: kina` (volumes are `kina_kina-data`, `kina_pgdata`, network `kina_default`; a
  pre-seeded JLCPCB file in `kina_kina-data` is adopted at startup, see 9.3, and pre-provisioned cross-encoder files in
  `/data/cross-encoder` are used without download, see 3.5). Services:
  - `kina`: build `.`, ports `${KINA_PORT:-8080}:8080`, `env_file: .env` (optional), environment for the datasource,
    `KINA_JLCPCB_DATA_DIR=/data/jlcpcb` and `KINA_CROSS_ENCODER_MODEL_DIR=/data/cross-encoder`, volume `kina-data:/data`,
    `mem_limit: ${KINA_MEM_LIMIT:-2g}` (heap = 75%; the ONNX Runtime session lives outside the heap),
    `depends_on: postgres (healthy)`.
  - `postgres`: `postgres:17-alpine`, `POSTGRES_DB/USER/PASSWORD=kina`, volume `pgdata`, healthcheck `pg_isready`.
- `.env.example` documenting every variable; `.env` is git-ignored.

## 12. Quality bar

- `./mvnw -q verify` must pass: unit tests for the query parser, parametric extractor, deterministic ranker, Mouser price
  parsing and mapping (JSON fixtures), TME mapping and token refresh (`MockRestServiceServer`), JLCPCB price parsing and
  SQLite search (build a tiny FTS5 database in the test), the cross-encoder tokenizer (fixtures from the Hugging Face
  tokenizer), ranker batching/timeouts, model download/verification and the rank blend with its fallbacks, PKCE, token hashing,
  OAuth metadata/register/authorize/token flow (MockMvc), bearer filter; Testcontainers-backed repository tests.
- No secrets in code, logs or test fixtures. Never log bearer tokens or API keys.
- Every external call has a timeout. Every distributor error is isolated per distributor.
