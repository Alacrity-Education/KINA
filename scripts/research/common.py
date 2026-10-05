"""Shared helpers for the ranking research scripts (host Python 3.11+ and the research container).

Score files: scripts/research/out/scores/<method>.json
    {"method": str, "meta": {...}, "queries": {qid: {"scores": {candidate_key: float}, "latency_ms": float}}}
Higher score = more relevant. Ties are broken by the harness (see evaluate.py), never by the scorer.
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
DATASET = os.environ.get("RANKING_DATASET", os.path.join(ROOT, "docs", "research", "data", "ranking-eval.jsonl"))
OUT = os.environ.get("RANKING_OUT", os.path.join(HERE, "out"))
SCORES = os.path.join(OUT, "scores")
DET_FEATURES = os.path.join(OUT, "det_features.jsonl")


def load_dataset(path=DATASET):
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def load_det_features():
    """Java ResearchRunner output: per query the parsed query, per candidate det score, feature vector, comparables."""
    out = {}
    with open(DET_FEATURES, encoding="utf-8") as f:
        for line in f:
            rec = json.loads(line)
            out[rec["id"]] = rec
    return out


def write_scores(method, queries, meta=None):
    os.makedirs(SCORES, exist_ok=True)
    path = os.path.join(SCORES, method + ".json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"method": method, "meta": meta or {}, "queries": queries}, f, ensure_ascii=False)
    return path


def read_scores(method):
    with open(os.path.join(SCORES, method + ".json"), encoding="utf-8") as f:
        return json.load(f)


def folds(data):
    """The study's 2-fold split by query, stratified by category: every other query of each category goes to fold A."""
    by_cat = {}
    for rec in data:
        by_cat.setdefault(rec["category"], []).append(rec["id"])
    a = set()
    for ids in by_cat.values():
        a.update(ids[0::2])
    return a, {r["id"] for r in data} - a


def candidate_text(part, comparable=None, with_category=True):
    """Plain-text rendering used by the lexical and neural scorers."""
    bits = [part.get("manufacturer") or "", part.get("manufacturerPartNumber") or "", part.get("description") or ""]
    if with_category and part.get("category"):
        bits.append(part["category"])
    if part.get("packageName"):
        bits.append("package " + part["packageName"])
    attrs = dict(part.get("attributes") or {})
    for k, v in (comparable or {}).items():
        attrs.setdefault(k, v)
    if attrs:
        bits.append("; ".join("%s: %s" % (k, v) for k, v in attrs.items()))
    return " | ".join(b for b in bits if b)
