// The project used last on this device (the core's creation memory): it leads the Projects list and opens when the
// Projects tab is chosen from another tab.
import { test, expect, type Page } from "@playwright/test";
import { open, aria, settle } from "./helpers";

/** Runs [script]; its short waits let the core's answer arrive, so the page settles only once it is done. */
async function run(page: Page, script: string) {
  await open(page, script);
  await page.waitForTimeout(800);
  return settle(page);
}

test("the project used last leads the Projects list and opens from the Projects tab", async ({ page }) => {
  expect(await run(page, "tab projects")).not.toContain("Last used");
  // a board in use makes its project the last used; choosing Projects again returns to the list ...
  let tree = await run(page, "tab projects; board Automations; wait 100; tab projects");
  const last = tree.indexOf("Last used"), all = tree.indexOf("All projects");
  expect(last).toBeGreaterThan(-1);
  expect(all).toBeGreaterThan(last);
  expect(tree.slice(last, all)).toContain("Home-Assistant-Sem2");
  expect(tree.slice(last, all)).not.toContain("dieter-addon");
  // ... and choosing it from another tab opens the project
  tree = await run(page, "tab projects; board Automations; wait 100; tab projects; tab inbox; tab projects");
  expect(tree).not.toContain("Last used");
  expect(tree).not.toContain("All projects");
  expect(tree).toContain("Home-Assistant-Sem2");
  expect(tree).toContain('button "Automations');
});
