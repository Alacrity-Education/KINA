-- Prometheus counters kept across restarts (DESIGN.md 3.7). One row per counter and tag combination; a timer is two
-- rows (<name>:count and <name>:nanos). tags is the canonical "key=value,key=value" string sorted by key ('' = none).
-- KINA adds the stored values back on startup, so the exported counters only grow.
CREATE TABLE metrics_counters (
  name       TEXT        NOT NULL,
  tags       TEXT        NOT NULL DEFAULT '',
  value      BIGINT      NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (name, tags)
);
