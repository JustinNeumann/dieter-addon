// Phones and narrow panes: the Inbox's filters stay on one line, and lists scroll to their end by touch.
import { test, expect } from "@playwright/test";
import { open, settle } from "./helpers";

test.describe("a 360 dp phone", () => {
  test.use({ viewport: { width: 360, height: 640 }, hasTouch: true, isMobile: true });

  test("the Inbox's filters fit on one line inside the margins", async ({ page }) => {
    await open(page, "");
    const boxes = [];
    for (const id of ["all", "needs", "running", "review"])
      boxes.push((await page.locator(`[id^="inbox-filter-${id}"]`).first().boundingBox())!);
    expect(boxes[0].x).toBeGreaterThanOrEqual(15);
    for (const b of boxes) {
      expect(b.y).toBe(boxes[0].y);
      expect(b.x + b.width).toBeLessThanOrEqual(360 - 15);
    }
  });

  test("Tools scrolls to its last row by touch, and the browser leaves the pan to Compose", async ({ page, context }) => {
    await open(page, "tab tools");
    const cdp = await context.newCDPSession(page);
    for (let i = 0; i < 4; i++) {
      await cdp.send("Input.synthesizeScrollGesture", { x: 180, y: 420, yDistance: -400, gestureSourceType: "touch", speed: 1200 });
      await page.waitForTimeout(300);
    }
    await settle(page, 800);
    const settings = (await page.getByRole("button", { name: "Settings", exact: true }).boundingBox())!;
    const inbox = { name: "Inbox", exact: true }; // a tab from Compose 1.13, a button before
    const nav = (await page.getByRole("tab", inbox).or(page.getByRole("button", inbox)).first().boundingBox())!;
    expect(settings.y + settings.height).toBeLessThanOrEqual(nav.y);
    expect(await page.evaluate(() => [scrollX, scrollY, visualViewport!.scale])).toEqual([0, 0, 1]);
    expect(await page.evaluate(() => getComputedStyle(document.getElementById("dieter")!).touchAction)).toBe("pinch-zoom");
  });
});
