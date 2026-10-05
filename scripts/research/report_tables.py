"""Builds the report's main tables (markdown) from the score files and their meta blocks.

    python3 scripts/research/report_tables.py > scripts/research/out/report_tables.md
Latency: the P-core re-measurement (`*_p8`, `*_p4` score files from latency.py) where it exists, otherwise the latency
recorded by the scorer itself (marked with the cores it ran on). Fits-20s: worst query (all candidates, up to 40)
below 20 s at 8 threads.
"""
import json
import os

from common import OUT, SCORES, load_dataset
from evaluate import CATS, evaluate, fmt

ROWS = [
    # (label, method, latency source p8, latency source p4, ram note)
    ("Deterministic ranker (production Java)", "det", "det", None, "in-process, < 1 MB"),
    ("Deterministic ranker, main @ bf1d59b (connector-aware)", "det_main_bf1d59b", None, None, "in-process, < 1 MB"),
    ("Distributor order", "distributor_order", None, None, "-"),
    ("BM25 over the candidate set", "bm25", None, None, "-"),
    ("Learned weights, ridge (LOQO)", "learned_ridge", None, None, "in-process"),
    ("Learned weights, pairwise logistic (LOQO)", "learned_pairwise", None, None, "in-process"),
    ("Laya multilingual noul, JSON state (current)", "laya_ml_noul", "laya_ml_noul_p8", "laya_ml_noul_p4", "laya"),
    ("Laya multilingual score scale", "laya_ml_score", None, None, "laya"),
    ("Laya multilingual choice (4 contrasting options)", "laya_ml_choice", None, None, "laya"),
    ("Laya multilingual 4 per-attribute nouls, mean", "laya_ml_decomp_mean", None, None, "laya"),
    ("Laya multilingual noul, plain-text state", "laya_ml_noul_plain", None, None, "laya"),
    ("Laya multilingual noul, parsed query attributes in state", "laya_ml_noul_parsed", None, None, "laya"),
    ("Laya multilingual noul, max_len 192 / head_max_len 64", "laya_ml_noul_len192", None, None, "laya"),
    ("Laya english noul", "laya_en_noul", None, None, "laya"),
    ("Laya english choice", "laya_en_choice", None, None, "laya"),
    ("Laya typed-decisions noul", "laya_td_noul", "laya_td_noul_p8", "laya_td_noul_p4", "laya"),
    ("Laya typed-decisions choice", "laya_td_choice", None, None, "laya"),
    ("Laya pairwise, 1 round (n/2 pairs), Bradley-Terry", "laya_ml_pair_k1", None, None, "laya"),
    ("Laya pairwise, 2 rounds (n pairs)", "laya_ml_pair_k2", None, None, "laya"),
    ("Laya pairwise, 4 rounds (2n pairs)", "laya_ml_pair_k4", None, None, "laya"),
    ("Laya pairwise, 8 rounds (4n pairs)", "laya_ml_pair_k8", None, None, "laya"),
    ("Laya pairwise round robin of det top 10 (45 pairs)", "laya_ml_pair_top10", None, None, "laya"),
    ("Laya multilingual embeddings, cosine (SDK)", "laya_ml_embed", None, None, "laya"),
    ("Laya multilingual embeddings vs JSON state (SDK)", "laya_ml_embed_json", None, None, "laya"),
    ("Laya predict_shortlist k=10 + choice (SDK)", "laya_ml_shortlist", None, None, "laya"),
    ("Laya multilingual, typed head fine-tuned (2-fold)", "laya_ft_ml_head", None, None, "laya"),
    ("Laya multilingual, typed head fine-tuned on synthetic labels", "laya_ft_ml_head_synth", None, None, "laya"),
    ("Laya multilingual, full fine-tune 3 epochs (2-fold)", "laya_ft_ml_full2", None, None, "laya"),
    ("Hybrid: rank blend 0.7 det / 0.3 Laya full fine-tune", "hyb_rrblend_w0.3_laya_ft_ml_full2", None, None, "laya"),
    ("Bi-encoder all-MiniLM-L6-v2", "minilm_bi_ecore", "minilm_bi_p8", "minilm_bi_p4", "neural"),
    ("Bi-encoder bge-small-en-v1.5", "bge_small_bi_ecore", "bge_small_bi_p8", "bge_small_bi_p4", "neural"),
    ("Cross-encoder ms-marco-MiniLM-L6-v2", "msmarco_minilm_ce_ecore", "msmarco_minilm_ce_p8", "msmarco_minilm_ce_p4",
     "neural"),
    ("Cross-encoder ms-marco-MiniLM-L6-v2, ONNX Runtime fp32", "msmarco_minilm_ce_onnx_t8", "msmarco_minilm_ce_onnx_t8",
     "msmarco_minilm_ce_onnx_t4", "onnx"),
    ("Cross-encoder ms-marco-MiniLM-L6-v2, ONNX Runtime int8", "msmarco_minilm_ce_onnx_int8_t8",
     "msmarco_minilm_ce_onnx_int8_t8", "msmarco_minilm_ce_onnx_int8_t4", "onnx"),
    ("Cross-encoder ms-marco-MiniLM-L12-v2", "msmarco_minilm12_ce_ecore", "msmarco_minilm12_ce_p8",
     "msmarco_minilm12_ce_p4", "neural"),
    ("Cross-encoder bge-reranker-base", "bge_reranker_base_ce_ecore", "bge_reranker_base_ce_p8",
     "bge_reranker_base_ce_p4", "neural"),
    ("MiniLM-L6 CE fine-tuned on real labels (2-fold)", "msmarco_minilm_ce_ft_real", None, None, "neural"),
    ("MiniLM-L6 CE fine-tuned on synthetic labels", "msmarco_minilm_ce_ft_synth", None, None, "neural"),
    ("MiniLM-L6 CE fine-tuned on synthetic + real (2-fold)", "msmarco_minilm_ce_ft_synth_real", None, None, "neural"),
    ("Hybrid: current blend, det + 0.2 x Laya ml noul", "hyb_blend_w0.2_laya_ml_noul", None, None, "laya"),
    ("Hybrid: Laya ml noul only breaks det ties", "hyb_tie_laya_ml_noul", None, None, "laya"),
    ("Hybrid: rank blend 0.7 det / 0.3 Laya td noul", "hyb_rrblend_w0.3_laya_td_noul", None, None, "laya"),
    ("Hybrid: det + 0.2 x MiniLM-L6 CE (production formula)", "hyb_blend_w0.2_msmarco_minilm_ce_ecore", None, None,
     "neural"),
    ("Hybrid: rank blend 0.7 det / 0.3 MiniLM-L6 CE", "hyb_rrblend_w0.3_msmarco_minilm_ce_ecore", None, None, "neural"),
    ("Hybrid: rank blend 0.5 det / 0.5 MiniLM-L6 CE", "hyb_rrblend_w0.5_msmarco_minilm_ce_ecore", None, None, "neural"),
    ("Hybrid: MiniLM-L6 CE re-orders det top 10", "hyb_top10_msmarco_minilm_ce_ecore", None, None, "neural"),
    ("Hybrid: MiniLM-L6 CE inside det bands of 0.05", "hyb_band05_msmarco_minilm_ce_ecore", None, None, "neural"),
    ("Hybrid: MiniLM-L6 CE only breaks det ties", "hyb_tie_msmarco_minilm_ce_ecore", None, None, "neural"),
    ("Hybrid: MiniLM-L6 CE on candidates with det >= 0.5", "hyb_thr05_msmarco_minilm_ce_ecore", None, None, "neural"),
    ("Hybrid: rank blend 0.5 det / 0.5 bge-reranker-base", "hyb_rrblend_w0.5_bge_reranker_base_ce_ecore", None, None,
     "neural"),
    ("Hybrid: rank blend 0.5 det / 0.5 MiniLM-L6 CE fine-tuned synth+real", "hyb_rrblend_w0.5_msmarco_minilm_ce_ft_synth_real",
     None, None, "neural"),
    ("Hybrid: rank blend 0.5 det / 0.5 MiniLM-L6 CE fine-tuned synth only", "hyb_rrblend_w0.5_msmarco_minilm_ce_ft_synth",
     None, None, "neural"),
    ("Hybrid: main det @ bf1d59b, rank blend 0.5 with MiniLM-L6 CE fine-tuned synth+real",
     "hyb_main_bf1d59b_rrblend_w0.5_msmarco_minilm_ce_ft_synth_real", None, None, "neural"),
]


def meta(m):
    p = os.path.join(SCORES, m + ".json")
    if not os.path.exists(p):
        return None, None
    with open(p) as f:
        sf = json.load(f)
    lat = [v["latency_ms"] for v in sf["queries"].values()]
    return sf["meta"], lat


def lat_str(src):
    if not src:
        return "-", "-"
    mt, lat = meta(src)
    if lat is None:
        return "-", "-"
    lat = sorted(lat)
    return "%d" % lat[len(lat) // 2], "%d" % lat[-1]


def ram(m, kind):
    if kind == "laya":
        p = os.path.join(OUT, "latency_laya.json")
        if os.path.exists(p):
            lj = json.load(open(p))
            if m.startswith("laya_td") or "td_noul" in m:
                v = lj.get("laya_td_noul_p8", {}).get("mem_after_mib")
            else:
                v = lj.get("laya_ml_noul_p8", {}).get("mem_after_mib")
            if v:
                return "%.1f GB (server)" % (v / 1024)
        return "about 2 GB (server)"
    if kind in ("neural", "onnx"):
        for cand in (m.replace("_ecore", "_p8"), m):
            mt, _ = meta(cand)
            if mt and mt.get("rss_peak_mb"):
                return "%d MB" % mt["rss_peak_mb"]
    return kind


def main():
    data = load_dataset()
    methods = [r[1] for r in ROWS if os.path.exists(os.path.join(SCORES, r[1] + ".json"))]
    res, _ = evaluate(methods, data)
    print("| method | NDCG@5 | NDCG@10 | P@3 | MRR | Spearman | ms/query 8 thr (median / max) | ms/query 4 thr (median / max) "
          "| peak RAM | fits 20 s | dNDCG@10 vs det (95% CI) |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for label, m, p8, p4, kind in ROWS:
        if m not in res:
            continue
        r = res[m]
        src8 = p8 if p8 and os.path.exists(os.path.join(SCORES, p8 + ".json")) else m
        med8, max8 = lat_str(src8)
        med4, max4 = lat_str(p4) if p4 and os.path.exists(os.path.join(SCORES, p4 + ".json")) else ("-", "-")
        fits = "yes" if max8 != "-" and float(max8) < 20000 else ("-" if max8 == "-" else "no")
        ci = r.get("ndcg10_vs_det_ci95")
        star = "" if src8 != m or kind not in ("neural", "laya") or m.startswith("laya_ml_pair") else ""
        print("| %s | %s | %s | %s | %s | %s | %s / %s%s | %s / %s | %s | %s | %s |" % (
            label, fmt(r["ndcg5"]), fmt(r["ndcg10"]), fmt(r["p3"]), fmt(r["mrr"]), fmt(r["spearman"]),
            med8, max8, star, med4, max4, ram(m, kind), fits, ("[%+.3f, %+.3f]" % ci) if ci else ""))
    print()
    print("| method | " + " | ".join(CATS) + " |")
    print("|---|" + "---|" * len(CATS))
    for label, m, *_ in ROWS:
        if m in res:
            print("| %s | %s |" % (label, " | ".join(fmt(res[m]["ndcg10_" + c]) for c in CATS)))


if __name__ == "__main__":
    main()
