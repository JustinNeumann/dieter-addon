#!/usr/bin/env bash
# Puts the page, the web host and the embedded gateway into the add-on's image build context (dieter/web-dist/), where
# dieter/Dockerfile copies them from: dieter-web.tar.gz (tools/package.sh), dieter-web-host.jar and dieter-gateway-{amd64,arm64}.
#
#   tools/addon-dist.sh            builds :web and :host first (JDK 21 in JAVA_HOME, npm on the PATH)
#   tools/addon-dist.sh --no-build packs what the last build left (CI builds and tests in its own step)
#
# The version is the add-on's (dieter/config.yaml), so the page's BUILD.json names the release it ships in.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
VERSION=$(sed -n 's/^version: *"\{0,1\}\([^"]*\)"\{0,1\} *$/\1/p' "$ROOT/dieter/config.yaml")
[ -n "$VERSION" ] || { echo "no version in dieter/config.yaml" >&2; exit 1; }
if [ "${1:-}" != --no-build ]; then
  (cd "$ROOT" && DIETER_WEB_VERSION="$VERSION" ./gradlew --console=plain -q :web:wasmJsBrowserDistribution :host:fatJar)
fi
JAR="$ROOT/host/build/libs/dieter-web-host-$VERSION.jar"
[ -f "$JAR" ] || { echo "no $JAR: build with DIETER_WEB_VERSION=$VERSION (or drop --no-build)" >&2; exit 1; }
"$ROOT/tools/package.sh" "$VERSION" "" "$ROOT/build/package" >/dev/null
OUT="$ROOT/dieter/web-dist"
mkdir -p "$OUT"
cp "$ROOT/build/package/dieter-web-$VERSION.tar.gz" "$OUT/dieter-web.tar.gz"
cp "$JAR" "$OUT/dieter-web-host.jar"

GO_BIN=$(command -v go || true)
if [ -z "$GO_BIN" ] && [ -x "$HOME/.local/go/bin/go" ]; then
  GO_BIN="$HOME/.local/go/bin/go"
fi
[ -n "$GO_BIN" ] || { echo "go not found on PATH (needed to build dieter-gateway)" >&2; exit 1; }
OVERLAY=$(mktemp "${TMPDIR:-/tmp}/dieter-gateway-overlay-XXXXXX.json")
trap 'rm -f "$OVERLAY"' EXIT
printf '{"Replace":{"%s/fork/cmd/dieter-embedded-gateway/main.go":"%s/tools/embedded-gateway/main.go"}}\n' "$ROOT" "$ROOT" > "$OVERLAY"
for ARCH in amd64 arm64; do
  (cd "$ROOT/fork" && CGO_ENABLED=0 GOOS=linux GOARCH="$ARCH" "$GO_BIN" build -overlay="$OVERLAY" -trimpath -ldflags="-s -w" -o "$OUT/dieter-gateway-$ARCH" ./cmd/dieter-embedded-gateway)
done

(cd "$OUT" && sha256sum dieter-web.tar.gz dieter-web-host.jar dieter-gateway-amd64 dieter-gateway-arm64)
