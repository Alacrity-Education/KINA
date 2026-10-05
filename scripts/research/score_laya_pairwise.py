"""Laya pairwise comparisons over HTTP, aggregated with a Bradley-Terry fit.

    python3 scripts/research/score_laya_pairwise.py [checkpoint] [rounds]      # default multilingual 8
One state per pair: {"request", "candidate A", "candidate B"} (production candidate objects, A/B order randomised),
one choice question A / B / equal. Rounds: each round is a random perfect matching of the candidates, so after k
rounds every candidate has been compared k times (k*n/2 pairs). Methods written for k = 1, 2, 4, 8:
  laya_<ck>_pair_k<k>       Bradley-Terry strengths (soft wins pA + 0.5*p_equal, 0.5 virtual draw vs a phantom)
Also laya_<ck>_pair_top10 : full round robin (45 pairs) among the deterministic top 10, BT inside the top 10,
the rest keep deterministic order below them.
Latency for k rounds = sum of the measured request times of those rounds.
"""
import hashlib
import json
import math
import random
import sys
import time

from common import load_dataset, load_det_features, write_scores
from score_laya_http import post, warm

Q_PAIR = {"better": {"type": "choice",
                     "instructions": "Which candidate electronic component fits the request better?",
                     "criteria": {"A": "candidate A fits the request better",
                                  "B": "candidate B fits the request better",
                                  "equal": "both fit the request equally well (or equally badly)"}}}


def pair_state(query, a, b):
    return json.dumps({"request": query, "candidate A": a, "candidate B": b}, ensure_ascii=False, separators=(",", ":"))


def cand_obj(state_json):
    return json.loads(state_json)["candidate"]


def bradley_terry(n, comps, iters=200):
    """comps: list of (i, j, w_ij) with w_ij = soft win of i over j in [0,1]. MM algorithm with a phantom player."""
    s = [1.0] * n
    wins = [0.5] * n            # 0.5 virtual draw against the phantom (strength 1) keeps strengths finite
    for i, j, w in comps:
        wins[i] += w
        wins[j] += 1 - w
    for _ in range(iters):
        new = []
        for i in range(n):
            denom = 1.0 / (s[i] + 1.0)
            for a, b, _w in comps:
                if a == i:
                    denom += 1.0 / (s[i] + s[b])
                elif b == i:
                    denom += 1.0 / (s[i] + s[a])
            new.append(wins[i] / denom)
        s = new
    return [math.log(x) for x in s]


def run_pairs(model, query, objs, pairs, rng):
    states, flips = [], []
    for i, j in pairs:
        flip = rng.random() < 0.5
        a, b = (objs[j], objs[i]) if flip else (objs[i], objs[j])
        states.append(pair_state(query, a, b))
        flips.append(flip)
    t0 = time.perf_counter()
    results = []
    for k in range(0, len(states), 64):
        results += post("/v1/systemone/batch", dict(states=states[k:k + 64], questions=Q_PAIR, model=model,
                                                     sort_by_length=True))["results"]
    ms = (time.perf_counter() - t0) * 1000
    comps = []
    for (i, j), flip, r in zip(pairs, flips, results):
        p = r["answers"]["better"]["probabilities"]
        w_first = p["A"] + 0.5 * p["equal"]
        comps.append((i, j, 1 - w_first if flip else w_first))
    return comps, ms


def main():
    model = sys.argv[1] if len(sys.argv) > 1 else "multilingual"
    rounds = int(sys.argv[2]) if len(sys.argv) > 2 else 8
    tag = {"multilingual": "ml", "english": "en", "typed-decisions": "td"}[model]
    from common import production_state
    data = load_dataset()
    det = load_det_features()
    warm(model)
    ks = [k for k in (1, 2, 4, 8) if k <= rounds]
    outs = {k: {} for k in ks}
    top = {}
    for rec in data:
        rng = random.Random(int(hashlib.sha1(rec["id"].encode()).hexdigest()[:8], 16))
        rows = {r["key"]: r for r in det[rec["id"]]["candidates"]}
        cands = rec["candidates"]
        n = len(cands)
        objs = [cand_obj(production_state(rec["query"], c["part"], rows[c["key"]]["comparable"])) for c in cands]
        comps_by_round, ms_by_round = [], []
        for _ in range(rounds):
            perm = list(range(n))
            rng.shuffle(perm)
            pairs = [(perm[t], perm[t + 1]) for t in range(0, n - 1, 2)]
            comps, ms = run_pairs(model, rec["query"], objs, pairs, rng)
            comps_by_round.append(comps)
            ms_by_round.append(ms)
        for k in ks:
            comps = [c for r in comps_by_round[:k] for c in r]
            st = bradley_terry(n, comps)
            outs[k][rec["id"]] = {"scores": {c["key"]: s for c, s in zip(cands, st)},
                                  "latency_ms": sum(ms_by_round[:k]), "pairs": len(comps)}
        # round robin among the deterministic top 10
        order = sorted(range(n), key=lambda i: -rows[cands[i]["key"]]["det"])
        t10 = order[:10]
        pairs = [(a, b) for x, a in enumerate(t10) for b in t10[x + 1:]]
        comps, ms = run_pairs(model, rec["query"], objs, pairs, rng)
        idx = {g: t for t, g in enumerate(t10)}
        st = bradley_terry(len(t10), [(idx[i], idx[j], w) for i, j, w in comps])
        sc = {}
        for rank, i in enumerate(order):
            key = cands[i]["key"]
            sc[key] = 100.0 + st[idx[i]] if i in idx else -float(rank)
        top[rec["id"]] = {"scores": sc, "latency_ms": ms, "pairs": len(pairs)}
        print(rec["id"], n, [round(sum(ms_by_round[:k])) for k in ks], round(ms), flush=True)
    meta = {"model": model, "question": Q_PAIR, "aggregation": "Bradley-Terry MM, soft wins, phantom prior"}
    for k in ks:
        write_scores("laya_%s_pair_k%d" % (tag, k), outs[k], dict(meta, rounds=k))
    write_scores("laya_%s_pair_top10" % tag, top, dict(meta, rounds="round robin top-10 by det"))


if __name__ == "__main__":
    main()
