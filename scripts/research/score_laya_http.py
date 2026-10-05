"""Laya zero-shot variants over the HTTP API (POST /v1/systemone/batch), one request per query (all candidates).

    python3 scripts/research/score_laya_http.py [variant ...]      # default: all VARIANTS
    LAYA_URL=http://127.0.0.1:8001 (default)

Each variant = (checkpoint, state builder, questions, aggregation, request extras). Latency is the wall clock of the
single batch request for the query's candidate set (server pinned to 8 P-cores, LAYA_THREADS=8, see README).
"""
import json
import os
import sys
import time
import urllib.request

from common import candidate_text, load_dataset, load_det_features, production_state, write_scores

URL = os.environ.get("LAYA_URL", "http://127.0.0.1:8001")
TIMEOUT = 600

FITS = ("The candidate electronic component satisfies every requirement stated in the request: component type, value, "
        "tolerance, voltage or current rating, dielectric or technology, package or footprint and mounting type.")

Q_NOUL = {"fits": {"type": "noul", "instructions": FITS}}
Q_SCORE = {"rel": {"type": "score", "instructions": "How well does the candidate electronic component match the request?",
                   "criteria": ["unrelated: a different kind of component",
                                "same kind of component but the main value or function is wrong",
                                "close alternative: right kind and value, one stated requirement differs",
                                "exact match: every stated requirement is met"]}}
Q_CHOICE = {"rel": {"type": "choice", "instructions": "Which statement describes the candidate best, given the request?",
                    "criteria": {"exact": "it meets every requirement stated in the request",
                                 "close": "right kind and value, but one stated requirement such as package, rating, "
                                          "tolerance or dielectric differs",
                                 "family": "same kind of component with a different main value or function",
                                 "unrelated": "a different kind of component"}}}
Q_DECOMP = {
    "type": {"type": "noul", "instructions": "The candidate is the same kind of electronic component as the one requested."},
    "value": {"type": "noul", "instructions": "The candidate's main value (capacitance, resistance, inductance, output "
                                              "voltage, frequency or part number) is the one requested."},
    "package": {"type": "noul", "instructions": "The candidate's package or footprint is the requested one, or the "
                                                "request names no package."},
    "ratings": {"type": "noul", "instructions": "The candidate's voltage, current, tolerance and dielectric meet the "
                                                "request, or the request states none of them."},
}


def plain_state(query, part, comparable):
    return "Request: %s\nCandidate: %s" % (query, candidate_text(part, comparable))


def parsed_state(query, part, comparable, parsed):
    req = {"text": query}
    if parsed.get("family"):
        req["component type"] = parsed["family"]
    req.update(parsed.get("constraints") or {})
    for k in ("dielectric", "package", "mounting"):
        if parsed.get(k):
            req[k] = parsed[k]
    cand = json.loads(production_state(query, part, comparable))["candidate"]
    return json.dumps({"request": req, "candidate": cand}, ensure_ascii=False, separators=(",", ":"))


def agg_noul(ans):
    return ans["fits"]["noul"]


def agg_score(ans):
    return ans["rel"]["score"]


def agg_choice(ans):
    p = ans["rel"]["probabilities"]
    return 3 * p["exact"] + 2 * p["close"] + 1 * p["family"]


def agg_decomp_mean(ans):
    return sum(ans[k]["noul"] for k in Q_DECOMP) / len(Q_DECOMP)


def agg_decomp_prod(ans):
    out = 1.0
    for k in Q_DECOMP:
        out *= ans[k]["noul"]
    return out


VARIANTS = {
    # name: (model, state, questions, {score_name: aggregator}, extra request fields)
    "laya_ml_noul": ("multilingual", "prod", Q_NOUL, {"laya_ml_noul": agg_noul}, {}),
    "laya_ml_score": ("multilingual", "prod", Q_SCORE, {"laya_ml_score": agg_score}, {}),
    "laya_ml_choice": ("multilingual", "prod", Q_CHOICE, {"laya_ml_choice": agg_choice}, {}),
    "laya_ml_decomp": ("multilingual", "prod", Q_DECOMP,
                       {"laya_ml_decomp_mean": agg_decomp_mean, "laya_ml_decomp_prod": agg_decomp_prod}, {}),
    "laya_ml_noul_plain": ("multilingual", "plain", Q_NOUL, {"laya_ml_noul_plain": agg_noul}, {}),
    "laya_ml_noul_parsed": ("multilingual", "parsed", Q_NOUL, {"laya_ml_noul_parsed": agg_noul}, {}),
    "laya_ml_noul_len192": ("multilingual", "prod", Q_NOUL, {"laya_ml_noul_len192": agg_noul},
                            {"max_len": 192, "head_max_len": 64}),
    "laya_en_noul": ("english", "prod", Q_NOUL, {"laya_en_noul": agg_noul}, {}),
    "laya_td_noul": ("typed-decisions", "prod", Q_NOUL, {"laya_td_noul": agg_noul}, {}),
    "laya_en_choice": ("english", "prod", Q_CHOICE, {"laya_en_choice": agg_choice}, {}),
    "laya_td_choice": ("typed-decisions", "prod", Q_CHOICE, {"laya_td_choice": agg_choice}, {}),
}


def post(path, body):
    req = urllib.request.Request(URL + path, data=json.dumps(body).encode(), headers={"content-type": "application/json"})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        return json.load(r)


def warm(model):
    t0 = time.time()
    post("/v1/systemone", {"state": "warm up", "questions": Q_NOUL, "model": model})
    return time.time() - t0


def run(name):
    model, state_kind, questions, aggs, extra = VARIANTS[name]
    data = load_dataset()
    det = load_det_features()
    cold = warm(model)
    out = {k: {} for k in aggs}
    tokens = []
    for rec in data:
        rows = {r["key"]: r for r in det[rec["id"]]["candidates"]}
        states = []
        for c in rec["candidates"]:
            comp = rows[c["key"]]["comparable"]
            if state_kind == "prod":
                states.append(production_state(rec["query"], c["part"], comp))
            elif state_kind == "plain":
                states.append(plain_state(rec["query"], c["part"], comp))
            else:
                states.append(parsed_state(rec["query"], c["part"], comp, det[rec["id"]]["parsed"]))
        body = dict(states=states, questions=questions, model=model, sort_by_length=True, **extra)
        t0 = time.perf_counter()
        resp = post("/v1/systemone/batch", body)
        ms = (time.perf_counter() - t0) * 1000
        res = resp["results"]
        assert len(res) == len(states)
        tokens += [r.get("usage", {}).get("state_tokens", 0) for r in res]
        for k, fn in aggs.items():
            out[k][rec["id"]] = {"scores": {c["key"]: fn(r["answers"]) for c, r in zip(rec["candidates"], res)},
                                 "latency_ms": ms}
        print("%s %s %d states %.0f ms" % (name, rec["id"], len(states), ms), flush=True)
    meta = {"model": model, "state": state_kind, "questions": list(questions), "extra": extra,
            "cold_load_s": round(cold, 2), "mean_state_tokens": round(sum(tokens) / max(1, len(tokens)), 1),
            "server": URL}
    for k in aggs:
        write_scores(k, out[k], meta)


def main():
    names = sys.argv[1:] or list(VARIANTS)
    for n in names:
        run(n)


if __name__ == "__main__":
    main()
