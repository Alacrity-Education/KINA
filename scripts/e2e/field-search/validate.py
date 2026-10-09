#!/usr/bin/env python3
"""Checks of the field-search validation stack (Python 3.10+, standard library only).

Run through run.sh (it passes --base and --metrics):

    run.sh validate lookups  --parts parts.tsv            # GET /api/v1/parts/{d}/{pn} for every row, in parallel
    run.sh validate sample   --rows rows.tsv --out q.json # build one query per sampled cached part (its own attributes)
    run.sh validate recall   --queries q.json --out r.json  # search each query at its distributor, is the part found?
    run.sh validate replay   --searches s.tsv --out r.json  # search the query of every cached search list
    run.sh validate compare  a.json b.json                # recall or replay results of two modes side by side
    run.sh validate lcsc     --out l.json                 # the LCSC queries, first and warm latency
    run.sh validate latency  --query Q --distributor D    # latency of one cached query (20 runs)

The TSV inputs come from psql (run.sh psql ...); see docs/research/field-search-validation-2026-10-09.md.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import random
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://localhost:18080"
MAX_RESULTS = 50


def get(path: str, timeout: float = 150) -> tuple[int, dict | None, float]:
    started = time.monotonic()
    try:
        with urllib.request.urlopen(BASE + path, timeout=timeout) as r:
            body = json.loads(r.read() or b"null")
            return r.status, body, time.monotonic() - started
    except urllib.error.HTTPError as e:
        try:
            body = json.loads(e.read() or b"null")
        except ValueError:
            body = None
        return e.code, body, time.monotonic() - started


def mcp_tool(name: str, arguments: dict) -> dict:
    """One MCP tools/call (dev mode: no token); the tool's structured or text result."""
    message = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                          "params": {"name": name, "arguments": arguments}}).encode()
    req = urllib.request.Request(BASE + "/mcp", data=message, method="POST",
                                 headers={"Content-Type": "application/json",
                                          "Accept": "application/json, text/event-stream",
                                          "MCP-Protocol-Version": "2025-06-18"})
    with urllib.request.urlopen(req, timeout=150) as r:
        text = r.read().decode()
        if "text/event-stream" in r.headers.get("Content-Type", ""):
            text = [line[5:].strip() for line in text.splitlines() if line.startswith("data:")][-1]
    result = json.loads(text).get("result") or {}
    if result.get("structuredContent"):
        return result["structuredContent"]
    texts = [c.get("text", "") for c in result.get("content", []) if c.get("type") == "text"]
    return json.loads(texts[0]) if texts else {}


def search(query: str, distributor: str, max_results: int = MAX_RESULTS) -> tuple[int, dict | None, float]:
    q = urllib.parse.urlencode({"q": query, "distributors": distributor, "max_results": max_results})
    return get("/api/v1/parts/search?" + q)


def result_of(body: dict | None, distributor: str) -> dict | None:
    if not body:
        return None
    for r in body.get("distributors", []):
        if r.get("distributor") == distributor:
            return r
    return None


# ---------------------------------------------------------------- lookups

def lookups(args) -> int:
    rows = [line.rstrip("\n").split("|", 1) for line in open(args.parts) if line.strip()]

    def one(row):
        d, pn = row
        status, body, took = get("/api/v1/parts/%s/%s" % (d, urllib.parse.quote(pn, safe="/")))
        ok = status == 200 and body and body.get("part_number") == pn and (body.get("stock") or 0) > 0
        via = "rest"
        if args.query_form:
            # the documented form for awkward part numbers: GET /api/v1/parts/{d}?part_number=<percent-encoded>
            q_status, q_body, q_took = get("/api/v1/parts/%s?%s" % (d, urllib.parse.urlencode(
                {"part_number": pn}, quote_via=urllib.parse.quote)))
            q_ok = q_status == 200 and q_body and q_body.get("part_number") == pn and (q_body.get("stock") or 0) > 0
            if not q_ok:
                ok, status, body = False, q_status, q_body
            took = max(took, q_took)
        if status == 400 and args.mcp_fallback:
            # before C2 the REST path refused some characters ("%", "\\": the Spring Security firewall); get_part
            # takes it as a parameter
            started = time.monotonic()
            res = mcp_tool("get_part", {"distributor": d, "part_number": pn, "detail": "compact"})
            took = time.monotonic() - started
            part = res.get("part") or {}
            ok = bool(res.get("found")) and part.get("part_number") == pn and (part.get("stock") or 0) > 0
            body, status, via = part, 200 if ok else status, "mcp"
        return {"distributor": d, "part_number": pn, "status": status, "via": via, "ok": bool(ok),
                "ms": round(took * 1000),
                "stale": bool(body and body.get("stale")),
                "error": None if ok else (body.get("detail") if isinstance(body, dict) else None)}

    started = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(args.workers) as pool:
        results = list(pool.map(one, rows))
    took = time.monotonic() - started
    failed = [r for r in results if not r["ok"]]
    by = {}
    for r in results:
        s = by.setdefault(r["distributor"], {"total": 0, "ok": 0, "stale": 0})
        s["total"] += 1
        s["ok"] += r["ok"]
        s["stale"] += r["stale"]
    ms = sorted(r["ms"] for r in results)
    summary = {"lookups": len(results), "ok": len(results) - len(failed), "failed": len(failed), "by": by,
               "via_mcp": sum(1 for r in results if r["via"] == "mcp"), "seconds": round(took, 1), "p50_ms": ms[len(ms) // 2] if ms else None,
               "p95_ms": ms[int(len(ms) * 0.95)] if ms else None, "failures": failed[:50]}
    print(json.dumps(summary, indent=1))
    if args.out:
        json.dump({"summary": summary, "results": results}, open(args.out, "w"), indent=1)
    return 0 if not failed else 1


# ---------------------------------------------------------------- sample

def si(value: float, unit: str, prefixes=("p", "n", "u", "m", "", "k", "M", "G")) -> str:
    exps = {"p": -12, "n": -9, "u": -6, "m": -3, "": 0, "k": 3, "M": 6, "G": 9}
    for p in reversed(prefixes):
        v = value / 10 ** exps[p]
        if v >= 1 or p == prefixes[0]:
            text = ("%.3g" % v)
            if "e" in text:
                text = "%g" % v
            return text + p + unit
    return "%g%s" % (value, unit)


def ohms(value: float) -> str:
    if value >= 1e6:
        return si(value, "", ("", "k", "M"))
    if value >= 1e3:
        return si(value, "", ("", "k"))
    if value < 1:
        return si(value, "ohm", ("m", ""))
    return ("%.3g" % value) + " ohm"


FAMILY_WORDS = {"capacitor": "capacitor", "resistor": "resistor", "inductor": "inductor", "ferrite": "ferrite bead",
                "mosfet": "mosfet", "schottky": "schottky diode", "tvs": "TVS diode", "led": "LED", "fan": "fan",
                "switch": "switch", "crystal": "crystal", "oscillator": "oscillator", "opamp": "op amp",
                "regulator": "regulator", "transistor": "transistor", "gate driver": "gate driver", "mcu": "MCU",
                "comparator": "comparator", "diode": "diode", "heater": "heater"}

COLUMNS = ["distributor", "part_number", "family", "capacitance_f", "resistance_ohm", "inductance_h", "impedance_ohm",
           "frequency_hz", "voltage_v", "current_a", "power_w", "tolerance_pct", "dielectric", "package_key",
           "connector_type", "positions", "pitch_mm", "gender", "orientation", "usb_type", "attrs", "description"]


def build_query(row: dict) -> tuple[str, list[str]] | None:
    """A query from the part's own stated attributes: family word, primary value, package, dielectric, tolerance."""
    fam = row["family"]
    words, used = [], []
    f = lambda k: float(row[k]) if row.get(k) else None
    if fam == "connector":
        if not row.get("connector_type"):
            return None
        words.append(row["connector_type"])
        used.append("connector_type")
        if row.get("positions"):
            words.append(row["positions"] + " pin")
            used.append("positions")
        if row.get("pitch_mm"):
            words.append("%gmm" % float(row["pitch_mm"]))
            used.append("pitch")
        if row.get("orientation"):
            words.append(row["orientation"])
            used.append("orientation")
        return " ".join(words), used
    word = FAMILY_WORDS.get(fam)
    if not word:
        return None
    if fam == "led":
        attrs = json.loads(row["attrs"] or "{}")
        if attrs.get("colour") and attrs["colour"] not in ("bi-colour", "rgb"):
            words.append(attrs["colour"])
            used.append("colour")
    words.append(word)
    used.append("family")
    if fam == "capacitor" and f("capacitance_f"):
        words.append(si(f("capacitance_f"), "F", ("p", "n", "u", "m", "")))
        used.append("capacitance")
    elif fam == "resistor" and f("resistance_ohm") is not None and f("resistance_ohm") > 0:
        words.append(ohms(f("resistance_ohm")))
        used.append("resistance")
    elif fam == "inductor" and f("inductance_h"):
        words.append(si(f("inductance_h"), "H", ("n", "u", "m", "")))
        used.append("inductance")
    elif fam == "ferrite" and f("impedance_ohm"):
        words.append(ohms(f("impedance_ohm")) if f("impedance_ohm") >= 1e3 else ("%.3g" % f("impedance_ohm")) + " ohm")
        used.append("impedance")
    elif fam in ("crystal", "oscillator") and f("frequency_hz"):
        words.append(si(f("frequency_hz"), "Hz", ("", "k", "M")))
        used.append("frequency")
    elif fam in ("mosfet", "schottky", "tvs", "fan") and f("voltage_v"):
        words.append("%gV" % f("voltage_v"))
        used.append("voltage")
    if row.get("package_key"):
        words.append(row["package_key"])
        used.append("package")
    if row.get("dielectric"):
        words.append(row["dielectric"].upper())
        used.append("dielectric")
    if f("tolerance_pct") and fam in ("resistor", "capacitor", "inductor"):
        words.append("%g%%" % f("tolerance_pct"))
        used.append("tolerance")
    if len(used) < 2:
        return None   # the family alone (the stated-constraint rule sends it to the cached-search path)
    return " ".join(words), used


def sample(args) -> int:
    rows = []
    for line in open(args.rows):
        if not line.strip():
            continue
        values = line.rstrip("\n").split("|")
        rows.append(dict(zip(COLUMNS, values)))
    rng = random.Random(args.seed)
    by = {}
    for r in rows:
        by.setdefault((r["distributor"], r["family"]), []).append(r)
    queries = []
    for key in sorted(by):
        group = by[key][:]
        rng.shuffle(group)
        taken = 0
        for r in group:
            built = build_query(r)
            if not built:
                continue
            queries.append({"distributor": r["distributor"], "part_number": r["part_number"], "family": r["family"],
                            "query": built[0], "used": built[1], "description": r["description"]})
            taken += 1
            if taken >= args.per_group:
                break
    json.dump(queries, open(args.out, "w"), indent=1)
    print("%d queries over %d (distributor, family) groups" % (len(queries), len({(q["distributor"], q["family"])
                                                                                  for q in queries})))
    return 0


# ---------------------------------------------------------------- recall

def recall(args) -> int:
    queries = json.load(open(args.queries))

    def one(q):
        status, body, took = search(q["query"], q["distributor"])
        r = result_of(body, q["distributor"]) or {}
        numbers = [p["part_number"] for p in r.get("parts", [])]
        return dict(q, status=status, ms=round(took * 1000), cache=r.get("cache"), error=r.get("error"),
                    fetched=r.get("fetched"), returned=r.get("returned"), total=r.get("total_results"),
                    excluded=r.get("excluded_by_constraints"), excluded_detail=r.get("excluded_by_constraints_detail"),
                    below_spec=r.get("excluded_below_spec"), relaxed=r.get("constraints_relaxed"),
                    dropped=r.get("query_terms_dropped"), steps=r.get("field_steps_tried"),
                    live=r.get("fetched_live"), found=q["part_number"] in numbers,
                    rank=numbers.index(q["part_number"]) + 1 if q["part_number"] in numbers else None,
                    parsed=(body or {}).get("parsed"))

    with concurrent.futures.ThreadPoolExecutor(args.workers) as pool:
        results = list(pool.map(one, queries))
    answered = [r for r in results if not r["error"]]
    found = [r for r in answered if r["found"]]
    summary = {"queries": len(results), "answered": len(answered), "errors": len(results) - len(answered),
               "found": len(found), "share": round(len(found) / len(answered), 4) if answered else None,
               "cache": {}, "live": sum(1 for r in results if r["live"])}
    for r in results:
        summary["cache"][str(r["cache"])] = summary["cache"].get(str(r["cache"]), 0) + 1
    print(json.dumps(summary, indent=1))
    json.dump({"summary": summary, "results": results}, open(args.out, "w"), indent=1)
    return 0


# ---------------------------------------------------------------- replay

def replay(args) -> int:
    rows = [line.rstrip("\n").split("|") for line in open(args.searches) if line.strip()]

    def one(row):
        d, key, n = row[0], row[1], int(row[2] or 0)
        status, body, took = search(key, d)
        r = result_of(body, d) or {}
        return {"distributor": d, "query": key, "listed": n, "status": status, "ms": round(took * 1000),
                "cache": r.get("cache"), "error": r.get("error"), "fetched": r.get("fetched"),
                "returned": r.get("returned"), "steps": r.get("field_steps_tried"), "live": r.get("fetched_live"),
                "parts": [p["part_number"] for p in r.get("parts", [])]}

    with concurrent.futures.ThreadPoolExecutor(args.workers) as pool:
        results = list(pool.map(one, rows))
    summary = {"queries": len(results), "errors": sum(1 for r in results if r["error"]), "cache": {}}
    for r in results:
        summary["cache"][str(r["cache"])] = summary["cache"].get(str(r["cache"]), 0) + 1
    print(json.dumps(summary, indent=1))
    json.dump({"summary": summary, "results": results}, open(args.out, "w"), indent=1)
    return 0


def compare(args) -> int:
    a = json.load(open(args.a))["results"]
    b = json.load(open(args.b))["results"]
    key = lambda r: (r["distributor"], r["query"], r.get("part_number"))
    bm = {key(r): r for r in b}
    rows = []
    for ra in a:
        rb = bm.get(key(ra))
        if rb is None:
            continue
        if "found" in ra:
            rows.append((ra, rb, ra["found"], rb["found"]))
        else:
            sa, sb = set(ra["parts"]), set(rb["parts"])
            rows.append((ra, rb, sa, sb))
    if rows and "found" in rows[0][0]:
        both = [r for r in rows if not r[0]["error"] and not r[1]["error"]]
        out = {"pairs": len(rows), "both_answered": len(both),
               "a_found": sum(1 for r in rows if r[2] and not r[0]["error"]),
               "b_found": sum(1 for r in rows if r[3] and not r[1]["error"]),
               "a_only": [r[0]["query"] for r in both if r[2] and not r[3]],
               "b_only": [r[0]["query"] for r in both if r[3] and not r[2]],
               "a_errors": sum(1 for r in rows if r[0]["error"]), "b_errors": sum(1 for r in rows if r[1]["error"])}
    else:
        both = [r for r in rows if not r[0]["error"] and not r[1]["error"]]
        missing = []
        for ra, rb, sa, sb in both:
            # a part B returns that A does not: only counts when A returned fewer than the cap
            gone = sb - sa
            if gone and len(sa) < MAX_RESULTS:
                missing.append({"query": ra["query"], "distributor": ra["distributor"], "a": len(sa), "b": len(sb),
                                "missing_in_a": sorted(gone)})
        out = {"pairs": len(rows), "both_answered": len(both),
               "a_errors": sum(1 for r in rows if r[0]["error"]), "b_errors": sum(1 for r in rows if r[1]["error"]),
               "a_parts": sum(len(r[2]) for r in both), "b_parts": sum(len(r[3]) for r in both),
               "a_superset_of_b": sum(1 for r in both if r[3] <= r[2]),
               "b_parts_missing_in_a": missing}
    print(json.dumps(out, indent=1))
    return 0


# ---------------------------------------------------------------- lcsc

LCSC_QUERIES = ["10uF X7R 0805 25V", "4.7k 1% 0603 resistor", "female header 1x6 right angle",
                "USB-C receptacle 16 pin SMD USB 2.0", "Thin film resistor 5.36k 0805 0.1%", "RP2040"]


def lcsc(args) -> int:
    out = []
    for q in LCSC_QUERIES:
        runs = []
        body = None
        for i in range(args.runs):
            status, body, took = get("/api/v1/parts/search?" + urllib.parse.urlencode(
                {"q": q, "distributors": "LCSC", "max_results": args.max_results, "detail": "full"}))
            runs.append(round(took * 1000, 1))
        r = result_of(body, "LCSC") or {}
        parts = r.get("parts", [])
        out.append({"query": q, "first_ms": runs[0], "warm_ms": statistics.median(runs[1:]) if len(runs) > 1 else None,
                    "total": r.get("total_results"), "fetched": r.get("fetched"), "returned": r.get("returned"),
                    "exact": r.get("exact_matches"), "relaxed": r.get("constraints_relaxed"),
                    "dropped": r.get("query_terms_dropped"), "excluded": r.get("excluded_by_constraints"),
                    "below_spec": r.get("excluded_below_spec"), "parsed": (body or {}).get("parsed"),
                    "top": [{"pn": p["part_number"], "mpn": p["mpn"], "match": p["match"], "stock": p["stock"],
                             "desc": (p.get("description") or "")[:90]} for p in parts[:5]],
                    "all": [p["part_number"] for p in parts], "matches": [p["match"] for p in parts],
                    # a confirmed fit: full match, nothing contradicted, nothing unverified
                    "clean": sum(1 for p in parts if p.get("match") == 1.0 and not p.get("mismatches")
                                 and not p.get("unverified"))})
        print("%-40s first %7.1f ms  warm %7.1f ms  total %s fetched %s returned %s exact %s clean %s" % (
            q, out[-1]["first_ms"], out[-1]["warm_ms"] or 0, out[-1]["total"], out[-1]["fetched"],
            out[-1]["returned"], out[-1]["exact"], out[-1]["clean"]))
    json.dump(out, open(args.out, "w"), indent=1)
    return 0


def latency(args) -> int:
    runs = []
    r = {}
    for i in range(args.runs):
        status, body, took = search(args.query, args.distributor, args.max_results)
        runs.append(took * 1000)
        r = result_of(body, args.distributor) or {}
    print(json.dumps({"query": args.query, "distributor": args.distributor, "first_ms": round(runs[0], 1),
                      "median_ms": round(statistics.median(runs[1:]), 1), "p90_ms": round(sorted(runs[1:])[
                          int(len(runs[1:]) * 0.9) - 1], 1), "cache": r.get("cache"), "fetched": r.get("fetched"),
                      "returned": r.get("returned"), "steps": r.get("field_steps_tried")}))
    return 0


def main() -> int:
    global BASE
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--base", default=BASE)
    p.add_argument("--metrics", default="http://localhost:19090")
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("lookups")
    s.add_argument("--parts", required=True)
    s.add_argument("--workers", type=int, default=16)
    s.add_argument("--mcp-fallback", action="store_true", help="look up REST-refused part numbers with get_part")
    s.add_argument("--query-form", action="store_true",
                   help="also look every part up with GET /api/v1/parts/{d}?part_number= (both forms must succeed)")
    s.add_argument("--out")
    s = sub.add_parser("sample")
    s.add_argument("--rows", required=True)
    s.add_argument("--out", required=True)
    s.add_argument("--per-group", type=int, default=12)
    s.add_argument("--seed", type=int, default=20261009)
    s = sub.add_parser("recall")
    s.add_argument("--queries", required=True)
    s.add_argument("--out", required=True)
    s.add_argument("--workers", type=int, default=8)
    s = sub.add_parser("replay")
    s.add_argument("--searches", required=True)
    s.add_argument("--out", required=True)
    s.add_argument("--workers", type=int, default=8)
    s = sub.add_parser("compare")
    s.add_argument("a")
    s.add_argument("b")
    s = sub.add_parser("lcsc")
    s.add_argument("--out", required=True)
    s.add_argument("--runs", type=int, default=6)
    s.add_argument("--max-results", type=int, default=10)
    s = sub.add_parser("latency")
    s.add_argument("--query", required=True)
    s.add_argument("--distributor", required=True)
    s.add_argument("--runs", type=int, default=21)
    s.add_argument("--max-results", type=int, default=10)
    args = p.parse_args()
    BASE = args.base.rstrip("/")
    return {"lookups": lookups, "sample": sample, "recall": recall, "replay": replay, "compare": compare,
            "lcsc": lcsc, "latency": latency}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
