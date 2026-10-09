# KINA LCSC source: SQLite FTS5 baseline, typed field index and atomic refresh benchmark

Run on 2026-10-08, files under `/var/tmp/kina-bench/sqlite/`. Source: `/var/tmp/kina-bench/parts-fts5.db`, opened read-only (`mode=ro`), never copied or modified. All raw results are in `res_*.json` next to this file, scripts in `py/` (also reproduced in the appendix).

## 1. Environment

| Item | Value |
|---|---|
| CPU / cores | Intel Core Ultra 9 285K, 24 cores |
| Python / SQLite | Python 3.14.7 (GIL enabled), SQLite 3.53.4 (`sqlite3` module; FTS5, trigram) |
| Filesystem | btrfs on NVMe (`/dev/nvme0n1p2`), `/var/tmp` 235 GB, about 24 to 28 GB free during the run (a Postgres benchmark ran concurrently) |
| RAM at start | 31.4 GB total, 17.5 GB used, 2.3 GB free, 14.9 GB buff/cache, swap 22.9 GB of 31.4 GB used (memory pressure) |
| RAM at end | 7.3 GB free, 8.5 GB buff/cache, swap 24.9 GB used |
| Source DB | 5 329 715 200 bytes, 7 146 764 rows, 723 865 rows with stock > 0 (10.1 %) |
| My footprint | **The 4 GB limit was exceeded once, for a few minutes**: during test (C) in WAL mode part_index.db (2.04 GB) + its WAL (2.05 GB) + the 0.35 GB new_pi.db source reached about 4.4 GB. Free space on `/var/tmp` never fell below 22 GB, so the Postgres benchmark was not affected. All other steps stayed under 3.4 GB. Subset, dummy and new_pi files are removed. Kept: `part_index.db`, 981 909 504 bytes (937 MiB), in WAL mode, plus small result files and `py/`. |

Caveats on timing: the page cache was not dropped (no root). For "cold" runs I evicted the source file with `posix_fadvise(DONTNEED)`, which works for clean pages without root and gave clearly cold timings (for example Q1 0.35 s cold vs 0.023 s warm). "Warm" means the median of 5 (or 7) repeated runs. Other processes, swap and a concurrent Postgres benchmark add noise. The `kina_value` SQL function is a Python port of `JlcpcbSqliteSearch.containsValue` (Python UDF, called under the GIL); KINA registers a Java function through sqlite-jdbc.

## 2. Baseline: current FTS5 approach (read-only source)

Query shapes are copied from `JlcpcbQuery` / `JlcpcbSqliteSearch`: `parts MATCH '("t1") AND ("t2") ...'`, `kina_value("Description", ?)` for value terms, `CAST("Stock" AS INTEGER) > 0`, `ORDER BY rank, CAST("Stock" AS INTEGER) DESC LIMIT 200`, and a separate `count(*)` with the same predicate. A "search" is count plus page, as in `JlcpcbSqliteSearch.search`. `4.7k` is stored as `4.7kΩ`; the term `4.7k` matches it as a substring (plus the boundary function).

| Query | In-stock total | Page rows | count: cold / warm median | page: cold / warm median | Search (count + page) cold | Search warm median |
|---|---:|---:|---|---|---:|---:|
| Q1 `10uF X7R 0805` | 73 | 73 | 252 ms / 3.1 ms | 102 ms / 19.9 ms | 354 ms | **23.1 ms** |
| Q2 `4.7k 0603` | 200 | 200 | 464 ms / 5.1 ms | 62 ms / 20.4 ms | 525 ms | 25.4 ms |
| Q3 `"Female Header" 1x6P "Right Angle"` | 35 | 35 | 871 ms / 9.6 ms | 183 ms / 53.2 ms | 1 055 ms | 62.7 ms |
| Q4 `"Thin Film" 5.36k 0805` | 6 | 6 | 989 ms / 10.3 ms | 206 ms / 74.8 ms | 1 195 ms | 85.1 ms |
| Q5 `1k` (single 2-char token, LIKE path, full scan) | 24 443 | 200 | 19.9 s / 1.50 s | 4.9 s / 1.14 s | 24.8 s | 2.65 s |
| Q6 OR fallback, 6 terms `100nF` OR `X7R` OR `0402` OR `16V` OR `capacitor` OR `Murata` | 95 874 | 200 | 20.5 s / 0.63 s | 8.3 s / 0.85 s | 28.8 s | 1.48 s |
| Relaxation flow (ALL = 0, three drops): `10uF X7R 0805 1% 100V` drops X7R, 1%, 0805, then finds 149 rows with `10uF 100V` (4 counts + page) | 149 | 149 | | | 780 ms | 41.3 ms |

Notes: Q2's total of 200 is the true `count(*)` (checked with a page limit of 10 000: 200 rows), not the LIMIT. Q1 to Q4 warm times are dominated by the page query (BM25 `rank` ordering over the matches plus the Python UDF), not by the count. Q5 and Q6 show the two expensive shapes: a LIKE-only predicate scans every row, and an OR over common trigrams ranks about 100 000 matches. The out-of-stock count (`countIgnoringStock`) is not included in the table; it only runs when ALL finds nothing in stock.

### Concurrency (8 distinct searches x 20 rounds = 160 searches, warm, median of 3 runs)

Workload: Q1 to Q4 plus `100nF X7R 0402`, `10kΩ 0805`, `"Pin Header" 2x10P "Right Angle"`, `Schottky SOD-123`. Each worker runs count + page.

| Mode | Wall clock | Throughput | Relative to 1 shared connection behind a lock |
|---|---:|---:|---:|
| Sequential, one connection (reference) | 7.06 s | 22.7 searches/s | 1.12x |
| 8 threads, **one shared connection behind a lock** (KINA today) | 7.94 s | **20.1 searches/s** | 1.00x |
| 8 threads, 8 read-only connections (Python threads, GIL limited by the Python UDF) | 4.50 s | **35.6 searches/s** | 1.77x |
| 8 processes, one read-only connection each (no GIL) | 1.81 s | **88.2 searches/s** | 4.4x |

The 8 processes number is the better estimate for a JVM, where UDF calls do not share a GIL. A shared connection with no lock at all could not be measured: it deadlocks in Python (a thread inside `sqlite3_step` holds the connection mutex and its UDF call waits for the GIL held by another thread blocked on that mutex). KINA's `ReentrantReadWriteLock` read lock lets threads in together, but with sqlite-jdbc's serialized connection the engine still runs one statement step at a time, so I expect KINA to behave like the lock row (inference, not measured in Java).

## 3. Typed field index (`part_index.db`)

Schema: `part_index(rowid INTEGER PRIMARY KEY, lcsc, family, value_num REAL, voltage_v, tolerance_pct, power_w, package, dielectric, mounting, technology, stock_int)`, where `rowid` equals the `parts` rowid. Extraction is by simple regexes (Python, appendix); fidelity is not the point.

### Extraction scan

| Item | Value |
|---|---|
| Rows | 7 146 764 |
| Total time | 132.4 s (about 54 000 rows/s) |
| of which read FTS table + regex extraction | 112.6 s |
| of which batched inserts (100 000 rows per transaction, 72 batches) | 19.8 s |
| Table-only file size | 346 103 808 bytes (330 MiB, about 48 bytes/row) |

### NULL rates and cardinality (all rows)

| Column | NULL % | Distinct |
|---|---:|---:|
| lcsc | 0.0 | 7 146 764 |
| family | 25.4 | 9 |
| value_num | 74.2 | 7 430 |
| voltage_v | 76.8 | 2 576 |
| tolerance_pct | 73.0 | 134 |
| power_w | 81.9 | 767 |
| package | 18.9 | 64 621 (raw text, includes `D12.5xL14.5mm` style packages) |
| dielectric | 95.7 | 11 |
| mounting | 32.4 | 2 |
| technology | 72.7 | 14 |
| stock_int | 0.0 | 42 022 |

NULL rates for the families that matter, in-stock rows only (723 865 rows):

| Family | In-stock rows | value_num NULL % | voltage_v NULL % | tolerance_pct NULL % | package NULL % | dielectric NULL % | mounting NULL % |
|---|---:|---:|---:|---:|---:|---:|---:|
| capacitor | 64 688 | 7.8 | 8.1 | 14.5 | 0.4 | 61.9 (non-ceramic) | 4.9 |
| resistor | 118 200 | 7.0 | 27.7 | 7.5 | 0.1 | 100 | 3.0 |
| inductor | 46 675 | 8.1 | 97.2 | 14.5 | 0.8 | 100 | 4.7 |
| connector | 146 360 | 100 | 100 | 100 | 11.9 | 100 | 31.4 |
| diode / transistor / ic / led | 72 864 / 55 402 / 97 736 / 11 874 | 100 | 9 to 21 | 97 to 100 | 0.1 to 2.0 | 100 | 16 to 57 |
| (no family) | 105 660 | 100 | 36.7 | 95.8 | 7.5 | 100 | 21.3 |

So `value_num` is only meaningful for capacitors, resistors, inductors and ferrites (the regexes do not model other families), which is why the plans below always filter on `family` first. The full per-family table over all rows is in `res_nulls.json`.

### Index build (one at a time, on the cached table)

| Index | Build time | File growth | File size after |
|---|---:|---:|---:|
| (table only) | | | 346 103 808 |
| `(family, value_num)` | 2.56 s | +123 568 128 | 469 671 936 |
| `(family, package)` | 2.74 s | +152 682 496 | 622 354 432 |
| `(value_num)` | 1.83 s | +74 113 024 | 696 467 456 |
| `(voltage_v)` | 1.76 s | +70 475 776 | 766 943 232 |
| `(tolerance_pct)` | 1.67 s | +69 292 032 | 836 235 264 |
| partial `(family, value_num) WHERE stock_int > 0` | 0.47 s | +12 890 112 | 849 125 376 |
| `ANALYZE` | 1.26 s | +24 576 | 849 149 952 |
| **Total** | **11.04 s (+1.26 s ANALYZE)** | **+503 MB** | **849 MB (810 MiB)** |

Later `ALTER TABLE ADD COLUMN generation` plus `CREATE INDEX ix_gen ON part_index(generation)` (0.82 s, +64.5 MB) bring the kept file to 981 909 504 bytes.

### Field queries (warm = median of 7 runs, first = after evicting `part_index.db`; `ANALYZE` was run)

Predicates: capacitor `value_num BETWEEN 9.9e-6 AND 10.1e-6`; resistor `value_num BETWEEN 4653 AND 4747`; `voltage_v IS NULL OR voltage_v >= 25`; `stock_int > 0`; `ORDER BY stock_int DESC LIMIT 40`. Results are exact counts in the stored index (not comparable one-to-one with the FTS rows: the field queries also apply 1 % tolerance and 25 V).

| Query | Rows (count) | select first / warm | count(*) first / warm | Plan |
|---|---:|---|---|---|
| Q1 cap 10uF 1 %, 0805, X7R, V >= 25 or NULL | 5 | 354 ms / **12.7 ms** | 473 ms / 12.5 ms | `SEARCH part_index USING INDEX ix_fam_pkg (family=? AND package=?)` + `USE TEMP B-TREE FOR ORDER BY` |
| Q2 (no dielectric) | 92 (40 returned) | 232 ms / 13.5 ms | 269 ms / 12.6 ms | same as Q1 |
| Q3 (no dielectric, no package) | 1 919 (40 returned) | 157 ms / 0.92 ms | 111 ms / 0.81 ms | `SEARCH part_index USING INDEX ix_fam_val_stock (family=? AND value_num>? AND value_num<?)` + temp B-tree |
| Q4 resistor 4.7k 1 %, 0603, tol NULL or <= 1 | 119 (40 returned) | 286 ms / 24.9 ms | 302 ms / 24.6 ms | `SEARCH part_index USING INDEX ix_fam_pkg (ANY(family) AND package=?)` (skip-scan) + temp B-tree |
| Q5 resistor 4.7k 0805 JOIN FTS `parts MATCH '"thin film"'` | 26 | 1 927 ms / 75.0 ms | 1 782 ms / 75.6 ms | `SCAN fp VIRTUAL TABLE INDEX 0:M12` then `SEARCH pi USING INTEGER PRIMARY KEY (rowid=?)` + temp B-tree |
| Q1 forced `INDEXED BY ix_fam_val_stock` | 5 | 245 ms / **0.91 ms** | 185 ms / 0.87 ms | `SEARCH ... ix_fam_val_stock (family=? AND value_num>? AND value_num<?)` |
| Q4 forced `INDEXED BY ix_fam_val_stock` | 119 | 128 ms / **0.35 ms** | 166 ms / 0.41 ms | same |
| Q1 `NOT INDEXED` (full scan, reference) | 5 | 273 ms / 147 ms | 294 ms / 146 ms | `SCAN part_index` |

How SQLite handles `IS NULL OR`: both `(voltage_v IS NULL OR voltage_v >= 25)` and `(tolerance_pct IS NULL OR tolerance_pct <= 1)` are not used for index seeks. They do not appear in any plan line: they are residual filters evaluated on each row the chosen index returns (the plans show only `family`, `package` and `value_num` seeks). Consequence: the cost depends on how many rows the equality/range prefix returns, and the `voltage_v`/`tolerance_pct`/`value_num` single-column indexes were not chosen by any of the five queries (only `ix_fam_pkg`, `ix_fam_val_stock`, the PK for Q5).

Planner finding: with `ANALYZE` data, Q1 and Q4 choose `ix_fam_pkg`, which reads every 0805 capacitor (in stock or not, about 100 000 rows) and filters, 12.7 ms and 24.9 ms. Forcing the partial index with `INDEXED BY` gives 0.91 ms and 0.35 ms (14x and 70x faster), because the partial index holds only the 10 % in-stock rows and narrows on value first. A composite such as `(family, package, value_num) WHERE stock_int > 0` would let the planner choose well on its own (not built or tested). Q5 is driven by the FTS side (the `"thin film"` trigram scan returns many rows) and costs 75 ms.

## 4. Atomic refresh

### (A) Today: file swap

| Step | Measured |
|---|---:|
| `os.replace` of a 1 GiB file over an existing 1 GiB file (3 runs, btrfs, same directory) | 3.1 ms, 3.3 ms, 3.8 ms |
| `os.rename` of a 1 GiB file to a new name | about 0.04 ms |
| `os.sync()` after the rename | 55 to 73 ms |
| Reopen read-only connection + `SELECT "LCSC Part" FROM parts LIMIT 1` probe (the real 5.3 GB file) | 0.07 to 0.12 ms warm, 2.4 ms right after cache eviction |
| First real search after the swap on a cold page cache (Q1 count + page) | 353 ms |
| `SELECT count(*) FROM parts` validation (KINA's validator) on the real file | 19.6 s cold (after evicting), 0.81 s warm |
| Download + unzip | not measured (network, outside the scope) |

The swap itself is a metadata operation of milliseconds and readers only wait for the write lock for that long. The cost of today's approach is downloading and validating a whole new 5.3 GB file (20 s cold validation), and the cold page cache afterwards (first searches 0.35 to 1.2 s instead of 0.02 to 0.09 s). Disk need during refresh is 2x the file (about 10.7 GB).

### (B) In-database replacement

FTS table on a 500 k-row subset: `subset.db` has 510 483 rows (every 14th rowid of the source, same schema and trigram tokenizer), size 392 851 456 bytes. `new.db` / `new2.db` have 510 484 different rows (rowid mod 14 = 1 and 2). Statement: `ATTACH 'new.db' AS n; BEGIN; DELETE FROM main.parts; INSERT INTO main.parts SELECT * FROM n.parts; COMMIT`. Extrapolation factor is 14.0x (7 146 764 / 510 483), **assuming time scales linearly with rows** (FTS5 segment merges are not guaranteed linear; treat as an estimate). A reader ran an FTS query (`10uF X7R 0805`) every 200 ms in another thread.

| Variant (subset, 510 k rows) | DELETE | INSERT | COMMIT | Total | Extrapolated to 7.1 M rows | Peak journal / WAL | Longest reader stall |
|---|---:|---:|---:|---:|---:|---:|---:|
| Rollback journal, DELETE FROM + INSERT SELECT | 8.4 s | 10.7 s | 0.12 s | 19.2 s | **269 s (4.5 min)** | journal 270 MB | **19.05 s** (all of the transaction after the cache spill, about 270 s extrapolated) |
| WAL, DELETE FROM + INSERT SELECT | 8.6 s | 18.4 s | 7.4 s | 34.4 s | 482 s | WAL 733 MB | 7.6 ms (max read latency; max gap 0.20 s = the 200 ms loop) |
| Rollback journal, DROP TABLE + CREATE VIRTUAL TABLE + INSERT SELECT | 4.6 s | 7.8 s | 1.1 s | 13.5 s | 189 s | journal 383 MB | 13.3 s |
| WAL, DROP TABLE + CREATE VIRTUAL TABLE + INSERT SELECT | 4.6 s | 12.3 s | 7.0 s | 23.8 s | 334 s | WAL 500 MB | 4.8 ms |
| `INSERT INTO parts(parts) VALUES('delete-all')` | | | | | | | refused by FTS5: only for contentless or external-content tables |

File size effects: after the first in-place replacement the subset file grew from 392.9 MB to 727.9 MB (freed pages, not returned); `VACUUM` took 5.3 s and brought it back to 382.0 MB. Extrapolated to the full file, an in-place FTS replace leaves a file about 1.85x the size until `VACUUM` (about 74 s extrapolated, and it needs about one extra copy of free disk). Journal/WAL peak is about the size of the new data (0.7 to 1x), so a full-size swap needs another 4 to 10 GB of temp disk next to the new database it reads from.

The full-size part_index replace (7 146 764 rows, rollback journal, six indexes live): `DELETE` 4.1 s, `INSERT SELECT` 71.7 s, `COMMIT` 0.7 s, **76.5 s total**, peak journal 851 MB, file 849 MB to 912 MB. Variant E: inside one transaction `DROP INDEX` x7, `DELETE`, `INSERT SELECT`, `CREATE INDEX` x7, `COMMIT` (WAL): drop 1.1 s, delete 0.4 s, insert 2.4 s, recreate indexes 9.6 s, commit 1.7 s, **15.1 s total** (5x faster), peak WAL 988 MB, file unchanged at 982 MB, reader max latency 17 ms.

### (C) Generation column on part_index (7 146 764 rows, 7 indexes live incl. `ix_gen`)

In one transaction: `INSERT ... SELECT rowid + gen*16777216, ..., gen FROM new` (new generation, rowids offset so they do not collide), then `DELETE FROM part_index WHERE generation = old`, then `COMMIT`. A reader ran Q1 (field) every 200 ms in another thread.

| Journal mode | INSERT | DELETE | COMMIT | Total | File before / after | Peak journal or WAL | Reader: reads, max latency |
|---|---:|---:|---:|---:|---|---:|---|
| Rollback journal (default cache) | 77.7 s | 107.1 s | 0.5 s | **185.3 s** | 976 MB / 2 022 MB | journal 979 MB | 8 reads, **185.9 s stall** (blocked from about 1 s into the transaction until commit) |
| WAL (default cache) | 80.9 s | 74.7 s | 22.5 s | **178.0 s** | 982 MB / 2 037 MB | WAL 2 049 MB | 899 reads, **max 36 ms**, median 16 ms, no errors |

The 22.5 s WAL commit most likely includes the automatic checkpoint (`wal_autocheckpoint` runs at commit when the WAL exceeds 1 000 pages; inferred from the timing, not traced); readers were not blocked by it. In WAL mode the reader sees the old generation until the commit is visible, and the new generation after, with no gap longer than the 200 ms loop period.

### (D) WAL, VACUUM and disk

| Item | Value |
|---|---|
| WAL reader stall during (C) | max 36 ms (vs 185.9 s with the rollback journal) |
| WAL file size reached | 2 049 090 272 bytes (about 2.0 GB, 2.1x the database; it holds every changed page of a 178 s transaction) |
| `PRAGMA wal_checkpoint(TRUNCATE)` after the run | 0.14 s (already checkpointed at commit), WAL back to 0 |
| Peak disk of part_index + temp during (C), rollback | 2.02 GB db + 0.98 GB journal = 3.0 GB |
| Peak disk of part_index + temp during (C), WAL | 2.04 GB db + 2.05 GB WAL = 4.1 GB (4.4 GB with the new_pi.db source; the only point over my 4 GB limit; free space stayed above 22 GB) |
| `VACUUM` after the deletes, rollback mode | 19.7 s, 2 022 416 384 to 981 909 504 bytes (233 907 of 493 754 pages free); needs about 976 MB of temp |
| `VACUUM` after the deletes, WAL mode | 11.9 s, 2 037 178 368 to 981 909 504 bytes (WAL 988 MB afterwards, back to 0 after a checkpoint) |

### Summary of the refresh comparison

| Approach | Time to refresh (7.1 M rows) | Readers blocked | Temp disk | Notes |
|---|---|---|---|---|
| (A) File swap (today) | ms for the swap; download + 20 s validation outside | write lock for ms | 1x file (5.3 GB) extra | cold cache after the swap |
| (B) FTS in place, rollback journal | about 4.5 min (DELETE + INSERT) or 3.1 min (DROP/CREATE) extrapolated | **whole transaction (about 4.5 min)** | 0.7 to 1x data | file bloats about 1.85x until VACUUM |
| (B) FTS in place, WAL | about 8 min or 5.6 min extrapolated | none | WAL about the size of the data | slower (the commit checkpoints) |
| (B) part_index in place, rollback | 76.5 s (15 s without indexes) | whole transaction | 0.85 GB | |
| (C) generation swap on part_index | about 3 min | rollback: whole transaction; WAL: none | WAL 2.0 GB | file doubles until VACUUM (12 to 20 s) |

## 5. Trigram share of the 5.3 GB

`plain_subset.db` holds the same 510 483 rows as a regular table (same 12 columns, no index), size 121 507 840 bytes. Page accounting of `subset.db` via `dbstat`:

| Object (subset.db, 392 851 456 bytes) | Bytes | Share |
|---|---:|---:|
| `parts_data` (trigram posting lists) | 256 782 336 | 65.4 % |
| `parts_idx` | 491 520 | 0.1 % |
| `parts_content` (the row text) | 122 433 536 | 31.2 % |
| `parts_docsize` | 11 182 080 | 2.8 % |
| Total FTS5 file vs plain table | 392.9 MB vs 121.5 MB | FTS5 = 3.23x plain; the extra 271.3 MB = **69.1 % of the FTS5 file** |

Same accounting on the full source (`dbstat`, 11 s): `parts_data` 3 447 156 736 bytes (**64.7 %** of 5 329 715 200), `parts_content` 1 714 790 400 (32.2 %), `parts_docsize` 156 520 448 (2.9 %), `parts_idx` 1.7 MB. So the trigram index is about **3.45 GB, 65 % of the file**; the row text is 1.71 GB. The subset estimate (65.4 %) matches the exact number (64.7 %) within 0.7 points. Only 10.1 % of the rows are in stock.

## 6. Headline findings

1. Baseline Q1 (`10uF X7R 0805`) is 23 ms warm (354 ms cold); the slow shapes are single 2-character tokens (LIKE full scan, 2.7 s warm, 25 s cold) and OR fallbacks over common trigrams (1.5 s warm, 29 s cold).
2. One shared connection behind a lock gives 20 searches/s; 8 independent read-only connections give 36/s in Python threads and 88/s in processes (4.4x).
3. A typed index costs 132 s to extract once and 11 s to build six indexes; file grows from 346 MB to 849 MB (982 MB with the generation column). Field Q1 is 12.7 ms warm with the planner's choice and 0.9 ms with the partial in-stock index (vs 23 ms for FTS); Q4 is 24.9 ms vs 0.35 ms forced.
4. In-database refresh of the FTS table is about 4.5 min extrapolated in rollback mode and blocks readers for all of it; in WAL readers are never blocked (max 7.6 ms) but the job takes about 8 min and needs WAL space about equal to the data. Replacing part_index in place takes 76 s (15 s if indexes are dropped and rebuilt inside the transaction).
5. The trigram index is 65 % of the database file (3.45 GB of 5.33 GB).

## 7. Caveats

- Python `sqlite3` with Python UDFs, not sqlite-jdbc; absolute numbers for `kina_value` heavy queries will differ in Java (the FTS5 and index work is the same C code, SQLite 3.53.4 here; sqlite-jdbc bundles its own version).
- The concurrency test is limited by the GIL in thread mode; process mode removes it but adds nothing else. The shared connection with no lock is untestable in Python (deadlock). KINA's behaviour (read lock, serialized sqlite-jdbc connection) was inferred, not run.
- Page cache: eviction by `posix_fadvise` removes the file's clean pages only; the OS also keeps readahead and the swap-pressured memory made the "warm" numbers slightly noisy (min/max in `res_base1.json`, `res_fq.json`).
- Baseline queries were reconstructed from the Java source rather than run through `JlcpcbQuery.parse`; the term lists were written by hand to match the parser's output. The relaxation flow is a Python port of `relax`/`dropOneAtATime`.
- Extraction regexes are crude (first match wins, no unit-aware family rules, `family` is NULL for 25 % of rows, `package` is raw text with 64 621 distinct values, `dielectric` and `tolerance_pct` miss `-20%~+80%` style ranges). NULL rates are therefore upper bounds on what a real extractor would leave empty; the in-stock rows are better covered than the all-rows average.
- The field-query result counts are not comparable one-to-one with the FTS counts (field queries add 1 % and 25 V, and use the extracted columns).
- Extrapolation to 7.1 M rows is linear in row count from a 510 k subset (14.0x). FTS5 insert and delete costs per row can grow with index size; the extrapolated numbers may be optimistic. The part_index numbers are full size, not extrapolated.
- Rollback-journal stalls depend on the page cache size: the default cache (about 2 MB) forced an exclusive lock early in the transaction. A very large `cache_size` would delay the stall to commit; not tested.
- In (C) the new generation's rowids are offset (+2^24 per generation), so the join `part_index.rowid = parts.rowid` for the FTS side would need a separate `fts_rowid` column in a real design.
- A concurrent Postgres benchmark and heavy swap use were running on the host during all timings.
- `part_index.db` was left in WAL mode, with 7 indexes (including `ix_gen`), `sqlite_stat1/4` populated and generation 1 on every row.


## Appendix A. SQL used

```sql
-- baseline (Q1): count and page, as JlcpcbSqliteSearch builds them
SELECT count(*) FROM parts WHERE parts MATCH '("10uF") AND ("X7R") AND ("0805")' AND kina_value("Description", '10uF') AND CAST("Stock" AS INTEGER) > 0;
SELECT "LCSC Part","First Category","Second Category","MFR.Part",Package,"Solder Joint",Manufacturer,"Library Type",Description,Datasheet,Price,Stock FROM parts WHERE parts MATCH '("10uF") AND ("X7R") AND ("0805")' AND kina_value("Description", '10uF') AND CAST("Stock" AS INTEGER) > 0 ORDER BY rank, CAST("Stock" AS INTEGER) DESC LIMIT 200;
-- Q2 MATCH '("4.7k") AND ("0603")' + kina_value(...,'4.7k')
-- Q3 MATCH '("Second Category" : "Female Header") AND ("1x6P") AND ("Right Angle")' + (kina_value(Description,'1x6P') OR kina_value("MFR.Part",'1x6P'))
-- Q4 MATCH '("Thin Film") AND ("5.36k") AND ("0805")' + kina_value(...,'5.36k')
-- Q5 "Description" LIKE '%1k%' ESCAPE '\' AND kina_value("Description",'1k') AND CAST("Stock" AS INTEGER) > 0   (no MATCH: full scan; order by stock only)
-- Q6 (ANY) parts MATCH '("100nF") OR ("X7R") OR ("0402") OR ("16V") OR ("capacitor") OR ("Murata")' AND CAST("Stock" AS INTEGER) > 0 ORDER BY rank, CAST("Stock" AS INTEGER) DESC LIMIT 200

-- field index
CREATE TABLE part_index(rowid INTEGER PRIMARY KEY, lcsc TEXT, family TEXT, value_num REAL, voltage_v REAL, tolerance_pct REAL, power_w REAL, package TEXT, dielectric TEXT, mounting TEXT, technology TEXT, stock_int INTEGER);
CREATE INDEX ix_fam_val ON part_index (family, value_num);
CREATE INDEX ix_fam_pkg ON part_index (family, package);
CREATE INDEX ix_val ON part_index (value_num);
CREATE INDEX ix_volt ON part_index (voltage_v);
CREATE INDEX ix_tol ON part_index (tolerance_pct);
CREATE INDEX ix_fam_val_stock ON part_index (family, value_num) WHERE stock_int > 0;
ANALYZE;

-- field Q1 (Q2 drops dielectric; Q3 also drops package)
SELECT rowid,lcsc,value_num,voltage_v,stock_int FROM part_index WHERE family='capacitor' AND value_num BETWEEN 9.9e-6 AND 10.1e-6 AND package='0805' AND dielectric='X7R' AND (voltage_v IS NULL OR voltage_v >= 25) AND stock_int > 0 ORDER BY stock_int DESC LIMIT 40;
-- Q4
SELECT rowid,lcsc,value_num,voltage_v,stock_int FROM part_index WHERE family='resistor' AND value_num BETWEEN 4653 AND 4747 AND package='0603' AND (tolerance_pct IS NULL OR tolerance_pct <= 1) AND stock_int > 0 ORDER BY stock_int DESC LIMIT 40;
-- Q5 (ATTACH 'file:/var/tmp/kina-bench/parts-fts5.db?mode=ro' AS f)
SELECT pi.rowid,pi.lcsc,pi.value_num,pi.stock_int FROM part_index pi JOIN f.parts AS fp ON fp.rowid=pi.rowid WHERE fp.parts MATCH '"thin film"' AND pi.family='resistor' AND pi.value_num BETWEEN 4653 AND 4747 AND pi.package='0805' AND pi.stock_int > 0 ORDER BY pi.stock_int DESC LIMIT 40;

-- refresh (B), FTS subset
ATTACH 'new.db' AS n; BEGIN; DELETE FROM main.parts; INSERT INTO main.parts SELECT * FROM n.parts; COMMIT;
-- DROP/CREATE variant: BEGIN; DROP TABLE main.parts; CREATE VIRTUAL TABLE main.parts USING fts5(<same 12 columns>, tokenize="trigram"); INSERT INTO main.parts SELECT * FROM n.parts; COMMIT;
-- refresh (C), generation swap
ALTER TABLE part_index ADD COLUMN generation INTEGER NOT NULL DEFAULT 1; CREATE INDEX ix_gen ON part_index(generation);
BEGIN; INSERT INTO main.part_index(rowid,lcsc,...,stock_int,generation) SELECT rowid+16777216*:new, lcsc,...,stock_int, :new FROM n.part_index; DELETE FROM main.part_index WHERE generation=:old; COMMIT;
PRAGMA journal_mode=WAL; PRAGMA wal_checkpoint(TRUNCATE); VACUUM;
-- variant E: BEGIN; DROP INDEX x7; DELETE FROM main.part_index; INSERT INTO main.part_index SELECT ... FROM n.part_index; CREATE INDEX x7; COMMIT;

-- trigram share
SELECT name, sum(pgsize), sum(unused) FROM dbstat GROUP BY name ORDER BY 2 DESC;
```

## Appendix B. Python scripts (also in `py/`)

### kb.py: Shared helpers: UDF port, query building, relaxation port, benchmark queries

```python
import sqlite3, time, statistics, re
SRC = "file:/var/tmp/kina-bench/parts-fts5.db?mode=ro"
COLS = '"LCSC Part","First Category","Second Category","MFR.Part",Package,"Solder Joint",Manufacturer,"Library Type",Description,Datasheet,Price,Stock'
IN_STOCK = 'CAST("Stock" AS INTEGER) > 0'

def contains_value(text, token):  # port of JlcpcbSqliteSearch.containsValue
    if not text or not token: return 0
    h = text.lower(); n = token.lower(); frm = 0
    while True:
        at = h.find(n, frm)
        if at < 0: return 0
        if at == 0 or not (h[at-1].isdigit() or h[at-1] == '.'): return 1
        frm = at + 1

def connect(uri=SRC, **kw):
    c = sqlite3.connect(uri, uri=True, check_same_thread=False, **kw)
    c.create_function("kina_value", 2, contains_value, deterministic=True)
    return c

def q(s): return '"' + s.replace('"', '""') + '"'

class T:  # a parsed term, as JlcpcbQuery would produce
    def __init__(s, text, kind, alts=None, column=None):
        s.text, s.kind, s.alts, s.column = text, kind, alts or [], column
    def phrases(s): return s.alts or [s.text]
    def matchable(s): return all(len(p) >= 3 for p in s.phrases())
    def boundary(s): return s.kind in ("VALUE", "POSITIONS", "PITCH")
    def expr(s):
        ph = s.phrases()
        inner = q(ph[0]) if len(ph) == 1 else "(" + " OR ".join(q(p) for p in ph) + ")"
        return inner if not s.column else q(s.column) + " : " + inner
    def __repr__(s): return s.text

DROP = ["KEYWORD","FEATURE","MOUNTING","ORIENTATION","PITCH","DIELECTRIC","PACKAGE","RATING","VALUE","POSITIONS","FAMILY","CATEGORY"]
def drop_rank(t):
    if t.kind == "VALUE" and t.text.endswith("%"): return DROP.index("DIELECTRIC") + .5
    return DROP.index(t.kind)

def conjunction(terms):
    clauses, params = [], []
    m = " AND ".join("(" + t.expr() + ")" for t in terms if t.matchable())
    has = bool(m)
    if has: clauses.append("parts MATCH ?"); params.append(m)
    for t in terms:
        if not t.matchable():
            lk = []
            for p in t.phrases():
                lk.append("\"Description\" LIKE ? ESCAPE '\\'"); params.append("%" + p.replace("\\","\\\\").replace("%","\\%").replace("_","\\_") + "%")
            clauses.append(lk[0] if len(lk) == 1 else "(" + " OR ".join(lk) + ")")
        if t.kind == "VALUE":
            clauses.append('kina_value("Description", ?)'); params.append(t.text)
        elif t.boundary():
            col = '"Package"' if t.kind == "PITCH" else '"MFR.Part"'
            ch = []
            for p in t.phrases():
                ch.append(f'kina_value("Description", ?) OR kina_value({col}, ?)'); params += [p, p]
            clauses.append("(" + " OR ".join(ch) + ")")
    clauses.append(IN_STOCK)
    return " AND ".join(clauses), params, has

def any_pred(terms):
    m = " OR ".join("(" + t.expr() + ")" for t in terms if t.matchable())
    return "parts MATCH ? AND " + IN_STOCK, [m], True

def count(c, p):
    w, ps, _ = p
    return c.execute(f"SELECT count(*) FROM parts WHERE {w}", ps).fetchone()[0]
def page(c, p, limit=200):
    w, ps, has = p
    order = 'rank, CAST("Stock" AS INTEGER) DESC' if has else 'CAST("Stock" AS INTEGER) DESC'
    return c.execute(f"SELECT {COLS} FROM parts WHERE {w} ORDER BY {order} LIMIT ?", ps + [limit]).fetchall()
def count_nostock(c, p):
    w, ps, h = p
    suf = " AND " + IN_STOCK
    return count(c, (w[:-len(suf)], ps, h))

def search(c, terms, trace=None):
    """Port of JlcpcbSqliteSearch.search: ALL, out-of-stock count, relaxation, PARAMETRIC, ANY. Returns (mode,total,rows,dropped)."""
    def log(s):
        if trace is not None: trace.append(s)
    p = conjunction(terms); has = p[2]
    tot = count(c, p) if p else 0
    if tot > 0:
        log(f"ALL total={tot}"); return "ALL", tot, page(c, p), []
    oos = count_nostock(c, p); log(f"ALL total=0 (out-of-stock matches {oos})")
    tried = {tuple(id(t) for t in terms)}
    remaining = list(terms); dropped = []
    def occurs(t):
        return c.execute("SELECT 1 FROM parts WHERE parts MATCH ? LIMIT 1", [t.expr()]).fetchone() is not None
    dead = [t for t in remaining if t.matchable() and not occurs(t)]
    log(f"dead terms {dead}")
    def attempt(rem, dr, mode="RELAXED"):
        key = tuple(id(t) for t in rem)
        if not rem or key in tried: return None
        tried.add(key)
        pp = conjunction(rem)
        if not pp[2]: return None
        n = count(c, pp); log(f"attempt {rem} -> {n}")
        if n == 0: return None
        return mode, n, page(c, pp), list(dr)
    if dead and len(dead) < len(remaining):
        remaining = [t for t in remaining if t not in dead]; dropped += [t.text for t in dead]
        r = attempt(remaining, dropped)
        if r: return r
    while sum(1 for t in remaining if t.kind != "RATING") > 2:
        best = None; br = 1e9
        for t in remaining:
            rk = drop_rank(t)
            if rk <= br: best, br = t, rk
        remaining.remove(best); dropped.append(best.text)
        r = attempt(remaining, dropped)
        if r: return r
    pp = any_pred(terms)
    n = count(c, pp); log(f"ANY -> {n}")
    if n: return "ANY", n, page(c, pp), []
    return "EMPTY", 0, [], []

# ---- the benchmark queries (what JlcpcbQuery.parse yields for each text)
Q = {
 "Q1 10uF X7R 0805": [T("10uF","VALUE"),T("X7R","DIELECTRIC"),T("0805","PACKAGE")],
 "Q2 4.7k 0603": [T("4.7k","VALUE"),T("0603","PACKAGE")],
 "Q3 Female Header 1x6P Right Angle": [T("Female Header","CATEGORY",["Female Header"],"Second Category"),T("1x6P","POSITIONS"),T("Right Angle","ORIENTATION",["Right Angle"])],
 "Q4 Thin Film 5.36k 0805": [T("Thin Film","KEYWORD"),T("5.36k","VALUE"),T("0805","PACKAGE")],
 "Q5 1k (LIKE path)": [T("1k","VALUE")],
}
Q6 = [T("100nF","VALUE"),T("X7R","DIELECTRIC"),T("0402","PACKAGE"),T("16V","VALUE"),T("capacitor","FAMILY"),T("Murata","KEYWORD")]
QR = [T("10uF","VALUE"),T("X7R","DIELECTRIC"),T("0805","PACKAGE"),T("1%","VALUE"),T("100V","VALUE")]

def timeit(fn, warm=5):
    t = time.perf_counter(); r = fn(); first = time.perf_counter() - t
    ws = []
    for _ in range(warm):
        t = time.perf_counter(); fn(); ws.append(time.perf_counter() - t)
    return first, statistics.median(ws), min(ws), max(ws), r

```

### base2.py: Baseline timings (cold via posix_fadvise, 5 warm)

```python
import sys, json, os; sys.path.insert(0,'.')
from kb import *
def evict():
    fd=os.open('/var/tmp/kina-bench/parts-fts5.db',os.O_RDONLY); os.posix_fadvise(fd,0,0,os.POSIX_FADV_DONTNEED); os.close(fd)
out={}
def run(name, terms):
    c = connect()
    p = conjunction(terms)
    evict()
    t=time.perf_counter(); n=count(c,p); cf=time.perf_counter()-t
    t=time.perf_counter(); rows=page(c,p); pf=time.perf_counter()-t
    cw=[];pw=[]
    for _ in range(5):
        t=time.perf_counter(); count(c,p); cw.append(time.perf_counter()-t)
        t=time.perf_counter(); page(c,p); pw.append(time.perf_counter()-t)
    r=dict(total=n,rows=len(rows),count_cold=cf,page_cold=pf,count_warm_med=statistics.median(cw),page_warm_med=statistics.median(pw),
           search_cold=cf+pf, search_warm_med=statistics.median(cw)+statistics.median(pw))
    out[name]=r; print(name,{k:(round(v,4) if isinstance(v,float) else v) for k,v in r.items()},flush=True)
for name,terms in Q.items(): run(name,terms)
# Q6: ANY (OR fallback)
c=connect(); p=any_pred(Q6); evict()
t=time.perf_counter(); n=count(c,p); cf=time.perf_counter()-t
t=time.perf_counter(); rows=page(c,p); pf=time.perf_counter()-t
cw=[];pw=[]
for _ in range(5):
    t=time.perf_counter(); count(c,p); cw.append(time.perf_counter()-t)
    t=time.perf_counter(); page(c,p); pw.append(time.perf_counter()-t)
out["Q6 OR fallback (6 terms)"]=dict(total=n,rows=len(rows),count_cold=cf,page_cold=pf,count_warm_med=statistics.median(cw),page_warm_med=statistics.median(pw),search_cold=cf+pf,search_warm_med=statistics.median(cw)+statistics.median(pw),match=p[1][0])
print(out["Q6 OR fallback (6 terms)"],flush=True)
# relaxation flow
for nm,terms in (("QR relax 10uF X7R 0805 1% 100V",QR),("Q6 as ALL then relax",Q6)):
    c=connect(); evict(); tr=[]
    t=time.perf_counter(); mode,n,rows,dr=search(c,terms,tr); cold=time.perf_counter()-t
    ws=[]
    for _ in range(3):
        t=time.perf_counter(); search(c,terms); ws.append(time.perf_counter()-t)
    out[nm]=dict(mode=mode,total=n,rows=len(rows),dropped=dr,trace=tr,cold=cold,warm_med=statistics.median(ws))
    print(nm,out[nm],flush=True)
json.dump(out,open("../res_base2.json","w"),indent=1,default=str)

```

### conc.py: Concurrency test

```python
import sys, json, threading, multiprocessing as mp; sys.path.insert(0,'.')
from kb import *
W = [Q["Q1 10uF X7R 0805"],Q["Q2 4.7k 0603"],Q["Q3 Female Header 1x6P Right Angle"],Q["Q4 Thin Film 5.36k 0805"],
 [T("100nF","VALUE"),T("X7R","DIELECTRIC"),T("0402","PACKAGE")],
 [T("10kΩ","VALUE"),T("0805","PACKAGE")],
 [T("Pin Header","CATEGORY",["Pin Header"],"Second Category"),T("2x10P","POSITIONS"),T("Right Angle","ORIENTATION",["Right Angle"])],
 [T("Schottky","FAMILY"),T("SOD-123","PACKAGE")]]
ROUNDS=20
def one(c, terms):
    p=conjunction(terms); count(c,p); page(c,p)
def proc_worker(i, barrier, q):
    c=connect(); one(c,W[i]); barrier.wait()
    t=time.perf_counter()
    for _ in range(ROUNDS): one(c,W[i])
    q.put(time.perf_counter()-t)
def run_mode(mode):
    if mode=="sequential_1conn":
        c=connect(); [one(c,w) for w in W]
        t=time.perf_counter()
        for w in W:
            for _ in range(ROUNDS): one(c,w)
        return time.perf_counter()-t
    if mode=="processes_own_conn":
        b=mp.Barrier(9); qq=mp.Queue(); ps=[mp.Process(target=proc_worker,args=(i,b,qq)) for i in range(8)]
        [p.start() for p in ps]; b.wait(); t=time.perf_counter(); [p.join() for p in ps]; return time.perf_counter()-t
    conns=[connect() for _ in range(8)] if mode=="threads_own_conn" else None
    shared=connect() if mode!="threads_own_conn" else None
    lock=threading.Lock()
    for i in range(8): one(conns[i] if conns else shared, W[i])
    b=threading.Barrier(9)
    def work(i):
        b.wait()
        for _ in range(ROUNDS):
            if mode=="threads_own_conn": one(conns[i],W[i])
            elif mode=="threads_shared_conn_lock":
                with lock: one(shared,W[i])
            else: one(shared,W[i])   # shared, no python lock: SQLite serialized mutex
    ths=[threading.Thread(target=work,args=(i,)) for i in range(8)]
    [t.start() for t in ths]; b.wait(); t=time.perf_counter(); [t.join() for t in ths]; return time.perf_counter()-t
if __name__=="__main__":
    res={}
    for mode in ["sequential_1conn","threads_shared_conn_lock","threads_own_conn","processes_own_conn"]:
        ws=[run_mode(mode) for _ in range(3)]
        n=8*ROUNDS; med=statistics.median(ws)
        res[mode]=dict(wall_s=ws,searches=n,median_wall=med,throughput_per_s=n/med)
        print(mode,res[mode],flush=True)
    json.dump(res,open("../res_conc.json","w"),indent=1)

```

### extract.py: Field extraction scan

```python
import sys, re, time, sqlite3, json, os; sys.path.insert(0,'.')
OUT="/var/tmp/kina-bench/sqlite/part_index.db"
if os.path.exists(OUT): os.remove(OUT)
src=sqlite3.connect("file:/var/tmp/kina-bench/parts-fts5.db?mode=ro",uri=True)
dst=sqlite3.connect(OUT)
dst.execute("""CREATE TABLE part_index(rowid INTEGER PRIMARY KEY, lcsc TEXT, family TEXT, value_num REAL, voltage_v REAL,
 tolerance_pct REAL, power_w REAL, package TEXT, dielectric TEXT, mounting TEXT, technology TEXT, stock_int INTEGER)""")
NUM=r"(\d+(?:\.\d+)?)"
PFX={'p':1e-12,'n':1e-9,'u':1e-6,'m':1e-3,'':1.0,'k':1e3,'K':1e3,'M':1e6,'G':1e9}
R_F=re.compile(NUM+r"\s?([pnumµ]?)F\b")
R_OHM=re.compile(NUM+r"\s?([mkKMG]?)Ω")
R_H=re.compile(NUM+r"\s?([nuµm]?)H\b")
R_V=re.compile(r"(?<![\d.])"+NUM+r"\s?([mk]?)V\b")
R_W=re.compile(r"(?<![\d.])"+NUM+r"\s?([mk]?)W\b")
R_TOL=re.compile(r"±\s?"+NUM+r"%")
R_DI=re.compile(r"\b(X7R|X5R|C0G|NP0|NPO|Y5V|X7S|X6S|X8R|X7T|Z5U)\b",re.I)
R_THT=re.compile(r"Plugin|插件|弯插|Through Hole|THT|DIP",re.I)
R_SMD=re.compile(r"\bSMD|SMT|Surface Mount|贴片",re.I)
R_CHIP=re.compile(r"^(01005|0201|0402|0603|0805|1008|1206|1210|1806|1812|2010|2220|2512|SOT|SOD|SOIC|SOP|TSSOP|QFN|DFN|QFP|BGA)")
R_TECH=re.compile(r"(Thick Film|Thin Film|Metal Film|Carbon Film|Wirewound|Metal Oxide|Metal Glaze|Ceramic|Tantalum|Electrolytic|Polymer|Polypropylene|Polyester|Mica|Supercap)",re.I)
FAM=[("capacitor",re.compile("Capacitor",re.I)),("resistor",re.compile("Resistor|Potentiometer",re.I)),
 ("inductor",re.compile("Inductor|Choke",re.I)),("ferrite",re.compile("Ferrite|Bead",re.I)),
 ("diode",re.compile("Diode|Rectifier|TVS|Zener",re.I)),("led",re.compile(r"\bLED",re.I)),
 ("transistor",re.compile("MOSFET|Transistor|Thyristor|IGBT",re.I)),("connector",re.compile("Connector|Header|Socket|Terminal",re.I)),
 ("ic",re.compile("Microcontroller|Amplifier|Regulator|Converter|Memory|Interface|Driver|Logic|Timer|Comparator|Sensor",re.I))]
def val(rx,s,mult=PFX):
    m=rx.search(s)
    if not m: return None
    return float(m.group(1))*mult[m.group(2).replace('µ','u')]
def row(rid,lcsc,cat1,cat2,pkg,desc,stock):
    cat=(cat2 or "")+" "+(cat1 or ""); fam=None
    for f,rx in FAM:
        if rx.search(cat): fam=f;break
    d=desc or ""
    v=None
    if fam=="capacitor": v=val(R_F,d)
    elif fam=="resistor": v=val(R_OHM,d)
    elif fam in("inductor","ferrite"): v=val(R_H,d) if fam=="inductor" else val(R_OHM,d)
    volt=val(R_V,d) if fam in("capacitor","diode","transistor","resistor","led","inductor","ic",None) else None
    tol=None; m=R_TOL.search(d)
    if m: tol=float(m.group(1))
    pw=val(R_W,d) if fam in("resistor","diode","inductor",None) else None
    p=(pkg or "").split(",")[0].strip() or None
    if p=="-": p=None
    di=None
    if fam=="capacitor":
        m=R_DI.search(d)
        if m: di=m.group(1).upper()
    pk=(pkg or "")+" "+d
    mnt="THT" if R_THT.search(pk) else ("SMD" if (R_SMD.search(pk) or R_CHIP.match(p or "")) else None)
    m=R_TECH.search(cat+" "+d); tech=m.group(1).lower() if m else None
    try: st=int(stock)
    except: st=0
    return (rid,lcsc,fam,v,volt,tol,pw,p,di,mnt,tech,st)
t0=time.time(); n=0; batch=[]
cur=src.execute('select rowid,"LCSC Part","First Category","Second Category",Package,Description,Stock from parts')
tw=0.0
for r in cur:
    batch.append(row(r[0],r[1],r[2],r[3],r[4],r[5],r[6])); n+=1
    if len(batch)==100000:
        t=time.time()
        dst.execute("BEGIN"); dst.executemany("INSERT INTO part_index VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",batch); dst.execute("COMMIT"); tw+=time.time()-t
        batch=[]
        if n%1000000==0: print(n,round(time.time()-t0,1),flush=True)
if batch:
    t=time.time(); dst.execute("BEGIN"); dst.executemany("INSERT INTO part_index VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",batch); dst.execute("COMMIT"); tw+=time.time()-t
tot=time.time()-t0
print(json.dumps(dict(rows=n,total_s=tot,insert_s=tw,scan_extract_s=tot-tw,size=os.path.getsize(OUT))))
json.dump(dict(rows=n,total_s=tot,insert_s=tw,scan_extract_s=tot-tw,size=os.path.getsize(OUT)),open("../res_extract.json","w"))

```

### idx.py: Index builds

```python
import sqlite3, time, os, json
P="/var/tmp/kina-bench/sqlite/part_index.db"
c=sqlite3.connect(P,isolation_level=None)
IDX=[("ix_fam_val","(family, value_num)"),("ix_fam_pkg","(family, package)"),("ix_val","(value_num)"),("ix_volt","(voltage_v)"),("ix_tol","(tolerance_pct)"),("ix_fam_val_stock","(family, value_num) WHERE stock_int > 0")]
res=[dict(name="(table only)",seconds=0,size=os.path.getsize(P),growth=0)]
prev=os.path.getsize(P)
for n,d in IDX:
    t=time.time(); c.execute(f"CREATE INDEX {n} ON part_index {d}"); dt=time.time()-t
    s=os.path.getsize(P); res.append(dict(name=n,def_=d,seconds=dt,size=s,growth=s-prev)); prev=s; print(res[-1],flush=True)
t=time.time(); c.execute("ANALYZE"); res.append(dict(name="ANALYZE",seconds=time.time()-t,size=os.path.getsize(P),growth=os.path.getsize(P)-prev))
print(res[-1])
# per-object sizes
try:
    res.append(dict(dbstat=c.execute("select name,sum(pgsize) from dbstat group by name order by 2 desc").fetchall()))
except Exception as e: print(e)
json.dump(res,open("../res_idx.json","w"),indent=1)

```

### fq.py: Field queries and plans

```python
import sqlite3, time, os, json, statistics, sys; sys.path.insert(0,'.')
from kb import contains_value
P="/var/tmp/kina-bench/sqlite/part_index.db"
def evict(p):
    fd=os.open(p,os.O_RDONLY); os.posix_fadvise(fd,0,0,os.POSIX_FADV_DONTNEED); os.close(fd)
def conn():
    c=sqlite3.connect("file:/var/tmp/kina-bench/sqlite/part_index.db?mode=ro",uri=True)
    c.execute("ATTACH 'file:/var/tmp/kina-bench/parts-fts5.db?mode=ro' AS f"); return c
c=conn()
CAP="family='capacitor' AND value_num BETWEEN 9.9e-6 AND 10.1e-6"
RES="family='resistor' AND value_num BETWEEN 4653 AND 4747"
VOLT="(voltage_v IS NULL OR voltage_v >= 25)"
S="stock_int > 0"
Qs={
"Q1 cap 10uF+-1% 0805 X7R >=25V":f"{CAP} AND package='0805' AND dielectric='X7R' AND {VOLT} AND {S}",
"Q2 (no dielectric)":f"{CAP} AND package='0805' AND {VOLT} AND {S}",
"Q3 (no dielectric, no package)":f"{CAP} AND {VOLT} AND {S}",
"Q4 res 4.7k 1% 0603":f"{RES} AND package='0603' AND (tolerance_pct IS NULL OR tolerance_pct <= 1) AND {S}",
}
sqls={}
for k,w in Qs.items():
    sqls[k]=(f"SELECT rowid,lcsc,value_num,voltage_v,stock_int FROM part_index WHERE {w} ORDER BY stock_int DESC LIMIT 40",
             f"SELECT count(*) FROM part_index WHERE {w}")
PI="pi.family='resistor' AND pi.value_num BETWEEN 4653 AND 4747 AND pi.package='0805' AND pi.stock_int > 0"
sqls["Q5 res 4.7k 0805 + FTS 'thin film'"]=(
 f"SELECT pi.rowid,pi.lcsc,pi.value_num,pi.stock_int FROM part_index pi JOIN f.parts AS fp ON fp.rowid=pi.rowid WHERE fp.parts MATCH '\"thin film\"' AND {PI} ORDER BY pi.stock_int DESC LIMIT 40",
 f"SELECT count(*) FROM part_index pi JOIN f.parts AS fp ON fp.rowid=pi.rowid WHERE fp.parts MATCH '\"thin film\"' AND {PI}")
for key,base in (("Q1 cap 10uF+-1% 0805 X7R >=25V","Q1y"),("Q4 res 4.7k 1% 0603","Q4y")):
    sqls[base+" same, INDEXED BY ix_fam_val_stock"]=tuple(x.replace("FROM part_index","FROM part_index INDEXED BY ix_fam_val_stock") for x in sqls[key])
sqls["Q1x same as Q1 NOT INDEXED (full scan)"]=(sqls["Q1 cap 10uF+-1% 0805 X7R >=25V"][0].replace("FROM part_index","FROM part_index NOT INDEXED"),sqls["Q1 cap 10uF+-1% 0805 X7R >=25V"][1].replace("FROM part_index","FROM part_index NOT INDEXED"))
res={}
for k,(sel,cnt) in sqls.items():
    r={"sql":sel,"count_sql":cnt}
    r["plan"]=[x[3] for x in c.execute("EXPLAIN QUERY PLAN "+sel)]
    r["count_plan"]=[x[3] for x in c.execute("EXPLAIN QUERY PLAN "+cnt)]
    for nm,sql in (("select",sel),("count",cnt)):
        c2=conn(); evict(P)
        if k.startswith("Q5"): evict("/var/tmp/kina-bench/parts-fts5.db")
        t=time.perf_counter(); rows=c2.execute(sql).fetchall(); first=time.perf_counter()-t
        ws=[]
        for _ in range(7):
            t=time.perf_counter(); c2.execute(sql).fetchall(); ws.append(time.perf_counter()-t)
        r[nm]=dict(first_s=first,warm_median_s=statistics.median(ws),warm_min=min(ws),warm_max=max(ws),rows=len(rows) if nm=="select" else rows[0][0])
    res[k]=r; print(k,{a:r[a] for a in("select","count","plan","count_plan")},flush=True)
json.dump(res,open("../res_fq.json","w"),indent=1)

```

### pi_refresh.py: Refresh B (part_index), C, D, VACUUM

```python
import sqlite3, time, os, json, threading, statistics, sys, shutil
sys.path.insert(0,'.')
D="/var/tmp/kina-bench/sqlite/"; P=D+"part_index.db"; N=D+"new_pi.db"
res={}
def sz(p): return os.path.getsize(p) if os.path.exists(p) else 0
def free_gb(): s=os.statvfs(D); return s.f_bavail*s.f_frsize/1e9
def guard():
    if free_gb()<4.5: raise SystemExit("free space below limit: %.1f GB"%free_gb())
class Mon(threading.Thread):
    def __init__(s,paths):
        super().__init__(daemon=True); s.paths=paths; s.stop=False; s.peak={}; s.min_free=1e9
    def run(s):
        while not s.stop:
            for p in s.paths: s.peak[p]=max(s.peak.get(p,0),sz(p))
            s.min_free=min(s.min_free,free_gb())
            if free_gb()<4.0: os._exit(3)
            time.sleep(0.25)
Q1=("SELECT rowid,lcsc,value_num,voltage_v,stock_int FROM part_index WHERE family='capacitor' AND value_num BETWEEN 9.9e-6 AND 10.1e-6 "
    "AND package='0805' AND dielectric='X7R' AND (voltage_v IS NULL OR voltage_v >= 25) AND stock_int > 0 ORDER BY stock_int DESC LIMIT 40")
class Reader(threading.Thread):
    def __init__(s,path,sql,period=0.2):
        super().__init__(daemon=True); s.path=path; s.sql=sql; s.period=period; s.stop=False; s.log=[]
    def run(s):
        c=sqlite3.connect(s.path,timeout=600); t00=time.time()
        while not s.stop:
            t=time.perf_counter(); ts=time.time()-t00
            try: n=len(c.execute(s.sql).fetchall()); err=None
            except Exception as e: n=-1; err=str(e)
            lat=time.perf_counter()-t; s.log.append((ts,lat,n,err))
            time.sleep(max(0,s.period-lat))
    def summary(s):
        l=[x[1] for x in s.log]; 
        gaps=[b[0]-a[0] for a,b in zip(s.log,s.log[1:])]
        return dict(reads=len(l),max_latency_s=max(l),median_latency_s=statistics.median(l),max_gap_between_reads_s=max(gaps) if gaps else None,
                    p95=sorted(l)[int(.95*len(l))-1] if len(l)>1 else None,errors=sum(1 for x in s.log if x[3]),
                    slow=[(round(a,2),round(b,3)) for a,b,_,_ in s.log if b>0.5][:10])
# --- B for part_index: lean new_pi.db
if os.path.exists(N): os.remove(N)
c=sqlite3.connect(P); c.execute("ATTACH ? AS n",(N,))
c.execute("""CREATE TABLE n.part_index(rowid INTEGER PRIMARY KEY, lcsc TEXT, family TEXT, value_num REAL, voltage_v REAL,
 tolerance_pct REAL, power_w REAL, package TEXT, dielectric TEXT, mounting TEXT, technology TEXT, stock_int INTEGER)""")
t=time.time(); c.execute("INSERT INTO n.part_index SELECT * FROM main.part_index"); c.commit(); res["new_pi_build_s"]=time.time()-t
res["new_pi_size"]=sz(N); c.close(); guard()
print("new_pi built",res,flush=True)
c=sqlite3.connect(P,isolation_level=None); c.execute("ATTACH ? AS n",(N,))
base=sz(P); mon=Mon([P,P+"-journal",P+"-wal"]); mon.start()
t=time.time(); c.execute("BEGIN"); c.execute("DELETE FROM main.part_index"); td=time.time()-t
c.execute("INSERT INTO main.part_index SELECT * FROM n.part_index"); ti=time.time()-t-td
t2=time.time(); c.execute("COMMIT"); tc=time.time()-t2
mon.stop=True; time.sleep(.5)
res["B_pi"]=dict(mode="rollback journal",rows=7146764,delete_s=td,insert_s=ti,commit_s=tc,total_s=td+ti+tc,peak_journal=mon.peak.get(P+"-journal"),peak_db=mon.peak.get(P),db_before=base,db_after=sz(P),min_free_gb=mon.min_free)
print(res["B_pi"],flush=True); guard()
c.execute("DETACH n")
# --- C: generation column
c.execute("ALTER TABLE part_index ADD COLUMN generation INTEGER NOT NULL DEFAULT 1")
t=time.time(); c.execute("CREATE INDEX ix_gen ON part_index(generation)"); res["ix_gen_build_s"]=time.time()-t
res["size_after_gen_col_and_index"]=sz(P)
def gen_swap(label,old,new):
    c=sqlite3.connect(P,isolation_level=None); c.execute("ATTACH ? AS n",(N,))
    off=new*16777216
    before=sz(P); mon=Mon([P,P+"-journal",P+"-wal"]); mon.start()
    rd=Reader("file:"+P+"?mode=ro" if False else P,Q1); rd.start(); time.sleep(0.8)
    t0=time.time(); c.execute("BEGIN")
    c.execute(f"INSERT INTO main.part_index(rowid,lcsc,family,value_num,voltage_v,tolerance_pct,power_w,package,dielectric,mounting,technology,stock_int,generation) SELECT rowid+{off},lcsc,family,value_num,voltage_v,tolerance_pct,power_w,package,dielectric,mounting,technology,stock_int,{new} FROM n.part_index")
    ti=time.time()-t0; t1=time.time()
    c.execute("DELETE FROM main.part_index WHERE generation=?",(old,)); td=time.time()-t1
    t2=time.time(); c.execute("COMMIT"); tc=time.time()-t2; total=time.time()-t0
    time.sleep(1.0); rd.stop=True; rd.join(); mon.stop=True; time.sleep(.4)
    r=dict(label=label,insert_s=ti,delete_s=td,commit_s=tc,total_s=total,db_before=before,db_after=sz(P),peak_db=mon.peak.get(P),peak_journal=mon.peak.get(P+"-journal"),peak_wal=mon.peak.get(P+"-wal"),
           min_free_gb=mon.min_free,reader=rd.summary(),txn_window=(round(0.8,2),round(0.8+total,2)),reader_log_big=[(round(a,2),round(b,3)) for a,b,_,_ in rd.log if b>0.1],
           rows_after=c.execute("select count(*),min(generation),max(generation) from part_index").fetchone())
    c.close(); return r
res["C_rollback"]=gen_swap("rollback journal, default cache",1,2); print(res["C_rollback"],flush=True); guard()
json.dump(res,open(D+"res_pi_refresh_partial.json","w"),indent=1,default=str)
# VACUUM after deletes (rollback mode)
c=sqlite3.connect(P,isolation_level=None)
b=sz(P); fp=c.execute("PRAGMA freelist_count").fetchone()[0]; pc=c.execute("PRAGMA page_count").fetchone()[0]
mon=Mon([P,P+"-journal",P+"-wal",P+"-journal"]); mon.start(); t=time.time(); c.execute("VACUUM"); vt=time.time()-t; mon.stop=True; time.sleep(.4)
res["vacuum_rollback"]=dict(seconds=vt,before=b,after=sz(P),freelist_pages=fp,page_count=pc,peak_extra=mon.peak.get(P+"-journal"),min_free_gb=mon.min_free)
print(res["vacuum_rollback"],flush=True); guard()
# WAL
print(c.execute("PRAGMA journal_mode=WAL").fetchall()); c.close()
res["C_wal"]=gen_swap("WAL, default cache",2,3); print(res["C_wal"],flush=True); guard()
c=sqlite3.connect(P,isolation_level=None)
wal_before=sz(P+"-wal"); t=time.time(); ck=c.execute("PRAGMA wal_checkpoint(TRUNCATE)").fetchall(); res["wal_checkpoint"]=dict(seconds=time.time()-t,result=ck,wal_before=wal_before,wal_after=sz(P+"-wal"),db=sz(P))
b=sz(P); t=time.time(); c.execute("VACUUM"); vt=time.time()-t
res["vacuum_wal"]=dict(seconds=vt,before=b,after=sz(P),wal_after=sz(P+"-wal"))
c.execute("PRAGMA wal_checkpoint(TRUNCATE)"); res["vacuum_wal"]["wal_after_ckpt"]=sz(P+"-wal"); res["vacuum_wal"]["after_ckpt"]=sz(P)
print(res["vacuum_wal"],flush=True)
json.dump(res,open(D+"res_pi_refresh.json","w"),indent=1,default=str)

```

### pi_e.py: Refresh variant E (drop/rebuild indexes in the transaction; loads the Mon/Reader classes from pi_refresh.py)

```python
import sqlite3,time,os,json,sys; sys.path.insert(0,'.')
exec(open('pi_refresh.py').read().split("# --- B for part_index")[0].split("res={}")[0])
D="/var/tmp/kina-bench/sqlite/"; P=D+"part_index.db"; N=D+"new_pi.db"
def sz(p): return os.path.getsize(p) if os.path.exists(p) else 0
import threading, statistics
src=open('pi_refresh.py').read()
ns={}
exec(src[src.index("class Mon"):src.index("# --- B for part_index")],{**globals(),'free_gb':lambda:(os.statvfs(D).f_bavail*os.statvfs(D).f_frsize/1e9),'sz':sz},ns)
Mon,Reader,Q1=ns['Mon'],ns['Reader'],ns['Q1']
c=sqlite3.connect(P,isolation_level=None); c.execute("ATTACH ? AS n",(N,))
idx=[r for r in c.execute("select name,sql from sqlite_master where type='index' and tbl_name='part_index' and sql is not null")]
print(idx)
mon=Mon([P,P+"-wal"]); mon.start(); rd=Reader(P,Q1); rd.start(); time.sleep(.8)
before=sz(P); t0=time.time(); c.execute("BEGIN")
for n,s in idx: c.execute(f"DROP INDEX {n}")
t1=time.time(); c.execute("DELETE FROM main.part_index"); t2=time.time()
c.execute("INSERT INTO main.part_index(rowid,lcsc,family,value_num,voltage_v,tolerance_pct,power_w,package,dielectric,mounting,technology,stock_int) SELECT rowid,lcsc,family,value_num,voltage_v,tolerance_pct,power_w,package,dielectric,mounting,technology,stock_int FROM n.part_index"); t3=time.time()
for n,s in idx: c.execute(s)
t4=time.time(); c.execute("COMMIT"); t5=time.time()
time.sleep(1); rd.stop=True; rd.join(); mon.stop=True; time.sleep(.4)
r=dict(drop_idx_s=t1-t0,delete_s=t2-t1,insert_s=t3-t2,recreate_idx_s=t4-t3,commit_s=t5-t4,total_s=t5-t0,db_before=before,db_after=sz(P),peak_wal=mon.peak.get(P+"-wal"),reader=rd.summary(),min_free=mon.min_free)
print(r); 
b=sz(P); c.execute("PRAGMA wal_checkpoint(TRUNCATE)"); c.execute("ANALYZE"); r["db_after_ckpt"]=sz(P)
json.dump(r,open(D+"res_pi_e.json","w"),indent=1,default=str)

```

### subsets.py: Subset and plain_subset builds

```python
import sqlite3,time,os,json
D="/var/tmp/kina-bench/sqlite/"
SRC="file:/var/tmp/kina-bench/parts-fts5.db?mode=ro"
COLS=['LCSC Part','First Category','Second Category','MFR.Part','Package','Solder Joint','Manufacturer','Library Type','Description','Datasheet','Price','Stock']
sel=",".join('"%s"'%c for c in COLS)
FTS="""CREATE VIRTUAL TABLE parts using fts5 (
        'LCSC Part','First Category','Second Category','MFR.Part','Package','Solder Joint' unindexed,'Manufacturer','Library Type','Description','Datasheet' unindexed,'Price' unindexed,'Stock' unindexed, tokenize="trigram")"""
res={}
for f in ("plain_all.db","plain_subset.db","subset.db","new.db","new2.db"):
    if os.path.exists(D+f): os.remove(D+f)
c=sqlite3.connect(D+"plain_all.db"); c.execute("ATTACH ? AS s",(SRC,)) if False else None
c.close()
c=sqlite3.connect(":memory:",uri=True); c.close()
def attach_src(c):
    c.execute("ATTACH DATABASE ? AS s",(SRC,))
# plain_all: residues 0,1,2 in one scan
c=sqlite3.connect("file:"+D+"plain_all.db",uri=True); attach_src(c)
c.execute("CREATE TABLE parts(src_rowid INTEGER, "+",".join('"%s"'%x for x in COLS)+")")
t=time.time(); c.execute(f"INSERT INTO parts SELECT rowid,{sel} FROM s.parts WHERE rowid % 14 IN (0,1,2)"); c.commit(); res["plain_all_scan_s"]=time.time()-t
res["plain_all_rows"]=c.execute("select count(*) from parts").fetchone()[0]; c.close(); print(res,flush=True)
def build_fts(name,resid):
    c=sqlite3.connect(D+name); c.execute("ATTACH DATABASE ? AS a",(D+"plain_all.db",)); c.execute(FTS)
    t=time.time(); c.execute(f"INSERT INTO parts(rowid,{sel}) SELECT src_rowid,{sel} FROM a.parts WHERE src_rowid % 14 = {resid}"); c.commit(); dt=time.time()-t
    n=c.execute("select count(*) from parts").fetchone()[0]; c.close(); return dict(build_s=dt,rows=n,size=os.path.getsize(D+name))
for name,r in (("subset.db",0),("new.db",1),("new2.db",2)):
    res[name]=build_fts(name,r); print(name,res[name],flush=True)
# plain subset = same rows as subset.db, regular table, all columns as in the source
c=sqlite3.connect(D+"plain_subset.db"); c.execute("ATTACH DATABASE ? AS a",(D+"plain_all.db",))
c.execute("CREATE TABLE parts("+",".join('"%s"'%x for x in COLS)+")")
t=time.time(); c.execute(f"INSERT INTO parts SELECT {sel} FROM a.parts WHERE src_rowid % 14 = 0"); c.commit(); res["plain_subset"]=dict(build_s=time.time()-t,rows=c.execute("select count(*) from parts").fetchone()[0],size=os.path.getsize(D+"plain_subset.db")); c.close()
print(res["plain_subset"])
os.remove(D+"plain_all.db")
for name in ("subset.db","new.db","plain_subset.db"):
    c=sqlite3.connect(D+name); res["dbstat_"+name]=c.execute("select name,sum(pgsize),sum(unused) from dbstat group by name order by 2 desc").fetchall(); c.close()
    print(name,res["dbstat_"+name])
json.dump(res,open(D+"res_subsets.json","w"),indent=1)

```

### fts_refresh.py: Refresh B (FTS) with reader loop; fts_refresh2.py/fts_refresh3.py are the re-runs of the DROP/CREATE variants after fixing a lock left by the first script

```python
import sqlite3,time,os,json,sys,threading,statistics
sys.path.insert(0,'.')
src=open('pi_refresh.py').read()
D="/var/tmp/kina-bench/sqlite/"
def sz(p): return os.path.getsize(p) if os.path.exists(p) else 0
def free_gb(): s=os.statvfs(D); return s.f_bavail*s.f_frsize/1e9
ns={}; exec(src[src.index("class Mon"):src.index("Q1=(")]+src[src.index("class Reader"):src.index("# --- B for part_index")],{'threading':threading,'time':time,'os':os,'sqlite3':sqlite3,'statistics':statistics,'free_gb':free_gb,'sz':sz},ns)
Mon,Reader=ns['Mon'],ns['Reader']
P=D+"subset.db"
RQ="""SELECT "LCSC Part",Description FROM parts WHERE parts MATCH '("10uF") AND ("X7R") AND ("0805")' AND CAST("Stock" AS INTEGER) > 0 ORDER BY rank, CAST("Stock" AS INTEGER) DESC LIMIT 200"""
FACTOR=7146764/510483
res={"factor":FACTOR}
def run(label,newdb,mode,variant):
    c=sqlite3.connect(P,isolation_level=None)
    c.execute(f"PRAGMA journal_mode={mode}"); c.execute("ATTACH ? AS n",(D+newdb,))
    before=sz(P); mon=Mon([P,P+"-journal",P+"-wal"]); mon.start(); rd=Reader(P,RQ); rd.start(); time.sleep(.8)
    t0=time.time(); c.execute("BEGIN"); err=None
    t1=t0
    if variant=="delete":
        c.execute("DELETE FROM main.parts"); t1=time.time()
        c.execute("INSERT INTO main.parts SELECT * FROM n.parts"); t2=time.time()
    elif variant=="delete-all":
        c.execute("INSERT INTO main.parts(parts) VALUES('delete-all')"); t1=time.time()
        c.execute("INSERT INTO main.parts SELECT * FROM n.parts"); t2=time.time()
    elif variant=="drop-create":
        c.execute("DROP TABLE main.parts")
        c.execute(open('subsets.py').read().split('FTS="""')[1].split('"""')[0].join(["CREATE VIRTUAL TABLE main.parts using fts5 (","" ]) if False else None) if False else None
        c.execute("""CREATE VIRTUAL TABLE main.parts using fts5 ('LCSC Part','First Category','Second Category','MFR.Part','Package','Solder Joint' unindexed,'Manufacturer','Library Type','Description','Datasheet' unindexed,'Price' unindexed,'Stock' unindexed, tokenize="trigram")"""); t1=time.time()
        c.execute("INSERT INTO main.parts SELECT * FROM n.parts"); t2=time.time()
    c.execute("COMMIT"); t3=time.time()
    time.sleep(1); rd.stop=True; rd.join(); mon.stop=True; time.sleep(.4)
    n=c.execute("select count(*) from parts").fetchone()[0]
    r=dict(label=label,variant=variant,mode=mode,delete_s=t1-t0,insert_s=t2-t1,commit_s=t3-t2,total_s=t3-t0,extrapolated_total_s=(t3-t0)*FACTOR,
           rows_after=n,db_before=before,db_after=sz(P),peak_journal=mon.peak.get(P+"-journal"),peak_wal=mon.peak.get(P+"-wal"),min_free_gb=mon.min_free,reader=rd.summary())
    if mode=="WAL": c.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    c.close(); print(r,flush=True); return r
for label,newdb,mode,variant in (("rollback, DELETE+INSERT","new.db","DELETE","delete"),("WAL, DELETE+INSERT","new2.db","WAL","delete"),
        ("WAL, delete-all+INSERT","new.db","WAL","delete-all"),("WAL, DROP+CREATE+INSERT","new2.db","WAL","drop-create"),("rollback, DROP+CREATE+INSERT","new.db","DELETE","drop-create")):
    try: res[label]=run(label,newdb,mode,variant)
    except Exception as e: res[label]=dict(error=str(e)); print(label,"ERROR",e,flush=True)
    try: c=sqlite3.connect(P); print(c.execute("pragma integrity_check").fetchone()[0] if False else "", end=""); c.close()
    except Exception as e: print(e)
# VACUUM on the subset for reference
c=sqlite3.connect(P,isolation_level=None); c.execute("PRAGMA journal_mode=DELETE"); b=sz(P); t=time.time(); c.execute("VACUUM"); res["vacuum_subset"]=dict(seconds=time.time()-t,before=b,after=sz(P))
print(res["vacuum_subset"])
json.dump(res,open(D+"res_fts_refresh.json","w"),indent=1,default=str)

```

### nulls.py: NULL rates

```python
import sqlite3, json
c=sqlite3.connect("/var/tmp/kina-bench/sqlite/part_index.db")
cols=["lcsc","family","value_num","voltage_v","tolerance_pct","power_w","package","dielectric","mounting","technology","stock_int"]
n=c.execute("select count(*) from part_index").fetchone()[0]
res={"rows":n,"in_stock":c.execute("select count(*) from part_index where stock_int>0").fetchone()[0],"cols":{}}
for col in cols:
    nn,d=c.execute(f"select count({col}),count(distinct {col}) from part_index").fetchone()
    res["cols"][col]=dict(non_null=nn,null_pct=round(100*(n-nn)/n,2),distinct=d)
res["fam"]=[]
for r in c.execute("""select coalesce(family,'(null)'),count(*),sum(stock_int>0),
 round(100.0*sum(value_num is null)/count(*),1),round(100.0*sum(voltage_v is null)/count(*),1),round(100.0*sum(tolerance_pct is null)/count(*),1),
 round(100.0*sum(package is null)/count(*),1),round(100.0*sum(dielectric is null)/count(*),1),round(100.0*sum(mounting is null)/count(*),1) from part_index group by 1 order by 2 desc"""): res["fam"].append(r)
res["fam_instock"]=[]
for r in c.execute("""select coalesce(family,'(null)'),count(*),
 round(100.0*sum(value_num is null)/count(*),1),round(100.0*sum(voltage_v is null)/count(*),1),round(100.0*sum(tolerance_pct is null)/count(*),1),
 round(100.0*sum(package is null)/count(*),1),round(100.0*sum(dielectric is null)/count(*),1),round(100.0*sum(mounting is null)/count(*),1) from part_index where stock_int>0 group by 1 order by 2 desc"""): res["fam_instock"].append(r)
json.dump(res,open("../res_nulls.json","w"),indent=1)
print(json.dumps(res,indent=1))

```

### Inline snippets (run from the shell)

Rename/reopen (A): `os.replace(new.bin, old.bin)` and `os.rename(old.bin, moved.bin)` on 1 GiB files of random data written with `os.urandom(1 MiB)` chunks and fsync, `time.perf_counter()` around each; reopen = `sqlite3.connect('file:...parts-fts5.db?mode=ro', uri=True)` + `SELECT "LCSC Part" FROM parts LIMIT 1`; validation = `SELECT count(*) FROM parts` after `posix_fadvise(DONTNEED)`.
