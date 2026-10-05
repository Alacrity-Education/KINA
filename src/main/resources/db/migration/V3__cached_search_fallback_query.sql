-- Shorter parametric "core" phrase that was actually sent to the distributor when the full query found nothing
-- (PartSearchService phrase fallback). NULL when the full query itself was searched.
ALTER TABLE cached_searches ADD COLUMN fallback_query TEXT;
