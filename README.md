# KINA

KINA is an MCP server and HTTP API that lets Claude search electronic components. You describe a part ("10uF X7R 0805 MLCC 25V") and KINA queries three distributors, drops everything that is not in stock, ranks the rest and returns full part details: prices, stock, datasheet, photo, parametric attributes.

The distributors are LCSC (served from the JLCPCB parts database, downloaded and read locally), TME (API v2) and Mouser. Results from TME and Mouser are cached in PostgreSQL for five days. Ranking is a deterministic parametric score blended 50/50 by rank with a small cross-encoder model (`cross-encoder/ms-marco-MiniLM-L6-v2`, Apache-2.0) that runs inside the KINA process on the CPU. If the model is not loaded, slow or disabled, KINA falls back to the deterministic ranking and says so in the response.

## Features

- MCP over Streamable HTTP (stateless) at `/mcp`, plus a REST API under `/api/v1`.
- Three distributors: LCSC (JLCPCB database), TME, Mouser. Each one fails on its own; a broken distributor never fails the whole search.
- Only stock that ships now is returned. Out-of-stock, on-order and factory-stock offers are never ranked, cached or returned.
- Prices are trimmed to the 3 smallest quantity brackets.
- `max_results` is per distributor (1 to 50, default 10). Every distributor entry also reports how many matches the distributor found, how many KINA holds, and how many it returned.
- Cache of 5 days for TME and Mouser. Ask again with a larger `max_results` and the answer comes from the cache; KINA only calls the distributor when the cache holds too few parts.
- `bypass_cache` skips the cache lookup and still refreshes the cache.
- Phrase fallback: when Mouser or TME find nothing for the full query, KINA retries once with the parametric core of the query (for example `MOSFET 30V SOT-23` for `SOT-23 N-channel MOSFET 30V`) and reports it in `fallback_query`. A query with only keywords gets the 3 to 5 most informative tokens instead.
- Distributor phrasing: when KINA rewrites a connector request for a distributor, `distributor_query` shows the phrase it sent. It is null when your text went through as written. The cache key stays your own query text.
- Connector-aware search. Describe a connector in plain words ("90 degree dupont style female pin header, THT, 6 position") and KINA extracts the type, gender, positions, rows, pitch, orientation and mounting, then rewrites the request into each distributor's own vocabulary (`distributor_query`). Accepted wording:
  - Types: pin header (male), female header, socket or receptacle, box or shrouded header, terminal block or screw terminal, JST series XH, PH, GH, SH and ZH, USB-C, micro USB, FPC or FFC, RJ45, D-sub, barrel jack, or just "connector".
  - Positions: `6-position`, `6 pos`, `6 pin`, `6P`, `6 way`, `PIN: 6`.
  - Rows: `1x6`, `2x3`, "single row", "dual row".
  - Pitch: `2.54mm`, `0.1"`, `1.27mm`. The word `dupont` implies a 2.54 mm header.
  - Orientation: "right angle", `90°`, "angled", "horizontal" versus "vertical" or "straight". Mounting: `THT` or `SMD`.
  - IC packages such as `SOIC-8`, `LQFP-48` and `SOT-23-6` are not read as connector positions.
- USB connector precision. USB requests are read in more detail than other connectors:
  - Type: Type-C or USB-C, Micro-B, Micro-AB, Mini-B, Type-A, Type-B, "USB 3.0 Micro-B", "USB 3.0 Type-A".
  - Gender: receptacle, socket or female versus plug or male.
  - Standard, mapped to one speed class: USB 2.0 is 480 Mbps. USB 3.0, USB 3.1 Gen 1 and USB 3.2 Gen 1 are all 5 Gbps. USB 3.1 Gen 2 and USB 3.2 Gen 2 are 10 Gbps. USB 3.2 Gen 2x2 is 20 Gbps. USB4 is 40 Gbps.
  - Type-C pin configuration: 6P (power only), 12P, 14P, 16P (USB 2.0) and 24P (full featured).
  - Mounting style: SMD, THT, hybrid, mid-mount (also "recessed"), top-mount. Also orientation.
  - Features: power only, PD, waterproof or IPX7, board lock.
  - Examples: `USB-C receptacle 16 pin SMD USB 2.0`, `USB Type-C 24 pin USB 3.1 receptacle horizontal`, `micro USB B receptacle 5 pin SMD`, `USB-C 6 pin power only`, `waterproof USB-C receptacle IP67`, `mid-mount USB-C 16P`.
  - Pin counts are normalised. Some distributors count 1 or 2 shell or mounting pins, so a 16-pin connector can be listed as 17P or 18P. KINA keeps the reported `Positions` and adds the canonical `PinConfiguration`. Ranking compares configurations, so a 17P listing fully matches a 16-pin request, and a "17 pin" request finds 16-pin parts.
- Batch search of up to 20 queries in one call.
- Graceful rate limits: when Mouser or TME answer with a rate limit, KINA waits and retries instead of failing at once, for up to 2 minutes per request (`kina.search.max-request-duration`). `rate_limit_waited_ms` in each distributor entry says how long it waited.
- Ranking: deterministic parametric ranker blended 50/50 by rank with an in-process cross-encoder (ONNX Runtime, CPU, no extra container, nothing leaves the host), with a 5 s budget per query and an automatic fallback (`ranking: "fallback"`). Search never fails because of the model.
- OAuth 2.1 authorization server for Claude's remote connector (dynamic client registration, PKCE, Client ID Metadata Documents, consent page). Login is delegated to your organisation's OIDC provider.
- Access by group: in `prod`, only members of the configured groups can sign in. KINA checks again on every refresh and on bearer requests, so a removed member is cut off within about an hour.
- Two security modes: `dev` (no login, fake admin) and `prod` (OIDC login, bearer tokens required).
- Provider-agnostic OIDC: only the issuer URL, client id and client secret are required, plus the group names. Authentik is the worked example in [docs/OPERATIONS.md](docs/OPERATIONS.md#authentik-setup); Keycloak, Google, Entra ID and other compliant providers work unchanged.
- Static access tokens (30 days) for scripts and machines without a browser, created in the web UI. They can be switched off.

## Quick start (Docker Compose)

### Prerequisites

- Docker with the Compose plugin.
- About 7 GB of free disk: the JLCPCB database takes 5.3 GB once unpacked (about 1 GB more for the zip while it downloads), the ranking model 23 MB (int8) or 91 MB (fp32), and the images about 0.6 GB.
- RAM: about 2 GB free is a safe minimum, 4 GB is comfortable. Measured: `kina` about 485 MiB before the model was added, PostgreSQL about 45 MiB. The model adds an estimated 100 to 250 MB outside the JVM heap. See [Measured numbers](#measured-numbers) and [docs/OPERATIONS.md](docs/OPERATIONS.md).
- Optional: a Mouser API key and TME API v2 credentials. Without them those distributors report `not_configured` and LCSC still works.

### Start

```bash
cp .env.example .env        # then edit .env: MOUSER_API_KEY, TME_TOKEN, TME_APPLICATION_SECRET
docker compose up -d --build
```

`.env` is git-ignored. Never commit it.

### What happens on the first start

- The `kina` image is built (Maven build, a few minutes).
- KINA starts and, in the background, downloads the JLCPCB parts database (about 1 GB zipped, 5.3 GB on disk) into the `kina-data` volume. KINA does not wait for it. Until the file is ready, LCSC reports `unavailable` ("JLCPCB database not downloaded yet") and TME and Mouser work normally.
- Also in the background, KINA downloads the ranking model from Hugging Face into `/data/cross-encoder` on the same volume (about 23 MB for the default int8 file, a few seconds). It checks size and SHA-256 and records the revision in `model.json`. Until the model is loaded, searches still work and report `ranking: "fallback"` with the note `cross-encoder model not loaded yet`. If the download fails, KINA logs it once and tries again every hour. Files that are already in the directory are used without downloading.
- The JLCPCB database is downloaded again when it is older than 5 days.

### Check that it is ready

```bash
curl -s localhost:8080/actuator/health          # {"status":"UP"}
docker compose ps                               # kina and postgres show "healthy"
docker compose logs -f kina                     # download progress and errors
```

Then call the `list_distributors` MCP tool, or the REST equivalent:

```bash
curl -s localhost:8080/api/v1/distributors
```

Check that LCSC is `available: true` (with the JLCPCB part count), that TME and Mouser are `configured: true`, and that `ranking.ready` is `true` (`ranking.mode` is then `blended`). A first search with `ranking: "blended"` confirms it. In `prod` mode the REST call needs a bearer token.

### Web UI

Open `http://localhost:8080/` (the port is `KINA_PORT`). The page explains how to connect Claude, lists your access tokens and lets you create and revoke them. Clients that connected through OAuth show up as `MCP: <client name>`. Personal tokens are for scripts and machines without a browser; set `KINA_TOKENS_UI_ENABLED=false` to turn them off.

## Connecting Claude

The examples use `https://<host>` for the public address of KINA. Claude's remote connector needs HTTPS, which KINA does not terminate itself; put a reverse proxy in front (see [docs/OPERATIONS.md](docs/OPERATIONS.md)).

KINA is the OAuth 2.1 authorization server that Claude talks to. When you sign in, KINA sends you to your organisation's login (for example Authentik with Google), checks your group, and then gives Claude its own short-lived tokens. Nothing is pasted by hand. The setup for the administrator is in [Access control by group](#access-control-by-group) and [docs/OPERATIONS.md](docs/OPERATIONS.md#authentik-setup).

### Claude.ai and Claude Desktop (custom connector)

1. Add a custom connector. Use exactly `https://<host>/mcp` as the URL. Keep the default identity option.
2. Click Connect.
3. Sign in with Google through Authentik (or whichever provider your organisation uses).

For Claude's published identity there is no consent page. If Claude registers itself dynamically instead, KINA shows one Approve page. Claude then holds a 1-hour access token and a 30-day refresh token and renews them by itself.

Team and Enterprise plans: an administrator adds the connector once (Organization settings, Connectors, Add, Custom). Members then click Connect and sign in.

### Claude Code

```bash
claude mcp add --transport http kina https://<host>/mcp
```

Then run `/mcp` inside Claude Code and choose to sign in. Your browser opens, you sign in, and you click Approve once. Claude Code waits for the answer on a local port, and KINA always asks for approval in that case. This is by design: any local program could claim to be Claude Code.

For local development in `dev` mode, `claude mcp add --transport http kina http://localhost:8080/mcp` is enough, because no login is needed.

### Static tokens (scripts and machines without a browser)

A static token is a 30-day secret created in the web UI. Use it for scripts that call the HTTP API, or for a Claude Code machine that cannot open a browser:

```bash
claude mcp add --transport http kina https://<host>/mcp --header "Authorization: Bearer <token>"
```

Static tokens are also tied to the group: when a user has left the group, their static tokens stop working at the next re-check. If every user comes through Claude, set `KINA_TOKENS_UI_ENABLED=false`.

Requirements for any connection:

- HTTPS in front of KINA.
- The proxy must send `X-Forwarded-Proto` and `X-Forwarded-Host` (and `X-Forwarded-Port` if the port is not standard), so that the OAuth metadata advertises the public origin. Alternatively set `KINA_PUBLIC_BASE_URL=https://<host>`, which always wins.
- The proxy must leave `/.well-known/*` and `/oauth/*` open to anonymous requests, without bot challenges.


### Plain HTTP API

```bash
curl -s -H "Authorization: Bearer <token>" \
  "https://<host>/api/v1/parts/search?q=10uF%20X7R%200805&max_results=5"
```

The full reference is in [docs/API.md](docs/API.md).

## Security modes

| | `dev` (default) | `prod` |
|---|---|---|
| Set with | `KINA_MODE=dev` | `KINA_MODE=prod` |
| Web UI | No login. Every request runs as the fake user "Development Admin". | OIDC login. With `OIDC_REQUIRED_GROUPS` set, only group members get in. |
| `/api/**` and `/mcp` | Open when no token is sent. A token that is sent is still validated. | Bearer token required (401 otherwise). Blocked users get 401 `invalid_token`. |
| `/oauth/authorize` | Signs in as the fake admin automatically, then shows the consent page. | Redirects to the OIDC provider, checks the group, then shows the consent page (skipped for Claude's published identity). |
| Group checks | Not applicable. | At login, on every refresh grant and on bearer requests. |
| OIDC settings | Not needed. | `OIDC_ISSUER_URI`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET`. KINA refuses to start without issuer and client id. Add `OIDC_REQUIRED_GROUPS` and `KINA_TOKEN_ENCRYPTION_KEY` for group access. |

OIDC redirect URI to register at the provider: `https://<host>/login/oauth2/code/oidc`. Scopes requested: `openid profile email`, plus `OIDC_EXTRA_SCOPES`, plus `offline_access` when required groups and the encryption key are set and the provider advertises it. Discovery uses `<issuer>/.well-known/openid-configuration` and runs on the first login, so KINA starts even if the provider is down.

> **Warning.** Never expose `dev` mode to the internet. Anyone who can reach the port can use the API, create tokens and approve OAuth clients as the admin. Compose publishes `KINA_PORT` on all interfaces, so keep dev mode on trusted networks only, and set `KINA_MODE=prod` for anything public.

`/actuator/health` and `/actuator/info` are public in both modes.

## Access control by group

In `prod`, set `OIDC_REQUIRED_GROUPS` (comma separated; a user needs at least one) and KINA lets in only members of those groups. Nothing in the code is specific to one provider. The groups come from a claim (default `groups`) in the ID token, or from the userinfo endpoint when the ID token has none. Nested names such as `realm_access.roles` and namespaced names such as `https://example.com/groups` work. Group names are compared without regard to case. Optionally `OIDC_ALLOWED_EMAIL_DOMAINS=alacrity.ro` also limits the e-mail domain.

KINA checks membership at three points:

| Point | What happens |
|---|---|
| Login | The group is read from the ID token, else userinfo. A refused user lands on the page `/login-denied` (HTTP 403) that names the group, and gets no session. If that user already existed, they are blocked and all their tokens are revoked. |
| Refresh grant | Claude refreshes its 1-hour access token about every hour. When the last check is older than `KINA_MEMBERSHIP_RECHECK_INTERVAL` (1 hour), KINA asks the provider again before it answers. A refusal is `invalid_grant`, and Claude asks the user to reconnect. |
| Bearer requests | A blocked user gets 401 `invalid_token`, and their web session ends. Static tokens trigger a background re-check at most once per user; the request itself never waits for the provider. |

So a member who is removed from the group is cut off within about an hour.

To re-check later, KINA keeps the provider's refresh token, encrypted with AES-256-GCM. Generate the key and keep it secret:

```bash
openssl rand -base64 32     # put the result in KINA_TOKEN_ENCRYPTION_KEY
```

What happens when something is missing:

- **No encryption key.** Upstream refresh tokens are not stored and KINA logs a WARN at startup. Refresh grants and static tokens still work for 24 hours after the user's last login (`KINA_RELOGIN_INTERVAL_WITHOUT_RECHECK`). After that the user must sign in again, and that login checks the group.
- **Provider unreachable.** Access continues for 4 hours after the last successful check (`KINA_MEMBERSHIP_GRACE`). After that, refresh grants fail with `invalid_grant`. Nothing is revoked, and the same refresh token works again when the provider is back.
- **Provider says the user is no longer a member** (or answers `invalid_grant`). The user is revoked with all their tokens. A later successful login lifts the block.
- **Turning on `OIDC_REQUIRED_GROUPS` on a running deployment.** Existing users must sign in once before their refresh grants and static tokens work again.

Group checks are active only in `prod` and only when at least one required group is set. Authentik setup, step by step, is in [docs/OPERATIONS.md](docs/OPERATIONS.md#authentik-setup).

### Client ID Metadata Documents and registration

Claude can identify itself with an `https` URL as its `client_id`. The URL points to a small JSON document that Anthropic publishes (a Client ID Metadata Document). KINA advertises this (`client_id_metadata_document_supported: true`) and accepts it only for trusted hosts (`KINA_OAUTH_TRUSTED_CLIENT_HOSTS`, default `claude.ai, claude.com, *.anthropic.com`). The document is fetched with a 5 second timeout, a 1 MB limit and no redirects, and cached for 1 hour. Redirect URIs must match the document exactly; loopback `http` entries accept any port. An unknown host or an invalid document gives an error page at `/oauth/authorize` and `invalid_client` at `/oauth/token`, never a redirect.

For trusted documents with a non-loopback redirect URI (claude.ai), the consent page is skipped (`KINA_OAUTH_AUTO_APPROVE_TRUSTED_CLIENTS=true`). Claude Code uses a loopback redirect, so it still sees the Approve page.

Dynamic registration (`POST /oauth/register`) still works for clients without metadata documents. It is limited per client IP (`KINA_OAUTH_REGISTER_RATE_LIMIT_PER_MINUTE`, default 30; the answer is 429 with `Retry-After`). KINA only honours `X-Forwarded-For` when forwarded headers are trusted, so the reverse proxy must overwrite that header. A daily cleanup deletes dynamically registered clients that were unused for 90 days and hold no live tokens.


## Configuration reference

Set variables in `.env` (read by Compose). Everything is optional unless noted.

### Distributors

| Variable | Default | Meaning |
|---|---|---|
| `MOUSER_API_KEY` | empty | Mouser Search API key. Empty means Mouser is `not_configured`. |
| `TME_TOKEN` | empty | TME API v2 token (OAuth2 client credentials). |
| `TME_APPLICATION_SECRET` | empty | TME API v2 application secret. Both TME values are needed. |
| `COUNTRY` | `RO` | Country for TME prices and stock (ISO 3166-1 alpha-2). |

### Security

| Variable | Default | Meaning |
|---|---|---|
| `KINA_MODE` | `dev` | `dev` or `prod`. |
| `KINA_PUBLIC_BASE_URL` | empty | Public HTTPS origin, for example `https://kina.example.com`. Empty means derived from `X-Forwarded-*` headers. |
| `OIDC_ISSUER_URI` | empty | OIDC issuer URL. Required in `prod`. |
| `OIDC_CLIENT_ID` | empty | OIDC client id. Required in `prod`. |
| `OIDC_CLIENT_SECRET` | empty | OIDC client secret. |
| `OIDC_REQUIRED_GROUPS` | empty | Comma-separated groups; a user needs at least one. Empty means no group check. |
| `OIDC_ALLOWED_EMAIL_DOMAINS` | empty | Comma-separated e-mail domains, for example `alacrity.ro`. Empty means any domain. |
| `OIDC_GROUPS_CLAIM` | `groups` | Claim that holds the groups (ID token first, then userinfo). Dotted paths and namespaced names work. |
| `OIDC_EXTRA_SCOPES` | empty | Extra scopes besides `openid profile email`. `offline_access` is added automatically. |
| `KINA_TOKEN_ENCRYPTION_KEY` | unset | Base64 of 32 bytes (`openssl rand -base64 32`). Encrypts the provider's refresh tokens for re-checks. Unset: no server-side re-checks (24 hour re-login fallback, WARN at startup). |
| `KINA_MEMBERSHIP_RECHECK_INTERVAL` | `1h` | How old a membership check may be before KINA asks the provider again. |
| `KINA_MEMBERSHIP_GRACE` | `4h` | How long access continues when the provider is unreachable. |
| `KINA_RELOGIN_INTERVAL_WITHOUT_RECHECK` | `24h` | Without a stored provider token: how long after the last login refresh grants and static tokens keep working. |
| `KINA_OAUTH_ACCESS_TOKEN_VALIDITY` | `1h` | Lifetime of access tokens issued by the OAuth flow. |
| `KINA_OAUTH_REFRESH_TOKEN_VALIDITY` | `30d` | Lifetime of OAuth refresh tokens. |
| `KINA_OAUTH_TRUSTED_CLIENT_HOSTS` | `claude.ai, claude.com, *.anthropic.com` | Hosts whose `https` `client_id` URLs (metadata documents) are accepted. `*.x` matches subdomains. |
| `KINA_OAUTH_AUTO_APPROVE_TRUSTED_CLIENTS` | `true` | Skip the consent page for trusted metadata-document clients with a non-loopback redirect URI. |
| `KINA_OAUTH_REGISTER_RATE_LIMIT_PER_MINUTE` | `30` | `POST /oauth/register` requests per client IP and minute. `0` means unlimited. |
| `KINA_TOKENS_UI_ENABLED` | `true` | `false`: the web UI only explains the Claude connector and static tokens cannot be created (`POST /tokens` gives 404). Existing tokens keep working until they expire. |

### Ranking (cross-encoder)

| Variable | Default | Meaning |
|---|---|---|
| `KINA_CROSS_ENCODER_ENABLED` | `true` | `false` disables the model; searches use the deterministic ranking (`ranking: "fallback"`, note `cross-encoder disabled`). |
| `KINA_CROSS_ENCODER_VARIANT` | `int8` | `int8` (about 23 MB, quantised, about twice as fast) or `fp32` (91 MB). int8 picks the file that matches the CPU: `model_qint8_avx512_vnni` (AVX-VNNI and ARM) or `model_quint8_avx2`. |
| `KINA_CROSS_ENCODER_MODEL_DIR` | `/data/cross-encoder` in Docker, `./data/cross-encoder` otherwise | Where the model files live. In Compose it is on the `kina-data` volume. |
| `KINA_CROSS_ENCODER_MODEL_URL` | `https://huggingface.co/cross-encoder/ms-marco-MiniLM-L6-v2/resolve/main/` | Where the files are downloaded from: an HTTP(S) directory with the same layout, or a local path that is used in place (for a fine-tuned or pre-provisioned model). |
| `KINA_CROSS_ENCODER_THREADS` | `0` | ONNX Runtime threads for one ranking call. `0` means the smaller of 4 and the number of cores. Count physical cores only. |

### JLCPCB / LCSC database

| Variable | Default | Meaning |
|---|---|---|
| `KINA_JLCPCB_LIBRARY` | `parts-fts5.db` | Which library to download: `parts-fts5.db` (all parts), `current-parts-fts5.db` or `basic-parts-fts5.db`. The upstream project defines what each contains; the two non-default variants are subsets and download faster. |
| `KINA_JLCPCB_DATA_DIR` | `/data/jlcpcb` in Docker, `./data/jlcpcb` otherwise | Where the SQLite file lives. It is on the `kina-data` volume. |

### Runtime

| Variable | Default | Meaning |
|---|---|---|
| `KINA_PORT` | `8080` | Host port published by Compose. |
| `KINA_MEM_LIMIT` | `2g` | Memory limit of the `kina` container. The JVM heap is 75 percent of it. |
| `PORT` | `8080` | HTTP port inside the process (outside Compose). |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/kina` (Compose: `jdbc:postgresql://postgres:5432/kina`) | PostgreSQL connection. |
| `SPRING_DATASOURCE_USERNAME` | `kina` | Database user. |
| `SPRING_DATASOURCE_PASSWORD` | `kina` | Database password. Compose uses `kina`; change it if you expose PostgreSQL. |

### Tuning keys without a dedicated variable

Every `kina.*` key can still be overridden with Spring's relaxed binding, for example `KINA_CACHE_TTL=2d` or `KINA_RANKING_CROSS_ENCODER_WEIGHT=0.3`. Put them in `.env`; Compose passes `.env` into the `kina` container.

| Key | Default | Meaning |
|---|---|---|
| `kina.tokens.validity` | `30d` | Lifetime of static tokens created in the web UI. |
| `kina.oauth.client-metadata-cache` | `1h` | How long a fetched Client ID Metadata Document is cached. |
| `kina.oauth.unused-client-retention` | `90d` | Dynamically registered clients unused for this long, with no live tokens, are deleted by the daily cleanup. |
| `kina.cache.ttl` | `5d` | Freshness of cached TME and Mouser data. |
| `kina.cache.empty-result-ttl` | `1h` | Freshness of a cached search that found no in-stock part. It goes stale after this time, so a glitch or a new listing does not hide parts for 5 days. |
| `kina.search.candidate-window` | `40` | Minimum parts fetched per distributor per query. |
| `kina.search.default-max-results` | `10` | Used when `max_results` is missing. |
| `kina.search.max-max-results` | `50` | Upper limit for `max_results`. |
| `kina.search.distributor-timeout` | `12s` | Budget for the active work of one distributor fetch. Time spent waiting on a rate limit does not count against it. |
| `kina.search.max-request-duration` | `2m` | Hard cap for one incoming request (`search_parts`, a whole `search_parts_batch`, `get_part` and the REST equivalents), including rate-limit waits. Clients and proxies need a read timeout above this plus ranking, about 2.5 minutes. |
| `kina.ranking.timeout` | `5s` | Ranking budget per query (the model needs about 0.1 to 0.35 s for 40 candidates). |
| `kina.ranking.batch-timeout` | `60s` | Ranking budget for a whole batch. |
| `kina.ranking.score-cache-ttl` | `1h` | In-memory cache of model scores. |
| `kina.ranking.cross-encoder.max-candidates` | `40` | Parts scored by the model per query, across distributors. |
| `kina.ranking.cross-encoder.weight` | `0.5` | Weight of the model in the rank blend. |
| `kina.ranking.cross-encoder.max-concurrent` | `2` | Scoring calls that run at once. Others wait inside their ranking budget. |
| `kina.ranking.cross-encoder.batch-size` | `16` | Part and query pairs per inference call. |
| `kina.ranking.cross-encoder.max-sequence-length` | `256` | Tokens per pair. The part text is cut first. |
| `kina.ranking.cross-encoder.check-interval` | `1h` | How often a missing or failed model is tried again. |
| `kina.ranking.cross-encoder.download-timeout` | `10m` | Upper bound for downloading one model file. |
| `kina.ranking.cross-encoder.auto-download` | `true` | `false` never downloads; only files already present are used (air-gapped hosts). |
| `kina.distributors.mouser.max-results-per-search` | `50` | Parts requested from Mouser per query (one API call). |
| `kina.distributors.tme.max-results-per-search` | `60` | Parts requested from TME per query (up to 3 pages). |
| `kina.distributors.tme.currency` and `.language` | `EUR` and `en` | TME price currency and language. |
| `kina.distributors.tme.excluded-statuses` | `CANNOT_BE_ORDERED`, `ONLY_FOR_SPECIAL_ORDER`, `EXTERNAL_WAREHOUSE` | TME product statuses that do not ship now; those parts are dropped. |
| `kina.jlcpcb.refresh-after` | `5d` | Age at which the JLCPCB database is downloaded again. |
| `kina.jlcpcb.check-interval` | `1h` | How often KINA checks that age. |
| `kina.jlcpcb.max-results-per-search` | `200` | Rows read from the JLCPCB database per query. |
| `kina.jlcpcb.auto-download` | `true` | `false` stops KINA from downloading the database. |

## MCP tools

| Tool | Parameters | Purpose |
|---|---|---|
| `search_parts` | `query` (required), `max_results` (1 to 50, default 10, per distributor), `distributors` (`LCSC`, `TME`, `MOUSER`; default all configured), `bypass_cache` (default false) | Search and rank in-stock parts. Can take up to 2 minutes when a distributor is rate limited. |
| `search_parts_batch` | `queries` (1 to 20 of `{query, max_results}`), `distributors`, `bypass_cache` | Several searches in one call. Returns `{"results": [...]}` in request order. The whole batch shares one 2-minute limit for rate-limit waits. |
| `get_part` | `distributor`, `part_number`, `bypass_cache` | One part by distributor part number (LCSC `C15850`, TME symbol, Mouser number) or by MPN (hyphens and spaces ignored). Returns `found: false` with `reason` `not_found` or `out_of_stock` (then `identity` names the listed part). Can take up to 2 minutes when the distributor is rate limited. |
| `list_distributors` | none | State of each distributor, cache statistics and ranking status (mode, model, readiness, latency, last error). Never calls the Mouser or TME APIs. |
| `ping` | none | `{"status":"ok","version":"..."}`. |

Full schemas are in [docs/API.md](docs/API.md).

### Example

Request (tool arguments of `search_parts`):

```json
{"query": "10uF X7R 0805", "max_results": 10, "distributors": ["MOUSER"]}
```

Response (abridged):

```json
{
  "query": "10uF X7R 0805",
  "parsed": {"family": "capacitor", "capacitance": "10uF", "dielectric": "X7R", "package": "0805", "keywords": []},
  "ranking": "blended",
  "ranking_note": null,
  "distributors": [
    {
      "distributor": "MOUSER",
      "total_results": 113,
      "fetched": 50,
      "returned": 10,
      "cache": "hit",
      "error": null,
      "rate_limit_waited_ms": 0,
      "parts": [
        {
          "rank": 1, "score": 0.93, "match": 1.0, "distributor": "MOUSER", "part_number": "603-CC0805MKX77BB106",
          "manufacturer": "YAGEO", "mpn": "CC0805MKX7R7BB106", "description": "...", "category": "...",
          "package": "0805", "stock": 76689, "min_order_qty": 1, "order_multiple": 1,
          "prices": [
            {"qty": 1, "unit_price": 1.40, "currency": "EUR"},
            {"qty": 10, "unit_price": 0.853, "currency": "EUR"},
            {"qty": 50, "unit_price": 0.631, "currency": "EUR"}
          ],
          "datasheet_url": "...", "photo_url": "...", "product_url": "...",
          "attributes": {"Capacitance": "10uF"},
          "extra": {"lifecycle_status": null, "rohs": "RoHS Compliant"}
        }
      ]
    }
  ]
}
```

`total_results` is what the distributor reported. `fetched` is how many in-stock parts KINA holds for the query. `returned` is `min(max_results, fetched)`.

### USB connectors: pin counts and standards

- **Pin-count normalisation.** KINA maps a reported count to the canonical configuration: 17 or 18 to 16, 25 or 26 to 24, 7 or 8 to 6. 14 stays 14, because it is a real USB 2.0 Type-C configuration. `Positions` stays as the distributor reported it. `ShieldPinsCounted` says how many extra pins were counted (1 or 2).
- **Inference.** If you name no pin count, USB 2.0 Type-C implies 16 pins and USB 3.x Type-C implies 24. `parsed.connector.pin_configuration_implied` is then `true`, and the pin signal counts half.
- **Physical consistency.** A Type-C part with 12 to 16 pins is treated as USB 2.0, and one with 2 to 6 pins as power only, whatever the distributor label says. JLCPCB labels many 16P and 6P parts "USB 3.1", although they cannot carry SuperSpeed.
- **Honest limit.** No distributor lists a 17P or 18P Type-C part in the data seen on 2026-10-05. The rule is covered by tests and by the "17 pin" request direction.
- **Data quality differs.** LCSC has description text only (mid-mount shows as "Recessed" or "Sink board", waterproofing only in part numbers). TME has the richest parameters. Mouser's search API returns no USB attributes, so everything comes from descriptions, and many Mouser descriptions give no pin count.

Example: `USB-C receptacle 17 pin` gives this `parsed.connector` (abridged):

```json
{"type": "usb-c", "gender": "female", "positions": 17,
 "usb_type": "Type-C", "pin_configuration": 16, "shield_pins_counted": 1}
```

Checked live on 2026-10-05: it returns 16-pin Type-C parts on all three distributors (TME `USB4145-03-0170-C`, Mouser `217182-0001` and `DX07S016JA3R1500`). Before, it returned power supplies, cables and circular connectors. `USB Type-C 24 pin USB 3.1 receptacle horizontal` now ranks exact-class 24P parts above USB4 ones.

### Example: a connector query

Request:

```json
{"query": "90 degree dupont style female pin header, THT, 6 position", "max_results": 3}
```

Response (abridged, one distributor entry shown in full):

```json
{
  "query": "90 degree dupont style female pin header, THT, 6 position",
  "parsed": {
    "family": "connector", "mounting": "THT", "keywords": [],
    "connector": {"type": "female header", "gender": "female", "positions": 6, "pitch": "2.54mm", "orientation": "right angle"}
  },
  "ranking": "blended",
  "distributors": [
    {"distributor": "LCSC", "distributor_query": "\"Female Header\" 6P \"Right Angle\" 2.54mm", "fallback_query": null,
     "parts": [{"rank": 1, "part_number": "C...", "mpn": "PM254-1-06-W-8.5",
                "attributes": {"ConnectorType": "female header", "Gender": "female", "Positions": "6", "Pitch": "2.54mm", "Orientation": "right angle"}}]},
    {"distributor": "TME", "distributor_query": "pin strips female 6 angled", "...": "..."},
    {"distributor": "MOUSER", "distributor_query": "female header 6 pos right angle", "...": "..."}
  ]
}
```

Checked on a local instance on 2026-10-05. The top results were LCSC `PM254-1-06-W-8.5`, `DW254W-11-06-85` and `X5511FR-06`; TME `ZL263-6SG` and `DS1002-01-1X06R13`; Mouser `PRT-12590`, `613006143121` and `M22-6540642R`.

Known limit: KINA does not send rows to Mouser, and Mouser keyword search is loose. A `2x3` request can therefore return single-row parts there. TME and LCSC handle rows.

## How ranking works

1. The query is parsed: component family, value, tolerance (`.1%` too), voltage, dielectric, package, mounting, the technology of a resistor, capacitor or inductor (thin film, thick film, wirewound, tantalum, polymer, film, multilayer...), and leftover keywords.
2. A deterministic ranker scores every part from 0 to 1: primary value (0.30), package (0.20), dielectric (0.15), technology (0.15), voltage/current/power rating (0.10), tolerance (0.10), family keyword (0.05), lexical match (0.10), and small tie-break bonuses for stock, price and the JLCPCB Basic/Preferred library. A mismatch on value, package, dielectric, technology, rating or tolerance is penalised by the same amount a match earns. The same signals give each part its `match` grade (0 to 1, 1.0 = every stated parameter matches), which is absolute while `score` is relative to the other candidates.
   For USB requests see the weights in [docs/API.md](docs/API.md#usb-connector-queries). For other connector requests the value feature is replaced by connector features: positions (0.30), gender (0.20), orientation (0.15), pitch (0.15, where 2.54 mm equals 0.1"), connector type (0.10) and mounting (0.05). A wrong row count costs 0.10. Multi-row parts cost 0.08 when you did not ask for rows. Attributes a part does not list never count against it.
3. The top 40 candidates (shared across distributors, at least 5 per distributor) go to the cross-encoder `cross-encoder/ms-marco-MiniLM-L6-v2`. It reads the query text and the part text (manufacturer, MPN, description, category, package, attributes) together and returns one relevance score per part. It runs inside the KINA JVM through ONNX Runtime on the CPU. The score is cached in memory for 1 hour.
4. Both orders are turned into ranks inside the candidate set, and the final score is `0.5 * deterministic rank + 0.5 * model rank`. Parts that were not sent to the model come after the scored ones. The response says `"ranking": "blended"`.
5. When the model cannot score, the deterministic order is used and the response says `"ranking": "fallback"` with a `ranking_note`: `cross-encoder disabled`, `cross-encoder model not loaded yet`, `cross-encoder timeout after 5s`, `cross-encoder timeout: budget exhausted`, `cross-encoder busy: no free slot within ...` or `cross-encoder failed: ...`. In a batch, queries reached after the 60 s ranking budget also fall back. Search never fails because of the model.

### Measured results

The study is in [docs/research/ranking-evaluation-2026-10-05.md](docs/research/ranking-evaluation-2026-10-05.md). It uses 32 labelled queries (1259 candidates). Score is NDCG@10, higher is better. The dataset in `docs/research/data` has since grown to 41 queries and 1 619 candidates with 9 labelled USB connector queries; the numbers below are from the 32-query study.

| Ranking | NDCG@10 | Time per search |
|---|---|---|
| Deterministic ranker alone | 0.898 | under 5 ms |
| Blend with the cross-encoder, zero-shot (shipped default) | 0.913 | 130 to 300 ms (40 candidates, 4 threads, int8); 16 ms when the scores are cached |
| Blend with a fine-tuned cross-encoder | 0.918 | same |

The cross-encoder helps most on discrete parts, ICs and connectors, where the parser does not model words such as `RS-485` or `1x4P`. Passives and vague requests are not hurt.

### Model files

- KINA downloads the model files on the first start, in the background, from `https://huggingface.co/cross-encoder/ms-marco-MiniLM-L6-v2/resolve/main/` into `KINA_CROSS_ENCODER_MODEL_DIR`. Each file is checked by size and SHA-256. `model.json` in that directory records the source and revision. `list_distributors` shows `model_revision`.
- The default is the int8 file (about 23 MB). Set `KINA_CROSS_ENCODER_VARIANT=fp32` for the 91 MB file. It is slower and scored the same in the study.
- Files that are already in the directory are used without downloading, so you can provision the directory yourself (see [docs/OPERATIONS.md](docs/OPERATIONS.md)).
- A failed download is logged once and retried every hour. KINA never waits for it at startup.

### Fine-tuning

You can fine-tune the model on your own labelled parts. The script runs in a Docker image (`kina-ce-finetune:local`, built from `python:3.11-slim`) and takes about 4 to 5 minutes on 16 cores:

```bash
scripts/ranking/finetune_cross_encoder.sh                    # mode synth (default): synthetic labels only
scripts/ranking/finetune_cross_encoder.sh -m synth_real -o ./data/ce-synth-real
```

It writes a model directory in the Hugging Face layout plus `model.json`. Point `KINA_CROSS_ENCODER_MODEL_URL` at it (a local path is used in place). Details are in `scripts/ranking/README.md`.

Check a model before you ship it. The evaluation test runs when `KINA_CROSS_ENCODER_TEST_MODEL_DIR` is set, and it asserts a blended NDCG@10 of at least 0.90 on `docs/research/data/ranking-eval.jsonl`:

```bash
KINA_CROSS_ENCODER_TEST_MODEL_DIR=$PWD/data/cross-encoder-finetuned ./mvnw test -Dtest=CrossEncoderEvaluationTest
```

Part data is never sent to a third-party service for ranking.

## Operations

- Volumes: `kina-data` (JLCPCB SQLite file), `pgdata` (PostgreSQL). The ranking model is in `kina-data` too, under `/data/cross-encoder`.
- JLCPCB database: checked every hour, downloaded again when older than 5 days. The old file keeps serving while the new one downloads.
- TME and Mouser cache: 5 days. A cached search with zero parts goes stale after 1 hour (`kina.cache.empty-result-ttl`). Rows older than 10 days (2 x TTL) are purged every 6 hours.
- Rate limits: Mouser allows 1 000 calls per day and 30 per minute. KINA makes one Mouser call per uncached query and does not throttle itself. When a distributor answers with a rate limit, KINA waits and retries (see the next item). Use `bypass_cache` sparingly.
- Rate-limit handling: HTTP 429, HTTP 502, 503 or 504 with a `Retry-After` header, and Mouser's in-body `TooManyRequests` error trigger a wait. KINA waits for `Retry-After` (at least 1 s) or, without it, 2, 4, 8, 16, 30, 30... seconds with 20 percent jitter, and retries while the next wait fits inside the request deadline (`kina.search.max-request-duration`, 2 minutes). A 503 without `Retry-After` is an outage and fails at once. After a rate limit, all calls to that distributor share a cool-down: they wait for it if that fits their deadline, otherwise they fail at once with `rate_limited`. If the limit outlasts the deadline, the entry reports `error: "rate_limited"` and keeps the parts already fetched. The retry helps with the per-minute limit, not with an exhausted daily quota. Worst case for one request is about 2 minutes plus ranking.
- Phrase fallback: when the full query returns 0 parts at Mouser or TME, KINA retries once with the parsed core phrase. The distributor entry then has `fallback_query` set. The phrase is stored with the cached search, so a cache hit reports it too.
- Logs: `docker compose logs -f kina`. Each search logs fetch and rank timings. Tokens and API keys are never logged.

### Measured numbers

From `docs/DEVELOPMENT.md`, section "Measured on 2026-10-05" (24-core, 30 GB host, full JLCPCB database of 7.1 million parts):

- RAM after the end-to-end run: `kina` about 485 MiB (limit 2 GiB), `postgres` about 45 MiB. That was measured before the in-process model replaced the old ranking container. The model adds an estimated 100 to 250 MB outside the JVM heap.
- Disk: JLCPCB database 5.33 GB, int8 model 23 MB (91 MB for fp32), `kina.jar` about 115 MB (the ONNX Runtime jar is about 53 MB of it).
- Search: cold (distributor calls) about 6 s for three distributors; the same query again with a larger `max_results` about 60 ms.
- Ranking: 130 to 300 ms per search for 40 candidates (4 threads, int8), 16 ms when the scores are cached. First start of the model (download, load, warm-up) took 3.6 s in the background.

### Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Connector results look generic or wrong | Look at `parsed.connector`: it shows what KINA understood (type, gender, positions, pitch, orientation). If a field is missing, state it more plainly, for example "female header 1x6 right angle 2.54mm". Then look at `distributor_query` per distributor to see the phrase KINA really sent. Results cached before an upgrade can look old; ask again with `bypass_cache`. Mouser ignores rows. |
| USB results show the wrong pin count or standard | Look at `parsed.connector`: `pin_configuration` is what KINA compared, `positions` is what you wrote. If `pin_configuration_implied` is `true`, you gave no pin count and KINA guessed from the standard (16 for USB 2.0, 24 for USB 3.x); state the count to fix it. Check the part's `PinConfiguration`, `ShieldPinsCounted` and `UsbStandard` attributes: a 17P listing is a 16-pin part, and a 12 to 16 pin Type-C is USB 2.0 whatever its label says. Mouser gives no USB attributes, so its parts depend on description text. Ask again with `bypass_cache` if the results were cached before the upgrade. |
| LCSC `error: "unavailable"`, detail "JLCPCB parts database not downloaded yet" | The first download is still running (about 1 GB). Watch `docker compose logs kina`. If it failed, `jlcpcb.last_error` in `list_distributors` says why; check disk space and internet access. |
| `ranking: "fallback"` | Read `ranking_note`. `cross-encoder model not loaded yet`: the first download is still running or failed; check `ranking.last_error` in `list_distributors` and `docker compose logs kina`, then internet access, disk space and write access to `/data/cross-encoder`. KINA retries every hour. `cross-encoder disabled`: `KINA_CROSS_ENCODER_ENABLED` is `false`. `cross-encoder timeout ...` or `busy ...`: the host is short of CPU; lower the load or check `KINA_CROSS_ENCODER_THREADS`. `cross-encoder failed: ...`: see the log. Search still works. |
| TME or Mouser `error: "not_configured"` | The credentials are missing in `.env`. Restart with `docker compose up -d`. |
| Slow search (up to 2 minutes), `rate_limit_waited_ms` above 0 | The distributor rate limited the request and KINA waited. This is normal for Mouser's 30 calls per minute. The log has a `rate limited ... cooling down` line. |
| Mouser `error: "rate_limited"` | The limit outlasted the 2-minute budget, usually an exhausted daily quota (1 000 calls). Wait, and rely on the cache. |
| Client or proxy times out on a search | Their read timeout is below about 2.5 minutes. Raise it (see [docs/OPERATIONS.md](docs/OPERATIONS.md)). |
| 401 on `/api` or `/mcp` in `prod` | Missing, expired or revoked token. The `WWW-Authenticate` header points to the OAuth metadata. |
| Claude connector cannot sign in | Check HTTPS, the `X-Forwarded-*` headers or `KINA_PUBLIC_BASE_URL`, and that `https://<host>/.well-known/oauth-protected-resource` shows your public origin. |
| "Access denied" page after signing in (`/login-denied`, 403) | The account is not in a group listed in `OIDC_REQUIRED_GROUPS` (or its e-mail domain is not allowed). Ask the administrator to add you to the group, then sign in again. If you were just added, sign out of the identity provider first so it issues a fresh sign-in. |
| Claude asks you to reconnect; a refresh answers `invalid_grant` | Either you were removed from the group, or the identity provider was unreachable for longer than `KINA_MEMBERSHIP_GRACE` (4 hours). In the second case nothing was revoked and it recovers by itself when the provider is back. Without `KINA_TOKEN_ENCRYPTION_KEY`, users must also sign in again every 24 hours. |
| 429 on `POST /oauth/register` | The client IP went over `KINA_OAUTH_REGISTER_RATE_LIMIT_PER_MINUTE`. Wait for `Retry-After` seconds. If every client seems to share one address, the proxy is not overwriting `X-Forwarded-For`. |
| Claude Code still shows the Approve page | Expected. Claude Code redirects to a local port, so KINA always asks. Claude.ai with Claude's published identity skips the page. |
| `invalid_client` at `/oauth/token`, or an error page at `/oauth/authorize`, with an `https` `client_id` | The host is not in `KINA_OAUTH_TRUSTED_CLIENT_HOSTS`, the document is invalid or unreachable, or the redirect URI is not listed in it. |

See [docs/OPERATIONS.md](docs/OPERATIONS.md) for deployment, backup, upgrades and hardening.

## Development

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for the toolchain and local run. The binding design is [docs/DESIGN.md](docs/DESIGN.md).

```bash
./mvnw -q verify               # compile and run all tests (needs Docker for Testcontainers)
./mvnw -q -DskipTests package  # builds target/kina.jar
```

End-to-end checks against a running stack are in `scripts/e2e/` (`python3 scripts/e2e/kina_e2e.py`, and `scripts/e2e/prod_smoke.sh` for a `prod` mode smoke test); see the "End-to-end checks" section of `docs/DEVELOPMENT.md`. They create tokens and OAuth clients in the database and make a couple of Mouser calls on a cold cache.

Tests live in `src/test/java/ro/alacrity/kina/`, one package per main package (`search`, `distributor/{lcsc,mouser,tme}`, `security`, `oauth`, `api`, `mcp`, `cache`, `domain`, `web`). JSON fixtures are in `src/test/resources/fixtures`. Tests start PostgreSQL 17 through Testcontainers and never download the JLCPCB database. Tests that need live services or a model directory (`CrossEncoderEvaluationTest`, the Mouser live test) run only when their environment variables are set.
