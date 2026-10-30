// Dreamfall boards through the add-on's API and the `dreamfall` command, as the add-on core and the stage agents use
// them: creation (three stage boards, two paused schedules), the agents' steps inside the container (with
// DIETER_CARD_ID of a run card), the person's decisions, the trail, settings and removal. The page's side is in
// next-dreamfall.spec.mjs. Started by tests/run.sh.
import { test, expect } from "@playwright/test";
import { execFileSync } from "node:child_process";

test.describe.configure({ mode: "serial" });

const stamp = Date.now().toString(36);
const PROJECT = `Dreamfall-${stamp}`;
let projectId, pid, pipe;

async function state(request) {
  const r = await request.get("api/state");
  expect(r.ok()).toBeTruthy();
  return r.json();
}
async function until(fn, ms = 30_000) {
  const end = Date.now() + ms; let v;
  while (Date.now() < end) { v = await fn(); if (v) return v; await new Promise((f) => setTimeout(f, 500)); }
  return v;
}
// a command in the add-on container, as the agents run it (user dieter, the add-on's store)
function inBox(cmd, args, card = "") {
  const env = ["-e", "HOME=/data", "-e", "DIETER_HOME=/data/dieter", ...(card ? ["-e", `DIETER_CARD_ID=${card}`] : [])];
  try { return { code: 0, out: execFileSync("docker", ["exec", "-u", "dieter", ...env, "dieter-e2e", cmd, ...args], { stdio: "pipe" }).toString() }; }
  catch (e) { return { code: e.status, out: `${e.stdout || ""}${e.stderr || ""}` }; }
}
const stageOf = (role) => pipe.stages.find((s) => s.role === role);
async function runCard(request, role, after = 0) {
  const s = stageOf(role);
  return until(async () => (await state(request)).cards.filter((c) => c.board_id === s.board_id && c.title === s.run_title && Date.parse(c.created_at) > after).sort((a, b) => Date.parse(b.created_at) - Date.parse(a.created_at))[0]);
}
const act = async (request, data) => request.post(`api/dreamfall/${pid}/act`, { data, timeout: 60_000 });
const onStage = (st, role, title) => st.cards.find((c) => c.board_id === stageOf(role).board_id && c.title.includes(title) && !c.archived);

test("a Dreamfall board is created: three stage boards, two paused schedules with the navigation logic", async ({ request }) => {
  const made = await (await request.post("api/project", { data: { folder: PROJECT } })).json();
  projectId = made.id || (await state(request)).projects.find((p) => p.name === PROJECT).id;
  const df = await (await request.post("api/dreamfall", { data: {
    project: projectId, categories: "Energy\nHeating\nSafety", per_category: 2,
    navigation_logic: "Promote only ideas that need no new hardware.\nRelease everything about lighting.\nAbout one in two may pass.",
    goal: "Improvement ideas for the test house, not bug fixes", timezone: "Europe/Berlin", agent: { provider: "mock" }, enabled: false,
  }, timeout: 120_000 })).json();
  expect(df.ok, JSON.stringify(df)).toBe(true);
  pid = df.id;
  const st = await state(request);
  pipe = st.dreamfall.pipelines.find((p) => p.id === pid);
  expect(pipe.project_id).toBe(projectId);
  expect(pipe.stages.map((s) => [s.role, s.actor])).toEqual([["enumeration", "agent"], ["navigation", "agent"], ["implementation", "human"]]);
  expect(pipe.enabled).toBe(false);
  for (const s of pipe.stages) expect(st.boards.find((b) => b.id === s.board_id)?.lanes).toEqual(["todo", "running", "review", "done"]);
  const scheds = (await (await request.get(`api/schedules?project=${projectId}`)).json()).schedules;
  expect(scheds.map((s) => s.name).sort()).toEqual(["Dreamfall · Enumeration", "Dreamfall · Navigation"]);
  expect(scheds.every((s) => !s.enabled)).toBe(true);
  const navPrompt = scheds.find((s) => s.name.endsWith("Navigation")).prompt;
  expect(navPrompt).toContain("   > Promote only ideas that need no new hardware.\n   > Release everything about lighting.");
  expect(navPrompt).not.toContain("about one idea in four");
  expect(scheds.find((s) => s.name.endsWith("Enumeration")).prompt).toContain("at least 2 new ideas");
  expect(pipe.navigation_logic).toContain("no new hardware");
});

test("the Enumeration agent adds ideas with the dreamfall command; a duplicate is refused", async ({ request }) => {
  test.skip(!pid, "no Dreamfall board");
  const t0 = Date.now();
  const r = await (await request.post(`api/dreamfall/${pid}/run`, { data: { stage: "enumeration" } })).json();
  expect(r.ok, JSON.stringify(r)).toBe(true);
  const run = await runCard(request, "enumeration", t0 - 5000);
  expect(run).toBeTruthy();
  expect(inBox("dreamfall", ["run", "begin"], run.id).code).toBe(0);
  const ideas = [["Heating", "Shutters as night insulation"], ["Energy", "Base-load creep detector"], ["Safety", "Leak watch from live water flow"], ["Energy", "Micro-inverter underperformance alert"]];
  for (const [cat, title] of ideas) {
    const a = inBox("dreamfall", ["add", "--category", cat, "--title", title, "--brief", `**Idea:** ${title}.\n\n**Why it is valuable:** test.`], run.id);
    expect(a.code, a.out).toBe(0);
  }
  const dup = inBox("dreamfall", ["add", "--category", "Heating", "--title", "shutters as NIGHT insulation", "--brief", "x"], run.id);
  expect(dup.code).toBe(3);
  expect(dup.out).toContain("exists");
  expect(inBox("dreamfall", ["run", "end", "--note", "4 ideas"], run.id).code).toBe(0);
  const st = await until(async () => { const s = await state(request); return s.cards.filter((c) => c.board_id === stageOf("enumeration").board_id && c.lane === "todo" && c.title !== stageOf("enumeration").run_title).length === 4 && s; });
  expect(st, "four ideas waiting on Enumeration").toBeTruthy();
});

test("Navigation waits for Enumeration, then judges every idea; a rerun changes nothing", async ({ request }) => {
  test.skip(!pid, "no Dreamfall board");
  const E = stageOf("enumeration");
  // an Enumeration run that has begun and not ended
  const r2 = inBox("dieter", ["card", "create", "--project", projectId, "--board", E.board_id, "--lane", "todo", "--title", E.run_title, "--workspace", "project", "--prompt", "second run", "--format", "id"]).out.trim();
  expect(inBox("dreamfall", ["run", "begin"], r2).code).toBe(0);
  const t0 = Date.now();
  const nav = await (await request.post(`api/dreamfall/${pid}/run`, { data: { stage: "navigation" } })).json();
  expect(nav.ok).toBe(true);
  const run = await runCard(request, "navigation", t0 - 5000);
  const wait = inBox("dreamfall", ["run", "begin"], run.id);
  expect(wait.code).toBe(75);
  expect(wait.out).toContain("still working");
  expect(inBox("dreamfall", ["run", "end"], r2).code).toBe(0);
  expect(inBox("dreamfall", ["run", "begin"], run.id).code).toBe(0);
  const q = JSON.parse(inBox("dreamfall", ["queue", "--json"], run.id).out);
  expect(q.length).toBe(4);
  const byTitle = (t) => q.find((c) => c.title.includes(t)).id;
  let r = inBox("dreamfall", ["promote", byTitle("Shutters"), "--score", "8", "--rationale", "Cheap and saves heat on frost nights."], run.id);
  expect(r.out).toMatch(/^promoted/);
  r = inBox("dreamfall", ["promote", byTitle("Shutters"), "--score", "8", "--rationale", "again"], run.id);
  expect(r.out).toMatch(/already promoted/);
  expect(inBox("dreamfall", ["promote", byTitle("Leak"), "--score", "9", "--rationale", "A burst pipe costs more than any kWh."], run.id).code).toBe(0);
  expect(inBox("dreamfall", ["release", byTitle("Base-load"), "--score", "3", "--rationale", "Too vague without a baseline."], run.id).code).toBe(0);
  expect(inBox("dreamfall", ["release", byTitle("Micro-inverter"), "--score", "4", "--rationale", "Monitoring for its own sake."], run.id).code).toBe(0);
  expect(inBox("dreamfall", ["release", byTitle("Shutters"), "--rationale", "flip"], run.id).code).not.toBe(0);
  expect(inBox("dreamfall", ["queue"], run.id).out).toContain("queue is empty");
  expect(inBox("dreamfall", ["run", "end", "--note", "4 judged, 2 promoted"], run.id).code).toBe(0);
  // two copies wait on Navigation, the two released ideas stay on Enumeration in review
  const st = await until(async () => { const s = await state(request); return onStage(s, "navigation", "Shutters")?.lane === "todo" && onStage(s, "navigation", "Leak")?.lane === "todo" && s; });
  expect(st, "two promoted copies waiting on Navigation").toBeTruthy();
  expect(onStage(st, "enumeration", "Base-load").lane).toBe("review");
  // the idea list for duplicate checks covers every stage and lane, without the run cards
  const rows = JSON.parse(inBox("dreamfall", ["ideas", "--json"], run.id).out);
  expect(rows.length).toBe(6);
  expect(rows.some((x) => x.title.includes("run"))).toBe(false);
});

test("agents cannot write to Implementation", async ({ request }) => {
  test.skip(!pid, "no Dreamfall board");
  const I = stageOf("implementation");
  const sneaky = inBox("dieter", ["card", "create", "--project", projectId, "--board", I.board_id, "--lane", "todo", "--title", "Sneaked in", "--workspace", "project", "--prompt", "x", "--format", "id"]).out.trim();
  const navIdea = onStage(await state(request), "navigation", "Shutters");
  for (const args of [["promote", navIdea.id, "--rationale", "x"], ["run", "begin"], ["add", "--category", "A", "--title", "B", "--brief", "c"]]) {
    const r = inBox("dreamfall", args, sneaky);
    expect(r.code, args.join(" ")).toBe(13);
    expect(r.out).toContain("person's stage");
  }
  await request.post(`api/card/${sneaky}/archive`, { data: {} });
});

test("the person picks an idea: a Todo draft on Implementation with the board's instructions, mail access and its trail", async ({ request }) => {
  test.skip(!pid, "no Dreamfall board");
  const u = await (await request.post(`api/dreamfall/${pid}/update`, { data: {
    implementation_instructions: "When done, add a chart of the result and mail me a short report with it.", implementation_mail: true } })).json();
  expect(u.ok, JSON.stringify(u)).toBe(true);
  const idea = onStage(await state(request), "navigation", "Shutters");
  const r = await act(request, { action: "pick", card: idea.id, note: "Before the first frost.", workspace: "project" });
  const out = await r.json();
  expect(r.ok(), JSON.stringify(out)).toBe(true);
  const st = await until(async () => { const s = await state(request); return s.cards.some((c) => c.id === out.id) && s; });
  const copy = st.cards.find((c) => c.id === out.id);
  expect(copy.board_id).toBe(stageOf("implementation").board_id);
  expect(copy.lane).toBe("todo");
  expect(copy.initial_prompt_sent).toBe(false);
  expect(copy.initial_prompt).toContain("## For every implementation on this Dreamfall board");
  expect(copy.initial_prompt).toContain("When done, add a chart of the result and mail me a short report with it.");
  expect(copy.initial_prompt).toContain("dieter mail send");
  expect(inBox("dieter", ["card", "show", copy.id]).out).toMatch(/"mailAccess":\s*true/);
  const waiting = onStage(st, "navigation", "Leak");
  expect(inBox("dieter", ["card", "show", waiting.id]).out).not.toMatch(/"mailAccess":\s*true/);   // only picked cards
  expect(st.dreamfall.records[copy.id].source).toBe(idea.id);
  // a second pick of the same idea is refused (it left the queue)
  expect((await act(request, { action: "pick", card: idea.id })).ok()).toBe(false);
  // the trail: Enumeration → Navigation → this card, with the verdict's rationale and the pick's note; the rerun left no trace
  const detail = await (await request.get(`api/dreamfall/card/${copy.id}`)).json();
  expect(detail.chain.map((n) => n.stage)).toEqual(["enumeration", "navigation", "implementation"]);
  expect(detail.chain[2].self).toBe(true);
  const trail = JSON.stringify(detail.events);
  for (const t of ["Cheap and saves heat on frost nights.", "Before the first frost."]) expect(trail).toContain(t);
  expect(trail).not.toContain('"again"');
});

test("park and back to the queue; the person can promote a released idea", async ({ request }) => {
  test.skip(!pid, "no Dreamfall board");
  const leak = onStage(await state(request), "navigation", "Leak");
  let r = await act(request, { action: "park", card: leak.id });
  expect(r.ok(), await r.text()).toBe(true);
  expect(await until(async () => onStage(await state(request), "navigation", "Leak")?.lane === "review")).toBe(true);
  r = await act(request, { action: "restore", card: leak.id });
  expect(r.ok(), await r.text()).toBe(true);
  expect(await until(async () => onStage(await state(request), "navigation", "Leak")?.lane === "todo")).toBe(true);
  const kinds = (await (await request.get(`api/dreamfall/card/${leak.id}`)).json()).events.map((e) => e.kind);
  expect(kinds).toEqual(expect.arrayContaining(["parked", "restored"]));
  const released = onStage(await state(request), "enumeration", "Base-load");
  const p = await (await act(request, { action: "promote", card: released.id, note: "I disagree" })).json();
  expect(p.ok, JSON.stringify(p)).toBe(true);
  expect(await until(async () => (await state(request)).cards.some((c) => c.id === p.id && c.board_id === stageOf("navigation").board_id))).toBe(true);
});

test("settings change the schedules; removing the Dreamfall board keeps its boards", async ({ request }) => {
  test.skip(!pid, "no Dreamfall board");
  const u = await (await request.post(`api/dreamfall/${pid}/update`, { data: { name: "Garden ideas", enumeration_time: "01:30", navigation_time: "04:45", navigation_logic: "Only garden ideas pass." } })).json();
  expect(u.ok).toBe(true);
  const scheds = (await (await request.get(`api/schedules?project=${projectId}`)).json()).schedules;
  expect(scheds.find((s) => s.name.endsWith("Navigation")).prompt).toContain("   > Only garden ideas pass.");
  expect(scheds.map((s) => [s.name, s.cron]).sort()).toEqual([["Garden ideas · Enumeration", "30 1 * * *"], ["Garden ideas · Navigation", "45 4 * * *"]]);
  expect((await request.post(`api/dreamfall/${pid}/update`, { data: { navigation_logic: "  " } })).ok()).toBe(false);
  expect((await state(request)).dreamfall.pipelines.find((p) => p.id === pid).navigation_logic).toBe("Only garden ideas pass.");
  const d = await (await request.post(`api/dreamfall/${pid}/delete`, { data: {} })).json();
  expect(d.ok, JSON.stringify(d)).toBe(true);
  const st = await state(request);
  expect(st.dreamfall.pipelines.some((p) => p.id === pid)).toBe(false);
  for (const s of pipe.stages) expect(st.boards.some((b) => b.id === s.board_id)).toBe(true);
  expect((await (await request.get(`api/schedules?project=${projectId}`)).json()).schedules.length).toBe(0);
});
