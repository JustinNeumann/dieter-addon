// New task offers "Allow Home Assistant access" next to vault access (MobileExtensions.homeAssistantAccess): the
// add-on's agent relay lets such conversations' hass-cli reach Home Assistant. Other hosts show no such switch.
import { test, expect } from "@playwright/test";
import { open, aria, tap } from "./helpers";

test("New task: Allow Home Assistant access sits between vault access and mail, off until switched on", async ({ page }) => {
  await open(page, "new");
  expect(await aria(page)).toContain("What should we do?");
  // the Access section is further down the form
  for (let i = 0; i < 20 && !(await aria(page)).includes("Allow mail to you"); i++) {
    await page.mouse.move(640, 500);
    await page.mouse.wheel(0, 300);
    await page.waitForTimeout(150);
  }
  const form = await aria(page);
  expect(form).toMatch(/Allow vault access[\s\S]*Allow Home Assistant access[\s\S]*The agent may read and control Home Assistant[\s\S]*Allow mail to you/);
  // the switch is the row's button (Compose gives it no checked state): its pixels change when it turns on
  const toggle = page.getByText("Allow Home Assistant access", { exact: true }).locator("xpath=../*[@role='button']");
  const before = await toggle.screenshot();
  await tap(page, toggle);
  await expect.poll(async () => Buffer.compare(await toggle.screenshot(), before) !== 0).toBe(true);
});
