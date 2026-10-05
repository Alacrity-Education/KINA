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
| hyb_band05_msmarco_minilm_ce_ecore | 0.930 | 0.901 | 0.896 | 0.959 | 0.686 | 303 / 533 | [-0.025, +0.036] |
| hyb_main_bf1d59b_band05_msmarco_minilm_ce_ecore | 0.930 | 0.901 | 0.896 | 0.959 | 0.688 | 308 / 538 | [-0.025, +0.036] |
| hyb_rrblend_w0.2_bge_reranker_base_ce_ecore | 0.919 | 0.901 | 0.885 | 0.969 | 0.662 | 2029 / 3286 | [+0.003, +0.015] |
| hyb_rrblend_w0.2_msmarco_minilm_ce_ft_synth | 0.909 | 0.900 | 0.875 | 0.958 | 0.672 | 152 / 372 | [-0.004, +0.024] |
| hyb_main_bf1d59b_rrblend_w0.2_msmarco_minilm_ce_ecore | 0.920 | 0.900 | 0.875 | 0.958 | 0.674 | 308 / 538 | [-0.001, +0.024] |
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
| hyb_main_bf1d59b_rrblend_w0.1_msmarco_minilm_ce_ft_synth_real | 0.917 | 0.897 | 0.865 | 0.961 | 0.674 | 156 / 347 | [-0.007, +0.020] |
| hyb_blend_w0.1_msmarco_minilm12_ce_ecore | 0.923 | 0.897 | 0.885 | 0.959 | 0.678 | 590 / 1047 | [-0.032, +0.032] |
| hyb_main_bf1d59b_thr05_msmarco_minilm_ce_ecore | 0.921 | 0.897 | 0.865 | 0.984 | 0.642 | 51 / 221 | [-0.005, +0.019] |
| hyb_main_bf1d59b_blend_w0.1_msmarco_minilm_ce_ecore | 0.934 | 0.896 | 0.885 | 0.938 | 0.679 | 308 / 538 | [-0.034, +0.033] |
| hyb_main_bf1d59b_tie_msmarco_minilm_ce_ecore | 0.914 | 0.896 | 0.865 | 0.964 | 0.665 | 308 / 538 | [-0.006, +0.017] |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ecore | 0.910 | 0.896 | 0.865 | 0.964 | 0.659 | 303 / 533 | [-0.004, +0.014] |
| hyb_blend_w0.1_msmarco_minilm_ce_ecore | 0.930 | 0.896 | 0.885 | 0.938 | 0.678 | 303 / 533 | [-0.035, +0.032] |
| hyb_main_bf1d59b_tie_msmarco_minilm_ce_ft_synth_real | 0.914 | 0.895 | 0.865 | 0.964 | 0.669 | 156 / 347 | [-0.007, +0.018] |
| hyb_top10_bge_small_bi_ecore | 0.910 | 0.895 | 0.875 | 0.969 | 0.633 | 139 / 241 | [-0.008, +0.017] |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ft_synth_real | 0.912 | 0.895 | 0.865 | 0.961 | 0.664 | 151 / 340 | [-0.007, +0.015] |
| hyb_blend_w0.1_bge_small_bi_ecore | 0.919 | 0.895 | 0.885 | 0.969 | 0.664 | 545 / 959 | [-0.012, +0.018] |
| hyb_rrblend_w0.1_bge_reranker_base_ce_ecore | 0.910 | 0.895 | 0.875 | 0.969 | 0.657 | 2029 / 3286 | [+0.000, +0.006] |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ft_synth | 0.910 | 0.895 | 0.865 | 0.961 | 0.663 | 152 / 372 | [-0.007, +0.014] |
| msmarco_minilm_ce_ft_synth_real | 0.901 | 0.894 | 0.854 | 0.919 | 0.695 | 149 / 335 | [-0.052, +0.051] |
| hyb_rrblend_w0.5_bge_small_bi_ecore | 0.909 | 0.894 | 0.854 | 0.969 | 0.630 | 545 / 959 | [-0.013, +0.018] |
| hyb_blend_w0.2_msmarco_minilm12_ce_ecore | 0.913 | 0.894 | 0.875 | 0.957 | 0.668 | 590 / 1047 | [-0.047, +0.038] |
| hyb_top10_bge_reranker_base_ce_ecore | 0.929 | 0.893 | 0.896 | 0.904 | 0.640 | 514 / 823 | [-0.010, +0.014] |
| msmarco_minilm_ce_ft_synth | 0.909 | 0.893 | 0.865 | 0.943 | 0.681 | 150 / 371 | [-0.060, +0.052] |
| hyb_rrblend_w0.1_minilm_bi_ecore | 0.906 | 0.893 | 0.875 | 0.953 | 0.660 | 282 / 493 | [-0.003, +0.004] |
| hyb_blend_w0.2_bge_small_bi_ecore | 0.916 | 0.893 | 0.885 | 0.984 | 0.662 | 545 / 959 | [-0.019, +0.020] |
| hyb_top10_minilm_bi_ecore | 0.915 | 0.892 | 0.885 | 0.929 | 0.636 | 73 / 124 | [-0.009, +0.011] |
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
| hyb_blend_w0.3_msmarco_minilm_ce_ecore | 0.918 | 0.892 | 0.885 | 0.931 | 0.662 | 303 / 533 | [-0.053, +0.037] |
| hyb_tie_minilm_bi_ecore | 0.908 | 0.892 | 0.875 | 0.953 | 0.655 | 282 / 493 | [-0.004, +0.003] |
| hyb_rrblend_w0.1_msmarco_minilm12_ce_ecore | 0.902 | 0.892 | 0.885 | 0.948 | 0.660 | 590 / 1047 | [-0.005, +0.003] |
| hyb_tie_bge_small_bi_ecore | 0.907 | 0.891 | 0.865 | 0.953 | 0.651 | 545 / 959 | [-0.004, +0.002] |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ft_real | 0.905 | 0.891 | 0.875 | 0.948 | 0.662 | 330 / 580 | [-0.007, +0.004] |
| hyb_blend_w0.2_minilm_bi_ecore | 0.914 | 0.891 | 0.885 | 0.934 | 0.690 | 282 / 493 | [-0.024, +0.022] |
| hyb_tie_msmarco_minilm12_ce_ecore | 0.904 | 0.891 | 0.875 | 0.948 | 0.658 | 590 / 1047 | [-0.006, +0.002] |
| hyb_thr05_msmarco_minilm_ce_ecore | 0.908 | 0.890 | 0.865 | 0.969 | 0.634 | 42 / 214 | [-0.005, +0.000] |
| hyb_thr05_msmarco_minilm_ce_ft_real | 0.908 | 0.890 | 0.865 | 0.969 | 0.633 | 46 / 312 | [-0.006, +0.000] |
| hyb_tie_msmarco_minilm_ce_ecore | 0.905 | 0.890 | 0.865 | 0.948 | 0.655 | 303 / 533 | [-0.006, +0.000] |
| hyb_blend_w0.2_msmarco_minilm_ce_ft_real | 0.921 | 0.890 | 0.906 | 0.957 | 0.685 | 330 / 580 | [-0.031, +0.023] |
| hyb_tie_msmarco_minilm_ce_ft_real | 0.905 | 0.890 | 0.865 | 0.948 | 0.658 | 330 / 580 | [-0.008, +0.001] |
| hyb_tie_msmarco_minilm_ce_ft_synth_real | 0.905 | 0.890 | 0.865 | 0.948 | 0.660 | 151 / 340 | [-0.008, +0.001] |
| hyb_main_bf1d59b_blend_w0.5_msmarco_minilm_ce_ecore | 0.911 | 0.890 | 0.865 | 0.919 | 0.651 | 308 / 538 | [-0.066, +0.046] |
| hyb_blend_w0.3_bge_reranker_base_ce_ecore | 0.912 | 0.890 | 0.885 | 0.943 | 0.651 | 2029 / 3286 | [-0.055, +0.042] |
| hyb_blend_w0.5_msmarco_minilm_ce_ecore | 0.909 | 0.889 | 0.865 | 0.919 | 0.652 | 303 / 533 | [-0.066, +0.045] |
| hyb_tie_msmarco_minilm_ce_ft_synth | 0.901 | 0.889 | 0.865 | 0.948 | 0.659 | 152 / 372 | [-0.009, +0.000] |
| hyb_thr05_minilm_bi_ecore | 0.902 | 0.889 | 0.854 | 0.969 | 0.633 | 39 / 204 | [-0.010, +0.000] |
| hyb_blend_w0.3_minilm_bi_ecore | 0.907 | 0.888 | 0.865 | 0.931 | 0.686 | 282 / 493 | [-0.039, +0.029] |
| hyb_blend_w0.2_bge_reranker_base_ce_ecore | 0.911 | 0.888 | 0.885 | 0.944 | 0.650 | 2029 / 3286 | [-0.049, +0.031] |
| hyb_blend_w0.3_msmarco_minilm_ce_ft_real | 0.916 | 0.887 | 0.896 | 0.935 | 0.683 | 330 / 580 | [-0.041, +0.025] |
| hyb_thr05_bge_small_bi_ecore | 0.898 | 0.887 | 0.844 | 0.953 | 0.632 | 73 / 374 | [-0.014, +0.000] |
| hyb_blend_w0.5_msmarco_minilm12_ce_ecore | 0.905 | 0.886 | 0.854 | 0.944 | 0.645 | 590 / 1047 | [-0.069, +0.040] |
| hyb_blend_w0.3_bge_small_bi_ecore | 0.913 | 0.885 | 0.896 | 0.984 | 0.648 | 545 / 959 | [-0.029, +0.015] |
| hyb_blend_w0.5_msmarco_minilm_ce_ft_real | 0.897 | 0.879 | 0.854 | 0.904 | 0.678 | 330 / 580 | [-0.064, +0.027] |
| msmarco_minilm_ce_onnx_int8_t4 | 0.892 | 0.877 | 0.844 | 0.911 | 0.627 | 150 / 288 | [-0.082, +0.038] |
| msmarco_minilm_ce_onnx_int8_t8 | 0.892 | 0.877 | 0.844 | 0.911 | 0.627 | 97 / 200 | [-0.082, +0.038] |
| learned_pairwise | 0.887 | 0.876 | 0.823 | 0.939 | 0.688 | 0 / 0 | [-0.039, -0.001] |
| hyb_blend_w0.5_bge_reranker_base_ce_ecore | 0.893 | 0.875 | 0.844 | 0.882 | 0.639 | 2029 / 3286 | [-0.082, +0.036] |
| msmarco_minilm_ce_ecore | 0.891 | 0.874 | 0.844 | 0.902 | 0.624 | 301 / 531 | [-0.088, +0.039] |
| msmarco_minilm_ce_onnx_t4 | 0.891 | 0.874 | 0.844 | 0.902 | 0.624 | 297 / 534 | [-0.088, +0.039] |
| msmarco_minilm_ce_onnx_t8 | 0.891 | 0.874 | 0.844 | 0.902 | 0.624 | 182 / 335 | [-0.088, +0.039] |
| msmarco_minilm_ce_p4 | 0.891 | 0.874 | 0.844 | 0.902 | 0.624 | 289 / 512 | [-0.088, +0.039] |
| msmarco_minilm_ce_p8 | 0.891 | 0.874 | 0.844 | 0.902 | 0.624 | 160 / 283 | [-0.088, +0.039] |
| msmarco_minilm12_ce_ecore | 0.892 | 0.873 | 0.844 | 0.927 | 0.620 | 589 / 1046 | [-0.088, +0.037] |
| msmarco_minilm12_ce_p4 | 0.892 | 0.873 | 0.844 | 0.927 | 0.620 | 568 / 999 | [-0.088, +0.037] |
| msmarco_minilm12_ce_p8 | 0.892 | 0.873 | 0.844 | 0.927 | 0.620 | 317 / 555 | [-0.088, +0.037] |
| learned_ridge | 0.876 | 0.868 | 0.792 | 0.916 | 0.681 | 0 / 0 | [-0.052, -0.003] |
| hyb_blend_w0.5_bge_small_bi_ecore | 0.897 | 0.864 | 0.844 | 0.984 | 0.593 | 545 / 959 | [-0.056, +0.000] |
| msmarco_minilm_ce_ft_real | 0.892 | 0.864 | 0.844 | 0.900 | 0.657 | 329 / 578 | [-0.089, +0.024] |
| hyb_blend_w0.5_minilm_bi_ecore | 0.869 | 0.859 | 0.802 | 0.914 | 0.664 | 282 / 493 | [-0.084, +0.014] |
| bge_reranker_base_ce_ecore | 0.868 | 0.851 | 0.802 | 0.859 | 0.594 | 2027 / 3285 | [-0.111, +0.019] |
| bge_reranker_base_ce_p4 | 0.868 | 0.851 | 0.802 | 0.859 | 0.594 | 2126 / 3488 | [-0.111, +0.019] |
| bge_reranker_base_ce_p8 | 0.868 | 0.851 | 0.802 | 0.859 | 0.594 | 1155 / 1894 | [-0.111, +0.019] |
| distributor_order | 0.794 | 0.800 | 0.719 | 0.801 | 0.620 | 0 / 0 | [-0.173, -0.022] |
| bge_small_bi_ecore | 0.814 | 0.790 | 0.729 | 0.882 | 0.484 | 543 / 958 | [-0.159, -0.048] |
| bge_small_bi_p4 | 0.814 | 0.790 | 0.729 | 0.882 | 0.484 | 529 / 947 | [-0.159, -0.048] |
| bge_small_bi_p8 | 0.814 | 0.790 | 0.729 | 0.882 | 0.484 | 291 / 567 | [-0.159, -0.048] |
| minilm_bi_ecore | 0.789 | 0.777 | 0.688 | 0.833 | 0.566 | 280 / 491 | [-0.186, -0.045] |
| minilm_bi_p4 | 0.789 | 0.777 | 0.688 | 0.833 | 0.566 | 267 / 479 | [-0.186, -0.045] |
| minilm_bi_p8 | 0.789 | 0.777 | 0.688 | 0.833 | 0.566 | 152 / 272 | [-0.186, -0.045] |
| bm25 | 0.735 | 0.745 | 0.677 | 0.776 | 0.442 | 0 / 1 | [-0.244, -0.058] |

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
| hyb_band05_msmarco_minilm_ce_ecore | 0.983 | 0.943 | 0.943 | 0.960 | 0.580 |
| hyb_main_bf1d59b_band05_msmarco_minilm_ce_ecore | 0.983 | 0.943 | 0.943 | 0.960 | 0.580 |
| hyb_rrblend_w0.2_bge_reranker_base_ce_ecore | 0.992 | 0.910 | 0.946 | 0.919 | 0.630 |
| hyb_rrblend_w0.2_msmarco_minilm_ce_ft_synth | 0.988 | 0.912 | 0.945 | 0.954 | 0.606 |
| hyb_main_bf1d59b_rrblend_w0.2_msmarco_minilm_ce_ecore | 0.988 | 0.908 | 0.937 | 0.960 | 0.616 |
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
| hyb_main_bf1d59b_rrblend_w0.1_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.908 | 0.934 | 0.957 | 0.601 |
| hyb_blend_w0.1_msmarco_minilm12_ce_ecore | 0.989 | 0.930 | 0.949 | 0.948 | 0.560 |
| hyb_main_bf1d59b_thr05_msmarco_minilm_ce_ecore | 0.983 | 0.902 | 0.930 | 0.960 | 0.620 |
| hyb_main_bf1d59b_blend_w0.1_msmarco_minilm_ce_ecore | 0.983 | 0.943 | 0.947 | 0.960 | 0.545 |
| hyb_main_bf1d59b_tie_msmarco_minilm_ce_ecore | 0.988 | 0.902 | 0.930 | 0.955 | 0.608 |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ecore | 0.988 | 0.905 | 0.934 | 0.943 | 0.607 |
| hyb_blend_w0.1_msmarco_minilm_ce_ecore | 0.983 | 0.943 | 0.947 | 0.955 | 0.545 |
| hyb_main_bf1d59b_tie_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.902 | 0.930 | 0.955 | 0.606 |
| hyb_top10_bge_small_bi_ecore | 0.980 | 0.920 | 0.930 | 0.949 | 0.604 |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.908 | 0.934 | 0.943 | 0.601 |
| hyb_blend_w0.1_bge_small_bi_ecore | 0.988 | 0.925 | 0.929 | 0.921 | 0.604 |
| hyb_rrblend_w0.1_bge_reranker_base_ce_ecore | 0.988 | 0.905 | 0.934 | 0.910 | 0.629 |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ft_synth | 0.988 | 0.908 | 0.934 | 0.943 | 0.598 |
| msmarco_minilm_ce_ft_synth_real | 0.992 | 0.987 | 0.958 | 0.969 | 0.438 |
| hyb_rrblend_w0.5_bge_small_bi_ecore | 0.979 | 0.922 | 0.921 | 0.945 | 0.614 |
| hyb_blend_w0.2_msmarco_minilm12_ce_ecore | 0.988 | 0.945 | 0.944 | 0.955 | 0.525 |
| hyb_top10_bge_reranker_base_ce_ecore | 0.988 | 0.922 | 0.933 | 0.924 | 0.590 |
| msmarco_minilm_ce_ft_synth | 0.986 | 1.000 | 0.941 | 0.967 | 0.454 |
| hyb_rrblend_w0.1_minilm_bi_ecore | 0.988 | 0.903 | 0.934 | 0.909 | 0.618 |
| hyb_blend_w0.2_bge_small_bi_ecore | 0.988 | 0.925 | 0.929 | 0.917 | 0.592 |
| hyb_top10_minilm_bi_ecore | 0.982 | 0.927 | 0.938 | 0.910 | 0.594 |
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
| hyb_blend_w0.3_msmarco_minilm_ce_ecore | 0.983 | 0.966 | 0.944 | 0.959 | 0.494 |
| hyb_tie_minilm_bi_ecore | 0.988 | 0.902 | 0.930 | 0.910 | 0.619 |
| hyb_rrblend_w0.1_msmarco_minilm12_ce_ecore | 0.988 | 0.905 | 0.934 | 0.910 | 0.609 |
| hyb_tie_bge_small_bi_ecore | 0.988 | 0.902 | 0.930 | 0.910 | 0.616 |
| hyb_rrblend_w0.1_msmarco_minilm_ce_ft_real | 0.988 | 0.907 | 0.934 | 0.910 | 0.604 |
| hyb_blend_w0.2_minilm_bi_ecore | 0.988 | 0.921 | 0.953 | 0.869 | 0.592 |
| hyb_tie_msmarco_minilm12_ce_ecore | 0.988 | 0.902 | 0.930 | 0.910 | 0.612 |
| hyb_thr05_msmarco_minilm_ce_ecore | 0.983 | 0.902 | 0.930 | 0.910 | 0.620 |
| hyb_thr05_msmarco_minilm_ce_ft_real | 0.982 | 0.902 | 0.930 | 0.910 | 0.620 |
| hyb_tie_msmarco_minilm_ce_ecore | 0.988 | 0.902 | 0.930 | 0.910 | 0.608 |
| hyb_blend_w0.2_msmarco_minilm_ce_ft_real | 0.983 | 0.953 | 0.947 | 0.934 | 0.513 |
| hyb_tie_msmarco_minilm_ce_ft_real | 0.988 | 0.902 | 0.930 | 0.910 | 0.606 |
| hyb_tie_msmarco_minilm_ce_ft_synth_real | 0.988 | 0.902 | 0.930 | 0.910 | 0.606 |
| hyb_main_bf1d59b_blend_w0.5_msmarco_minilm_ce_ecore | 0.986 | 0.990 | 0.943 | 0.969 | 0.438 |
| hyb_blend_w0.3_bge_reranker_base_ce_ecore | 0.992 | 0.977 | 0.936 | 0.960 | 0.458 |
| hyb_blend_w0.5_msmarco_minilm_ce_ecore | 0.986 | 0.990 | 0.943 | 0.967 | 0.438 |
| hyb_tie_msmarco_minilm_ce_ft_synth | 0.988 | 0.902 | 0.930 | 0.910 | 0.601 |
| hyb_thr05_minilm_bi_ecore | 0.977 | 0.902 | 0.930 | 0.910 | 0.620 |
| hyb_blend_w0.3_minilm_bi_ecore | 0.988 | 0.925 | 0.942 | 0.870 | 0.581 |
| hyb_blend_w0.2_bge_reranker_base_ce_ecore | 0.989 | 0.933 | 0.941 | 0.956 | 0.502 |
| hyb_blend_w0.3_msmarco_minilm_ce_ft_real | 0.982 | 0.954 | 0.946 | 0.940 | 0.494 |
| hyb_thr05_bge_small_bi_ecore | 0.973 | 0.902 | 0.930 | 0.908 | 0.620 |
| hyb_blend_w0.5_msmarco_minilm12_ce_ecore | 0.991 | 0.963 | 0.940 | 0.955 | 0.455 |
| hyb_blend_w0.3_bge_small_bi_ecore | 0.984 | 0.926 | 0.900 | 0.912 | 0.593 |
| hyb_blend_w0.5_msmarco_minilm_ce_ft_real | 0.986 | 0.968 | 0.946 | 0.937 | 0.417 |
| msmarco_minilm_ce_onnx_int8_t4 | 0.952 | 0.983 | 0.955 | 0.966 | 0.421 |
| msmarco_minilm_ce_onnx_int8_t8 | 0.952 | 0.983 | 0.955 | 0.966 | 0.421 |
| learned_pairwise | 0.988 | 0.883 | 0.930 | 0.910 | 0.543 |
| hyb_blend_w0.5_bge_reranker_base_ce_ecore | 0.996 | 0.979 | 0.933 | 0.924 | 0.387 |
| msmarco_minilm_ce_ecore | 0.938 | 1.000 | 0.957 | 0.967 | 0.402 |
| msmarco_minilm_ce_onnx_t4 | 0.938 | 1.000 | 0.957 | 0.967 | 0.402 |
| msmarco_minilm_ce_onnx_t8 | 0.938 | 1.000 | 0.957 | 0.967 | 0.402 |
| msmarco_minilm_ce_p4 | 0.938 | 1.000 | 0.957 | 0.967 | 0.402 |
| msmarco_minilm_ce_p8 | 0.938 | 1.000 | 0.957 | 0.967 | 0.402 |
| msmarco_minilm12_ce_ecore | 0.942 | 0.970 | 0.954 | 0.964 | 0.435 |
| msmarco_minilm12_ce_p4 | 0.942 | 0.970 | 0.954 | 0.964 | 0.435 |
| msmarco_minilm12_ce_p8 | 0.942 | 0.970 | 0.954 | 0.964 | 0.435 |
| learned_ridge | 0.957 | 0.883 | 0.930 | 0.919 | 0.545 |
| hyb_blend_w0.5_bge_small_bi_ecore | 0.977 | 0.920 | 0.858 | 0.865 | 0.580 |
| msmarco_minilm_ce_ft_real | 0.937 | 0.985 | 0.960 | 0.937 | 0.376 |
| hyb_blend_w0.5_minilm_bi_ecore | 0.965 | 0.910 | 0.896 | 0.854 | 0.542 |
| bge_reranker_base_ce_ecore | 0.965 | 0.924 | 0.933 | 0.920 | 0.367 |
| bge_reranker_base_ce_p4 | 0.965 | 0.924 | 0.933 | 0.920 | 0.367 |
| bge_reranker_base_ce_p8 | 0.965 | 0.924 | 0.933 | 0.920 | 0.367 |
| distributor_order | 0.948 | 0.964 | 0.822 | 0.812 | 0.265 |
| bge_small_bi_ecore | 0.831 | 0.944 | 0.819 | 0.702 | 0.555 |
| bge_small_bi_p4 | 0.831 | 0.944 | 0.819 | 0.702 | 0.555 |
| bge_small_bi_p8 | 0.831 | 0.944 | 0.819 | 0.702 | 0.555 |
| minilm_bi_ecore | 0.808 | 0.865 | 0.864 | 0.768 | 0.497 |
| minilm_bi_p4 | 0.808 | 0.865 | 0.864 | 0.768 | 0.497 |
| minilm_bi_p8 | 0.808 | 0.865 | 0.864 | 0.768 | 0.497 |
| bm25 | 0.875 | 0.981 | 0.700 | 0.892 | 0.145 |
