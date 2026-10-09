# Field search validation, 2026-10-09 (phases C and C2)

Validation of the field-based search (study `field-search-2026-10-08.md`, DESIGN.md 3.2 "Field-first flow", 3.8,
9.3) on a copy of the production cache and the full JLCPCB file, in a separate compose project. Branch
`feature/field-search` after the merge of phase B2 (LCSC typed table) and the fixes listed below.

## Setup

- Stack: `scripts/e2e/field-search/` (compose project `kina-fs`, ports 18080 and 19090, image `kina-fs:latest` built
  from the branch, volumes `kina-fs_kina-data` (external: JLCPCB file of 7 146 764 parts, 5.3 GB, and the model) and a
  fresh `kina-fs_pgdata`). The project `kina` running next to it was never touched.
- Data: production dump `kina-live-20261009.dump` restored with `pg_restore --no-owner --no-privileges` into the empty
  database (schema at V13).
- No distributor credentials on this machine. Mouser and TME were made "configured but offline": placeholder
  credentials and base URLs on a closed local port (`http://127.0.0.1:9`), so every search and lookup reads the cache
  and the index, and every live call fails at once with `unavailable`. No distributor was contacted.
- Host: 24 cores, 30 GB RAM, container limit 2 GB (heap 75 %).

## 1. Merge and fixes

The merge of `feature/field-search-lcsc` into `feature/field-search` had no textual conflicts (git merged
`KinaProperties`, `application.yml`, `.env.example`, `DistributorStatusResponse`, `DistributorStatusService` and
DESIGN.md automatically); the full suite passed on the merge commit (1 474 tests).

Loose ends of phases B1 and B2:

| Item | Change |
|---|---|
| `SqliteFieldSql.candidates` and `count` rewrote the SQL of `FieldSql.select` | `FieldSql.body()` returns the parts of a statement (stated conditions, family conditions, `FROM ... WHERE`, parameters); both dialects compose from it |
| DESIGN `list_distributors` line | names `jlcpcb{..., field_index{...}}` and `field_index{..., journal_rows}` |
| journal size | `field_index.journal_rows` in `list_distributors` and `/api/v1/distributors` |
| family-only guard | `kina.search.field-index.require-stated-constraint` (default true, `KINA_FIELD_INDEX_REQUIRE_STATED_CONSTRAINT`), DESIGN 3.2 "The stated-constraint rule"; `on` and `augment` follow it |
| journal read failure | before any call: the cached-search path with reason `sql_error`, never a spent call |

Defects found by the validation, each fixed with a test:

| Defect | Fix |
|---|---|
| With `kina.jlcpcb.auto-download=false` an old JLCPCB file never got its typed table | `check()` starts the build when the download is skipped |
| `on`: a part-number request whose part is not cached (`lmg2100r026 gan half-bridge`, `epc2302`) returned 0 parts where the cached list had 50 and 2 | the request's fresh cached list joins the field candidates, so the field path serves at least what the cached-search path serves |
| LCSC typed path: `40x40x10 fan 12V` returned 12 V buck converters, buzzers and TVS diodes (rows of unknown family filled the window) | when the request names a family, rows of unknown family are left to the FTS search |
| LCSC typed path: `4.7k 1% 0603 resistor` returned 38 parts (FTS: 44): SQL-confirmed 4.75k and 4.64k rows took places the Java check then emptied | two windows are fetched and only the candidates the Java check returns take a place |
| LCSC typed path: `female header 1x6 right angle` returned 2x3 headers with more stock before 1x6 ones (23 confirmed fits, FTS 34) | soft kinds order the candidates (group `S`; `ROWS` declares `@Indexed` for ordering only); now 34 |
| REST lookup of a part number with `%` or `\` (77 cached parts) is refused by the Spring Security firewall (HTTP 400) | not fixed (not field-search related, API change): `get_part` over MCP returns them; noted for the API |

Additional field (coordinator request): `live_calls` in every distributor result (search calls of the request, one per
page and phrase, failed calls included, LCSC 0); `fetched_live` is now `live_calls > 0` in every mode, so a failed call
counts as a call (B1 reported `fetched_live: false` for it).

Full suite at the end: `./mvnw -q verify` green, 1 484 tests (3 skipped: the manual and live tests), `-Werror` clean.
`ConstraintGoldenTest`, `ExtractionGoldenTest`, `CachePreservationMigrationTest`, `FieldIndexContinuityTest` and
`FieldQuerySupersetTest` pass unchanged.

## 2. Cache preservation

Baseline after the restore (schema V13):

| Table | Rows | md5 over `row_to_json`, ordered by key |
|---|---|---|
| `cached_parts` MOUSER | 3 064 (3 061 in stock) | |
| `cached_parts` TME | 3 596 (3 596 in stock) | |
| `cached_parts` all | 6 660 | `2ea4c6bbf7d36f066541e66cb97e59c2` |
| `cached_searches` | 221 | `8d9afeec20ef01178afd030f647eb705` |

After startup in mode `on` (Flyway V14 and V15 on the real data, 23 ms; re-index; journal backfill), and again after
every run of this report (all four modes, about 3 000 searches and 13 000 lookups): counts and both md5 values
identical; only `flyway_schema_history` moved from 13 to 15.

- Re-index: `wrote 6660 rows (TME 3596, MOUSER 3064), 0 unreadable payloads; 9534 ms` (background, after startup).
- `part_index`: MOUSER 3 064, TME 3 596 (equal to `cached_parts`); `field_index.incomplete: []`, `stale: 0`.
- Journal backfill: 221 cached searches read, 214 phrases added, 675 ms (`distributor_phrases`: MOUSER 88, TME 126;
  `journal_rows: 214`).
- Startup: `Started KinaApplication in 2.147 seconds`; health up after 5 s.

### 2a. Lookups

`GET /api/v1/parts/{distributor}/{part_number}` for every cached in-stock part, 16 in parallel
(`validate.py lookups --mcp-fallback`): **6 657 / 6 657** returned the part (MOUSER 3 061, TME 3 596), 1.9 s in total,
p50 4 ms, p95 7 ms. 77 part numbers with `%` or `\` are refused by the REST path (HTTP 400, firewall) and were looked
up with the MCP `get_part` tool, which returned all 77. Every stock refresh failed (offline) and the part came back
with its cached figures; 3 068 of them carry `stale: true` (stock older than `kina.cache.ttl`).

### 2b. Search recall from the parts' own attributes

238 queries, up to 10 per (distributor, family) over 27 groups, built from each part's index row: family word,
primary value, package, dielectric, tolerance (`validate.py sample`, seed 20261009), searched at the part's
distributor with `max_results` 50.

| Mode | Answered without a live call | Part found | Errors (needed a live call) |
|---|---|---|---|
| `on` | 227 (`cache: hit` 227) | **192 / 227 (84.6 %)** | 11 (`miss`/`stale`, `unavailable`) |
| `off` | 1 | 1 / 1 | 237 (no cached list for the query) |
| `shadow`, `augment` | as `off` | | |

The 35 misses of `on` (`explain_misses.py`, which reruns the logged field query without its limit):

- 20: the part is in the SQL result (position 15 to 86), its stock is older than the TTL and the refresh failed, so it
  is moved below the fresh parts and lands after the 50 returned. The cached-search path does the same.
- 10: in the SQL result within `max-candidates` (position 13 to 179 of up to 490: `fan 12V`, `inductor 2.2uH`),
  ranked below the 50 returned. No miss was cut by `max-candidates`.
- 5: not in the SQL result of the served step: `ferrite bead 120 ohm 1206` (a bead array), `RGB LED 5050` twice (LED
  tapes), `LED 5MM` (a blinking bicolour LED) and `usb 4 pin right angle` (free text; the part does not state the
  keywords). The Java check refuses all of them (or, for the keywords, the step requires them):
  `FieldQuerySupersetTest` on the production pool (below) finds no violation.

No miss is explained by the SQL filter. The 11 errors are requests where the index held fewer than `max_results`
passing parts, so the flow asked the distributor (offline here): five of them name a package key the parser does not
read (`D5X11MM`, `1411`, `POWERDI33338`...), three are ferrite values written `1.5e+03 ohm` by the sampler (fixed in
`validate.py` after the run), one is `connector` alone (`generic`).

**Superset on production data.** `FieldQuerySupersetTest -Dkina.superset.pool=<6 657 cached payloads>
-Dkina.superset.queries=<290 queries: the 238 above and every cached query key>`: 8 099 parts (production and the
repository pool), 384 queries, every step, both dialects: 0 violations, 214 s.

### 2c. The cached lists replayed (on versus off)

Each of the 221 `cached_searches` query keys, searched at its distributor:

| Mode | hit | stale | partial | miss | errors | parts returned |
|---|---|---|---|---|---|---|
| `off` | 88 | 108 | 4 | 21 | 133 | 5 512 |
| `augment` | 88 | 108 | 4 | 21 | 133 | 6 911 |
| `on` | 196 | 18 | 0 | 7 | 25 | 9 920 |

`on` returns every part `off` returns for 118 queries; for 74 more both return 50 (the cap, not comparable); the 7
others are queries whose list has expired: `off` serves the expired list with `stale`, `on` tried the call (offline),
and served the index candidates of its first step. These need a live call and are excluded by the rule of the task.
`augment` returns every part of `off` on 191 queries and is capped on 8.

## 3. LCSC on the full file

Typed table built in the background at the first start (the file was adopted): 723 865 in-stock rows extracted and
inserted in 52.5 s (16 threads), indexes and `ANALYZE` 2.9 s, page cache warmed 5.9 s; sidecar
`parts-fts5.index.db` 402 MB. `jlcpcb.field_index`: `{enabled: true, available: false, building: true}` from 00:46:34
until 00:47:35, then `{available: true, version: 1, rows: 723865, building: false}`. Later starts attach it at once.

Requests of `max_results` 50, typed path (mode `on`) against the FTS path (`kina.jlcpcb.field-index.enabled=false`),
after the fixes. "Fits" are parts with `match` 1.0, no mismatch and nothing unverified; latency is the whole request
(retrieval, check, both rankers), first and warm median of 6 runs, page cache warm from earlier runs.

| Query | Typed: returned / fits / total | FTS: returned / fits / total | Typed first / warm | FTS first / warm |
|---|---|---|---|---|
| `10uF X7R 0805 25V` | 50 / 5 / 1 742 | 5 / 5 / 5 | 461 / 154 ms | 178 / 49 ms |
| `4.7k 1% 0603 resistor` | 50 / 50 / 180 | 44 / 44 / 76 | 405 / 242 ms | 769 / 584 ms |
| `female header 1x6 right angle` | 50 / 34 / 29 018 | 35 / 34 / 35 | 575 / 373 ms | 446 / 237 ms |
| `USB-C receptacle 16 pin SMD USB 2.0` | 50 / 50 / 845 | 47 / 46 / 211 | 433 / 227 ms | 760 / 563 ms |
| `Thin film resistor 5.36k 0805 0.1%` | 50 / 5 / 2 294 | 5 / 5 / 5 | 218 / 120 ms | 512 / 507 ms |
| `RP2040` | 3 / - / 3 | 3 / - / 3 | 24 / 14 ms | 23 / 10 ms |

The typed path never returns fewer fitting parts than the FTS path; where it returns more than 50 candidates, the two
paths pick different fitting parts (highest stock against FTS order). With `max_results` 10 the typed requests take
105 to 253 ms warm. The FTS path on a cold page cache (first run after the start, `max_results` 10): 486, 3 263, 1 147,
3 864, 1 465 and 22 ms.

Before the fixes the typed path returned, for the same queries: 38 fits (4.7k) and 23 fits (header), and 50 non-fans
for `40x40x10 fan 12V`.

## 4. End-to-end suite per mode

`kina_e2e.py` (88 checks) after restarting only the `kina` service per mode; LCSC typed table on in `shadow`,
`augment` and `on`, off in `off`.

| Mode | Passed | Failed checks |
|---|---|---|
| `off` | 87 / 88 | A |
| `shadow` | 84 / 88 | A, B, C, D |
| `augment` | 84 / 88 | A, B, C, D |
| `on` | 85 / 88 | B, C, D |

- **A** `search_parts again with max_results 20 is a cache hit for Mouser/TME`: TME's list for `10uF X7R 0805` has
  expired, `stale` without a live call. Needs TME credentials. Passes in `on` (answered from the index).
- **B** `electrolytic capacitor 470uF 35V 105°C 5000h THT names the parts left out below spec`: the typed LCSC path
  filters below-spec ratings in SQL, so no part is left out by the check and `excluded_below_spec_detail` is empty.
  A behaviour difference of the typed path (the response no longer says that below-spec parts exist).
- **C** `22uF X7R 0201 100V -> empty, hint`: the typed path keeps rows that do not state the attributes (blank
  descriptions, an unreadable package `SMD,6.1x5.3mm`) and returns 30 unverified parts with `match` 1.0 instead of the
  empty list and the hint. A behaviour difference of the superset rule on LCSC data.
- **D** `tactile switch 6x6 SMD returns 6x6 only`: the typed path also returns 6.2x6.2 and 6.3x6 mm switches, which
  the Java check accepts as 6x6 (within its tolerance); the FTS path never fetched them.

B, C and D are LCSC typed-path differences, not credential problems; they are open for a decision (section 7).
Checks that need credentials in every mode: none fails besides A, because the suite makes its Mouser/TME calls only
on a cold cache (two Mouser calls) and the restored cache holds the queries.

Per mode, the MCP searches of the suite (`cache/fetched` for TME and Mouser): `off` and `shadow` TME `stale/27`,
Mouser `hit/49`; `augment` TME `stale/27`, Mouser `hit/193`; `on` TME `hit/200`, Mouser `hit/193`. LCSC `fetched` 40
in every mode.

`kina_field_*` after the four runs (counters persist across restarts): `served` MOUSER 866, TME 726;
`journal_hits` MOUSER 55, TME 45; `fallbacks{reason=mode}` MOUSER 2 002, TME 1 764; `fallbacks{reason=generic}` TME 3;
`shadow_queries{outcome=ok}` MOUSER 224, TME 98 (no `dropped`, no `failed`); `shadow_candidates` MOUSER 27 678,
TME 14 134; `index_reindexed` MOUSER 3 064, TME 3 596; no `sql_error`, no `live_calls` (no call succeeded).

## 5. Metrics and performance

| | `off` | `shadow` | `augment` | `on` |
|---|---|---|---|---|
| RSS after the run (2 GB limit) | 1.40 GB | 1.39 GB | 1.30 GB | 1.45 GB |
| `10uF X7R 0805` Mouser, cached, median | 74 ms (49 parts) | 67 ms | 375 ms (193) | 413 ms (193) |
| `100nF X7R 0603 50V MLCC` | 77 ms (50) | 70 ms | 169 ms (87) | 210 ms (87) |
| `10uH inductor 0805` | 56 ms (50) | 48 ms | 342 ms (213) | 376 ms (213) |

- Memory: the RSS is the JVM heap growing toward its 1.5 GB maximum under load in every mode; right after a start it is
  about 470 MB. The pool (4 connections), the sidecar and `part_index` add no visible resident memory; the typed
  table build is transient. The container's page cache (the JLCPCB file) counts toward its memory limit and is
  reclaimed.
- Latency: `augment` and `on` load, enrich, check and rank up to 200 candidates instead of the 50 of a cached list:
  about 200 ms of retrieval and 130 ms of ranking for 193 candidates. Lowering `max-candidates` would cut it.
- Re-index of 6 660 rows: 9.5 s; journal backfill 0.7 s; V14 and V15: 23 ms.

## 6. Commands

```bash
scripts/e2e/field-search/run.sh build && scripts/e2e/field-search/run.sh db
scripts/e2e/field-search/run.sh restore kina-live-20261009.dump
scripts/e2e/field-search/run.sh snapshot baseline.txt
scripts/e2e/field-search/run.sh up on
scripts/e2e/field-search/run.sh snapshot after.txt && diff baseline.txt after.txt
scripts/e2e/field-search/run.sh psql "SELECT distributor, part_number FROM cached_parts WHERE in_stock" > parts.tsv
scripts/e2e/field-search/run.sh validate lookups --mcp-fallback --parts parts.tsv
scripts/e2e/field-search/run.sh validate sample --rows rows.tsv --out queries.json --per-group 10
scripts/e2e/field-search/run.sh validate recall --queries queries.json --out recall_on.json
python3 scripts/e2e/field-search/explain_misses.py recall_on.json --out explained.json   # needs statement logging
scripts/e2e/field-search/run.sh validate replay --searches searches.tsv --out replay_on.json
scripts/e2e/field-search/run.sh validate lcsc --max-results 50 --out lcsc_on.json
KINA_JLCPCB_FIELD_INDEX_ENABLED=false scripts/e2e/field-search/run.sh mode off
scripts/e2e/field-search/run.sh e2e --report e2e_off.json
./mvnw test -Dtest=FieldQuerySupersetTest -Dkina.superset.pool=pool.jsonl -Dkina.superset.queries=queries.txt
scripts/e2e/field-search/run.sh down
```

`rows.tsv` is the `part_index` row of every in-stock part with its description (the columns of `validate.py
COLUMNS`), `searches.tsv` the `cached_searches` keys (`distributor|query_key|list size|fresh`).

## 7. Limitations and open items

- Not verified: live distributor calls in mode `on` (the flow's calls, the journal writes, `partial` and `miss`
  answers, `live_calls > 0`), Mouser quota behaviour in production, and the cached-search path's refreshes. Every
  Mouser and TME call in this stack failed by design.
- When the field flow's first call fails it stops and serves the candidates of that step; the cached-search path
  serves the expired list instead. Evaluating the relaxed steps from the index after a failed call (no further call),
  and merging the expired list, would close the 7 `stale` differences of 2c.
- The typed LCSC path changes three answers the e2e suite expects (B, C, D in section 4). C is the most visible: an
  impossible request gets unverified parts instead of an empty list with a hint.
- `excluded_below_spec` is 0 on the field paths (Mouser/TME `on`, LCSC typed): below-spec parts are filtered in SQL
  and never counted, so the response cannot tell the caller that passing `allow_below_spec` would find parts.
- The REST part path refuses `%` and `\` in part numbers (77 cached parts); `get_part` works.
- `on` and `augment` are 2.5 to 5 times slower than `off` on cached Mouser queries (section 5).
- 4 202 of the 6 660 cached parts have no package key in the index (Mouser and TME packages the extractor does not
  read, such as `2917` in a description only); they stay reachable because NULL is kept, but a package request does
  not order them first.

## 8. Phase C2

The open items of section 7, the product decisions taken on them, and a rerun of the validation on the final code
(same stack, same production dump restored again into a fresh `kina-fs_pgdata`, same offline Mouser and TME).

### 8.1 What changed

| Item | Change |
|---|---|
| Merge | `feature/quota-tracking` merged (`--no-ff`, no textual conflict); the `search_parts` description is 2 679 characters (limit 2 700, kept) |
| Ratings (decision a) | the group `R` leaves the SQL filter on both dialects; it only orders the candidates. PostgreSQL orders by the ratings first (per rating: met 2, not stated 1, below spec 0), then by `confirmed`, so a part below spec takes a place only when the 100 rows leave room. The LCSC order counts the rating columns among the stated ones and then orders by stock, as an FTS window does, and the confirmed-only form puts the ratings in its `WHERE` as stated, never compared. The Java check excludes and counts below-spec parts (`excluded_below_spec`, `_detail`); the LCSC retriever hands them to the ranker without a place in the window. A requested rating makes a request selective (`mosfet 60V` reads the index) |
| Unverified parts (decision b) | returned as before, flagged; when every returned part leaves a stated constraint unverified, the entry and the response carry `ConstraintPolicy.unconfirmedHint`: `No in-stock 22uF capacitor in package 0201 at LCSC is confirmed: no part returned states its capacitance, voltage, dielectric and package (listed in unverified; check the datasheet); capacitance and package are never relaxed. ...` |
| Size tolerance (decision c) | unchanged; the e2e check accepts 6x6 within 0.5 mm |
| REST part path | `%25`, `%5C`, `%2F`, `%20` and `+` work in `GET /api/v1/parts/{d}/{*pn}` (`PartPathFirewall` on that path only, Tomcat `passthrough` for encoded slashes); new `GET /api/v1/parts/{d}?part_number=` |
| Failed call in `on` | no further call; the request's cached list (expired too) joins the candidates; the relaxed steps are read from the index; nothing found: the expired list as the cached-search path serves it |
| Candidate cap | `kina.search.field-index.max-candidates` 100 (was 200), `KINA_FIELD_INDEX_MAX_CANDIDATES`; the ranking stage checks and ranks at most that many in-stock parts per distributor; the field paths list live and cached-list parts first |
| e2e | C accepts an empty list or unverified parts with the "is confirmed" hint; D accepts 6x6 within 0.5 mm; the shape rule expects a hint when every part is unverified. B unchanged and passing |

Full suite: `./mvnw -q verify` green, 1 510 tests (3 skipped), `-Werror` clean. A first try of the ratings order (`confirmed` first with the
rating columns in it) filled the 100 PostgreSQL rows of `mosfet 55V SOT23` at TME with 83 parts below spec, and
`mosfet 60V` took the cached-search path (`generic`: the rating was its only stated constraint); both were fixed before
the numbers below.

### 8.2 Cache preservation

Baseline after the restore: identical to section 2 (MOUSER 3 064 / 3 061 in stock, TME 3 596, `cached_parts` md5
`2ea4c6bbf7d36f066541e66cb97e59c2`, 221 `cached_searches`, md5 `8d9afeec20ef01178afd030f647eb705`). After startup in
`on` (V14, V15, re-index 6 660 rows in 8.1 s, journal backfill 214 phrases in 498 ms, started in 2.1 s) and after every
run below (`on`, `on` with 200 candidates, `augment`, `off`): counts and both md5 values identical; only
`flyway.max` moved from 13 to 15.

Lookups, `validate.py lookups --query-form` (path form and `?part_number=` form, both must succeed, 16 in parallel):
**6 657 / 6 657** (MOUSER 3 061, TME 3 596), all through REST, none through MCP (the 77 part numbers with `%` or `\`
included), 4.0 s, p50 5 ms, p95 9 ms. Stock refreshes fail offline; 3 295 parts come back `stale`.

### 8.3 Recall and replay

Recall (the 238 own-attribute queries of 2b, same seed), mode `on`:

| | Answered without error | Part found | Errors |
|---|---|---|---|
| phase C (200 candidates, ratings in SQL) | 227 | 192 (84.6 %) | 11 |
| C2, 100 candidates | 234 | **201 (85.9 %)** | 4 |
| C2, 200 candidates | 234 | 198 (84.6 %) | 4 |

The 4 errors: `connector` alone (`generic`, cached-search path, offline) and three TME capacitor queries with a package
the parser does not read (`D63X54MM`, `2917`, `D19X205MM`): their first step is short, the call fails, and the relaxed
step now answers from the index (50 parts, cache `stale`, the error) where phase C returned the error. The 33 misses
are generic queries with more candidates than 50 returned (`fan 12V`, `capacitor 10uF 10%`, `mosfet 30V`): 32 of them
hold 100 candidates, and the cap at 200 finds 5 of them and loses 8 others (ranking order within the 50).

Replay of the 221 cached query keys:

| Mode | hit | stale | partial | miss | errors | parts returned |
|---|---|---|---|---|---|---|
| `off` | 88 | 108 | 4 | 21 | 133 | 5 512 |
| `augment` | 88 | 108 | 4 | 21 | 133 | 6 906 |
| `on` | 195 | 22 | 0 | 4 | 26 | 10 527 |

`on` returns every part `off` returns for 145 queries; for the other 76 `on` returns the full 50 (not comparable). No
query is left where `off` serves an expired list and `on` serves less (phase C: 7). `augment` returns every part of
`off` on 214 queries.

### 8.4 LCSC on the full file

`validate.py lcsc --max-results 50`, typed path (mode `on`), warm medians:

| Query | returned / fits / total | warm |
|---|---|---|
| `10uF X7R 0805 25V` | 17 / 5 / 5 | 229 ms |
| `4.7k 1% 0603 resistor` | 50 / 50 / 180 | 260 ms |
| `female header 1x6 right angle` | 50 / 34 / 29 018 | 367 ms |
| `USB-C receptacle 16 pin SMD USB 2.0` | 50 / 50 / 845 | 229 ms |
| `Thin film resistor 5.36k 0805 0.1%` | 50 / 5 / 2 294 | 120 ms |
| `RP2040` | 3 / - / 3 | 10 ms |

Fits are unchanged against phase C. `10uF X7R 0805 25V` returns 17 parts instead of 50: 83 of the 100 candidates are
below 25 V and are now excluded and counted (`excluded_below_spec` 83) instead of being filtered in SQL, and the FTS
search found only 5 rows to fill the window; the 33 parts it no longer returns were unverified fillers, not fits.
`electrolytic capacitor 470uF 35V 105°C 5000h THT` (`max_results` 50): 50 returned, 21 exact, `excluded_below_spec`
50 with the detail. `22uF X7R 0201 100V`: 50 unverified parts, `exact_matches` 0, `excluded_below_spec` 33, the
unconfirmed hint; the FTS path returns it empty with the hint (DESIGN 9.3: an improvement of the typed path, by the
declared semantics).

### 8.5 End-to-end suite and performance

`kina_e2e.py` (88 checks), LCSC typed table on in `on` and `augment`, off in `off`:

| Mode | Passed | Failed |
|---|---|---|
| `on` | **88 / 88** | none |
| `augment` | 87 / 88 | A |
| `off` | 87 / 88 | A |

A is the credential check of section 4 (TME's list for `10uF X7R 0805` has expired; it passes in `on`). B, C and D
pass in every mode. `shadow` was not rerun (no behaviour change in C2 besides the shared paths).

Latency, Mouser, cached, `max_results` 10, median of 20 warm runs (`validate.py latency`):

| Query | `off` | `augment` | `on`, 100 candidates | `on`, 200 candidates | phase C `on` (200) |
|---|---|---|---|---|---|
| `10uF X7R 0805` | 66 ms (49 parts) | 158 ms (104) | **198 ms (104)** | 399 ms (193) | 413 ms (193) |
| `100nF X7R 0603 50V MLCC` | 68 ms (50) | 146 ms (87) | 176 ms (88) | 204 ms (88) | 210 ms (87) |
| `10uH inductor 0805` | 48 ms (50) | 155 ms (132) | 184 ms (132) | 345 ms (213) | 376 ms (213) |

The cap halves `on` on large candidate sets; it is about 3 times `off`, from loading, enriching and ranking 100 parts
instead of 50. `kina_field_served_total` after the runs: MOUSER 614, TME 478; `kina_field_fallbacks_total{reason=
generic}` MOUSER 2.

### 8.6 Open items after C2

- Live calls in `on` remain unverified (no credentials on this host).
- The LCSC typed path returns fewer unverified fillers when most candidates are below spec (`10uF X7R 0805 25V`: 17
  instead of 50); a third window would find more, at the cost of reading more rows.
- Generic Mouser and TME queries with more than 100 candidates rank by the index order before the cut (`fan 12V`):
  the parts the cut drops are never ranked.
