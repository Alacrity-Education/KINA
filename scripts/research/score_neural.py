"""Non-Laya neural scorers (sentence-transformers) in the research container: bi-encoders and cross-encoders.

    docker run --rm --cpuset-cpus=0-7 -e THREADS=8 ... kina-laya-research:local python score_neural.py <model-key> [suffix]
One model per process so ru_maxrss is that model's peak RAM. Latency per query = scoring every candidate of the query
from scratch (query + candidate encoding, no embedding cache), batch size 64.
"""
import json
import os
import resource
import sys
import time

import torch

from common import DET_FEATURES, candidate_text, load_dataset, write_scores

MODELS = {
    # key: (hf id, kind, licence, query prefix)
    "minilm_bi": ("sentence-transformers/all-MiniLM-L6-v2", "bi", "Apache-2.0", ""),
    "bge_small_bi": ("BAAI/bge-small-en-v1.5", "bi", "MIT", "Represent this sentence for searching relevant passages: "),
    "msmarco_minilm_ce": ("cross-encoder/ms-marco-MiniLM-L6-v2", "ce", "Apache-2.0", ""),
    "msmarco_minilm12_ce": ("cross-encoder/ms-marco-MiniLM-L12-v2", "ce", "Apache-2.0", ""),
    "bge_reranker_base_ce": ("BAAI/bge-reranker-base", "ce", "MIT", ""),
}


def dir_size(path):
    total = 0
    for root, _, files in os.walk(path):
        for f in files:
            fp = os.path.join(root, f)
            if not os.path.islink(fp):
                total += os.path.getsize(fp)
            else:
                total += os.path.getsize(os.path.realpath(fp))
    return total


def main():
    key = sys.argv[1]
    suffix = sys.argv[2] if len(sys.argv) > 2 else ""
    threads = int(os.environ.get("THREADS", "8"))
    torch.set_num_threads(threads)
    hf_id, kind, licence, prefix = MODELS[key]
    from sentence_transformers import CrossEncoder, SentenceTransformer
    t0 = time.time()
    model = SentenceTransformer(hf_id, device="cpu") if kind == "bi" else CrossEncoder(hf_id, device="cpu")
    load_s = time.time() - t0
    rss_loaded = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024
    params = sum(p.numel() for p in (model.parameters() if kind == "bi" else model.model.parameters()))
    data = load_dataset()
    det = {}
    with open(DET_FEATURES) as f:
        for line in f:
            r = json.loads(line)
            det[r["id"]] = {c["key"]: c["comparable"] for c in r["candidates"]}
    # warm-up (first call pays lazy init)
    if kind == "bi":
        model.encode(["warm up"] * 4)
    else:
        model.predict([("warm", "up")] * 4)
    out = {}
    for rec in data:
        texts = [candidate_text(c["part"], det[rec["id"]][c["key"]]) for c in rec["candidates"]]
        t1 = time.perf_counter()
        with torch.inference_mode():
            if kind == "bi":
                q = model.encode([prefix + rec["query"]], normalize_embeddings=True, batch_size=64)
                d = model.encode(texts, normalize_embeddings=True, batch_size=64)
                s = (d @ q[0]).tolist()
            else:
                s = [float(x) for x in model.predict([(rec["query"], t) for t in texts], batch_size=64)]
        ms = (time.perf_counter() - t1) * 1000
        out[rec["id"]] = {"scores": {c["key"]: v for c, v in zip(rec["candidates"], s)}, "latency_ms": ms}
    rss_peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024
    cache = os.environ.get("HF_HOME", os.path.expanduser("~/.cache/huggingface"))
    snap = os.path.join(cache, "hub", "models--" + hf_id.replace("/", "--"))
    meta = {"hf_id": hf_id, "kind": kind, "licence": licence, "threads": threads, "params_m": round(params / 1e6, 1),
            "download_mb": round(dir_size(snap) / 1e6, 1) if os.path.isdir(snap) else None, "load_s": round(load_s, 2),
            "rss_after_load_mb": round(rss_loaded), "rss_peak_mb": round(rss_peak), "torch": torch.__version__}
    write_scores(key + suffix, out, meta)
    lat = sorted(v["latency_ms"] for v in out.values())
    print(json.dumps(dict(meta, lat_median_ms=lat[len(lat) // 2], lat_max_ms=lat[-1])))


if __name__ == "__main__":
    main()
