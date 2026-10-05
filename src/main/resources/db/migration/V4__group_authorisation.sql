-- Group authorisation and membership re-checks (DESIGN.md 7.1).
-- access_revoked_at: set when the user failed a group check (login or re-check) or the identity provider rejected the
--   stored grant; every token of the user is revoked at the same time and bearer authentication refuses the user until
--   a successful interactive login clears it.
-- membership_checked_at: last successful membership check (interactive login or server-side re-check).
-- upstream_refresh_token: the identity provider's refresh token, AES-GCM encrypted (UpstreamTokenCipher), or NULL.
ALTER TABLE users
  ADD COLUMN access_revoked_at                 TIMESTAMPTZ,
  ADD COLUMN membership_checked_at             TIMESTAMPTZ,
  ADD COLUMN upstream_refresh_token            TEXT,
  ADD COLUMN upstream_refresh_token_updated_at TIMESTAMPTZ;

-- metadata_url: set for clients identified by a Client ID Metadata Document (client_id is that URL).
-- last_used_at: last token issuance, used by the cleanup of unused dynamically registered clients.
ALTER TABLE oauth_clients
  ADD COLUMN metadata_url TEXT,
  ADD COLUMN last_used_at TIMESTAMPTZ;

CREATE INDEX oauth_refresh_tokens_user_idx ON oauth_refresh_tokens (user_id);
CREATE INDEX oauth_refresh_tokens_client_idx ON oauth_refresh_tokens (client_id);
CREATE INDEX access_tokens_oauth_client_idx ON access_tokens (oauth_client_id) WHERE oauth_client_id IS NOT NULL;
