// Dreamfall boards end to end: the page creates one (three stage boards, two schedules), the stage agents' steps run
// as the `dreamfall` command inside the container (as an agent would, with DIETER_CARD_ID of a run card), and the
// person picks, parks and reads the trail on the page. Started by tests/run.sh after the other specs.
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
async function openDreamfall(page) {
  await page.goto("");
  await page.waitForTimeout(800); // the wide-screen boot opens a default board first
  await expect(async () => {
    await page.locator('#rail [data-act="tab"][data-tab="projects"]').click();
    const row = page.locator(`#list [data-act="dreamfall"][data-pid="${pid}"]`);
    if (!(await row.count())) await page.locator("#list [data-act='project']").filter({ hasText: PROJECT }).first().click({ timeout: 2000 });
    await row.click({ timeout: 2000 });
  }).toPass({ timeout: 20_000 });
  await expect(page.locator(".df-stages .df-stage, .df-tabs")).not.toHaveCount(0);
}
async function runCard(request, role, after = 0) {
  const s = stageOf(role);
  return until(async () => (await state(request)).cards.filter((c) => c.board_id === s.board_id && c.title === s.run_title && Date.parse(c.created_at) > after).sort((a, b) => Date.parse(b.created_at) - Date.parse(a.created_at))[0]);
}

test("a Dreamfall board is created from New board: three stage boards, two paused schedules", async ({ page, request }) => {
  const made = await (await request.post("api/project", { data: { folder: PROJECT } })).json();
  projectId = made.id || (await state(request)).projects.find((p) => p.name === PROJECT).id;
  await page.goto("");
  await page.waitForTimeout(800);
  await expect(async () => {
    await page.locator('#rail [data-act="tab"][data-tab="projects"]').click();
    await page.locator(`#list [data-menu="project"][data-id="${projectId}"]`).first().click({ timeout: 2000 });
    await page.locator('.menu [data-mi="new-board"]').click({ timeout: 2000 });
  }).toPass({ timeout: 20_000 });
  await page.locator('#modal [data-act="board-type"][data-i="1"]').click();
  const submit = page.locator('#modal .btn.large[data-act="submit-repo"]');
  await expect(submit).toBeDisabled();
  // the three inputs: categories, the enumeration goal per category, the navigation logic (prefilled, editable)
  await page.locator('#modal [data-field="categories"]').fill("Energy\nHeating\nSafety");
  await page.locator('#modal [data-field="per_category"]').fill("2");
  const logic = page.locator('#modal [data-field="navigation_logic"]');
  await expect(logic).toHaveValue(/Promote an idea only when/);
  await expect(submit).toBeEnabled();                       // the topic is optional
  await logic.fill("");
  await expect(submit).toBeDisabled();                      // no navigation logic, no Dreamfall board
  await logic.fill("Promote only ideas that need no new hardware.\nRelease everything about lighting.\nAbout one in two may pass.");
  await page.locator('#modal [data-field="goal"]').fill("Improvement ideas for the test house, not bug fixes");
  await page.locator('#modal [data-field="timezone"]').fill("Europe/Berlin");
  await page.locator('#modal [data-act="df-form-pick"][data-key="provider"]').click();
  await page.locator('#overlay .dialog .opt[data-value="mock"]').click();
  await page.locator('#modal [data-act="df-form-toggle"][data-key="enabled"]').click(); // paused: no night runs while testing
  await expect(submit).toBeEnabled();
  await submit.click();
  await expect(page.locator(".df-stages .df-stage")).toHaveCount(3, { timeout: 60_000 });
  await expect(page.locator(".df-funnel .df-stage-chip")).toHaveCount(3);
  const st = await state(request);
  pipe = st.dreamfall.pipelines.find((p) => p.project_id === projectId);
  expect(pipe).toBeTruthy();
  pid = pipe.id;
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
  // the project lists one Dreamfall row instead of the three stage boards
  await page.locator('[data-act="back"]').first().click();
  await expect(page.locator(`#list [data-act="dreamfall"][data-pid="${pid}"]`)).toBeVisible();
  await expect(page.locator("#list [data-act='board']").filter({ hasText: /^Enumeration|^Navigation|^Implementation/ })).toHaveCount(0);
});

test("the Enumeration agent adds ideas with the dreamfall command; a duplicate is refused", async ({ page, request }) => {
  test.skip(!pid, "no Dreamfall board");
  const t0 = Date.now();
  await openDreamfall(page);
  await page.locator(`[data-act="df-run"][data-stage="enumeration"]`).click();
  await expect(page.locator("#snackbar")).toContainText("run started", { timeout: 30_000 });
  const run = await runCard(request, "enumeration", t0 - 5000);
  expect(run).toBeTruthy();
  expect(inBox("dreamfall", ["run", "begin"], run.id).code).toBe(0);
  const ideas = [["Heating", "Shutters as night insulation"], ["Energy", "Base-load creep detector"], ["Safety", "Leak watch from live water flow"], ["Energy", "Micro-inverter underperformance alert"]];
  for (const [cat, title] of ideas) {
    const r = inBox("dreamfall", ["add", "--category", cat, "--title", title, "--brief", `**Idea:** ${title}.\n\n**Why it is valuable:** test.`], run.id);
    expect(r.code, r.out).toBe(0);
  }
  const dup = inBox("dreamfall", ["add", "--category", "Heating", "--title", "shutters as NIGHT insulation", "--brief", "x"], run.id);
  expect(dup.code).toBe(3);
  expect(dup.out).toContain("exists");
  expect(inBox("dreamfall", ["run", "end", "--note", "4 ideas"], run.id).code).toBe(0);
  const enumCol = page.locator('.df-stage[data-stage="enumeration"]');
  await expect(enumCol.locator(".lane-list > .df-card")).toHaveCount(4, { timeout: 30_000 });
  await expect(enumCol.locator(".df-run")).toContainText("4 new ideas");
  await expect(enumCol.locator(".lpill").filter({ hasText: "Energy" })).toHaveCount(2);
  await expect(page.locator('.df-stage-chip[data-stage="enumeration"] .big')).toHaveText("4");
});

test("Navigation waits for Enumeration, then judges every idea; a rerun changes nothing", async ({ page, request }) => {
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
  await openDreamfall(page);
  const navCol = page.locator('.df-stage[data-stage="navigation"]');
  await expect(navCol.locator(".lane-list > .df-card")).toHaveCount(2, { timeout: 30_000 });
  await expect(navCol.locator(".df-verdict").first()).toContainText("Promoted");
  await expect(navCol.locator(".mpill").filter({ hasText: "from Enumeration" })).toHaveCount(2);
  await expect(navCol.locator(".df-run")).toContainText("4 judged, 2 promoted");
  await expect(page.locator(".df-funnel .df-arrow").first()).toContainText("2 of 4");
  await expect(navCol.locator(".df-card .summary").first()).not.toContainText("Found by");   // the excerpt stops before the provenance footer
  const enumCol = page.locator('.df-stage[data-stage="enumeration"]');
  await expect(enumCol.locator(".lane-list > .df-card")).toHaveCount(0);
  await enumCol.locator(".df-sub").filter({ hasText: "Released" }).click();
  await expect(enumCol.locator(".df-group.review .df-card")).toHaveCount(2);
  await expect(enumCol.locator(".df-group.review")).toContainText("Too vague without a baseline.");
  // the idea list for duplicate checks covers every stage and lane, without the run cards
  const all = inBox("dreamfall", ["ideas", "--json"], run.id);
  const rows = JSON.parse(all.out);
  expect(rows.length).toBe(6);
  expect(rows.some((x) => x.title.includes("run"))).toBe(false);
});

test("agents cannot write to Implementation; a card that arrived there without a pick is flagged", async ({ page, request }) => {
  test.skip(!pid, "no Dreamfall board");
  const I = stageOf("implementation"), N = stageOf("navigation");
  const sneaky = inBox("dieter", ["card", "create", "--project", projectId, "--board", I.board_id, "--lane", "todo", "--title", "Sneaked in", "--workspace", "project", "--prompt", "x", "--format", "id"]).out.trim();
  const navIdea = (await state(request)).cards.find((c) => c.board_id === N.board_id && c.lane === "todo");
  for (const args of [["promote", navIdea.id, "--rationale", "x"], ["run", "begin"], ["add", "--category", "A", "--title", "B", "--brief", "c"]]) {
    const r = inBox("dreamfall", args, sneaky);
    expect(r.code, args.join(" ")).toBe(13);
    expect(r.out).toContain("person's stage");
  }
  await openDreamfall(page);
  await expect(page.locator('.df-stage[data-stage="implementation"] .df-warn')).toContainText("Added outside Dreamfall", { timeout: 30_000 });
  await (await request.post(`api/card/${sneaky}/archive`, { data: {} })).json();
});

test("the person picks an idea: a Todo draft on Implementation with provenance and trail", async ({ page, request }) => {
  test.skip(!pid, "no Dreamfall board");
  await openDreamfall(page);
  const navCol = page.locator('.df-stage[data-stage="navigation"]');
  const card = navCol.locator(".lane-list > .df-card").filter({ hasText: "Shutters" });
  await card.locator('[data-act="df-pick"]').click();
  await page.locator("#df-note").fill("Before the first frost.");
  await page.locator('#overlay [data-act="df-pick-ws"][data-v="project"]').click();
  await page.locator('#overlay [data-act="df-pick-ok"]').click();
  await expect(page.locator("#snackbar")).toContainText("Picked", { timeout: 30_000 });
  const impl = page.locator('.df-stage[data-stage="implementation"]');
  await expect(impl.locator(".df-card").filter({ hasText: "Shutters" })).toBeVisible({ timeout: 30_000 });
  await expect(impl.locator(".df-card").filter({ hasText: "Shutters" })).toContainText("Picked by");
  await expect(navCol.locator(".lane-list > .df-card")).toHaveCount(1);
  const st = await state(request);
  const copy = st.cards.find((c) => c.board_id === stageOf("implementation").board_id && c.title.includes("Shutters"));
  expect(copy.lane).toBe("todo");
  expect(copy.initial_prompt_sent).toBe(false);
  expect(st.dreamfall.records[copy.id].source).toBeTruthy();
  // a second pick of the same idea is refused (it left the queue)
  const again = await request.post(`api/dreamfall/${pid}/act`, { data: { action: "pick", card: st.dreamfall.records[copy.id].source } });
  expect(again.ok()).toBe(false);
  // the trail: from the card's menu
  await impl.locator(".df-card").filter({ hasText: "Shutters" }).locator('[data-menu="dfcard"]').click();
  await page.locator('.menu [data-mi="df-trail"]').click();
  await expect(page.locator(".df-row")).toHaveCount(3, { timeout: 30_000 });
  await expect(page.locator(".df-row").first()).toContainText("Enumeration");
  await expect(page.locator(".df-row").nth(2)).toContainText("this card");
  const trail = page.locator(".df-trail");
  for (const t of ["Created on Enumeration", "Judged: promote", "Cheap and saves heat on frost nights.", "Transferred to Navigation", "Picked for Implementation", "Before the first frost.", "Now: Todo on Implementation"])
    await expect(trail).toContainText(t);
  await expect(trail).not.toContainText("again");     // the rerun left no trace
});

test("park and back to the queue; the person can promote a released idea", async ({ page, request }) => {
  test.skip(!pid, "no Dreamfall board");
  await openDreamfall(page);
  const navCol = page.locator('.df-stage[data-stage="navigation"]');
  await navCol.locator(".lane-list > .df-card").filter({ hasText: "Leak" }).click();
  await page.locator('[data-act="df-do"][data-do="park"]').click();
  await page.locator('#overlay [data-act="df-do-ok"]').click();
  await expect(page.locator("#snackbar")).toContainText("Parked", { timeout: 30_000 });
  await expect(page.locator(".df-chips")).toContainText("Parked");
  await page.locator('[data-act="df-do"][data-do="restore"]').click();
  await page.locator('#overlay [data-act="df-do-ok"]').click();
  await expect(page.locator(".df-chips")).toContainText("Waiting for you", { timeout: 30_000 });
  await expect(page.locator(".df-trail")).toContainText("Parked");
  await expect(page.locator(".df-trail")).toContainText("Back in the queue");
  const st = await state(request);
  const released = st.cards.find((c) => c.board_id === stageOf("enumeration").board_id && c.title.includes("Base-load"));
  const r = await (await request.post(`api/dreamfall/${pid}/act`, { data: { action: "promote", card: released.id, note: "I disagree" } })).json();
  expect(r.ok).toBe(true);
  await until(async () => (await state(request)).cards.some((c) => c.id === r.id && c.board_id === stageOf("navigation").board_id));
});

test.describe("phone", () => {
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
  test("the stages are tabs; Navigation first", async ({ page }) => {
    test.skip(!pid, "no Dreamfall board");
    await page.goto("");
    await page.locator('#bottombar [data-act="tab"][data-tab="projects"]').click();
    const row = page.locator(`#list [data-act="dreamfall"][data-pid="${pid}"]`);
    await expect(async () => {
      if (!(await row.count())) await page.locator("#list [data-act='project']").filter({ hasText: PROJECT }).first().click({ timeout: 2000 });
      await row.click({ timeout: 2000 });
    }).toPass({ timeout: 20_000 });
    await expect(page.locator(".df-tabs .tab")).toHaveCount(3);
    await expect(page.locator(".df-tabs .tab.on")).toContainText("Navigation");
    await page.locator('.df-tabs .tab[data-stage="implementation"]').click();
    await expect(page.locator(".df-stage.single .df-gate")).toContainText("You decide what gets built");
  });
});

test("screenshots of the Dreamfall board (light and dark)", async ({ page }) => {
  test.skip(!pid, "no Dreamfall board");
  await openDreamfall(page);
  // 4 ideas were written; their copies on Navigation (3) and Implementation (1) are the same ideas
  await expect(page.locator(".appbar .subtitle").first()).toContainText("4 ideas");
  await page.locator('.df-stage[data-stage="enumeration"] .df-sub').filter({ hasText: "Released" }).click().catch(() => {});
  await page.waitForTimeout(500);
  await page.screenshot({ path: "test-results/dreamfall-board-light.png" });
  await page.evaluate(() => document.documentElement.setAttribute("data-theme", "dark"));
  await page.waitForTimeout(300);
  await page.screenshot({ path: "test-results/dreamfall-board-dark.png" });
});

test("settings change the schedules; removing the Dreamfall board keeps its boards", async ({ page, request }) => {
  test.skip(!pid, "no Dreamfall board");
  const u = await (await request.post(`api/dreamfall/${pid}/update`, { data: { name: "Garden ideas", enumeration_time: "01:30", navigation_time: "04:45", navigation_logic: "Only garden ideas pass." } })).json();
  expect(u.ok).toBe(true);
  const scheds = (await (await request.get(`api/schedules?project=${projectId}`)).json()).schedules;
  expect(scheds.find((s) => s.name.endsWith("Navigation")).prompt).toContain("   > Only garden ideas pass.");
  const empty = await request.post(`api/dreamfall/${pid}/update`, { data: { navigation_logic: "  " } });
  expect(empty.ok()).toBe(false);
  expect(scheds.map((s) => [s.name, s.cron]).sort()).toEqual([["Garden ideas · Enumeration", "30 1 * * *"], ["Garden ideas · Navigation", "45 4 * * *"]]);
  // earlier run cards keep counting as runs, not as ideas; Settings shows the stored logic
  await openDreamfall(page);
  await page.locator('[data-menu="dreamfall"]').click();
  await page.locator('.menu [data-mi="settings"]').click();
  await expect(page.locator('#modal [data-field="navigation_logic"]')).toHaveValue("Only garden ideas pass.");
  await page.locator('#modal [data-act="cancel-modal"]').click();
  await expect(page.locator(".appbar")).toContainText("Garden ideas");
  await expect(page.locator('.df-stage-chip[data-stage="enumeration"] .big')).toHaveText("0");
  page.once("dialog", (d) => d.accept());
  await page.locator('[data-menu="dreamfall"]').click();
  await page.locator('.menu [data-mi="remove"]').click();
  await expect(page.locator("#snackbar")).toContainText("its boards stay", { timeout: 30_000 });
  const st = await state(request);
  expect(st.dreamfall.pipelines.some((p) => p.id === pid)).toBe(false);
  for (const s of pipe.stages) expect(st.boards.some((b) => b.id === s.board_id)).toBe(true);
  const after = (await (await request.get(`api/schedules?project=${projectId}`)).json()).schedules;
  expect(after.length).toBe(0);
});
