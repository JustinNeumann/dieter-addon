// The add-on's HTTP API as Home Assistant and the scripts use it (homeassistant/: sensor.dieter from api/ha, the
// rest_commands dieter_create_task / dieter_send_message / dieter_move_card / dieter_focus, dieter_task.py), against the
// image with Dieter's mock harness (DIETER_ENABLE_MOCK_HARNESS=1) behind the Ingress stand-in. Started by tests/run.sh.
// Whatever serves these paths (the web host's front door, the operator behind it) must keep these answers.
import { test, expect } from "@playwright/test";
import { execFileSync } from "node:child_process";

test.describe.configure({ mode: "serial" });

const REPO = "https://github.com/octocat/Hello-World.git";
const stamp = Date.now().toString(36);
let cardId;

async function state(request) {
  const r = await request.get("api/state");
  expect(r.ok()).toBeTruthy();
  return r.json();
}
async function until(fn, ms = 90_000) {
  const end = Date.now() + ms; let v;
  while (Date.now() < end) { v = await fn(); if (v) return v; await new Promise((f) => setTimeout(f, 1000)); }
  return v;
}
const conversation = async (request, id) => JSON.stringify(await (await request.get(`api/card/${id}`)).json());

test("the catalog lists the mock harness", async ({ request }) => {
  const st = await state(request);
  expect(st.ok).toBe(true);
  expect(st.harnesses.map((h) => h.id)).toContain("mock");
});

test("a repository is cloned and registered (POST through Ingress)", async ({ request }) => {
  const r = await request.post("api/project", { data: { git_url: REPO }, timeout: 120_000 });
  const out = await r.json();
  expect(r.ok() || /already registered/.test(out.error || ""), JSON.stringify(out)).toBe(true);
  expect((await state(request)).projects.some((p) => p.name === "Hello-World")).toBe(true);
});

test("a new folder becomes a project (empty Git repository under /data/projects), once", async ({ request }) => {
  const made = await (await request.post("api/project", { data: { folder: `Scratch-${stamp}` } })).json();
  expect(made.error).toBeUndefined();
  expect((await state(request)).projects.some((p) => p.name === `Scratch-${stamp}`)).toBe(true);
  const again = await (await request.post("api/project", { data: { folder: `Scratch-${stamp}` } })).json();
  expect(again.existed).toBe(true); // adopted, not duplicated
  expect((await request.post("api/project", { data: { folder: "../escape" } })).ok()).toBeFalsy();
});

test("dieter_create_task: a task starts on the mock harness and answers", async ({ request }) => {
  const st = await state(request);
  const project = st.projects.find((p) => p.name === "Hello-World");
  const board = st.boards.find((b) => b.project_id === project.id);
  expect(board, "the project's board").toBeTruthy();
  // the body of the rest_command (and of dieter_task.py)
  const r = await request.post("api/card", { data: { project: project.id, board: board.id, title: `E2E ${stamp}`, prompt: `e2e ${stamp}`,
    provider: "mock", model: "", effort: "", workspace: "project", start: true }, timeout: 60_000 });
  const out = await r.json();
  expect(r.ok(), JSON.stringify(out)).toBe(true);
  cardId = out.id;
  expect(cardId).toMatch(/^c_/);
  expect(await until(async () => (await conversation(request, cardId)).includes(`Mock harness received: e2e ${stamp}`))).toBe(true);
});

test("dieter_send_message: a follow-up reaches the card and is answered", async ({ request }) => {
  test.skip(!cardId, "no card from the earlier test");
  await until(async () => (await state(request)).cards.find((c) => c.id === cardId)?.lane !== "running", 60_000);
  const r = await request.post(`api/card/${cardId}/send`, { data: { message: `again ${stamp}` }, timeout: 60_000 });
  expect(r.ok(), await r.text()).toBe(true);
  expect(await until(async () => (await conversation(request, cardId)).includes(`Mock harness received: again ${stamp}`))).toBe(true);
});

test("attachments: a file uploaded on the page goes with a follow-up and is gone after", async ({ request }) => {
  test.skip(!cardId, "no card from the earlier test");
  await until(async () => (await state(request)).cards.find((c) => c.id === cardId)?.lane !== "running", 60_000);
  const upload = await request.post("api/upload", { data: Buffer.from(`notes ${stamp}\n`), headers: { "Content-Type": "text/plain", "X-Filename": "My%20notes.txt" } });
  const file = await upload.json();
  expect(upload.ok(), JSON.stringify(file)).toBe(true);
  expect(file).toEqual({ id: expect.stringMatching(/^[A-Za-z0-9_-]{24}$/), name: "My-notes.txt", size: `notes ${stamp}\n`.length });
  const r = await request.post(`api/card/${cardId}/send`, { data: { message: `attached ${stamp}`, attachments: [file.id] }, timeout: 60_000 });
  expect(r.ok(), await r.text()).toBe(true);
  expect(await until(async () => (await conversation(request, cardId)).includes(`Mock harness received: attached ${stamp}`))).toBe(true);
  const again = await request.post(`api/card/${cardId}/send`, { data: { message: "never sent", attachments: [file.id] } });
  expect(await again.json()).toEqual({ error: "attachment expired, add it again" });
});

test("dieter_move_card: the card moves to another lane", async ({ request }) => {
  test.skip(!cardId, "no card from the earlier test");
  await until(async () => (await state(request)).cards.find((c) => c.id === cardId)?.lane !== "running", 60_000);
  const r = await request.post(`api/card/${cardId}/move`, { data: { lane: "done" } });
  expect(r.ok(), await r.text()).toBe(true);
  expect(await until(async () => (await state(request)).cards.find((c) => c.id === cardId)?.lane === "done", 30_000)).toBe(true);
});

test("dieter_focus: the card a push opens is kept for the next page load, once", async ({ request }) => {
  test.skip(!cardId, "no card from the earlier test");
  expect((await request.post("api/focus", { data: { id: "not-a-card" } })).ok()).toBeFalsy();
  expect((await request.post("api/focus", { data: { id: cardId } })).ok()).toBe(true);
  expect((await (await request.get("api/focus")).json()).id).toBe(cardId);
  expect((await (await request.get("api/focus?consume=1")).json()).id).toBe(cardId);
  expect((await (await request.get("api/focus")).json()).id).toBeNull();
});

test("sensor.dieter: the HA summary names the projects and the last finished task", async ({ request }) => {
  let ha;
  for (let i = 0; i < 15; i++) { // the operator polls the daemon every 8 s
    const r = await request.get("api/ha");
    expect(r.ok()).toBeTruthy();
    ha = await r.json();
    if (ha.last_finished?.title?.includes(`E2E ${stamp}`)) break;
    await new Promise((f) => setTimeout(f, 2000));
  }
  expect(ha.ok).toBe(true);
  expect(ha.addon).toBe(true);
  expect(ha.projects).toContain("Hello-World");
  expect(ha.last_finished?.title).toContain(`E2E ${stamp}`);
});

test("hass-cli is in the image, the Supervisor CLI is not", async () => {
  // tests/run.sh starts the container as dieter-e2e; outside an add-on no token is set, so only the binary is checked
  expect(() => execFileSync("docker", ["exec", "dieter-e2e", "sh", "-c", "command -v ha"], { stdio: "pipe" })).toThrow();
  const hass = execFileSync("docker", ["exec", "dieter-e2e", "hass-cli", "--version"]).toString();
  expect(hass).toContain("1.0.0");
});

test("port 8099: Ingress gets everything, Home Assistant its routes, the agents on loopback nothing", async ({ request }) => {
  const port = process.env.BRIDGE_PORT || "18099";
  const upgrade = "-H 'Upgrade: websocket' -H 'Connection: Upgrade' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' -H 'Sec-WebSocket-Version: 13'";
  // run.sh treats 127.0.0.3 as Home Assistant core (BRIDGE_CORE_NETS), as 172.30.32.1 is in the add-on
  const status = (path, { from = "", method = "GET" } = {}) => execFileSync("docker", ["exec", "-u", "dieter", "dieter-e2e", "sh", "-c",
    `curl -s -o /dev/null -w '%{http_code}' --max-time 5 ${from && `--interface ${from}`} -X ${method} ${method === "POST" ? "-H 'Content-Type: application/json' -d '{}'" : upgrade} http://127.0.0.1:${port}/${path}`],
    { stdio: "pipe" }).toString().trim();
  for (const path of ["next/core", "next/signin?code=x&state=y", "next/", "api/state", "api/questions"]) expect(status(path), path).toBe("403");
  expect(status("api/gateway/unenroll", { method: "POST" })).toBe("403");
  expect(status("api/ha", { from: "127.0.0.3" })).toBe("200");
  expect(status("api/state", { from: "127.0.0.3" })).toBe("200");
  for (const path of ["next/core", "next/", "api/questions", "api/settings"]) expect(status(path, { from: "127.0.0.3" }), path).toBe("403");
  expect(status("api/gateway/unenroll", { from: "127.0.0.3", method: "POST" })).toBe("403");
  expect((await request.get("next/")).status()).toBe(200);               // through Ingress the page and its core work
  expect((await request.get("api/questions")).status()).toBe(200);
});

test("the operator (127.0.0.1:8096): agents register and poll questions, nothing else; root and the web host get everything", async ({ request }) => {
  const status = (user, method, path, body) => execFileSync("docker", ["exec", "-u", user, "dieter-e2e", "sh", "-c",
    `curl -s -o /dev/null -w '%{http_code}' --max-time 5 -X ${method} ${body ? `-H 'Content-Type: application/json' -d '${body}'` : ""} http://127.0.0.1:8096/api/${path}`],
    { stdio: "pipe" }).toString().trim();
  expect(status("dieter", "GET", "questions/toolu_nope")).toBe("404");             // the harness worker's poll
  expect(status("dieter", "POST", "questions", "{}")).toBe("400");                   // its registration (refused as empty)
  expect(status("dieter", "GET", "questions")).toBe("403");                          // another card's pending questions
  expect(status("dieter", "POST", "questions/toolu_nope/answer", '{"action":"declined"}')).toBe("403");
  expect(status("dieter", "POST", "gateway/unenroll", "{}")).toBe("403");
  expect(status("dieter", "GET", "state")).toBe("403");
  expect(status("root", "GET", "questions")).toBe("200");
  expect((await request.get("api/questions")).status()).toBe(200);                   // the page, through the web host
});

test("the Supervisor token reaches the web host only: no agent finds it, and its relay passes only the operator's calls", async () => {
  // tests/run.sh sets SUPERVISOR_TOKEN as the Supervisor does; the option agents_home_assistant is off. (docker exec
  // passes the container's environment on, which the daemon never does for agents: the checks run without it.)
  const sh = (cmd, user) => execFileSync("docker", ["exec", "-u", user, "dieter-e2e", "env", "-u", "SUPERVISOR_TOKEN", "sh", "-c", cmd], { stdio: "pipe" }).toString().trim();
  const token = "e2e-supervisor-token-2718";
  expect(sh(`grep -l ${token} /proc/[0-9]*/environ 2>/dev/null | xargs -r -n1 dirname | xargs -r -I{} cat {}/comm 2>/dev/null; true`, "dieter")).toBe("");
  expect(sh(`grep -l ${token} /proc/[0-9]*/environ 2>/dev/null | wc -l`, "root")).not.toBe("0");   // the check would see it: root sees the web host's
  expect(sh(`grep -rl ${token} /data /run /tmp /etc 2>/dev/null; true`, "dieter")).toBe("");
  expect(sh("cat /run/s6/container_environment/SUPERVISOR_TOKEN >/dev/null 2>&1 && echo read || echo denied", "dieter")).toBe("denied");
  expect(sh("cat /data/.claude/CLAUDE.md", "dieter")).toContain("The agents have no access to Home Assistant here");
  const relay = (path, body) => sh(`curl -s -o /dev/null -w '%{http_code}' --max-time 10 -X POST -H 'Content-Type: application/json' -d '${body}' http://127.0.0.1:8097/core/api/${path}`, "dieter");
  expect(relay("services/rest_command/dieter_send_message", '{"card":"c_1","message":"hi"}')).toBe("403");
  expect(relay("services/homeassistant/restart", "{}")).toBe("403");
  expect(relay("template", '{"template":"{{ states | list }}"}')).toBe("403");
  expect(relay("events/dieter_question", '{"card_id":"c_1"}')).toBe("502");               // passed on; no Supervisor here
});

test("questions: the poll token is the asking worker's; an id still waiting cannot be registered again", async ({ request }) => {
  const sh = (cmd) => execFileSync("docker", ["exec", "-u", "dieter", "dieter-e2e", "sh", "-c", cmd], { stdio: "pipe" }).toString().trim();
  const id = `toolu_e2e_${stamp}`.replace(/[^A-Za-z0-9_]/g, "_");
  const body = (q) => JSON.stringify({ id, session: "c_e2e_tokens", input: { questions: [{ id: "q1", question: q, options: [{ id: "a" }] }] } });
  const register = (q) => sh(`curl -s -w ' %{http_code}' -X POST -H 'Content-Type: application/json' -d '${body(q)}' http://127.0.0.1:8096/api/questions`);
  const first = register("Which?");
  expect(first.endsWith(" 200")).toBe(true);
  const token = JSON.parse(first.slice(0, -4)).token;
  expect(token.length).toBeGreaterThan(20);
  expect(register("Your password?").endsWith(" 409")).toBe(true);
  const poll = (header) => sh(`curl -s -o /dev/null -w '%{http_code}' ${header} http://127.0.0.1:8096/api/questions/${id}`);
  expect(poll("")).toBe("404");
  expect(poll("-H 'X-Question-Token: guess'")).toBe("404");
  expect(poll(`-H 'X-Question-Token: ${token}'`)).toBe("200");
  expect((await request.post(`api/questions/${id}/answer`, { data: { action: "declined" } })).status()).toBe(200);
  expect(sh(`curl -s -H 'X-Question-Token: ${token}' http://127.0.0.1:8096/api/questions/${id}`)).toContain('"action":"declined"');
});

test("the API key reaches the web host only: options.json is root's, the operator reads a copy without it", async () => {
  const sh = (cmd, user) => execFileSync("docker", ["exec", "-u", user, "dieter-e2e", "sh", "-c", cmd], { stdio: "pipe" }).toString().trim();
  expect(sh("stat -c '%U %a' /data/options.json", "root")).toBe("root 600");
  expect(sh("cat /data/options.json >/dev/null 2>&1 && echo read || echo denied", "dieter")).toBe("denied");
  expect(sh("stat -c '%U %a' /data/.operator-options.json", "root")).toBe("dieter 600");
  expect(sh("jq 'has(\"api_key\")' /data/.operator-options.json", "dieter")).toBe("false");
  expect(sh("jq -r .git_user /data/.operator-options.json", "dieter")).toBe("E2E");
  expect(sh("for p in $(pgrep -u dieter); do tr '\\0' '\\n' < /proc/$p/environ 2>/dev/null; done | grep -c '^BRIDGE_API_KEY=' || true", "root")).toBe("0");
  // tests/run.sh's options set an api_key: with it, any address gets Home Assistant's routes, and only those
  const key = sh("jq -r '.api_key // \"\"' /data/options.json", "root");
  test.skip(!key, "options.json without api_key (a data directory from before 0.6.431.24)");
  const port = process.env.BRIDGE_PORT || "18099";
  const status = (path, auth, method = "GET") => sh(`curl -s -o /dev/null -w '%{http_code}' --max-time 5 -X ${method} ${auth ? `-H 'Authorization: Bearer ${auth}'` : ""} http://127.0.0.1:${port}/${path}`, "dieter");
  expect(status("api/ha", key)).toBe("200");
  expect(status("api/ha", key + "x")).toBe("403");
  expect(status("api/questions", key)).toBe("403");
  expect(status("api/gateway/unenroll", key, "POST")).toBe("403");
});

test("Agent instructions (api/prompts): the daemon's templates, refusals with the daemon's reason, set and reset", async ({ request }) => {
  const prompts = async () => (await request.get("api/prompts")).json();
  const before = await prompts();
  for (const k of ["template", "board_skill", "chat_skill"]) expect(typeof before[k]).toBe("string");
  expect(before.template).toContain("{{project.instructions_block}}");
  expect(before.variables.length).toBeGreaterThan(5);
  expect(Array.isArray(before.overrides)).toBe(true);
  for (const bad of [{}, { chat_skill: "  " }, { chat_skill: "x", reset: ["chat_skill"] }, { reset: ["nope"] }]) {
    const r = await request.post("api/prompts", { data: bad });
    expect(r.status(), JSON.stringify(bad)).toBe(400);
  }
  const refused = await request.post("api/prompts", { data: { template: "Hello" } });
  expect(refused.status()).toBe(400);
  expect((await refused.json()).error).toContain("instructions_block");
  const set = await (await request.post("api/prompts", { data: { chat_skill: `-Chats ${stamp}` } })).json();
  expect(set.chat_skill).toBe(`-Chats ${stamp}`);                       // a text starting with "-" is no flag
  const reset = await (await request.post("api/prompts", { data: { reset: ["chat_skill"] } })).json();
  expect(reset.chat_skill).toBe(before.chat_skill);
  expect(reset.template).toBe(before.template);
});

test("Settings (api/settings, api/boards/order): title models, work folder retention and board order, with refusals", async ({ request }) => {
  const get = async () => (await request.get("api/settings")).json();
  const before = await get();
  expect(["openai", "ollama"]).toContain(before.title_model);
  expect(before.title_models.map((m) => m.id)).toEqual(["openai", "ollama"]);
  expect(before.title_models[1].available).toBe(false);                  // a Supervisor token (run.sh), but no Home Assistant behind it
  expect(before.title_models[1].detail).toBe("No AI Task entity of the Ollama integration found; OpenAI titles instead");
  expect(before.titles_daemon_id).toBe("");
  expect(Number.isInteger(before.worktree_retention_days)).toBe(true);
  for (const bad of [{ worktree_retention_days: -1 }, { worktree_retention_days: 1.5 }, { worktree_retention_days: "x" }, { title_model: "gpt" }]) {
    const r = await request.post("api/settings", { data: bad });
    expect(r.status(), JSON.stringify(bad)).toBe(400);
  }
  expect((await (await request.post("api/settings", { data: { worktree_retention_days: "45" } })).json()).worktree_retention_days).toBe(45);
  expect((await get()).worktree_retention_days).toBe(45);
  await request.post("api/settings", { data: { worktree_retention_days: before.worktree_retention_days } });
  for (const bad of [{}, { ids: [] }, { ids: [""] }]) expect((await request.post("api/boards/order", { data: bad })).status(), JSON.stringify(bad)).toBe(400);
  const order = await (await request.post("api/boards/order", { data: { ids: [`b-e2e-${stamp}`] } })).json();
  expect(order.ok).toBe(true);
  expect(order.board_order[0]).toBe(`b-e2e-${stamp}`);
  expect(order.board_order.slice(1)).toEqual(before.board_order.filter((x) => x !== `b-e2e-${stamp}`));
  // Usage → Antigravity → "Include in summary": the groups left out, one change at a time
  expect(before.antigravity_excluded).toEqual([]);
  const left = await (await request.post("api/settings", { data: { antigravity_inclusion: { group: "Gemini 4 Argon", included: false } } })).json();
  expect(left.antigravity_excluded).toEqual(["Gemini 4 Argon"]);
  expect((await get()).antigravity_excluded).toEqual(["Gemini 4 Argon"]);
  expect((await request.post("api/settings", { data: { antigravity_inclusion: { group: "", included: false } } })).status()).toBe(400);
  await request.post("api/settings", { data: { antigravity_inclusion: { group: "Gemini 4 Argon", included: true } } });
  expect((await get()).antigravity_excluded).toEqual([]);
});

test("the operator supervises the daemon: a restart on request, and an operator that died leaves no second daemon", async () => {
  // root's sh is not matched (-u dieter), so neither pgrep nor pkill can hit the shell that runs them
  const sh = (cmd, user = "root") => execFileSync("docker", ["exec", "-u", user, "dieter-e2e", "sh", "-c", cmd], { stdio: "pipe" }).toString().trim();
  const daemons = () => sh("pgrep -u dieter -f 'dieter --store /data/dieter daemon start' || true").split("\n").filter(Boolean);
  const healthy = () => { try { return JSON.parse(sh("HOME=/data DIETER_HOME=/data/dieter dieter status --format json", "dieter")).health?.status === "ok"; } catch { return false; } };
  const replaced = (old, ms) => until(async () => { const d = daemons(); return d.length === 1 && d[0] !== old && healthy() ? d[0] : null; }, ms);
  const first = daemons();
  expect(first.length, first.join(" ")).toBe(1);
  // what the operator does after a Claude sign-in or a gateway enrollment
  expect(JSON.parse(sh("curl -s -X POST -d '{}' http://127.0.0.1:8096/api/daemon/restart"))).toEqual({ ok: true, restarted: true });
  const second = await replaced(first[0], 60_000);
  expect(second, "one new daemon, healthy").toBeTruthy();
  sh("pkill -KILL -u dieter -f com.getdieter.operator.MainKt");
  await new Promise((f) => setTimeout(f, 1000));
  expect(daemons(), "the daemon outlives its operator").toEqual([second]);
  // run.sh starts the next operator after 5 s; it stops the leftover before it starts its own
  expect(await replaced(second, 120_000), "one new daemon after the operator's restart").toBeTruthy();
  const log = execFileSync("sh", ["-c", "docker logs dieter-e2e 2>&1 | tail -300"]).toString();
  expect(log).toContain(`operator: daemon: stopping the daemon an earlier operator left running (pid ${second})`);
});
