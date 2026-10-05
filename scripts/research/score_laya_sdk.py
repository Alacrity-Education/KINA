"""Laya Python-SDK-only signals: encoder embeddings (embed_fn_from_agent) and predict_shortlist.

    scripts/research/rc.sh -l -c 0-7 -t 8 python score_laya_sdk.py [checkpoint]     # default multilingual
Methods written:
  laya_<ck>_embed      cosine(request text, candidate text) with the checkpoint's mean-pooled encoder
  laya_<ck>_embed_json cosine(request, production JSON state) (what the HTTP ranker sends)
  laya_<ck>_shortlist  predict_shortlist: one choice question over the candidates (short labels), embedding
                       pre-filter to k=10, choice probabilities for the kept ones; the rest ordered by cosine below them
"""
import json
import os
import resource
import sys
import time

import numpy as np
import torch

from common import candidate_text, load_dataset, load_det_features, production_state, write_scores


def main():
    ck = sys.argv[1] if len(sys.argv) > 1 else "multilingual"
    tag = {"multilingual": "ml", "english": "en", "typed-decisions": "td"}[ck]
    threads = int(os.environ.get("THREADS", "8"))
    torch.set_num_threads(threads)
    import laya
    from laya.shortlist import embed_fn_from_agent, predict_shortlist, shortlist_choice
    t0 = time.time()
    agent = laya.load(ck, device="cpu")
    load_s = time.time() - t0
    embed = embed_fn_from_agent(agent, max_length=256, batch_size=64)
    data = load_dataset()
    det = load_det_features()
    out_e, out_j, out_s = {}, {}, {}
    embed(["warm up"])
    for rec in data:
        rows = {r["key"]: r for r in det[rec["id"]]["candidates"]}
        cands = rec["candidates"]
        texts = [candidate_text(c["part"], rows[c["key"]]["comparable"]) for c in cands]
        t1 = time.perf_counter()
        v = embed([rec["query"]] + texts)
        v = v / np.maximum(np.linalg.norm(v, axis=1, keepdims=True), 1e-9)
        cos = (v[1:] @ v[0]).tolist()
        ms_e = (time.perf_counter() - t1) * 1000
        out_e[rec["id"]] = {"scores": {c["key"]: s for c, s in zip(cands, cos)}, "latency_ms": ms_e}

        states = [production_state(rec["query"], c["part"], rows[c["key"]]["comparable"]) for c in cands]
        t1 = time.perf_counter()
        v = embed([rec["query"]] + states)
        v = v / np.maximum(np.linalg.norm(v, axis=1, keepdims=True), 1e-9)
        cosj = (v[1:] @ v[0]).tolist()
        out_j[rec["id"]] = {"scores": {c["key"]: s for c, s in zip(cands, cosj)},
                            "latency_ms": (time.perf_counter() - t1) * 1000}

        # predict_shortlist: options are short candidate labels ("c<i>": "<mpn> <description[:60]>")
        criteria = {"c%d" % i: ("%s %s" % (c["part"].get("manufacturerPartNumber") or "",
                                           (c["part"].get("description") or "")[:60])).strip()
                    for i, c in enumerate(cands)}
        q = {"pick": {"type": "choice", "instructions": "Which candidate part best matches the request?",
                      "criteria": criteria}}
        t1 = time.perf_counter()
        try:
            res = predict_shortlist(agent, rec["query"], q, embed, k=10)
            probs = res["answers"]["pick"]["probabilities"]
            meta_sl = res["shortlist"]["pick"]
            kept = set(meta_sl["labels"])
            err = None
        except Exception as e:  # head budget or other refusal: fall back to cosine only, record it
            probs, kept, err = {}, set(), repr(e)[:200]
        ms_s = (time.perf_counter() - t1) * 1000
        labels, sims = shortlist_choice(rec["query"], criteria, embed, k=len(criteria), return_scores=True)
        sim = dict(zip(labels, sims or [0.0] * len(labels)))
        sc = {}
        for i, c in enumerate(cands):
            lab = "c%d" % i
            sc[c["key"]] = (1.0 + probs[lab]) if lab in kept and lab in probs else (sim.get(lab, 0.0) - 2.0)
        out_s[rec["id"]] = {"scores": sc, "latency_ms": ms_s, "error": err}
        print(rec["id"], round(ms_e), round(ms_s), err or "", flush=True)
    meta = {"checkpoint": ck, "threads": threads, "load_s": round(load_s, 2),
            "rss_peak_mb": round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024),
            "laya": getattr(laya, "__version__", "?")}
    write_scores("laya_%s_embed" % tag, out_e, dict(meta, text="plain candidate text"))
    write_scores("laya_%s_embed_json" % tag, out_j, dict(meta, text="production JSON state"))
    write_scores("laya_%s_shortlist" % tag, out_s, dict(meta, k=10, note="choice over short candidate labels"))


if __name__ == "__main__":
    main()
