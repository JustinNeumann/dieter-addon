#!/usr/bin/env python3
"""Changes to the fork's harness runtime sources at image build time (after the sha check of the sources).

Two kinds, both pinned to the fork release so a new release has to be re-checked instead of silently
keeping or skipping a change:
- OVERRIDES: files in this directory that replace a fork file; the build fails when the fork's file no
  longer has the recorded sha256 (its changes would have to be merged into the add-on's copy).
- PATCHES: exact string replacements; the build fails when the pinned source no longer matches.
Files in ADDITIONS exist only in the add-on and are copied alongside.
Drop an entry once the fork release contains it.

Usage: patch_runtime.py <runtime sources dir>
"""
import hashlib
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

# The add-on's Antigravity adapter (host tools over MCP, per-step usage, /compact, lost-conversation
# recovery, the --effort fix) replaces the fork's; sha256 of the fork's file at DIETER_COMMIT.
OVERRIDES = {
    "antigravity-runtime.mjs": "e5284a82635a9ffd8db13ec4827ff290d6809cf8654e77fbe70a875b287068eb",
    # the worker sends the operator's poll token back (fork web-addon, after the pinned release)
    "host-questions.mjs": "ca865acbfe0c78bcc12792f9fd6a7575c085b4c06742b7826f8b7ddbad6afb69",
}
# The stdio MCP proxy agy launches to reach the host tools of a turn (see antigravity-runtime.mjs).
ADDITIONS = ["antigravity-host-tools.mjs"]
PATCHES = {}

root = sys.argv[1]
for name, expected in OVERRIDES.items():
    path = f"{root}/{name}"
    actual = hashlib.sha256(open(path, "rb").read()).hexdigest()
    if actual != expected:
        sys.exit(f"patch_runtime: {name}: the fork's file changed (sha256 {actual}); merge it into the add-on's copy")
    shutil.copyfile(f"{HERE}/{name}", path)
    print(f"replaced {name}")
for name in ADDITIONS:
    shutil.copyfile(f"{HERE}/{name}", f"{root}/{name}")
    print(f"added {name}")
for name, (old, new) in PATCHES.items():
    path = f"{root}/{name}"
    text = open(path, encoding="utf-8").read()
    if text.count(old) != 1:
        sys.exit(f"patch_runtime: {name}: expected exactly one match, found {text.count(old)}")
    open(path, "w", encoding="utf-8").write(text.replace(old, new))
    print(f"patched {name}")
