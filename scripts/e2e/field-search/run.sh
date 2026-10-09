#!/usr/bin/env bash
# Driver of the field-search validation stack (compose project kina-fs, app on 18080, metrics on 19090).
# See docs/research/field-search-validation-2026-10-09.md for the procedure and the results.
#
#   scripts/e2e/field-search/run.sh build              # build the image kina-fs:latest from this checkout
#   scripts/e2e/field-search/run.sh db                 # start postgres only (fresh volume kina-fs_pgdata)
#   scripts/e2e/field-search/run.sh restore [dump]     # pg_restore the production dump into the empty database
#   scripts/e2e/field-search/run.sh snapshot <file>    # cache counts and md5 of cached_parts / cached_searches
#   scripts/e2e/field-search/run.sh up [mode]          # start kina (mode: off|shadow|augment|on, default on), wait
#   scripts/e2e/field-search/run.sh mode <mode>        # recreate only the kina service in another mode, wait
#   scripts/e2e/field-search/run.sh psql [sql]         # psql in the postgres container
#   scripts/e2e/field-search/run.sh logs               # kina logs
#   scripts/e2e/field-search/run.sh validate [args]    # validate.py against the stack
#   scripts/e2e/field-search/run.sh e2e [args]         # kina_e2e.py against the stack
#   scripts/e2e/field-search/run.sh down               # stop and remove the containers, KEEP the volumes
#
# Never touches the compose project kina. The data volume kina-fs_kina-data must exist (external).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
PROJECT=kina-fs
export KINA_PORT="${KINA_PORT:-18080}"
export KINA_METRICS_PORT="${KINA_METRICS_PORT:-19090}"
export KINA_FS_DUMP_DIR="${KINA_FS_DUMP_DIR:-/var/tmp/kina-fs}"
BASE="http://localhost:${KINA_PORT}"
METRICS="http://localhost:${KINA_METRICS_PORT}"

compose() {
    docker compose -p "$PROJECT" --project-directory "$ROOT" -f "$ROOT/compose.yaml" -f "$HERE/compose.override.yaml" "$@"
}

psql_c() {
    compose exec -T postgres psql -U kina -d kina -v ON_ERROR_STOP=1 -At "$@"
}

wait_healthy() {
    local deadline=$((SECONDS + ${1:-300}))
    until curl -fsS "$METRICS/actuator/health" >/dev/null 2>&1; do
        if ((SECONDS > deadline)); then
            echo "kina did not become healthy" >&2
            compose logs --tail 80 kina >&2
            return 1
        fi
        sleep 2
    done
}

wait_db() {
    local deadline=$((SECONDS + 120))
    until compose exec -T postgres pg_isready -U kina -d kina >/dev/null 2>&1; do
        ((SECONDS > deadline)) && { echo "postgres not ready" >&2; return 1; }
        sleep 1
    done
}

cmd="${1:-}"
shift || true
case "$cmd" in
    build)
        docker volume inspect kina-fs_kina-data >/dev/null
        compose build kina
        ;;
    db)
        compose up -d postgres
        wait_db
        ;;
    restore)
        dump="${1:-kina-live-20261009.dump}"
        if [[ "$(psql_c -c "SELECT to_regclass('public.cached_parts') IS NOT NULL")" == "t" ]]; then
            echo "the database already holds cached_parts; restore only into a fresh kina-fs_pgdata" >&2
            exit 1
        fi
        compose exec -T postgres pg_restore --no-owner --no-privileges -U kina -d kina "/dump/$(basename "$dump")"
        ;;
    snapshot)
        out="${1:?snapshot <file>}"
        psql_c > "$out" <<'SQL'
SELECT 'cached_parts.' || distributor || '.total', count(*) FROM cached_parts GROUP BY distributor;
SELECT 'cached_parts.' || distributor || '.in_stock', count(*) FROM cached_parts WHERE in_stock GROUP BY distributor;
SELECT 'cached_searches.total', count(*) FROM cached_searches;
-- the columns of V13 to V15 (V16 adds metadata_md5, which the startup job fills): the md5 is comparable across V16
SELECT 'cached_parts.md5', md5(coalesce(string_agg(row_to_json(t)::text, E'\n' ORDER BY distributor, part_number), ''))
  FROM (SELECT distributor, part_number, payload, stock_fetched_at, metadata_fetched_at, in_stock, type
        FROM cached_parts) t;
SELECT 'cached_searches.md5', md5(coalesce(string_agg(row_to_json(t)::text, E'\n' ORDER BY distributor, query_key), ''))
  FROM cached_searches t;
SELECT 'flyway.max', max(version::int) FROM flyway_schema_history WHERE success;
SQL
        sed 's/|/ /' "$out"
        ;;
    up)
        KINA_FIELD_INDEX_MODE="${1:-on}" compose up -d kina
        wait_healthy 600
        ;;
    mode)
        KINA_FIELD_INDEX_MODE="${1:?mode <off|shadow|augment|on>}" compose up -d --no-deps --force-recreate kina
        wait_healthy 600
        ;;
    psql)
        if (($#)); then psql_c -c "$*"; else compose exec postgres psql -U kina -d kina; fi
        ;;
    logs)
        compose logs "$@" kina
        ;;
    validate)
        python3 "$HERE/validate.py" --base "$BASE" --metrics "$METRICS" "$@"
        ;;
    e2e)
        python3 "$ROOT/scripts/e2e/kina_e2e.py" --base "$BASE" --metrics "$METRICS" "$@"
        ;;
    down)
        compose down
        ;;
    *)
        sed -n '2,17p' "$0"
        exit 2
        ;;
esac
