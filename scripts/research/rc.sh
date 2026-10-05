#!/usr/bin/env bash
# Run a research script inside kina-laya-research:local (see docker/Dockerfile).
#   scripts/research/rc.sh [-c CPUSET] [-t THREADS] [-l] python script.py args...
#   -c  docker --cpuset-cpus (default 0-7, the 8 P-cores on the reference host)
#   -t  torch/OMP threads (default 8)
#   -l  mount the Laya model cache (kina_laya-models) as HF_HOME instead of the research cache (kina-research-hf)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
CPUS=0-7
THREADS=8
HF_VOL=kina-research-hf
while getopts "c:t:l" opt; do
  case $opt in
    c) CPUS=$OPTARG ;;
    t) THREADS=$OPTARG ;;
    l) HF_VOL=kina_laya-models ;;
    *) exit 2 ;;
  esac
done
shift $((OPTIND - 1))
HF_MOUNT=/hf
if [ "$HF_VOL" = kina_laya-models ]; then HF_MOUNT=/home/laya/.cache/huggingface; fi
exec docker run --rm --cpuset-cpus="$CPUS" \
  -e THREADS="$THREADS" -e OMP_NUM_THREADS="$THREADS" -e LAYA_THREADS="$THREADS" -e LAYA_DEVICE=cpu \
  -e HF_HOME="$HF_MOUNT" -e HF_HUB_DISABLE_TELEMETRY=1 \
  -e RANKING_DATASET=/repo/docs/research/data/ranking-eval.jsonl -e RANKING_OUT=/work/out \
  -v "$HF_VOL":"$HF_MOUNT" -v kina-research-ft:/ft -v "$HERE":/work -v "$ROOT/docs":/repo/docs:ro -w /work \
  kina-laya-research:local "$@"
