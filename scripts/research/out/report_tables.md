| method | NDCG@5 | NDCG@10 | P@3 | MRR | Spearman | ms/query 8 thr (median / max) | ms/query 4 thr (median / max) | peak RAM | fits 20 s | dNDCG@10 vs det (95% CI) |
|---|---|---|---|---|---|---|---|---|---|---|
| Deterministic ranker (production Java) | 0.908 | 0.892 | 0.865 | 0.969 | 0.634 | 1 / 6 | - / - | in-process, < 1 MB | yes |  |
| Deterministic ranker, main @ bf1d59b (connector-aware) | 0.917 | 0.898 | 0.865 | 0.984 | 0.641 | 5 / 14 | - / - | in-process, < 1 MB | yes | [+0.000, +0.017] |
| Distributor order | 0.794 | 0.800 | 0.719 | 0.801 | 0.620 | 0 / 0 | - / - | - | yes | [-0.173, -0.022] |
| BM25 over the candidate set | 0.735 | 0.745 | 0.677 | 0.776 | 0.442 | 0 / 0 | - / - | - | yes | [-0.244, -0.058] |
| Learned weights, ridge (LOQO) | 0.876 | 0.868 | 0.792 | 0.916 | 0.681 | 0 / 0 | - / - | in-process | yes | [-0.052, -0.003] |
| Learned weights, pairwise logistic (LOQO) | 0.887 | 0.876 | 0.823 | 0.939 | 0.688 | 0 / 0 | - / - | in-process | yes | [-0.039, -0.001] |
| Laya multilingual noul, JSON state (current) | 0.542 | 0.555 | 0.438 | 0.594 | 0.187 | 2396 / 3014 | 4258 / 5348 | 2.2 GB (server) | yes | [-0.436, -0.240] |
| Laya multilingual score scale | 0.558 | 0.591 | 0.427 | 0.598 | 0.225 | 2726 / 3790 | - / - | 2.2 GB (server) | yes | [-0.401, -0.202] |
| Laya multilingual choice (4 contrasting options) | 0.452 | 0.494 | 0.344 | 0.522 | 0.043 | 2764 / 3282 | - / - | 2.2 GB (server) | yes | [-0.492, -0.306] |
| Laya multilingual 4 per-attribute nouls, mean | 0.522 | 0.537 | 0.417 | 0.552 | 0.169 | 10256 / 12932 | - / - | 2.2 GB (server) | yes | [-0.453, -0.257] |
| Laya multilingual noul, plain-text state | 0.484 | 0.498 | 0.365 | 0.511 | 0.114 | 3151 / 4573 | - / - | 2.2 GB (server) | yes | [-0.482, -0.308] |
| Laya multilingual noul, parsed query attributes in state | 0.511 | 0.554 | 0.396 | 0.519 | 0.217 | 2710 / 3243 | - / - | 2.2 GB (server) | yes | [-0.434, -0.246] |
| Laya multilingual noul, max_len 192 / head_max_len 64 | 0.545 | 0.555 | 0.427 | 0.589 | 0.175 | 2332 / 3226 | - / - | 2.2 GB (server) | yes | [-0.435, -0.238] |
| Laya english noul | 0.557 | 0.579 | 0.417 | 0.563 | 0.245 | 7386 / 12032 | - / - | 2.2 GB (server) | yes | [-0.409, -0.221] |
| Laya english choice | 0.637 | 0.647 | 0.542 | 0.703 | 0.316 | 7229 / 8927 | - / - | 2.2 GB (server) | yes | [-0.322, -0.171] |
| Laya typed-decisions noul | 0.647 | 0.669 | 0.521 | 0.627 | 0.392 | 6082 / 7407 | 10908 / 13332 | 2.1 GB (server) | yes | [-0.303, -0.141] |
| Laya typed-decisions choice | 0.597 | 0.635 | 0.479 | 0.573 | 0.378 | 7141 / 8390 | - / - | 2.1 GB (server) | yes | [-0.337, -0.181] |
| Laya pairwise, 1 round (n/2 pairs), Bradley-Terry | 0.627 | 0.613 | 0.500 | 0.721 | 0.088 | 2025 / 2633 | - / - | 2.2 GB (server) | yes | [-0.355, -0.196] |
| Laya pairwise, 2 rounds (n pairs) | 0.553 | 0.541 | 0.427 | 0.580 | 0.065 | 3941 / 5328 | - / - | 2.2 GB (server) | yes | [-0.428, -0.275] |
| Laya pairwise, 4 rounds (2n pairs) | 0.568 | 0.561 | 0.438 | 0.607 | 0.107 | 7901 / 10420 | - / - | 2.2 GB (server) | yes | [-0.402, -0.259] |
| Laya pairwise, 8 rounds (4n pairs) | 0.596 | 0.594 | 0.448 | 0.636 | 0.127 | 15934 / 20551 | - / - | 2.2 GB (server) | no | [-0.376, -0.222] |
| Laya pairwise round robin of det top 10 (45 pairs) | 0.853 | 0.864 | 0.760 | 0.825 | 0.626 | 4541 / 5810 | - / - | 2.2 GB (server) | yes | [-0.047, -0.010] |
| Laya multilingual embeddings, cosine (SDK) | 0.503 | 0.534 | 0.354 | 0.529 | 0.113 | 2566 / 4598 | - / - | 2.2 GB (server) | yes | [-0.458, -0.257] |
| Laya multilingual embeddings vs JSON state (SDK) | 0.440 | 0.467 | 0.229 | 0.378 | -0.027 | 2484 / 3409 | - / - | 2.2 GB (server) | yes | [-0.509, -0.345] |
| Laya predict_shortlist k=10 + choice (SDK) | 0.613 | 0.596 | 0.458 | 0.603 | 0.128 | 1225 / 1727 | - / - | 2.2 GB (server) | yes | [-0.378, -0.213] |
| Laya multilingual, typed head fine-tuned (2-fold) | 0.625 | 0.651 | 0.469 | 0.730 | 0.320 | 1910 / 2375 | - / - | 2.2 GB (server) | yes | [-0.304, -0.179] |
| Laya multilingual, typed head fine-tuned on synthetic labels | 0.599 | 0.605 | 0.479 | 0.614 | 0.200 | 1945 / 2384 | - / - | 2.2 GB (server) | yes | [-0.373, -0.200] |
| Laya multilingual, full fine-tune 3 epochs (2-fold) | 0.832 | 0.828 | 0.750 | 0.853 | 0.561 | 1971 / 2466 | - / - | 2.2 GB (server) | yes | [-0.137, +0.002] |
| Hybrid: rank blend 0.7 det / 0.3 Laya full fine-tune | 0.930 | 0.907 | 0.885 | 0.952 | 0.668 | 1974 / 2468 | - / - | 2.2 GB (server) | yes | [-0.004, +0.035] |
| Bi-encoder all-MiniLM-L6-v2 | 0.789 | 0.777 | 0.688 | 0.833 | 0.566 | 145 / 272 | 252 / 479 | 860 MB | yes | [-0.186, -0.045] |
| Bi-encoder bge-small-en-v1.5 | 0.814 | 0.790 | 0.729 | 0.882 | 0.484 | 272 / 566 | 500 / 946 | 1074 MB | yes | [-0.159, -0.048] |
| Cross-encoder ms-marco-MiniLM-L6-v2 | 0.891 | 0.874 | 0.844 | 0.902 | 0.624 | 146 / 283 | 277 / 511 | 1021 MB | yes | [-0.088, +0.039] |
| Cross-encoder ms-marco-MiniLM-L6-v2, ONNX Runtime fp32 | 0.891 | 0.874 | 0.844 | 0.902 | 0.624 | 165 / 335 | 273 / 534 | 1020 MB | yes | [-0.088, +0.039] |
| Cross-encoder ms-marco-MiniLM-L6-v2, ONNX Runtime int8 | 0.892 | 0.877 | 0.844 | 0.911 | 0.627 | 85 / 199 | 135 / 288 | 913 MB | yes | [-0.082, +0.038] |
| Cross-encoder ms-marco-MiniLM-L12-v2 | 0.892 | 0.873 | 0.844 | 0.927 | 0.620 | 303 / 555 | 539 / 998 | 1060 MB | yes | [-0.088, +0.037] |
| Cross-encoder bge-reranker-base | 0.868 | 0.851 | 0.802 | 0.859 | 0.594 | 1079 / 1894 | 1957 / 3488 | 1929 MB | yes | [-0.111, +0.019] |
| MiniLM-L6 CE fine-tuned on real labels (2-fold) | 0.892 | 0.864 | 0.844 | 0.900 | 0.657 | 330 / 577 | - / - | neural | yes | [-0.089, +0.024] |
| MiniLM-L6 CE fine-tuned on synthetic labels | 0.909 | 0.893 | 0.865 | 0.943 | 0.681 | 133 / 370 | - / - | neural | yes | [-0.060, +0.052] |
| MiniLM-L6 CE fine-tuned on synthetic + real (2-fold) | 0.901 | 0.894 | 0.854 | 0.919 | 0.695 | 132 / 335 | - / - | neural | yes | [-0.052, +0.051] |
| Hybrid: current blend, det + 0.2 x Laya ml noul | 0.829 | 0.823 | 0.781 | 0.830 | 0.587 | 2406 / 3139 | - / - | 2.2 GB (server) | yes | [-0.143, -0.011] |
| Hybrid: Laya ml noul only breaks det ties | 0.901 | 0.889 | 0.854 | 0.948 | 0.640 | 2406 / 3139 | - / - | 2.2 GB (server) | yes | [-0.009, +0.000] |
| Hybrid: rank blend 0.7 det / 0.3 Laya td noul | 0.923 | 0.896 | 0.896 | 0.937 | 0.652 | 6847 / 8511 | - / - | 2.1 GB (server) | yes | [-0.016, +0.024] |
| Hybrid: det + 0.2 x MiniLM-L6 CE (production formula) | 0.924 | 0.892 | 0.885 | 0.932 | 0.672 | 297 / 532 | - / - | neural | yes | [-0.044, +0.032] |
| Hybrid: rank blend 0.7 det / 0.3 MiniLM-L6 CE | 0.921 | 0.909 | 0.896 | 0.945 | 0.673 | 297 / 532 | - / - | neural | yes | [+0.004, +0.032] |
| Hybrid: rank blend 0.5 det / 0.5 MiniLM-L6 CE | 0.946 | 0.913 | 0.917 | 0.953 | 0.666 | 297 / 532 | - / - | neural | yes | [+0.003, +0.041] |
| Hybrid: MiniLM-L6 CE re-orders det top 10 | 0.944 | 0.904 | 0.906 | 0.969 | 0.641 | 76 / 134 | - / - | neural | yes | [-0.001, +0.028] |
| Hybrid: MiniLM-L6 CE inside det bands of 0.05 | 0.930 | 0.901 | 0.896 | 0.959 | 0.686 | 297 / 532 | - / - | neural | yes | [-0.025, +0.036] |
| Hybrid: MiniLM-L6 CE only breaks det ties | 0.905 | 0.890 | 0.865 | 0.948 | 0.655 | 297 / 532 | - / - | neural | yes | [-0.006, +0.000] |
| Hybrid: MiniLM-L6 CE on candidates with det >= 0.5 | 0.908 | 0.890 | 0.865 | 0.969 | 0.634 | 1 / 213 | - / - | neural | yes | [-0.005, +0.000] |
| Hybrid: rank blend 0.5 det / 0.5 bge-reranker-base | 0.934 | 0.913 | 0.885 | 0.973 | 0.660 | 1889 / 3286 | - / - | neural | yes | [+0.000, +0.044] |
| Hybrid: rank blend 0.5 det / 0.5 MiniLM-L6 CE fine-tuned synth+real | 0.945 | 0.917 | 0.906 | 0.975 | 0.701 | 135 / 339 | - / - | neural | yes | [+0.007, +0.045] |
| Hybrid: rank blend 0.5 det / 0.5 MiniLM-L6 CE fine-tuned synth only | 0.946 | 0.914 | 0.906 | 0.964 | 0.693 | 135 / 371 | - / - | neural | yes | [+0.005, +0.042] |
| Hybrid: main det @ bf1d59b, rank blend 0.5 with MiniLM-L6 CE fine-tuned synth+real | 0.947 | 0.917 | 0.906 | 0.975 | 0.703 | 141 / 346 | - / - | neural | yes | [+0.007, +0.046] |

| method | passive | discrete | ic | crystal_connector | vague |
|---|---|---|---|---|---|
| Deterministic ranker (production Java) | 0.988 | 0.902 | 0.930 | 0.910 | 0.620 |
| Deterministic ranker, main @ bf1d59b (connector-aware) | 0.988 | 0.902 | 0.930 | 0.955 | 0.620 |
| Distributor order | 0.948 | 0.964 | 0.822 | 0.812 | 0.265 |
| BM25 over the candidate set | 0.875 | 0.981 | 0.700 | 0.892 | 0.145 |
| Learned weights, ridge (LOQO) | 0.957 | 0.883 | 0.930 | 0.919 | 0.545 |
| Learned weights, pairwise logistic (LOQO) | 0.988 | 0.883 | 0.930 | 0.910 | 0.543 |
| Laya multilingual noul, JSON state (current) | 0.464 | 0.716 | 0.649 | 0.525 | 0.434 |
| Laya multilingual score scale | 0.492 | 0.788 | 0.593 | 0.720 | 0.446 |
| Laya multilingual choice (4 contrasting options) | 0.325 | 0.544 | 0.703 | 0.517 | 0.463 |
| Laya multilingual 4 per-attribute nouls, mean | 0.446 | 0.749 | 0.590 | 0.535 | 0.394 |
| Laya multilingual noul, plain-text state | 0.389 | 0.619 | 0.657 | 0.425 | 0.404 |
| Laya multilingual noul, parsed query attributes in state | 0.480 | 0.711 | 0.619 | 0.513 | 0.453 |
| Laya multilingual noul, max_len 192 / head_max_len 64 | 0.473 | 0.717 | 0.603 | 0.549 | 0.463 |
| Laya english noul | 0.548 | 0.665 | 0.642 | 0.482 | 0.527 |
| Laya english choice | 0.700 | 0.690 | 0.670 | 0.686 | 0.426 |
| Laya typed-decisions noul | 0.680 | 0.778 | 0.632 | 0.667 | 0.569 |
| Laya typed-decisions choice | 0.649 | 0.763 | 0.664 | 0.749 | 0.321 |
| Laya pairwise, 1 round (n/2 pairs), Bradley-Terry | 0.672 | 0.676 | 0.532 | 0.643 | 0.511 |
| Laya pairwise, 2 rounds (n pairs) | 0.614 | 0.661 | 0.409 | 0.626 | 0.365 |
| Laya pairwise, 4 rounds (2n pairs) | 0.622 | 0.709 | 0.454 | 0.656 | 0.336 |
| Laya pairwise, 8 rounds (4n pairs) | 0.659 | 0.774 | 0.533 | 0.602 | 0.327 |
| Laya pairwise round robin of det top 10 (45 pairs) | 0.963 | 0.890 | 0.915 | 0.915 | 0.526 |
| Laya multilingual embeddings, cosine (SDK) | 0.596 | 0.563 | 0.392 | 0.746 | 0.406 |
| Laya multilingual embeddings vs JSON state (SDK) | 0.451 | 0.520 | 0.480 | 0.661 | 0.265 |
| Laya predict_shortlist k=10 + choice (SDK) | 0.566 | 0.897 | 0.534 | 0.661 | 0.331 |
| Laya multilingual, typed head fine-tuned (2-fold) | 0.641 | 0.739 | 0.714 | 0.691 | 0.449 |
| Laya multilingual, typed head fine-tuned on synthetic labels | 0.769 | 0.568 | 0.531 | 0.713 | 0.340 |
| Laya multilingual, full fine-tune 3 epochs (2-fold) | 0.886 | 0.972 | 0.849 | 0.960 | 0.402 |
| Hybrid: rank blend 0.7 det / 0.3 Laya full fine-tune | 0.992 | 0.946 | 0.939 | 0.953 | 0.608 |
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
| Hybrid: current blend, det + 0.2 x Laya ml noul | 0.983 | 0.892 | 0.842 | 0.666 | 0.521 |
| Hybrid: Laya ml noul only breaks det ties | 0.988 | 0.902 | 0.930 | 0.910 | 0.599 |
| Hybrid: rank blend 0.7 det / 0.3 Laya td noul | 0.992 | 0.944 | 0.930 | 0.884 | 0.609 |
| Hybrid: det + 0.2 x MiniLM-L6 CE (production formula) | 0.983 | 0.944 | 0.945 | 0.960 | 0.520 |
| Hybrid: rank blend 0.7 det / 0.3 MiniLM-L6 CE | 0.988 | 0.934 | 0.946 | 0.966 | 0.620 |
| Hybrid: rank blend 0.5 det / 0.5 MiniLM-L6 CE | 0.992 | 0.956 | 0.954 | 0.966 | 0.603 |
| Hybrid: MiniLM-L6 CE re-orders det top 10 | 0.988 | 0.932 | 0.940 | 0.957 | 0.608 |
| Hybrid: MiniLM-L6 CE inside det bands of 0.05 | 0.983 | 0.943 | 0.943 | 0.960 | 0.580 |
| Hybrid: MiniLM-L6 CE only breaks det ties | 0.988 | 0.902 | 0.930 | 0.910 | 0.608 |
| Hybrid: MiniLM-L6 CE on candidates with det >= 0.5 | 0.983 | 0.902 | 0.930 | 0.910 | 0.620 |
| Hybrid: rank blend 0.5 det / 0.5 bge-reranker-base | 1.000 | 0.955 | 0.947 | 0.962 | 0.602 |
| Hybrid: rank blend 0.5 det / 0.5 MiniLM-L6 CE fine-tuned synth+real | 0.996 | 0.960 | 0.954 | 0.967 | 0.611 |
| Hybrid: rank blend 0.5 det / 0.5 MiniLM-L6 CE fine-tuned synth only | 0.996 | 0.957 | 0.949 | 0.967 | 0.605 |
| Hybrid: main det @ bf1d59b, rank blend 0.5 with MiniLM-L6 CE fine-tuned synth+real | 0.996 | 0.960 | 0.954 | 0.969 | 0.611 |
