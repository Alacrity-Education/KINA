-- Raw distributor offset (0-based) at which the next page of a cached search starts. Distributors drop parts that
-- do not ship now, so the number of cached part numbers is not the number of distributor records consumed.
-- NULL for rows written before this column existed (callers fall back to the part count).
ALTER TABLE cached_searches ADD COLUMN next_offset INTEGER;
