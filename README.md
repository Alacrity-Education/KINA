# KINA

An MCP server and REST API that lets Claude find electronic components that are in stock at LCSC, TME and Mouser.

![last commit](https://img.shields.io/github/last-commit/Alacrity-Education/KINA?style=flat-square) ![last release](https://img.shields.io/github/v/release/Alacrity-Education/KINA?style=flat-square) ![language](https://img.shields.io/github/languages/top/Alacrity-Education/KINA?style=flat-square) ![license](https://img.shields.io/github/license/Alacrity-Education/KINA?style=flat-square) ![status](https://img.shields.io/badge/status-working-green?style=flat-square) ![repo size](https://img.shields.io/github/repo-size/Alacrity-Education/KINA?style=flat-square)

## Introduction

You ask Claude for "10uF X7R 0805 MLCC 25V". KINA reads the request, asks three distributors, throws away everything that does not ship now, ranks what is left and hands back prices, stock, datasheet links and parameters. Claude can then compare parts and write a bill of materials without you opening three web shops.

The distributors are LCSC (served from the JLCPCB parts database, which KINA downloads and searches locally), TME (API v2) and Mouser. Each one fails on its own, so a broken distributor never fails the whole search.

KINA speaks MCP over Streamable HTTP at `/mcp` and also has a plain REST API under `/api/v1`. It is also an OAuth 2.1 authorization server, so Claude's remote connector can sign in through your organisation's login (Authentik, Keycloak, Google, Entra ID or any OIDC provider). Only members of the groups you name get in.

It runs as two containers: KINA (Spring Boot, Java 21) and PostgreSQL 17. The ranking model runs inside the KINA process on the CPU. No part data leaves your host for ranking.

## Demo

Claude calls the `search_parts` tool with these arguments:

```json
{"query": "10uF X7R 0805", "max_results": 10, "distributors": ["MOUSER"]}
```

KINA answers (abridged):

```json
{
  "query": "10uF X7R 0805",
  "parsed": {"family": "capacitor", "capacitance": "10uF", "dielectric": "X7R", "package": "0805", "keywords": []},
  "query_understood": true,
  "ranking": "blended",
  "currencies": ["EUR"],
  "distributors": [
    {
      "distributor": "MOUSER",
      "total_results": 113, "fetched": 50, "excluded_by_constraints": 0, "excluded_below_spec": 0, "returned": 10,
      "cache": "hit", "error": null, "query_terms_dropped": [], "constraints_relaxed": [], "exact_matches": 7,
      "parts": [
        {
          "rank": 1, "score": 0.93, "match": 1.0, "part_number": "603-CC0805MKX77BB106",
          "manufacturer": "YAGEO", "mpn": "CC0805MKX7R7BB106",
          "stock": 76689, "stock_as_of": "2026-10-06T09:12:44Z", "min_order_qty": 1, "order_multiple": 1,
          "prices": [
            {"qty": 1, "unit_price": 1.40, "currency": "EUR"},
            {"qty": 10, "unit_price": 0.853, "currency": "EUR"},
            {"qty": 50, "unit_price": 0.631, "currency": "EUR"}
          ],
          "availability": {"status": "in_stock", "note": "Ships now from stock."}, "lifecycle": "active",
          "datasheet_url": "...", "product_url": "...",
          "attributes": {"Capacitance": "10uF", "Voltage": "25V", "Dielectric": "X7R", "Package": "0805"}
        }
      ]
    }
  ]
}
```

`parsed` shows what KINA understood (`query_understood` is false when it recognised nothing typed). `total_results` is what the distributor reported, `fetched` is every in-stock part KINA received, the two `excluded_*` counts are the parts of it left out, and `returned` is what you get. Add `quantity` and each part also gets `ordered_quantity`, `unit_price_at_quantity` and `total_price`. LCSC prices are USD, TME and Mouser EUR (`currencies`); KINA does not convert them.

## Features

- **Only stock that ships now.** Out-of-stock, on-order and factory-stock offers are never ranked, cached or returned.
- **Three distributors in one call.** LCSC, TME and Mouser, with `search_parts_batch` for up to 20 queries at once.
- **Prometheus metrics.** Searches, distributor calls and rate limits, cache contents, ranking model runs, tool calls, logins and users, on a separate unauthenticated port (`/actuator/prometheus` on 9090); counters survive restarts.
- **Prices that fit in a chat.** The three smallest price brackets, plus `total_price` at the `quantity` you ask for (minimum order quantity and multiples included).
- **Understands components, not only words.** Value, tolerance, package, dielectric, mounting and technology (thin film, wirewound, tantalum, polymer and more) are parsed and rewritten into each distributor's own vocabulary.
- **Connector and USB aware.** "90 degree dupont style female pin header, THT, 6 position" becomes a typed request. USB-C requests know standards, speed classes, pin configurations and features, and shield pins are normalised (a 17 pin listing is a 16 pin part).
- **Ratings are hard minimums.** `25V` accepts 35 V and 50 V parts, and an equal rating ranks first. A part below a stated rating is never returned unless you pass `allow_below_spec`, and then it is flagged `below_spec` and listed last.
- **Never relaxes what defines the part.** The value, the package (except for inductors, crystals and oscillators), mounting, technology and the type (crystal or oscillator, Schottky or rectifier, N- or P-channel, fixed or adjustable regulator, connector type, gender, positions, pitch) are hard: a part that contradicts one is left out and counted per constraint. If nothing is left, the list is empty and a `hint` says which constraints could not be met. No substitutes.
- **Packages are imperial.** `0603` always means the inch code; a metric code counts only when it is labelled (`1608 metric`, TME `Case - mm`).
- **Honest about compromises.** When nothing meets the request, KINA reads more pages, then loosens the dielectric, then the package of an inductor, crystal or oscillator, then the tolerance (never a rating or a hard constraint). The response lists what was loosened (`constraints_relaxed`), what each part does not satisfy (`mismatches`) and what the distributor does not state (`unverified`).
- **Stock you can trust.** Low stock (`low_stock`) and large minimum orders rank lower, every part says how old its stock figure is (`stock_as_of`), and cached figures older than a day are refreshed before they are returned.
- **Better ranking.** A deterministic parametric ranker is blended 50/50 by rank with a MiniLM cross-encoder (`ms-marco-MiniLM-L6-v2`) on ONNX Runtime. NDCG@10 is 0.913 blended against 0.898 deterministic on our labelled queries. If the model cannot score, you get the deterministic order and `ranking: "fallback"`. Search never fails because of the model.
- **Small answers by default.** `detail: compact` returns identity, stock, prices, availability, links and key attributes. `full` adds photo, category and raw distributor attributes; it is the default for `get_part`.
- **Fast on the second ask.** TME and Mouser results are cached in PostgreSQL for 5 days. A cold search takes about 6 s, a cached one about 60 ms, and ranking 40 candidates takes 130 to 300 ms.
- **Polite to rate limits.** KINA waits and retries on rate limits, inside a 2-minute deadline per request, instead of failing at once.
- **Access control.** OAuth 2.1 for Claude, OIDC login, group-gated access that is checked again at every refresh and on bearer requests (a removed member is cut off within about an hour), and static tokens for scripts.

Details of every behaviour are in [docs/SEARCH.md](docs/SEARCH.md).

## Getting started

You need Docker with the Compose plugin and about 7 GB of free disk. About 2 GB of free RAM is a safe minimum. A Mouser API key and TME API v2 credentials are optional. Without them those distributors report `not_configured` and LCSC still works.

### Path 1: pull the published image

Images are published to `ghcr.io/alacrity-education/kina` as `:<tag>` and `:latest` for every `v*` release tag. The ranking model is baked into the image, so the running container never contacts Hugging Face.

Get `compose.yaml` and `.env.example` from this repository, then change the `kina` service to use the image instead of building it:

```yaml
services:
  kina:
    image: ghcr.io/alacrity-education/kina:latest
    # remove the "build: ." line
```

```bash
cp .env.example .env        # fill in MOUSER_API_KEY, TME_TOKEN, TME_APPLICATION_SECRET
docker compose up -d
```

`.env` is git-ignored. Never commit it. Every variable is described in `.env.example` and in [docs/CONFIGURATION.md](docs/CONFIGURATION.md).

### Path 2: build from source

```bash
git clone https://github.com/Alacrity-Education/KINA.git && cd KINA
cp .env.example .env
docker compose up -d --build
```

The first build takes a few minutes. It downloads the ranking model from a pinned Hugging Face revision and checks it against SHA-256 values before it goes into the image.

### What happens on the first start

KINA starts at once and downloads the JLCPCB parts database in the background: about 1 GB zipped, 5.3 GB on disk, into the `kina-data` volume. Until it is ready, LCSC reports `unavailable` and TME and Mouser work normally. The database is downloaded again when it is older than 5 days.

Check that it is ready:

```bash
curl -s localhost:8080/actuator/health      # {"status":"UP"}
curl -s localhost:8080/api/v1/distributors  # same data as the list_distributors tool
```

Call the `list_distributors` tool and look for LCSC `available: true`, TME and Mouser `configured: true`, and `ranking.ready: true`. The default mode is `dev`: no login, every request runs as a fake admin. Never expose `dev` mode to the internet. For anything public set `KINA_MODE=prod` (see [Security](#security)).

## Connecting Claude

Claude's remote connector needs HTTPS, which KINA does not terminate. Put a reverse proxy in front. It must send `X-Forwarded-Proto` and `X-Forwarded-Host` (or set `KINA_PUBLIC_BASE_URL=https://<host>`) and leave `/.well-known/*` and `/oauth/*` open to anonymous requests. See [docs/OPERATIONS.md](docs/OPERATIONS.md).

**Claude.ai and Claude Desktop.** Add a custom connector with the URL `https://<host>/mcp`, click Connect and sign in with your organisation's login. Claude then holds a 1-hour access token and a 30-day refresh token and renews them by itself. On Team and Enterprise plans an administrator adds the connector once and members click Connect.

**Claude Code.**

```bash
claude mcp add --transport http kina https://<host>/mcp
```

Run `/mcp` inside Claude Code, choose to sign in, and approve once in the browser. In `dev` mode `http://localhost:8080/mcp` works without any login.

**Scripts and machines without a browser.** Open the web UI at `https://<host>/`, create a static token (valid 30 days) and send it as a bearer token:

```bash
claude mcp add --transport http kina https://<host>/mcp --header "Authorization: Bearer <token>"
curl -s -H "Authorization: Bearer <token>" "https://<host>/api/v1/parts/search?q=10uF%20X7R%200805&max_results=5"
```

Static tokens are tied to the group too. Set `KINA_TOKENS_UI_ENABLED=false` to switch them off. The MCP tools are `search_parts`, `search_parts_batch`, `get_part`, `list_distributors` and `ping`. The full reference is in [docs/API.md](docs/API.md).

## How it works

1. **Parse.** The query is turned into a typed request: family, value, tolerance, ratings, dielectric, package, mounting, technology, and for connectors the type, gender, positions, rows, pitch and orientation. `parsed` in the response shows the result.
2. **Fetch.** KINA asks each distributor in its own words (`distributor_query`). LCSC is searched in the local JLCPCB SQLite database. TME and Mouser are served from the PostgreSQL cache when it holds enough fresh parts (5 days, 1 hour for a search that found nothing). Otherwise KINA calls the API, waits out rate limits within the 2-minute deadline, and keeps only parts with stock above zero. When a search finds nothing, KINA relaxes it step by step: no ratings, no tolerance, the minimal core phrase, no dielectric.
3. **Rank.** A deterministic ranker scores each part from 0 to 1 and gives it a `match` grade. The best 40 candidates then go to the in-process cross-encoder. The final score is half the deterministic rank and half the model rank.
4. **Respond.** The three smallest price brackets, `total_price` for the requested quantity, availability, lifecycle, links and attributes, per distributor, with the errors of any distributor that failed.

The design is documented in [docs/DESIGN.md](docs/DESIGN.md).

### Security

KINA has two modes. In `dev` there is no login. In `prod`, Claude is sent to KINA's OAuth endpoints. KINA delegates the login to your OIDC provider, reads the user's groups, and issues its own short-lived tokens only when the user belongs to one of `OIDC_REQUIRED_GROUPS`. The group is checked at three points: at login, on every refresh grant, and on every bearer request. A user who leaves the group loses all tokens within about an hour. If the provider is unreachable, access continues for a 4-hour grace period and then refresh grants fail until the provider is back. The provider's refresh tokens are stored encrypted with AES-256-GCM (`KINA_TOKEN_ENCRYPTION_KEY`, from `openssl rand -base64 32`).

Only the issuer URL, client id and client secret are required. Authentik is the worked example in [docs/OPERATIONS.md](docs/OPERATIONS.md#authentik-setup). The two modes, the group rules, the failure cases and Client ID Metadata Documents are explained in [docs/CONFIGURATION.md](docs/CONFIGURATION.md).

## Deploying to a server

`deploy-push/deploy-push.sh <sshhost>:<path>` builds the image, loads it into the Docker daemon of a server you reach over SSH and writes `compose.yaml` and `.env` there. It starts nothing. See [deploy-push/README.md](deploy-push/README.md). Volumes: `kina-data` (the JLCPCB file) and `pgdata` (PostgreSQL).

## Documentation

- [docs/CONFIGURATION.md](docs/CONFIGURATION.md): every setting, security modes, group access, measured numbers, troubleshooting.
- [docs/SEARCH.md](docs/SEARCH.md): search features, connector and USB vocabulary, ranking and the model.
- [docs/API.md](docs/API.md): REST and MCP reference.
- [docs/OPERATIONS.md](docs/OPERATIONS.md): deployment, reverse proxy, Authentik setup, backup, upgrades.
- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md): toolchain, build, local run, end-to-end checks.
- [docs/DESIGN.md](docs/DESIGN.md): the binding design.

## Contributing

Issues and pull requests go to [GitHub](https://github.com/Alacrity-Education/KINA).

- Build and test with `./mvnw -q verify`. It needs Docker for the PostgreSQL Testcontainer and must stay free of compiler warnings. CI runs the same command on every push to `main` and on pull requests.
- If you change a shared type or behaviour, change [docs/DESIGN.md](docs/DESIGN.md) in the same commit.
- Never commit `.env`, API keys, tokens or secrets. Use placeholders in docs and fixtures.
- A release is a `v*` tag. It publishes the image to `ghcr.io/alacrity-education/kina`.

## License

GNU Affero General Public License v3.0. See [LICENSE](LICENSE).
