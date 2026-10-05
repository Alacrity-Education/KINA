#!/usr/bin/env bash
# Production-mode smoke test without real OIDC credentials.
#
# Starts a throwaway second KINA container (image kina:latest, built by `docker compose up --build`) in prod mode
# next to the running compose stack, sharing its Postgres and (read-only) JLCPCB volume, then checks that:
#   - the app starts,
#   - POST /mcp without a token is 401 with a resource_metadata challenge,
#   - GET / redirects to /oauth2/authorization/oidc,
#   - /oauth2/authorization/oidc redirects to the authorization endpoint discovered from the issuer
#     (provider-agnostic discovery via /.well-known/openid-configuration).
# The container is removed afterwards; the compose stack keeps running in dev mode.
#
#   scripts/e2e/prod_smoke.sh                              # Google as the example issuer
#   OIDC_ISSUER_URI=https://idp.example.com/realms/x EXPECTED_AUTH_HOST=idp.example.com scripts/e2e/prod_smoke.sh
set -euo pipefail

cd "$(dirname "$0")/../.."
NAME=${SMOKE_CONTAINER:-kina-prod-smoke}
PORT=${SMOKE_PORT:-18080}
NETWORK=${SMOKE_NETWORK:-kina_default}
ISSUER=${OIDC_ISSUER_URI:-https://accounts.google.com}
AUTH_HOST=${EXPECTED_AUTH_HOST:-accounts.google.com}

cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup

docker run -d --name "$NAME" --network "$NETWORK" -p "127.0.0.1:${PORT}:8080" \
  -e KINA_MODE=prod \
  -e OIDC_ISSUER_URI="$ISSUER" -e OIDC_CLIENT_ID=dummy -e OIDC_CLIENT_SECRET=dummy \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/kina \
  -e SPRING_DATASOURCE_USERNAME=kina -e SPRING_DATASOURCE_PASSWORD=kina \
  -e KINA_RANKING_CROSSENCODER_AUTODOWNLOAD=false -e KINA_JLCPCB_AUTODOWNLOAD=false \
  -v kina_kina-data:/data:ro \
  --memory 1g kina:latest >/dev/null

echo "waiting for $NAME on port $PORT ..."
for _ in $(seq 1 60); do
  if curl -fsS "http://localhost:${PORT}/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 1
done
docker logs "$NAME" 2>&1 | grep -E "Started KinaApplication|PRODUCTION|OIDC|ERROR" | sed 's/^/  log: /' || true

python3 scripts/e2e/kina_e2e.py --base "http://localhost:${PORT}" --auth-host "$AUTH_HOST" prod "$@"
