// The add-on's HTTP API as Home Assistant and the scripts use it (homeassistant/: sensor.dieter from api/ha, the
// rest_commands dieter_create_task / dieter_send_message / dieter_move_card / dieter_focus, dieter_task.py), against the
// image with Dieter's mock harness (DIETER_ENABLE_MOCK_HARNESS=1) behind the Ingress stand-in. Started by tests/run.sh.
// Whatever serves these paths (the bridge now, the web host later) must keep these answers.
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
  for (let i = 0; i < 15; i++) { // the bridge polls the daemon every 8 s
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
