// Images in a conversation: an attached image and a tool's screenshot show as pictures, a tap opens them full
// screen over the page, and the tool's output shows its images above the text. The demo's web task has both.
import { test, expect, type Page } from "@playwright/test";
import { open, aria, settle, tap } from "./helpers";

const TASK = "card Compose web UI behind Ingress";

/** The inbox list beside the conversation: dark under the viewer, unchanged once it is closed. */
const list = (page: Page) => page.screenshot({ clip: { x: 100, y: 240, width: 360, height: 240 } });

test("an attached image and a tool's screenshot show as pictures and open full screen; Escape closes", async ({ page }) => {
  await open(page, TASK);
  await page.waitForTimeout(800);
  const tree = await settle(page);
  const attachment = page.getByRole("button", { name: "ingress.png", exact: true });
  const screenshot = page.getByRole("button", { name: "Screenshot of next/ in the iframe", exact: true });
  expect(tree).toContain('button "ingress.png"');
  expect(tree).toContain('button "Screenshot of next/ in the iframe"');
  // pictures, not file tiles (a tile reads "ingress.png PNG · 1009 B")
  expect(tree).not.toContain("PNG ·");
  expect((await screenshot.boundingBox())!.height).toBeGreaterThan(200);

  // the attachment sits above the reply: bring it into view
  await page.mouse.move(840, 400);
  await page.mouse.wheel(0, -300);
  await settle(page, 600);
  const before = await list(page);
  await tap(page, attachment);
  await settle(page, 600);
  const viewer = await aria(page);
  expect(viewer).toContain('button "Open in a new tab"');
  expect(viewer).toContain('button "Close"');
  expect((await list(page)).equals(before)).toBe(false);
  await page.keyboard.press("Escape");
  await page.waitForTimeout(800);
  expect((await list(page)).equals(before)).toBe(true);
  expect(await settle(page)).not.toContain('button "Open in a new tab"');
});

test("a tool's output shows its images above the text and their base64 as a placeholder", async ({ page }) => {
  await open(page, TASK);
  await page.waitForTimeout(800);
  await settle(page);
  await tap(page, page.getByRole("button", { name: "Screenshot of next/ in the iframe Done", exact: true }));
  const sheet = await settle(page, 600);
  expect(sheet).toContain('button "image-1.png"');
  expect(sheet).toMatch(/‹image\/png · [\d.]+ (B|KB)›/);
  expect(sheet).not.toContain("iVBORw0KGgo");
});
