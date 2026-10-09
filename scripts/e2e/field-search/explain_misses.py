#!/usr/bin/env python3
"""Explains the misses of a recall run (validate.py recall) from the SQL the server ran.

Needs statement logging in the kina-fs postgres (run.sh psql "ALTER SYSTEM SET log_min_duration_statement = 0" and
"SELECT pg_reload_conf()"). For every miss it repeats the search, takes the last field query of the distributor from
the postgres log, runs it again without its LIMIT and reports whether the part passes the SQL filter, its position in
the statement's order (the max-candidates cut), the age of its stock (stale parts rank below the fresh ones) and what
the server returned. A part outside the served step's SQL result is checked against the Java check with
FieldQuerySupersetTest on the same pool (-Dkina.superset.pool, -Dkina.superset.queries).

    explain_misses.py recall_on.json --out explained.json
"""

from __future__ import annotations

import argparse
import datetime
import json
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request

BASE = "http://localhost:18080"
PG = "kina-fs-postgres-1"
PARAM = re.compile(r"\$(\d+) = ('(?:[^']|'')*'|NULL)")


def psql(sql: str) -> str:
    return subprocess.run(["docker", "exec", "-i", PG, "psql", "-U", "kina", "-d", "kina", "-At", "-v",
                           "ON_ERROR_STOP=1"], input=sql, capture_output=True, text=True, check=True).stdout.strip()


def field_sql(since: str, distributor: str) -> str | None:
    log = subprocess.run(["docker", "logs", "--since", since, PG], capture_output=True, text=True).stderr
    lines = log.splitlines()
    found = None
    for i, line in enumerate(lines):
        if "execute" in line and "FROM part_index WHERE" in line and "SELECT distributor, part_number" in line:
            sql = line.split(": ", 2)[-1] if "execute" in line else None
            sql = sql[sql.index("SELECT"):]
            detail = next((l for l in lines[i + 1:i + 3] if "Parameters:" in l), "")
            params = {int(n): v for n, v in PARAM.findall(detail)}
            if params.get(1) != "'%s'" % distributor:
                continue
            found = (sql, params)
    if not found:
        return None
    sql, params = found
    sql = re.sub(r" LIMIT \$\d+$", "", sql.strip())
    return re.sub(r"\$(\d+)", lambda m: params[int(m.group(1))], sql)


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("recall")
    p.add_argument("--out", required=True)
    p.add_argument("--limit", type=int, default=200, help="kina.search.field-index.max-candidates")
    args = p.parse_args()
    results = json.load(open(args.recall))["results"]
    misses = [r for r in results if not r["error"] and not r["found"]]
    out = []
    for r in misses:
        since = (datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(seconds=1)).isoformat()
        q = urllib.parse.urlencode({"q": r["query"], "distributors": r["distributor"], "max_results": 50})
        urllib.request.urlopen(BASE + "/api/v1/parts/search?" + q, timeout=150).read()
        time.sleep(0.5)
        sql = field_sql(since, r["distributor"])
        if sql is None:
            out.append(dict(r, verdict="no field query (cached-search path)"))
            continue
        pn = r["part_number"].replace("'", "''")
        row = psql("""WITH q AS (%s), o AS (SELECT part_number, confirmed, row_number() OVER
                      (ORDER BY confirmed DESC, distributor, part_number) AS rn FROM q)
                      SELECT (SELECT count(*) FROM q), coalesce((SELECT rn FROM o WHERE part_number = '%s'), 0),
                             coalesce((SELECT confirmed::text FROM o WHERE part_number = '%s'), ''),
                             (SELECT (now() - stock_fetched_at < interval '3 days')::text FROM cached_parts
                               WHERE distributor = '%s' AND part_number = '%s');"""
                   % (sql, pn, pn, r["distributor"], pn)).split("|")
        total, rank, confirmed, fresh = int(row[0]), int(row[1]), row[2], row[3]
        if rank == 0:
            # the superset run on the same pool (FieldQuerySupersetTest -Dkina.superset.pool) tells whether the
            # Java check refuses the part too (or, in a step with free text, the part does not state the keywords)
            verdict = "not in the served step's SQL result"
        elif rank > args.limit:
            verdict = "in the SQL result at position %d of %d: cut by max-candidates (%d)" % (rank, total, args.limit)
        elif fresh != "true":
            verdict = ("in the SQL result at position %d of %d; stock older than the TTL (refresh failed): moved "
                       "below the fresh parts, beyond the %d returned" % (rank, total, r["returned"]))
        else:
            verdict = "in the SQL result at position %d of %d (within max-candidates): ranked below the %d returned" % (
                rank, total, r["returned"])
        out.append(dict(r, sql_total=total, sql_rank=rank, confirmed=confirmed, fresh=fresh, verdict=verdict))
        print("%-7s %-11s %-40s %s" % (r["distributor"], r["family"], r["query"][:40], verdict))
    json.dump(out, open(args.out, "w"), indent=1)
    counts = {}
    for o in out:
        k = o["verdict"].split(" at position")[0].split(";")[0] if "SQL result" in o["verdict"] else o["verdict"]
        k = "stale stock" if "older than the TTL" in o["verdict"] else "cut by max-candidates" \
            if "max-candidates (" in o["verdict"] else "ranked below" if "ranked below" in o["verdict"] else k
        counts[k] = counts.get(k, 0) + 1
    print("misses %d: %s" % (len(out), json.dumps(counts)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
