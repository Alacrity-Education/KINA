# Cross-encoder model tooling

KINA ranks search results with the deterministic ranker blended 50/50 (by rank) with the MiniLM cross-encoder
`cross-encoder/ms-marco-MiniLM-L6-v2`, run in the JVM with ONNX Runtime (`docs/DESIGN.md` sections 3.3 and 3.5). By
default KINA downloads the zero-shot model from Hugging Face. The scripts here build an optional fine-tuned model in the
same directory layout and regenerate the tokenizer test fixtures.

| file | role |
|---|---|
| `finetune_cross_encoder.sh` | fine-tunes the model on rubric labels in a container and writes a model directory KINA can load |
| `finetune_cross_encoder.py` | the training / ONNX export / int8 quantisation / manifest code the shell script runs |
| `docker/Dockerfile` | CPU image (`python:3.11-slim`, torch CPU, transformers 4.x, sentence-transformers, onnx, onnxruntime) |
| `make_tokenizer_fixtures.py` | regenerates `src/test/resources/ce/tokenizer-fixtures.jsonl` from the Hugging Face tokenizer |

## Fine-tune

```bash
scripts/ranking/finetune_cross_encoder.sh                    # synthetic labels only -> ./data/cross-encoder-finetuned
scripts/ranking/finetune_cross_encoder.sh -m synth_real -o ./data/ce-synth-real   # + every labelled pair
scripts/ranking/finetune_cross_encoder.sh -c 8-23 -t 16      # pin to cores 8-23, 16 threads
```

The first run builds `kina-ce-finetune:local` (override with `FINETUNE_IMAGE`) and downloads the base model into
`HF_CACHE_DIR` (default `./data/hf-cache`). Training data and recipe are those of the ranking study
(`scripts/research/ce_finetune.py`, `synth_data.py`): pairs `(query, candidate_text)` with soft target `label / 3`,
BCE-with-logits, AdamW lr 2e-5, batch 16, 3 epochs, 10% warm-up, `max_length` 256.

- `synth` (default): the 2872 synthetic, rubric-labelled pairs of `scripts/research/out/synth-train.jsonl` (80 random
  passive requests, value/package combinations of the evaluation set excluded). The labelled evaluation set stays
  unseen, so `CrossEncoderEvaluationTest` gives an honest estimate.
- `synth_real`: synthetic pairs plus all 1259 labelled pairs of `docs/research/data/ranking-eval.jsonl`. This is the
  model to ship once the labelled set grows. Its numbers on `ranking-eval.jsonl` are optimistic (training data); the
  study's 2-fold estimate for this recipe is 0.917 blended.

Output directory (written to `<out>.tmp`, then renamed):

```
vocab.txt config.json tokenizer_config.json special_tokens_map.json tokenizer.json
onnx/model.onnx                     fp32 (torch.onnx export, opset 17, dynamic batch/sequence)
onnx/model_qint8_avx512_vnni.onnx   int8, quantize_dynamic QInt8
onnx/model_quint8_avx2.onnx         int8, quantize_dynamic QUInt8
model.json                          revision finetuned-<mode>-<UTC time>, data sizes, timings, ONNX-vs-torch checks
```

Measured on 2026-10-05 (Core Ultra 9 285K, 16 E-cores, `synth`): training 254 s, export and quantisation 3 s; fp32
ONNX matches torch to 7e-6, the int8 files rank the 64 check pairs with Spearman 0.996 against torch.

| model (`CrossEncoderEvaluationTest`, 32 queries) | cross-encoder alone NDCG@10 | blend NDCG@10 | dNDCG@10 vs det (95% CI, `evaluate.py`) |
|---|---|---|---|
| deterministic ranker | - | 0.898 | |
| zero-shot, int8 (default download) | 0.878 | 0.913 | +0.001 to +0.031 |
| zero-shot, fp32 | 0.874 | 0.913 | |
| fine-tuned `synth`, int8 | 0.890 | 0.918 | +0.004 to +0.037 |
| fine-tuned `synth`, fp32 | 0.893 | 0.914 | +0.002 to +0.032 |

## Use a fine-tuned model

KINA loads any directory with this layout. Point `KINA_CROSS_ENCODER_MODEL_URL` at it:

```bash
# local run: the directory is used in place, nothing is downloaded
KINA_CROSS_ENCODER_MODEL_URL=$PWD/data/cross-encoder-finetuned java -jar target/kina.jar

# Docker: copy it into the kina-data volume, then set KINA_CROSS_ENCODER_MODEL_URL=/data/cross-encoder-finetuned in .env
docker run --rm -v kina_kina-data:/data -v "$PWD/data/cross-encoder-finetuned":/src:ro alpine \
  sh -c 'cp -r /src /data/cross-encoder-finetuned && chown -R 10001:10001 /data/cross-encoder-finetuned'
docker compose up -d kina
```

An HTTP(S) directory with the same layout works too: KINA downloads it into `KINA_CROSS_ENCODER_MODEL_DIR`, replacing
files that `model.json` says came from another source. `list_distributors` shows the loaded `model_revision`.

Evaluate a model directory before shipping it:

```bash
KINA_CROSS_ENCODER_TEST_MODEL_DIR=$PWD/data/cross-encoder-finetuned ./mvnw test -Dtest=CrossEncoderEvaluationTest
# add KINA_CROSS_ENCODER_TEST_VARIANT=fp32 for the fp32 file and
# KINA_CROSS_ENCODER_TEST_SCORES_DIR=<dir> to write score files for scripts/research/evaluate.py
```

The test asserts blended NDCG@10 >= the deterministic ranker's and >= 0.90.

## Tokenizer fixtures

The committed fixtures were generated with transformers 5.18 (the version the ranking study scored with, image of
`scripts/research/docker`). Use a transformers 5.x environment to regenerate them:

```bash
docker run --rm -v "$PWD":/repo:ro -v <dir with vocab.txt and tokenizer.json>:/model:ro -w /repo <image with transformers 5.x> \
  python scripts/ranking/make_tokenizer_fixtures.py /model > src/test/resources/ce/tokenizer-fixtures.jsonl
```

transformers 4.57 (the fine-tuning image) differs in exactly one corner: when both the query and the document are
longer than half of an odd token budget, 5.x gives the extra token to the document, 4.57 to the query (3 of the 143
fixtures, budgets 16, 32 and 64). Real queries are far shorter than half of 256 tokens, so training and inference agree.
