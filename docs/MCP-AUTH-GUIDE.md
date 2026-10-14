# Adding a remote MCP server with OAuth to an app that already signs in through Authentik

This guide is for an implementer who has an application with OIDC login (Authentik as the provider) and wants to expose an MCP server over HTTPS to Claude (claude.ai, Claude Desktop, Claude Code) with the same identity and the same group-based access control. It describes the flow KINA uses. It is language-agnostic: it names endpoints, data, rules and timings, not libraries.

The short version: your app becomes an OAuth 2.1 authorization server for Claude, keeps the login where it is (Authentik), and re-checks the user's group membership after the login using a refresh token from Authentik. The one extra scope you need from Authentik is `offline_access`. Without it the design degrades to "sign in again every 24 hours".

## 1. Roles

| Party | Role |
|---|---|
| Claude (the MCP client) | OAuth 2.1 public client. Discovers your authorization server from a `401`, registers itself or presents a published client identity, runs the authorization code flow with PKCE, calls `/mcp` with a bearer token, refreshes the token when it expires. |
| Your app | Resource server (`/mcp`) and authorization server (`/oauth/*`, `/.well-known/*`). Issues its own access and refresh tokens. Decides who may use the MCP server. |
| Authentik | Identity provider. Authenticates the user (browser login), says which groups the user is in, and issues your app a refresh token that lets it ask again later. |

Your app never forwards Authentik's tokens to Claude. Claude only ever sees tokens your app minted.

## 2. Authentik configuration

Create (or extend) the OAuth2/OpenID provider your app already uses:

- Client type: confidential. Grant types: `authorization_code` and `refresh_token`.
- Redirect URI: your app's existing OIDC callback, strict match.
- Signing key: a certificate, so ID tokens are RS256 and can be verified with the JWKS.
- Scopes selected on the provider: `openid`, `email`, `profile`, `offline_access`.
  - `openid`: the ID token.
  - `email`: the address, used for an optional allowed-domain rule. Authentik's built-in `email` mapping always sends `email_verified: false`; either add a custom `email` scope mapping that returns `true`, or do not require the verified flag in your app.
  - `profile`: in Authentik the default `profile` mapping carries the `groups` claim. If you use a dedicated `groups` scope mapping instead, select that scope too and request it.
  - `offline_access`: makes Authentik issue a refresh token. This is the extra scope. Authentik lists it in `scopes_supported` of the discovery document only once it is selected on the provider.
- Include claims in the ID token: enabled, so `groups` and `email` arrive in the ID token and the userinfo call is only a fallback.
- Authorization flow: implicit consent. Your app shows its own consent page for Claude, one consent screen is enough.
- Refresh token validity: at least as long as the refresh tokens your app issues to Claude (KINA uses 30 days by default, 90 days in one deployment). The shorter of the two is the real idle limit, see section 9.
- Application binding: bind the allowed group to the application. This is the first gate (Authentik refuses the login). Your app's check is the second gate and the only one that acts after the login. Authentik does not re-evaluate the binding on a refresh grant; it just returns tokens whose `groups` claim lacks the group. Your claim check is what cuts the user off.

Issuer: `https://<authentik>/application/o/<slug>/`. Discovery is `<issuer>/.well-known/openid-configuration`.

## 3. What changes in your existing OIDC login

Your app already redirects to Authentik and gets an ID token back. Add four things.

1. Request scopes `openid profile email offline_access` (plus `groups` if you use a dedicated scope). Add `offline_access` only when the discovery document lists it, and re-read the discovery document from time to time while it is missing (every 10 minutes at the next login is enough). An app that reads discovery once at startup and caches it forever never notices that the administrator added the scope later, and keeps asking for a refresh token it will never get.
2. Evaluate the access policy before creating a session:
   - Read the `groups` claim from the ID token; only when it is absent, from userinfo. Accept an array of strings or a single string. Compare names case-insensitively, ignore a leading `/`. The user must be in at least one required group.
   - Optionally check the e-mail domain (and the verified flag). A refused identity gets no session and a page that names the reason (`group`, `email_missing`, `email_domain`, `email_unverified`).
   - A refusal for an identity that already has a user row blocks that user: set `access_revoked_at`, revoke every token the user holds.
3. On success, upsert the user (`issuer`, `subject`, `email`, `display_name`, `last_login_at`), set `membership_checked_at = now` and clear `access_revoked_at`.
4. Store the refresh token Authentik returned, encrypted (AES-256-GCM with a key from configuration, the user id as associated data). It is the user's upstream refresh token and the key to every later re-check. Never log it, never return it.

Every call your app makes to Authentik (code exchange, userinfo, JWKS, re-checks) needs an explicit connect and read timeout (5 seconds) so a slow provider cannot hang your request threads.

## 4. The authorization server Claude talks to

All URLs are relative to your public origin (honour `X-Forwarded-Proto`, `X-Forwarded-Host` and `X-Forwarded-Prefix` from your reverse proxy; every URL in metadata and redirects must use the public origin).

### 4.1 Discovery

| Endpoint | Returns |
|---|---|
| `GET /.well-known/oauth-protected-resource` (also with the `/mcp` suffix) | `{"resource": "<origin>/mcp", "authorization_servers": ["<origin>"], "bearer_methods_supported": ["header"], "scopes_supported": ["<your scope>"], "resource_name": "<app>"}` |
| `GET /.well-known/oauth-authorization-server` (also with the `/mcp` suffix, and `/.well-known/openid-configuration`) | `issuer`, `authorization_endpoint`, `token_endpoint`, `registration_endpoint`, `revocation_endpoint`, `response_types_supported: ["code"]`, `response_modes_supported: ["query"]`, `grant_types_supported: ["authorization_code", "refresh_token"]`, `code_challenge_methods_supported: ["S256"]`, `token_endpoint_auth_methods_supported: ["none", "client_secret_basic", "client_secret_post"]`, `scopes_supported`, `client_id_metadata_document_supported: true` |

These endpoints, `/oauth/register`, `/oauth/token`, `/oauth/revoke` and `/mcp` are anonymous and need CORS (Claude's web app calls some of them from the browser). `/oauth/authorize` runs inside your normal web session.

### 4.2 The MCP endpoint

- `/mcp` speaks Streamable HTTP. A stateless server is enough for tools.
- It accepts only `Authorization: Bearer <token>`. Anything else gets `401` with:

```
WWW-Authenticate: Bearer realm="<app>", resource_metadata="<origin>/.well-known/oauth-protected-resource"
```

  Add `error="invalid_token"` when a token was presented. This header is how Claude finds your authorization server. Without it the connector cannot start the flow.

### 4.3 Client registration

Claude presents itself in one of two ways. Support both.

- Dynamic registration (RFC 7591): `POST /oauth/register` with `redirect_uris` (absolute, no fragment; `https`, or `http://localhost` and `http://127.0.0.1` on any port, or a custom scheme), optional `client_name`, `token_endpoint_auth_method` (default `none`), `grant_types`, `response_types`, `scope`. Answer `201` with `client_id`, `client_id_issued_at`, `client_secret_expires_at: 0`, the echoed metadata, and a `client_secret` only for a confidential method. Rate-limit it per client IP (a token bucket of about 30 per minute, `429` with `Retry-After`). Claude registers a new client on every fresh connection, so delete registered clients unused for 90 days that hold no live token.
- Client ID metadata documents: the `client_id` is an `https` URL (Claude's is on `claude.ai`; Claude Code's is `https://claude.ai/oauth/claude-code-client-metadata`). Fetch the document only from hosts you trust (`claude.ai`, `claude.com`, `*.anthropic.com`), with no redirects, a 5 second budget and a 1 MB cap. Validate: a JSON object whose `client_id` equals the URL exactly, non-empty `redirect_uris`, `token_endpoint_auth_method` absent or `none`, `grant_types` containing `authorization_code`, `response_types` containing `code`. Cache a valid document for an hour and persist it, so a short outage of the host does not break refreshes. An untrusted host or an invalid document never leads to a redirect: show an error page at `/oauth/authorize`, answer `invalid_client` at `/oauth/token`.

### 4.4 Authorize

`GET /oauth/authorize?response_type=code&client_id=...&redirect_uri=...&code_challenge=...&code_challenge_method=S256&state=...&scope=...&resource=...`

1. Validate the client and the exact `redirect_uri`. For a metadata-document client, a loopback `http` redirect URI may use any port (Claude Code listens on an ephemeral port). PKCE with `S256` is mandatory.
2. Require a signed-in user. If there is no session, send the browser through your normal Authentik login (with the policy of section 3) and come back to the same authorize URL. A blocked user is refused here too.
3. Show a consent page (client name, user, token lifetimes, Approve and Deny). You may skip it for trusted metadata-document clients with a non-loopback redirect URI. Keep it for loopback redirects: any local program can listen on a port and claim a trusted client's name.
4. On approve, issue an authorization code: 32 random bytes, store only its SHA-256, valid 10 minutes, bound to client, redirect URI, user, code challenge, scope and resource. Redirect to `redirect_uri?code=...&state=...`. On deny, redirect with `error=access_denied`.

### 4.5 Token

`POST /oauth/token`, form-encoded.

- `grant_type=authorization_code`: verify the client (a secret only if it registered with one), the code (unused, unexpired, same client and redirect URI), PKCE (`BASE64URL(SHA256(code_verifier)) == code_challenge`), and that the user is not blocked. Mark the code used. Issue an access token and a refresh token. Answer `{"access_token", "token_type": "Bearer", "expires_in", "refresh_token", "scope"}` with the real lifetime in `expires_in`.
- `grant_type=refresh_token`: first run the membership re-check of section 5 if it is due. A refusal is `invalid_grant`; that is the error Claude understands as "ask the user to sign in again". Then rotate: revoke the old refresh token and its access token, issue a new pair.
- Errors follow RFC 6749: `invalid_request`, `invalid_client` (401), `invalid_grant`, `unsupported_grant_type`.

Token storage: generate 32 random bytes, give the client the base64url string with a recognisable prefix, store only the SHA-256 hash and a short display prefix. Record `created_at`, `expires_at`, `revoked_at`, `last_used_at` (updated at most once a minute). Link each refresh token to the access token issued with it: revoking either revokes the other, so a user revoking a token in your UI does not leave a refresh token that mints a new one. Keep one method that revokes every token of a user; the policy uses it.

### 4.6 Revoke

`POST /oauth/revoke` (RFC 7009): revokes an access or refresh token of the authenticated client, always `200`.

## 5. Membership re-checks

The login proved membership once. Three points keep proving it.

| Point | When | How |
|---|---|---|
| Login | every interactive login | Section 3. Sets `membership_checked_at`. |
| Refresh grant | `/oauth/token` with `grant_type=refresh_token`, when `membership_checked_at` is older than the re-check interval (1 hour) | Synchronous, before rotation. A refusal is `invalid_grant`. |
| Bearer request | every request to `/mcp` and your API | Reject blocked users (`access_revoked_at` set) with `401`. Tokens from the OAuth flow need nothing more: they are short-lived and re-checked at refresh. Long-lived static tokens, if you offer them for scripts, run the re-check in the background and never make the request wait. |

The re-check itself:

1. Decrypt the user's upstream refresh token. Serialise re-checks per user, so the token is never redeemed twice at once.
2. Redeem it at Authentik's token endpoint (client authentication as discovered). Store the rotated refresh token.
3. Verify the new ID token with the JWKS and check the subject is the same user. Read the `groups` claim from it, or from userinfo with the new access token if the ID token lacks it.

| Outcome | Effect |
|---|---|
| Still a member | `membership_checked_at = now`. |
| Not a member, or Authentik answers `invalid_grant` (user removed, disabled, grant revoked or expired) | Block the user: set `access_revoked_at`, delete the upstream token, revoke every access and refresh token. The refresh grant fails with `invalid_grant`. A later successful login lifts the block. |
| Authentik unreachable (network error, timeout, 5xx, an unverifiable ID token) | Keep serving while `membership_checked_at` is younger than the grace period (4 hours). After that, refresh grants fail with `invalid_grant`, but nothing is revoked and the same refresh token works again once the provider is back (a refused attempt must not rotate it). |
| No upstream refresh token (no encryption key, or Authentik issued none because `offline_access` was not granted) | Keep serving while the last login is younger than the re-login interval (24 hours), then `invalid_grant`. Log a warning that names the cause. This is the degraded mode, not the design. |

Enable the enforcement only when at least one required group is configured. When you turn it on in an existing deployment, every user has to sign in once before refresh grants work: they have no `membership_checked_at` and no upstream refresh token yet.

## 6. The flow as Claude sees it

1. Claude calls `POST /mcp` without a token and gets `401` with `resource_metadata`.
2. It fetches the protected-resource metadata, then the authorization-server metadata of the origin named there.
3. It registers (`/oauth/register`) or uses its published client identity (an `https` `client_id`).
4. It opens `/oauth/authorize` in the browser with a PKCE challenge.
5. Your app has no session: the browser goes to Authentik, the user signs in, Authentik applies the application binding, your app applies its policy, stores the upstream refresh token and returns to the authorize URL.
6. Consent (or skipped for a trusted client), then the code goes to Claude's redirect URI.
7. Claude exchanges the code with the verifier at `/oauth/token` and calls `/mcp` with the access token.
8. When the access token expires, Claude refreshes. Your app re-checks membership at most once per hour, rotates the tokens, and the user notices nothing. If the re-check says the user is gone, `invalid_grant` makes Claude ask the user to sign in again, and the login is refused.

## 7. Privacy and logging rules

- Never log tokens, keys, ID tokens or refresh tokens, yours or Authentik's.
- Never log the e-mail address or name of a refused user. Log the provider subject or your user id, the issuer, and the reason.
- Never return the encryption key or an upstream token in any response. A malformed encryption key must fail startup, not fall back silently.
- Show the user their own tokens (name, prefix, created, expires, last used) and let them revoke each one.

## 8. Lifetimes that work

| Token | Lifetime | Why |
|---|---|---|
| Your access token (OAuth flow) | 1 hour | The re-check runs at refresh, so this is how long a removed member keeps access. |
| Your refresh token | 30 days, rotated on every use | How long an idle user stays connected. |
| Authorization code | 10 minutes, single use | |
| Static tokens for scripts (optional) | 30 days | Re-checked in the background on use. |
| Authentik refresh token | at least your refresh token lifetime | It rotates on every re-check. If it is shorter than your refresh token, an idle user is cut off earlier and, because Authentik answers `invalid_grant`, blocked until the next login. |
| Re-check interval | 1 hour | |
| Grace when Authentik is down | 4 hours | |
| Re-login interval without a refresh token | 24 hours | Degraded mode only. |

## 9. The idle limit

A connected user who does nothing stays connected for the shorter of your refresh token lifetime and Authentik's refresh token validity, both counted from the last use, because both rotate when used. Set Authentik's validity to at least yours, or the Authentik one decides. Raising the 24-hour re-login interval is not a fix for daily logouts: it only matters when there is no refresh token, and it is the window in which a removed member keeps access.

## 10. Checklist before going live

- [ ] Discovery document of Authentik lists `offline_access`; your app logs the scopes it requests and they include it.
- [ ] After a login, the user row holds an encrypted upstream refresh token.
- [ ] A member connects Claude, uses a tool, waits past the access token lifetime, and is not asked to sign in again.
- [ ] Remove the member from the group in Authentik: at the next refresh the grant fails with `invalid_grant`, the user is blocked, and a new login attempt is refused with the `group` reason. Add the member back: the next login works and lifts the block.
- [ ] Stop Authentik: refreshes keep working for the grace period, nothing is revoked, and they work again when it is back.
- [ ] Remove `offline_access` from the provider: new logins get the warning, and connector sessions end after the re-login interval. Put it back: within 10 minutes a login requests it again, with no restart.
- [ ] `401` from `/mcp` carries `WWW-Authenticate` with `resource_metadata`; CORS answers on the anonymous endpoints.
- [ ] An untrusted `https` `client_id` and an unregistered redirect URI produce an error page, never a redirect.
- [ ] No token, key or address appears in the logs of any of the above.

## 11. Things that went wrong once

- Discovery cached forever. The scope was added at Authentik; the app kept requesting the old scopes until a restart. Re-read discovery while the scope is missing.
- Authentik's default `email` mapping says `email_verified: false` for everyone. Decide between a custom mapping and not requiring the flag before the first user signs in.
- The application group binding is not re-evaluated on refresh grants. Without the claim check in your app, a removed member keeps refreshing until the refresh token expires.
- A consent skip for trusted clients must not apply to loopback redirect URIs.
- Static tokens for scripts die with the 24-hour rule when there is no refresh token, even though the token row says it is valid for a month. The error the script sees is a plain `401`; make the log explain it.
