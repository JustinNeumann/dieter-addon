// A conversation's header names the card's lane, so a move from its menu or composer shows at once (a move used to
// change nothing visible there, which read as "Move to did nothing").
import { test, expect } from "@playwright/test";
import { open, aria, settle, tap } from "./helpers";

test("the header names the card's lane and follows a move from the composer", async ({ page }) => {
  await open(page, "card Pin Node 22.19 in the image");
  await expect.poll(() => aria(page)).toContain("dieter-addon · Add-on · Done");
  await tap(page, page.getByRole("button", { name: "Move to Review", exact: true }));
  await settle(page, 600);
  await expect.poll(() => aria(page)).toContain("dieter-addon · Add-on · Review");
});

test("a chat's header has no lane", async ({ page }) => {
  await open(page, "card Kotlin/Wasm vs Node inside an add-on");
  await expect.poll(() => aria(page)).toContain("Kotlin/Wasm vs Node inside an add-on");
  expect(await aria(page)).toMatch(/dieter-addon · Chat\b(?! ·)/);
});
