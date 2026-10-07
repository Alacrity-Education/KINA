-- TME product pages use the symbol lower-cased with every '/' replaced by '_' (DESIGN.md 9.2): an encoded '%2F' gives
-- a 404. Rewrite productUrl of TME rows cached with the encoded form, and datasheetUrl when it was that product page.
UPDATE cached_parts
SET payload = CASE
        WHEN payload ->> 'datasheetUrl' = payload ->> 'productUrl'
            THEN jsonb_set(jsonb_set(payload, '{productUrl}',
                    to_jsonb('https://www.tme.eu/en/details/' || lower(replace(part_number, '/', '_')) || '/')),
                    '{datasheetUrl}',
                    to_jsonb('https://www.tme.eu/en/details/' || lower(replace(part_number, '/', '_')) || '/'))
        ELSE jsonb_set(payload, '{productUrl}',
                to_jsonb('https://www.tme.eu/en/details/' || lower(replace(part_number, '/', '_')) || '/'))
    END
WHERE distributor = 'TME'
  AND payload ->> 'productUrl' LIKE '%\%2F%';
