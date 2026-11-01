// A conversation's header names the card's lane, so a move from its menu or composer shows at once (a move used to
// change nothing visible there, which read as "Move to did nothing").
import { test, expect } from "@playwright/test";
import { open, aria, settle, tap } from "./helpers";

test("the header names the card's lane and follows a move from the composer", async ({ page }) => {
  await open(page, "card Add structured request logging with slog");
  await expect.poll(() => aria(page)).toContain("atlas-api · Roadmap · Done");
  await tap(page, page.getByRole("button", { name: "Move to Review", exact: true }));
  await settle(page, 600);
  await expect.poll(() => aria(page)).toContain("atlas-api · Roadmap · Review");
});

test("a chat's header has no lane", async ({ page }) => {
  await open(page, "card Why is the search index rebuild slow?");
  await expect.poll(() => aria(page)).toContain("Why is the search index rebuild slow?");
  expect(await aria(page)).toMatch(/atlas-api · Chat\b(?! ·)/);
});
