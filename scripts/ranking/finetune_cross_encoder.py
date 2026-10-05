"""Fine-tune cross-encoder/ms-marco-MiniLM-L6-v2 on rubric labels and export it in the directory layout KINA loads
(docs/DESIGN.md 3.5). Runs inside the image of scripts/ranking/docker (see finetune_cross_encoder.sh).

    python finetune_cross_encoder.py --out /out [--mode synth|synth_real] [--epochs 3] [--threads N]

Training data and recipe are the ranking study's (scripts/research/ce_finetune.py): pairs (query, candidate_text) with
soft targets label/3, BCE-with-logits, AdamW lr 2e-5, weight decay 0.01, batch 16, 3 epochs, linear warm-up 10% then
linear decay, max_length 256, seed 0.
  synth       the 2872 synthetic rubric-labelled pairs only (scripts/research/out/synth-train.jsonl); the evaluation
              set stays unseen, so its NDCG@10 is an honest estimate (study: 0.893 alone, 0.914 blended)
  synth_real  synthetic + every labelled pair of docs/research/data/ranking-eval.jsonl (the model to ship; the study's
              2-fold estimate is 0.917 blended; evaluating it on ranking-eval.jsonl is optimistic)

Output (--out):
  vocab.txt config.json tokenizer_config.json special_tokens_map.json tokenizer.json
  onnx/model.onnx                    fp32, torch.onnx export, opset 17, dynamic batch and sequence axes
  onnx/model_qint8_avx512_vnni.onnx  onnxruntime.quantization.quantize_dynamic, QInt8 weights
  onnx/model_quint8_avx2.onnx        quantize_dynamic, QUInt8 weights
  model.json                         manifest (source, base, revision = finetuned-<mode>-<date>, data, timings, checks)
"""
import argparse
import hashlib
import json
import os
import random
import shutil
import sys
import time
from datetime import datetime, timezone

import numpy as np
import torch

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "research"))
from common import OUT, candidate_text, load_dataset, load_det_features  # noqa: E402

BASE = "cross-encoder/ms-marco-MiniLM-L6-v2"
MAX_LEN = 256


def pairs_from(recs, det):
    out = []
    for rec in recs:
        rows = {r["key"]: r for r in det[rec["id"]]["candidates"]} if rec["id"] in det else {}
        for c in rec["candidates"]:
            comp = rows.get(c["key"], {}).get("comparable")
            out.append((rec["query"], candidate_text(c["part"], comp), c["label"] / 3.0))
    return out


def train(pairs, epochs, bs=16, lr=2e-5):
    from sentence_transformers import CrossEncoder
    ce = CrossEncoder(BASE, device="cpu", num_labels=1)
    model, tok = ce.model, ce.tokenizer
    model.train()
    opt = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=0.01)
    steps = epochs * ((len(pairs) + bs - 1) // bs)
    sched = torch.optim.lr_scheduler.LambdaLR(
        opt, lambda s: min(1.0, (s + 1) / max(1, 0.1 * steps)) * max(0.0, (steps - s) / steps))
    lossf = torch.nn.BCEWithLogitsLoss()
    t0 = time.time()
    for ep in range(epochs):
        random.Random(ep).shuffle(pairs)
        tot = 0.0
        for i in range(0, len(pairs), bs):
            b = pairs[i:i + bs]
            enc = tok([q for q, _, _ in b], [d for _, d, _ in b], padding=True, truncation=True, max_length=MAX_LEN,
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
    return model, tok, time.time() - t0


def export(model, tok, out):
    onnx_dir = os.path.join(out, "onnx")
    os.makedirs(onnx_dir, exist_ok=True)
    fp32 = os.path.join(onnx_dir, "model.onnx")
    enc = tok(["10uF X7R 0805"], ["YAGEO | CC0805KKX7R7BB106 | 10uF 16V X7R"], return_tensors="pt")
    args = (enc["input_ids"], enc["attention_mask"], enc["token_type_ids"])
    axes = {"input_ids": {0: "batch", 1: "sequence"}, "attention_mask": {0: "batch", 1: "sequence"},
            "token_type_ids": {0: "batch", 1: "sequence"}, "logits": {0: "batch"}}
    kwargs = dict(input_names=["input_ids", "attention_mask", "token_type_ids"], output_names=["logits"],
                  dynamic_axes=axes, opset_version=17, do_constant_folding=True)
    try:
        torch.onnx.export(model, args, fp32, dynamo=False, **kwargs)
    except TypeError:  # torch without the dynamo switch
        torch.onnx.export(model, args, fp32, **kwargs)
    from onnxruntime.quantization import QuantType, quantize_dynamic
    quantize_dynamic(fp32, os.path.join(onnx_dir, "model_qint8_avx512_vnni.onnx"), weight_type=QuantType.QInt8)
    quantize_dynamic(fp32, os.path.join(onnx_dir, "model_quint8_avx2.onnx"), weight_type=QuantType.QUInt8)
    tok.save_pretrained(out)
    model.config.save_pretrained(out)


def check(model, tok, out, samples):
    """Max |onnx - torch| logit difference per file, and Spearman of int8 vs fp32 on the samples."""
    import onnxruntime as ort
    enc = tok([q for q, _, _ in samples], [d for _, d, _ in samples], padding=True, truncation=True,
              max_length=MAX_LEN, return_tensors="pt")
    with torch.inference_mode():
        ref = model(**enc).logits.view(-1).numpy()
    feeds = {k: enc[k].numpy().astype(np.int64) for k in ("input_ids", "attention_mask", "token_type_ids")}
    result = {}
    for f in ("model.onnx", "model_qint8_avx512_vnni.onnx", "model_quint8_avx2.onnx"):
        so = ort.SessionOptions()
        so.intra_op_num_threads = torch.get_num_threads()   # explicit: no thread affinity inside a cpuset
        sess = ort.InferenceSession(os.path.join(out, "onnx", f), so, providers=["CPUExecutionProvider"])
        got = sess.run(None, feeds)[0].reshape(-1)
        ra, rb = np.argsort(np.argsort(got)), np.argsort(np.argsort(ref))
        result[f] = {"max_abs_diff_vs_torch": float(np.max(np.abs(got - ref))),
                     "spearman_vs_torch": float(np.corrcoef(ra, rb)[0, 1])}
    return result


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--mode", choices=["synth", "synth_real"], default="synth")
    ap.add_argument("--epochs", type=int, default=3)
    ap.add_argument("--threads", type=int, default=int(os.environ.get("THREADS", os.cpu_count() or 4)))
    a = ap.parse_args()
    torch.set_num_threads(a.threads)
    torch.manual_seed(0)
    random.seed(0)

    with open(os.path.join(OUT, "synth-train.jsonl"), encoding="utf-8") as f:
        synth = [json.loads(line) for line in f if line.strip()]
    with open(os.path.join(OUT, "synth_features.jsonl"), encoding="utf-8") as f:
        sdet = {r["id"]: r for r in (json.loads(line) for line in f if line.strip())}
    pairs = pairs_from(synth, sdet)
    real = 0
    if a.mode == "synth_real":
        data = load_dataset()
        real_pairs = pairs_from(data, load_det_features())
        real = len(real_pairs)
        pairs += real_pairs
    print("training on %d pairs (%d synthetic, %d labelled), %d threads" % (len(pairs), len(pairs) - real, real,
                                                                            a.threads), flush=True)
    samples = pairs[:64]
    model, tok, secs = train(list(pairs), a.epochs)

    tmp = a.out.rstrip("/") + ".tmp"
    shutil.rmtree(tmp, ignore_errors=True)
    os.makedirs(tmp)
    t0 = time.time()
    export(model, tok, tmp)
    checks = check(model, tok, tmp, samples)
    now = datetime.now(timezone.utc)
    files = {}
    for root, _, names in os.walk(tmp):
        for n in names:
            p = os.path.join(root, n)
            rel = os.path.relpath(p, tmp)
            files[rel] = {"size": os.path.getsize(p), "sha256": sha256(p)}
    manifest = {
        "source": "scripts/ranking/finetune_cross_encoder.sh",
        "repo": BASE + " (fine-tuned)",
        "revision": "finetuned-%s-%s" % (a.mode, now.strftime("%Y%m%dT%H%M%SZ")),
        "variant": "int8",
        "downloaded_at": now.isoformat().replace("+00:00", "Z"),
        "base": BASE,
        "mode": a.mode,
        "train_pairs": len(pairs),
        "labelled_pairs": real,
        "epochs": a.epochs,
        "train_seconds": round(secs),
        "export_seconds": round(time.time() - t0),
        "torch": torch.__version__,
        "checks": checks,
        "files": files,
    }
    with open(os.path.join(tmp, "model.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)
    shutil.rmtree(a.out, ignore_errors=True)
    os.replace(tmp, a.out)
    print(json.dumps({k: manifest[k] for k in ("revision", "train_pairs", "train_seconds", "export_seconds",
                                               "checks")}, indent=2))


if __name__ == "__main__":
    main()
