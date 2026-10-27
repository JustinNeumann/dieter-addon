// End-to-end tests of the Dieter page behind an Ingress-like proxy, against the add-on image with
// Dieter's mock harness (DIETER_ENABLE_MOCK_HARNESS=1). Started by tests/run.sh.
import { test, expect } from "@playwright/test";

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
