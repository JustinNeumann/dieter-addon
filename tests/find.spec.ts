// Phase 2 step 2: Ctrl/⌘+F opens the shared UI's find bar for the conversation, a mail message and a
// file; it counts the hits, steps with Enter / Shift+Enter / arrows, scrolls to them, closes on
// Escape. Mail and Markdown files are HTML islands by default; ?islands=off covers Compose's path.
import { test, expect } from "@playwright/test";
import { open, aria, settle } from "./helpers";

const count = async (page) => {
  const s = await aria(page);
  return (s.match(/text: (\d+\/\d+|No results)/) || [])[1] ?? null;
};

async function find(page, query: string) {
  await page.keyboard.press("ControlOrMeta+f");
  await page.waitForTimeout(400);
  await page.keyboard.press("ControlOrMeta+a");
  await page.keyboard.type(query);
  await settle(page, 500);
}

test("conversation: count, step, wrap and close", async ({ page }) => {
  await open(page, "card Add per-key rate limiting to the public API");
  await find(page, "bucket");
  const first = await count(page);
  expect(first).toMatch(/^1\/\d+$/);
  const total = Number(first!.split("/")[1]);
  expect(total).toBeGreaterThan(1);
  await page.keyboard.press("Enter");
  await expect.poll(() => count(page)).toBe(`2/${total}`);
  await page.keyboard.press("Shift+Enter");
  await expect.poll(() => count(page)).toBe(`1/${total}`);
  await page.keyboard.press("ArrowUp");
  await expect.poll(() => count(page)).toBe(`${total}/${total}`);
  await page.keyboard.press("ArrowDown");
  await expect.poll(() => count(page)).toBe(`1/${total}`);
  await find(page, "no such words anywhere");
  await expect.poll(() => count(page)).toBe("No results");
  await page.keyboard.press("Escape");
  await expect.poll(async () => (await aria(page)).includes("Find in conversation")).toBe(false);
  // Escape closed the bar, not the conversation.
  expect(await aria(page)).toContain('button "Conversation actions"');
});

test("mail message (HTML island): marks hits in the DOM and scrolls to them", async ({ page }) => {
  await open(page, "tab mail; mail Nightly build");
  const island = page.locator(".dieter-island");
  await expect(island).toBeVisible();
  await find(page, "quokka");
  await expect.poll(() => count(page)).toBe("1/1");
  const mark = island.locator("mark.dieter-find.current");
  await expect(mark).toHaveText("quokka");
  await expect(mark).toBeInViewport();
  await find(page, "step 3");
  await expect.poll(() => count(page)).toBe("1/8");
  await page.keyboard.press("Enter");
  await expect(island.locator("mark.dieter-find.current")).toHaveCount(1);
  await page.keyboard.press("Escape");
  await expect(island.locator("mark.dieter-find")).toHaveCount(0);
});

test("mail message (Compose): scrolls the hit's block into view", async ({ page }) => {
  await open(page, "tab mail; mail Nightly build", "islands=off");
  await expect(page.locator(".dieter-island")).toHaveCount(0);
  await find(page, "quokka");
  await expect.poll(() => count(page)).toBe("1/1");
  await expect(page.getByText("checksum ends in quokka").first()).toBeInViewport();
});

test("file: Markdown preview and plain text", async ({ page }) => {
  await open(page, "file docs/CHANGELOG.md");
  await find(page, "quokka");
  await expect.poll(() => count(page)).toBe("1/1");
  await expect(page.locator(".dieter-island mark.dieter-find.current")).toBeInViewport();
  await open(page, "file notes.txt");
  await find(page, "revision");
  await expect.poll(() => count(page)).toBe("1/1");
});

test("Ctrl+F is left to the browser where nothing can be found", async ({ page }) => {
  await open(page, "");
  const prevented = await page.evaluate(() => {
    let seen: boolean | null = null;
    window.addEventListener("keydown", (e) => { if (e.key === "f") seen = e.defaultPrevented; }, { once: true });
    window.dispatchEvent(new KeyboardEvent("keydown", { key: "f", ctrlKey: true, bubbles: true, cancelable: true }));
    return seen;
  });
  expect(prevented).toBe(false);
});
