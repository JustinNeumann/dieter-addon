#!/usr/bin/env bash
# Puts the page and the web host into the add-on's image build context (dieter/web-dist/), where
# dieter/Dockerfile copies them from: dieter-web.tar.gz (tools/package.sh) and dieter-web-host.jar.
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
(cd "$OUT" && sha256sum dieter-web.tar.gz dieter-web-host.jar)
