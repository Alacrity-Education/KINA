#!/usr/bin/env bash
# Run a research script inside the CPU research image: the cross-encoder fine-tuning image of scripts/ranking/docker
# (python:3.11-slim, torch CPU, transformers 4.x, sentence-transformers, onnx, onnxruntime), built on first use.
#   scripts/research/rc.sh [-c CPUSET] [-t THREADS] python script.py args...
#   -c  docker --cpuset-cpus (default 0-7, the 8 P-cores on the reference host)
#   -t  torch/OMP threads (default 8)
# Env: RESEARCH_IMAGE (default kina-ce-finetune:local), HF_CACHE_DIR (default ./data/hf-cache, shared with
#      scripts/ranking/finetune_cross_encoder.sh), RESEARCH_FT_DIR (default ./data/research-ft: ONNX exports),
#      RANKING_DATASET (default docs/research/data/ranking-eval.jsonl; a file under scripts/research is mapped into
#      the container, e.g. the 32-query subset out/logs/ranking-eval-32.jsonl of README.md).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
CPUS=0-7
THREADS=8
while getopts "c:t:" opt; do
  case $opt in
    c) CPUS=$OPTARG ;;
    t) THREADS=$OPTARG ;;
    *) exit 2 ;;
  esac
done
shift $((OPTIND - 1))
IMAGE="${RESEARCH_IMAGE:-kina-ce-finetune:local}"
HF_CACHE_DIR="${HF_CACHE_DIR:-$ROOT/data/hf-cache}"
FT_DIR="${RESEARCH_FT_DIR:-$ROOT/data/research-ft}"
DATASET_IN=/repo/docs/research/data/ranking-eval.jsonl
if [ -n "${RANKING_DATASET:-}" ]; then
  DS="$(cd "$(dirname "$RANKING_DATASET")" && pwd)/$(basename "$RANKING_DATASET")"
  case "$DS" in
    "$HERE"/*) DATASET_IN="/work/${DS#"$HERE"/}" ;;
    "$ROOT/docs"/*) DATASET_IN="/repo/docs/${DS#"$ROOT/docs"/}" ;;
    *) echo "RANKING_DATASET must be under scripts/research or docs" >&2; exit 2 ;;
  esac
fi
mkdir -p "$HF_CACHE_DIR" "$FT_DIR" "$HERE/out/scores"
if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "building $IMAGE ..." >&2
  docker build -t "$IMAGE" "$ROOT/scripts/ranking/docker" >&2
fi
exec docker run --rm --cpuset-cpus="$CPUS" -u "$(id -u):$(id -g)" -e HOME=/tmp \
  -e THREADS="$THREADS" -e OMP_NUM_THREADS="$THREADS" \
  -e HF_HOME=/hf -e HF_HUB_DISABLE_TELEMETRY=1 \
  -e RANKING_DATASET="$DATASET_IN" -e RANKING_OUT=/work/out \
  -v "$HF_CACHE_DIR":/hf -v "$FT_DIR":/ft -v "$HERE":/work -v "$ROOT/docs":/repo/docs:ro -w /work \
  "$IMAGE" "$@"
