"""Appends the USB connector queries (u01-u09, usb_queries.py) to docs/research/data/ranking-eval.jsonl.

    python3 scripts/research/build_usb_dataset.py [--fetch] [--refresh-lcsc] [--review FILE]

Run after build_dataset.py (which rewrites the file with the 32 original queries); existing u* records are replaced,
the others are kept byte for byte. Candidate sources per query:

  stack:LCSC/TME/MOUSER  the running KINA stack (main @ 560a32e, 2026-10-05), raw responses in
                         docs/research/data/raw/stack/usb/<slug>.json ({query, response, distributor_order});
                         --fetch requests missing ones (each new query = 1 Mouser call) and reads the distributors'
                         own order from the stack's Postgres cache like fetch_stack.py
  mined:LCSC             direct FTS5 queries on the JLCPCB database restricted to "USB Connectors" (hard negatives and
                         near misses: other pin counts, types, genders), top 6 by stock each; cached in
                         docs/research/data/raw/lcsc-usb.jsonl (--refresh-lcsc re-reads $JLC_DB). This is plain SQL,
                         independent of KINA's JlcpcbQuery, and also returns out-of-stock rows named in extra_lcsc
  extra_stack            single parts taken from another stack response (Mouser's 8-position power-only Type-C)

Labels come from the rubric functions in usb_queries.py (+ USB_OVERRIDES); sampling as in build_dataset.py (up to 10
per label level, natives first, at most 40). No method scores are read.
"""
import json
import os
import random
import re
import sqlite3
import sys
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from build_dataset import DATA, DIST_ORDER, JLC_DB, RAW, from_api, key, sample, trim  # noqa: E402
from fetch_stack import BASE, distributor_order, normalize_key, slug  # noqa: E402
from usb_queries import USB_OVERRIDES, USB_QUERIES  # noqa: E402

STACK_DIR = os.path.join(RAW, "stack", "usb")
LCSC_CACHE = os.path.join(RAW, "lcsc-usb.jsonl")
MINED_PER_QUERY = 6


def stack_record(query, fetch):
    path = os.path.join(STACK_DIR, slug(query) + ".json")
    if not os.path.exists(path):
        if not fetch:
            raise SystemExit("missing %s (run with --fetch)" % path)
        url = "%s/api/v1/parts/search?%s" % (BASE, urllib.parse.urlencode(
            {"q": query, "max_results": 50, "distributors": "LCSC,TME,MOUSER"}))
        with urllib.request.urlopen(url, timeout=300) as r:
            resp = json.load(r)
        os.makedirs(STACK_DIR, exist_ok=True)
        with open(path, "w") as f:
            json.dump({"query": query, "response": resp, "distributor_order": distributor_order(normalize_key(query))},
                      f, ensure_ascii=False)
    return json.load(open(path))


def lcsc_part(row):
    """JLCPCB row -> Part JSON (the fields LcscPartMapper fills; prices: the 3 smallest breaks)."""
    code, first, second, mfr_part, package, manufacturer, library, description, price, stock = row
    prices = []
    for piece in (price or "").split(","):
        m = re.match(r"\s*(\d+)-(\d*):([\d.]+)", piece)
        if m:
            prices.append({"quantity": int(m.group(1)), "unitPrice": float(m.group(3)), "currency": "USD"})
    return {"distributor": "LCSC", "distributorPartNumber": code, "manufacturer": manufacturer,
            "manufacturerPartNumber": mfr_part, "description": description,
            "category": ("%s / %s" % (first, second)) if first or second else None, "packageName": package,
            "stock": int(stock or 0), "minimumOrderQuantity": None, "orderMultiple": None, "prices": prices[:3],
            "attributes": {}, "extra": {"library_type": library, "second_category": second,
                                        "jlcpcb_url": "https://jlcpcb.com/partdetail/" + code},
            "fetchedAt": "2026-10-05T00:00:00Z"}


COLUMNS = '"LCSC Part","First Category","Second Category","MFR.Part","Package","Manufacturer","Library Type",' \
          '"Description","Price","Stock"'


def refresh_lcsc():
    con = sqlite3.connect(JLC_DB)
    rows = []
    for spec in USB_QUERIES:
        for m in spec["mined"]:
            got = con.execute("select %s from parts where parts match ? and CAST(\"Stock\" AS INTEGER) > 0 "
                              "order by CAST(\"Stock\" AS INTEGER) desc limit 20" % COLUMNS, (m,)).fetchall()
            rows.append({"query": m, "parts": [lcsc_part(r) for r in got]})
        for code in spec.get("extra_lcsc", []):
            got = con.execute("select %s from parts where \"LCSC Part\" = ?" % COLUMNS, (code,)).fetchall()
            rows.append({"query": "LCSC Part = " + code, "parts": [lcsc_part(r) for r in got]})
    with open(LCSC_CACHE, "w") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")


def pool_for(spec, lcsc, fetch):
    pool = {}
    rec = stack_record(spec["query"], fetch)
    for d in rec["response"]["distributors"]:
        order = rec["distributor_order"].get(d["distributor"], [])
        for i, p in enumerate(d["parts"]):
            part = from_api(p)
            k = key(part)
            pn = part["distributorPartNumber"]
            rank = i if d["distributor"] == "LCSC" else (order.index(pn) if pn in order else 999)
            pool.setdefault(k, dict(key=k, source="stack:" + d["distributor"], source_query=spec["query"],
                                    source_rank=rank, part=trim(part)))
    for other, dist, pn in spec.get("extra_stack", []):
        for d in stack_record(other, fetch)["response"]["distributors"]:
            for p in d["parts"]:
                if d["distributor"] == dist and p["part_number"] == pn:
                    part = from_api(p)
                    pool.setdefault(key(part), dict(key=key(part), source="mined:" + dist, source_query=other,
                                                    source_rank=900, part=trim(part)))
    for mi, m in enumerate(spec["mined"] + ["LCSC Part = " + c for c in spec.get("extra_lcsc", [])]):
        n = 0
        for i, part in enumerate(lcsc.get(m, [])):
            if n >= MINED_PER_QUERY:
                break
            if key(part) in pool:
                continue
            pool[key(part)] = dict(key=key(part), source="mined:LCSC", source_query=m,
                                   source_rank=1000 + mi * 100 + i, part=trim(part))
            n += 1
    return list(pool.values())


def main():
    if "--refresh-lcsc" in sys.argv or not os.path.exists(LCSC_CACHE):
        refresh_lcsc()
    lcsc = {r["query"]: r["parts"] for r in map(json.loads, open(LCSC_CACHE))}
    fetch = "--fetch" in sys.argv
    review = sys.argv[sys.argv.index("--review") + 1] if "--review" in sys.argv else None
    rng = random.Random(20261005)
    path = os.path.join(DATA, "ranking-eval.jsonl")
    kept = [l for l in open(path) if l.strip() and not json.loads(l)["id"].startswith("u")]
    out, lines = [], []
    for spec in USB_QUERIES:
        cands = pool_for(spec, lcsc, fetch)
        for c in cands:
            lab, why = spec["label"](c)
            m = c["part"].get("manufacturerPartNumber") or ""
            ov = next((v for (qid, prefix), v in USB_OVERRIDES.items() if qid == spec["id"] and m.startswith(prefix)),
                      None)
            if ov:
                lab, why = ov[0], "override: " + ov[1]
            c["label"], c["label_reason"] = lab, why
        chosen = sample(cands, rng)
        # extra parts named by the spec are always kept (they are why the query is in the set)
        for c in cands:
            if (c["source_query"].startswith("LCSC Part = ") or c["source"] in ("mined:MOUSER", "mined:TME")) \
                    and c not in chosen:
                chosen[-1] = c
        nat = sorted([c for c in chosen if not c["source"].startswith("mined")],
                     key=lambda c: (c["source_rank"], DIST_ORDER[c["part"]["distributor"]]))
        mined = sorted([c for c in chosen if c["source"].startswith("mined")], key=lambda c: c["source_rank"])
        for i, c in enumerate(nat + mined):
            c["dist_rank"] = i
        out.append(dict(id=spec["id"], query=spec["query"], category="usb_connector", rubric=spec["rubric"],
                        candidates=[{k: c[k] for k in ("key", "label", "label_reason", "source", "source_query",
                                                       "source_rank", "dist_rank", "part")} for c in chosen]))
        dist = {l: sum(1 for c in chosen if c["label"] == l) for l in range(4)}
        print("%s %-48s pool=%3d chosen=%2d labels=%s" % (spec["id"], spec["query"][:48], len(cands), len(chosen), dist))
        if review:
            lines.append("\n## %s  %s\n   rubric: %s" % (spec["id"], spec["query"], spec["rubric"]))
            for c in sorted(chosen, key=lambda c: -c["label"]):
                p = c["part"]
                lines.append("%d|%-6s|%-26s|%-9s|%s|| %s" % (c["label"], c["key"][:6], (p.get("manufacturerPartNumber") or "")[:26],
                                                       (p.get("packageName") or "")[:9], (p.get("description") or "")[:110],
                                                       c["label_reason"][:70]))
    with open(path, "w") as f:
        for l in kept:
            f.write(l if l.endswith("\n") else l + "\n")
        for rec in out:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    if review:
        open(review, "w").write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
