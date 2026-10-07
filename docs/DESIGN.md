# KINA design specification

KINA is an MCP server and HTTP API that lets an LLM (Claude) search electronic components across
three distributors: **LCSC** (served from the JLCPCB parts database), **TME** (API v2) and **Mouser**.
This document is the binding contract for the implementation. The original brief that started the project is no longer kept in the repository; where this document is silent, the code is the reference.

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
├── domain/          Distributor, Part, PriceBreak, ParsedQuery, SearchRequest, SearchResponse DTOs, RankingMode,
│                    ConstraintKind (@Relax, @Match, RelaxStrategy, MatchMode), PolicyFamily, PartFeatures, MatchContext
├── distributor/     DistributorClient, DistributorSearchPage, DistributorException, DistributorRegistry
│   ├── mouser/      MouserClient, MouserProperties, response records, MouserPartMapper
│   ├── tme/         TmeClient, TmeTokenManager, TmeProperties, response records, TmePartMapper
│   └── lcsc/        JlcpcbDatabaseManager (download/refresh), JlcpcbSqliteSearch, LcscClient, JlcpcbPriceParser
├── cache/           PartCacheRepository, SearchCacheRepository, CacheProperties
├── search/          QueryParser, ParametricExtractor, PassiveDetails, DeterministicRanker, ConstraintPolicy,
│   │                SearchMatchContext, PartRanker, RankingService,
│   │                RankingScoreCache, PartSearchService (the sequence), ParallelRetrieval, DistributorRetriever
│   │                (LcscRetriever, CachedDistributorRetriever), PageCollector, StockRefresher, ResponseAssembler,
│   │                CorePhrases, Prepared, Fetched, Progress, PartLookupService, DistributorStatusService
│   └── ce/          CrossEncoderPartRanker, CrossEncoderModel (download/load), ModelDownloader, ModelLayout,
│                    BertTokenizer, ScoringBackend, OnnxScoringBackend
├── mcp/             KinaMcpTools (@McpTool methods)
├── metrics/         KinaMetrics (facade), MetricsStore, MetricsPersistence, MetricsGauges, MetricsController (3.7)
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

/** A single in-stock offer from one distributor. Never construct one with stock <= 0, except the stock rule's
 *  exception below: a part requested explicitly by its part number, listed without stock, has stock 0. */
public record Part(
    Distributor distributor,
    String distributorPartNumber,      // LCSC "Cxxxxx", TME symbol, Mouser part number
    String manufacturer,
    String manufacturerPartNumber,
    String description,
    String category,                   // distributor category path, may be null
    String packageName,                // "0805", "SOT-23", may be null
    int stock,                         // quantity that ships now; > 0 (0 only for the exception of the stock rule)
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
    /** get_part and requested part numbers: FOUND (in-stock Part), OUT_OF_STOCK (listed without ships-now stock: its
     *  identity and, when the distributor gives the data, the listed Part with stock 0) or NOT_FOUND. The default
     *  wraps getPart (every miss is NOT_FOUND); Mouser, TME and LCSC override it. */
    default PartLookupResult lookup(String partNumber, Deadline deadline) { ... }
    /** Current stock and prices by part number, as few calls as possible (TME /products/data, 50 per call; Mouser
     *  part-number search, 10 per call); a number missing from the result is unknown. Default (LCSC): empty. */
    default Map<String, StockUpdate> refreshStock(List<String> partNumbers, Deadline deadline) { ... }
}

public record StockUpdate(int stock /* <= 0: sold out */, List<PriceBreak> prices /* empty: keep the cached ones */) {}

public record PartLookupResult(Status status, Part part, Identity identity) {
    public enum Status { FOUND, OUT_OF_STOCK, NOT_FOUND }
    public record Identity(String partNumber, String manufacturer, String mpn, String description) {}
}

/** outOfStock: records of the page dropped for having no ships-now stock (out_of_stock_matches);
 *  relaxed: constraints the distributor's own search dropped (LCSC relaxation), else empty;
 *  droppedKeywords: free-text terms it dropped, as written (part of query_terms_dropped). */
public record DistributorSearchPage(List<Part> parts, int totalResults, boolean hasMore, int outOfStock,
                                    List<String> relaxed, List<String> droppedKeywords) {}

public class DistributorException extends RuntimeException {
    public enum Kind { NOT_CONFIGURED, UNAVAILABLE, RATE_LIMITED, BAD_RESPONSE, TIMEOUT }
    public long rateLimitWaitedMillis();   // time the failed call waited on rate limits (0 if none)
    ...
}
```

Stock rule (all distributors): only quantity that ships now counts. "Expected", "on order",
"factory stock" and lead-time quantities are ignored. Parts with no ships-now stock are **never**
returned, cached or ranked, with one exception (user decision 2026-10-07): a part requested **explicitly by its part
number** (a part-number token in a search query, section 3.4 "Requested part numbers", or `get_part`) that the
distributor lists without ships-now stock is returned anyway, with `stock: 0`, its prices as listed and
`availability.status: "out_of_stock"` (note `Out of stock at MOUSER; shown because the part number was requested
explicitly.`). Only the distributor lookup builds such a part (`PartLookupResult.listed`, the mappers' `mapListed` /
`toListedPart`); search pages never carry it (`PageCollector` keeps dropping stock-0 records, which still count in
`out_of_stock_matches`). In a search it ranks after every part in stock (`RankingService.LISTED_TIER`), is never
counted in `fetched` or the exclusion counts, and takes the last returned place when it would fall outside
`max_results` (with at least 2 places). Its `cached_parts` row carries `in_stock = false`
(`PartCacheRepository.upsertListed`): the metadata is kept, the finders never serve it, so a keyword or parametric
search never sees it.

## 3. Search semantics

### 3.1 Request

```
SearchRequest(String query, int maxResults /*1..50, default 10*/, Set<Distributor> distributors /*default: all configured*/, boolean bypassCache,
              int quantity /*pieces to order, default 1*/, ResponseDetail detail /*compact (default) | full*/,
              boolean allowBelowSpec /*default false, section 3.4 "Below spec"*/)
BatchSearchRequest(List<SearchRequest> queries /*1..20, each with its own maxResults and quantity*/, Set<Distributor> distributors,
                   boolean bypassCache, ResponseDetail detail, boolean allowBelowSpec /*OR the query's own*/)
```

### 3.2 Per-distributor fetch with cache

Normalised query key: trim, collapse whitespace, lower-case, Unicode NFKC, `µ` -> `u`, `Ω` -> `ohm`.

**Cache model** (user decisions 2026-10-07). KINA is a search engine and aggregator: what a distributor says about a
component is kept, what it says about stock and price expires.

- **Metadata** of a cached part (everything except `stock`, `prices` and `availability`: identity, description,
  category, package, order rules, links, attributes, extra) is kept for the distributor's
  `kina.cache.metadata-retention` after it was last fetched in full (`cached_parts.metadata_fetched_at`). The default
  is `forever` for every distributor. The per-distributor value is the compliance switch: an operator who receives a
  notice from a distributor sets a duration (`MOUSER=3d`) and the purge applies it from then on. The research report
  `docs/research/cache-fill-2026-10-07.md` quotes an excerpt of Mouser's terms that restricts storing its content;
  the operator has decided to store until notified. TME's attribution notice and its rule that stored data is deleted
  when API access ends still apply (OPERATIONS.md "Distributor terms").
- **Search lists** (`cached_searches`) are fresh for `kina.cache.ttl` (default `3d`, was `5d` before 2026-10-07);
  anything younger is fresh. Exception: a list that is **empty** is fresh only for `kina.cache.empty-result-ttl`
  (default `1h`, the shorter of the two applies), so a transient distributor glitch or a newly stocked part is not
  hidden for days. An expired list is searched again normally; the parts it finds are upserted, which replaces the
  payload of a known part (the refetched metadata is current; attributes are not merged, so a value the extractor no
  longer derives cannot survive) and keeps `metadata_fetched_at` current.
- **Stock and prices** carry their own age, `cached_parts.stock_fetched_at` (= `Part.fetchedAt`, reported as
  `stock_as_of`). Older than `kina.cache.stock-ttl` (default `24h`): refreshed before the part is returned (step 5).
  When the refresh fails or the distributor is not configured, the part is returned as it is while its figures are at
  most `kina.cache.ttl` (3 days) old; beyond that it is returned with **`stale: true`** (covers stock and prices),
  `availability.status: "stale"` with a note (`Stock and price were last confirmed on 2026-10-01 (4 days ago) and could
  not be refreshed; check them at the distributor before ordering. Last known stock: 500.`), and it ranks below the
  fresh parts (`StockRefresher.demoteStale`: its score is lowered by `kina.cache.stale-rank-penalty`, default 1.0,
  reported at least 0, and it is placed before the first fresh part of its group, meeting the request or below spec,
  that scores lower; with 1.0 every stale part ends up after every fresh part of its group). A stale part that a
  refresh shows as sold out is dropped from the results; its row keeps the metadata with `in_stock = false` and is not
  served until a live fetch finds it in stock again (the stock rule: a part without ships-now stock is never returned).
  LCSC parts are never stale (the local JLCPCB database is the cache).
- **Expired list as a last resort**: when the live search of a query fails (first page, any `DistributorException`)
  and the query has an expired, non-empty cached list, its parts that are still cached in stock are served with cache
  status **`stale`** and the distributor's `error`; their stock is refreshed or marked stale like any cached part. A
  failing distributor without an expired list still reports an empty list with its `error`.
- **Purge** (`CacheMaintenance`, every 6 hours): search lists older than `2 x kina.cache.ttl`, and the parts of a
  distributor whose finite metadata retention expired (by `metadata_fetched_at`). With the default retention parts are
  never purged.

Fetch window per distributor: `window = max(maxResults, kina.search.candidate-window /*default 40*/)`
capped by `kina.distributors.<name>.max-results-per-search` (Mouser default 50 = one API call,
TME default 60, LCSC default 200).

Algorithm (`DistributorRetriever.retrieve`: `CachedDistributorRetriever` for Mouser and TME, `LcscRetriever` for
LCSC). `ParallelRetrieval` runs every requested distributor on its own virtual thread, bounded by
`kina.search.distributor-timeout` of active work; time spent waiting on a rate limit is added to that budget, but never
beyond the request deadline `kina.search.max-request-duration` (section 3.6):

"Meets the request" (`PageCollector.Check`, `RankingService.verdict`): no known attribute contradicts a
hard constraint (section 3.4 "Hard constraints": the primary value, the package except for inductors, crystals and
oscillators, mounting, technology, the type...) and no known rating is below the request. A part with a wrong primary
value is excluded like any other hard conflict (`Verdict.CONSTRAINT`; before 2026-10-07 it was returned; live, TME's
`ferrite 120ohm` returned ferrite cores specified at 25 MHz for a 100 MHz bead request). A part that does not state a
requested rating meets the request but is not **confirmed** (`Verdict.UNVERIFIED_RATING`).

1. `bypassCache == false`: read `cached_searches(distributor, query_key)`. When it is fresh, load its parts from
   `cached_parts` (in-stock rows of any stock age: old figures are refreshed or marked in step 5); if any part is
   missing or sold out, go to step 2 with `offset = 0` (`MISS`).
   - **Safeguard**: when the cached list is not empty but none of its parts could be returned (every one is excluded by
     a hard constraint or below spec; below-spec parts count as returnable with `allow_below_spec`), the hit is
     treated as a miss and the live search of step 2 runs (status `MISS`). A cached search never hides a live result.
   - the list is sufficient (`exhausted`, or `part_numbers.size >= window`, or `part_numbers.size >= maxResults`)
     -> status `HIT`, no distributor call. The `>= maxResults` clause keeps a repeat of the same query from
     re-querying a distributor whose single allowed page held fewer than `window` in-stock parts (Mouser quota).
     The hit reports the stored `fallback_query`, `out_of_stock_matches` and `constraints_relaxed` (a row from before
     V7 derives the last from the ladder step whose phrase equals `fallback_query`).
   - fresh but shorter than `maxResults` and not exhausted ("further querying is needed"): keep the cached list and
     fetch more pages starting at `offset = next_offset` (the raw distributor record offset stored with the list;
     the part count when unknown), append -> status `PARTIAL`.
   - missing or expired -> step 2 with `offset = 0`, status `MISS` (an expired list is kept as the last resort of
     the "Cache model" when the live search fails: status `stale`).
2. Call `DistributorClient.search(query, offset, limit)` page by page with `limit = maxPageSize()` (the first page is
   shortened so pages end on a page boundary) and `offset += limit`, until `window` in-stock parts are collected
   **and at least one of them is confirmed** (meets the request with every requested rating stated: a rating is never
   in the phrase, so the parts that satisfy it often sit on later pages), the distributor reports no more results
   (`hasMore == false`), `max-pages-per-search` (Mouser 1, TME 3, LCSC 1) is hit, or the next page would not finish
   before the distributor deadline (estimated from the previous page's active time, rate-limit waits excluded). Paging
   is driven by **raw record offsets**,
   not by the number of parts kept: distributors drop records without ships-now stock (live: TME reported 32 in-stock
   matches for "10uF X7R 0805" of which 26 were kept), so `part_numbers.size` is not a valid resume offset.
   LCSC is queried once with `limit = window`.
   **Relaxation ladder** (Mouser and TME only; LCSC relaxes inside its database search, section 9.3): while the
   fetch has found **nothing that meets the request** (no in-stock part, or every one excluded) and the deadline has
   not passed, the search is repeated with the next step of `DistributorPhraser.ladder`, stopping at the first one
   that finds a part that meets the request. Only **relaxable** constraints are loosened (`ConstraintPolicy`,
   section 3.4 "Hard constraints"), in the order declared on `ConstraintKind`
   (`@Relax(strategy = LADDER, order = n)`: dielectric, package, tolerance, orientation, tcr, esr, dcr; user decisions
   2026-10-06 and 2026-10-07). The parametric core has steps for **the dielectric, then the package (only for
   inductors, crystals and oscillators), then the tolerance**, the connector core leaves out the orientation; **a
   rating or a hard constraint never** (a rating is not in any phrase and the ranker excludes below-spec parts; a hard constraint stays in every
   parametric phrase and the ranker excludes parts that contradict it). Each step carries what it loosens
   (`DistributorPhraser.Relaxation.relaxed`):
   1. the phrase sent without rating values (normally already the case, see "Distributor phrasing"); loosens nothing;
   2. the minimal core, a rewording: connector queries the type words with the positions (TME, `pin strips female
      6`; it leaves the orientation out and reports `["orientation"]` when the request states one) or with the written
      pitch and the orientation (Mouser, `female header right angle`, `male header 2.54mm`; loosens nothing); USB the
      type and gender words (`["orientation"]` when stated); otherwise the parametric core, which loosens nothing (`CorePhrases.corePhrase`):
      the family word as written (`MOSFET`, `MLCC`, `LDO`...), the values that are not ratings (display form; an
      impedance without its test frequency; regulator and Zener voltages stay), the technology in the distributor's
      spelling, the dielectric, the package (imperial; a can size is left out of phrases) and the tolerance, e.g. `"22uF X7R 1206 25V 10% MLCC"` -> `"MLCC 22uF X7R
      1206 10%"`, `"SOT-23 N-channel MOSFET 30V"` -> `"MOSFET SOT-23"`; keyword-only queries (no
      value/dielectric/package/technology): the 3 to 5 most informative tokens in query order
      (`DistributorPhraser.keywordCore`): the family word, recognised values and packages, part-number-like tokens with
      letters and digits, then longer words; filler words (`nice`, `cheap`, `module`, `with`...) never, e.g.
      `"ESP32-WROOM-32 wifi bluetooth module with antenna"` -> `"ESP32-WROOM-32 wifi bluetooth antenna"`;
   3. the parametric core without the dielectric (and the technology, which stays a hard constraint and is not
      reported): `"MLCC 22uF 1206 10%"`, loosens `["dielectric"]`. Verified 2026-10-06 and 2026-10-07: TME has no
      22uF X7R 1206 part rated 25 V or more (only 6.3 to 16 V) while `MLCC 22uF 1206` finds 25 V X5R and X6S parts
      with good stock, all in 1206;
   4. only for inductors, crystals and oscillators (`ConstraintPolicy.isRelaxable(query, "package")`): also without
      the package (`"inductor 47uH 20%"`), loosens `["dielectric", "package"]` (live 2026-10-07: TME has no 47uH 0402
      inductor; `inductor 47uH` returned 1212 and larger parts, reported `["package"]`);
   5. also without the tolerance: `"MLCC 22uF 1206"`, loosens `["dielectric", "tolerance"]` for a capacitor;
      `"inductor 47uH"`, `["dielectric", "package", "tolerance"]` for an inductor.
   A constraint a family configures as hard (`kina.search.hard-constraints`) is never dropped. Steps 3 to 5 exist only
   for constraints the request states and only while the core keeps two terms. A step whose
   words equal what was sent (for TME after its 40-character cut) or an earlier step, in any order, is skipped. The
   result of the first step that finds a part that meets the request is kept and cached under the original query key,
   with its phrase (`cached_searches.fallback_query`) and what it loosened (`cached_searches.constraints_relaxed`); a
   `PARTIAL` extension pages on with that phrase. When no step finds such a part, the least relaxed result that found
   in-stock parts is kept (with its phrase and loosened constraints; with every step empty, the last phrase tried). The
   distributor entry reports the phrase as `fallback_query` (null when the first phrase produced the parts). A failure
   of a relaxed search is reported as the distributor's `error`, keeps the least relaxed parts found before, and caches
   nothing.
   **Out-of-stock matches**: distributors drop records without ships-now stock (TME stock 0 or an excluded status,
   Mouser `AvailabilityInStock` 0; LCSC counts the rows that match every term but have no stock when that step found
   nothing in stock). `PageCollector.collect` adds them up over every phrase tried (`out_of_stock_matches`, stored in
   `cached_searches.out_of_stock_matches` and reported again on a cache hit). While every match so far is out of
   stock, up to `PageCollector.EXTRA_OUT_OF_STOCK_PAGES` (2) pages beyond `max-pages-per-search` are read, then the
   next relaxation step is tried.
   **Terms dropped and constraints relaxed** (two lists; they replace the former `relaxed`):
   `query_terms_dropped` (informational) lists the stated terms that were not part of the phrase that produced the
   parts: those the phrase used (the fallback phrase, else the distributor phrase, else the user's text) does not state
   when parsed again (`voltage`, `current`, `tolerance`, `dielectric`, `package`, `technology`, `mounting`...; Mouser
   and TME never get ratings, so a rated request always lists them there; connector queries, whose phrases are
   rewritten, list none), plus the free-text keywords the LCSC relaxation dropped, as written. The ranker still checks
   every constraint. `constraints_relaxed` lists the constraints actually loosened to obtain the parts: of what the
   relaxation loosened (for Mouser and TME the ladder step, stored as `cached_searches.constraints_relaxed`; for LCSC the
   constraint names of the terms its relaxation dropped, by term kind, every term in `ANY` mode) those the policy lets
   relax for the request's family (`ResponseAssembler.relaxable`: a rating or a hard constraint is never reported, even
   when the LCSC search dropped its term; the ranker excludes the parts that miss it) and **the returned parts really
   miss** (a mismatch or an unverified constraint of that name, `ResponseAssembler.actuallyRelaxed`). A
   step that drops the dielectric and the package may still find parts with the requested dielectric (live: TME
   `10uF 100V X7R 1210 MLCC` relaxed to `MLCC 10uF` and returned a 100 V X7R part in 2220, reported as `["package"]`),
   and the LCSC search drops terms one at a time, so a dropped term is not necessarily the one that failed (the third
   audit saw `voltage` reported when the failing term was a size). Empty when nothing was relaxed or nothing returned
   misses a loosened constraint; a rating or a hard constraint is never loosened.
   **Empty after the hard set** (user decision 2026-10-07): when a distributor has nothing that satisfies the hard
   constraints after the relaxable steps, its `parts` list is empty, `exact_matches` is 0, `excluded_by_constraints`
   and `excluded_by_constraints_detail` count what was left out, and the entry carries a `hint`
   (`ConstraintPolicy.hint`) naming the request (`22uF capacitor in package 1206`), the hard constraints that could not
   be met (those that excluded parts, by count, then the other stated ones), the parts below a stated rating (with the
   advice to pass `allow_below_spec` when it is false) and that no substitutes are returned, e.g. `No in-stock 22uF
   capacitor in package 0201 at TME; capacitance and package are never relaxed. No substitutes are returned; try
   another package or value.` The response-level `hint` says the same for every distributor that came back empty (only
   for an understood query; a distributor with an `error` gets no hint).
   TME's 40-character phrase limit is applied by the client as for any query.
   Every fetched part gets `fetchedAt = now` and is enriched with `ParametricExtractor.enrich` before it is cached or
   ranked (Mouser and LCSC deliver almost no parametric attributes).
3. Upsert the newly fetched parts into `cached_parts` (payload = JSON of `Part`, `stock_fetched_at =
   metadata_fetched_at = part.fetchedAt`, `in_stock = true`; an existing row's `metadata_fetched_at` never moves back) and
   the ordered part-number list + `total_results` + `exhausted` + `next_offset` + `fallback_query` +
   `out_of_stock_matches` + `constraints_relaxed` into `cached_searches` (`fetched_at = now`; a `PARTIAL` extension
   keeps the list's original `fetched_at`). A list whose parts **all** fail the request is never stored as a reusable
   search: only its parts are cached, and an existing row for the key is deleted (an empty list is stored as before and
   is fresh only for `empty-result-ttl`). Cache read/write failures are logged and never fail the search.
   `bypassCache == true` skips step 1 but still performs step 3 (status `BYPASSED`).
4. Distributor failures never fail the whole search: the distributor entry carries
   `error` (`"rate_limited"`, `"unavailable"`, `"not_configured"`, `"timeout"`, `"bad_response"` = `DistributorException.Kind.code()`).
   The part list is empty, except that parts already in hand are kept and ranked: the cached list when extending a
   `PARTIAL` search fails, pages fetched before a later page failed, and pages fetched before the timeout. Unexpected
   exceptions map to `unavailable`. A rate limit is not an immediate error: the call waits and retries (section 3.6);
   `rate_limited` is reported only when the next retry would end after the request deadline. Every distributor entry
   reports `rate_limit_waited_ms` (0 when it did not wait, also on a cache hit). A requested distributor without a configured client reports `not_configured` with
   cache status `not_applicable`; with no `distributors` given only configured ones are searched.
5. **Stock refresh** (`StockRefresher.refresh`, after ranking): cached stock and prices can be older than a
   day (a fresh list holds parts of any stock age). For Mouser and TME, the parts about to be returned (the top
   `max_results` of the ranked list) whose `fetchedAt` is older than `kina.cache.stock-ttl` (default `24h`) are
   refreshed with one cheap call per batch (`DistributorClient.refreshStock`: TME `/products/data` with up to 50
   symbols, Mouser `/search/partnumber` with up to 10 part numbers joined by `|`, verified live 2026-10-06;
   quota-aware: only those parts). A refreshed part gets the new stock and prices and `fetchedAt = now` and is written
   back (`PartCacheRepository.updateStock`: payload and `stock_fetched_at`, never `metadata_fetched_at`); a part that
   sold out is dropped from the list and marked sold out (`markSoldOut`: `in_stock = false`, metadata kept), which pulls
   the next part into the top (at most `STOCK_REFRESH_ROUNDS` = 2 rounds). A failed refresh, or a part number missing
   from the answer, keeps the cached figures. Then the stale parts (figures older than `kina.cache.ttl`) are flagged and
   moved below the fresh ones ("Cache model"). LCSC reads its local database anyway. Every part reports `stock_as_of`
   (its `fetchedAt`, to the second). Each refreshed part counts in `kina_cache_stock_refreshes_total` (`ok`,
   `out_of_stock`, `failed`). `get_part` (`PartLookupService`) does the same for a cache hit: a sold-out part is marked
   and looked up live (reported `out_of_stock`, with the listed part at stock 0, section 2); a part whose refresh failed is served as it is within the TTL; beyond
   the TTL it is looked up live and served with `stale: true` only when that lookup fails; with the distributor not
   configured a cached part is served without a refresh (stale beyond the TTL), an uncached one reports
   `not_configured`.

**Counts** of a distributor entry: `fetched` is every in-stock part received from the distributor for the query
(after deduplication, before any exclusion); `excluded_by_constraints` and `excluded_below_spec` are subsets of it
(`excluded_by_constraints_detail` splits the first by the constraint each part contradicts first, so it adds up);
`returned <= fetched - excluded_by_constraints - excluded_below_spec` (and `<= max_results`), plus at most the requested
parts listed without stock (section 2, stock rule; never in `fetched` or the exclusion counts); `out_of_stock_matches`
are records without ships-now stock and are not part of `fetched`; `total_results` is what the distributor reported for
the phrase that produced the parts. The third audit saw `total 55, fetched 6, excluded_by_constraints 40, out_of_stock
9` because `fetched` used to count the parts left after the exclusions.

**Requested part numbers** (`RequestedLookup`, the last step of both retrievers): when the query names part numbers
(section 3.4) and a part number is carried by none of the fetched parts (MPN or distributor part number equal to it or
starting with it), it is looked up directly (`DistributorClient.lookup`, one call per missing part number, within the
distributor's budget; skipped when the retrieval failed). An in-stock part joins the fetched parts (and `cached_parts`;
its number is appended to the cached search list); a part listed without stock joins `Fetched.listed` (section 2, stock
rule); a failed lookup is logged and changes nothing.
**The outcome is part of the cached search** (V10 `cached_searches.requested_parts`, written by
`SearchCacheRepository.recordRequested` on the existing row; `fetched_at` is unchanged, so it is fresh exactly as
long as the list: `kina.cache.ttl`, or `kina.cache.empty-result-ttl` for an empty list). Per part number: `found`
(in stock, number appended to `part_numbers`, so a cache hit holds it), `listed` (stock 0: the number is **not** in
`part_numbers`, whose rows must be in stock; a cache hit reads its `in_stock = false` row with
`PartCacheRepository.findListed`, only for a part number the query names and only when the row is the part that number
requests: the query key contains the part number, so such a hit is always an explicit search) or `not_found` (not
retried). A cache hit (or a `PARTIAL` extension, which keeps the outcomes) therefore makes no lookup call; a live
search (miss, bypass, an expired list) starts without outcomes and looks the part numbers up again; a search that is
not stored (none of its parts meets the request) records nothing. A listed part follows the stock refresh of step 5
like any part (refreshed when older than `kina.cache.stock-ttl`, also outside the top `max_results`): still without
stock, its row gets the new prices and age; back in stock, it is written with `in_stock = true` and served normally. Live 2026-10-07: Mouser's
keyword search for `uP1966E GaN half bridge gate driver` does not return 65-UP1966E (3685 in stock); the lookup does,
and it ranks first. A part number whose outcome a fresh cached search holds costs no call.

LCSC parts are read from the SQLite file and are **not** written to `cached_parts`/`cached_searches`
(the SQLite database is the cache). Only Mouser and TME use the Postgres cache.

**Attributions**: every search response lists in `attributions` the notice of each distributor whose parts it returns
(`Distributor.attribution()`, enum order), `get_part` the notice of the distributor when it returns a part or an
identity: TME `Data powered by TME.eu Data – no guarantee of data accuracy` (exact text, required by TME's API terms
wherever TME data is shown), Mouser `Product data provided by Mouser Electronics`, LCSC `LCSC parts from the JLCPCB
parts database (kicad-jlcpcb-tools)`. The web UI shows all three in the footer of every page.

**Ratings are never part of a keyword phrase.** A voltage, current, saturation current, power, temperature or
lifetime in a request is a minimum rating (section 3.4) and a DCR limit a maximum: a keyword search for `25V` only finds
parts that print `25V` and silently misses the 35 V and 50 V parts that satisfy the request. For every non-connector
query `DistributorPhraser.withoutRatings` removes these values from the user's text (single words such as `25V`,
`105°C`, `2000h`; a number and its unit `25 V`; the labelled forms `Isat 8A`, `DCR < 20mΩ`, `low DCR`, `-40~105°C`;
not when fewer than two words would remain). Regulator and Zener voltages and fuse currents are specifications and
stay. A package the user labelled as metric (`2012 metric`, `3216M`) is sent as its imperial code (`0805`, `1206`;
`Recognizers.imperial`). The phrase is sent to all three distributors; LCSC additionally gets the minimum voltage, current and power as
rating terms (`22uF X7R 1206 MLCC >=25V`) that its database checks exactly (section 9.3). Ranking then prefers the
exact rating over higher ones (section 3.4).

**Distributor phrasing** (`search.DistributorPhraser`). A query that `QueryParser` recognises as a connector request
(section 3.4) is not sent verbatim: each distributor gets the wording its search understands, as the primary query of
step 2 (and of a `PARTIAL` extension). A passive request that names a technology (section 3.4) keeps the user's text
but gets the technology words in the distributor's spelling where it has its own: LCSC a quoted JLCPCB phrase
(`"Thin Film"`, `"Thick Film"`, `"Metal Film"`, `"Carbon Film"`, `"Metal Oxide"`, `"Metal Foil"`, `"Current Sense"`,
`"Aluminum Electrolytic"`, `"Polymer Aluminum"`; FTS5 phrase, a free-text term for the relaxation), TME `wirewound`,
`electrolytic` and `polymer` (aluminium polymer), Mouser `aluminum organic polymer` and
(verified live: `wirewound resistor 5W` finds TME's "wire-wound" resistors, `wire-wound resistor 5W` needed the
fallback, `aluminium electrolytic 100uF` found nothing), Mouser `wirewound`, `thin film`, `thick film` (its category
words). E.g. `Thin film resistor, 5.36k 0805 0.1%` -> LCSC `"Thin Film" resistor, 5.36k 0805 0.1%`, TME and Mouser
verbatim; `100uF 16V polymer aluminium capacitor SMD` -> LCSC `100uF "Polymer Aluminum" capacitor SMD >=16V`, TME
`100uF polymer capacitor SMD`, Mouser `100uF aluminum organic polymer capacitor SMD`. Every other query is sent as
written, minus its ratings. The cache key stays
the user's normalised query; the phrase is a pure function of the parsed query, so a cache hit reports it again.

| Distributor | Rules | Example for `90 degree dupont style female pin header 90 degree THT pins 6 position` |
|---|---|---|
| LCSC | quoted category phrase (`"Female Header"`, `"Pin Header"`, `"Header"`, `"IDC Header"`, `"IC Socket"`, `"Terminal Block"`, `"Wire To Board"` + series, `"USB Connectors"` + `Type-C`/`Micro-B`, `"FPC"`, `RJ45`, `"D-Sub"` + gender, `"DC Power"`); positions `RxNP` when the rows are known, else `NP`; `"Right Angle"`; pitch (`2.54mm`, also when implied); mounting `"Through Hole"` (not for right angle: JLCPCB writes only "Right Angle" there) or `"Surface Mount"` (+ `Vertical`); up to 2 free-text keywords last | `"Female Header" 6P "Right Angle" 2.54mm` |
| TME | TME description wording, most informative first, at most 40 characters (a token that does not fit is skipped): type (`pin strips female`, `pin header male`, `IDC male`, `terminal block`, `wire-board XH`, `USB C socket`, `FFC/FPC`, `RJ45 socket`, `D-Sub female`, `DC supply socket`), positions (`6`, or `2x3` for several rows), orientation (`angled`/`straight`; `horizontal`/`vertical` for USB and FFC/FPC), the pitch only when written (not implied), keywords | `pin strips female 6 angled` |
| Mouser | type (`female header`, `male header`, `header`, `shrouded header`, `terminal block`, `JST XH`, `USB type C receptacle`, `micro USB receptacle`, `FPC connector`, `RJ45 jack`, `D-Sub female`, `DC power jack`), positions as `N pos`, pitch only when written, orientation (`right angle`/`vertical`), mounting for non-header types, keywords. Verified live: `female header 6 pos right angle` finds 6-pin right-angle female headers, `... 6 position ...` matched 9 modular jacks | `female header 6 pos right angle` |

**USB connector requests** (`DistributorPhraser.usbPhrase`, any request whose `parsed.connector` is USB, section 3.4)
use their own wording and **never put the pin count into the TME or Mouser phrase**: distributors may list a 16-pin
Type-C receptacle with its shell pins (17P/18P, `PIN: 17`, `17 Positions`), so a count would exclude it; the ranker
sorts by the canonical pin configuration instead. Type, gender, mounting, standard and features stay. The
configuration implied by a standard (`USB 2.0 Type-C` -> 16) is used for ranking only, never in a phrase.

| Distributor | USB rules | `USB-C receptacle 16 pin SMD USB 2.0` |
|---|---|---|
| LCSC | `"USB Connectors"` (category column filter) + the dominant JLCPCB spelling of the type (`Type-C`, `Micro-B`, `Micro-AB`, `Mini-B`, `Type-A`, `Type-B`; `JlcpcbQuery` adds `TypeC`/`MicroB`/... as alternatives), `Male` for plugs, the **stated** pin count as an OR group of the configuration and its shell-counted variants (`16P/17P/18P`, `24P/25P/26P`, `6P/7P/8P`, `5P/6P/7P`; `14P/15P` because 16 is a configuration of its own), the standard (`"USB 2.0"`, `"USB 3"` for any 3.x, `USB4`), `"Through Hole"`/`"Surface Mount"`, `"Right Angle"`/`Vertical`, `mid-mount`, `waterproof`, `"board lock"`, up to 2 keywords | `"USB Connectors" Type-C 16P/17P/18P "USB 2.0" "Surface Mount"` |
| TME | TME `Type of connector` wording + `socket`/`plug` (`USB C socket`, `USB B micro socket`, `USB AB micro`, `USB B mini`, `USB A`, `USB B`), `SMT`/`THT`, `horizontal`/`vertical`, `2.0` (and `3.0` for Type-A/B/Micro-B; Type-C 3.x is left out because TME's `Version` varies between 3.0/3.1/3.2/Gen spellings and live `USB C socket 24 horizontal 3.1` found nothing), `charging` (power only, TME "only for charging (6p)"), `middle` (mid-mount), the IP rating or `waterproof`; 40 characters | `USB C socket SMT 2.0` |
| Mouser | `USB type C`/`micro USB`/`mini USB`/`USB type A`/`USB type B` + `receptacle`/`plug`, orientation, `SMD`/`THT`, the version as written (`2.0`, `3.1`, `3.2 Gen 2x2`, `USB4`), `mid`, `hybrid`, the IP rating or `waterproof`; power-only requests add `6 pos power only` (the one pin count kept: live, `USB type C receptacle 6 pos power only` returned 11 power-only parts, `USB type C receptacle power only` 1) | `USB type C receptacle SMD 2.0` |

USB fallback (TME, Mouser): the type and gender words only (`USB C socket`, `USB type C receptacle`). Verified live on
2026-10-05 through the stack: Mouser `USB type C receptacle SMD 2.0` (18 parts, 16-pin USB 2.0 receptacles first),
`USB type C receptacle right angle SMD 3.1` (50), `USB type C receptacle mid` (15, GCT/Same Sky mid-mounts),
`micro USB receptacle SMD` (50), `USB type C plug` (50; with `24 pos` only 1), TME `USB C socket charging` (50, all
6-pin) and `USB C socket waterproof` (27). The new TME phrases with `SMT`/`THT` and the Mouser `USB type A ...`
phrases could not be sent verbatim through the stack (it runs the previous phraser) and are not verified live.

Each distributor entry reports the phrase as `distributor_query` (null when the user's text was sent verbatim);
`fallback_query` is the relaxed phrase that produced the parts after the first one found nothing that meets the
request.

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

**Before ranking** (`RankingService.rank(query, fetched, budget, RankOptions(quantity, allowBelowSpec))`): parts whose
known attribute contradicts a hard constraint are removed and counted per distributor (`excluded_by_constraints`, per
constraint `RankedResults.excludedDetail`, section 3.4 "Hard constraints"); parts with a known rating below the request are removed and counted
(`excluded_below_spec`, the five closest per distributor in `excluded_below_spec_detail`) unless `allowBelowSpec`
(section 3.4 "Below spec"). Every remaining part gets a tier: 0 for a
**complete** match (no mismatch, nothing unverified), +1 for a part with a mismatch or an unverified constraint
(including an unstated hard attribute), +4 when its stock is below `quantity`, +8 when it is below spec (only with
`allowBelowSpec`), and -16 (`RankingService.REQUESTED_TIER`) when the query names it by part number (section 3.4
"Requested part numbers"): the requested part comes first in its distributor whatever its score, and is reported with
score 1.0; it is still excluded by a hard constraint or a rating below the request like any other part (a rule, not a
weight). The quantity, MOQ, low-stock and lifecycle penalties and the voltage overshoot (section 3.4) are subtracted
from the deterministic score and **again from the final score** (blended or not), so the model cannot hide them. Every ordering
(deterministic, blended, fallback) sorts by tier first, so a part with an unverified constraint or too little stock
never ranks above a complete match with enough stock, whatever the model says; within the below-spec tier the order
is the distance from the target (closest first), never the blend. `score` is then made non-increasing down the list.
The match grade, `mismatches`, `unverified` and `below_spec` of every part (section 3.4) come from the same
assessment; when the query was not understood (`ParsedQuery.understood()` false) every match grade is null.

Batch search fetches the queries in parallel (at most 4 queries at a time, to respect distributor rate limits),
then ranks each query independently through the same path, each with `min(kina.ranking.timeout, remaining batch
budget)`. The ranking phase has `kina.ranking.batch-timeout` (default 60s); queries reached after it expired are ranked
with a zero budget (fallback ranking, `ranking_note` `"batch ranking budget of 60s exhausted"`; scores already in
`RankingScoreCache` are still used).

### 3.4 Deterministic ranking and query parsing

`QueryParser.parse(String) -> ParsedQuery` extracts, case-insensitively:

- component family keywords: capacitor/MLCC/cap, resistor/res, inductor, ferrite, diode/rectifier, Schottky,
  Zener, LED, MOSFET/FET/HEMT (TME `N-MOSFET`/`P-MOSFET`, kept as keywords), transistor/BJT/NPN/PNP, LDO/regulator,
  op amp/opamp, comparator, MCU, crystal/xtal/resonator, oscillator/XO/TCXO/VCXO/OCXO/SPXO and TME's `generator`
  (two families, never mixed: `crystal oscillator` is an oscillator, priority decides), connector, fuse, TVS/ESD,
  relay, switch, and `gate driver`: `gate driver`, `MOSFET driver`, `IGBT driver`, `half-bridge driver` (Mouser `Half
  Bridge Gate Dvr`, `HALF BRDG DRVR`, `Iso 1/2 Bridge Drv`), `power stage` / `ePower stage` (EPC), and a `half-bridge`
  whose text also says `driver` (`GaN half-bridge with integrated driver`). The gate driver words win over `MOSFET`,
  `FET` and `transistor` (`MOSFET gate driver` is a gate driver); `driver` alone names no family (`LED driver` stays an
  LED request). Distributor phrases spell the family as `gate driver` / `power stage`. A part's family comes
  from its category, else its description; when the description names a specialisation of the category's family
  (TME category `SMD N channel transistors`, description `Transistor: N-MOSFET`), the specialisation wins (`mosfet`),
  and a description naming a gate driver wins over a transistor category (Mouser lists the TI LMG and Infineon IGI60
  half-bridges with integrated driver under `GaN FETs`). Gate drivers and MOSFETs are different families, so the type
  check excludes gate drivers from a MOSFET request and the reverse (like crystals and oscillators)
- value with SI prefix and unit, including RKM notation (`4k7`, `4u7`, `10R`, `2R2`):
  capacitance (`pF nF uF µF mF F`), resistance (`Ω ohm R`, `k`, `M`, `m`), inductance (`nH uH mH H`),
  voltage (`V`, `kV`, `mV`; KEMET's truncated `10Volt`, `10Vol`, `6.3Vo` at Mouser), current (`A`, `mA`, `uA`), power
  (`W`, `mW`, `kW`, `watt`, `watts`, `kilowatt`, with or without a space: `300watts`, `50watt`, `150 W`, `1.5 kW`,
  `1/4W`; shown in watts below 1 kW, `250W`, `0.5W`, `0.125W`, and in kilowatts from 1 kW, `1.5kW`), frequency (`Hz kHz MHz`), maximum operating temperature (`105°C`, `℃`, Mouser `105C` for 70..200, the
  upper end of a range `-55℃~+105℃`, `-55...105°C`, `-55÷125°C`), lifetime in hours (`2000h`, `2000hrs`, `5000 hours`,
  `2000hrs@105℃` with its test temperature)
- units are read case-sensitively where the case carries meaning: the prefix `m` is milli and `M` mega for every unit
  (`10mA` and `10MA` differ; `meg` is mega too), with one tolerated spelling, `mhz` = MHz (millihertz never occurs in
  component data); a lower-case `h` after a bare number is hours and an upper-case `H` henry, except in the text of a
  part or query whose family is known and not inductive, where `3000H` (at least 100) is a lifetime (Mouser
  `100UF 63V 105C 3000H`); with an SI prefix (`uH`, `uh`, `mH`) it is always henry. Units whose case means nothing
  (`v`/`V`, `a`/`A`, `w`/`W`, `uf`/`uF`, `hz`/`Hz`) are accepted either way
- values tied to their meaning: for an inductor or ferrite bead an ohm value is never a resistance: a ferrite bead's
  first value of at least 1 Ω (or one written with a test frequency, `120Ω@100MHz`) is its **impedance** (with the
  test frequency taken from the `@` or from a separate frequency, `120 ohm 100MHz` -> `120ohm @100MHz`), a smaller one
  its **DCR**; an inductor's ohm value is its DCR. Labelled forms: `Isat 8A`, `8A Isat`, `saturation current 8A` =
  **saturation current**; `Irated`, `Irms`, `Ioper:` (TME), `rated current` = rated current; `DCR 20mΩ`, `DCR=17.2mOhms`,
  `DCR < 20mΩ`, `DCR 20mΩ max`, `DC resistance` = **DCR** (a maximum). For an inductor an unlabelled current is the rated
  current; of several unlabelled ones (JLCPCB lists rated and saturation current without labels, in no fixed order)
  the lowest, which is conservative. A value with a condition that is not a rating is ignored (`902mA@100kHz` ripple
  current, `16mΩ@100kHz` ESR). `low DCR` is a preference (`ParsedQuery.preferences`), not a keyword
- tolerance (`±5%`, `5%`, `1%`), dielectric (`X7R X5R C0G NP0 Y5V X7S X6S X8R`),
  package (`01005 0201 0402 0603 0805 1206 1210 1808 1812 1825 2010 2220 2225 2512`, `SOT-23 SOT-23-5 SOT-223 SOT-89
  SOD-123 SOD-323 SOD-523 TO-220 TO-252 TO-263 DPAK D2PAK SOIC-8 SOP-8 TSSOP-20 MSOP QFN-32 DFN LQFP-48 TQFP-64 BGA
  ...` via regex), **imperial always** (see "Packages are imperial" below), crystal sizes (`3225`, `2520`, `2016`...)
  for crystals and oscillators, can sizes (`6.3x5.4mm`, `D6.3xL5.4mm`) of aluminium capacitors, mounting (`SMD SMT
  THT through-hole`, JLCPCB `插件` = THT, `卧贴` = SMD)
- transistor polarity (`ComponentTypes.polarity`, `ParsedQuery.polarity`): `N-channel`, `N-CH`, `N-MOSFET`, `NMOS`,
  `NFET` (and P), `NPN`, `PNP`; both polarities, `N+P` or `complementary` are `complementary` (an N+P pair is a type
  of its own). A polarity without a family word makes the family `mosfet` (or `transistor` for NPN/PNP)
- the subtype (`ComponentTypes.subtype`, `ParsedQuery.subtype`): `standard` for a diode request that says
  `rectifier`, `rectifying`, `switching`, `general purpose`, `fast recovery`, `ultrafast`, `high efficiency`,
  `universal`, `small signal`, `SBR`; `fixed` or `adjustable` (`ADJ`, `variable`) for a regulator, `fixed` when the
  request states an output voltage
- connector attributes (`search.ConnectorRecognizer`, `ParsedQuery.Connector`, see below)
- the technology of a passive (`search.TechnologyVocabulary`, `ParsedQuery.technology`, see below)
- remaining tokens are free text keywords.

Tolerances and values also accept a leading-dot decimal (`.1%`, `±.5%`, `.1W`, `.5k`): Mouser writes
`Thin Film Resistors - SMD 5.36Kohms .1% 25ppm`. `JlcpcbQuery` adds the zero (`.1%` -> `0.1%`, JLCPCB's wording).

**Technology** (`search.TechnologyVocabulary`, resistors, capacitors and inductors: the construction; MOSFETs,
transistors and gate drivers: the semiconductor; the family must be known, from a family word or a value). Canonical
values and the wording recognised in queries and parts (mined 2026-10-05 from the JLCPCB database, live TME parameters
and Mouser categories; transistors 2026-10-07 from Mouser):

| Family | Technology | Wording |
|---|---|---|
| resistor | `thin film`, `thick film`, `metal film`, `carbon film`, `carbon composition`, `metal oxide` | the words with a space, a hyphen or none (`thin-film`); JLCPCB `Thin Film Resistor`, TME `Type of resistor: thin film`, Mouser `Thin Film Resistors - SMD` |
| resistor | `wirewound` | `wirewound`, `wire-wound`, `wire wound` (TME `wire-wound`, Mouser `Wirewound Resistors`) |
| resistor | `metal foil`, `metal strip` | `metal foil`, `foil`; `metal strip` (TME) |
| resistor | `current sense` | `current sense`, `current sensing`, `current shunt`, `shunt` (JLCPCB `Current Sense Resistor`, TME `Kind of resistor: current shunt, sensing`, Mouser `Current Sense Resistors`). An application rather than a construction: a construction named anywhere in the same part wins |
| capacitor | `ceramic` | `ceramic`, `multilayer ceramic`, `MLCC` (parts only: in a query `MLCC` is the family word, scored lexically); JLCPCB category `Multilayer Ceramic Capacitors MLCC`, TME `Type of capacitor: ceramic` |
| capacitor | `tantalum`, `tantalum polymer` | `tantalum`; `tantalum-polymer`, `polymer tantalum`, Mouser `Tantalum Capacitors - Polymer` |
| capacitor | `aluminium polymer` | `polymer aluminium`, `aluminium polymer`, `aluminum organic polymer`, `conductive polymer aluminum`, `OS-CON`, `solid polymer`, `solid capacitor` (JLCPCB category `Polymer Aluminum Capacitors`, `Solid Capacitors`, Mouser `Aluminium Organic Polymer Capacitors`); a part that says only `polymer` is aluminium polymer when another of its texts names aluminium or OS-CON (TME `Capacitor: polymer; ... OS-CON SVF`) and tantalum polymer when one names tantalum |
| capacitor | `hybrid polymer` | `hybrid`, `polymer hybrid` (JLCPCB `Hybrid Aluminum Electrolytic Capacitors`) |
| capacitor | `polymer` | `polymer` alone (JLCPCB `Polarized Polymer`, TME `Type of capacitor: polymer`) |
| capacitor | `aluminium electrolytic` | `aluminium electrolytic`, `aluminum electrolytic`, `electrolytic` (TME `Capacitor: electrolytic`) |
| capacitor | `film`, `polypropylene`, `polyester`, `PPS` | `film`; `polypropylene`, `MKP`, `CBB`; `polyester`, `PET`, `polyethylene terephthalate`, `mylar`, `MKT`; `PPS` |
| capacitor | `supercapacitor` | `supercapacitor`, `super capacitor`, `ultracapacitor`, `supercap`, `EDLC` |
| inductor | `wirewound`, `multilayer`, `thin film` | `wire-wound`, TME `Type of inductor: wire`; `multilayer`, `multi-layer`; `thin film` |
| MOSFET, transistor, gate driver | `GaN` | `GaN`, `eGaN`, `CoolGaN`, `GaNFast`, `DrGaN`, `gallium nitride`, `GaN-on-SiC` / `GaN-on-Si` (GaN); Mouser category `GaN FETs`. A part number such as `GAN140-650EBEZ` is no technology word. For a gate driver it names the switches it drives |
| MOSFET, transistor, gate driver | `SiC` | `SiC`, `silicon carbide`; Mouser category `SiC MOSFETs` |
| MOSFET, transistor, gate driver | `silicon` | `silicon`, `Si MOSFET`, `Si FET`, Mouser `Technology: Si`. A plain `MOSFETs` category names no semiconductor (unknown, not silicon) |

"Ferrite" is not a technology: the word names the ferrite-bead family. The recognised words leave the free-text
keywords. TCR is deliberately not parsed. The technology is hard for transistors (the `transistor` policy family:
MOSFETs and transistors) and for the default family (gate drivers among others): a GaN request excludes a known SiC or
silicon part, and a part that names no semiconductor stays, unverified.

**On-resistance of MOSFETs** (`Recognizers.RDS_ON`, MOSFET and transistor families only): `3.2 milliohm`,
`270 mohm`, `120mOhm`, `58mΩ@2.5V` (JLCPCB, the value at its gate voltage), `70-m?` and `170/248-m?` (Mouser prints
`?` where the ohm sign was; of a list the first value) are the part's `Resistance` (R_DS(on)); a bare `m` (`2.6m`,
`185 m`) is too ambiguous and stays unread. Mouser `Rds On - Drain-Source Resistance` is read as an attribute.

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

**USB connectors** (`search.UsbVocabulary`, shared by parser, extractor, ranker and phraser). A query is a USB connector
request when it names a USB type, or says `USB` together with connector words (`connector`, `receptacle`, `socket`,
`jack`, `plug`, `port`, `female`/`male`); a USB type next to product words that are not board connectors (`IC`, `chip`,
`controller`, `bridge`, `UART`, `hub`, `PHY`, `transceiver`, `ESD`, `TVS`, `protection`, `diode`, `cable`, `adapter`,
`charger`, `power supply`, `module`, `switch`...) is not, so `USB to UART bridge IC`, `USB ESD protection diode`,
`USB 5V 2A power adapter`, `USB-C PD controller`, `USB Type-C cable` stay non-connector queries. Additional
`ParsedQuery.Connector` fields (exposed additively in `parsed.connector` as `usb_type`, `usb_standard`,
`usb_speed_gbps`, `pin_configuration`, `pin_configuration_implied`, `shield_pins_counted`, `mounting_style`,
`features`):

| Field | Recognised wording |
|---|---|
| `usbType` (`type`) | `Type-C` (`USB-C`, `USBC`, `USB Type-C`, `Type C`, `TypeC`, Mouser `C type`; type `usb-c`), `Micro-B` (`micro USB`, `microUSB`, `Micro-USB B`, `Micro B`, `MicroB`, TME `USB B micro`; type `micro usb`), `Micro-AB`, `Mini-B` (`mini USB`, `Mini-B`, TME `USB B mini`), `Mini-AB`, `Type-A` (`USB-A`, `USB A`, `Type A`), `Type-B` (`USB-B`, `USB B`); the last four have type `usb`. A USB4/Thunderbolt part without a type word is Type-C |
| `gender` | as for other connectors; also Mouser `Rec`, `Recpt`, `Rcpt`, `Skt`, `Jack`, `FML` (female), `Plug` (male) |
| `usbStandard`, `usbSpeedGbps` | canonical speed classes: `USB 2.0` (0.48; `USB 2.0`, `USB2.0`, `480 Mbps`, `0.48Gbps`, Mouser `Type C, 2.0`, `USB Jack 2.0`), `USB 3.2 Gen 1` (5; `USB 3.0` = `USB 3.1 Gen 1` = `USB 3.2 Gen 1`, `5Gbps`, Mouser `5G`), `USB 3.x` (5, generation not stated: `USB 3.1`, `USB 3.2`), `USB 3.2 Gen 2` (10; `USB 3.1 Gen 2`, `Gen 2x1`, `10Gbps`), `USB 3.2 Gen 2x2` (20; TME `Gen.2x2`, `20Gbps`), `USB4` (40; `USB4`, `USB 4.0`, `40Gbps`), `Thunderbolt 3`/`Thunderbolt 4` (40), `USB 1.1`. `Gen 2x2` is not read as a 2x2 grid |
| `positions` | the count as written or reported (`17 pin` -> 17) |
| `pinConfiguration`, `shieldPinsCounted` | the canonical signal configuration. Configurations per type: Type-C 6 (power only: VBUS/GND/CC), 12, 14, 16 (USB 2.0 data), 24 (full-featured); Micro-B/Micro-AB 5 and 10 (USB 3.0 Micro-B); Mini-B 5; Type-A/Type-B 4 (USB 2.0) and 9 (USB 3.x). A count N that is not itself a configuration of the type but exceeds one (C) by 1 or 2 is that configuration with `shieldPinsCounted = N - C` (shell / shield / mounting pins counted by the distributor): Type-C 17/18 -> 16, 25/26 -> 24, 7/8 -> 6, 13 -> 12; Micro-B 6/7 -> 5; Type-A 5/6 -> 4, 10/11 -> 9. 14 stays 14 (a real USB 2.0 Type-C configuration); Type-C 2P/4P stay (power only). Explicit sums: `16+2P` -> 18 positions, configuration 16, 2 shield pins; `4P+4P`, `9P+9P` (stacked ports) and `4P+14P` next to two USB types (combo) -> feature `multi-port`, configuration = the largest configuration term; `8P+16P` -> configuration 16; `2P+4J` -> 2 pins and feature `4 legs` (J = 脚, legs). JLCPCB uses `aP+bP` only for several ports, never for shield pins (mined 2026-10-05) |
| `pinConfigurationImplied` | a request without a pin count gets the configuration its standard implies: Type-C `USB 2.0` -> 16, `USB 3.x`/`USB4`/Thunderbolt -> 24, power only -> 6; Micro-B `USB 2.0` -> 5, `USB 3.0` -> 10; Type-A/B `USB 2.0` -> 4, `USB 3.x` -> 9. Used by the ranker at half weight, never in a phrase |
| `mountingStyle` | `mid-mount` (`mid-mount`, `mid mount`, `Mid Mnt`, `MidMt`, `MSMT`, `Mid Surface Mount`, `sunken`, `middle board mount`, JLCPCB `Recessed`, `Sink board`, `Sinking`, `Laminated board` = 沉板), else `hybrid` (`hybrid`, `SMD+THT`, `SMT, THT`, TME `hybrid SMT/THT`, or a through-hole shell: Mouser `SMT & TH stakes`, `W/Shell Stake`), else `top-mount` (`top mount`, TME `top board mount`, Mouser `TOPMNT`, `T.Mt.`) |
| `features` | `power only` (`power only`, `charging only`, Mouser `Charge-Only`, TME `only for charging (6p)`; parts: any Type-C with 2-6 contacts), `PD` (`PD`, `power delivery`), `mid-mount`, `top-mount`, `hybrid`, `through-hole shell`, `fully SMD` (TME `Fully SMT`), `waterproof` (`waterproof`, `sealed`, `O-ring`, `gasket`, any IP rating) plus the rating (`IP67`, `IP68`, `IPX7`...), `board lock` (`board lock`, `locating pins/pegs/posts`, `with post`, `peg`), `straddle-mount` (JLCPCB `Clamping plate`), `N legs`, `multi-port` |
| `orientation` | as for other connectors, plus `horz`, `hrz`, `HZ`, `side insertion` (right angle), `vert`, `upright` (vertical); TME `horizontal` = right angle |

A bare number after the type words (`USB C socket 16`, TME's own wording) is the pin count when it is a configuration
(or shell-counted variant) of the type; not when a unit follows (`5 Vdc`, `20 V`).

`ParsedQuery` holds the original text, normalised key, the extracted constraints (typed, with SI
values normalised to base units as `double`), the free-text tokens and `elements` (the requested array: `array`,
`network` -> `ANY_ELEMENTS`; `4 lines`, `2 elements` -> the count; null for a single element; not for connectors).
`understood()` is false when nothing typed was recognised (section "Keyword-only queries" below).

`ParametricExtractor.extract(Part) -> Map<String,String>` applies the same recognisers to the
part's description and attribute values, so parts from all three distributors expose comparable
`Capacitance`, `Resistance`, `Inductance`, `Voltage`, `Current`, `Power`, `Tolerance`, `Dielectric`,
`Package` (imperial, section "Packages are imperial"), `Mounting` keys, `Technology` for resistors, capacitors and
inductors (the construction) and for MOSFETs, transistors and gate drivers (`GaN`, `SiC`, `silicon`), `Resistance` of a
MOSFET (its on-resistance, above), `Polarity` for transistors (`N-channel`, `P-channel`, `NPN`, `PNP`, `complementary`), `Subtype` (`standard`
for a rectifier or switching diode of the generic diode family, `fixed` or `adjustable` for a regulator), and
`Impedance` (ferrite beads, `120ohm @100MHz`), `SaturationCurrent`, `DCR`, `MaxTemperature` (`105°C`),
`OperatingTemperature` (`-55...155°C`, below), `Lifetime` (`2000h @105°C`) and `FormFactor` (section "Form factor"
below; only when the package does not already say it). A distributor `Power` attribute in another form of the same
value (TME `Power: 0.25kW`) is shown as the canonical `250W` in both detail levels; every other distributor attribute
is kept as given. A crystal's `Capacitance` is its load capacitance (attribute `Load capacitance` too). Inductors
and ferrite beads report their current as `RatedCurrent` instead of `Current` and never carry `Resistance`. Distributor
attributes (TME parameters, Mouser ProductAttributes) take precedence over description parsing. Inductor and ferrite
parameters (verified on TME 2026-10-06): `Resistance` (TME: the DC resistance, BLM31KN121SN1L `9mΩ`), `DC resistance`,
Mouser `Maximum DC Resistance` -> `DCR`; `Impedance at 100MHz` (the frequency from the name), `Impedance` + `Test
Frequency` -> `Impedance`; `Operating current`, `Rated current`, `Maximum DC Current` -> `RatedCurrent`; `Saturation
current` (may be empty: IHLP2525CZER2R2M01), `Isat` -> `SaturationCurrent`. A lifetime attribute (`Service life`,
`Lifetime`, `Load Life`, `Endurance`) is read in hours whatever the case of its `h`, never as inductance (the Samwha
`RC1C107M6L006VR` "1000h" bug); an operating temperature attribute gives its largest number. Mounting also comes from
the package field (LCSC `SMD,D8xL10mm`, `插件,D6.3xL8mm`) and the category (`... - SMD`, `Radial Leaded`, `Leaded`;
`radial`, `axial`, `leaded` mean THT). Extracting again from an enriched (cached) part gives the same values. `Technology` comes from the TME
parameters `Type of resistor`/`Type of capacitor`/`Type of inductor`/`Kind of capacitor`/`Kind of resistor` (or a
`Technology`/`Composition`/`Construction` attribute), then the description, then the category (JLCPCB capacitors say
it only in the category: `Capacitors / Tantalum Capacitors`), then the series or category of a power resistor
(`TechnologyVocabulary.ofPart`, parts only): the Mouser category `Planar Resistors` and Ohmite's `TGH` series
(`TGHG`, `TGHPV`) are thick film. TME's `Type of resistor: power` and its `LPR` / `AHP` series name no technology and
get none, never a guessed one; a technology request against such a part stays unverified.

**Thermal fields** (distributor text only, never datasheets): `MaxTemperature` is the largest number of an operating
temperature attribute (Mouser `Maximum Operating Temperature`, TME `Max. operating temperature`, then TME `Operating
temperature`), else the upper end of a range or a single temperature in the description (LCSC `-55℃~+155℃`, Mouser
`+155C`). `OperatingTemperature` is the range with both ends, normalised to `<min>...<max>°C`: TME `Operating
temperature` (`-55...155°C`), else a range the description prints (LCSC `-55℃~+175℃` -> `-55...175°C`). Mouser's
search API returns no parametric attributes, so Mouser parts carry only what the description says.

**Power resistors** (DESIGN decision 2026-10-07). A power the description or the attributes state always wins. Only
when neither states one, the series of a resistor's part number names it (`ResistorSeries`): Arcol `HS`, `HSA`, `HSC`
(`HS25` 25 W, `HSC100` 100 W; the number must be a series wattage, so `HS254R7J` is 25 W), TE `THS` (`THS25`), Vishay /
Dale `RH` (`RH-50`, `RH0254R700`), Ohmite `TEH70` / `TEH100`, Bourns `PWR220T-20` (20 W) / `PWR263S-35`, Caddock
`MP915`, `MP925`, `MP930`, `MP9100`, and `LPS0300` / `LPS0800` (300 W / 800 W). Ohmite `TGH` names no wattage.

**Descriptive details of passives** (`PassiveDetails`, canonical keys that are reported but not scored, except
`Elements`): `Elements` (arrays and networks, section "Arrays" below); for capacitors `RippleCurrent` (a capacitor's
current is always its ripple current and never `Current`: TME lists it as `Operating current`, 0.24 A on Panasonic
`EEEFK1C101P`, which the second audit's compact view mislabelled `Current: 240mA`; Mouser `Ripple Current`), `ESR` and
`Impedance` in ohm with the test frequency when stated (attributes `ESR`, `Impedance`, `ESR at 100kHz`; the LCSC
description `15mΩ@100kHz` is the ESR of a polymer capacitor or when the text says ESR, else the impedance; TME gives
`Impedance 0.36Ω` without a frequency, so none is added); `Dimensions` from a dimensions attribute (`Body dimensions`
`Ø6.3x5.8mm`), the diameter and height attributes, the package field (LCSC `SMD,D6.3xL5.8mm`) or the description, in
the form `D6.3 x 5.8mm` for a can (diameter x height) and `8.8 x 8.4 x 3.8mm` otherwise; the `Package` of a can
capacitor whose package is not a chip code is its size (`D6.3 x 5.8mm`) and the vendor case code (Panasonic `D`) moves
to `Case`; `Qualification` (`AEC-Q200`, `AEC-Q101`... from any attribute such as TME `Conform to the norm`, or the
description); `Features` for passives (comma-separated, from the description and attributes: `low ESR`, `low
impedance`, `high ripple current`, `long life`, `low DCR`, `high current`, `shielded`, `semi-shielded`,
`unshielded`, `anti-sulfur`, `soft termination`, `low profile`; USB connectors keep their own `Features`).
`get_part` returns the full attribute set by default (`detail=full`: these canonical keys plus every raw distributor
attribute, `photo_url` and `extra`); searches stay compact.

**Package from the part number.** A resistor, capacitor, inductor or ferrite part whose package field, attributes and
description name no package (Mouser keyword results often carry none: `Thin Film Resistors - SMD 5.36Kohms .1% 25ppm`)
gets `Package` from its MPN when the MPN starts with a known series followed by an imperial chip code
(`0201 0402 0603 0805 1206 1210 1812 2010 2512`): Vishay `CRCW`, `TNPW`, `TNPU`, `RCP`, `RCS`, `RCG`, `RCWE`, `MCT`,
`MCS`, `MCU`, `MCA`, `PAT`, `PLT`, `PLTT`, `PTN`, `WSL`, `VJ`; Yageo `RC`, `RT`, `AC`, `AT`, `AA`, `AF`, `AR`, `PE`,
`PT`, `SR`, `RE`, `RL`, `RV`, `CC`, `CQ`; Stackpole `RNCF`, `RMCF`, `RMCS`, `RMCP`, `RMEF`, `RGC`, `RNCS`, `CSR`; TE
`CPF`, `CRG`, `CRGH`, `CRGV`, `CRGCQ`; KEMET `C` (`C0805C106K...`, only when the manufacturer is KEMET). TE `RN73`
size letters after the TCR letter: `1E` 0402, `1J` 0603, `2A` 0805, `2B` 1206, `2E` 1210, `2H` 2010, `3A` 2512 (TE
datasheet 1773270, "How To Order"). Guard rails: every listed prefix agreed with the JLCPCB `Package` column on all of
its chip rows (over 450 000 rows, 2026-10-05), while a generic "letters + code" rule was wrong on 1.4 % of rows;
manufacturers that put **metric** codes after a letter prefix are excluded (Samsung `RC0402...` = 01005, Susumu
`RT0603...` = 0201, TDK `C0603...`/`MLG0603...`, Taiyo Yuden, Sunlord, Murata); Panasonic `ERJ6`/`ERA-6`, Samsung `CL21`
and AVX `08055C...` are not read. A package the distributor states is never overridden, even when it is not recognised
(`-` counts as not stated).

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

USB connector parts additionally get `UsbType`, `UsbStandard` (canonical name), `UsbSpeedGbps` (`0.48`, `5`, `10`,
`20`, `40`), `PinConfiguration`, `ShieldPinsCounted` (only when non-zero), `MountingStyle` (`mid-mount`, `hybrid`,
`top-mount`, else `SMD`/`THT`), `Waterproof` (the IP rating, or `yes`) and `Features` (comma-separated); `Positions`
stays the count the distributor reports (a 17P part: `Positions=17`, `PinConfiguration=16`, `ShieldPinsCounted=1`).
Precedence: TME parameters (`Type of connector`, `Connector`, `Number of pins`, `Version`, `Data transfer rate` (wins
over `Version`: TME lists `CX90B1-24P/C` as `USB 4.0` with `20Gbps` and `Gen.2x2`), `Connector variant`,
`Connectors application`, `IP rating`, `Electrical mounting`), then the description, then the category; LCSC and Mouser
have only the description (Mouser's keyword search returns no USB `ProductAttributes`, only `Packaging` and
`Standard Pack Qty`). The sealing of JLCPCB parts is often only in the part number (`USBC-0032IPX8-00`): an IP rating
in the MPN adds `waterproof`. Physical consistency rules (distributor labels are kept otherwise): a Type-C part with
12, 14 or 16 contacts is `USB 2.0` (no SuperSpeed pairs; JLCPCB labels 841 Type-C rows "USB 3.1", many of them 16P or
6P, after the Type-C specification generation), one with 2-6 contacts is `power only` with no data standard; an
unlabelled Micro-B 5P / Mini-B 5P / Type-A or B 4P is `USB 2.0`, Micro-B 10P / Type-A or B 9P `USB 3.2 Gen 1`; a
Type-C 24P part without a stated standard keeps none (no pin-based guess: USB 2.0-only 24P parts exist). Connector
parts no longer carry a capacitance/inductance/resistance read from the description (Mouser `Gold plated 3u`, `6.5H`
height). Not connectors: categories naming cables, adapters, power supplies, hubs (`USB cables and adapters`,
`Plug-in Power Supplies`, `Sensor Cables / Actuator Cables`) unless they say "connector", and TME descriptions that start
with the product kind (`Adapter;`, `Cable;`, `Hub USB;`, `Power supply`).

`DeterministicRanker.score(ParsedQuery, Part) -> double in [0,1]` (weights and rules declared on `ConstraintKind`
with `@Match`, see "Declarative constraint model" below; the constant is named in brackets):

| Signal | Weight | Rule |
|---|---|---|
| primary value (C/R/L; ferrite impedance; `VALUE`) | 0.30 | exact match within 1% -> full (an impedance also at the same test frequency when both state one); different -> -0.30 penalty; unknown -> 0 |
| package (`PACKAGE`) | 0.20 | `Recognizers.samePackage` (imperial codes; `SOT-23-3L` == `SOT-23` == `TO-236AB`; can sizes within 0.2 mm); mismatch -> -0.20; a package that cannot be read is unverified |
| polarity (`POLARITY`) | 0.10 | the request's polarity: same -> +0.10, different -> -0.10 (excluded anyway), unknown -> unverified |
| load capacitance (`LOAD_CAPACITANCE`) | 0.10 | a crystal request with a capacitance: same within 1 % -> +0.10, different -> -0.10 (excluded), unknown -> unverified |
| dielectric (`DIELECTRIC`) | 0.15 | exact; `C0G` == `NP0`; mismatch -> -0.15 |
| technology (`TECHNOLOGY`) | 0.15 | the query names a technology: same -> +0.15; a different known technology -> -0.15; unknown -> 0. Compatible (+): a `film` request and a polypropylene/polyester/PPS part, a `tantalum` or `polymer` request and a tantalum polymer part, a `current sense` request and a metal strip/metal foil part. Neutral (0): a polypropylene request and a part that only says `film`, a tantalum polymer request and a `tantalum` part, a `current sense` request and any other construction |
| ratings, group `rating`: voltage, current (an inductor's rated current), saturation current, power, temperature, lifetime; DCR | 0.10 | shared between the stated ratings. Minimums: part >= requested -> full; lower -> -0.10; a higher rating keeps full credit in the match grade but loses up to `DeterministicRanker.W_RATING_EXCESS` (0.05) of score, `0.05 * min(1, log2(part / requested) / 2)`, so 25 V > 35 V > 50 V > 100 V for a 25 V request. A voltage far above the request also pays the **overshoot** penalty (below). DCR is a maximum (part <= requested). Regulator and Zener voltages and fuse currents must match within 2 %. Saturation current is compared with the part's saturation current only: a part that does not state it scores 0 |
| mounting (non-connector requests, `MOUNTING`) | 0.05 | SMD/THT same +0.05, different -0.05 |
| form factor named by the request's words (`FORM_FACTOR`) | 0.10 | `FormFactor.compatible`: +0.10, another known class -0.10 (excluded anyway), unknown -> unverified (`form factor`). A class implied only by the package is not scored again |
| low DCR preference (`LOW_DCR`) | up to 0.04 | `0.04 / (1 + DCR / 10 mΩ)`, score only: lower DCR ranks higher among otherwise equal parts |
| on-resistance preference (`LOW_RDS_ON`, MOSFET and transistor requests) | up to 0.04 | `0.04 / (1 + R_DS(on) / 10 mΩ)`, score only, always on for these families: a lower on-resistance ranks higher among the parts that meet the request; a part that states none earns nothing |
| voltage overshoot (`@Overshoot` on `VOLTAGE_RATING`) | -0.25 per octave, at most -0.50 | a voltage rating above 2x the request (above 3x for capacitors) loses `0.25 * min(2, log2(part / (ratio * requested)))`, score only; see "Rating overshoot" |
| tolerance (`TOLERANCE`) | 0.10 | part tolerance <= requested -> full; looser -> -0.10 |
| family (`TYPE`): the same or a more specific family, or a family word in the part text | 0.05 | a different known family -0.05; the generic family (a `diode` part for a `schottky` request) 0 |
| lexical: share of free-text tokens found in mpn/description/attributes | 0.10 | score only, never part of the match grade |
| tie-break bonuses | up to 0.05 | log10(stock) scaled, has price, JLCPCB "Basic"/"Preferred" library |

Clamp to [0,1].

**Match grade** (`DeterministicRanker.assess`, the `match` field of every search result part): the signals the part
earned (tie-break excluded) divided by what a part matching every stated **and verified** parameter earns: the weights
of the stated typed signals (primary value, package, polarity, load capacitance, dielectric, technology, each rating
with an equal share of 0.10, mounting, form factor, tolerance, family; for connector and USB requests the stated
connector attributes), clamped to [0,1]
and rounded to 2 decimals in responses. It is computed before the blend, is absolute (not rank-normalised) and does
not influence the order. For `Thin film resistor, 5.36k 0805 0.1%` the Mouser parts `TNPW08055K36BEEA` and
`RN73C2A5K36BTDF` grade 1.0 (tolerance `.1%`, package from the MPN, technology from the category).

**Unverified constraints** (`Assessment.unverified`, the `unverified` of every search result part): a stated
constraint the part does not state at all (primary value, package (or a package KINA cannot read), polarity, a
crystal's load capacitance, dielectric, technology, each rating, mounting, tolerance, the element count of an array; for connectors positions, gender, orientation, pitch, connector type and
mounting; for USB requests type, pin configuration, standard, gender, mounting and orientation) is listed by name
(`current`, `saturation current`, `package`...) and left out of **both** sides of the grade, so `match` reflects only
verified constraints. `match` 1.0 with a non-empty `unverified` list is therefore **not** a confirmed fit (the third
audit saw TME `JRPI0804M-2R2M`, which states no current, score 0.67 for an 8 A request because the unknown rating
counted as a match). Such a part ranks below every complete part (tier, section 3.3) and is not counted in
`exact_matches`. A part that states none of the stated constraints has `match` null. Family words stay in the grade
(an unknown family earns nothing). Free-text keywords never do (since 2026-10-07): they rank (the lexical signal of
the score) but neither lower `match` nor block an exact match. Before, every chassis query (`heatsink`, `housed`,
`chassis`, `mount` missing from the part text) reported `exact_matches` 0 on every distributor although parts met 25 W
and 100 ohm with nothing unverified.

**Mismatches** (`DeterministicRanker.mismatches`, the `mismatches` of every search result part): the stated
parameters the part is known not to satisfy, in plain words (a part with a hard conflict is excluded, so returned
parts show relaxable misses, and ratings with `allow_below_spec`): `capacitance: 10uF instead of 22uF`, `package: 1210
instead of 1206` (an inductor, crystal or oscillator), `polarity: P-channel instead of N-channel`, `load capacitance:
8pF instead of 18pF`, `dielectric: X5R instead of X7R`, `technology: tantalum polymer instead of aluminium polymer`,
`voltage: 16V below 25V`, `dcr: 40mohm above 20mohm`, `tolerance: 10% instead of 1%`, `mounting: THT instead of SMD`,
`family: ...`, `elements: single instead of array` / `elements: array instead of single`, `form factor: chip instead of
chassis`, and for non-USB connectors
positions, gender, pitch and orientation. An attribute the part does not state is not a mismatch (it is unverified).
The distributor entry's `exact_matches` counts the returned parts with `match` 1.0, nothing unverified and not below
spec (null when the query was not understood): every typed constraint of the request (family, value, tolerance,
ratings, package, mounting, technology, dielectric, polarity, subtype, elements, form factor) met and verified.

**Rating overshoot** (`domain.Overshoot`, declared on `ConstraintKind.VOLTAGE_RATING`; user decision 2026-10-07). A
part whose voltage rating is far above the request meets it (ratings are minimums, `match` stays 1.0, the part is
never excluded) but ranks below parts closer to the request: above `ratio` times the requested voltage it loses
`perOctave` (0.25) of score per octave beyond the ratio, up to `maxOctaves` (2) octaves, so at most 0.5. The ratio is
declared per policy family on the kind: `@Overshoot(ratio = 2.0)` for every family and `@Overshoot(ratio = 3.0,
families = CAPACITOR)` (MLCC derating makes 2x to 3x normal practice). The penalty is taken from the deterministic score
(after its clamp) and again from the final score, like the quantity penalties (section 3.3), so the model cannot undo
it: for `GaN FET 100V` the 600 V `IGI60L1111B1MXUMA1` (-0.40) ranks below every 100 V to 200 V part (before, only the
closeness preference, at most 0.05, separated it from the 100 V parts, so the model could lift it above them; the
parts that state no voltage still rank after it, by tier). `Assessment.overshoot` carries it. The small
closeness preference of the ratings (`W_RATING_EXCESS`) stays for every rating.

**Below spec** (`Assessment.belowSpec`, `belowSpecDistance`): every stated rating is a hard limit. A part whose
**known** voltage, current (an inductor's rated current), saturation current, power, maximum temperature or lifetime is
below the request, or whose DCR is above a stated maximum, is below spec. By default it is excluded before ranking and
counted in `excluded_below_spec` (the third audit saw 22uF X7R 1206 parts at 6.3 to 16 V returned for a 25 V request,
and 10 mA to 3 A beads for a 6 A request). With `allow_below_spec` (MCP and REST parameter, default false) it is
returned with `below_spec: true` and its `mismatches`, in the last tier, ordered by its distance from the target:
the sum of `|ln(part / requested)|` over the failed ratings (16 V before 10 V before 6.3 V for 25 V), never by the
blended score. Regulator and Zener voltages must match (a different value is a hard conflict, never below spec), and
so must fuse currents (a mismatch, not excluded: the decision of 2026-10-07 does not name fuses). An impedance is the primary value of a ferrite bead (matched within 1 %), not a minimum: KINA has no syntax for an
impedance minimum, so it is never treated as below spec. A rating the part does not state is unverified, not below spec.

**Declarative constraint model** (`domain.ConstraintKind`). Every attribute a request can state and a
part can match is one constant of the `ConstraintKind` enum. The relaxation policy and the matching rule are declared
on the constant with two annotations, and the policy, the ladder, the check, the score, the grade and the reporting read
them. Change a rule on the constant, not in the ranker.

- `@Relax(strategy, order, cost, families)` (repeatable) declares how a kind may be loosened. `RelaxStrategy`:
  `NEVER` (hard: a known contradicting value excludes the part), `LADDER` (the relaxation ladder may loosen it, in
  `order`; a miss is a mismatch and is reported in `constraints_relaxed`), `SOFT` (ranked and graded, never excludes,
  no ladder step), `BELOW_SPEC` (a rating: never relaxed downward, only `allow_below_spec` returns parts below it, a
  minimum above the request is preferred less the further it is) and `PREFERENCE` (score only, never in the grade, for
  example `low dcr`). `cost` is the score cost of a relaxed miss (-1, the default everywhere, uses the `@Match`
  weight). Each kind has one general declaration without `families`: the strategy when a family does not make the
  kind hard. Family-specific declarations name the families (`Relax.ALL` for every family). Resolution: the family's
  own declaration, then `Relax.ALL`, then the general one; `kina.search.hard-constraints.<family>` then replaces a
  family's declared table (listed kinds `NEVER`, the others their general strategy).
- `@Overshoot(ratio, perOctave, maxOctaves, families)` (repeatable, on an `AT_LEAST` rating) declares a score penalty
  for a rating far above the request ("Rating overshoot" above); resolved like `@Relax`: the family's own declaration,
  then the general one.
- `@Match(mode, tolerance, weight, group, inGrade, scope, order, report)` declares the comparison. `MatchMode`:
  `EQUAL`, `EQUAL_IGNORE_CASE`, `AT_LEAST` and `AT_MOST` (relative `tolerance`), `WITHIN` (relative `tolerance`),
  `COMPATIBLE` (a graded comparison such as the technology) and `CUSTOM` (the constant's own comparator: the type and
  family, the package, form factor, elements, connector and USB rules). A match earns `weight`, a miss loses it, an
  attribute the part does not state is unverified. The ratings form the group `rating`: its weight (0.10) is shared
  equally at run time between the ratings the request states. `inGrade = false` marks a score-only signal. `scope`
  limits a signal to part, connector or USB requests. `order` is the order of the score and of the `unverified` list,
  `report` the order of the `mismatches`; the enum order is the check order (the first conflict names the part's entry
  in `excluded_by_constraints_detail`).
- Each constant declares how the wanted value is read from `ParsedQuery` and the actual value from `PartFeatures` (a
  domain interface that `ParametricExtractor.Features` implements), and its comparator when the mode is `CUSTOM` or
  `COMPATIBLE`. The comparators use the component vocabularies of the search package through `MatchContext` (package
  equivalence, technology compatibility, form factor classes, USB standards, connector types), so `domain` does not
  depend on `search`.
- Kinds that share a label are one attribute matched differently by request: `EXACT_VOLTAGE` (the Zener and fixed
  regulator voltage, exact within 2 %, hard for diodes, regulators and the default family) and `VOLTAGE_RATING` (a
  minimum everywhere else); `CURRENT` and `EXACT_CURRENT` (a fuse); `MAX_DCR` (the maximum DCR rating), `DCR` (the
  relaxable DCR preference name) and `LOW_DCR` (the "low DCR" score preference); the connector and USB variants of
  gender, orientation and mounting. The policy names are the labels of the kinds that are `NEVER` for some family or
  `LADDER`.

**Hard constraints** (declared on `ConstraintKind` with `@Relax`, read by `search.ConstraintPolicy`; user decision
2026-10-07; they replace the former strict constraints). A hard constraint is never relaxed: when the request states it and the part's **known** value
contradicts it, the part is excluded before ranking and counted in `excluded_by_constraints` and, under the first
constraint it contradicts, in `excluded_by_constraints_detail` (`ConstraintPolicy.check`). A part that does not state
the attribute stays, unverified and ranked below complete matches (section 3.3); a package string KINA cannot read
(`Recognizers.isRecognisedPackage` false, e.g. LCSC `SMD,8.5x8mm`) never contradicts. A relaxable constraint is never a
reason to exclude: the ladder may loosen it (section 3.2), the part lists the miss in `mismatches` and the response the
constraint in `constraints_relaxed`. The decided table (`ConstraintPolicy.DEFAULT_HARD`) is rendered from the
declarations below; `ConstraintTableDocumentationTest` renders it again and fails when this copy differs. The policy
family of a request (`PolicyFamily.of`) is its family, `diode` for Schottky, Zener, TVS and LED, `transistor` for
MOSFETs, `usb` for USB connectors and `default` for any other or unknown family. Hard kinds are listed in check order,
relaxable ones in ladder order. Every other policy kind is soft for the family: it is ranked and graded, a miss is a
mismatch, it never excludes a part and the ladder has no step for it (for example the value of a diode). The notes on
each kind (resistance, capacitance, "a higher USB standard is accepted"...) are in the checks below.

| Family | Hard (never relaxed) | Relaxable (ladder order) |
|---|---|---|
| `resistor` | `type`, `value`, `package`, `mounting`, `technology`, `form factor`, `elements` | `dielectric`, `tolerance`, `orientation`, `tcr`, `esr`, `dcr` |
| `capacitor` | `type`, `value`, `package`, `mounting`, `technology`, `form factor`, `elements` | `dielectric`, `tolerance`, `orientation`, `tcr`, `esr`, `dcr` |
| `inductor` | `type`, `value`, `mounting`, `technology`, `form factor` | `dielectric`, `package`, `tolerance`, `orientation`, `tcr`, `esr`, `dcr` |
| `ferrite` | `type`, `value`, `package`, `mounting`, `elements` | `dielectric`, `tolerance`, `orientation`, `tcr`, `esr`, `dcr` |
| `crystal` | `type`, `value`, `load capacitance`, `mounting` | `dielectric`, `package`, `tolerance`, `orientation`, `tcr`, `esr`, `dcr` |
| `oscillator` | `type`, `value`, `mounting` | `dielectric`, `package`, `tolerance`, `orientation`, `tcr`, `esr`, `dcr` |
| `diode` | `type`, `voltage`, `package`, `mounting` | `dielectric`, `tolerance`, `orientation`, `tcr`, `esr`, `dcr` |
| `transistor` | `type`, `polarity`, `package`, `mounting`, `technology` | `dielectric`, `tolerance`, `orientation`, `tcr`, `esr`, `dcr` |
| `regulator` | `type`, `voltage`, `package`, `mounting` | `dielectric`, `tolerance`, `orientation`, `tcr`, `esr`, `dcr` |
| `connector` | `type`, `package`, `mounting`, `connector type`, `positions`, `pitch`, `gender` | `dielectric`, `tolerance`, `orientation`, `tcr`, `esr`, `dcr` |
| `usb` | `type`, `mounting`, `usb type`, `pin configuration`, `usb standard`, `gender` | `dielectric`, `package`, `tolerance`, `orientation`, `tcr`, `esr`, `dcr` |
| `default` | `type`, `polarity`, `value`, `voltage`, `package`, `mounting`, `technology`, `form factor`, `elements` | `dielectric`, `tolerance`, `orientation`, `tcr`, `esr`, `dcr` |

Ratings (minimum voltage, current, saturation current, power, temperature, lifetime; maximum DCR) are not in the
table: they are always hard downward ("Below spec" below) and only `allow_below_spec` returns parts below them.

The checks, in this order (the first conflict names the part's entry in the detail):

- **type**: two known families that are not the same and not a specialisation of one another
  (`Recognizers.compatibleFamilies`: `schottky` fits a `diode` request and the reverse; `crystal` and `oscillator` do
  not fit each other; a resistor never fits a capacitor request), a `standard` diode against a Schottky, Zener, TVS or
  LED (either way), a `fixed` against an `adjustable` regulator. Wording mined on 2026-10-07 (`ComponentTypes`):
  JLCPCB categories `Crystals` and `Crystal Oscillators` (a JLCPCB crystal's description often says "Crystal
  Oscillator": the category decides), `Temperature Compensated Crystal Oscillators (TCXO)`, `Switching Diodes`,
  `Diodes - General Purpose`, `Diodes - Fast Recovery Rectifiers`, descriptions `1 N-channel`, `NPN`, `Fixed`,
  `Adjustable`; TME `Crystal; 16MHz`, `Generator: quartz` (category `Resonators and Generators` names both kinds,
  so the description decides), `Transistor: N-MOSFET`, `SMD P channel transistors`, `Diode: Schottky rectifying`,
  `Diode: rectifying`, `Kind of voltage regulator: fixed, LDO, linear`; Mouser `Crystals`, `MEMS Oscillators`,
  `Standard Clock Oscillators`, `MOSFETs N-Channel`, `N-CH`, `Schottky Diodes & Rectifiers`, `Rectifiers`. The part
  side reads the type attributes (`Type of transistor`, `Type of diode`, `Kind of voltage regulator`...), the category
  and the description, never the MPN.
- **polarity**: both known and different (an N+P pair is `complementary`, a type of its own).
- **value**: the primary value (`ConstraintKind.primaryKind`: the frequency for crystals and oscillators, where a
  capacitance is the load capacitance; else capacitance, resistance, inductance, impedance; the frequency also when
  the family is unknown) within 1 %, an impedance also at the same test frequency when both state one. Reported by its
  kind (`capacitance`, `frequency`...).
- **voltage**: the exact voltage of a Zener diode or a regulator within 2 %, against the voltages the part states as
  its specification (`Features.voltages`: the output or Zener voltage attribute, else every single voltage of the
  description, because JLCPCB lists values unlabelled and sorted as text, `1.1V@(800mA) 15V 1A 3.3V` for an
  AMS1117-3.3; ranges `25.1V~28.9V`, `1.8V - 3.3V` and conditioned values `100nA@0.8V` are left out). A regulator's
  `Output voltage` and a Zener's `Zener voltage` attribute come before any other voltage attribute.
  **Voltage of transistors and diodes** (`ParametricExtractor.LARGEST_VOLTAGE_FAMILIES`: transistor, MOSFET, diode,
  Schottky): when no attribute states the voltage, the rating (Vds, Vrrm) is the **largest** single voltage of the
  description, not the first. JLCPCB lists a MOSFET's gate threshold before its drain-source rating when that sorts
  first as text (`1.45V 1.4W 30V` for AO3400A, `1 N-channel 1.2V ... 20V` for SI2302, `±20V` after `60V` for 2N7002),
  and a diode's forward voltage likewise. Conditioned values (`1.25V@150mA`, `500uA@40V`) and ranges are left out. A
  largest value below 3 V is a threshold or forward voltage: the voltage is then unknown (unverified), never a wrong
  rating. Before 2026-10-07 the first voltage was used and `SOT-23 N-channel MOSFET 30V` excluded 37 of 40 LCSC parts.
  Zener, TVS, LED and regulator voltages are specifications and keep the rules above.
- **load capacitance**: a crystal's capacitance within 1 %.
- **package**: `Recognizers.samePackage`: the same `packageKey` (`SOT-23-3L` == `SOT-23` == `TO-236AB`), can sizes
  within 0.2 mm in diameter and length; a conflict only when the part's package is recognised.
- **mounting**: SMD vs THT (a hybrid USB part never conflicts). **technology**: `TechnologyVocabulary.compare` = -1.
  **elements**: a resistor, capacitor or ferrite request that does not ask for an array (`ParsedQuery.elements` null)
  excludes arrays and networks (the part's `Elements`, see "Arrays" below), e.g. the 4-line bead array
  `BLA31BD121SN4D` for `120 ohm 100MHz 1206 ferrite bead 6A`.
- **form factor** (reported as `form factor`): the request's class (its package's class when the package is hard and
  has one, else the class its words name) against the part's class, `FormFactor.compatible` false. A part whose class
  cannot be read stays; it is `unverified: ["form factor"]` when the request's words named the class (a class implied
  only by the package adds nothing: the package is already unverified), and ranks below every verified part.
- **connector type** (`ConnectorRecognizer.typesMatch` false), **positions**, **pitch** (0.03 mm), **gender**: known
  and different. **usb type**: different USB types, or a non-USB connector; **pin configuration**: a stated (not
  implied) configuration against the part's canonical one (17P is 16); **usb standard**: a lower class or a
  power-only part (`UsbVocabulary.compare` <= 0; a higher class is accepted).
- a family may also make `dielectric`, `tolerance` (looser) or `orientation` hard.

The table is configurable per family (`kina.search.hard-constraints.<family>`: the listed kinds become `NEVER`, every
other kind of that family takes its general strategy, so a ladder kind stays relaxable and any other kind becomes soft;
unknown families and names are ignored with a warning). The deprecated `kina.search.strict-constraints`
(`KINA_STRICT_CONSTRAINTS`) is still read: `mounting`, `technology` or `elements` missing from a non-empty list are
removed from every family, with a warning; empty or unset means the defaults. Distributor data is taken as given: KINA
does not decode part numbers to check a distributor's values and does not compare distributors with each other (user
decision 2026-10-07).

**Form factor** (`search.FormFactor`, decision 2026-10-07; resistors, capacitors, inductors, ferrite beads and
requests without a family). A bounded set of classes, read from distributor text only:

| Class | Part side | Request side |
|---|---|---|
| `chip` | imperial chip package (`0201`..`2512`, also `1225`, `2728`, `4527`, `0612`, `1218` in a resistor's description); for resistors without a body package also `SMD`, `SMT`, surface mount (Mouser `Wirewound Resistors - SMD`) | a chip package (`0805`) |
| `through_hole` | leaded bodies: `axial`, `radial`, `leaded`, LCSC `AXIAL-0.6`. A bare `Through Hole` or `THT` is not enough: Mouser files TO-220 and TO-247 power resistors under `Through Hole` | (none) |
| `chassis` | `chassis`, `heatsink`, `bolt`, `screw` (with or without `mount`; never `screw terminal`), `aluminium housed` / `aluminum housed` / `alum housed`, TME `with heatsink; screw`, LCSC package `Bolt Mount`, Mouser `- Chassis Mount` | the same words |
| `power_package` | `SOT-227`, `TO-220`, `TO-247`, `TO-218`, `TO-126`, `TO-264`, `TO-3P` | the same packages |
| `power_smd` | `TO-263` / `D2PAK`, `TO-252` / `DPAK`, `D3PAK`, `TO-268` | the same packages |

The part's package decides first, then the chassis and leaded-body words of its description, category and package
field, then (resistors only) the chip codes and the SMD words. A request's package with a class wins over its words
(`300W 10 ohm power resistor SOT-227 heatsink` asks for `power_package`); `parsed.form_factor` shows the class the
words name. Compatibility: a `chassis` request accepts `chassis` and `power_package` parts (both are screwed to a
heatsink), a `power_package` request accepts both as well, every other class only itself. A different known class is
a hard conflict for resistors, capacitors, inductors and the default family (never relaxed); for an inductor only the
request's words count (its package is relaxable). The live probe of 2026-10-07 for query B returned Mouser `Wirewound
Resistors - SMD 10 OHMS 5%` and `Thick Film Resistors - SMD 2watt ... 1225` for a SOT-227 request because their package
was unreadable; they are now excluded under `form factor`.

**Packages are imperial** (user decision 2026-10-07). A bare four-digit chip code anywhere (query, description,
attribute, package field) is the imperial code: `0603` is imperial 0603, never metric 0603 (imperial 0201). A metric
code is recognised only where the source labels it as millimetres: TME `Case - mm` (the TME mapper labels a bare value
`1608 mm`, and `ParametricExtractor` reads the `Case - mm` attribute as metric), Mouser `0603 (1608 metric)`, `3216M`,
`0603mm` (`Recognizers.imperial`, applied when a text is prepared), and it is converted to the imperial code
(`1005`->`0402`, `1608`->`0603`, `2012`->`0805`, `3216`->`1206`, `3225`->`1210`, `4532`->`1812`, `5025`->`2010`,
`6332`->`2512`, `0603`->`0201`, `0402`->`01005`; also `4520`->`1808`, `5750`->`2220`, `5764`->`2225`, `2016`->`0806`,
`2520`->`1008`, `4516`->`1806`). `P=2.54mm` is a pitch, not a case code. The canonical `Package` attribute,
`parsed.package`, the ranker comparison and the distributor phrases use the imperial code; a bare metric code in a
query (`10uF 2012 MLCC`) is a free-text keyword, not a package. Crystal and oscillator sizes (`3225`, `2520`,
`2016`...) are their own codes; when TME states only the body (`Body dimensions: 3.2x2.5x0.8mm`) the size code is
derived from it for crystals and oscillators. The package of a can capacitor is its size (`D6.3 x 5.8mm`); a request
states it as `6.3x5.4mm` (aluminium electrolytic or polymer requests) or `D6.3xL5.4mm` / `Ø6.3x5.4mm`, and it matches
within 0.2 mm in diameter and length. Live 2026-10-07: TME lists the Ferrocore `DLG-1005-470` power inductor with
`Case - mm: 1005`, which reads as imperial 0402; distributor data is taken as given. Technology compatibility: `polymer aluminium` /
`aluminium polymer` is aluminium polymer (OS-CON included, THT or SMD); tantalum polymer and plain aluminium
electrolytic contradict it; a part that says only `polymer` is not comparable; a bare `polymer` request accepts
aluminium polymer and tantalum polymer; hybrid polymer neither matches nor contradicts an aluminium request.

**Arrays** (`PassiveDetails.elements`, the `Elements` attribute; resistors, capacitors and ferrite beads; never for
common-mode chokes and filters): the count from an attribute (`Number of elements`, `Number of resistors`, `Number of
lines`, `Number of channels`...), from the text (`4 lines`, `4 elements`, `8 resistors`; JLCPCB networks
`0603x4`, `0402x8` in the package or description: the chip code directly followed by `x` and the count, with no letter
after the count, so `0805 X7R` is never a 7-element array), else `array` when a word says so (`array`, `network`, TME `Kind of ferrite:
array`, categories such as `Resistor Networks, Arrays`). A request asks for an array with `array`, `network`
(`ParsedQuery.ANY_ELEMENTS`, `parsed.elements` `"array"`) or a count (`4 lines`, `2 elements`; `parsed.elements` `"4"`);
a different known count is a mismatch, an array of unstated size for a counted request is unverified.

**Quantity, low stock and MOQ** (`quantity`, default 1; `RankingService.penalty`): a part with less stock than the
quantity loses `kina.search.quantity.stock-shortfall-penalty` (0.3) and ranks after every part with enough stock (tier,
section 3.3). A **low-stock** part (`Availability.isLowStock`: stock below `kina.search.low-stock-threshold`, default 10,
or below twice the quantity, but not below the quantity) loses `kina.search.quantity.low-stock-penalty` (0.3) and
reports `availability.status` `low_stock` (the third audit saw `IHLP2525CZER2R2M01` with 2 pieces rank first for an
unstated quantity). A minimum order quantity above the quantity loses up to `kina.search.quantity.moq-penalty` (0.3),
scaled by `min(1, log10(moq / quantity) / 3)`, **also for a quantity of 1**: an MOQ of 10 costs 0.1, 100 costs 0.2,
1000 or more the whole 0.3 (the audit saw `CS3216X7R226K100NRI` with an MOQ of 2000 rank high for one piece; before,
the penalty applied only above a quantity of 1). These penalties and the lifecycle ones are taken from the
deterministic score and again from the final score (section 3.3). Every part gets `ordered_quantity` (the quantity
raised to the minimum order quantity and to the smallest price bracket, rounded up to the order multiple),
`unit_price_at_quantity` (the bracket that applies, from all brackets) and `total_price` when the quantity is above 1
(always with `detail=full`). The `search_parts` description tells the LLM to pass `quantity` for BOM work.

**Lifecycle** (`Availability.lifecycleOf`, the part's `lifecycle`): `active`, `new` (TME `NEW`, Mouser "New Product"),
`supply_constrained` (TME `HARDLY_AVAILABLE`) or `last_time_buy` (TME `AVAILABLE_WHILE_STOCKS_LAST`, Mouser end of life
/ obsolete / not recommended for new designs). It is separate from `availability.status`, which is the stock situation
only (`in_stock`, `low_stock`, `limited`, `last_units`; `special_order` and `external_warehouse` when not excluded):
before, both said `supply_constrained` for TME `HARDLY_AVAILABLE`, which TME uses for limited market availability even
with 150 000 pieces in stock. Weights (configurable):

| `lifecycle` | Penalty | Key |
|---|---|---|
| `active`, `new` | 0 | |
| `supply_constrained` | 0.05 | `kina.search.lifecycle.supply-constrained-penalty` |
| `last_time_buy` | 0.1 | `kina.search.lifecycle.last-time-buy-penalty` |

Before, the penalty only lowered the deterministic score, whose rank-normalised half could be outweighed by the model;
it is now also subtracted from the final score, so a supply-constrained or last-time-buy part always loses score
against an otherwise equal active part (`AuditRoundThreeTest`).

**Requested part numbers** (`search.PartNumbers`, `ParsedQuery.partNumbers`, `parsed.part_numbers`). A free-text
keyword that is part-number shaped is a requested part number, kept as sent (`uP1966E`): letters and digits mixed, at
least 5 characters, at least one letter and two digits, no decimal number, and not a standard, interface or quantity
word (`RS485`, `AEC-Q200`, `IP67`, `USB3`, `DDR4`, `2-channel`, `1000pcs`, `100ppm`, `24AWG`, a range such as
`100-240V`); values, units, packages, dielectrics and family words are already taken by the parser. A part is the
requested one when its MPN or distributor part number equals the token or starts with it, compared on letters and
digits only (`PartLookupResult.normalize`: `EPC2218` requests `EPC2218A`, `ERA-6AEB5361V` and `ERA6AEB5361V` are
equal). It ranks first in its distributor (section 3.3). Each distributor entry reports `requested_part_found`: null
when the query names no part number (or the distributor failed and returned nothing), true when a returned in-stock
part is the requested one for every part number, false otherwise; false comes with a `hint` per missing part number:
`EPC23101 is not listed in stock at MOUSER; the parts below are keyword matches.`, or, when the distributor listed it
but it was left out, why: `EPC2218 is listed at MOUSER (EPC2218A) but was left out: voltage 80V below 100V (pass
"allow_below_spec": true to see it).` (a hard constraint: `... it contradicts the requested type`). The part number
also stays a keyword (lexical score). A query that is only a part number is still not understood (below).

**Keyword-only queries** (`ParsedQuery.understood()`): when the parser recognises no family and no typed constraint
(value, rating, tolerance, dielectric, package, mounting, technology, connector attribute, element count), e.g.
`asdfqwerty zz9` or a bare part number, the response has `query_understood: false` and a `hint`, every part's `match`
is null and every `exact_matches` is null, so "no parametric understanding" is distinguishable from "no matches"
(before, arbitrary parts graded 0.5).

For connector queries (`ParsedQuery.isConnector()`) the primary value signal is replaced by connector signals
(the `CONNECTOR`-scoped kinds of `ConstraintKind`). Each applies only when both the query and the
part know the attribute; an unknown attribute scores 0, never a penalty. Package, dielectric, ratings (e.g. `3A`),
family, lexical and tie-break signals stay as above.

| Signal | Weight | Rule |
|---|---|---|
| positions (`POSITIONS`) | 0.30 | same total -> +0.30; different -> -0.30 |
| rows (`ROWS`) | -0.10 | both known and different (`1x6` vs `2x3`: same positions, -0.10); same -> 0 |
| rows unspecified (`SINGLE_ROW`) | -0.08 | header query with positions but no rows, part with more than one row (a "6 position header" is usually 1x6) |
| gender (`GENDER`) | 0.20 | same -> +0.20; different -> -0.20 |
| orientation (`ORIENTATION`) | 0.15 | right angle vs vertical: same -> +0.15; different -> -0.15 |
| pitch (`PITCH`) | 0.15 | within 0.03 mm (2.54 mm == 0.1") -> +0.15; different -> -0.15 |
| connector type (`CONNECTOR_TYPE`) | 0.10 | same -> +0.10; different (female header vs pin header vs IC socket) -> -0.10; a gender-less `header` is compatible with pin/female/box headers and `connector`/`usb` with anything (0) |
| mounting (`CONNECTOR_MOUNTING`) | 0.05 | query THT/SMD vs part mounting: same -> +0.05; different -> -0.05 |

With these weights, for `female header 1x6 right angle 2.54mm`: 1x6 female right angle (0.90 + family + tie-break) >
2x3 female right angle (-0.10) > 1x6 female straight (-0.30) > 1x10 female right angle (-0.60) = 1x6 male right angle
(-0.60, gender and type) > unrelated parts.

USB connector requests (`ParsedQuery.Connector.isUsb()`) use USB signals instead of the connector signals above
(the `USB`-scoped kinds of `ConstraintKind`; other connectors keep the table above). Unknown attributes score 0.

| Signal | Weight | Rule |
|---|---|---|
| USB type (`USB_TYPE`) | 0.30 | Type-C vs Micro-B vs Type-A...: same +0.30, different -0.30 (a Micro-B never outranks a Type-C for a Type-C request); a non-USB connector (pin header, RJ45) -0.30; a generic `usb` on either side: 0 |
| pin configuration (`PIN_CONFIGURATION`) | 0.20 | canonical configuration on both sides (17P/18P == 16, 25P/26P == 24): same +0.20, different -0.20; half (±0.10) when the request only implies it through the standard |
| USB standard (`USB_STANDARD`) | 0.20 | same speed class +0.20; a higher class than requested +0.10; a lower class or a power-only part -0.20; `USB 3.x` (generation not stated) matches any 3.x class; a Gen 2 request against a `USB 3.x` part is unknown |
| gender (`USB_GENDER`) | 0.15 | receptacle vs plug |
| mounting style (`USB_MOUNTING`) | 0.10 | requested mid-mount/hybrid/top-mount vs the part's style (a part that does not say: 0; hybrid vs `Fully SMT`: -); else SMD/THT vs the part's mounting, a hybrid part counts half (+0.05) |
| orientation (`USB_ORIENTATION`) | 0.05 | horizontal/right angle vs vertical |
| features (`WATERPROOF`, `BOARD_LOCK`, `POWER_ONLY`) | +0.03 each | waterproof, board lock, power only: requested and present |

For `USB-C receptacle 16 pin SMD USB 2.0`: Type-C 16P = 17P = 18P USB 2.0 receptacle SMD (0.95) > 14P USB 2.0 (0.55) >
24P USB 3.1 (0.45: wrong pins, higher class) > 6P power only (0.15) > Micro-B 5P (-0.05). For `USB Type-C 24 pin USB
3.1`: 24P = 25P = 26P USB 3.x (0.70) > 16P/17P (-0.10, also when labelled "USB 3.1"). Scores are clamped to [0,1], so
several complete matches can tie at 1.0 and keep their distributor order.

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

**Model files (`CrossEncoderModel`, `ModelDownloader`, `ModelLayout`).** Two modes:

- **Bundled (Docker image, the default deployment).** The image build fetches and verifies the model (section 11) into
  `/opt/kina/cross-encoder` and sets `KINA_CROSS_ENCODER_MODEL_URL=/opt/kina/cross-encoder` and
  `KINA_CROSS_ENCODER_AUTO_DOWNLOAD=false`. The running container never contacts Hugging Face or any other host for
  the model.
- **Download (local runs).** Directory `kina.ranking.cross-encoder.model-dir` (env `KINA_CROSS_ENCODER_MODEL_DIR`;
  default `${KINA_JLCPCB_DATA_DIR}/../cross-encoder`, i.e. `./data/cross-encoder`), filled from `model-url`.

Layout of the Hugging Face repository (both modes):

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
- Startup never blocks: after `ApplicationReadyEvent` KINA logs the model source once
  (`Cross-encoder model: local directory <dir> used in place (read only, no download)`, or the download directory and
  source), then a virtual thread downloads missing files (download mode only) from
  `kina.ranking.cross-encoder.model-url` (default `https://huggingface.co/cross-encoder/ms-marco-MiniLM-L6-v2/resolve/main/`),
  loads the tokenizer and the session and warms it up. Each file is streamed to `<dir>/tmp/*.part`, its size checked
  against `X-Linked-Size` or `Content-Length`, its SHA-256 against `X-Linked-Etag` (LFS files), then moved atomically
  into place. `model.json` records `source`, `repo`, `revision` (`X-Repo-Commit`), `variant`, `onnx_file`,
  `downloaded_at` and per-file size and SHA-256. Timeouts: connect 10 s, `download-timeout` (10 min) per file.
- Files already present are used as they are (pre-provisioned or offline directory). `auto-download: false`
  (env `KINA_CROSS_ENCODER_AUTO_DOWNLOAD`; false in the image and in the tests) never downloads.
- `model-url` may point to any HTTP(S) directory with the same layout, or to a local directory (absolute path or
  `file:` URI), which is used in place and read only: no `tmp/`, no manifest update, nothing written, so a read-only
  directory works (the bundled one is mode 0555/0444). The int8 choice by CPU flags and the fp32 fallback apply to a
  local directory exactly as to a downloaded one; a file that is not there is skipped.
- Precedence: `KINA_CROSS_ENCODER_MODEL_URL` set by the operator (`.env` or compose `environment`) overrides the image
  default `/opt/kina/cross-encoder`. A local path is used in place. An HTTP(S) URL is downloaded into `model-dir` only
  when the operator also sets `KINA_CROSS_ENCODER_AUTO_DOWNLOAD=true`; otherwise the load fails without a network call.
- A failed attempt is logged once (again only when the reason changes) and retried every `check-interval` (1h): at
  ERROR when no network source is involved (local directory or `auto-download: false`; the files are missing or
  unusable, e.g. `model directory /opt/kina/cross-encoder does not exist (vocab.txt missing)`), at WARN for a failed
  download. Until a model loads, searches report `ranking: "fallback"`, note `"cross-encoder model not loaded yet"`.
- Memory: the int8 session adds roughly 100 to 200 MB of native memory to the JVM process (fp32 about 150 to 250 MB).

**Evaluation.** `CrossEncoderEvaluationTest` (runs only when `KINA_CROSS_ENCODER_TEST_MODEL_DIR` names a model
directory; `KINA_CROSS_ENCODER_TEST_VARIANT` = int8|fp32; `KINA_CROSS_ENCODER_TEST_SCORES_DIR` writes score files
for `scripts/research/evaluate.py`) ranks every query of `docs/research/data/ranking-eval.jsonl` through the
production `RankingService` and asserts blended NDCG@10 >= the deterministic ranker's and >= 0.90.

**Fine-tuned model (optional).** `scripts/ranking/finetune_cross_encoder.sh` (see its README) fine-tunes the model on
the study's rubric labels in a CPU container (about 4 minutes on 16 cores), exports fp32 and both int8 files and
writes `model.json`; point `KINA_CROSS_ENCODER_MODEL_URL` at the resulting directory, or bake it into the image with
the `CROSS_ENCODER_SOURCE` build argument (section 11). Trained on synthetic labels only
(evaluation set unseen): cross-encoder alone 0.890 (int8) / 0.893 (fp32), blend 0.918 / 0.914, against 0.878 / 0.874
and 0.913 zero-shot.

### 3.6 Rate limiting

Distributor APIs that answer with a rate limit are waited for and retried instead of failing the request at once,
within a hard per-request cap.

**Request deadline.** Every incoming request gets one deadline = start + `kina.search.max-request-duration`
(default `2m`): one `search_parts` / `GET /api/v1/parts/search`, one whole `search_parts_batch` /
`POST .../search/batch` (all queries share it), one `get_part` / `GET /api/v1/parts/{distributor}/{partNumber}`. It is a
`ro.alacrity.kina.distributor.Deadline` (`System.nanoTime()`-based); each distributor fetch gets its own `fork()` (same
deadline, separate wait accounting) and passes it to `DistributorClient.search/getPart(..., Deadline)`.

**Budgets.** `kina.search.distributor-timeout` (20 s) still bounds a fetch's *active* work. The distributor deadline is
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

### 3.7 Observability

KINA exports Prometheus metrics with `micrometer-registry-prometheus`. Spring Boot's actuator runs on its own port,
`management.server.port` (`KINA_METRICS_PORT`, default 9090), and serves `/actuator/health`, `/actuator/info` and
`/actuator/prometheus` there. The main port (8080) has no actuator endpoints at all.

**Security.** The management port has no authentication. `SecurityConfig.managementSecurityFilterChain` (order 0)
matches `EndpointRequest.toAnyEndpoint()`, which with a separate port only matches requests of the management server,
and permits them without authentication, CSRF or session. The machine and web chains still cover the main port, where
`/actuator/**` is a 404 (development) or a login redirect (production). The port must be reachable only from the
monitoring network: compose publishes it on `${KINA_METRICS_BIND:-127.0.0.1}:${KINA_METRICS_PORT:-9090}:9090`. When
`management.server.port` is unset or equal to `server.port`, KINA logs a WARN at startup, because the actuator would
then be public on the main port. The metrics hold no secrets: no tokens, keys, e-mail addresses, queries or part
numbers, only counts with the bounded tags below.

**Metrics.** Prefix `kina_`. The distributor tag is the enum name (`LCSC`, `TME`, `MOUSER`). The `type` tag of the
search counters is the component type of the query: the parser family (`ParsedQuery.family()`, section 3.4) in lower
case, one of `capacitor`, `resistor`, `inductor`, `ferrite`, `diode`, `schottky`, `zener`, `led`, `mosfet`,
`transistor`, `regulator`, `opamp`, `comparator`, `mcu`, `crystal`, `oscillator`, `connector`, `fuse`, `tvs`, `relay`,
`switch` (the families of `Recognizers`, `QueryParser.families()`), or `unknown` when the parser recognised no family.
No other value is possible (connector and USB sub-types are not tags), so the tag set stays bounded.

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `kina_searches_total` | counter | | search requests: one `search_parts`, one REST search, one whole batch |
| `kina_search_queries_total` | counter | `type` | search queries, every query of a batch; `type`: a family or `unknown` |
| `kina_search_duration_seconds` | timer (`_count`, `_sum`) | | time to answer a search request |
| `kina_distributor_calls_total` | counter | `distributor`, `outcome`, `type` | one per distributor and search query: `ok` or the result's `error` (`rate_limited`, `timeout`, `unavailable`, `bad_response`, `not_configured`); `type`: a family or `unknown` |
| `kina_distributor_duration_seconds` | timer | `distributor` | one successful distributor search page, rate-limit waits included |
| `kina_parts_fetched_total` | counter | `distributor`, `type` | in-stock parts received on distributor search pages (not from the cache); `type`: a family or `unknown` |
| `kina_parts_returned_total` | counter | `distributor`, `type` | parts in search responses; `type`: a family or `unknown` |
| `kina_distributor_rate_limited_responses_total` | counter | `distributor` | HTTP calls answered with a rate limit (429, 502/503/504 with `Retry-After`, Mouser `TooManyRequests`), retried or not |
| `kina_distributor_rate_limit_waits_total` | counter | `distributor` | waits before a retry (backoff, `Retry-After` or shared cool-down) |
| `kina_cache_search_lookups_total` | counter | `distributor`, `status`, `type` | Postgres cache use per distributor fetch: `hit`, `miss`, `partial`, `bypassed`, `stale`; `type`: a family or `unknown` |
| `kina_cache_parts_added_total` | counter | `distributor` | new `cached_parts` rows |
| `kina_cache_parts_refreshed_total` | counter | `distributor` | existing `cached_parts` rows fetched again in full and overwritten |
| `kina_cache_stock_refreshes_total` | counter | `distributor`, `outcome` | cached parts whose stock and prices a refresh asked for (section 3.2 step 5, `get_part`): `ok`, `out_of_stock` (marked sold out), `failed` (the call failed or did not answer for the part) |
| `kina_cache_parts` | gauge | `distributor` | `cached_parts` rows (Mouser, TME) |
| `kina_cache_parts_fresh` | gauge | `distributor` | rows in stock whose stock and prices are younger than `kina.cache.ttl` |
| `kina_cache_parts_stale` | gauge | `distributor` | the other rows, kept for their metadata (stock and prices older than `kina.cache.ttl`, or sold out) |
| `kina_cache_parts_stale_stock` | gauge | `distributor` | rows in stock whose stock and prices are older than `kina.cache.ttl` (returned with `stale: true` unless a refresh succeeds) |
| `kina_cache_searches` | gauge | `distributor` | `cached_searches` rows |
| `kina_cross_encoder_executions_total` | counter | | cross-encoder (MiniLM) model runs |
| `kina_cross_encoder_candidates_total` | counter | | candidates scored by the model |
| `kina_cross_encoder_duration_seconds` | timer | | time of one model run |
| `kina_ranking_fallback_total` | counter | `reason` | queries ranked with the deterministic fallback: `disabled`, `unavailable` (model not loaded), `busy`, `timeout`, `batch_budget`, `failed` (from `ranking_note`) |
| `kina_tool_calls_total` | counter | `tool` | MCP tool calls (`search_parts`, `search_parts_batch`, `get_part`, `list_distributors`, `ping`) |
| `kina_tool_errors_total` | counter | `tool` | tool calls that ended with an error (for example a blank query) |
| `kina_api_requests_total` | counter | `endpoint` | `/api/**` requests by matched path pattern, e.g. `/api/v1/parts/search` |
| `kina_logins_total` | counter | `outcome` | interactive OIDC logins: `ok`, `denied` |
| `kina_login_denied_total` | counter | `reason` | refused logins: `group`, `email_domain`, `email_unverified`, `email_missing` |
| `kina_oauth_tokens_issued_total` | counter | `grant` | access tokens issued by `/oauth/token`: `authorization_code`, `refresh_token` |
| `kina_membership_rechecks_total` | counter | `outcome` | re-checks that asked the identity provider: `member`, `not_member`, `grant_invalid`, `unavailable`, `no_upstream_token` |
| `kina_users_known` | gauge | | `users` rows that are not blocked |
| `kina_users_revoked` | gauge | | blocked users (`access_revoked_at` set) |
| `kina_tokens_active` | gauge | | access tokens neither revoked nor expired |
| `kina_jlcpcb_database_parts` | gauge | | parts in the JLCPCB database (0 when unknown) |
| `kina_jlcpcb_database_age_seconds` | gauge | | age of the JLCPCB download (0 when unknown) |
| `kina_jlcpcb_downloads_total` | counter | `outcome` | JLCPCB downloads: `ok`, `failed`, `interrupted` |

The timers (`kina_search_duration_seconds`, `kina_distributor_duration_seconds`) and `kina_searches_total` (a batch
mixes types) have no `type` tag. Counts recorded before 0.5 have no type: migration V9 moved them to `type="unknown"`
(Prometheus refuses two meters of one name with different tag keys), so `sum without (type) (...)` continues across
the upgrade.

Spring Boot's own JVM, HTTP server, Hikari and process metrics are exported as well. Gauges cannot end in `_total` in
the Prometheus exposition format, so the cache gauges are `kina_cache_parts` and `kina_cache_searches`.

**Persistence.** Counters and timers live in memory (`MetricsStore`: one `AtomicLong` per name and canonical tag
string, exported as Micrometer `FunctionCounter`s and `FunctionTimer`s). `MetricsPersistence` saves every value that
changed to `metrics_counters` (section 8) every `kina.metrics.save-interval` (30 s) and on graceful shutdown, with one
`INSERT ... SELECT FROM unnest(...) ON CONFLICT DO UPDATE SET value = GREATEST(old, new)` statement. On startup it
adds the stored values to the in-memory ones, so each series continues where the previous run stopped: the counters
only grow across restarts, and `rate()` and `increase()` work without a reset (an unclean stop loses at most the last
30 s). A timer is stored as two rows, `<name>:count` and `<name>:nanos`. Nothing is saved until the restore succeeded,
so a run that could not read the table never overwrites it with smaller values. A database failure never affects a
request: it is logged once at WARN and retried at the next tick. Gauges are not persisted; `MetricsGauges` recomputes
the database gauges every 30 s with a few `count(*)` queries and reads the JLCPCB gauges from memory at every scrape.
Several KINA instances sharing one database would each keep their own counts, and the larger value would win in the
table; run one instance per database.

**Instrumentation.** `KinaMetrics` is the facade; business code makes one call per event and never fails because of a
metric. Classes default to `KinaMetrics.NOOP` and get the bean through a setter, so tests that build them by hand need
no metrics. Points: `PartSearchService` (request and batch, from the assembled response), `PageCollector` (one call
per page), `StockRefresher` (stock refresh outcomes), `RateLimitRetry` (a process-wide `RateLimitRetry.Listener` for
rate-limit responses and waits, because the clients create their retry objects themselves), `CrossEncoderPartRanker` (model runs), `PartCacheRepository.upsertAll`
(counts the existing rows first to tell added from refreshed), `KinaMcpTools` (`KinaMetrics.toolCall`), an
interceptor on `/api/**`, `OidcUserSynchronizer`, `TokenController`, `MembershipVerifier.recheck` and
`JlcpcbDatabaseManager.runDownload`.

**JSON.** `GET /api/v1/metrics/summary` (bearer token like the rest of `/api`) returns `{"summary": {...},
"counters": [{"name", "tags", "value"}]}`: the key counters and every counter and timer in Prometheus naming (timer
sums in seconds). `list_distributors` and `GET /api/v1/distributors` carry the same key counters as `metrics`:
`{"searches", "search_queries", "tool_calls": {tool: n}, "cache_added": {distributor: n}, "rate_limited_calls":
{distributor: n}, "cross_encoder_executions", "search_queries_by_type": {type: n}}` (`search_queries_by_type` is
`kina_search_queries_total` per `type`, sorted). They count since the first start against this database.

## 4. MCP tools

Server name `kina`, version from the build. Tools (JSON Schema generated from the method
parameters; descriptions are read by the LLM, keep them precise):

| Tool | Parameters | Returns |
|---|---|---|
| `search_parts` | `query` (string, required), `max_results` (int 1..50, default 10, per distributor), `distributors` (array of `LCSC\|TME\|MOUSER`, default all configured), `bypass_cache` (bool, default false: skip cache lookup, still refresh the cache), `quantity` (pieces to order, default 1, section 3.4), `detail` (`compact` default, `full`), `allow_below_spec` (bool, default false, section 3.4 "Below spec") | `SearchResponse` |
| `search_parts_batch` | `queries` (array of `{query, max_results, quantity}`, 1..20), `distributors`, `bypass_cache`, `detail`, `allow_below_spec` | `{ "results": [SearchResponse...] }` |
| `get_part` | `distributor` (case-insensitive), `part_number` (distributor part number, or the MPN; spaces are tried as hyphens, then removed: `HCMA0703 2R2 R` -> `HCMA0703-2R2-R`, `HCMA07032R2R`; characters a distributor refuses are dropped, and a TME `E_INPUT_PARAMS_VALIDATION_ERROR` on `symbols[]`/`mpns[]` is `not_found`), `bypass_cache`, `quantity`, `detail` (`full` default: every attribute; `compact`) | `PartLookupResponse` `{found, distributor, part_number, cache, error, reason, identity, part, attributions}`; `found: false` instead of a tool error with `reason` `not_found` (unknown), or with `error` (and `reason` null) when the lookup failed. `reason` `out_of_stock`: listed without ships-now stock; `identity` `{part_number, manufacturer, mpn, description}`, and, when the distributor gives the part's data, `part` with `stock` 0, prices as listed and `availability.status` `out_of_stock` (`found: true`: the part number was requested explicitly, section 2); a Mouser catalogue part without a Mouser part number (`N/A`) has the identity only (`found: false`). The listed part is cached with `in_stock = false` and never served from the cache. Lookup per distributor: Mouser one `Exact` part-number search, matched by Mouser number then MPN after normalisation (upper case, letters and digits only; Mouser itself answers `ERA6AEB5361V` with `667-ERA-6AEB5361V`); TME `/products?symbols[]=`, on a miss once more with `mpns[]` (as written and normalised; TME matches `manufacturer_symbols` exactly); LCSC `"LCSC Part"`, on a miss the `"MFR.Part"` trigram index with 3-character chunks of the normalised MPN at the three phases, compared after normalisation (most stock first) |
| `list_distributors` | none | `DistributorStatusResponse`: per distributor `configured`, `available`, `detail` (LCSC: JLCPCB file, part count, source date, download state), `uses_cache`, `cached_parts`, `max_results_per_search`, `jlcpcb{...}` (LCSC); `cache{ttl, parts, fresh_parts, searches, oldest_fetch}`; `ranking{mode, cross_encoder_enabled, ready, model, model_variant, model_revision, model_dir, threads, avg_latency_ms, last_error, max_candidates, weight, timeout}`; `metrics{searches, search_queries, tool_calls, cache_added, rate_limited_calls, cross_encoder_executions, search_queries_by_type}` (section 3.7). Never calls the Mouser/TME APIs |
| `ping` | none | `{"status":"ok","version":"<build version>"}` (wiring/health check, already implemented) |

`SearchResponse` JSON (snake_case):

```json
{
  "query": "10uF X7R 0805",
  "parsed": {"family": "capacitor", "capacitance": "10uF", "dielectric": "X7R", "package": "0805", "keywords": []},
  "query_understood": true,
  "ranking": "blended",
  "ranking_note": null,
  "currencies": ["EUR"],
  "attributions": ["Product data provided by Mouser Electronics"],
  "distributors": [
    {
      "distributor": "MOUSER",
      "total_results": 113,
      "fetched": 50,
      "returned": 10,
      "cache": "hit",
      "error": null,
      "parts": [
        {"rank": 1, "match": 1.0, "distributor": "MOUSER", "part_number": "603-CC0805MKX77BB106",
         "manufacturer": "YAGEO", "mpn": "CC0805MKX7R7BB106", "description": "...",
         "stock": 76689, "stock_as_of": "2026-10-06T09:12:44Z", "min_order_qty": 1, "order_multiple": 1,
         "prices": [{"qty": 1, "unit_price": 1.40, "currency": "EUR"}, {"qty": 10, "unit_price": 0.853, "currency": "EUR"}, {"qty": 50, "unit_price": 0.631, "currency": "EUR"}],
         "availability": {"status": "in_stock", "note": "Ships now from stock."}, "lifecycle": "active",
         "datasheet_url": "...", "product_url": "...",
         "attributes": {"Capacitance": "10uF", "Dielectric": "X7R", "Package": "0805", "...": "..."}}
      ],
      "fallback_query": null,
      "rate_limit_waited_ms": 0,
      "distributor_query": null,
      "excluded_by_constraints": 0,
      "excluded_by_constraints_detail": {},
      "excluded_below_spec": 0,
      "excluded_below_spec_detail": [],
      "out_of_stock_matches": 0,
      "query_terms_dropped": [],
      "constraints_relaxed": [],
      "exact_matches": 7,
      "requested_part_found": null
    }
  ]
}
```

Response-level fields: `query_understood` (false when nothing typed was recognised, section 3.4 "Keyword-only
queries"; then `hint` says what to change, every `match` and `exact_matches` is null), `hint` (also, for an understood
query, when distributors returned nothing: which hard constraints could not be met there, section 3.2 "Empty after the
hard set"; omitted otherwise),
`currencies` (the distinct price currencies of the returned parts, sorted; LCSC USD, TME and Mouser EUR; KINA never
converts prices) and `attributions` (the notice of every distributor whose parts the response returns, enum order,
section 3.2 "Attributions"; `get_part` carries the same field with the distributor's notice when it returns a part or
an identity, else an empty list).

**Detail** (`ResponseDetail`, `detail`): `compact` (default for the searches; `get_part` and its REST endpoint default
to `full`) returns per part `rank`, `match`, `below_spec` (only when true), `mismatches` and `unverified`
(omitted when empty), `distributor`, `part_number` (the distributor's number), `manufacturer`, `manufacturer_id` (the
distributor's own manufacturer id as it provides it: TME only, omitted when absent), `mpn`, `description`, `stock`,
`stock_as_of` (the part's `fetchedAt` to the second, both levels), `stale` (only when true: stock and prices older
than `kina.cache.ttl` that could not be refreshed, section 3.2 "Cache model"), `min_order_qty`,
`order_multiple`, `prices` (3 brackets), with `quantity` > 1 `ordered_quantity`, `unit_price_at_quantity` and
`total_price`, `availability`, `lifecycle`, `datasheet_url`, `product_url` and only the canonical attributes
(`ParametricExtractor.CANONICAL_KEYS`, computed with `extract`, so raw duplicates such as TME `Operating voltage`,
`Case - inch`, `Case - mm` are left out). `full` adds `score`, `category`, `package`, `photo_url`, every distributor attribute
and `extra` (TME `product_status`, `category_id`, `packing`, `price_type`, `tax_rate`; Mouser compliance and lifecycle
fields; LCSC library type), and always the order fields. Fields a level leaves out are omitted from the JSON (null
`category`, `package` and `photo_url` are omitted in `full` as well).

**Availability** (every part): `{"status", "note"}`, the stock situation only: status `in_stock`, `low_stock` (stock
below `kina.search.low-stock-threshold`, default 10, or below twice the quantity), `limited` (stock below `quantity`),
`last_units` (no restocking: TME `AVAILABLE_WHILE_STOCKS_LAST`, Mouser end of life / obsolete / NRND),
`special_order` (TME `ONLY_FOR_SPECIAL_ORDER`, `CANNOT_BE_ORDERED`), `external_warehouse` (TME), `out_of_stock`
(stock 0: a part requested explicitly by its part number and listed without stock, section 2) or `stale` (stock and
prices older than `kina.cache.ttl`, the part's `stale` flag; the note says when they were last confirmed and the last
known stock, then the other notes); the note is plain
sentences (TME `HARDLY_AVAILABLE` as a supply warning, `MOQ_VALID_WHILE_STOCKS_LAST`, `DANGEROUS`/`OVERSIZED`, the
Mouser maximum order quantity when it is below the quantity; with `full` also TME `NEW`/`PROMOTED`, the Mouser
lifecycle and reel option, the JLCPCB library type Basic/Preferred/Extended). `supply_constrained` is no longer an
availability status: it is a `lifecycle` (section 3.4).

`cache` is `hit`, `partial`, `miss`, `bypassed`, `not_applicable` or `stale` (the live search failed, `error` is set,
and the parts come from the query's expired cached list, section 3.2 "Cache model").
`fetched`, `excluded_by_constraints`, `excluded_by_constraints_detail` (per hard constraint, each part under its first
conflict, `{"capacitance": 12, "package": 3}`; empty object when nothing was excluded), `excluded_below_spec`,
`excluded_below_spec_detail` (up to 5 of the `excluded_below_spec` parts, closest to the request first, each
`{"part_number", "mpn", "rating", "part_value", "requested"}`, e.g. `{"part_number": "65-EPC2218A", "mpn": "EPC2218A",
"rating": "voltage", "part_value": "80V", "requested": "100V"}`; the first failed rating of each part, the value as KINA
read it from the distributor's data; empty with `allow_below_spec`), `out_of_stock_matches` (null when unknown),
`query_terms_dropped`, `constraints_relaxed` (they replace the former `relaxed`), `exact_matches` (null when the query
was not understood), `requested_part_found` (section 3.4 "Requested part numbers") and `hint` (when the entry has no
parts for an understood query and no `error`, and when a requested part number is not among the parts) are described
in sections 3.2 and 3.4. `parsed` also carries `polarity` and `subtype` when stated or implied and `part_numbers` when
the query names part numbers (section 3.4).

`rank` orders the list. `score` is the blend of rank-normalised scores (section 3.3), relative to the other candidates,
so the last of four exact matches can show `0.00`; a reader took that for "does not fit", so `score` is returned with
`detail: "full"` only (omitted in `compact`). `match` (0 to 1) says how well the part satisfies the stated typed
parameters (section 3.4 "Match grade"; 1.0 = every stated parameter matches; free-text words do not count); it is on
every search result part. Both are null (omitted) for `get_part`. `parsed.form_factor` is the form factor the request's
words name (`chassis`), omitted when none. `parsed.technology` is the recognised technology of a passive (omitted when absent); parts carry it as the
`Technology` attribute.
`total_results` is what the distributor reported for the query (in-stock where the API can filter),
`fetched` is every in-stock part KINA received for the query before any exclusion, `returned` is at most `max_results`
and at most `fetched - excluded_by_constraints - excluded_below_spec` (section 3.2 "Counts").
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
| `GET /api/v1/parts/search?q=&max_results=&distributors=LCSC,TME&bypass_cache=&quantity=&detail=` | `SearchResponse`; `quantity` 1..10 000 000 (default 1), `detail` `compact` (default) or `full` |
| `POST /api/v1/parts/search/batch` | body `BatchSearchRequest` (snake_case: `queries[{query, max_results, quantity}]`, `distributors`, `bypass_cache`, `detail`), returns `{results: [...]}` |
| `GET /api/v1/parts/{distributor}/{*partNumber}?bypass_cache=&quantity=&detail=` | `PartResponse`; the part number is the rest of the path, so TME symbols containing `/` work unencoded; an MPN works as for `get_part`; a part listed without stock is returned with `stock` 0 and `availability.status` `out_of_stock`; 404 problem with `reason` `not_found` or `out_of_stock` (identity only: then also `identity`) |
| `GET /api/v1/distributors` | same as `list_distributors` |
| `GET /api/v1/metrics/summary` | key counters and every persisted counter and timer as JSON (section 3.7) |
| `GET /actuator/health`, `GET /actuator/info`, `GET /actuator/prometheus` | management port only (`KINA_METRICS_PORT`, 9090), no authentication (section 3.7); not served on the main port |

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
`OIDC_CLIENT_SECRET` (lazy discovery via `/.well-known/openid-configuration`). Scopes: `openid profile email`, plus
`kina.security.oidc.extra-scopes`, plus `offline_access` when membership re-checks are enabled (required groups and an
encryption key, section 7.1) and the provider lists it in `scopes_supported`. No provider-specific code. Every call to
the provider made by KINA's code (code exchange, userinfo, JWKS, re-checks) has a 5 s connect and read timeout
(`OidcHttp`); ID tokens are verified with the provider's JWKS and the asymmetric algorithms it advertises
(`OidcIdTokenDecoders`, RS256 when it advertises none).
On login `OidcUserSynchronizer` applies the group and e-mail-domain policy (`OidcAccessPolicy`, section 7.1) and then
upserts `users(issuer, subject, email, display_name, last_login_at)`, sets `membership_checked_at` and clears
`access_revoked_at`. A refused identity is not signed in: the login ends on `/login-denied?reason=<code>` (403, one
sentence per reason, section 7.1), and an existing user row is blocked (`access_revoked_at`, all tokens revoked).
The login's authorized client lives in the HTTP session (`UpstreamTokenCapturingClientRepository`), which hands the
provider's refresh token to `MembershipVerifier` (stored encrypted, section 7.1).
`/api/**` and `/mcp/**` accept bearer tokens only. The actuator endpoints are on the management port and need no
authentication (management chain, section 3.7). `RevokedUserSessionFilter` ends the web session of a user blocked
after signing in; the next page view starts a new login (and so a new group check).

**Access tokens** (`AccessTokenService`): plaintext `kina_` + 43 base64url chars from 32 random
bytes; stored as SHA-256 hex in `access_tokens.token_hash`; `token_prefix` = first 12 chars for display;
shown to the user exactly once. Lifetime: `kina.tokens.validity` (default `30d`) for tokens created in the web UI
("static tokens"), `kina.oauth.access-token-validity` (default `1h`) for tokens issued by `/oauth/token`.
`last_used_at` is updated at most once per minute per token. Revocation sets `revoked_at` and, in the same
transaction, revokes every `oauth_refresh_tokens` row whose `access_token_id` is that token
(`AccessTokenRepository.revoke*`): a user revoking an OAuth-issued token in the web UI, or a client revoking its access
token at `/oauth/revoke`, must not leave a refresh token that mints a new one.
`AccessTokenRepository.revokeAllForUser` revokes every access and refresh token of a user (group membership lost).
The same table and service issue the OAuth access tokens (`oauth_client_id` set, name `MCP: <client_name>`).

**Bearer filter**: `Authorization: Bearer <token>` -> lookup by hash -> must be unexpired and
unrevoked -> the user must not be blocked (`users.access_revoked_at` null) -> for a static token under group
authorisation, `MembershipVerifier.allowsStaticToken` (section 7.1; may start a background re-check, never waits for
the provider) -> `KinaPrincipal(userId, displayName, tokenId)` with `ROLE_USER`. Failures return 401 with
`WWW-Authenticate: Bearer realm="kina", resource_metadata="<public>/.well-known/oauth-protected-resource"`
(plus `error="invalid_token"` when a token was presented). This header is what makes Claude's MCP
connector discover the authorization server.

**Public origin**: `server.forward-headers-strategy=framework` so `X-Forwarded-Proto/Host/Port/Prefix` (and
`X-Forwarded-For` for the client address) are honoured; `PublicUrlResolver` returns `kina.public-base-url` when set,
otherwise the request's forwarded origin. All metadata, redirect URIs and `resource_metadata` values use it.

**Web UI** (Thymeleaf, CSRF on): `GET /` first explains the main path (Claude's connector with the `/mcp` URL, or
`claude mcp add` + `/mcp` sign-in in Claude Code), then lists the current user's tokens (name, prefix, created,
expires, last used, revoked). Static tokens are presented as the option for scripts calling the HTTP API and for Claude
Code on machines without a browser. `POST /tokens` creates one (name required) and renders the plaintext once,
`POST /tokens/{id}/revoke`. `kina.tokens.ui-enabled=false` (`KINA_TOKENS_UI_ENABLED`) replaces `/` with a short page
explaining that access is through Claude's connector and that static tokens are disabled, and makes `POST /tokens` a
404 (existing tokens keep working until they expire or are revoked). Keep the pages plain and dependency-free (inline
CSS).

## 7. OAuth 2.1 authorization server for MCP clients

All endpoints are relative to the public origin. `/.well-known/**`, `/oauth/register`, `/oauth/token` and
`/oauth/revoke` are anonymous and CORS-enabled (as is `/mcp`); `/oauth/authorize` runs in the web chain (signed-in
user).

| Endpoint | Behaviour |
|---|---|
| `GET /.well-known/oauth-protected-resource` and `/.well-known/oauth-protected-resource/mcp` | `{"resource": "<public>/mcp", "authorization_servers": ["<public>"], "bearer_methods_supported": ["header"], "scopes_supported": ["kina"], "resource_name": "KINA"}` |
| `GET /.well-known/oauth-authorization-server`, `/.well-known/oauth-authorization-server/mcp`, `/.well-known/openid-configuration` | `issuer`, `authorization_endpoint=/oauth/authorize`, `token_endpoint=/oauth/token`, `registration_endpoint=/oauth/register`, `revocation_endpoint=/oauth/revoke`, `response_types_supported=["code"]`, `response_modes_supported=["query"]`, `grant_types_supported=["authorization_code","refresh_token"]`, `code_challenge_methods_supported=["S256"]`, `token_endpoint_auth_methods_supported=["none","client_secret_basic","client_secret_post"]`, `scopes_supported=["kina"]`, `client_id_metadata_document_supported` (`true` unless `kina.oauth.trusted-client-metadata-hosts` is empty; section 7.2) |
| `POST /oauth/register` (RFC 7591, anonymous) | Rate-limited per client IP (section 7.3; 429 + `Retry-After`). Accepts `redirect_uris` (required, absolute, no fragment; `https`, `http://localhost`/`127.0.0.1` any port, or custom schemes), `client_name`, `token_endpoint_auth_method` (default `none`), `grant_types` (default `["authorization_code","refresh_token"]`), `response_types` (`["code"]`), `scope`. Returns 201 with `client_id`, `client_secret` (only when auth method is not `none`), `client_id_issued_at`, `client_secret_expires_at: 0` and the echoed metadata. Clients are stored in `oauth_clients`. |
| `GET /oauth/authorize` | Requires `response_type=code`, a known `client_id` (registered, or an `https` metadata document URL on a trusted host, section 7.2), exact `redirect_uri` match (metadata-document clients: loopback redirect URIs may use any port), `code_challenge` + `code_challenge_method=S256` (PKCE mandatory), optional `scope`, `state`, `resource`. Requires an authenticated user who is not blocked (dev: automatic admin; prod: OIDC login with the group check, then return). Renders a consent page (client name, user, token lifetimes, the client's metadata host, a warning for loopback redirects, Approve/Deny); trusted metadata-document clients skip it (section 7.2). Approve -> authorization code (32 random bytes base64url, SHA-256 stored, 10 min validity, bound to client, redirect URI, user, challenge, scope, resource) -> 302 `redirect_uri?code=&state=`. Deny -> `error=access_denied`. Invalid or untrusted client, invalid document, or unregistered redirect URI -> error page, never a redirect. |
| `POST /oauth/token` (form-encoded) | `grant_type=authorization_code`: verify client (secret if registered with one; public clients need none), code unused and unexpired, `redirect_uri` equal, PKCE `S256(code_verifier) == code_challenge`, user not blocked; mark code used; issue access token (`kina.oauth.access-token-validity`, default `1h`, via `AccessTokenService`) and refresh token (`kina.oauth.refresh-token-validity`, default `30d`, `oauth_refresh_tokens`); record `oauth_clients.last_used_at`. Response `{"access_token","token_type":"Bearer","expires_in","refresh_token","scope"}`, `expires_in` is the real lifetime (3600). `grant_type=refresh_token`: the group membership is re-checked first when due (section 7.1; a refusal is `invalid_grant`, which makes Claude ask the user to reconnect), then rotate (revoke old refresh token and its access token, issue new pair). Errors follow RFC 6749 (`invalid_request`, `invalid_client` (401), `invalid_grant`, `unsupported_grant_type`). |
| `POST /oauth/revoke` (RFC 7009) | Revokes an access or refresh token belonging to the authenticated client; always 200. Revoking a refresh token also revokes its current access token; revoking an access token also revokes the refresh token issued with it. |

`resource` (RFC 8707) is stored and echoed; a value outside the public origin is logged, not rejected.

### 7.1 Group authorisation and membership re-checks

KINA stays the authorization server Claude talks to and delegates the login to the organisation's OIDC provider
(Authentik in the reference setup; nothing provider-specific in code). Who may use KINA is decided by the provider's
group membership, checked at three points:

| Point | Where | What |
|---|---|---|
| Login | `OidcUserSynchronizer` + `OidcAccessPolicy` | The groups claim (`kina.security.oidc.groups-claim`, default `groups`) is read from the ID token; only when the ID token does not carry it, from userinfo. The name is looked up literally (namespaced claims such as `https://example.com/groups` work), then as a dotted path (`realm_access.roles`). An array of strings or a single string; group names compare case-insensitively, a leading `/` is ignored. The user must be in at least one of `required-groups`. Optional `allowed-email-domains`: the address must be present, in one of them and, unless `require-verified-email` is `false`, not marked unverified (reasons below). Failure: `/login-denied?reason=<code>`, no session, existing user blocked (`access_revoked_at`, all tokens revoked), logs as below. Success: `membership_checked_at = now`, `access_revoked_at` cleared. |
| Refresh grant | `TokenController` -> `MembershipVerifier.checkRefreshGrant` (synchronous) | When `membership_checked_at` is older than `membership-recheck-interval` (default `1h`), re-check at the provider before rotating. With 1-hour access tokens, a removed member is cut off within about an hour. |
| Static tokens | `BearerTokenAuthenticationFilter` -> `MembershipVerifier.allowsStaticToken` (asynchronous) | Same interval; the re-check runs on a virtual thread (at most one per user), the request is never blocked. OAuth access tokens are only checked for `access_revoked_at` (they live one hour and are re-checked at refresh). |

**Login refusals.** `OidcAccessPolicy.evaluateLogin` checks the e-mail address first (only with
`allowed-email-domains`), then the groups, and returns one of four reasons. The OAuth2 error codes stay
`kina_email_domain_not_allowed` (the three e-mail reasons) and `kina_group_membership_required`.

| Reason code | When | Page text |
|---|---|---|
| `email_missing` | No non-blank string `email` claim in the ID token or userinfo (or one without `@` and domain). With `kina.security.oidc.email-from-preferred-username=true` (default `false`, `OIDC_EMAIL_FROM_PREFERRED_USERNAME`), `preferred_username` and then `upn` count as the address when they contain `@`; `email` always wins. | "Your identity provider did not send an e-mail address for your account, so KINA cannot check it. Ask the administrator to enable the e-mail scope for the KINA application." |
| `email_domain` | The domain after the last `@`, trimmed and lower-cased, is not one of the allowed domains (also lower-cased, a leading `@` ignored). Checked before `email_verified`: a wrong domain is refused either way. | "You signed in with an account from <domain>, but KINA only accepts <allowed domains>." |
| `email_unverified` | `email_verified` (ID token, then userinfo) is `false` or the string `"false"` (any case). A missing flag counts as verified. Only with `kina.security.oidc.require-verified-email=true` (default, `OIDC_REQUIRE_VERIFIED_EMAIL`); with `false` the flag is ignored (DEBUG log with the subject only) and only the domain counts. Authentik's built-in `email` scope mapping (`goauthentik.io/providers/oauth2/scope-email`) always sends `email_verified: false`, so with Authentik defaults every login is unverified: either select a custom `email` mapping that returns `true`, or turn the option off. | "Your e-mail address is marked as unverified by the identity provider." |
| `group` | Not in a required group, or no groups claim. | "Your account is not in a group that may use KINA (required: <groups>)." |

`OidcUserSynchronizer` throws a `LoginDeniedException` (an `OAuth2AuthenticationException`) that carries a
`LoginDenial(reason, domain, existingUserBlocked)`. The failure handler puts the `LoginDenial` in the HTTP session and
redirects to `/login-denied?reason=<code>`. The page shows the domain and the sentence "Tokens you created earlier no
longer work" only from that session attribute (read once, then removed), and the sentence only when the identity
matched an existing user that this refusal blocked. A hand-made URL shows only the generic text of its `reason`
(`email`, the old form, still works). Logs, never with an address, a name or another claim value:

- WARN `OIDC login denied for subject <sub> (issuer <iss>): <detail>`, where the detail is, for example,
  `no e-mail claim in ID token or userinfo (claims present: aud, exp, iat, iss, name, sub); e-mail from
  preferred_username/upn is off`, `e-mail domain example.org not allowed`, `e-mail address not verified
  (email_verified=false)` or `not in a required group [ElectronicsEngineer]`.
- INFO `OIDC login denied for subject <sub>: ID token claims [names]; userinfo claims [names]; granted scopes [scopes]`.
  Instead of the userinfo names it says `userinfo not fetched`, with the cause when the discovery document has no
  `userinfo_endpoint`. Spring Security 7 fetches userinfo whenever the provider has that endpoint, whatever the
  granted scopes. A token response without `scope` is logged as `the token response named no scope`, and granted
  scopes without `email` get the hint `no email scope granted`.
- INFO at startup (production): `OIDC login policy: groups claim 'groups', required groups [...], allowed e-mail
  domains [...], e-mail from preferred_username/upn off`.

**Re-check**: `MembershipVerifier` decrypts the stored upstream refresh token (`UpstreamTokenCipher`, AES-256-GCM, key
`kina.security.oidc.token-encryption-key`, base64 of 32 bytes, user id as associated data; stored as `v1.` +
base64url(IV || ciphertext || tag)), redeems it at the provider's token endpoint from discovery (client
authentication as discovered, 5 s timeouts, HTTP/1.1), stores the rotated refresh token, verifies the new ID token with
the JWKS (same subject), and reads the groups claim from it or, if absent, from userinfo with the new access token
(same subject). Re-checks of one user are serialised (striped locks) so the upstream refresh token is never redeemed
twice concurrently.

| Outcome | Effect |
|---|---|
| Still a member | `membership_checked_at = now`. |
| Not a member, or the provider answers `invalid_grant` (user removed, disabled, grant revoked or expired) | User revoked: `access_revoked_at` set, upstream token deleted, every access and refresh token revoked; the refresh grant fails with `invalid_grant`; the web session ends. A later successful interactive login lifts the block. |
| Provider unreachable (network error, timeout, 5xx, other error responses, unverifiable ID token, discovery failing) | Access continues while `membership_checked_at` is younger than `membership-grace` (default `4h`); after that refresh grants fail with `invalid_grant` ("identity provider unreachable"), nothing is revoked, and the same refresh token works again once the provider is back (the refused attempt does not rotate it). Static tokens: denied once the grace period is over and the latest background re-check found the provider unreachable. |
| No upstream refresh token (no encryption key configured, the provider issued none, or the key changed) | Refresh grants (and static tokens) keep working while the last interactive login is younger than `relogin-interval-without-recheck` (default `24h`); then `invalid_grant` ("sign in again"), which makes Claude ask the user to reconnect, and the new login re-checks the group. A WARN at startup explains this when the key is missing. |

Enforcement is active only in production mode with at least one required group (`KinaProperties.Security.enforcesGroups`);
otherwise only `access_revoked_at` is honoured. After enabling it, existing users must sign in once before their
refresh grants and static tokens work again (they have no `membership_checked_at`). In the reference setup the
provider also denies non-members itself (group binding on the application), so KINA's checks are the second gate and
the only one that acts after the login.

Verified with a real Authentik 2026.8.3 (`scripts/e2e/authentik`): Authentik answers a refresh grant for a user who
was removed from the bound group with new tokens whose `groups` lack the group (the application binding is not
re-evaluated on refresh), so KINA's claim check is what revokes the user.

### 7.2 Client ID metadata documents

MCP authorization 2025-11-25 and draft-ietf-oauth-client-id-metadata-document: a client may use an `https` URL as its
`client_id`; the document at that URL describes it (Claude's "published identity"; Claude Code's is
`https://claude.ai/oauth/claude-code-client-metadata`).

- **Detection**: a `client_id` starting with `https://` is a metadata document URL (`ClientMetadataDocumentResolver`,
  via `OAuthClientLookup`, used by `/oauth/authorize`, `/oauth/token` and `/oauth/revoke`).
- **URL rules**: `https`, a host, a path other than `/`, no user info, no fragment, no `.`/`..` segments.
- **Trust**: the host must match `kina.oauth.trusted-client-metadata-hosts` (default `claude.ai`, `claude.com`,
  `*.anthropic.com`; `*.x` matches subdomains, not the apex). Other hosts are never fetched (SSRF protection):
  `/oauth/authorize` shows an error page, the token endpoint answers `invalid_client`.
- **Fetch**: GET with a 5 s budget for the whole exchange, no redirects, at most 1 MB, HTTP/1.1. 5xx and network errors
  count as unreachable; other statuses reject the client.
- **Validation**: a JSON object whose `client_id` equals the URL exactly; non-empty `redirect_uris` passing the
  registration rules; `token_endpoint_auth_method` absent or `none` (KINA supports no `private_key_jwt`, and shared
  secrets are forbidden, as are `client_secret`/`client_secret_expires_at`); `grant_types` default
  `["authorization_code"]`, must include `authorization_code`, unsupported types are ignored; `response_types` must
  include `code`; `client_name` (falls back to the host).
- **Redirect URI**: exact string match against the document, except that a loopback `http` entry
  (`http://localhost/callback`) matches any port (RFC 8252 section 7.3; Claude Code listens on an ephemeral port).
- **Cache and persistence**: a valid document is cached for `kina.oauth.client-metadata-cache` (default `1h`) and
  upserted into `oauth_clients` (`client_id` = `metadata_url` = the URL; codes and refresh tokens reference it). When the
  cache expired and the host is unreachable, the persisted copy is used, so refreshes survive a short outage.
- **Consent**: with `kina.oauth.auto-approve-trusted-clients=true` (default) the consent page is skipped for these
  clients, but only for non-loopback redirect URIs (for example `https://claude.ai/api/mcp/auth_callback`): any local
  program can listen on a loopback port and claim a trusted client's identity, so loopback redirects keep the consent
  page with a warning. The user must still be signed in and pass the group check.

### 7.3 Registration hygiene

- **Rate limit**: `RegistrationRateLimiter`, an in-memory token bucket per client IP for `POST /oauth/register`:
  `kina.oauth.register-rate-limit-per-minute` (default 30, refilled continuously; 0 disables). The client IP is
  `request.getRemoteAddr()`, which reflects `X-Forwarded-For` because forwarded headers are trusted
  (`server.forward-headers-strategy=framework`; the proxy must overwrite the header). Over the limit: 429,
  `Retry-After` in seconds, body `{"error":"too_many_requests"}`.
- **Usage tracking**: every token issuance sets `oauth_clients.last_used_at`.
- **Cleanup**: `OAuthClientMaintenance`, daily (first run 15 minutes after start), deletes dynamically registered
  clients (`metadata_url IS NULL`) whose `COALESCE(last_used_at, created_at)` is older than
  `kina.oauth.unused-client-retention` (default `90d`) and that have no unrevoked, unexpired access or refresh token.
  Their codes and refresh tokens go with them (`ON DELETE CASCADE`). Claude registers a new client on every fresh
  connection when it uses dynamic registration, so without this the table only grows.

## 8. Database schema (Flyway `V1__init.sql` to `V10__cached_search_requested_parts.sql`)

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
-- V8__cached_parts_metadata_and_stock.sql (section 3.2 "Cache model"): metadata is kept, stock and prices expire
ALTER TABLE cached_parts RENAME COLUMN fetched_at TO stock_fetched_at;          -- = Part.fetchedAt (stock_as_of)
ALTER INDEX cached_parts_fetched_idx RENAME TO cached_parts_stock_fetched_idx;
ALTER TABLE cached_parts ADD COLUMN metadata_fetched_at TIMESTAMPTZ NOT NULL;   -- last full fetch (retention)
ALTER TABLE cached_parts ADD COLUMN in_stock BOOLEAN NOT NULL DEFAULT true;     -- false: sold out, metadata only
CREATE INDEX cached_parts_metadata_fetched_idx ON cached_parts (distributor, metadata_fetched_at);
CREATE INDEX cached_searches_fetched_idx ON cached_searches (fetched_at);

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
-- V5__cached_search_out_of_stock.sql: matches without ships-now stock seen while building the list (NULL = unknown)
ALTER TABLE cached_searches ADD COLUMN out_of_stock_matches INTEGER;
-- V7__cached_search_constraints_relaxed.sql: what the relaxation ladder loosened to build the list (a JSON array such
-- as ["dielectric"]), so a cache hit reports the same constraints_relaxed (section 3.2); NULL = unknown (older rows,
-- derived from fallback_query when read)
ALTER TABLE cached_searches ADD COLUMN constraints_relaxed JSONB;
-- V10__cached_search_requested_parts.sql: the outcome of the direct lookup of each part number the query names
-- (section 3.2 "Requested part numbers"), by the part number as sent:
-- {"EPC2302": {"status": "listed", "part_number": "65-EPC2302"}}; status found (in stock, its number is also in
-- part_numbers), listed (no ships-now stock; its cached_parts row has in_stock = false) or not_found. NULL = nothing
-- looked up (older rows, queries without part numbers). It lives as long as the list (fetched_at is unchanged)
ALTER TABLE cached_searches ADD COLUMN requested_parts JSONB;

-- V4__group_authorisation.sql (section 7.1 to 7.3)
ALTER TABLE users
  ADD COLUMN access_revoked_at                 TIMESTAMPTZ,  -- blocked (failed group check / invalid_grant upstream)
  ADD COLUMN membership_checked_at             TIMESTAMPTZ,  -- last successful membership check
  ADD COLUMN upstream_refresh_token            TEXT,         -- provider refresh token, AES-GCM (UpstreamTokenCipher)
  ADD COLUMN upstream_refresh_token_updated_at TIMESTAMPTZ;
ALTER TABLE oauth_clients
  ADD COLUMN metadata_url TEXT,                              -- Client ID Metadata Document URL (= client_id)
  ADD COLUMN last_used_at TIMESTAMPTZ;                       -- last token issuance (cleanup)
CREATE INDEX oauth_refresh_tokens_user_idx ON oauth_refresh_tokens (user_id);
CREATE INDEX oauth_refresh_tokens_client_idx ON oauth_refresh_tokens (client_id);
CREATE INDEX access_tokens_oauth_client_idx ON access_tokens (oauth_client_id) WHERE oauth_client_id IS NOT NULL;

CREATE TABLE jlcpcb_database (
  id            INTEGER PRIMARY KEY CHECK (id = 1),
  library       TEXT NOT NULL,
  file_path     TEXT NOT NULL,
  downloaded_at TIMESTAMPTZ NOT NULL,
  size_bytes    BIGINT,
  part_count    BIGINT,
  source_date   TEXT
);

-- V6__metrics_counters.sql (section 3.7): Prometheus counters kept across restarts
CREATE TABLE metrics_counters (
  name       TEXT        NOT NULL,             -- Micrometer name; a timer is <name>:count and <name>:nanos
  tags       TEXT        NOT NULL DEFAULT '',  -- canonical "key=value,key=value", sorted by key
  value      BIGINT      NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (name, tags)
);
-- V9__metrics_counters_type_tag.sql (section 3.7): the stored rows of kina.search.queries, kina.distributor.calls,
-- kina.parts.returned, kina.parts.fetched and kina.cache.search.lookups get type=unknown ('' becomes type=unknown,
-- otherwise ",type=unknown" is appended, which keeps the sorted order); rows with a type tag are left alone
UPDATE metrics_counters
   SET tags = CASE WHEN tags = '' THEN 'type=unknown' ELSE tags || ',type=unknown' END, updated_at = now()
 WHERE name IN ('kina.search.queries', 'kina.distributor.calls', 'kina.parts.returned', 'kina.parts.fetched',
                'kina.cache.search.lookups')
   AND tags NOT LIKE 'type=%' AND tags NOT LIKE '%,type=%';
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
  (a number with spaces is sent with hyphens, then without spaces, until the answer matches);
  the answer is matched by `MouserPartNumber`, then `ManufacturerPartNumber`, both normalised (upper case, letters and
  digits only). Verified live 2026-10-05: `Exact` and `None` both answer `ERA6AEB5361V` (TME's spelling) with
  `667-ERA-6AEB5361V` / `ERA-6AEB5361V`, so no keyword retry is needed. A matching part with `AvailabilityInStock` 0 or
  missing is `OUT_OF_STOCK`; so is a catalogue part Mouser does not sell, answered with `MouserPartNumber` `N/A`
  (live: `ERA-6ARB5361V`), whose identity then has no part number.
- Stock refresh (`MouserClient.refreshStock`, section 3.2): the same `/search/partnumber` call with up to 10 Mouser
  part numbers joined by `|` (`"667-ERA-6AEB5361V|652-SRR1260-100M"` with `Exact` returned both, verified live
  2026-10-06), matched by `MouserPartNumber`; a listed part without ships-now stock reports stock 0, a number Mouser
  does not answer for stays unknown. One call per 10 returned parts, only for parts whose cached figures are older than
  `kina.cache.stock-ttl`.
- Keyword recall for parametric passive requests (tested live 2026-10-05, `searchOptions=InStock`): Mouser's keyword
  index drops parts whose record does not carry the package token as an indexed attribute. `5.36k 0805`,
  `5.36Kohm 0805`, `Thin film resistor, 5.36k 0805 0.1%`, `5.36 kOhm 0805 thin film`, `5.36K 0805 thin film resistor`
  and `resistor 5.36k 0805 0.1% thin film` (4 to 18 results) never returned the in-stock Panasonic `ERA-6AEB5361V`
  (description `Thin Film Resistors - SMD 0805 5.36Kohm 0.1% 25ppm`); only phrases without `0805` did (`5.36Kohm 0.1%`:
  41 results, every case size), and `5.36k ohm 0805 thin film 0.1%` matched 11 413 loosely related parts. Dropping the
  package would flood the 50-record window with other sizes for common queries (`4.7k 1% 0603 resistor`), so the phrase
  is unchanged; such a part is still reachable by `get_part` with its MPN.
- Part fields observed: `Availability` ("76689 In Stock"), `AvailabilityInStock` ("76689"), `AvailabilityOnOrder` (ignored),
  `FactoryStock` (ignored), `DataSheetUrl`, `Description`, `ImagePath`, `Category`, `LeadTime`, `LifecycleStatus`,
  `Manufacturer`, `ManufacturerPartNumber`, `Min`, `Mult`, `MouserPartNumber`, `ProductAttributes[{AttributeName,AttributeValue}]`,
  `PriceBreaks[{Quantity, Price:"1,40 €", Currency:"EUR"}]`, `ProductDetailUrl`, `ROHSStatus`, `SuggestedReplacement`,
  `SalesMaximumOrderQty`, `ProductCompliance`, `TradeCompliance`.
- Stock = integer parsed from `AvailabilityInStock` (digits only); drop the part when <= 0 and count it in the page's
  `outOfStock` (`out_of_stock_matches`).
- Prices are locale formatted (`"1,40 €"`, `"0,853 €"`, `"$0.10"`): strip everything except digits, `,` and `.`;
  when both separators appear the last one is the decimal separator; a lone `,` is a decimal separator
  when followed by 1-3 digits at the end. Currency from `Currency`.
- Attributes: `ProductAttributes` by name (repeated names joined with `", "`). Extra: `lifecycle_status`, `rohs`,
  `lead_time`, `factory_stock`, `category`, `suggested_replacement`, `reeling`, `sales_maximum_order_qty`, `availability_on_order`.
- Errors: HTTP 429, HTTP 502/503/504 with `Retry-After`, or `Errors[].Code == "TooManyRequests"` (HTTP 200) are rate
  limits: retried within the request deadline (section 3.6, Mouser allows 30 calls/min), `RATE_LIMITED` when it runs
  out; other non-empty `Errors` -> `BAD_RESPONSE`; other 5xx -> `UNAVAILABLE`.
- USB connectors (category `USB Connectors`, verified 2026-10-05): `ProductAttributes` carry only `Packaging` and
  `Standard Pack Qty`, so type, pins, standard, gender and mounting come from the description, whose wording varies
  by manufacturer: `Type C, 2.0, Horizontal, Gold plated 3u, Mid Surface Mount 1.86mm, 16 pin, T&R`,
  `USB2.0 Type C Rcpt, SMT, Hrz`, `USB Jack 2.0, Type-C, Vertical`, `USB jack 3.1 C type 24pin Horz SMT`,
  `USB 3.2 Type C Gen 1 Receptacle Hybrid 24Pin IP67`, `Type C, USB 3.2 Gen 2x1, 10 Gbps, ... 24 Pins, IP67`,
  `Receptacle, USB4, 24pos., 5A, right angle`, `Mid-Mnt DR SMT 24Ckt Type C Rec.`, `USB C Rec 16P 3u" Mid Mnt`,
  `Type C, Power Only, ... 6 Pins`, `USB Type-C Charge-Only Receptacle,6 Pin`, `USB 2.0 micro B jack 5 pin`,
  `Micro B Skt, Vertical, SMT`, `USB A 3.0 Skt RA ... T Hole W/Shell Stake`, `USB 3.0 TYPE A FML RIGHT ANGLE T/H`,
  `USB C Receptacle Right Angle 8 Positions G/F Sink 0.8mm IPX5`. Many omit the pin count (`480Mbps, 20VDC, 5A
  Type-C USB 2.0 Receptacle`). Keyword search: a pin count in the phrase over-constrains USB searches
  (`USB type C plug 24 pos` 1 result, `USB type C receptacle 16 pos mid` 1) and `17 pos`/`18 pos` return circular
  connectors and headers; no 17/18-position Type-C was found.

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
  `type == "DTE"` (prefer PDF; one call per page, <= 50 symbols), else the first document whose file name or URL says
  `datasheet` / `data sheet`, else the product page (`productUrl`), whose documentation section links the
  manufacturer's files; `extra.datasheet_source` is `dte`, `document` or `product_page`. Verified live 2026-10-06: TME
  has no `DTE` document for Eaton `HCMA0703-2R2-R` and Murata `BLM31KN121SN1L`/`BLA31BD121SN4D`, only an `LNK` text
  file (a link to the manufacturer page, behind a browser challenge for scripts) and `YTB` videos, so they get the
  product page. `photoUrl = https:` + `assets.primary_photo.prime`.
- Extra: `product_status`, `category_id`, `manufacturer_id` (TME's own manufacturer id, also returned as the part's
  `manufacturer_id`; Mouser and LCSC have none), `unit`, `packing`, `price_type`, `tax_rate`, `datasheet_source`.
- Stock refresh (`TmeClient.refreshStock`, section 3.2): `/products/data` (stock and prices) for up to 50 symbols per
  call. Measured 2026-10-06: TME answered `/products/data` in 6 to 11 s even for one symbol (the search, parameter and
  file endpoints in 0.1 s), which makes a TME search with relaxation steps exceed `kina.search.distributor-timeout`
  (20 s); the timeout reports what was collected so far.
- `product_status` meanings (TME API documentation): `HARDLY_AVAILABLE` "limited market availability" (a supply-side
  flag: TME may still hold a large stock, 150k+ pieces seen; never read as low stock; availability
  `supply_constrained`, lifecycle `supply_constrained`); `AVAILABLE_WHILE_STOCKS_LAST` "available for sale while stocks
  last" (no restocking: availability `last_units`, lifecycle `last_time_buy`); `MOQ_VALID_WHILE_STOCKS_LAST` "the MOQ
  may change after the product is sold out" (a note); `NEW` (new in the catalogue, lifecycle `new`); `PROMOTED`
  (promotional, no availability meaning); `DANGEROUS`, `OVERSIZED` (shipping restrictions, a note);
  `CANNOT_BE_ORDERED` (not for sale in your country); `ONLY_FOR_SPECIAL_ORDER`; `EXTERNAL_WAREHOUSE`; `NOT_IN_OFFER`
  (not in the offer any more, no stock or prices); `PRODUCT_BLOCKED` (blocked for sale); `INVALID`;
  `BLOCKED_FOR_ZBL_*` (observed, undocumented; treated as not orderable).
- Products whose `product_status` contains one of `kina.distributors.tme.excluded-statuses` (default
  `CANNOT_BE_ORDERED`, `ONLY_FOR_SPECIAL_ORDER`, `EXTERNAL_WAREHOUSE`, `NOT_IN_OFFER`, `PRODUCT_BLOCKED`, `INVALID`,
  `BLOCKED_FOR_ZBL_*`; compared case-insensitively, a trailing `*` is a prefix) do not ship now and are dropped by
  `TmePartMapper` (counted as out-of-stock matches). `product_status` stays in `extra`.
- Part lookup with spaces: TME answers HTTP 400 `E_INPUT_PARAMS_VALIDATION_ERROR` ("Some characters are not permitted
  in symbols[0]") for `HCMA0703 2R2 R` (verified 2026-10-06), `HCMA0703-2R2-R` resolves and `HCMA07032R2R` finds
  nothing. `TmeClient.lookup` tries `PartLookupResult.variants` (whitespace runs as `-`, then removed; characters outside
  letters, digits and `-_./+#,()` dropped) as symbols, then all of them and the normalised form as `mpns[]`; a
  validation error on these calls counts as "not found", never `bad_response`.
- USB connectors (category `USB & IEEE1394 connectors`, verified 2026-10-05): description
  `Connector: USB C; socket; SMT; PIN: 16; horizontal; USB 2.0; 5A`; parameters `Type of connector` (`USB C`,
  `USB B micro`, `USB A`, `USB B`), `Connector` (`socket`/`plug`), `Number of pins` (4, 5, 6, 9, 10, 16, 24 seen;
  no 17/18), `Version` (`USB 2.0`, `USB 3.0`, `USB 3.1`, `USB 3.1 Gen 1`, `USB 3.1 Gen 2`, `USB 3.2`, `USB 3.2 Gen 2`,
  `USB 4.0`), `Data transfer rate` (`0.48Gbps`, `5Gbps`, `10Gbps`, `20Gbps`), `Connector variant` (`top board mount`,
  `middle board mount`, `bottom board mount`, `Gen.2x2`, `sealed`, `shielded`), `Electrical mounting` (`SMT`, `THT`,
  `SMT, THT`, `hybrid SMT/THT`, `Fully SMT`), `Spatial orientation` (`horizontal`, `vertical`, `angled 90°`,
  `straight`), `Connectors application` (`only for charging (6p)`), `IP rating` (`IP67`, `IP68`, `IPX7`),
  `Mechanical durability`, `Current rating`, `Rated voltage`. `Version` and `Data transfer rate` can disagree
  (`USB 4.0` + `20Gbps`): the rate wins. The search ANDs the phrase words (`USB C socket 24 horizontal 3.1`: 0 results,
  `USB C socket 16 2.0`: 50); cables and adapters (`USB cables and adapters`) and hubs match `USB C socket` too and are
  not connectors.
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
- USB vocabulary (mined from the 8 606 `Connectors / USB Connectors` rows, 6 503 in stock, 2026-10-05): descriptions
  only (no parametric columns), e.g. `-40℃~+85℃ 1 16P 20V 3,000 Cycles 3A 7.35mm Black Female Surface Mount, Right
  Angle Type-C USB 2.0 With Locating Pins`; the lone `1`/`2` is the port count. Types `Type-C` (2 739 rows), `TypeC`
  (131), `Type-A` (1 663), `TypeA` (64), `Type-B` (119), `Micro-B` (668), `MicroB` (15), `Micro-AB` (16), `Mini-B`
  (115), `Mini-AB` (19); Type-C positions `16P` (1 119), `24P` (804), `6P` (480), `14P` (107), `12P` (48), `2P`, `8P`,
  no 17P/18P (the only shell-counted Type-C is `TYPE-C-24` with package `SMD-26P`, out of stock); standards `USB 2.0`,
  `USB 3.0`, `USB 3.1` (841 Type-C rows, including 16P and 6P parts), `USB 3.2`, `USB4`/`USB 4`; `Female`/`Male`;
  `Surface Mount`, `Through Hole`, package `SMD`/`Plugin`/`插件`; `Right Angle`, `Vertical`, `Vertical, Flag`,
  `Side insertion`; mid-mount `Recessed`, `Sink board`, `Sinking`, `Laminated board`; `Clamping plate` (straddle-mount
  plugs); `With Locating Pins`, `with Post`; waterproof only in the part number (`IPX7`, `IPX8`) or `with O-ring`; no
  power-only or PD wording; `aP+bP` = several ports (`4P+4P`, `9P+9P`, `4P+14P` Type-A + Type-C).
  `JlcpcbQuery` maps `Type-C`/`TypeC`/`USB-C`/`USBC` -> `("Type-C" OR "TypeC")` (likewise `Micro-B`, `Micro-AB`,
  `Mini-B`, `Type-A`, `Type-B`; kind `FAMILY`), and, as kind `FEATURE` (dropped right after free-text keywords in the
  relaxation), `"USB 2.0"` -> `("USB 2.0" OR "USB2.0")`, `"USB 3"` -> `("USB 3" OR "USB3")`, `USB4` ->
  `("USB4" OR "USB 4")`, `mid-mount` -> `("Recessed" OR "Sink board" OR "Sinking" OR "Laminated board" OR "Mid-mount")`,
  `waterproof` -> `("IPX" OR "IP67" OR "IP68" OR "Waterproof" OR "O-ring")`, `"board lock"` -> `("Locating" OR
  "with Post" OR "Board Lock")`. A positions group `16P/17P/18P` is one `POSITIONS` term matched as an OR of its
  alternatives, each checked at a number boundary (`6P/7P/8P` never matches `16P`). `24P` is a positions token as before;
  the hyphen in `Type-C` is kept (trigram phrase).
- Minimum ratings (`JlcpcbQuery.Kind.RATING`, DESIGN 3.2): a query token `>=25V`, `>=6A`, `>=125mW` (units V, A, W with
  an optional `m`/`k` prefix) is not matched as text but checked by the SQLite function
  `kina_at_least("Description", unit, minimum)`: the description must state a value of that unit (at a number
  boundary, `m` milli, `M` mega, `k` kilo, `u` micro) at least that high, so `>=25V` matches `25V`, `35V` and `50V`
  parts. A rating is dropped after the dielectric, the package and the tolerance (it never counts towards the two terms
  a relaxation step keeps); a rating alone is no query.
- Out-of-stock count: when `ALL` finds nothing in stock, the same predicate without the stock filter is counted
  (`Result.outOfStock`, reported as `out_of_stock_matches`). The terms the relaxation dropped are reported as
  constraint names by term kind (`LcscClient.relaxed`; the response keeps those the returned parts really miss in
  `constraints_relaxed`, section 3.2) and the dropped free-text keywords as written (`LcscClient.droppedKeywords`, part
  of `query_terms_dropped`).
- Relaxation (`JlcpcbSqliteSearch`): `ALL` (every term) first. When it has no in-stock match, `RELAXED`: (1) remove
  the dead terms, i.e. terms that occur nowhere in the database (one `MATCH ... LIMIT 1` probe per matchable term,
  stock ignored, so it stops at the first hit; e.g. `dupont`, misspellings), and retry; (2) drop the least informative
  remaining term and retry, one term at a time, while at least 2 terms that are not ratings remain. Drop order by kind
  (the relaxation order of section 3.2): free-text keyword, feature, mounting, orientation, pitch, dielectric, package,
  tolerance (a value ending in `%`), rating, value, positions, family word, category; within a kind the last term of
  the query first; (3) when that finds nothing either, the same once more without the ratings (the ranker excludes
  the parts below them, or flags them with `allow_below_spec`; the closest parts beat an `ANY` match). A step whose
  predicate has no MATCH (LIKE-only, a full scan) is skipped. A step whose
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
management:                    # section 3.7: actuator on its own port, no authentication there
  server.port: ${KINA_METRICS_PORT:9090}
  endpoints.web.exposure.include: health,info,prometheus
  prometheus.metrics.export.enabled: true
kina:
  public-base-url: ${KINA_PUBLIC_BASE_URL:}
  security.mode: ${KINA_MODE:dev}
  security.oidc:                 # production only (section 6, 7.1)
    issuer-uri: "${OIDC_ISSUER_URI:}"
    client-id: "${OIDC_CLIENT_ID:}"
    client-secret: "${OIDC_CLIENT_SECRET:}"
    groups-claim: "${OIDC_GROUPS_CLAIM:groups}"                 # ID token first, then userinfo; dotted path for nested claims
    required-groups: "${OIDC_REQUIRED_GROUPS:}"                 # comma separated, any of them; empty = no group check
    allowed-email-domains: "${OIDC_ALLOWED_EMAIL_DOMAINS:}"     # comma separated; empty = any domain
    email-from-preferred-username: ${OIDC_EMAIL_FROM_PREFERRED_USERNAME:false}  # no email claim: preferred_username/upn with @
    require-verified-email: ${OIDC_REQUIRE_VERIFIED_EMAIL:true}  # false: email_verified=false ignored, domain still checked
    extra-scopes: "${OIDC_EXTRA_SCOPES:}"                       # besides openid profile email; offline_access is automatic
    token-encryption-key: "${KINA_TOKEN_ENCRYPTION_KEY:}"       # base64 of 32 bytes; unset = no upstream refresh tokens
    membership-recheck-interval: ${KINA_MEMBERSHIP_RECHECK_INTERVAL:1h}
    membership-grace: ${KINA_MEMBERSHIP_GRACE:4h}               # provider unreachable: access continues this long
    relogin-interval-without-recheck: ${KINA_RELOGIN_INTERVAL_WITHOUT_RECHECK:24h}
  tokens:
    validity: 30d                                               # static tokens created in the web UI
    ui-enabled: ${KINA_TOKENS_UI_ENABLED:true}
  oauth:                         # section 7
    access-token-validity: ${KINA_OAUTH_ACCESS_TOKEN_VALIDITY:1h}
    refresh-token-validity: ${KINA_OAUTH_REFRESH_TOKEN_VALIDITY:30d}
    trusted-client-metadata-hosts: "${KINA_OAUTH_TRUSTED_CLIENT_HOSTS:claude.ai,claude.com,*.anthropic.com}"
    client-metadata-cache: 1h
    auto-approve-trusted-clients: ${KINA_OAUTH_AUTO_APPROVE_TRUSTED_CLIENTS:true}
    unused-client-retention: 90d
    register-rate-limit-per-minute: ${KINA_OAUTH_REGISTER_RATE_LIMIT_PER_MINUTE:30}   # very high in src/test/resources
  cache:                       # section 3.2 "Cache model"
    ttl: ${KINA_CACHE_TTL:3d}  # search lists; stock/prices older than this that cannot be refreshed are stale
    empty-result-ttl: 1h       # cached searches with no in-stock part
    stock-ttl: ${KINA_CACHE_STOCK_TTL:24h}   # older cached stock/prices of returned parts are refreshed (section 3.2)
    metadata-retention:        # per distributor: forever (default) or a duration; purge by metadata_fetched_at
      TME: ${KINA_CACHE_METADATA_RETENTION_TME:forever}
      MOUSER: ${KINA_CACHE_METADATA_RETENTION_MOUSER:forever}
    stale-rank-penalty: ${KINA_CACHE_STALE_RANK_PENALTY:1.0}   # 1.0: every stale part ranks below every fresh one
  search:
    candidate-window: 40
    default-max-results: 10
    max-max-results: 50
    distributor-timeout: 20s     # active work per distributor fetch; rate-limit waits do not count
    max-request-duration: 2m     # hard cap per request (search, whole batch, get_part) incl. rate-limit waits
    strict-constraints: ${KINA_STRICT_CONSTRAINTS:}   # deprecated, section 3.4; empty = unset
    # hard-constraints: { inductor: "type,value,package,mounting,technology" }   # section 3.4; unset = the decided table
    low-stock-threshold: ${KINA_LOW_STOCK_THRESHOLD:10}   # low_stock: stock below this or below 2 x quantity
    quantity: { stock-shortfall-penalty: 0.3, moq-penalty: 0.3, low-stock-penalty: 0.3 }   # section 3.4 "Quantity"
    lifecycle: { last-time-buy-penalty: 0.1, supply-constrained-penalty: 0.05 }
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
      auto-download: ${KINA_CROSS_ENCODER_AUTO_DOWNLOAD:true}   # false in the image and in src/test/resources/config/application.yml
  distributors:
    mouser: { api-key: "${MOUSER_API_KEY:}", base-url: https://api.mouser.com/api/v1, max-results-per-search: 50, max-pages-per-search: 1 }
    tme:    { token: "${TME_TOKEN:}", secret: "${TME_APPLICATION_SECRET:}", country: "${COUNTRY:RO}", currency: EUR, language: en, base-url: https://api.tme.eu, max-results-per-search: 60, max-pages-per-search: 3,
              excluded-statuses: [CANNOT_BE_ORDERED, ONLY_FOR_SPECIAL_ORDER, EXTERNAL_WAREHOUSE, NOT_IN_OFFER, PRODUCT_BLOCKED,
                                  INVALID, "BLOCKED_FOR_ZBL_*"] }
  metrics:
    save-interval: 30s           # section 3.7: counters saved to metrics_counters, database gauges recomputed
  jlcpcb: { data-dir: "${KINA_JLCPCB_DATA_DIR:./data/jlcpcb}", library: "${KINA_JLCPCB_LIBRARY:parts-fts5.db}", base-url: https://bouni.github.io/kicad-jlcpcb-tools/, refresh-after: 5d, check-interval: 1h, max-results-per-search: 200,
            auto-download: true }   # false in src/test/resources/config/application.yml
```

Production OIDC is configured only through `kina.security.oidc.*` (read when `kina.security.mode=prod`), not through
`spring.security.oauth2.client.*`: `OidcLoginConfiguration` (conditional on prod mode) builds the single registration
`oidc` lazily from the issuer's discovery document (`LazyOidcClientRegistrationRepository`, redirect URI
`{baseUrl}/login/oauth2/code/oidc`), so development mode needs no OIDC settings and production starts while the
provider is unreachable. A malformed `token-encryption-key` fails startup with a message saying how to generate one.
Comma-separated environment values bind to the list settings; leave unused duration and boolean variables unset (an
empty value is not a valid duration or boolean).

## 11. Docker

- `Dockerfile`: stage `build` `maven:3.9-eclipse-temurin-21` (copy `pom.xml`, `./mvnw`, `.mvn`, run
  `dependency:go-offline`, then copy `src`, `package -DskipTests`); stage `model` `alpine:3.22` with `curl` runs
  `docker/model/fetch-model.sh` (the only build-time dependency on Hugging Face); stage `runtime` `eclipse-temurin:21-jre`,
  non-root user `kina` (uid 10001), the model copied to `/opt/kina/cross-encoder` (owned by uid 10001, directories
  0555, files 0444), `ENV KINA_JLCPCB_DATA_DIR=/data/jlcpcb KINA_CROSS_ENCODER_MODEL_URL=/opt/kina/cross-encoder
  KINA_CROSS_ENCODER_AUTO_DOWNLOAD=false`, `/data` volume (JLCPCB database only), `EXPOSE 8080 9090`, `HEALTHCHECK` on
  `http://localhost:${KINA_METRICS_PORT:-9090}/actuator/health` (curl, management port), `ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED",
  "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/kina.jar"]` (native access for sqlite-jdbc;
  heap sized from the container memory limit; extra flags via `JAVA_TOOL_OPTIONS`). The ONNX Runtime jar bundles its
  native library (linux-x64/aarch64), extracted to the temp directory at first use; it loads in `eclipse-temurin:21-jre`
  as the non-root user.
- Model stage (`docker/model/fetch-model.sh`): downloads `config.json`, `tokenizer_config.json`, `vocab.txt` and the
  ONNX files of the requested variants from `CROSS_ENCODER_SOURCE`, checks each against the committed sha256sum file
  (`docker/model/ms-marco-MiniLM-L6-v2.sha256`; ONNX values equal the Hugging Face LFS ids) and fails the build on a
  mismatch, then writes `model.json` (`source`, `repo`, `revision`, `variant` = first requested, `variants`,
  `provisioned_by`, `downloaded_at`, per-file `size` and `sha256`; unknown fields are ignored by
  `ModelDownloader.Manifest`). Build arguments:
  - `CROSS_ENCODER_SOURCE`: default
    `https://huggingface.co/cross-encoder/ms-marco-MiniLM-L6-v2/resolve/233902d25c440f23af6f7d6e94d2946bac0bee0a/`
    (pinned revision); any HTTP(S) directory with the same layout, e.g. a fine-tuned model served during the build.
  - `CROSS_ENCODER_VARIANTS`: `int8,fp32` (default, 138 MB layer) or `int8` (both int8 files, 46 MB) or `fp32`.
  - `CROSS_ENCODER_SHA256_FILE`: hash file name in `docker/model/`; a custom source needs its own.
  - `CROSS_ENCODER_SKIP_VERIFY=1`: skip the hash check (custom sources only; logged as a warning in the build).
  - `CROSS_ENCODER_REVISION`: revision for `model.json`; default taken from a Hugging Face `resolve/<rev>/` URL.
  The same script pre-fetches the pinned files for local runs (`docs/DEVELOPMENT.md`).
- Offline guarantee: with the image defaults, no code path downloads the model at runtime. A missing or invalid
  bundled directory logs one ERROR and ranking falls back to the deterministic order (3.5).
- `compose.yaml`: top-level `name: kina` (volumes are `kina_kina-data`, `kina_pgdata`, network `kina_default`; a
  pre-seeded JLCPCB file in `kina_kina-data` is adopted at startup, see 9.3; the volume no longer holds the model).
  Services:
  - `kina`: build `.`, ports `${KINA_PORT:-8080}:8080` and `${KINA_METRICS_BIND:-127.0.0.1}:${KINA_METRICS_PORT:-9090}:9090`
    (management port without authentication, localhost only by default, section 3.7), `env_file: .env` (optional),
    environment for the datasource, `KINA_JLCPCB_DATA_DIR=/data/jlcpcb` and `KINA_METRICS_PORT=9090` (the container
    always listens on 9090; the `.env` value only picks the host port), volume `kina-data:/data`,
    `mem_limit: ${KINA_MEM_LIMIT:-2g}` (heap = 75%; the ONNX Runtime session lives outside the heap),
    `depends_on: postgres (healthy)`.
  - `postgres`: `postgres:17-alpine`, `POSTGRES_DB/USER/PASSWORD=kina`, volume `pgdata`, healthcheck `pg_isready`.
- `.env.example` documenting every variable; `.env` is git-ignored.

## 12. Quality bar

- `./mvnw -q verify` must pass: unit tests for the query parser, parametric extractor, deterministic ranker, Mouser price
  parsing and mapping (JSON fixtures), TME mapping and token refresh (`MockRestServiceServer`), JLCPCB price parsing and
  SQLite search (build a tiny FTS5 database in the test), the cross-encoder tokenizer (fixtures from the Hugging Face
  tokenizer), ranker batching/timeouts, model download/verification and the rank blend with its fallbacks, PKCE, token hashing,
  OAuth metadata/register/authorize/token flow (MockMvc), bearer filter; group-claim evaluation, upstream token
  cipher, Client ID Metadata Document parsing and host allowlist, registration rate limit; a production-mode
  integration test against an in-process OIDC provider (`FakeOidcProvider`: login gate, refresh re-checks, grace,
  fallback without upstream token, static-token background re-check); Testcontainers-backed repository tests;
  metrics: the counter store (canonical tags, timers, restore), persistence round trip with Testcontainers (save,
  restore in a new store, never decreasing), a production-mode `@SpringBootTest` on random ports that scrapes
  `/actuator/prometheus` on the management port without credentials and checks the main port does not serve it, and
  the MCP integration test counting searches and tool calls.
- No secrets in code, logs or test fixtures. Never log bearer tokens or API keys.
- Every external call has a timeout. Every distributor error is isolated per distributor.
