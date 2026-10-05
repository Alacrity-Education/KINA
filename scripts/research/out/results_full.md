### Results (full candidate sets, 32 queries)

| method | NDCG@5 | NDCG@10 | P@3 | MRR | Spearman | latency/query ms (mean / max) | dNDCG@10 vs det, 95% CI |
|---|---|---|---|---|---|---|---|
| hyb_main_bf1d59b_rrblend_w0.5_msmarco_minilm_ce_ft_synth_real | 0.947 | 0.917 | 0.906 | 0.975 | 0.703 | 156 / 347 | [+0.007, +0.046] |
| hyb_rrblend_w0.5_msmarco_minilm_ce_ft_synth_real | 0.945 | 0.917 | 0.906 | 0.975 | 0.701 | 151 / 340 | [+0.007, +0.045] |
| hyb_rrblend_w0.5_msmarco_minilm_ce_ft_synth | 0.946 | 0.914 | 0.906 | 0.964 | 0.693 | 152 / 372 | [+0.005, +0.042] |
| hyb_main_bf1d59b_rrblend_w0.5_msmarco_minilm_ce_ecore | 0.950 | 0.913 | 0.917 | 0.953 | 0.671 | 308 / 538 | [+0.003, +0.042] |
| hyb_rrblend_w0.5_bge_reranker_base_ce_ecore | 0.934 | 0.913 | 0.885 | 0.973 | 0.660 | 2029 / 3286 | [+0.000, +0.044] |
| hyb_rrblend_w0.5_msmarco_minilm_ce_ecore | 0.946 | 0.913 | 0.917 | 0.953 | 0.666 | 303 / 533 | [+0.003, +0.041] |
| hyb_band05_msmarco_minilm_ce_ft_synth_real | 0.948 | 0.913 | 0.917 | 0.984 | 0.697 | 151 / 340 | [+0.003, +0.041] |
| hyb_main_bf1d59b_band05_msmarco_minilm_ce_ft_synth_real | 0.948 | 0.913 | 0.917 | 0.984 | 0.700 | 156 / 347 | [+0.003, +0.041] |
| hyb_band05_msmarco_minilm_ce_ft_synth | 0.938 | 0.912 | 0.896 | 0.984 | 0.699 | 152 / 372 | [-0.003, +0.043] |
| hyb_main_bf1d59b_blend_w0.1_msmarco_minilm_ce_ft_synth_real | 0.955 | 0.911 | 0.917 | 0.984 | 0.696 | 156 / 347 | [+0.002, +0.039] |
| hyb_blend_w0.1_msmarco_minilm_ce_ft_synth_real | 0.952 | 0.911 | 0.917 | 0.984 | 0.696 | 151 / 340 | [+0.002, +0.038] |
| hyb_rrblend_w0.3_bge_reranker_base_ce_ecore | 0.934 | 0.910 | 0.885 | 0.977 | 0.668 | 2029 / 3286 | [+0.004, +0.035] |
| hyb_blend_w0.1_msmarco_minilm_ce_ft_synth | 0.947 | 0.910 | 0.906 | 0.964 | 0.695 | 152 / 372 | [-0.005, +0.041] |
| hyb_main_bf1d59b_rrblend_w0.3_msmarco_minilm_ce_ecore | 0.926 | 0.909 | 0.896 | 0.945 | 0.680 | 308 / 538 | [+0.004, +0.034] |
| hyb_rrblend_w0.3_msmarco_minilm_ce_ecore | 0.921 | 0.909 | 0.896 | 0.945 | 0.673 | 303 / 533 | [+0.004, +0.032] |
| hyb_blend_w0.2_msmarco_minilm_ce_ft_synth | 0.936 | 0.908 | 0.896 | 0.961 | 0.698 | 152 / 372 | [-0.011, +0.041] |
| hyb_rrblend_w0.5_msmarco_minilm12_ce_ecore | 0.938 | 0.907 | 0.896 | 0.984 | 0.666 | 590 / 1047 | [-0.004, +0.036] |
| hyb_rrblend_w0.3_laya_ft_ml_full2 | 0.930 | 0.907 | 0.885 | 0.952 | 0.668 | 1968 / 2469 | [-0.004, +0.035] |
| hyb_rrblend_w0.5_msmarco_minilm_ce_ft_real | 0.936 | 0.906 | 0.917 | 0.964 | 0.687 | 330 / 580 | [-0.004, +0.033] |
| hyb_blend_w0.2_msmarco_minilm_ce_ft_synth_real | 0.946 | 0.906 | 0.906 | 0.971 | 0.697 | 151 / 340 | [-0.011, +0.039] |
| hyb_main_bf1d59b_blend_w0.2_msmarco_minilm_ce_ft_synth_real | 0.946 | 0.906 | 0.906 | 0.971 | 0.694 | 156 / 347 | [-0.011, +0.039] |
| hyb_blend_w0.3_msmarco_minilm_ce_ft_synth | 0.922 | 0.905 | 0.875 | 0.947 | 0.696 | 152 / 372 | [-0.018, +0.043] |
| hyb_band05_msmarco_minilm12_ce_ecore | 0.928 | 0.905 | 0.885 | 0.984 | 0.684 | 590 / 1047 | [-0.023, +0.044] |
| hyb_rrblend_w0.3_msmarco_minilm_ce_ft_synth | 0.919 | 0.905 | 0.896 | 0.948 | 0.680 | 152 / 372 | [+0.000, +0.028] |
| hyb_main_bf1d59b_rrblend_w0.3_msmarco_minilm_ce_ft_synth_real | 0.929 | 0.905 | 0.896 | 0.961 | 0.692 | 156 / 347 | [-0.003, +0.031] |
| hyb_rrblend_w0.3_msmarco_minilm_ce_ft_real | 0.915 | 0.905 | 0.896 | 0.964 | 0.677 | 330 / 580 | [+0.002, +0.028] |
| hyb_rrblend_w0.3_msmarco_minilm_ce_ft_synth_real | 0.922 | 0.905 | 0.896 | 0.961 | 0.684 | 151 / 340 | [-0.003, +0.030] |
| hyb_band05_bge_reranker_base_ce_ecore | 0.936 | 0.905 | 0.906 | 0.964 | 0.675 | 2029 / 3286 | [-0.011, +0.033] |
| hyb_rrblend_w0.2_msmarco_minilm_ce_ft_real | 0.909 | 0.905 | 0.885 | 0.958 | 0.670 | 330 / 580 | [+0.001, +0.027] |
| hyb_main_bf1d59b_top10_msmarco_minilm_ce_ecore | 0.946 | 0.904 | 0.906 | 0.969 | 0.647 | 83 / 139 | [-0.001, +0.029] |
| hyb_main_bf1d59b_top10_msmarco_minilm_ce_ft_synth_real | 0.945 | 0.904 | 0.906 | 0.973 | 0.646 | 45 / 95 | [-0.001, +0.029] |
| hyb_rrblend_w0.2_laya_td_noul | 0.926 | 0.904 | 0.885 | 0.961 | 0.655 | 6852 / 8512 | [-0.004, +0.028] |
| hyb_blend_w0.3_msmarco_minilm_ce_ft_synth_real | 0.941 | 0.904 | 0.906 | 0.970 | 0.696 | 151 / 340 | [-0.018, +0.040] |
| hyb_main_bf1d59b_blend_w0.3_msmarco_minilm_ce_ft_synth_real | 0.941 | 0.904 | 0.906 | 0.970 | 0.694 | 156 / 347 | [-0.018, +0.040] |
| hyb_top10_msmarco_minilm_ce_ecore | 0.944 | 0.904 | 0.906 | 0.969 | 0.641 | 78 / 134 | [-0.001, +0.028] |
| hyb_top10_msmarco_minilm_ce_ft_real | 0.935 | 0.904 | 0.917 | 0.984 | 0.639 | 87 / 195 | [+0.002, +0.025] |
| hyb_top10_msmarco_minilm_ce_ft_synth_real | 0.943 | 0.904 | 0.906 | 0.973 | 0.639 | 39 / 88 | [-0.001, +0.028] |
| hyb_main_bf1d59b_rrblend_w0.2_msmarco_minilm_ce_ft_synth_real | 0.915 | 0.903 | 0.875 | 0.958 | 0.684 | 156 / 347 | [-0.002, +0.029] |
| hyb_top10_msmarco_minilm_ce_ft_synth | 0.943 | 0.903 | 0.906 | 0.964 | 0.641 | 40 / 94 | [-0.002, +0.028] |
| hyb_top10_msmarco_minilm12_ce_ecore | 0.933 | 0.903 | 0.906 | 0.984 | 0.639 | 150 / 263 | [+0.001, +0.026] |
| hyb_rrblend_w0.2_msmarco_minilm_ce_ft_synth_real | 0.909 | 0.903 | 0.875 | 0.958 | 0.674 | 151 / 340 | [-0.002, +0.027] |
| hyb_rrblend_w0.2_minilm_bi_ecore | 0.913 | 0.903 | 0.896 | 0.969 | 0.669 | 282 / 493 | [+0.002, +0.021] |
| hyb_band05_minilm_bi_ecore | 0.916 | 0.903 | 0.865 | 0.961 | 0.689 | 282 / 493 | [-0.009, +0.029] |
| hyb_blend_w0.1_minilm_bi_ecore | 0.921 | 0.902 | 0.885 | 0.956 | 0.688 | 282 / 493 | [-0.005, +0.026] |
| hyb_band05_bge_small_bi_ecore | 0.915 | 0.902 | 0.875 | 0.984 | 0.665 | 545 / 959 | [-0.010, +0.032] |
| hyb_rrblend_w0.3_msmarco_minilm12_ce_ecore | 0.917 | 0.902 | 0.885 | 0.964 | 0.672 | 590 / 1047 | [-0.002, +0.025] |
| hyb_blend_w0.1_bge_reranker_base_ce_ecore | 0.925 | 0.902 | 0.896 | 0.943 | 0.672 | 2029 / 3286 | [-0.011, +0.029] |
| hyb_rrblend_w0.3_bge_small_bi_ecore | 0.912 | 0.902 | 0.833 | 0.969 | 0.659 | 545 / 959 | [-0.000, +0.023] |
| hyb_band05_msmarco_minilm_ce_ft_real | 0.930 | 0.901 | 0.906 | 0.969 | 0.683 | 330 / 580 | [-0.015, +0.031] |
| hyb_blend_w0.1_msmarco_minilm_ce_ft_real | 0.931 | 0.901 | 0.917 | 0.969 | 0.682 | 330 / 580 | [-0.009, +0.026] |
| hyb_rrblend_w0.5_laya_ft_ml_full2 | 0.915 | 0.901 | 0.875 | 0.946 | 0.661 | 1968 / 2469 | [-0.019, +0.038] |
| hyb_band05_msmarco_minilm_ce_ecore | 0.930 | 0.901 | 0.896 | 0.959 | 0.686 | 303 / 533 | [-0.025, +0.036] |
| hyb_main_bf1d59b_band05_msmarco_minilm_ce_ecore | 0.930 | 0.901 | 0.896 | 0.959 | 0.688 | 308 / 538 | [-0.025, +0.036] |
| hyb_rrblend_w0.2_bge_reranker_base_ce_ecore | 0.919 | 0.901 | 0.885 | 0.969 | 0.662 | 2029 / 3286 | [+0.003, +0.015] |
| hyb_rrblend_w0.2_msmarco_minilm_ce_ft_synth | 0.909 | 0.900 | 0.875 | 0.958 | 0.672 | 152 / 372 | [-0.004, +0.024] |
| hyb_main_bf1d59b_rrblend_w0.2_msmarco_minilm_ce_ecore | 0.920 | 0.900 | 0.875 | 0.958 | 0.674 | 308 / 538 | [-0.001, +0.024] |
| hyb_rrblend_w0.2_laya_ft_ml_full2 | 0.922 | 0.900 | 0.885 | 0.958 | 0.661 | 1968 / 2469 | [-0.006, +0.024] |
| hyb_rrblend_w0.2_bge_small_bi_ecore | 0.907 | 0.900 | 0.865 | 0.953 | 0.657 | 545 / 959 | [-0.002, +0.020] |
| hyb_rrblend_w0.3_minilm_bi_ecore | 0.921 | 0.900 | 0.896 | 0.964 | 0.674 | 282 / 493 | [-0.004, +0.021] |
| hyb_rrblend_w0.2_msmarco_minilm_ce_ecore | 0.912 | 0.899 | 0.875 | 0.958 | 0.666 | 303 / 533 | [-0.001, +0.021] |
| hyb_rrblend_w0.1_bge_small_bi_ecore | 0.912 | 0.899 | 0.865 | 0.969 | 0.656 | 545 / 959 | [-0.001, +0.018] |
| hyb_rrblend_w0.2_msmarco_minilm12_ce_ecore | 0.908 | 0.899 | 0.865 | 0.958 | 0.666 | 590 / 1047 | [-0.003, +0.021] |
| hyb_blend_w0.5_msmarco_minilm_ce_ft_synth_real | 0.922 | 0.899 | 0.875 | 0.971 | 0.698 | 151 / 340 | [-0.033, +0.041] |
| hyb_main_bf1d59b_blend_w0.5_msmarco_minilm_ce_ft_synth_real | 0.922 | 0.899 | 0.875 | 0.971 | 0.696 | 156 / 347 | [-0.033, +0.041] |
| hyb_rrblend_w0.5_minilm_bi_ecore | 0.929 | 0.898 | 0.885 | 0.954 | 0.676 | 282 / 493 | [-0.012, +0.025] |
| hyb_main_bf1d59b_thr05_msmarco_minilm_ce_ft_synth_real | 0.921 | 0.898 | 0.865 | 0.984 | 0.643 | 27 / 109 | [+0.000, +0.019] |
| det_main_bf1d59b | 0.917 | 0.898 | 0.865 | 0.984 | 0.641 | 7 / 14 | [+0.000, +0.017] |
| hyb_main_bf1d59b_rrblend_w0.1_msmarco_minilm_ce_ecore | 0.916 | 0.897 | 0.865 | 0.964 | 0.669 | 308 / 538 | [-0.004, +0.020] |
| hyb_blend_w0.5_msmarco_minilm_ce_ft_synth | 0.915 | 0.897 | 0.875 | 0.944 | 0.688 | 152 / 372 | [-0.044, +0.044] |
| hyb_rrblend_w0.2_laya_ml_score | 0.912 | 0.897 | 0.865 | 0.958 | 0.636 | 2784 / 3793 | [-0.006, +0.017] |
| hyb_main_bf1d59b_rrblend_w0.1_msmarco_minilm_ce_ft_synth_real | 0.917 | 0.897 | 0.865 | 0.961 | 0.674 | 156 / 347 | [-0.007, +0.020] |
| hyb_blend_w0.1_msmarco_minilm12_ce_ecore | 0.923 | 0.897 | 0.885 | 0.959 | 0.678 | 590 / 1047 | [-0.032, +0.032] |
| hyb_main_bf1d59b_thr05_msmarco_minilm_ce_ecore | 0.921 | 0.897 | 0.865 | 0.984 | 0.642 | 51 / 221 | [-0.005, +0.019] |
| hyb_main_bf1d59b_blend_w0.1_msmarco_minilm_ce_ecore | 0.934 | 0.896 | 0.885 | 0.938 | 0.679 | 308 / 538 | [-0.034, +0.033] |
| hyb_rrblend_w0.3_laya_td_noul | 0.923 | 0.896 | 0.896 | 0.937 | 0.652 | 6852 / 8512 | [-0.016, +0.024] |
| hyb_main_bf1d59b_tie_msmarco_minilm_ce_ecore | 0.914 | 0.896 | 0.865 | 0.964 | 0.665 | 308 / 538 | [-0.006, +0.017] |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ecore | 0.910 | 0.896 | 0.865 | 0.964 | 0.659 | 303 / 533 | [-0.004, +0.014] |
| hyb_blend_w0.1_msmarco_minilm_ce_ecore | 0.930 | 0.896 | 0.885 | 0.938 | 0.678 | 303 / 533 | [-0.035, +0.032] |
| hyb_main_bf1d59b_tie_msmarco_minilm_ce_ft_synth_real | 0.914 | 0.895 | 0.865 | 0.964 | 0.669 | 156 / 347 | [-0.007, +0.018] |
| hyb_top10_bge_small_bi_ecore | 0.910 | 0.895 | 0.875 | 0.969 | 0.633 | 139 / 241 | [-0.008, +0.017] |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ft_synth_real | 0.912 | 0.895 | 0.865 | 0.961 | 0.664 | 151 / 340 | [-0.007, +0.015] |
| hyb_rrblend_w0.1_laya_ft_ml_full2 | 0.910 | 0.895 | 0.885 | 0.948 | 0.658 | 1968 / 2469 | [-0.004, +0.010] |
| hyb_blend_w0.1_bge_small_bi_ecore | 0.919 | 0.895 | 0.885 | 0.969 | 0.664 | 545 / 959 | [-0.012, +0.018] |
| hyb_rrblend_w0.1_bge_reranker_base_ce_ecore | 0.910 | 0.895 | 0.875 | 0.969 | 0.657 | 2029 / 3286 | [+0.000, +0.006] |
| hyb_top10_laya_ft_ml_full2 | 0.912 | 0.895 | 0.854 | 0.948 | 0.633 | 501 / 618 | [-0.013, +0.020] |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ft_synth | 0.910 | 0.895 | 0.865 | 0.961 | 0.663 | 152 / 372 | [-0.007, +0.014] |
| hyb_rrblend_w0.1_laya_ml_pair_k8 | 0.908 | 0.895 | 0.875 | 0.964 | 0.634 | 15698 / 20553 | [-0.006, +0.011] |
| hyb_main_bf1d59b_tie_laya_ml_noul | 0.910 | 0.894 | 0.854 | 0.964 | 0.645 | 2407 / 3147 | [-0.008, +0.017] |
| msmarco_minilm_ce_ft_synth_real | 0.901 | 0.894 | 0.854 | 0.919 | 0.695 | 149 / 335 | [-0.052, +0.051] |
| hyb_rrblend_w0.5_bge_small_bi_ecore | 0.909 | 0.894 | 0.854 | 0.969 | 0.630 | 545 / 959 | [-0.013, +0.018] |
| hyb_blend_w0.2_msmarco_minilm12_ce_ecore | 0.913 | 0.894 | 0.875 | 0.957 | 0.668 | 590 / 1047 | [-0.047, +0.038] |
| hyb_main_bf1d59b_rrblend_w0.1_laya_ml_noul | 0.915 | 0.894 | 0.844 | 0.964 | 0.648 | 2407 / 3147 | [-0.012, +0.018] |
| hyb_top10_bge_reranker_base_ce_ecore | 0.929 | 0.893 | 0.896 | 0.904 | 0.640 | 514 / 823 | [-0.010, +0.014] |
| msmarco_minilm_ce_ft_synth | 0.909 | 0.893 | 0.865 | 0.943 | 0.681 | 150 / 371 | [-0.060, +0.052] |
| hyb_tie_laya_ml_score | 0.910 | 0.893 | 0.875 | 0.969 | 0.639 | 2784 / 3793 | [+0.000, +0.003] |
| hyb_rrblend_w0.1_minilm_bi_ecore | 0.906 | 0.893 | 0.875 | 0.953 | 0.660 | 282 / 493 | [-0.003, +0.004] |
| hyb_blend_w0.2_bge_small_bi_ecore | 0.916 | 0.893 | 0.885 | 0.984 | 0.662 | 545 / 959 | [-0.019, +0.020] |
| hyb_top10_minilm_bi_ecore | 0.915 | 0.892 | 0.885 | 0.929 | 0.636 | 73 / 124 | [-0.009, +0.011] |
| hyb_rrblend_w0.1_laya_ml_score | 0.908 | 0.892 | 0.875 | 0.964 | 0.640 | 2784 / 3793 | [-0.006, +0.007] |
| hyb_blend_w0.3_msmarco_minilm12_ce_ecore | 0.916 | 0.892 | 0.875 | 0.956 | 0.656 | 590 / 1047 | [-0.059, +0.042] |
| hyb_tie_bge_reranker_base_ce_ecore | 0.908 | 0.892 | 0.865 | 0.969 | 0.654 | 2029 / 3286 | [+0.000, +0.001] |
| hyb_blend_w0.2_msmarco_minilm_ce_ecore | 0.924 | 0.892 | 0.885 | 0.932 | 0.672 | 303 / 533 | [-0.044, +0.032] |
| hyb_main_bf1d59b_blend_w0.2_msmarco_minilm_ce_ecore | 0.924 | 0.892 | 0.885 | 0.932 | 0.669 | 308 / 538 | [-0.044, +0.032] |
| det | 0.908 | 0.892 | 0.865 | 0.969 | 0.634 | 2 / 7 |  |
| hyb_thr05_bge_reranker_base_ce_ecore | 0.908 | 0.892 | 0.865 | 0.969 | 0.634 | 262 / 1349 | [+0.000, +0.000] |
| hyb_thr05_msmarco_minilm12_ce_ecore | 0.908 | 0.892 | 0.865 | 0.969 | 0.634 | 79 / 409 | [+0.000, +0.000] |
| hyb_thr05_msmarco_minilm_ce_ft_synth | 0.908 | 0.892 | 0.865 | 0.969 | 0.634 | 20 / 102 | [+0.000, +0.000] |
| hyb_thr05_msmarco_minilm_ce_ft_synth_real | 0.908 | 0.892 | 0.865 | 0.969 | 0.634 | 20 / 102 | [+0.000, +0.000] |
| hyb_main_bf1d59b_blend_w0.3_msmarco_minilm_ce_ecore | 0.919 | 0.892 | 0.885 | 0.931 | 0.659 | 308 / 538 | [-0.053, +0.038] |
| hyb_rrblend_w0.1_laya_td_noul | 0.913 | 0.892 | 0.885 | 0.948 | 0.652 | 6852 / 8512 | [-0.008, +0.005] |
| hyb_blend_w0.3_msmarco_minilm_ce_ecore | 0.918 | 0.892 | 0.885 | 0.931 | 0.662 | 303 / 533 | [-0.053, +0.037] |
| hyb_tie_minilm_bi_ecore | 0.908 | 0.892 | 0.875 | 0.953 | 0.655 | 282 / 493 | [-0.004, +0.003] |
| hyb_rrblend_w0.2_laya_ml_pair_k8 | 0.900 | 0.892 | 0.844 | 0.935 | 0.625 | 15698 / 20553 | [-0.012, +0.011] |
| hyb_rrblend_w0.1_msmarco_minilm12_ce_ecore | 0.902 | 0.892 | 0.885 | 0.948 | 0.660 | 590 / 1047 | [-0.005, +0.003] |
| hyb_tie_bge_small_bi_ecore | 0.907 | 0.891 | 0.865 | 0.953 | 0.651 | 545 / 959 | [-0.004, +0.002] |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ft_real | 0.905 | 0.891 | 0.875 | 0.948 | 0.662 | 330 / 580 | [-0.007, +0.004] |
| hyb_blend_w0.2_minilm_bi_ecore | 0.914 | 0.891 | 0.885 | 0.934 | 0.690 | 282 / 493 | [-0.024, +0.022] |
| hyb_tie_laya_ft_ml_full2 | 0.909 | 0.891 | 0.875 | 0.953 | 0.654 | 1968 / 2469 | [-0.006, +0.003] |
| hyb_tie_laya_td_noul | 0.909 | 0.891 | 0.875 | 0.953 | 0.649 | 6852 / 8512 | [-0.007, +0.003] |
| hyb_tie_msmarco_minilm12_ce_ecore | 0.904 | 0.891 | 0.875 | 0.948 | 0.658 | 590 / 1047 | [-0.006, +0.002] |
| hyb_tie_laya_ml_pair_k8 | 0.908 | 0.890 | 0.875 | 0.948 | 0.634 | 15698 / 20553 | [-0.008, +0.003] |
| hyb_thr05_msmarco_minilm_ce_ecore | 0.908 | 0.890 | 0.865 | 0.969 | 0.634 | 42 / 214 | [-0.005, +0.000] |
| hyb_thr05_msmarco_minilm_ce_ft_real | 0.908 | 0.890 | 0.865 | 0.969 | 0.633 | 46 / 312 | [-0.006, +0.000] |
| hyb_tie_msmarco_minilm_ce_ecore | 0.905 | 0.890 | 0.865 | 0.948 | 0.655 | 303 / 533 | [-0.006, +0.000] |
| hyb_main_bf1d59b_rrblend_w0.2_laya_ml_noul | 0.903 | 0.890 | 0.854 | 0.911 | 0.640 | 2407 / 3147 | [-0.018, +0.017] |
| hyb_tie_laya_ml_embed | 0.905 | 0.890 | 0.865 | 0.953 | 0.634 | 2817 / 4600 | [-0.007, +0.000] |
| hyb_blend_w0.2_msmarco_minilm_ce_ft_real | 0.921 | 0.890 | 0.906 | 0.957 | 0.685 | 330 / 580 | [-0.031, +0.023] |
| hyb_tie_msmarco_minilm_ce_ft_real | 0.905 | 0.890 | 0.865 | 0.948 | 0.658 | 330 / 580 | [-0.008, +0.001] |
| hyb_tie_msmarco_minilm_ce_ft_synth_real | 0.905 | 0.890 | 0.865 | 0.948 | 0.660 | 151 / 340 | [-0.008, +0.001] |
| hyb_main_bf1d59b_blend_w0.5_msmarco_minilm_ce_ecore | 0.911 | 0.890 | 0.865 | 0.919 | 0.651 | 308 / 538 | [-0.066, +0.046] |
| hyb_blend_w0.3_bge_reranker_base_ce_ecore | 0.912 | 0.890 | 0.885 | 0.943 | 0.651 | 2029 / 3286 | [-0.055, +0.042] |
| hyb_blend_w0.5_msmarco_minilm_ce_ecore | 0.909 | 0.889 | 0.865 | 0.919 | 0.652 | 303 / 533 | [-0.066, +0.045] |
| hyb_tie_msmarco_minilm_ce_ft_synth | 0.901 | 0.889 | 0.865 | 0.948 | 0.659 | 152 / 372 | [-0.009, +0.000] |
| hyb_tie_laya_ml_noul | 0.901 | 0.889 | 0.854 | 0.948 | 0.640 | 2402 / 3140 | [-0.009, +0.000] |
| hyb_thr05_minilm_bi_ecore | 0.902 | 0.889 | 0.854 | 0.969 | 0.633 | 39 / 204 | [-0.010, +0.000] |
| hyb_thr05_laya_ft_ml_full2 | 0.901 | 0.888 | 0.844 | 0.969 | 0.634 | 255 / 1260 | [-0.013, +0.000] |
| hyb_blend_w0.3_minilm_bi_ecore | 0.907 | 0.888 | 0.865 | 0.931 | 0.686 | 282 / 493 | [-0.039, +0.029] |
| hyb_rrblend_w0.1_laya_ml_noul | 0.904 | 0.888 | 0.844 | 0.948 | 0.641 | 2402 / 3140 | [-0.014, +0.005] |
| hyb_blend_w0.2_bge_reranker_base_ce_ecore | 0.911 | 0.888 | 0.885 | 0.944 | 0.650 | 2029 / 3286 | [-0.049, +0.031] |
| hyb_blend_w0.3_msmarco_minilm_ce_ft_real | 0.916 | 0.887 | 0.896 | 0.935 | 0.683 | 330 / 580 | [-0.041, +0.025] |
| hyb_thr05_bge_small_bi_ecore | 0.898 | 0.887 | 0.844 | 0.953 | 0.632 | 73 / 374 | [-0.014, +0.000] |
| hyb_rrblend_w0.3_laya_ml_score | 0.904 | 0.887 | 0.875 | 0.926 | 0.621 | 2784 / 3793 | [-0.019, +0.009] |
| hyb_blend_w0.5_msmarco_minilm12_ce_ecore | 0.905 | 0.886 | 0.854 | 0.944 | 0.645 | 590 / 1047 | [-0.069, +0.040] |
| hyb_rrblend_w0.3_laya_ml_pair_k8 | 0.898 | 0.886 | 0.854 | 0.919 | 0.611 | 15698 / 20553 | [-0.024, +0.013] |
| hyb_main_bf1d59b_rrblend_w0.3_laya_ml_noul | 0.903 | 0.885 | 0.854 | 0.885 | 0.627 | 2407 / 3147 | [-0.032, +0.017] |
| hyb_blend_w0.3_bge_small_bi_ecore | 0.913 | 0.885 | 0.896 | 0.984 | 0.648 | 545 / 959 | [-0.029, +0.015] |
| hyb_rrblend_w0.1_laya_ml_embed | 0.897 | 0.884 | 0.865 | 0.911 | 0.631 | 2817 / 4600 | [-0.017, -0.001] |
| hyb_band05_laya_ft_ml_full2 | 0.895 | 0.884 | 0.875 | 0.945 | 0.643 | 1968 / 2469 | [-0.048, +0.027] |
| hyb_rrblend_w0.2_laya_ml_noul | 0.893 | 0.882 | 0.854 | 0.896 | 0.634 | 2402 / 3140 | [-0.023, +0.003] |
| hyb_top10_laya_td_noul | 0.910 | 0.882 | 0.833 | 0.887 | 0.633 | 1739 / 2133 | [-0.039, +0.012] |
| hyb_blend_w0.1_laya_ft_ml_full2 | 0.896 | 0.881 | 0.875 | 0.936 | 0.644 | 1968 / 2469 | [-0.046, +0.019] |
| hyb_main_bf1d59b_band05_laya_ml_noul | 0.881 | 0.881 | 0.823 | 0.922 | 0.665 | 2407 / 3147 | [-0.053, +0.025] |
| hyb_main_bf1d59b_blend_w0.1_laya_ml_noul | 0.887 | 0.881 | 0.844 | 0.897 | 0.657 | 2407 / 3147 | [-0.049, +0.019] |
| hyb_rrblend_w0.2_laya_ml_embed | 0.888 | 0.879 | 0.854 | 0.886 | 0.622 | 2817 / 4600 | [-0.025, -0.002] |
| hyb_blend_w0.5_msmarco_minilm_ce_ft_real | 0.897 | 0.879 | 0.854 | 0.904 | 0.678 | 330 / 580 | [-0.064, +0.027] |
| hyb_band05_laya_td_noul | 0.888 | 0.878 | 0.854 | 0.914 | 0.631 | 6852 / 8512 | [-0.049, +0.016] |
| hyb_top10_laya_ml_score | 0.876 | 0.878 | 0.823 | 0.891 | 0.628 | 707 / 950 | [-0.033, +0.002] |
| msmarco_minilm_ce_onnx_int8_t4 | 0.892 | 0.877 | 0.844 | 0.911 | 0.627 | 150 / 288 | [-0.082, +0.038] |
| msmarco_minilm_ce_onnx_int8_t8 | 0.892 | 0.877 | 0.844 | 0.911 | 0.627 | 97 / 200 | [-0.082, +0.038] |
| hyb_thr05_laya_td_noul | 0.884 | 0.877 | 0.833 | 0.942 | 0.628 | 914 / 4259 | [-0.036, -0.000] |
| learned_pairwise | 0.887 | 0.876 | 0.823 | 0.939 | 0.688 | 0 / 0 | [-0.039, -0.001] |
| hyb_rrblend_w0.3_laya_ml_noul | 0.892 | 0.876 | 0.854 | 0.870 | 0.618 | 2402 / 3140 | [-0.040, +0.005] |
| hyb_rrblend_w0.5_laya_td_noul | 0.901 | 0.875 | 0.854 | 0.923 | 0.607 | 6852 / 8512 | [-0.053, +0.020] |
| hyb_blend_w0.5_bge_reranker_base_ce_ecore | 0.893 | 0.875 | 0.844 | 0.882 | 0.639 | 2029 / 3286 | [-0.082, +0.036] |
| msmarco_minilm_ce_ecore | 0.891 | 0.874 | 0.844 | 0.902 | 0.624 | 301 / 531 | [-0.088, +0.039] |
| msmarco_minilm_ce_onnx_t4 | 0.891 | 0.874 | 0.844 | 0.902 | 0.624 | 297 / 534 | [-0.088, +0.039] |
| msmarco_minilm_ce_onnx_t8 | 0.891 | 0.874 | 0.844 | 0.902 | 0.624 | 182 / 335 | [-0.088, +0.039] |
| msmarco_minilm_ce_p4 | 0.891 | 0.874 | 0.844 | 0.902 | 0.624 | 289 / 512 | [-0.088, +0.039] |
| msmarco_minilm_ce_p8 | 0.891 | 0.874 | 0.844 | 0.902 | 0.624 | 160 / 283 | [-0.088, +0.039] |
| msmarco_minilm12_ce_ecore | 0.892 | 0.873 | 0.844 | 0.927 | 0.620 | 589 / 1046 | [-0.088, +0.037] |
| msmarco_minilm12_ce_p4 | 0.892 | 0.873 | 0.844 | 0.927 | 0.620 | 568 / 999 | [-0.088, +0.037] |
| msmarco_minilm12_ce_p8 | 0.892 | 0.873 | 0.844 | 0.927 | 0.620 | 317 / 555 | [-0.088, +0.037] |
| hyb_thr05_laya_ml_pair_k8 | 0.887 | 0.873 | 0.823 | 0.948 | 0.626 | 2064 / 11075 | [-0.042, -0.000] |
| hyb_top10_laya_ml_pair_k8 | 0.871 | 0.872 | 0.792 | 0.873 | 0.625 | 3976 / 5140 | [-0.041, +0.002] |
| hyb_blend_w0.1_laya_td_noul | 0.879 | 0.871 | 0.823 | 0.898 | 0.623 | 6852 / 8512 | [-0.056, +0.009] |
| hyb_blend_w0.2_laya_ft_ml_full2 | 0.891 | 0.870 | 0.844 | 0.925 | 0.621 | 1968 / 2469 | [-0.069, +0.018] |
| hyb_thr05_laya_ml_score | 0.878 | 0.870 | 0.812 | 0.926 | 0.627 | 359 / 1747 | [-0.049, -0.001] |
| hyb_thr05_laya_ml_embed | 0.877 | 0.869 | 0.812 | 0.928 | 0.623 | 370 / 1839 | [-0.054, -0.000] |
| learned_ridge | 0.876 | 0.868 | 0.792 | 0.916 | 0.681 | 0 / 0 | [-0.052, -0.003] |
| hyb_blend_w0.1_laya_ml_pair_k8 | 0.879 | 0.867 | 0.833 | 0.909 | 0.596 | 15698 / 20553 | [-0.053, +0.001] |
| hyb_blend_w0.5_bge_small_bi_ecore | 0.897 | 0.864 | 0.844 | 0.984 | 0.593 | 545 / 959 | [-0.056, +0.000] |
| laya_ml_pair_top10 | 0.853 | 0.864 | 0.760 | 0.825 | 0.626 | 4485 / 5810 | [-0.047, -0.010] |
| hyb_band05_laya_ml_pair_k8 | 0.874 | 0.864 | 0.823 | 0.907 | 0.604 | 15698 / 20553 | [-0.060, -0.001] |
| msmarco_minilm_ce_ft_real | 0.892 | 0.864 | 0.844 | 0.900 | 0.657 | 329 / 578 | [-0.089, +0.024] |
| hyb_band05_laya_ml_embed | 0.858 | 0.863 | 0.812 | 0.900 | 0.622 | 2817 / 4600 | [-0.056, -0.008] |
| hyb_main_bf1d59b_top10_laya_ml_noul | 0.847 | 0.861 | 0.750 | 0.795 | 0.636 | 615 / 794 | [-0.061, -0.006] |
| hyb_top10_laya_ml_noul | 0.845 | 0.861 | 0.750 | 0.796 | 0.630 | 610 / 787 | [-0.061, -0.006] |
| hyb_band05_laya_ml_noul | 0.860 | 0.860 | 0.812 | 0.895 | 0.638 | 2402 / 3140 | [-0.082, +0.009] |
| hyb_blend_w0.5_laya_ft_ml_full2 | 0.858 | 0.860 | 0.833 | 0.905 | 0.602 | 1968 / 2469 | [-0.103, +0.028] |
| hyb_blend_w0.1_laya_ml_score | 0.869 | 0.859 | 0.833 | 0.912 | 0.622 | 2784 / 3793 | [-0.073, +0.003] |
| hyb_blend_w0.5_minilm_bi_ecore | 0.869 | 0.859 | 0.802 | 0.914 | 0.664 | 282 / 493 | [-0.084, +0.014] |
| hyb_main_bf1d59b_thr05_laya_ml_noul | 0.869 | 0.859 | 0.781 | 0.914 | 0.630 | 354 / 1615 | [-0.071, -0.003] |
| hyb_thr05_laya_ml_noul | 0.870 | 0.859 | 0.792 | 0.924 | 0.622 | 308 / 1609 | [-0.071, -0.004] |
| hyb_rrblend_w0.3_laya_ml_embed | 0.870 | 0.859 | 0.823 | 0.856 | 0.599 | 2817 / 4600 | [-0.053, -0.014] |
| hyb_main_bf1d59b_blend_w0.2_laya_ml_noul | 0.869 | 0.858 | 0.812 | 0.879 | 0.629 | 2407 / 3147 | [-0.082, +0.007] |
| hyb_blend_w0.3_laya_ft_ml_full2 | 0.875 | 0.857 | 0.833 | 0.904 | 0.606 | 1968 / 2469 | [-0.101, +0.017] |
| hyb_blend_w0.1_laya_ml_embed | 0.847 | 0.856 | 0.812 | 0.888 | 0.609 | 2817 / 4600 | [-0.071, -0.010] |
| hyb_band05_laya_ml_score | 0.859 | 0.856 | 0.812 | 0.874 | 0.621 | 2784 / 3793 | [-0.086, +0.008] |
| hyb_blend_w0.2_laya_td_noul | 0.869 | 0.856 | 0.823 | 0.883 | 0.601 | 6852 / 8512 | [-0.080, +0.002] |
| hyb_main_bf1d59b_rrblend_w0.5_laya_ml_noul | 0.882 | 0.855 | 0.833 | 0.858 | 0.544 | 2407 / 3147 | [-0.075, -0.001] |
| hyb_blend_w0.1_laya_ml_noul | 0.858 | 0.855 | 0.823 | 0.881 | 0.620 | 2402 / 3140 | [-0.094, +0.005] |
| hyb_top10_laya_ml_embed | 0.821 | 0.855 | 0.771 | 0.819 | 0.623 | 713 / 1151 | [-0.069, -0.012] |
| bge_reranker_base_ce_ecore | 0.868 | 0.851 | 0.802 | 0.859 | 0.594 | 2027 / 3285 | [-0.111, +0.019] |
| bge_reranker_base_ce_p4 | 0.868 | 0.851 | 0.802 | 0.859 | 0.594 | 2126 / 3488 | [-0.111, +0.019] |
| bge_reranker_base_ce_p8 | 0.868 | 0.851 | 0.802 | 0.859 | 0.594 | 1155 / 1894 | [-0.111, +0.019] |
| hyb_blend_w0.3_laya_td_noul | 0.861 | 0.845 | 0.812 | 0.898 | 0.575 | 6852 / 8512 | [-0.096, -0.000] |
| hyb_rrblend_w0.5_laya_ml_noul | 0.863 | 0.842 | 0.812 | 0.853 | 0.529 | 2402 / 3140 | [-0.096, -0.009] |
| hyb_rrblend_w0.5_laya_ml_score | 0.872 | 0.839 | 0.812 | 0.898 | 0.546 | 2784 / 3793 | [-0.086, -0.019] |
| hyb_rrblend_w0.5_laya_ml_pair_k8 | 0.864 | 0.836 | 0.812 | 0.886 | 0.524 | 15698 / 20553 | [-0.094, -0.019] |
| hyb_blend_w0.2_laya_ml_pair_k8 | 0.853 | 0.831 | 0.792 | 0.900 | 0.541 | 15698 / 20553 | [-0.104, -0.022] |
| hyb_main_bf1d59b_blend_w0.3_laya_ml_noul | 0.848 | 0.830 | 0.792 | 0.880 | 0.584 | 2407 / 3147 | [-0.116, -0.011] |
| laya_ft_ml_full2 | 0.832 | 0.828 | 0.750 | 0.853 | 0.561 | 1966 / 2467 | [-0.137, +0.002] |
| hyb_blend_w0.2_laya_ml_noul | 0.829 | 0.823 | 0.781 | 0.830 | 0.587 | 2402 / 3140 | [-0.143, -0.011] |
| hyb_blend_w0.2_laya_ml_score | 0.843 | 0.820 | 0.812 | 0.837 | 0.581 | 2784 / 3793 | [-0.125, -0.024] |
| hyb_blend_w0.3_laya_ml_pair_k8 | 0.829 | 0.804 | 0.750 | 0.859 | 0.492 | 15698 / 20553 | [-0.147, -0.034] |
| hyb_rrblend_w0.5_laya_ml_embed | 0.823 | 0.802 | 0.802 | 0.832 | 0.496 | 2817 / 4600 | [-0.127, -0.056] |
| distributor_order | 0.794 | 0.800 | 0.719 | 0.801 | 0.620 | 0 / 0 | [-0.173, -0.022] |
| hyb_blend_w0.2_laya_ml_embed | 0.792 | 0.798 | 0.740 | 0.860 | 0.527 | 2817 / 4600 | [-0.148, -0.048] |
| hyb_blend_w0.3_laya_ml_noul | 0.802 | 0.796 | 0.750 | 0.823 | 0.542 | 2402 / 3140 | [-0.175, -0.031] |
| hyb_blend_w0.3_laya_ml_score | 0.815 | 0.794 | 0.760 | 0.833 | 0.537 | 2784 / 3793 | [-0.161, -0.038] |
| bge_small_bi_ecore | 0.814 | 0.790 | 0.729 | 0.882 | 0.484 | 543 / 958 | [-0.159, -0.048] |
| bge_small_bi_p4 | 0.814 | 0.790 | 0.729 | 0.882 | 0.484 | 529 / 947 | [-0.159, -0.048] |
| bge_small_bi_p8 | 0.814 | 0.790 | 0.729 | 0.882 | 0.484 | 291 / 567 | [-0.159, -0.048] |
| hyb_blend_w0.5_laya_td_noul | 0.782 | 0.787 | 0.729 | 0.800 | 0.525 | 6852 / 8512 | [-0.170, -0.042] |
| minilm_bi_ecore | 0.789 | 0.777 | 0.688 | 0.833 | 0.566 | 280 / 491 | [-0.186, -0.045] |
| minilm_bi_p4 | 0.789 | 0.777 | 0.688 | 0.833 | 0.566 | 267 / 479 | [-0.186, -0.045] |
| minilm_bi_p8 | 0.789 | 0.777 | 0.688 | 0.833 | 0.566 | 152 / 272 | [-0.186, -0.045] |
| hyb_main_bf1d59b_blend_w0.5_laya_ml_noul | 0.796 | 0.770 | 0.740 | 0.837 | 0.461 | 2407 / 3147 | [-0.192, -0.054] |
| bm25 | 0.735 | 0.745 | 0.677 | 0.776 | 0.442 | 0 / 1 | [-0.244, -0.058] |
| hyb_blend_w0.3_laya_ml_embed | 0.760 | 0.744 | 0.677 | 0.816 | 0.457 | 2817 / 4600 | [-0.213, -0.090] |
| hyb_blend_w0.5_laya_ml_noul | 0.756 | 0.741 | 0.708 | 0.777 | 0.430 | 2402 / 3140 | [-0.235, -0.074] |
| hyb_blend_w0.5_laya_ml_score | 0.758 | 0.739 | 0.688 | 0.790 | 0.443 | 2784 / 3793 | [-0.225, -0.084] |
| hyb_blend_w0.5_laya_ml_pair_k8 | 0.754 | 0.737 | 0.677 | 0.809 | 0.362 | 15698 / 20553 | [-0.227, -0.088] |
| laya_ft_ml_full | 0.676 | 0.697 | 0.556 | 0.735 | 0.381 | 2004 / 2372 |  |
| hyb_blend_w0.5_laya_ml_embed | 0.680 | 0.683 | 0.604 | 0.707 | 0.331 | 2817 / 4600 | [-0.289, -0.133] |
| laya_td_noul | 0.647 | 0.669 | 0.521 | 0.627 | 0.392 | 6850 / 8505 | [-0.303, -0.141] |
| laya_td_noul_p4 | 0.647 | 0.669 | 0.521 | 0.627 | 0.392 | 11006 / 13333 | [-0.303, -0.141] |
| laya_td_noul_p8 | 0.647 | 0.669 | 0.521 | 0.627 | 0.392 | 6131 / 7407 | [-0.303, -0.141] |
| laya_ft_ml_head | 0.625 | 0.651 | 0.469 | 0.730 | 0.320 | 1897 / 2376 | [-0.304, -0.179] |
| laya_en_choice | 0.637 | 0.647 | 0.542 | 0.703 | 0.316 | 7184 / 8927 | [-0.322, -0.171] |
| laya_td_choice | 0.597 | 0.635 | 0.479 | 0.573 | 0.378 | 7120 / 8390 | [-0.337, -0.181] |
| laya_ml_pair_k1 | 0.627 | 0.613 | 0.500 | 0.721 | 0.088 | 1978 / 2633 | [-0.355, -0.196] |
| laya_ft_ml_head_synth | 0.599 | 0.605 | 0.479 | 0.614 | 0.200 | 1926 / 2384 | [-0.373, -0.200] |
| laya_ml_shortlist | 0.613 | 0.596 | 0.458 | 0.603 | 0.128 | 1213 / 1727 | [-0.378, -0.213] |
| laya_ml_pair_k8 | 0.596 | 0.594 | 0.448 | 0.636 | 0.127 | 15696 / 20551 | [-0.376, -0.222] |
| laya_ml_score | 0.558 | 0.591 | 0.427 | 0.598 | 0.225 | 2782 / 3790 | [-0.401, -0.202] |
| laya_en_noul | 0.557 | 0.579 | 0.417 | 0.563 | 0.245 | 7739 / 12033 | [-0.409, -0.221] |
| laya_ml_pair_k4 | 0.568 | 0.561 | 0.438 | 0.607 | 0.107 | 7862 / 10420 | [-0.402, -0.259] |
| laya_ml_noul_len192 | 0.545 | 0.555 | 0.427 | 0.589 | 0.175 | 2345 / 3227 | [-0.435, -0.238] |
| laya_ml_noul | 0.542 | 0.555 | 0.438 | 0.594 | 0.187 | 2401 / 3137 | [-0.436, -0.240] |
| laya_ml_noul_p4 | 0.542 | 0.555 | 0.438 | 0.594 | 0.187 | 4243 / 5349 | [-0.436, -0.240] |
| laya_ml_noul_p8 | 0.542 | 0.555 | 0.438 | 0.594 | 0.187 | 2377 / 3015 | [-0.436, -0.240] |
| laya_ml_noul_parsed | 0.511 | 0.554 | 0.396 | 0.519 | 0.217 | 2680 / 3244 | [-0.434, -0.246] |
| laya_ml_pair_k2 | 0.553 | 0.541 | 0.427 | 0.580 | 0.065 | 3944 / 5329 | [-0.428, -0.275] |
| laya_ml_decomp_prod | 0.522 | 0.537 | 0.417 | 0.552 | 0.166 | 10128 / 12932 | [-0.453, -0.256] |
| laya_ml_decomp_mean | 0.522 | 0.537 | 0.417 | 0.552 | 0.169 | 10128 / 12932 | [-0.453, -0.257] |
| laya_ml_embed | 0.503 | 0.534 | 0.354 | 0.529 | 0.113 | 2815 / 4598 | [-0.458, -0.257] |
| laya_ml_noul_plain | 0.484 | 0.498 | 0.365 | 0.511 | 0.114 | 3230 / 4574 | [-0.482, -0.308] |
| laya_ml_choice | 0.452 | 0.494 | 0.344 | 0.522 | 0.043 | 2691 / 3283 | [-0.492, -0.306] |
| laya_ml_embed_json | 0.440 | 0.467 | 0.229 | 0.378 | -0.027 | 2462 / 3410 | [-0.509, -0.345] |

| method | passive | discrete | ic | crystal_connector | vague |
|---|---|---|---|---|---|
| hyb_main_bf1d59b_rrblend_w0.5_msmarco_minilm_ce_ft_synth_real | 0.996 | 0.960 | 0.954 | 0.969 | 0.611 |
| hyb_rrblend_w0.5_msmarco_minilm_ce_ft_synth_real | 0.996 | 0.960 | 0.954 | 0.967 | 0.611 |
| hyb_rrblend_w0.5_msmarco_minilm_ce_ft_synth | 0.996 | 0.957 | 0.949 | 0.967 | 0.605 |
| hyb_main_bf1d59b_rrblend_w0.5_msmarco_minilm_ce_ecore | 0.992 | 0.956 | 0.954 | 0.969 | 0.603 |
| hyb_rrblend_w0.5_bge_reranker_base_ce_ecore | 1.000 | 0.955 | 0.947 | 0.962 | 0.602 |
| hyb_rrblend_w0.5_msmarco_minilm_ce_ecore | 0.992 | 0.956 | 0.954 | 0.966 | 0.603 |
| hyb_band05_msmarco_minilm_ce_ft_synth_real | 0.989 | 0.943 | 0.944 | 0.960 | 0.644 |
| hyb_main_bf1d59b_band05_msmarco_minilm_ce_ft_synth_real | 0.989 | 0.943 | 0.944 | 0.960 | 0.644 |
| hyb_band05_msmarco_minilm_ce_ft_synth | 0.989 | 0.943 | 0.941 | 0.960 | 0.640 |
| hyb_main_bf1d59b_blend_w0.1_msmarco_minilm_ce_ft_synth_real | 0.989 | 0.947 | 0.947 | 0.960 | 0.626 |
| hyb_blend_w0.1_msmarco_minilm_ce_ft_synth_real | 0.989 | 0.947 | 0.947 | 0.956 | 0.626 |
| hyb_rrblend_w0.3_bge_reranker_base_ce_ecore | 0.992 | 0.940 | 0.941 | 0.961 | 0.628 |
| hyb_blend_w0.1_msmarco_minilm_ce_ft_synth | 0.989 | 0.946 | 0.946 | 0.956 | 0.623 |
| hyb_main_bf1d59b_rrblend_w0.3_msmarco_minilm_ce_ecore | 0.988 | 0.934 | 0.946 | 0.969 | 0.620 |
| hyb_rrblend_w0.3_msmarco_minilm_ce_ecore | 0.988 | 0.934 | 0.946 | 0.966 | 0.620 |
| hyb_blend_w0.2_msmarco_minilm_ce_ft_synth | 0.989 | 0.956 | 0.944 | 0.960 | 0.594 |
| hyb_rrblend_w0.5_msmarco_minilm12_ce_ecore | 0.992 | 0.951 | 0.954 | 0.952 | 0.582 |
| hyb_rrblend_w0.3_laya_ft_ml_full2 | 0.992 | 0.946 | 0.939 | 0.953 | 0.608 |
| hyb_rrblend_w0.5_msmarco_minilm_ce_ft_real | 0.987 | 0.957 | 0.954 | 0.943 | 0.587 |
| hyb_blend_w0.2_msmarco_minilm_ce_ft_synth_real | 0.989 | 0.956 | 0.945 | 0.960 | 0.583 |
| hyb_main_bf1d59b_blend_w0.2_msmarco_minilm_ce_ft_synth_real | 0.989 | 0.956 | 0.945 | 0.960 | 0.583 |
| hyb_blend_w0.3_msmarco_minilm_ce_ft_synth | 0.989 | 0.966 | 0.941 | 0.959 | 0.574 |
| hyb_band05_msmarco_minilm12_ce_ecore | 0.989 | 0.937 | 0.940 | 0.968 | 0.602 |
| hyb_rrblend_w0.3_msmarco_minilm_ce_ft_synth | 0.988 | 0.933 | 0.945 | 0.956 | 0.609 |
| hyb_main_bf1d59b_rrblend_w0.3_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.930 | 0.948 | 0.969 | 0.599 |
| hyb_rrblend_w0.3_msmarco_minilm_ce_ft_real | 0.988 | 0.923 | 0.947 | 0.961 | 0.614 |
| hyb_rrblend_w0.3_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.930 | 0.948 | 0.965 | 0.599 |
| hyb_band05_bge_reranker_base_ce_ecore | 0.989 | 0.929 | 0.940 | 0.934 | 0.634 |
| hyb_rrblend_w0.2_msmarco_minilm_ce_ft_real | 0.988 | 0.920 | 0.946 | 0.961 | 0.615 |
| hyb_main_bf1d59b_top10_msmarco_minilm_ce_ecore | 0.988 | 0.932 | 0.940 | 0.960 | 0.608 |
| hyb_main_bf1d59b_top10_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.935 | 0.940 | 0.960 | 0.604 |
| hyb_rrblend_w0.2_laya_td_noul | 0.988 | 0.941 | 0.929 | 0.931 | 0.634 |
| hyb_blend_w0.3_msmarco_minilm_ce_ft_synth_real | 0.989 | 0.966 | 0.945 | 0.960 | 0.557 |
| hyb_main_bf1d59b_blend_w0.3_msmarco_minilm_ce_ft_synth_real | 0.989 | 0.966 | 0.945 | 0.960 | 0.557 |
| hyb_top10_msmarco_minilm_ce_ecore | 0.988 | 0.932 | 0.940 | 0.957 | 0.608 |
| hyb_top10_msmarco_minilm_ce_ft_real | 0.988 | 0.929 | 0.940 | 0.949 | 0.617 |
| hyb_top10_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.935 | 0.940 | 0.957 | 0.604 |
| hyb_main_bf1d59b_rrblend_w0.2_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.922 | 0.945 | 0.959 | 0.609 |
| hyb_top10_msmarco_minilm_ce_ft_synth | 0.988 | 0.934 | 0.940 | 0.957 | 0.603 |
| hyb_top10_msmarco_minilm12_ce_ecore | 0.988 | 0.927 | 0.940 | 0.955 | 0.612 |
| hyb_rrblend_w0.2_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.922 | 0.945 | 0.954 | 0.609 |
| hyb_rrblend_w0.2_minilm_bi_ecore | 0.988 | 0.914 | 0.946 | 0.933 | 0.633 |
| hyb_band05_minilm_bi_ecore | 0.988 | 0.925 | 0.953 | 0.892 | 0.642 |
| hyb_blend_w0.1_minilm_bi_ecore | 0.988 | 0.927 | 0.953 | 0.913 | 0.621 |
| hyb_band05_bge_small_bi_ecore | 0.988 | 0.932 | 0.927 | 0.933 | 0.636 |
| hyb_rrblend_w0.3_msmarco_minilm12_ce_ecore | 0.988 | 0.919 | 0.947 | 0.952 | 0.607 |
| hyb_blend_w0.1_bge_reranker_base_ce_ecore | 0.988 | 0.929 | 0.949 | 0.944 | 0.595 |
| hyb_rrblend_w0.3_bge_small_bi_ecore | 0.986 | 0.911 | 0.941 | 0.946 | 0.632 |
| hyb_band05_msmarco_minilm_ce_ft_real | 0.982 | 0.940 | 0.946 | 0.952 | 0.591 |
| hyb_blend_w0.1_msmarco_minilm_ce_ft_real | 0.983 | 0.940 | 0.954 | 0.935 | 0.591 |
| hyb_rrblend_w0.5_laya_ft_ml_full2 | 0.996 | 0.973 | 0.919 | 0.956 | 0.556 |
| hyb_band05_msmarco_minilm_ce_ecore | 0.983 | 0.943 | 0.943 | 0.960 | 0.580 |
| hyb_main_bf1d59b_band05_msmarco_minilm_ce_ecore | 0.983 | 0.943 | 0.943 | 0.960 | 0.580 |
| hyb_rrblend_w0.2_bge_reranker_base_ce_ecore | 0.992 | 0.910 | 0.946 | 0.919 | 0.630 |
| hyb_rrblend_w0.2_msmarco_minilm_ce_ft_synth | 0.988 | 0.912 | 0.945 | 0.954 | 0.606 |
| hyb_main_bf1d59b_rrblend_w0.2_msmarco_minilm_ce_ecore | 0.988 | 0.908 | 0.937 | 0.960 | 0.616 |
| hyb_rrblend_w0.2_laya_ft_ml_full2 | 0.992 | 0.920 | 0.940 | 0.953 | 0.594 |
| hyb_rrblend_w0.2_bge_small_bi_ecore | 0.988 | 0.903 | 0.940 | 0.943 | 0.629 |
| hyb_rrblend_w0.3_minilm_bi_ecore | 0.987 | 0.917 | 0.940 | 0.935 | 0.619 |
| hyb_rrblend_w0.2_msmarco_minilm_ce_ecore | 0.988 | 0.908 | 0.937 | 0.952 | 0.616 |
| hyb_rrblend_w0.1_bge_small_bi_ecore | 0.988 | 0.903 | 0.939 | 0.943 | 0.627 |
| hyb_rrblend_w0.2_msmarco_minilm12_ce_ecore | 0.988 | 0.907 | 0.944 | 0.952 | 0.609 |
| hyb_blend_w0.5_msmarco_minilm_ce_ft_synth_real | 0.996 | 0.968 | 0.944 | 0.969 | 0.499 |
| hyb_main_bf1d59b_blend_w0.5_msmarco_minilm_ce_ft_synth_real | 0.996 | 0.968 | 0.944 | 0.969 | 0.499 |
| hyb_rrblend_w0.5_minilm_bi_ecore | 0.979 | 0.919 | 0.937 | 0.936 | 0.629 |
| hyb_main_bf1d59b_thr05_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.902 | 0.930 | 0.960 | 0.620 |
| det_main_bf1d59b | 0.988 | 0.902 | 0.930 | 0.955 | 0.620 |
| hyb_main_bf1d59b_rrblend_w0.1_msmarco_minilm_ce_ecore | 0.988 | 0.905 | 0.934 | 0.959 | 0.607 |
| hyb_blend_w0.5_msmarco_minilm_ce_ft_synth | 0.996 | 0.967 | 0.941 | 0.967 | 0.500 |
| hyb_rrblend_w0.2_laya_ml_score | 0.982 | 0.911 | 0.931 | 0.940 | 0.629 |
| hyb_main_bf1d59b_rrblend_w0.1_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.908 | 0.934 | 0.957 | 0.601 |
| hyb_blend_w0.1_msmarco_minilm12_ce_ecore | 0.989 | 0.930 | 0.949 | 0.948 | 0.560 |
| hyb_main_bf1d59b_thr05_msmarco_minilm_ce_ecore | 0.983 | 0.902 | 0.930 | 0.960 | 0.620 |
| hyb_main_bf1d59b_blend_w0.1_msmarco_minilm_ce_ecore | 0.983 | 0.943 | 0.947 | 0.960 | 0.545 |
| hyb_rrblend_w0.3_laya_td_noul | 0.992 | 0.944 | 0.930 | 0.884 | 0.609 |
| hyb_main_bf1d59b_tie_msmarco_minilm_ce_ecore | 0.988 | 0.902 | 0.930 | 0.955 | 0.608 |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ecore | 0.988 | 0.905 | 0.934 | 0.943 | 0.607 |
| hyb_blend_w0.1_msmarco_minilm_ce_ecore | 0.983 | 0.943 | 0.947 | 0.955 | 0.545 |
| hyb_main_bf1d59b_tie_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.902 | 0.930 | 0.955 | 0.606 |
| hyb_top10_bge_small_bi_ecore | 0.980 | 0.920 | 0.930 | 0.949 | 0.604 |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.908 | 0.934 | 0.943 | 0.601 |
| hyb_rrblend_w0.1_laya_ft_ml_full2 | 0.988 | 0.904 | 0.945 | 0.919 | 0.610 |
| hyb_blend_w0.1_bge_small_bi_ecore | 0.988 | 0.925 | 0.929 | 0.921 | 0.604 |
| hyb_rrblend_w0.1_bge_reranker_base_ce_ecore | 0.988 | 0.905 | 0.934 | 0.910 | 0.629 |
| hyb_top10_laya_ft_ml_full2 | 0.987 | 0.935 | 0.922 | 0.950 | 0.578 |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ft_synth | 0.988 | 0.908 | 0.934 | 0.943 | 0.598 |
| hyb_rrblend_w0.1_laya_ml_pair_k8 | 0.988 | 0.902 | 0.929 | 0.931 | 0.621 |
| hyb_main_bf1d59b_tie_laya_ml_noul | 0.988 | 0.902 | 0.930 | 0.955 | 0.599 |
| msmarco_minilm_ce_ft_synth_real | 0.992 | 0.987 | 0.958 | 0.969 | 0.438 |
| hyb_rrblend_w0.5_bge_small_bi_ecore | 0.979 | 0.922 | 0.921 | 0.945 | 0.614 |
| hyb_blend_w0.2_msmarco_minilm12_ce_ecore | 0.988 | 0.945 | 0.944 | 0.955 | 0.525 |
| hyb_main_bf1d59b_rrblend_w0.1_laya_ml_noul | 0.977 | 0.903 | 0.929 | 0.957 | 0.615 |
| hyb_top10_bge_reranker_base_ce_ecore | 0.988 | 0.922 | 0.933 | 0.924 | 0.590 |
| msmarco_minilm_ce_ft_synth | 0.986 | 1.000 | 0.941 | 0.967 | 0.454 |
| hyb_tie_laya_ml_score | 0.988 | 0.902 | 0.930 | 0.910 | 0.627 |
| hyb_rrblend_w0.1_minilm_bi_ecore | 0.988 | 0.903 | 0.934 | 0.909 | 0.618 |
| hyb_blend_w0.2_bge_small_bi_ecore | 0.988 | 0.925 | 0.929 | 0.917 | 0.592 |
| hyb_top10_minilm_bi_ecore | 0.982 | 0.927 | 0.938 | 0.910 | 0.594 |
| hyb_rrblend_w0.1_laya_ml_score | 0.983 | 0.899 | 0.929 | 0.913 | 0.634 |
| hyb_blend_w0.3_msmarco_minilm12_ce_ecore | 0.988 | 0.961 | 0.944 | 0.956 | 0.495 |
| hyb_tie_bge_reranker_base_ce_ecore | 0.988 | 0.902 | 0.930 | 0.910 | 0.622 |
| hyb_blend_w0.2_msmarco_minilm_ce_ecore | 0.983 | 0.944 | 0.945 | 0.960 | 0.520 |
| hyb_main_bf1d59b_blend_w0.2_msmarco_minilm_ce_ecore | 0.983 | 0.944 | 0.945 | 0.960 | 0.520 |
| det | 0.988 | 0.902 | 0.930 | 0.910 | 0.620 |
| hyb_thr05_bge_reranker_base_ce_ecore | 0.988 | 0.902 | 0.930 | 0.910 | 0.620 |
| hyb_thr05_msmarco_minilm12_ce_ecore | 0.988 | 0.902 | 0.930 | 0.910 | 0.620 |
| hyb_thr05_msmarco_minilm_ce_ft_synth | 0.988 | 0.902 | 0.930 | 0.910 | 0.620 |
| hyb_thr05_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.902 | 0.930 | 0.910 | 0.620 |
| hyb_main_bf1d59b_blend_w0.3_msmarco_minilm_ce_ecore | 0.983 | 0.966 | 0.944 | 0.960 | 0.494 |
| hyb_rrblend_w0.1_laya_td_noul | 0.988 | 0.909 | 0.929 | 0.913 | 0.609 |
| hyb_blend_w0.3_msmarco_minilm_ce_ecore | 0.983 | 0.966 | 0.944 | 0.959 | 0.494 |
| hyb_tie_minilm_bi_ecore | 0.988 | 0.902 | 0.930 | 0.910 | 0.619 |
| hyb_rrblend_w0.2_laya_ml_pair_k8 | 0.991 | 0.909 | 0.924 | 0.919 | 0.604 |
| hyb_rrblend_w0.1_msmarco_minilm12_ce_ecore | 0.988 | 0.905 | 0.934 | 0.910 | 0.609 |
| hyb_tie_bge_small_bi_ecore | 0.988 | 0.902 | 0.930 | 0.910 | 0.616 |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ft_real | 0.988 | 0.907 | 0.934 | 0.910 | 0.604 |
| hyb_blend_w0.2_minilm_bi_ecore | 0.988 | 0.921 | 0.953 | 0.869 | 0.592 |
| hyb_tie_laya_ft_ml_full2 | 0.988 | 0.902 | 0.930 | 0.910 | 0.614 |
| hyb_tie_laya_td_noul | 0.988 | 0.902 | 0.930 | 0.910 | 0.613 |
| hyb_tie_msmarco_minilm12_ce_ecore | 0.988 | 0.902 | 0.930 | 0.910 | 0.612 |
| hyb_tie_laya_ml_pair_k8 | 0.988 | 0.902 | 0.930 | 0.910 | 0.610 |
| hyb_thr05_msmarco_minilm_ce_ecore | 0.983 | 0.902 | 0.930 | 0.910 | 0.620 |
| hyb_thr05_msmarco_minilm_ce_ft_real | 0.982 | 0.902 | 0.930 | 0.910 | 0.620 |
| hyb_tie_msmarco_minilm_ce_ecore | 0.988 | 0.902 | 0.930 | 0.910 | 0.608 |
| hyb_main_bf1d59b_rrblend_w0.2_laya_ml_noul | 0.973 | 0.895 | 0.929 | 0.957 | 0.610 |
| hyb_tie_laya_ml_embed | 0.988 | 0.902 | 0.930 | 0.910 | 0.606 |
| hyb_blend_w0.2_msmarco_minilm_ce_ft_real | 0.983 | 0.953 | 0.947 | 0.934 | 0.513 |
| hyb_tie_msmarco_minilm_ce_ft_real | 0.988 | 0.902 | 0.930 | 0.910 | 0.606 |
| hyb_tie_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.902 | 0.930 | 0.910 | 0.606 |
| hyb_main_bf1d59b_blend_w0.5_msmarco_minilm_ce_ecore | 0.986 | 0.990 | 0.943 | 0.969 | 0.438 |
| hyb_blend_w0.3_bge_reranker_base_ce_ecore | 0.992 | 0.977 | 0.936 | 0.960 | 0.458 |
| hyb_blend_w0.5_msmarco_minilm_ce_ecore | 0.986 | 0.990 | 0.943 | 0.967 | 0.438 |
| hyb_tie_msmarco_minilm_ce_ft_synth | 0.988 | 0.902 | 0.930 | 0.910 | 0.601 |
| hyb_tie_laya_ml_noul | 0.988 | 0.902 | 0.930 | 0.910 | 0.599 |
| hyb_thr05_minilm_bi_ecore | 0.977 | 0.902 | 0.930 | 0.910 | 0.620 |
| hyb_thr05_laya_ft_ml_full2 | 0.975 | 0.902 | 0.930 | 0.910 | 0.620 |
| hyb_blend_w0.3_minilm_bi_ecore | 0.988 | 0.925 | 0.942 | 0.870 | 0.581 |
| hyb_rrblend_w0.1_laya_ml_noul | 0.977 | 0.903 | 0.929 | 0.909 | 0.615 |
| hyb_blend_w0.2_bge_reranker_base_ce_ecore | 0.989 | 0.933 | 0.941 | 0.956 | 0.502 |
| hyb_blend_w0.3_msmarco_minilm_ce_ft_real | 0.982 | 0.954 | 0.946 | 0.940 | 0.494 |
| hyb_thr05_bge_small_bi_ecore | 0.973 | 0.902 | 0.930 | 0.908 | 0.620 |
| hyb_rrblend_w0.3_laya_ml_score | 0.980 | 0.920 | 0.905 | 0.933 | 0.600 |
| hyb_blend_w0.5_msmarco_minilm12_ce_ecore | 0.991 | 0.963 | 0.940 | 0.955 | 0.455 |
| hyb_rrblend_w0.3_laya_ml_pair_k8 | 0.982 | 0.930 | 0.909 | 0.920 | 0.580 |
| hyb_main_bf1d59b_rrblend_w0.3_laya_ml_noul | 0.954 | 0.911 | 0.926 | 0.947 | 0.610 |
| hyb_blend_w0.3_bge_small_bi_ecore | 0.984 | 0.926 | 0.900 | 0.912 | 0.593 |
| hyb_rrblend_w0.1_laya_ml_embed | 0.988 | 0.887 | 0.933 | 0.898 | 0.591 |
| hyb_band05_laya_ft_ml_full2 | 0.989 | 0.943 | 0.852 | 0.951 | 0.593 |
| hyb_rrblend_w0.2_laya_ml_noul | 0.973 | 0.895 | 0.929 | 0.893 | 0.610 |
| hyb_top10_laya_td_noul | 0.988 | 0.932 | 0.873 | 0.917 | 0.594 |
| hyb_blend_w0.1_laya_ft_ml_full2 | 0.989 | 0.947 | 0.874 | 0.947 | 0.542 |
| hyb_main_bf1d59b_band05_laya_ml_noul | 0.983 | 0.925 | 0.863 | 0.947 | 0.596 |
| hyb_main_bf1d59b_blend_w0.1_laya_ml_noul | 0.983 | 0.914 | 0.883 | 0.952 | 0.575 |
| hyb_rrblend_w0.2_laya_ml_embed | 0.985 | 0.867 | 0.926 | 0.898 | 0.600 |
| hyb_blend_w0.5_msmarco_minilm_ce_ft_real | 0.986 | 0.968 | 0.946 | 0.937 | 0.417 |
| hyb_band05_laya_td_noul | 0.989 | 0.934 | 0.900 | 0.828 | 0.601 |
| hyb_top10_laya_ml_score | 0.973 | 0.925 | 0.889 | 0.913 | 0.584 |
| msmarco_minilm_ce_onnx_int8_t4 | 0.952 | 0.983 | 0.955 | 0.966 | 0.421 |
| msmarco_minilm_ce_onnx_int8_t8 | 0.952 | 0.983 | 0.955 | 0.966 | 0.421 |
| hyb_thr05_laya_td_noul | 0.941 | 0.902 | 0.930 | 0.908 | 0.620 |
| learned_pairwise | 0.988 | 0.883 | 0.930 | 0.910 | 0.543 |
| hyb_rrblend_w0.3_laya_ml_noul | 0.954 | 0.911 | 0.926 | 0.872 | 0.610 |
| hyb_rrblend_w0.5_laya_td_noul | 0.958 | 0.971 | 0.909 | 0.828 | 0.586 |
| hyb_blend_w0.5_bge_reranker_base_ce_ecore | 0.996 | 0.979 | 0.933 | 0.924 | 0.387 |
| msmarco_minilm_ce_ecore | 0.938 | 1.000 | 0.957 | 0.967 | 0.402 |
| msmarco_minilm_ce_onnx_t4 | 0.938 | 1.000 | 0.957 | 0.967 | 0.402 |
| msmarco_minilm_ce_onnx_t8 | 0.938 | 1.000 | 0.957 | 0.967 | 0.402 |
| msmarco_minilm_ce_p4 | 0.938 | 1.000 | 0.957 | 0.967 | 0.402 |
| msmarco_minilm_ce_p8 | 0.938 | 1.000 | 0.957 | 0.967 | 0.402 |
| msmarco_minilm12_ce_ecore | 0.942 | 0.970 | 0.954 | 0.964 | 0.435 |
| msmarco_minilm12_ce_p4 | 0.942 | 0.970 | 0.954 | 0.964 | 0.435 |
| msmarco_minilm12_ce_p8 | 0.942 | 0.970 | 0.954 | 0.964 | 0.435 |
| hyb_thr05_laya_ml_pair_k8 | 0.928 | 0.902 | 0.930 | 0.909 | 0.620 |
| hyb_top10_laya_ml_pair_k8 | 0.980 | 0.923 | 0.876 | 0.909 | 0.562 |
| hyb_blend_w0.1_laya_td_noul | 0.988 | 0.937 | 0.894 | 0.789 | 0.591 |
| hyb_blend_w0.2_laya_ft_ml_full2 | 0.976 | 0.956 | 0.855 | 0.951 | 0.508 |
| hyb_thr05_laya_ml_score | 0.917 | 0.902 | 0.930 | 0.910 | 0.620 |
| hyb_thr05_laya_ml_embed | 0.916 | 0.902 | 0.930 | 0.909 | 0.620 |
| learned_ridge | 0.957 | 0.883 | 0.930 | 0.919 | 0.545 |
| hyb_blend_w0.1_laya_ml_pair_k8 | 0.989 | 0.930 | 0.878 | 0.861 | 0.539 |
| hyb_blend_w0.5_bge_small_bi_ecore | 0.977 | 0.920 | 0.858 | 0.865 | 0.580 |
| laya_ml_pair_top10 | 0.963 | 0.890 | 0.915 | 0.915 | 0.526 |
| hyb_band05_laya_ml_pair_k8 | 0.989 | 0.927 | 0.855 | 0.887 | 0.535 |
| msmarco_minilm_ce_ft_real | 0.937 | 0.985 | 0.960 | 0.937 | 0.376 |
| hyb_band05_laya_ml_embed | 0.981 | 0.898 | 0.911 | 0.900 | 0.490 |
| hyb_main_bf1d59b_top10_laya_ml_noul | 0.966 | 0.913 | 0.855 | 0.897 | 0.568 |
| hyb_top10_laya_ml_noul | 0.966 | 0.913 | 0.855 | 0.896 | 0.568 |
| hyb_band05_laya_ml_noul | 0.983 | 0.925 | 0.863 | 0.779 | 0.596 |
| hyb_blend_w0.5_laya_ft_ml_full2 | 0.957 | 1.000 | 0.853 | 0.959 | 0.427 |
| hyb_blend_w0.1_laya_ml_score | 0.971 | 0.923 | 0.869 | 0.908 | 0.509 |
| hyb_blend_w0.5_minilm_bi_ecore | 0.965 | 0.910 | 0.896 | 0.854 | 0.542 |
| hyb_main_bf1d59b_thr05_laya_ml_noul | 0.886 | 0.902 | 0.930 | 0.904 | 0.620 |
| hyb_thr05_laya_ml_noul | 0.886 | 0.902 | 0.930 | 0.899 | 0.620 |
| hyb_rrblend_w0.3_laya_ml_embed | 0.970 | 0.852 | 0.905 | 0.900 | 0.545 |
| hyb_main_bf1d59b_blend_w0.2_laya_ml_noul | 0.983 | 0.892 | 0.842 | 0.940 | 0.521 |
| hyb_blend_w0.3_laya_ft_ml_full2 | 0.971 | 0.969 | 0.855 | 0.951 | 0.419 |
| hyb_blend_w0.1_laya_ml_embed | 0.981 | 0.894 | 0.910 | 0.862 | 0.482 |
| hyb_band05_laya_ml_score | 0.969 | 0.925 | 0.836 | 0.915 | 0.528 |
| hyb_blend_w0.2_laya_td_noul | 0.982 | 0.938 | 0.883 | 0.732 | 0.564 |
| hyb_main_bf1d59b_rrblend_w0.5_laya_ml_noul | 0.900 | 0.923 | 0.889 | 0.892 | 0.610 |
| hyb_blend_w0.1_laya_ml_noul | 0.983 | 0.914 | 0.883 | 0.745 | 0.575 |
| hyb_top10_laya_ml_embed | 0.986 | 0.889 | 0.858 | 0.891 | 0.517 |
| bge_reranker_base_ce_ecore | 0.965 | 0.924 | 0.933 | 0.920 | 0.367 |
| bge_reranker_base_ce_p4 | 0.965 | 0.924 | 0.933 | 0.920 | 0.367 |
| bge_reranker_base_ce_p8 | 0.965 | 0.924 | 0.933 | 0.920 | 0.367 |
| hyb_blend_w0.3_laya_td_noul | 0.970 | 0.955 | 0.828 | 0.725 | 0.583 |
| hyb_rrblend_w0.5_laya_ml_noul | 0.900 | 0.923 | 0.889 | 0.784 | 0.610 |
| hyb_rrblend_w0.5_laya_ml_score | 0.892 | 0.927 | 0.829 | 0.932 | 0.566 |
| hyb_rrblend_w0.5_laya_ml_pair_k8 | 0.929 | 0.941 | 0.795 | 0.865 | 0.562 |
| hyb_blend_w0.2_laya_ml_pair_k8 | 0.981 | 0.923 | 0.797 | 0.791 | 0.500 |
| hyb_main_bf1d59b_blend_w0.3_laya_ml_noul | 0.962 | 0.835 | 0.816 | 0.918 | 0.511 |
| laya_ft_ml_full2 | 0.886 | 0.972 | 0.849 | 0.960 | 0.402 |
| hyb_blend_w0.2_laya_ml_noul | 0.983 | 0.892 | 0.842 | 0.666 | 0.521 |
| hyb_blend_w0.2_laya_ml_score | 0.958 | 0.912 | 0.788 | 0.845 | 0.458 |
| hyb_blend_w0.3_laya_ml_pair_k8 | 0.973 | 0.935 | 0.708 | 0.780 | 0.465 |
| hyb_rrblend_w0.5_laya_ml_embed | 0.915 | 0.786 | 0.831 | 0.869 | 0.501 |
| distributor_order | 0.948 | 0.964 | 0.822 | 0.812 | 0.265 |
| hyb_blend_w0.2_laya_ml_embed | 0.970 | 0.793 | 0.827 | 0.817 | 0.406 |
| hyb_blend_w0.3_laya_ml_noul | 0.962 | 0.835 | 0.816 | 0.644 | 0.511 |
| hyb_blend_w0.3_laya_ml_score | 0.936 | 0.875 | 0.737 | 0.858 | 0.441 |
| bge_small_bi_ecore | 0.831 | 0.944 | 0.819 | 0.702 | 0.555 |
| bge_small_bi_p4 | 0.831 | 0.944 | 0.819 | 0.702 | 0.555 |
| bge_small_bi_p8 | 0.831 | 0.944 | 0.819 | 0.702 | 0.555 |
| hyb_blend_w0.5_laya_td_noul | 0.917 | 0.918 | 0.688 | 0.697 | 0.582 |
| minilm_bi_ecore | 0.808 | 0.865 | 0.864 | 0.768 | 0.497 |
| minilm_bi_p4 | 0.808 | 0.865 | 0.864 | 0.768 | 0.497 |
| minilm_bi_p8 | 0.808 | 0.865 | 0.864 | 0.768 | 0.497 |
| hyb_main_bf1d59b_blend_w0.5_laya_ml_noul | 0.884 | 0.793 | 0.745 | 0.853 | 0.484 |
| bm25 | 0.875 | 0.981 | 0.700 | 0.892 | 0.145 |
| hyb_blend_w0.3_laya_ml_embed | 0.950 | 0.740 | 0.670 | 0.798 | 0.395 |
| hyb_blend_w0.5_laya_ml_noul | 0.884 | 0.793 | 0.745 | 0.620 | 0.484 |
| hyb_blend_w0.5_laya_ml_score | 0.832 | 0.843 | 0.667 | 0.841 | 0.444 |
| hyb_blend_w0.5_laya_ml_pair_k8 | 0.924 | 0.879 | 0.630 | 0.706 | 0.368 |
| laya_ft_ml_full | 0.749 | 0.838 | 0.464 | 0.752 | 0.650 |
| hyb_blend_w0.5_laya_ml_embed | 0.894 | 0.663 | 0.540 | 0.804 | 0.387 |
| laya_td_noul | 0.680 | 0.778 | 0.632 | 0.667 | 0.569 |
| laya_td_noul_p4 | 0.680 | 0.778 | 0.632 | 0.667 | 0.569 |
| laya_td_noul_p8 | 0.680 | 0.778 | 0.632 | 0.667 | 0.569 |
| laya_ft_ml_head | 0.641 | 0.739 | 0.714 | 0.691 | 0.449 |
| laya_en_choice | 0.700 | 0.690 | 0.670 | 0.686 | 0.426 |
| laya_td_choice | 0.649 | 0.763 | 0.664 | 0.749 | 0.321 |
| laya_ml_pair_k1 | 0.672 | 0.676 | 0.532 | 0.643 | 0.511 |
| laya_ft_ml_head_synth | 0.769 | 0.568 | 0.531 | 0.713 | 0.340 |
| laya_ml_shortlist | 0.566 | 0.897 | 0.534 | 0.661 | 0.331 |
| laya_ml_pair_k8 | 0.659 | 0.774 | 0.533 | 0.602 | 0.327 |
| laya_ml_score | 0.492 | 0.788 | 0.593 | 0.720 | 0.446 |
| laya_en_noul | 0.548 | 0.665 | 0.642 | 0.482 | 0.527 |
| laya_ml_pair_k4 | 0.622 | 0.709 | 0.454 | 0.656 | 0.336 |
| laya_ml_noul_len192 | 0.473 | 0.717 | 0.603 | 0.549 | 0.463 |
| laya_ml_noul | 0.464 | 0.716 | 0.649 | 0.525 | 0.434 |
| laya_ml_noul_p4 | 0.464 | 0.716 | 0.649 | 0.525 | 0.434 |
| laya_ml_noul_p8 | 0.464 | 0.716 | 0.649 | 0.525 | 0.434 |
| laya_ml_noul_parsed | 0.480 | 0.711 | 0.619 | 0.513 | 0.453 |
| laya_ml_pair_k2 | 0.614 | 0.661 | 0.409 | 0.626 | 0.365 |
| laya_ml_decomp_prod | 0.446 | 0.749 | 0.590 | 0.544 | 0.387 |
| laya_ml_decomp_mean | 0.446 | 0.749 | 0.590 | 0.535 | 0.394 |
| laya_ml_embed | 0.596 | 0.563 | 0.392 | 0.746 | 0.406 |
| laya_ml_noul_plain | 0.389 | 0.619 | 0.657 | 0.425 | 0.404 |
| laya_ml_choice | 0.325 | 0.544 | 0.703 | 0.517 | 0.463 |
| laya_ml_embed_json | 0.451 | 0.520 | 0.480 | 0.661 | 0.265 |
