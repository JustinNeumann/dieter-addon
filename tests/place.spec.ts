// The page keeps its place across loads: Home Assistant loads the add-on's frame afresh whenever you come back to
// it from another panel or dashboard, which used to start over at the Inbox.
import { test, expect } from "@playwright/test";
import { open, aria } from "./helpers";

test("a plain load opens the conversation and tab open before; a deep link still wins", async ({ page }) => {
  await open(page, "card Add per-key rate limiting to the public API");
  expect(await aria(page)).toContain('textbox "Message"');
  await open(page);
  expect(await aria(page)).toContain('textbox "Message"');
  expect(await aria(page)).toContain("text: Add per-key rate limiting to the public API atlas-api");
  // the browser's Back first goes back inside Dieter, to the list the conversation was opened from
  await page.evaluate(() => history.back());
  await expect.poll(() => aria(page), { timeout: 10_000 }).not.toContain('textbox "Message"');

  await open(page, "tab projects; board Sprint 42");
  await open(page);
  expect(await aria(page)).toContain("text: Sprint 42 storefront");

  await open(page, "", "route=tab/mail");
  await open(page);
  expect(await aria(page)).toContain("Nightly build");
});

test("a conversation that is gone since leaves the place", async ({ page }) => {
  await open(page, "", "route=card/c-web");
  // what the page saved, with the card swapped for one the account no longer has
  await page.evaluate(() => localStorage.setItem("dieter.place.demo", localStorage.getItem("dieter.place.demo")!.replace("c-web", "c-gone")));
  await open(page);
  await expect.poll(() => aria(page), { timeout: 10_000 }).not.toContain('textbox "Message"');
  expect(await page.evaluate(() => localStorage.getItem("dieter.place.demo"))).not.toContain("c-gone");
});
