"""Fine-tune a small cross-encoder (default cross-encoder/ms-marco-MiniLM-L6-v2) on CPU with soft labels label/3.

    scripts/research/rc.sh -c 8-23 -t 16 python ce_finetune.py <mode> [model-key]
      real        2-fold by query on the evaluation labels (common.folds)                     -> <key>_ft_real
      synth       synthetic rubric labels only (out/synth-train.jsonl), scored on all queries  -> <key>_ft_synth
      synth_real  synthetic + the training fold's real labels, 2-fold                         -> <key>_ft_synth_real
Loss: BCE-with-logits against label/3, AdamW lr 2e-5, 3 epochs, batch 16, linear warm-up 10%.
"""
import json
import os
import random
import sys
import time

import torch

from common import OUT, candidate_text, folds, load_dataset, load_det_features, write_scores
from score_neural import MODELS


def pairs_from(recs, det=None):
    out = []
    for rec in recs:
        rows = {r["key"]: r for r in det[rec["id"]]["candidates"]} if det and rec["id"] in det else {}
        for c in rec["candidates"]:
            comp = rows.get(c["key"], {}).get("comparable")
            out.append((rec["query"], candidate_text(c["part"], comp), c["label"] / 3.0))
    return out


def train(hf_id, pairs, epochs=3, bs=16, lr=2e-5):
    from sentence_transformers import CrossEncoder
    ce = CrossEncoder(hf_id, device="cpu", num_labels=1)
    model, tok = ce.model, ce.tokenizer
    model.train()
    opt = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=0.01)
    steps = epochs * ((len(pairs) + bs - 1) // bs)
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: min(1.0, (s + 1) / max(1, 0.1 * steps)) *
                                              max(0.0, (steps - s) / steps))
    lossf = torch.nn.BCEWithLogitsLoss()
    t0 = time.time()
    for ep in range(epochs):
        random.Random(ep).shuffle(pairs)
        tot = 0.0
        for i in range(0, len(pairs), bs):
            b = pairs[i:i + bs]
            enc = tok([q for q, _, _ in b], [d for _, d, _ in b], padding=True, truncation=True, max_length=256,
                      return_tensors="pt")
            logits = model(**enc).logits.view(-1)
            loss = lossf(logits, torch.tensor([y for _, _, y in b], dtype=torch.float32))
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            sched.step()
            opt.zero_grad()
            tot += loss.item() * len(b)
        print("epoch %d loss %.4f elapsed %.0fs" % (ep + 1, tot / len(pairs), time.time() - t0), flush=True)
    model.eval()
    return ce, time.time() - t0


def score(ce, recs, det):
    out = {}
    for rec in recs:
        rows = {r["key"]: r for r in det[rec["id"]]["candidates"]}
        texts = [candidate_text(c["part"], rows[c["key"]]["comparable"]) for c in rec["candidates"]]
        t0 = time.perf_counter()
        with torch.inference_mode():
            s = [float(x) for x in ce.predict([(rec["query"], t) for t in texts], batch_size=64)]
        out[rec["id"]] = {"scores": {c["key"]: v for c, v in zip(rec["candidates"], s)},
                          "latency_ms": (time.perf_counter() - t0) * 1000}
    return out


def main():
    mode = sys.argv[1]
    key = sys.argv[2] if len(sys.argv) > 2 else "msmarco_minilm_ce"
    torch.set_num_threads(int(os.environ.get("THREADS", "16")))
    torch.manual_seed(0)
    hf_id = MODELS[key][0]
    data = load_dataset()
    det = load_det_features()
    synth = []
    if mode.startswith("synth"):
        with open(os.path.join(OUT, "synth-train.jsonl")) as f:
            synth = [json.loads(l) for l in f]
        with open(os.path.join(OUT, "synth_features.jsonl")) as f:
            sdet = {r["id"]: r for r in (json.loads(l) for l in f)}
    res, meta = {}, {"base": hf_id, "mode": mode, "runs": {}}
    if mode == "synth":
        ce, secs = train(hf_id, pairs_from(synth, sdet))
        res = score(ce, data, det)
        meta["runs"]["all"] = {"train_pairs": sum(len(s["candidates"]) for s in synth), "train_seconds": round(secs)}
    else:
        fa, fb = folds(data)
        for name, tr_ids, te_ids in (("A", fa, fb), ("B", fb, fa)):
            tr = [r for r in data if r["id"] in tr_ids]
            te = [r for r in data if r["id"] in te_ids]
            pairs = pairs_from(tr, det) + (pairs_from(synth, sdet) if synth else [])
            ce, secs = train(hf_id, pairs)
            res.update(score(ce, te, det))
            meta["runs"][name] = {"train_pairs": len(pairs), "train_seconds": round(secs)}
    write_scores("%s_ft_%s" % (key, mode), res, meta)
    print(meta)


if __name__ == "__main__":
    main()
