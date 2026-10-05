"""Evaluation harness: metrics for every score file in out/scores (or the ones named on the command line).

    python3 scripts/research/evaluate.py [method ...] [--native-only] [--md out/results.md]

Metrics per query, then averaged over queries (macro):
  NDCG@5, NDCG@10   gain 2^label - 1, ideal ordering from the labels
  P@3               share of the top 3 with label 3 (exact match)
  MRR               1 / rank of the first label-3 candidate
  Spearman          rank correlation between scores and labels (average ranks for ties)
Ties in a method's scores are broken by a fixed pseudo-random order (sha1 of the candidate key), identical for every
method, so no method inherits distributor order or label order through tie-breaking.
Also reported: latency (mean/median/max per query, as recorded by the scorer) and a paired bootstrap 95% CI of the
NDCG@10 difference against the deterministic ranker (10 000 resamples of queries, seed 7).
"""
import hashlib
import json
import math
import os
import random
import sys

from common import OUT, SCORES, load_dataset

CATS = ["passive", "discrete", "ic", "crystal_connector", "vague"]


def tiebreak(key):
    return hashlib.sha1(key.encode()).hexdigest()


def order(cands, scores):
    return sorted(cands, key=lambda c: (-scores.get(c["key"], -1e18), tiebreak(c["key"])))


def dcg(labels, k):
    return sum((2 ** l - 1) / math.log2(i + 2) for i, l in enumerate(labels[:k]))


def ndcg(ranked, all_labels, k):
    ideal = dcg(sorted(all_labels, reverse=True), k)
    return dcg(ranked, k) / ideal if ideal > 0 else 0.0


def avg_ranks(xs):
    idx = sorted(range(len(xs)), key=lambda i: xs[i])
    r = [0.0] * len(xs)
    i = 0
    while i < len(xs):
        j = i
        while j + 1 < len(xs) and xs[idx[j + 1]] == xs[idx[i]]:
            j += 1
        for t in range(i, j + 1):
            r[idx[t]] = (i + j) / 2 + 1
        i = j + 1
    return r


def spearman(a, b):
    ra, rb = avg_ranks(a), avg_ranks(b)
    n = len(a)
    ma, mb = sum(ra) / n, sum(rb) / n
    cov = sum((x - ma) * (y - mb) for x, y in zip(ra, rb))
    va = math.sqrt(sum((x - ma) ** 2 for x in ra))
    vb = math.sqrt(sum((y - mb) ** 2 for y in rb))
    return cov / (va * vb) if va > 0 and vb > 0 else 0.0


def query_metrics(rec, scores):
    cands = rec["candidates"]
    ranked = [c["label"] for c in order(cands, scores)]
    labels = [c["label"] for c in cands]
    first3 = next((i for i, l in enumerate(ranked) if l == 3), None)
    return {
        "ndcg5": ndcg(ranked, labels, 5),
        "ndcg10": ndcg(ranked, labels, 10),
        "p3": sum(1 for l in ranked[:3] if l == 3) / 3,
        "mrr": 1.0 / (first3 + 1) if first3 is not None else 0.0,
        "spearman": spearman([scores.get(c["key"], -1e18) for c in cands], labels),
    }


def mean(xs):
    return sum(xs) / len(xs) if xs else float("nan")


def median(xs):
    s = sorted(xs)
    n = len(s)
    return (s[n // 2] if n % 2 else (s[n // 2 - 1] + s[n // 2]) / 2) if n else float("nan")


def bootstrap_diff(a, b, n=10000, seed=7):
    rng = random.Random(seed)
    m = len(a)
    diffs = []
    for _ in range(n):
        idx = [rng.randrange(m) for _ in range(m)]
        diffs.append(sum(a[i] - b[i] for i in idx) / m)
    diffs.sort()
    return diffs[int(0.025 * n)], diffs[int(0.975 * n)]


def evaluate(methods, data, native_only=False):
    if native_only:
        data = [dict(r, candidates=[c for c in r["candidates"] if not c["source"].startswith("mined")]) for r in data]
        data = [r for r in data if len(r["candidates"]) >= 5 and any(c["label"] == 3 for c in r["candidates"])]
    results = {}
    per_query = {}
    for m in methods:
        with open(os.path.join(SCORES, m + ".json"), encoding="utf-8") as f:
            sf = json.load(f)
        qs = sf["queries"]
        rows = {}
        for rec in data:
            if rec["id"] not in qs:
                continue
            rows[rec["id"]] = dict(query_metrics(rec, qs[rec["id"]]["scores"]), latency_ms=qs[rec["id"]].get("latency_ms"),
                                   category=rec["category"], n=len(rec["candidates"]))
        per_query[m] = rows
        agg = {k: mean([r[k] for r in rows.values()]) for k in ("ndcg5", "ndcg10", "p3", "mrr", "spearman")}
        lat = [r["latency_ms"] for r in rows.values() if r["latency_ms"] is not None]
        agg.update(queries=len(rows), lat_mean_ms=mean(lat), lat_median_ms=median(lat), lat_max_ms=max(lat) if lat else None)
        for cat in CATS:
            cr = [r for r in rows.values() if r["category"] == cat]
            agg["ndcg10_" + cat] = mean([r["ndcg10"] for r in cr])
        agg["meta"] = sf.get("meta", {})
        results[m] = agg
    if "det" in per_query:
        base = per_query["det"]
        for m in methods:
            common_q = [q for q in per_query[m] if q in base]
            if m == "det" or len(common_q) < len(base):
                continue
            a = [per_query[m][q]["ndcg10"] for q in common_q]
            b = [base[q]["ndcg10"] for q in common_q]
            results[m]["ndcg10_vs_det_ci95"] = bootstrap_diff(a, b)
    return results, per_query


def fmt(x, nd=3):
    if x is None or (isinstance(x, float) and math.isnan(x)):
        return "-"
    return ("%." + str(nd) + "f") % x


def markdown(results, title):
    lines = ["### " + title, "",
             "| method | NDCG@5 | NDCG@10 | P@3 | MRR | Spearman | latency/query ms (mean / max) | dNDCG@10 vs det, 95% CI |",
             "|---|---|---|---|---|---|---|---|"]
    for m, r in sorted(results.items(), key=lambda kv: -kv[1]["ndcg10"]):
        ci = r.get("ndcg10_vs_det_ci95")
        lines.append("| %s | %s | %s | %s | %s | %s | %s / %s | %s |" % (
            m, fmt(r["ndcg5"]), fmt(r["ndcg10"]), fmt(r["p3"]), fmt(r["mrr"]), fmt(r["spearman"]),
            fmt(r["lat_mean_ms"], 0), fmt(r["lat_max_ms"], 0),
            ("[%+.3f, %+.3f]" % ci) if ci else ""))
    lines += ["", "| method | " + " | ".join(CATS) + " |", "|---|" + "---|" * len(CATS)]
    for m, r in sorted(results.items(), key=lambda kv: -kv[1]["ndcg10"]):
        lines.append("| %s | %s |" % (m, " | ".join(fmt(r["ndcg10_" + c]) for c in CATS)))
    return "\n".join(lines) + "\n"


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    native = "--native-only" in sys.argv
    md_path = sys.argv[sys.argv.index("--md") + 1] if "--md" in sys.argv else None
    if md_path in args:
        args.remove(md_path)
    methods = args or sorted(f[:-5] for f in os.listdir(SCORES) if f.endswith(".json"))
    data = load_dataset()
    results, per_query = evaluate(methods, data, native)
    tag = "native" if native else "full"
    with open(os.path.join(OUT, "results_%s.json" % tag), "w") as f:
        json.dump({"results": results, "per_query": per_query}, f, indent=1, default=str)
    md = markdown(results, "Results (%s candidate sets, %d queries)" % (tag, max(r["queries"] for r in results.values())))
    if md_path:
        open(md_path, "w").write(md)
    print(md)


if __name__ == "__main__":
    main()
