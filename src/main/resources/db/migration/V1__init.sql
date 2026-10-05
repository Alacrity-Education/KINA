CREATE TABLE users (
  id            UUID PRIMARY KEY,
  issuer        TEXT NOT NULL,
  subject       TEXT NOT NULL,
  email         TEXT,
  display_name  TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_login_at TIMESTAMPTZ,
  UNIQUE (issuer, subject)
);

CREATE TABLE access_tokens (
  id              UUID PRIMARY KEY,
  user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name            TEXT NOT NULL,
  token_hash      TEXT NOT NULL UNIQUE,
  token_prefix    TEXT NOT NULL,
  scope           TEXT,
  oauth_client_id TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at      TIMESTAMPTZ NOT NULL,
  revoked_at      TIMESTAMPTZ,
  last_used_at    TIMESTAMPTZ
);
CREATE INDEX access_tokens_user_idx ON access_tokens (user_id);

CREATE TABLE oauth_clients (
  client_id                  TEXT PRIMARY KEY,
  client_secret_hash         TEXT,
  client_name                TEXT,
  redirect_uris              JSONB NOT NULL,
  grant_types                JSONB NOT NULL,
  response_types             JSONB NOT NULL,
  token_endpoint_auth_method TEXT NOT NULL,
  scope                      TEXT,
  metadata                   JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE oauth_authorization_codes (
  code_hash             TEXT PRIMARY KEY,
  client_id             TEXT NOT NULL REFERENCES oauth_clients(client_id) ON DELETE CASCADE,
  user_id               UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  redirect_uri          TEXT NOT NULL,
  scope                 TEXT,
  resource              TEXT,
  code_challenge        TEXT NOT NULL,
  code_challenge_method TEXT NOT NULL,
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at            TIMESTAMPTZ NOT NULL,
  used_at               TIMESTAMPTZ
);

CREATE TABLE oauth_refresh_tokens (
  token_hash      TEXT PRIMARY KEY,
  client_id       TEXT NOT NULL REFERENCES oauth_clients(client_id) ON DELETE CASCADE,
  user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  access_token_id UUID REFERENCES access_tokens(id) ON DELETE SET NULL,
  scope           TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at      TIMESTAMPTZ NOT NULL,
  revoked_at      TIMESTAMPTZ
);

CREATE TABLE cached_parts (
  distributor TEXT NOT NULL,
  part_number TEXT NOT NULL,
  payload     JSONB NOT NULL,
  fetched_at  TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (distributor, part_number)
);
CREATE INDEX cached_parts_fetched_idx ON cached_parts (fetched_at);

CREATE TABLE cached_searches (
  distributor   TEXT NOT NULL,
  query_key     TEXT NOT NULL,
  total_results INTEGER,
  part_numbers  JSONB NOT NULL,
  exhausted     BOOLEAN NOT NULL DEFAULT false,
  fetched_at    TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (distributor, query_key)
);

CREATE TABLE jlcpcb_database (
  id            INTEGER PRIMARY KEY CHECK (id = 1),
  library       TEXT NOT NULL,
  file_path     TEXT NOT NULL,
  downloaded_at TIMESTAMPTZ NOT NULL,
  size_bytes    BIGINT,
  part_count    BIGINT,
  source_date   TEXT
);
