# Configuration and operations reference

This page holds the reference material that used to live in the README: every setting, the two security modes, group access, and troubleshooting. For the overview see the [README](../README.md). For deployment, backup and upgrades see [OPERATIONS.md](OPERATIONS.md).

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

Group checks are active only in `prod` and only when at least one required group is set. Authentik setup, step by step, is in [docs/OPERATIONS.md](OPERATIONS.md#authentik-setup).

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
| `OIDC_EMAIL_FROM_PREFERRED_USERNAME` | `false` | `true`: when the provider sends no `email` claim, use `preferred_username` (then `upn`) as the address if it contains `@`. For providers that put the address there. |
| `OIDC_REQUIRE_VERIFIED_EMAIL` | `true` | With `OIDC_ALLOWED_EMAIL_DOMAINS`: refuse an address the provider marks as unverified (`email_verified: false`). Set `false` when the provider only admits accounts of your organisation and does not maintain `email_verified` (Authentik's default `email` mapping always sends `false`). The domain is still checked. |
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
| `KINA_SEARCH_HARDCONSTRAINTS_<FAMILY>` | unset (the decided table) | The hard constraints of one family, comma separated, replacing its whole default list; see `kina.search.hard-constraints` below. |
| `KINA_STRICT_CONSTRAINTS` | unset | Deprecated, replaced by `kina.search.hard-constraints`. A non-empty list still works: `mounting`, `technology` or `elements` missing from it are removed from every family's hard constraints, and KINA logs a warning at startup. Empty or unset: the decided defaults. |
| `KINA_LOW_STOCK_THRESHOLD` | `10` | A part with less stock than this, or less than twice the requested quantity, is `low_stock` and ranks lower. |
| `KINA_CACHE_TTL` | `3d` | Cached TME and Mouser search lists are searched again after this; stock and prices older than this that cannot be refreshed are returned with `stale: true`. |
| `KINA_CACHE_STOCK_TTL` | `24h` | Cached TME and Mouser stock and prices older than this are refreshed with one cheap call before a part is returned. |
| `KINA_CACHE_METADATA_RETENTION_TME`, `KINA_CACHE_METADATA_RETENTION_MOUSER` | `forever` | How long cached component data is kept per distributor (`forever` or a duration such as `3d`). See [Distributor terms](OPERATIONS.md#distributor-terms). |
| `KINA_CACHE_STALE_RANK_PENALTY` | `1.0` | Score penalty of a part with stale stock and prices; 1.0 ranks it below every fresh part. |
| `KINA_CROSS_ENCODER_ENABLED` | `true` | `false` disables the model; searches use the deterministic ranking (`ranking: "fallback"`, note `cross-encoder disabled`). |
| `KINA_CROSS_ENCODER_VARIANT` | `int8` | `int8` (about 23 MB, quantised, about twice as fast) or `fp32` (91 MB). Both are in the image. int8 picks the file that matches the CPU: `model_qint8_avx512_vnni` (AVX-VNNI and ARM) or `model_quint8_avx2`. |
| `KINA_CROSS_ENCODER_MODEL_URL` | `/opt/kina/cross-encoder` in Docker (bundled), `https://huggingface.co/cross-encoder/ms-marco-MiniLM-L6-v2/resolve/main/` otherwise | A local path is used in place and only read (a fine-tuned model, for example). An HTTP(S) directory with the same layout is downloaded into `KINA_CROSS_ENCODER_MODEL_DIR`. A value you set replaces the bundled model. |
| `KINA_CROSS_ENCODER_AUTO_DOWNLOAD` | `false` in Docker, `true` otherwise | Download missing files from an HTTP(S) `KINA_CROSS_ENCODER_MODEL_URL`. Set it to `true` in Docker only to use a mirror at runtime. |
| `KINA_CROSS_ENCODER_MODEL_DIR` | `${KINA_JLCPCB_DATA_DIR}/../cross-encoder` (`./data/cross-encoder` locally) | Download target for an HTTP(S) source. Not used by the bundled model. |
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
| `KINA_METRICS_PORT` | `9090` | Host port of the Prometheus and health endpoints (management server, no authentication). |
| `KINA_METRICS_BIND` | `127.0.0.1` | Host address the metrics port is published on. Keep it on localhost or a monitoring network; the endpoint has no authentication. |
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
| `kina.cache.ttl` | `3d` | `KINA_CACHE_TTL`. Freshness of cached TME and Mouser search lists, and the age after which stock and prices that cannot be refreshed are returned with `stale: true`. Search lists are purged after twice this. |
| `kina.cache.empty-result-ttl` | `1h` | Freshness of a cached search that found no in-stock part. It goes stale after this time, so a glitch or a new listing does not hide parts for days. |
| `kina.cache.stock-ttl` | `24h` | `KINA_CACHE_STOCK_TTL`. Cached stock and prices of the parts about to be returned that are older than this are refreshed (TME `/products/data`, 50 symbols per call; Mouser part-number search, 10 numbers per call). Every part reports its age as `stock_as_of`. |
| `kina.cache.metadata-retention.TME`, `kina.cache.metadata-retention.MOUSER` | `forever` | `KINA_CACHE_METADATA_RETENTION_TME`, `KINA_CACHE_METADATA_RETENTION_MOUSER`. How long a cached part's component data (everything but stock, prices and availability) is kept after it was last fetched: `forever` or a duration such as `3d`. Set a duration to honour a distributor's notice; the purge (every 6 hours) then deletes older rows. A malformed value fails startup. See [OPERATIONS.md, Distributor terms](OPERATIONS.md#distributor-terms). |
| `kina.cache.stale-rank-penalty` | `1.0` | `KINA_CACHE_STALE_RANK_PENALTY`. Subtracted from the score of a part with stale stock and prices before its distributor's list is re-sorted. Scores are 0 to 1, so 1.0 ranks every stale part below every fresh one; 0 keeps the order and only flags the part. |
| `kina.search.candidate-window` | `40` | Minimum parts fetched per distributor per query. |
| `kina.search.default-max-results` | `10` | Used when `max_results` is missing. |
| `kina.search.max-max-results` | `50` | Upper limit for `max_results`. |
| `kina.search.distributor-timeout` | `20s` | Budget for the active work of one distributor fetch. Time spent waiting on a rate limit does not count against it. |
| `kina.search.hard-constraints.<family>` | the decided table (below) | Per family, the constraints that are never relaxed: a part whose known value contradicts one is excluded (`excluded_by_constraints`, `excluded_by_constraints_detail`). Setting a family replaces its whole list; other families keep the defaults. Families: `resistor`, `capacitor`, `inductor`, `ferrite`, `crystal`, `oscillator`, `diode`, `transistor`, `regulator`, `connector`, `usb`, `default` (any other part). Names: `value`, `package`, `mounting`, `technology`, `elements`, `type`, `polarity`, `voltage` (exact Zener and regulator voltages), `load capacitance`, `connector type`, `gender`, `positions`, `pitch`, `usb type`, `pin configuration`, `usb standard`, and the normally relaxable `dielectric`, `tolerance`, `orientation`. A name the list leaves out is relaxable: the relaxation ladder may loosen it and it is reported in `constraints_relaxed`. Unknown families and names are ignored with a warning. Ratings are not in these lists: a part below a stated rating is always excluded (`excluded_below_spec`) unless the request passes `allow_below_spec`. |
| `kina.search.strict-constraints` | unset | `KINA_STRICT_CONSTRAINTS`, deprecated (see above). |
| `kina.search.low-stock-threshold` | `10` | `KINA_LOW_STOCK_THRESHOLD`. A part with less stock than this, or less than twice `quantity`, is `low_stock`. |
| `kina.search.quantity.stock-shortfall-penalty` | `0.3` | Score deduction for a part with less stock than `quantity` (it also ranks after every part that has enough). |
| `kina.search.quantity.low-stock-penalty` | `0.3` | Score deduction for a `low_stock` part that can still supply `quantity`. |
| `kina.search.quantity.moq-penalty` | `0.3` | Largest score deduction for a minimum order quantity above `quantity`, also for a quantity of 1: `0.3 * min(1, log10(moq / quantity) / 3)` (an MOQ of 10 for one piece costs 0.1, 1000 or more the whole 0.3). |
| `kina.search.lifecycle.last-time-buy-penalty` | `0.1` | Score deduction for a `last_time_buy` part. |
| `kina.search.lifecycle.supply-constrained-penalty` | `0.05` | Score deduction for a `supply_constrained` part (TME `HARDLY_AVAILABLE`). |
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
| `kina.ranking.cross-encoder.auto-download` | `true` (`false` in the image) | `KINA_CROSS_ENCODER_AUTO_DOWNLOAD`. `false` never downloads; only files already present are used. |
| `kina.distributors.mouser.max-results-per-search` | `50` | Parts requested from Mouser per query (one API call). |
| `kina.distributors.tme.max-results-per-search` | `60` | Parts requested from TME per query (up to 3 pages). |
| `kina.distributors.tme.currency` and `.language` | `EUR` and `en` | TME price currency and language. |
| `kina.distributors.tme.excluded-statuses` | `CANNOT_BE_ORDERED`, `ONLY_FOR_SPECIAL_ORDER`, `EXTERNAL_WAREHOUSE`, `NOT_IN_OFFER`, `PRODUCT_BLOCKED`, `INVALID`, `BLOCKED_FOR_ZBL_*` | TME product statuses that do not ship now; those parts are dropped. A trailing `*` matches a prefix. |
| `kina.jlcpcb.refresh-after` | `5d` | Age at which the JLCPCB database is downloaded again. |
| `kina.jlcpcb.check-interval` | `1h` | How often KINA checks that age. |
| `kina.jlcpcb.max-results-per-search` | `200` | Rows read from the JLCPCB database per query. |
| `kina.jlcpcb.auto-download` | `true` | `false` stops KINA from downloading the database. |

## Operations notes

- Deployment to a server over SSH: `deploy-push/deploy-push.sh <sshhost>:<path>` builds the image, loads it into the server's Docker daemon and writes `compose.yaml` and `.env` there without starting anything (see [deploy-push/README.md](../deploy-push/README.md)).
- Volumes: `kina-data` (JLCPCB SQLite file only), `pgdata` (PostgreSQL). The ranking model is in the image, under `/opt/kina/cross-encoder`.
- JLCPCB database: checked every hour, downloaded again when older than 5 days. The old file keeps serving while the new one downloads.
- TME and Mouser cache: search lists 3 days, a cached search with zero parts 1 hour (`kina.cache.empty-result-ttl`); stock and prices are refreshed after 24 hours and flagged `stale` after 3 days without a refresh; component data is kept (`kina.cache.metadata-retention`, default `forever`). Every 6 hours the purge deletes search lists older than 6 days (2 x TTL) and parts whose metadata retention expired.
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
| `ranking: "fallback"` | Read `ranking_note`. `cross-encoder model not loaded yet`: the model is still loading or cannot be loaded; check `ranking.last_error` and `ranking.model_dir` in `list_distributors` and the ERROR line in `docker compose logs kina` (a wrong `KINA_CROSS_ENCODER_MODEL_URL`, or `fp32` on an image built with `CROSS_ENCODER_VARIANTS=int8`). KINA checks again every hour. `cross-encoder disabled`: `KINA_CROSS_ENCODER_ENABLED` is `false`. `cross-encoder timeout ...` or `busy ...`: the host is short of CPU; lower the load or check `KINA_CROSS_ENCODER_THREADS`. `cross-encoder failed: ...`: see the log. Search still works. |
| TME or Mouser `error: "not_configured"` | The credentials are missing in `.env`. Restart with `docker compose up -d`. |
| Slow search (up to 2 minutes), `rate_limit_waited_ms` above 0 | The distributor rate limited the request and KINA waited. This is normal for Mouser's 30 calls per minute. The log has a `rate limited ... cooling down` line. |
| Mouser `error: "rate_limited"` | The limit outlasted the 2-minute budget, usually an exhausted daily quota (1 000 calls). Wait, and rely on the cache. |
| Client or proxy times out on a search | Their read timeout is below about 2.5 minutes. Raise it (see [docs/OPERATIONS.md](OPERATIONS.md)). |
| 401 on `/api` or `/mcp` in `prod` | Missing, expired or revoked token. The `WWW-Authenticate` header points to the OAuth metadata. |
| Claude connector cannot sign in | Check HTTPS, the `X-Forwarded-*` headers or `KINA_PUBLIC_BASE_URL`, and that `https://<host>/.well-known/oauth-protected-resource` shows your public origin. |
| "Access denied" page after signing in (`/login-denied`, 403) | The page says why: no e-mail address from the provider, an unverified address, a domain not in `OIDC_ALLOWED_EMAIL_DOMAINS`, or no group listed in `OIDC_REQUIRED_GROUPS`. The KINA log has the reason and the claim names the provider sent. For a group, ask the administrator to add you, then sign out of the identity provider and sign in again. For the other reasons see [Troubleshooting login](OPERATIONS.md#troubleshooting-login). |
| Claude asks you to reconnect; a refresh answers `invalid_grant` | Either you were removed from the group, or the identity provider was unreachable for longer than `KINA_MEMBERSHIP_GRACE` (4 hours). In the second case nothing was revoked and it recovers by itself when the provider is back. Without `KINA_TOKEN_ENCRYPTION_KEY`, users must also sign in again every 24 hours. |
| 429 on `POST /oauth/register` | The client IP went over `KINA_OAUTH_REGISTER_RATE_LIMIT_PER_MINUTE`. Wait for `Retry-After` seconds. If every client seems to share one address, the proxy is not overwriting `X-Forwarded-For`. |
| Claude Code still shows the Approve page | Expected. Claude Code redirects to a local port, so KINA always asks. Claude.ai with Claude's published identity skips the page. |
| `invalid_client` at `/oauth/token`, or an error page at `/oauth/authorize`, with an `https` `client_id` | The host is not in `KINA_OAUTH_TRUSTED_CLIENT_HOSTS`, the document is invalid or unreachable, or the redirect URI is not listed in it. |

### Hard constraints (`kina.search.hard-constraints`)

The decided defaults (user decision 2026-10-07; `search.ConstraintPolicy.DEFAULT_HARD`, DESIGN.md 3.4):

| Family | Hard constraints |
|---|---|
| `resistor`, `capacitor` | type, value, package, mounting, technology, elements |
| `inductor` | type, value, mounting, technology (the package is relaxable) |
| `ferrite` | type, value, package, mounting, elements |
| `crystal` | type, value, load capacitance, mounting (the package is relaxable) |
| `oscillator` | type, value, mounting (the package is relaxable) |
| `diode` | type, voltage, package, mounting |
| `transistor` | type, polarity, package, mounting |
| `regulator` | type, voltage, package, mounting |
| `connector` | type, connector type, gender, positions, pitch, package, mounting |
| `usb` | type, usb type, pin configuration, usb standard, gender, mounting |
| `default` | type, value, package, mounting, technology, elements, polarity, voltage |

In YAML:

```yaml
kina:
  search:
    hard-constraints:
      inductor: type,value,package,mounting,technology   # make an inductor's package hard as well
```

The same as an environment variable: `KINA_SEARCH_HARDCONSTRAINTS_INDUCTOR=type,value,package,mounting,technology`. Write `load-capacitance`, `connector-type` and so on with a hyphen in an environment variable if spaces are a problem; both forms are accepted.
