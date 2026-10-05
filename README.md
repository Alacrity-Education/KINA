# KINA

KINA is an MCP server and HTTP API that lets Claude search electronic components. You describe a part ("10uF X7R 0805 MLCC 25V") and KINA queries three distributors, drops everything that is not in stock, ranks the rest and returns full part details: prices, stock, datasheet, photo, parametric attributes.

The distributors are LCSC (served from the JLCPCB parts database, downloaded and read locally), TME (API v2) and Mouser. Results from TME and Mouser are cached in PostgreSQL for five days. Ranking is a deterministic parametric score blended with a small local decision model ([Laya](https://github.com/NandhaKishorM/laya)) that runs as a sidecar container. If Laya is slow or down, KINA falls back to the deterministic ranking and says so in the response.

## Features

- MCP over Streamable HTTP (stateless) at `/mcp`, plus a REST API under `/api/v1`.
- Three distributors: LCSC (JLCPCB database), TME, Mouser. Each one fails on its own; a broken distributor never fails the whole search.
- Only stock that ships now is returned. Out-of-stock, on-order and factory-stock offers are never ranked, cached or returned.
- Prices are trimmed to the 3 smallest quantity brackets.
- `max_results` is per distributor (1 to 50, default 10). Every distributor entry also reports how many matches the distributor found, how many KINA holds, and how many it returned.
- Cache of 5 days for TME and Mouser. Ask again with a larger `max_results` and the answer comes from the cache; KINA only calls the distributor when the cache holds too few parts.
- `bypass_cache` skips the cache lookup and still refreshes the cache.
- Phrase fallback: when Mouser or TME find nothing for the full query, KINA retries once with the parametric core of the query (for example `MOSFET 30V SOT-23` for `SOT-23 N-channel MOSFET 30V`) and reports it in `fallback_query`.
- Batch search of up to 20 queries in one call.
- Ranking: deterministic parametric ranker blended with Laya, with an 18 s budget per query and an automatic fallback (`ranking: "fallback"`).
- OAuth 2.1 authorization server for Claude's remote connector (dynamic client registration, PKCE, consent page).
- 30-day static access tokens for Claude Code and the HTTP API, created in the web UI.
- Two security modes: `dev` (no login, fake admin) and `prod` (OIDC login, bearer tokens required).
- Provider-agnostic OIDC: only the issuer URL, client id and client secret are configured. Keycloak, Authentik, Google, Entra ID and other compliant providers work unchanged.

## Quick start (Docker Compose)

### Prerequisites

- Docker with the Compose plugin.
- About 10 GB of free disk: the JLCPCB database takes 5.3 GB once unpacked, the Laya checkpoint 1.5 GB and the Laya image 1.8 GB.
- RAM: about 4 GB free is a safe minimum. Measured: `laya-serve` about 1.9 GiB, `kina` about 485 MiB, PostgreSQL about 45 MiB. See [Measured numbers](#measured-numbers) and [docs/OPERATIONS.md](docs/OPERATIONS.md).
- Optional: a Mouser API key and TME API v2 credentials. Without them those distributors report `not_configured` and LCSC still works.

### Start

```bash
cp .env.example .env        # then edit .env: MOUSER_API_KEY, TME_TOKEN, TME_APPLICATION_SECRET
docker compose up -d --build
```

`.env` is git-ignored. Never commit it.

### What happens on the first start

- The `kina` image is built (Maven build, a few minutes).
- The `laya-serve` image is built from the Laya git repository and downloads PyTorch. This is slow the first time.
- Laya downloads its checkpoint (`multilingual`, about 650 MB) into the `laya-models` volume.
- KINA starts and, in the background, downloads the JLCPCB parts database (about 1 GB zipped, 5.3 GB on disk) into the `kina-data` volume. KINA does not wait for it. Until the file is ready, LCSC reports `unavailable` ("JLCPCB database not downloaded yet") and TME and Mouser work normally.
- The JLCPCB database is downloaded again when it is older than 5 days.

### Check that it is ready

```bash
curl -s localhost:8080/actuator/health          # {"status":"UP"}
docker compose ps                               # laya-serve shows "healthy" once the checkpoint is loaded
docker compose logs -f kina                     # download progress and errors
```

Then call the `list_distributors` MCP tool, or the REST equivalent:

```bash
curl -s localhost:8080/api/v1/distributors
```

Check that LCSC is `available: true` (with the JLCPCB part count), that TME and Mouser are `configured: true`, and that `ranking.laya_healthy` is `true`. In `prod` mode the REST call needs a bearer token.

### Web UI

Open `http://localhost:8080/` (the port is `KINA_PORT`). The page lists your access tokens, lets you create one (shown once, valid 30 days) and revoke tokens. Clients that connected through OAuth show up as `MCP: <client name>`.

## Connecting Claude

The examples use `https://<host>` for the public address of KINA. Claude's remote connector needs HTTPS, which KINA does not terminate itself; put a reverse proxy in front (see [docs/OPERATIONS.md](docs/OPERATIONS.md)).

### Claude Code with a static token

1. Open the KINA web UI, create a token, copy it. It is shown only once.
2. Add the server:

```bash
claude mcp add --transport http kina https://<host>/mcp --header "Authorization: Bearer <token>"
```

For local development in `dev` mode, `claude mcp add --transport http kina http://localhost:8080/mcp` is enough, because no token is required.

### Claude.ai and Claude Desktop (remote connector)

1. Add a custom connector with the URL `https://<host>/mcp`.
2. Claude discovers the OAuth endpoints, registers itself, sends you to KINA to sign in (OIDC in `prod`, automatic in `dev`) and shows a consent page. Approve it. Claude then receives a 30-day access token and a refresh token (90 days). No token needs to be pasted.

Requirements:

- HTTPS in front of KINA.
- The proxy must send `X-Forwarded-Proto` and `X-Forwarded-Host` (and `X-Forwarded-Port` if the port is not standard), so that the OAuth metadata advertises the public origin. Alternatively set `KINA_PUBLIC_BASE_URL=https://<host>`, which always wins.

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
| Web UI | No login. Every request runs as the fake user "Development Admin". | OIDC login. |
| `/api/**` and `/mcp` | Open when no token is sent. A token that is sent is still validated. | Bearer token required (401 otherwise). |
| `/oauth/authorize` | Signs in as the fake admin automatically, then shows the consent page. | Redirects to the OIDC provider, then shows the consent page. |
| OIDC settings | Not needed. | `OIDC_ISSUER_URI`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET`. KINA refuses to start without issuer and client id. |

OIDC redirect URI to register at the provider: `https://<host>/login/oauth2/code/oidc`. Scopes requested: `openid profile email`. Discovery uses `<issuer>/.well-known/openid-configuration` and runs on the first login, so KINA starts even if the provider is down.

> **Warning.** Never expose `dev` mode to the internet. Anyone who can reach the port can use the API, create tokens and approve OAuth clients as the admin. Compose publishes `KINA_PORT` on all interfaces, so keep dev mode on trusted networks only, and set `KINA_MODE=prod` for anything public.

`/actuator/health` and `/actuator/info` are public in both modes.

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

### Ranking and Laya

| Variable | Default | Meaning |
|---|---|---|
| `KINA_LAYA_ENABLED` | `true` | `false` disables Laya; searches use the deterministic ranking (`ranking_note: "laya disabled"`). |
| `KINA_LAYA_MODEL` | `multilingual` | Checkpoint name sent to laya-serve. It must be loaded by the sidecar (compose sets `LAYA_MODELS=multilingual`). |
| `KINA_LAYA_MAX_CONCURRENT` | `1` | Ranking requests in flight at once. Also becomes the sidecar's `LAYA_MAX_CONCURRENT`, so both limits stay equal. |
| `LAYA_THREADS` | `4` | CPU threads for Laya (also `OMP_NUM_THREADS`). Keep it at or below the physical cores you can dedicate; never count SMT siblings, oversubscribing is about a 10x slowdown. On a host with 8 or more free physical cores, 8 threads were measured about 1.5 to 1.8 times faster than 4. |
| `LAYA_API_KEY` | empty | Optional shared secret. KINA sends it as a bearer token and laya-serve requires it when set. |
| `LAYA_GPU_ID` | `0` | GPU index used by `compose.cuda.yaml`. |
| `LAYA_URL` | `http://laya-serve:8000` in Compose, `http://localhost:8000` otherwise | Base URL of laya-serve. Set by `compose.yaml`. |

GPU ranking (needs the NVIDIA Container Toolkit; no KINA change needed):

```bash
docker compose -f compose.yaml -f compose.cuda.yaml up -d --build
```

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

Every `kina.*` key can still be overridden with Spring's relaxed binding, for example `KINA_CACHE_TTL=2d` or `KINA_RANKING_LAYA_WEIGHT=0.3`. Put them in `.env`; Compose passes `.env` into the `kina` container.

| Key | Default | Meaning |
|---|---|---|
| `kina.tokens.validity` | `30d` | Lifetime of access tokens (web UI and OAuth). |
| `kina.oauth.refresh-token-validity` | `90d` | Lifetime of OAuth refresh tokens. |
| `kina.cache.ttl` | `5d` | Freshness of cached TME and Mouser data. |
| `kina.cache.empty-result-ttl` | `1h` | Freshness of a cached search that found no in-stock part. It goes stale after this time, so a glitch or a new listing does not hide parts for 5 days. |
| `kina.search.candidate-window` | `40` | Minimum parts fetched per distributor per query. |
| `kina.search.default-max-results` | `10` | Used when `max_results` is missing. |
| `kina.search.max-max-results` | `50` | Upper limit for `max_results`. |
| `kina.search.distributor-timeout` | `12s` | Deadline for one distributor fetch. |
| `kina.ranking.timeout` | `18s` | Ranking budget per query. |
| `kina.ranking.batch-timeout` | `60s` | Ranking budget for a whole batch. |
| `kina.ranking.score-cache-ttl` | `1h` | In-memory cache of Laya scores. |
| `kina.ranking.laya.max-candidates` | `40` | Parts sent to Laya per query, across distributors. |
| `kina.ranking.laya.weight` | `0.2` | Weight of the Laya score in the final score. |
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
| `search_parts` | `query` (required), `max_results` (1 to 50, default 10, per distributor), `distributors` (`LCSC`, `TME`, `MOUSER`; default all configured), `bypass_cache` (default false) | Search and rank in-stock parts. |
| `search_parts_batch` | `queries` (1 to 20 of `{query, max_results}`), `distributors`, `bypass_cache` | Several searches in one call. Returns `{"results": [...]}` in request order. |
| `get_part` | `distributor`, `part_number`, `bypass_cache` | One part by distributor part number (LCSC `C15850`, TME symbol, Mouser number). Returns `found: false` for unknown or out-of-stock parts. |
| `list_distributors` | none | State of each distributor, cache statistics and Laya health. Never calls the Mouser or TME APIs. |
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
  "ranking": "laya",
  "ranking_note": null,
  "distributors": [
    {
      "distributor": "MOUSER",
      "total_results": 113,
      "fetched": 50,
      "returned": 10,
      "cache": "hit",
      "error": null,
      "parts": [
        {
          "rank": 1, "score": 0.93, "distributor": "MOUSER", "part_number": "603-CC0805MKX77BB106",
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

## How ranking works

1. The query is parsed: component family, value, tolerance, voltage, dielectric, package, mounting, and leftover keywords.
2. A deterministic ranker scores every part from 0 to 1: primary value (0.30), package (0.20), dielectric (0.15), voltage/current/power rating (0.10), tolerance (0.10), family keyword (0.05), lexical match (0.10), and small tie-break bonuses for stock, price and the JLCPCB Basic/Preferred library. A mismatch on value, package, dielectric, rating or tolerance is penalised by the same amount a match earns.
3. The top 40 candidates (shared across distributors, at least 5 per distributor) go to Laya, one state per part. Laya answers one yes/no question per part: does it satisfy every requirement of the request. KINA rank-normalises the raw probabilities within the candidate set (best 1.0, worst 0.0), because they cluster near 1.0.
4. Final score is `0.8 * deterministic + 0.2 * Laya`. Parts that were not sent to Laya come after the Laya-ranked ones.
5. On Laya timeout (18 s per query), unavailability, or `KINA_LAYA_ENABLED=false`, the deterministic order is used and the response says `"ranking": "fallback"` with a `ranking_note`. In a batch, queries reached after the 60 s ranking budget also fall back.

### Measured limits

Zero-shot Laya is a weak signal. On a labelled set for "10uF X7R 0805 MLCC ceramic capacitor" (3 true matches, 7 distractors) with the `multilingual` checkpoint:

- Matches scored 0.996 on average and distractors 0.78, but a 10k resistor scored 0.98 and the X5R variant 0.01.
- Only 2 of the 3 true matches landed in the top 3.
- Other question shapes (`score`, `choice`, per-attribute) and the `typed-decisions` checkpoint were no better.

That is why the deterministic ranker is primary and Laya has a weight of 0.2. Speed on CPU (8 threads): 40 candidates take about 3.5 s with one question per part.

The labelled set is in the test fixtures, in `src/test/java/ro/alacrity/kina/search/LayaRankerEvaluationTest.java`. It runs only against a live Laya:

```bash
KINA_LAYA_TEST_URL=http://127.0.0.1:8001 ./mvnw test -Dtest=LayaRankerEvaluationTest
```

Set `KINA_LAYA_TEST_MODEL` to evaluate another checkpoint (default `multilingual`). To use a fine-tuned checkpoint, make laya-serve load it (`LAYA_MODELS`, `LAYA_DEFAULT_MODEL` and the Hugging Face cache volume in `compose.yaml`), set `KINA_LAYA_MODEL` to its name, re-run the evaluation, and only then consider raising `kina.ranking.laya.weight`. Part data is only ever sent to the local Laya URL.

## Operations

- Volumes: `kina-data` (JLCPCB SQLite file), `pgdata` (PostgreSQL), `laya-models` (Hugging Face cache).
- JLCPCB database: checked every hour, downloaded again when older than 5 days. The old file keeps serving while the new one downloads.
- TME and Mouser cache: 5 days. A cached search with zero parts goes stale after 1 hour (`kina.cache.empty-result-ttl`). Rows older than 10 days (2 x TTL) are purged every 6 hours.
- Rate limits: Mouser allows 1 000 calls per day and 30 per minute. KINA makes one Mouser call per uncached query and does not throttle itself; a refused call shows as `error: "rate_limited"`. Use `bypass_cache` sparingly.
- Phrase fallback: when the full query returns 0 parts at Mouser or TME, KINA retries once with the parsed core phrase. The distributor entry then has `fallback_query` set. The phrase is stored with the cached search, so a cache hit reports it too.
- Logs: `docker compose logs -f kina`. Each search logs fetch and rank timings. Tokens and API keys are never logged.

### Measured numbers

From `docs/DEVELOPMENT.md`, section "Measured on 2026-10-05" (24-core, 30 GB host, CPU Laya with `LAYA_THREADS=4`, full JLCPCB database of 7.1 million parts):

- RAM after the end-to-end run: `kina` about 485 MiB (limit 2 GiB), `laya-serve` about 1.9 GiB, `postgres` about 45 MiB.
- Disk: JLCPCB database 5.33 GB, Laya models 1.5 GB, Laya image 1.76 GB.
- Search: cold (distributor calls) about 6 s for three distributors; the same query again with a larger `max_results` about 60 ms; Laya ranking 2 to 4 s per search on CPU.

### Troubleshooting

| Symptom | Cause and fix |
|---|---|
| LCSC `error: "unavailable"`, detail "JLCPCB parts database not downloaded yet" | The first download is still running (about 1 GB). Watch `docker compose logs kina`. If it failed, `jlcpcb.last_error` in `list_distributors` says why; check disk space and internet access. |
| `ranking: "fallback"` with a Laya note | Laya is not healthy yet (the first start loads the checkpoint), is overloaded or timed out. Check `docker compose ps` and `docker compose logs laya-serve`. Search still works. |
| TME or Mouser `error: "not_configured"` | The credentials are missing in `.env`. Restart with `docker compose up -d`. |
| Mouser `error: "rate_limited"` | Daily or per-minute quota hit. Wait, and rely on the cache. |
| 401 on `/api` or `/mcp` in `prod` | Missing, expired or revoked token. The `WWW-Authenticate` header points to the OAuth metadata. |
| Claude connector cannot sign in | Check HTTPS, the `X-Forwarded-*` headers or `KINA_PUBLIC_BASE_URL`, and that `https://<host>/.well-known/oauth-protected-resource` shows your public origin. |

See [docs/OPERATIONS.md](docs/OPERATIONS.md) for deployment, backup, upgrades and hardening.

## Development

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for the toolchain and local run. The binding design is [docs/DESIGN.md](docs/DESIGN.md).

```bash
./mvnw -q verify               # compile and run all tests (needs Docker for Testcontainers)
./mvnw -q -DskipTests package  # builds target/kina.jar
```

End-to-end checks against a running stack are in `scripts/e2e/` (`python3 scripts/e2e/kina_e2e.py`, and `scripts/e2e/prod_smoke.sh` for a `prod` mode smoke test); see the "End-to-end checks" section of `docs/DEVELOPMENT.md`. They create tokens and OAuth clients in the database and make a couple of Mouser calls on a cold cache.

Tests live in `src/test/java/ro/alacrity/kina/`, one package per main package (`search`, `distributor/{lcsc,mouser,tme}`, `security`, `oauth`, `api`, `mcp`, `cache`, `domain`, `web`). JSON fixtures are in `src/test/resources/fixtures`. Tests start PostgreSQL 17 through Testcontainers and never download the JLCPCB database. Tests that need live services (the Laya evaluation, the Mouser live test) run only when their environment variables are set.
