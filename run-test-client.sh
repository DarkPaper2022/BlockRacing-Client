#!/usr/bin/env bash
# Launch one isolated Fabric E2E client. run-e2e.sh normally starts two of these.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BOT_NAME="${1:-TestBot_Red}"
TEAM="${2:-red}"
RTT_MS="${3:-60}"
JITTER_MS="${4:-5}"
BW_BYTES_PER_SEC="${5:-3145728}"
ITERATIONS="${6:-1}"
INTERVAL_SECS="${7:-2}"
HEADLESS="${8:-true}"
PORT="${9:-25575}"
COORDINATOR="${10:-false}"
TARGETS="${11:-CHERRY_PLANKS,STONE,DIRT,COBBLESTONE}"
SCENARIO="${BLOCKRACING_TEST_SCENARIO:-dual-client}"
ROLE="${BLOCKRACING_TEST_ROLE:-}"
SYNC_DIR="${BLOCKRACING_TEST_SYNC_DIR:-}"
case "${TEAM}" in
  red) DISPLAY_NUMBER=111 ;;
  blue) DISPLAY_NUMBER=112 ;;
  green) DISPLAY_NUMBER=113 ;;
  yellow) DISPLAY_NUMBER=114 ;;
  *) DISPLAY_NUMBER=115 ;;
esac
DISPLAY_NUMBER="${BLOCKRACING_TEST_DISPLAY:-${DISPLAY_NUMBER}}"

export JAVA_HOME="${BLOCKRACING_CLIENT_JAVA_HOME:-/home/darkpaper/.local/share/hmcl/java/linux-x86_64/mojang-java-runtime-epsilon}"

echo "BlockRacing E2E client: name=${BOT_NAME} team=${TEAM} port=${PORT} coordinator=${COORDINATOR}"
echo "Network profile: RTT=${RTT_MS}ms jitter=${JITTER_MS}ms bandwidth=${BW_BYTES_PER_SEC}B/s"

cd "${SCRIPT_DIR}"
mkdir -p "run/${BOT_NAME}"
cp "e2e-options.txt" "run/${BOT_NAME}/options.txt"
LAUNCH_CMD=(
  ./gradlew runClient -x classes
  --args="--username ${BOT_NAME} --quickPlayMultiplayer 127.0.0.1:${PORT}"
  -Dblockracing.test.instance="${BOT_NAME}"
  -Dblockracing.test.scenario="${SCENARIO}"
  -Dblockracing.test.role="${ROLE}"
  -Dblockracing.test.syncDir="${SYNC_DIR}"
  -Dblockracing.test.autoconnect=true
  -Dblockracing.test.host=127.0.0.1
  -Dblockracing.test.port="${PORT}"
  -Dblockracing.test.team="${TEAM}"
  -Dblockracing.test.coordinator="${COORDINATOR}"
  -Dblockracing.test.targets="${TARGETS}"
  -Dblockracing.test.rtt="${RTT_MS}"
  -Dblockracing.test.jitter="${JITTER_MS}"
  -Dblockracing.test.bandwidth="${BW_BYTES_PER_SEC}"
  -Dblockracing.test.iterations="${ITERATIONS}"
  -Dblockracing.test.interval="${INTERVAL_SECS}"
)

if [[ "${HEADLESS}" == "true" ]]; then
  env -u WAYLAND_DISPLAY -u XDG_SESSION_TYPE -u DISPLAY -u XAUTHORITY \
    xvfb-run -n "${DISPLAY_NUMBER}" -s "-screen 0 1280x720x24" "${LAUNCH_CMD[@]}"
else
  "${LAUNCH_CMD[@]}"
fi
