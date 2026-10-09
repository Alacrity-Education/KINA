-- The phrase journal of the field-first search (DESIGN.md 3.2 "Field-first flow", 8): which phrases a distributor was
-- already asked, and when, so a phrase is not asked again while its answer is fresh (the Mouser quota is 1 000 calls a
-- day). This migration only ADDS a table: it never reads, modifies, re-keys or deletes cached_parts or cached_searches
-- rows. The table is filled by the searches and, once at startup, from the existing cached_searches rows
-- (search.field.PhraseJournalBackfill), so the history counts as already asked.
CREATE TABLE distributor_phrases (
  distributor   TEXT        NOT NULL,                 -- MOUSER | TME
  phrase_key    TEXT        NOT NULL,                 -- the phrase's normalised words in sorted order
  phrase        TEXT        NOT NULL,                 -- the phrase as sent
  asked_at      TIMESTAMPTZ NOT NULL,
  raw_total     INTEGER,                              -- the distributor's result count for the phrase; NULL: unknown
  next_offset   INTEGER,                              -- raw record offset where the next page starts; NULL: unknown
  exhausted     BOOLEAN     NOT NULL DEFAULT FALSE,   -- the distributor had no more records
  out_of_stock  INTEGER,                              -- records matched without ships-now stock; NULL: unknown
  empty         BOOLEAN     NOT NULL DEFAULT FALSE,   -- no in-stock part came back (fresh for empty-result-ttl only)
  ladder_step   INTEGER     NOT NULL DEFAULT 0,       -- 0: the phrase of the request, n: the n-th relaxation phrase
  query_key     TEXT,                                 -- the query that asked it (informational)
  PRIMARY KEY (distributor, phrase_key)
);

CREATE INDEX distributor_phrases_asked_idx ON distributor_phrases (asked_at);
