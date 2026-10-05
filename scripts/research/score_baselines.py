"""Baselines: production DeterministicRanker (Java, via ResearchRunner), distributor order, BM25.

    python3 scripts/research/score_baselines.py
Needs target/classes + target/cp.txt (./mvnw -q -o -DskipTests compile dependency:build-classpath
-Dmdep.outputFile=target/cp.txt) and target/research-classes (javac of scripts/research/java, see README).
"""
import json
import math
import os
import re
import subprocess
import time

from common import DATASET, DET_FEATURES, OUT, ROOT, candidate_text, load_dataset, load_det_features, write_scores


def run_java_det():
    os.makedirs(OUT, exist_ok=True)
    cp = open(os.path.join(ROOT, "target", "cp.txt")).read().strip()
    cp = ":".join([os.path.join(ROOT, "target", "research-classes"), os.path.join(ROOT, "target", "classes"), cp])
    t0 = time.time()
    subprocess.run(["java", "--enable-native-access=ALL-UNNAMED", "-cp", cp, "ro.alacrity.kina.search.ResearchRunner",
                    "score", DATASET, DET_FEATURES], check=True, capture_output=True)
    return time.time() - t0


TOKEN = re.compile(r"[a-z0-9]+(?:[.][0-9]+)?")


def tokens(s):
    s = (s or "").lower().replace("µ", "u").replace("Ω", "ohm").replace("±", " ")
    return TOKEN.findall(s)


def bm25_scores(query, docs, k1=1.2, b=0.75):
    """Okapi BM25 with the candidate set itself as the corpus (what a re-ranker sees at request time)."""
    toks = [tokens(d) for d in docs]
    n = len(toks)
    avgdl = sum(len(t) for t in toks) / max(1, n)
    df = {}
    for t in toks:
        for w in set(t):
            df[w] = df.get(w, 0) + 1
    q = tokens(query)
    out = []
    for t in toks:
        tf = {}
        for w in t:
            tf[w] = tf.get(w, 0) + 1
        s = 0.0
        for w in q:
            if w not in tf:
                continue
            idf = math.log(1 + (n - df[w] + 0.5) / (df[w] + 0.5))
            s += idf * tf[w] * (k1 + 1) / (tf[w] + k1 * (1 - b + b * len(t) / avgdl))
        out.append(s)
    return out


def main():
    secs = run_java_det()
    det = load_det_features()
    data = load_dataset()
    det_q, dist_q, bm_q = {}, {}, {}
    for rec in data:
        rows = {r["key"]: r for r in det[rec["id"]]["candidates"]}
        det_q[rec["id"]] = {"scores": {k: r["det"] for k, r in rows.items()},
                            "latency_ms": sum(r["nanos"] for r in rows.values()) / 1e6}
        dist_q[rec["id"]] = {"scores": {c["key"]: -float(c["dist_rank"]) for c in rec["candidates"]}, "latency_ms": 0.0}
        docs = [candidate_text(c["part"], rows[c["key"]]["comparable"]) for c in rec["candidates"]]
        t0 = time.perf_counter()
        s = bm25_scores(rec["query"], docs)
        bm_q[rec["id"]] = {"scores": {c["key"]: v for c, v in zip(rec["candidates"], s)},
                           "latency_ms": (time.perf_counter() - t0) * 1000}
    write_scores("det", det_q, {"impl": "Java DeterministicRanker via ResearchRunner (production code)",
                                "java_wall_s_total_incl_jvm_start": round(secs, 2)})
    write_scores("distributor_order", dist_q, {"impl": "distributor's own order; mined negatives appended"})
    write_scores("bm25", bm_q, {"impl": "Okapi BM25 k1=1.2 b=0.75, corpus = candidate set, text = mpn+desc+category+attrs"})
    print("baselines written; java run %.1fs" % secs)


if __name__ == "__main__":
    main()
