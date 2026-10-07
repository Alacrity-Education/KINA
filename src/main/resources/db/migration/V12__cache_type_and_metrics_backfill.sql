-- The component type of every cached search and cached part (DESIGN.md 3.7 "Backfill", 8): the same value as the
-- type tag of the metrics (a parser family in lower case, or unknown). Written on every upsert and recomputed for every
-- row by the daily metrics backfill, so a vocabulary change re-types old rows. NULL (rows written before this release)
-- counts as unknown until the backfill has typed the row.
ALTER TABLE cached_searches ADD COLUMN type TEXT;
ALTER TABLE cached_parts ADD COLUMN type TEXT;

-- What the metrics backfill moved into each typed counter (name and canonical tags as in metrics_counters), summed over
-- every run. The row named 'backfill.completed' (tags '') is the completion marker: attributed is the number of
-- completed runs, updated_at the end of the last one. Without it the backfill runs once at startup.
CREATE TABLE metrics_backfill (
  name       TEXT        NOT NULL,
  tags       TEXT        NOT NULL DEFAULT '',
  attributed BIGINT      NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (name, tags)
);
