-- The outcome of the direct lookup of the part numbers a query names (DESIGN.md 3.2 "Requested part numbers", 8), so
-- a cache hit for the same query reproduces the response without calling the distributor again:
-- {"<part number as sent>": {"status": "found" | "listed" | "not_found", "part_number": "<distributor number>"}}.
-- "found": in stock, its number is also in part_numbers; "listed": without ships-now stock (the cached_parts row has
-- in_stock = false and is read only for this explicit search); "not_found": the distributor does not have it.
-- NULL for rows written before this migration and for queries without part numbers (looked up again when needed).
ALTER TABLE cached_searches ADD COLUMN requested_parts JSONB;
