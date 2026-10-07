# KINA operations guide

This guide covers running KINA for real: HTTPS, login and group access (with an Authentik setup guide), secrets, storage, backups, upgrades, sizing and hardening. For a first start see the [README](../README.md). For endpoints see [API.md](API.md).

## Services and volumes

`compose.yaml` runs two services. Ranking needs no extra container: the model runs inside `kina`.

| Service | Image | Port | Volume | Purpose |
|---|---|---|---|---|
| `kina` | built from `Dockerfile` (memory limit `KINA_MEM_LIMIT`, default `2g`) | `${KINA_PORT:-8080}` published on the host; management port `${KINA_METRICS_PORT:-9090}` (health and Prometheus, no authentication) on `${KINA_METRICS_BIND:-127.0.0.1}` | `kina-data` at `/data` | The application. The volume holds only the JLCPCB SQLite file (`/data/jlcpcb`). The ranking model is part of the image (`/opt/kina/cross-encoder`). |
| `postgres` | `postgres:17-alpine` | none published | `pgdata` | Cache, users, tokens, OAuth clients, JLCPCB download timestamp, Prometheus counters. Schema is managed by Flyway at KINA startup. |

`compose.yaml` sets the project name `name: kina`, so the volumes are `kina_kina-data` and `kina_pgdata` whatever the directory is called. Use `docker volume ls` to see them.

## Environment

Create `.env` from `.env.example`, keep it out of git and restrict it (`chmod 600 .env`). Compose passes it to the `kina` container, and substitutes `KINA_PORT`, `KINA_METRICS_PORT`, `KINA_METRICS_BIND` and `KINA_MEM_LIMIT` in `compose.yaml`. The ranking variables (`KINA_CROSS_ENCODER_*`) reach the `kina` container through `.env`. The full variable list is in the [README](../README.md#configuration-reference).

A minimal production `.env`:

```bash
KINA_MODE=prod
KINA_PUBLIC_BASE_URL=https://kina.example.com
OIDC_ISSUER_URI=https://idp.example.com/realms/main
OIDC_CLIENT_ID=kina
OIDC_CLIENT_SECRET=<secret>
OIDC_REQUIRED_GROUPS=ElectronicsEngineer
OIDC_ALLOWED_EMAIL_DOMAINS=alacrity.ro
KINA_TOKEN_ENCRYPTION_KEY=<output of: openssl rand -base64 32>
MOUSER_API_KEY=<key>
TME_TOKEN=<token>
TME_APPLICATION_SECRET=<secret>
KINA_CROSS_ENCODER_THREADS=4
KINA_PORT=127.0.0.1:8080
KINA_MEM_LIMIT=2g
```

`KINA_PORT=127.0.0.1:8080` works because `compose.yaml` publishes `"${KINA_PORT:-8080}:8080"`; it binds KINA to the loopback interface so only the reverse proxy on the same host can reach it.

The management port (health and Prometheus) is published as `"${KINA_METRICS_BIND:-127.0.0.1}:${KINA_METRICS_PORT:-9090}:9090"`, so by default only the host itself reaches it. It has no authentication; see [Monitoring](#monitoring) before you change `KINA_METRICS_BIND`. Do not proxy it through the public reverse proxy.

Register this redirect URI at the OIDC provider: `https://kina.example.com/login/oauth2/code/oidc`. KINA refuses to start in `prod` mode without `OIDC_ISSUER_URI` and `OIDC_CLIENT_ID`. Discovery of the provider happens on the first login, not at startup.

The group settings are:

- `OIDC_REQUIRED_GROUPS`: comma-separated; a user needs at least one. Empty means no group check.
- `OIDC_ALLOWED_EMAIL_DOMAINS`: optional, for example `alacrity.ro`. The provider must then send an `email` claim, and by default one not marked unverified.
- `OIDC_REQUIRE_VERIFIED_EMAIL` (default `true`): set `false` when the login source only admits accounts of your organisation (for example Google Workspace sign-in restricted to your domain). KINA then ignores `email_verified: false` and checks only the domain. Authentik's default `email` mapping always sends `email_verified: false`; see [Troubleshooting login](#troubleshooting-login).
- `OIDC_EMAIL_FROM_PREFERRED_USERNAME` (default `false`): set `true` only if your provider sends no `email` claim but puts the address in `preferred_username` or `upn`.
- `KINA_TOKEN_ENCRYPTION_KEY`: base64 of 32 random bytes. Without it KINA cannot re-check a member in the background, logs a WARN at startup, and users must sign in again every 24 hours. Treat it like a password and never commit it.
- `OIDC_GROUPS_CLAIM` (default `groups`) and `OIDC_EXTRA_SCOPES` (default empty) only matter if your provider differs from the Authentik example.

The timing settings (`KINA_MEMBERSHIP_RECHECK_INTERVAL` 1h, `KINA_MEMBERSHIP_GRACE` 4h, `KINA_RELOGIN_INTERVAL_WITHOUT_RECHECK` 24h) rarely need a change. Leave unused duration variables unset: an empty value is not a valid duration. The full list is in the [README](../README.md#configuration-reference).

Enabling `OIDC_REQUIRED_GROUPS` on a deployment that already has users: each user must sign in once before their refresh grants and static tokens work again.

## Authentik setup

KINA is the OAuth 2.1 server that Claude talks to. It delegates the login to your OIDC provider and then requires membership of the configured groups. Authentik is the worked example here; the same steps map to any OIDC provider that puts the groups in a claim. This guide uses `https://<authentik>` for Authentik and `https://<kina>` for KINA.

### In Authentik

1. **Source.** Make sure the login source (for example Google) only accepts your organisation. Use an e-mail-domain policy on the source's flows, or set the Google consent screen to Internal.
2. **Provider.** Create an OAuth2/OpenID provider named `KINA`:
   - Client type: confidential. Copy the client ID and the client secret.
   - Grant types: `authorization_code` and `refresh_token`.
   - Redirect URIs: strict, exactly `https://<kina>/login/oauth2/code/oidc`.
   - Signing key: the self-signed certificate, so tokens are signed with RS256.
   - Scopes: `openid`, `email`, `profile` and `offline_access`. The `email` scope uses the mapping "authentik default OAuth Mapping: OpenID 'email'"; without it KINA gets no address and refuses everyone when `OIDC_ALLOWED_EMAIL_DOMAINS` is set. That default mapping always sends `email_verified: false`, so with `OIDC_ALLOWED_EMAIL_DOMAINS` either use a custom `email` mapping that sends `true` or set `OIDC_REQUIRE_VERIFIED_EMAIL=false` (see [Troubleshooting login](#troubleshooting-login)). The default `profile` scope already carries `groups`. If you use a dedicated `groups` scope mapping, add that scope too and set `OIDC_EXTRA_SCOPES=groups` in KINA.
   - Authorization flow: implicit consent (`default-provider-authorization-implicit-consent`). KINA has its own consent decision, so one consent screen is enough.
   - Include claims in id_token: enabled.
   - Refresh token validity: at least 30 days, so Authentik does not end sessions earlier than KINA does.
3. **Application.** Create an application with slug `kina` and the provider above.
4. **Bind the group.** In the application's bindings, bind the group `ElectronicsEngineer`. Without a binding, everyone with an Authentik account can sign in. KINA's group check is a second gate; the binding is the first.
5. **Note the issuer.** It is `https://<authentik>/application/o/kina/`.

### In KINA

```bash
KINA_MODE=prod
KINA_PUBLIC_BASE_URL=https://<kina>
OIDC_ISSUER_URI=https://<authentik>/application/o/kina/
OIDC_CLIENT_ID=<from Authentik>
OIDC_CLIENT_SECRET=<from Authentik>
OIDC_REQUIRED_GROUPS=ElectronicsEngineer
OIDC_ALLOWED_EMAIL_DOMAINS=alacrity.ro
KINA_TOKEN_ENCRYPTION_KEY=<openssl rand -base64 32>
```

Then `docker compose up -d`. Sign in with a member account: the web UI should open. Sign in with an account outside the group: Authentik or KINA must refuse it (KINA shows the "Access denied" page, HTTP 403).

### In Claude

- claude.ai and Claude Desktop: add a custom connector with exactly `https://<kina>/mcp` and keep the default identity option. Click Connect and sign in.
- Team and Enterprise: an Owner adds the connector once (Organization settings, Connectors, Add, Custom). Members click Connect and sign in.
- Claude Code: `claude mcp add --transport http kina https://<kina>/mcp`, then `/mcp` to sign in (one Approve click).

No consent page appears for Claude's published identity. Claude Code always shows the Approve page, because it redirects to a local port.

### Permission prompts for the tools

KINA marks all its tools as read-only, but Claude still asks the user before using them until the user allows them. This is Claude's behaviour, and KINA cannot change it.

- claude.ai and Claude Desktop: every tool of a custom connector starts as "Not set". Each user opens Customize, Connectors, the KINA connector, Tool permissions, and sets Always allow for `search_parts`, `search_parts_batch`, `get_part`, `list_distributors` and `ping` (or for the whole tool group). An organisation Owner can add the connector for the team but cannot pre-set the per-tool permissions for users. Tell new users to do this once after they click Connect. Sources: [Use connectors to extend Claude's capabilities](https://support.claude.com/en/articles/11176164-use-connectors-to-extend-claude-s-capabilities) and [Connectors: getting started](https://claude.com/docs/connectors/getting-started).
- Claude Code: add `{"permissions": {"allow": ["mcp__kina"]}}` to the user or project `settings.json`. The entry allows every tool of the server named `kina`. A single tool is `mcp__kina__search_parts`. Source: [Claude Code permissions](https://code.claude.com/docs/en/permissions).

### What to expect after removing someone

Remove the user from `ElectronicsEngineer` in Authentik. The user keeps working until the current 1-hour access token ends. At the next refresh KINA asks Authentik again, sees that the group is gone, revokes the user and answers `invalid_grant`. Claude then asks the user to reconnect, and the login is refused. Static tokens follow the same rule through a background re-check.

If Authentik is unreachable, access continues for `KINA_MEMBERSHIP_GRACE` (4 hours) after the last successful check. After that, refreshes fail with `invalid_grant` but nothing is revoked, and everything works again when Authentik is back.

## HTTPS reverse proxy

KINA speaks plain HTTP. Claude's remote connector and OAuth need HTTPS, so terminate TLS in a reverse proxy. Two things matter:

1. The proxy must send `X-Forwarded-Proto` and `X-Forwarded-Host` (and `X-Forwarded-Port` for a non-standard port). KINA honours them (`server.forward-headers-strategy=framework`) and uses them in the OAuth metadata, redirects and the `WWW-Authenticate` header. Or set `KINA_PUBLIC_BASE_URL`, which wins over the headers.
2. The proxy should overwrite, not append to, any `X-Forwarded-*` headers a client sent, and must be the only way to reach KINA. `X-Forwarded-For` matters most: KINA uses it as the client address for the rate limit on `/oauth/register`, so a header a client can forge lets it dodge the limit.

Do not buffer the MCP endpoint, and allow long requests. A search can wait up to 2 minutes for a rate-limited distributor (`kina.search.max-request-duration`) and then rank (5 s per query, 60 s per batch), so set the read timeout above about 2.5 minutes. `spring.ai.mcp.server.request-timeout` is `3m`.

### Caddy

Caddy sets the `X-Forwarded-*` headers itself and obtains certificates automatically.

```caddyfile
kina.example.com {
    reverse_proxy 127.0.0.1:8080 {
        flush_interval -1
        transport http {
            response_header_timeout 180s
        }
    }
}
```

### nginx

```nginx
server {
    listen 443 ssl;
    http2 on;
    server_name kina.example.com;

    ssl_certificate     /etc/letsencrypt/live/kina.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/kina.example.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host              $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host  $host;
        proxy_set_header X-Forwarded-Port  $server_port;
        proxy_set_header X-Forwarded-For   $remote_addr;
        proxy_buffering off;
        proxy_read_timeout 180s;
        proxy_send_timeout 180s;
    }
}
```

### Proxy checklist for Claude

- Keep these paths anonymous and free of bot or JavaScript challenges, forward auth, basic auth or an identity-aware proxy: `/.well-known/*`, `/oauth/register`, `/oauth/token`, `/oauth/revoke` and `/mcp`. Claude's servers fetch them and cannot solve a challenge. `/mcp` without a token must answer `401` with `WWW-Authenticate`, not a login page.
- `/oauth/authorize` and the login redirect are opened by users' browsers.
- Do not strip the CORS headers KINA sends on those paths, and do not answer `OPTIONS` yourself.
- Overwrite `X-Forwarded-For` (nginx: `proxy_set_header X-Forwarded-For $remote_addr;`). Caddy replaces an `X-Forwarded-For` that comes from an untrusted client by default.
- The connector URL must equal the `resource` KINA advertises: `https://<host>/mcp`. Do not mount KINA under a path prefix unless `KINA_PUBLIC_BASE_URL` includes it.
- Read and send timeouts of at least 180 seconds, and no buffering on `/mcp`.
- Anthropic's servers call KINA from `160.79.104.0/21`. This range is published by Anthropic; verify it before allowlisting. Do not restrict the whole host to it: browsers must reach `/oauth/authorize`, and Claude Code connects from users' own machines. If you use a WAF, make sure it does not block that range.
- Optionally rate-limit `/oauth/token` at the proxy as well (for example 60 requests a minute per IP). KINA already limits `/oauth/register` per client IP (`KINA_OAUTH_REGISTER_RATE_LIMIT_PER_MINUTE`, 30 by default).

Verify from outside:

```bash
curl -s https://kina.example.com/.well-known/oauth-protected-resource
curl -s https://kina.example.com/.well-known/oauth-authorization-server
```

Every URL in the answers must start with `https://kina.example.com`. If they show `http://` or an internal host name, fix the headers or set `KINA_PUBLIC_BASE_URL`.

## Storage and backup

| Data | Where | Backup |
|---|---|---|
| Users, tokens, OAuth clients, refresh tokens, TME/Mouser cache | PostgreSQL, volume `pgdata` | Back up with `pg_dump`. |
| JLCPCB parts database | SQLite file in volume `kina-data` (`/data/jlcpcb`) | Not needed. KINA downloads it again. |
| Ranking model | In the image, `/opt/kina/cross-encoder` | Not needed. Rebuild or reload the image. Back up a fine-tuned model directory yourself if you mount one. |

Dump and restore PostgreSQL:

```bash
docker compose exec -T postgres pg_dump -U kina -d kina -Fc > kina-$(date +%F).dump

# restore into an empty database (stop kina first)
docker compose stop kina
docker compose exec -T postgres pg_restore -U kina -d kina --clean --if-exists < kina-2026-10-05.dump
docker compose start kina
```

What you lose without a backup: users, every token (the 30-day web tokens and the OAuth ones, so Claude connectors must reconnect), registered OAuth clients, and the cache (which refills by itself). The cache is the only part you can ignore.

If the JLCPCB file is missing, KINA downloads it at the next startup or hourly check. If you restore a `kina-data` volume (or pre-seed the file) and the database has no matching `jlcpcb_database` row, for example after restoring `kina-data` without `pgdata`, KINA adopts the file: it validates it, records a row and uses the file's modification time as the download time. That check took about 19 s in the background on the measured host. An invalid file is downloaded again. A restored file older than 5 days is refreshed in the background while the old one keeps serving.

Disk use: the unpacked JLCPCB database is 5.33 GB. During a refresh the new file is downloaded (about 1 GB zipped) and unpacked next to the old one before it replaces it, so plan for roughly twice the database size plus the zip on the `kina-data` volume at peak (this is an estimate, not a measurement). Choosing `KINA_JLCPCB_LIBRARY=current-parts-fts5.db` or `basic-parts-fts5.db` reduces it.

## Verifying a deployment

`scripts/e2e/` holds end-to-end checks that drive a running stack through its published port (Python 3.10+, standard library only):

```bash
python3 scripts/e2e/kina_e2e.py                 # suites ui, mcp, oauth, forwarded, rest, metrics against http://localhost:8080 (management port 9090)
KINA_URL=http://host:8080 python3 scripts/e2e/kina_e2e.py
scripts/e2e/prod_smoke.sh                       # prod mode smoke test in a throwaway container on port 18080
```

For group access there is a separate test against a real, disposable Authentik (`scripts/e2e/authentik/`; needs Docker and Python 3.10+, about 70 seconds once the images are cached):

```bash
./mvnw -q -DskipTests package                          # the driver runs target/kina.jar
python3 scripts/e2e/authentik/kina_authentik_e2e.py   # Authentik 2026.8; removes everything at the end
python3 scripts/e2e/authentik/kina_authentik_e2e.py --keep   # keep Authentik (localhost:19000) and KINA (18080) running
```

It starts Authentik, creates the group, users, provider and application, runs KINA in `prod` mode and checks that a member can connect, that a removed member is refused at the next refresh, and that non-members and guests are refused. It does not touch your own stack. Details are in [DEVELOPMENT.md](DEVELOPMENT.md).

Run the other checks against a staging or dev stack. They create tokens and OAuth clients in the database and, on a cold cache, make 2 Mouser calls. Details are in the "End-to-end checks" section of [DEVELOPMENT.md](DEVELOPMENT.md).

## Deploying to a server with deploy-push

`deploy-push/deploy-push.sh` builds the image `alacrity-education/kina`, loads it into the Docker daemon of a server that you reach over SSH, and writes `compose.yaml` (using that image, no `build:`) and `.env` into a directory under the remote home. It starts nothing.

```bash
deploy-push/deploy-push.sh kina-prod:apps/kina    # <ssh alias>:<path relative to the remote home>
```

`.env` is copied from `.env.example` on the first deploy only. Later deploys keep it and list variables of `.env.example` that it does not set. Then fill in `.env` on the server and run `docker compose up -d` there. Options, prerequisites, rollback and the test are in [deploy-push/README.md](../deploy-push/README.md).

## Upgrading

```bash
git pull
docker compose up -d --build
```

Flyway applies new database migrations when `kina` starts (the current ones are V1 to V12; V4 adds the group-authorisation columns, V5 the `out_of_stock_matches` column of `cached_searches`, V6 the `metrics_counters` table, V7 the `constraints_relaxed` column of `cached_searches`, V8 the cache model: `cached_parts.fetched_at` becomes `stock_fetched_at`, plus `metadata_fetched_at` and `in_stock`, V9 the `type="unknown"` tag on the stored search counters, V10 the `requested_parts` column of `cached_searches`: the outcome of the lookup of a part number named in a query, so a repeated search makes no distributor call, V11 removes the attributes KINA derived itself (`Family`, `Subtype`, the USB keys...) from the cached parts, so they are derived again by the running version on every read, V12 the `type` column of `cached_searches` and `cached_parts` and the `metrics_backfill` table; the first start after it runs the metrics backfill once). Since V6 the health check is on the management port (9090): update external health checks and load-balancer probes that called `/actuator/health` on 8080. Take a `pg_dump` first. The ranking model comes with the image, so an upgrade never downloads it. A `/data/cross-encoder` directory left in `kina-data` by an older version is no longer used; delete it if you want the space back: `docker compose exec kina rm -rf /data/cross-encoder`. Changing the JLCPCB library variant makes KINA download that file on the next check; the old file stays in the volume and can be deleted by hand.

Upgrading to group access: set the group variables from [Environment](#environment) and restart. Existing users must sign in once. OAuth access tokens now live 1 hour (`expires_in` 3600) and refresh tokens 30 days (they were 30 days and 90 days); clients refresh by themselves. Refresh tokens issued earlier keep their old expiry.

Upgrading to the cache model with kept metadata (V8): existing rows keep their payload; their metadata age starts at their old fetch time. The cache TTL drops from 5 to 3 days. Nothing else to do; see [Distributor terms](#distributor-terms) before you change the metadata retention.

Upgrading to connector-aware search: cached TME and Mouser results from before the upgrade stay in the cache until they expire (3 days by default). Connector queries asked before the upgrade keep returning the old, generic results until then, because the cache key is your own query text. Ask again with `bypass_cache` to refresh one query at once, or wait for the cache TTL. Bypassing costs Mouser calls, so use it only for the queries you care about. No migration is involved.

Upgrading to USB connector precision: the same cache rule applies. USB queries asked before the upgrade keep their old results (for example power supplies and cables for `USB-C receptacle 17 pin`) until the cache entry expires. Ask again with `bypass_cache` for the queries you care about. No migration is involved.

The research dataset in `docs/research/data` (41 queries, 1 619 candidates) is rebuilt with `scripts/research/build_dataset.py`, then `scripts/research/build_usb_dataset.py`, in that order. It is not needed to run KINA.

Roll back by checking out the previous version and running `docker compose up -d --build` again. Migrations are not reversed, so restore the dump if a migration must be undone.

## Ranking model

Ranking blends the deterministic score with the `cross-encoder/ms-marco-MiniLM-L6-v2` model (Apache-2.0, 22.7 M parameters). It runs in the KINA process on the CPU with ONNX Runtime. There is no GPU overlay, no sidecar and no ranking API key. The CPU is fast enough: 40 candidates take 130 to 300 ms with 4 threads (int8), and 16 ms when the scores are cached.

### The model is in the image

The `Dockerfile` downloads the model while the image is built, from the pinned Hugging Face revision `233902d25c440f23af6f7d6e94d2946bac0bee0a`, and checks every file against the SHA-256 values in `docker/model/ms-marco-MiniLM-L6-v2.sha256`. A mismatch fails the build. The files land in `/opt/kina/cross-encoder` (read only, owned by uid 10001) with a `model.json` that records the source, revision and hashes. The image sets `KINA_CROSS_ENCODER_MODEL_URL=/opt/kina/cross-encoder` and `KINA_CROSS_ENCODER_AUTO_DOWNLOAD=false`, so the running container never contacts Hugging Face. Every host is "air-gapped" for the model: only the build machine needs internet access.

KINA loads the model in the background after startup (about 0.2 s on the measured host). Startup never waits for it. Until it is loaded, searches report `ranking: "fallback"` with the note `cross-encoder model not loaded yet`. The log shows:

```
Cross-encoder model: local directory /opt/kina/cross-encoder used in place (read only, no download)
Cross-encoder ready: onnx/model_qint8_avx512_vnni.onnx (int8), revision 233902d2..., 4 threads, from /opt/kina/cross-encoder
```

If the directory is missing or unusable (for example an image built with the wrong arguments), KINA logs one ERROR line, ranks with the deterministic order and checks again every hour (`kina.ranking.cross-encoder.check-interval`). It never falls back to a download.

The image holds both variants by default. The int8 file matches the CPU: `model_qint8_avx512_vnni` (AVX-512 VNNI, AVX-VNNI and ARM) or `model_quint8_avx2` (AVX2). A CPU without AVX2 uses fp32 (91 MB). `KINA_CROSS_ENCODER_VARIANT=fp32` forces fp32 and works from the bundled files.

Build arguments (`docker compose build --build-arg NAME=value kina`):

| Argument | Default | Effect |
|---|---|---|
| `CROSS_ENCODER_VARIANTS` | `int8,fp32` | `int8` leaves out the fp32 file and makes the image about 90 MB smaller. Then `KINA_CROSS_ENCODER_VARIANT=fp32` and CPUs without AVX2 have no model and rank deterministically. |
| `CROSS_ENCODER_SOURCE` | the pinned Hugging Face `resolve/<revision>/` URL | Any HTTP(S) directory with the same layout, for example an internal mirror or a fine-tuned model. |
| `CROSS_ENCODER_SHA256_FILE` | `ms-marco-MiniLM-L6-v2.sha256` | Hash file in `docker/model/`. A custom source needs its own file (same `sha256sum` format). |
| `CROSS_ENCODER_SKIP_VERIFY` | `0` | `1` skips the hash check. Only for a custom source you trust. |
| `CROSS_ENCODER_REVISION` | from a Hugging Face URL | The revision recorded in `model.json` and shown as `model_revision`. |

`deploy-push/deploy-push.sh` builds with the defaults. For other arguments, build the image yourself and push it with `--no-build`.

### Fine-tuned or mirrored model

Precedence: a `KINA_CROSS_ENCODER_MODEL_URL` that you set in `.env` (or in the compose `environment`) replaces the bundled directory, because the image value is only a default.

- A local path, for example a fine-tuned model from `scripts/ranking/finetune_cross_encoder.sh` copied into the `kina-data` volume as `/data/cross-encoder-finetuned`, is used in place and only read:

```bash
docker run --rm -v kina_kina-data:/data -v "$PWD/data/cross-encoder-finetuned":/src:ro alpine \
  sh -c 'cp -r /src /data/cross-encoder-finetuned && chown -R 10001:10001 /data/cross-encoder-finetuned'
# .env: KINA_CROSS_ENCODER_MODEL_URL=/data/cross-encoder-finetuned
docker compose up -d kina
```

- An HTTP(S) directory (an internal mirror) is downloaded at runtime only if you also set `KINA_CROSS_ENCODER_AUTO_DOWNLOAD=true`. The files go to `KINA_CROSS_ENCODER_MODEL_DIR` (default `/data/cross-encoder` in the volume) and are checked against the source's size and SHA-256 headers. Without that variable the image refuses to download and logs an ERROR.
- To bake a fine-tuned model into the image instead, serve its directory over HTTP during the build and give it its own hash file:

```bash
(cd data/cross-encoder-finetuned && sha256sum config.json tokenizer_config.json vocab.txt onnx/*.onnx) \
  > docker/model/finetuned.sha256
(cd data/cross-encoder-finetuned && python3 -m http.server 8000) &
docker build --network host -t kina:latest \
  --build-arg CROSS_ENCODER_SOURCE=http://127.0.0.1:8000/ \
  --build-arg CROSS_ENCODER_SHA256_FILE=finetuned.sha256 \
  --build-arg CROSS_ENCODER_REVISION=finetuned-synth-2026-10-05 .
```

Run `CrossEncoderEvaluationTest` first (see the [README](../README.md#fine-tuning)); it must give a blended NDCG@10 of at least 0.90. `model_revision` and `model_dir` in `list_distributors` show which model is loaded.

## Sizing

| Component | Memory | Notes |
|---|---|---|
| Ranking model (cross-encoder) | an estimated 100 to 250 MB, outside the JVM heap | Not measured yet. int8 about 100 to 200 MB, fp32 about 150 to 250 MB. In the image: 46 MB for the two int8 files plus 91 MB fp32 (138 MB layer). There is no separate container. |
| KINA JVM | about 485 MiB measured (before the model was added) | The container limit is `KINA_MEM_LIMIT` (default `2g`) and the heap is 75 percent of it (1.5 GiB). The JVM exits on out-of-memory (`-XX:+ExitOnOutOfMemoryError`) and Compose restarts it. |
| PostgreSQL | about 45 MiB measured | The cache and tokens are small (`pgdata` about 50 MB after the end-to-end run). |
| JLCPCB SQLite file | page cache | The file is read through the operating system page cache; free RAM makes LCSC queries faster. |

These numbers come from the measurements in `docs/DEVELOPMENT.md` ("Measured on 2026-10-05"; 24-core, 30 GB host). A host with 2 GB of free RAM is a safe minimum, 4 GB is comfortable. The `kina` container limit (`KINA_MEM_LIMIT`, default `2g`) must cover the heap (75 percent of it) plus the model's native memory and the Java runtime; if the container is killed for memory, raise it a little. CPU: `KINA_CROSS_ENCODER_THREADS` (default: the smaller of 4 and the cores) should not exceed the physical core count. Oversubscribing SMT siblings makes scoring much slower and rankings can time out and fall back. Measured cost of ranking 40 candidates: 130 to 300 ms with 4 threads and int8, 16 ms with cached scores. A cold search for three distributors took about 6 s, a repeat with a larger `max_results` about 60 ms. Check `ranking_note` in search results for timeouts. The size of the artefacts: `kina.jar` is about 115 MB, of which the ONNX Runtime jar is about 53 MB.

## Token lifecycle

- Static tokens (web UI): created by a signed-in user, 30 days, shown once, stored only as a SHA-256 hash. They are for scripts and machines without a browser. The token list shows prefix, creation, expiry, last use (updated at most once a minute) and status. Set `KINA_TOKENS_UI_ENABLED=false` to stop users creating them; existing ones keep working until they expire or are revoked.
- OAuth access tokens: issued by the OAuth flow, 1 hour (`KINA_OAUTH_ACCESS_TOKEN_VALIDITY`), named `MCP: <client name>`, visible in the same list. Refresh tokens last 30 days (`KINA_OAUTH_REFRESH_TOKEN_VALIDITY`) and rotate on every use. Claude renews them by itself.
- Expired static tokens stop working; there is no automatic renewal. Create a new one and update the client (`claude mcp remove kina`, then `claude mcp add ...` again).
- A user who loses group membership is blocked: every access and refresh token of that user is revoked. The block is lifted by a later successful login.
- The provider's refresh tokens (used for the re-checks) are stored encrypted in the `users` table. Rotating `KINA_TOKEN_ENCRYPTION_KEY` makes the stored ones unreadable: KINA cannot re-check those users in the background, so they fall back to the 24-hour rule and must sign in again within 24 hours. Rotate the key at a quiet moment and tell users.

Revoking:

- A web UI token: click Revoke on the token page.
- An OAuth connector: Revoke on the token page (or `POST /oauth/revoke` by the client) revokes that access token and every refresh token issued with it, so the client cannot get a new access token. The client must go through the consent flow again. To remove a client registration or revoke everything it holds in one go, use SQL:

```bash
# list clients and their active tokens
docker compose exec postgres psql -U kina -d kina -c \
  "SELECT client_id, client_name, created_at FROM oauth_clients ORDER BY created_at DESC;"

# revoke every refresh token and access token of one client (all of its sessions)
docker compose exec postgres psql -U kina -d kina -c \
  "UPDATE oauth_refresh_tokens SET revoked_at = now() WHERE client_id = '<client_id>' AND revoked_at IS NULL;
   UPDATE access_tokens SET revoked_at = now() WHERE oauth_client_id = '<client_id>' AND revoked_at IS NULL;"

# remove the client registration (also deletes its codes and refresh tokens)
docker compose exec postgres psql -U kina -d kina -c \
  "DELETE FROM oauth_clients WHERE client_id = '<client_id>';"
```

- Everything at once, for example after a suspected leak: `UPDATE access_tokens SET revoked_at = now() WHERE revoked_at IS NULL;` and the same for `oauth_refresh_tokens`.

Changing the validity (`kina.tokens.validity`, `KINA_OAUTH_ACCESS_TOKEN_VALIDITY`, `KINA_OAUTH_REFRESH_TOKEN_VALIDITY`) affects only tokens issued afterwards.

## OAuth client registrations

`POST /oauth/register` is anonymous, as the MCP specification expects. Anyone who can reach KINA can create a client record, but at most `KINA_OAUTH_REGISTER_RATE_LIMIT_PER_MINUTE` (30) per client IP and minute; over that KINA answers 429 with `Retry-After`. A client cannot get a token without a signed-in user (who must be in the required group) approving it on the consent page, which shows the client name and the redirect target. Teach users to check both before approving. Authorization codes live 10 minutes and are single use.

Claude can also use an `https` client id that points to a Client ID Metadata Document. Only hosts in `KINA_OAUTH_TRUSTED_CLIENT_HOSTS` (default `claude.ai, claude.com, *.anthropic.com`) are fetched. The consent page is skipped only for such trusted clients with a non-loopback redirect URI (claude.ai). Claude Code uses a loopback redirect and still shows the page. Set `KINA_OAUTH_AUTO_APPROVE_TRUSTED_CLIENTS=false` if you want the page for everyone.

Registered clients live in `oauth_clients`. A daily job (first run 15 minutes after start) deletes dynamically registered clients that were unused for 90 days (`kina.oauth.unused-client-retention`, based on `last_used_at`, else `created_at`) and hold no live access or refresh token. Their authorization codes and refresh tokens go with them. Clients identified by a metadata document are kept. The job logs how many it deleted. Delete entries you do not recognise by hand (see above).

## Security hardening

- Run `KINA_MODE=prod` for anything reachable beyond your own machine. In `dev` mode there is no login and no group check: API, MCP, token creation and OAuth approval are open to anyone who reaches the port. Keep dev mode on a private network.
- Keep KINA behind HTTPS. Tokens travel in headers; plain HTTP exposes them.
- Bind KINA to loopback (`KINA_PORT=127.0.0.1:8080`) when the proxy runs on the same host, or firewall the port.
- Set `KINA_PUBLIC_BASE_URL` in production. KINA trusts `X-Forwarded-*` headers from any client (`server.forward-headers-strategy=framework`); with the variable set, the advertised origin cannot be influenced by request headers. Also configure the proxy to overwrite, not append, the forwarded headers.
- Set `KINA_TOKEN_ENCRYPTION_KEY` whenever you use `OIDC_REQUIRED_GROUPS`. Without it, removed members are only noticed at their next login, up to 24 hours later.
- If every user comes through Claude, set `KINA_TOKENS_UI_ENABLED=false`, so nobody holds a long-lived static token.
- Keep the `ElectronicsEngineer` binding on the Authentik application, and list `OIDC_ALLOWED_EMAIL_DOMAINS` as well. KINA's check is the second gate.
- Never log or share e-mail addresses of refused users. KINA logs only the provider's subject id for a refusal; keep it that way.
- Overwrite `X-Forwarded-For` at the proxy, or the registration rate limit can be bypassed.
- Client registration (`/oauth/register`) is anonymous, as the MCP specification requires, and rate limited per IP. A malformed `/oauth/authorize` request for a registered client is answered with a redirect to that client's registered URI (RFC 6749 behaviour), without user interaction. Approving access always needs a signed-in user and the consent page.
- Keep the management port (`KINA_METRICS_PORT`, 9090) private. It serves health and `/actuator/prometheus` without authentication. Leave `KINA_METRICS_BIND=127.0.0.1` unless Prometheus runs on another host, then bind it to the monitoring network's interface and firewall it; never `0.0.0.0` on a public host and never through the reverse proxy. The metrics hold counts only (no tokens, addresses, queries or part numbers), but they still reveal usage.
- Do not publish the PostgreSQL port. `compose.yaml` does not. If you add a port, set a real password: the compose file uses the database password `kina`, which you can change in the `kina` and `postgres` services (or in a `compose.override.yaml`) together with `SPRING_DATASOURCE_PASSWORD`.
- The ranking model runs inside the KINA process. There is no ranking service to secure, and nothing leaves the host for ranking. The model is in the image, so ranking makes no outbound call at all. Hugging Face is contacted only while the image is built.
- Never put part data or credentials in logs. KINA logs neither tokens nor API keys; keep it that way when adding debug output.
- Keep `.env` out of git and readable only by the deploy user. Rotate `MOUSER_API_KEY`, `TME_TOKEN`, `TME_APPLICATION_SECRET` and `OIDC_CLIENT_SECRET` if they leak.
- CORS for `/mcp`, `/oauth/*` and `/.well-known/*` allows any origin without credentials. This is intended for browser-based MCP clients and is safe because bearer tokens are not cookies.
- Limit who can sign in at the OIDC provider and with `OIDC_REQUIRED_GROUPS`. Every user who gets in can use the distributors' quotas (Mouser: 1 000 calls a day).
- The image build verifies the model against hashes committed in the repository (`docker/model/`), so a changed upstream file fails the build. If you set `KINA_CROSS_ENCODER_MODEL_URL` or `CROSS_ENCODER_SOURCE`, point it at a source you trust, because the files are loaded and executed as a model. A runtime download (mirror with `KINA_CROSS_ENCODER_AUTO_DOWNLOAD=true`) checks size and SHA-256 only against the source's own headers, which protects against corrupt downloads, not against a malicious source.

## Monitoring

- Health and metrics are on the management port, not on 8080: `http://localhost:9090/actuator/health` returns `{"status":"UP"}` and `/actuator/prometheus` the Prometheus metrics, both without authentication. The Docker `HEALTHCHECK` of the `kina` image uses the health endpoint. It does not check the ranking model, the distributors or the JLCPCB database. On port 8080, `/actuator/*` does not exist.
- The port is published on `127.0.0.1` by default. **It has no authentication: expose it only to the monitoring network.** If Prometheus runs on the same host, scrape `127.0.0.1:9090`. If it runs elsewhere, set `KINA_METRICS_BIND` to the address of the interface on the monitoring network (for example a WireGuard or private VLAN address) and allow only the Prometheus host in the firewall. If Prometheus runs in Docker on the same host, attach it to the `kina_default` network and scrape `kina:9090` instead of publishing the port at all (`KINA_METRICS_BIND` stays `127.0.0.1`).
- Prometheus scrape configuration:

  ```yaml
  scrape_configs:
    - job_name: kina
      metrics_path: /actuator/prometheus
      scrape_interval: 30s
      static_configs:
        - targets: ["127.0.0.1:9090"]     # or kina:9090 on the kina_default network
  ```

- The search counters (`kina_search_queries_total`, `kina_distributor_calls_total`, `kina_parts_fetched_total`, `kina_parts_returned_total`, `kina_cache_search_lookups_total`) carry a `type` tag: the component type of the query (`resistor`, `capacitor`, `mosfet`, `connector`... or `unknown`; the list is in DESIGN.md 3.7). Counts from before 0.5 are under `type="unknown"`, so `sum without (type) (...)` continues across the upgrade.
- Metrics backfill: every day at 06:00 (`KINA_METRICS_BACKFILL_CRON`, time zone of the JVM, UTC in the image unless `TZ` is set), and once at the first start of a new version against a database, KINA moves counts from `type="unknown"` to the types the database proves. It types every cached search (by its query) and cached part (by its family) again, then raises each typed counter to the number of matching rows when it is below that, taking the difference from the `unknown` series of the same counter (shared in proportion when that series holds less than the types lack together). It never changes a total and never lowers a typed counter, and a second run moves nothing. The result is a lower bound and best effort: purged rows, repeated searches, LCSC searches and queries the parser does not know stay under `unknown`, and `kina_parts_returned_total` is not touched. Each run logs one line, for example `Metrics backfill: retyped 12 cached_searches and 840 cached_parts rows; moved from type=unknown: kina.search.queries 40, kina.distributor.calls 70, kina.cache.search.lookups 70, kina.parts.fetched 1500; 412 ms` (rows whose type changed, then the amount moved per counter, then the duration). A failure logs one WARN and is retried the next day. `kina_metrics_backfill_runs_total{outcome}`, `kina_metrics_backfill_moved_total{name}` and `kina_metrics_backfill_last_run_seconds` (Unix time of the last successful run) show it in Prometheus. The `unknown` series drops once per run that moves something, which Prometheus reads as a counter reset of that series. `KINA_METRICS_BACKFILL_ENABLED=false` turns it off.
- `kina_cache_parts` and `kina_cache_searches` carry `distributor` and `type` (the type of each row). Use `sum by (distributor) (kina_cache_parts)` where a panel showed one value per distributor.
- What is exported (all names start with `kina_`, full list in [DESIGN.md 3.7](DESIGN.md#37-observability)): searches and queries (`kina_searches_total`, `kina_search_queries_total{type}`, `kina_search_duration_seconds`), distributor outcomes per distributor (`kina_distributor_calls_total{outcome,type}`, `kina_distributor_rate_limited_responses_total`, `kina_distributor_rate_limit_waits_total`, `kina_parts_fetched_total{type}`, `kina_parts_returned_total{type}`), the cache (`kina_cache_parts{type}`, `kina_cache_parts_fresh`, `kina_cache_parts_stale` (rows kept for their metadata only), `kina_cache_parts_stale_stock` (in stock, stock and prices older than the TTL), `kina_cache_searches{type}`, `kina_cache_parts_added_total`, `kina_cache_parts_refreshed_total`, `kina_cache_stock_refreshes_total{outcome}` (`ok`, `failed`, `out_of_stock`), `kina_cache_search_lookups_total{status,type}`), ranking (`kina_cross_encoder_executions_total`, `kina_cross_encoder_duration_seconds`, `kina_ranking_fallback_total{reason}`), MCP and API (`kina_tool_calls_total{tool}`, `kina_tool_errors_total{tool}`, `kina_api_requests_total{endpoint}`), users and logins (`kina_users_known`, `kina_users_revoked`, `kina_tokens_active`, `kina_logins_total`, `kina_login_denied_total{reason}`, `kina_oauth_tokens_issued_total{grant}`, `kina_membership_rechecks_total{outcome}`) the JLCPCB database (`kina_jlcpcb_database_parts`, `kina_jlcpcb_database_age_seconds`, `kina_jlcpcb_downloads_total`) and the metrics backfill (`kina_metrics_backfill_runs_total{outcome}`, `kina_metrics_backfill_moved_total{name}`, `kina_metrics_backfill_last_run_seconds`). The JVM, Tomcat and HikariCP metrics of Spring Boot are there too.
- The counters are saved to PostgreSQL (`metrics_counters`) every 30 seconds and on shutdown, and restored at startup, so they keep growing across restarts and upgrades; `rate()` and `increase()` see no reset. An unclean stop loses at most the last 30 seconds. The gauges are recomputed every 30 seconds. To start the counters from zero, stop `kina` and run `docker compose exec postgres psql -U kina -d kina -c 'TRUNCATE metrics_counters'`.
- Grafana: add the Prometheus data source and build panels from queries such as `sum by (distributor) (rate(kina_distributor_calls_total{outcome!="ok"}[1h]))` (failures per distributor), `increase(kina_distributor_rate_limited_responses_total[1d])` (rate limits a day, compare with the Mouser quota), `kina_cache_parts_stale / sum by (distributor) (kina_cache_parts)` (share of the cache kept for metadata only), `sum by (distributor) (rate(kina_cache_stock_refreshes_total{outcome="failed"}[1h]))` (stock refreshes that fail: parts may come back `stale`), `rate(kina_search_duration_seconds_sum[5m]) / rate(kina_search_duration_seconds_count[5m])` (mean search time), `sum by (tool) (increase(kina_tool_calls_total[1d]))`, `sum by (type) (increase(kina_search_queries_total[1d]))` (queries a day per component type: `resistor`, `capacitor`, `connector`... or `unknown` when the parser recognised no type) and `kina_jlcpcb_database_age_seconds / 86400` (alert above 7 days).
- Without Prometheus, `GET /api/v1/metrics/summary` (bearer token) returns the same counters as JSON, and `list_distributors` shows the key ones under `metrics`.
- For those, call `list_distributors` (MCP) or `GET /api/v1/distributors` with a token. Watch `jlcpcb.available`, `jlcpcb.downloading`, `jlcpcb.last_error`, `ranking.ready`, `ranking.last_error`, `ranking.avg_latency_ms` and `cache.fresh_parts`.
- `ranking: "fallback"` in search results means the model was not loaded, slow, busy, failed or disabled; `ranking_note` gives the reason (see the troubleshooting table below). `ranking.mode` in `list_distributors` is `blended` when the model is loaded.
- Logs: `docker compose logs -f kina`. Each search logs how long fetching and ranking took. Cache purges (every 6 hours: search lists older than twice the cache TTL, and parts whose metadata retention expired, none with the default `forever`; a cached search with no parts is already stale after 1 hour) and JLCPCB downloads are logged too.
- `docker compose ps` shows the health of `kina` and `postgres`. The model download does not delay the health check. Set `LOGGING_LEVEL_RO_ALACRITY_KINA_SEARCH_CE=DEBUG` to log the scoring time of every search.
- Mouser quota: 1 000 calls a day and 30 a minute. KINA does not enforce it; `kina_distributor_calls_total{distributor="MOUSER"}` and `kina_parts_fetched_total` show how much is used. Avoid `bypass_cache` for bulk work.
- Rate limits: KINA waits and retries when a distributor rate limits a call. One WARN line is logged per episode, for example `Mouser rate limited (/search/keyword returned HTTP 429, Retry-After 30 s); cooling down for 30 s`. Retries inside the episode log at DEBUG. A WARN now and then is normal. Frequent WARNs, or `error: "rate_limited"` in results, mean the quota is too small for the load.
- `rate_limit_waited_ms` in each distributor entry shows how long a request waited. Values near 120000 with `error: "rate_limited"` mean the limit outlasted the deadline.

## Distributor terms

KINA caches what TME and Mouser return in PostgreSQL. The cache model (DESIGN.md 3.2) keeps component data (identity, description, category, package, order rules, links, attributes) and lets stock and prices expire: search lists are reused for 3 days (`KINA_CACHE_TTL`), stock and prices are refreshed after 24 hours, and parts whose stock and prices are older than 3 days and cannot be refreshed are returned with `stale: true`.

KINA is a search engine and aggregator. By default it stores component data permanently, for every distributor, as aggregator data (`kina.cache.metadata-retention`, default `forever`). The research report [cache-fill-2026-10-07.md](research/cache-fill-2026-10-07.md) (sections 1, 2 and 5) has the details of both distributors' terms.

- **Data notices.** The web UI shows the distributors' data notices in the footer of every page. Search and lookup responses (MCP and REST) do not carry them. KINA is an internal application, and the web notice covers relaying the message to users.
- **TME.** The footer shows the notice `Data powered by TME.eu Data – no guarantee of data accuracy` (TME terms 8.7). If TME ends your API access, its terms (8.10) require you to delete all data obtained through the API at once. Remove the TME credentials and delete the TME rows: `docker compose exec postgres psql -U kina -d kina -c "DELETE FROM cached_parts WHERE distributor = 'TME'; DELETE FROM cached_searches WHERE distributor = 'TME'"`.
- **Mouser.** The report quotes an excerpt of Mouser's API terms that restricts caching or storing Mouser content (the full page was behind bot protection). The operator has decided to store Mouser component data until Mouser gives notice. The footer shows `Product data provided by Mouser Electronics`.
- **Honouring a notice.** The retention is set per distributor. To stop keeping a distributor's data beyond a period, set for example `KINA_CACHE_METADATA_RETENTION_MOUSER=3d` and restart. The purge runs 5 minutes after startup and then every 6 hours, and deletes that distributor's rows whose component data was last fetched more than 3 days earlier. A stock refresh does not extend that age; only a full fetch does. Set the value back to `forever` to keep data again.
- **LCSC.** LCSC parts come from the JLCPCB parts database of kicad-jlcpcb-tools and are not stored in PostgreSQL. The footer shows `LCSC parts from the JLCPCB parts database (kicad-jlcpcb-tools)`.

## Rate-limit tuning

| Key | Default | Notes |
|---|---|---|
| `kina.search.max-request-duration` | `2m` | Hard cap for one request, including rate-limit waits. Raise it only if you also raise the proxy and client timeouts (keep them above this value plus about 30 s). Lower it to fail sooner. |
| `kina.search.distributor-timeout` | `12s` | Budget for active work of one distributor fetch. Rate-limit waits do not count against it. |

Set them in `.env` as `KINA_SEARCH_MAX_REQUEST_DURATION=2m`. Keep `spring.ai.mcp.server.request-timeout` (`3m`) above the request duration.

The retry helps with the per-minute limit (Mouser: 30 calls a minute). It does not help when the daily quota (1 000 calls) is used up: the request waits out its budget and then reports `rate_limited`. Use the cache and avoid `bypass_cache` in that case.

A 503 without a `Retry-After` header is treated as an outage and fails at once. Only 429, and 502, 503 or 504 with `Retry-After`, are waited for.

## Troubleshooting login

When KINA refuses a login, the "Access denied" page names the reason, and `docker compose logs kina` has two lines for it. The WARN line has the provider subject, the issuer and the reason. The INFO line lists the claim names (never values) of the ID token and the userinfo response, and the granted scopes. At startup an INFO line `OIDC login policy: ...` shows the groups claim, the required groups, the allowed domains, whether `OIDC_EMAIL_FROM_PREFERRED_USERNAME` is on and whether a verified e-mail is required (`OIDC_REQUIRE_VERIFIED_EMAIL`).

| Page says | Log says | Fix in Authentik |
|---|---|---|
| "Your identity provider did not send an e-mail address for your account" (`reason=email_missing`) | `no e-mail claim in ID token or userinfo (claims present: ...)` | In the KINA provider, add the scope mapping "authentik default OAuth Mapping: OpenID 'email'" to the selected scopes, and enable "Include claims in id_token". Check that the user has an e-mail address in Authentik (Directory, Users); for Google sign-in, check the source's user property mappings. If the INFO line shows `no email scope granted`, the provider did not grant the `email` scope. Only if your provider puts the address in `preferred_username` or `upn`, set `OIDC_EMAIL_FROM_PREFERRED_USERNAME=true`. |
| "Your e-mail address is marked as unverified by the identity provider" (`reason=email_unverified`) | `e-mail address not verified (email_verified=false)` | Authentik's built-in mapping "authentik default OAuth Mapping: OpenID 'email'" always returns `"email_verified": False`, whatever the login source. So with Authentik defaults every login is unverified. There are two fixes. (1) In Authentik, create a scope mapping with scope name `email` and the expression `return {"email": request.user.email, "email_verified": True}`, and select it on the KINA provider instead of the default one. Do this only when the provider admits just accounts your organisation controls, for example Google Workspace sign-in restricted to your domain. (2) Or set `OIDC_REQUIRE_VERIFIED_EMAIL=false` in KINA and restart. KINA then ignores the flag and checks only the domain. |
| "You signed in with an account from example.org, but KINA only accepts alacrity.ro" (`reason=email_domain`) | `e-mail domain example.org not allowed` | The user signed in with another account. Limit the login source to your organisation (see Authentik setup, step 1). If the domain is right but KINA still refuses it, check `OIDC_ALLOWED_EMAIL_DOMAINS` in the startup line. |
| "Your account is not in a group that may use KINA" (`reason=group`) | `not in a required group [...]` or `groups claim 'groups' missing in ID token and userinfo` | Add the user to the group. If the claim is missing, the `profile` scope (or your `groups` mapping, with `OIDC_EXTRA_SCOPES=groups`) is not selected, or `OIDC_GROUPS_CLAIM` names the wrong claim. After a change, sign out of Authentik so it issues a fresh sign-in. |

"Tokens you created earlier no longer work" appears only when the refused account already existed in KINA. That account is now blocked; the next successful login lifts the block.

## Troubleshooting rate limits

| Symptom | Cause and fix |
|---|---|
| A search takes up to 2 minutes | A distributor rate limited it and KINA is waiting. Check `rate_limit_waited_ms` and the `cooling down` WARN in `docker compose logs kina`. Reduce parallel use or wait. |
| `error: "rate_limited"` after about 2 minutes | The limit outlasted the request deadline. For Mouser this is usually the exhausted daily quota. Wait for the quota to reset and rely on the cache. |
| `error: "rate_limited"` at once, `rate_limit_waited_ms` 0 | Another request put the distributor into a cool-down that does not fit this request's deadline. Retry later. |
| Gateway timeout (502 or 504) from the proxy, or the client gives up | The proxy or client read timeout is below the request duration. Raise it above about 2.5 minutes. |

## Troubleshooting ranking

| Symptom | Cause and fix |
|---|---|
| `ranking: "fallback"`, note `cross-encoder model not loaded yet` | The model is still loading (well under a second after startup), or it cannot be loaded. Look at `ranking.last_error` and `ranking.model_dir` in `list_distributors` and for the ERROR line in `docker compose logs kina`. With the bundled model, check that `KINA_CROSS_ENCODER_MODEL_URL` is not set to a wrong path, and that a `KINA_CROSS_ENCODER_VARIANT=fp32` image was not built with `CROSS_ENCODER_VARIANTS=int8`. KINA checks again every hour. |
| Note `cross-encoder disabled` | `KINA_CROSS_ENCODER_ENABLED=false`. Remove it or set it to `true`, then `docker compose up -d`. |
| Note `cross-encoder timeout after 5s` or `cross-encoder timeout: budget exhausted` | The host is short of CPU, or too many threads share too few cores. Lower the load, set `KINA_CROSS_ENCODER_THREADS` to the physical cores you can spare, and check `ranking.avg_latency_ms`. |
| Note `cross-encoder busy: no free slot within ...` | Two scoring calls (`kina.ranking.cross-encoder.max-concurrent`) were already running for the whole budget. Reduce parallel searches or add CPU. |
| Note `cross-encoder failed: ...` | An error while scoring. Read the log line, and check that the model files are complete (`model.json` and the `onnx` directory). With the bundled model, rebuild the image. |
| Mounted model directory failed to load | Wrong layout, a missing `vocab.txt`, or files not readable by uid 10001. Compare with the layout in `docs/DESIGN.md` 3.5. |
