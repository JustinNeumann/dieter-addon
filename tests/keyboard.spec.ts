// Phase 2 step 2: keyboard and screen-reader fixes found by the measurement (tests/measure.spec.ts).
import { test, expect } from "@playwright/test";
import { open, aria, settle } from "./helpers";

// Compose for Web hands Escape to the back handlers when the canvas has focus; a focused text field
// keeps it (its input element is outside the canvas), so this clicks an empty spot first.
test("Escape closes New task", async ({ page }) => {
  await open(page, "new");
  expect(await aria(page)).toContain("What should we do?");
  await page.mouse.click(900, 760);
  await page.keyboard.press("Escape");
  await expect.poll(async () => (await aria(page)).includes("What should we do?")).toBe(false);
});

// While something can go back the page keeps one history entry of its own, so the browser's Back (and
// Android's, in the Companion app) goes back inside the app first; the address never changes.
test("the browser's Back closes New task, then leaves a board", async ({ page }) => {
  await open(page, "new");
  expect(await aria(page)).toContain("What should we do?");
  const url = page.url();
  await page.goBack();
  await expect.poll(async () => (await aria(page)).includes("What should we do?")).toBe(false);
  expect(page.url()).toBe(url);
  await open(page, "board Roadmap");
  expect(await aria(page)).toContain("Search cards");
  await page.goBack();
  await expect.poll(async () => (await aria(page)).includes("Search cards")).toBe(false);
  expect(page.url()).toContain("board");
});

test("rows and cards are named by their titles", async ({ page }) => {
  await open(page, "");
  const inbox = await aria(page);
  expect(inbox).not.toMatch(/button "Chat"/);
  expect(inbox).toMatch(/button "Why is the search index rebuild slow\?/);
  expect(inbox).not.toMatch(/button "A Add per-key rate limiting/);
  await open(page, "board Roadmap");
  const board = await aria(page);
  expect(board).not.toMatch(/button "Last activity/);
  expect(board).toMatch(/button "[^"]*Add per-key rate limiting to the public API[^"]*Last activity/);
  await open(page, "card Add per-key rate limiting to the public API");
  expect(await aria(page)).not.toMatch(/"Done, Done, Open, Open"/);
});
