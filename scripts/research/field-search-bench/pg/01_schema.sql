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
