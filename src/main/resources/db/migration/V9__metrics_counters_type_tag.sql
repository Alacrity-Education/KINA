-- The search counters gain the component type tag (DESIGN.md 3.7): type is the parser family of the query
-- (resistor, capacitor...) or unknown. Prometheus refuses a meter whose tag keys differ from an already registered one
-- of the same name, so the stored rows of these five series get type=unknown before the application registers the new
-- keys. type sorts after distributor, outcome and status, so appending it keeps the canonical tag order. Rows that
-- already carry a type tag are left alone, so running this twice changes nothing.
UPDATE metrics_counters
   SET tags = CASE WHEN tags = '' THEN 'type=unknown' ELSE tags || ',type=unknown' END,
       updated_at = now()
 WHERE name IN ('kina.search.queries', 'kina.distributor.calls', 'kina.parts.returned', 'kina.parts.fetched',
                'kina.cache.search.lookups')
   AND tags NOT LIKE 'type=%'
   AND tags NOT LIKE '%,type=%';
