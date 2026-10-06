#!/bin/sh
# Fetches the cross-encoder files KINA needs into a directory, verifies them against a sha256sum file and writes
# KINA's model.json manifest, so the directory can be used in place (KINA_CROSS_ENCODER_MODEL_URL=<dir>,
# docs/DESIGN.md 3.5). The Dockerfile stage "model" runs it at image build time; it also works on a developer machine
# (POSIX sh, curl, sha256sum).
#
# Usage: fetch-model.sh <out-dir>
# Environment (the Dockerfile build args of the same names):
#   CROSS_ENCODER_SOURCE       HTTP(S) directory with the Hugging Face layout (default: the pinned Hugging Face revision)
#   CROSS_ENCODER_VARIANTS     comma-separated subset of int8,fp32 (default int8,fp32; int8 alone is about 90 MB smaller)
#   CROSS_ENCODER_SHA256_FILE  sha256sum file to verify against (default: ms-marco-MiniLM-L6-v2.sha256 next to this script)
#   CROSS_ENCODER_SKIP_VERIFY  1 = do not verify (custom sources without a hash file); the manifest still records hashes
#   CROSS_ENCODER_REVISION     revision recorded in model.json (default: taken from a Hugging Face resolve URL)
set -eu

DEFAULT_SOURCE=https://huggingface.co/cross-encoder/ms-marco-MiniLM-L6-v2/resolve/233902d25c440f23af6f7d6e94d2946bac0bee0a/
here=$(cd "$(dirname "$0")" && pwd)
out=${1:?usage: fetch-model.sh <out-dir>}
source=${CROSS_ENCODER_SOURCE:-$DEFAULT_SOURCE}
case $source in */) ;; *) source=$source/ ;; esac
variants=${CROSS_ENCODER_VARIANTS:-int8,fp32}
hashes=${CROSS_ENCODER_SHA256_FILE:-$here/ms-marco-MiniLM-L6-v2.sha256}
skip_verify=${CROSS_ENCODER_SKIP_VERIFY:-0}

files="config.json tokenizer_config.json vocab.txt"
variant_list=""
for v in $(echo "$variants" | tr ',' ' '); do
  case $v in
    int8) files="$files onnx/model_qint8_avx512_vnni.onnx onnx/model_quint8_avx2.onnx" ;;
    fp32) files="$files onnx/model.onnx" ;;
    *) echo "fetch-model: unknown variant '$v' in CROSS_ENCODER_VARIANTS (use int8, fp32 or int8,fp32)" >&2; exit 1 ;;
  esac
  variant_list="$variant_list${variant_list:+, }\"$v\""
done
[ -n "$variant_list" ] || { echo "fetch-model: CROSS_ENCODER_VARIANTS is empty" >&2; exit 1; }

# repository and revision from a Hugging Face resolve URL: https://huggingface.co/<org>/<name>/resolve/<rev>/
repo=""
revision=${CROSS_ENCODER_REVISION:-}
case $source in
  https://huggingface.co/*/resolve/*)
    path=${source#https://huggingface.co/}
    repo=${path%%/resolve/*}
    rev=${path#*/resolve/}
    rev=${rev%/}
    [ -n "$revision" ] || revision=$rev
    ;;
esac

mkdir -p "$out/onnx"
for f in $files; do
  echo "fetch-model: $source$f"
  curl -fsSL --retry 3 --connect-timeout 20 --max-time 900 -o "$out/$f" "$source$f"
done

if [ "$skip_verify" = 1 ]; then
  echo "fetch-model: WARNING: SHA-256 verification skipped (CROSS_ENCODER_SKIP_VERIFY=1)" >&2
else
  [ -r "$hashes" ] || { echo "fetch-model: hash file $hashes not found (set CROSS_ENCODER_SHA256_FILE or CROSS_ENCODER_SKIP_VERIFY=1)" >&2; exit 1; }
  for f in $files; do
    expected=$(grep -v '^#' "$hashes" | awk -v f="$f" '$2 == f { print $1 }')
    [ -n "$expected" ] || { echo "fetch-model: $f has no entry in $hashes" >&2; exit 1; }
    actual=$(sha256sum "$out/$f" | awk '{ print $1 }')
    if [ "$actual" != "$expected" ]; then
      echo "fetch-model: SHA-256 mismatch for $f: got $actual, expected $expected" >&2
      exit 1
    fi
  done
  echo "fetch-model: all files match $(basename "$hashes")"
fi

json_or_null() { if [ -n "$1" ]; then printf '"%s"' "$1"; else printf 'null'; fi; }
first_variant=$(echo "$variants" | cut -d, -f1)
{
  printf '{\n'
  printf '  "source" : "%s",\n' "$source"
  printf '  "repo" : %s,\n' "$(json_or_null "$repo")"
  printf '  "revision" : %s,\n' "$(json_or_null "$revision")"
  printf '  "variant" : "%s",\n' "$first_variant"
  printf '  "variants" : [ %s ],\n' "$variant_list"
  printf '  "provisioned_by" : "docker/model/fetch-model.sh",\n'
  printf '  "downloaded_at" : "%s",\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf '  "files" : {'
  sep=""
  for f in $files; do
    size=$(wc -c < "$out/$f" | tr -d ' ')
    sha=$(sha256sum "$out/$f" | awk '{ print $1 }')
    printf '%s\n    "%s" : { "size" : %s, "sha256" : "%s" }' "$sep" "$f" "$size" "$sha"
    sep=","
  done
  printf '\n  }\n}\n'
} > "$out/model.json"
echo "fetch-model: wrote $out/model.json ($variants, revision ${revision:-unknown})"
