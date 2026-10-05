"""Hybrids of the deterministic ranker with a model signal X (any score file).

    python3 scripts/research/score_hybrids.py X [X ...]      # default: a fixed list of model signals
For every X:
  hyb_blend_w<w>_<X>      (1-w)*det + w*ranknorm(X)          (additive formula on the raw det score)
  hyb_rrblend_w<w>_<X>    (1-w)*ranknorm(det) + w*ranknorm(X)
  hyb_tie_<X>             det first, X only breaks exact det ties
  hyb_band05_<X>          det bands of width 0.05, X orders inside a band
  hyb_top10_<X>           deterministic top 10 re-ordered by X, the rest by det below
  hyb_thr05_<X>           candidates with det >= 0.5 ordered by X, the rest by det below
ranknorm = RankingService.normalise (dense rank, best 1.0, worst 0.0, 4-decimal rounding, ties share a value).
Env DET_METHOD=<score file> uses another deterministic ranker (files named hyb<suffix>_...).
Latency = det latency + X latency scaled by the share of candidates X has to score.
"""
import math
import os
import sys

from common import SCORES, load_dataset, read_scores, write_scores

DEFAULT_X = ["msmarco_minilm_ce_ecore", "msmarco_minilm12_ce_ecore", "bge_reranker_base_ce_ecore", "minilm_bi_ecore",
             "bge_small_bi_ecore", "msmarco_minilm_ce_ft_real", "msmarco_minilm_ce_ft_synth",
             "msmarco_minilm_ce_ft_synth_real"]
WEIGHTS = [0.1, 0.2, 0.3, 0.5]


def ranknorm(scores):
    distinct = sorted({round(v, 4) for v in scores.values()}, reverse=True)
    pos = {v: i for i, v in enumerate(distinct)}
    if len(distinct) == 1:
        return {k: 1.0 for k in scores}
    return {k: 1.0 - pos[round(v, 4)] / (len(distinct) - 1) for k, v in scores.items()}


def hybrids(det, x, det_ms, x_ms):
    n = len(det)
    out = {}
    xn, dn = ranknorm(x), ranknorm(det)
    for w in WEIGHTS:
        out["blend_w%g" % w] = ({k: (1 - w) * det[k] + w * xn[k] for k in det}, det_ms + x_ms)
        out["rrblend_w%g" % w] = ({k: (1 - w) * dn[k] + w * xn[k] for k in det}, det_ms + x_ms)
    out["tie"] = ({k: round(det[k], 6) * 10 + xn[k] * 1e-3 for k in det}, det_ms + x_ms)
    out["band05"] = ({k: math.floor(det[k] / 0.05 + 1e-9) * 10 + xn[k] for k in det}, det_ms + x_ms)
    order = sorted(det, key=lambda k: -det[k])
    top = set(order[:10])
    xt = ranknorm({k: x[k] for k in top})
    out["top10"] = ({k: (100 + xt[k]) if k in top else -float(r) for r, k in enumerate(order)},
                    det_ms + x_ms * min(1.0, 10 / n))
    above = {k for k in det if det[k] >= 0.5}
    xa = ranknorm({k: x[k] for k in above}) if above else {}
    out["thr05"] = ({k: (100 + xa[k]) if k in above else det[k] for k in det},
                    det_ms + x_ms * (len(above) / n))
    return out


def main():
    xs = sys.argv[1:] or [x for x in DEFAULT_X if os.path.exists(os.path.join(SCORES, x + ".json"))]
    data = load_dataset()
    det_name = os.environ.get("DET_METHOD", "det")
    det = read_scores(det_name)["queries"]
    prefix = "hyb_" if det_name == "det" else "hyb%s_" % det_name[3:]
    for xname in xs:
        xq = read_scores(xname)["queries"]
        res = {}
        for rec in data:
            if rec["id"] not in det or rec["id"] not in xq:   # e.g. queries added after X was scored
                continue
            d = det[rec["id"]]
            h = hybrids(d["scores"], xq[rec["id"]]["scores"], d["latency_ms"], xq[rec["id"]]["latency_ms"])
            for name, (sc, ms) in h.items():
                res.setdefault(name, {})[rec["id"]] = {"scores": sc, "latency_ms": ms}
        for name, q in res.items():
            write_scores("%s%s_%s" % (prefix, name, xname), q, {"det": det_name, "x": xname, "kind": name})
    print("hybrids for", xs)


if __name__ == "__main__":
    main()
