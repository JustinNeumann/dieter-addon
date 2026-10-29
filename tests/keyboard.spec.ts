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

test("rows and cards are named by their titles", async ({ page }) => {
  await open(page, "");
  const inbox = await aria(page);
  expect(inbox).not.toMatch(/button "Chat"/);
  expect(inbox).toMatch(/button "Kotlin\/Wasm vs Node inside an add-on/);
  expect(inbox).not.toMatch(/button "D Compose web UI/);
  await open(page, "board Add-on");
  const board = await aria(page);
  expect(board).not.toMatch(/button "Last activity/);
  expect(board).toMatch(/button "[^"]*Compose web UI behind Ingress[^"]*Last activity/);
  await open(page, "card Compose web UI behind Ingress");
  expect(await aria(page)).not.toMatch(/"Done, Done, Open, Open"/);
});
