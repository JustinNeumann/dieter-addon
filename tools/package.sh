#!/usr/bin/env bash
# Packs the production build of :web into the bundle the add-on serves at /next/.
#
#   tools/package.sh <version> [dist dir] [output dir]
#
# - dieter.js gets a content hash in its name (index.html is rewritten), so every file except
#   index.html and composeResources/ has a hashed name and can be cached as immutable.
# - Every .wasm gets a .wasm.gz next to it: Home Assistant's Ingress strips Accept-Encoding and
#   does not compress application/wasm, so the loader fetches the .gz and inflates it in the
#   browser (DecompressionStream). For direct (non-Ingress) access the bridge also negotiates
#   Content-Encoding with .gz / .br copies of the larger text and font files.
# - Output: <output dir>/dieter-web-<version>.tar.gz and SHA256SUMS (reproducible tar).
set -euo pipefail
VERSION=${1:?usage: tools/package.sh <version> [dist dir] [output dir]}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
DIST=$(realpath -m "${2:-$ROOT/web/build/dist/wasmJs/productionExecutable}")
OUT=$(realpath -m "${3:-$ROOT/build/package}")
NAME="dieter-web-${VERSION}"
STAGE="$OUT/$NAME"

[ -f "$DIST/index.html" ] && [ -f "$DIST/dieter.js" ] || { echo "no production build in $DIST" >&2; exit 1; }
rm -rf "$STAGE" && mkdir -p "$STAGE"
cp -r "$DIST"/. "$STAGE"/
cd "$STAGE"
rm -f ./*.map

hash=$(sha256sum dieter.js | cut -c1-16)
sed -i '/^\/\/# sourceMappingURL=/d' dieter.js
mv dieter.js "dieter.$hash.js"
[ -f dieter.js.LICENSE.txt ] && mv dieter.js.LICENSE.txt "dieter.$hash.js.LICENSE.txt"
grep -q 'script.src = "dieter.js"' index.html || { echo "index.html has no bundle reference to rewrite" >&2; exit 1; }
sed -i "s|script.src = \"dieter.js\"|script.src = \"dieter.$hash.js\"|" index.html
sed -i "s|dieter.js.LICENSE.txt|dieter.$hash.js.LICENSE.txt|g" "dieter.$hash.js"

commit=$(git -C "$ROOT/fork" rev-parse HEAD 2>/dev/null || echo unknown)
own=$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unknown)
printf '{"version": "%s", "dieter_commit": "%s", "commit": "%s"}\n' "$VERSION" "$commit" "$own" > BUILD.json

# precompressed copies (deterministic: no names or times in the gzip header)
find . -type f \( -name '*.wasm' -o -name '*.js' -o -name '*.ttf' -o -name '*.otf' -o -name '*.json' -o -name '*.html' -o -name '*.txt' \) -size +1k |
  while read -r f; do
    gzip -9 -n -k -f "$f"
    if command -v brotli >/dev/null; then brotli -q 11 -k -f "$f"; fi
  done

cd "$OUT"
export SOURCE_DATE_EPOCH=${SOURCE_DATE_EPOCH:-$(git -C "$ROOT" log -1 --format=%ct 2>/dev/null || date +%s)}
tar --sort=name --owner=0 --group=0 --numeric-owner --mtime="@$SOURCE_DATE_EPOCH" -cf - "$NAME" | gzip -9 -n > "$NAME.tar.gz"
sha256sum "$NAME.tar.gz" > SHA256SUMS
du -sh "$NAME" "$NAME.tar.gz"
cat SHA256SUMS
