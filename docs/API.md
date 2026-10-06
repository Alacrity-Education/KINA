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

A token is either a static token from the web UI (`kina_` followed by 43 characters, valid 30 days) or an access token issued by the OAuth flow (same format and table, valid 1 hour, `kina.oauth.access-token-validity`).

A missing, unknown, expired or revoked token gives `401` with an `application/problem+json` body and:

```
WWW-Authenticate: Bearer realm="kina", resource_metadata="https://<host>/.well-known/oauth-protected-resource"[, error="invalid_token"]
```

`error="invalid_token"` is added only when a token was sent. The `resource_metadata` link is how MCP clients find the authorization server.

In `prod` with required groups (`OIDC_REQUIRED_GROUPS`), a user who is no longer a member is blocked: every token of that user gives `401` with `error="invalid_token"`, and the web session ends. A static token of a member triggers a background membership re-check at most once per user per interval; the request itself never waits for the identity provider.

`/actuator/health` and `/actuator/info` are public.

CORS is enabled (any origin, no credentials) for `/mcp`, `/.well-known/**`, `/oauth/register`, `/oauth/token` and `/oauth/revoke`.

## REST API

### Common types

`SearchResponse`:

| Field | Type | Meaning |
|---|---|---|
| `query` | string | The query as sent. |
| `parsed` | object | What KINA understood: `family`, value fields such as `capacitance` or `resistance` (display form, for example `"10uF"`), `tolerance` (`.1%` and `0.1%` both work), `dielectric`, `package`, `mounting`, `technology` (resistors, capacitors, inductors: `thin film`, `thick film`, `metal film`, `carbon film`, `metal oxide`, `wirewound`, `metal foil`, `metal strip`, `current sense`; `ceramic`, `tantalum`, `tantalum polymer`, `polymer`, `aluminium electrolytic`, `film`, `polypropylene`, `polyester`, `PPS`, `supercapacitor`; `multilayer`), `keywords`, and for connectors `connector` (see [`parsed.connector`](#parsedconnector)). Absent values are omitted. |
| `ranking` | string | `blended` (deterministic score blended 50/50 by rank with the in-process cross-encoder) or `fallback` (deterministic order only). |
| `ranking_note` | string or null | Why the ranking fell back. Always present, null when `ranking` is `blended`. See [Ranking notes](#ranking-notes). |
| `distributors` | array | One `DistributorResult` per searched distributor. |

### Ranking notes

Ranking is the deterministic parametric score blended 50/50 by rank with a cross-encoder model that runs inside KINA. When the model cannot score, KINA returns the deterministic order, sets `ranking` to `fallback` and explains why in `ranking_note`. A search never fails because of the model.

| `ranking_note` | Meaning |
|---|---|
| `cross-encoder disabled` | `KINA_CROSS_ENCODER_ENABLED=false`. |
| `cross-encoder model not loaded yet` | The model is still downloading or loading (first start), or the download failed and is retried hourly. |
| `cross-encoder timeout after 5s` | Scoring did not finish within the ranking budget (`kina.ranking.timeout`). |
| `cross-encoder timeout: budget exhausted` | No ranking time was left before the model was called. |
| `cross-encoder busy: no free slot within 5s` | Other searches were using all scoring slots for the whole budget. |
| `cross-encoder failed: <reason>` | The model raised an error. |
| `batch ranking budget of 60s exhausted` | Batch only: the query was reached after the batch ranking budget (`kina.ranking.batch-timeout`) ran out. |

### `parsed.connector`

Present when KINA reads the query as a connector request (`parsed.family` is then `"connector"`). Absent attributes are omitted.

| Field | Type | Meaning |
|---|---|---|
| `type` | string | For example `pin header`, `female header`, `box header`, `terminal block`, `usb-c`, `micro usb`, `usb`, `fpc`, `rj45`, `d-sub`, `barrel jack`, or a generic `connector`. |
| `series` | string | A series such as JST `XH`, `PH`, `GH`, `SH`, `ZH`. |
| `gender` | string | `male` or `female`. |
| `positions` | integer | Number of positions. Read from `6-position`, `6 pos`, `6 pin`, `6P`, `6 way`, `PIN: 6` or from `rows x pins`. IC packages such as `SOIC-8` are not read as positions. |
| `rows` | integer | From `1x6`, `2x3`, "single row", "dual row". |
| `pitch` | string | Display form, for example `"2.54mm"`. `0.1"` becomes `2.54mm`. `dupont` implies `2.54mm`. |
| `orientation` | string | `right angle` or `vertical`. |
| `usb_type` | string | USB requests only. `Type-C`, `Micro-B`, `Micro-AB`, `Mini-B`, `Mini-AB`, `Type-A` or `Type-B`. |
| `usb_standard` | string | USB requests only. Canonical name: `USB 2.0`, `USB 3.2 Gen 1`, `USB 3.2 Gen 2`, `USB 3.2 Gen 2x2`, `USB4`, `Thunderbolt 3`, `Thunderbolt 4`, `USB 1.1`, or `USB 3.x` when a 3.x version is written without a generation (for example `USB 3.1`). |
| `usb_speed_gbps` | number | USB requests only. Speed class: 0.48 (USB 2.0), 5 (USB 3.0, 3.1 Gen 1, 3.2 Gen 1, and `USB 3.x`), 10 (3.1 Gen 2, 3.2 Gen 2), 20 (Gen 2x2), 40 (USB4). |
| `pin_configuration` | integer | USB requests only. The canonical pin configuration: your `positions` normalised (17 or 18 to 16, 25 or 26 to 24, 7 or 8 to 6; 14 stays 14), or the implied one. |
| `pin_configuration_implied` | boolean | Only present, as `true`, when you gave no pin count and KINA inferred it from the standard (see below). |
| `shield_pins_counted` | integer | USB requests only. 1 or 2 when your `positions` count shell or mounting pins on top of the configuration (17 gives 1, 18 gives 2). |
| `mounting_style` | string | USB requests only. `mid-mount`, `hybrid` or `top-mount`. Plain `SMD` or `THT` stays in `parsed.mounting`. |
| `features` | array of strings | USB requests only. For example `power only`, `PD`, `waterproof`, `board lock`. |

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
| `score` | number or null | 0 to 1. Orders the list. It is relative to the other candidates, so the last of several good parts can show 0. Null for single-part lookups. |
| `match` | number or null | 0 to 1, two decimals. How well the part satisfies the stated parameters: 1.0 means every stated parameter is known and matches; a parameter the distributor does not state counts as not matched. Does not change the order. Null for single-part lookups. |
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
| `attributes` | object | Parametric attributes, for example `{"Capacitance": "10uF"}`. Resistors, capacitors and inductors carry `Technology` (same values as `parsed.technology`) when the distributor data names it. A chip resistor or capacitor without a stated package gets `Package` from a known MPN series (`TNPW0805...`, `RC0805...`, TE `RN73C2A...`). Connector parts also carry `ConnectorType`, `Series`, `Gender`, `Positions`, `Rows`, `Pitch`, `Orientation` and `Mounting` when the distributor data allows it. USB connector parts add `UsbType`, `UsbStandard`, `UsbSpeedGbps`, `PinConfiguration`, `ShieldPinsCounted`, `MountingStyle`, `Waterproof` (the IP rating, or `yes`) and `Features` (comma separated). `Positions` stays as the distributor reported it; `PinConfiguration` is the canonical count. |
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

### USB connector queries

KINA reads USB wording in detail: type (Type-C or USB-C, Micro-B, Micro-AB, Mini-B, Type-A, Type-B, "USB 3.0 Micro-B"), gender (receptacle, socket, female versus plug, male), standard, Type-C pin configuration, mounting style (SMD, THT, hybrid, mid-mount, top-mount), orientation and features (power only, PD, waterproof or IPX7, board lock). Fields are in [`parsed.connector`](#parsedconnector).

Standards map to speed classes: USB 2.0 is 480 Mbps. USB 3.0, USB 3.1 Gen 1 and USB 3.2 Gen 1 are the same class (5 Gbps). USB 3.1 Gen 2 and USB 3.2 Gen 2 are 10 Gbps. USB 3.2 Gen 2x2 is 20 Gbps. USB4 is 40 Gbps.

**Pin-count normalisation.** Distributors sometimes count 1 or 2 shell or mounting pins, so a 16-pin Type-C can be listed as 17P or 18P. KINA keeps `Positions` as reported and derives `PinConfiguration` and `ShieldPinsCounted`. Mapping: 17 or 18 to 16, 25 or 26 to 24, 7 or 8 to 6; 14 stays 14. Ranking compares configurations on both sides. A 17P listing fully matches a 16-pin request, and a "17 pin" request matches 16-pin parts. No distributor lists a 17P or 18P Type-C part in the data seen on 2026-10-05, so this rule is covered by tests and by the "17 pin" request direction.

**Inference.** Without a stated pin count, USB 2.0 Type-C implies 16 pins and USB 3.x Type-C implies 24. The response then has `pin_configuration_implied: true`. On the part side, a Type-C with 12 to 16 pins is treated as USB 2.0 and one with 2 to 6 pins as power only, whatever the distributor label says. JLCPCB labels many 16P and 6P parts "USB 3.1".

Ranking for USB requests replaces the generic connector weights. Unknown attributes never lower a score.

| Signal | Weight |
|---|---|
| USB type | +0.30 match, -0.30 mismatch |
| Pin configuration | +0.20 match, -0.20 mismatch (half of that when only implied) |
| Standard | +0.20 same speed class, +0.10 higher class, -0.20 lower class or power-only part |
| Gender | +0.15 / -0.15 |
| Mounting style | +0.10 / -0.10 |
| Orientation | +0.05 / -0.05 |
| Features | +0.03 for each requested feature the part has (waterproof, board lock, power only) |

Scores cap at 1.0, so complete matches can tie. Tied parts keep the distributor's order.

Example phrases for `USB-C receptacle 16 pin SMD USB 2.0`:

| Distributor | `distributor_query` |
|---|---|
| LCSC | `"USB Connectors" Type-C 16P/17P/18P "USB 2.0" "Surface Mount"` |
| TME | `USB C socket SMT 2.0` |
| MOUSER | `USB type C receptacle SMD 2.0` |

LCSC groups the pin alternatives, so 17P and 18P listings are not excluded. TME and Mouser phrases leave out the pin count. The one exception is a power-only request, where the Mouser phrase adds `6 pos power only`.

Data quality differs by distributor. LCSC has description text only (mid-mount appears as "Recessed" or "Sink board", waterproofing only in part numbers). TME parameters are the richest: type, gender, pins, version, data rate, mounting variant, charging only, IP rating and hybrid. Mouser's search API returns no USB attributes, so everything comes from descriptions, and many Mouser descriptions give no pin count.

Checked live on 2026-10-05: `USB-C receptacle 17 pin` returns 16-pin Type-C parts at all three distributors (TME `USB4145-03-0170-C`, Mouser `217182-0001` and `DX07S016JA3R1500`).

### Rate limits and timing

When Mouser or TME rate limit a call, KINA waits and retries instead of failing at once.

- Triggers: HTTP 429; HTTP 502, 503 or 504 only when a `Retry-After` header is present; Mouser's in-body error code `TooManyRequests` on HTTP 200. A 503 without `Retry-After` is an outage and fails fast with `unavailable`. TME has no throttling error code, so only the statuses count.
- Wait: the `Retry-After` value (seconds or HTTP date, at least 1 s). Without it, 2, 4, 8, 16, 30, 30... seconds with 20 percent jitter, never below 1 s. KINA retries while the next wait fits inside the request deadline.
- Request deadline: `kina.search.max-request-duration`, default `2m`, for each incoming request. A batch shares one deadline for all its queries. The waits extend a distributor's 12 s work budget but never the deadline.
- Shared cool-down: after a rate limit, other calls to the same distributor wait for the cool-down to end if that fits their deadline. Otherwise they fail at once with `rate_limited`, without calling the distributor.
- Result: `rate_limit_waited_ms` in each distributor entry tells how long KINA waited. `error: "rate_limited"` means the limit outlasted the deadline. Parts fetched before that (earlier pages, a cached list) are still returned.
- Ranking runs after fetching (5 s per query, 60 s per batch). The worst case is therefore about 2 minutes plus ranking.
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

Returns one `PartResponse` (without `rank`, `score` and `match`, prices trimmed to 3 brackets). The part number can also be the manufacturer part number; spelling differences in hyphens and spaces are ignored (`ERA6AEB5361V` finds Mouser `667-ERA-6AEB5361V`). It returns 404 when the part is not available: the problem has `reason` `not_found` (the distributor does not know it) or `out_of_stock` (listed, but no ships-now stock; `identity` then gives `part_number`, `manufacturer`, `mpn` and `description`). Lookup failures return 503, 429, 504 or 502 (see below). On a rate limit the call waits and retries for up to 2 minutes; 429 means the limit outlasted that. The response has no `rate_limit_waited_ms`.

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
  "ranking": {"mode": "blended", "cross_encoder_enabled": true, "ready": true,
              "model": "cross-encoder/ms-marco-MiniLM-L6-v2", "model_variant": "int8",
              "model_revision": "<hugging face commit>", "model_dir": "/data/cross-encoder",
              "threads": 4, "avg_latency_ms": 180.0, "last_error": null,
              "max_candidates": 40, "weight": 0.5, "timeout": "PT5S"}
}
```

The `ranking` object:

| Field | Meaning |
|---|---|
| `mode` | `blended` when the cross-encoder is enabled and loaded, else `fallback`. |
| `cross_encoder_enabled` | `kina.ranking.cross-encoder.enabled`. |
| `ready` | The model is loaded and warmed up. |
| `model`, `model_variant`, `model_revision`, `model_dir` | Model name, `int8` or `fp32`, source revision (Hugging Face commit, null when unknown) and directory. |
| `threads` | ONNX Runtime threads used for scoring. |
| `avg_latency_ms` | Mean scoring time per query since start. Null before the first scored query. |
| `last_error` | Why the model is not loaded. Null when fine. |
| `max_candidates`, `weight`, `timeout` | Candidates scored per query (40), weight of the model in the blend (0.5) and ranking budget per query (ISO-8601 duration). |

Numbers above are placeholders. `available` for TME and Mouser means "configured"; there is no live probe. `cache` is null when the database cannot be read. Null fields in a distributor entry are omitted.

### Errors

REST errors are RFC 9457 `application/problem+json`. They never contain stack traces. Types are `urn:kina:problem:<name>`.

| Status | `type` | When |
|---|---|---|
| 400 | `urn:kina:problem:validation` | Blank `q`, `max_results` out of range, bad batch body, malformed JSON. Body and parameter validation add `errors: [{"field": "...", "message": "..."}]`. |
| 400 | `urn:kina:problem:unknown-distributor` | A distributor name other than LCSC, TME, MOUSER. |
| 401 | `about:blank` | Missing or invalid token (see Authentication). |
| 404 | `urn:kina:problem:not-found` | Unknown or out-of-stock part. Adds `distributor`, `part_number`, `reason` (`not_found` or `out_of_stock`) and, for `out_of_stock`, `identity`. |
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

Current details of one part by distributor part number (the `part_number` of a search result) or by manufacturer part number.

```json
{
  "type": "object",
  "properties": {
    "distributor": {"type": "string", "description": "\"LCSC\", \"TME\" or \"MOUSER\" (case-insensitive)."},
    "part_number": {"type": "string", "description": "Distributor part number, or the manufacturer part number."},
    "bypass_cache": {"type": "boolean"}
  },
  "required": ["distributor", "part_number"]
}
```

Returns:

```json
{"found": true, "distributor": "LCSC", "part_number": "C15850", "cache": "not_applicable", "error": null, "reason": null, "part": {"...": "PartResponse"}}
```

`found` is false (and `part` null) in three cases. `reason: "not_found"`: the distributor does not know the part. `reason: "out_of_stock"`: it lists the part but has no ships-now stock; `identity` names it:

```json
{"found": false, "distributor": "MOUSER", "part_number": "ERA6AEB5361V", "cache": "miss", "error": null, "reason": "out_of_stock",
 "identity": {"part_number": "667-ERA-6AEB5361V", "manufacturer": "Panasonic", "mpn": "ERA-6AEB5361V", "description": "Thin Film Resistors - SMD 0805 5.36Kohm 0.1% 25ppm"}, "part": null}
```

The lookup failed: `error` carries the failure code and `reason` is null. On a rate limit the call waits and retries for up to 2 minutes before it reports `rate_limited`. The response has no waited-time field. `cache` is `hit`, `miss`, `bypassed` or `not_applicable`. An unknown distributor name is a tool error.

### `list_distributors`

No parameters. Returns the same payload as `GET /api/v1/distributors`: per-distributor state, cache statistics and ranking status. Does not call the Mouser or TME APIs.

### `ping`

No parameters. Returns `{"status": "ok", "version": "<build version>"}`.

## OAuth 2.1 authorization server

Used by Claude's remote connector and any other MCP client that supports OAuth. The access tokens it issues are KINA tokens like the web UI ones, but they live 1 hour (`expires_in` is 3600) and come with a 30-day refresh token. They appear in the web UI as `MCP: <client name>` and can be revoked there. Scope: `kina` (the only scope).

Flow: the client calls `/mcp`, gets 401 with `resource_metadata`, reads the metadata documents, identifies itself (an `https` `client_id` URL, or a registration at `/oauth/register`), sends the user to `/oauth/authorize` (PKCE `S256`), receives a code on its redirect URI and exchanges it at `/oauth/token`.

In `prod`, the user signs in at the organisation's OIDC provider. With required groups configured, a user outside the groups ends on `GET /login-denied` (see [Web UI endpoints](#web-ui-endpoints)) and no code is issued.

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
  "scopes_supported": ["kina"],
  "client_id_metadata_document_supported": true
}
```

`client_id_metadata_document_supported` is `true` unless `kina.oauth.trusted-client-metadata-hosts` is empty. See [Client ID Metadata Documents](#client-id-metadata-documents).

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

The endpoint is rate limited per client IP (`kina.oauth.register-rate-limit-per-minute`, default 30, `0` disables). Over the limit KINA answers `429` with a `Retry-After` header (seconds) and the body `{"error": "too_many_requests", "error_description": "..."}`. The client IP is the one the reverse proxy reports in `X-Forwarded-For`, so the proxy must overwrite that header.

Dynamically registered clients that were not used for 90 days and hold no live token are deleted by a daily cleanup. `oauth_clients.last_used_at` records each token issuance.

### Client ID Metadata Documents

Instead of registering, a client may use an `https` URL as its `client_id`. The URL points to a JSON document that describes the client (draft-ietf-oauth-client-id-metadata-document; Claude Code uses `https://claude.ai/oauth/claude-code-client-metadata`). KINA accepts this only for hosts in `kina.oauth.trusted-client-metadata-hosts` (default `claude.ai`, `claude.com`, `*.anthropic.com`).

- KINA fetches the document with a 5 second timeout, a 1 MB cap and no redirects, and caches it for 1 hour (`kina.oauth.client-metadata-cache`). The client is stored in `oauth_clients` with `metadata_url`.
- The document's `client_id` must equal the URL. `redirect_uris` must be valid. `token_endpoint_auth_method` must be absent or `none`.
- `redirect_uri` must match an entry of the document exactly. A loopback `http` entry (`http://localhost/callback`) accepts any port.
- An unknown host or an invalid document gives an error page at `/oauth/authorize` (never a redirect) and `invalid_client` at `/oauth/token`.
- Consent is skipped only for trusted documents with a non-loopback redirect URI (for example claude.ai). Loopback redirects (Claude Code) still show the Approve page.

### `GET /oauth/authorize`

Requires a signed-in user (`dev`: automatic; `prod`: OIDC login, then the request resumes). Parameters:

| Parameter | Meaning |
|---|---|
| `response_type` | Must be `code`. |
| `client_id` | A registered client, or an `https` URL of a Client ID Metadata Document on a trusted host. |
| `redirect_uri` | Exact match with a registered URI (or with an entry of the metadata document; loopback `http` entries accept any port). May be omitted when the client registered exactly one. |
| `code_challenge`, `code_challenge_method` | Required. Method must be `S256`. |
| `state` | Echoed back on the redirect. |
| `scope` | Accepted; the granted scope is always `kina`. |
| `resource` | Stored and echoed. A value outside the public origin is logged, not rejected. |

In `prod`, the user is sent to the OIDC provider first. With required groups, a refused user sees `/login-denied` and the request does not continue. It renders a consent page (Approve and Deny buttons, `POST /oauth/authorize`); trusted metadata-document clients with a non-loopback redirect URI skip it. Approve redirects to `redirect_uri?code=...&state=...` with a single-use code valid for 10 minutes. Deny redirects with `error=access_denied`. An unknown client, an untrusted or invalid metadata document, or an unregistered `redirect_uri` shows an error page with status 400 and never redirects. Other request problems redirect to the client with `error` and `error_description`.

### `POST /oauth/token`

Form-encoded (`application/x-www-form-urlencoded`). Clients registered with a secret authenticate with HTTP Basic or `client_secret` in the form; public clients (and metadata-document clients) send only `client_id`, which may be the `https` URL.

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
{"access_token": "kina_...", "token_type": "Bearer", "expires_in": 3600, "refresh_token": "kina_rt_...", "scope": "kina"}
```

The code is single use: the first attempt consumes it, even if PKCE verification then fails. **Refresh semantics.** In `prod` with required groups, a refresh grant first re-checks the user's group membership at the identity provider when the last check is older than `KINA_MEMBERSHIP_RECHECK_INTERVAL` (1 hour). The check is synchronous:

- Still a member: the old pair is rotated and a new pair is issued.
- No longer a member, or the provider reports the grant as invalid: `invalid_grant`. The user is blocked and all their tokens are revoked.
- Provider unreachable: access continues for `KINA_MEMBERSHIP_GRACE` (4 hours) after the last successful check. After that the answer is `invalid_grant` ("identity provider unreachable"). Nothing is revoked, and the same refresh token works again when the provider is back.
- No stored provider token (no `KINA_TOKEN_ENCRYPTION_KEY`): refresh works for 24 hours after the user's last interactive login, then `invalid_grant` ("sign in again").

Claude reacts to `invalid_grant` by asking the user to reconnect.

Errors are `{"error": "...", "error_description": "..."}`: `invalid_request`, `invalid_client` (401), `invalid_grant`, `unauthorized_client`, `unsupported_grant_type`, `invalid_scope`. `expires_in` is the real lifetime of the access token (`kina.oauth.access-token-validity`, default 1 hour). Refresh tokens last 30 days (`kina.oauth.refresh-token-validity`) and rotate on every use. Revoking an access token, in the web UI or at `/oauth/revoke`, also revokes the refresh tokens issued with it.

### `POST /oauth/revoke`

RFC 7009. Form-encoded `token=<access or refresh token>`, with the same client authentication as the token endpoint. A refresh token is revoked together with its access token, and an access token together with its refresh tokens. Unknown tokens and tokens of other clients are ignored. The answer is always 200 once the client is authenticated.

## Web UI endpoints

These need a signed-in user (`prod`: OIDC session, `dev`: automatic) and use CSRF-protected forms.

| Endpoint | Purpose |
|---|---|
| `GET /` | List your tokens, with a short explanation of how to connect Claude. When `kina.tokens.ui-enabled` is `false` it shows only that explanation. |
| `POST /tokens` | Create a static token (`name`, up to 100 characters). The plaintext is shown once. Returns `404` when `kina.tokens.ui-enabled` (`KINA_TOKENS_UI_ENABLED`) is `false`; existing tokens keep working until they expire or are revoked. |
| `POST /tokens/{id}/revoke` | Revoke one of your tokens. For an OAuth token this also revokes its refresh tokens. |
| `GET`/`POST /oauth/authorize` | Consent page and decision. |
| `GET /login-denied` | Shown after a login that the group or e-mail policy refused. Status `403`. Query `reason`: `email_missing` (the provider sent no e-mail address), `email_unverified`, `email_domain` or `group` (`email` is the older, general form). The page has one sentence for the reason and names the required groups or the allowed domains. The refused domain, and the note that earlier tokens no longer work, come only from the browser session of that login and are shown once. The user gets no session. Public. |
