"""Fetch candidate lists from the running KINA stack (dev mode, no auth) and the distributors' own
order from the stack's Postgres cache. Each NEW query costs one Mouser API call; repeats are cached.

Usage (host python3, no extra packages):
    python3 scripts/research/fetch_stack.py [query ...]   # default: every STACK_QUERIES entry not yet on disk
Outputs docs/research/data/raw/stack/<slug>.json with {query, response, distributor_order}.
The KINA scores in the response are NOT used by the dataset builder (labels must not see scores).
"""
import json
import os
import re
import subprocess
import sys
import unicodedata
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "docs", "research", "data", "raw", "stack")
BASE = os.environ.get("KINA_URL", "http://localhost:8080")
PG_CONTAINER = os.environ.get("KINA_PG_CONTAINER", "kina-postgres-1")

# Queries sent to the stack. The first two were already in the stack cache (free).
STACK_QUERIES = [
    "10uF X7R 0805",
    "100nF 50V X7R 0603",
    "4k7 1% 0603 resistor",
    "ferrite bead 600 ohm 0603",
    "Schottky diode 40V 3A SMA",
    "TVS diode 5V unidirectional SMB",
    "SOT-23 N-channel MOSFET 30V",
    "AMS1117-3.3",
    "LDO 3.3V 500mA SOT-23-5",
    "STM32F103 LQFP-48",
    "16MHz crystal 3225 SMD",
    "USB-C receptacle 16 pin SMD",
    "low-noise op amp for audio, SOIC-8",
    "decoupling cap for a 3.3V MCU",
    "MOSFET to switch a 12V LED strip from a 3.3V GPIO",
]


def slug(q):
    return re.sub(r"[^a-z0-9]+", "-", q.lower()).strip("-")[:60]


def normalize_key(q):
    s = unicodedata.normalize("NFKC", q).strip()
    s = re.sub(r"\s+", " ", s).lower()
    return s.replace("µ", "u").replace("μ", "u").replace("ω", "ohm").replace("Ω", "ohm")


def distributor_order(query_key):
    """Ordered part-number lists per distributor as the distributor returned them (cached_searches)."""
    sql = "select distributor, part_numbers::text from cached_searches where query_key = '%s'" % query_key.replace("'", "''")
    out = subprocess.run(["docker", "exec", PG_CONTAINER, "psql", "-U", "kina", "-d", "kina", "-At", "-F", "\t",
                          "-c", sql], capture_output=True, text=True, check=True).stdout
    order = {}
    for line in out.splitlines():
        if "\t" in line:
            d, arr = line.split("\t", 1)
            order[d] = json.loads(arr)
    return order


def main():
    os.makedirs(OUT, exist_ok=True)
    only = sys.argv[1:]
    for q in STACK_QUERIES:
        if only and q not in only:
            continue
        path = os.path.join(OUT, slug(q) + ".json")
        if os.path.exists(path):
            print("skip (on disk):", q)
            continue
        url = "%s/api/v1/parts/search?%s" % (BASE, urllib.parse.urlencode({"q": q, "max_results": 50}))
        with urllib.request.urlopen(url, timeout=180) as r:
            resp = json.load(r)
        rec = {"query": q, "response": resp, "distributor_order": distributor_order(normalize_key(q))}
        with open(path, "w") as f:
            json.dump(rec, f, ensure_ascii=False)
        print(q, [(d["distributor"], d["returned"], d["cache"], d.get("error"), d.get("fallback_query"))
                  for d in resp["distributors"]])


if __name__ == "__main__":
    main()
