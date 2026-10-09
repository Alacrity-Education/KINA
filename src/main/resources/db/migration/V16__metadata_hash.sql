-- The metadata hash of the field index (DESIGN.md 3.8 "When a row is current", 8; v0.15.1). A part_index row is
-- current when it was written by the running extractor from the metadata cached_parts holds now, with the same
-- in_stock: both tables carry metadata_md5, the hash of the stored metadata (identity, description, category,
-- package, attributes, extra; never stock, prices or timestamps, cache.PartMetadataHash), written by the application
-- with every write. A stock refresh therefore no longer makes a row stale.
--
-- This migration only ADDS two nullable columns and two covering indexes (so the coverage check reads the indexes,
-- never the payloads): it never reads, modifies, re-keys or deletes rows of cached_parts or cached_searches. The new
-- columns start NULL (not current); the background job after startup (search.field.PartIndexReindexer) fills
-- cached_parts.metadata_md5 from each payload, copies it to the index rows built from that same payload (no
-- extraction) and re-indexes only the rest.
ALTER TABLE cached_parts ADD COLUMN metadata_md5 TEXT;   -- PartMetadataHash of the payload; NULL until backfilled
ALTER TABLE part_index   ADD COLUMN metadata_md5 TEXT;   -- PartMetadataHash of the part the row was built from

CREATE INDEX cached_parts_index_state_idx ON cached_parts (distributor, part_number) INCLUDE (in_stock, metadata_md5);
CREATE INDEX part_index_state_idx ON part_index (distributor, part_number)
  INCLUDE (extractor_version, in_stock, metadata_md5);
