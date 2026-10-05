<!-- TABLE RESULTS_TABLE -->
| method | NDCG@5 | NDCG@10 | P@3 | MRR | Spearman | ms/query 8 thr (median / max) | ms/query 4 thr (median / max) | peak RSS | fits 20 s | dNDCG@10 vs det (95% CI) |
|---|---|---|---|---|---|---|---|---|---|---|
| Deterministic ranker (production Java) | 0.908 | 0.892 | 0.865 | 0.969 | 0.634 | 1 / 6 | - | in-process | yes |  |
| Deterministic ranker, main @ bf1d59b (connector-aware) | 0.917 | 0.898 | 0.865 | 0.984 | 0.641 | 5 / 14 | - | in-process | yes | [+0.000, +0.017] |
| Distributor order | 0.794 | 0.800 | 0.719 | 0.801 | 0.620 | 0 / 0 | - | - | yes | [-0.173, -0.022] |
| BM25 over the candidate set | 0.735 | 0.745 | 0.677 | 0.776 | 0.442 | 0 / 0 | - | - | yes | [-0.244, -0.058] |
| Learned weights, ridge (LOQO) | 0.876 | 0.868 | 0.792 | 0.916 | 0.681 | 0 / 0 | - | in-process | yes | [-0.052, -0.003] |
| Learned weights, pairwise logistic (LOQO) | 0.887 | 0.876 | 0.823 | 0.939 | 0.688 | 0 / 0 | - | in-process | yes | [-0.039, -0.001] |
| Bi-encoder all-MiniLM-L6-v2 | 0.789 | 0.777 | 0.688 | 0.833 | 0.566 | 145 / 272 | 252 / 479 | 860 MB | yes | [-0.186, -0.045] |
| Bi-encoder bge-small-en-v1.5 | 0.814 | 0.790 | 0.729 | 0.882 | 0.484 | 272 / 566 | 500 / 946 | 1074 MB | yes | [-0.159, -0.048] |
| Cross-encoder ms-marco-MiniLM-L6-v2 | 0.891 | 0.874 | 0.844 | 0.902 | 0.624 | 146 / 283 | 277 / 511 | 1021 MB | yes | [-0.088, +0.039] |
| Cross-encoder ms-marco-MiniLM-L6-v2, ONNX Runtime fp32 | 0.891 | 0.874 | 0.844 | 0.902 | 0.624 | 165 / 335 | 273 / 534 | 1020 MB | yes | [-0.088, +0.039] |
| Cross-encoder ms-marco-MiniLM-L6-v2, ONNX Runtime int8 | 0.892 | 0.877 | 0.844 | 0.911 | 0.627 | 85 / 199 | 135 / 288 | 913 MB | yes | [-0.082, +0.038] |
| Cross-encoder ms-marco-MiniLM-L12-v2 | 0.892 | 0.873 | 0.844 | 0.927 | 0.620 | 303 / 555 | 539 / 998 | 1060 MB | yes | [-0.088, +0.037] |
| Cross-encoder bge-reranker-base | 0.868 | 0.851 | 0.802 | 0.859 | 0.594 | 1079 / 1894 | 1957 / 3488 | 1929 MB | yes | [-0.111, +0.019] |
| MiniLM-L6 CE fine-tuned on real labels (2-fold) | 0.892 | 0.864 | 0.844 | 0.900 | 0.657 | 330 / 577 | - | - | yes | [-0.089, +0.024] |
| MiniLM-L6 CE fine-tuned on synthetic labels | 0.909 | 0.893 | 0.865 | 0.943 | 0.681 | 133 / 370 | - | - | yes | [-0.060, +0.052] |
| MiniLM-L6 CE fine-tuned on synthetic + real (2-fold) | 0.901 | 0.894 | 0.854 | 0.919 | 0.695 | 132 / 335 | - | - | yes | [-0.052, +0.051] |
| Hybrid: det + 0.2 x MiniLM-L6 CE (additive formula) | 0.924 | 0.892 | 0.885 | 0.932 | 0.672 | 297 / 532 | - | - | yes | [-0.044, +0.032] |
| Hybrid: rank blend 0.7 det / 0.3 MiniLM-L6 CE | 0.921 | 0.909 | 0.896 | 0.945 | 0.673 | 297 / 532 | - | - | yes | [+0.004, +0.032] |
| Hybrid: rank blend 0.5 det / 0.5 MiniLM-L6 CE (shipped) | 0.946 | 0.913 | 0.917 | 0.953 | 0.666 | 297 / 532 | - | - | yes | [+0.003, +0.041] |
| Hybrid: MiniLM-L6 CE re-orders det top 10 | 0.944 | 0.904 | 0.906 | 0.969 | 0.641 | 76 / 134 | - | - | yes | [-0.001, +0.028] |
| Hybrid: MiniLM-L6 CE inside det bands of 0.05 | 0.930 | 0.901 | 0.896 | 0.959 | 0.686 | 297 / 532 | - | - | yes | [-0.025, +0.036] |
| Hybrid: MiniLM-L6 CE only breaks det ties | 0.905 | 0.890 | 0.865 | 0.948 | 0.655 | 297 / 532 | - | - | yes | [-0.006, +0.000] |
| Hybrid: MiniLM-L6 CE on candidates with det >= 0.5 | 0.908 | 0.890 | 0.865 | 0.969 | 0.634 | 1 / 213 | - | - | yes | [-0.005, +0.000] |
| Hybrid: rank blend 0.5 det / 0.5 bge-reranker-base | 0.934 | 0.913 | 0.885 | 0.973 | 0.660 | 1889 / 3286 | - | - | yes | [+0.000, +0.044] |
| Hybrid: rank blend 0.5 det / 0.5 MiniLM-L6 CE fine-tuned synth only | 0.946 | 0.914 | 0.906 | 0.964 | 0.693 | 135 / 371 | - | - | yes | [+0.005, +0.042] |
| Hybrid: rank blend 0.5 det / 0.5 MiniLM-L6 CE fine-tuned synth+real | 0.945 | 0.917 | 0.906 | 0.975 | 0.701 | 135 / 339 | - | - | yes | [+0.007, +0.045] |
| Hybrid: main det @ bf1d59b, rank blend 0.5 with MiniLM-L6 CE | 0.950 | 0.913 | 0.917 | 0.953 | 0.671 | 304 / 537 | - | - | yes | [+0.003, +0.042] |
| Hybrid: main det @ bf1d59b, rank blend 0.5 with MiniLM-L6 CE fine-tuned synth+real | 0.947 | 0.917 | 0.906 | 0.975 | 0.703 | 141 / 346 | - | - | yes | [+0.007, +0.046] |

<!-- TABLE CATEGORY_TABLE -->
| method | passive | discrete | ic | crystal_connector | vague |
|---|---|---|---|---|---|
| Deterministic ranker (production Java) | 0.988 | 0.902 | 0.930 | 0.910 | 0.620 |
| Deterministic ranker, main @ bf1d59b (connector-aware) | 0.988 | 0.902 | 0.930 | 0.955 | 0.620 |
| Distributor order | 0.948 | 0.964 | 0.822 | 0.812 | 0.265 |
| BM25 over the candidate set | 0.875 | 0.981 | 0.700 | 0.892 | 0.145 |
| Learned weights, ridge (LOQO) | 0.957 | 0.883 | 0.930 | 0.919 | 0.545 |
| Learned weights, pairwise logistic (LOQO) | 0.988 | 0.883 | 0.930 | 0.910 | 0.543 |
| Bi-encoder all-MiniLM-L6-v2 | 0.808 | 0.865 | 0.864 | 0.768 | 0.497 |
| Bi-encoder bge-small-en-v1.5 | 0.831 | 0.944 | 0.819 | 0.702 | 0.555 |
| Cross-encoder ms-marco-MiniLM-L6-v2 | 0.938 | 1.000 | 0.957 | 0.967 | 0.402 |
| Cross-encoder ms-marco-MiniLM-L6-v2, ONNX Runtime fp32 | 0.938 | 1.000 | 0.957 | 0.967 | 0.402 |
| Cross-encoder ms-marco-MiniLM-L6-v2, ONNX Runtime int8 | 0.952 | 0.983 | 0.955 | 0.966 | 0.421 |
| Cross-encoder ms-marco-MiniLM-L12-v2 | 0.942 | 0.970 | 0.954 | 0.964 | 0.435 |
| Cross-encoder bge-reranker-base | 0.965 | 0.924 | 0.933 | 0.920 | 0.367 |
| MiniLM-L6 CE fine-tuned on real labels (2-fold) | 0.937 | 0.985 | 0.960 | 0.937 | 0.376 |
| MiniLM-L6 CE fine-tuned on synthetic labels | 0.986 | 1.000 | 0.941 | 0.967 | 0.454 |
| MiniLM-L6 CE fine-tuned on synthetic + real (2-fold) | 0.992 | 0.987 | 0.958 | 0.969 | 0.438 |
| Hybrid: det + 0.2 x MiniLM-L6 CE (additive formula) | 0.983 | 0.944 | 0.945 | 0.960 | 0.520 |
| Hybrid: rank blend 0.7 det / 0.3 MiniLM-L6 CE | 0.988 | 0.934 | 0.946 | 0.966 | 0.620 |
| Hybrid: rank blend 0.5 det / 0.5 MiniLM-L6 CE (shipped) | 0.992 | 0.956 | 0.954 | 0.966 | 0.603 |
| Hybrid: MiniLM-L6 CE re-orders det top 10 | 0.988 | 0.932 | 0.940 | 0.957 | 0.608 |
| Hybrid: MiniLM-L6 CE inside det bands of 0.05 | 0.983 | 0.943 | 0.943 | 0.960 | 0.580 |
| Hybrid: MiniLM-L6 CE only breaks det ties | 0.988 | 0.902 | 0.930 | 0.910 | 0.608 |
| Hybrid: MiniLM-L6 CE on candidates with det >= 0.5 | 0.983 | 0.902 | 0.930 | 0.910 | 0.620 |
| Hybrid: rank blend 0.5 det / 0.5 bge-reranker-base | 1.000 | 0.955 | 0.947 | 0.962 | 0.602 |
| Hybrid: rank blend 0.5 det / 0.5 MiniLM-L6 CE fine-tuned synth only | 0.996 | 0.957 | 0.949 | 0.967 | 0.605 |
| Hybrid: rank blend 0.5 det / 0.5 MiniLM-L6 CE fine-tuned synth+real | 0.996 | 0.960 | 0.954 | 0.967 | 0.611 |
| Hybrid: main det @ bf1d59b, rank blend 0.5 with MiniLM-L6 CE | 0.992 | 0.956 | 0.954 | 0.969 | 0.603 |
| Hybrid: main det @ bf1d59b, rank blend 0.5 with MiniLM-L6 CE fine-tuned synth+real | 0.996 | 0.960 | 0.954 | 0.969 | 0.611 |

<!-- TABLE NEURAL_TABLE -->
| model | params (M) | HF download (MB, all formats) | licence | NDCG@10 alone | best hybrid NDCG@10 (kind) |
|---|---|---|---|---|---|
| all-MiniLM-L6-v2 (bi-encoder) | 22.7 | 183 | Apache-2.0 | 0.777 | 0.903 (rrblend_w0.2) |
| bge-small-en-v1.5 (bi-encoder) | 33.4 | 269 | MIT | 0.790 | 0.902 (band05) |
| ms-marco-MiniLM-L6-v2 (cross-encoder) | 22.7 | 184 | Apache-2.0 | 0.874 | 0.913 (rrblend_w0.5) |
| ms-marco-MiniLM-L12-v2 (cross-encoder) | 33.4 | 269 | Apache-2.0 | 0.873 | 0.907 (rrblend_w0.5) |
| bge-reranker-base (cross-encoder) | 278.0 | 2269 | MIT | 0.851 | 0.913 (rrblend_w0.5) |
| MiniLM-L6 CE fine-tuned, real labels (2-fold) | 22.7 | - | Apache-2.0 (base) | 0.864 | 0.906 (rrblend_w0.5) |
| MiniLM-L6 CE fine-tuned, synthetic labels only | 22.7 | - | Apache-2.0 (base) | 0.893 | 0.914 (rrblend_w0.5) |
| MiniLM-L6 CE fine-tuned, synthetic + real (2-fold) | 22.7 | - | Apache-2.0 (base) | 0.894 | 0.917 (rrblend_w0.5) |

<!-- TABLE FINETUNE_TABLE -->
| MiniLM-L6 cross-encoder | training pairs (per fold) | training s (per fold, 16 E-cores) | NDCG@10 alone | Spearman alone | rank blend 0.5 NDCG@10 | blend dNDCG@10 vs det (95% CI) |
|---|---|---|---|---|---|---|
| zero-shot (reference) | - | - | 0.874 | 0.624 | 0.913 | [+0.003, +0.041] |
| MiniLM-L6 CE fine-tuned, real labels (2-fold) | 660 / 599 | 243 / 195 | 0.864 | 0.657 | 0.906 | [-0.004, +0.033] |
| MiniLM-L6 CE fine-tuned, synthetic labels only | 2872 | 187 | 0.893 | 0.681 | 0.914 | [+0.005, +0.042] |
| MiniLM-L6 CE fine-tuned, synthetic + real (2-fold) | 3532 / 3471 | 772 / 261 | 0.894 | 0.695 | 0.917 | [+0.007, +0.045] |

<!-- TABLE LEARNED_TABLE -->
| model | NDCG@10 | Spearman |
|---|---|---|
| hand-set weights (production) | 0.892 | 0.634 |
| ridge (LOQO) | 0.868 | 0.681 |
| pairwise logistic (LOQO) | 0.876 | 0.688 |

<!-- TABLE WEIGHTS_TABLE -->
| feature | hand-set weight | pairwise logistic on all queries (sum of abs = 1) |
|---|---|---|
| value | 0.30 | 0.20 |
| package | 0.20 | 0.09 |
| dielectric | 0.15 | 0.09 |
| rating | 0.10 | 0.08 |
| tolerance | 0.10 | 0.05 |
| family | 0.05 | 0.17 |
| lexical | 0.10 | 0.23 |
| stock | 0.03 | 0.09 |
| price | 0.01 | 0.00 |
| library | 0.01 | 0.01 |

<!-- TABLE HYBRID_TABLE -->
| hybrid (det = 0.892) | MiniLM-L6 CE | MiniLM-L12 CE | bge-reranker-base | MiniLM-L6 CE ft synth | MiniLM-L6 CE ft synth+real | all-MiniLM-L6 bi | bge-small bi |
|---|---|---|---|---|---|---|---|
| X alone | 0.874 | 0.873 | 0.851 | 0.893 | 0.894 | 0.777 | 0.790 |
| additive, w = 0.2: 0.8 det + 0.2 ranknorm(X) | 0.892 | 0.894 | 0.888 | 0.908 | 0.906 | 0.891 | 0.893 |
| rank blend, w = 0.1 | 0.896 | 0.892 | 0.895 | 0.895 | 0.895 | 0.893 | 0.899 |
| rank blend, w = 0.2 | 0.899 | 0.899 | 0.901 | 0.900 | 0.903 | 0.903 | 0.900 |
| rank blend, w = 0.3 | 0.909 | 0.902 | 0.910 | 0.905 | 0.905 | 0.900 | 0.902 |
| rank blend, w = 0.5 | 0.913 | 0.907 | 0.913 | 0.914 | 0.917 | 0.898 | 0.894 |
| X only breaks exact det ties | 0.890 | 0.891 | 0.892 | 0.889 | 0.890 | 0.892 | 0.891 |
| X inside det bands of 0.05 | 0.901 | 0.905 | 0.905 | 0.912 | 0.913 | 0.903 | 0.902 |
| X re-orders the det top 10 | 0.904 | 0.903 | 0.893 | 0.903 | 0.904 | 0.892 | 0.895 |
| X on candidates with det >= 0.5 | 0.890 | 0.892 | 0.892 | 0.892 | 0.892 | 0.889 | 0.887 |

<!-- TABLE SELECT_CV_TABLE -->
| family | configurations | LOQO NDCG@10 | best in sample (NDCG@10) | chosen (times out of 32) |
|---|---|---|---|---|
| hybrids det + ms-marco MiniLM-L6 CE | 12 | 0.913 | hyb_rrblend_w0.5_msmarco_minilm_ce_ecore (0.913) | hyb_rrblend_w0.5_msmarco_minilm_ce_ecore (32) |
| hybrids det + any signal | 96 | 0.903 | hyb_rrblend_w0.5_msmarco_minilm_ce_ft_synth_real (0.917) | hyb_rrblend_w0.5_msmarco_minilm_ce_ft_synth_real (31), hyb_band05_msmarco_minilm12_ce_ecore (1) |
| hybrids det + fine-tuned MiniLM CE (synth+real) | 12 | 0.917 | hyb_rrblend_w0.5_msmarco_minilm_ce_ft_synth_real (0.917) | hyb_rrblend_w0.5_msmarco_minilm_ce_ft_synth_real (32) |

<!-- TABLE NATIVE_TABLE -->
| method (27 queries, native candidates only) | NDCG@10 | dNDCG@10 vs det (95% CI) |
|---|---|---|
| Deterministic ranker | 0.946 |  |
| Distributor order | 0.884 | [-0.118, -0.016] |
| BM25 | 0.939 | [-0.034, +0.014] |
| MiniLM-L6 CE alone | 0.934 | [-0.039, +0.011] |
| rank blend 0.5 det / 0.5 MiniLM-L6 CE | 0.950 | [-0.006, +0.016] |
| rank blend 0.5 det / 0.5 MiniLM-L6 CE fine-tuned synth+real | 0.952 | [-0.002, +0.016] |

<!-- TABLE LATENCY_TABLE -->
| method | 8 threads, median / max ms | 4 threads, median / max ms | peak RSS (8 threads) | load s | Spearman vs PyTorch | artefact |
|---|---|---|---|---|---|---|
| deterministic ranker (Java, cold JVM pass) | 1.8 / 6.7 | same (single-threaded) | in the KINA JVM | - | - | none |
| MiniLM-L6 cross-encoder, PyTorch | 146 / 283 | 277 / 511 | 1021 MB | 1.4 | - | 184 MB download |
| MiniLM-L6 cross-encoder, ONNX Runtime fp32 | 165 / 335 | 273 / 534 | 1020 MB | - | 1.0000 | 91 MB model.onnx |
| MiniLM-L6 cross-encoder, ONNX Runtime int8 | 85 / 199 | 135 / 288 | 913 MB | - | 0.9981 | 23 MB model_int8.onnx |
| MiniLM-L12 cross-encoder, PyTorch | 303 / 555 | 539 / 998 | 1060 MB | 1.5 | - | 269 MB download |
| bge-reranker-base, PyTorch | 1079 / 1894 | 1957 / 3488 | 1929 MB | 4.9 | - | 2269 MB download |
| all-MiniLM-L6-v2 bi-encoder, PyTorch | 145 / 272 | 252 / 479 | 860 MB | 2.2 | - | 183 MB download |
| bge-small-en-v1.5 bi-encoder, PyTorch | 272 / 566 | 500 / 946 | 1074 MB | 2.4 | - | 269 MB download |

