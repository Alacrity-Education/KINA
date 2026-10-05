"""Latency and memory on fixed cores with nothing else running (run after all scorers).

    python3 scripts/research/latency.py [laya] [neural] [onnx]     # default: all three groups
laya   : fresh laya-serve container per (checkpoint, threads): 8 threads on cpus 0-7, 4 threads on cpus 0-3,
         production noul question over every query (one batch request per query); container memory after the run
neural : score_neural.py on cpus 0-7 (8 threads) and 0-3 (4 threads) -> <model>_p8 / <model>_p4
onnx   : score_onnx.py (fp32 and int8) at 8 and 4 threads -> msmarco_minilm_ce_onnx[_int8]_t<threads>
Writes out/latency_laya.json; the neural/ONNX numbers land in their score files' meta and latency fields.
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

from common import OUT, load_dataset, load_det_features, production_state, write_scores
from score_laya_http import Q_NOUL

HERE = os.path.dirname(os.path.abspath(__file__))
PORT = 8002
NAME = "kina-laya-lat"


def sh(*args, **kw):
    return subprocess.run(list(args), check=True, capture_output=True, text=True, **kw).stdout


def post(path, body):
    req = urllib.request.Request("http://127.0.0.1:%d%s" % (PORT, path), data=json.dumps(body).encode(),
                                 headers={"content-type": "application/json"})
    with urllib.request.urlopen(req, timeout=900) as r:
        return json.load(r)


def laya_server(cpus, threads):
    subprocess.run(["docker", "rm", "-f", NAME], capture_output=True)
    sh("docker", "run", "-d", "--name", NAME, "--cpuset-cpus=" + cpus, "-v",
       "kina_laya-models:/home/laya/.cache/huggingface", "-e", "LAYA_PRELOAD=0", "-e", "LAYA_MAX_LOADED=1",
       "-e", "LAYA_THREADS=%d" % threads, "-e", "OMP_NUM_THREADS=%d" % threads, "-e", "LAYA_DEVICE=cpu",
       "-e", "LAYA_MAX_CONCURRENT=1", "-e", "HF_HUB_OFFLINE=1", "-p", "127.0.0.1:%d:8000" % PORT,
       "kina-laya:v0.3.27", "laya-serve")
    for _ in range(120):
        try:
            with urllib.request.urlopen("http://127.0.0.1:%d/health" % PORT, timeout=2):
                return
        except Exception:
            time.sleep(1)
    raise RuntimeError("laya server did not start")


def mem_mib():
    s = sh("docker", "stats", "--no-stream", "--format", "{{.MemUsage}}", NAME).split("/")[0].strip()
    v = float(s[:-3])
    return v * 1024 if s.endswith("GiB") else v


def laya():
    data = load_dataset()
    det = load_det_features()
    out = {}
    for model, tag in (("multilingual", "ml"), ("typed-decisions", "td")):
        for cpus, threads in (("0-7", 8), ("0-3", 4)):
            laya_server(cpus, threads)
            idle = mem_mib()
            t0 = time.time()
            post("/v1/systemone", {"state": "warm up", "questions": Q_NOUL, "model": model})
            cold = time.time() - t0
            q = {}
            for rec in data:
                rows = {r["key"]: r for r in det[rec["id"]]["candidates"]}
                states = [production_state(rec["query"], c["part"], rows[c["key"]]["comparable"])
                          for c in rec["candidates"]]
                t1 = time.perf_counter()
                res = post("/v1/systemone/batch", dict(states=states, questions=Q_NOUL, model=model, sort_by_length=True))
                ms = (time.perf_counter() - t1) * 1000
                q[rec["id"]] = {"scores": {c["key"]: r["answers"]["fits"]["noul"]
                                           for c, r in zip(rec["candidates"], res["results"])}, "latency_ms": ms,
                                "n": len(states)}
            name = "laya_%s_noul_p%d" % (tag, threads)
            mem = mem_mib()
            lat40 = sorted(v["latency_ms"] for v in q.values() if v["n"] == 40)
            out[name] = {"model": model, "threads": threads, "cpus": cpus, "cold_load_s": round(cold, 2),
                         "mem_idle_mib": round(idle), "mem_after_mib": round(mem),
                         "lat40_median_ms": round(lat40[len(lat40) // 2]), "lat40_max_ms": round(lat40[-1])}
            write_scores(name, q, out[name])
            print(name, out[name], flush=True)
    subprocess.run(["docker", "rm", "-f", NAME], capture_output=True)
    with open(os.path.join(OUT, "latency_laya.json"), "w") as f:
        json.dump(out, f, indent=1)


def neural():
    for m in ("msmarco_minilm_ce", "msmarco_minilm12_ce", "bge_reranker_base_ce", "minilm_bi", "bge_small_bi"):
        for cpus, threads in (("0-7", 8), ("0-3", 4)):
            r = subprocess.run([os.path.join(HERE, "rc.sh"), "-c", cpus, "-t", str(threads), "python", "score_neural.py",
                                m, "_p%d" % threads], capture_output=True, text=True)
            print(m, threads, r.stdout.strip().splitlines()[-1] if r.stdout.strip() else r.stderr[-500:], flush=True)


def onnx():
    for cpus, threads in (("0-7", 8), ("0-3", 4)):
        for q in ("", "int8"):
            cmd = ["docker", "run", "--rm", "--cpuset-cpus=" + cpus, "-e", "OMP_NUM_THREADS=%d" % threads,
                   "-e", "HF_HOME=/hf", "-e", "RANKING_DATASET=/repo/docs/research/data/ranking-eval.jsonl",
                   "-e", "RANKING_OUT=/work/out", "-v", "kina-research-hf:/hf", "-v", "kina-research-ft:/ft",
                   "-v", HERE + ":/work", "-v", os.path.join(os.path.dirname(os.path.dirname(HERE)), "docs") +
                   ":/repo/docs:ro", "-w", "/work", "kina-laya-research:onnx", "python", "score_onnx.py", str(threads)]
            if q:
                cmd.append(q)
            r = subprocess.run(cmd, capture_output=True, text=True)
            print("onnx", threads, q, r.stdout.strip().splitlines()[-1] if r.stdout.strip() else r.stderr[-800:],
                  flush=True)


def main():
    groups = sys.argv[1:] or ["laya", "neural", "onnx"]
    for g in groups:
        {"laya": laya, "neural": neural, "onnx": onnx}[g]()


if __name__ == "__main__":
    main()
