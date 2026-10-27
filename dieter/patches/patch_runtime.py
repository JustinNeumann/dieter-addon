#!/usr/bin/env python3
"""Patches applied to the fork's harness runtime sources at image build time (after the sha check).

Each patch is an exact string replacement and fails the build when the pinned source no longer matches,
so a new fork release has to be re-checked instead of silently skipping a patch.
Drop a patch once the fork release contains it.
"""
import sys

PATCHES = {
    # agy model ids carry their reasoning level (gemini-3.8-flash-high, claude-opus-5-5-low, ...) and agy rejects
    # every --effort that differs from that suffix ("--model X conflicts with --effort=xhigh"), but Dieter
    # offers low..max for all Antigravity models. A model with a level suffix is run without --effort.
    "antigravity-runtime.mjs": (
        '          if (effort) args.push("--effort", effort);\n',
        '          if (effort && !/-(low|medium|high)$/.test(turn.model || "")) args.push("--effort", effort);\n',
    ),
}

root = sys.argv[1]
for name, (old, new) in PATCHES.items():
    path = f"{root}/{name}"
    text = open(path, encoding="utf-8").read()
    if text.count(old) != 1:
        sys.exit(f"patch_runtime: {name}: expected exactly one match, found {text.count(old)}")
    open(path, "w", encoding="utf-8").write(text.replace(old, new))
    print(f"patched {name}")
