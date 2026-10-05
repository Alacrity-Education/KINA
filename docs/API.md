# KINA API reference

KINA exposes three surfaces:

- a REST API under `/api/v1`,
- an MCP server at `/mcp` (Streamable HTTP, stateless),
- an OAuth 2.1 authorization server under `/oauth` and `/.well-known`, used by MCP clients such as Claude's remote connector.

All paths are relative to the public origin, for example `https://kina.example.com`. Response fields are snake_case. Distributor names are case-insensitive in every input (`lcsc`, `LCSC`, ` Mouser `) and upper-case in every output (`LCSC`, `TME`, `MOUSER`).

## Authentication

| Mode | `/api/**` and `/mcp` |
|---|---|
| `dev` | No credentials needed; requests run as the "Development Admin". A bearer token that is sent is still validated. |
| `prod` | `Authorization: Bearer <token>` required. |

A token is either a 30-day token from the web UI (`kina_` followed by 43 characters) or an access token issued by the OAuth flow (same format, same table, same validity).

A missing, unknown, expired or revoked token gives `401` with an `application/problem+json` body and:

```
WWW-Authenticate: Bearer realm="kina", resource_metadata="https://<host>/.well-known/oauth-protected-resource"[, error="invalid_token"]
```

`error="invalid_token"` is added only when a token was sent. The `resource_metadata` link is how MCP clients find the authorization server.

`/actuator/health` and `/actuator/info` are public.

CORS is enabled (any origin, no credentials) for `/mcp`, `/.well-known/**`, `/oauth/register`, `/oauth/token` and `/oauth/revoke`.

## REST API

### Common types

`SearchResponse`:

| Field | Type | Meaning |
|---|---|---|
| `query` | string | The query as sent. |
| `parsed` | object | What KINA understood: `family`, value fields such as `capacitance` or `resistance` (display form, for example `"10uF"`), `dielectric`, `package`, `mounting`, `keywords`, and for connectors `connector` (see [`parsed.connector`](#parsedconnector)). Absent values are omitted. |
| `ranking` | string | `laya` or `fallback`. |
| `ranking_note` | string or null | Why the ranking fell back, for example `"laya timeout after 18s"`. Always present, null when Laya ranked. |
| `distributors` | array | One `DistributorResult` per searched distributor. |

### `parsed.connector`

Present when KINA reads the query as a connector request (`parsed.family` is then `"connector"`). Absent attributes are omitted.

| Field | Type | Meaning |
|---|---|---|
| `type` | string | For example `pin header`, `female header`, `box header`, `terminal block`, `usb-c`, `fpc`, `rj45`, `d-sub`, `barrel jack`, or a generic `connector`. |
| `series` | string | A series such as JST `XH`, `PH`, `GH`, `SH`, `ZH`. |
| `gender` | string | `male` or `female`. |
| `positions` | integer | Number of positions. Read from `6-position`, `6 pos`, `6 pin`, `6P`, `6 way`, `PIN: 6` or from `rows x pins`. IC packages such as `SOIC-8` are not read as positions. |
| `rows` | integer | From `1x6`, `2x3`, "single row", "dual row". |
| `pitch` | string | Display form, for example `"2.54mm"`. `0.1"` becomes `2.54mm`. `dupont` implies `2.54mm`. |
| `orientation` | string | `right angle` or `vertical`. |

Mounting (`THT` or `SMD`) stays in `parsed.mounting`. The server also knows whether a pitch was implied rather than written, but it does not put that in the response.

`DistributorResult`:

| Field | Type | Meaning |
|---|---|---|
| `distributor` | string | `LCSC`, `TME` or `MOUSER`. |
| `total_results` | integer or null | How many matches the distributor reported. Null when unknown or on error. |
| `fetched` | integer | In-stock parts KINA holds for the query and ranked. |
| `returned` | integer | `min(max_results, fetched)`; the length of `parts`. |
| `cache` | string | `hit`, `partial`, `miss`, `bypassed` or `not_applicable` (LCSC, and distributors that were never looked up). |
| `fallback_query` | string or null | Set when the full query found nothing at this distributor and KINA retried with a shorter parametric core phrase, for example `"MOSFET 30V SOT-23"` for `"SOT-23 N-channel MOSFET 30V"`. Only Mouser and TME; null otherwise (always present in the JSON). The parts in the entry come from that phrase. The fallback rules: connector requests fall back to the type words plus the positions (TME) or plus pitch and orientation (Mouser); other requests fall back to the parametric core; a query with only keywords falls back to its 3 to 5 most informative tokens. No fallback is tried when the shorter phrase equals what was already sent. |
| `distributor_query` | string or null | The phrase KINA sent when it rewrote your request into this distributor's vocabulary (connector requests). Null when your text went through as written. Always present. If it found nothing, `fallback_query` is what was sent after it. |
| `error` | string or null | `rate_limited`, `unavailable`, `not_configured`, `timeout` or `bad_response`. A failing distributor has an empty `parts` list, except that parts already in hand are kept. `rate_limited` means the rate limit outlasted the request deadline (see [Rate limits and timing](#rate-limits-and-timing)). |
| `rate_limit_waited_ms` | integer | Milliseconds this distributor's fetch spent waiting on rate limits, including waiting for a shared cool-down. Always present, 0 when KINA did not wait. |
| `parts` | array | `PartResponse` entries, best first. |

`PartResponse`:

| Field | Type | Meaning |
|---|---|---|
| `rank` | integer or null | 1 is best within the distributor. Null for single-part lookups. |
| `score` | number or null | 0 to 1. Null for single-part lookups. |
| `distributor` | string | `LCSC`, `TME`, `MOUSER`. |
| `part_number` | string | Distributor part number (LCSC `Cxxxxx`, TME symbol, Mouser number). |
| `manufacturer` | string | |
| `mpn` | string | Manufacturer part number. |
| `description` | string | |
| `category` | string or null | |
| `package` | string or null | For example `0805`, `SOT-23`. |
| `stock` | integer | Quantity that ships now; always above 0. |
| `min_order_qty` | integer or null | Null when unknown (always null for LCSC). |
| `order_multiple` | integer or null | Null when unknown (always null for LCSC). |
| `prices` | array | At most 3 entries, the smallest quantity brackets: `{"qty": 1, "unit_price": 1.40, "currency": "EUR"}`. LCSC prices are in USD. |
| `datasheet_url` | string or null | |
| `photo_url` | string or null | Null when the distributor gives none (LCSC). |
| `product_url` | string or null | |
| `attributes` | object | Parametric attributes, for example `{"Capacitance": "10uF"}`. Connector parts also carry `ConnectorType`, `Series`, `Gender`, `Positions`, `Rows`, `Pitch`, `Orientation` and `Mounting` when the distributor data allows it. |
| `extra` | object | Distributor-specific details (lifecycle, RoHS, library type, lead time, and so on). |

### Connector queries

Write a connector request in plain words, for example `90 degree dupont style female pin header, THT, 6 position`. KINA extracts type, gender, positions, rows, pitch, orientation and mounting (`parsed.connector`), rewrites the request for each distributor (`distributor_query`) and ranks with connector features. For that request the phrases were:

| Distributor | `distributor_query` |
|---|---|
| LCSC | `"Female Header" 6P "Right Angle" 2.54mm` |
| TME | `pin strips female 6 angled` (TME phrases are cut at 40 characters) |
| MOUSER | `female header 6 pos right angle` |

LCSC searches the local JLCPCB database. A known connector type becomes a category filter, `THT` matches `Through Hole` or `Plugin`, and `SMD` or `SMT` match `Surface Mount`. If the first search finds nothing, KINA relaxes it step by step: all terms; terms that occur nowhere in the database removed; one term dropped at a time (least informative first); parametric terms only; an OR of all terms.

Ranking for connectors uses positions (0.30), gender (0.20), orientation (0.15), pitch (0.15, 2.54 mm equals 0.1"), type (0.10) and mounting (0.05). A wrong row count costs 0.10 and multi-row parts cost 0.08 when rows were not requested. Unknown attributes never lower a score.

Known limit: rows are not sent to Mouser and Mouser keyword search is loose, so a `2x3` request returns single-row parts there. TME and LCSC handle rows.

The cache key is your own query text, not the distributor phrase.

### Rate limits and timing

When Mouser or TME rate limit a call, KINA waits and retries instead of failing at once.

- Triggers: HTTP 429; HTTP 502, 503 or 504 only when a `Retry-After` header is present; Mouser's in-body error code `TooManyRequests` on HTTP 200. A 503 without `Retry-After` is an outage and fails fast with `unavailable`. TME has no throttling error code, so only the statuses count.
- Wait: the `Retry-After` value (seconds or HTTP date, at least 1 s). Without it, 2, 4, 8, 16, 30, 30... seconds with 20 percent jitter, never below 1 s. KINA retries while the next wait fits inside the request deadline.
- Request deadline: `kina.search.max-request-duration`, default `2m`, for each incoming request. A batch shares one deadline for all its queries. The waits extend a distributor's 12 s work budget but never the deadline.
- Shared cool-down: after a rate limit, other calls to the same distributor wait for the cool-down to end if that fits their deadline. Otherwise they fail at once with `rate_limited`, without calling the distributor.
- Result: `rate_limit_waited_ms` in each distributor entry tells how long KINA waited. `error: "rate_limited"` means the limit outlasted the deadline. Parts fetched before that (earlier pages, a cached list) are still returned.
- Ranking runs after fetching (18 s per query, 60 s per batch). The worst case is therefore about 2 minutes plus ranking.
- Mouser quotas stay at 1 000 calls a day and 30 a minute. The retry helps with the per-minute limit, not with an exhausted daily quota.

Set the read timeout of your HTTP client or proxy above about 2.5 minutes for the search endpoints.

### `GET /api/v1/parts/search`

Search one query.

| Parameter | Required | Meaning |
|---|---|---|
| `q` | yes | Query text. Must not be blank. |
| `max_results` | no | Parts per distributor, 1 to 50. Default 10. Out of range gives 400. |
| `distributors` | no | `LCSC`, `TME`, `MOUSER`. Repeat the parameter or separate with commas. Default: all configured distributors. |
| `bypass_cache` | no | `true` skips the cache lookup; the cache is still refreshed. Default `false`. |

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "https://kina.example.com/api/v1/parts/search?q=10uF%20X7R%200805&max_results=5&distributors=LCSC,TME"
```

Returns a `SearchResponse` (see the example in the [README](../README.md#example)). With a rate-limited distributor the call can take up to 2 minutes plus ranking.

### `POST /api/v1/parts/search/batch`

Search 1 to 20 queries. `distributors` and `bypass_cache` apply to every query; `max_results` is per query.

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  https://kina.example.com/api/v1/parts/search/batch -d '{
    "queries": [
      {"query": "10uF X7R 0805", "max_results": 5},
      {"query": "4k7 1% 0603 resistor"}
    ],
    "distributors": ["LCSC", "MOUSER"],
    "bypass_cache": false
  }'
```

Response: `{"results": [SearchResponse, ...]}` in request order. Queries are fetched in parallel (4 at a time) and ranked one after another within a 60 s budget; queries reached after the budget is spent return `"ranking": "fallback"` with `"ranking_note": "batch ranking budget of 60s exhausted"`. All queries share one 2-minute deadline for rate-limit waits, so the whole call can take about 2 minutes plus ranking.

### `GET /api/v1/parts/{distributor}/{partNumber}`

Get one part by distributor part number. The part number is the rest of the path, so TME symbols that contain `/` work unencoded.

| Parameter | Meaning |
|---|---|
| `bypass_cache` | Query the distributor live. Default `false`. |

```bash
curl -s -H "Authorization: Bearer $TOKEN" https://kina.example.com/api/v1/parts/lcsc/C15850
```

Returns one `PartResponse` (without `rank` and `score`, prices trimmed to 3 brackets). It returns 404 when the distributor does not know the part or has no ships-now stock for it. Lookup failures return 503, 429, 504 or 502 (see below). On a rate limit the call waits and retries for up to 2 minutes; 429 means the limit outlasted that. The response has no `rate_limit_waited_ms`.

### `GET /api/v1/distributors`

State of each distributor. It never calls the Mouser or TME APIs. Same payload as the `list_distributors` MCP tool.

```bash
curl -s -H "Authorization: Bearer $TOKEN" https://kina.example.com/api/v1/distributors
```

```json
{
  "distributors": [
    {
      "distributor": "LCSC", "configured": true, "available": true,
      "detail": "...", "uses_cache": false, "max_results_per_search": 200,
      "jlcpcb": {"available": true, "library": "parts-fts5.db", "downloaded_at": "2026-10-05T08:00:00Z",
                 "source_date": "...", "part_count": 0, "downloading": false, "last_error": null}
    },
    {"distributor": "TME", "configured": true, "available": true, "detail": "...", "uses_cache": true,
     "cached_parts": 0, "max_results_per_search": 60},
    {"distributor": "MOUSER", "configured": true, "available": true, "detail": "...", "uses_cache": true,
     "cached_parts": 0, "max_results_per_search": 50}
  ],
  "cache": {"ttl": "PT120H", "parts": 0, "fresh_parts": 0, "searches": 0, "oldest_fetch": null},
  "ranking": {"laya_enabled": true, "laya_healthy": true, "model": "multilingual",
              "max_candidates": 40, "weight": 0.2, "timeout": "..."}
}
```

Numbers above are placeholders. `available` for TME and Mouser means "configured"; there is no live probe. `cache` is null when the database cannot be read. Null fields in a distributor entry are omitted.

### Errors

REST errors are RFC 9457 `application/problem+json`. They never contain stack traces. Types are `urn:kina:problem:<name>`.

| Status | `type` | When |
|---|---|---|
| 400 | `urn:kina:problem:validation` | Blank `q`, `max_results` out of range, bad batch body, malformed JSON. Body and parameter validation add `errors: [{"field": "...", "message": "..."}]`. |
| 400 | `urn:kina:problem:unknown-distributor` | A distributor name other than LCSC, TME, MOUSER. |
| 401 | `about:blank` | Missing or invalid token (see Authentication). |
| 404 | `urn:kina:problem:not-found` | Unknown or out-of-stock part. Adds `distributor` and `part_number`. |
| 429, 502, 503, 504 | `urn:kina:problem:distributor-error` | Single-part lookup failed: 503 for `not_configured` and `unavailable`, 429 for `rate_limited` (only after the 2-minute retry budget), 504 for `timeout`, 502 for `bad_response`. Adds `distributor` and `error`. |
| 500 | `urn:kina:problem:internal` | Anything else. |

Search endpoints do not return distributor errors as HTTP errors; they put the code in the `error` field of the distributor entry. A rate limit is retried first; `error: "rate_limited"` appears only after the retry budget is spent.

Example:

```json
{
  "type": "urn:kina:problem:validation",
  "title": "Invalid request",
  "status": 400,
  "detail": "Request parameter validation failed.",
  "instance": "/api/v1/parts/search",
  "errors": [{"field": "max_results", "message": "must be less than or equal to 50"}]
}
```

## MCP

- Endpoint: `POST /mcp`, JSON-RPC 2.0 over Streamable HTTP, stateless: no `initialize` handshake or session is required.
- Send `Content-Type: application/json` and `Accept: application/json, text/event-stream`.
- Server name `kina`; version comes from the build.
- Tool results are JSON, returned as text content.

```bash
curl -s -X POST https://kina.example.com/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"search_parts","arguments":{"query":"10uF X7R 0805","max_results":5}}}'
```

List the tools with `{"jsonrpc":"2.0","id":1,"method":"tools/list"}`. The authoritative schemas are generated from the Java method parameters (parameter names are the argument names). They look like this:

### `search_parts`

Search electronic components across distributors and return ranked, in-stock offers. Only stock that ships now is returned. Results are cached for 5 days (a search that found nothing, for only 1 hour); calling again with a larger `max_results` is served from the cache.

```json
{
  "type": "object",
  "properties": {
    "query": {"type": "string", "description": "Component description or part number, e.g. \"10uF X7R 0805\", \"100nF 50V C0G 0603\", \"2N7002 SOT-23\"."},
    "max_results": {"type": "integer", "description": "Maximum number of parts returned PER DISTRIBUTOR (1-50, default 10). With 3 distributors up to 3 x max_results parts come back. Asking again with a larger value is served from the cache."},
    "distributors": {"type": "array", "items": {"type": "string"}, "description": "Distributors to search: any of \"LCSC\", \"TME\", \"MOUSER\" (case-insensitive). Default: all configured distributors. A listed distributor that is not configured reports error \"not_configured\"."},
    "bypass_cache": {"type": "boolean", "description": "Default false. true skips the cache lookup and queries the distributors live (fresh stock and prices); the results still refresh the cache. Mouser has a small daily API quota, so use it only when fresh data matters. No effect on LCSC (served from a local JLCPCB database)."}
  },
  "required": ["query"]
}
```

Returns a `SearchResponse`. If a distributor is rate limited the call can take up to 2 minutes plus ranking; see [Rate limits and timing](#rate-limits-and-timing). The MCP server request timeout is `3m`; set client timeouts above 2.5 minutes.

### `search_parts_batch`

Run 1 to 20 searches at once, for example every line of a BOM. Same semantics and result shape as `search_parts`. `distributors` and `bypass_cache` apply to every query; each query has its own `max_results`.

```json
{
  "type": "object",
  "properties": {
    "queries": {
      "type": "array",
      "description": "1-20 searches, each {\"query\": \"...\", \"max_results\": 10}; max_results is per distributor (1-50, default 10).",
      "items": {
        "type": "object",
        "properties": {
          "query": {"type": "string", "description": "Component description or part number."},
          "max_results": {"type": "integer", "description": "Parts per distributor, 1-50, default 10."}
        }
      }
    },
    "distributors": {"type": "array", "items": {"type": "string"}},
    "bypass_cache": {"type": "boolean"}
  },
  "required": ["queries"]
}
```

Returns `{"results": [SearchResponse, ...]}` in request order. An empty or missing `queries` is an error. The whole batch shares one 2-minute deadline for rate-limit waits.

### `get_part`

Current details of one part by distributor part number (the `part_number` of a search result).

```json
{
  "type": "object",
  "properties": {
    "distributor": {"type": "string", "description": "\"LCSC\", \"TME\" or \"MOUSER\" (case-insensitive)."},
    "part_number": {"type": "string", "description": "Distributor part number (not the manufacturer part number)."},
    "bypass_cache": {"type": "boolean"}
  },
  "required": ["distributor", "part_number"]
}
```

Returns:

```json
{"found": true, "distributor": "LCSC", "part_number": "C15850", "cache": "not_applicable", "error": null, "part": {"...": "PartResponse"}}
```

`found` is false (and `part` null) when the distributor does not know the part, has no ships-now stock, or failed; `error` then carries the failure code. On a rate limit the call waits and retries for up to 2 minutes before it reports `rate_limited`. The response has no waited-time field. `cache` is `hit`, `miss`, `bypassed` or `not_applicable`. An unknown distributor name is a tool error.

### `list_distributors`

No parameters. Returns the same payload as `GET /api/v1/distributors`: per-distributor state, cache statistics and Laya health. Does not call the Mouser or TME APIs.

### `ping`

No parameters. Returns `{"status": "ok", "version": "<build version>"}`.

## OAuth 2.1 authorization server

Used by Claude's remote connector and any other MCP client that supports OAuth. The access tokens it issues are the same 30-day KINA tokens as the web UI ones. They appear in the web UI as `MCP: <client name>` and can be revoked there. Scope: `kina` (the only scope).

Flow: the client calls `/mcp`, gets 401 with `resource_metadata`, reads the metadata documents, registers at `/oauth/register`, sends the user to `/oauth/authorize` (PKCE `S256`), receives a code on its redirect URI and exchanges it at `/oauth/token`.

### Discovery documents

`GET /.well-known/oauth-protected-resource` and `GET /.well-known/oauth-protected-resource/mcp` (RFC 9728):

```json
{
  "resource": "https://kina.example.com/mcp",
  "authorization_servers": ["https://kina.example.com"],
  "bearer_methods_supported": ["header"],
  "scopes_supported": ["kina"],
  "resource_name": "KINA"
}
```

`GET /.well-known/oauth-authorization-server`, `GET /.well-known/oauth-authorization-server/mcp` and `GET /.well-known/openid-configuration` (RFC 8414; same document at all three):

```json
{
  "issuer": "https://kina.example.com",
  "authorization_endpoint": "https://kina.example.com/oauth/authorize",
  "token_endpoint": "https://kina.example.com/oauth/token",
  "registration_endpoint": "https://kina.example.com/oauth/register",
  "revocation_endpoint": "https://kina.example.com/oauth/revoke",
  "response_types_supported": ["code"],
  "response_modes_supported": ["query"],
  "grant_types_supported": ["authorization_code", "refresh_token"],
  "code_challenge_methods_supported": ["S256"],
  "token_endpoint_auth_methods_supported": ["none", "client_secret_basic", "client_secret_post"],
  "revocation_endpoint_auth_methods_supported": ["none", "client_secret_basic", "client_secret_post"],
  "scopes_supported": ["kina"]
}
```

The origin comes from `KINA_PUBLIC_BASE_URL` or, when empty, from the `X-Forwarded-*` headers of the request.

### `POST /oauth/register`

Dynamic client registration (RFC 7591), anonymous, JSON body.

| Field | Meaning |
|---|---|
| `redirect_uris` | Required, 1 to 20 absolute URIs without fragment. `https`, `http` for `localhost`, `127.0.0.1` or `[::1]` (any port), or a custom scheme. `javascript`, `data`, `file`, `vbscript`, `about`, `blob`, `ftp`, `ws`, `wss` are rejected. |
| `client_name` | Optional, up to 200 characters. Shown on the consent page. |
| `token_endpoint_auth_method` | `none` (default), `client_secret_basic` or `client_secret_post`. |
| `grant_types` | Default `["authorization_code", "refresh_token"]`; must include `authorization_code`. |
| `response_types` | Only `["code"]`. |
| `scope` | Optional. |

```bash
curl -s -X POST https://kina.example.com/oauth/register -H 'Content-Type: application/json' \
  -d '{"client_name":"My client","redirect_uris":["http://localhost:8765/callback"],"token_endpoint_auth_method":"none"}'
```

Returns 201 with `client_id`, `client_secret` (only when the method is not `none`; it is shown once and stored hashed), `client_id_issued_at`, `client_secret_expires_at` (always 0) and the registered metadata. Errors are `invalid_redirect_uri` or `invalid_client_metadata` with status 400.

### `GET /oauth/authorize`

Requires a signed-in user (`dev`: automatic; `prod`: OIDC login, then the request resumes). Parameters:

| Parameter | Meaning |
|---|---|
| `response_type` | Must be `code`. |
| `client_id` | A registered client. |
| `redirect_uri` | Exact match with a registered URI. May be omitted when the client registered exactly one. |
| `code_challenge`, `code_challenge_method` | Required. Method must be `S256`. |
| `state` | Echoed back on the redirect. |
| `scope` | Accepted; the granted scope is always `kina`. |
| `resource` | Stored and echoed. A value outside the public origin is logged, not rejected. |

It renders a consent page (Approve and Deny buttons, `POST /oauth/authorize`). Approve redirects to `redirect_uri?code=...&state=...` with a single-use code valid for 10 minutes. Deny redirects with `error=access_denied`. An unknown client or an unregistered `redirect_uri` shows an error page with status 400 and never redirects. Other request problems redirect to the client with `error` and `error_description`.

### `POST /oauth/token`

Form-encoded (`application/x-www-form-urlencoded`). Clients registered with a secret authenticate with HTTP Basic or `client_secret` in the form; public clients send only `client_id`.

Authorization code grant:

```bash
curl -s -X POST https://kina.example.com/oauth/token \
  -d grant_type=authorization_code -d client_id=<client_id> -d code=<code> \
  -d redirect_uri=<redirect_uri> -d code_verifier=<verifier>
```

Refresh token grant (rotation: the old refresh token and its access token are revoked, a new pair is issued):

```bash
curl -s -X POST https://kina.example.com/oauth/token \
  -d grant_type=refresh_token -d client_id=<client_id> -d refresh_token=<refresh_token>
```

Response:

```json
{"access_token": "kina_...", "token_type": "Bearer", "expires_in": 2592000, "refresh_token": "kina_rt_...", "scope": "kina"}
```

The code is single use: the first attempt consumes it, even if PKCE verification then fails. Errors are `{"error": "...", "error_description": "..."}`: `invalid_request`, `invalid_client` (401), `invalid_grant`, `unauthorized_client`, `unsupported_grant_type`, `invalid_scope`. Refresh tokens last 90 days (`kina.oauth.refresh-token-validity`). Revoking an access token, in the web UI or at `/oauth/revoke`, also revokes the refresh tokens issued with it.

### `POST /oauth/revoke`

RFC 7009. Form-encoded `token=<access or refresh token>`, with the same client authentication as the token endpoint. A refresh token is revoked together with its access token, and an access token together with its refresh tokens. Unknown tokens and tokens of other clients are ignored. The answer is always 200 once the client is authenticated.

## Web UI endpoints

These need a signed-in user (`prod`: OIDC session, `dev`: automatic) and use CSRF-protected forms.

| Endpoint | Purpose |
|---|---|
| `GET /` | List your tokens. |
| `POST /tokens` | Create a token (`name`, up to 100 characters). The plaintext is shown once. |
| `POST /tokens/{id}/revoke` | Revoke one of your tokens. For an OAuth token this also revokes its refresh tokens. |
| `GET`/`POST /oauth/authorize` | Consent page and decision. |
