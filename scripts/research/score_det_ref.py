"""Score the dataset with the DeterministicRanker of another git revision (e.g. main) without checking it out.

    git archive -o /tmp/ref.tar <rev> src/main
    python3 scripts/research/score_det_ref.py /tmp/ref.tar <tag>
Extracts src/main from the tarball, compiles it with the project's dependency classpath (target/cp.txt), compiles
ResearchRunner against it, writes out/det_features_<tag>.jsonl and the score file det_<tag>.
If the reference's package-private feature API changed, the runner falls back to the score only (features empty).
"""
import json
import os
import subprocess
import sys
import tarfile
import tempfile

from common import DATASET, OUT, ROOT, load_dataset, write_scores

HERE = os.path.dirname(os.path.abspath(__file__))


def main():
    tar, tag = sys.argv[1], sys.argv[2]
    cp = open(os.path.join(ROOT, "target", "cp.txt")).read().strip()
    work = tempfile.mkdtemp(prefix="detref-")
    with tarfile.open(tar) as t:
        t.extractall(work)
    srcs = []
    for r, _, fs in os.walk(os.path.join(work, "src", "main", "java")):
        srcs += [os.path.join(r, f) for f in fs if f.endswith(".java")]
    classes = os.path.join(work, "classes")
    subprocess.run(["javac", "--release", "21", "-nowarn", "-d", classes, "-cp", cp] + srcs, check=True,
                   capture_output=True)
    rclasses = os.path.join(work, "rclasses")
    subprocess.run(["javac", "--release", "21", "-d", rclasses, "-cp", classes + ":" + cp,
                    os.path.join(HERE, "java", "ro", "alacrity", "kina", "search", "ResearchRunner.java")], check=True)
    out = os.path.join(OUT, "det_features_%s.jsonl" % tag)
    subprocess.run(["java", "--enable-native-access=ALL-UNNAMED", "-cp", ":".join([rclasses, classes, cp]),
                    "ro.alacrity.kina.search.ResearchRunner", "score", DATASET, out], check=True, capture_output=True,
                   env=dict(os.environ, RESEARCH_LENIENT="1"))
    rows = {json.loads(l)["id"]: json.loads(l) for l in open(out)}
    q = {rec["id"]: {"scores": {c["key"]: c["det"] for c in rows[rec["id"]]["candidates"]},
                     "latency_ms": sum(c["nanos"] for c in rows[rec["id"]]["candidates"]) / 1e6} for rec in load_dataset()}
    write_scores("det_" + tag, q, {"impl": "DeterministicRanker from " + os.path.basename(tar)})
    print("written det_" + tag)


if __name__ == "__main__":
    main()
