// End-to-end tests of the Dieter page behind an Ingress-like proxy, against the add-on image with
// Dieter's mock harness (DIETER_ENABLE_MOCK_HARNESS=1). Started by tests/run.sh.
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

test("page loads through Ingress with relative assets", async ({ page }) => {
  const failed = [];
  page.on("requestfailed", (r) => failed.push(r.url()));
  page.on("response", (r) => { if (r.status() >= 400) failed.push(`${r.status()} ${r.url()}`); });
  await page.goto("");
  await expect(page.locator("#rail")).toBeVisible();
  await expect(page.locator('#rail [data-act="tab"][data-tab="projects"]')).toBeVisible();
  await expect(page.locator("#list")).toContainText(/Projects|Hello-World/); // two-pane boot opens the first board
  expect(failed.filter((u) => !/fonts\.g/.test(u))).toEqual([]);
});

test("the bridge reports the mock harness in its catalog", async ({ request }) => {
  const st = await state(request);
  expect(st.ok).toBe(true);
  expect(st.harnesses.map((h) => h.id)).toContain("mock");
});

test("a repository can be added from the Projects page (chunked POST through Ingress)", async ({ page, request }) => {
  await page.goto("");
  await page.locator('[data-menu="projects-add"]').click();
  await page.locator('[data-mi="np"]').click();
  await page.locator("#r-url").fill(REPO);
  await page.locator('[data-act="submit-repo"]').first().click();
  await expect(page.locator("#snackbar")).toContainText(/added|already registered/, { timeout: 90_000 });
  await expect(page.locator("#list")).toContainText("Hello-World");
  const st = await state(request);
  expect(st.projects.some((p) => p.name === "Hello-World")).toBe(true);
});

test("a new folder becomes a project (empty Git repository under /data/projects)", async ({ page, request }) => {
  await page.goto("");
  await page.locator('[data-menu="projects-add"]').click();
  await page.locator('[data-mi="nf"]').click();
  await expect(page.locator('[data-act="submit-repo"]').first()).toBeDisabled();
  await page.locator("#r-name").fill(`Scratch-${stamp}`);
  await page.locator('[data-act="submit-repo"]').first().click();
  await expect(page.locator("#snackbar")).toContainText(/added/, { timeout: 60_000 });
  await page.locator('#rail [data-act="tab"][data-tab="projects"]').click();
  await expect(page.locator("#list")).toContainText(`Scratch-${stamp}`);
  const st = await state(request);
  expect(st.projects.some((p) => p.name === `Scratch-${stamp}`)).toBe(true);
  const again = await (await request.post("api/project", { data: { folder: `Scratch-${stamp}` } })).json();
  expect(again.existed).toBe(true); // adopted, not duplicated
  const bad = await request.post("api/project", { data: { folder: "../escape" } });
  expect(bad.ok()).toBeFalsy();
});

test("a task runs on the mock harness and its conversation streams", async ({ page, request }) => {
  await page.goto("");
  await page.locator('[data-act="project"]').first().click();
  await page.locator(".fab").click();
  await page.locator("#c-prompt").fill(`e2e ${stamp}`);
  await page.locator('[data-act="picker"][data-key="provider"]').click();
  await page.locator('#overlay .dialog .opt[data-value="mock"]').click();
  await expect(page.locator("#overlay")).toHaveClass(/hidden/);
  await page.locator("#c-title").fill(`E2E ${stamp}`);
  await page.locator('[data-act="submit-creation"]').first().click();
  await expect(page.locator("#draft")).toBeVisible({ timeout: 30_000 });
  await expect(page.locator("#panes")).toContainText(`Mock harness received: e2e ${stamp}`, { timeout: 90_000 });
  await expect(page.locator("#panes")).toContainText(/Reasoning/); // collapsed reasoning block
  const st = await state(request);
  cardId = st.cards.find((c) => c.title === `E2E ${stamp}`)?.id || null;
  expect(cardId).toBeTruthy();
});

test("a task for a second project is listed too (the bridge collects cards per project)", async ({ page, request }) => {
  // regression: `dieter card list` without --project answers for one project only, so cards of any further
  // project were created and run but never reached api/state or the page
  const r = await request.post("api/project", { data: { git_url: REPO, name: "Second-Repo" } });
  expect(r.ok()).toBeTruthy();
  let st = await state(request);
  const second = st.projects.find((p) => p.name === "Second-Repo");
  expect(second).toBeTruthy();
  const board = st.boards.find((b) => b.project_id === second.id);
  expect(board).toBeTruthy();
  const created = await request.post("api/card", { data: { project: second.id, board: board.id, title: `Second ${stamp}`, prompt: `second ${stamp}`, provider: "mock", model: "mock", start: false } });
  expect(created.ok()).toBeTruthy();
  let card;
  for (let i = 0; i < 20 && !card; i++) {
    st = await state(request);
    card = st.cards.find((c) => c.title === `Second ${stamp}`);
    if (!card) await new Promise((f) => setTimeout(f, 1000));
  }
  expect(card).toBeTruthy();
  expect(card.project_id).toBe(second.id);
  expect(st.cards.some((c) => c.title === `E2E ${stamp}`)).toBe(true); // the first project's card is still there
  expect(new Set(st.cards.map((c) => c.project_id)).size).toBeGreaterThanOrEqual(2);
  // the second project's board shows it
  await page.goto("");
  await page.locator('[data-act="project"]').filter({ hasText: "Second-Repo" }).click();
  await page.locator('[data-act="board"]').first().click();
  const todoTab = page.locator('[data-act="lane"][data-lane="todo"]'); // narrow board pane: lanes are tabs, Running first
  if (await todoTab.count()) await todoTab.first().click();
  await expect(page.locator("#panes")).toContainText(`Second ${stamp}`, { timeout: 30_000 });
});

test("typing in the chat field keeps the focus and sending gets a reply", async ({ page }) => {
  await page.goto(`?card=${cardId || ""}`);
  const draft = page.locator("#draft");
  await expect(draft).toBeVisible({ timeout: 30_000 });
  await draft.click();
  await page.keyboard.type("Hi da", { delay: 60 });
  await expect(draft).toBeFocused();
  await expect(draft).toHaveValue("Hi da");
  await expect(page.locator(".sendbtn")).toBeEnabled();
  await page.keyboard.press("Enter");
  await expect(page.locator("#panes")).toContainText("Mock harness received: Hi da", { timeout: 90_000 });
});

test("an image attached in the chat reaches the conversation and shows in the sent message", async ({ page, request }) => {
  await page.goto(`?card=${cardId || ""}`);
  await expect(page.locator("#draft")).toBeVisible({ timeout: 30_000 });
  // 1x1 PNG
  const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==", "base64");
  const chooser = page.waitForEvent("filechooser");
  await page.locator('.composer [data-act="attach"]').click();
  await (await chooser).setFiles({ name: "dot.png", mimeType: "image/png", buffer: png });
  await expect(page.locator(".composer .fchip")).toContainText("dot.png");
  await page.locator("#draft").fill(`look at the image ${stamp}`);
  await page.locator('[data-act="send"]').click();
  await expect(page.locator(".composer .fchip")).toHaveCount(0, { timeout: 60_000 });
  await expect(page.locator("#panes .msg-user").last()).toContainText(`look at the image ${stamp}`, { timeout: 60_000 });
  await expect(page.locator("#panes .msg-user").last().locator(".att-img, .att-pill")).toHaveCount(1);
  const conv = await (await request.get(`api/card/${cardId}`)).json();
  const files = conv.messages.flatMap((m) => m.parts || []).filter((p) => (p.filename || p.name) === "dot.png");
  expect(files.length).toBeGreaterThan(0);
  await expect(page.locator("#panes")).toContainText("Mock harness received", { timeout: 90_000 });
});

test("a question from the agent is shown on the card; the answer and a decline reach the worker's poll", async ({ page, request }) => {
  const ask = (id, extra = {}) => request.post("api/questions", { data: { id, session: cardId, input: { allowPartialAnswers: false, questions: [{ id: "question-1", question: "Which colour?", header: "Colour", options: [{ id: "red", label: "Red" }, { id: "blue", label: "Blue", description: "calm" }], allowFreeForm: true, ...extra }] } } });
  expect((await ask(`q-${stamp}-1`)).ok()).toBeTruthy();
  let st = await state(request);
  expect(st.questions.map((q) => q.id)).toContain(`q-${stamp}-1`);
  expect((await (await request.get(`api/questions/q-${stamp}-1`)).json()).done).toBe(false);
  // the Inbox puts the card under attention
  await page.goto("");
  await page.locator('#rail [data-act="tab"][data-tab="inbox"]').click();
  await expect(page.locator("#list")).toContainText("Waiting for your answer");
  // the card shows the question; pick Blue, add a free-form note, answer
  await page.goto(`?card=${cardId}`);
  const card = page.locator(`[data-question="q-${stamp}-1"]`);
  await expect(card).toBeVisible({ timeout: 30_000 });
  await expect(card).toContainText("Which colour?");
  await expect(card.locator('[data-act="q-answer"]')).toBeDisabled();
  await card.locator('[data-act="q-opt"][data-o="blue"]').click();
  await expect(card.locator('[data-act="q-answer"]')).toBeEnabled();
  await card.locator("[data-qfree]").fill("and green");
  await card.locator('[data-act="q-answer"]').click();
  await expect(page.locator("#snackbar")).toContainText(/Answer sent/, { timeout: 15_000 });
  const polled = await (await request.get(`api/questions/q-${stamp}-1`)).json();
  expect(polled).toEqual({ done: true, output: { action: "answered", answers: { "question-1": { optionIds: ["blue"], freeform: "and green" } } } });
  expect((await request.get(`api/questions/q-${stamp}-1`)).status()).toBe(404); // handed over once
  await expect(card).toHaveCount(0);
  // decline
  expect((await ask(`q-${stamp}-2`)).ok()).toBeTruthy();
  const second = page.locator(`[data-question="q-${stamp}-2"]`);
  await expect(second).toBeVisible({ timeout: 30_000 });
  await second.locator('[data-act="q-decline"]').click();
  await expect.poll(async () => (await (await request.get(`api/questions/q-${stamp}-2`)).json()).output?.action, { timeout: 15_000 }).toBe("declined");
  st = await state(request);
  expect(st.questions.filter((q) => q.session === cardId)).toEqual([]);
  // invalid registrations are rejected
  expect((await request.post("api/questions", { data: { id: "bad id", session: cardId, input: { questions: [] } } })).ok()).toBeFalsy();
});

test("model and effort pickers in the conversation work", async ({ page }) => {
  await page.goto(`?card=${cardId || ""}`);
  await page.locator('[data-act="agent-sheet"]').click();
  const opts = page.locator("#overlay .dialog .opt");
  await expect(opts.first()).toBeVisible();
  await opts.first().click();
  // the mock model has efforts: an effort dialog follows
  await expect(page.locator("#overlay .dialog .dt")).toHaveText("Effort");
  await page.locator('#overlay .dialog .opt[data-value="high"]').click();
  await expect(page.locator("#overlay")).toHaveClass(/hidden/);
  await expect(page.locator('[data-act="agent-sheet"]')).toContainText("Mock");
});

test("the Inbox lists the finished task and opens it", async ({ page }) => {
  await page.goto("");
  await page.locator('#rail [data-act="tab"][data-tab="inbox"]').click();
  const row = page.locator(".inbox-row").filter({ hasText: `E2E ${stamp}` }).first();
  await expect(row).toBeVisible();
  await row.click();
  await expect(page.locator("#panes")).toContainText(`Mock harness received`);
});

test("Tools pages: Machines, Usage and Changes render without errors", async ({ page }) => {
  await page.goto("");
  await page.locator('#rail [data-act="tab"][data-tab="tools"]').click();
  await page.locator('[data-act="tool"][data-page="machines"]').click();
  await expect(page.locator("#panes")).toContainText("Home Assistant");
  await expect(page.locator("#panes")).toContainText(/1 of 1 online/);
  await page.locator('#rail [data-act="tab"][data-tab="tools"]').click();
  await page.locator('[data-act="tool"][data-page="usage"]').click();
  await expect(page.locator("#panes")).toContainText(/Usage|quota/i);
  await page.locator('#rail [data-act="tab"][data-tab="tools"]').click();
  await page.locator('[data-act="tool"][data-page="changes"]').click();
  await expect(page.locator("#panes")).toContainText(/Changes/);
  await expect(page.locator("#panes")).not.toContainText("undefined");
});

test("Usage offers the Claude sign-in: link to claude.com, Cancel aborts, a wrong code shows an error", async ({ page, request }) => {
  await page.goto("");
  await page.locator('#rail [data-act="tab"][data-tab="tools"]').click();
  await page.locator('[data-act="tool"][data-page="usage"]').click();
  await expect(page.locator("#panes")).toContainText("Claude Code account");
  await expect(page.locator("#panes")).not.toContainText("100% left"); // no fake Claude gauge without a login
  await page.locator('[data-act="claude-login"]').click();
  const link = page.locator("#overlay [data-claude-link]");
  await expect(link).toBeVisible({ timeout: 30_000 });
  expect(await link.getAttribute("href")).toMatch(/^https:\/\/claude\.com\/.*oauth/);
  expect((await (await request.get("api/claude/login")).json()).running).toBe(true);
  await page.locator('[data-act="claude-login-cancel"]').click();
  await expect(page.locator("#overlay")).toHaveClass(/hidden/);
  await expect.poll(async () => (await (await request.get("api/claude/login")).json()).running, { timeout: 10_000 }).toBe(false);
  // a wrong code is rejected by the CLI and reported in the dialog
  await page.locator('[data-act="claude-login"]').click();
  await expect(link).toBeVisible({ timeout: 30_000 });
  await page.locator("#claude-code").fill("not-a-real-code#state");
  await page.keyboard.press("Enter");
  await expect(page.locator("[data-claude-error]")).not.toBeEmpty({ timeout: 60_000 });
  await page.keyboard.press("Escape");
  await expect(page.locator("#overlay")).toHaveClass(/hidden/);
  const st = await (await request.get("api/state")).json();
  expect(st.claude_auth.signed_in).toBe(false);
  expect(Array.isArray(st.backends)).toBe(true); // one Inbox usage bar per configured backend; none configured here
  expect(st.backends).toEqual([]);
  await page.locator('#rail [data-act="tab"][data-tab="inbox"]').click();
  await expect(page.locator(".usage-strip")).toHaveCount(0);
});

test("Machines shows the add-on's own SSH key; the connection test reports a failure for an unreachable host", async ({ page, request }) => {
  const k = await (await request.get("api/ssh")).json();
  expect(k.public_key).toMatch(/^ssh-ed25519 [A-Za-z0-9+/=]+ dieter@home-assistant$/);
  expect(k.fingerprint).toMatch(/^SHA256:/);
  await page.goto("");
  await page.locator('#rail [data-act="tab"][data-tab="tools"]').click();
  await page.locator('[data-act="tool"][data-page="machines"]').click();
  await expect(page.locator("#panes")).toContainText("SSH key of this add-on");
  await expect(page.locator("[data-ssh-key]")).toHaveText(k.public_key);
  await page.locator("#ssh-target").fill("nobody@127.0.0.1:1");
  await page.keyboard.press("Enter");
  await expect(page.locator("[data-ssh-result]")).toContainText(/Failed:/, { timeout: 40_000 });
  const bad = await (await request.post("api/ssh/test", { data: { target: "no spaces allowed" } })).json();
  expect(bad.ok).toBe(false);
});

test("a push deep link (api/focus) opens the card on the next load", async ({ page, request }) => {
  test.skip(!cardId, "no card from the earlier test");
  const r = await request.post("api/focus", { data: { id: cardId } });
  expect(r.ok()).toBeTruthy();
  await page.goto("");
  await expect(page.locator("#draft")).toBeVisible({ timeout: 30_000 });
  await expect(page.locator("#panes")).toContainText(`E2E ${stamp}`);
});

test("the HA summary endpoint stays consistent", async ({ request }) => {
  let ha;
  for (let i = 0; i < 12; i++) { // the bridge polls the daemon every 8 s
    const r = await request.get("api/ha");
    expect(r.ok()).toBeTruthy();
    ha = await r.json();
    if (ha.last_finished?.title?.includes("E2E")) break;
    await new Promise((f) => setTimeout(f, 2000));
  }
  expect(ha.ok).toBe(true);
  expect(ha.addon).toBe(true);
  expect(ha.projects).toContain("Hello-World");
  expect(ha.last_finished?.title).toContain("E2E");
});

test("hass-cli is in the image, the Supervisor CLI is not", async () => {
  // tests/run.sh starts the container as dieter-e2e; outside an add-on no token is set, so only the binary is checked
  expect(() => execFileSync("docker", ["exec", "dieter-e2e", "sh", "-c", "command -v ha"], { stdio: "pipe" })).toThrow();
  const hass = execFileSync("docker", ["exec", "dieter-e2e", "hass-cli", "--version"]).toString();
  expect(hass).toContain("1.0.0");
});

test("Files browses the project's checkout and shows a file", async ({ page, request }) => {
  const st = await state(request);
  const hello = st.projects.find((p) => p.name === "Hello-World");
  expect(hello).toBeTruthy();
  const list = await (await request.get(`api/project/${hello.id}/files?path=`)).json();
  expect(list.entries.some((e) => e.name === "README" && e.kind === "file")).toBe(true);
  const bad = await request.get(`api/project/${hello.id}/file?path=../etc/passwd`);
  expect(bad.ok()).toBe(false);
  await page.goto("");
  await page.locator('#rail [data-act="tab"][data-tab="tools"]').click();
  await page.locator('[data-act="tool"][data-page="files"]').click();
  const pick = page.locator('[data-act="tool"][data-page="files"][data-project]').filter({ hasText: "Hello-World" });
  if (await pick.count()) await pick.click(); // more than one project: the picker shows first
  const readme = page.locator('[data-act="file-entry"][data-kind="file"]').filter({ hasText: "README" });
  await expect(readme).toBeVisible({ timeout: 30_000 });
  await readme.click();
  await expect(page.locator("[data-file-content]")).toContainText("Hello World!", { timeout: 30_000 });
  // the download icon is there for a text file; back returns to the folder listing
  await expect(page.locator('[data-act="file-download"]')).toBeVisible();
  await page.locator('[data-act="back"]').first().click();
  await expect(readme).toBeVisible();
});

test("Schedules: create with the mock agent, run now, pause, history, delete", async ({ page, request }) => {
  await page.goto("");
  await page.locator('#rail [data-act="tab"][data-tab="tools"]').click();
  await page.locator('[data-act="tool"][data-page="schedules"]').click();
  await page.locator('[data-act="sched-new"]').first().click();
  await expect(page.locator("[data-sfield=name]")).toBeVisible();
  await page.locator("[data-sfield=name]").fill(`Nightly ${stamp}`);
  await page.locator("[data-sfield=prompt]").fill(`scheduled probe ${stamp}`);
  await page.locator("[data-sfield=cron]").fill("0 3 * * *");
  await expect(page.locator("[data-sched-preview]")).toContainText("Next:", { timeout: 15_000 });
  await page.locator('[data-act="sched-pick"][data-key="project"]').click();
  await page.locator("#overlay .dialog .opt").filter({ hasText: "Hello-World" }).click();
  await page.locator('[data-act="sched-pick"][data-key="provider"]').click();
  await page.locator('#overlay .dialog .opt[data-value="mock"]').click();
  await page.locator('[data-act="sched-seg"][data-field="action"][data-i="1"]').click(); // Add to Todo: deterministic, no agent run
  await page.locator('[data-act="sched-submit"]').first().click();
  // the detail page opens
  await expect(page.locator("#panes")).toContainText(`Nightly ${stamp}`, { timeout: 30_000 });
  await expect(page.locator("#panes")).toContainText("0 3 * * *");
  const list = await (await request.get("api/schedules")).json();
  const s = list.schedules.find((x) => x.name === `Nightly ${stamp}`);
  expect(s).toBeTruthy();
  expect(s.enabled).toBe(true);
  // run now → a run in the history and a Todo card
  await page.locator('[data-act="sched-run"]').click();
  await expect(page.locator('[data-act="sched-card"]')).toHaveCount(1, { timeout: 30_000 });
  await expect(page.locator("#panes")).toContainText("completed");
  let card;
  for (let i = 0; i < 20 && !card; i++) { const st = await state(request); card = st.cards.find((c) => c.title === `Nightly ${stamp}`); if (!card) await new Promise((f) => setTimeout(f, 1000)); }
  expect(card).toBeTruthy();
  expect(card.lane).toBe("todo");
  // pause
  await page.locator('[data-act="sched-toggle"]').click();
  await expect(page.locator("[data-sched-paused]")).toBeVisible({ timeout: 15_000 });
  const shown = await (await request.get(`api/schedules/${s.id}`)).json();
  expect(shown.schedule.enabled).toBe(false);
  expect(shown.runs.length).toBe(1);
  expect(shown.runs[0].card_id).toBe(card.id);
  // delete through the API; the list no longer has it
  const del = await (await request.post(`api/schedules/${s.id}/delete`, { data: {} })).json();
  expect(del.ok).toBe(true);
  const after = await (await request.get("api/schedules")).json();
  expect(after.schedules.some((x) => x.id === s.id)).toBe(false);
  const bad = await request.post("api/schedules/nope/run", { data: {} });
  expect(bad.ok()).toBe(false);
});

test("the most recently used project is listed first, preselected in New task and opened from the Projects tab", async ({ page }) => {
  await page.goto("");
  await page.locator('#rail [data-act="tab"][data-tab="projects"]').click();
  await page.locator('[data-act="project"]').filter({ hasText: "Second-Repo" }).click();
  await expect(page.locator("#list")).toContainText("Checkouts");
  await page.reload(); // c) a wide screen opens the recent project at start…
  await expect(page.locator("#list")).toContainText("Second-Repo", { timeout: 15_000 });
  await expect(page.locator("#list")).toContainText("Checkouts");
  await page.locator('#rail [data-act="tab"][data-tab="inbox"]').click();
  await page.locator('#rail [data-act="tab"][data-tab="projects"]').click(); // …and the Projects tab shows it too
  await expect(page.locator("#list")).toContainText("Checkouts");
  await expect(page.locator("#list")).toContainText("Second-Repo");
  await page.locator('#list [data-act="back"]').click(); // a) back on the list it is first
  await expect(page.locator('[data-act="project"]').first()).toContainText("Second-Repo");
  await page.locator('[data-act="new"]').first().click(); // b) preselected in New task
  await expect(page.locator('[data-act="picker"][data-key="project"]')).toContainText("Second-Repo");
  await page.locator('#modal [data-act="cancel-modal"]').click();
  // using another project moves it to the top
  await page.locator('[data-act="project"]').filter({ hasText: "Hello-World" }).click();
  await page.reload();
  await expect(page.locator("#list")).toContainText("Hello-World", { timeout: 15_000 });
  await page.locator('#list [data-act="back"]').click();
  await expect(page.locator('[data-act="project"]').first()).toContainText("Hello-World");
});
