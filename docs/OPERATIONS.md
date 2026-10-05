# KINA operations guide

This guide covers running KINA for real: HTTPS, secrets, storage, backups, upgrades, sizing and hardening. For a first start see the [README](../README.md). For endpoints see [API.md](API.md).

## Services and volumes

`compose.yaml` runs three services.

| Service | Image | Port | Volume | Purpose |
|---|---|---|---|---|
| `kina` | built from `Dockerfile` (memory limit `KINA_MEM_LIMIT`, default `2g`) | `${KINA_PORT:-8080}` published on the host | `kina-data` at `/data` | The application. Holds the JLCPCB SQLite file in `/data/jlcpcb`. |
| `postgres` | `postgres:17-alpine` | none published | `pgdata` | Cache, users, tokens, OAuth clients, JLCPCB download timestamp. Schema is managed by Flyway at KINA startup. |
| `laya-serve` | built from the Laya git repository (tag `v0.3.27`) | none published (internal `laya-serve:8000`) | `laya-models` | Local ranking model. Not a hard dependency of `kina`. |

`compose.yaml` sets the project name `name: kina`, so the volumes are `kina_kina-data`, `kina_pgdata` and `kina_laya-models` whatever the directory is called. Use `docker volume ls` to see them.

## Environment

Create `.env` from `.env.example`, keep it out of git and restrict it (`chmod 600 .env`). Compose passes it to the `kina` container, and substitutes `LAYA_THREADS`, `KINA_LAYA_MAX_CONCURRENT`, `LAYA_API_KEY`, `LAYA_GPU_ID` and `KINA_PORT` in `compose.yaml`. The full variable list is in the [README](../README.md#configuration-reference).

A minimal production `.env`:

```bash
KINA_MODE=prod
KINA_PUBLIC_BASE_URL=https://kina.example.com
OIDC_ISSUER_URI=https://idp.example.com/realms/main
OIDC_CLIENT_ID=kina
OIDC_CLIENT_SECRET=<secret>
MOUSER_API_KEY=<key>
TME_TOKEN=<token>
TME_APPLICATION_SECRET=<secret>
LAYA_API_KEY=<random string>
LAYA_THREADS=4
KINA_PORT=127.0.0.1:8080
KINA_MEM_LIMIT=2g
```

`KINA_PORT=127.0.0.1:8080` works because `compose.yaml` publishes `"${KINA_PORT:-8080}:8080"`; it binds KINA to the loopback interface so only the reverse proxy on the same host can reach it.

Register this redirect URI at the OIDC provider: `https://kina.example.com/login/oauth2/code/oidc`. KINA refuses to start in `prod` mode without `OIDC_ISSUER_URI` and `OIDC_CLIENT_ID`. Discovery of the provider happens on the first login, not at startup.

## HTTPS reverse proxy

KINA speaks plain HTTP. Claude's remote connector and OAuth need HTTPS, so terminate TLS in a reverse proxy. Two things matter:

1. The proxy must send `X-Forwarded-Proto` and `X-Forwarded-Host` (and `X-Forwarded-Port` for a non-standard port). KINA honours them (`server.forward-headers-strategy=framework`) and uses them in the OAuth metadata, redirects and the `WWW-Authenticate` header. Or set `KINA_PUBLIC_BASE_URL`, which wins over the headers.
2. The proxy should overwrite, not append to, any `X-Forwarded-*` headers a client sent, and must be the only way to reach KINA.

Do not buffer the MCP endpoint, and allow long requests: a batch search with ranking can run for about a minute.

### Caddy

Caddy sets the `X-Forwarded-*` headers itself and obtains certificates automatically.

```caddyfile
kina.example.com {
    reverse_proxy 127.0.0.1:8080 {
        flush_interval -1
        transport http {
            response_header_timeout 120s
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
        proxy_read_timeout 120s;
    }
}
```

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
| Laya checkpoint | Volume `laya-models` | Not needed. It is downloaded again from Hugging Face. |

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
python3 scripts/e2e/kina_e2e.py                 # suites ui, mcp, oauth, forwarded, rest against http://localhost:8080
KINA_URL=http://host:8080 python3 scripts/e2e/kina_e2e.py
scripts/e2e/prod_smoke.sh                       # prod mode smoke test in a throwaway container on port 18080
```

Run them against a staging or dev stack. They create tokens and OAuth clients in the database and, on a cold cache, make 2 Mouser calls. Details are in the "End-to-end checks" section of [DEVELOPMENT.md](DEVELOPMENT.md).

## Upgrading

```bash
git pull
docker compose up -d --build
```

Flyway applies new database migrations when `kina` starts (the current ones are V1 to V3). Take a `pg_dump` first. The `laya-serve` image is pinned to tag `v0.3.27`, so it only rebuilds when `compose.yaml` changes. Changing the JLCPCB library variant makes KINA download that file on the next check; the old file stays in the volume and can be deleted by hand.

Roll back by checking out the previous version and running `docker compose up -d --build` again. Migrations are not reversed, so restore the dump if a migration must be undone.

## GPU overlay

On a host with an NVIDIA GPU and the NVIDIA Container Toolkit:

```bash
docker compose -f compose.yaml -f compose.cuda.yaml up -d --build
```

The overlay rebuilds Laya with `TORCH_INDEX=cu128`, sets `LAYA_DEVICE=cuda` and reserves the GPU named by `LAYA_GPU_ID` (default `0`). KINA needs no change. Use the same two `-f` flags for every later `docker compose` command that should keep the overlay (`ps`, `logs`, `down`). Measured by the Laya project on a T4: about 33 ms for one question and 7 ms for each further question, against about 600 ms per question on a 4-core CPU with the English checkpoints.

## Sizing

| Component | Memory | Notes |
|---|---|---|
| Laya (`multilingual`, CPU) | about 1.9 GiB measured | One loaded checkpoint is about 1.5 GB. On disk: checkpoint 1.5 GB, image 1.76 GB. |
| KINA JVM | about 485 MiB measured | The container limit is `KINA_MEM_LIMIT` (default `2g`) and the heap is 75 percent of it (1.5 GiB). The JVM exits on out-of-memory (`-XX:+ExitOnOutOfMemoryError`) and Compose restarts it. |
| PostgreSQL | about 45 MiB measured | The cache and tokens are small (`pgdata` about 50 MB after the end-to-end run). |
| JLCPCB SQLite file | page cache | The file is read through the operating system page cache; free RAM makes LCSC queries faster. |

These numbers come from the measurements in `docs/DEVELOPMENT.md` ("Measured on 2026-10-05"; 24-core, 30 GB host). A host with 4 GB of free RAM is a safe minimum, 8 GB is comfortable. CPU: `LAYA_THREADS` must not exceed the physical core count. Oversubscribing SMT siblings makes Laya many times slower and then rankings time out and fall back. Keep `KINA_LAYA_MAX_CONCURRENT=1` unless you have measured otherwise; a second concurrent pass slows both. Measured Laya cost on CPU: about 90 ms per candidate with 4 threads (40 candidates 3.7 to 4.3 s) and 50 to 60 ms per candidate with 8 threads. A cold search for three distributors took about 6 s, a repeat with a larger `max_results` about 60 ms. Check `ranking_note` in search results for timeouts.

## Token lifecycle

- Web UI tokens: created by a signed-in user, 30 days, shown once, stored only as a SHA-256 hash. The token list shows prefix, creation, expiry, last use (updated at most once a minute) and status.
- OAuth access tokens: issued by the OAuth flow, same 30 days, named `MCP: <client name>`, visible in the same list. Refresh tokens last 90 days and rotate on every use.
- Expired tokens stop working; there is no automatic renewal for web UI tokens. Create a new one and update the client (`claude mcp remove kina`, then `claude mcp add ...` again).

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

Changing the validity (`KINA_TOKENS_VALIDITY`, `KINA_OAUTH_REFRESH_TOKEN_VALIDITY`) affects only tokens issued afterwards.

## OAuth client registrations

`POST /oauth/register` is anonymous, as the MCP specification expects. Anyone who can reach KINA can create a client record. A client cannot get a token without a signed-in user approving it on the consent page, which shows the client name and the redirect target. Teach users to check both before approving. Registered clients live in `oauth_clients`; delete entries you do not recognise (see above). Authorization codes live 10 minutes and are single use.

## Security hardening

- Run `KINA_MODE=prod` for anything reachable beyond your own machine. In `dev` mode there is no login: API, MCP, token creation and OAuth approval are open to anyone who reaches the port.
- Keep KINA behind HTTPS. Tokens travel in headers; plain HTTP exposes them.
- Bind KINA to loopback (`KINA_PORT=127.0.0.1:8080`) when the proxy runs on the same host, or firewall the port.
- Set `KINA_PUBLIC_BASE_URL` in production. KINA trusts `X-Forwarded-*` headers from any client (`server.forward-headers-strategy=framework`); with the variable set, the advertised origin cannot be influenced by request headers. Also configure the proxy to overwrite, not append, the forwarded headers.
- Client registration (`/oauth/register`) is anonymous, as the MCP specification requires. A malformed `/oauth/authorize` request for a registered client is answered with a redirect to that client's registered URI (RFC 6749 behaviour), without user interaction. Approving access always needs a signed-in user and the consent page.
- Do not publish PostgreSQL or Laya ports. `compose.yaml` does not. If you add a port, set a real password: the compose file uses the database password `kina`, which you can change in the `kina` and `postgres` services (or in a `compose.override.yaml`) together with `SPRING_DATASOURCE_PASSWORD`.
- Set `LAYA_API_KEY` to a random string. KINA sends it as a bearer token to laya-serve and laya-serve requires it. Laya has no host port, so this is defence in depth against other containers on the network.
- Never put part data or credentials in logs. KINA logs neither tokens nor API keys; keep it that way when adding debug output.
- Keep `.env` out of git and readable only by the deploy user. Rotate `MOUSER_API_KEY`, `TME_TOKEN`, `TME_APPLICATION_SECRET` and `OIDC_CLIENT_SECRET` if they leak.
- CORS for `/mcp`, `/oauth/*` and `/.well-known/*` allows any origin without credentials. This is intended for browser-based MCP clients and is safe because bearer tokens are not cookies.
- Limit who can sign in at the OIDC provider. Every user the provider lets in can create API tokens and use the distributors' quotas (Mouser: 1 000 calls a day).
- Part data is only sent to the configured Laya URL, which should stay the local sidecar.

## Monitoring

- `GET /actuator/health` is public and returns `{"status":"UP"}`. The Docker `HEALTHCHECK` of the `kina` image uses it. It does not check Laya, the distributors or the JLCPCB database.
- For those, call `list_distributors` (MCP) or `GET /api/v1/distributors` with a token. Watch `jlcpcb.available`, `jlcpcb.downloading`, `jlcpcb.last_error`, `ranking.laya_healthy`, and `cache.fresh_parts`.
- `ranking: "fallback"` in search results means Laya was unavailable, slow or disabled; `ranking_note` gives the reason.
- Logs: `docker compose logs -f kina`. Each search logs how long fetching and ranking took. Cache purges (every 6 hours, rows older than twice the cache TTL; a cached search with no parts is already stale after 1 hour) and JLCPCB downloads are logged too.
- `docker compose ps` shows the health of `postgres` and `laya-serve`. `laya-serve` has a 10 minute start period for the first checkpoint download.
- Mouser quota: 1 000 calls a day and 30 a minute. KINA does not count calls. Frequent `error: "rate_limited"` means that quota is exhausted; avoid `bypass_cache` for bulk work.
