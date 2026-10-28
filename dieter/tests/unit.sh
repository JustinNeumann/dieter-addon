#!/bin/bash
# Unit tests of the add-on's harness runtime files (patches/*.test.mjs). They import @ai-sdk/harness from a
# staged Dieter runtime: NODE_MODULES=<dir>, else the add-on's own /data/dieter/harness-staged/<version>/runtime/node_modules.
# Usage: tests/unit.sh [node --test args]
set -euo pipefail
cd "$(dirname "$0")/../patches"
NM="${NODE_MODULES:-$(ls -d /data/dieter/harness-staged/*/runtime/node_modules 2>/dev/null | tail -1 || true)}"
[ -n "$NM" ] && [ -d "$NM" ] || { echo "no staged runtime node_modules found; set NODE_MODULES=<dir>"; exit 1; }
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
cp ./*.mjs "$T"/
ln -s "$NM" "$T/node_modules"
node --test "$@" "$T"/*.test.mjs
