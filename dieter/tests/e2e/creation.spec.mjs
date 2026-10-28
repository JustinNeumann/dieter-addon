// The "New task" / "New chat" form: opening, cancelling, pickers, segments, submitting to Todo.
// Runs after dieter.spec.mjs has registered the Hello-World project (serial within this file).
import { test, expect } from "@playwright/test";

test.describe.configure({ mode: "serial" });

const stamp = Date.now().toString(36);

async function openNewTask(page) {
  await page.goto("");
  await page.locator('#rail [data-act="tab"][data-tab="projects"]').click();
  await page.locator('[data-act="new"]').first().click();
  await expect(page.locator("#modal")).not.toHaveClass(/hidden/);
  await expect(page.locator("#modal .appbar .title, #modal .appbar .ctitle").first()).toHaveText("New task");
}

async function cardCount(request) {
  const r = await request.get("api/state");
  return (await r.json()).cards.length;
}

test("the X in the app bar closes New task without creating a card", async ({ page, request }) => {
  const before = await cardCount(request);
  await openNewTask(page);
  await page.locator('#modal [data-act="cancel-modal"]').click();
  await expect(page.locator("#modal")).toHaveClass(/hidden/);
  await expect(page.locator("#modal")).toBeEmpty();
  expect(await cardCount(request)).toBe(before);
});

test("cancelling discards the draft: reopening shows an empty form", async ({ page }) => {
  await openNewTask(page);
  await page.locator("#c-prompt").fill("discard me");
  await page.locator("#c-title").fill("discard title");
  await page.locator('#modal [data-act="cancel-modal"]').click();
  await expect(page.locator("#modal")).toHaveClass(/hidden/);
  await page.locator('[data-act="new"]').first().click();
  await expect(page.locator("#c-prompt")).toHaveValue("");
  await expect(page.locator("#c-title")).toHaveValue("");
  await page.locator('#modal [data-act="cancel-modal"]').click();
});

test("Escape closes the form", async ({ page }) => {
  await openNewTask(page);
  await page.locator("#c-prompt").fill("esc");
  await page.keyboard.press("Escape");
  await expect(page.locator("#modal")).toHaveClass(/hidden/);
});

test("the submit buttons are disabled until a prompt is entered", async ({ page }) => {
  await openNewTask(page);
  const submits = page.locator('[data-act="submit-creation"]');
  await expect(submits.first()).toBeDisabled();
  await expect(submits.last()).toBeDisabled();
  await page.locator("#c-prompt").fill("x");
  await expect(submits.first()).toBeEnabled();
  await expect(submits.last()).toBeEnabled();
  await page.locator("#c-prompt").fill("");
  await expect(submits.first()).toBeDisabled();
  await page.locator('#modal [data-act="cancel-modal"]').click();
});

test("project, board, agent, model and effort pickers open and apply", async ({ page }) => {
  await openNewTask(page);
  await page.locator('[data-act="picker"][data-key="project"]').click();
  await expect(page.locator("#overlay .dialog .dt")).toHaveText("Project");
  await page.locator("#overlay .dialog .opt").filter({ hasText: "Hello-World" }).click();
  await expect(page.locator("#overlay")).toHaveClass(/hidden/);
  await expect(page.locator('[data-act="picker"][data-key="project"]')).toContainText("Hello-World");
  await page.locator('[data-act="picker"][data-key="board"]').click();
  await expect(page.locator("#overlay .dialog .dt")).toHaveText("Board");
  await page.locator("#overlay .dialog .opt").first().click();
  await expect(page.locator('[data-act="picker"][data-key="board"]')).toContainText("Main");
  await page.locator('[data-act="picker"][data-key="provider"]').click();
  await expect(page.locator("#overlay .dialog .dt")).toHaveText("Agent");
  await page.locator('#overlay .dialog .opt[data-value="mock"]').click();
  await expect(page.locator('[data-act="picker"][data-key="provider"]')).toContainText("Mock");
  await page.locator('[data-act="picker"][data-key="model"]').click();
  await expect(page.locator("#overlay .dialog .dt")).toHaveText("Model");
  await page.locator('#overlay .dialog .opt[data-value="mock"]').click();
  await page.locator('[data-act="picker"][data-key="effort"]').click();
  await expect(page.locator("#overlay .dialog .dt")).toHaveText("Effort");
  await page.locator('#overlay .dialog .opt[data-value="high"]').click();
  await expect(page.locator('[data-act="picker"][data-key="effort"]')).toContainText("High");
  // a picker dialog can be dismissed by its scrim without changing the value
  await page.locator('[data-act="picker"][data-key="effort"]').click();
  await page.locator("#overlay .scrim").click({ position: { x: 5, y: 5 } });
  await expect(page.locator("#overlay")).toHaveClass(/hidden/);
  await expect(page.locator('[data-act="picker"][data-key="effort"]')).toContainText("High");
  await page.locator('#modal [data-act="cancel-modal"]').click();
});

test("workspace segment: New worktree shows branch fields, Project directory hides them", async ({ page }) => {
  await openNewTask(page);
  await page.locator('[data-act="workspace"][data-i="0"]').click();
  await expect(page.locator('[data-field="branch"]')).toBeVisible();
  await expect(page.locator('[data-field="base"]')).toBeVisible();
  await page.locator('[data-act="workspace"][data-i="1"]').click();
  await expect(page.locator('[data-field="branch"]')).toHaveCount(0);
  await page.locator('#modal [data-act="cancel-modal"]').click();
});

test("Add to Todo creates an unstarted card in the Todo lane; Start agent runs it", async ({ page, request }) => {
  await openNewTask(page);
  await page.locator("#c-prompt").fill(`todo ${stamp}`);
  await page.locator("#c-title").fill(`Todo ${stamp}`);
  await page.locator('[data-act="picker"][data-key="provider"]').click();
  await page.locator('#overlay .dialog .opt[data-value="mock"]').click();
  await page.locator('[data-act="when"][data-i="1"]').click();
  await expect(page.locator('[data-act="submit-creation"]').last()).toContainText(/Todo|Add/);
  await page.locator('[data-act="submit-creation"]').last().click();
  await expect(page.locator("#modal")).toHaveClass(/hidden/, { timeout: 30_000 });
  let card;
  for (let i = 0; i < 20 && !card; i++) {
    const st = await (await request.get("api/state")).json();
    card = st.cards.find((c) => c.title === `Todo ${stamp}`);
    if (!card) await new Promise((f) => setTimeout(f, 1000));
  }
  expect(card).toBeTruthy();
  expect(card.lane).toBe("todo");
  expect(card.initial_prompt_sent).toBe(false);
  // start it from the conversation's card menu
  await page.goto(`?card=${card.id}`);
  await expect(page.locator("#draft")).toBeVisible({ timeout: 30_000 });
  await page.locator('[data-menu="conversation"]').first().click();
  await page.locator('[data-mi="start"]').click();
  await expect(page.locator("#panes")).toContainText(`Mock harness received: todo ${stamp}`, { timeout: 90_000 });
});

test("Edit… changes the title and task of a Todo card; once started only the title", async ({ page, request }) => {
  const st = await (await request.get("api/state")).json();
  const board = st.boards[0];
  const made = await request.post("api/card", { data: { project: board.project_id, board: board.id, title: `Edit ${stamp}`, prompt: `before ${stamp}`, provider: "mock", workspace: "project", start: false } });
  expect(made.ok()).toBeTruthy();
  const id = (await made.json()).id;
  const card = async () => (await (await request.get("api/state")).json()).cards.find((c) => c.id === id);
  await page.goto(`?card=${id}`);
  await expect(page.locator("#draft")).toBeVisible({ timeout: 30_000 });
  await page.locator('[data-menu="conversation"]').first().click();
  await page.locator('[data-mi="edit"]').click();
  await expect(page.locator("#e-title")).toHaveValue(`Edit ${stamp}`);
  await expect(page.locator("#e-prompt")).toHaveValue(`before ${stamp}`);
  await page.locator("#e-title").fill(`Edited ${stamp}`);
  await page.locator("#e-prompt").fill(`after ${stamp}`);
  await page.locator('[data-act="edit-save"]').click();
  await expect(page.locator("#overlay")).toHaveClass(/hidden/);
  await expect.poll(async () => (await card()).initial_prompt, { timeout: 20_000 }).toBe(`after ${stamp}`);
  expect((await card()).title).toBe(`Edited ${stamp}`);
  // an empty task is refused client-side
  await page.locator('[data-menu="conversation"]').first().click();
  await page.locator('[data-mi="edit"]').click();
  await page.locator("#e-prompt").fill("  ");
  await page.locator('[data-act="edit-save"]').click();
  await expect(page.locator("#e-title")).toBeVisible();
  await page.locator('#overlay [data-act="close-dialog"]').last().click();
  // start it: the task is locked, the title is not
  await page.locator('[data-menu="conversation"]').first().click();
  await page.locator('[data-mi="start"]').click();
  await expect(page.locator("#panes")).toContainText(`Mock harness received: after ${stamp}`, { timeout: 90_000 });
  await expect.poll(async () => (await card()).initial_prompt_sent, { timeout: 20_000 }).toBe(true);
  await page.locator('[data-menu="conversation"]').first().click();
  await page.locator('[data-mi="edit"]').click();
  await expect(page.locator("#e-prompt")).toHaveCount(0);
  await page.locator("#e-title").fill(`Renamed ${stamp}`);
  await page.locator('[data-act="edit-save"]').click();
  await expect.poll(async () => (await card()).title, { timeout: 20_000 }).toBe(`Renamed ${stamp}`);
});

test("New chat has no board row and no Start/Todo segment; cancel works", async ({ page }) => {
  await page.goto("");
  await page.locator('#rail [data-act="tab"][data-tab="chats"]').click();
  await page.locator('[data-act="new"]').first().click();
  await expect(page.locator("#modal .appbar .title, #modal .appbar .ctitle").first()).toHaveText("New chat");
  await expect(page.locator('[data-act="picker"][data-key="board"]')).toHaveCount(0);
  await expect(page.locator('[data-act="when"]')).toHaveCount(0);
  await page.locator('#modal [data-act="cancel-modal"]').click();
  await expect(page.locator("#modal")).toHaveClass(/hidden/);
});

test("the Attach button does not break the form", async ({ page }) => {
  await openNewTask(page);
  await page.locator('#modal [data-act="attach"]').click();
  await expect(page.locator("#modal")).not.toHaveClass(/hidden/);
  await page.locator('#modal [data-act="cancel-modal"]').click();
  await expect(page.locator("#modal")).toHaveClass(/hidden/);
});

test.describe("phone layout", () => {
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });

  test("New task opens from the bottom FAB and the X cancels it", async ({ page, request }) => {
    const before = await cardCount(request);
    await page.goto("");
    await page.locator('#bottombar [data-act="tab"][data-tab="projects"]').click();
    const fab = page.locator('[data-act="new"]:visible').first(); // the rail FAB is hidden on phones; the content FAB shows
    await expect(fab).toBeVisible();
    await fab.tap();
    await expect(page.locator("#modal")).not.toHaveClass(/hidden/);
    await page.locator("#c-prompt").fill("phone draft");
    const x = page.locator('#modal [data-act="cancel-modal"]');
    await expect(x).toBeVisible();
    await x.tap();
    await expect(page.locator("#modal")).toHaveClass(/hidden/);
    expect(await cardCount(request)).toBe(before);
  });

  test("the pickers work with touch on the phone", async ({ page }) => {
    await page.goto("");
    await page.locator('#bottombar [data-act="tab"][data-tab="projects"]').click();
    await page.locator('[data-act="new"]:visible').first().tap();
    await page.locator('[data-act="picker"][data-key="provider"]').tap();
    await page.locator('#overlay .dialog .opt[data-value="mock"]').tap();
    await expect(page.locator("#overlay")).toHaveClass(/hidden/);
    await expect(page.locator('[data-act="picker"][data-key="provider"]')).toContainText("Mock");
    await page.locator('#modal [data-act="cancel-modal"]').tap();
    await expect(page.locator("#modal")).toHaveClass(/hidden/);
  });
});

test("typing in the form survives live updates (no keyboard loss on Android)", async ({ page, request }) => {
  await openNewTask(page);
  const prompt = page.locator("#c-prompt");
  await prompt.click();
  await page.keyboard.type("keep ", { delay: 40 });
  // a state change arrives while typing: the bridge re-renders on its SSE "state" event
  const st = await (await request.get("api/state")).json();
  const card = st.cards.find((c) => c.title.startsWith("E2E ") || c.title.startsWith("Todo "));
  if (card) { await request.post(`api/card/${card.id}/move`, { data: { lane: card.lane === "done" ? "review" : "done" } }); }
  await page.waitForTimeout(1500);
  await page.keyboard.type("typing", { delay: 40 });
  await expect(prompt).toBeFocused();
  await expect(prompt).toHaveValue("keep typing");
  await page.locator("#c-title").click();
  await page.keyboard.type("Title kept", { delay: 30 });
  await page.waitForTimeout(1200);
  await expect(page.locator("#c-title")).toBeFocused();
  await expect(page.locator("#c-title")).toHaveValue("Title kept");
  await page.locator('#modal [data-act="cancel-modal"]').click();
});

test("a picker dialog closes with its Cancel button, stays open on a click in its body, closes on the scrim", async ({ page }) => {
  await openNewTask(page);
  const row = page.locator('[data-act="picker"][data-key="project"]');
  const before = await row.textContent();
  await row.click();
  await expect(page.locator("#overlay .dialog .dt")).toHaveText("Project");
  await page.locator("#overlay .dialog .dt").click();
  await expect(page.locator("#overlay")).not.toHaveClass(/hidden/);
  await page.locator('#overlay .dialog button[data-act="close-dialog"]').click();
  await expect(page.locator("#overlay")).toHaveClass(/hidden/);
  await expect(row).toHaveText(before);
  await row.click();
  await page.locator("#overlay .scrim").click({ position: { x: 5, y: 5 } });
  await expect(page.locator("#overlay")).toHaveClass(/hidden/);
  await page.locator('#modal [data-act="cancel-modal"]').click();
});
