# Documentation parity review: field-based search, LCSC typed table, quota tracking (v0.15)

Scope: commits `a2f188f..4839fa6` on `main`. The code is the reference. Where the code is the intended behaviour, the
document was fixed. Where the code looks wrong against the documented intent, it is listed as a code finding and was
not edited. The documentation tests (`./mvnw -q test -Dtest='*DocumentationTest'`: `MetricDocumentationTest`,
`ConstraintTableDocumentationTest`, `ExtractionTableDocumentationTest`, `IndexTableDocumentationTest`) pass after the
fixes (8 tests).

## Counts

- Areas checked: 9 (configuration, response fields, semantics, metrics, schema, LCSC, quoted numbers, CLAUDE.md,
  validation stack).
- Documentation mismatches found: 7, all fixed.
- Code findings: 4, for the maintainers.
- Notes without a change: 3.

## Documentation mismatches (fixed in the document)

| # | Document | Code or source | Mismatch | Resolution |
|---|---|---|---|---|
| 1 | `docs/DESIGN.md` 9.3 (build paragraph) | `docs/research/field-search-validation-2026-10-09.md` section 3 (402 MB) | DESIGN said the sidecar is 387 MB. README, OPERATIONS and CONFIGURATION said 402 MB. No research document holds 387 MB. | Fixed in DESIGN: 402 MB. |
| 2 | `docs/DESIGN.md` 9.3 (same paragraph) | validation section 3: 52.5 s extract and insert, 2.9 s indexes and `ANALYZE`, 5.9 s warm-up | DESIGN said 42 to 57 s, 1.1 to 1.4 s and 1 s. CONFIGURATION already quotes 52 s, 3 s and 6 s. | Fixed in DESIGN to the validation figures. |
| 3 | `docs/OPERATIONS.md` (upgrade note for V14 and V15) | validation 8.2 (phase C2): re-index 8.1 s, journal backfill 498 ms | OPERATIONS quoted the phase C figures (9.5 s and 0.7 s). | Fixed: 8.1 s and 0.5 s. |
| 4 | `docs/API.md` `hint` row | `search/ResponseAssembler.java` lines 85 to 95 and 134 to 141: `unconfirmedHint` is built on every path | The text said the unconfirmed-parts hint is present "with the field index". | Fixed: "on every path". DESIGN 3.2 already said this. |
| 5 | `docs/DEVELOPMENT.md` field search validation stack | `scripts/e2e/field-search/compose.override.yaml` | `KINA_JLCPCB_AUTODOWNLOAD` is declared in neither `application.yml` nor `.env.example`. It works through Spring relaxed binding. This was not said. | Fixed: one clause explains it. |
| 6 | `docs/DEVELOPMENT.md` package table (`distributor`) | `distributor/ApiQuotaTracker.java`, `distributor/lcsc/*` | The table omitted `ApiQuotaTracker`, the connection pool and the typed-table classes. CLAUDE.md lists them. | Fixed. |
| 7 | `docs/DEVELOPMENT.md` package table (`search`, `search.field`, `cache`) | `FieldFirstSearch`, `PhraseJournalBackfill`, `FieldIndexStatus`, `PhraseJournalRepository` exist | The table omitted these four classes. | Fixed. |

## Code findings (not edited)

| # | Code | Documented intent | Finding |
|---|---|---|---|
| C1 | `metrics/Metric.java` lines 74 to 76 (`FIELD_FALLBACKS` help text) | DESIGN 3.7 and OPERATIONS list five reasons: `mode`, `bypass`, `incomplete`, `generic`, `sql_error` | The help text lists four and omits `generic`, which `FieldFirstSearch.java:149` emits. `MetricDocumentationTest` does not compare help text, so it passes. The Prometheus `# HELP` line is incomplete. |
| C2 | `mcp/KinaMcpTools.java` `list_distributors` description (about line 199) | DESIGN 4 and API.md: the payload has `field_index` and `jlcpcb.field_index` | The description names quota, cache, ranking and counters but not the field index or the typed table state. Optional. |
| C3 | `search/FieldFirstSearch.java` `assemble` (line 604) | DESIGN 3.2, API.md and README: `stale` means the live call failed and the parts come from the index or an expired list | `stale` is set only when no call succeeded (`calls == 0` and an error). When an earlier call of the same request succeeded and a later one failed, the entry is `miss` or `partial` with an `error`. The docs do not describe that case. Either document it or return `stale`. |
| C4 | `search_parts` description (`KinaMcpTools.java` lines 66 to 67) | API.md lists `fetched_live`, `live_calls`, `field_steps_tried` | The description names only "live_calls 0". Acceptable under the size limit; listed so the choice is deliberate. |

## Notes without a change

- `docs/DESIGN.md` 9.3 quotes pool throughput (7.8, 15.8, 28.1 and 53.8 searches/s; typed 88, 183, 395 and 750) and
  typed query latencies (53 ms, 2.4 ms, 12 ms, 2.8 ms). No research or validation document holds these figures.
  CONFIGURATION repeats 28 against 7.8. I could not trace them, so they are unchanged. The maintainers should confirm
  the source.
- `docs/DEVELOPMENT.md` uses port 18080 for `scripts/e2e/prod_smoke.sh` and for the `kina-fs` stack. They cannot run at
  the same time. Each document states its port correctly.
- `docs/research/field-search-validation-2026-10-09.md` section 6 still shows `validate lookups --mcp-fallback`, and
  section 8 says the REST path now accepts those part numbers. It is a dated report and is left as written.

## What was checked and matches

1. Configuration. Every key under `kina.search.field-index.*`, `kina.jlcpcb.field-index.*`, `kina.jlcpcb.pool-*` and
   `kina.distributors.*.quota.*` in `KinaProperties`, `application.yml`, `.env.example`, DESIGN 10, CONFIGURATION and
   README agrees on name, environment variable and default (`off`, 0, 100, 2, 500, true, true; `false`, 0; 4 and 10s;
   30 and 1000 for Mouser, 30 and 2000 for TME).
2. Response fields. `DistributorResult` (with `fetched_live`, `live_calls`, `field_steps_tried`),
   `DistributorStatusResponse` (`quota`, `field_index` with `journal_rows`, `jlcpcb.field_index`) and the metrics
   summary (`distributor_quota`) match DESIGN 4, DESIGN 3.2 and API.md field by field, including the omit rules.
3. Semantics. The `cache` values in mode `on` (`hit`, `miss`, `partial`, `stale`), the `fetched` and `total_results`
   rules, the `enough` rule, the journal freshness, the fallback reasons and the unconfirmed hint wording match the
   docs (see C3 for one edge).
4. Metrics. All series and labels of `Metric.java` appear in DESIGN 3.7. OPERATIONS and README name the same quota and
   field series with the same labels.
5. Schema. V14 (`part_index`: 50 columns, 14 indexes, the `pg_trgm` extension) and V15 (`distributor_phrases`: 11
   columns, one index) match DESIGN 8 column by column and index by index. The SQLite index set matches DESIGN 9.3.
6. LCSC. Sidecar name, `kina_meta` keys (`index_version`, `rows`, `built_at`, `source`), the fingerprint (size plus
   the `meta` row), the version and source check, the rename order (main file first), the pool, the fetch of twice
   the window and the fallback rules match the code.
7. Numbers. 6 657 of 6 657 lookups, 85.9 %, 66 ms and 198 ms, 120 to 370 ms, 724 000 rows, 402 MB and "about a
   minute" match the phase C2 section of the validation report.
8. CLAUDE.md. Every class named in the module map and the conventions exists. The conventions hold in code:
   `RateLimitRetry` is the only caller of `ApiQuotaTracker.record`, the sidecar is renamed after the main file, and the
   migrations V14 and V15 only add objects.
9. Validation stack. Commands, ports (18080 and 19090), project name, volumes and environment names in DEVELOPMENT.md
   match `scripts/e2e/field-search/run.sh`, `compose.override.yaml` and `validate.py`.

No em-dashes were found in the Markdown files touched by the v0.15 commits.
