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
    "antigravity-runtime.mjs": "5b963e047a2617b10ff9096bd324bbb62e3b4bb79777c6c3c98352731796839c",
}
# The stdio MCP proxy agy launches to reach the host tools of a turn (see antigravity-runtime.mjs),
# and the worker resilience helpers (OOM score adjustment, broken-stdout EPIPE guard, turn slot gate).
ADDITIONS = ["antigravity-host-tools.mjs", "turn-resilience.mjs"]
PATCHES = {
    "local-sandbox.mjs": [
        (
            'import { createServer } from "node:net";',
            'import { createServer } from "node:net";\nimport { raiseOomScoreAdj } from "./turn-resilience.mjs";',
        ),
        (
            "  if (scopedInstall) {\n"
            "    return `${install} --prefix ${scopedInstall[1]}`;\n"
            "  }\n"
            "  return command;\n"
            "}",
            "  if (scopedInstall) {\n"
            "    return `${install} --prefix ${scopedInstall[1]}`;\n"
            "  }\n"
            '  if (command.includes("--bridge-state-dir") && !command.trimStart().startsWith("exec ")) {\n'
            "    return `exec ${command}`;\n"
            "  }\n"
            "  return command;\n"
            "}",
        ),
        (
            "      detached: process.platform !== \"win32\",\n    });",
            "      detached: process.platform !== \"win32\",\n    });\n    if (child.pid) raiseOomScoreAdj(600, child.pid);",
        ),
    ],
    "runner.mjs": [
        (
            'import { QUESTIONS_TOOL, createQuestionRelay, questionToolResult } from "./host-questions.mjs";',
            'import { QUESTIONS_TOOL, createQuestionRelay, questionToolResult } from "./host-questions.mjs";\n'
            'import { acquireTurnSlot, guardWorkerStdout, raiseOomScoreAdj } from "./turn-resilience.mjs";\n\n'
            "raiseOomScoreAdj(500);\n"
            "const stdoutGuard = guardWorkerStdout();",
        ),
        (
            "let exitPromise;\n"
            "const send = (value) => {\n"
            "  if (!exitPromise) return protocolWrite(`${JSON.stringify(value)}\\n`);\n"
            "};\n"
            "const exitWorker = (exitCode) => {\n"
            "  if (!exitPromise) {\n"
            "    clearInterval(heartbeatTimer);\n"
            "    // stdout is an asynchronous pipe. In particular, a suspended turn can\n"
            "    // enqueue a continuation larger than the pipe buffer. process.exit() must\n"
            "    // wait for every queued frame, or Go receives a truncated JSON checkpoint.\n"
            "    // Stop accepting frames before the barrier so late stream/heartbeat events\n"
            "    // cannot race it. The host retains its bounded shutdown/kill deadline.\n"
            "    exitPromise = new Promise(() => {\n"
            '      protocolWrite("", (error) => process.exit(error ? 1 : exitCode));\n'
            "    });\n"
            "  }\n"
            "  return exitPromise;\n"
            "};",
            "let exitPromise;\n"
            "const send = (value) => {\n"
            "  if (!exitPromise && !stdoutGuard.isBroken()) return protocolWrite(`${JSON.stringify(value)}\\n`);\n"
            "};\n"
            "const exitWorker = (exitCode) => {\n"
            "  if (!exitPromise) {\n"
            "    clearInterval(heartbeatTimer);\n"
            "    // stdout is an asynchronous pipe. In particular, a suspended turn can\n"
            "    // enqueue a continuation larger than the pipe buffer. process.exit() must\n"
            "    // wait for every queued frame, or Go receives a truncated JSON checkpoint.\n"
            "    // Stop accepting frames before the barrier so late stream/heartbeat events\n"
            "    // cannot race it. The host retains its bounded shutdown/kill deadline.\n"
            "    exitPromise = new Promise(() => {\n"
            "      if (stdoutGuard.isBroken()) process.exit(exitCode);\n"
            '      else protocolWrite("", (error) => process.exit(error ? 1 : exitCode));\n'
            "    });\n"
            "  }\n"
            "  return exitPromise;\n"
            "};",
        ),
        (
            'const heartbeatTimer = setInterval(() => send({ type: "heartbeat" }), 5_000);',
            "const heartbeatTimer = setInterval(() => {\n"
            '  if (!stdoutGuard.checkParent()) send({ type: "heartbeat" });\n'
            "}, 5_000);",
        ),
        (
            'process.once("SIGTERM", () => interrupt(143));\n'
            'process.once("SIGINT", () => interrupt(130));\n'
            'process.once("SIGUSR1", () => {\n'
            "  suspending = true;\n"
            "  suspendPromise = suspend();\n"
            "  void suspendPromise.finally(() => exitWorker(process.exitCode || 0));\n"
            "});\n\n"
            "try {",
            'process.once("SIGTERM", () => interrupt(143));\n'
            'process.once("SIGINT", () => interrupt(130));\n'
            "stdoutGuard.onDisconnect(() => interrupt(141));\n"
            'process.once("SIGUSR1", () => {\n'
            "  suspending = true;\n"
            "  suspendPromise = suspend();\n"
            "  void suspendPromise.finally(() => exitWorker(process.exitCode || 0));\n"
            "});\n\n"
            "let releaseTurnSlot;\n"
            "try {\n"
            "  releaseTurnSlot = await acquireTurnSlot({\n"
            "    abortSignal: controller.signal,\n"
            "    shouldStop: () => suspending || stdoutGuard.isBroken(),\n"
            "  });\n"
            "  if (suspending) {\n"
            "    await suspendPromise;\n"
            "    await exitWorker(process.exitCode || 0);\n"
            "  }",
        ),
        (
            "} finally {\n  processBridge?.dispose();",
            "} finally {\n  await releaseTurnSlot?.();\n  processBridge?.dispose();",
        ),
    ],
}

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
for name, replacements in PATCHES.items():
    path = f"{root}/{name}"
    text = open(path, encoding="utf-8").read()
    pairs = [replacements] if isinstance(replacements, tuple) else list(replacements)
    for old, new in pairs:
        if text.count(old) != 1:
            sys.exit(f"patch_runtime: {name}: expected{old!r} once, found {text.count(old)}")
        text = text.replace(old, new)
    open(path, "w", encoding="utf-8").write(text)
    print(f"patched {name}")
