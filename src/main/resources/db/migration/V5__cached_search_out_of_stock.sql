-- Records a distributor matched without ships-now stock while a cached search list was built (DESIGN.md 3.2, 8).
-- NULL for rows written before this migration (unknown).
ALTER TABLE cached_searches ADD COLUMN out_of_stock_matches INTEGER;
