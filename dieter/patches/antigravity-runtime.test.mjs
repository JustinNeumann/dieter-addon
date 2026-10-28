// Unit tests of the add-on's Antigravity adapter against a fake `agy` (tests/unit.sh runs them with the
// staged runtime's node_modules, which provide @ai-sdk/harness).
import assert from "node:assert/strict";
import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { after, before, beforeEach, test } from "node:test";
import { createAntigravity } from "./antigravity-runtime.mjs";

const here = fileURLToPath(new URL(".", import.meta.url));
const PROXY = join(here, "antigravity-host-tools.mjs");

// Fake agy: the scenario comes from FAKE_AGY_SCENARIO; every run appends {args, stdin, tools} to FAKE_AGY_LOG.
const FAKE_AGY = `#!/usr/bin/env node
import { appendFileSync } from "node:fs";
import { spawn } from "node:child_process";
import readline from "node:readline";
const args = process.argv.slice(2);
const arg = (name) => (args.includes(name) ? args[args.indexOf(name) + 1] : undefined);
const out = (o) => process.stdout.write(JSON.stringify(o) + "\\n");
const usage = (input, output) => ({ input_tokens: input, output_tokens: output, thinking_tokens: 0, cache_read_tokens: 0, total_tokens: input + output });
let stdin = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (c) => (stdin += c));
process.stdin.on("end", async () => {
  appendFileSync(process.env.FAKE_AGY_LOG, JSON.stringify({ args, stdin, tools: !!process.env.DIETER_AGY_TOOLS_URL }) + "\\n");
  const scenario = process.env.FAKE_AGY_SCENARIO || "simple";
  const resume = arg("--conversation");
  const conv = resume || "conv-new";
  const step = (su) => out({ event: "step_update", step_update: { conversation_id: conv, ...su } });
  const result = (r) => out({ event: "result", result: { conversation_id: conv, ...r } });
  if (scenario === "lost" && resume) {
    process.stderr.write('warning: conversation "' + resume + '" not found\\n');
    out({ event: "init", conversation_id: "conv-fresh", init: { model: arg("--model") } });
    await new Promise((r) => setTimeout(r, 10000)); // the adapter stops this run
    return;
  }
  out({ event: "init", conversation_id: conv, init: { model: arg("--model"), permission_mode: args.includes("--dangerously-skip-permissions") ? "always-proceed" : "request-review" } });
  step({ step_index: 0, state: "DONE", step_type: "user_input" });
  if (stdin.startsWith("/compact")) {
    step({ step_index: 1, state: "DONE", step_type: "system_message" });
    step({ step_index: 2, state: "DONE", step_type: "checkpoint", duration_seconds: 1 });
    return result({ status: "SUCCESS", response: "", usage: usage(0, 0) });
  }
  if (scenario === "error") {
    process.stderr.write('AGY_ERROR: {"status":"RESOURCE_EXHAUSTED","short_error":"Quota reached"}\\n');
    process.exitCode = 1;
    return result({ status: "ERROR", response: "", error: "Quota reached for this model. Resets in 2h3m", usage: usage(0, 0) });
  }
  if (scenario === "slow") {
    step({ step_index: 1, state: "ACTIVE", step_type: "agent_response", text_delta: "working" });
    await new Promise((r) => setTimeout(r, 10000));
    return;
  }
  step({ step_index: 1, state: "DONE", step_type: "agent_response", duration_seconds: 1, usage: usage(100, 10) });
  if (scenario === "host-tool") {
    const proxy = spawn(process.execPath, [process.env.FAKE_AGY_PROXY], { stdio: ["pipe", "pipe", "inherit"] });
    const lines = readline.createInterface({ input: proxy.stdout });
    const waiting = new Map();
    lines.on("line", (l) => { const m = JSON.parse(l); waiting.get(m.id)?.(m); });
    let id = 0;
    const request = (method, params) => new Promise((resolve) => { waiting.set(++id, resolve); proxy.stdin.write(JSON.stringify({ jsonrpc: "2.0", id, method, params }) + "\\n"); });
    const init = await request("initialize", { protocolVersion: "2025-11-25", capabilities: {}, clientInfo: { name: "fake-agy", version: "0" } });
    proxy.stdin.write(JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" }) + "\\n");
    const list = await request("tools/list", {});
    const schema = { Path: "/data/.gemini/antigravity-cli/mcp/dieter/present_content.json" };
    step({ step_index: 2, state: "ACTIVE", step_type: "tool", tool_name: "read_file", tool_info: { name: "read_file", parameters: schema } });
    step({ step_index: 2, state: "DONE", step_type: "tool", tool_name: "read_file", tool_info: { name: "read_file", parameters: schema, output: "1 lines" } });
    const params = { ServerName: "dieter", ToolName: "present_content", Arguments: { path: "a.md" } };
    step({ step_index: 3, state: "ACTIVE", step_type: "tool", tool_name: "call_mcp_tool", tool_info: { name: "call_mcp_tool", parameters: params } });
    const call = await request("tools/call", { name: "present_content", arguments: { path: "a.md" } });
    proxy.stdin.end();
    appendFileSync(process.env.FAKE_AGY_LOG, JSON.stringify({ mcp: { init: init.result, tools: list.result.tools, call: call.result } }) + "\\n");
    step({ step_index: 3, state: "DONE", step_type: "tool", tool_name: "call_mcp_tool", duration_seconds: 0.1, tool_info: { name: "call_mcp_tool", parameters: params, output: call.result.content[0].text } });
  } else {
    step({ step_index: 2, state: "ACTIVE", step_type: "tool", tool_name: "run_command", tool_info: { name: "run_command", parameters: { CommandLine: "ls" } } });
    step({ step_index: 2, state: "DONE", step_type: "tool", tool_name: "run_command", duration_seconds: 0.1, tool_info: { name: "run_command", parameters: { CommandLine: "ls" }, output: "a.md" } });
  }
  step({ step_index: 4, state: "ACTIVE", step_type: "agent_response", text_delta: "hello" });
  step({ step_index: 4, state: "DONE", step_type: "agent_response", text_delta: " world", duration_seconds: 1, usage: usage(150, 20) });
  result({ status: "SUCCESS", response: "hello world", num_turns: 1, usage: usage(250, 30) });
});
`;

let dir;
let fakeAgy;
let logFile;
before(() => {
  dir = mkdtempSync(join(tmpdir(), "agy-test-"));
  fakeAgy = join(dir, "agy");
  writeFileSync(fakeAgy, FAKE_AGY);
  chmodSync(fakeAgy, 0o755);
  logFile = join(dir, "log");
  process.env.FAKE_AGY_LOG = logFile;
  process.env.FAKE_AGY_PROXY = PROXY;
});
after(() => rmSync(dir, { recursive: true, force: true }));
beforeEach(() => {
  writeFileSync(logFile, "");
  process.env.FAKE_AGY_SCENARIO = "simple";
});

const runs = () =>
  readFileSync(logFile, "utf8")
    .split("\n")
    .filter(Boolean)
    .map((l) => JSON.parse(l));

async function startSession(harness, extra = {}) {
  return harness.doStart({
    sessionId: "s1",
    sessionWorkDir: dir,
    permissionMode: "allow-all",
    ...extra,
  });
}

async function prompt(session, turn) {
  const events = [];
  const handle = await session.doPromptTurn({
    prompt: "hi",
    emit: (e) => events.push(e),
    abortSignal: new AbortController().signal,
    ...turn,
  });
  return { events, handle, done: handle.done };
}

const types = (events) => events.map((e) => e.type);

test("a turn runs agy headless, streams tools and text, reports per-step usage", async () => {
  const session = await startSession(createAntigravity({ command: fakeAgy, effort: "max" }));
  const { events, done } = await prompt(session, {
    model: "gemini-4-argon",
    instructions: "Be brief.",
  });
  await done;
  const [run] = runs();
  assert.deepEqual(run.args, [
    "--output-format",
    "stream-json",
    "--model",
    "gemini-4-argon",
    "--effort",
    "max",
    "--dangerously-skip-permissions",
  ]);
  assert.equal(run.stdin, "<instructions>\nBe brief.\n</instructions>\n\nhi\n");
  assert.equal(run.tools, false);
  assert.deepEqual(types(events), [
    "stream-start",
    "tool-call",
    "tool-result",
    "finish-step",
    "text-start",
    "text-delta",
    "text-delta",
    "text-end",
    "finish-step",
    "finish",
  ]);
  const toolCall = events[1];
  assert.equal(toolCall.toolName, "run_command");
  assert.equal(toolCall.providerExecuted, true);
  assert.equal(toolCall.input, '{"CommandLine":"ls"}');
  assert.deepEqual(events[2].result, { output: "a.md", durationSeconds: 0.1 });
  const steps = events.filter((e) => e.type === "finish-step");
  assert.deepEqual(steps[0].finishReason, { unified: "tool-calls", raw: "tool-calls" });
  assert.equal(steps[0].usage.inputTokens.total, 100);
  assert.deepEqual(steps[1].finishReason, { unified: "stop", raw: "SUCCESS" });
  assert.equal(steps[1].usage.inputTokens.total, 150);
  assert.equal(steps[1].usage.outputTokens.total, 20);
  assert.equal(events.at(-1).totalUsage.inputTokens.total, 250);
  assert.equal(events.at(-1).totalUsage.raw.total_tokens, 280);

  const state = await session.doDetach();
  assert.deepEqual(state.data, { conversationId: "conv-new", instructionsApplied: true });

  // the next turn continues the stored conversation without repeating the instructions
  const second = await prompt(session, { model: "gemini-4-argon", instructions: "Be brief." });
  await second.done;
  const [, run2] = runs();
  assert.ok(run2.args.includes("--conversation") && run2.args.includes("conv-new"));
  assert.equal(run2.stdin, "hi\n");
});

test("a model id with its own level is run without --effort; other modes keep agy's prompts", async () => {
  const session = await startSession(createAntigravity({ command: fakeAgy, effort: "xhigh" }), {
    permissionMode: "default",
  });
  const { done } = await prompt(session, { model: "gemini-3.8-flash-high" });
  await done;
  const [run] = runs();
  assert.ok(!run.args.includes("--effort"));
  assert.ok(!run.args.includes("--dangerously-skip-permissions"));
});

test("host tools reach agy through the MCP proxy and come back as host tool calls", async () => {
  process.env.FAKE_AGY_SCENARIO = "host-tool";
  const session = await startSession(createAntigravity({ command: fakeAgy }));
  const tools = [
    {
      name: "present_content",
      description: "Show a file.",
      inputSchema: { type: "object", properties: { path: { type: "string" } } },
    },
  ];
  const events = [];
  let hostCall;
  const handle = await session.doPromptTurn({
    prompt: "show a.md",
    model: "m",
    tools,
    emit: (e) => {
      events.push(e);
      if (e.type === "tool-call" && !e.providerExecuted) hostCall = e;
    },
    abortSignal: new AbortController().signal,
  });
  // the host executes the tool once its call shows up and answers like the harness agent does
  const answered = (async () => {
    while (!hostCall) await new Promise((r) => setTimeout(r, 20));
    assert.equal(hostCall.toolName, "present_content");
    assert.deepEqual(JSON.parse(hostCall.input), { path: "a.md" });
    await handle.submitToolResult({
      toolCallId: hostCall.toolCallId,
      output: { requested: true },
      toolResult: {
        type: "tool-result",
        toolCallId: hostCall.toolCallId,
        toolName: "present_content",
        output: { type: "json", value: { requested: true } },
      },
    });
  })();
  await Promise.all([handle.done, answered]);
  const [run, mcp] = runs();
  assert.equal(run.tools, true);
  assert.equal(mcp.mcp.init.protocolVersion, "2025-11-25");
  assert.deepEqual(mcp.mcp.tools, [
    { name: "present_content", description: "Show a file.", inputSchema: tools[0].inputSchema },
  ]);
  assert.deepEqual(mcp.mcp.call, { content: [{ type: "text", text: '{"requested":true}' }], isError: false });
  assert.deepEqual(types(events), [
    "stream-start",
    "tool-call",
    "tool-result",
    "finish-step",
    "text-start",
    "text-delta",
    "text-delta",
    "text-end",
    "finish-step",
    "finish",
  ]);
  const echo = events[2];
  assert.equal(echo.toolCallId, hostCall.toolCallId);
  assert.equal(echo.providerExecuted, false);
  assert.deepEqual(echo.result, { requested: true });
  assert.ok(!events.some((e) => e.toolName === "call_mcp_tool"), "agy's own MCP step is not shown twice");
  assert.ok(!events.some((e) => e.toolName === "read_file"), "agy's read of the tool schema is not shown");
  await session.doStop();
});

test("the MCP proxy offers no tools outside a Dieter turn", async () => {
  const { spawn } = await import("node:child_process");
  const proxy = spawn(process.execPath, [PROXY], {
    env: { ...process.env, DIETER_AGY_TOOLS_URL: "", DIETER_AGY_TOOLS_TOKEN: "" },
    stdio: ["pipe", "pipe", "inherit"],
  });
  let out = "";
  proxy.stdout.on("data", (c) => (out += c));
  proxy.stdin.write('{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}\n');
  proxy.stdin.write('{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}\n');
  proxy.stdin.write('{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"x","arguments":{}}}\n');
  proxy.stdin.end();
  await new Promise((r) => proxy.on("close", r));
  const replies = out.trim().split("\n").map((l) => JSON.parse(l));
  assert.equal(replies.find((r) => r.id === 1).result.protocolVersion, "2025-03-26");
  assert.deepEqual(replies.find((r) => r.id === 2).result, { tools: [] });
  assert.equal(replies.find((r) => r.id === 3).result.isError, true);
});

test("a conversation agy no longer has is restarted with the instructions and a note", async () => {
  process.env.FAKE_AGY_SCENARIO = "lost";
  const session = await startSession(createAntigravity({ command: fakeAgy }), {
    resumeFrom: {
      type: "resume-session",
      harnessId: "antigravity",
      data: { conversationId: "conv-old", instructionsApplied: true },
    },
  });
  assert.equal(session.isResume, true);
  const { events, done } = await prompt(session, { model: "m", instructions: "Be brief." });
  await done;
  const [first, second] = runs();
  assert.ok(first.args.includes("conv-old"));
  assert.equal(first.stdin, "hi\n");
  assert.ok(!second.args.includes("--conversation"));
  assert.equal(second.stdin, "<instructions>\nBe brief.\n</instructions>\n\nhi\n");
  const note = events.find((e) => e.type === "text-delta" && /no longer has the stored conversation/.test(e.delta));
  assert.ok(note, "the user is told");
  assert.equal(types(events).filter((t) => t === "stream-start").length, 1);
  assert.equal(types(events).at(-1), "finish");
  assert.deepEqual((await session.doDetach()).data, { conversationId: "conv-new", instructionsApplied: true });
});

test("agy's failure is one sentence for the conversation", async () => {
  process.env.FAKE_AGY_SCENARIO = "error";
  const session = await startSession(createAntigravity({ command: fakeAgy }));
  const { events, done } = await prompt(session, { model: "m" });
  await assert.rejects(done, {
    message: "Antigravity has no quota left for this model (resets in 2h3m). Pick another model (e.g. Gemini 3.8 Flash) or try again later.",
  });
  assert.deepEqual(events.at(-1).finishReason, { unified: "error", raw: "ERROR" });
});

test("an interrupted turn stops agy", async () => {
  process.env.FAKE_AGY_SCENARIO = "slow";
  const session = await startSession(createAntigravity({ command: fakeAgy }));
  const controller = new AbortController();
  const events = [];
  const handle = await session.doPromptTurn({
    prompt: "hi",
    model: "m",
    emit: (e) => events.push(e),
    abortSignal: controller.signal,
  });
  while (!events.some((e) => e.type === "text-delta")) await new Promise((r) => setTimeout(r, 20));
  controller.abort();
  await assert.rejects(handle.done, { message: "agent turn interrupted" });
  assert.ok(events.some((e) => e.type === "text-end"), "open parts are closed");
});

test("compaction runs agy's /compact on the stored conversation", async () => {
  const session = await startSession(createAntigravity({ command: fakeAgy }));
  await session.doCompact(); // nothing stored yet: nothing to do
  assert.equal(runs().length, 0);
  const { done } = await prompt(session, { model: "m" });
  await done;
  await session.doCompact();
  const [, compact] = runs();
  assert.deepEqual(compact.args, ["--output-format", "stream-json", "--conversation", "conv-new"]);
  assert.equal(compact.stdin, "/compact\n");
});
