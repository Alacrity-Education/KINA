"""Latency and memory on fixed cores with nothing else running (run after all scorers).

    python3 scripts/research/latency.py [neural] [onnx]     # default: both groups
neural : score_neural.py on cpus 0-7 (8 threads) and 0-3 (4 threads) -> <model>_p8 / <model>_p4
onnx   : score_onnx.py (fp32 and int8) at 8 and 4 threads -> msmarco_minilm_ce_onnx[_int8]_t<threads>
Every run goes through rc.sh (the research image). The numbers land in the score files' meta (peak RSS, load time) and
latency_ms fields; report_tables.py reads them from there. The deterministic ranker's latency is recorded by
score_baselines.py (in-process Java, no container).
"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RC = os.path.join(HERE, "rc.sh")
NEURAL = ("msmarco_minilm_ce", "msmarco_minilm12_ce", "bge_reranker_base_ce", "minilm_bi", "bge_small_bi")
CORES = (("0-7", 8), ("0-3", 4))


def run(cmd, label):
    r = subprocess.run(cmd, capture_output=True, text=True)
    out = r.stdout.strip()
    print(label, out.splitlines()[-1] if out else r.stderr[-800:], flush=True)


def neural():
    for m in NEURAL:
        for cpus, threads in CORES:
            run([RC, "-c", cpus, "-t", str(threads), "python", "score_neural.py", m, "_p%d" % threads],
                "%s %d threads:" % (m, threads))


def onnx():
    for cpus, threads in CORES:
        for q in ("", "int8"):
            cmd = [RC, "-c", cpus, "-t", str(threads), "python", "score_onnx.py", str(threads)] + ([q] if q else [])
            run(cmd, "onnx %s %d threads:" % (q or "fp32", threads))


GROUPS = {"neural": neural, "onnx": onnx}


def main():
    args = sys.argv[1:]
    if any(a in ("-h", "--help") for a in args) or any(a not in GROUPS for a in args):
        print(__doc__)
        return
    for g in args or list(GROUPS):
        GROUPS[g]()


if __name__ == "__main__":
    main()
