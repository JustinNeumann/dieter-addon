// Phase 2 step 2/3: text can be selected and copied in replies, mail, files and subagent details.
import { test, expect } from "@playwright/test";
import { open, dragCopy, clipboard, tap } from "./helpers";

test("conversation reply: drag-select and copy", async ({ page }) => {
  await open(page, "card Add per-key rate limiting to the public API");
  const copied = await dragCopy(page, page.getByText("Rejected requests return 429 Too Many Requests").last());
  expect(copied.length).toBeGreaterThan(10);
  expect("Rejected requests return 429 Too Many Requests with Retry-After and the draft RateLimit headers, so SDKs can back off without guessing. Next I'm running the race tests across the limiter and the HTTP middleware.").toContain(copied.trim());
});

test("mail body (HTML island): native selection across blocks", async ({ page }) => {
  await open(page, "tab mail; mail Dependency audit");
  const island = page.locator(".dieter-island");
  const from = await island.locator("h1").boundingBox();
  const to = await island.locator("p").first().boundingBox();
  await page.evaluate(() => navigator.clipboard.writeText(""));
  await page.mouse.move(from!.x + 2, from!.y + from!.height / 2);
  await page.mouse.down();
  await page.mouse.move(to!.x + 200, to!.y + to!.height - 6, { steps: 10 });
  await page.mouse.up();
  await page.keyboard.press("ControlOrMeta+c");
  const copied = await clipboard(page);
  expect(copied).toContain("Dependency audit");
  expect(copied).toContain("Two dependencies have newer releases");
});

test("mail body (Compose): one selection spans heading and paragraph", async ({ page }) => {
  await open(page, "tab mail; mail Dependency audit", "islands=off");
  const heading = await page.getByText("Dependency audit", { exact: true }).last().boundingBox();
  const paragraph = await page.getByText("Two dependencies have newer releases").last().boundingBox();
  await page.evaluate(() => navigator.clipboard.writeText(""));
  await page.mouse.move(heading!.x + 2, heading!.y + heading!.height / 2);
  await page.mouse.down();
  await page.mouse.move(paragraph!.x + 200, paragraph!.y + 10, { steps: 12 });
  await page.mouse.up();
  await page.keyboard.press("ControlOrMeta+c");
  await page.waitForTimeout(300);
  const copied = await clipboard(page);
  expect(copied).toContain("Dependency audit");
  expect(copied).toContain("Two dependencies");
});

test("file viewer: Markdown preview (island) and plain text", async ({ page }) => {
  await open(page, "file README.md");
  const p = page.locator(".dieter-island p").first();
  await p.click({ clickCount: 3 });
  await page.keyboard.press("ControlOrMeta+c");
  expect(await clipboard(page)).toContain("compiled for the browser");
  await open(page, "file notes.txt");
  await tap(page, page.getByRole("textbox", { name: "File contents" }));
  await page.keyboard.press("ControlOrMeta+a");
  await page.keyboard.press("ControlOrMeta+c");
  await page.waitForTimeout(300);
  expect(await clipboard(page)).toContain("Release checklist");
});

test("subagent details are selectable", async ({ page }) => {
  await open(page, "card Add per-key rate limiting to the public API; pane subagents");
  // The card is one node for assistive tech; its last line is the subagent's recent output.
  const card = (await page.getByText("Benchmark Allow under parallel key contention").last().boundingBox())!;
  const y = card.y + card.height - 21;
  await page.evaluate(() => navigator.clipboard.writeText(""));
  await page.mouse.move(card.x + 16, y);
  await page.mouse.down();
  await page.mouse.move(card.x + 320, y, { steps: 10 });
  await page.mouse.up();
  await page.keyboard.press("ControlOrMeta+c");
  await page.waitForTimeout(300);
  expect(await clipboard(page)).toContain("BenchmarkAllow");
});
