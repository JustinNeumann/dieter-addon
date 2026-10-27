#!/bin/bash
# End-to-end tests of the Dieter add-on: builds the amd64 image, starts it on the host network with
# Dieter's deterministic mock harness, puts an Ingress-like proxy in front and runs Playwright.
# Usage: tests/run.sh [playwright args]      E2E_DATA=<dir> keeps the staged runtime between runs.
set -euo pipefail
cd "$(dirname "$0")"
DATA="${E2E_DATA:-$HOME/.cache/dieter-addon-e2e/data}"
IMAGE=dieter-addon-e2e
mkdir -p "$DATA"
[ -f "$DATA/options.json" ] || echo '{"git_user":"E2E","git_email":"e2e@example.invalid","github_token":"","claude_code_oauth_token":"","openai_api_key":"","antigravity_token":"","projects":[],"log_level":"info"}' > "$DATA/options.json"
echo "building image…"
docker build -q --build-arg BUILD_ARCH=amd64 -t "$IMAGE" .. >/dev/null
docker rm -f dieter-e2e >/dev/null 2>&1 || true
pkill -f '^node ingress-proxy.mjs' >/dev/null 2>&1 || true
docker run -d --name dieter-e2e --network host -e BRIDGE_PORT=18099 -e DIETER_DAEMON_ADDR=127.0.0.1:4342 -e DIETER_ENABLE_MOCK_HARNESS=1 -v "$DATA:/data" "$IMAGE" >/dev/null
cleanup() { kill "${PROXY:-}" 2>/dev/null || true; docker rm -f dieter-e2e >/dev/null 2>&1 || true; }
trap cleanup EXIT
echo "waiting for the bridge and daemon (first run stages the harness runtime, ~800 MB)…"
for i in $(seq 1 180); do
  if curl -sf http://127.0.0.1:18099/api/state 2>/dev/null | grep -q '"id": "mock"'; then break; fi
  sleep 5
  [ "$i" = 180 ] && { echo "add-on did not become ready"; docker logs dieter-e2e | tail -20; exit 1; }
done
node ingress-proxy.mjs & PROXY=$!
sleep 1
npx playwright test "$@"
