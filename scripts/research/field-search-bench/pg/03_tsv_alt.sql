\timing on
CREATE TABLE tsv_alt (part_number text PRIMARY KEY, description text);
INSERT INTO tsv_alt SELECT part_number, description FROM part_index;
SELECT pg_relation_size('tsv_alt') heap_before;
ALTER TABLE tsv_alt ADD COLUMN tsv tsvector GENERATED ALWAYS AS (to_tsvector('simple', coalesce(description,''))) STORED;
SELECT pg_relation_size('tsv_alt') heap_after, pg_total_relation_size('tsv_alt') total_after;
CREATE INDEX tsv_alt_gin ON tsv_alt USING gin (tsv);
SELECT pg_relation_size('tsv_alt_gin') gin_bytes;
