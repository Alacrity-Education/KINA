"""Failure analysis helper: per query, the top-k of several methods with labels, and the queries where methods differ most.

    python3 scripts/research/failures.py [k] [method ...]
"""
import sys

from common import load_dataset, read_scores
from evaluate import order, query_metrics


def main():
    k = int(sys.argv[1]) if len(sys.argv) > 1 else 5
    methods = sys.argv[2:] or ["det", "msmarco_minilm_ce_ecore", "laya_ml_noul"]
    data = load_dataset()
    sc = {m: read_scores(m)["queries"] for m in methods}
    rows = []
    for rec in data:
        ms = {m: query_metrics(rec, sc[m][rec["id"]]["scores"])["ndcg10"] for m in methods}
        rows.append((rec, ms))
    print("per-query NDCG@10")
    print("%-5s %-46s " % ("id", "query") + " ".join("%8s" % m[:8] for m in methods))
    for rec, ms in rows:
        print("%-5s %-46s " % (rec["id"], rec["query"][:46]) + " ".join("%8.3f" % ms[m] for m in methods))
    for rec, ms in sorted(rows, key=lambda r: min(r[1].values()))[:8]:
        print("\n== %s %s  %s" % (rec["id"], rec["query"], {m: round(v, 3) for m, v in ms.items()}))
        for m in methods:
            top = order(rec["candidates"], sc[m][rec["id"]]["scores"])[:k]
            print("  %s:" % m)
            for c in top:
                p = c["part"]
                print("    %d %-22s %-10s %s" % (c["label"], (p.get("manufacturerPartNumber") or "")[:22],
                                               (p.get("packageName") or "")[:10], (p.get("description") or "")[:70]))


if __name__ == "__main__":
    main()
