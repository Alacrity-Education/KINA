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
