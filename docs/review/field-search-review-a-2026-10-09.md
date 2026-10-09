# Field search review A: declarative model and index core (2026-10-09)

Scope: `Indexed`, the `@Indexed` declarations, `FieldQueryBuilder`, `FieldQuery`, `FieldPredicate`, `FieldSql`,
`PostgresFieldSql`, `SqliteFieldSql`, `SqlitePartIndex`, `PartIndexSql`, `PartIndexRepository`, `PartIndexRows`,
`PartIndexReindexer`, `FieldIndexStatus`, `FieldSearchShadow`, V14, V15 and the five named tests. Commits
`a2f188f..4839fa6`. Line numbers refer to the tree at `4839fa6`.

## Summary against the rubric

- Declarative: partly. The numbers (slack, margin, column, type, predicate class) are on the model and the DESIGN 3.8
  table is rendered from them and checked. The behaviour is not: `FieldQueryBuilder.predicates` is a 25-case
  `switch (kind)` that ignores `@Indexed.predicate` for most kinds, so the annotation names a predicate the code does
  not read. The DDL (V14), `PartIndexSql.columns()` and the SQLite DDL are three hand-written copies of the column
  list; only the value columns come from the annotations, and no test compares them.
- Generic: the predicate model (`FieldPredicate`) is shared and both dialects render it, which is good. Duplication
  remains inside `SqliteFieldSql` (the CONFIRMED renderer repeats `PackageIs`, `AnyInRange`, `Range`, `AtLeast`), and
  `FieldSql.body` special-cases `ConstraintKind.TYPE`. `PartIndexRows` is the single place that turns a `Part` into
  values for both databases; this part is clean.
- Hacks: no string-built SQL without parameters (names go through `identifier`), no `Thread.sleep`, no static mutable
  state. Magic numbers: `EPSILON = 1e-12` (an absolute epsilon on values that reach 1e-12), write chunk 500 and the
  coverage TTL 30 s are code constants. Swallowed exceptions are logged and intentional (isolation of the cache write).
- Correctness: one production bug (stock refresh makes every refreshed row stale, no periodic re-index; known, hotfix
  separate), one race in the re-index and a few small items.
- Tests: the superset test is strong. The documentation test only checks the declarations against DESIGN, not the
  declarations against the DDL or the builder.
- Perf: `isComplete` recomputes a full scan of `cached_parts` with `md5(payload::text)` whenever a write has cleared
  the snapshot, so on a busy cache it scans per search. The partial value indexes of V14 are probably not usable by the
  `col IS NULL OR col BETWEEN` form (unverified, needs an EXPLAIN test).

## Findings

| # | Sev | Cat | Where | Defect | Proposed fix | complex |
|---|-----|-----|-------|--------|--------------|---------|
| 1 | high | correctness | `cache/PartCacheRepository.java:248-270` (`updateStock`), `PartIndexRepository.java:100,113-127,269-290` | A stock refresh rewrites `cached_parts.payload` but the listener only flips `in_stock`; `payload_md5` no longer matches, so every refreshed row is not current, `isComplete` turns false and field-first / index reads stay off for that distributor. Nothing re-indexes after startup (`PartIndexReindexer` runs once at `ApplicationReadyEvent`), so it lasts until a restart. Known (observed in production, hotfix separate). | Either run `upserting` for the refreshed parts in the same transaction as the stock update (rewrites the row and md5), or define "current" on the metadata only (hash of the payload without the stock and price fields, computed by the writer from the `Part`, never `md5(payload::text)`). Also schedule the re-index (periodic, or when coverage is incomplete) so any stale row heals without a restart. | yes |
| 2 | high | perf | `PartIndexRepository.java:176,245,269-307` | `write` and `reindexStale` clear the coverage snapshot, and `coverage()` is a join of the whole `cached_parts` with `md5(c.payload::text)` over every row. With constant cache writes `isComplete` (called per search by `FieldFirstSearch:138`, `CachedDistributorRetriever:154`, the shadow) recomputes it each time instead of every 30 s; at 300 000 parts (TME filler) that is seconds of CPU and a table scan per request. | Do not invalidate on every write (the TTL is enough, or maintain counts incrementally); compute "stale" from a stored hash/version on the index row and a cheap generation counter, never md5 of all payloads on the read path. | yes |
| 3 | medium | correctness | `PartIndexSql.java:50-52`, `PartIndexRepository.java:197-237` | The row is built from the payload text read by the batch SELECT, but `payload_md5` is computed from `cached_parts` at INSERT time (`md5(c.payload::text)` in the SELECT). If the payload changes between the two (a live upsert during the re-index), the row holds old features with the new md5 and is considered current forever. | Select `md5(c.payload::text)` together with the payload and make the upsert conditional on it (`WHERE md5(c.payload::text) = ?`), or store the md5 read with the payload. | no |
| 4 | medium | declarative | `FieldQueryBuilder.java:166-274` | `predicates` switches on 25 specific kinds; `@Indexed.predicate` is read only in `generic()`. For `IN_COMPATIBLE`, `ARRAY_ANY`, `PACKAGE`, `ABSENT`, `JSONB_CONTAINS` the annotation is documentation, and a mismatch (e.g. `TECHNOLOGY` changed to `EQUAL`) changes nothing. The vocabulary and comparator of each refusing kind (`FieldVocabulary.technologies()` + `compareTechnology`, `ledTypes()` + `typeGrade`, ...) and the wanted-value extraction are code, not declarations. | Declare what is missing on the model: the vocabulary and the refusal test for `IN_COMPATIBLE` kinds (a small interface on the constant, like the `@Match` custom comparators already used by `compare`). Dispatch on `rule.predicate()` only, and add a test that every predicate class is rendered from its declaration. | yes |
| 5 | medium | declarative | `V14__part_index.sql`, `PartIndexSql.java:94-130`, `SqlitePartIndex.java:339-365` | The column list lives in three places. `PartIndexSql.columns()` hand-lists ~35 columns with SQL casts (`"float8"`, `"smallint"`) and a getter each, repeated by `PartIndexRow` and `PartIndexRows.of`; the SQLite DDL is derived from `casts()` but its indexes are a hand-written subset that differs from V14. Types in `@Indexed(type=...)` are not used to produce a cast (they only feed the documentation table), so changing a type in an annotation changes nothing. | Derive casts and the typed columns of `PartIndexSql` from the `@Indexed` annotations, keep a hand list only for the structural columns, and add a test comparing `information_schema.columns` (name and type) with `PartIndexSql.casts()` in both directions. | yes (the test alone: no) |
| 6 | medium | test | `FieldIndexContinuityTest.java:148-161` (`everyDeclaredColumnExists`) | The only schema check is one-directional (declared columns are a subset of the table) and checks names only, not types, nor `PartIndexSql.COLUMNS` against the table. A column the writer writes that V14 lacks is only found at runtime through a swallowed failure (`writeIsolated` logs and continues). | See 5: compare both directions, with types, and compare `PartIndexSql.COLUMNS` to the table. | no |
| 7 | medium | correctness | `FieldQueryBuilder.java:48,353-359` | `EPSILON = 1e-12` is an absolute widening in the column's unit. For capacitance (1 pF = 1e-12 F) it widens the range by 100 % of a 1 pF part and 10 % of 10 pF; for inductance in H, 1 nH gives 0.1 %. It is correct for the superset, but selectivity depends on the unit and the value is not on the model. | Use a relative epsilon derived from the 9 significant digits the writer stores (`FieldVocabulary.SIGNIFICANT_DIGITS`): `value * 1e-8`, applied to the bound. | no |
| 8 | medium | perf | `V14__part_index.sql:70-85`, `FieldSql.java:218-226` | Every value predicate renders as `(col IS NULL OR col BETWEEN ? AND ?)`. The five partial value indexes (`... WHERE capacitance_f IS NOT NULL`) cannot serve an OR with `IS NULL`, and the `attrs` GIN (`jsonb_path_ops`) cannot serve `(attrs->>'k') IS NULL OR attrs @> ...`. The study (4.3) relied on a pure `BETWEEN` index condition. Probably only `(distributor, family)` is used and the value filter runs on the family's rows. Unverified: no test checks a plan. | Add an EXPLAIN-based test for the eval queries (the study proposed one); if confirmed, render the stated branch as a separate scan unioned with the NULL rows, or index without the partial predicate. | yes |
| 9 | medium | generic | `SqliteFieldSql.java:126-181` | The CONFIRMED renderer copies the `Range`, `AtLeast`, `AtMost`, `Equal`, `OneOf`, `NoneOf`, `AnyInRange` and `PackageIs` rendering of `FieldSql.render` with the NULL branch removed (about 55 lines, `PackageIs` duplicated including the can margin math). A change to a predicate must be made twice and the superset test does not cover the CONFIRMED form. | Give `FieldSql.render` a `nullBranch(column)` hook (returns `""` or `"col IS NULL OR "`) so the stated-only form is built in one place, and extend the superset test to assert CONFIRMED hits are a subset of INSTANCE hits. | no |
| 10 | low | generic | `FieldSql.java:200-215` | `body` decides "holds for every row" by `p.kind() == ConstraintKind.TYPE`, a special case on one kind in the generic renderer (and `familyStated` the same). | Mark it on the predicate or group role instead of comparing with a constant. | no |
| 11 | low | declarative | `FieldQueryBuilder.java:318-326`, `ConstraintKind.java:220` | `PACKAGE` declares `margin = 0.25` but the builder uses `Math.max(rule.margin(), FieldVocabulary.canTolerance())`, so the declared value is a floor and the real tolerance is `Recognizers.CAN_TOLERANCE_MM`; the DESIGN table prints a margin that is not the one applied. | Declare the margin as the can tolerance (or reference the constant) and drop the `max`. | no |
| 12 | low | declarative | `Indexed.java` (`ColumnType.TEXT_ARRAY`, `ColumnType.JSONB`, `Predicate.TEXT`, `nullKept`) | Dead declarations: no annotation uses `TEXT_ARRAY`, `JSONB`, `Predicate.TEXT`; `nullKept` is always true and only validated. | Remove them (and the `nullKept` checks in `ConstraintKind:1473` and the documentation test). | no |
| 13 | low | hack | `PartIndexRepository.java:45,173` | Magic constants: write chunk 500 in `write` (config has a separate `reindex-batch-size: 500`) and coverage TTL 30 000 ms in code. | Put them in `KinaProperties.FieldIndex`. | no |
| 14 | low | correctness | `PartIndexRepository.java:296-307` | `isComplete` returns false on any `RuntimeException` and logs at DEBUG, so a broken index silently disables the feature with no WARN or metric. | Log WARN once per failure streak and count it. | no |
| 15 | low | correctness | `PartIndexRepository.java:129-140`, `PartCacheRepository.java:285-300` | A part the extractor fails on is skipped (WARN); the cache row exists without an index row, the distributor is incomplete (see 1) until a restart. | Write a placeholder row (as `reindexStale` does) or re-index on the next write; covered by a periodic re-index (fix of 1). | no |
| 16 | low | correctness | `PartCacheRepository.java:270,282` (`updateStock`, `markSoldOut`) | The payload write and the listener's `stockChanged` run in separate transactions (the upsert path wraps both in `inTransaction`); a crash between them leaves `part_index.in_stock` wrong. | Wrap both in `inTransaction` as `upsertAll` does. | no |
| 17 | low | test | `IndexVersionTest.java:96-123` | `row(...)` lists the index row fields by hand for the fingerprint; a field added to `PartIndexRow` is not hashed, so changing its extraction needs no bump. | Hash the record generically (sorted-key JSON of the row). | no |
| 18 | low | test | `IndexTableDocumentationTest.java:112-113` (fixed) | A no-op assertion (`PolicyFamily.values()` not empty) and two unused imports. | Removed. | no |
| 19 | low | generic | `PartIndexRepository.java:105-110` (`upsertFrom`) | Public method used only by `FieldIndexContinuityTest`. | Move it to the test or document it as a test seam. | no |
| 20 | low | hack | `FieldSearchShadow.java:78` | A virtual thread per observed search, unbounded (shadow mode only). | Use a bounded executor or sample. | no |

## Applied fixes (behaviour-preserving, targeted tests green)

- `ConstraintKind`: 15 copies of `slack = 1e-6` replaced by `Indexed.RATING_SLACK` (value unchanged; the DESIGN table
  renders the same number).
- `IndexTableDocumentationTest`: removed the no-op assertion and the two unused imports (finding 18).
- `PartIndexRepository`, `SqliteFieldSql`: fully qualified `java.util.*` names replaced by imports.

## Items marked complex

1. Finding 1 (stock refresh invalidates `payload_md5`; no re-index after startup). Known, hotfix separate; the design
   question is what "current" means (payload hash, metadata hash, or version only).
2. Finding 2 (coverage recomputed per search, md5 of every payload). Tied to 1: fix them together.
3. Finding 4 (the builder reads `@Indexed.predicate` only for the generic kinds; move vocabularies and comparators
   onto the model).
4. Finding 5 (generate the column list, casts and DDL from the annotations, or at least verify them by test).
5. Finding 8 (the value indexes may not be used by the `IS NULL OR BETWEEN` form; needs an EXPLAIN check first).
