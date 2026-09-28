#!/usr/bin/env bash
# run-test-client.sh - Launches a Fabric Test Client for BlockRacing automated tests & profiling
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BOT_NAME="${1:-TestBot_Red}"
TEAM="${2:-red}"
LATENCY_MS="${3:-0}"
JITTER_MS="${4:-0}"

export JAVA_HOME="/usr/lib/jvm/java-27-openjdk"

echo "=========================================================="
echo " Starting BlockRacing Fabric Test Client"
echo " Bot Username : ${BOT_NAME}"
echo " Target Team  : ${TEAM}"
echo " Extra Latency: ${LATENCY_MS} ms (Jitter: ${JITTER_MS} ms)"
echo "=========================================================="

cd "${SCRIPT_DIR}"
./gradlew runClient \
  --args="--username ${BOT_NAME} --server 127.0.0.1 --port 25565" \
  -Dblockracing.test.team="${TEAM}" \
  -Dblockracing.test.latency="${LATENCY_MS}" \
  -Dblockracing.test.jitter="${JITTER_MS}"
