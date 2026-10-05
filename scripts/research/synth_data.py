"""Synthetic training data from the parametric rubric (no hand labels): random passive queries, candidates from
KINA's own LCSC retrieval for the query and for perturbed queries, labels from the same rubric functions that label
the evaluation set (cap_query / res_query). Value/package combinations used by evaluation queries are excluded.

    python3 scripts/research/synth_data.py [n_queries]     # default 80
Writes scripts/research/out/synth-train.jsonl (same schema as ranking-eval.jsonl, plus "synthetic": true).
"""
import json
import os
import random
import subprocess
import sys

from build_dataset import JLC_DB, java_cp, key, trim
from common import OUT
from queries import cap_query, res_query

CAPS = [("1nF", 1e-9), ("2.2nF", 2.2e-9), ("4.7nF", 4.7e-9), ("10nF", 10e-9), ("22nF", 22e-9), ("47nF", 47e-9),
        ("220nF", 220e-9), ("470nF", 470e-9), ("1uF", 1e-6), ("2.2uF", 2.2e-6), ("4.7uF", 4.7e-6), ("22uF", 22e-6),
        ("10pF", 10e-12), ("100pF", 100e-12), ("47pF", 47e-12)]
RES = [("100R", "100 ohm", 100.0), ("1k", "1k", 1e3), ("2k2", "2.2k", 2.2e3), ("10k", "10k", 1e4), ("47k", "47k", 4.7e4),
       ("100k", "100k", 1e5), ("330R", "330 ohm", 330.0), ("1M", "1M", 1e6), ("22R", "22 ohm", 22.0), ("5k1", "5.1k", 5.1e3)]
PKGS = ["0402", "0603", "0805", "1206"]
METRIC = {"0402": "1005", "0603": "1608", "0805": "2012", "1206": "3216"}
DIELS = ["X7R", "X5R", "C0G"]
VOLTS = [10, 16, 25, 50]
EVAL_COMBOS = {("cap", 10e-6, "0805"), ("cap", 100e-9, "0603"), ("cap", 1e-9, "0402"), ("cap", 22e-12, "0603"),
               ("res", 4.7e3, "0603"), ("res", 10.0, "0805"), ("res", 2.2, "1206")}


def make_queries(n, rng):
    specs = []
    while len(specs) < n:
        pkg = rng.choice(PKGS)
        if rng.random() < 0.55:
            txt, c = rng.choice(CAPS)
            if ("cap", c, pkg) in EVAL_COMBOS:
                continue
            d = "C0G" if c < 1e-9 else rng.choice(DIELS[:2])
            v = rng.choice(VOLTS)
            q = " ".join(rng.sample([txt, d, pkg, "%dV" % v], 4) + (["MLCC"] if rng.random() < 0.5 else []))
            other_d = "X5R" if d != "X5R" else "X7R"
            other_p = rng.choice([p for p in PKGS if p != pkg])
            mined = ["%s %s %s" % (txt, other_d, pkg), "%s %s %s" % (txt, d, other_p),
                     "%s %s %s" % (rng.choice([t for t, x in CAPS if x != c]), d, pkg), "%s resistor %s" % (txt[:-1], pkg)]
            specs.append(dict(query=q, label=cap_query(c, [pkg, METRIC[pkg]], [d], vmin=v), mined=mined))
        else:
            rkm, txt, r = rng.choice(RES)
            if ("res", r, pkg) in EVAL_COMBOS:
                continue
            tol = rng.choice([1, 5])
            q = "%s %s%s resistor" % (rng.choice([rkm, txt]), pkg, " %d%%" % tol if rng.random() < 0.7 else "")
            mined = ["%s %s resistor" % (txt, rng.choice([p for p in PKGS if p != pkg])),
                     "%s %s resistor" % (rng.choice([t for _, t, x in RES if x != r]), pkg),
                     "%s %s resistor array" % (txt, pkg), "%sF %s capacitor" % (txt.split()[0], pkg)]
            specs.append(dict(query=q, label=res_query(r, [pkg, METRIC[pkg]], tolmax=tol if "%" in q else None),
                              mined=mined))
    return specs


def lcsc(queries, limit):
    out = subprocess.run(["java", "--enable-native-access=ALL-UNNAMED", "-cp", java_cp(),
                          "ro.alacrity.kina.search.ResearchRunner", "lcsc", JLC_DB, str(limit)] + queries,
                         capture_output=True, text=True, check=True).stdout
    return {r["query"]: r["parts"] for r in (json.loads(l) for l in out.splitlines() if l.startswith("{"))}


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 80
    rng = random.Random(1234)
    specs = make_queries(n, rng)
    native = lcsc([s["query"] for s in specs], 20)
    mined = lcsc(sorted({m for s in specs for m in s["mined"]}), 5)
    os.makedirs(OUT, exist_ok=True)
    total = 0
    with open(os.path.join(OUT, "synth-train.jsonl"), "w") as f:
        for i, s in enumerate(specs):
            pool = {}
            for part in native.get(s["query"], []) + [p for m in s["mined"] for p in mined.get(m, [])]:
                pool.setdefault(key(part), dict(key=key(part), part=trim(part)))
            cands = []
            for c in pool.values():
                lab, why = s["label"](c)
                cands.append(dict(c, label=lab, label_reason=why))
            if len(cands) < 8:
                continue
            total += len(cands)
            f.write(json.dumps(dict(id="s%03d" % i, query=s["query"], category="passive", synthetic=True,
                                    candidates=cands), ensure_ascii=False) + "\n")
    print("synthetic queries=%d candidates=%d" % (len(specs), total))


if __name__ == "__main__":
    main()
