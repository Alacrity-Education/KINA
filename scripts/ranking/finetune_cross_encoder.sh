#!/usr/bin/env bash
# Fine-tune the KINA cross-encoder on the rubric labels and export it in the layout KINA loads (DESIGN.md 3.5).
#
#   scripts/ranking/finetune_cross_encoder.sh [-m synth|synth_real] [-o OUTPUT_DIR] [-t THREADS] [-c CPUSET] [-e EPOCHS]
#
#   -m  synth (default): synthetic rubric labels only, the evaluation set stays unseen
#       synth_real: synthetic + every labelled pair of docs/research/data/ranking-eval.jsonl (the model to ship)
#   -o  output directory (default ./data/cross-encoder-finetuned, git-ignored); replaced atomically when done
#   -t  torch threads (default: all CPUs of the cpuset, or nproc)
#   -c  docker --cpuset-cpus (default: no pinning)
#   -e  epochs (default 3)
# Env: FINETUNE_IMAGE (default kina-ce-finetune:local, built from scripts/ranking/docker on first use),
#      HF_CACHE_DIR (default ./data/hf-cache) for the base model download.
#
# Use the result: KINA_CROSS_ENCODER_MODEL_URL=<absolute OUTPUT_DIR> (local directory, used in place), or copy the
# directory into the kina-data volume and set KINA_CROSS_ENCODER_MODEL_URL=/data/cross-encoder-finetuned.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
MODE=synth
OUT_DIR="$ROOT/data/cross-encoder-finetuned"
THREADS=""
CPUSET=""
EPOCHS=3
while getopts "m:o:t:c:e:" opt; do
  case $opt in
    m) MODE=$OPTARG ;;
    o) OUT_DIR=$OPTARG ;;
    t) THREADS=$OPTARG ;;
    c) CPUSET=$OPTARG ;;
    e) EPOCHS=$OPTARG ;;
    *) exit 2 ;;
  esac
done
IMAGE="${FINETUNE_IMAGE:-kina-ce-finetune:local}"
HF_CACHE_DIR="${HF_CACHE_DIR:-$ROOT/data/hf-cache}"
mkdir -p "$(dirname "$OUT_DIR")" "$HF_CACHE_DIR"
OUT_PARENT="$(cd "$(dirname "$OUT_DIR")" && pwd)"
OUT_NAME="$(basename "$OUT_DIR")"
if [ -z "$THREADS" ]; then
  THREADS=$(nproc)
fi
if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "building $IMAGE ..."
  docker build -t "$IMAGE" "$HERE/docker"
fi
CPU_ARGS=()
if [ -n "$CPUSET" ]; then CPU_ARGS=(--cpuset-cpus="$CPUSET"); fi
echo "fine-tuning ($MODE, $EPOCHS epochs, $THREADS threads) -> $OUT_PARENT/$OUT_NAME"
docker run --rm "${CPU_ARGS[@]}" -u "$(id -u):$(id -g)" \
  -e THREADS="$THREADS" -e OMP_NUM_THREADS="$THREADS" -e HF_HOME=/hf -e HF_HUB_DISABLE_TELEMETRY=1 -e HOME=/tmp \
  -v "$ROOT/scripts":/repo/scripts:ro -v "$ROOT/docs":/repo/docs:ro \
  -v "$HF_CACHE_DIR":/hf -v "$OUT_PARENT":/out -w /repo/scripts/ranking \
  "$IMAGE" python finetune_cross_encoder.py --out "/out/$OUT_NAME" --mode "$MODE" --epochs "$EPOCHS" --threads "$THREADS"
echo "done: $OUT_PARENT/$OUT_NAME"
echo "evaluate: KINA_CROSS_ENCODER_TEST_MODEL_DIR=$OUT_PARENT/$OUT_NAME ./mvnw test -Dtest=CrossEncoderEvaluationTest"
