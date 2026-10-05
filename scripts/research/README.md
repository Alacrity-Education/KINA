# Ranking research scripts

Everything behind `docs/research/ranking-evaluation-2026-10-05.md`: the labelled dataset builders, the scorers, the
evaluation harness and the report tables. The study compares the deterministic ranker with distributor order, BM25,
learned weights, bi-encoders, cross-encoders (zero-shot, ONNX Runtime fp32/int8, fine-tuned) and hybrids of the
deterministic ranker with each model signal, and measures latency and memory. Nothing here is part of the application
build and nothing touches `src/main`. No script contains credentials: the stack runs in dev mode without auth and the
Hugging Face models are public.

## Layout

| file | role |
|---|---|
| `fetch_stack.py` | fetches candidate lists from the running KINA stack and the distributors' own order from its Postgres cache (each new query = 1 Mouser call) |
| `queries.py`, `rubric_lib.py` | the 32 original evaluation queries, their written rubrics and labelling functions, hand `OVERRIDES` |
| `build_dataset.py` | builds `docs/research/data/ranking-eval.jsonl` (`--review FILE` writes a human-readable label dump) |
| `usb_queries.py`, `build_usb_dataset.py` | the 9 USB connector queries (`u01`-`u09`): rubrics, labelling functions, and the builder that appends them to `ranking-eval.jsonl` after `build_dataset.py` (stack responses in `raw/stack/usb/`, plain-SQL JLCPCB mining cached in `raw/lcsc-usb.jsonl`) |
| `java/ro/alacrity/kina/search/ResearchRunner.java` | bridge to the production Java code: `lcsc` (KINA's LCSC retrieval) and `score` (production `DeterministicRanker` score + feature vector, checked to reproduce the score exactly) |
| `common.py` | dataset loading, score-file format, the plain candidate text, the 2-fold split by query |
| `evaluate.py` | the harness: NDCG@5/@10, P@3, MRR, Spearman, latency, per-category NDCG@10, paired bootstrap CI vs `det`; `--native-only` |
| `select_cv.py` | leave-one-query-out selection of a configuration inside a hybrid family (selection-corrected numbers) |
| `score_baselines.py` | `det` (Java), `distributor_order`, `bm25`; writes `out/det_features.jsonl` |
| `score_det_ref.py` | scores the `DeterministicRanker` of another revision from a `git archive` tarball (used for `main` @ `bf1d59b`) |
| `score_learned.py` | learned linear weights on the deterministic features (LOQO CV) |
| `score_neural.py` | sentence-transformers bi-encoders and cross-encoders |
| `score_onnx.py` | ONNX export (torch.onnx), int8 quantisation and onnxruntime scoring of the MiniLM cross-encoder |
| `synth_data.py` | synthetic training data labelled by the rubric functions (passives only) |
| `ce_finetune.py` | CPU fine-tune of a MiniLM cross-encoder on real (2-fold) and/or synthetic labels |
| `score_hybrids.py` | blends, tie-breakers, band, top-10 and threshold re-rankers of `det` with any model signal |
| `latency.py` | re-measures the neural and ONNX scorers at 8 and 4 threads on fixed cores |
| `failures.py` | per-query NDCG@10 table and top-k dumps for the failure analysis |
| `report_tables.py`, `fill_report.py` | build every table of the report (`out/report_tables.md`) and copy them into the report between its `<!-- BEGIN/END NAME -->` markers |
| `rc.sh` | runs a script inside the research image with pinned cores and the Hugging Face cache mounted |
| `out/scores/*.json` | one score file per method (committed: small, they are the evidence behind every table) |
| `out/results_full.*`, `out/results_native.*`, `out/select_cv.json`, `out/report_tables.md` | generated metric dumps and tables |

The research image is the cross-encoder fine-tuning image of `scripts/ranking/docker` (`python:3.11-slim`, torch CPU,
transformers 4.x, sentence-transformers, onnx, onnxruntime); `rc.sh` builds it as `kina-ce-finetune:local` on first
use (override with `RESEARCH_IMAGE`), runs as the calling user, mounts `./data/hf-cache` (shared with
`scripts/ranking/finetune_cross_encoder.sh`) as `HF_HOME` and `./data/research-ft` as `/ft` (ONNX exports).

## Re-evaluate from the committed score files (no models, no Docker)

Host Python 3.11+ only. This regenerates every generated file in `out/` and the report tables:

```bash
cd scripts/research
python3 score_hybrids.py                         # det x {CE, CE-L12, bge-reranker, bi-encoders, fine-tuned CEs}
DET_METHOD=det_main_bf1d59b python3 score_hybrids.py msmarco_minilm_ce_ecore msmarco_minilm_ce_ft_synth_real
python3 evaluate.py --md out/results_full.md; python3 evaluate.py --native-only --md out/results_native.md
python3 select_cv.py
python3 report_tables.py > out/report_tables.md && python3 fill_report.py
python3 failures.py 5                            # optional: failure analysis dump
```

`score_learned.py` also runs on the host (numpy) from the committed `out/det_features.jsonl`.

## Rerun from scratch

Host needs JDK 21+, Python 3.11+ with numpy, Docker. All paths are relative to the repository root.

The committed score files cover the 32 original queries; the dataset has since grown to 41 (`u01`-`u09`). To
reproduce the study, score the 32-query subset by setting `RANKING_DATASET` (read by `common.py` and mapped into the
container by `rc.sh`); without it every scorer covers all 41 queries.

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

# 3. (optional) the study's 32-query subset
cd scripts/research
mkdir -p out/logs && grep -v '"id": "u0' ../../docs/research/data/ranking-eval.jsonl > out/logs/ranking-eval-32.jsonl
export RANKING_DATASET=$PWD/out/logs/ranking-eval-32.jsonl

# 4. deterministic reference scores and baselines (Java det + feature vectors, distributor order, BM25)
python3 score_baselines.py
git archive -o /tmp/main.tar bf1d59b src/main && python3 score_det_ref.py /tmp/main.tar main_bf1d59b
python3 score_learned.py

# 5. neural scorers (accuracy runs; the _ecore suffix marks runs on the 16 E-cores of the reference host)
for m in minilm_bi bge_small_bi msmarco_minilm_ce msmarco_minilm12_ce bge_reranker_base_ce; do
  ./rc.sh -c 8-15 -t 8 python score_neural.py $m _ecore; done

# 6. fine-tuned cross-encoders (synthetic data needs the JLCPCB database, see synth_data.py)
python3 synth_data.py 80
java --enable-native-access=ALL-UNNAMED -cp "../../target/research-classes:../../target/classes:$(cat ../../target/cp.txt)" \
  ro.alacrity.kina.search.ResearchRunner score out/synth-train.jsonl out/synth_features.jsonl
./rc.sh -c 8-23 -t 16 python ce_finetune.py real
./rc.sh -c 8-23 -t 16 python ce_finetune.py synth
./rc.sh -c 8-23 -t 16 python ce_finetune.py synth_real

# 7. latency and memory on the P-cores, quiet machine: neural at 8/4 threads, ONNX fp32/int8 at 8/4 threads
python3 latency.py                               # = python3 latency.py neural onnx

# 8. hybrids, evaluation, selection-corrected numbers, report tables (as in the previous section)
python3 score_hybrids.py
DET_METHOD=det_main_bf1d59b python3 score_hybrids.py msmarco_minilm_ce_ecore msmarco_minilm_ce_ft_synth_real
python3 evaluate.py --md out/results_full.md; python3 evaluate.py --native-only --md out/results_native.md
python3 select_cv.py
python3 report_tables.py > out/report_tables.md && python3 fill_report.py
```

A single script in the image: `scripts/research/rc.sh -c 0-7 -t 8 python score_neural.py msmarco_minilm_ce _p8`.

Notes:

- The `_ecore` accuracy runs ran on the host's E-cores in parallel with other experiments; their latency is not
  comparable. `latency.py` re-measures the methods in the report on P-cores (`0-7` for 8 threads, `0-3` for 4
  threads) with nothing else running (`*_p8`, `*_p4`, `*_onnx*_t8`, `*_onnx*_t4`); the report quotes those numbers.
  Hybrid latency is the deterministic latency plus the model's (scaled by the share of candidates it scores).
- The committed neural score files were produced with torch 2.14 / transformers 5.18 (recorded in their `meta`) and
  the ONNX files with an optimum export; the research image now pins transformers 4.x and exports with torch.onnx (as
  `scripts/ranking/finetune_cross_encoder.py` does). A rerun can differ in the last digits; see
  `scripts/ranking/README.md` for the one tokenizer truncation corner where 4.x and 5.x differ.
- Scores are deterministic for every method except the fine-tunes (seeded, but CPU kernels are not bit-exact across
  thread counts).
