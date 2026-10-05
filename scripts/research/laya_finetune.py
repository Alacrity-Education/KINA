"""Tiny CPU fine-tune of a Laya checkpoint on the labelled ranking data (2-fold, split by query).

    scripts/research/rc.sh -l -c 8-23 -t 16 python laya_finetune.py <mode> [epochs] [checkpoint] [max_steps]
      mode: head        typed head only (encoder frozen, its outputs cached once), 2 folds        -> laya_ft_<ck>_head
            head_synth  typed head only, trained on out/synth-train.jsonl, scored on all queries -> laya_ft_<ck>_head_synth
            full        encoder + head (the repository recipe), fold A only; max_steps caps the run
                        (used to time a full fine-tune on CPU)                                   -> laya_ft_<ck>_full
            full2       encoder + head, both folds                                              -> laya_ft_<ck>_full2
Training recipe = the repository's notebooks/laya_finetune_typed_decisions_mps.py (RLCD: GRPO-style policy-gradient
term with annealed exploration noise + soft cross-entropy, AdamW encoder 2.5e-5 / head 1e-4, cosine schedule, grad
clip 1.0, gradient checkpointing), applied to one `noul` question (the production FITS question) per candidate with
the soft target P(true) = label / 3. No calibration step (temperature does not change a ranking).
Folds are stratified by category (alternating query ids within each category). Writes laya_ft_noul (held-out scores
for every query once both folds ran) and laya_base_noul_sdk (the untouched checkpoint through the same code path).
Checkpoints go to /ft (docker volume kina-research-ft), never into the repository.
"""
import json
import math
import os
import random
import resource
import sys
import time
from pathlib import Path

import torch

from common import OUT, load_dataset, load_det_features, production_state, write_scores
from score_laya_http import FITS, Q_NOUL

SNAP = "/home/laya/.cache/huggingface/hub/models--convaiinnovations--laya/snapshots"


def checkpoint_dir(name):
    snap = os.path.join(SNAP, sorted(os.listdir(SNAP))[0])
    return snap if name == "english" else os.path.join(snap, name)


def folds(data):
    by_cat = {}
    for rec in data:
        by_cat.setdefault(rec["category"], []).append(rec["id"])
    a = set()
    for ids in by_cat.values():
        a.update(ids[0::2])
    return a, {r["id"] for r in data} - a


def build_items(tokenizer, cfg, recs, det):
    from laya.common import build_sequence, QTYPES, render_options
    items = []
    q = {"t": "noul", "ins": FITS, "crit": {}}
    n_opt = len(render_options({"t": "noul", "crit": {}}))
    for rec in recs:
        rows = {r["key"]: r for r in det[rec["id"]]["candidates"]}
        for c in rec["candidates"]:
            state = production_state(rec["query"], c["part"], rows[c["key"]]["comparable"])
            seq, markers = build_sequence(tokenizer, state, q, cfg["max_len"], cfg["head_max_len"])
            if len(markers) != n_opt:
                continue
            p = c["label"] / 3.0
            target = [1 - p, p]          # option order of a noul question: false, true
            items.append({"ids": seq, "markers": markers, "qtype": QTYPES["noul"], "target": target,
                          "label": int(p >= 0.5)})
    return items


def collate(items, pad_id):
    b = len(items)
    seq_len = max(len(i["ids"]) for i in items)
    kmax = max(len(i["markers"]) for i in items)
    ids = torch.full((b, seq_len), pad_id, dtype=torch.long)
    att = torch.zeros((b, seq_len), dtype=torch.long)
    pos = torch.zeros((b, kmax), dtype=torch.long)
    mask = torch.zeros((b, kmax), dtype=torch.bool)
    tgt = torch.zeros((b, kmax), dtype=torch.float32)
    for i, it in enumerate(items):
        n = len(it["ids"])
        ids[i, :n] = torch.tensor(it["ids"])
        att[i, :n] = 1
        k = len(it["markers"])
        pos[i, :k] = torch.tensor(it["markers"])
        mask[i, :k] = True
        tgt[i, :len(it["target"])] = torch.tensor(it["target"])
    return ids, att, pos, mask, tgt, torch.tensor([it["qtype"] for it in items])


def head_forward(model, h, attention_mask, marker_pos, marker_mask, qtype):
    """DecisionModel.forward after the encoder (same maths), for cached encoder outputs."""
    h = h + model.type_emb(qtype)[:, None, :]
    pad = ~attention_mask.bool()
    for layer in model.head.layers:
        h = layer(h, src_key_padding_mask=pad)
    idx = marker_pos.clamp(min=0)[:, :, None].expand(-1, -1, h.size(-1))
    logits = model.scorer(torch.gather(h, 1, idx)).squeeze(-1).float()
    return logits.masked_fill(~marker_mask, -1e4)


def rlcd_loss(logits, mask, tgt, qt, sigma):
    from laya.common import proper_reward
    k = mask.sum(-1, keepdim=True).float()
    eps = torch.randn((4,) + logits.shape) * sigma * mask
    eps = (eps - eps.sum(-1, keepdim=True) / k) * mask
    noisy = logits.detach().unsqueeze(0) + eps
    probs = torch.softmax(noisy.masked_fill(~mask, -1e4), -1)
    with torch.no_grad():
        rew = proper_reward(probs, tgt.unsqueeze(0), qt, mask, w_sph=0.75, w_rps=1.0)
        adv = rew - rew.mean(0, keepdim=True)
        adv = adv / (adv.std() + 1e-6)
    logp = -(((noisy - logits.unsqueeze(0)) ** 2) * mask).sum(-1) / (2 * sigma ** 2)
    return -(adv * logp).mean() - (tgt * torch.log_softmax(logits.masked_fill(~mask, -1e4), -1)).sum(-1).mean()


def load_model(ckdir):
    from safetensors.torch import load_file
    from transformers import AutoTokenizer
    from laya.common import build_model
    with open(Path(ckdir) / "rl_agent_config.json") as f:
        cfg = json.load(f)
    tok = AutoTokenizer.from_pretrained(Path(ckdir) / "tokenizer")
    model = build_model(cfg, encoder_dir=Path(ckdir) / "encoder")
    model.load_state_dict(load_file(str(Path(ckdir) / "model.safetensors")), strict=True)
    model.float()
    return cfg, tok, model


def save_model(model, tok, cfg, out_dir):
    from safetensors.torch import save_file
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    save_file({n: v.detach().contiguous() for n, v in model.state_dict().items()}, str(out / "model.safetensors"))
    model.encoder.config.save_pretrained(out / "encoder")
    tok.save_pretrained(out / "tokenizer")
    cfg = dict(cfg)
    cfg.pop("temperature_by_options", None)
    cfg["fine_tuned"] = True
    with open(out / "rl_agent_config.json", "w") as f:
        json.dump(cfg, f, indent=2)


def train_head(ckdir, items, epochs, out_dir, batch=16):
    """Typed head only: encoder frozen; its last hidden states are computed once per item and cached."""
    cfg, tok, model = load_model(ckdir)
    model.eval()
    t0 = time.time()
    cache = []
    with torch.inference_mode():
        for s in range(0, len(items), 32):
            chunk = items[s:s + 32]
            ids, att, pos, mask, tgt, qt = collate(chunk, tok.pad_token_id)
            h = model.encoder(input_ids=ids, attention_mask=att).last_hidden_state
            for i, it in enumerate(chunk):
                cache.append((h[i, :len(it["ids"])].clone(), it))
    enc_s = time.time() - t0
    params = list(model.head.parameters()) + list(model.type_emb.parameters()) + list(model.scorer.parameters())
    for p in model.parameters():
        p.requires_grad_(False)
    for p in params:
        p.requires_grad_(True)
    model.head.train()
    opt = torch.optim.AdamW(params, lr=1e-4, weight_decay=0.01)
    steps = max(1, math.ceil(len(cache) / batch) * epochs)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=steps, eta_min=1e-6)
    for epoch in range(epochs):
        random.Random(42 + epoch).shuffle(cache)
        sigma = 0.4 + (0.1 - 0.4) * epoch / max(1, epochs - 1)
        tot, nb = 0.0, 0
        for s in range(0, len(cache), batch):
            chunk = cache[s:s + batch]
            L = max(h.size(0) for h, _ in chunk)
            hb = torch.zeros((len(chunk), L, chunk[0][0].size(1)))
            for i, (h, _) in enumerate(chunk):
                hb[i, :h.size(0)] = h
            _, att, pos, mask, tgt, qt = collate([it for _, it in chunk], tok.pad_token_id)
            logits = head_forward(model, hb, att, pos, mask, qt)
            loss = rlcd_loss(logits, mask, tgt, qt, sigma)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(params, 1.0)
            opt.step()
            sched.step()
            opt.zero_grad(set_to_none=True)
            tot += loss.item()
            nb += 1
        print("head epoch %d/%d loss %.4f elapsed %.0fs" % (epoch + 1, epochs, tot / nb, time.time() - t0), flush=True)
    model.eval()
    save_model(model, tok, cfg, out_dir)
    return time.time() - t0, enc_s


def train(ckdir, items, epochs, out_dir, micro_batch=8, grad_accum=2, max_steps=None):
    from safetensors.torch import load_file, save_file
    from transformers import AutoTokenizer
    from laya.common import build_model, proper_reward
    with open(Path(ckdir) / "rl_agent_config.json") as f:
        cfg = json.load(f)
    cfg["gradient_checkpointing"] = True
    tok = AutoTokenizer.from_pretrained(Path(ckdir) / "tokenizer")
    model = build_model(cfg, encoder_dir=Path(ckdir) / "encoder")
    model.load_state_dict(load_file(str(Path(ckdir) / "model.safetensors")), strict=True)
    model.float()
    model.encoder.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False})
    model.head_checkpointing = True
    model.train()
    enc = [p for n, p in model.named_parameters() if "encoder." in n]
    head = [p for n, p in model.named_parameters() if "encoder." not in n]
    opt = torch.optim.AdamW([{"params": enc, "lr": 2.5e-5}, {"params": head, "lr": 1e-4}], weight_decay=0.01)
    updates = max(1, math.ceil(len(items) / micro_batch / grad_accum) * epochs)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=updates, eta_min=1e-6)
    t0 = time.time()
    for epoch in range(epochs):
        random.Random(42 + epoch).shuffle(items)
        sigma = 0.4 + (0.1 - 0.4) * epoch / max(1, epochs - 1)
        opt.zero_grad(set_to_none=True)
        tot, nb = 0.0, 0
        for s in range(0, len(items), micro_batch):
            if max_steps and nb >= max_steps:
                break
            ids, att, pos, mask, tgt, qt = collate(items[s:s + micro_batch], tok.pad_token_id)
            logits, act = model(ids, att, pos, mask, qt)
            logits = logits.float()
            k = mask.sum(-1, keepdim=True).float()
            eps = torch.randn((4,) + logits.shape) * sigma * mask
            eps = (eps - eps.sum(-1, keepdim=True) / k) * mask
            noisy = logits.detach().unsqueeze(0) + eps
            probs = torch.softmax(noisy.masked_fill(~mask, -1e4), -1)
            with torch.no_grad():
                rew = proper_reward(probs, tgt.unsqueeze(0), qt, mask, w_sph=0.75, w_rps=1.0)
                adv = rew - rew.mean(0, keepdim=True)
                adv = adv / (adv.std() + 1e-6)
            logp = -(((noisy - logits.unsqueeze(0)) ** 2) * mask).sum(-1) / (2 * sigma ** 2)
            loss = (-(adv * logp).mean() - (tgt * torch.log_softmax(logits.masked_fill(~mask, -1e4), -1)).sum(-1).mean()
                    + 0.0 * act.sum()) / grad_accum
            loss.backward()
            nb += 1
            if nb % grad_accum == 0 or s + micro_batch >= len(items):
                torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
                opt.step()
                sched.step()
                opt.zero_grad(set_to_none=True)
            tot += loss.item() * grad_accum
            if nb % 5 == 0:
                print("  step %d %.1fs/step" % (nb, (time.time() - t0) / nb), flush=True)
        print("epoch %d/%d loss %.4f elapsed %.0fs" % (epoch + 1, epochs, tot / max(1, nb), time.time() - t0), flush=True)
    model.eval()
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    save_file({n: v.detach().half().contiguous() for n, v in model.state_dict().items()}, str(out / "model.safetensors"))
    model.encoder.config.save_pretrained(out / "encoder")
    tok.save_pretrained(out / "tokenizer")
    cfg.pop("temperature_by_options", None)
    cfg["fine_tuned"] = True
    with open(out / "rl_agent_config.json", "w") as f:
        json.dump(cfg, f, indent=2)
    return time.time() - t0


def score(agent, recs, det):
    res = {}
    for rec in recs:
        rows = {r["key"]: r for r in det[rec["id"]]["candidates"]}
        states = [production_state(rec["query"], c["part"], rows[c["key"]]["comparable"]) for c in rec["candidates"]]
        t0 = time.perf_counter()
        out = agent.predict_batch(states, Q_NOUL, sort_by_length=True)
        ms = (time.perf_counter() - t0) * 1000
        res[rec["id"]] = {"scores": {c["key"]: o["answers"]["fits"]["noul"] for c, o in zip(rec["candidates"], out)},
                          "latency_ms": ms}
    return res


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "head"
    epochs = int(sys.argv[2]) if len(sys.argv) > 2 else 8
    ck = sys.argv[3] if len(sys.argv) > 3 else "multilingual"
    max_steps = int(sys.argv[4]) if len(sys.argv) > 4 else None
    torch.set_num_threads(int(os.environ.get("THREADS", "16")))
    import laya
    from transformers import AutoTokenizer
    data = load_dataset()
    det = load_det_features()
    fa, fb = folds(data)
    ckdir = checkpoint_dir(ck)
    with open(Path(ckdir) / "rl_agent_config.json") as f:
        cfg = json.load(f)
    tok = AutoTokenizer.from_pretrained(Path(ckdir) / "tokenizer")
    tag = {"multilingual": "ml", "english": "en", "typed-decisions": "td"}[ck]
    name = "laya_ft_%s_%s" % (tag, mode)
    meta = {"checkpoint": ck, "mode": mode, "epochs": epochs, "folds": {"A": sorted(fa), "B": sorted(fb)}, "runs": {}}
    if mode == "head_synth":
        synth = [json.loads(l) for l in open(os.path.join(OUT, "synth-train.jsonl"))]
        sdet = {r["id"]: r for r in (json.loads(l) for l in open(os.path.join(OUT, "synth_features.jsonl")))}
        runs = [("synth", synth, sdet, data)]
    elif mode in ("head", "full2"):
        runs = [("A", [r for r in data if r["id"] in fa], det, [r for r in data if r["id"] in fb]),
                ("B", [r for r in data if r["id"] in fb], det, [r for r in data if r["id"] in fa])]
    else:
        runs = [("A", [r for r in data if r["id"] in fa], det, [r for r in data if r["id"] in fb])]
    res = {}
    for fold, tr, tdet, te in runs:
        items = build_items(tok, cfg, tr, tdet)
        print("%s fold %s: %d train items from %d queries, %d eval queries" % (mode, fold, len(items), len(tr), len(te)),
              flush=True)
        out_dir = "/ft/%s_%s" % (name, fold)
        if mode in ("full", "full2"):
            secs, enc_s = train(ckdir, items, epochs, out_dir, max_steps=max_steps), None
        else:
            secs, enc_s = train_head(ckdir, items, epochs, out_dir)
        ft = laya.load(out_dir, device="cpu")
        res.update(score(ft, te, det))
        meta["runs"][fold] = {"train_items": len(items), "train_seconds": round(secs),
                              "encoder_cache_seconds": round(enc_s) if enc_s else None,
                              "rss_peak_mb": round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024)}
        del ft
        write_scores(name, res, meta)
    print(json.dumps(meta["runs"]))


if __name__ == "__main__":
    main()
