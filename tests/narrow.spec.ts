// Phones and narrow panes: the Inbox's filters stay on one line (scrolling sideways when they don't fit), and lists
// scroll to their end by touch.
import { test, expect, type Page } from "@playwright/test";
import { open, settle } from "./helpers";

const FILTERS = ["all", "needs", "running", "review"];
const filterBoxes = async (page: Page) => {
  const boxes = [];
  for (const id of FILTERS) boxes.push((await page.locator(`[id^="inbox-filter-${id}"]`).first().boundingBox())!);
  return boxes;
};

test.describe("a 360 dp phone", () => {
  test.use({ viewport: { width: 360, height: 640 }, hasTouch: true, isMobile: true });

  test("the Inbox's filters fit on one line inside the margins", async ({ page }) => {
    await open(page, "");
    const boxes = await filterBoxes(page);
    expect(boxes[0].x).toBeGreaterThanOrEqual(15);
    expect(boxes[0].width).toBeLessThan(44);
    for (const b of boxes) {
      expect(b.y).toBe(boxes[0].y);
      expect(b.x + b.width).toBeLessThanOrEqual(344 - 15);
    }
  });

  test("More scrolls to its last row by touch, and the browser leaves the pan to Compose", async ({ page, context }) => {
    await open(page, "tab more");
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

test.describe("a pane too narrow for the Inbox's filters", () => {
  test.use({ viewport: { width: 260, height: 640 }, hasTouch: true, isMobile: true });

  test("they stay on one line and scroll sideways to the last one", async ({ page, context }) => {
    await open(page, "");
    const before = await filterBoxes(page);
    for (const b of before) expect(b.y).toBe(before[0].y);
    const cdp = await context.newCDPSession(page);
    const y = before[0].y + before[0].height / 2;
    await cdp.send("Input.synthesizeScrollGesture", { x: 200, y, xDistance: -300, gestureSourceType: "touch", speed: 1200 });
    await settle(page, 600);
    const after = await filterBoxes(page);
    // "All" may have scrolled out entirely (an empty box); the others are on the same line
    for (const b of after.filter((b) => b.width > 0)) expect(b.y).toBe(before[0].y);
    // the pane cut the last one off (its box ends at the pane's edge), the swipe shows all of it
    expect(after[3].x).toBeLessThan(before[3].x);
    expect(after[3].width).toBeGreaterThan(before[3].width);
    expect(after[3].x + after[3].width).toBeLessThanOrEqual(260);
  });
});
