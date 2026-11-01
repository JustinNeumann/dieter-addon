// Screen readers read the accessibility DOM Compose for Web keeps next to its canvas. Compose 1.12 kept a closed
// popup's entries there (and nothing else) until the next reload; from 1.13 the screen's entries come back.
import { test, expect, type Page } from "@playwright/test";
import { open, aria, settle, tap, COMPOSE_112, STALE_POPUPS } from "./helpers";

test.fail(COMPOSE_112, STALE_POPUPS); // the screen readers' gap while the page builds with 1.12

const TASK = "card Add per-key rate limiting to the public API";

/** Opens a popup with [button], closes it with Escape; the tree must hold the screen again and nothing of the popup. */
async function closes(page: Page, button: string, screen: string) {
  const before = await settle(page, 600);
  expect(before).toContain(screen);
  await tap(page, page.getByRole("button", { name: button, exact: true }).last());
  const opened = await settle(page, 600);
  const added = opened.split("\n").filter((l) => /button "/.test(l) && !before.includes(l));
  expect(added.length, `${button} opened nothing`).toBeGreaterThan(0);
  await page.keyboard.press("Escape");
  const after = await settle(page, 800);
  expect(after).toContain(screen);
  for (const line of added) expect(after).not.toContain(line);
}

test("a conversation's menu, a tool's output sheet and the image viewer leave the tree to the conversation", async ({ page }) => {
  await open(page, TASK);
  await page.waitForTimeout(800);
  const screen = "Rejected requests return 429 Too Many Requests";
  await closes(page, "Conversation actions", screen);
  await closes(page, "Screenshot of /docs/rate-limits Done", screen);
  await closes(page, "Screenshot of /docs/rate-limits", screen);
});

test("the Inbox's filter menu leaves the tree to the Inbox", async ({ page }) => {
  await open(page, "");
  await closes(page, "Filter", "Add per-key rate limiting to the public API");
});
