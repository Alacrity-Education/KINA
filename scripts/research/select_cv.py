"""Selection-corrected scores: inside a family of configurations, pick the best one on all other queries (mean NDCG@10) and
score the held-out query with it; repeat for every query (leave-one-query-out). This removes the optimism of reporting
the best of many hybrids measured on the same 32 queries (the study's score files cover the 32 original
queries; queries without scores in every file of a family are skipped).

    python3 scripts/research/select_cv.py            # families defined below
"""
import json
import os
import re
from collections import Counter

from common import OUT, SCORES, load_dataset
from evaluate import evaluate

FAMILIES = {
    "hybrids det + ms-marco MiniLM-L6 CE": r"^hyb_(?!main).*_msmarco_minilm_ce_ecore$",
    "hybrids det + any signal": r"^hyb_(?!main)",
    "hybrids det + fine-tuned MiniLM CE (synth+real)": r"^hyb_(?!main).*_msmarco_minilm_ce_ft_synth_real$",
}


def main():
    data = load_dataset()
    allm = sorted(f[:-5] for f in os.listdir(SCORES) if f.endswith(".json"))
    report = {}
    for fam, pat in FAMILIES.items():
        ms = [m for m in allm if re.search(pat, m)]
        if not ms:
            continue
        _, pq = evaluate(ms, data)
        qids = [r["id"] for r in data if all(r["id"] in pq[m] for m in ms)]
        held, picks = [], Counter()
        for q in qids:
            best = max(ms, key=lambda m: sum(pq[m][x]["ndcg10"] for x in qids if x != q))
            picks[best] += 1
            held.append(pq[best][q]["ndcg10"])
        oracle = max(ms, key=lambda m: sum(pq[m][x]["ndcg10"] for x in qids))
        report[fam] = {"configs": len(ms), "loqo_ndcg10": sum(held) / len(held),
                       "best_in_sample": oracle, "best_in_sample_ndcg10": sum(pq[oracle][x]["ndcg10"] for x in qids) / len(qids),
                       "picked": picks.most_common(3)}
        print("%-50s configs=%3d  LOQO NDCG@10=%.3f  in-sample best=%s (%.3f)  picks=%s" % (
            fam, len(ms), report[fam]["loqo_ndcg10"], oracle, report[fam]["best_in_sample_ndcg10"], picks.most_common(2)))
    with open(os.path.join(OUT, "select_cv.json"), "w") as f:
        json.dump(report, f, indent=1)


if __name__ == "__main__":
    main()
