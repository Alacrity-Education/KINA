# Field-based search over the cache (research, 2026-10-08)

Question from the product owner: instead of looking a search up in the cache by its normalised text, split the request
into constraints on feature fields and search the cache by those fields. Unknown keywords match words in the
description. Return parts that match every feature. When there are not enough, relax one feature and also ask the
distributor, so the cache gets new parts and the answer stays accurate. The database query must be fast (functional
and multi-column indexes). Estimate the write overhead and the disk cost of the indexes. For LCSC, evaluate moving the
JLCPCB SQLite data into Postgres, the index and storage cost on SQLite too, and the cost of refreshing the data when a
new file arrives, ideally as one atomic transaction.

This is research only. No code was changed. The code read is KINA 0.14.0 (`a2f188f`). Facts marked **verified** were
read in the code or measured today. Facts marked **inference** are estimates. Numbers in `{{...}}` come from three
measurement runs made in parallel (Postgres on the 7.1 M-row JLCPCB data, the same on SQLite, and the coverage of the
real attribute extractor); they are filled in section 12.

Live observations made for this report (read-only): the local compose stack's `cached_parts` and `cached_searches`
(SQL counts below), and one LCSC-only `search_parts_batch` call with the three worked examples (no Mouser quota used).

## 1. Summary and recommendation

**Recommendation: phased go for Mouser and TME, no-go for moving the raw LCSC data into Postgres (pending the numbers
in section 12), go later for a typed field table for LCSC kept inside the SQLite file.**

1. The field index is cheap at today's scale and the idea is sound. The local cache holds 6 660 parts (3 064 Mouser,
   3 596 TME), 25 MB in total, with 1.6 KB of JSON per part (**verified**). Any field query over this is a few
   milliseconds even without an index. Cost only becomes a question with the TME category filler of the earlier study
   (about 310 000 parts) or with LCSC (7.1 M rows). Measured on 7.1 M rows, a field query takes 3.8 ms when the value
   test is written as a range (`BETWEEN`) and 47 ms when written as `abs(value - x) <= y`, which no index can serve:
   the query builder must only emit ranges and plain comparisons.
2. The hard part is not speed. It is keeping the SQL filter consistent with the Java judge
   (`ConstraintPolicy.check`, `PageCollector.Check`). The design that stays stable is: **SQL is a recall filter, Java
   is the judge.** SQL must always return a superset of what the Java check would keep. It may be looser, never
   stricter. Every candidate is still enriched, checked and ranked exactly as today.
3. Do not put the fields on `cached_parts`. Use a separate `part_index` table (one row per part, written by the
   running extractor, with an `extractor_version` column and a background re-index). Reason: stock refreshes rewrite
   `cached_parts.payload` often, and those updates are already not HOT (`stock_fetched_at` is indexed), so every extra
   index on `cached_parts` would be rewritten on every stock refresh. A separate table is untouched by stock refreshes.
   Expression indexes on the JSONB payload are not an option: the payload holds raw distributor attributes with
   distributor spellings and units, never the SI values (`Part.asStored()`, DESIGN 3.2 "Cache model").
4. Keep `cached_searches`, but change what it means: a **journal** of "distributor D was asked phrase P at time T,
   with this total, next offset and exhausted flag", keyed by the distributor phrase, not by the user's text. The
   journal is what stops KINA from asking Mouser the same phrase twice (1 000 calls a day) and what keeps
   `total_results` honest. Without it, a field hit can silently hide parts the distributor has (coverage bias, risk R2).
5. The new flow: run the field query with every hard and ladder constraint; when it returns enough, answer from the
   index; when it does not, and the journal has no fresh entry for the current phrase, ask the distributor with that
   phrase, upsert, and run the field query again; then relax the next ladder constraint (SQL and phrase together) and
   repeat. Steps that change only the SQL (dropping free-text keywords) need no distributor call.
6. LCSC: the JLCPCB data is replaced as a whole every 5 days and is never written by KINA. SQLite with an atomic file
   rename is already the right refresh model. A typed field table of the in-stock rows can be built **inside the new
   file before the rename**, so the refresh stays atomic for free. Moving 7.1 M rows into Postgres is possible (load
   into a new table, `ANALYZE`, then rename-swap in one short transaction; measured: 94 s for the whole reload, a
   30 ms swap, no reader interrupted, field queries 4 to 80 ms). But it adds 3.78 GB to the Postgres volume and its
   backups, plus 3.5 GB of transient disk during each 5-day reload, for a gain that section 9 weighs once the SQLite
   numbers are in.
7. Effort: about 4 to 6 weeks of one engineer for Mouser and TME (phases 0 to 3), plus 1 to 2 weeks for the LCSC
   typed table (phase 4). Every phase sits behind a flag and keeps today's path as the fallback.

The three biggest risks (section 7): (R1) the SQL filter drifts from the Java check and silently drops good parts;
(R2) a field hit hides better parts the distributor has, because the cache only holds what earlier searches happened
to fetch; (R3) Mouser parts carry almost no structured attributes (4.4 attributes per part on average, all packaging;
**verified** locally), so most Mouser fields come from parsing descriptions and many are NULL.

## 2. How KINA searches today (what the change touches)

- **Mouser and TME** (`search/CachedDistributorRetriever.java`). The cache key is the user's normalised text
  (`ParsedQuery.normalizedKey()`). A fresh `cached_searches` row lists part numbers in the distributor's order; the
  parts are read from `cached_parts`, enriched (`ParametricExtractor.enrich`) and checked. The list is a HIT when it is
  exhausted, holds the window (40) or holds `max_results` (`isSufficient`, line 274); shorter lists are extended from
  `next_offset` (PARTIAL); a list whose parts are all excluded is treated as a miss (the safeguard). On a miss the
  phrase from `DistributorPhraser` is sent, and the relaxation ladder runs only while **nothing meets the request**
  (lines 132 to 165). The result is stored under the user's key with `fallback_query` and `constraints_relaxed`.
- **The cache** (`cache/PartCacheRepository.java`). `UPSERT` (line 48) replaces the payload, `UPDATE_STOCK` (line 67)
  rewrites the whole payload and `stock_fetched_at` on every stock refresh, `retype` (line 157) is an existing batch
  job that recomputes a derived column (`type`) for every row from the payload. That job is the pattern for a re-index.
- **LCSC** (`distributor/lcsc/JlcpcbSqliteSearch.java`). One read-only SQLite connection (`openReadOnly`, `mode=ro`)
  behind a `ReentrantReadWriteLock` (line 122). The `parts` table is an FTS5 virtual table with the trigram tokenizer.
  `JlcpcbQuery` turns the phrase into MATCH terms, LIKE clauses for short tokens, value-boundary checks and rating
  checks (`kina_at_least`); `search` (line 204) counts, then relaxes term by term (dead terms, then least informative,
  then without ratings, then PARAMETRIC, then ANY). The extractor runs only on the returned window (up to 200 rows).
  A refresh downloads a new file, validates it and moves it in with `ATOMIC_MOVE` (`JlcpcbDownloader.java` line 110)
  under the write lock (`replaceDatabase`, line 170).
- **The judge.** `ConstraintKind` declares, per kind, `@Relax` (NEVER, LADDER, SOFT, BELOW_SPEC, PREFERENCE, per
  policy family) and `@Match` (EQUAL, WITHIN 1 %, AT_LEAST, AT_MOST, CUSTOM...). `ConstraintPolicy` reads them;
  `PageCollector.Check.meets/confirmed/returnable` (lines 57 to 90) apply them; `DeterministicRanker` and the
  cross-encoder (top 40, `kina.search.cross-encoder.max-candidates`) rank. Ranking does not use the distributor's
  order (`DeterministicRanker.tieBreak`, line 318, uses stock, prices and library type only), so a candidate set from
  the index ranks the same way as one from a distributor page (**verified**).

Local cache profile (compose stack, **verified** 2026-10-08):

| | Mouser | TME |
|---|---|---|
| cached parts (in stock) | 3 064 (3 061) | 3 596 (3 596) |
| average payload | 1 639 B | 1 562 B |
| distributor attributes per part, mean / median | 4.4 / 4 | 15.3 / 15 |
| cached searches | 95 | 126 |
| searches that used a fallback phrase | 3 | 21 |
| empty searches | 1 | 18 |
| mean list length | 32.3 | 37.2 |

`cached_parts` is 25 MB with 752 kB of indexes. Types: capacitor 1 401, resistor 865, connector 855, inductor 681,
fan 479, switch 448, LED 429, unknown 411, MOSFET 294, ferrite 282, the rest under 150 each.

## 3. Data model for field search

### 3.1 Where the fields live: three options

| Option | How | Verdict |
|---|---|---|
| A. Columns on `cached_parts` (written by the upsert, or `GENERATED ALWAYS AS (...) STORED` from the payload) | Generated columns can only call immutable SQL; the extraction rules live in Java (`PartAttribute` `@Source`, `domain.extract`), so a SQL copy would be a second extractor that drifts. Columns written by the upsert work, but `UPDATE_STOCK` rewrites the row on every stock refresh, and those updates are not HOT because `stock_fetched_at` is indexed: every new index would be updated on every refresh. | No |
| B. Expression indexes on `payload` | The payload holds distributor attributes only (`Part.asStored()`), with distributor names and units (`Capacitance: 10µF`, TME `Case - mm: 2012`, Mouser descriptions). No SI value exists in it to index. | No |
| C. A separate `part_index` table, one row per part, written by the running extractor | Derived values are never written into the payload (the DESIGN rule stays intact). Stock refreshes do not touch it. It can be rebuilt at any time from `cached_parts`. An `extractor_version` column tells which rows are stale. | **Yes** |

### 3.2 Which constraint maps to which field

The judge reads `PartFeatures` (`ParametricExtractor.Features`, line 220: family, a map of SI values, dielectric,
package, mounting, connector, technology, elements, polarity, subtype, the list of spec voltages, form factor, fan, LED,
switch). The index stores the same values, so it is filled from `features(part)`, not from the display strings of
`extract`.

| `ConstraintKind` | Column | Type | SQL rule (looser than or equal to Java) |
|---|---|---|---|
| `TYPE` | `family`, `family_path text[]` | text, array | `family_path && :compatible` (the request's family and its compatible families from `Recognizers.compatibleFamilies`; `schottky` fits `diode`); `family IS NULL` kept |
| `POLARITY` | `polarity` | text | equal, or NULL |
| `VALUE` (capacitance, resistance, inductance, impedance, frequency) | `capacitance_f`, `resistance_ohm`, `inductance_h`, `impedance_ohm` + `impedance_test_hz`, `frequency_hz` | float8, SI | range `[x/1.015, x*1.015]` (Java: 1 %, `ConstraintKind.sameValue` line 1692), or NULL |
| `LOAD_CAPACITANCE` | `capacitance_f` of a crystal | float8 | as above |
| `EXACT_VOLTAGE` | `voltages_v float8[]` | array | any element within 2.5 % (Java 2 %), or empty |
| `VOLTAGE_RATING`, `CURRENT`, `SATURATION_CURRENT`, `POWER`, `TEMPERATURE`, `LIFETIME` | `voltage_v`, `current_a`, `isat_a`, `power_w`, `max_temp_c`, `lifetime_h` | float8 | `>= x*(1-1e-6)`, or NULL; dropped entirely with `allow_below_spec` |
| `MAX_DCR`, `MAX_CURRENT`, `NOISE` | `dcr_ohm`, `current_a`, `noise_dba` | float8 | `<=`, or NULL |
| `PACKAGE` | `package_key`, `package_readable`, `can_d_mm`, `can_l_mm` | text, bool, real | `package_key = Recognizers.packageKey(x)` (imperial, `SOT-23` == `TO-236AB`), or not readable; can sizes within 0.25 mm |
| `MOUNTING` | `mounting` | text | equal, or NULL (a hybrid USB part is stored NULL) |
| `TECHNOLOGY` | `technology` | text | `technology IN (:compatible)` from `TechnologyVocabulary` (compare >= 0), or NULL |
| `DIELECTRIC` (LADDER) | `dielectric` | text | equal ignoring case, or NULL |
| `TOLERANCE` (LADDER) | `tolerance_pct` | real | `<= x` (a tighter tolerance is fine), or NULL |
| `FORM_FACTOR`, `ELEMENTS` | `form_factor`, `elements` | text, int2 | `FormFactor.compatible` set; `elements IS NULL` when no array is asked |
| `CONNECTOR_TYPE`, `GENDER`, `POSITIONS`, `PITCH`, `ORIENTATION` | `connector_type`, `gender`, `positions`, `rows`, `pitch_mm`, `orientation` | text, int2, real | equal (pitch within 0.03 mm), or NULL |
| `USB_TYPE`, `PIN_CONFIGURATION`, `USB_STANDARD` | `usb_type`, `pin_configuration`, `usb_class` | text, int2, int2 | equal; standard `usb_class >= :class` (a higher class is accepted); pin configuration on the canonical count (17P and 18P stored as 16) |
| fan, LED and switch kinds (`FAN_TYPE`, `FRAME_SIZE`, `COLOUR`, `SWITCH_TYPE`, `CONTACTS`...) | `attrs` JSONB | jsonb | `attrs @> '{"colour":"red"}'` or the key absent; their CUSTOM comparators stay in Java |
| SOFT and PREFERENCE kinds (`SPEED`, `BEARING`, `LENS`, `LOW_DCR`...) | `attrs` JSONB | jsonb | never in SQL (ranking only) |
| free-text keywords | `search_tsv`, `search_text` | tsvector, text | `search_tsv @@ to_tsquery('simple', 'k1 & k2')`; tokens under 3 characters by `search_text LIKE`; MPN prefixes by trigram |

Every rule keeps rows where the column is NULL, because the judge keeps a part that does not state an attribute
(unverified, ranked lower). Section 4.3 shows how that is queried without losing the index.

Rules for the table:

- Columns exist only for kinds that are hard (`NEVER`) or rated (`BELOW_SPEC`) for some family, or on the ladder.
  Everything else goes into `attrs`. This keeps the index set small (section 8).
- The mapping should be **declared on the model**, in the style of `@Relax` and `@Match`: for example an
  `@Indexed(column = "capacitance_f", predicate = RANGE, slack = 0.005)` on the `PartAttribute` or `ConstraintKind`
  constant, read by a `FieldQueryBuilder`. A documentation test like `ConstraintTableDocumentationTest` then checks the
  DESIGN table against the declarations, and a test checks that every hard or rated kind has an index rule or is
  declared `attrs`-only.
- `search_text` is the description, category, MPN and manufacturer, normalised exactly like the query key (NFKC,
  lower case, `µ` to `u`, `Ω` to `ohm`), so `10µF` and `10uF` match.

### 3.3 Keeping the index consistent with the running extractor

Extraction changes between releases (0.14.0 changed fans; DESIGN 3.4 records many 2026-10-07 changes). The rule
"derived attributes are never stored" exists so that old rows always show the current extractor. The index must give
the same guarantee for the filter, or it must be harmless when it lags.

1. **Version column.** `part_index.extractor_version` holds a constant `ParametricExtractor.INDEX_VERSION`. It is a
   number bumped by hand when extraction changes, checked by a test: the test hashes the golden extraction output of
   `ExtractionGoldenTest` and fails when the hash changes without a version bump. (Using the app version would
   re-index on every release, which is also acceptable at today's size.)
2. **Write path.** `upsertAll` and `upsertListed` write the `part_index` row in the same transaction as the payload,
   from the already enriched `Part`. The update is skipped when the new row is equal to the old one
   (`WHERE (part_index.*) IS DISTINCT FROM (EXCLUDED.*)`), so a refetch of an unchanged part costs no index writes.
3. **Re-index job.** A background task in the style of `PartCacheRepository.retype` and `MetricsBackfill`: after
   `ApplicationReadyEvent`, read `cached_parts` in key order, 500 rows at a time, for rows whose index row is missing or
   has an older version, enrich, write. It never blocks startup. Cost: rows divided by the extractor rate
   ({{EXTRACT_ROWS_PER_S}} rows/s measured), so 6 660 rows take about {{EXTRACT_CACHE_REINDEX_S}} s, and 310 000 rows
   (TME filler) about {{EXTRACT_310K_REINDEX_S}} s.
4. **While rows are stale.** The field query is only a candidate filter, so a stale row has two possible effects: a
   part included wrongly (the Java check excludes it; cost: one wasted candidate), or a part dropped wrongly (lost
   recall). The second is mitigated by making SQL looser than Java (slack on values, NULL kept everywhere) and, during
   a re-index, by treating rows with an older version as NULL in every column except `family` (one extra predicate
   `OR extractor_version < :current`). The flag `kina.search.field-index.min-version` lets an operator force that.

## 4. Query model

### 4.1 From `ParsedQuery` to predicates

For a request and a distributor the builder produces an ordered list of predicate groups:

- **H** (never relaxed): every kind whose resolved strategy for the request's policy family is `NEVER`
  (`ConstraintPolicy.strategy`), plus the always-on rules: `distributor = :d`, `in_stock`, and the `elements` rule.
- **R** (ratings): every stated `BELOW_SPEC` kind, unless `allow_below_spec` is true (then they are only ranked).
- **L1..Ln** (ladder): stated `LADDER` kinds in `@Relax(order)`: dielectric, package (only where it is not hard:
  inductors, crystals, oscillators, USB, fans), tolerance, orientation, then the family-specific ones (speed and
  bearing for fans, lens, viewing angle and colour temperature for LEDs, force for switches).
- **K** (keywords): the free-text keywords (`ParsedQuery.keywords`), ALL-match.

SOFT kinds are not in SQL. Family rules come from the declarations, not from the builder: the package of an inductor is
`LADDER`, so it is an `L` group, not `H`; for a crystal the capacitance is `LOAD_CAPACITANCE`, not `VALUE`.

### 4.2 Relaxation as dropped predicates

The SQL ladder is: `H+R+L1..Ln+K`, then without `K`, then without `L1`, without `L1,L2`, and so on, the same order as
`DistributorPhraser.ladder` (line 142). Dropping `K` first matches LCSC's drop order (free-text keyword first,
DESIGN 9.3) and, for an understood query, costs nothing: keywords are a ranking signal only, never in the grade. `H`
and `R` are never dropped. When every step is exhausted, the answer is empty with the `ConstraintPolicy.hint`, as
today.

### 4.3 The "unknown attribute" rule without losing the index

Today a part that does not state a requested rating or attribute still meets the request (unverified). In SQL that is
`(col IS NULL OR col >= x)`. Measured on 7.1 M rows (section 12.1): the planner never uses such a predicate as an
index condition; it applies it as a filter to the rows the other, indexed predicates found, and that costs nothing
extra (Q1 47.3 ms, Q1 with two more `IS NULL OR` ratings 49.0 ms). So the rule is cheap **as long as at least one
predicate is sargable**: the family plus a value range, or the family plus a package.

**The value filter must be a range.** The same query took 47.3 ms with `abs(value_num - 10e-6) <= 10e-6 * 0.01` (not
sargable: Postgres used the `(family, package)` index and filtered 88 434 heap rows) and 3.8 ms with `value_num
BETWEEN 9.9e-6 AND 10.1e-6` (it used the partial in-stock `(family, value_num)` index), 12x faster for the same 5
rows. The builder must emit every value, tolerance and rating comparison as a bare column against constants
(`BETWEEN`, `>=`, `<=`, `=`), never as an expression over the column; a test should assert this (for example by
checking that the plans of the eval queries use an index condition on a value column).

Two ways to keep the confirmed parts first:

1. **Two branches.** `confirmed` (every stated column `IS NOT NULL` and in range) `UNION ALL` `unverified` (at least one
   stated column NULL, the rest in range), each with its own `LIMIT`. It also gives the "at least one confirmed" count
   that paging needs (`PageCollector`, line 130) without a second query.
2. **One query ordered by a confirmed flag** (the examples in 4.5). Simpler; with the measured filter cost it is just
   as fast while a sargable predicate bounds the rows.

Recommended: form 2 first, form 1 only if a family proves to have a large unverified share (Mouser, risk R3). The candidate list is ordered confirmed first, then by stock (descending), then by part number,
and capped at `kina.search.field-index.max-candidates` (proposal 200; the deterministic ranker scores 40 candidates
in 1.8 ms, so 200 cost about 10 ms; the cross-encoder still takes the top 40).

### 4.4 What "enough" means

Proposal, matching today's two rules (`isSufficient` and `PageCollector`'s "at least one confirmed"):

- **enough** when the field query returns at least `max_results` parts that pass `H` and `R` **and** at least one of
  them is confirmed (every stated constraint stated by the part), **or** the journal says the distributor has nothing
  more for the current phrase (exhausted, or `max-pages-per-search` used).
- Not the candidate window of 40. A window of 40 would make Mouser calls far more frequent, because the index pools
  parts from many phrases and is meant to answer without calls. The window still caps how many candidates are ranked.

### 4.5 Worked examples

Values come from the real parser (LCSC-only call on 2026-10-08, `parsed` field, **verified**). SQL is a sketch for
Postgres; `:d` is the distributor.

**Example 1: `10uF X7R 0805 25V`** parses as `family capacitor, capacitance 10uF, voltage 25V, dielectric X7R, package
0805`. Policy family `capacitor`: hard `type`, `value`, `package` (plus mounting, technology, form factor, elements
when stated or implied); rating `voltage`; ladder `dielectric`.

```sql
-- step 0: H + R + L(dielectric); no keywords
SELECT i.part_number, (i.capacitance_f IS NOT NULL AND i.voltage_v IS NOT NULL AND i.dielectric IS NOT NULL
                       AND i.package_readable) AS confirmed
FROM part_index i
WHERE i.distributor = :d AND i.in_stock
  AND (i.family_path && ARRAY['capacitor'] OR i.family IS NULL)
  AND (i.capacitance_f BETWEEN 9.852e-6 AND 1.015e-5 OR i.capacitance_f IS NULL)
  AND (i.package_key = '0805' OR NOT i.package_readable)
  AND i.elements IS NULL
  AND (i.voltage_v >= 25 OR i.voltage_v IS NULL)
  AND (lower(i.dielectric) = 'x7r' OR i.dielectric IS NULL)
ORDER BY confirmed DESC, i.stock DESC, i.part_number
LIMIT 200;
-- step 1 (dielectric relaxed): the same without the dielectric line; phrase "MLCC 10uF 0805"
```

Index used: `(family, capacitance_f)` partial on `capacitance_f IS NOT NULL`, or `(package_key, capacitance_f)`. The
Postgres run measured this query on 7.1 M LCSC rows: 3.8 ms with the `BETWEEN` range shown here, 47.3 ms when the
value test was written as `abs(...) <= ...` (section 4.3).
Live LCSC found 5 parts for this request (all 25 V X7R 0805, `total_results 5`); the field query over LCSC would also
return the 50 V and 100 V parts if the text search missed any (it did not here, `kina_at_least` already checks `>=`).

**Example 2: `4.7k 1% 0603 resistor thin film`** parses as `family resistor, resistance 4.7kohm, tolerance 1%, package
0603, technology thin film`. Hard: `type`, `value`, `package`, `technology`; ladder: `tolerance`.

```sql
WHERE i.distributor = :d AND i.in_stock
  AND (i.family_path && ARRAY['resistor'] OR i.family IS NULL)
  AND (i.resistance_ohm BETWEEN 4630.5 AND 4770.5 OR i.resistance_ohm IS NULL)   -- 4.7k; 4.75k (E96) stays out
  AND (i.package_key = '0603' OR NOT i.package_readable)
  AND (i.technology IN ('thin film', 'metal film') OR i.technology IS NULL)      -- the compatible set
  AND i.elements IS NULL
  AND (i.tolerance_pct <= 1 OR i.tolerance_pct IS NULL)                          -- L: tolerance
```

Note `4.75k` is 1.06 % from 4.7k: Java excludes it (1 %), and the SQL slack of 1.5 % includes it, which is the
intended direction (SQL looser). The compatible technology set is an assumption to check against
`TechnologyVocabulary.compare`. Live LCSC found 15 parts, all thin film 1 % 0603.

**Example 3: `USB-C receptacle 16 pin SMD USB 2.0`** parses as `family connector, mounting SMD, connector {type usb-c,
gender female, positions 16, usb_type Type-C, usb_standard USB 2.0, pin_configuration 16}`. Policy family `usb`: hard
`type`, `mounting`, `usb type`, `pin configuration`, `usb standard`, `gender`; ladder `package`, `orientation`.

```sql
WHERE i.distributor = :d AND i.in_stock
  AND (i.family_path && ARRAY['connector'] OR i.family IS NULL)
  AND (i.usb_type = 'Type-C' OR i.usb_type IS NULL)
  AND (i.gender = 'female' OR i.gender IS NULL)
  AND (i.mounting = 'SMD' OR i.mounting IS NULL)          -- hybrid parts are stored NULL
  AND (i.pin_configuration = 16 OR i.pin_configuration IS NULL)   -- 17P/18P stored as 16
  AND (i.usb_class >= 2 OR i.usb_class IS NULL)           -- USB 2.0 or higher accepted
```

Index used: partial `(usb_type, pin_configuration) WHERE usb_type IS NOT NULL`. Live LCSC fetched 40 of 211 and
excluded 2 (`pin configuration 1`, `mounting 1`). The first result is a stability example: its description says
`Type-C USB 3.1`, its MPN says `USB 2.0 TYPE-C`, and the extractor stored `UsbStandard: USB 2.0`. A single-valued
column records one reading; the judge reads the same one, so they agree, but a later extractor change flips it (risk
R4, section 7).

## 5. Relaxation plus distributor flow

### 5.1 The journal (what `cached_searches` becomes)

`cached_searches` stays, re-keyed: `query_key` becomes `phrase:<distributor phrase, normalised>` for new rows. The
phrase is a pure function of the parsed query (DESIGN 3.2 "Distributor phrasing"), so `10uF X7R 0805 25V`, `0805 X7R
10uF 50V` and `10 uF 25V X7R 0805 MLCC` share the phrase `10uF X7R 0805` and one journal row when ratings are the only
difference. The row keeps `total_results`, `next_offset`, `exhausted`, `out_of_stock_matches`, `fetched_at` and the
part list (needed for the expired-list fallback, `servedStale`). `requested_parts` stays keyed by the user's query (it
is about part numbers the user typed). Old rows keyed by the user's text expire within `kina.cache.ttl` (3 days); no
data migration is needed.

### 5.2 Decision flow (Mouser, TME)

```
search(request, distributor D):
  groups = [H, R, L1..Ln, K]                 # section 4.1
  steps  = [all] + [drop K] + [drop L1] + [drop L1,L2] + ...
  calls  = 0
  for step in steps:
      phrase = DistributorPhraser phrase for this step       # unchanged by "drop K"
      rows   = fieldQuery(D, step)                           # Postgres, ms
      if enough(rows):                                       # 4.4
          return rows, cache = (calls == 0 ? HIT : PARTIAL)
      j = journal(D, phrase)
      if j is fresh and (j.exhausted or j.pages_used >= max-pages):
          continue                                           # asked already; relax
      if deadline passed or calls >= max-calls(D):           # Mouser 2, TME 4 (proposal)
          break
      page = D.search(phrase, offset = j.next_offset or 0)   # rate-limit waits inside the deadline
      calls += 1
      upsert parts + part_index rows; upsert journal(D, phrase)
      rows = fieldQuery(D, step)
      if enough(rows):
          return rows, cache = (step == all ? MISS : PARTIAL)
  return best rows found (least relaxed step with parts), hint when empty
then, as today: requested part numbers (RequestedLookup), rank, StockRefresher (top max_results), assemble
```

Differences from today:

- The ladder runs when there are **not enough** parts, not only when **nothing meets** the request. Relaxed parts
  then fill the list after the exact ones (they carry a mismatch, so they rank in a lower tier). This is the user's
  rule. It changes `constraints_relaxed` from "what we had to loosen to find anything" to "what the returned parts
  miss", which `ResponseAssembler.actuallyRelaxed` already computes.
- A distributor call happens only when the journal has no usable entry for the phrase of the current step. Repeated
  and reworded requests share journal rows. Requests that differ only by rating (`25V` vs `50V`) never cause a call
  when the first was journaled.
- The stock step is unchanged. Field candidates can be older than today's list parts (they came from any earlier
  phrase), so more of the top `max_results` may need a refresh: still one batch call per distributor (Mouser 10 parts,
  TME 50 parts per call) and at most 2 rounds (`StockRefresher.STOCK_REFRESH_ROUNDS`).
- The 2-minute deadline (`kina.search.max-request-duration`) still bounds everything; a step is skipped when the next
  call would not finish.

### 5.3 Mouser quota

Mouser allows 1 000 calls a day and 50 records per call, with `max-pages-per-search: 1`. Today a request costs 1 call
plus up to 4 ladder calls (only when nothing meets), plus lookups and refreshes. With the journal, the field flow can
spend fewer calls (shared phrases, rating variants, index hits) or more (the ladder now also runs when results are
short). The cap `max-calls(MOUSER) = 2` per request bounds the second effect. Which effect dominates depends on the
query mix and cannot be measured without production logs (section 11). A shadow mode (phase 0) logs both counts.

### 5.4 What the LLM-facing fields mean

| Field | Today | With field search |
|---|---|---|
| `cache` | `hit`: the stored list for this text; `partial`: list extended; `miss`: live | `hit`: answered from the index, no call; `partial`: index plus at least one call; `miss`: the index had nothing usable for the unrelaxed step and a call was made; `stale`, `bypassed`, `not_applicable` unchanged |
| `fallback_query` | the ladder phrase that produced the parts | the phrase of the step that produced the returned parts (null for step 0) |
| `constraints_relaxed` | ladder constraints the parts really miss | unchanged definition (it already only lists what returned parts miss) |
| `total_results` | the distributor's count for the phrase that produced the parts | the journal's count for that phrase (still the distributor's number); null when the answer came from the index without a journal row for that phrase |
| `fetched` | in-stock parts received for the query | in-stock parts the field query returned (before exclusions); a new count `fetched_live` (parts received by calls in this request) keeps the old meaning visible |
| `exact_matches`, exclusions, `hint` | unchanged | unchanged (computed by the judge) |

These are contract changes: DESIGN 3.2 and the `KinaMcpTools` descriptions must change in the same commit.

## 6. Ranking interaction

Nothing in ranking reads the distributor's order. The deterministic ranker and the cross-encoder run over the field
candidates exactly as over a distributor page. Two side effects: candidates come pre-sorted by "confirmed, stock" (the
SQL order), which matters only when more than `max-candidates` match; and a larger pool means the cross-encoder sees
the best 40 by deterministic score instead of the distributor's first 40. Both should help NDCG; the eval in phase 3
checks it.

## 7. Stability risk register

| # | Risk | Effect | Likelihood | Mitigation |
|---|---|---|---|---|
| R1 | SQL predicates drift from the Java check (new comparator, tolerance change, vocabulary added) | Good parts silently dropped by SQL | High over time | SQL is a filter only, always looser (slack, NULL kept); an invariant test: for every eval candidate and every cached fixture part, `Check.returnable` true implies the SQL predicate true; predicates declared on the model next to `@Match`; extractor version and re-index |
| R2 | Coverage bias: the cache holds what earlier phrases fetched; a hit hides better parts the distributor has | Worse or narrower answers, no visible signal | High for narrow earlier searches (`... Murata`) | Hit only with a fresh journal row for the step's own phrase (5.1); later, a journal of TME category enumerations (filler) as coverage |
| R3 | Mouser gives almost no attributes (4.4 per part, packaging) | Most Mouser columns NULL; field query degenerates to family + text; unverified branch large | Certain | Description parsing coverage {{EXTRACT_MOUSER_VALUE_PCT}} % value, {{EXTRACT_MOUSER_PACKAGE_PCT}} % package (section 12); keep the distributor call path for Mouser when coverage of the stated kinds is low |
| R4 | Normalisation and multi-valued data: a part states two standards, several voltages, metric and imperial codes | One column records one reading | Medium | Arrays for multi-valued fields (`voltages_v`); canonical package key from `Recognizers.packageKey`; the judge decides anyway |
| R5 | Value tolerance and E-series neighbours (4.7k vs 4.75k, 10uF vs 10.5uF) | Wrong inclusion or exclusion at the edge | Low | SQL slack 1.5 %, Java 1 % decides |
| R6 | Package equivalences (0805 = 2012 metric, SOT-23 = TO-236AB, can sizes) | Missed parts | Medium | Store the imperial key; never index raw strings; unreadable package stored as "not readable", never a conflict |
| R7 | CJK and symbols in LCSC descriptions (`℃`, `Ω`, `±`, `弯插`) | Text search misses or matches noise | Medium for LCSC | Normalise like the query key; `simple` tsvector; CJK only used by the extractor as today; pg_trgm behaviour with the database locale is unverified (section 11) |
| R8 | Non-sargable predicates (`abs(col - x) <= y`, functions over columns); `IS NULL OR` | 12x slower plans at scale (47 ms vs 3.8 ms measured) | Medium (an easy mistake) | Emit ranges only (4.3) and test the plans; `IS NULL OR` measured free as a filter; `ANALYZE` after bulk loads |
| R9 | Concurrency: two requests upsert the same parts; the re-index job writes at the same time | Deadlocks, lost updates | Low | Sort batches by key; one statement per batch; re-index writes only rows with an older version (`WHERE extractor_version < :v`) |
| R10 | Semantic change of `cache`, `fetched`, `total_results` | LLM misreads counts | Medium | Document in DESIGN 3.2 and the tool descriptions; add `fetched_live` |
| R11 | Mouser quota: the ladder now runs on short results | More calls per request | Medium | Per-request call cap; journal; shadow-mode counts before enabling |
| R12 | Migration of the existing cache | Empty index on first start | Certain, short | Background re-index fills 6 660 rows in seconds; flag stays off until it is done |
| R13 | Metadata kept forever: a field hit can return a part whose metadata is old (lifecycle, description) | Outdated metadata | Low | Unchanged from today: refetches replace the payload; stock refresh catches sold-out parts |
| R14 | LCSC typed table out of date with the extractor between refreshes | Filter drift for LCSC | Medium | Rebuild on version change (section 9.4), not only every 5 days |

## 8. Postgres index plan (Mouser and TME cache)

### 8.1 DDL sketch (migration V14)

```sql
CREATE TABLE part_index (
  distributor        TEXT    NOT NULL,
  part_number        TEXT    NOT NULL,
  extractor_version  INTEGER NOT NULL,
  indexed_at         TIMESTAMPTZ NOT NULL,
  in_stock           BOOLEAN NOT NULL,          -- copy of cached_parts.in_stock (markSoldOut, upserts)
  stock              INTEGER NOT NULL,          -- copy for the SQL order; not indexed, so updates stay HOT
  family             TEXT,                      -- ComponentFamily label, NULL unknown
  family_path        TEXT[]  NOT NULL DEFAULT '{}',  -- the family and its parents (schottky, diode)
  policy_family      TEXT,
  subtype            TEXT,
  polarity           TEXT,
  package_key        TEXT,                      -- Recognizers.packageKey of the imperial package
  package_readable   BOOLEAN NOT NULL DEFAULT false,
  can_d_mm REAL, can_l_mm REAL,
  mounting           TEXT,                      -- SMD / THT; NULL unknown or hybrid
  technology         TEXT,
  dielectric         TEXT,
  form_factor        TEXT,
  elements           SMALLINT,                  -- NULL single element; 0 array of unstated size
  capacitance_f      DOUBLE PRECISION,
  resistance_ohm     DOUBLE PRECISION,
  inductance_h       DOUBLE PRECISION,
  impedance_ohm      DOUBLE PRECISION,
  impedance_test_hz  DOUBLE PRECISION,
  frequency_hz       DOUBLE PRECISION,
  tolerance_pct      REAL,
  voltage_v          DOUBLE PRECISION,
  voltages_v         DOUBLE PRECISION[] NOT NULL DEFAULT '{}',
  current_a          DOUBLE PRECISION,
  isat_a             DOUBLE PRECISION,
  power_w            DOUBLE PRECISION,
  max_temp_c         REAL,
  lifetime_h         REAL,
  dcr_ohm            DOUBLE PRECISION,
  connector_type     TEXT,
  gender             TEXT,
  positions          SMALLINT,
  rows_count         SMALLINT,
  pitch_mm           REAL,
  orientation        TEXT,
  usb_type           TEXT,
  usb_class          SMALLINT,                  -- UsbVocabulary rank of the standard
  pin_configuration  SMALLINT,                  -- canonical (17/18 -> 16)
  attrs              JSONB NOT NULL DEFAULT '{}',   -- long tail: canonical key -> SI number or text
  search_text        TEXT NOT NULL,             -- normalised description, category, MPN, manufacturer
  search_tsv         TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', search_text)) STORED,
  PRIMARY KEY (distributor, part_number),
  FOREIGN KEY (distributor, part_number) REFERENCES cached_parts ON DELETE CASCADE
) WITH (fillfactor = 90);

-- values: one partial index per primary value, led by the distributor and family
CREATE INDEX part_index_cap  ON part_index (distributor, family, capacitance_f)  WHERE capacitance_f  IS NOT NULL;
CREATE INDEX part_index_res  ON part_index (distributor, family, resistance_ohm) WHERE resistance_ohm IS NOT NULL;
CREATE INDEX part_index_ind  ON part_index (distributor, family, inductance_h)   WHERE inductance_h   IS NOT NULL;
CREATE INDEX part_index_freq ON part_index (distributor, family, frequency_hz)   WHERE frequency_hz   IS NOT NULL;
CREATE INDEX part_index_pkg  ON part_index (distributor, package_key, family);
CREATE INDEX part_index_conn ON part_index (distributor, connector_type, positions) WHERE connector_type IS NOT NULL;
CREATE INDEX part_index_usb  ON part_index (distributor, usb_type, pin_configuration) WHERE usb_type IS NOT NULL;
CREATE INDEX part_index_fam  ON part_index (distributor, family);
CREATE INDEX part_index_tsv  ON part_index USING gin (search_tsv);
CREATE INDEX part_index_trgm ON part_index USING gin (search_text gin_trgm_ops);  -- MPN fragments, short tokens
CREATE INDEX part_index_attrs ON part_index USING gin (attrs jsonb_path_ops);
CREATE INDEX part_index_version ON part_index (extractor_version);
```

Ratings (`voltage_v`, `current_a`, `power_w`...) get no index of their own: they are never selective alone and are
always combined with a value or package. `pg_trgm` is available in `postgres:17-alpine` (**verified** by the
Postgres run: `CREATE EXTENSION pg_trgm` on 17.11).

Two lessons from the measurements shape the DDL:

- **Never put `stock` or `in_stock` into an index or an index predicate.** The benchmark's partial index
  `WHERE stock_int > 0` made every stock update a non-HOT update (`n_tup_hot_upd` 0 with all indexes): each update
  then writes into every index. In the sketch above neither column is indexed, so `UPDATE_STOCK` and `markSoldOut`
  stay HOT. (For LCSC, where stock only changes at a reload, a partial in-stock index is fine and was the fastest plan.)
- **Stored tsvector column or expression index.** On 7.1 M rows the stored generated column cost 524 MB of heap and
  made the GIN build 2.7x faster (5.5 s vs 14.6 s); the expression index `to_tsvector('simple', description)` costs no
  heap; query speed is equal. For the cache (thousands of rows) either is fine; for LCSC in Postgres use the
  expression index.

### 8.2 Measured selectivity and latency

With the cache at 6 660 rows, every index is optional: a sequential scan of the whole table is a few milliseconds
(**inference**: 25 MB, fully cached; not measured). Indexes matter for the TME filler (310 000 parts) and for LCSC in
Postgres. On the 7.1 M-row JLCPCB data (Postgres 17.11, warm, host under memory pressure; timings within 2x are equal)
the measured plans were (section 12.1 has every query):

| Query | Rows returned | Plan | warm median / first ms |
|---|---|---|---|
| example 1, value as `BETWEEN` range (10uF X7R 0805, voltage `IS NULL OR >= 25`, in stock, LIMIT 40) | 5 | partial in-stock `(family, value_num)` btree | 3.8 / 5.8 |
| example 1, value as `abs(value - x) <= y` | 5 | `(family, package)` bitmap, 88 434 heap rows filtered | 47.3 / 64.3 |
| example 2 (resistor 4.7k 1% 0603, `abs` form; thin film not in this query) | 40 | `(family, package)` | 64.0 / 107.9 |
| example 3 (USB-C) | not measured: the benchmark schema had no connector or USB columns | | |
| full text `thin film` + resistor + 5.36k | 15 | tsvector GIN BitmapAnd value btree | 34.6 / 35.0 |
| full text `thin film` only, count (266 245 matches) | 1 | tsvector GIN | 79.8 / 77.9 |
| `description ILIKE '%X7R%'`, in stock, LIMIT 40 | 40 | trigram GIN + value btree | 37.3 / 41.4 |
| MPN fragment `mpn ILIKE '%ERA6AEB%'`, LIMIT 40 | 40 | trigram GIN on MPN | 0.50 / 0.52 |
| jsonb `@> {"Dielectric":"X7R"}` + value | 40 | jsonb GIN + value btree | 35.1 / 34.7 |
| example 1 (`abs` form) plus two more `IS NULL OR` ratings | 5 | same as example 1 `abs` form | 49.0 / 47.8 |
| `count(*)` of example 1 (`abs` form), the honest total | 1 | `(family, package)` | 41.5 / 40.9 |

No query used a sequential scan; all are under 130 ms warm. The `abs` form of examples 1 and 2 is what a naive
translation of `WITHIN` would produce: written as ranges, both should drop to the few-millisecond range of the
`BETWEEN` row (**inference** for example 2, measured for example 1). The trigram MPN lookup at 0.5 ms makes part-number
fragments cheap. Note: the benchmark's extraction was a set of regexes over the LCSC description, not KINA's
extractor, so the NULL rates (74 % of rows without a value) and the selectivity differ from what KINA would store.

### 8.3 Write amplification and maintenance

Measured with batches of 50 rows (one multi-row `INSERT ... ON CONFLICT DO UPDATE` per transaction, which is the shape
of `upsertAll` for one Mouser page), 200 batches, on the 7.1 M-row table:

| Item | median ms | p95 ms | WAL per batch (median) |
|---|---|---|---|
| insert 50 rows, primary key only | 8.5 | 10.9 | 32 KB |
| insert 50 rows, all 13 indexes | 8.0 (run 2: 14.7) | 20.8 (run 2: 34.6) | 260 KB |
| update 50 rows, primary key only | 9.2 | 11.6 | 28 KB |
| update 50 rows, all 13 indexes | 6.6 (run 2: 14.8) | 10.3 (run 2: 29.7) | 153 KB |
| row unchanged (skipped by `IS DISTINCT FROM`) | not measured | | |
| index size per 1 000 rows (all indexes incl. PK, LCSC rows) | 263 KB | | |
| heap per 1 000 rows (LCSC rows) | 266 KB | | |

So the indexes cost about 8x the WAL and a heavier tail (p95 up to 35 ms vs 11 ms per 50-row batch), while the median
is dominated by the commit fsync. For the cache path (one batch per search, written after the answer is assembled)
that is a few milliseconds per search and under 100 ms at p99 even with 13 indexes. Bloat after 10 000 updated rows
was 0.14 % dead tuples; autovacuum did not even trigger. GIN indexes use the pending list (`fastupdate`), so inserts
stay cheap; default autovacuum settings are fine.

Disk (**inference**, scaled from the per-1 000 figures; Mouser and TME rows have longer descriptions than LCSC rows,
so allow up to 2x): at the current 6 660 parts the index table and all its indexes take about 4 to 8 MB; at 310 000
parts (TME filler) about 160 to 330 MB.

## 9. LCSC: Postgres or SQLite

### 9.1 What the data is

The JLCPCB file `parts-fts5.db` is 5.3 GB for 7.1 M rows; most of the size is the FTS5 trigram index
({{SQLITE_FTS_SHARE_PCT}} % measured). {{SQLITE_IN_STOCK_ROWS}} rows are in stock (the Postgres run's export counted
723 865 rows with stock above 0, about 10 %, by its own family split). Only in-stock rows can ever be
returned by a search (stock rule); out-of-stock rows matter only for a lookup by part number (`WHERE "LCSC Part" = ?`)
and for `out_of_stock_matches`. KINA never writes to this data; it is replaced as a whole every 5 days
(`refresh-after`).

### 9.2 The options

| | A. Today (SQLite FTS5) | B. SQLite + typed table in the same file | C. Everything in Postgres | D. Hybrid: SQLite raw, Postgres typed index |
|---|---|---|---|---|
| Field query | no (text + value boundaries, extractor on 200 rows) | yes, btree on SQLite | yes | yes |
| Text search | FTS5 trigram | FTS5 trigram (join by rowid) | pg_trgm GIN + tsvector GIN | FTS5 in SQLite, then join in Java |
| Extra disk | 0 | {{SQLITE_TYPED_TOTAL_MB}} MB (typed rows of in-stock parts + indexes) | 3.78 GB in Postgres for all 7.1 M rows (heap 1.81 GB, PK 320 MB, trigram GIN 253 MB description + 271 MB MPN, tsvector GIN 73 MB, jsonb GIN 37 MB, btrees 841 MB); 2.28x the 1.66 GB CSV; plus 3.5 GB transient during each reload | Postgres typed table of in-stock rows only, about 0.4 GB (**inference**: 10 % of the rows) |
| Refresh cost | download + validate + rename | + extraction {{EXTRACT_LCSC_FULL_S}} s + index build {{SQLITE_BUILD_S}} s, before the rename | + extraction ({{EXTRACT_LCSC_FULL_S}} s with KINA's extractor; 5.4 s with the benchmark's regexes) + COPY 17.5 to 27 s + PK 9 s + 12 indexes about 64 s + ANALYZE 0.3 s: 94 s measured end to end | + extraction + COPY of the typed rows |
| Atomic swap | yes (file rename under the write lock) | yes, the same rename | yes, rename swap in one transaction (9.3) | two stores to swap together: not atomic without extra work |
| Concurrency | one connection, serialised | a small pool with `immutable=1` | the Hikari pool (default 10) | both |
| Typical query, example 1 | {{SQLITE_FTS_Q1_MS}} ms | {{SQLITE_FIELD_Q1_MS}} ms | 3.8 ms with a `BETWEEN` value range (47.3 ms with `abs`) | two round trips |
| Backups | not in Postgres backups | not in Postgres backups | in every `pg_dump` unless excluded | partly |

### 9.3 Atomic refresh in Postgres (option C)

1. Load into a new table `lcsc_parts_next` (same DDL, generation-suffixed index names), with `COPY` from the extracted
   rows. Create the table `UNLOGGED`: the data can always be rebuilt from the SQLite file, so WAL is wasted on it. An
   unlogged table is emptied after a crash; KINA then rebuilds it in the background, and LCSC reports `unavailable`
   until it is done (as today before the first download). `ALTER TABLE ... SET LOGGED` would rewrite the whole table
   with WAL and is not needed.
2. Build the indexes after the load (much faster than loading into an indexed table), then `ANALYZE lcsc_parts_next`.
   Without `ANALYZE` the first queries after the swap are planned with default estimates.
3. Swap in one short transaction:

   ```sql
   SET lock_timeout = '2s';
   BEGIN;
   ALTER TABLE lcsc_parts RENAME TO lcsc_parts_old;
   ALTER TABLE lcsc_parts_next RENAME TO lcsc_parts;
   COMMIT;
   DROP TABLE lcsc_parts_old;
   ```

   DDL is transactional in Postgres, so readers see the old table or the new one, never neither. `ALTER TABLE` takes
   an ACCESS EXCLUSIVE lock: it waits for queries already running on `lcsc_parts`, and new queries queue behind it.
   `lock_timeout` keeps a long query from stalling every reader; on timeout the swap is retried a few seconds later.
   Measured (approach A below): the swap took 0.03 s; a concurrent reader running example 1 every 200 ms saw a
   longest read of 197 ms against a 30 to 40 ms baseline, which is host noise, not a lock wait, and no error.
4. Effect on the app: server-side prepared statements reference the old table's OID; Postgres invalidates cached plans
   on DDL and re-plans them on the next execution. **Verified**: the reader's server-side prepared statement switched
   to the new table (by `tableoid`) without error. The caveat is `SELECT *`: it fails with "cached plan must not change
   result type" when the columns change, so statements must name their columns, and a schema change ships with a new
   table definition on both sides of the swap. Hikari connections are unaffected. `DROP TABLE lcsc_parts_old` (0.6 s)
   also waits for its readers.

Measured reload approaches (7.1 M rows, 13 indexes, one run each on a loaded host):

| Approach | Wall time | Swap | Longest reader query | Reader errors | Peak extra disk |
|---|---|---|---|---|---|
| A. New table, COPY 17.5 s, PK 9.1 s, 12 indexes about 64 s, ANALYZE 0.3 s, rename swap, drop old | 94 s | 0.03 s | 197 ms | 0 | 3.5 GB |
| B. Partitioned by generation: build the new partition standalone with a CHECK, ATTACH (0.01 s), DETACH CONCURRENTLY (0.01 s), drop | 116 s | 0.02 s | 206 ms | 0 | 3.6 GB |
| B2. Same, `BEGIN; DETACH old; ATTACH new; COMMIT` | 129 s | 0.01 s | 246 ms | 0 | 3.6 GB |
| C. `BEGIN; TRUNCATE; COPY; COMMIT` with the indexes in place | 209 s | (whole load) | **209.7 s**, blocked for the whole load | 0 | 3.7 GB |

Choice: **A**. B works too, but the partition key must be part of the primary key (`(generation, distributor,
part_number)`), so uniqueness of a part number is no longer enforced, the partitioned parent is never analyzed by
autovacuum, and B (not B2) has a window of about 10 ms in which both generations are visible. C blocks every LCSC search
for 3.5 minutes: `TRUNCATE` takes ACCESS EXCLUSIVE at the start and holds it until commit; MVCC does not help because
readers wait for the lock. `DELETE` + `COPY` in one transaction (not measured) would keep readers going but doubles the
table, maintains every index row by row and leaves 7 M dead tuples.

### 9.4 Atomic refresh on SQLite (option B)

The downloader already validates the new file in `<dataDir>/tmp` and then moves it with `ATOMIC_MOVE`
(`JlcpcbDownloader.java` lines 76 to 113). Before that move, open the temporary file read-write, create
`part_fields` (the typed columns of 8.1 for the in-stock rows, keyed by the FTS rowid or `LCSC Part`), insert the
extracted rows in one transaction, create the indexes, run `ANALYZE`, close, then move. The swap stays one rename under
the write lock; readers never see a half-built table. When the extractor version changes between downloads, the same
build runs on a copy of the current file and is swapped the same way (risk R14). Cost: extraction
{{EXTRACT_LCSC_FULL_S}} s plus index build {{SQLITE_BUILD_S}} s, and the file grows by {{SQLITE_TYPED_TOTAL_MB}} MB.

Concurrency on SQLite: today one connection serves every LCSC query, so the read lock allows concurrent callers but
SQLite serialises them on the connection. A small pool (2 to 4) of read-only connections opened with `immutable=1`
removes the serialisation and SQLite's file locking; it is safe because the file is never modified while open (the
swap renames a new file into place, and an open connection keeps reading the old inode until it is closed). Measured:
{{SQLITE_POOL_QPS_1}} queries/s with one connection, {{SQLITE_POOL_QPS_4}} with four.

### 9.5 LCSC verdict

To be confirmed with the SQLite numbers. The Postgres run shows option C is technically sound: a 94 s reload with a
30 ms swap and no reader interruption, field queries of 4 to 80 ms with the right predicates, 3.78 GB of disk. So the
choice is about cost and simplicity, not feasibility. Expected: option B still wins. It keeps the refresh model that
already works (one atomic rename), keeps 3.78 GB (plus 3.5 GB transient every 5 days) out of Postgres and its backups,
and gives LCSC the same field query semantics. Option C wins if the measured SQLite field and text queries are clearly
slower than Postgres (full text 35 to 80 ms, trigram 37 ms, MPN fragment 0.5 ms in Postgres vs {{SQLITE_FTS_QK_MS}} ms
in SQLite) or if a single connection pool for every distributor matters more than the disk. If C is chosen, load only
the columns KINA reads, create the table `UNLOGGED`, use expression tsvector indexes (no heap cost) and the partial
in-stock index (stock never changes between reloads). Option D is rejected: two stores cannot be swapped together
atomically without a generation column in both.

## 10. Effort and rollout

| Phase | Content | Flag | Effort (inference) |
|---|---|---|---|
| 0. Shadow | `part_index` table (V14), writer in `upsertAll`/`upsertListed`, re-index job, `FieldQueryBuilder` from declarations. The field query runs next to today's path; only logs and metrics: overlap with the returned parts, parts SQL would have dropped that Java kept (must be 0), calls saved | `kina.search.field-index.mode=shadow` | 1.5 to 2 weeks |
| 1. Candidates added | Today's flow unchanged; on a HIT or PARTIAL, add the field candidates to the cached list before ranking (better recall, no new calls) | `mode=augment` | 3 to 4 days |
| 2. Field-first with journal | The flow of 5.2; `cached_searches` keyed by phrase; per-request call caps; new `cache` semantics and `fetched_live`; DESIGN 3.2, API.md and tool descriptions | `mode=on` | 1.5 to 2 weeks |
| 3. Validation and default | Eval (below), e2e suite, a week of production shadow numbers for Mouser call counts | default `on` | 3 to 5 days |
| 4. LCSC typed table | Option B: build in the downloader before the rename, read pool with `immutable=1`, field query for LCSC with the FTS5 text terms | `kina.jlcpcb.field-index.enabled` | 1 to 2 weeks |

Fallbacks: any SQL error or a disabled flag returns to today's path (the cache already never fails a search). An
empty or partly built index is detected by a row count per distributor and treated as "no index".

Tests:

- Testcontainers PostgreSQL tests for the writer, the re-index job (version bump), and the field query on recorded
  fixtures (`fixtures/leds`, `fixtures/switches` and the distributor JSON fixtures).
- **The superset invariant**: for every candidate of `docs/research/data/ranking-eval.jsonl` (41 queries with
  labelled candidates) and every fixture part, `PageCollector.Check.returnable` true implies the SQL predicate true.
  This is the test that guards R1. It runs the parsed query of each eval line against a `part_index` built from the
  labelled parts.
- Recall on the eval set: every candidate labelled 2 or 3 that the Java check keeps must come back from the field
  query; NDCG@10 of the blended ranking over field candidates must stay at or above 0.90 (the current gate of
  `CrossEncoderEvaluationTest`).
- `ConstraintGoldenTest` and `ExtractionGoldenTest` must not change (the field index does not change scoring or
  extraction).
- A declaration test: every kind that is `NEVER` for some family or `BELOW_SPEC` has an index rule or an explicit
  "Java only" mark, and the DESIGN table of index rules matches.
- E2E: `scripts/e2e/kina_e2e.py` on the compose stack in each mode; compare `cache`, counts and returned parts.

## 11. What could not be verified

- Production Mouser and TME call counts per request and the share of reworded or rating-only repeat queries, so the
  quota effect of the journal (section 5.3) is not known. Phase 0 measures it.
- How often a field hit would hide better distributor parts (R2). Only a shadow comparison against live results can
  measure it.
- The treatment of non-ASCII characters (`℃`, `Ω`, CJK) by `pg_trgm` under the musl locale of `postgres:17-alpine`
  (trigram extraction depends on the locale's notion of alphanumeric characters). The extension itself is available.
- Postgres query latency on a cold cache and on a smaller production host: the benchmark host was never cold (3.8 GB
  fit in the page cache) and was under memory pressure; first-hit reads will cost more in production.
- The USB example (example 3) and the field query with KINA's real extractor: the Postgres benchmark used regex
  extraction and had no connector columns.
- The technology compatibility set used in example 2 (`thin film` with `metal film`) against
  `TechnologyVocabulary.compare`.
- Whether `UsbVocabulary` exposes a numeric rank for standards suitable for `usb_class >= n`; the `compare` method
  exists, a rank may need adding.
- Server-side prepared statements across the rename swap were verified with psycopg (Python), not with PgJDBC and
  Hikari; the mechanism (plan invalidation in the server) is the same, but the JDBC path is untested.
- The cross-encoder effect of a larger candidate pool (section 6).
- The TME category filler of the 2026-10-07 study does not exist yet; the 310 000-part figures are projections.

## 12. Measured numbers

This section is filled from the three measurement runs.

### 12.1 Postgres on the JLCPCB data (7.1 M rows)

Source: the Postgres benchmark run of 2026-10-08 (scripts, plans and logs in `/var/tmp/kina-bench/pg/`, outside the
repository). Postgres 17.11 (`postgres:17-alpine`) in a container on a 24-core, 30 GB host with NVMe; the host was
under memory pressure (22 to 24 GB of swap in use) and ran the SQLite benchmark at the same time, so timings within 2x
are equal. Settings for queries and writes: `shared_buffers=2GB`, `work_mem=128MB`, `maintenance_work_mem=1GB`. The
benchmark table is a simplified `part_index` (family, generic `value_num`, `voltage_v`, `tolerance_pct`, `power_w`,
package, dielectric, mounting, technology, stock, description, MPN, jsonb attributes) filled by regexes over the
JLCPCB descriptions, not by KINA's extractor.

| Measure | Value |
|---|---|
| Rows | 7 146 764 (723 865 with stock above 0) |
| Export SQLite to CSV (12 processes, regex extraction) | 5.4 s, 1.66 GB CSV |
| COPY with the primary key in place | 27.1 s (264 000 rows/s) |
| Heap / PK after load | 1.81 GB / 320 MB |
| Index builds (one at a time) | partial btrees 0.2 to 0.26 s; full btrees 0.7 to 1.95 s (153 to 254 MB each); jsonb GIN 2.3 s (37 MB); tsvector expression GIN 14.6 s (73 MB); trigram GIN description 21.6 s (253 MB), MPN 15.9 s (271 MB); ANALYZE 0.3 s |
| Stored tsvector column instead of an expression index | +524 MB heap, GIN build 5.5 s (2.7x faster), same query speed |
| Total size | 3.78 GB (heap 1.90 GB, indexes 1.88 GB including PK) = 2.28x the CSV |
| Query, example 1 with `BETWEEN` value range | 3.8 ms warm (partial in-stock btree) |
| Query, example 1 with `abs(value - x) <= y` | 47.3 ms warm (88 434 heap rows filtered) |
| Other queries (full text, trigram, jsonb, counts) | 0.5 to 123 ms warm, no sequential scan |
| `IS NULL OR` ratings | applied as filters, no extra cost (49.0 vs 47.3 ms) |
| Upsert, 50-row batches, p95 | 13 indexes: insert 20.8 ms, update 10.3 ms (busier run 34.6 / 29.7); PK only 10.9 / 11.6 ms |
| WAL per 50-row batch (median) | 13 indexes: 260 KB insert, 153 KB update; PK only: 32 KB, 28 KB |
| Bloat after the update run | 0.14 % dead tuples; autovacuum not triggered; HOT updates 0 with the stock partial index |
| Reload A (new table, rename swap) | 94 s, swap 0.03 s, longest reader query 197 ms, 0 errors, 3.5 GB transient disk |
| Reload B / B2 (partition by generation) | 116 s / 129 s, longest reader query 206 / 246 ms, 0 errors |
| Reload C (TRUNCATE + COPY in one transaction) | 209 s, reader blocked 209.7 s |

### 12.2 SQLite on the JLCPCB data

{{SQLITE_RESULTS_TABLE}}

### 12.3 Extractor coverage (JLCPCB, cached Mouser and TME)

{{EXTRACT_COVERAGE_TABLE}}

## 13. Code locations relied on

- `src/main/java/ro/alacrity/kina/search/CachedDistributorRetriever.java`: hit, partial, miss, safeguard, ladder,
  `isSufficient` (line 274), `store`, `servedStale`.
- `src/main/java/ro/alacrity/kina/search/DistributorRetriever.java`: `usesPostgresCache`, `window`, `maxPages`, `plan`.
- `src/main/java/ro/alacrity/kina/search/LcscRetriever.java`: one live query per LCSC search.
- `src/main/java/ro/alacrity/kina/search/PageCollector.java`: `Check.meets`, `confirmed`, `returnable` (lines 57 to
  90), the "at least one confirmed" paging rule (line 130).
- `src/main/java/ro/alacrity/kina/search/StockRefresher.java`: `STOCK_REFRESH_ROUNDS` (line 38).
- `src/main/java/ro/alacrity/kina/search/DistributorPhraser.java`: `ladder` (line 142).
- `src/main/java/ro/alacrity/kina/search/ParametricExtractor.java`: `Features` (line 220), `extract`,
  `DERIVED_ONLY_KEYS` (line 178).
- `src/main/java/ro/alacrity/kina/search/ConstraintPolicy.java`: `strategy`, `isRelaxable`, `statedHard`.
- `src/main/java/ro/alacrity/kina/search/DeterministicRanker.java`: `tieBreak` (line 318).
- `src/main/java/ro/alacrity/kina/search/Recognizers.java`: `samePackage`, `packageKey` (line 572).
- `src/main/java/ro/alacrity/kina/domain/ConstraintKind.java`: `@Relax`/`@Match` declarations (lines 76 to 1000),
  `sameValue` (line 1692).
- `src/main/java/ro/alacrity/kina/domain/PartAttribute.java`: `@Source`/`@Unit` per attribute.
- `src/main/java/ro/alacrity/kina/domain/Part.java`: `asStored`, `derivedAttributes`.
- `src/main/java/ro/alacrity/kina/cache/PartCacheRepository.java`: `UPSERT` (line 48), `UPDATE_STOCK` (line 67),
  `retype` (line 157).
- `src/main/java/ro/alacrity/kina/distributor/lcsc/JlcpcbSqliteSearch.java`: lock (line 122), `replaceDatabase`
  (line 170), `search` and `relax` (lines 204 to 290), `openReadOnly`.
- `src/main/java/ro/alacrity/kina/distributor/lcsc/JlcpcbDownloader.java`: validation and `ATOMIC_MOVE` (line 110).
- `src/main/resources/application.yml`: `candidate-window: 40`, `max-candidates: 40`, Mouser 50 per call and 1 page,
  TME 60 and 3 pages.
- `docs/DESIGN.md` sections 2, 3.2, 3.4, 8, 9.3; `docs/research/cache-fill-2026-10-07.md` (Mouser attributes, quota,
  TME filler); `docs/research/ranking-evaluation-2026-10-05.md` (ranker latency).
