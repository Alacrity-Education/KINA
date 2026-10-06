-- New cache model (DESIGN.md 3.2, 8): component metadata is kept (per-distributor retention, default forever) while
-- stock and prices expire. fetched_at becomes stock_fetched_at (when the stock and prices in the payload were fetched);
-- metadata_fetched_at is when the metadata was last fetched in full; in_stock is false once a stock refresh found the
-- part sold out (the row then only keeps its metadata and is never served).
ALTER TABLE cached_parts RENAME COLUMN fetched_at TO stock_fetched_at;
ALTER INDEX cached_parts_fetched_idx RENAME TO cached_parts_stock_fetched_idx;
ALTER TABLE cached_parts ADD COLUMN metadata_fetched_at TIMESTAMPTZ;
UPDATE cached_parts SET metadata_fetched_at = stock_fetched_at;
ALTER TABLE cached_parts ALTER COLUMN metadata_fetched_at SET NOT NULL;
ALTER TABLE cached_parts ADD COLUMN in_stock BOOLEAN NOT NULL DEFAULT true;
CREATE INDEX cached_parts_metadata_fetched_idx ON cached_parts (distributor, metadata_fetched_at);
CREATE INDEX cached_searches_fetched_idx ON cached_searches (fetched_at);
