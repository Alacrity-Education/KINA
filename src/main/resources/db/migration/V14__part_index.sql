-- The field index (DESIGN.md 3.8, 8): one row per cached_parts row with the features the Java check reads, written
-- by the running extractor (search.PartIndexRows) and re-indexed in the background when the extractor version changes
-- (search.field.PartIndexReindexer). This migration only ADDS a table, its indexes and the pg_trgm extension: it never
-- reads, modifies, re-keys or deletes rows of cached_parts or cached_searches. The table starts empty; the background
-- re-index fills it from cached_parts after startup.
--
-- Rules: every SI value is rounded to 9 significant digits by the writer; the package is its normalised key, never
-- the raw string; NULL means the part does not state the attribute (every field rule keeps NULL rows). in_stock is a
-- copy of cached_parts.in_stock and is never indexed (stock refreshes stay HOT updates, study 8.1).
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE part_index (
  distributor        TEXT             NOT NULL,
  part_number        TEXT             NOT NULL,
  extractor_version  INTEGER          NOT NULL,
  indexed_at         TIMESTAMPTZ      NOT NULL,
  payload_md5        TEXT             NOT NULL,          -- md5(cached_parts.payload::text) the row was built from
  in_stock           BOOLEAN          NOT NULL,          -- copy of cached_parts.in_stock; not indexed
  family             TEXT,
  family_path        TEXT[]           NOT NULL DEFAULT '{}',
  policy_family      TEXT,
  subtype            TEXT,
  polarity           TEXT,
  package_key        TEXT,                               -- Recognizers.packageKey of the imperial package
  package_readable   BOOLEAN          NOT NULL DEFAULT false,
  package_class      TEXT,                               -- 'led' (LED size) or 'plcc', never compared with each other
  can_d_mm           DOUBLE PRECISION,
  can_l_mm           DOUBLE PRECISION,
  mounting           TEXT,                               -- SMD / THT; NULL unknown or a hybrid USB part
  technology         TEXT,
  dielectric         TEXT,                               -- lower case
  form_factor        TEXT,
  elements           SMALLINT,                           -- NULL single element, 0 an array of unstated size
  capacitance_f      DOUBLE PRECISION,
  resistance_ohm     DOUBLE PRECISION,
  inductance_h       DOUBLE PRECISION,
  impedance_ohm      DOUBLE PRECISION,
  impedance_test_hz  DOUBLE PRECISION,
  frequency_hz       DOUBLE PRECISION,
  voltage_v          DOUBLE PRECISION,
  current_a          DOUBLE PRECISION,
  isat_a             DOUBLE PRECISION,
  dcr_ohm            DOUBLE PRECISION,
  power_w            DOUBLE PRECISION,
  max_temp_c         DOUBLE PRECISION,
  lifetime_h         DOUBLE PRECISION,
  tolerance_pct      DOUBLE PRECISION,
  noise_dba          DOUBLE PRECISION,
  voltages_v         DOUBLE PRECISION[] NOT NULL DEFAULT '{}',   -- specification voltages, else the voltage
  connector_type     TEXT,
  gender             TEXT,
  positions          SMALLINT,
  rows_count         SMALLINT,
  pitch_mm           DOUBLE PRECISION,
  orientation        TEXT,
  usb_type           TEXT,
  usb_class          SMALLINT,                           -- speed class of the USB standard (USB 3.x unknown: 4)
  pin_configuration  SMALLINT,                           -- as the part states it (canonical: 17/18 -> 16)
  attrs              JSONB            NOT NULL DEFAULT '{}',   -- fan, LED and switch attributes, their SI values
  mpn                TEXT             NOT NULL DEFAULT '',     -- normalised manufacturer part number
  search_text        TEXT             NOT NULL DEFAULT '',     -- normalised description, category, attributes, MPN
  search_tsv         TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', search_text)) STORED,
  PRIMARY KEY (distributor, part_number),
  FOREIGN KEY (distributor, part_number) REFERENCES cached_parts (distributor, part_number)
    ON UPDATE CASCADE ON DELETE CASCADE
) WITH (fillfactor = 90);

-- values: one partial index per primary value, led by the distributor and family
CREATE INDEX part_index_cap_idx  ON part_index (distributor, family, capacitance_f)  WHERE capacitance_f IS NOT NULL;
CREATE INDEX part_index_res_idx  ON part_index (distributor, family, resistance_ohm) WHERE resistance_ohm IS NOT NULL;
CREATE INDEX part_index_ind_idx  ON part_index (distributor, family, inductance_h)   WHERE inductance_h IS NOT NULL;
CREATE INDEX part_index_imp_idx  ON part_index (distributor, family, impedance_ohm)  WHERE impedance_ohm IS NOT NULL;
CREATE INDEX part_index_freq_idx ON part_index (distributor, family, frequency_hz)   WHERE frequency_hz IS NOT NULL;
CREATE INDEX part_index_pkg_idx  ON part_index (distributor, package_key, family);
CREATE INDEX part_index_conn_idx ON part_index (distributor, connector_type, positions)
  WHERE connector_type IS NOT NULL;
CREATE INDEX part_index_usb_idx  ON part_index (distributor, usb_type, pin_configuration) WHERE usb_type IS NOT NULL;
CREATE INDEX part_index_fam_idx  ON part_index (distributor, family);
CREATE INDEX part_index_tsv_idx  ON part_index USING gin (search_tsv);
CREATE INDEX part_index_text_trgm_idx ON part_index USING gin (search_text gin_trgm_ops);
CREATE INDEX part_index_mpn_trgm_idx  ON part_index USING gin (mpn gin_trgm_ops);
CREATE INDEX part_index_attrs_idx ON part_index USING gin (attrs jsonb_path_ops);
CREATE INDEX part_index_version_idx ON part_index (extractor_version);
