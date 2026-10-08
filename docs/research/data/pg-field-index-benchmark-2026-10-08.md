# KINA field-based part index in PostgreSQL 17: benchmark report

Date: 2026-10-08. All files are in `/var/tmp/kina-bench/pg/` (scripts, logs, JSON results, plans `plan_Q*.txt`).

## 1. Setup

| Item | Value |
|---|---|
| Host | 24 cores, 30 GB RAM, NVMe (one partition shared by `/var/tmp` and Docker, 28-30 GB free). **The host was under memory pressure and ran a concurrent SQLite benchmark**: `free -g` during the runs showed 16-19 GB used, 1-9 GB free, 22-24 GB of 30 GB swap in use. Timings are noisy (see write overhead, where the same test differed 2x between runs). |
| Postgres | 17.11 (`postgres:17-alpine`, musl), container `kina-pgbench`, `127.0.0.1:55432`, volume `kina-pgbench-data` |
| Settings | `shared_buffers=2GB`, `work_mem=128MB`, `maintenance_work_mem=1GB`, `max_wal_size=4GB`, `effective_cache_size=8GB`, `max_parallel_maintenance_workers=4`, `track_io_timing=on`, shm 1 GB. (Only the table load (COPY) ran with the originally requested 4GB/256MB/2GB/8GB set; the container was then recreated with the reduced memory limits on the coordinator's instruction. All indexes, queries, writes and refreshes ran with the reduced set.) |
| Source | SQLite FTS5 `parts-fts5.db`, 7 146 764 rows, 5 329 715 200 bytes |
| Extensions | pg_trgm, pgstattuple |

## 2. Export (SQLite to CSV, Python 3.14, regex extraction)

12 worker processes over 48 rowid ranges. Wall time 5.4 s (first export, 1.66 GB CSV, 7 146 764 rows; a second export for the refresh tests took 9.2 s under load). CSV size 1 656 425 180 bytes.

| Column | NULL/empty % | | Column | NULL/empty % |
|---|---|---|---|---|
| family | 0 | | tolerance_pct | 72.68 |
| mpn | 0 | | power_w (resistors only) | 85.23 |
| manufacturer | 0.00 | | dielectric (capacitors only) | 96.22 |
| description | 6.37 | | mounting | 67.59 |
| category1 / category2 | 3.83 / 3.84 | | technology | 71.87 |
| package | 0 | | price_text | 88.84 |
| value_num | 74.34 | | stock_int (0 is stored as 0) | 0 |
| voltage_v | 76.37 | | attributes (JSONB) | 0 (`{}` when empty) |

Family counts and rows with stock_int > 0: other 2 551 451 / 308 785; connector 1 702 538 / 143 637; resistor 1 412 068 / 117 673; capacitor 987 250 / 64 688; inductor 329 800 / 46 675; diode 163 657 / 42 407. Only about 10% of rows are in stock, which makes the `stock_int > 0` partial index effective.

## 3. Load

| Metric | Value |
|---|---|
| `COPY ... FROM PROGRAM 'cat chunks'` with the primary key in place | 27.08 s (264 000 rows/s) |
| Heap after load (`pg_relation_size`) | 1 899 036 672 bytes (1811 MB) |
| PK index | 335 396 864 bytes (320 MB) |
| `pg_total_relation_size` after load | 2 234 925 056 bytes (2131 MB) |

## 4. Indexes (one at a time, after the load; heap 1811 MB)

| # | Index | Build time | Size |
|---|---|---|---|
| 1 | btree (family, value_num) | 1.38 s | 254 MB |
| 2 | btree (family, package) | 1.95 s | 52 MB |
| 3 | btree (value_num) | 0.73 s | 153 MB |
| 4 | btree (voltage_v) | 0.73 s | 153 MB |
| 5 | btree (tolerance_pct) | 0.72 s | 153 MB |
| 6a | partial btree (value_num) WHERE family='capacitor' | 0.20 s | 21 MB |
| 6b | partial btree (value_num) WHERE family='resistor' | 0.25 s | 30 MB |
| 7 | GIN attributes jsonb_path_ops | 2.33 s | 37 MB |
| 8a | GIN to_tsvector('simple', description) (expression) | 14.62 s | 73 MB |
| 9a | pg_trgm GIN description | 21.60 s | 253 MB |
| 9b | pg_trgm GIN mpn | 15.88 s | 271 MB |
| 10 | partial btree (family, value_num) WHERE stock_int > 0 | 0.26 s | 25 MB |
| | PK (distributor, part_number) | (during load) | 320 MB |
| | ANALYZE | 0.32 s | |

The builds are fast because most columns are NULL (btree dedup) and the descriptions are short and repetitive.

8b, generated tsvector column alternative (measured on a separate 2-column copy of all 7.1 M rows, not on part_index): copying the rows 11.9 s; `ADD COLUMN tsv tsvector GENERATED ALWAYS AS (to_tsvector('simple', description)) STORED` rewrote the table in 15.5 s and grew the heap from 507 MB to 1031 MB (+524 MB for the stored tsvector); the GIN on it built in 5.5 s and is 70 MB (same size as the expression index). Conclusion: the expression index costs no heap but recomputes `to_tsvector` at build time and on recheck; the stored column costs about 0.5 GB of heap here and makes the GIN build 2.7x faster. Query speed is equal.

Disk totals after all indexes: `pg_total_relation_size` 3 782 598 656 bytes (3.78 GB; heap 1.90 GB, indexes 1.88 GB including PK) versus the raw CSV 1.66 GB: 2.28x the CSV, indexes alone are 99% of the heap size.

## 5. Query latency

`EXPLAIN (ANALYZE, BUFFERS)`, first run and median of 5 warm runs (`Execution Time`). "First" is not cold: the table had just been built and parts were in the OS cache and shared buffers, so first and warm are close. JIT was on (default; about 6 ms of JIT overhead shows in Q1 plans). "Rows" is the rows returned by the top node (40 = LIMIT reached). Plans are in `plan_Q*.txt`.

| Query | First ms | Warm median ms (min-max) | Rows | Index used | Buffers, first | Buffers, warm |
|---|---|---|---|---|---|---|
| Q1 all-match, `abs(value_num-10e-6)<=...` | 64.3 | 47.3 (46.6-51.5) | 5 | idx_fam_package (bitmap, then 88 434 heap rows filtered) | hit 11805 read 8456 | hit 20261 |
| Q1s Q1 with sargable `value_num BETWEEN 9.9e-6 AND 10.1e-6` (extra) | 5.8 | 3.8 (3.76-3.89) | 5 | idx_fam_value_instock | hit 1055 read 900 | hit 1955 |
| Q2 no dielectric | 46.6 | 47.2 (47.0-50.9) | 40 | idx_fam_package | hit 20261 | hit 20261 |
| Q3 no package | 53.0 | 48.6 (43.3-50.6) | 40 | idx_fam_value_instock | hit 8479 read 5238 | hit 13717 |
| Q4 resistor 4.7k 1% 0603 | 107.9 | 64.0 (59.6-74.2) | 40 | idx_fam_package | hit 8468 read 23106 | hit 31574 |
| Q5 FTS "thin film" + resistor + 5.36k | 35.0 | 34.6 (31.8-36.0) | 15 | idx_desc_fts + idx_fam_value_instock (BitmapAnd) | hit 3331 read 993 | hit 4324 |
| Q5b FTS only, count (266 245 matches) | 77.9 | 79.8 | 1 | idx_desc_fts | hit 28629 read 923 | hit 29552 |
| Q6a `description ILIKE '%X7R%'` count (104 989) | 73.2 | 70.0 | 1 | idx_desc_trgm | hit 20256 read 1433 | hit 21689 |
| Q6a2 same, stock>0, LIMIT 40 | 41.4 | 37.3 | 40 | idx_desc_trgm + idx_fam_value_instock | hit 4535 read 2282 | hit 6817 |
| Q6b `description % 'X7R'` count (3 968) | 126.2 | 123.3 | 1 | idx_desc_trgm | hit 21859 read 83 | hit 21942 |
| Q6c `mpn ILIKE '%ERA6AEB%'` count (110) | 0.45 | 0.47 | 1 | idx_mpn_trgm | hit 48 | hit 48 |
| Q6c2 same, LIMIT 40 | 0.52 | 0.50 | 40 | idx_mpn_trgm | hit 51 | hit 51 |
| Q7 `attributes @> {"Dielectric":"X7R"}` + value | 34.7 | 35.1 | 40 | idx_attrs_gin + idx_fam_value_instock | hit 6799 read 17 | hit 6816 |
| Q7b `@> {"Dielectric":"X7R","Capacitance":"10uF"}` (extra, exact string match) | 3.6 | 3.4 | 40 | idx_attrs_gin | hit 824 read 3 | hit 827 |
| Q8 `count(*)` of Q1 without LIMIT (true total: 5) | 40.9 | 41.5 | 1 | idx_fam_package | hit 20258 | hit 20258 |
| Q9 Q1 + `(tolerance_pct IS NULL OR <=10)` + `(power_w IS NULL OR >=0.1)` | 47.8 | 49.0 | 5 | idx_fam_package | hit 20261 | hit 20261 |

Findings:
- Every query is under 130 ms warm, most under 65 ms, with no sequential scan. The planner never used the (family, value_num) indexes for `abs(value_num-x)<=y` because it is not sargable. Q1 reads 88 434 capacitors of package 0805 and filters them. Writing the tolerance as a range (`BETWEEN`, Q1s) makes it 12x faster (3.8 ms) and uses the partial in-stock index.
- `IS NULL OR` predicates (Q1, Q9) are never index conditions; they are filters on the rows found by other predicates, so they cost nothing extra here (Q9 equals Q1).
- Q1 to Q3 are bound by heap visits to rows that fail the filter (hit 13 717 to 20 261 buffers, all shared hits warm).
- The tsvector and jsonb GIN indexes are small (73 and 37 MB) and fast to combine with btrees.

## 6. Write overhead (Mouser/TME cache path)

200 batches of 50 rows (one multi-row `INSERT ... ON CONFLICT (distributor, part_number) DO UPDATE SET all non-key columns` per transaction, psycopg 3, autocommit off per batch via `with c.transaction()`), distributor 'MOUSER', rows copied from real LCSC rows (fresh part numbers: inserts; then the same 200 batches with changed stock/price: updates). Latency is the full transaction including commit. WAL from `pg_current_wal_lsn()` deltas.

| Run | Phase | Median ms | p95 ms | p99 ms | Max ms | Commit median ms | WAL bytes/batch median (mean) |
|---|---|---|---|---|---|---|---|
| All 13 indexes, run 1 | insert | 8.0 | 20.8 | 50.3 | 72.4 | 2.5 | 260 160 (400 729) |
| All 13 indexes, run 1 | update | 6.6 | 10.3 | 52.0 | 171.1 | 2.3 | 153 120 (571 386) |
| PK only | insert | 8.5 | 10.9 | 14.9 | 20.1 | 5.9 | 32 080 (31 151) |
| PK only | update | 9.2 | 11.6 | 15.2 | 21.4 | 6.6 | 28 220 (28 285) |
| All 13 indexes, run 2 (rebuilt, host busier) | insert | 14.7 | 34.6 | 94.8 | 113.3 | 7.2 | 260 552 (408 060) |
| All 13 indexes, run 2 | update | 14.8 | 29.7 | 126.0 | 371.8 | 7.4 | 153 252 (579 189) |

- Index maintenance costs about 8x the WAL (260 KB vs 32 KB per insert batch of 50 rows; 153 KB vs 28 KB per update batch) and a heavier tail (p95 10-35 ms vs 11-12 ms). The median latency is dominated by the commit fsync on this loaded host: PK-only was not faster in the median than run 1 and was faster than run 2. The two all-index runs differ 2x, which is host noise (swap, concurrent benchmark), so treat medians within a factor 2 as equal.
- The mean WAL per batch is higher than the median because the first modification of a page after a checkpoint writes a full-page image.
- Bloat after the updates (`pgstattuple_approx`, table 1.9 GB): dead tuples 6 721 to 6 968, 0.14-0.15% of tuples, free space 0.8-1.5%. `pg_stat_user_tables`: n_tup_hot_upd 0 with all indexes (updated columns are in index predicates), 620 to 1 158 HOT updates in the later cumulative reads (PK only). Autovacuum count 0 (the dead tuple count stays below the 50 + 20% threshold of a 7 M row table); the Mouser rows were deleted and `VACUUM`ed between runs by hand. The counters are cumulative over runs, so only the pgstattuple lines are per run.
- For the real cache path (50-row batches, one per search), the write cost is a few ms at the median and under 100 ms at p99 even with 13 indexes.

## 7. Atomic refresh (LCSC full reload)

A concurrent Python reader (psycopg, a prepared statement, Q1 every 200 ms, with `tableoid` to see which table answers) ran during every refresh. "Reader blocked" is the longest single read latency; baseline read is 30 to 40 ms. Peak extra disk is the peak of `du -sm /var/lib/postgresql/data` (data plus WAL) minus the start value, sampled every 0.5 s. Sizes include the old table (3.8 GB) until it is dropped.

| Approach | Wall time | of which swap | Reader blocked (max read latency) | Reader errors | Peak extra disk | Notes |
|---|---|---|---|---|---|---|
| A: new table, COPY (17.5 s), PK (9.1 s), 12 indexes (about 64 s), ANALYZE (0.3 s), rename swap in one transaction, drop old (0.6 s) | 94.0 s | 0.03 s | 197 ms (median 38 ms; longest gap between read starts 0.48 s) | 0 | 3.5 GB | Reader switched tables at t=93.5 s without error |
| B: partitioned by `generation`, build part_g2 standalone with CHECK, COPY (27.9 s), PK (18.8 s), indexes (about 71 s), ANALYZE; ATTACH 0.01 s; DETACH CONCURRENTLY 0.01 s; drop old 0.4 s | 116.4 s | 0.02 s | 206 ms (longest gap 0.31 s) | 0 | 3.6 GB | Read duplicates were possible only between ATTACH and DETACH; none observed (window about 10 ms) |
| B2: same, atomic `BEGIN; DETACH old; ATTACH new; COMMIT` | 129.2 s | 0.01 s | 246 ms (longest gap 0.40 s) | 0 | 3.6 GB | No duplicate window at all |
| C: `BEGIN; TRUNCATE; COPY; COMMIT` with 13 indexes in place | 209.2 s | whole copy is under the lock | **209.7 s** (one read waited on the ACCESS EXCLUSIVE lock for the whole load) | 0 | 3.7 GB | COPY with indexes in place took 206 s (about 3x slower than A's COPY plus builds) |

- A and B give no practical reader interruption: the maximum read latency (200 to 250 ms) is the usual noise (6 ms JIT and a loaded host), not lock waits. C blocks every reader for the full load (3.5 minutes) and, being one transaction, keeps old files until commit.
- ANALYZE after the swap: 0.3 s on the new standalone table (A: done before the swap, so the new table has statistics when it becomes visible; skipping it leaves the new table without statistics until autovacuum analyzes it). C: ANALYZE 2.7 s after commit (readers run with stale stats, though row estimates are almost the same). B: `ANALYZE part_g2` 0.3 s before attach; ANALYZE of the partitioned parent (not done by autovacuum, ever) 0.55 s.
- Partitioned primary key must include the partition key: `(generation, distributor, part_number)`, so uniqueness of `(distributor, part_number)` across generations is not enforced. Matching indexes built on the new table before ATTACH are adopted (attach 0.01 s, with the CHECK constraint the scan is skipped).
- Prepared statements and plan cache (also observed here: the reader used a server-side prepared statement for the whole run, with zero errors and a switch to the new table's oid): Postgres plan cache stores the parse tree and re-plans or re-analyzes when a relation it depends on changes (relcache invalidation on rename, drop or index change, PostgreSQL docs on `PREPARE` and "Plan Caching"). After `RENAME` swaps, the same statement resolves `part_index` to the new table at the next execution, with no client action. Caveat: a `SELECT *` prepared statement can fail with "cached plan must not change result type" if columns change. Statements that name columns survive a swap as long as the new table has the same columns. Pgjdbc and Spring `JdbcClient` need no special handling.
- Cost of a refresh every 5 days: about 1.5 to 2 minutes of CPU and I/O and 3.5 GB of transient disk. Approach A drops the old table, so no vacuum or bloat is left; C (truncate) leaves none either, but blocks.

## 8. Caveats

- Extraction fidelity: values come from simple regexes over the LCSC description text. Cardinalities and NULL rates are realistic but not exact (about 74% of rows have no value, since non-passive parts and unparsed units leave NULL). `family` is derived from category words; the `other` class (36%) hides real families. Q1 matches only 5 rows; real KINA queries could be more or less selective, which changes LIMIT early-exit behavior.
- Warm cache: the machine was never cold. `drop_caches` was not allowed. The "first run" is not a disk-cold run; the dataset (3.8 GB) fits in the 30 GB RAM page cache, though shared_buffers is only 2 GB, so most buffers come from the OS cache. On a smaller production host, expect first-hit reads to cost more.
- Container overhead: Postgres runs in Docker (overlay2 volume is a bind to the same NVMe, queries go through `docker exec psql` and for writes a TCP socket on localhost). Latencies include psql startup only outside Execution Time (the numbers above are server-side `Execution Time`). Write latencies include network round trips on loopback.
- Host noise: memory pressure with 22 to 24 GB of swap used and a concurrent SQLite benchmark. The 2x difference between two identical all-index runs shows the noise. The load, index and refresh times are from single runs.
- Reader gap metric: the reader runs one query at a time with a 200 ms period, so a blocked read shows up as one very long read. It does not simulate parallel readers.
- Mouser rows were deleted after the write tests. The CSV was deleted after the tests. Final state: `part_index` is the partitioned table (generation 3, 7 146 764 rows); the original unpartitioned layout (used in sections 3 to 6) no longer exists but is rebuilt by `01_schema.sql` + `02_indexes.sql`.

## 9. Cleanup

```
docker rm -f kina-pgbench; docker volume rm kina-pgbench-data
rm -rf /var/tmp/kina-bench/pg      # logs, scripts, JSON, this report
```
The container and volume were kept for follow-ups. The 5.3 GB SQLite source in `/var/tmp/kina-bench/` is not mine to delete.

## Appendix: SQL and scripts

Files in this directory: `01_schema.sql`, `02_indexes.sql`, `03_tsv_alt.sql`, `04_parent.sql`, `queries.py` (all Q1 to Q9 SQL in the `Q` dict), `writebench.py` (upsert SQL), `refresh.py` and `reader.py` (refresh DDL, reader query), `export.py` (extraction regexes).

### 01_schema.sql
```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS pgstattuple;
CREATE TABLE part_index (
  distributor text NOT NULL DEFAULT 'LCSC',
  part_number text NOT NULL,
  family text NOT NULL,
  lcsc text, mpn text, manufacturer text, description text,
  category1 text, category2 text, package text,
  value_num double precision, voltage_v double precision, tolerance_pct double precision, power_w double precision,
  dielectric text, mounting text, technology text,
  stock_int integer NOT NULL, price_text text, attributes jsonb NOT NULL,
  PRIMARY KEY (distributor, part_number)
);
```

### 02_indexes.sql
```sql
\timing on
\echo IDX1
CREATE INDEX idx_fam_value ON part_index (family, value_num);
\echo IDX2
CREATE INDEX idx_fam_package ON part_index (family, package);
\echo IDX3
CREATE INDEX idx_value ON part_index (value_num);
\echo IDX4
CREATE INDEX idx_voltage ON part_index (voltage_v);
\echo IDX5
CREATE INDEX idx_tolerance ON part_index (tolerance_pct);
\echo IDX6a
CREATE INDEX idx_value_cap ON part_index (value_num) WHERE family = 'capacitor';
\echo IDX6b
CREATE INDEX idx_value_res ON part_index (value_num) WHERE family = 'resistor';
\echo IDX7
CREATE INDEX idx_attrs_gin ON part_index USING gin (attributes jsonb_path_ops);
\echo IDX8a
CREATE INDEX idx_desc_fts ON part_index USING gin (to_tsvector('simple', description));
\echo IDX9a
CREATE INDEX idx_desc_trgm ON part_index USING gin (description gin_trgm_ops);
\echo IDX9b
CREATE INDEX idx_mpn_trgm ON part_index USING gin (mpn gin_trgm_ops);
\echo IDX10
CREATE INDEX idx_fam_value_instock ON part_index (family, value_num) WHERE stock_int > 0;
\echo ANALYZE
ANALYZE part_index;
SELECT c.relname, pg_relation_size(c.oid) bytes, pg_size_pretty(pg_relation_size(c.oid)) FROM pg_class c WHERE c.relname LIKE 'idx_%' OR c.relname='part_index_pkey' ORDER BY 1;
SELECT pg_total_relation_size('part_index') total, pg_relation_size('part_index') heap;
```

### 03_tsv_alt.sql
```sql
\timing on
CREATE TABLE tsv_alt (part_number text PRIMARY KEY, description text);
INSERT INTO tsv_alt SELECT part_number, description FROM part_index;
SELECT pg_relation_size('tsv_alt') heap_before;
ALTER TABLE tsv_alt ADD COLUMN tsv tsvector GENERATED ALWAYS AS (to_tsvector('simple', coalesce(description,''))) STORED;
SELECT pg_relation_size('tsv_alt') heap_after, pg_total_relation_size('tsv_alt') total_after;
CREATE INDEX tsv_alt_gin ON tsv_alt USING gin (tsv);
SELECT pg_relation_size('tsv_alt_gin') gin_bytes;
```

### 04_parent.sql
```sql
DROP TABLE part_index;
CREATE TABLE part_index (
  generation int NOT NULL,
  distributor text NOT NULL DEFAULT 'LCSC', part_number text NOT NULL, family text NOT NULL,
  lcsc text, mpn text, manufacturer text, description text, category1 text, category2 text, package text,
  value_num double precision, voltage_v double precision, tolerance_pct double precision, power_w double precision,
  dielectric text, mounting text, technology text, stock_int integer NOT NULL, price_text text, attributes jsonb NOT NULL,
  PRIMARY KEY (generation, distributor, part_number)
) PARTITION BY LIST (generation);
CREATE INDEX idx_fam_value ON part_index (family, value_num);
CREATE INDEX idx_fam_package ON part_index (family, package);
CREATE INDEX idx_value ON part_index (value_num);
CREATE INDEX idx_voltage ON part_index (voltage_v);
CREATE INDEX idx_tolerance ON part_index (tolerance_pct);
CREATE INDEX idx_value_cap ON part_index (value_num) WHERE family = 'capacitor';
CREATE INDEX idx_value_res ON part_index (value_num) WHERE family = 'resistor';
CREATE INDEX idx_attrs_gin ON part_index USING gin (attributes jsonb_path_ops);
CREATE INDEX idx_desc_fts ON part_index USING gin (to_tsvector('simple', description));
CREATE INDEX idx_desc_trgm ON part_index USING gin (description gin_trgm_ops);
CREATE INDEX idx_mpn_trgm ON part_index USING gin (mpn gin_trgm_ops);
CREATE INDEX idx_fam_value_instock ON part_index (family, value_num) WHERE stock_int > 0;
```

### Query SQL (from queries.py)
```python
Q={
'Q1 all-match (abs form)': f"SELECT part_number,stock_int FROM part_index WHERE {Q1W} ORDER BY stock_int DESC LIMIT 40",
'Q1s Q1 sargable value range': "SELECT part_number,stock_int FROM part_index WHERE family='capacitor' AND value_num BETWEEN 9.9e-6 AND 10.1e-6 AND package='0805' AND dielectric='X7R' AND (voltage_v IS NULL OR voltage_v>=25) AND stock_int>0 ORDER BY stock_int DESC LIMIT 40",
'Q2 no dielectric': "SELECT part_number,stock_int FROM part_index WHERE family='capacitor' AND abs(value_num-10e-6)<=10e-6*0.01 AND package='0805' AND (voltage_v IS NULL OR voltage_v>=25) AND stock_int>0 ORDER BY stock_int DESC LIMIT 40",
'Q3 no package': "SELECT part_number,stock_int FROM part_index WHERE family='capacitor' AND abs(value_num-10e-6)<=10e-6*0.01 AND dielectric='X7R' AND (voltage_v IS NULL OR voltage_v>=25) AND stock_int>0 ORDER BY stock_int DESC LIMIT 40",
'Q4 resistor 4.7k 1% 0603': "SELECT part_number,stock_int FROM part_index WHERE family='resistor' AND abs(value_num-4700)<=4700*0.01 AND package='0603' AND (tolerance_pct IS NULL OR tolerance_pct<=1) AND stock_int>0 ORDER BY stock_int DESC LIMIT 40",
'Q5 fts thin film + 5.36k': "SELECT part_number,stock_int FROM part_index WHERE to_tsvector('simple',description) @@ plainto_tsquery('simple','thin film') AND family='resistor' AND abs(value_num-5360)<=5360*0.01 AND stock_int>0 ORDER BY stock_int DESC LIMIT 40",
'Q5b fts only count': "SELECT count(*) FROM part_index WHERE to_tsvector('simple',description) @@ plainto_tsquery('simple','thin film')",
'Q6a trigram ILIKE %X7R% count': "SELECT count(*) FROM part_index WHERE description ILIKE '%X7R%'",
'Q6a2 trigram ILIKE %X7R% limit 40': "SELECT part_number FROM part_index WHERE description ILIKE '%X7R%' AND stock_int>0 ORDER BY stock_int DESC LIMIT 40",
'Q6b trigram % X7R count': "SELECT count(*) FROM part_index WHERE description % 'X7R'",
'Q6c mpn ILIKE %ERA6AEB% count': "SELECT count(*) FROM part_index WHERE mpn ILIKE '%ERA6AEB%'",
'Q6c2 mpn ILIKE %ERA6AEB% limit 40': "SELECT part_number FROM part_index WHERE mpn ILIKE '%ERA6AEB%' AND stock_int>0 ORDER BY stock_int DESC LIMIT 40",
'Q7 jsonb @> dielectric + value': "SELECT part_number,stock_int FROM part_index WHERE attributes @> '{\"Dielectric\":\"X7R\"}' AND abs(value_num-10e-6)<=10e-6*0.01 AND stock_int>0 ORDER BY stock_int DESC LIMIT 40",
'Q7b jsonb @> dielectric+capacitance string': "SELECT part_number,stock_int FROM part_index WHERE attributes @> '{\"Dielectric\":\"X7R\",\"Capacitance\":\"10uF\"}' AND stock_int>0 ORDER BY stock_int DESC LIMIT 40",
'Q8 count(*) of Q1 no LIMIT': f"SELECT count(*) FROM part_index WHERE {Q1W}",
'Q9 Q1 + three IS NULL OR ratings': "SELECT part_number,stock_int FROM part_index WHERE family='capacitor' AND abs(value_num-10e-6)<=10e-6*0.01 AND package='0805' AND dielectric='X7R' AND (voltage_v IS NULL OR voltage_v>=25) AND (tolerance_pct IS NULL OR tolerance_pct<=10) AND (power_w IS NULL OR power_w>=0.1) AND stock_int>0 ORDER BY stock_int DESC LIMIT 40",
}
def run(sql):
    p=subprocess.run(['docker','exec','-i','kina-pgbench','psql','-U','postgres','-d','bench','-At'],input="EXPLAIN (ANALYZE, BUFFERS) "+sql,capture_output=True,text=True)
    return p.stdout+p.stderr
def parse(t):
    ex=float(re.search(r'Execution Time: ([\d.]+)',t).group(1)); pl=float(re.search(r'Planning Time: ([\d.]+)',t).group(1))
```
### Upsert SQL (writebench.py)
```python
ph=",".join(["%s"]*20); 
sets=",".join(f"{k}=EXCLUDED.{k}" for k in colist[2:])
def sql(n): return f"INSERT INTO part_index ({cols}) VALUES "+",".join([f"({ph})"]*n)+f" ON CONFLICT (distributor,part_number) DO UPDATE SET {sets}"
```
### Refresh DDL and swap (refresh.py)
```python
IDX=[("idx_fam_value","(family, value_num)",""),("idx_fam_package","(family, package)",""),("idx_value","(value_num)",""),("idx_voltage","(voltage_v)",""),("idx_tolerance","(tolerance_pct)",""),
("idx_value_cap","(value_num)"," WHERE family = 'capacitor'"),("idx_value_res","(value_num)"," WHERE family = 'resistor'"),
("idx_attrs_gin","USING gin (attributes jsonb_path_ops)",""),("idx_desc_fts","USING gin (to_tsvector('simple', description))",""),
("idx_desc_trgm","USING gin (description gin_trgm_ops)",""),("idx_mpn_trgm","USING gin (mpn gin_trgm_ops)",""),("idx_fam_value_instock","(family, value_num)"," WHERE stock_int > 0")]
def ddl(tbl,suf): return [f"CREATE INDEX {n}{suf} ON {tbl} {d}{w}" for n,d,w in IDX]
steps=[]
def step(name,sql):
    t=time.perf_counter(); c.execute(sql); dt=time.perf_counter()-t
    steps.append((name,round(dt,2))); print(name,round(dt,2),flush=True)
def copy_in(tbl,cols=COLS):
    step('copy '+tbl,f"COPY {tbl} ({cols}) FROM PROGRAM 'cat /data/chunk_*.csv' WITH (FORMAT csv)")
def build(tbl,suf,pk=True):
    if pk: step('pk '+tbl,f"ALTER TABLE {tbl} ADD PRIMARY KEY (distributor, part_number)")
    for s in ddl(tbl,suf): step(s.split()[2],s)
def du():
```
