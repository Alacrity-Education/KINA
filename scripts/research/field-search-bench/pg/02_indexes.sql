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
