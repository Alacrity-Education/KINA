-- The constraints the relaxation ladder loosened to build a cached search list (dielectric, package, tolerance;
-- DESIGN.md 3.2, 8), so a cache hit reports the same constraints_relaxed as the live search.
-- NULL for rows written before this migration (derived from fallback_query when read).
ALTER TABLE cached_searches ADD COLUMN constraints_relaxed JSONB;
