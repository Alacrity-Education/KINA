"""Builds docs/research/data/ranking-eval.jsonl from the raw candidate sources and the rubrics in queries.py.

    python3 scripts/research/build_dataset.py [--refresh-lcsc] [--review FILE]

Steps: (1) native LCSC retrieval for every query text and every mined query via the Java ResearchRunner
(cached in docs/research/data/raw/lcsc.jsonl; --refresh-lcsc re-runs it against $JLC_DB), (2) stack candidates from
docs/research/data/raw/stack/*.json, (3) automatic labels from the rubric functions plus OVERRIDES, (4) stratified
sampling to at most 40 candidates per query (up to 10 per label level, natives first), (5) write the JSONL.
No method scores are read here.
"""
import json
import os
import random
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from queries import OVERRIDES, QUERIES  # noqa: E402
from fetch_stack import slug  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(HERE))
DATA = os.path.join(ROOT, "docs", "research", "data")
RAW = os.path.join(DATA, "raw")
LCSC_CACHE = os.path.join(RAW, "lcsc.jsonl")
JLC_DB = os.environ.get("JLC_DB", "/tmp/claude-2017/-home-alex-lucaci-Projects-KINA/24fe0a96-6356-4e28-9e0c-59507f460208/scratchpad/jlcfull/parts-fts5.db")
NATIVE_LIMIT = 50
MINED_PER_QUERY = 6
MAX_CANDIDATES = 40
PER_LABEL = 10


def java_cp():
    cp = open(os.path.join(ROOT, "target", "cp.txt")).read().strip()
    return ":".join([os.path.join(ROOT, "target", "research-classes"), os.path.join(ROOT, "target", "classes"), cp])


def lcsc_queries():
    qs = []
    for spec in QUERIES:
        qs.append((spec.get("stack") or spec["query"], NATIVE_LIMIT))
        qs.append((spec["query"], NATIVE_LIMIT))
        for m in spec["mined"]:
            qs.append((m, MINED_PER_QUERY * 2))
    seen, out = set(), []
    for q, n in qs:
        if q not in seen:
            seen.add(q)
            out.append((q, n))
    return out


def refresh_lcsc():
    rows = []
    for limit in sorted({n for _, n in lcsc_queries()}):
        batch = [q for q, n in lcsc_queries() if n == limit]
        out = subprocess.run(["java", "--enable-native-access=ALL-UNNAMED", "-cp", java_cp(),
                              "ro.alacrity.kina.search.ResearchRunner", "lcsc", JLC_DB, str(limit)] + batch,
                             capture_output=True, text=True, check=True).stdout
        rows += [json.loads(l) for l in out.splitlines() if l.startswith("{")]
    with open(LCSC_CACHE, "w") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")


def load_lcsc():
    return {r["query"]: r["parts"] for r in map(json.loads, open(LCSC_CACHE))}


def from_api(p):
    """Stack API (snake_case PartResponse) -> Part record JSON (camelCase)."""
    return {
        "distributor": p["distributor"], "distributorPartNumber": p["part_number"], "manufacturer": p.get("manufacturer"),
        "manufacturerPartNumber": p.get("mpn"), "description": p.get("description"), "category": p.get("category"),
        "packageName": p.get("package"), "stock": p.get("stock"), "minimumOrderQuantity": p.get("min_order_qty"),
        "orderMultiple": p.get("order_multiple"),
        "prices": [{"quantity": b["qty"], "unitPrice": b["unit_price"], "currency": b["currency"]} for b in p.get("prices") or []],
        "datasheetUrl": p.get("datasheet_url"), "photoUrl": p.get("photo_url"), "productUrl": p.get("product_url"),
        "attributes": p.get("attributes") or {}, "extra": p.get("extra") or {}, "fetchedAt": "2026-10-05T00:00:00Z",
    }


def trim(part):
    part = dict(part)
    part["prices"] = (part.get("prices") or [])[:3]
    for k in ("datasheetUrl", "photoUrl", "productUrl"):
        part.pop(k, None)
    part["fetchedAt"] = "2026-10-05T00:00:00Z"
    return part


def key(part):
    return "%s:%s" % (part["distributor"], part["distributorPartNumber"])


def pool_for(spec, lcsc):
    pool = {}
    stack_q = spec.get("stack")
    if stack_q:
        rec = json.load(open(os.path.join(RAW, "stack", slug(stack_q) + ".json")))
        lcsc_order = [key(p) for p in lcsc.get(stack_q, [])]
        for d in rec["response"]["distributors"]:
            order = rec["distributor_order"].get(d["distributor"], [])
            for p in d["parts"]:
                part = from_api(p)
                k = key(part)
                if d["distributor"] == "LCSC":
                    rank = lcsc_order.index(k) if k in lcsc_order else 999
                else:
                    rank = order.index(part["distributorPartNumber"]) if part["distributorPartNumber"] in order else 999
                pool.setdefault(k, dict(key=k, source="stack:" + d["distributor"], source_query=stack_q,
                                        source_rank=rank, part=trim(part)))
    else:
        for i, part in enumerate(lcsc.get(spec["query"], [])):
            pool.setdefault(key(part), dict(key=key(part), source="native:LCSC", source_query=spec["query"],
                                            source_rank=i, part=trim(part)))
    for mi, m in enumerate(spec["mined"]):
        n = 0
        for i, part in enumerate(lcsc.get(m, [])):
            if n >= MINED_PER_QUERY:
                break
            if key(part) in pool:
                continue
            pool[key(part)] = dict(key=key(part), source="mined:LCSC", source_query=m, source_rank=1000 + mi * 100 + i,
                                   part=trim(part))
            n += 1
    return list(pool.values())


DIST_ORDER = {"LCSC": 0, "TME": 1, "MOUSER": 2}


def sample(cands, rng):
    natives = sorted([c for c in cands if not c["source"].startswith("mined")],
                     key=lambda c: (c["source_rank"], DIST_ORDER[c["part"]["distributor"]]))
    mined = [c for c in cands if c["source"].startswith("mined")]
    rng.shuffle(mined)
    ordered = natives + mined
    if len(ordered) <= MAX_CANDIDATES:
        return ordered
    chosen, counts = [], {0: 0, 1: 0, 2: 0, 3: 0}
    # interleave natives across distributors so the cap does not drop a whole distributor
    by_d = {}
    for c in natives:
        by_d.setdefault(c["part"]["distributor"], []).append(c)
    inter = []
    while any(by_d.values()):
        for d in ("LCSC", "TME", "MOUSER"):
            if by_d.get(d):
                inter.append(by_d[d].pop(0))
    for c in inter + mined:
        if counts[c["label"]] < PER_LABEL and len(chosen) < MAX_CANDIDATES:
            chosen.append(c)
            counts[c["label"]] += 1
    for c in inter + mined:
        if len(chosen) >= MAX_CANDIDATES:
            break
        if c not in chosen:
            chosen.append(c)
    return chosen


def main():
    if "--refresh-lcsc" in sys.argv or not os.path.exists(LCSC_CACHE):
        refresh_lcsc()
    lcsc = load_lcsc()
    review = sys.argv[sys.argv.index("--review") + 1] if "--review" in sys.argv else None
    rng = random.Random(20261005)
    out, lines = [], []
    for spec in QUERIES:
        cands = pool_for(spec, lcsc)
        for c in cands:
            lab, why = spec["label"](c)
            m = c["part"].get("manufacturerPartNumber") or ""
            ov = next((v for (qid, prefix), v in OVERRIDES.items() if qid == spec["id"] and m.startswith(prefix)), None)
            if ov:
                lab, why = ov[0], "override: " + ov[1]
            c["label"], c["label_reason"] = lab, why
        chosen = sample(cands, rng)
        # distributor-order baseline position: natives by their source rank (interleaved), mined appended
        nat = sorted([c for c in chosen if not c["source"].startswith("mined")],
                     key=lambda c: (c["source_rank"], DIST_ORDER[c["part"]["distributor"]]))
        mined = sorted([c for c in chosen if c["source"].startswith("mined")], key=lambda c: c["source_rank"])
        for i, c in enumerate(nat + mined):
            c["dist_rank"] = i
        rec = dict(id=spec["id"], query=spec["query"], category=spec["category"], rubric=spec["rubric"],
                   candidates=[{k: c[k] for k in ("key", "label", "label_reason", "source", "source_query",
                                                  "source_rank", "dist_rank", "part")} for c in chosen])
        out.append(rec)
        dist = {l: sum(1 for c in chosen if c["label"] == l) for l in range(4)}
        print("%s %-45s pool=%3d chosen=%2d labels=%s" % (spec["id"], spec["query"][:45], len(cands), len(chosen), dist))
        if review:
            lines.append("\n## %s  %s\n   rubric: %s" % (spec["id"], spec["query"], spec["rubric"]))
            for c in sorted(chosen, key=lambda c: -c["label"]):
                p = c["part"]
                attrs = {k: v for k, v in (p.get("attributes") or {}).items()
                         if k in ("Capacitance", "Resistance", "Inductance", "Voltage", "Current", "Tolerance",
                                  "Dielectric", "Package", "Frequency")}
                lines.append("%d|%s|%-22s|%-10s|%s|%s|%s|| %s" % (
                    c["label"], c["key"][:2], (p.get("manufacturerPartNumber") or "")[:22], (p.get("packageName") or "")[:10],
                    (p.get("description") or "")[:95], (p.get("category") or "").split("/")[-1].strip()[:22],
                    ",".join("%s=%s" % (k[:3], v) for k, v in attrs.items())
                    if c["source"].startswith("stack:M") or c["source"].startswith("stack:T") else "", c["label_reason"][:60]))
    with open(os.path.join(DATA, "ranking-eval.jsonl"), "w") as f:
        for rec in out:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    if review:
        open(review, "w").write("\n".join(lines) + "\n")
    n = sum(len(r["candidates"]) for r in out)
    print("queries=%d candidates=%d" % (len(out), n))


if __name__ == "__main__":
    main()
