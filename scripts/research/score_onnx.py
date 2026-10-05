"""ONNX Runtime check for the MiniLM cross-encoder (the Java-only deployment path: onnxruntime-java runs the same graph).

    scripts/research/rc.sh -c 0-7 -t 8 ...   but with the ONNX image:
    docker run ... kina-laya-research:onnx python score_onnx.py <threads> [int8]
Exports cross-encoder/ms-marco-MiniLM-L6-v2 to ONNX with optimum (fp32; optional dynamic INT8 quantisation), scores
every query with onnxruntime (intra_op_num_threads = threads), and records latency, RSS and the agreement with the
PyTorch scores (Spearman over all candidates). Writes msmarco_minilm_ce_onnx[_int8]_t<threads>.
"""
import json
import os
import resource
import sys
import time

import numpy as np

from common import candidate_text, load_dataset, load_det_features, read_scores, write_scores


def main():
    threads = int(sys.argv[1]) if len(sys.argv) > 1 else 8
    int8 = len(sys.argv) > 2 and sys.argv[2] == "int8"
    import onnxruntime as ort
    from optimum.onnxruntime import ORTModelForSequenceClassification
    from transformers import AutoTokenizer
    hf_id = "cross-encoder/ms-marco-MiniLM-L6-v2"
    export_dir = "/ft/onnx_msmarco_minilm_l6"
    if not os.path.exists(os.path.join(export_dir, "model.onnx")):
        m = ORTModelForSequenceClassification.from_pretrained(hf_id, export=True)
        m.save_pretrained(export_dir)
        AutoTokenizer.from_pretrained(hf_id).save_pretrained(export_dir)
    path = os.path.join(export_dir, "model.onnx")
    if int8:
        from onnxruntime.quantization import QuantType, quantize_dynamic
        qpath = os.path.join(export_dir, "model_int8.onnx")
        if not os.path.exists(qpath):
            quantize_dynamic(path, qpath, weight_type=QuantType.QInt8)
        path = qpath
    so = ort.SessionOptions()
    so.intra_op_num_threads = threads
    so.inter_op_num_threads = 1
    sess = ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])
    tok = AutoTokenizer.from_pretrained(export_dir)
    names = [i.name for i in sess.get_inputs()]
    data = load_dataset()
    det = load_det_features()

    def run(q, texts):
        enc = tok([q] * len(texts), texts, padding=True, truncation=True, max_length=512, return_tensors="np")
        feeds = {n: enc[n].astype(np.int64) for n in names if n in enc}
        return sess.run(None, feeds)[0].reshape(-1).tolist()

    run("warm", ["up"] * 4)
    out = {}
    for rec in data:
        rows = {r["key"]: r for r in det[rec["id"]]["candidates"]}
        texts = [candidate_text(c["part"], rows[c["key"]]["comparable"]) for c in rec["candidates"]]
        t0 = time.perf_counter()
        s = run(rec["query"], texts)
        out[rec["id"]] = {"scores": {c["key"]: v for c, v in zip(rec["candidates"], s)},
                          "latency_ms": (time.perf_counter() - t0) * 1000}
    ref = read_scores("msmarco_minilm_ce_ecore")["queries"]
    a, b = [], []
    for q, v in out.items():
        for k, s in v["scores"].items():
            a.append(s)
            b.append(ref[q]["scores"][k])
    ra, rb = np.argsort(np.argsort(a)), np.argsort(np.argsort(b))
    rho = float(np.corrcoef(ra, rb)[0, 1])
    meta = {"onnx": os.path.basename(path), "onnx_mb": round(os.path.getsize(path) / 1e6, 1), "threads": threads,
            "onnxruntime": ort.__version__, "spearman_vs_torch": round(rho, 5),
            "rss_peak_mb": round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024)}
    name = "msmarco_minilm_ce_onnx%s_t%d" % ("_int8" if int8 else "", threads)
    write_scores(name, out, meta)
    lat = sorted(v["latency_ms"] for v in out.values())
    print(json.dumps(dict(meta, lat_median_ms=round(lat[len(lat) // 2], 1), lat_max_ms=round(lat[-1], 1))))


if __name__ == "__main__":
    main()
