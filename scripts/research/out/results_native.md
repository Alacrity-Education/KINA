### Results (native candidate sets, 27 queries)

| method | NDCG@5 | NDCG@10 | P@3 | MRR | Spearman | latency/query ms (mean / max) | dNDCG@10 vs det, 95% CI |
|---|---|---|---|---|---|---|---|
| hyb_blend_w0.5_msmarco_minilm_ce_ft_synth | 0.954 | 0.962 | 0.926 | 0.968 | 0.345 | 153 / 372 | [-0.000, +0.034] |
| msmarco_minilm_ce_ft_synth | 0.949 | 0.958 | 0.901 | 0.950 | 0.342 | 151 / 371 | [-0.006, +0.033] |
| hyb_blend_w0.3_msmarco_minilm_ce_ft_synth | 0.955 | 0.957 | 0.926 | 0.968 | 0.334 | 153 / 372 | [-0.004, +0.029] |
| hyb_blend_w0.3_msmarco_minilm_ce_ft_synth_real | 0.965 | 0.956 | 0.938 | 0.970 | 0.339 | 152 / 340 | [-0.001, +0.023] |
| hyb_main_bf1d59b_blend_w0.3_msmarco_minilm_ce_ft_synth_real | 0.965 | 0.956 | 0.938 | 0.970 | 0.339 | 157 / 347 | [-0.001, +0.023] |
| hyb_blend_w0.2_msmarco_minilm_ce_ft_synth | 0.956 | 0.956 | 0.938 | 0.968 | 0.327 | 153 / 372 | [-0.004, +0.028] |
| hyb_blend_w0.2_msmarco_minilm_ce_ft_synth_real | 0.964 | 0.955 | 0.938 | 0.970 | 0.331 | 152 / 340 | [-0.001, +0.023] |
| hyb_main_bf1d59b_blend_w0.2_msmarco_minilm_ce_ft_synth_real | 0.964 | 0.955 | 0.938 | 0.970 | 0.331 | 157 / 347 | [-0.001, +0.023] |
| hyb_blend_w0.1_msmarco_minilm_ce_ft_synth | 0.960 | 0.955 | 0.938 | 0.968 | 0.320 | 153 / 372 | [-0.004, +0.028] |
| hyb_rrblend_w0.5_msmarco_minilm_ce_ft_synth | 0.960 | 0.955 | 0.938 | 0.966 | 0.335 | 153 / 372 | [-0.001, +0.020] |
| hyb_band05_msmarco_minilm_ce_ft_synth | 0.955 | 0.954 | 0.926 | 0.968 | 0.310 | 153 / 372 | [-0.007, +0.027] |
| hyb_band05_minilm_bi_ecore | 0.954 | 0.953 | 0.926 | 0.954 | 0.308 | 284 / 493 | [+0.002, +0.015] |
| hyb_rrblend_w0.5_bge_reranker_base_ce_ecore | 0.950 | 0.953 | 0.926 | 0.965 | 0.321 | 2047 / 3286 | [-0.009, +0.023] |
| hyb_blend_w0.1_msmarco_minilm_ce_ft_synth_real | 0.967 | 0.953 | 0.938 | 0.970 | 0.321 | 152 / 340 | [-0.004, +0.021] |
| hyb_main_bf1d59b_blend_w0.1_msmarco_minilm_ce_ft_synth_real | 0.967 | 0.953 | 0.938 | 0.970 | 0.322 | 157 / 347 | [-0.004, +0.021] |
| hyb_blend_w0.5_msmarco_minilm_ce_ft_synth_real | 0.945 | 0.953 | 0.901 | 0.940 | 0.348 | 152 / 340 | [-0.011, +0.025] |
| hyb_main_bf1d59b_blend_w0.5_msmarco_minilm_ce_ft_synth_real | 0.945 | 0.953 | 0.901 | 0.940 | 0.348 | 157 / 347 | [-0.011, +0.025] |
| hyb_blend_w0.3_msmarco_minilm12_ce_ecore | 0.951 | 0.953 | 0.914 | 0.966 | 0.325 | 595 / 1047 | [-0.010, +0.028] |
| hyb_band05_msmarco_minilm_ce_ft_synth_real | 0.962 | 0.953 | 0.938 | 0.970 | 0.310 | 152 / 340 | [-0.006, +0.021] |
| hyb_main_bf1d59b_band05_msmarco_minilm_ce_ft_synth_real | 0.962 | 0.953 | 0.938 | 0.970 | 0.315 | 157 / 347 | [-0.006, +0.021] |
| hyb_rrblend_w0.5_minilm_bi_ecore | 0.943 | 0.953 | 0.914 | 0.946 | 0.324 | 284 / 493 | [-0.003, +0.019] |
| hyb_main_bf1d59b_rrblend_w0.5_msmarco_minilm_ce_ft_synth_real | 0.952 | 0.952 | 0.938 | 0.966 | 0.336 | 157 / 347 | [-0.002, +0.016] |
| hyb_rrblend_w0.5_msmarco_minilm_ce_ft_synth_real | 0.952 | 0.952 | 0.938 | 0.966 | 0.334 | 152 / 340 | [-0.002, +0.016] |
| hyb_blend_w0.2_minilm_bi_ecore | 0.954 | 0.952 | 0.926 | 0.954 | 0.323 | 284 / 493 | [+0.001, +0.013] |
| hyb_blend_w0.3_minilm_bi_ecore | 0.954 | 0.952 | 0.926 | 0.954 | 0.328 | 284 / 493 | [-0.000, +0.013] |
| hyb_blend_w0.1_minilm_bi_ecore | 0.952 | 0.952 | 0.926 | 0.948 | 0.317 | 284 / 493 | [+0.000, +0.013] |
| hyb_blend_w0.5_minilm_bi_ecore | 0.952 | 0.951 | 0.914 | 0.954 | 0.327 | 284 / 493 | [-0.006, +0.018] |
| hyb_blend_w0.2_msmarco_minilm12_ce_ecore | 0.952 | 0.950 | 0.914 | 0.966 | 0.314 | 595 / 1047 | [-0.012, +0.024] |
| hyb_main_bf1d59b_rrblend_w0.5_msmarco_minilm_ce_ecore | 0.955 | 0.950 | 0.938 | 0.947 | 0.340 | 310 / 538 | [-0.006, +0.016] |
| hyb_rrblend_w0.5_msmarco_minilm_ce_ecore | 0.955 | 0.950 | 0.938 | 0.947 | 0.336 | 305 / 533 | [-0.006, +0.016] |
| hyb_rrblend_w0.3_msmarco_minilm_ce_ft_synth | 0.949 | 0.950 | 0.938 | 0.948 | 0.313 | 153 / 372 | [-0.001, +0.011] |
| hyb_rrblend_w0.3_bge_reranker_base_ce_ecore | 0.956 | 0.950 | 0.926 | 0.965 | 0.307 | 2047 / 3286 | [-0.003, +0.011] |
| hyb_blend_w0.1_msmarco_minilm12_ce_ecore | 0.957 | 0.949 | 0.938 | 0.966 | 0.310 | 595 / 1047 | [-0.011, +0.022] |
| hyb_blend_w0.5_msmarco_minilm12_ce_ecore | 0.946 | 0.949 | 0.914 | 0.947 | 0.333 | 595 / 1047 | [-0.017, +0.026] |
| hyb_band05_msmarco_minilm12_ce_ecore | 0.954 | 0.949 | 0.914 | 0.966 | 0.298 | 595 / 1047 | [-0.016, +0.025] |
| hyb_blend_w0.3_msmarco_minilm_ce_ft_real | 0.950 | 0.948 | 0.938 | 0.948 | 0.329 | 341 / 580 | [-0.008, +0.013] |
| hyb_rrblend_w0.2_bge_reranker_base_ce_ecore | 0.954 | 0.948 | 0.926 | 0.965 | 0.298 | 2047 / 3286 | [+0.000, +0.006] |
| msmarco_minilm_ce_ft_synth_real | 0.944 | 0.948 | 0.901 | 0.939 | 0.338 | 150 / 335 | [-0.020, +0.022] |
| hyb_rrblend_w0.3_minilm_bi_ecore | 0.943 | 0.948 | 0.914 | 0.948 | 0.303 | 284 / 493 | [-0.006, +0.011] |
| hyb_rrblend_w0.5_msmarco_minilm12_ce_ecore | 0.949 | 0.947 | 0.926 | 0.966 | 0.326 | 595 / 1047 | [-0.009, +0.013] |
| hyb_band05_msmarco_minilm_ce_ft_real | 0.947 | 0.947 | 0.938 | 0.948 | 0.299 | 341 / 580 | [-0.009, +0.012] |
| hyb_blend_w0.3_msmarco_minilm_ce_ecore | 0.951 | 0.947 | 0.926 | 0.936 | 0.334 | 305 / 533 | [-0.011, +0.013] |
| hyb_main_bf1d59b_blend_w0.3_msmarco_minilm_ce_ecore | 0.951 | 0.947 | 0.926 | 0.936 | 0.334 | 310 / 538 | [-0.011, +0.013] |
| hyb_blend_w0.5_laya_ft_ml_full2 | 0.946 | 0.947 | 0.926 | 0.957 | 0.316 | 2014 / 2469 | [-0.028, +0.028] |
| hyb_blend_w0.1_msmarco_minilm_ce_ft_real | 0.947 | 0.947 | 0.938 | 0.948 | 0.311 | 341 / 580 | [-0.010, +0.012] |
| hyb_top10_msmarco_minilm_ce_ft_synth | 0.954 | 0.947 | 0.938 | 0.965 | 0.280 | 40 / 94 | [-0.004, +0.006] |
| hyb_rrblend_w0.2_laya_ml_score | 0.960 | 0.947 | 0.926 | 0.965 | 0.280 | 2847 / 3793 | [-0.005, +0.007] |
| hyb_blend_w0.5_msmarco_minilm_ce_ecore | 0.948 | 0.947 | 0.914 | 0.936 | 0.344 | 305 / 533 | [-0.014, +0.015] |
| hyb_main_bf1d59b_blend_w0.5_msmarco_minilm_ce_ecore | 0.948 | 0.947 | 0.914 | 0.936 | 0.344 | 310 / 538 | [-0.014, +0.015] |
| hyb_blend_w0.1_laya_ft_ml_full2 | 0.956 | 0.946 | 0.938 | 0.963 | 0.306 | 2014 / 2469 | [-0.021, +0.022] |
| hyb_main_bf1d59b_top10_msmarco_minilm_ce_ecore | 0.954 | 0.946 | 0.938 | 0.965 | 0.291 | 83 / 139 | [-0.005, +0.005] |
| hyb_top10_msmarco_minilm_ce_ecore | 0.954 | 0.946 | 0.938 | 0.965 | 0.279 | 78 / 134 | [-0.005, +0.005] |
| hyb_rrblend_w0.1_bge_reranker_base_ce_ecore | 0.954 | 0.946 | 0.914 | 0.966 | 0.292 | 2047 / 3286 | [+0.000, +0.001] |
| hyb_main_bf1d59b_top10_msmarco_minilm_ce_ft_synth_real | 0.954 | 0.946 | 0.938 | 0.965 | 0.290 | 45 / 95 | [-0.005, +0.005] |
| hyb_top10_msmarco_minilm_ce_ft_synth_real | 0.954 | 0.946 | 0.938 | 0.965 | 0.279 | 39 / 88 | [-0.005, +0.005] |
| hyb_tie_bge_reranker_base_ce_ecore | 0.954 | 0.946 | 0.914 | 0.965 | 0.287 | 2047 / 3286 | [+0.000, +0.001] |
| hyb_tie_laya_ml_score | 0.954 | 0.946 | 0.914 | 0.965 | 0.277 | 2847 / 3793 | [+0.000, +0.001] |
| hyb_band05_bge_small_bi_ecore | 0.947 | 0.946 | 0.914 | 0.965 | 0.279 | 546 / 959 | [-0.013, +0.013] |
| hyb_rrblend_w0.2_minilm_bi_ecore | 0.946 | 0.946 | 0.914 | 0.948 | 0.296 | 284 / 493 | [-0.006, +0.007] |
| hyb_blend_w0.2_msmarco_minilm_ce_ecore | 0.956 | 0.946 | 0.926 | 0.936 | 0.327 | 305 / 533 | [-0.012, +0.011] |
| hyb_main_bf1d59b_blend_w0.2_msmarco_minilm_ce_ecore | 0.956 | 0.946 | 0.926 | 0.936 | 0.327 | 310 / 538 | [-0.012, +0.011] |
| hyb_top10_msmarco_minilm_ce_ft_real | 0.957 | 0.946 | 0.938 | 0.965 | 0.279 | 87 / 147 | [-0.007, +0.005] |
| det | 0.953 | 0.946 | 0.914 | 0.965 | 0.275 | 2 / 7 |  |
| det_main_bf1d59b | 0.953 | 0.946 | 0.914 | 0.965 | 0.287 | 7 / 14 | [+0.000, +0.000] |
| hyb_main_bf1d59b_thr05_msmarco_minilm_ce_ft_synth_real | 0.953 | 0.946 | 0.914 | 0.965 | 0.288 | 30 / 109 | [+0.000, +0.000] |
| hyb_thr05_bge_reranker_base_ce_ecore | 0.953 | 0.946 | 0.914 | 0.965 | 0.275 | 310 / 1349 | [+0.000, +0.000] |
| hyb_thr05_msmarco_minilm12_ce_ecore | 0.953 | 0.946 | 0.914 | 0.965 | 0.275 | 94 / 409 | [+0.000, +0.000] |
| hyb_thr05_msmarco_minilm_ce_ft_synth | 0.953 | 0.946 | 0.914 | 0.965 | 0.276 | 24 / 102 | [+0.000, +0.000] |
| hyb_thr05_msmarco_minilm_ce_ft_synth_real | 0.953 | 0.946 | 0.914 | 0.965 | 0.276 | 24 / 102 | [+0.000, +0.000] |
| hyb_rrblend_w0.5_bge_small_bi_ecore | 0.934 | 0.946 | 0.901 | 0.947 | 0.288 | 546 / 959 | [-0.013, +0.012] |
| hyb_blend_w0.2_msmarco_minilm_ce_ft_real | 0.949 | 0.946 | 0.938 | 0.948 | 0.322 | 341 / 580 | [-0.011, +0.011] |
| hyb_top10_msmarco_minilm12_ce_ecore | 0.953 | 0.945 | 0.938 | 0.965 | 0.277 | 150 / 263 | [-0.007, +0.004] |
| hyb_rrblend_w0.1_laya_ml_score | 0.955 | 0.945 | 0.926 | 0.965 | 0.278 | 2847 / 3793 | [-0.006, +0.004] |
| hyb_blend_w0.5_msmarco_minilm_ce_ft_real | 0.935 | 0.945 | 0.901 | 0.916 | 0.337 | 341 / 580 | [-0.018, +0.015] |
| hyb_main_bf1d59b_rrblend_w0.3_msmarco_minilm_ce_ecore | 0.951 | 0.945 | 0.926 | 0.938 | 0.324 | 310 / 538 | [-0.009, +0.008] |
| hyb_rrblend_w0.3_msmarco_minilm_ce_ecore | 0.951 | 0.945 | 0.926 | 0.938 | 0.316 | 305 / 533 | [-0.009, +0.008] |
| hyb_rrblend_w0.2_bge_small_bi_ecore | 0.948 | 0.945 | 0.914 | 0.928 | 0.291 | 546 / 959 | [-0.007, +0.005] |
| hyb_rrblend_w0.1_bge_small_bi_ecore | 0.951 | 0.945 | 0.914 | 0.947 | 0.286 | 546 / 959 | [-0.004, +0.001] |
| hyb_blend_w0.1_bge_reranker_base_ce_ecore | 0.948 | 0.945 | 0.914 | 0.933 | 0.298 | 2047 / 3286 | [-0.013, +0.008] |
| hyb_blend_w0.3_laya_ft_ml_full2 | 0.950 | 0.945 | 0.926 | 0.957 | 0.317 | 2014 / 2469 | [-0.031, +0.025] |
| hyb_rrblend_w0.3_msmarco_minilm_ce_ft_real | 0.949 | 0.944 | 0.938 | 0.941 | 0.308 | 341 / 580 | [-0.011, +0.008] |
| hyb_blend_w0.2_bge_reranker_base_ce_ecore | 0.946 | 0.944 | 0.901 | 0.933 | 0.313 | 2047 / 3286 | [-0.015, +0.011] |
| hyb_tie_bge_small_bi_ecore | 0.951 | 0.944 | 0.914 | 0.947 | 0.278 | 546 / 959 | [-0.005, +0.000] |
| hyb_tie_minilm_bi_ecore | 0.951 | 0.944 | 0.914 | 0.947 | 0.284 | 284 / 493 | [-0.005, +0.000] |
| hyb_main_bf1d59b_rrblend_w0.1_msmarco_minilm_ce_ecore | 0.952 | 0.944 | 0.914 | 0.941 | 0.306 | 310 / 538 | [-0.007, +0.002] |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ecore | 0.952 | 0.944 | 0.914 | 0.941 | 0.295 | 305 / 533 | [-0.007, +0.002] |
| hyb_rrblend_w0.2_laya_ml_pair_k8 | 0.939 | 0.944 | 0.901 | 0.935 | 0.279 | 16011 / 20553 | [-0.014, +0.007] |
| hyb_band05_laya_ft_ml_full2 | 0.951 | 0.944 | 0.938 | 0.957 | 0.296 | 2014 / 2469 | [-0.023, +0.017] |
| hyb_blend_w0.1_msmarco_minilm_ce_ecore | 0.952 | 0.944 | 0.926 | 0.938 | 0.317 | 305 / 533 | [-0.014, +0.010] |
| hyb_main_bf1d59b_blend_w0.1_msmarco_minilm_ce_ecore | 0.952 | 0.944 | 0.926 | 0.938 | 0.320 | 310 / 538 | [-0.014, +0.010] |
| hyb_main_bf1d59b_thr05_msmarco_minilm_ce_ecore | 0.953 | 0.944 | 0.914 | 0.965 | 0.286 | 57 / 221 | [-0.006, +0.000] |
| hyb_thr05_msmarco_minilm_ce_ecore | 0.953 | 0.944 | 0.914 | 0.965 | 0.274 | 49 / 214 | [-0.006, +0.000] |
| hyb_rrblend_w0.2_msmarco_minilm_ce_ft_real | 0.946 | 0.943 | 0.926 | 0.935 | 0.298 | 341 / 580 | [-0.013, +0.007] |
| hyb_rrblend_w0.2_laya_ft_ml_full2 | 0.939 | 0.943 | 0.914 | 0.941 | 0.294 | 2014 / 2469 | [-0.014, +0.007] |
| hyb_thr05_msmarco_minilm_ce_ft_real | 0.953 | 0.943 | 0.914 | 0.965 | 0.273 | 55 / 312 | [-0.007, +0.000] |
| hyb_rrblend_w0.3_bge_small_bi_ecore | 0.949 | 0.943 | 0.901 | 0.947 | 0.291 | 546 / 959 | [-0.007, +0.002] |
| hyb_rrblend_w0.1_msmarco_minilm12_ce_ecore | 0.950 | 0.943 | 0.914 | 0.941 | 0.293 | 595 / 1047 | [-0.007, +0.000] |
| hyb_main_bf1d59b_tie_msmarco_minilm_ce_ecore | 0.950 | 0.943 | 0.914 | 0.941 | 0.300 | 310 / 538 | [-0.008, +0.000] |
| hyb_tie_msmarco_minilm12_ce_ecore | 0.950 | 0.943 | 0.914 | 0.941 | 0.286 | 595 / 1047 | [-0.008, +0.000] |
| hyb_tie_msmarco_minilm_ce_ecore | 0.950 | 0.943 | 0.914 | 0.941 | 0.288 | 305 / 533 | [-0.008, +0.000] |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ft_real | 0.951 | 0.943 | 0.914 | 0.941 | 0.291 | 341 / 580 | [-0.010, +0.002] |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ft_synth | 0.951 | 0.943 | 0.914 | 0.938 | 0.295 | 153 / 372 | [-0.011, +0.003] |
| hyb_tie_laya_ml_embed | 0.950 | 0.943 | 0.914 | 0.947 | 0.271 | 2822 / 4600 | [-0.008, +0.001] |
| hyb_tie_laya_ft_ml_full2 | 0.949 | 0.943 | 0.914 | 0.947 | 0.288 | 2014 / 2469 | [-0.008, +0.000] |
| hyb_tie_laya_td_noul | 0.949 | 0.943 | 0.914 | 0.947 | 0.281 | 7003 / 8512 | [-0.008, +0.000] |
| hyb_rrblend_w0.1_laya_ml_pair_k8 | 0.940 | 0.943 | 0.914 | 0.941 | 0.280 | 16011 / 20553 | [-0.011, +0.003] |
| hyb_rrblend_w0.1_minilm_bi_ecore | 0.950 | 0.943 | 0.914 | 0.947 | 0.292 | 284 / 493 | [-0.008, +0.000] |
| hyb_band05_msmarco_minilm_ce_ecore | 0.951 | 0.943 | 0.926 | 0.936 | 0.304 | 305 / 533 | [-0.016, +0.009] |
| hyb_main_bf1d59b_band05_msmarco_minilm_ce_ecore | 0.951 | 0.943 | 0.926 | 0.936 | 0.309 | 310 / 538 | [-0.016, +0.009] |
| hyb_rrblend_w0.1_laya_ml_embed | 0.952 | 0.943 | 0.914 | 0.941 | 0.274 | 2822 / 4600 | [-0.012, +0.003] |
| hyb_top10_laya_td_noul | 0.950 | 0.943 | 0.914 | 0.936 | 0.274 | 1752 / 2133 | [-0.013, +0.003] |
| hyb_rrblend_w0.5_msmarco_minilm_ce_ft_real | 0.951 | 0.943 | 0.938 | 0.941 | 0.330 | 341 / 580 | [-0.018, +0.011] |
| hyb_main_bf1d59b_rrblend_w0.1_msmarco_minilm_ce_ft_synth_real | 0.949 | 0.943 | 0.914 | 0.938 | 0.305 | 157 / 347 | [-0.013, +0.002] |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ft_synth_real | 0.949 | 0.943 | 0.914 | 0.938 | 0.293 | 152 / 340 | [-0.013, +0.002] |
| hyb_blend_w0.3_bge_reranker_base_ce_ecore | 0.941 | 0.943 | 0.901 | 0.933 | 0.320 | 2047 / 3286 | [-0.016, +0.007] |
| hyb_rrblend_w0.2_laya_td_noul | 0.945 | 0.943 | 0.901 | 0.938 | 0.291 | 7003 / 8512 | [-0.014, +0.005] |
| hyb_blend_w0.1_bge_small_bi_ecore | 0.947 | 0.942 | 0.914 | 0.947 | 0.288 | 546 / 959 | [-0.017, +0.007] |
| hyb_rrblend_w0.1_laya_ft_ml_full2 | 0.945 | 0.942 | 0.914 | 0.947 | 0.292 | 2014 / 2469 | [-0.010, +0.000] |
| hyb_main_bf1d59b_tie_laya_ml_noul | 0.948 | 0.942 | 0.914 | 0.941 | 0.290 | 2457 / 3147 | [-0.010, +0.000] |
| hyb_main_bf1d59b_tie_msmarco_minilm_ce_ft_synth_real | 0.948 | 0.942 | 0.914 | 0.941 | 0.300 | 157 / 347 | [-0.010, +0.000] |
| hyb_tie_laya_ml_noul | 0.948 | 0.942 | 0.914 | 0.941 | 0.279 | 2452 / 3140 | [-0.010, +0.000] |
| hyb_tie_laya_ml_pair_k8 | 0.948 | 0.942 | 0.914 | 0.941 | 0.274 | 16011 / 20553 | [-0.010, +0.000] |
| hyb_tie_msmarco_minilm_ce_ft_real | 0.948 | 0.942 | 0.914 | 0.941 | 0.285 | 341 / 580 | [-0.010, +0.000] |
| hyb_tie_msmarco_minilm_ce_ft_synth | 0.948 | 0.942 | 0.914 | 0.941 | 0.288 | 153 / 372 | [-0.010, +0.000] |
| hyb_tie_msmarco_minilm_ce_ft_synth_real | 0.948 | 0.942 | 0.914 | 0.941 | 0.288 | 152 / 340 | [-0.010, +0.000] |
| hyb_band05_bge_reranker_base_ce_ecore | 0.946 | 0.942 | 0.914 | 0.933 | 0.288 | 2047 / 3286 | [-0.016, +0.006] |
| hyb_band05_laya_ml_pair_k8 | 0.944 | 0.942 | 0.914 | 0.936 | 0.280 | 16011 / 20553 | [-0.025, +0.018] |
| hyb_blend_w0.2_laya_ft_ml_full2 | 0.951 | 0.942 | 0.926 | 0.957 | 0.312 | 2014 / 2469 | [-0.034, +0.021] |
| hyb_thr05_minilm_bi_ecore | 0.946 | 0.942 | 0.901 | 0.965 | 0.263 | 46 / 204 | [-0.012, +0.000] |
| hyb_rrblend_w0.3_msmarco_minilm12_ce_ecore | 0.945 | 0.942 | 0.914 | 0.941 | 0.308 | 595 / 1047 | [-0.013, +0.005] |
| hyb_top10_bge_reranker_base_ce_ecore | 0.950 | 0.941 | 0.938 | 0.928 | 0.276 | 513 / 823 | [-0.010, +0.001] |
| hyb_main_bf1d59b_rrblend_w0.3_msmarco_minilm_ce_ft_synth_real | 0.949 | 0.941 | 0.926 | 0.938 | 0.318 | 157 / 347 | [-0.019, +0.007] |
| hyb_rrblend_w0.3_msmarco_minilm_ce_ft_synth_real | 0.949 | 0.941 | 0.926 | 0.938 | 0.309 | 152 / 340 | [-0.019, +0.007] |
| hyb_rrblend_w0.5_laya_ft_ml_full2 | 0.930 | 0.941 | 0.901 | 0.938 | 0.320 | 2014 / 2469 | [-0.035, +0.019] |
| hyb_main_bf1d59b_rrblend_w0.2_msmarco_minilm_ce_ft_synth_real | 0.945 | 0.941 | 0.914 | 0.935 | 0.312 | 157 / 347 | [-0.016, +0.005] |
| hyb_rrblend_w0.2_msmarco_minilm_ce_ft_synth_real | 0.945 | 0.941 | 0.914 | 0.935 | 0.302 | 152 / 340 | [-0.016, +0.005] |
| hyb_rrblend_w0.3_laya_ml_score | 0.948 | 0.941 | 0.938 | 0.941 | 0.282 | 2847 / 3793 | [-0.016, +0.005] |
| hyb_main_bf1d59b_rrblend_w0.1_laya_ml_noul | 0.949 | 0.941 | 0.914 | 0.941 | 0.295 | 2457 / 3147 | [-0.014, +0.002] |
| hyb_rrblend_w0.1_laya_ml_noul | 0.949 | 0.941 | 0.914 | 0.941 | 0.282 | 2452 / 3140 | [-0.014, +0.002] |
| hyb_blend_w0.2_bge_small_bi_ecore | 0.942 | 0.941 | 0.914 | 0.965 | 0.292 | 546 / 959 | [-0.025, +0.010] |
| hyb_blend_w0.3_bge_small_bi_ecore | 0.942 | 0.941 | 0.914 | 0.965 | 0.295 | 546 / 959 | [-0.025, +0.010] |
| hyb_top10_bge_small_bi_ecore | 0.939 | 0.941 | 0.914 | 0.947 | 0.268 | 138 / 241 | [-0.014, +0.002] |
| hyb_main_bf1d59b_rrblend_w0.2_msmarco_minilm_ce_ecore | 0.946 | 0.941 | 0.914 | 0.935 | 0.313 | 310 / 538 | [-0.012, +0.001] |
| hyb_rrblend_w0.2_msmarco_minilm_ce_ecore | 0.946 | 0.941 | 0.914 | 0.935 | 0.304 | 305 / 533 | [-0.012, +0.001] |
| hyb_thr05_laya_ft_ml_full2 | 0.944 | 0.941 | 0.889 | 0.965 | 0.265 | 302 / 1260 | [-0.015, +0.000] |
| hyb_rrblend_w0.1_laya_td_noul | 0.947 | 0.940 | 0.914 | 0.941 | 0.285 | 7003 / 8512 | [-0.015, +0.000] |
| hyb_rrblend_w0.2_msmarco_minilm12_ce_ecore | 0.939 | 0.940 | 0.901 | 0.935 | 0.299 | 595 / 1047 | [-0.013, +0.000] |
| hyb_blend_w0.1_laya_ml_pair_k8 | 0.944 | 0.940 | 0.914 | 0.936 | 0.281 | 16011 / 20553 | [-0.026, +0.016] |
| hyb_top10_minilm_bi_ecore | 0.941 | 0.940 | 0.914 | 0.941 | 0.268 | 72 / 124 | [-0.014, +0.001] |
| hyb_thr05_bge_small_bi_ecore | 0.942 | 0.940 | 0.889 | 0.947 | 0.261 | 87 / 374 | [-0.017, +0.000] |
| hyb_rrblend_w0.3_laya_td_noul | 0.943 | 0.939 | 0.914 | 0.934 | 0.293 | 7003 / 8512 | [-0.020, +0.004] |
| hyb_rrblend_w0.2_msmarco_minilm_ce_ft_synth | 0.944 | 0.939 | 0.914 | 0.935 | 0.302 | 153 / 372 | [-0.017, +0.001] |
| bm25 | 0.932 | 0.939 | 0.889 | 0.917 | 0.301 | 0 / 1 | [-0.034, +0.014] |
| hyb_blend_w0.1_laya_ml_score | 0.935 | 0.939 | 0.926 | 0.948 | 0.277 | 2847 / 3793 | [-0.024, +0.008] |
| hyb_rrblend_w0.3_laya_ml_pair_k8 | 0.939 | 0.939 | 0.901 | 0.934 | 0.273 | 16011 / 20553 | [-0.024, +0.007] |
| hyb_top10_laya_ft_ml_full2 | 0.930 | 0.939 | 0.901 | 0.936 | 0.274 | 505 / 618 | [-0.021, +0.003] |
| hyb_main_bf1d59b_blend_w0.1_laya_ml_noul | 0.945 | 0.939 | 0.926 | 0.929 | 0.293 | 2457 / 3147 | [-0.024, +0.008] |
| hyb_rrblend_w0.2_laya_ml_embed | 0.942 | 0.938 | 0.901 | 0.917 | 0.268 | 2822 / 4600 | [-0.018, +0.001] |
| hyb_main_bf1d59b_rrblend_w0.2_laya_ml_noul | 0.938 | 0.938 | 0.914 | 0.923 | 0.294 | 2457 / 3147 | [-0.019, +0.002] |
| hyb_main_bf1d59b_band05_laya_ml_noul | 0.935 | 0.938 | 0.901 | 0.930 | 0.280 | 2457 / 3147 | [-0.028, +0.008] |
| hyb_top10_laya_ml_score | 0.940 | 0.938 | 0.889 | 0.919 | 0.270 | 713 / 950 | [-0.023, +0.003] |
| hyb_blend_w0.5_bge_reranker_base_ce_ecore | 0.929 | 0.938 | 0.877 | 0.887 | 0.320 | 2047 / 3286 | [-0.027, +0.010] |
| hyb_band05_laya_ml_embed | 0.937 | 0.937 | 0.926 | 0.931 | 0.261 | 2822 / 4600 | [-0.027, +0.005] |
| msmarco_minilm_ce_onnx_int8_t4 | 0.928 | 0.936 | 0.877 | 0.920 | 0.314 | 150 / 288 | [-0.033, +0.010] |
| msmarco_minilm_ce_onnx_int8_t8 | 0.928 | 0.936 | 0.877 | 0.920 | 0.314 | 97 / 200 | [-0.033, +0.010] |
| hyb_rrblend_w0.3_laya_ft_ml_full2 | 0.935 | 0.936 | 0.914 | 0.939 | 0.304 | 2014 / 2469 | [-0.030, +0.006] |
| hyb_blend_w0.1_laya_ml_embed | 0.935 | 0.936 | 0.914 | 0.931 | 0.270 | 2822 / 4600 | [-0.028, +0.003] |
| hyb_top10_laya_ml_pair_k8 | 0.934 | 0.936 | 0.889 | 0.928 | 0.268 | 4004 / 5140 | [-0.027, +0.002] |
| hyb_blend_w0.2_laya_td_noul | 0.935 | 0.936 | 0.914 | 0.937 | 0.296 | 7003 / 8512 | [-0.043, +0.015] |
| msmarco_minilm12_ce_ecore | 0.925 | 0.935 | 0.877 | 0.934 | 0.307 | 593 / 1046 | [-0.042, +0.019] |
| msmarco_minilm12_ce_p4 | 0.925 | 0.935 | 0.877 | 0.934 | 0.307 | 570 / 999 | [-0.042, +0.019] |
| msmarco_minilm12_ce_p8 | 0.925 | 0.935 | 0.877 | 0.934 | 0.307 | 319 / 555 | [-0.042, +0.019] |
| hyb_rrblend_w0.2_laya_ml_noul | 0.938 | 0.935 | 0.914 | 0.923 | 0.281 | 2452 / 3140 | [-0.022, +0.000] |
| hyb_blend_w0.1_laya_td_noul | 0.937 | 0.935 | 0.901 | 0.937 | 0.298 | 7003 / 8512 | [-0.043, +0.014] |
| hyb_band05_laya_td_noul | 0.935 | 0.935 | 0.914 | 0.937 | 0.292 | 7003 / 8512 | [-0.044, +0.014] |
| msmarco_minilm_ce_ft_real | 0.930 | 0.935 | 0.889 | 0.915 | 0.318 | 339 / 578 | [-0.037, +0.010] |
| hyb_main_bf1d59b_blend_w0.2_laya_ml_noul | 0.938 | 0.934 | 0.914 | 0.929 | 0.293 | 2457 / 3147 | [-0.028, +0.005] |
| msmarco_minilm_ce_ecore | 0.929 | 0.934 | 0.877 | 0.907 | 0.322 | 303 / 531 | [-0.039, +0.011] |
| msmarco_minilm_ce_onnx_t4 | 0.929 | 0.934 | 0.877 | 0.907 | 0.322 | 297 / 534 | [-0.039, +0.011] |
| msmarco_minilm_ce_onnx_t8 | 0.929 | 0.934 | 0.877 | 0.907 | 0.322 | 183 / 335 | [-0.039, +0.011] |
| msmarco_minilm_ce_p4 | 0.929 | 0.934 | 0.877 | 0.907 | 0.322 | 290 / 512 | [-0.039, +0.011] |
| msmarco_minilm_ce_p8 | 0.929 | 0.934 | 0.877 | 0.907 | 0.322 | 160 / 283 | [-0.039, +0.011] |
| hyb_rrblend_w0.3_laya_ml_embed | 0.936 | 0.934 | 0.901 | 0.898 | 0.260 | 2822 / 4600 | [-0.032, +0.004] |
| hyb_main_bf1d59b_rrblend_w0.3_laya_ml_noul | 0.934 | 0.934 | 0.901 | 0.898 | 0.288 | 2457 / 3147 | [-0.033, +0.004] |
| hyb_top10_laya_ml_embed | 0.918 | 0.934 | 0.889 | 0.910 | 0.269 | 707 / 1151 | [-0.032, +0.001] |
| hyb_band05_laya_ml_score | 0.925 | 0.933 | 0.914 | 0.911 | 0.265 | 2847 / 3793 | [-0.036, +0.006] |
| hyb_blend_w0.2_laya_ml_score | 0.924 | 0.932 | 0.914 | 0.929 | 0.278 | 2847 / 3793 | [-0.036, +0.005] |
| hyb_blend_w0.2_laya_ml_pair_k8 | 0.939 | 0.932 | 0.901 | 0.935 | 0.254 | 16011 / 20553 | [-0.039, +0.007] |
| hyb_main_bf1d59b_top10_laya_ml_noul | 0.921 | 0.931 | 0.877 | 0.897 | 0.265 | 619 / 794 | [-0.035, -0.001] |
| hyb_top10_laya_ml_noul | 0.921 | 0.931 | 0.877 | 0.897 | 0.253 | 614 / 787 | [-0.035, -0.001] |
| hyb_thr05_laya_ml_pair_k8 | 0.933 | 0.931 | 0.877 | 0.941 | 0.242 | 2445 / 11075 | [-0.039, +0.000] |
| hyb_thr05_laya_td_noul | 0.928 | 0.930 | 0.877 | 0.934 | 0.241 | 1084 / 4259 | [-0.041, +0.000] |
| hyb_rrblend_w0.3_laya_ml_noul | 0.934 | 0.930 | 0.901 | 0.898 | 0.271 | 2452 / 3140 | [-0.038, +0.003] |
| hyb_blend_w0.5_bge_small_bi_ecore | 0.931 | 0.930 | 0.889 | 0.940 | 0.284 | 546 / 959 | [-0.045, +0.007] |
| learned_pairwise | 0.926 | 0.930 | 0.864 | 0.931 | 0.268 | 0 / 0 | [-0.040, -0.000] |
| learned_ridge | 0.926 | 0.930 | 0.864 | 0.931 | 0.261 | 0 / 0 | [-0.040, +0.001] |
| hyb_rrblend_w0.5_laya_ml_score | 0.924 | 0.929 | 0.877 | 0.912 | 0.282 | 2847 / 3793 | [-0.038, +0.002] |
| hyb_thr05_laya_ml_embed | 0.930 | 0.929 | 0.877 | 0.936 | 0.245 | 439 / 1839 | [-0.046, -0.000] |
| hyb_blend_w0.3_laya_td_noul | 0.934 | 0.929 | 0.901 | 0.937 | 0.288 | 7003 / 8512 | [-0.050, +0.009] |
| hyb_blend_w0.3_laya_ml_score | 0.918 | 0.928 | 0.901 | 0.911 | 0.278 | 2847 / 3793 | [-0.046, +0.005] |
| minilm_bi_ecore | 0.920 | 0.928 | 0.864 | 0.917 | 0.276 | 282 / 491 | [-0.042, +0.005] |
| minilm_bi_p4 | 0.920 | 0.928 | 0.864 | 0.917 | 0.276 | 269 / 479 | [-0.042, +0.005] |
| minilm_bi_p8 | 0.920 | 0.928 | 0.864 | 0.917 | 0.276 | 153 / 272 | [-0.042, +0.005] |
| hyb_blend_w0.2_laya_ml_embed | 0.920 | 0.928 | 0.889 | 0.931 | 0.240 | 2822 / 4600 | [-0.039, -0.002] |
| hyb_main_bf1d59b_blend_w0.3_laya_ml_noul | 0.928 | 0.928 | 0.877 | 0.929 | 0.272 | 2457 / 3147 | [-0.040, +0.003] |
| hyb_thr05_laya_ml_score | 0.923 | 0.925 | 0.864 | 0.915 | 0.241 | 426 / 1747 | [-0.051, +0.000] |
| hyb_blend_w0.3_laya_ml_pair_k8 | 0.929 | 0.924 | 0.889 | 0.917 | 0.231 | 16011 / 20553 | [-0.058, +0.011] |
| hyb_rrblend_w0.5_laya_td_noul | 0.933 | 0.923 | 0.914 | 0.935 | 0.268 | 7003 / 8512 | [-0.052, +0.003] |
| laya_ml_pair_top10 | 0.908 | 0.922 | 0.840 | 0.865 | 0.254 | 4498 / 5810 | [-0.046, -0.006] |
| laya_ft_ml_full2 | 0.921 | 0.922 | 0.877 | 0.910 | 0.254 | 2012 / 2467 | [-0.061, +0.010] |
| hyb_main_bf1d59b_rrblend_w0.5_laya_ml_noul | 0.926 | 0.922 | 0.889 | 0.895 | 0.243 | 2457 / 3147 | [-0.060, +0.006] |
| hyb_band05_laya_ml_noul | 0.924 | 0.920 | 0.901 | 0.930 | 0.250 | 2452 / 3140 | [-0.068, +0.005] |
| hyb_rrblend_w0.5_laya_ml_pair_k8 | 0.931 | 0.920 | 0.877 | 0.938 | 0.230 | 16011 / 20553 | [-0.053, -0.001] |
| bge_reranker_base_ce_ecore | 0.904 | 0.919 | 0.840 | 0.863 | 0.272 | 2046 / 3285 | [-0.059, +0.000] |
| bge_reranker_base_ce_p4 | 0.904 | 0.919 | 0.840 | 0.863 | 0.272 | 2147 / 3488 | [-0.059, +0.000] |
| bge_reranker_base_ce_p8 | 0.904 | 0.919 | 0.840 | 0.863 | 0.272 | 1169 / 1894 | [-0.059, +0.000] |
| hyb_blend_w0.3_laya_ml_embed | 0.917 | 0.916 | 0.889 | 0.931 | 0.227 | 2822 / 4600 | [-0.059, -0.007] |
| hyb_rrblend_w0.5_laya_ml_embed | 0.917 | 0.916 | 0.889 | 0.895 | 0.209 | 2822 / 4600 | [-0.059, -0.006] |
| hyb_blend_w0.1_laya_ml_noul | 0.920 | 0.914 | 0.901 | 0.929 | 0.250 | 2452 / 3140 | [-0.086, +0.005] |
| hyb_main_bf1d59b_thr05_laya_ml_noul | 0.915 | 0.914 | 0.840 | 0.913 | 0.212 | 397 / 1615 | [-0.075, -0.002] |
| hyb_thr05_laya_ml_noul | 0.915 | 0.914 | 0.840 | 0.913 | 0.200 | 365 / 1609 | [-0.075, -0.002] |
| hyb_blend_w0.5_laya_ml_score | 0.898 | 0.914 | 0.877 | 0.880 | 0.261 | 2847 / 3793 | [-0.064, -0.004] |
| bge_small_bi_ecore | 0.908 | 0.913 | 0.852 | 0.922 | 0.220 | 544 / 958 | [-0.066, -0.005] |
| bge_small_bi_p4 | 0.908 | 0.913 | 0.852 | 0.922 | 0.220 | 532 / 947 | [-0.066, -0.005] |
| bge_small_bi_p8 | 0.908 | 0.913 | 0.852 | 0.922 | 0.220 | 292 / 567 | [-0.066, -0.005] |
| hyb_rrblend_w0.5_laya_ml_noul | 0.912 | 0.910 | 0.864 | 0.895 | 0.224 | 2452 / 3140 | [-0.084, +0.004] |
| laya_ft_ml_full | 0.902 | 0.909 | 0.833 | 0.885 | 0.115 | 2013 / 2372 |  |
| hyb_main_bf1d59b_blend_w0.5_laya_ml_noul | 0.908 | 0.905 | 0.852 | 0.914 | 0.204 | 2457 / 3147 | [-0.088, -0.001] |
| hyb_blend_w0.5_laya_td_noul | 0.903 | 0.905 | 0.852 | 0.888 | 0.224 | 7003 / 8512 | [-0.085, -0.002] |
| hyb_blend_w0.2_laya_ml_noul | 0.901 | 0.903 | 0.877 | 0.896 | 0.242 | 2452 / 3140 | [-0.115, +0.002] |
| hyb_blend_w0.5_laya_ml_pair_k8 | 0.889 | 0.901 | 0.840 | 0.888 | 0.155 | 16011 / 20553 | [-0.092, -0.003] |
| hyb_blend_w0.5_laya_ml_embed | 0.906 | 0.900 | 0.852 | 0.894 | 0.166 | 2822 / 4600 | [-0.085, -0.015] |
| laya_td_choice | 0.889 | 0.897 | 0.815 | 0.853 | 0.186 | 7267 / 8390 | [-0.088, -0.013] |
| hyb_blend_w0.3_laya_ml_noul | 0.891 | 0.897 | 0.840 | 0.895 | 0.222 | 2452 / 3140 | [-0.126, +0.001] |
| laya_ml_score | 0.884 | 0.897 | 0.840 | 0.878 | 0.202 | 2845 / 3790 | [-0.090, -0.014] |
| laya_ft_ml_head | 0.885 | 0.893 | 0.827 | 0.892 | 0.191 | 1931 / 2376 | [-0.100, -0.011] |
| distributor_order | 0.875 | 0.884 | 0.815 | 0.870 | 0.161 | 0 / 0 | [-0.118, -0.016] |
| hyb_blend_w0.5_laya_ml_noul | 0.878 | 0.884 | 0.827 | 0.879 | 0.176 | 2452 / 3140 | [-0.145, -0.001] |
| laya_ml_choice | 0.850 | 0.867 | 0.815 | 0.860 | 0.118 | 2751 / 3283 | [-0.148, -0.028] |
| laya_ml_embed | 0.858 | 0.866 | 0.778 | 0.851 | 0.083 | 2820 / 4598 | [-0.142, -0.029] |
| laya_ft_ml_head_synth | 0.862 | 0.865 | 0.802 | 0.877 | 0.097 | 1952 / 2384 | [-0.132, -0.037] |
| laya_td_noul | 0.835 | 0.861 | 0.741 | 0.773 | 0.162 | 7001 / 8505 | [-0.143, -0.031] |
| laya_td_noul_p4 | 0.835 | 0.861 | 0.741 | 0.773 | 0.162 | 11210 / 13333 | [-0.143, -0.031] |
| laya_td_noul_p8 | 0.835 | 0.861 | 0.741 | 0.773 | 0.162 | 6243 / 7407 | [-0.143, -0.031] |
| laya_en_choice | 0.856 | 0.861 | 0.790 | 0.862 | 0.122 | 7303 / 8656 | [-0.147, -0.034] |
| laya_ml_shortlist | 0.868 | 0.859 | 0.753 | 0.832 | 0.044 | 1226 / 1727 | [-0.150, -0.031] |
| laya_ml_noul_len192 | 0.844 | 0.858 | 0.765 | 0.814 | 0.095 | 2368 / 3227 | [-0.173, -0.018] |
| laya_ml_noul | 0.844 | 0.856 | 0.765 | 0.814 | 0.094 | 2450 / 3137 | [-0.175, -0.020] |
| laya_ml_noul_p4 | 0.844 | 0.856 | 0.765 | 0.814 | 0.094 | 4328 / 5349 | [-0.175, -0.020] |
| laya_ml_noul_p8 | 0.844 | 0.856 | 0.765 | 0.814 | 0.094 | 2425 / 3015 | [-0.175, -0.020] |
| laya_ml_noul_parsed | 0.839 | 0.856 | 0.765 | 0.794 | 0.098 | 2759 / 3244 | [-0.175, -0.019] |
| laya_ml_pair_k8 | 0.832 | 0.851 | 0.741 | 0.781 | 0.043 | 16010 / 20551 | [-0.155, -0.038] |
| laya_ml_decomp_prod | 0.830 | 0.850 | 0.753 | 0.787 | 0.082 | 10258 / 12932 | [-0.183, -0.026] |
| laya_ml_decomp_mean | 0.833 | 0.849 | 0.753 | 0.790 | 0.083 | 10258 / 12932 | [-0.184, -0.027] |
| laya_en_noul | 0.818 | 0.843 | 0.704 | 0.821 | 0.071 | 8142 / 12033 | [-0.184, -0.039] |
| laya_ml_pair_k4 | 0.822 | 0.841 | 0.753 | 0.790 | 0.046 | 8036 / 10420 | [-0.162, -0.051] |
| laya_ml_pair_k2 | 0.814 | 0.836 | 0.716 | 0.733 | 0.039 | 4017 / 5329 | [-0.165, -0.059] |
| laya_ml_pair_k1 | 0.823 | 0.836 | 0.704 | 0.796 | 0.004 | 2018 / 2633 | [-0.163, -0.058] |
| laya_ml_embed_json | 0.782 | 0.808 | 0.642 | 0.728 | -0.033 | 2512 / 3410 | [-0.197, -0.081] |
| laya_ml_noul_plain | 0.780 | 0.804 | 0.679 | 0.752 | -0.038 | 3289 / 4574 | [-0.236, -0.063] |

| method | passive | discrete | ic | crystal_connector | vague |
|---|---|---|---|---|---|
| hyb_blend_w0.5_msmarco_minilm_ce_ft_synth | 0.996 | 1.000 | 0.981 | 1.000 | 0.699 |
| msmarco_minilm_ce_ft_synth | 0.996 | 1.000 | 0.981 | 1.000 | 0.666 |
| hyb_blend_w0.3_msmarco_minilm_ce_ft_synth | 0.989 | 1.000 | 0.981 | 0.988 | 0.691 |
| hyb_blend_w0.3_msmarco_minilm_ce_ft_synth_real | 0.989 | 1.000 | 0.987 | 0.988 | 0.677 |
| hyb_main_bf1d59b_blend_w0.3_msmarco_minilm_ce_ft_synth_real | 0.989 | 1.000 | 0.987 | 0.988 | 0.677 |
| hyb_blend_w0.2_msmarco_minilm_ce_ft_synth | 0.989 | 1.000 | 0.985 | 0.988 | 0.676 |
| hyb_blend_w0.2_msmarco_minilm_ce_ft_synth_real | 0.989 | 1.000 | 0.987 | 0.988 | 0.670 |
| hyb_main_bf1d59b_blend_w0.2_msmarco_minilm_ce_ft_synth_real | 0.989 | 1.000 | 0.987 | 0.988 | 0.670 |
| hyb_blend_w0.1_msmarco_minilm_ce_ft_synth | 0.989 | 1.000 | 0.988 | 0.988 | 0.667 |
| hyb_rrblend_w0.5_msmarco_minilm_ce_ft_synth | 0.996 | 1.000 | 1.000 | 1.000 | 0.605 |
| hyb_band05_msmarco_minilm_ce_ft_synth | 0.989 | 1.000 | 0.981 | 0.988 | 0.669 |
| hyb_band05_minilm_bi_ecore | 0.988 | 0.993 | 1.000 | 0.988 | 0.647 |
| hyb_rrblend_w0.5_bge_reranker_base_ce_ecore | 1.000 | 0.987 | 1.000 | 1.000 | 0.605 |
| hyb_blend_w0.1_msmarco_minilm_ce_ft_synth_real | 0.989 | 1.000 | 0.989 | 0.988 | 0.647 |
| hyb_main_bf1d59b_blend_w0.1_msmarco_minilm_ce_ft_synth_real | 0.989 | 1.000 | 0.989 | 0.988 | 0.647 |
| hyb_blend_w0.5_msmarco_minilm_ce_ft_synth_real | 0.996 | 1.000 | 0.985 | 1.000 | 0.615 |
| hyb_main_bf1d59b_blend_w0.5_msmarco_minilm_ce_ft_synth_real | 0.996 | 1.000 | 0.985 | 1.000 | 0.615 |
| hyb_blend_w0.3_msmarco_minilm12_ce_ecore | 0.988 | 1.000 | 0.981 | 0.988 | 0.660 |
| hyb_band05_msmarco_minilm_ce_ft_synth_real | 0.989 | 1.000 | 0.985 | 0.988 | 0.648 |
| hyb_main_bf1d59b_band05_msmarco_minilm_ce_ft_synth_real | 0.989 | 1.000 | 0.985 | 0.988 | 0.648 |
| hyb_rrblend_w0.5_minilm_bi_ecore | 0.991 | 0.991 | 1.000 | 0.987 | 0.632 |
| hyb_main_bf1d59b_rrblend_w0.5_msmarco_minilm_ce_ft_synth_real | 0.996 | 0.994 | 1.000 | 1.000 | 0.594 |
| hyb_rrblend_w0.5_msmarco_minilm_ce_ft_synth_real | 0.996 | 0.994 | 1.000 | 1.000 | 0.594 |
| hyb_blend_w0.2_minilm_bi_ecore | 0.988 | 0.993 | 1.000 | 0.988 | 0.633 |
| hyb_blend_w0.3_minilm_bi_ecore | 0.992 | 0.993 | 1.000 | 0.988 | 0.619 |
| hyb_blend_w0.1_minilm_bi_ecore | 0.988 | 0.993 | 1.000 | 0.988 | 0.631 |
| hyb_blend_w0.5_minilm_bi_ecore | 0.992 | 0.993 | 1.000 | 0.971 | 0.625 |
| hyb_blend_w0.2_msmarco_minilm12_ce_ecore | 0.988 | 0.994 | 0.981 | 0.988 | 0.649 |
| hyb_main_bf1d59b_rrblend_w0.5_msmarco_minilm_ce_ecore | 0.992 | 1.000 | 1.000 | 1.000 | 0.579 |
| hyb_rrblend_w0.5_msmarco_minilm_ce_ecore | 0.992 | 1.000 | 1.000 | 1.000 | 0.579 |
| hyb_rrblend_w0.3_msmarco_minilm_ce_ft_synth | 0.988 | 0.992 | 1.000 | 1.000 | 0.604 |
| hyb_rrblend_w0.3_bge_reranker_base_ce_ecore | 0.992 | 0.987 | 1.000 | 1.000 | 0.602 |
| hyb_blend_w0.1_msmarco_minilm12_ce_ecore | 0.989 | 0.993 | 0.989 | 0.988 | 0.625 |
| hyb_blend_w0.5_msmarco_minilm12_ce_ecore | 0.991 | 1.000 | 0.976 | 0.988 | 0.623 |
| hyb_band05_msmarco_minilm12_ce_ecore | 0.989 | 1.000 | 0.976 | 0.988 | 0.630 |
| hyb_blend_w0.3_msmarco_minilm_ce_ft_real | 0.982 | 1.000 | 0.989 | 0.988 | 0.623 |
| hyb_rrblend_w0.2_bge_reranker_base_ce_ecore | 0.992 | 0.977 | 1.000 | 0.988 | 0.617 |
| msmarco_minilm_ce_ft_synth_real | 0.996 | 0.987 | 0.985 | 1.000 | 0.596 |
| hyb_rrblend_w0.3_minilm_bi_ecore | 0.991 | 0.987 | 1.000 | 0.988 | 0.597 |
| hyb_rrblend_w0.5_msmarco_minilm12_ce_ecore | 0.992 | 0.990 | 1.000 | 0.988 | 0.587 |
| hyb_band05_msmarco_minilm_ce_ft_real | 0.982 | 1.000 | 0.988 | 0.988 | 0.617 |
| hyb_blend_w0.3_msmarco_minilm_ce_ecore | 0.983 | 1.000 | 0.985 | 0.988 | 0.618 |
| hyb_main_bf1d59b_blend_w0.3_msmarco_minilm_ce_ecore | 0.983 | 1.000 | 0.985 | 0.988 | 0.618 |
| hyb_blend_w0.5_laya_ft_ml_full2 | 0.999 | 1.000 | 0.985 | 1.000 | 0.550 |
| hyb_blend_w0.1_msmarco_minilm_ce_ft_real | 0.983 | 1.000 | 1.000 | 0.988 | 0.593 |
| hyb_top10_msmarco_minilm_ce_ft_synth | 0.988 | 0.984 | 1.000 | 0.988 | 0.604 |
| hyb_rrblend_w0.2_laya_ml_score | 0.982 | 0.978 | 1.000 | 1.000 | 0.623 |
| hyb_blend_w0.5_msmarco_minilm_ce_ecore | 0.986 | 1.000 | 0.984 | 1.000 | 0.593 |
| hyb_main_bf1d59b_blend_w0.5_msmarco_minilm_ce_ecore | 0.986 | 1.000 | 0.984 | 1.000 | 0.593 |
| hyb_blend_w0.1_laya_ft_ml_full2 | 0.989 | 1.000 | 0.989 | 0.988 | 0.587 |
| hyb_main_bf1d59b_top10_msmarco_minilm_ce_ecore | 0.988 | 0.984 | 1.000 | 0.988 | 0.600 |
| hyb_top10_msmarco_minilm_ce_ecore | 0.988 | 0.984 | 1.000 | 0.988 | 0.600 |
| hyb_rrblend_w0.1_bge_reranker_base_ce_ecore | 0.988 | 0.977 | 1.000 | 0.988 | 0.613 |
| hyb_main_bf1d59b_top10_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.984 | 1.000 | 0.988 | 0.597 |
| hyb_top10_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.984 | 1.000 | 0.988 | 0.597 |
| hyb_tie_bge_reranker_base_ce_ecore | 0.988 | 0.976 | 1.000 | 0.988 | 0.613 |
| hyb_tie_laya_ml_score | 0.988 | 0.976 | 1.000 | 0.988 | 0.613 |
| hyb_band05_bge_small_bi_ecore | 0.988 | 1.000 | 0.981 | 0.986 | 0.598 |
| hyb_rrblend_w0.2_minilm_bi_ecore | 0.992 | 0.987 | 1.000 | 0.988 | 0.579 |
| hyb_blend_w0.2_msmarco_minilm_ce_ecore | 0.983 | 1.000 | 0.987 | 0.988 | 0.604 |
| hyb_main_bf1d59b_blend_w0.2_msmarco_minilm_ce_ecore | 0.983 | 1.000 | 0.987 | 0.988 | 0.604 |
| hyb_top10_msmarco_minilm_ce_ft_real | 0.988 | 0.984 | 1.000 | 0.988 | 0.594 |
| det | 0.988 | 0.976 | 1.000 | 0.988 | 0.610 |
| det_main_bf1d59b | 0.988 | 0.976 | 1.000 | 0.988 | 0.610 |
| hyb_main_bf1d59b_thr05_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.976 | 1.000 | 0.988 | 0.610 |
| hyb_thr05_bge_reranker_base_ce_ecore | 0.988 | 0.976 | 1.000 | 0.988 | 0.610 |
| hyb_thr05_msmarco_minilm12_ce_ecore | 0.988 | 0.976 | 1.000 | 0.988 | 0.610 |
| hyb_thr05_msmarco_minilm_ce_ft_synth | 0.988 | 0.976 | 1.000 | 0.988 | 0.610 |
| hyb_thr05_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.976 | 1.000 | 0.988 | 0.610 |
| hyb_rrblend_w0.5_bge_small_bi_ecore | 0.986 | 0.990 | 1.000 | 1.000 | 0.577 |
| hyb_blend_w0.2_msmarco_minilm_ce_ft_real | 0.983 | 1.000 | 0.989 | 0.988 | 0.598 |
| hyb_top10_msmarco_minilm12_ce_ecore | 0.988 | 0.984 | 1.000 | 0.988 | 0.591 |
| hyb_rrblend_w0.1_laya_ml_score | 0.983 | 0.976 | 1.000 | 0.988 | 0.624 |
| hyb_blend_w0.5_msmarco_minilm_ce_ft_real | 0.986 | 1.000 | 0.988 | 1.000 | 0.574 |
| hyb_main_bf1d59b_rrblend_w0.3_msmarco_minilm_ce_ecore | 0.988 | 0.992 | 1.000 | 1.000 | 0.561 |
| hyb_rrblend_w0.3_msmarco_minilm_ce_ecore | 0.988 | 0.992 | 1.000 | 1.000 | 0.561 |
| hyb_rrblend_w0.2_bge_small_bi_ecore | 0.988 | 0.979 | 1.000 | 0.988 | 0.596 |
| hyb_rrblend_w0.1_bge_small_bi_ecore | 0.988 | 0.977 | 1.000 | 0.988 | 0.599 |
| hyb_blend_w0.1_bge_reranker_base_ce_ecore | 0.988 | 0.987 | 1.000 | 0.988 | 0.579 |
| hyb_blend_w0.3_laya_ft_ml_full2 | 0.996 | 1.000 | 0.987 | 0.988 | 0.547 |
| hyb_rrblend_w0.3_msmarco_minilm_ce_ft_real | 0.988 | 0.992 | 1.000 | 1.000 | 0.555 |
| hyb_blend_w0.2_bge_reranker_base_ce_ecore | 0.989 | 0.987 | 0.989 | 0.988 | 0.593 |
| hyb_tie_bge_small_bi_ecore | 0.988 | 0.976 | 1.000 | 0.988 | 0.596 |
| hyb_tie_minilm_bi_ecore | 0.988 | 0.976 | 1.000 | 0.988 | 0.596 |
| hyb_main_bf1d59b_rrblend_w0.1_msmarco_minilm_ce_ecore | 0.988 | 0.980 | 1.000 | 0.988 | 0.588 |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ecore | 0.988 | 0.980 | 1.000 | 0.988 | 0.588 |
| hyb_rrblend_w0.2_laya_ml_pair_k8 | 0.991 | 0.979 | 1.000 | 1.000 | 0.567 |
| hyb_band05_laya_ft_ml_full2 | 0.989 | 1.000 | 0.985 | 0.988 | 0.568 |
| hyb_blend_w0.1_msmarco_minilm_ce_ecore | 0.983 | 1.000 | 0.989 | 0.988 | 0.580 |
| hyb_main_bf1d59b_blend_w0.1_msmarco_minilm_ce_ecore | 0.983 | 1.000 | 0.989 | 0.988 | 0.580 |
| hyb_main_bf1d59b_thr05_msmarco_minilm_ce_ecore | 0.983 | 0.976 | 1.000 | 0.988 | 0.610 |
| hyb_thr05_msmarco_minilm_ce_ecore | 0.983 | 0.976 | 1.000 | 0.988 | 0.610 |
| hyb_rrblend_w0.2_msmarco_minilm_ce_ft_real | 0.988 | 0.991 | 1.000 | 1.000 | 0.549 |
| hyb_rrblend_w0.2_laya_ft_ml_full2 | 0.992 | 0.987 | 1.000 | 0.988 | 0.557 |
| hyb_thr05_msmarco_minilm_ce_ft_real | 0.982 | 0.976 | 1.000 | 0.988 | 0.610 |
| hyb_rrblend_w0.3_bge_small_bi_ecore | 0.986 | 0.981 | 1.000 | 0.988 | 0.586 |
| hyb_rrblend_w0.1_msmarco_minilm12_ce_ecore | 0.988 | 0.977 | 1.000 | 0.988 | 0.587 |
| hyb_main_bf1d59b_tie_msmarco_minilm_ce_ecore | 0.988 | 0.976 | 1.000 | 0.988 | 0.587 |
| hyb_tie_msmarco_minilm12_ce_ecore | 0.988 | 0.976 | 1.000 | 0.988 | 0.587 |
| hyb_tie_msmarco_minilm_ce_ecore | 0.988 | 0.976 | 1.000 | 0.988 | 0.587 |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ft_real | 0.988 | 0.980 | 1.000 | 0.988 | 0.581 |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ft_synth | 0.988 | 0.980 | 1.000 | 0.988 | 0.580 |
| hyb_tie_laya_ml_embed | 0.988 | 0.976 | 1.000 | 0.988 | 0.586 |
| hyb_tie_laya_ft_ml_full2 | 0.988 | 0.976 | 1.000 | 0.988 | 0.585 |
| hyb_tie_laya_td_noul | 0.988 | 0.976 | 1.000 | 0.988 | 0.585 |
| hyb_rrblend_w0.1_laya_ml_pair_k8 | 0.988 | 0.976 | 1.000 | 1.000 | 0.573 |
| hyb_rrblend_w0.1_minilm_bi_ecore | 0.988 | 0.977 | 1.000 | 0.988 | 0.582 |
| hyb_band05_msmarco_minilm_ce_ecore | 0.983 | 1.000 | 0.984 | 0.988 | 0.581 |
| hyb_main_bf1d59b_band05_msmarco_minilm_ce_ecore | 0.983 | 1.000 | 0.984 | 0.988 | 0.581 |
| hyb_rrblend_w0.1_laya_ml_embed | 0.988 | 0.977 | 1.000 | 0.988 | 0.583 |
| hyb_top10_laya_td_noul | 0.988 | 0.980 | 1.000 | 0.985 | 0.578 |
| hyb_rrblend_w0.5_msmarco_minilm_ce_ft_real | 0.987 | 0.994 | 1.000 | 1.000 | 0.540 |
| hyb_main_bf1d59b_rrblend_w0.1_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.980 | 1.000 | 0.988 | 0.575 |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.980 | 1.000 | 0.988 | 0.575 |
| hyb_blend_w0.3_bge_reranker_base_ce_ecore | 0.992 | 0.987 | 0.989 | 0.988 | 0.566 |
| hyb_rrblend_w0.2_laya_td_noul | 0.988 | 0.987 | 1.000 | 0.988 | 0.561 |
| hyb_blend_w0.1_bge_small_bi_ecore | 0.988 | 0.994 | 0.987 | 0.987 | 0.567 |
| hyb_rrblend_w0.1_laya_ft_ml_full2 | 0.988 | 0.977 | 1.000 | 0.988 | 0.577 |
| hyb_main_bf1d59b_tie_laya_ml_noul | 0.988 | 0.976 | 1.000 | 0.988 | 0.579 |
| hyb_main_bf1d59b_tie_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.976 | 1.000 | 0.988 | 0.579 |
| hyb_tie_laya_ml_noul | 0.988 | 0.976 | 1.000 | 0.988 | 0.579 |
| hyb_tie_laya_ml_pair_k8 | 0.988 | 0.976 | 1.000 | 0.988 | 0.579 |
| hyb_tie_msmarco_minilm_ce_ft_real | 0.988 | 0.976 | 1.000 | 0.988 | 0.579 |
| hyb_tie_msmarco_minilm_ce_ft_synth | 0.988 | 0.976 | 1.000 | 0.988 | 0.579 |
| hyb_tie_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.976 | 1.000 | 0.988 | 0.579 |
| hyb_band05_bge_reranker_base_ce_ecore | 0.989 | 0.987 | 0.988 | 0.988 | 0.574 |
| hyb_band05_laya_ml_pair_k8 | 0.989 | 0.990 | 1.000 | 0.951 | 0.584 |
| hyb_blend_w0.2_laya_ft_ml_full2 | 0.989 | 1.000 | 0.987 | 0.988 | 0.547 |
| hyb_thr05_minilm_bi_ecore | 0.977 | 0.976 | 1.000 | 0.988 | 0.610 |
| hyb_rrblend_w0.3_msmarco_minilm12_ce_ecore | 0.988 | 0.987 | 1.000 | 0.988 | 0.553 |
| hyb_top10_bge_reranker_base_ce_ecore | 0.988 | 0.970 | 1.000 | 0.988 | 0.585 |
| hyb_main_bf1d59b_rrblend_w0.3_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.992 | 1.000 | 1.000 | 0.528 |
| hyb_rrblend_w0.3_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.992 | 1.000 | 1.000 | 0.528 |
| hyb_rrblend_w0.5_laya_ft_ml_full2 | 1.000 | 0.994 | 1.000 | 0.988 | 0.495 |
| hyb_main_bf1d59b_rrblend_w0.2_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.990 | 1.000 | 0.988 | 0.541 |
| hyb_rrblend_w0.2_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.990 | 1.000 | 0.988 | 0.541 |
| hyb_rrblend_w0.3_laya_ml_score | 0.980 | 0.979 | 1.000 | 1.000 | 0.578 |
| hyb_main_bf1d59b_rrblend_w0.1_laya_ml_noul | 0.981 | 0.977 | 1.000 | 0.988 | 0.590 |
| hyb_rrblend_w0.1_laya_ml_noul | 0.981 | 0.977 | 1.000 | 0.988 | 0.590 |
| hyb_blend_w0.2_bge_small_bi_ecore | 0.988 | 0.994 | 0.985 | 0.987 | 0.558 |
| hyb_blend_w0.3_bge_small_bi_ecore | 0.988 | 0.994 | 0.985 | 0.986 | 0.558 |
| hyb_top10_bge_small_bi_ecore | 0.980 | 0.983 | 1.000 | 0.986 | 0.582 |
| hyb_main_bf1d59b_rrblend_w0.2_msmarco_minilm_ce_ecore | 0.988 | 0.980 | 1.000 | 0.988 | 0.560 |
| hyb_rrblend_w0.2_msmarco_minilm_ce_ecore | 0.988 | 0.980 | 1.000 | 0.988 | 0.560 |
| hyb_thr05_laya_ft_ml_full2 | 0.975 | 0.976 | 1.000 | 0.988 | 0.610 |
| hyb_rrblend_w0.1_laya_td_noul | 0.988 | 0.977 | 1.000 | 0.988 | 0.561 |
| hyb_rrblend_w0.2_msmarco_minilm12_ce_ecore | 0.988 | 0.977 | 1.000 | 0.988 | 0.561 |
| hyb_blend_w0.1_laya_ml_pair_k8 | 0.989 | 0.985 | 1.000 | 0.953 | 0.578 |
| hyb_top10_minilm_bi_ecore | 0.982 | 0.983 | 1.000 | 0.988 | 0.566 |
| hyb_thr05_bge_small_bi_ecore | 0.973 | 0.976 | 1.000 | 0.986 | 0.610 |
| hyb_rrblend_w0.3_laya_td_noul | 0.992 | 0.981 | 1.000 | 0.945 | 0.576 |
| hyb_rrblend_w0.2_msmarco_minilm_ce_ft_synth | 0.988 | 0.980 | 1.000 | 0.988 | 0.542 |
| bm25 | 0.991 | 1.000 | 0.976 | 0.988 | 0.533 |
| hyb_blend_w0.1_laya_ml_score | 0.976 | 0.986 | 0.985 | 0.988 | 0.593 |
| hyb_rrblend_w0.3_laya_ml_pair_k8 | 0.982 | 0.982 | 1.000 | 1.000 | 0.545 |
| hyb_top10_laya_ft_ml_full2 | 0.987 | 0.984 | 1.000 | 0.988 | 0.534 |
| hyb_main_bf1d59b_blend_w0.1_laya_ml_noul | 0.983 | 0.979 | 0.987 | 0.985 | 0.582 |
| hyb_rrblend_w0.2_laya_ml_embed | 0.985 | 0.969 | 1.000 | 0.988 | 0.571 |
| hyb_main_bf1d59b_rrblend_w0.2_laya_ml_noul | 0.980 | 0.970 | 1.000 | 0.988 | 0.577 |
| hyb_main_bf1d59b_band05_laya_ml_noul | 0.983 | 0.990 | 0.962 | 0.974 | 0.604 |
| hyb_top10_laya_ml_score | 0.973 | 0.978 | 1.000 | 0.988 | 0.583 |
| hyb_blend_w0.5_bge_reranker_base_ce_ecore | 0.996 | 0.979 | 0.988 | 1.000 | 0.512 |
| hyb_band05_laya_ml_embed | 0.981 | 0.991 | 1.000 | 0.987 | 0.527 |
| msmarco_minilm_ce_onnx_int8_t4 | 0.968 | 0.994 | 0.981 | 1.000 | 0.579 |
| msmarco_minilm_ce_onnx_int8_t8 | 0.968 | 0.994 | 0.981 | 1.000 | 0.579 |
| hyb_rrblend_w0.3_laya_ft_ml_full2 | 0.992 | 0.991 | 1.000 | 0.988 | 0.485 |
| hyb_blend_w0.1_laya_ml_embed | 0.981 | 0.987 | 1.000 | 0.987 | 0.527 |
| hyb_top10_laya_ml_pair_k8 | 0.980 | 0.982 | 1.000 | 0.987 | 0.540 |
| hyb_blend_w0.2_laya_td_noul | 0.988 | 0.991 | 1.000 | 0.863 | 0.614 |
| msmarco_minilm12_ce_ecore | 0.967 | 0.991 | 0.976 | 1.000 | 0.587 |
| msmarco_minilm12_ce_p4 | 0.967 | 0.991 | 0.976 | 1.000 | 0.587 |
| msmarco_minilm12_ce_p8 | 0.967 | 0.991 | 0.976 | 1.000 | 0.587 |
| hyb_rrblend_w0.2_laya_ml_noul | 0.980 | 0.970 | 1.000 | 0.967 | 0.577 |
| hyb_blend_w0.1_laya_td_noul | 0.988 | 0.990 | 1.000 | 0.867 | 0.609 |
| hyb_band05_laya_td_noul | 0.989 | 0.991 | 1.000 | 0.863 | 0.607 |
| msmarco_minilm_ce_ft_real | 0.968 | 0.994 | 0.988 | 1.000 | 0.550 |
| hyb_main_bf1d59b_blend_w0.2_laya_ml_noul | 0.983 | 0.979 | 0.974 | 0.969 | 0.583 |
| msmarco_minilm_ce_ecore | 0.962 | 1.000 | 0.984 | 1.000 | 0.561 |
| msmarco_minilm_ce_onnx_t4 | 0.962 | 1.000 | 0.984 | 1.000 | 0.561 |
| msmarco_minilm_ce_onnx_t8 | 0.962 | 1.000 | 0.984 | 1.000 | 0.561 |
| msmarco_minilm_ce_p4 | 0.962 | 1.000 | 0.984 | 1.000 | 0.561 |
| msmarco_minilm_ce_p8 | 0.962 | 1.000 | 0.984 | 1.000 | 0.561 |
| hyb_rrblend_w0.3_laya_ml_embed | 0.978 | 0.979 | 1.000 | 1.000 | 0.522 |
| hyb_main_bf1d59b_rrblend_w0.3_laya_ml_noul | 0.965 | 0.979 | 1.000 | 0.975 | 0.588 |
| hyb_top10_laya_ml_embed | 0.986 | 0.977 | 1.000 | 0.987 | 0.510 |
| hyb_band05_laya_ml_score | 0.969 | 0.990 | 0.962 | 0.975 | 0.610 |
| hyb_blend_w0.2_laya_ml_score | 0.976 | 0.986 | 0.955 | 0.973 | 0.599 |
| hyb_blend_w0.2_laya_ml_pair_k8 | 0.985 | 0.994 | 0.992 | 0.925 | 0.536 |
| hyb_main_bf1d59b_top10_laya_ml_noul | 0.966 | 0.969 | 1.000 | 0.974 | 0.576 |
| hyb_top10_laya_ml_noul | 0.966 | 0.969 | 1.000 | 0.974 | 0.576 |
| hyb_thr05_laya_ml_pair_k8 | 0.947 | 0.976 | 1.000 | 0.987 | 0.610 |
| hyb_thr05_laya_td_noul | 0.946 | 0.976 | 1.000 | 0.985 | 0.610 |
| hyb_rrblend_w0.3_laya_ml_noul | 0.965 | 0.979 | 1.000 | 0.939 | 0.588 |
| hyb_blend_w0.5_bge_small_bi_ecore | 0.987 | 0.994 | 0.966 | 0.949 | 0.530 |
| learned_pairwise | 0.988 | 0.954 | 0.993 | 0.988 | 0.522 |
| learned_ridge | 0.988 | 0.954 | 0.985 | 1.000 | 0.522 |
| hyb_rrblend_w0.5_laya_ml_score | 0.951 | 0.984 | 0.993 | 1.000 | 0.570 |
| hyb_thr05_laya_ml_embed | 0.944 | 0.976 | 1.000 | 0.987 | 0.610 |
| hyb_blend_w0.3_laya_td_noul | 0.977 | 0.985 | 1.000 | 0.860 | 0.604 |
| hyb_blend_w0.3_laya_ml_score | 0.963 | 0.986 | 0.955 | 0.971 | 0.606 |
| minilm_bi_ecore | 0.962 | 0.967 | 1.000 | 0.937 | 0.604 |
| minilm_bi_p4 | 0.962 | 0.967 | 1.000 | 0.937 | 0.604 |
| minilm_bi_p8 | 0.962 | 0.967 | 1.000 | 0.937 | 0.604 |
| hyb_blend_w0.2_laya_ml_embed | 0.978 | 0.979 | 0.991 | 0.987 | 0.493 |
| hyb_main_bf1d59b_blend_w0.3_laya_ml_noul | 0.973 | 0.979 | 0.974 | 0.939 | 0.585 |
| hyb_thr05_laya_ml_score | 0.933 | 0.976 | 1.000 | 0.988 | 0.610 |
| hyb_blend_w0.3_laya_ml_pair_k8 | 0.984 | 0.994 | 0.967 | 0.913 | 0.523 |
| hyb_rrblend_w0.5_laya_td_noul | 0.970 | 0.980 | 1.000 | 0.894 | 0.555 |
| laya_ml_pair_top10 | 0.963 | 0.968 | 1.000 | 0.985 | 0.503 |
| laya_ft_ml_full2 | 0.957 | 0.972 | 0.985 | 1.000 | 0.521 |
| hyb_main_bf1d59b_rrblend_w0.5_laya_ml_noul | 0.949 | 0.979 | 0.993 | 0.933 | 0.586 |
| hyb_band05_laya_ml_noul | 0.983 | 0.990 | 0.962 | 0.818 | 0.604 |
| hyb_rrblend_w0.5_laya_ml_pair_k8 | 0.966 | 0.980 | 0.970 | 0.943 | 0.542 |
| bge_reranker_base_ce_ecore | 0.981 | 0.924 | 0.988 | 1.000 | 0.504 |
| bge_reranker_base_ce_p4 | 0.981 | 0.924 | 0.988 | 1.000 | 0.504 |
| bge_reranker_base_ce_p8 | 0.981 | 0.924 | 0.988 | 1.000 | 0.504 |
| hyb_blend_w0.3_laya_ml_embed | 0.967 | 0.975 | 0.989 | 0.975 | 0.451 |
| hyb_rrblend_w0.5_laya_ml_embed | 0.952 | 0.966 | 0.990 | 0.988 | 0.501 |
| hyb_blend_w0.1_laya_ml_noul | 0.983 | 0.979 | 0.987 | 0.768 | 0.582 |
| hyb_main_bf1d59b_thr05_laya_ml_noul | 0.907 | 0.976 | 1.000 | 0.974 | 0.610 |
| hyb_thr05_laya_ml_noul | 0.907 | 0.976 | 1.000 | 0.974 | 0.610 |
| hyb_blend_w0.5_laya_ml_score | 0.940 | 0.988 | 0.955 | 0.944 | 0.583 |
| bge_small_bi_ecore | 0.942 | 0.994 | 0.969 | 0.937 | 0.534 |
| bge_small_bi_p4 | 0.942 | 0.994 | 0.969 | 0.937 | 0.534 |
| bge_small_bi_p8 | 0.942 | 0.994 | 0.969 | 0.937 | 0.534 |
| hyb_rrblend_w0.5_laya_ml_noul | 0.949 | 0.979 | 0.993 | 0.833 | 0.586 |
| laya_ft_ml_full | 0.977 | 0.974 | 0.751 | 1.000 | 0.595 |
| hyb_main_bf1d59b_blend_w0.5_laya_ml_noul | 0.938 | 0.981 | 0.969 | 0.853 | 0.593 |
| hyb_blend_w0.5_laya_td_noul | 0.945 | 0.952 | 0.993 | 0.828 | 0.613 |
| hyb_blend_w0.2_laya_ml_noul | 0.983 | 0.979 | 0.974 | 0.689 | 0.583 |
| hyb_blend_w0.5_laya_ml_pair_k8 | 0.976 | 0.970 | 0.935 | 0.847 | 0.508 |
| hyb_blend_w0.5_laya_ml_embed | 0.943 | 0.946 | 0.975 | 0.987 | 0.451 |
| laya_td_choice | 0.922 | 0.923 | 0.980 | 0.958 | 0.564 |
| hyb_blend_w0.3_laya_ml_noul | 0.973 | 0.979 | 0.974 | 0.665 | 0.585 |
| laya_ml_score | 0.908 | 0.975 | 0.955 | 0.940 | 0.565 |
| laya_ft_ml_head | 0.916 | 0.964 | 0.969 | 0.882 | 0.561 |
| distributor_order | 0.948 | 0.964 | 0.922 | 0.815 | 0.516 |
| hyb_blend_w0.5_laya_ml_noul | 0.938 | 0.981 | 0.969 | 0.660 | 0.593 |
| laya_ml_choice | 0.926 | 0.941 | 0.962 | 0.690 | 0.541 |
| laya_ml_embed | 0.886 | 0.899 | 0.975 | 0.969 | 0.451 |
| laya_ft_ml_head_synth | 0.970 | 0.829 | 0.951 | 0.865 | 0.440 |
| laya_td_noul | 0.893 | 0.857 | 0.990 | 0.829 | 0.578 |
| laya_td_noul_p4 | 0.893 | 0.857 | 0.990 | 0.829 | 0.578 |
| laya_td_noul_p8 | 0.893 | 0.857 | 0.990 | 0.829 | 0.578 |
| laya_en_choice | 0.900 | 0.930 | 0.988 | 0.767 | 0.474 |
| laya_ml_shortlist | 0.894 | 0.937 | 0.889 | 0.840 | 0.556 |
| laya_ml_noul_len192 | 0.887 | 0.957 | 0.953 | 0.678 | 0.590 |
| laya_ml_noul | 0.881 | 0.957 | 0.954 | 0.678 | 0.590 |
| laya_ml_noul_p4 | 0.881 | 0.957 | 0.954 | 0.678 | 0.590 |
| laya_ml_noul_p8 | 0.881 | 0.957 | 0.954 | 0.678 | 0.590 |
| laya_ml_noul_parsed | 0.885 | 0.923 | 0.962 | 0.690 | 0.615 |
| laya_ml_pair_k8 | 0.892 | 0.906 | 0.914 | 0.834 | 0.515 |
| laya_ml_decomp_prod | 0.882 | 0.949 | 0.941 | 0.661 | 0.588 |
| laya_ml_decomp_mean | 0.882 | 0.949 | 0.941 | 0.661 | 0.576 |
| laya_en_noul | 0.879 | 0.904 | 0.972 | 0.700 | 0.527 |
| laya_ml_pair_k4 | 0.892 | 0.876 | 0.912 | 0.822 | 0.508 |
| laya_ml_pair_k2 | 0.890 | 0.842 | 0.946 | 0.813 | 0.487 |
| laya_ml_pair_k1 | 0.904 | 0.854 | 0.915 | 0.787 | 0.491 |
| laya_ml_embed_json | 0.868 | 0.790 | 0.904 | 0.832 | 0.462 |
| laya_ml_noul_plain | 0.839 | 0.802 | 0.946 | 0.658 | 0.599 |
