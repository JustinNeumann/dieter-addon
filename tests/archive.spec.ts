// Archiving a board is Dieter's retirement: a board without open cards goes to its project's "Archived boards"
// and comes back with Restore; a board with open cards stays, and the page says why.
import { test, expect } from "@playwright/test";
import { open, aria, settle, tap } from "./helpers";

test("an empty board is archived from its menu, listed on its project's page and restored", async ({ page }) => {
  await open(page, "board Releases");
  await tap(page, page.getByRole("button", { name: "Board options", exact: true }));
  await settle(page, 500);
  await tap(page, page.getByRole("button", { name: "Archive board…", exact: true }));
  await settle(page, 500);
  expect(await aria(page)).toContain("Its archived cards stay with it");
  await tap(page, page.getByRole("button", { name: "Archive", exact: true }));
  await expect.poll(() => aria(page)).toContain("Board archived");
  // the project (Projects opens the one used last) lists it under Archived boards, not with its boards
  await tap(page, page.getByRole("tab", { name: "Projects", exact: true }));
  await expect.poll(() => aria(page)).toContain("Archived boards");
  await expect(page.locator("#board-row-b-releases")).toHaveCount(0);
  await tap(page, page.locator("#archived-board-b-releases"));
  await tap(page, page.getByRole("button", { name: "Restore board", exact: true }));
  await expect.poll(() => aria(page)).not.toContain("Board archived");
  expect(await settle(page)).toContain("Releases");
});

test("a board with open cards is not archived, and the page says why", async ({ page }) => {
  await open(page, "board Upstream");
  await tap(page, page.getByRole("button", { name: "Board options", exact: true }));
  await settle(page, 500);
  await tap(page, page.getByRole("button", { name: "Archive board…", exact: true }));
  await settle(page, 500);
  await tap(page, page.getByRole("button", { name: "Archive", exact: true }));
  await expect.poll(() => aria(page)).toContain("archive this one's cards first");
  expect(await aria(page)).not.toContain("Board archived");
});
