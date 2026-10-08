#!/usr/bin/env bash
# Usage: run.sh MODE [Xmx]   MODE: coverage | throughput | determinism
# Needs target/classes (./mvnw -q -DskipTests package), WORK/sample.db (see sample.sql) and WORK/cache.tsv.
set -euo pipefail
cd "$(dirname "$0")/../../.."
WORK=${WORK:-/var/tmp/kina-bench/extract}
MODE=${1:-coverage}
XMX=${2:-4g}
mkdir -p "$WORK/classes"
[ -f "$WORK/cp.txt" ] || ./mvnw -q dependency:build-classpath -Dmdep.outputFile="$WORK/cp.txt"
CP="target/classes:$(cat "$WORK/cp.txt")"
javac --release 21 -cp "$CP" -d "$WORK/classes" scripts/research/extraction_coverage/ro/alacrity/kina/search/ExtractionCoverage.java
java -Xmx"$XMX" ${JAVA_OPTS:-} -cp "$WORK/classes:$CP" ro.alacrity.kina.search.ExtractionCoverage "$MODE" "$WORK/sample.db" "$WORK/cache.tsv" "$WORK/out"
