# Ranking research scripts

Everything behind `docs/research/ranking-evaluation-2026-10-05.md`. Nothing here is part of the application build and
nothing touches `src/main`. No script contains credentials: the stack runs in dev mode without auth, the Laya server
without an API key, and Hugging Face models are public.

## Layout

| file | role |
|---|---|
| `fetch_stack.py` | fetches candidate lists from the running KINA stack and the distributors' own order from its Postgres cache (each new query = 1 Mouser call) |
| `queries.py`, `rubric_lib.py` | the 32 evaluation queries, their written rubrics and labelling functions, hand `OVERRIDES` |
| `build_dataset.py` | builds `docs/research/data/ranking-eval.jsonl` (`--review FILE` writes a human-readable label dump) |
| `usb_queries.py`, `build_usb_dataset.py` | the 9 USB connector queries (`u01`-`u09`): rubrics, labelling functions, and the builder that appends them to `ranking-eval.jsonl` after `build_dataset.py` (stack responses in `raw/stack/usb/`, plain-SQL JLCPCB mining cached in `raw/lcsc-usb.jsonl`) |
| `java/ro/alacrity/kina/search/ResearchRunner.java` | bridge to the production Java code: `lcsc` (KINA's LCSC retrieval) and `score` (production `DeterministicRanker` score + feature vector, checked to reproduce the score exactly) |
| `common.py` | dataset loading, score-file format, the production Laya state, plain candidate text |
| `evaluate.py` | the harness: NDCG@5/@10, P@3, MRR, Spearman, latency, per-category NDCG@10, paired bootstrap CI vs `det`; `--native-only` |
| `select_cv.py` | leave-one-query-out selection of a configuration inside a method family (selection-corrected numbers) |
| `score_baselines.py` | `det` (Java), `distributor_order`, `bm25` |
| `score_learned.py` | learned linear weights on the deterministic features (LOQO CV) |
| `score_laya_http.py` | Laya zero-shot variants over HTTP (question shapes, state formats, token windows, checkpoints) |
| `score_laya_pairwise.py` | Laya pairwise A/B/equal comparisons + Bradley-Terry |
| `score_laya_sdk.py` | Laya SDK only: encoder embeddings (`embed_fn_from_agent`) and `predict_shortlist` |
| `score_neural.py` | sentence-transformers bi-encoders and cross-encoders |
| `score_hybrids.py` | blends, tie-breakers, top-10 and threshold re-rankers of `det` with any model signal |
| `laya_finetune.py` | tiny CPU fine-tune of a Laya checkpoint (2-fold by query), repo's RLCD recipe |
| `synth_data.py` | synthetic training data labelled by the rubric functions (passives only) |
| `ce_finetune.py` | CPU fine-tune of a MiniLM cross-encoder on real (2-fold) and/or synthetic labels |
| `latency.py` | latency at 8 and 4 threads on fixed cores for the methods in the report |
| `score_onnx.py` | ONNX Runtime export / int8 quantisation / latency of the MiniLM cross-encoder (image `kina-laya-research:onnx`, `docker/Dockerfile.onnx`) |
| `score_det_ref.py` | scores the `DeterministicRanker` of another revision from a `git archive` tarball (used for `main` @ `bf1d59b`) |
| `failures.py` | per-query NDCG@10 table and top-k dumps for the failure analysis |
| `report_tables.py`, `fill_report.py` | build the report's main and per-category tables and insert them into the report |
| `rc.sh` | runs a script inside the research container (`docker/Dockerfile`) with pinned cores and the right caches |
| `out/scores/*.json` | one score file per method (committed: small, they are the evidence behind every table) |
| `out/results_full.json`, `out/results_native.json` | metric dump per method and per query |

## Rerun from scratch

Host needs JDK 21+, Python 3.11+ with numpy, Docker. All paths are relative to the repository root.

```bash
# 0. compile the production classes and the research bridge (no tests, offline)
./mvnw -q -o -DskipTests compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
javac --release 21 -d target/research-classes -cp "target/classes:$(cat target/cp.txt)" \
  scripts/research/java/ro/alacrity/kina/search/ResearchRunner.java

# 1. (optional, costs Mouser calls) refresh the stack candidates; the committed raw files make this unnecessary
python3 scripts/research/fetch_stack.py

# 2. dataset (uses docs/research/data/raw/lcsc.jsonl; --refresh-lcsc needs JLC_DB=/path/to/parts-fts5.db)
python3 scripts/research/build_dataset.py --review /tmp/review.txt
python3 scripts/research/build_usb_dataset.py --review /tmp/review-usb.txt   # appends u01-u09

# 3. research image and caches
docker build -t kina-laya-research:local scripts/research/docker
docker volume create kina-research-hf; docker volume create kina-research-ft
docker run --rm -u root -v kina-research-hf:/hf -v kina-research-ft:/ft kina-laya-research:local chown 10001:10001 /hf /ft
chmod -R a+rwX scripts/research/out

# 4. a Laya server for the HTTP experiments (8 P-cores of the reference host)
docker run -d --name kina-laya-eval --cpuset-cpus=0-7 -v kina_laya-models:/home/laya/.cache/huggingface \
  -e LAYA_PRELOAD=0 -e LAYA_MAX_LOADED=3 -e LAYA_THREADS=8 -e OMP_NUM_THREADS=8 -e LAYA_DEVICE=cpu \
  -e LAYA_MAX_CONCURRENT=2 -p 127.0.0.1:8001:8000 kina-laya:v0.3.27 laya-serve

# 5. scorers
cd scripts/research
python3 score_baselines.py
python3 score_learned.py
python3 score_laya_http.py                      # all zero-shot variants, ~1 h on 8 cores
python3 score_laya_pairwise.py multilingual 8
./rc.sh -l -c 8-15 -t 8 python score_laya_sdk.py multilingual
for m in minilm_bi bge_small_bi msmarco_minilm_ce msmarco_minilm12_ce bge_reranker_base_ce; do
  ./rc.sh -c 8-15 -t 8 python score_neural.py $m _ecore; done
python3 synth_data.py 80
./rc.sh -c 8-23 -t 16 python ce_finetune.py real
./rc.sh -c 8-23 -t 16 python ce_finetune.py synth
./rc.sh -c 8-23 -t 16 python ce_finetune.py synth_real
java --enable-native-access=ALL-UNNAMED -cp "../../target/research-classes:../../target/classes:$(cat ../../target/cp.txt)" \
  ro.alacrity.kina.search.ResearchRunner score out/synth-train.jsonl out/synth_features.jsonl
./rc.sh -l -c 8-23 -t 16 python laya_finetune.py head 8 multilingual
./rc.sh -l -c 8-23 -t 16 python laya_finetune.py head_synth 8 multilingual
./rc.sh -l -c 8-23 -t 16 python laya_finetune.py full 1 multilingual 10   # timing of 10 full steps
./rc.sh -l -c 8-23 -t 16 python laya_finetune.py full2 3 multilingual     # full fine-tune, 2-fold
python3 score_hybrids.py
python3 score_hybrids.py laya_td_noul msmarco_minilm_ce_ft_real msmarco_minilm_ce_ft_synth msmarco_minilm_ce_ft_synth_real \
  laya_ml_pair_k8 laya_ft_ml_full2
git archive -o /tmp/main.tar main src/main && python3 score_det_ref.py /tmp/main.tar main_bf1d59b
DET_METHOD=det_main_bf1d59b python3 score_hybrids.py msmarco_minilm_ce_ecore msmarco_minilm_ce_ft_synth_real laya_ml_noul
docker build -t kina-laya-research:onnx -f docker/Dockerfile.onnx docker
python3 latency.py                               # P-core latency at 8 and 4 threads, quiet machine
python3 evaluate.py --md out/results_full.md; python3 evaluate.py --native-only --md out/results_native.md
python3 select_cv.py
python3 report_tables.py > out/report_tables.md && python3 fill_report.py

# 6. clean up
docker rm -f kina-laya-eval
```

Notes:

- The `_ecore` suffix marks accuracy runs done on the host's E-cores while Laya occupied the P-cores; their latency
  column is not comparable. `latency.py` re-measures the methods in the report on P-cores (`0-7` for 8 threads,
  `0-3` for 4 threads) with nothing else running; the report quotes those numbers.
- Scores are deterministic for every method except the fine-tunes (seeded, but CPU kernels are not bit-exact across
  thread counts).
