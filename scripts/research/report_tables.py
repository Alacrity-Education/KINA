"""Builds every table of the report (markdown) from the score files, their meta blocks and out/select_cv.json.

    python3 scripts/research/report_tables.py > scripts/research/out/report_tables.md
    python3 scripts/research/fill_report.py          # copies each table into the report between its markers

Each table is preceded by a line `<!-- TABLE NAME -->`; fill_report.py replaces `<!-- BEGIN NAME -->` ...
`<!-- END NAME -->` in the report with it. Run select_cv.py first (SELECT_CV_TABLE reads its output).
Latency: the P-core re-measurement (`*_p8`, `*_p4`, `*_t8`, `*_t4` score files from latency.py) where it exists,
otherwise the latency recorded by the scorer itself. Fits 20 s: worst query (all candidates, up to 40) below 20 s at
8 threads.
"""
import json
import os
import re

from common import OUT, SCORES, load_dataset
from evaluate import CATS, evaluate, fmt

CE = "msmarco_minilm_ce_ecore"
FT = ["msmarco_minilm_ce_ft_real", "msmarco_minilm_ce_ft_synth", "msmarco_minilm_ce_ft_synth_real"]

ROWS = [
    # (label, method, latency source 8 threads, latency source 4 threads, peak RSS source)
    ("Deterministic ranker (production Java)", "det", "det", None, None),
    ("Deterministic ranker, main @ bf1d59b (connector-aware)", "det_main_bf1d59b", None, None, None),
    ("Distributor order", "distributor_order", None, None, None),
    ("BM25 over the candidate set", "bm25", None, None, None),
    ("Learned weights, ridge (LOQO)", "learned_ridge", None, None, None),
    ("Learned weights, pairwise logistic (LOQO)", "learned_pairwise", None, None, None),
    ("Bi-encoder all-MiniLM-L6-v2", "minilm_bi_ecore", "minilm_bi_p8", "minilm_bi_p4", "minilm_bi_p8"),
    ("Bi-encoder bge-small-en-v1.5", "bge_small_bi_ecore", "bge_small_bi_p8", "bge_small_bi_p4", "bge_small_bi_p8"),
    ("Cross-encoder ms-marco-MiniLM-L6-v2", CE, "msmarco_minilm_ce_p8", "msmarco_minilm_ce_p4", "msmarco_minilm_ce_p8"),
    ("Cross-encoder ms-marco-MiniLM-L6-v2, ONNX Runtime fp32", "msmarco_minilm_ce_onnx_t8", "msmarco_minilm_ce_onnx_t8",
     "msmarco_minilm_ce_onnx_t4", "msmarco_minilm_ce_onnx_t8"),
    ("Cross-encoder ms-marco-MiniLM-L6-v2, ONNX Runtime int8", "msmarco_minilm_ce_onnx_int8_t8",
     "msmarco_minilm_ce_onnx_int8_t8", "msmarco_minilm_ce_onnx_int8_t4", "msmarco_minilm_ce_onnx_int8_t8"),
    ("Cross-encoder ms-marco-MiniLM-L12-v2", "msmarco_minilm12_ce_ecore", "msmarco_minilm12_ce_p8",
     "msmarco_minilm12_ce_p4", "msmarco_minilm12_ce_p8"),
    ("Cross-encoder bge-reranker-base", "bge_reranker_base_ce_ecore", "bge_reranker_base_ce_p8",
     "bge_reranker_base_ce_p4", "bge_reranker_base_ce_p8"),
    ("MiniLM-L6 CE fine-tuned on real labels (2-fold)", FT[0], None, None, None),
    ("MiniLM-L6 CE fine-tuned on synthetic labels", FT[1], None, None, None),
    ("MiniLM-L6 CE fine-tuned on synthetic + real (2-fold)", FT[2], None, None, None),
    ("Hybrid: det + 0.2 x MiniLM-L6 CE (additive formula)", "hyb_blend_w0.2_" + CE, None, None, None),
    ("Hybrid: rank blend 0.7 det / 0.3 MiniLM-L6 CE", "hyb_rrblend_w0.3_" + CE, None, None, None),
    ("Hybrid: rank blend 0.5 det / 0.5 MiniLM-L6 CE (shipped)", "hyb_rrblend_w0.5_" + CE, None, None, None),
    ("Hybrid: MiniLM-L6 CE re-orders det top 10", "hyb_top10_" + CE, None, None, None),
    ("Hybrid: MiniLM-L6 CE inside det bands of 0.05", "hyb_band05_" + CE, None, None, None),
    ("Hybrid: MiniLM-L6 CE only breaks det ties", "hyb_tie_" + CE, None, None, None),
    ("Hybrid: MiniLM-L6 CE on candidates with det >= 0.5", "hyb_thr05_" + CE, None, None, None),
    ("Hybrid: rank blend 0.5 det / 0.5 bge-reranker-base", "hyb_rrblend_w0.5_bge_reranker_base_ce_ecore", None, None,
     None),
    ("Hybrid: rank blend 0.5 det / 0.5 MiniLM-L6 CE fine-tuned synth only", "hyb_rrblend_w0.5_" + FT[1], None, None, None),
    ("Hybrid: rank blend 0.5 det / 0.5 MiniLM-L6 CE fine-tuned synth+real", "hyb_rrblend_w0.5_" + FT[2], None, None, None),
    ("Hybrid: main det @ bf1d59b, rank blend 0.5 with MiniLM-L6 CE", "hyb_main_bf1d59b_rrblend_w0.5_" + CE, None, None,
     None),
    ("Hybrid: main det @ bf1d59b, rank blend 0.5 with MiniLM-L6 CE fine-tuned synth+real",
     "hyb_main_bf1d59b_rrblend_w0.5_" + FT[2], None, None, None),
]

# model signals for the neural and hybrid tables: (label, alone, latency/RSS source, download/params source)
SIGNALS = [
    ("all-MiniLM-L6-v2 (bi-encoder)", "minilm_bi_ecore", "minilm_bi_p8"),
    ("bge-small-en-v1.5 (bi-encoder)", "bge_small_bi_ecore", "bge_small_bi_p8"),
    ("ms-marco-MiniLM-L6-v2 (cross-encoder)", CE, "msmarco_minilm_ce_p8"),
    ("ms-marco-MiniLM-L12-v2 (cross-encoder)", "msmarco_minilm12_ce_ecore", "msmarco_minilm12_ce_p8"),
    ("bge-reranker-base (cross-encoder)", "bge_reranker_base_ce_ecore", "bge_reranker_base_ce_p8"),
    ("MiniLM-L6 CE fine-tuned, real labels (2-fold)", FT[0], None),
    ("MiniLM-L6 CE fine-tuned, synthetic labels only", FT[1], None),
    ("MiniLM-L6 CE fine-tuned, synthetic + real (2-fold)", FT[2], None),
]

HYBRID_KINDS = [
    ("additive, w = 0.2: 0.8 det + 0.2 ranknorm(X)", "blend_w0.2"),
    ("rank blend, w = 0.1", "rrblend_w0.1"),
    ("rank blend, w = 0.2", "rrblend_w0.2"),
    ("rank blend, w = 0.3", "rrblend_w0.3"),
    ("rank blend, w = 0.5", "rrblend_w0.5"),
    ("X only breaks exact det ties", "tie"),
    ("X inside det bands of 0.05", "band05"),
    ("X re-orders the det top 10", "top10"),
    ("X on candidates with det >= 0.5", "thr05"),
]
HYBRID_X = [("MiniLM-L6 CE", CE), ("MiniLM-L12 CE", "msmarco_minilm12_ce_ecore"),
            ("bge-reranker-base", "bge_reranker_base_ce_ecore"), ("MiniLM-L6 CE ft synth", FT[1]),
            ("MiniLM-L6 CE ft synth+real", FT[2]), ("all-MiniLM-L6 bi", "minilm_bi_ecore"),
            ("bge-small bi", "bge_small_bi_ecore")]

LATENCY = [
    # (label, 8-thread source, 4-thread source, artefact)
    ("MiniLM-L6 cross-encoder, PyTorch", "msmarco_minilm_ce_p8", "msmarco_minilm_ce_p4", "download"),
    ("MiniLM-L6 cross-encoder, ONNX Runtime fp32", "msmarco_minilm_ce_onnx_t8", "msmarco_minilm_ce_onnx_t4", "onnx"),
    ("MiniLM-L6 cross-encoder, ONNX Runtime int8", "msmarco_minilm_ce_onnx_int8_t8", "msmarco_minilm_ce_onnx_int8_t4",
     "onnx"),
    ("MiniLM-L12 cross-encoder, PyTorch", "msmarco_minilm12_ce_p8", "msmarco_minilm12_ce_p4", "download"),
    ("bge-reranker-base, PyTorch", "bge_reranker_base_ce_p8", "bge_reranker_base_ce_p4", "download"),
    ("all-MiniLM-L6-v2 bi-encoder, PyTorch", "minilm_bi_p8", "minilm_bi_p4", "download"),
    ("bge-small-en-v1.5 bi-encoder, PyTorch", "bge_small_bi_p8", "bge_small_bi_p4", "download"),
]

NATIVE = [("Deterministic ranker", "det"), ("Distributor order", "distributor_order"), ("BM25", "bm25"),
          ("MiniLM-L6 CE alone", CE), ("rank blend 0.5 det / 0.5 MiniLM-L6 CE", "hyb_rrblend_w0.5_" + CE),
          ("rank blend 0.5 det / 0.5 MiniLM-L6 CE fine-tuned synth+real", "hyb_rrblend_w0.5_" + FT[2])]


def exists(m):
    return bool(m) and os.path.exists(os.path.join(SCORES, m + ".json"))


def load(m):
    with open(os.path.join(SCORES, m + ".json")) as f:
        return json.load(f)


def lat(m):
    """(median, max) ms of a score file, or None."""
    if not exists(m):
        return None
    xs = sorted(v["latency_ms"] for v in load(m)["queries"].values())
    return xs[len(xs) // 2], xs[-1]


def lat_str(m):
    v = lat(m)
    return ("%d / %d" % v) if v else "-"


def ci_str(r):
    ci = r.get("ndcg10_vs_det_ci95")
    return ("[%+.3f, %+.3f]" % ci) if ci else ""


def table(name, header, rows):
    print("<!-- TABLE %s -->" % name)
    print("| " + " | ".join(header) + " |")
    print("|" + "---|" * len(header))
    for r in rows:
        print("| " + " | ".join(str(c) for c in r) + " |")
    print()


def main():
    data = load_dataset()
    allm = sorted(f[:-5] for f in os.listdir(SCORES) if f.endswith(".json"))
    res, _ = evaluate(allm, data)
    nq = max(r["queries"] for r in res.values())

    rows = []
    for label, m, p8, p4, rss in ROWS:
        if m not in res:
            continue
        r = res[m]
        src8 = p8 if exists(p8) else m
        l8 = lat(src8)
        mt = load(rss)["meta"] if exists(rss) else {}
        fits = "-" if not l8 else ("yes" if l8[1] < 20000 else "no")
        peak = ("%d MB" % mt["rss_peak_mb"]) if mt.get("rss_peak_mb") else ("in-process" if m.startswith(("det", "learned")) else "-")
        rows.append((label, fmt(r["ndcg5"]), fmt(r["ndcg10"]), fmt(r["p3"]), fmt(r["mrr"]), fmt(r["spearman"]),
                     lat_str(src8), lat_str(p4) if exists(p4) else "-", peak, fits, ci_str(r)))
    table("RESULTS_TABLE", ["method", "NDCG@5", "NDCG@10", "P@3", "MRR", "Spearman", "ms/query 8 thr (median / max)",
                            "ms/query 4 thr (median / max)", "peak RSS", "fits 20 s", "dNDCG@10 vs det (95% CI)"], rows)

    table("CATEGORY_TABLE", ["method"] + CATS,
          [[label] + [fmt(res[m]["ndcg10_" + c]) for c in CATS] for label, m, *_ in ROWS if m in res])

    # neural models: size, licence, alone, best hybrid
    base_meta = load(CE)["meta"]
    rows = []
    for label, m, p8 in SIGNALS:
        if m not in res:
            continue
        mt = load(p8)["meta"] if exists(p8) else {}
        hyb = [h for h in res if re.fullmatch(r"hyb_[a-z]+[0-9.w_]*_" + re.escape(m), h) and not h.startswith("hyb_main")]
        best = max(hyb, key=lambda h: res[h]["ndcg10"]) if hyb else None
        rows.append((label, mt.get("params_m", base_meta["params_m"]),
                     ("%d" % round(mt["download_mb"])) if mt.get("download_mb") else "-",
                     mt.get("licence", base_meta["licence"] + " (base)"), fmt(res[m]["ndcg10"]),
                     ("%s (%s)" % (fmt(res[best]["ndcg10"]), load(best)["meta"]["kind"])) if best else "-"))
    table("NEURAL_TABLE", ["model", "params (M)", "HF download (MB, all formats)", "licence", "NDCG@10 alone",
                           "best hybrid NDCG@10 (kind)"], rows)

    # fine-tuning runs
    rows = []
    zs = res[CE]
    rows.append(("zero-shot (reference)", "-", "-", fmt(zs["ndcg10"]), fmt(zs["spearman"]),
                 fmt(res["hyb_rrblend_w0.5_" + CE]["ndcg10"]), ci_str(res["hyb_rrblend_w0.5_" + CE])))
    for label, m, _ in SIGNALS[5:]:
        if m not in res:
            continue
        runs = load(m)["meta"]["runs"]
        h = "hyb_rrblend_w0.5_" + m
        rows.append((label, " / ".join(str(v["train_pairs"]) for v in runs.values()),
                     " / ".join(str(v["train_seconds"]) for v in runs.values()), fmt(res[m]["ndcg10"]),
                     fmt(res[m]["spearman"]), fmt(res[h]["ndcg10"]) if h in res else "-",
                     ci_str(res[h]) if h in res else ""))
    table("FINETUNE_TABLE", ["MiniLM-L6 cross-encoder", "training pairs (per fold)", "training s (per fold, 16 E-cores)",
                             "NDCG@10 alone", "Spearman alone", "rank blend 0.5 NDCG@10",
                             "blend dNDCG@10 vs det (95% CI)"], rows)

    # learned weights
    lm = load("learned_pairwise")["meta"]
    table("LEARNED_TABLE", ["model", "NDCG@10", "Spearman"],
          [(label, fmt(res[m]["ndcg10"]), fmt(res[m]["spearman"])) for label, m in
           (("hand-set weights (production)", "det"), ("ridge (LOQO)", "learned_ridge"),
            ("pairwise logistic (LOQO)", "learned_pairwise"))])
    table("WEIGHTS_TABLE", ["feature", "hand-set weight", "pairwise logistic on all queries (sum of abs = 1)"],
          [(f, "%.2f" % lm["hand_weights"][f], "%.2f" % lm["pairwise_weights_all_l1norm"][f]) for f in lm["features"]])

    # hybrid grid
    xs = [(l, x) for l, x in HYBRID_X if x in res]
    rows = [["X alone"] + [fmt(res[x]["ndcg10"]) for _, x in xs]]
    for label, kind in HYBRID_KINDS:
        rows.append([label] + [fmt(res["hyb_%s_%s" % (kind, x)]["ndcg10"]) if "hyb_%s_%s" % (kind, x) in res else "-"
                               for _, x in xs])
    table("HYBRID_TABLE", ["hybrid (det = %s)" % fmt(res["det"]["ndcg10"])] + [l for l, _ in xs], rows)

    # selection-corrected
    p = os.path.join(OUT, "select_cv.json")
    if os.path.exists(p):
        with open(p) as f:
            sc = json.load(f)
        table("SELECT_CV_TABLE", ["family", "configurations", "LOQO NDCG@10", "best in sample (NDCG@10)",
                                  "chosen (times out of %d)" % nq],
              [(fam, v["configs"], fmt(v["loqo_ndcg10"]), "%s (%s)" % (v["best_in_sample"], fmt(v["best_in_sample_ndcg10"])),
                ", ".join("%s (%d)" % (m, n) for m, n in v["picked"])) for fam, v in sc.items()])

    # native candidates only
    nat, _ = evaluate([m for _, m in NATIVE if exists(m)], data, native_only=True)
    nn = max(r["queries"] for r in nat.values())
    table("NATIVE_TABLE", ["method (%d queries, native candidates only)" % nn, "NDCG@10", "dNDCG@10 vs det (95% CI)"],
          [(label, fmt(nat[m]["ndcg10"]), ci_str(nat[m])) for label, m in NATIVE if m in nat])

    # latency and memory
    rows = []
    d = lat("det")
    rows.append(("deterministic ranker (Java, cold JVM pass)", "%.1f / %.1f" % d, "same (single-threaded)",
                 "in the KINA JVM", "-", "-", "none"))
    for label, s8, s4, art in LATENCY:
        if not exists(s8):
            continue
        mt = load(s8)["meta"]
        size = ("%.0f MB download" % mt["download_mb"]) if art == "download" and mt.get("download_mb") else \
            ("%.0f MB %s" % (mt["onnx_mb"], mt["onnx"])) if art == "onnx" else "-"
        rows.append((label, lat_str(s8), lat_str(s4), "%d MB" % mt["rss_peak_mb"],
                     ("%.1f" % mt["load_s"]) if "load_s" in mt else "-",
                     ("%.4f" % mt["spearman_vs_torch"]) if "spearman_vs_torch" in mt else "-", size))
    table("LATENCY_TABLE", ["method", "8 threads, median / max ms", "4 threads, median / max ms",
                            "peak RSS (8 threads)", "load s", "Spearman vs PyTorch", "artefact"], rows)


if __name__ == "__main__":
    main()
