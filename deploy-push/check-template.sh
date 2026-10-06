#!/usr/bin/env bash
# Drift guard: deploy-push/compose.template.yaml must match the root compose.yaml except for
#   - full-line comments and blank lines (ignored in both files),
#   - the build:, image: and pull_policy: lines of the kina service.
# Everything else (ports, env_file, environment, volumes, mem_limit, depends_on, restart, the postgres
# service, top-level volumes) must be identical. Exit 0 when they match, 1 with a diff when they drift.
#
# Usage: check-template.sh [<template> [<compose>]]   (defaults: the files of this repository)
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
template=${1:-$script_dir/compose.template.yaml}
compose=${2:-$script_dir/../compose.yaml}

for f in "$template" "$compose"; do
  [[ -r $f ]] || { echo "check-template: cannot read $f" >&2; exit 2; }
done

# Prints the file without comments, blank lines and the kina service's build/image/pull_policy lines.
normalise() {
  awk '
    /^[[:space:]]*#/ || /^[[:space:]]*$/ { next }
    /^[^[:space:]]/ { section = $1; service = "" }
    section == "services:" && /^  [^[:space:]#][^:]*:[[:space:]]*$/ { service = $1 }
    service == "kina:" && /^    (build|image|pull_policy):/ { next }
    { print }
  ' "$1"
}

if ! diff_out=$(diff -u --label "compose.yaml (normalised)" --label "compose.template.yaml (normalised)" \
                  <(normalise "$compose") <(normalise "$template")); then
  {
    echo "check-template: deploy-push/compose.template.yaml has drifted from compose.yaml."
    echo "Apply the same change to the template (only the kina build/image/pull_policy lines may differ):"
    echo "$diff_out"
  } >&2
  exit 1
fi

grep -q '^    image: __KINA_IMAGE__$' "$template" || {
  echo "check-template: $template has no 'image: __KINA_IMAGE__' line in the kina service." >&2
  exit 1
}
if normalise "$template" | grep -q '^    build:'; then
  echo "check-template: $template must not contain build:." >&2
  exit 1
fi
echo "check-template: compose.template.yaml matches compose.yaml."
