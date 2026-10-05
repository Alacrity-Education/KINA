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
| hyb_blend_w0.1_msmarco_minilm_ce_ft_real | 0.947 | 0.947 | 0.938 | 0.948 | 0.311 | 341 / 580 | [-0.010, +0.012] |
| hyb_top10_msmarco_minilm_ce_ft_synth | 0.954 | 0.947 | 0.938 | 0.965 | 0.280 | 40 / 94 | [-0.004, +0.006] |
| hyb_blend_w0.5_msmarco_minilm_ce_ecore | 0.948 | 0.947 | 0.914 | 0.936 | 0.344 | 305 / 533 | [-0.014, +0.015] |
| hyb_main_bf1d59b_blend_w0.5_msmarco_minilm_ce_ecore | 0.948 | 0.947 | 0.914 | 0.936 | 0.344 | 310 / 538 | [-0.014, +0.015] |
| hyb_main_bf1d59b_top10_msmarco_minilm_ce_ecore | 0.954 | 0.946 | 0.938 | 0.965 | 0.291 | 83 / 139 | [-0.005, +0.005] |
| hyb_top10_msmarco_minilm_ce_ecore | 0.954 | 0.946 | 0.938 | 0.965 | 0.279 | 78 / 134 | [-0.005, +0.005] |
| hyb_rrblend_w0.1_bge_reranker_base_ce_ecore | 0.954 | 0.946 | 0.914 | 0.966 | 0.292 | 2047 / 3286 | [+0.000, +0.001] |
| hyb_main_bf1d59b_top10_msmarco_minilm_ce_ft_synth_real | 0.954 | 0.946 | 0.938 | 0.965 | 0.290 | 45 / 95 | [-0.005, +0.005] |
| hyb_top10_msmarco_minilm_ce_ft_synth_real | 0.954 | 0.946 | 0.938 | 0.965 | 0.279 | 39 / 88 | [-0.005, +0.005] |
| hyb_tie_bge_reranker_base_ce_ecore | 0.954 | 0.946 | 0.914 | 0.965 | 0.287 | 2047 / 3286 | [+0.000, +0.001] |
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
| hyb_blend_w0.5_msmarco_minilm_ce_ft_real | 0.935 | 0.945 | 0.901 | 0.916 | 0.337 | 341 / 580 | [-0.018, +0.015] |
| hyb_main_bf1d59b_rrblend_w0.3_msmarco_minilm_ce_ecore | 0.951 | 0.945 | 0.926 | 0.938 | 0.324 | 310 / 538 | [-0.009, +0.008] |
| hyb_rrblend_w0.3_msmarco_minilm_ce_ecore | 0.951 | 0.945 | 0.926 | 0.938 | 0.316 | 305 / 533 | [-0.009, +0.008] |
| hyb_rrblend_w0.2_bge_small_bi_ecore | 0.948 | 0.945 | 0.914 | 0.928 | 0.291 | 546 / 959 | [-0.007, +0.005] |
| hyb_rrblend_w0.1_bge_small_bi_ecore | 0.951 | 0.945 | 0.914 | 0.947 | 0.286 | 546 / 959 | [-0.004, +0.001] |
| hyb_blend_w0.1_bge_reranker_base_ce_ecore | 0.948 | 0.945 | 0.914 | 0.933 | 0.298 | 2047 / 3286 | [-0.013, +0.008] |
| hyb_rrblend_w0.3_msmarco_minilm_ce_ft_real | 0.949 | 0.944 | 0.938 | 0.941 | 0.308 | 341 / 580 | [-0.011, +0.008] |
| hyb_blend_w0.2_bge_reranker_base_ce_ecore | 0.946 | 0.944 | 0.901 | 0.933 | 0.313 | 2047 / 3286 | [-0.015, +0.011] |
| hyb_tie_bge_small_bi_ecore | 0.951 | 0.944 | 0.914 | 0.947 | 0.278 | 546 / 959 | [-0.005, +0.000] |
| hyb_tie_minilm_bi_ecore | 0.951 | 0.944 | 0.914 | 0.947 | 0.284 | 284 / 493 | [-0.005, +0.000] |
| hyb_main_bf1d59b_rrblend_w0.1_msmarco_minilm_ce_ecore | 0.952 | 0.944 | 0.914 | 0.941 | 0.306 | 310 / 538 | [-0.007, +0.002] |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ecore | 0.952 | 0.944 | 0.914 | 0.941 | 0.295 | 305 / 533 | [-0.007, +0.002] |
| hyb_blend_w0.1_msmarco_minilm_ce_ecore | 0.952 | 0.944 | 0.926 | 0.938 | 0.317 | 305 / 533 | [-0.014, +0.010] |
| hyb_main_bf1d59b_blend_w0.1_msmarco_minilm_ce_ecore | 0.952 | 0.944 | 0.926 | 0.938 | 0.320 | 310 / 538 | [-0.014, +0.010] |
| hyb_main_bf1d59b_thr05_msmarco_minilm_ce_ecore | 0.953 | 0.944 | 0.914 | 0.965 | 0.286 | 57 / 221 | [-0.006, +0.000] |
| hyb_thr05_msmarco_minilm_ce_ecore | 0.953 | 0.944 | 0.914 | 0.965 | 0.274 | 49 / 214 | [-0.006, +0.000] |
| hyb_rrblend_w0.2_msmarco_minilm_ce_ft_real | 0.946 | 0.943 | 0.926 | 0.935 | 0.298 | 341 / 580 | [-0.013, +0.007] |
| hyb_thr05_msmarco_minilm_ce_ft_real | 0.953 | 0.943 | 0.914 | 0.965 | 0.273 | 55 / 312 | [-0.007, +0.000] |
| hyb_rrblend_w0.3_bge_small_bi_ecore | 0.949 | 0.943 | 0.901 | 0.947 | 0.291 | 546 / 959 | [-0.007, +0.002] |
| hyb_rrblend_w0.1_msmarco_minilm12_ce_ecore | 0.950 | 0.943 | 0.914 | 0.941 | 0.293 | 595 / 1047 | [-0.007, +0.000] |
| hyb_main_bf1d59b_tie_msmarco_minilm_ce_ecore | 0.950 | 0.943 | 0.914 | 0.941 | 0.300 | 310 / 538 | [-0.008, +0.000] |
| hyb_tie_msmarco_minilm12_ce_ecore | 0.950 | 0.943 | 0.914 | 0.941 | 0.286 | 595 / 1047 | [-0.008, +0.000] |
| hyb_tie_msmarco_minilm_ce_ecore | 0.950 | 0.943 | 0.914 | 0.941 | 0.288 | 305 / 533 | [-0.008, +0.000] |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ft_real | 0.951 | 0.943 | 0.914 | 0.941 | 0.291 | 341 / 580 | [-0.010, +0.002] |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ft_synth | 0.951 | 0.943 | 0.914 | 0.938 | 0.295 | 153 / 372 | [-0.011, +0.003] |
| hyb_rrblend_w0.1_minilm_bi_ecore | 0.950 | 0.943 | 0.914 | 0.947 | 0.292 | 284 / 493 | [-0.008, +0.000] |
| hyb_band05_msmarco_minilm_ce_ecore | 0.951 | 0.943 | 0.926 | 0.936 | 0.304 | 305 / 533 | [-0.016, +0.009] |
| hyb_main_bf1d59b_band05_msmarco_minilm_ce_ecore | 0.951 | 0.943 | 0.926 | 0.936 | 0.309 | 310 / 538 | [-0.016, +0.009] |
| hyb_rrblend_w0.5_msmarco_minilm_ce_ft_real | 0.951 | 0.943 | 0.938 | 0.941 | 0.330 | 341 / 580 | [-0.018, +0.011] |
| hyb_main_bf1d59b_rrblend_w0.1_msmarco_minilm_ce_ft_synth_real | 0.949 | 0.943 | 0.914 | 0.938 | 0.305 | 157 / 347 | [-0.013, +0.002] |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ft_synth_real | 0.949 | 0.943 | 0.914 | 0.938 | 0.293 | 152 / 340 | [-0.013, +0.002] |
| hyb_blend_w0.3_bge_reranker_base_ce_ecore | 0.941 | 0.943 | 0.901 | 0.933 | 0.320 | 2047 / 3286 | [-0.016, +0.007] |
| hyb_blend_w0.1_bge_small_bi_ecore | 0.947 | 0.942 | 0.914 | 0.947 | 0.288 | 546 / 959 | [-0.017, +0.007] |
| hyb_main_bf1d59b_tie_msmarco_minilm_ce_ft_synth_real | 0.948 | 0.942 | 0.914 | 0.941 | 0.300 | 157 / 347 | [-0.010, +0.000] |
| hyb_tie_msmarco_minilm_ce_ft_real | 0.948 | 0.942 | 0.914 | 0.941 | 0.285 | 341 / 580 | [-0.010, +0.000] |
| hyb_tie_msmarco_minilm_ce_ft_synth | 0.948 | 0.942 | 0.914 | 0.941 | 0.288 | 153 / 372 | [-0.010, +0.000] |
| hyb_tie_msmarco_minilm_ce_ft_synth_real | 0.948 | 0.942 | 0.914 | 0.941 | 0.288 | 152 / 340 | [-0.010, +0.000] |
| hyb_band05_bge_reranker_base_ce_ecore | 0.946 | 0.942 | 0.914 | 0.933 | 0.288 | 2047 / 3286 | [-0.016, +0.006] |
| hyb_thr05_minilm_bi_ecore | 0.946 | 0.942 | 0.901 | 0.965 | 0.263 | 46 / 204 | [-0.012, +0.000] |
| hyb_rrblend_w0.3_msmarco_minilm12_ce_ecore | 0.945 | 0.942 | 0.914 | 0.941 | 0.308 | 595 / 1047 | [-0.013, +0.005] |
| hyb_top10_bge_reranker_base_ce_ecore | 0.950 | 0.941 | 0.938 | 0.928 | 0.276 | 513 / 823 | [-0.010, +0.001] |
| hyb_main_bf1d59b_rrblend_w0.3_msmarco_minilm_ce_ft_synth_real | 0.949 | 0.941 | 0.926 | 0.938 | 0.318 | 157 / 347 | [-0.019, +0.007] |
| hyb_rrblend_w0.3_msmarco_minilm_ce_ft_synth_real | 0.949 | 0.941 | 0.926 | 0.938 | 0.309 | 152 / 340 | [-0.019, +0.007] |
| hyb_main_bf1d59b_rrblend_w0.2_msmarco_minilm_ce_ft_synth_real | 0.945 | 0.941 | 0.914 | 0.935 | 0.312 | 157 / 347 | [-0.016, +0.005] |
| hyb_rrblend_w0.2_msmarco_minilm_ce_ft_synth_real | 0.945 | 0.941 | 0.914 | 0.935 | 0.302 | 152 / 340 | [-0.016, +0.005] |
| hyb_blend_w0.2_bge_small_bi_ecore | 0.942 | 0.941 | 0.914 | 0.965 | 0.292 | 546 / 959 | [-0.025, +0.010] |
| hyb_blend_w0.3_bge_small_bi_ecore | 0.942 | 0.941 | 0.914 | 0.965 | 0.295 | 546 / 959 | [-0.025, +0.010] |
| hyb_top10_bge_small_bi_ecore | 0.939 | 0.941 | 0.914 | 0.947 | 0.268 | 138 / 241 | [-0.014, +0.002] |
| hyb_main_bf1d59b_rrblend_w0.2_msmarco_minilm_ce_ecore | 0.946 | 0.941 | 0.914 | 0.935 | 0.313 | 310 / 538 | [-0.012, +0.001] |
| hyb_rrblend_w0.2_msmarco_minilm_ce_ecore | 0.946 | 0.941 | 0.914 | 0.935 | 0.304 | 305 / 533 | [-0.012, +0.001] |
| hyb_rrblend_w0.2_msmarco_minilm12_ce_ecore | 0.939 | 0.940 | 0.901 | 0.935 | 0.299 | 595 / 1047 | [-0.013, +0.000] |
| hyb_top10_minilm_bi_ecore | 0.941 | 0.940 | 0.914 | 0.941 | 0.268 | 72 / 124 | [-0.014, +0.001] |
| hyb_thr05_bge_small_bi_ecore | 0.942 | 0.940 | 0.889 | 0.947 | 0.261 | 87 / 374 | [-0.017, +0.000] |
| hyb_rrblend_w0.2_msmarco_minilm_ce_ft_synth | 0.944 | 0.939 | 0.914 | 0.935 | 0.302 | 153 / 372 | [-0.017, +0.001] |
| bm25 | 0.932 | 0.939 | 0.889 | 0.917 | 0.301 | 0 / 1 | [-0.034, +0.014] |
| hyb_blend_w0.5_bge_reranker_base_ce_ecore | 0.929 | 0.938 | 0.877 | 0.887 | 0.320 | 2047 / 3286 | [-0.027, +0.010] |
| msmarco_minilm_ce_onnx_int8_t4 | 0.928 | 0.936 | 0.877 | 0.920 | 0.314 | 150 / 288 | [-0.033, +0.010] |
| msmarco_minilm_ce_onnx_int8_t8 | 0.928 | 0.936 | 0.877 | 0.920 | 0.314 | 97 / 200 | [-0.033, +0.010] |
| msmarco_minilm12_ce_ecore | 0.925 | 0.935 | 0.877 | 0.934 | 0.307 | 593 / 1046 | [-0.042, +0.019] |
| msmarco_minilm12_ce_p4 | 0.925 | 0.935 | 0.877 | 0.934 | 0.307 | 570 / 999 | [-0.042, +0.019] |
| msmarco_minilm12_ce_p8 | 0.925 | 0.935 | 0.877 | 0.934 | 0.307 | 319 / 555 | [-0.042, +0.019] |
| msmarco_minilm_ce_ft_real | 0.930 | 0.935 | 0.889 | 0.915 | 0.318 | 339 / 578 | [-0.037, +0.010] |
| msmarco_minilm_ce_ecore | 0.929 | 0.934 | 0.877 | 0.907 | 0.322 | 303 / 531 | [-0.039, +0.011] |
| msmarco_minilm_ce_onnx_t4 | 0.929 | 0.934 | 0.877 | 0.907 | 0.322 | 297 / 534 | [-0.039, +0.011] |
| msmarco_minilm_ce_onnx_t8 | 0.929 | 0.934 | 0.877 | 0.907 | 0.322 | 183 / 335 | [-0.039, +0.011] |
| msmarco_minilm_ce_p4 | 0.929 | 0.934 | 0.877 | 0.907 | 0.322 | 290 / 512 | [-0.039, +0.011] |
| msmarco_minilm_ce_p8 | 0.929 | 0.934 | 0.877 | 0.907 | 0.322 | 160 / 283 | [-0.039, +0.011] |
| hyb_blend_w0.5_bge_small_bi_ecore | 0.931 | 0.930 | 0.889 | 0.940 | 0.284 | 546 / 959 | [-0.045, +0.007] |
| learned_pairwise | 0.926 | 0.930 | 0.864 | 0.931 | 0.268 | 0 / 0 | [-0.040, -0.000] |
| learned_ridge | 0.926 | 0.930 | 0.864 | 0.931 | 0.261 | 0 / 0 | [-0.040, +0.001] |
| minilm_bi_ecore | 0.920 | 0.928 | 0.864 | 0.917 | 0.276 | 282 / 491 | [-0.042, +0.005] |
| minilm_bi_p4 | 0.920 | 0.928 | 0.864 | 0.917 | 0.276 | 269 / 479 | [-0.042, +0.005] |
| minilm_bi_p8 | 0.920 | 0.928 | 0.864 | 0.917 | 0.276 | 153 / 272 | [-0.042, +0.005] |
| bge_reranker_base_ce_ecore | 0.904 | 0.919 | 0.840 | 0.863 | 0.272 | 2046 / 3285 | [-0.059, +0.000] |
| bge_reranker_base_ce_p4 | 0.904 | 0.919 | 0.840 | 0.863 | 0.272 | 2147 / 3488 | [-0.059, +0.000] |
| bge_reranker_base_ce_p8 | 0.904 | 0.919 | 0.840 | 0.863 | 0.272 | 1169 / 1894 | [-0.059, +0.000] |
| bge_small_bi_ecore | 0.908 | 0.913 | 0.852 | 0.922 | 0.220 | 544 / 958 | [-0.066, -0.005] |
| bge_small_bi_p4 | 0.908 | 0.913 | 0.852 | 0.922 | 0.220 | 532 / 947 | [-0.066, -0.005] |
| bge_small_bi_p8 | 0.908 | 0.913 | 0.852 | 0.922 | 0.220 | 292 / 567 | [-0.066, -0.005] |
| distributor_order | 0.875 | 0.884 | 0.815 | 0.870 | 0.161 | 0 / 0 | [-0.118, -0.016] |

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
| hyb_blend_w0.1_msmarco_minilm_ce_ft_real | 0.983 | 1.000 | 1.000 | 0.988 | 0.593 |
| hyb_top10_msmarco_minilm_ce_ft_synth | 0.988 | 0.984 | 1.000 | 0.988 | 0.604 |
| hyb_blend_w0.5_msmarco_minilm_ce_ecore | 0.986 | 1.000 | 0.984 | 1.000 | 0.593 |
| hyb_main_bf1d59b_blend_w0.5_msmarco_minilm_ce_ecore | 0.986 | 1.000 | 0.984 | 1.000 | 0.593 |
| hyb_main_bf1d59b_top10_msmarco_minilm_ce_ecore | 0.988 | 0.984 | 1.000 | 0.988 | 0.600 |
| hyb_top10_msmarco_minilm_ce_ecore | 0.988 | 0.984 | 1.000 | 0.988 | 0.600 |
| hyb_rrblend_w0.1_bge_reranker_base_ce_ecore | 0.988 | 0.977 | 1.000 | 0.988 | 0.613 |
| hyb_main_bf1d59b_top10_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.984 | 1.000 | 0.988 | 0.597 |
| hyb_top10_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.984 | 1.000 | 0.988 | 0.597 |
| hyb_tie_bge_reranker_base_ce_ecore | 0.988 | 0.976 | 1.000 | 0.988 | 0.613 |
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
| hyb_blend_w0.5_msmarco_minilm_ce_ft_real | 0.986 | 1.000 | 0.988 | 1.000 | 0.574 |
| hyb_main_bf1d59b_rrblend_w0.3_msmarco_minilm_ce_ecore | 0.988 | 0.992 | 1.000 | 1.000 | 0.561 |
| hyb_rrblend_w0.3_msmarco_minilm_ce_ecore | 0.988 | 0.992 | 1.000 | 1.000 | 0.561 |
| hyb_rrblend_w0.2_bge_small_bi_ecore | 0.988 | 0.979 | 1.000 | 0.988 | 0.596 |
| hyb_rrblend_w0.1_bge_small_bi_ecore | 0.988 | 0.977 | 1.000 | 0.988 | 0.599 |
| hyb_blend_w0.1_bge_reranker_base_ce_ecore | 0.988 | 0.987 | 1.000 | 0.988 | 0.579 |
| hyb_rrblend_w0.3_msmarco_minilm_ce_ft_real | 0.988 | 0.992 | 1.000 | 1.000 | 0.555 |
| hyb_blend_w0.2_bge_reranker_base_ce_ecore | 0.989 | 0.987 | 0.989 | 0.988 | 0.593 |
| hyb_tie_bge_small_bi_ecore | 0.988 | 0.976 | 1.000 | 0.988 | 0.596 |
| hyb_tie_minilm_bi_ecore | 0.988 | 0.976 | 1.000 | 0.988 | 0.596 |
| hyb_main_bf1d59b_rrblend_w0.1_msmarco_minilm_ce_ecore | 0.988 | 0.980 | 1.000 | 0.988 | 0.588 |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ecore | 0.988 | 0.980 | 1.000 | 0.988 | 0.588 |
| hyb_blend_w0.1_msmarco_minilm_ce_ecore | 0.983 | 1.000 | 0.989 | 0.988 | 0.580 |
| hyb_main_bf1d59b_blend_w0.1_msmarco_minilm_ce_ecore | 0.983 | 1.000 | 0.989 | 0.988 | 0.580 |
| hyb_main_bf1d59b_thr05_msmarco_minilm_ce_ecore | 0.983 | 0.976 | 1.000 | 0.988 | 0.610 |
| hyb_thr05_msmarco_minilm_ce_ecore | 0.983 | 0.976 | 1.000 | 0.988 | 0.610 |
| hyb_rrblend_w0.2_msmarco_minilm_ce_ft_real | 0.988 | 0.991 | 1.000 | 1.000 | 0.549 |
| hyb_thr05_msmarco_minilm_ce_ft_real | 0.982 | 0.976 | 1.000 | 0.988 | 0.610 |
| hyb_rrblend_w0.3_bge_small_bi_ecore | 0.986 | 0.981 | 1.000 | 0.988 | 0.586 |
| hyb_rrblend_w0.1_msmarco_minilm12_ce_ecore | 0.988 | 0.977 | 1.000 | 0.988 | 0.587 |
| hyb_main_bf1d59b_tie_msmarco_minilm_ce_ecore | 0.988 | 0.976 | 1.000 | 0.988 | 0.587 |
| hyb_tie_msmarco_minilm12_ce_ecore | 0.988 | 0.976 | 1.000 | 0.988 | 0.587 |
| hyb_tie_msmarco_minilm_ce_ecore | 0.988 | 0.976 | 1.000 | 0.988 | 0.587 |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ft_real | 0.988 | 0.980 | 1.000 | 0.988 | 0.581 |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ft_synth | 0.988 | 0.980 | 1.000 | 0.988 | 0.580 |
| hyb_rrblend_w0.1_minilm_bi_ecore | 0.988 | 0.977 | 1.000 | 0.988 | 0.582 |
| hyb_band05_msmarco_minilm_ce_ecore | 0.983 | 1.000 | 0.984 | 0.988 | 0.581 |
| hyb_main_bf1d59b_band05_msmarco_minilm_ce_ecore | 0.983 | 1.000 | 0.984 | 0.988 | 0.581 |
| hyb_rrblend_w0.5_msmarco_minilm_ce_ft_real | 0.987 | 0.994 | 1.000 | 1.000 | 0.540 |
| hyb_main_bf1d59b_rrblend_w0.1_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.980 | 1.000 | 0.988 | 0.575 |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.980 | 1.000 | 0.988 | 0.575 |
| hyb_blend_w0.3_bge_reranker_base_ce_ecore | 0.992 | 0.987 | 0.989 | 0.988 | 0.566 |
| hyb_blend_w0.1_bge_small_bi_ecore | 0.988 | 0.994 | 0.987 | 0.987 | 0.567 |
| hyb_main_bf1d59b_tie_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.976 | 1.000 | 0.988 | 0.579 |
| hyb_tie_msmarco_minilm_ce_ft_real | 0.988 | 0.976 | 1.000 | 0.988 | 0.579 |
| hyb_tie_msmarco_minilm_ce_ft_synth | 0.988 | 0.976 | 1.000 | 0.988 | 0.579 |
| hyb_tie_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.976 | 1.000 | 0.988 | 0.579 |
| hyb_band05_bge_reranker_base_ce_ecore | 0.989 | 0.987 | 0.988 | 0.988 | 0.574 |
| hyb_thr05_minilm_bi_ecore | 0.977 | 0.976 | 1.000 | 0.988 | 0.610 |
| hyb_rrblend_w0.3_msmarco_minilm12_ce_ecore | 0.988 | 0.987 | 1.000 | 0.988 | 0.553 |
| hyb_top10_bge_reranker_base_ce_ecore | 0.988 | 0.970 | 1.000 | 0.988 | 0.585 |
| hyb_main_bf1d59b_rrblend_w0.3_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.992 | 1.000 | 1.000 | 0.528 |
| hyb_rrblend_w0.3_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.992 | 1.000 | 1.000 | 0.528 |
| hyb_main_bf1d59b_rrblend_w0.2_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.990 | 1.000 | 0.988 | 0.541 |
| hyb_rrblend_w0.2_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.990 | 1.000 | 0.988 | 0.541 |
| hyb_blend_w0.2_bge_small_bi_ecore | 0.988 | 0.994 | 0.985 | 0.987 | 0.558 |
| hyb_blend_w0.3_bge_small_bi_ecore | 0.988 | 0.994 | 0.985 | 0.986 | 0.558 |
| hyb_top10_bge_small_bi_ecore | 0.980 | 0.983 | 1.000 | 0.986 | 0.582 |
| hyb_main_bf1d59b_rrblend_w0.2_msmarco_minilm_ce_ecore | 0.988 | 0.980 | 1.000 | 0.988 | 0.560 |
| hyb_rrblend_w0.2_msmarco_minilm_ce_ecore | 0.988 | 0.980 | 1.000 | 0.988 | 0.560 |
| hyb_rrblend_w0.2_msmarco_minilm12_ce_ecore | 0.988 | 0.977 | 1.000 | 0.988 | 0.561 |
| hyb_top10_minilm_bi_ecore | 0.982 | 0.983 | 1.000 | 0.988 | 0.566 |
| hyb_thr05_bge_small_bi_ecore | 0.973 | 0.976 | 1.000 | 0.986 | 0.610 |
| hyb_rrblend_w0.2_msmarco_minilm_ce_ft_synth | 0.988 | 0.980 | 1.000 | 0.988 | 0.542 |
| bm25 | 0.991 | 1.000 | 0.976 | 0.988 | 0.533 |
| hyb_blend_w0.5_bge_reranker_base_ce_ecore | 0.996 | 0.979 | 0.988 | 1.000 | 0.512 |
| msmarco_minilm_ce_onnx_int8_t4 | 0.968 | 0.994 | 0.981 | 1.000 | 0.579 |
| msmarco_minilm_ce_onnx_int8_t8 | 0.968 | 0.994 | 0.981 | 1.000 | 0.579 |
| msmarco_minilm12_ce_ecore | 0.967 | 0.991 | 0.976 | 1.000 | 0.587 |
| msmarco_minilm12_ce_p4 | 0.967 | 0.991 | 0.976 | 1.000 | 0.587 |
| msmarco_minilm12_ce_p8 | 0.967 | 0.991 | 0.976 | 1.000 | 0.587 |
| msmarco_minilm_ce_ft_real | 0.968 | 0.994 | 0.988 | 1.000 | 0.550 |
| msmarco_minilm_ce_ecore | 0.962 | 1.000 | 0.984 | 1.000 | 0.561 |
| msmarco_minilm_ce_onnx_t4 | 0.962 | 1.000 | 0.984 | 1.000 | 0.561 |
| msmarco_minilm_ce_onnx_t8 | 0.962 | 1.000 | 0.984 | 1.000 | 0.561 |
| msmarco_minilm_ce_p4 | 0.962 | 1.000 | 0.984 | 1.000 | 0.561 |
| msmarco_minilm_ce_p8 | 0.962 | 1.000 | 0.984 | 1.000 | 0.561 |
| hyb_blend_w0.5_bge_small_bi_ecore | 0.987 | 0.994 | 0.966 | 0.949 | 0.530 |
| learned_pairwise | 0.988 | 0.954 | 0.993 | 0.988 | 0.522 |
| learned_ridge | 0.988 | 0.954 | 0.985 | 1.000 | 0.522 |
| minilm_bi_ecore | 0.962 | 0.967 | 1.000 | 0.937 | 0.604 |
| minilm_bi_p4 | 0.962 | 0.967 | 1.000 | 0.937 | 0.604 |
| minilm_bi_p8 | 0.962 | 0.967 | 1.000 | 0.937 | 0.604 |
| bge_reranker_base_ce_ecore | 0.981 | 0.924 | 0.988 | 1.000 | 0.504 |
| bge_reranker_base_ce_p4 | 0.981 | 0.924 | 0.988 | 1.000 | 0.504 |
| bge_reranker_base_ce_p8 | 0.981 | 0.924 | 0.988 | 1.000 | 0.504 |
| bge_small_bi_ecore | 0.942 | 0.994 | 0.969 | 0.937 | 0.534 |
| bge_small_bi_p4 | 0.942 | 0.994 | 0.969 | 0.937 | 0.534 |
| bge_small_bi_p8 | 0.942 | 0.994 | 0.969 | 0.937 | 0.534 |
| distributor_order | 0.948 | 0.964 | 0.922 | 0.815 | 0.516 |
