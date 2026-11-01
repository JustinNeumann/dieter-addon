#!/bin/bash
# End-to-end tests of the Dieter add-on: builds the amd64 image, starts it on the host network with
# Dieter's deterministic mock harness, puts an Ingress-like proxy in front and runs Playwright.
# Usage: tests/run.sh [playwright args]      E2E_DATA=<dir> keeps the staged runtime between runs,
#        The image takes the page and the web host from dieter/web-dist (tools/addon-dist.sh fills it; run.sh
#        does that first when it is empty). WEB_BUNDLE=<dir> and WEB_HOST_JAR=<file> together build without them
#        and mount an unpacked bundle (tools/package.sh) over /opt/web and the jar instead.
set -euo pipefail
cd "$(dirname "$0")"
DATA="${E2E_DATA:-$HOME/.cache/dieter-addon-e2e/data}"
IMAGE=dieter-addon-e2e
mkdir -p "$DATA"
[ -f "$DATA/options.json" ] || echo '{"git_user":"E2E","git_email":"e2e@example.invalid","github_token":"","claude_code_oauth_token":"","openai_api_key":"","antigravity_token":"","api_key":"e2e-k3y","projects":[],"log_level":"info"}' > "$DATA/options.json"
MOUNTED=
if [ -n "${WEB_BUNDLE:-}" ] && [ -n "${WEB_HOST_JAR:-}" ]; then MOUNTED=1
elif [ -n "${WEB_BUNDLE:-}${WEB_HOST_JAR:-}" ]; then echo "WEB_BUNDLE and WEB_HOST_JAR go together" >&2; exit 1
elif [ ! -f ../web-dist/dieter-web.tar.gz ] || [ ! -f ../web-dist/dieter-web-host.jar ] || [ ! -f ../web-dist/dieter-gateway-amd64 ]; then
  echo "building the page, web host and embedded gateway (tools/addon-dist.sh)…"; ../../tools/addon-dist.sh >/dev/null
fi
echo "building image…"
docker build -q --build-arg BUILD_ARCH=amd64 ${MOUNTED:+--build-arg DIETER_WEB=mounted} -t "$IMAGE" .. >/dev/null
docker rm -f dieter-e2e >/dev/null 2>&1 || true
pkill -f '^node ingress-proxy.mjs' >/dev/null 2>&1 || true
docker run -d --name dieter-e2e --network host -e BRIDGE_PORT=18099 -e BRIDGE_INGRESS_NETS=127.0.0.2/32 -e BRIDGE_CORE_NETS=127.0.0.3/32 -e SUPERVISOR_TOKEN=e2e-supervisor-token-2718 -e DIETER_DAEMON_ADDR=127.0.0.1:4342 -e DIETER_GATEWAY_ADDR=127.0.0.1:4343 -e DIETER_ENABLE_MOCK_HARNESS=1 -v "$DATA:/data" ${MOUNTED:+-v "$WEB_BUNDLE:/opt/web:ro" -v "$WEB_HOST_JAR:/opt/web-host/dieter-web-host.jar:ro"} "$IMAGE" >/dev/null
cleanup() { kill "${PROXY:-}" 2>/dev/null || true; [ -n "${E2E_KEEP:-}" ] || docker rm -f dieter-e2e >/dev/null 2>&1 || true; }   # E2E_KEEP=1 leaves the container for `docker logs dieter-e2e`
trap cleanup EXIT
echo "waiting for the add-on and daemon (first run stages the harness runtime, ~800 MB)…"
for i in $(seq 1 180); do
  if curl -sf --interface 127.0.0.3 http://127.0.0.1:18099/api/state 2>/dev/null | grep -qE '"id": ?"mock"'; then break; fi   # as Home Assistant
  sleep 5
  [ "$i" = 180 ] && { echo "add-on did not become ready"; docker logs dieter-e2e | tail -20; exit 1; }
done
node ingress-proxy.mjs & PROXY=$!
sleep 1
npx playwright test "$@"
