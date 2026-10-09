# Field-search benchmarks (2026-10-08)

Scripts behind `docs/research/field-search-2026-10-08.md`. Their reports are
`docs/research/data/pg-field-index-benchmark-2026-10-08.md` and
`docs/research/data/sqlite-field-index-benchmark-2026-10-08.md`. The extractor coverage runner is separate, in
`scripts/research/extraction_coverage/`.

These are one-off research scripts, copied as they ran. They are not part of the build. They hard-code the
paths of the run (`/var/tmp/kina-bench/...`). Data files, CSV chunks, `.db` files, logs, plans and JSON results were
not copied.

## Input

The JLCPCB database `parts-fts5.db` (7 146 764 rows, 5.33 GB), as KINA downloads it (DESIGN.md 9.3), copied to
`/var/tmp/kina-bench/parts-fts5.db`. Every script opens it read-only.

## Postgres (`pg/`)

Python 3 with `psycopg` 3. The scripts that connect over TCP read the connection string from `PGBENCH_DSN`, for
example `host=127.0.0.1 port=55432 user=postgres password=<password> dbname=bench`. `psq` wraps `psql` inside the
container.

1. Start a throwaway container: `postgres:17-alpine` named `kina-pgbench`, published on `127.0.0.1:55432`, database
   `bench`, a 1 GB `/dev/shm`, the CSV directory mounted at `/data`, and the settings of the report (section 1:
   `shared_buffers=2GB`, `work_mem=128MB`, `maintenance_work_mem=1GB`, `max_wal_size=4GB`,
   `effective_cache_size=8GB`, `max_parallel_maintenance_workers=4`, `track_io_timing=on`).
2. `export.py`: reads the SQLite file in 12 processes, extracts the typed columns with regexes and writes
   `chunk_*.csv`.
3. `psq < 01_schema.sql`, then `COPY part_index (...) FROM PROGRAM 'cat /data/chunk_*.csv' WITH (FORMAT csv)`.
4. `psq < 02_indexes.sql` (indexes one at a time, sizes); `psq < 03_tsv_alt.sql` (stored tsvector alternative).
5. `queries.py`: `EXPLAIN (ANALYZE, BUFFERS)` of Q1 to Q9, first run and median of 5.
6. `writebench.py <label>`: 200 upsert batches of 50 rows, insert then update, with all indexes and with the PK only.
7. `refresh.py <mode> [generation]` with `reader.py <out.jsonl>` running at the same time: reload approaches A (new
   table and rename swap), B and B2 (partition by generation; `04_parent.sql` creates the partitioned parent) and C
   (TRUNCATE and COPY in one transaction).

## SQLite (`sqlite/`)

Python 3.14 with the standard `sqlite3` module (SQLite 3.53.4, FTS5 trigram). `kb.py` holds the shared helpers: the
read-only connection, the Python port of `JlcpcbSqliteSearch.containsValue` registered as `kina_value`, and the
term model written to match `JlcpcbQuery.parse`. Cold runs evict files with `posix_fadvise(DONTNEED)`.

1. `base1.py`, `base2.py`: today's FTS5 searches (count plus page), warm and cold, the LIKE path and the OR fallback.
2. `conc.py`: 8 searches x 20 rounds with one shared connection behind a lock, 8 connections in threads, and 8
   processes.
3. `extract.py`: builds `part_index.db` (typed columns by regex, all rows); `nulls.py`: NULL rates; `idx.py`: the six
   indexes and `ANALYZE`; `fq.py`: field queries and plans.
4. `subsets.py`: the 510 k-row subsets; `fts_refresh.py`, `fts_refresh2.py`, `fts_refresh3.py`: in-place FTS
   replacement (rollback journal and WAL, DELETE and DROP/CREATE variants, VACUUM).
5. `pi_refresh.py`: in-place and generation replacement of `part_index` at full size; `pi_e.py`: drop indexes,
   replace and rebuild in one transaction.

## Caveats

The host was under memory pressure (22 to 25 GB of swap in use) and ran both benchmarks at the same time. Treat
timings within a factor of 2 as equal. The typed columns come from crude regexes, not from KINA's extractor.
