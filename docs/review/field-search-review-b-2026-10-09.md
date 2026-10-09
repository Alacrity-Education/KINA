# Field-based search review B: flows and the LCSC side (2026-10-09)

Scope: commits `a2f188f..4839fa6` (v0.15). Reviewed: `FieldFirstSearch`, `CachedDistributorRetriever`,
`LcscRetriever`, `LcscFieldSearch`, `PageCollector`, `ParallelRetrieval`, `ResponseAssembler`, `Fetched`, `Progress`,
`PhraseJournalRepository`, `PhraseJournalBackfill`, the `distributor/lcsc` pool, sidecar and download code,
`ApiQuotaTracker`, `QuotaGauges`, `PartPathFirewall`, `PartPathConnectorCustomizer`, `PartsController`, the MCP
descriptions and their tests. Reference: `docs/DESIGN.md` 3.2, 3.6, 3.8, 9.3 and study section 5.

## Overall assessment

The Mouser and TME flow is one generic loop over `FieldQuery.steps()`. It has no distributor, family or kind
special cases, the fallback reasons (`bypass`, `incomplete`, `generic`, `sql_error`, `mode`) are one metric tag and are
tested, the journal read failure rule is sound, and the new field SQL is generated from statements, not rewritten
strings. The sidecar swap order is safe: the main file is renamed first, a crash between the two renames leaves a new
main file with an old sidecar, and the fingerprint check then sends searches to the FTS path until the next check
rebuilds. The quota tracker is bounded and counts every attempt before the response, timeouts included.

The weak points are structural and about cost:

1. The LCSC side has a second relaxation loop with a different stop rule, and a second "is this request selective"
   test (B4).
2. The step-to-phrase mapping is 45 lines of matching heuristics inside `FieldFirstSearch`, not in
   `DistributorPhraser` (B5).
3. The mode switch is conditionals plus four optional beans in `CachedDistributorRetriever` (B3).
4. Each step loads, enriches and checks up to 100 candidates, twice per call, and the ranker checks them again (B2).
   That is where the measured 3x on cached queries comes from; the cross-encoder is already capped at 40.
5. A page that fails after the first is journaled as an answered phrase (B1), and one failed attachment of the sidecar
   disables the whole LCSC database (B7).
6. The Tomcat and firewall relaxation is broader than needed, because query-parameter lookups already exist (B10).

## Findings

Severity: high, medium, low. Category: declarative, generic, hack, correctness, test, perf. `complex: yes` means a
design change, a shared abstraction or behaviour risk.

| # | Where | Sev | Cat | Defect | Proposed fix | complex |
|---|---|---|---|---|---|---|
| B1 | `FieldFirstSearch.java` `call()` (journal.record before the `collected.error()` check, about lines 392 to 405); `CachedDistributorRetriever.recordPhrase` | medium | correctness | When a later page fails, the parts of the earlier pages are cached and the phrase is journaled as answered (`exhausted=false`, `next_offset` set), so it counts as asked for 3 days and the missing pages are never retried. DESIGN 3.2 says "a failed call writes nothing". | Do not journal when `collected.error() != null` (keep the parts), or journal with a short freshness. Add a test with a fake that fails on page 2. | no |
| B2 | `FieldFirstSearch.candidates()` and `load()`; `RankingService.capped` and `safeCheck`; `application.yml` `max-candidates: 100` | medium | perf | Every step runs the index query, loads up to 100 JSONB parts, enriches them and runs the Java check; `candidates()` runs again after each call; the verdicts are then recomputed by the ranker. `max-candidates` is one number for the SQL limit, the ranking cap and the augment limit. The cross-encoder is already capped at 40 (`ranking.cross-encoder.max-candidates`), so the 3x comes from loading and checking, not scoring. | Leaner default: query up to `max-candidates` keys (cheap), but load and check in chunks of `max(2 x max_results, 20)` in index order until `enough()`; hand the verdicts to the ranker (or cap the ranker at the same number). Keep 100 only as the SQL recall limit. Measure against `off`. | yes (touches ranking stage contract) |
| B3 | `CachedDistributorRetriever.searchByMode`, `augment`, `shadow`; fields `shadow`, `fieldFirst`, `index`, `journal` (`required = false`) | medium | generic | The four modes are `if` chains in the retriever, with four optional beans and a `mode` fallback metric emitted from two places. The legacy path writes the journal and calls shadow and augment, so it is entangled with the new feature. | One `FieldSearchMode` strategy per mode (`Off`, `Shadow`, `Augment`, `On`) behind one interface `Fetched search(client, prepared, progress, deadline, Supplier<Fetched> cachedSearch)`; the retriever picks one bean by `mode`. Drops the `required = false` fields. | yes |
| B4 | `LcscRetriever.typed()` lines 105 to 165, `constrains()` line 180; `FieldFirstSearch.selective()` | medium | generic, correctness | LCSC has its own loop over `query.steps()` with a different stop rule: it stops at `best.total() >= window` (rows the SQL matches), while Mouser/TME stop at `enough()` (candidates that pass the Java check and one confirmed). A step with many SQL rows that the Java check rejects, or none confirmed, ends the relaxation; the `window * 2` over-fetch (line 126) is a patch for this. `constrains()` and `selective()` are two definitions of "the request states something", with different results (`constrains` counts the family and TYPE rules). | Put the loop in one place: `FieldQuery.relax(Predicate<Step> stop)` or a small `RelaxationLoop`, with `enough` supplied by the caller; make LCSC stop on Java-passing and confirmed counts. Make `constrains` call `selective`. | yes |
| B5 | `FieldFirstSearch.Run.rungs()` lines 207 to 245 | medium | declarative | The mapping from a field step to the distributor phrase is heuristic code in the flow: three branches, a loop by exact relaxed set, then a loop by superset, then the minimal-core special case. It cannot be checked against `DistributorPhraser.ladder` in one place. | Add `DistributorPhraser.phraseFor(distributor, parsed, relaxedKinds)` (exact set, then superset, then core) with its own labelled test, and have `rungs()` call it. | yes (shared with the ladder) |
| B6 | `PageCollector.java:139` | medium | hack | `client.distributor() != Distributor.LCSC` special-cases a distributor to decide whether a page counts as a quota call. | Fixed on this branch: `ApiQuotaTracker.isTracked(client.distributor())`. Longer term, `DistributorClient.callsRemoteApi()`. | no |
| B7 | `JlcpcbSqliteSearch.openLocked()` lines for `FieldIndexFile.attach(c, indexFile)` in the loop for connections 2..n | medium | correctness | Attaching the sidecar to the first connection is guarded (`attachIndex` catches and detaches), but on the other connections a failing `attach` throws `SQLException`, so `openLocked` closes the whole pool: a bad or vanished sidecar takes LCSC FTS search down. Also, `fieldIndex` describes only connection 1. | Attach on all connections inside one try; on any failure detach all (or reopen the pool without the sidecar) and set `fieldIndex = null`. Add a pool test with a sidecar that is unreadable after the first attach. | no |
| B8 | `JlcpcbDatabaseManager.runDownload()` (the `replaceDatabase` lambda) | medium | correctness | If `installIndex` throws after `install` renamed the new main file, the catch block reports a failed download and does not set `current` or save the repository row, although the new file is in use. The next check can then download again. | Set `current` and save the row right after `install` succeeds (inside the lambda or in a `finally`), treat the sidecar failure as `indexFailure`. Add a test where `installIndex` throws. | no |
| B9 | `JlcpcbDatabaseManager.startDownload()` and `startIndexBuild()` | low | correctness | Each checks the other's flag with `get()` and then sets its own with `compareAndSet`: both can pass, and then build the same `tmp/<library>.index.db`. | One `AtomicReference<Task>` (or a lock) that admits a single maintenance task. | no |
| B10 | `PartPathConnectorCustomizer`, `PartPathFirewall`, `SecurityConfig.partPathFirewall` | medium | hack | Tomcat is set to pass `%2F` and `%5C` through for every path, and the security firewall is relaxed for `GET /api/v1/parts/**`. The query forms `GET /api/v1/parts/{distributor}?part_number=` and `/lookup` already carry any part number. The relaxation is a path-wide security exception for 77 parts, matches the raw URI prefix (silently off under a context path), and the connector setting is global. | Drop the connector customizer and the firewall; keep only the query forms as the documented way for `%`, `\`, `/`, `+`; keep plain `/` in `{*partNumber}` (works without relaxation). If the path form with `%25` must stay, relax only `allowUrlEncodedPercent` and keep the connector strict. | yes (API contract) |
| B11 | `LcscFieldSearch.java:33` | low | hack | `private final Clock clock = Clock.systemUTC()` instead of the injected `Clock`; the mapped parts carry `fetchedAt` from it, not testable. (`LcscClient` does the same, from before.) | Inject `Clock` with `@Autowired`, wire in `TestWiring`. | no |
| B12 | `LcscRetriever.java:41` `MAX_POOL_WAIT = 10 s`, line 126 `window * 2` | low | hack | The 10 s duplicates `kina.jlcpcb.pool-wait` (default 10 s) and ignores a changed setting; `window * 2` is an undeclared factor. | Pass `null` to use the configured `pool-wait`, bounded by the deadline; declare the factor as a named constant (or remove it with B4). | no |
| B13 | `Fetched.fetchedLive`, `Progress.fetchedLive`, `Fetched.liveCalls`, `ResponseAssembler.fetchedLive()` | low | declarative | One fact (`fetched_live` is always `live_calls > 0`, DESIGN 3.2) has three carriers plus a cache-status fallback. | Remove `Fetched.fetchedLive` and `Progress.fetchedLive`; derive from `liveCalls`. | no |
| B14 | `ApiQuotaTracker.TRACKED`, `readLimits()`; `RateLimitRetry` cooldown | low | declarative | The tracked set and the limits are `Mouser`/`TME` ternaries, and `throttledUntil` duplicates the state `DistributorCooldown` already holds. | Take the quota from a `Map<Distributor, Quota>` declared with the distributor properties; read `throttledUntil` from the cooldown. | no |
| B15 | `PhraseJournalBackfill` | low | correctness | It rebuilds ladders with `ConstraintPolicy.DEFAULTS`, ignoring the configured override, and re-parses the normalised key (not the user's text), so a phrase may differ from the one sent; it runs on an unsupervised virtual thread (failures are logged, so this is acceptable). | Use the configured policy; note the re-parse limit in the Javadoc. | no |
| B16 | `FieldSearchShadow.observe` | low | perf | One unbounded virtual thread per search in `shadow` mode, each running index queries. | A small bounded executor (drop when busy). | no |
| B17 | `FieldFirstSearch.Run.loaded` and `verdicts` | low | correctness | Parts are cached by number before a call; after the call the same part is re-upserted with fresh stock, but `candidates()` still takes the earlier copy and verdict for `enough()`. The response uses the live copy. | Remove the numbers of `live` from `loaded` and `verdicts` after a call. | no |
| B18 | `FieldFirstSearchTest` and `PhraseClient` | medium | test | The fake distributor answers by phrase but has `max-pages-per-search: 1`, so paging, a later page failing, the request deadline, and rate-limit retry inside the flow are never exercised; `failure` is one generic exception. `JlcpcbSqlitePoolTest` has no case for a sidecar attach failure, and no test covers a crash between the two renames at the pool level or a failing `installIndex`. | Add: page-2 failure (B1), `RATE_LIMITED` with a `Retry-After` that does or does not fit the deadline, cap of calls with `max-pages > 1`, `installIndex` failure (B8), sidecar attach failure (B7). | no |
| B19 | `FieldFirstSearch.Run.now` | low | correctness | `asked_at` and the freshness check use the request start; after a 2-minute rate-limit wait the journal row is up to 2 minutes older than the call. | Use `clock.instant()` when recording. | no |
| B20 | `FieldFirstSearch.execute()` journal skip | low | correctness | Any fresh row skips the call, but the study (5.2) skipped only when `exhausted` or the page cap was reached. With `max-pages: 1` (Mouser) the two agree; with more pages a fresh non-exhausted row stops page 2 for 3 days. DESIGN states the current rule, so the study and DESIGN differ. | State the rule and the page-count assumption in DESIGN 3.2, or carry `pages_used` in the row. | no |
| B21 | `JlcpcbSqliteSearch.countIgnoringStock` | low | hack | String rewriting of a WHERE clause by `endsWith(" AND " + IN_STOCK)`. Before this change, not in the field SQL (which is generated from statements). | Build the predicate with a `withStock` flag. | no |
| B22 | `CachedDistributorRetriever`, `LcscRetriever`, `JlcpcbDatabaseManager`, `CacheMaintenance`, `PartCacheRepository` | low | test | Production beans accept `@Autowired(required = false)` and null checks "for tests that build the bean by hand", against the `TestWiring` convention in CLAUDE.md. | Wire the beans in `TestWiring` and make the fields required. | no |
| B23 | `FieldFirstSearch` | low | generic | `loadCachedList` and `loadExpiredList` were two copies of the same read; `new java.util.HashSet` and fully qualified names in several places. | Fixed on this branch (one `loadList`, imports). | no |

Not found: `Thread.sleep` or `TODO` in the reviewed flow classes (the one in `JlcpcbDownloader` is the download
retry), static mutable state, `catch (Exception)` (all catches are `RuntimeException` or specific and logged), and
string-built SQL in the new field path. The magic numbers named in the brief are declared: `COVERAGE_TTL_MILLIS`
(30 s, `PartIndexRepository`), `ROW_PARTS` (50), `BATCH` (500), `MARGIN_MIN` (100). The 2x window in the journal purge
is documented in DESIGN 3.2.

## Checks of the brief, short answers

- Generic loop: yes for Mouser/TME; LCSC reuses `FieldQuery`/steps but loops again (B4). The legacy path is a clean
  fallback by return value (`Outcome.legacy(reason)`), but the retriever owns the mode switch (B3).
- Journal: key is the sorted word set (`phraseKey`), freshness uses `ttl` and `empty-result-ttl` (shorter wins),
  backfill is idempotent (`ON CONFLICT DO NOTHING`), purge is `2 x ttl` with the cached searches. Issues B1, B15, B19.
- Call cap and deadline: the cap counts `collect()` calls (phrases), `live_calls` counts pages; both are documented
  in DESIGN 3.2. The deadline is checked before every call. `live_calls` is counted when the page is requested, so a
  failed call counts.
- Pool: borrow waits at most `pool-wait`; a waiting write lock only waits for queries that hold a connection for at
  most that long; a swap closes every connection under the write lock. Nested `isAvailable()` inside
  `withConnection` cannot deadlock because `idle != null` there.
- Quota: sliding, bounded (`day + max(100, 10 %)`), counted before the response, throttle marked on the 429 path. OK.
- Firewall scope: only `GET` under `/api/v1/parts/`; strict elsewhere; see B10 for the connector.

## Complex items

B2 (lean candidate loading and a shared verdict with the ranker), B3 (mode strategy), B4 (one relaxation loop for
LCSC and the distributors), B5 (phrase for a step in `DistributorPhraser`), B10 (drop the path-wide encoded-slash
relaxation).

## Applied on this branch

- `FieldFirstSearch`: `loadCachedList` and `loadExpiredList` share one `loadList`; fully qualified `HashSet` replaced.
- `PageCollector`: the LCSC special case replaced by `ApiQuotaTracker.isTracked`.
- `Progress`: imports instead of fully qualified `AtomicInteger`.
- `./mvnw -q verify` green (see the commit).
