// New project (Projects → Add): the sheet says why Create failed (the app's snackbar is behind the sheet), and a
// relative path on the add-on's machine is a folder in /data/projects.
import { test, expect } from "@playwright/test";
import { open, aria, settle, tap } from "./helpers";

test("New project takes a relative path from /data/projects and shows a failure in the sheet", async ({ page }) => {
  await open(page, "tab projects");
  await tap(page, page.getByRole("button", { name: "Add", exact: true }));
  await settle(page, 500);
  await tap(page, page.getByRole("button", { name: "New project", exact: true }));
  await expect.poll(() => aria(page)).toContain("New Project");
  // the add-on's machine is picked
  expect(await aria(page)).toContain("Home Assistant");
  await tap(page, page.locator("#project-name"));
  await page.keyboard.type("Talk TV Factory");
  await tap(page, page.locator("#project-path"));
  await page.keyboard.type("talk-tv-factory");
  // the field's "Opens /data/projects/talk-tv-factory" is not in the accessibility tree (AddonRulesTest covers the path)
  await settle(page, 500);
  await tap(page, page.getByRole("button", { name: "Create", exact: true }));
  await expect.poll(() => aria(page)).toContain("project path must be an existing Git working tree");
  expect(await aria(page)).toContain("New Project");
  if (process.env.SHOT) await page.screenshot({ path: process.env.SHOT });
});
